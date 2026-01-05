import com.jayway.jsonpath.JsonPath
import groovy.json.JsonSlurper
import org.apache.spark.sql.SparkSession
import org.apache.spark.storage.StorageLevel
import org.apache.spark.sql.types.DataTypes
import org.apache.spark.sql.types.StructType
import org.slf4j.LoggerFactory
import groovy.json.JsonOutput
import static org.apache.spark.sql.functions.*

def logger = LoggerFactory.getLogger("darpan.reconciliation.json.JsonReconciliation")
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
        if (url != null) {
            if ("file".equalsIgnoreCase(url.protocol)) {
                try {
                    return new File(url.toURI()).getAbsolutePath()
                } catch (Exception e) {
                    return url.getPath()
                }
            }
            return url.toString()
        }
    }
    return location
}

// Validate inputs
if (!json1Location || !json2Location) {
    throw new IllegalArgumentException("json1Location and json2Location are required")
}
if (!schemaFileName) {
    throw new IllegalArgumentException("schemaFileName is required for validation")
}
if (!compareJsonPath) {
    throw new IllegalArgumentException("compareJsonPath is required (JSONPath expression to extract ID field)")
}

def schemaFileName1 = schemaFileName?.toString()?.trim()
def schemaFileName2Value = schemaFileName2?.toString()?.trim()
if (!schemaFileName1) {
    throw new IllegalArgumentException("schemaFileName is required for validation")
}
if (!schemaFileName2Value) schemaFileName2Value = null
def schemaFileName2ForValidation = schemaFileName2Value ?: schemaFileName1
def compareJsonPath1 = compareJsonPath
def compareJsonPath2Value = compareJsonPath2 ?: compareJsonPath

def normalizeJsonPathFromSchema = { rawPath, schemaName ->
    if (!rawPath) return rawPath
    def pathText = rawPath.toString().trim()
    if (!pathText) return pathText

    def keyName = null
    if (pathText.startsWith("\$")) {
        def simpleKey = pathText.startsWith("\$.") ? pathText.substring(2) : null
        if (simpleKey && !simpleKey.contains(".") && !simpleKey.contains("[")) {
            keyName = simpleKey
        } else {
            return pathText
        }
    } else {
        keyName = pathText
    }

    def schemaToUse = schemaName?.toString()?.trim()
    if (!schemaToUse) {
        return keyName ? "\$.${keyName}" : pathText
    }
    def resolveOut = ec.service.sync().name("JsonSchemaServices.resolve#JsonPathForKey")
            .parameters([schemaFileName: schemaToUse, key: keyName]).call()
    def matches = resolveOut?.matchingPaths ?: []
    if (matches.size() == 1) {
        logger.info("Resolved JSONPath ${pathText} to ${resolveOut.resolvedJsonPath} using schema ${schemaToUse}")
        return resolveOut.resolvedJsonPath
    }
    if (matches.size() > 1) {
        throw new IllegalArgumentException("ID field ${keyName} is ambiguous in schema ${schemaToUse}. Matches: ${matches}. Enter a full JSONPath.")
    }
    throw new IllegalArgumentException("ID field ${keyName} not found in schema ${schemaToUse}. Enter a full JSONPath.")
}

compareJsonPath1 = normalizeJsonPathFromSchema(compareJsonPath1, schemaFileName1)
compareJsonPath2Value = normalizeJsonPathFromSchema(compareJsonPath2Value, schemaFileName2Value)

String json1Path = resolvePath(json1Location)
String json2Path = resolvePath(json2Location)

logger.info("Starting JSON reconciliation: json1=${json1Path} json2=${json2Path} schema1=${schemaFileName1} schema2=${schemaFileName2Value} jsonPath1=${compareJsonPath1} jsonPath2=${compareJsonPath2Value}")

logStatus("JSON reconcile: before schema validation")

// Use existing validation service for both files
validationPassed = true
validationErrors = []

