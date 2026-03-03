import groovy.json.JsonOutput
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Encoders
import org.apache.spark.sql.Row
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions
import org.slf4j.LoggerFactory

import java.io.File
import java.time.LocalDate
import java.sql.Timestamp

def logger = LoggerFactory.getLogger("darpan.reconciliation.inventory.retrieveInventoryAdjustmentsByReference")

def normalize = { Object value -> value?.toString()?.trim() }
def extractErrorText = {
    try {
        List errs = (ec.message?.errors ?: []) as List
        String joined = errs.findAll { it != null && it.toString().trim() }.collect { it.toString().trim() }.join(" | ")
        return joined ?: null
    } catch (Exception ignored) {
        return null
    }
}
def clearErrors = {
    try {
        if (ec.message?.hasError()) ec.message.clearErrors()
    } catch (Exception ignored) {}
}
def callService = { String serviceName, Map params ->
    clearErrors()
    Map out = ec.service.sync()
            .name(serviceName)
            .parameters(params ?: [:])
            .ignorePreviousError(true)
            .ignoreTransaction(true)
            .call()
    if (ec.message?.hasError()) {
        String errText = extractErrorText() ?: normalize(ec.message?.errorsString) ?: "Unknown service error"
        clearErrors()
        throw new IllegalArgumentException("${serviceName} failed: ${errText}")
    }
    if (out == null) {
        String errText = extractErrorText()
        clearErrors()
        throw new IllegalArgumentException("${serviceName} returned no result${errText ? ': ' + errText : ''}")
    }
    return out
}
def toInt = { Object value ->
    if (value == null) return 0
    if (value instanceof Number) return ((Number) value).intValue()
    String raw = normalize(value)
    if (!raw) return 0
    try {
        return raw.toBigDecimal().intValue()
    } catch (Exception ignored) {
        return 0
    }
}
def warnings = []

String refLocation = normalize(referenceFileLocation)
String refTypeRaw = normalize(referenceFileType)
boolean refHasHeader = (referenceHasHeader != null) ? (referenceHasHeader as Boolean) : true
String defaultItemIdFieldExpr = normalize(itemIdField) ?: "itemId"
String defaultLocationIdFieldExpr = normalize(locationIdField) ?: "locationId"
String nsItemIdFieldExpr = normalize(nsItemIdField) ?: defaultItemIdFieldExpr
String nsLocationIdFieldExpr = normalize(nsLocationIdField) ?: defaultLocationIdFieldExpr
String readDbItemIdFieldExpr = normalize(readDbItemIdField ?: hcItemIdField) ?: defaultItemIdFieldExpr
String readDbLocationIdFieldExpr = normalize(readDbLocationIdField ?: hcLocationIdField) ?: defaultLocationIdFieldExpr
String fromDateStr = normalize(from)
String toDateStr = normalize(to)
String nsConfigId = normalize(nsRestletConfigId)
String hcConfigId = normalize(readDbConfigId ?: hcReadDbConfigId)
String comparisonRuleSetIdToUse = normalize(comparisonRuleSetId)
String compareStatusFieldName = normalize(compareStatusField) ?: "compareStatus"
String missingInNsStatusValue = normalize(missingInNsStatus) ?: "MISSING_IN_NS"
String missingInReadDbStatusValue = normalize(missingInReadDbStatus) ?: "MISSING_IN_READ_DB"
String countMismatchStatusValue = normalize(countMismatchStatus) ?: "COUNT_MISMATCH"
String matchedStatusValue = normalize(matchedStatus) ?: "MATCHED_COUNT"
String noRecordStatusValue = normalize(noRecordStatus) ?: "NO_RECORDS"
String errorStatusValue = normalize(errorStatus) ?: "ERROR"
int maxItemsToProcess = maxItems ? (maxItems as int) : 0
int nsDelayMsToUse = nsDelayMs ? (nsDelayMs as int) : 0

