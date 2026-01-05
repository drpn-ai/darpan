import org.apache.spark.sql.SparkSession
import groovy.json.JsonOutput
import org.slf4j.LoggerFactory
import static org.apache.spark.sql.functions.*

def logger = LoggerFactory.getLogger("reconciliation.csv.CsvReconciliation")
def toMb = { long bytes -> (bytes / (1024L * 1024L)) }
def toPercent = { val ->
    if (val == null) return "n/a"
    try {
        return ((val as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP)).toString()
    } catch (Exception e) {
        return val.toString()
    }
}
def getUtilization = {
    def smap = ec.factory.getStatusMap(true)
    def util = smap?.Utilization ?: [:]
    return [load: toPercent(util.LoadPercent), heap: toPercent(util.HeapPercent), disk: toPercent(util.DiskPercent)]
}
def logStatus = { String stage ->
    Runtime rt = Runtime.getRuntime()
    long total = rt.totalMemory()
    long free = rt.freeMemory()
    long used = total - free
    long max = rt.maxMemory()
    def util = getUtilization()
    logger.info("${stage} heap used=${toMb(used)}MB total=${toMb(total)}MB max=${toMb(max)}MB heapPct=${util.heap}% load=${util.load}% disk=${util.disk}%")
}

def resolvePath = { String location ->
    def rr = ec.resource.getLocationReference(location)
    if (rr != null && rr.supportsUrl()) {
        def url = rr.getUrl()
        if (url != null) return url.toString()
    }
    return location
}

def normalizeBool = { val, boolean defaultValue ->
    if (val == null) return defaultValue
    return val.toString().equalsIgnoreCase("true")
}

def safeCsv = { String value ->
    if (value == null) return ""
    return value.replace("\"", "\"\"")
}

def resolveMappingField = { String mappingId, String systemId, String label ->
    def member = ec.entity.find("darpan.mapping.ReconciliationMappingMember")
            .condition("reconciliationMappingId", mappingId)
            .condition("systemEnumId", systemId)
            .one()
    if (!member?.systemFieldName) {
        throw new IllegalArgumentException("Mapping member not found for mapping ${mappingId} and ${label} system ${systemId}")
    }
    return member.systemFieldName
}

def resolveSystemLabel = { String enumId, String defaultLabel ->
    def normalized = enumId?.toString()?.trim()
    if (!normalized) return defaultLabel
    def enumValue = ec.entity.find("moqui.basic.Enumeration")
            .condition("enumId", normalized)
            .one()
    def code = enumValue?.enumCode?.toString()?.trim()
    if (code) return code
    def description = enumValue?.description?.toString()?.trim()
    if (description) return description
    return normalized
}

def quoteField = { String name ->
    def safe = name?.replace("`", "``")
    return safe ? "`" + safe + "`" : null
}


if (!csv1Location || !csv2Location) {
    throw new IllegalArgumentException("csv1Location and csv2Location are required")
}

String mappingId = reconciliationMappingId?.toString()?.trim()
String csv1Field = null
String csv2Field = null
String outputField = null
String compareLabel = "id field"

if (mappingId) {
    String csv1SystemId = csv1SystemEnumId?.toString()?.trim()
    String csv2SystemId = csv2SystemEnumId?.toString()?.trim()
    if (!csv1SystemId || !csv2SystemId) {
        throw new IllegalArgumentException("csv1SystemEnumId and csv2SystemEnumId are required when reconciliationMappingId is provided")
    }

    def mapping = ec.entity.find("darpan.mapping.ReconciliationMapping")
            .condition("reconciliationMappingId", mappingId)
            .one()
    if (!mapping) {
        throw new IllegalArgumentException("ReconciliationMapping ${mappingId} not found")
    }

    csv1Field = resolveMappingField(mappingId, csv1SystemId, "CSV 1")
    csv2Field = resolveMappingField(mappingId, csv2SystemId, "CSV 2")
    outputField = mapping.mappingName ?: mappingId
    compareLabel = "mapping ${mappingId}"
} else {
    csv1Field = csv1IdField ?: idField ?: "order_id"
    csv2Field = csv2IdField ?: idField ?: "order_id"
    outputField = idField ?: csv1Field
}

csv1Field = csv1Field?.toString()?.trim()
csv2Field = csv2Field?.toString()?.trim()
outputField = outputField?.toString()?.trim()
if (!csv1Field || !csv2Field) {
    throw new IllegalArgumentException("Unable to resolve compare field names for CSV 1 and CSV 2")
}
if (!outputField) outputField = csv1Field
boolean includeHeader = normalizeBool(hasHeader, true)