logger.info("Validating JSON 1 against schema")
try {
    def json1ValidationResult = ec.service.sync()
            .name("JsonSchemaServices.validate#JsonLocationAgainstSchema")
            .parameters([jsonLocation: json1Location, schemaFileName: schemaFileName1])
            .call()
    
    if (!json1ValidationResult.valid) {
        validationPassed = false
        json1ValidationResult.errorMessages.each { errorMsg ->
            def prefixedMsg = "JSON 1: ${errorMsg}"
            validationErrors.add(prefixedMsg)
            logger.error(prefixedMsg)
        }
    }
} catch (Exception e) {
    validationPassed = false
    def errorMsg = "JSON 1 validation failed: ${e.message}"
    validationErrors.add(errorMsg)
    logger.error(errorMsg, e)
}

logger.info("Validating JSON 2 against schema")
try {
    def json2ValidationResult = ec.service.sync()
            .name("JsonSchemaServices.validate#JsonLocationAgainstSchema")
            .parameters([jsonLocation: json2Location, schemaFileName: schemaFileName2ForValidation])
            .call()
    
    if (!json2ValidationResult.valid) {
        validationPassed = false
        json2ValidationResult.errorMessages.each { errorMsg ->
            def prefixedMsg = "JSON 2: ${errorMsg}"
            validationErrors.add(prefixedMsg)
            logger.error(prefixedMsg)
        }
    }
} catch (Exception e) {
    validationPassed = false
    def errorMsg = "JSON 2 validation failed: ${e.message}"
    validationErrors.add(errorMsg)
    logger.error(errorMsg, e)
}

if (!validationPassed) {
    logger.warn("Validation failed with ${validationErrors.size()} errors, but continuing with reconciliation")
}


logStatus("JSON reconcile: before Spark session")