if (!refLocation) throw new IllegalArgumentException("referenceFileLocation is required")
if (!fromDateStr) throw new IllegalArgumentException("from is required in yyyy-MM-dd format")
if (!toDateStr) throw new IllegalArgumentException("to is required in yyyy-MM-dd format")
if (!nsConfigId) throw new IllegalArgumentException("nsRestletConfigId is required")
if (!hcConfigId) throw new IllegalArgumentException("readDbConfigId (or hcReadDbConfigId) is required")
if (!comparisonRuleSetIdToUse) throw new IllegalArgumentException("comparisonRuleSetId is required")
if (!(fromDateStr ==~ /\d{4}-\d{2}-\d{2}/)) throw new IllegalArgumentException("from must be yyyy-MM-dd")
if (!(toDateStr ==~ /\d{4}-\d{2}-\d{2}/)) throw new IllegalArgumentException("to must be yyyy-MM-dd")

def ensureDefaultInventoryComparisonRuleSet = { String ruleSetId ->
    if (!"INV_ADJ_DEFAULT_RS".equals(ruleSetId)) return

    def existingRuleSet = ec.entity.find("darpan.rule.RuleSet")
            .condition("ruleSetId", ruleSetId)
            .useCache(false)
            .one()
    if (!existingRuleSet) {
        ec.entity.makeValue("darpan.rule.RuleSet")
                .set("ruleSetId", ruleSetId)
                .set("ruleSetName", "Inventory Adjustment Default Comparison")
                .set("description", "Default configurable rules for NS vs Read DB inventory adjustment comparison")
                .set("version", "1.0")
                .set("createdDate", new Timestamp(System.currentTimeMillis()))
                .create()
    }

    List<Map> defaultRules = [
            [
                    ruleId     : "INV_ADJ_DEF_ERROR",
                    sequenceNum: 10L,
                    ruleText   : "Mark ERROR",
                    ruleLogic  : """rule "INV_ADJ_DEF_ERROR"
salience 100
when
    \$m : Map(this["compareStatus"] == null)
    eval("ERROR".equals(String.valueOf(\$m.get("nsStatus"))) || "ERROR".equals(String.valueOf(\$m.get("hcStatus"))))
then
    int _ns = (\$m.get("nsRecordCount") instanceof Number) ? ((Number) \$m.get("nsRecordCount")).intValue() : 0;
    int _hc = (\$m.get("hcRecordCount") instanceof Number) ? ((Number) \$m.get("hcRecordCount")).intValue() : 0;
    \$m.put("compareStatus", "ERROR");
    \$m.put("missingInNs", false);
    \$m.put("missingInReadDb", false);
    \$m.put("recordCountDelta", _ns - _hc);
    if (!results.contains(\$m)) results.add(\$m);
end"""
            ],
            [
                    ruleId     : "INV_ADJ_DEF_NOREC",
                    sequenceNum: 20L,
                    ruleText   : "Mark NO_RECORDS",
                    ruleLogic  : """rule "INV_ADJ_DEF_NOREC"
salience 90
when
    \$m : Map(this["compareStatus"] == null)
    eval((\$m.get("nsRecordCount") instanceof Number) && ((Number) \$m.get("nsRecordCount")).intValue() == 0 &&
         (\$m.get("hcRecordCount") instanceof Number) && ((Number) \$m.get("hcRecordCount")).intValue() == 0)
then
    int _ns = ((Number) \$m.get("nsRecordCount")).intValue();
    int _hc = ((Number) \$m.get("hcRecordCount")).intValue();
    \$m.put("compareStatus", "NO_RECORDS");
    \$m.put("missingInNs", false);
    \$m.put("missingInReadDb", false);
    \$m.put("recordCountDelta", _ns - _hc);
    if (!results.contains(\$m)) results.add(\$m);
end"""
            ],
            [
                    ruleId     : "INV_ADJ_DEF_MISS_NS",
                    sequenceNum: 30L,
                    ruleText   : "Mark MISSING_IN_NS",
                    ruleLogic  : """rule "INV_ADJ_DEF_MISS_NS"
salience 80
when
    \$m : Map(this["compareStatus"] == null)
    eval((\$m.get("nsRecordCount") instanceof Number) && ((Number) \$m.get("nsRecordCount")).intValue() == 0 &&
         (\$m.get("hcRecordCount") instanceof Number) && ((Number) \$m.get("hcRecordCount")).intValue() > 0)
then
    int _ns = ((Number) \$m.get("nsRecordCount")).intValue();
    int _hc = ((Number) \$m.get("hcRecordCount")).intValue();
    \$m.put("compareStatus", "MISSING_IN_NS");
    \$m.put("missingInNs", true);
    \$m.put("missingInReadDb", false);
    \$m.put("recordCountDelta", _ns - _hc);
    if (!results.contains(\$m)) results.add(\$m);
end"""
            ],
            [
                    ruleId     : "INV_ADJ_DEF_MISS_DB",
                    sequenceNum: 40L,
                    ruleText   : "Mark MISSING_IN_READ_DB",
                    ruleLogic  : """rule "INV_ADJ_DEF_MISS_DB"
salience 70
when
    \$m : Map(this["compareStatus"] == null)
    eval((\$m.get("hcRecordCount") instanceof Number) && ((Number) \$m.get("hcRecordCount")).intValue() == 0 &&
         (\$m.get("nsRecordCount") instanceof Number) && ((Number) \$m.get("nsRecordCount")).intValue() > 0)
then
    int _ns = ((Number) \$m.get("nsRecordCount")).intValue();
    int _hc = ((Number) \$m.get("hcRecordCount")).intValue();
    \$m.put("compareStatus", "MISSING_IN_READ_DB");
    \$m.put("missingInNs", false);
    \$m.put("missingInReadDb", true);
    \$m.put("recordCountDelta", _ns - _hc);
    if (!results.contains(\$m)) results.add(\$m);
end"""
            ],
            [
                    ruleId     : "INV_ADJ_DEF_MATCH",
                    sequenceNum: 50L,
                    ruleText   : "Mark MATCHED_COUNT",
                    ruleLogic  : """rule "INV_ADJ_DEF_MATCH"
salience 60
when
    \$m : Map(this["compareStatus"] == null)
    eval((\$m.get("nsRecordCount") instanceof Number) && (\$m.get("hcRecordCount") instanceof Number) &&
         ((Number) \$m.get("nsRecordCount")).intValue() > 0 &&
         ((Number) \$m.get("nsRecordCount")).intValue() == ((Number) \$m.get("hcRecordCount")).intValue())
then
    int _ns = ((Number) \$m.get("nsRecordCount")).intValue();
    int _hc = ((Number) \$m.get("hcRecordCount")).intValue();
    \$m.put("compareStatus", "MATCHED_COUNT");
    \$m.put("missingInNs", false);
    \$m.put("missingInReadDb", false);
    \$m.put("recordCountDelta", _ns - _hc);
    if (!results.contains(\$m)) results.add(\$m);
end"""
            ],
            [
                    ruleId     : "INV_ADJ_DEF_MISMATCH",
                    sequenceNum: 60L,
                    ruleText   : "Mark COUNT_MISMATCH",
                    ruleLogic  : """rule "INV_ADJ_DEF_MISMATCH"
salience 10
when
    \$m : Map(this["compareStatus"] == null)
then
    int _ns = (\$m.get("nsRecordCount") instanceof Number) ? ((Number) \$m.get("nsRecordCount")).intValue() : 0;
    int _hc = (\$m.get("hcRecordCount") instanceof Number) ? ((Number) \$m.get("hcRecordCount")).intValue() : 0;
    \$m.put("compareStatus", "COUNT_MISMATCH");
    \$m.put("missingInNs", false);
    \$m.put("missingInReadDb", false);
    \$m.put("recordCountDelta", _ns - _hc);
    if (!results.contains(\$m)) results.add(\$m);
end"""
            ]
    ]

    defaultRules.each { Map defRule ->
        def existingRule = ec.entity.find("darpan.rule.Rule")
                .condition("ruleId", defRule.ruleId)
                .useCache(false)
                .one()
        if (!existingRule) {
            ec.entity.makeValue("darpan.rule.Rule")
                    .set("ruleId", defRule.ruleId)
                    .set("ruleSetId", ruleSetId)
                    .set("sequenceNum", defRule.sequenceNum)
                    .set("ruleText", defRule.ruleText)
                    .set("ruleLogic", defRule.ruleLogic)
                    .set("enabled", "Y")
                    .set("createdDate", new Timestamp(System.currentTimeMillis()))
                    .create()
        }
    }
}