String csv1Path = resolvePath(csv1Location)
String csv2Path = resolvePath(csv2Location)

String outputBaseLocation = outputLocation ?: "tmp/reconciliation/csv-diff/output"
def outputLocationRef = ec.resource.getLocationReference(outputBaseLocation)
File outputDir = outputLocationRef?.getFile()
if (outputDir == null) {
    outputDir = outputBaseLocation.startsWith("/") ?
            new File(outputBaseLocation) :
            new File((String) ec.factory.getRuntimePath(), outputBaseLocation)
}
if (!outputDir.exists()) outputDir.mkdirs()

String timestamp = ec.l10n.format(ec.user.nowTimestamp, "yyyyMMdd-HHmmss")
String mappingSlug = (mappingId ?: "csv").toString().replaceAll("[^A-Za-z0-9._-]", "_")
String baseFileName = outputFileName ?: "${mappingSlug}-diff-${timestamp}.json"
def baseFileNameLower = baseFileName.toLowerCase()
if (baseFileNameLower.endsWith(".csv")) {
    baseFileName = baseFileName[0..-5] + ".json"
} else if (!baseFileNameLower.endsWith(".json")) {
    baseFileName = baseFileName + ".json"
}
String diffFileName = baseFileName
String nameRoot = baseFileName.toLowerCase().endsWith(".json") ? baseFileName[0..-6] : baseFileName
File outputFile = new File(outputDir, diffFileName)
int suffix = 1
while (outputFile.exists()) {
    diffFileName = "${nameRoot}-${suffix}.json"
    outputFile = new File(outputDir, diffFileName)
    suffix++
}

def outputLocationBase = outputBaseLocation.endsWith("/") ? outputBaseLocation[0..-2] : outputBaseLocation

diffLocation = "${outputLocationBase}/${diffFileName}"