// Use Spark for efficient comparison (similar to CSV reconciliation)
SparkSession spark = null
try {
    spark = SparkSession.builder()
            .appName(sparkAppName ?: "ReconciliationJsonCompare")
            .master(sparkMaster ?: "local[*]")
            .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
            .config("spark.sql.adaptive.enabled", "true")
            .config("spark.sql.adaptive.coalescePartitions.enabled", "true")
            .config("spark.sql.files.maxPartitionBytes", "134217728")
            .getOrCreate()
    
    // Helper to convert JSONPath to Spark SQL expression
    def normalizeSparkPath = { String jsonPath ->
        if (!jsonPath) return jsonPath
        def path = jsonPath.toString().trim()
        path = path.replaceFirst(/^\$\[\*\]/, "")
        path = path.replaceFirst(/^\$\./, "")
        if (path.startsWith(".")) path = path.substring(1)
        return path
    }
    def convertJsonPathToSparkSql = { String jsonPath ->
        // Spark reads root arrays as rows, so strip JSONPath root/array markers
        def path = normalizeSparkPath(jsonPath)
        if (!path) {
            throw new IllegalArgumentException("JSONPath ${jsonPath} resolves to an empty field path. Provide a field like \$.id or \$[*].id.")
        }
        
        // Handle array wildcards: edges[*].node -> explode(edges).node
        // For complex paths, we need to handle nested explosions
        if (path.contains("[*]")) {
            // For now, support simple single-level explosion
            // Example: data.orders.edges[*].node.legacyResourceId
            //  becomes: explode(data.orders.edges) as edge, edge.node.legacyResourceId
            def parts = path.split(/\[\*\]/)
            if (parts.length == 2) {
                def arrayPath = parts[0]
                def afterArray = parts[1].replaceFirst(/^\./, "")
                // Return both the explosion expression and the final path
                return [needsExplode: true, arrayPath: arrayPath, fieldPath: afterArray]
            }
        }
        
        // Simple path without arrays
        return [needsExplode: false, path: path]
    }
    
    logger.info("Loading JSON files with Spark")
    def json1RawDf = spark.read().option("multiLine", "true").json(json1Path)
    def json2RawDf = spark.read().option("multiLine", "true").json(json2Path)
    
    logStatus("JSON reconcile: after loading raw JSON")
    
    def assertRootFieldExists = { df, rootField, pathLabel ->
        def rootFields = (df.columns() ?: []) as List
        if (!rootFields.contains(rootField)) {
            throw new IllegalArgumentException("JSONPath ${pathLabel} does not match root fields. Available root fields: ${rootFields.join(', ')}")
        }
    }

    // Extract IDs using Spark SQL (allow different JSONPaths per file)
    def pathInfo1 = convertJsonPathToSparkSql(compareJsonPath1)
    def pathInfo2 = convertJsonPathToSparkSql(compareJsonPath2Value)

    def buildIdDf = { rawDf, pathInfo, pathLabel ->
        if (pathInfo.needsExplode) {
            def arrayPath = normalizeSparkPath(pathInfo.arrayPath)
            def fieldPath = normalizeSparkPath(pathInfo.fieldPath)
            if (!arrayPath) {
                throw new IllegalArgumentException("JSONPath ${pathLabel} resolves to an empty array path after normalization.")
            }
            if (!fieldPath) {
                throw new IllegalArgumentException("JSONPath ${pathLabel} resolves to an empty field path after normalization.")
            }
            def rootField = arrayPath?.split(/\./)[0]
            if (rootField) {
                assertRootFieldExists(rawDf, rootField, pathLabel)
            }
            logger.info("Using explode for JSONPath with arrays: path=${pathLabel} arrayPath=${arrayPath}, fieldPath=${fieldPath}")
            return rawDf
                    .selectExpr("explode(${arrayPath}) as exploded_item")
                    .selectExpr("exploded_item.${fieldPath} as compare_id")
                    .filter("compare_id IS NOT NULL AND length(trim(cast(compare_id as string))) > 0")
                    .withColumn("compare_id", col("compare_id").cast("string"))
                    .distinct()
        }

        def safePath = normalizeSparkPath(pathInfo.path)
        if (!safePath) {
            throw new IllegalArgumentException("JSONPath ${pathLabel} resolves to an empty field path after normalization.")
        }
        def rootField = safePath?.split(/\./)[0]
        if (rootField) {
            assertRootFieldExists(rawDf, rootField, pathLabel)
        }
        logger.info("Using simple path extraction: path=${safePath}")
        return rawDf
                .selectExpr("${safePath} as compare_id")
                .filter("compare_id IS NOT NULL AND length(trim(cast(compare_id as string))) > 0")
                .withColumn("compare_id", col("compare_id").cast("string"))
                .distinct()
    }

    def json1Df = buildIdDf(json1RawDf, pathInfo1, compareJsonPath1)
    def json2Df = buildIdDf(json2RawDf, pathInfo2, compareJsonPath2Value)
    
    // Persist to disk to avoid large in-memory caches
    json1Df = json1Df.persist(StorageLevel.DISK_ONLY())
    json2Df = json2Df.persist(StorageLevel.DISK_ONLY())
    
    logger.info("Created Spark DataFrames: json1UniqueIds=${json1Df.count()} json2UniqueIds=${json2Df.count()}")
    logStatus("JSON reconcile: after DataFrame creation")
    
    // Use Spark anti-joins to find differences (same pattern as CSV)
    def onlyInJson1Df = json1Df.join(json2Df, "compare_id", "left_anti").select("compare_id")
    def onlyInJson2Df = json2Df.join(json1Df, "compare_id", "left_anti").select("compare_id")
    onlyInJson1Df = onlyInJson1Df.persist(StorageLevel.DISK_ONLY())
    onlyInJson2Df = onlyInJson2Df.persist(StorageLevel.DISK_ONLY())
    logStatus("JSON reconcile: after anti-joins")
    
    // Counts without collecting entire datasets to the driver
    onlyInJson1Count = onlyInJson1Df.count()
    onlyInJson2Count = onlyInJson2Df.count()
    differenceCount = onlyInJson1Count + onlyInJson2Count
    
    logger.info("Reconciliation complete: onlyInJson1=${onlyInJson1Count} onlyInJson2=${onlyInJson2Count} total=${differenceCount}")
    logStatus("JSON reconcile: after comparison")
    
    // Optionally return a small sample of IDs to avoid memory pressure
    def maxIdsReturned = (maxIdsReturned != null ? maxIdsReturned as Integer : 1000)
    def collectIds = { df ->
        return df.collect().collect { row -> row.get(0)?.toString() }
                .findAll { it != null && it.trim() }
                .sort()
    }
    onlyInJson1 = maxIdsReturned > 0 ? collectIds(onlyInJson1Df.limit(maxIdsReturned)) : []
    onlyInJson2 = maxIdsReturned > 0 ? collectIds(onlyInJson2Df.limit(maxIdsReturned)) : []

    // Build output structure with full objects (streamed to file)
    String json1LabelStr = json1Label?.toString() ?: "JSON 1"
    String json2LabelStr = json2Label?.toString() ?: "JSON 2"
    def normalizeTypeLabel = { label ->
        def normalized = label?.toString()?.trim()
        if (!normalized) return null
        normalized = normalized.replaceAll(/\s+/, "_")
        normalized = normalized.replaceAll(/[^A-Za-z0-9_]/, "")
        return normalized ?: null
    }
    def json1TypeLabel = normalizeTypeLabel(json1LabelStr) ?: "json1"
    def json2TypeLabel = normalizeTypeLabel(json2LabelStr) ?: "json2"
    def missingInJson1Type = "missing_in_" + json1TypeLabel
    def missingInJson2Type = "missing_in_" + json2TypeLabel

    def differenceDfs = []
    if (differenceCount > 0) {
        def buildDiffObjects = { rawDf, diffIdsDf, pathInfo ->
            def baseDf
            if (pathInfo.needsExplode) {
                def arrayPath = normalizeSparkPath(pathInfo.arrayPath)
                def fieldPath = normalizeSparkPath(pathInfo.fieldPath)
                if (!arrayPath) {
                    throw new IllegalArgumentException("JSONPath resolves to an empty array path after normalization.")
                }
                if (!fieldPath) {
                    throw new IllegalArgumentException("JSONPath resolves to an empty field path after normalization.")
                }
                baseDf = rawDf
                        .selectExpr("explode(${arrayPath}) as exploded_item")
                        .selectExpr("exploded_item.${fieldPath} as compare_id", "exploded_item as data")
                        .filter("compare_id IS NOT NULL AND length(trim(cast(compare_id as string))) > 0")
                        .withColumn("compare_id", col("compare_id").cast("string"))
            } else {
                def safePath = normalizeSparkPath(pathInfo.path)
                if (!safePath) {
                    throw new IllegalArgumentException("JSONPath resolves to an empty field path after normalization.")
                }
                baseDf = rawDf
                        .selectExpr("${safePath} as compare_id", "struct(*) as data")
                        .filter("compare_id IS NOT NULL AND length(trim(cast(compare_id as string))) > 0")
                        .withColumn("compare_id", col("compare_id").cast("string"))
            }
            return baseDf.join(diffIdsDf, "compare_id", "inner")
                    .dropDuplicates(["compare_id"] as String[])
        }

        def diffJson1Note = "Present in ${json1LabelStr}, missing in ${json2LabelStr}".toString()
        def diffJson2Note = "Present in ${json2LabelStr}, missing in ${json1LabelStr}".toString()

        if (onlyInJson1Count > 0) {
            def onlyInJson1ObjDf = buildDiffObjects(json1RawDf, onlyInJson1Df, pathInfo1)
        def diffJson1Df = onlyInJson1ObjDf.select(
                    lit(missingInJson2Type).alias("type"),
                    col("compare_id").alias("id"),
                    lit(json1LabelStr).alias("presentIn"),
                    lit(json2LabelStr).alias("missingIn"),
                    col("data").alias("data"),
                    lit(diffJson1Note).alias("note")
            )
            differenceDfs.add(diffJson1Df)
        }

        if (onlyInJson2Count > 0) {
            def onlyInJson2ObjDf = buildDiffObjects(json2RawDf, onlyInJson2Df, pathInfo2)
        def diffJson2Df = onlyInJson2ObjDf.select(
                    lit(missingInJson1Type).alias("type"),
                    col("compare_id").alias("id"),
                    lit(json2LabelStr).alias("presentIn"),
                    lit(json1LabelStr).alias("missingIn"),
                    col("data").alias("data"),
                    lit(diffJson2Note).alias("note")
            )
            differenceDfs.add(diffJson2Df)
        }
    }
    
    // Write output file
    String outputBaseLocation = outputLocation ?: "tmp/reconciliation/json-diff/output"
    def outputLocationRef = ec.resource.getLocationReference(outputBaseLocation)
    File outputDir = outputLocationRef?.getFile()
    if (outputDir == null) {
        outputDir = outputBaseLocation.startsWith("/") ?
                new File(outputBaseLocation) :
                new File((String) ec.factory.getRuntimePath(), outputBaseLocation)
    }
    if (!outputDir.exists()) outputDir.mkdirs()
    
    String timestamp = ec.l10n.format(ec.user.nowTimestamp, "yyyyMMdd-HHmmss")
    String baseFileName = outputFileName ?: "json-diff-${timestamp}.json"
    if (!baseFileName.endsWith(".json")) baseFileName = baseFileName + ".json"
    String diffFileNameStr = baseFileName
    String nameRoot = baseFileName.endsWith(".json") ? baseFileName[0..-6] : baseFileName
    File outputFile = new File(outputDir, diffFileNameStr)
    int suffix = 1
    while (outputFile.exists()) {
        diffFileNameStr = "${nameRoot}-${suffix}.json"
        outputFile = new File(outputDir, diffFileNameStr)
        suffix++
    }
    
    def outputLocationBase = outputBaseLocation.endsWith("/") ? outputBaseLocation[0..-2] : outputBaseLocation
    diffLocation = "${outputLocationBase}/${diffFileNameStr}"
    diffFileName = diffFileNameStr

    def timestampIso = null
    try {
        timestampIso = ec.user.nowTimestamp?.toInstant()?.toString()
    } catch (Exception e) {
        timestampIso = ec.user.nowTimestamp?.toString()
    }

    def outputMetadata = [
            timestamp: timestampIso,
            json1Label: json1LabelStr,
            json2Label: json2LabelStr,
            schemaFileName: schemaFileName1,
            compareJsonPath: compareJsonPath1,
            validationPassed: validationPassed
    ]
    if (schemaFileName2Value && schemaFileName2Value != schemaFileName1) {
        outputMetadata.schemaFileName2 = schemaFileName2Value
    }
    if (compareJsonPath2Value && compareJsonPath2Value != compareJsonPath1) {
        outputMetadata.compareJsonPath2 = compareJsonPath2Value
    }
    def outputSummary = [
            totalDifferences: differenceCount
    ]
    outputSummary["onlyIn${json1TypeLabel}Count"] = onlyInJson1Count
    outputSummary["onlyIn${json2TypeLabel}Count"] = onlyInJson2Count
    def outputErrors = validationErrors ?: []

    outputFile.withWriter("UTF-8") { writer ->
        writer << "{\n"
        writer << "\"metadata\":" + JsonOutput.toJson(outputMetadata) + ",\n"
        writer << "\"summary\":" + JsonOutput.toJson(outputSummary) + ",\n"
        writer << "\"validationErrors\":" + JsonOutput.toJson(outputErrors) + ",\n"
        writer << "\"differences\":["
        boolean first = true
        if (differenceCount > 0 && differenceDfs) {
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
    json1Df.unpersist()
    json2Df.unpersist()
    onlyInJson1Df.unpersist()
    onlyInJson2Df.unpersist()
    
    logger.info("Reconciliation results written to ${diffLocation}")
    logStatus("JSON reconcile: after write output + cleanup")
    
} catch (Exception e) {
    logger.error("JSON reconciliation failed", e)
    throw e
}
// DON'T call spark.stop() - keep session alive for reuse (consistent with CSV reconciliation)
logger.info("JSON reconcile: Spark session preserved for reuse (not stopped)")