ensureDefaultInventoryComparisonRuleSet(comparisonRuleSetIdToUse)

LocalDate fromDate = LocalDate.parse(fromDateStr)
LocalDate toDate = LocalDate.parse(toDateStr)
if (toDate.isBefore(fromDate)) throw new IllegalArgumentException("to date must be greater than or equal to from date")

def assertSparkFieldExpr = { String expression, String label ->
    if (!(expression ==~ /[A-Za-z0-9_$.]+/)) {
        throw new IllegalArgumentException("${label} has unsupported characters: ${expression}")
    }
}
assertSparkFieldExpr(defaultItemIdFieldExpr, "itemIdField")
assertSparkFieldExpr(defaultLocationIdFieldExpr, "locationIdField")
assertSparkFieldExpr(nsItemIdFieldExpr, "nsItemIdField")
assertSparkFieldExpr(nsLocationIdFieldExpr, "nsLocationIdField")
assertSparkFieldExpr(readDbItemIdFieldExpr, "readDbItemIdField")
assertSparkFieldExpr(readDbLocationIdFieldExpr, "readDbLocationIdField")
assertSparkFieldExpr(compareStatusFieldName, "compareStatusField")

def detectFileTypeFromLocation = { String location ->
    String lower = location?.toLowerCase()
    if (lower?.endsWith(".csv")) return "CSV"
    if (lower?.endsWith(".json")) return "JSON"
    return null
}