logStatus("reconcile csv: before Spark session")
SparkSession spark = null
try {
    spark = SparkSession.builder()
            .appName(sparkAppName ?: "ReconciliationCsvCompare")
            .master(sparkMaster ?: "local[*]")
            .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
            .config("spark.sql.adaptive.enabled", "true")
            .config("spark.sql.adaptive.coalescePartitions.enabled", "true")
            .config("spark.sql.files.maxPartitionBytes", "134217728")
            .getOrCreate()

    def reader = spark.read().option("header", includeHeader.toString()).option("multiLine", "true")
    def loadCsv = { String path, String fieldName, String label ->
        logger.info("loading ${label} from ${path} using ${compareLabel} field '${fieldName}'")
        def raw = reader.csv(path)
        def fieldExpr = quoteField(fieldName)
        if (!fieldExpr) {
            throw new IllegalArgumentException("CSV compare field is required for ${label}")
        }
        def idDf = raw.selectExpr("${fieldExpr} as compare_id")
                .filter("compare_id IS NOT NULL AND length(trim(compare_id)) > 0")
                .withColumn("compare_id", col("compare_id").cast("string"))
                .dropDuplicates()
        def dataDf = raw.selectExpr("${fieldExpr} as compare_id", "struct(*) as data")
                .filter("compare_id IS NOT NULL AND length(trim(compare_id)) > 0")
                .withColumn("compare_id", col("compare_id").cast("string"))
        return [id: idDf, data: dataDf]
    }

    def csv1Load = loadCsv(csv1Path, csv1Field, "CSV 1")
    def csv1Df = csv1Load.id.cache()
    def csv1DataDf = csv1Load.data
    logStatus("reconcile csv: after load CSV 1")
    def csv2Load = loadCsv(csv2Path, csv2Field, "CSV 2")
    def csv2Df = csv2Load.id.cache()
    def csv2DataDf = csv2Load.data

    if (logInputCounts) {
        logger.info("loaded CSV id sets: csv1UniqueIds=${csv1Df.count()} csv2UniqueIds=${csv2Df.count()}")
    }
    logStatus("reconcile csv: after load + dedupe")

    def onlyInCsv1Df = csv1Df.join(csv2Df, "compare_id", "left_anti").select("compare_id")
    def onlyInCsv2Df = csv2Df.join(csv1Df, "compare_id", "left_anti").select("compare_id")
    logStatus("reconcile csv: after anti-joins")

    // Get counts only - don't collect actual data
    onlyInCsv1Count = onlyInCsv1Df.count()
    onlyInCsv2Count = onlyInCsv2Df.count()
    diffRowCount = onlyInCsv1Count + onlyInCsv2Count

    String csv1LabelStr = resolveSystemLabel(csv1SystemEnumId, "CSV 1")
    String csv2LabelStr = resolveSystemLabel(csv2SystemEnumId, "CSV 2")
    def normalizeTypeLabel = { label ->
        def normalized = label?.toString()?.trim()
        if (!normalized) return null
        normalized = normalized.replaceAll(/\s+/, "_")
        normalized = normalized.replaceAll(/[^A-Za-z0-9_]/, "")
        return normalized ?: null
    }
    def csv1TypeLabel = normalizeTypeLabel(csv1LabelStr) ?: "csv1"
    def csv2TypeLabel = normalizeTypeLabel(csv2LabelStr) ?: "csv2"
    def missingInCsv1Type = "missing_in_" + csv1TypeLabel
    def missingInCsv2Type = "missing_in_" + csv2TypeLabel
    String outputFieldName = outputField ?: "id"

    def differenceDfs = []
    if (onlyInCsv1Count > 0) {
        def onlyInCsv1ObjDf = csv1DataDf.join(onlyInCsv1Df, "compare_id", "inner")
                .dropDuplicates(["compare_id"] as String[])
        def diffCsv1Df = onlyInCsv1ObjDf.select(
                lit(missingInCsv2Type).alias("type"),
                col("compare_id").alias("id"),
                lit(csv1LabelStr).alias("presentIn"),
                lit(csv2LabelStr).alias("missingIn"),
                col("data").alias("data")
        )
        differenceDfs.add(diffCsv1Df)
    }
    if (onlyInCsv2Count > 0) {
        def onlyInCsv2ObjDf = csv2DataDf.join(onlyInCsv2Df, "compare_id", "inner")
                .dropDuplicates(["compare_id"] as String[])
        def diffCsv2Df = onlyInCsv2ObjDf.select(
                lit(missingInCsv1Type).alias("type"),
                col("compare_id").alias("id"),
                lit(csv2LabelStr).alias("presentIn"),
                lit(csv1LabelStr).alias("missingIn"),
                col("data").alias("data")
        )
        differenceDfs.add(diffCsv2Df)
    }

    def timestampIso = null
    try {
        timestampIso = ec.user.nowTimestamp?.toInstant()?.toString()
    } catch (Exception e) {
        timestampIso = ec.user.nowTimestamp?.toString()
    }

    def outputMetadata = [
            timestamp: timestampIso,
            json1Label: csv1LabelStr,
            json2Label: csv2LabelStr,
            compareField: outputFieldName,
            reconciliationMappingId: mappingId,
            csv1SystemEnumId: csv1SystemEnumId,
            csv2SystemEnumId: csv2SystemEnumId
    ]
    def outputSummary = [
            totalDifferences: diffRowCount
    ]
    outputSummary["onlyIn${csv1TypeLabel}Count"] = onlyInCsv1Count
    outputSummary["onlyIn${csv2TypeLabel}Count"] = onlyInCsv2Count

    outputFile.withWriter("UTF-8") { writer ->
        writer << "{\n"
        writer << "\"metadata\":" + JsonOutput.toJson(outputMetadata) + ",\n"
        writer << "\"summary\":" + JsonOutput.toJson(outputSummary) + ",\n"
        writer << "\"validationErrors\":[],\n"
        writer << "\"differences\":["
        boolean first = true
        if (diffRowCount > 0 && differenceDfs) {
            differenceDfs.each { df ->
                def iter = df.toJSON().toLocalIterator()
                while (iter.hasNext()) {
                    def rowJson = iter.next()
                    if (!first) writer << ","
                    writer << "\n" << rowJson
                    first = false
                }
            }
        }
        if (!first) writer << "\n"
        writer << "]\n}"
    }

    // Unpersist cached DataFrames
    csv1Df.unpersist()
    csv2Df.unpersist()

    logger.info("comparison results: onlyInCsv1=${onlyInCsv1Count} onlyInCsv2=${onlyInCsv2Count} diffFile=${diffFileName}")
    logStatus("reconcile csv: after write diff + cleanup")
} catch (Exception e) {
    logger.error("CSV reconciliation failed", e)
    throw e
}
// DON'T call spark.stop() - keep session alive for reuse
logger.info("reconcile csv: Spark session preserved for reuse (not stopped)")