String refType = refTypeRaw?.toUpperCase()
if (!refType) refType = detectFileTypeFromLocation(refLocation)
if (!refType) throw new IllegalArgumentException("referenceFileType is required when file extension is not .csv or .json")
if (!["CSV", "JSON"].contains(refType)) throw new IllegalArgumentException("referenceFileType must be CSV or JSON")

def resolvePath = { String location ->
    def rr = ec.resource.getLocationReference(location)
    if (rr != null && rr.supportsUrl()) {
        def url = rr.getUrl()
        if ("file".equalsIgnoreCase(url.protocol)) {
            try {
                return new File(url.toURI()).getAbsolutePath()
            } catch (Exception ignored) {
                return url.getPath()
            }
        }
        return url.toString()
    }
    return location
}

String refPath = resolvePath(refLocation)

SparkSession spark = null
Dataset referenceDf
Dataset pairDf
try {
    clearErrors()
    spark = SparkSession.builder()
            .appName(normalize(sparkAppName) ?: "InventoryReferenceRetrieval")
            .master(normalize(sparkMaster) ?: "local[*]")
            .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
            .config("spark.sql.adaptive.enabled", "true")
            .getOrCreate()

    referenceDf = (refType == "JSON")
            ? spark.read().option("multiLine", "true").json(refPath)
            : spark.read().option("header", refHasHeader.toString()).option("multiLine", "true").csv(refPath)

    Dataset mappedReferenceDf = referenceDf
            .selectExpr(
                    "cast(${nsItemIdFieldExpr} as string) as ns_item_id",
                    "cast(${nsLocationIdFieldExpr} as string) as ns_location_id",
                    "cast(${readDbItemIdFieldExpr} as string) as read_db_item_id",
                    "cast(${readDbLocationIdFieldExpr} as string) as read_db_location_id"
            )

    String nonEmptyPairExpr = "ns_item_id IS NOT NULL AND length(trim(ns_item_id)) > 0 " +
            "AND ns_location_id IS NOT NULL AND length(trim(ns_location_id)) > 0 " +
            "AND read_db_item_id IS NOT NULL AND length(trim(read_db_item_id)) > 0 " +
            "AND read_db_location_id IS NOT NULL AND length(trim(read_db_location_id)) > 0"

    pairDf = mappedReferenceDf
            .filter(nonEmptyPairExpr)
            .distinct()
            .orderBy("ns_item_id", "ns_location_id", "read_db_item_id", "read_db_location_id")

    int invalidReferenceRowCount = mappedReferenceDf.filter("NOT (${nonEmptyPairExpr})").count() as int
    if (invalidReferenceRowCount > 0) {
        warnings.add("Skipped ${invalidReferenceRowCount} reference rows with missing NS/Read DB item or location values.")
    }

    int totalReferenceItems = pairDf.count() as int
    referenceItemCount = totalReferenceItems
    if (totalReferenceItems == 0) {
        throw new IllegalArgumentException("No usable item/location rows found in reference file ${refLocation}")
    }

    Dataset finalPairDf = pairDf
    if (maxItemsToProcess > 0 && maxItemsToProcess < totalReferenceItems) {
        finalPairDf = pairDf.limit(maxItemsToProcess)
        warnings.add("Processing limited to ${maxItemsToProcess} rows out of ${totalReferenceItems} reference rows.")
    }

    List<Row> pairRows = finalPairDf.collectAsList()
    List<Map> itemPairs = pairRows.collect { Row row ->
        [
                itemId          : normalize(row.getAs("ns_item_id")),
                locationId      : normalize(row.getAs("ns_location_id")),
                nsItemId        : normalize(row.getAs("ns_item_id")),
                nsLocationId    : normalize(row.getAs("ns_location_id")),
                readDbItemId    : normalize(row.getAs("read_db_item_id")),
                readDbLocationId: normalize(row.getAs("read_db_location_id"))
        ]
    }

    def itemResultRows = []
    def nsPayloadByItem = []
    def hcPayloadByItem = []
    int nsTotal = 0
    int hcTotal = 0
    int missingInNsTotal = 0
    int missingInReadDbTotal = 0
    int countMismatchTotal = 0
    int matchedTotal = 0
    int noRecordTotal = 0
    int errorItemTotal = 0

    itemPairs.eachWithIndex { Map pair, int index ->
        String pairItemId = pair.itemId
        String pairLocationId = pair.locationId
        String pairNsItemId = pair.nsItemId
        String pairNsLocationId = pair.nsLocationId
        String pairReadDbItemId = pair.readDbItemId
        String pairReadDbLocationId = pair.readDbLocationId
        String nsStatus = "OK"
        String nsError = null
        String hcStatus = "OK"
        String hcError = null
        List nsRecordsForItem = []
        List hcRecordsForItem = []

        try {
            def nsResult = callService("reconciliation.ReconciliationInventoryServices.fetch#NsInventoryAdjustments", [
                    nsRestletConfigId: nsConfigId,
                    itemId           : pairNsItemId,
                    locationId       : pairNsLocationId,
                    from             : fromDateStr,
                    to               : toDateStr
            ])
            List nsRows = (nsResult.records ?: []) as List
            nsRecordsForItem = nsRows.collect { it?.record }
            ((nsResult.processingWarnings ?: []) as List).each { warnRow ->
                String warnText = normalize(warnRow?.warningMessage)
                if (warnText) warnings.add(warnText)
            }
        } catch (Exception nsEx) {
            nsStatus = "ERROR"
            nsError = normalize(nsEx.message) ?: "NS fetch failed"
            warnings.add("NS fetch failed for itemId=${pairNsItemId}, locationId=${pairNsLocationId}: ${nsError}")
            clearErrors()
        }

        try {
            def hcResult = callService("reconciliation.ReconciliationInventoryServices.fetch#HcInventoryAdjustments", [
                    readDbConfigId       : hcConfigId,
                    hcReadDbConfigId     : hcConfigId,
                    itemId               : pairReadDbItemId,
                    locationId           : pairReadDbLocationId,
                    from                 : fromDateStr,
                    to                   : toDateStr,
                    tableName            : tableName,
                    itemIdColumn         : itemIdColumn,
                    locationIdColumn     : locationIdColumn,
                    transactionDateColumn: transactionDateColumn,
                    inventoryItemTable   : inventoryItemTable,
                    sqlRuleSetId         : sqlRuleSetId
            ])
            List hcRows = (hcResult.records ?: []) as List
            hcRecordsForItem = hcRows.collect { it?.record }
            ((hcResult.processingWarnings ?: []) as List).each { warnRow ->
                String warnText = normalize(warnRow?.warningMessage)
                if (warnText) warnings.add(warnText)
            }
        } catch (Exception hcEx) {
            hcStatus = "ERROR"
            hcError = normalize(hcEx.message) ?: "Read DB fetch failed"
            warnings.add("Read DB fetch failed for itemId=${pairReadDbItemId}, locationId=${pairReadDbLocationId}: ${hcError}")
            clearErrors()
        }

        int nsRecordCountForItem = nsRecordsForItem.size()
        int hcRecordCountForItem = hcRecordsForItem.size()

        itemResultRows.add([
                itemId          : pairItemId,
                locationId      : pairLocationId,
                nsItemId        : pairNsItemId,
                nsLocationId    : pairNsLocationId,
                readDbItemId    : pairReadDbItemId,
                readDbLocationId: pairReadDbLocationId,
                nsStatus        : nsStatus,
                nsRecordCount   : nsRecordCountForItem,
                nsError         : nsError,
                nsRecords       : nsRecordsForItem,
                hcStatus        : hcStatus,
                hcRecordCount   : hcRecordCountForItem,
                hcError         : hcError,
                hcRecords       : hcRecordsForItem
        ])

        nsPayloadByItem.add([
                itemId          : pairNsItemId,
                locationId      : pairNsLocationId,
                readDbItemId    : pairReadDbItemId,
                readDbLocationId: pairReadDbLocationId,
                status          : nsStatus,
                error           : nsError,
                recordCount     : nsRecordCountForItem,
                records         : nsRecordsForItem
        ])
        hcPayloadByItem.add([
                itemId          : pairReadDbItemId,
                locationId      : pairReadDbLocationId,
                nsItemId        : pairNsItemId,
                nsLocationId    : pairNsLocationId,
                status          : hcStatus,
                error           : hcError,
                recordCount     : hcRecordCountForItem,
                records         : hcRecordsForItem
        ])

        if (nsDelayMsToUse > 0 && index < itemPairs.size() - 1) {
            try {
                Thread.sleep(nsDelayMsToUse as long)
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt()
                warnings.add("NS delay sleep interrupted; continuing processing.")
            }
        }
    }

    def droolsOut = callService("reconciliation.ReconciliationInventoryServices.evaluate#InventoryAdjustmentComparisonRules", [
            ruleSetId     : comparisonRuleSetIdToUse,
            dataList      : itemResultRows,
            returnAllFacts: true
    ])
    if (droolsOut.error) {
        throw new IllegalArgumentException("Comparison rules failed for ruleSet ${comparisonRuleSetIdToUse}: ${normalize(droolsOut.error)}")
    }
    List droolsRows = (droolsOut.results ?: []) as List
    if (!droolsRows || droolsRows.size() != itemResultRows.size()) {
        throw new IllegalArgumentException("Comparison rules returned ${droolsRows?.size() ?: 0} rows; expected ${itemResultRows.size()} for ruleSet ${comparisonRuleSetIdToUse}")
    }
    itemResultRows = droolsRows.collect { Object rowObj ->
        (rowObj instanceof Map) ? new LinkedHashMap((Map) rowObj) : [:]
    }
    ((droolsOut.warnings ?: []) as List).each { Object warnObj ->
        String warnText = normalize(warnObj)
        if (warnText) warnings.add(warnText)
    }

    try {
        List<String> itemResultJsonRows = itemResultRows.collect { Map row -> JsonOutput.toJson(row) }
        def itemResultDf = spark.read().json(spark.createDataset(itemResultJsonRows, Encoders.STRING()))
        def statusCounts = [:]
        itemResultDf.groupBy(compareStatusFieldName).count().collectAsList().each { Row row ->
            String status = normalize(row.getAs(compareStatusFieldName))
            int count = toInt(row.getAs("count"))
            if (status) statusCounts[status] = count
        }

        Row totalsRow = itemResultDf.agg(
                functions.sum("nsRecordCount").alias("ns_total"),
                functions.sum("hcRecordCount").alias("hc_total")
        ).first()

        nsTotal = totalsRow ? toInt(totalsRow.getAs("ns_total")) : 0
        hcTotal = totalsRow ? toInt(totalsRow.getAs("hc_total")) : 0
        missingInNsTotal = toInt(statusCounts[missingInNsStatusValue])
        missingInReadDbTotal = toInt(statusCounts[missingInReadDbStatusValue])
        countMismatchTotal = toInt(statusCounts[countMismatchStatusValue])
        matchedTotal = toInt(statusCounts[matchedStatusValue])
        noRecordTotal = toInt(statusCounts[noRecordStatusValue])
        errorItemTotal = toInt(statusCounts[errorStatusValue])
    } catch (Exception sparkAggEx) {
        warnings.add("Spark summary aggregation failed, using fallback aggregation: ${normalize(sparkAggEx.message)}")
        nsTotal = itemResultRows.collect { Map row -> toInt(row.nsRecordCount) }.sum() as int
        hcTotal = itemResultRows.collect { Map row -> toInt(row.hcRecordCount) }.sum() as int
        missingInNsTotal = itemResultRows.count { Map row -> missingInNsStatusValue == normalize(row[compareStatusFieldName]) } as int
        missingInReadDbTotal = itemResultRows.count { Map row -> missingInReadDbStatusValue == normalize(row[compareStatusFieldName]) } as int
        countMismatchTotal = itemResultRows.count { Map row -> countMismatchStatusValue == normalize(row[compareStatusFieldName]) } as int
        matchedTotal = itemResultRows.count { Map row -> matchedStatusValue == normalize(row[compareStatusFieldName]) } as int
        noRecordTotal = itemResultRows.count { Map row -> noRecordStatusValue == normalize(row[compareStatusFieldName]) } as int
        errorItemTotal = itemResultRows.count { Map row -> errorStatusValue == normalize(row[compareStatusFieldName]) } as int
    }

    String outputBaseLocation = normalize(outputLocation) ?: "runtime://tmp/reconciliation/inventory/retrieval"
    def outputRef = ec.resource.getLocationReference(outputBaseLocation)
    File outputDir = outputRef?.getFile()
    if (outputDir == null) {
        String runtimePath = ec.factory.getRuntimePath()
        outputDir = new File(runtimePath, outputBaseLocation.replace("runtime://", ""))
    }
    if (!outputDir.exists() && !outputDir.mkdirs()) {
        throw new IllegalArgumentException("Unable to create output directory at ${outputDir.absolutePath}")
    }

    def cleanFileName = { String raw ->
        String cleaned = normalize(raw)?.replaceAll(/[^A-Za-z0-9._-]/, "-")
        return cleaned ?: "inventory-adjustments"
    }
    String baseName = cleanFileName(outputBaseName)
    String timestamp = ec.l10n.format(ec.user.nowTimestamp, "yyyyMMdd-HHmmss")

    def createUniqueFile = { String prefix ->
        File out = new File(outputDir, "${prefix}-${baseName}-${timestamp}.json")
        int suffix = 1
        while (out.exists()) {
            out = new File(outputDir, "${prefix}-${baseName}-${timestamp}-${suffix}.json")
            suffix++
        }
        return out
    }

    File nsFile = createUniqueFile("ns")
    File hcFile = createUniqueFile("hc")
    File summaryFile = createUniqueFile("summary")

    Map nsDoc = [
            metadata: [
                    source        : "NS",
                    nsRestletConfigId: nsConfigId,
                    from          : fromDateStr,
                    to            : toDateStr,
                    generatedAt   : ec.user.nowTimestamp?.toString()
            ],
            rows    : nsPayloadByItem
    ]
    Map hcDoc = [
            metadata: [
                    source         : "READ_DB",
                    readDbConfigId : hcConfigId,
                    hcReadDbConfigId: hcConfigId,
                    from           : fromDateStr,
                    to             : toDateStr,
                    generatedAt    : ec.user.nowTimestamp?.toString()
            ],
            rows    : hcPayloadByItem
    ]
    Map summaryDoc = [
            metadata : [
                    referenceFileLocation: refLocation,
                    referenceFileType    : refType,
                    itemIdField          : defaultItemIdFieldExpr,
                    locationIdField      : defaultLocationIdFieldExpr,
                    nsItemIdField        : nsItemIdFieldExpr,
                    nsLocationIdField    : nsLocationIdFieldExpr,
                    readDbItemIdField    : readDbItemIdFieldExpr,
                    readDbLocationIdField: readDbLocationIdFieldExpr,
                    comparisonRuleSetId  : comparisonRuleSetIdToUse,
                    compareStatusField   : compareStatusFieldName,
                    statusValues         : [
                            missingInNs    : missingInNsStatusValue,
                            missingInReadDb: missingInReadDbStatusValue,
                            countMismatch  : countMismatchStatusValue,
                            matched        : matchedStatusValue,
                            noRecord       : noRecordStatusValue,
                            error          : errorStatusValue
                    ],
                    from                 : fromDateStr,
                    to                   : toDateStr,
                    generatedAt          : ec.user.nowTimestamp?.toString()
            ],
            summary  : [
                    referenceItemCount   : referenceItemCount,
                    processedItemCount   : itemResultRows.size(),
                    nsRecordCount        : nsTotal,
                    hcRecordCount        : hcTotal,
                    missingInNsCount     : missingInNsTotal,
                    missingInReadDbCount : missingInReadDbTotal,
                    countMismatchCount   : countMismatchTotal,
                    matchedCount         : matchedTotal,
                    noRecordCount        : noRecordTotal,
                    errorItemCount       : errorItemTotal
            ],
            itemResults: itemResultRows,
            warnings: warnings
    ]

    nsFile.withWriter("UTF-8") { writer -> writer << JsonOutput.prettyPrint(JsonOutput.toJson(nsDoc)) }
    hcFile.withWriter("UTF-8") { writer -> writer << JsonOutput.prettyPrint(JsonOutput.toJson(hcDoc)) }
    summaryFile.withWriter("UTF-8") { writer -> writer << JsonOutput.prettyPrint(JsonOutput.toJson(summaryDoc)) }

    processedItemCount = itemResultRows.size()
    nsRecordCount = nsTotal
    hcRecordCount = hcTotal
    missingInNsCount = missingInNsTotal
    missingInReadDbCount = missingInReadDbTotal
    countMismatchCount = countMismatchTotal
    matchedCount = matchedTotal
    noRecordCount = noRecordTotal
    errorItemCount = errorItemTotal
    nsOutputLocation = nsFile.absolutePath
    hcOutputLocation = hcFile.absolutePath
    summaryLocation = summaryFile.absolutePath
    itemResults = itemResultRows
    processingWarnings = warnings.unique().collect { [warningMessage: it] }

    logger.info("Inventory retrieval complete reference={} ruleSetId={} processedItems={} nsRecords={} hcRecords={} missingInNs={} missingInReadDb={} countMismatch={} summary={}",
            refLocation, comparisonRuleSetIdToUse, processedItemCount, nsRecordCount, hcRecordCount, missingInNsCount, missingInReadDbCount, countMismatchCount, summaryLocation)
} finally {
    try { spark?.stop() } catch (Exception ignored) {}
}
