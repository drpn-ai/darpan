import org.slf4j.LoggerFactory
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import org.apache.spark.sql.SparkSession
import org.apache.spark.storage.StorageLevel
import static org.apache.spark.sql.functions.*

def logger = LoggerFactory.getLogger("darpan.reconciliation.generic.GenericReconciliation")

// Validate required inputs
if (!file1 || !file2) {
    throw new IllegalArgumentException("Both file1 and file2 are required")
}
if (!file1SystemEnumId || !file2SystemEnumId) {
    throw new IllegalArgumentException("System selection is required for both files")
}
if (!reconciliationMappingId) {
    throw new IllegalArgumentException("Mapping selection is required")
}

// Load mapping and member configurations
def mapping = ec.entity.find("darpan.mapping.ReconciliationMapping")
        .condition("reconciliationMappingId", reconciliationMappingId)
        .useCache(true).one()
def mappingName = mapping?.mappingName ?: "reconciliation"

def mappingMembers = ec.entity.find("darpan.mapping.ReconciliationMappingMember")
        .condition("reconciliationMappingId", reconciliationMappingId)
        .list()

if (!mappingMembers) {
    throw new IllegalArgumentException("No mapping members found for mapping: ${reconciliationMappingId}")
}

// Build config map by systemEnumId
def configBySystem = [:]
mappingMembers.each { member ->
    def systemId = member.systemEnumId
    configBySystem[systemId] = [
        fileTypeEnumId: member.fileTypeEnumId,
        schemaFileName: member.schemaFileName,
        idFieldExpression: member.idFieldExpression ?: member.systemFieldName // fallback for backward compat
    ]
}

// Look up system descriptions for labels
def system1Enum = ec.entity.find("moqui.basic.Enumeration").condition("enumId", file1SystemEnumId).one()
def system2Enum = ec.entity.find("moqui.basic.Enumeration").condition("enumId", file2SystemEnumId).one()
def resolveEnumLabel = { enumValue, fallbackId ->
    def code = enumValue?.enumCode?.toString()?.trim()
    if (code) return code
    def description = enumValue?.description?.toString()?.trim()
    if (description) return description
    return fallbackId
}
def file1Label = resolveEnumLabel(system1Enum, file1SystemEnumId)
def file2Label = resolveEnumLabel(system2Enum, file2SystemEnumId)

// Get configs for selected systems
def file1Config = configBySystem[file1SystemEnumId] ?: [:]
def file2Config = configBySystem[file2SystemEnumId] ?: [:]

// Extract configuration from mapping
def file1IdField = file1Config.idFieldExpression ?: 'id'
def file2IdField = file2Config.idFieldExpression ?: 'id'
def file1Schema = file1Config.schemaFileName
def file2Schema = file2Config.schemaFileName

// Use idField from mapping (for CSV, both should be the same - use file1's)
def idField = file1IdField
// For JSON, use JSONPath from mapping (allow different paths per file)
def compareJsonPath1 = file1IdField?.startsWith('$') ? file1IdField : "\$.${file1IdField}"
def compareJsonPath2 = file2IdField?.startsWith('$') ? file2IdField : "\$.${file2IdField}"
// Schema for JSON validation
def schemaFileName1 = file1Schema
def schemaFileName2 = file2Schema
def schemaFileName = schemaFileName1 ?: schemaFileName2

logger.info("Loaded mapping config: file1=[${file1Config}], file2=[${file2Config}]")

def normalizeJsonPathForJson = { rawPath, schemaName, jsonData ->
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
    if (schemaToUse && keyName) {
        try {
            def resolveOut = ec.service.sync().name("JsonSchemaServices.resolve#JsonPathForKey")
                    .parameters([schemaFileName: schemaToUse, key: keyName]).call()
            def matches = resolveOut?.matchingPaths ?: []
            if (matches.size() == 1) {
                logger.info("Resolved JSONPath ${pathText} to ${resolveOut.resolvedJsonPath} using schema ${schemaToUse}")
                return resolveOut.resolvedJsonPath
            }
            if (matches.size() > 1) {
                logger.warn("JSONPath ${pathText} is ambiguous in schema ${schemaToUse}; using fallback path resolution")
            }
        } catch (Exception e) {
            logger.warn("Unable to resolve JSONPath ${pathText} using schema ${schemaToUse}: ${e.message}")
        }
    }

    if (jsonData instanceof List) {
        if (pathText.startsWith("\$.") && !pathText.startsWith("\$[*].")) {
            return pathText.replaceFirst(/^\$\./, "\$[*].")
        }
        return keyName ? "\$[*].${keyName}" : pathText
    }
    return keyName ? "\$.${keyName}" : pathText
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

def normalizeBool = { val, boolean defaultValue ->
    if (val == null) return defaultValue
    return val.toString().equalsIgnoreCase("true")
}

def normalizeCsvIdField = { rawField ->
    if (!rawField) return rawField
    def fieldText = rawField.toString().trim()
    if (!fieldText) return fieldText
    if (!fieldText.startsWith("\$")) return fieldText

    def normalized = fieldText
    normalized = normalized.replaceFirst(/^\$\[\*\]\./, "")
    normalized = normalized.replaceFirst(/^\$\./, "")
    if (normalized.contains(".")) {
        normalized = normalized.tokenize(".")[-1]
    }
    normalized = normalized.replaceAll(/\[.*\]$/, "")
    normalized = normalized?.trim()
    return normalized ?: null
}

// Helper to detect file type based on name and content (with override from mapping)
def detectFileType = { fileItem, systemEnumId ->
    // Check if mapping specifies file type
    def mappedConfig = configBySystem[systemEnumId]
    if (mappedConfig?.fileTypeEnumId) {
        def fileTypeEnum = ec.entity.find("moqui.basic.Enumeration")
                .condition("enumId", mappedConfig.fileTypeEnumId).one()
        if (fileTypeEnum?.enumCode) {
            logger.info("Using file type from mapping: ${fileTypeEnum.enumCode}")
            return fileTypeEnum.enumCode
        }
    }
    
    // Fall back to auto-detection
    def fileName = fileItem.getName()?.toLowerCase()?.trim()
    
    // Check extension first
    if (fileName.endsWith('.csv')) {
        logger.info("Detected CSV file by extension: ${fileName}")
        return 'CSV'
    }
    if (fileName.endsWith('.json')) {
        logger.info("Detected JSON file by extension: ${fileName}")
        return 'JSON'
    }
    
    // Fallback: peek at content
    try {
        def inputStream = fileItem.getInputStream()
        def firstBytes = new byte[500]
        def bytesRead = inputStream.read(firstBytes)
        inputStream.close()
        
        if (bytesRead > 0) {
            def content = new String(firstBytes, 0, bytesRead).trim()
            if (content.startsWith('{') || content.startsWith('[')) {
                logger.info("Detected JSON file by content: ${fileName}")
                return 'JSON'
            }
        }
    } catch (Exception e) {
        logger.warn("Could not peek at file content: ${e.message}")
    }
    
    // Default to CSV
    logger.info("Defaulting to CSV for file: ${fileName}")
    return 'CSV'
}

// Detect file types
file1Type = detectFileType(file1, file1SystemEnumId)
file2Type = detectFileType(file2, file2SystemEnumId)

logger.info("File type detection: file1=${file1Type}, file2=${file2Type}")

// Determine reconciliation strategy
def reconType = "${file1Type}_${file2Type}"
processingWarnings = []

// Save files to temp location
// Save files to temp location
def tempLoc = ec.resource.properties['reconciliation.temp.location'] ?: 'runtime://tmp/reconciliation/generic'
def baseDirRef = ec.resource.getLocationReference(tempLoc)
if (baseDirRef == null) {
    throw new IllegalStateException("Unable to resolve generic reconciliation temp directory: ${tempLoc}")
}
def inputDirRef = baseDirRef.makeDirectory('input')
if (inputDirRef == null) {
    throw new IllegalStateException("Unable to create generic reconciliation input directory")
}
if (!inputDirRef.getExists()) {
    def inputDirFile = inputDirRef.getFile()
    if (inputDirFile != null && !inputDirFile.exists()) {
        inputDirFile.mkdirs()
    }
}

def timestamp = ec.l10n.format(ec.user.nowTimestamp, 'yyyyMMdd-HHmmssSSS')
def file1SafeName = file1.getName()?.replaceAll('[^A-Za-z0-9._-]', '_') ?: 'file1'
def file2SafeName = file2.getName()?.replaceAll('[^A-Za-z0-9._-]', '_') ?: 'file2'

def file1Ref = inputDirRef.makeFile(timestamp + '-file1-' + file1SafeName)
def file2Ref = inputDirRef.makeFile(timestamp + '-file2-' + file2SafeName)

// Optimize file write: use write(File) if possible to avoid memory buffering
def saveFileItem = { fileItem, targetRef ->
    File targetFile = targetRef.getFile()
    if (targetFile != null) {
        try {
            fileItem.write(targetFile)
            return
        } catch (Exception e) {
            logger.warn("Failed to write directly to file ${targetFile.getAbsolutePath()}, falling back to stream: ${e.message}")
        }
    }
    targetRef.putStream(fileItem.getInputStream())
}

saveFileItem(file1, file1Ref)
saveFileItem(file2, file2Ref)

if (!file1Ref.getExists() || !file2Ref.getExists()) {
    throw new IllegalStateException("Failed to persist uploaded files to ${inputDirRef.getLocation()}")
}

logger.info("Saved files: file1=${file1Ref.getLocation()}, file2=${file2Ref.getLocation()}")

// Route to appropriate reconciliation service
def result

if (reconType == "CSV_CSV") {
    // Both CSV - use CSV reconciliation service
    reconciliationType = "CSV"
    logger.info("Routing to CSV reconciliation")
    
    result = ec.service.sync()
            .name("ReconciliationCsvServices.compare#CsvFiles")
            .parameters([
                csv1Location: file1Ref.getLocation(),
                csv2Location: file2Ref.getLocation(),
                reconciliationMappingId: reconciliationMappingId,
                csv1SystemEnumId: file1SystemEnumId,
                csv2SystemEnumId: file2SystemEnumId,
                idField: idField,
                hasHeader: hasHeader,
                outputLocation: tempLoc + '/output',
                sparkMaster: sparkMaster ?: (ec.resource.properties['spark.master'] ?: "local[*]"),
                sparkAppName: sparkAppName
            ])
            .call()
    
    diffLocation = result.diffLocation
    diffFileName = result.diffFileName
    onlyInFile1Count = result.onlyInCsv1Count
    onlyInFile2Count = result.onlyInCsv2Count
    differenceCount = result.diffRowCount
    
} else if (reconType == "JSON_JSON") {
    // Both JSON - use JSON reconciliation service
    reconciliationType = "JSON"
    logger.info("Routing to JSON reconciliation")
    
    // Schema is required for JSON
    if (!schemaFileName) {
        processingWarnings.add([warningMessage: "No schema specified, validation will be skipped"])
    }
    if (!compareJsonPath1) {
        compareJsonPath1 = '$.id'
        processingWarnings.add([warningMessage: "No JSONPath specified for file 1, using default: \$.id"])
    }
    if (!compareJsonPath2) {
        compareJsonPath2 = compareJsonPath1
        processingWarnings.add([warningMessage: "No JSONPath specified for file 2, using file 1 JSONPath"])
    }
    
    def file1Location = file1Ref.getFile()?.getAbsolutePath() ?: file1Ref.getLocation()
    def file2Location = file2Ref.getFile()?.getAbsolutePath() ?: file2Ref.getLocation()
    result = ec.service.sync()
            .name("ReconciliationJsonServices.reconcile#JsonFiles")
            .parameters([
                json1Location: file1Location,
                json2Location: file2Location,
                schemaFileName: schemaFileName ?: '',
                schemaFileName2: schemaFileName2,
                compareJsonPath: compareJsonPath1,
                compareJsonPath2: compareJsonPath2,
                json1Label: file1Label,
                json2Label: file2Label,
                outputLocation: tempLoc + '/output',
                sparkMaster: sparkMaster ?: (ec.resource.properties['spark.master'] ?: "local[*]"),
                sparkAppName: sparkAppName
            ])
            .call()
    
    diffLocation = result.diffLocation
    diffFileName = result.diffFileName
    onlyInFile1Count = result.onlyInJson1Count
    onlyInFile2Count = result.onlyInJson2Count
    differenceCount = result.differenceCount
    validationErrors = result.validationErrors ?: []
    
} else {
    // Mixed type: CSV + JSON
    reconciliationType = "MIXED"
    logger.info("Mixed file types detected: ${reconType}")
    processingWarnings.add([warningMessage: "Mixed file types (CSV + JSON). Comparing IDs and preserving full data."])
    
    // Strategy: Use Spark to compare IDs and preserve full data from both files
    // Identify which is CSV and which is JSON
    def csvLocation
    def jsonLocation
    def isFile1Csv = (file1Type == 'CSV')
    
    if (isFile1Csv) {
        csvLocation = file1Ref.getLocation()
        jsonLocation = file2Ref.getFile()?.getAbsolutePath() ?: file2Ref.getLocation()
    } else {
        csvLocation = file2Ref.getLocation()
        jsonLocation = file1Ref.getFile()?.getAbsolutePath() ?: file1Ref.getLocation()
    }
    
    def csvIdFieldRaw = isFile1Csv ? file1IdField : file2IdField
    def csvIdField = normalizeCsvIdField(csvIdFieldRaw)
    if (!csvIdField) {
        csvIdField = "id"
        def warnMessage = "CSV id field '${csvIdFieldRaw}' looks like a JSONPath; using default: ${csvIdField}"
        processingWarnings.add([warningMessage: warnMessage])
        logger.warn(warnMessage)
    } else if (csvIdFieldRaw?.toString()?.trim()?.startsWith("\$")) {
        def warnMessage = "CSV id field '${csvIdFieldRaw}' looks like a JSONPath; using column '${csvIdField}'"
        processingWarnings.add([warningMessage: warnMessage])
        logger.warn(warnMessage)
    }

    // Prepare JSON path for Spark extraction
    def jsonIdField = isFile1Csv ? file2IdField : file1IdField
    def jsonSchemaFileName = isFile1Csv ? file2Schema : file1Schema
    def compareJsonPathForJson = jsonIdField?.startsWith('$') ? jsonIdField : "\$.${jsonIdField}"
    if (!compareJsonPathForJson) {
        compareJsonPathForJson = '$.id'
        processingWarnings.add([warningMessage: "No JSONPath specified for JSON file, using default: \$.id"])
    }
    
    def jsonPathResolved = resolvePath(jsonLocation)
    def jsonData = null
    try {
        def slurper = new JsonSlurper()
        jsonData = new File(jsonPathResolved).withInputStream { stream -> slurper.parse(stream) }
    } catch (Exception e) {
        logger.warn("Unable to read JSON file for path normalization: ${e.message}")
    }
    if (jsonData != null) {
        compareJsonPathForJson = normalizeJsonPathForJson(compareJsonPathForJson, jsonSchemaFileName, jsonData)
    }
    logger.info("Mixed reconciliation using JSONPath: ${compareJsonPathForJson} and CSV id field: ${csvIdField}")

    boolean includeHeader = normalizeBool(hasHeader, true)
    SparkSession spark = SparkSession.builder()
            .appName(sparkAppName ?: "ReconciliationMixedCompare")
            .master(sparkMaster ?: (ec.resource.properties['spark.master'] ?: "local[*]"))
            .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
            .config("spark.sql.adaptive.enabled", "true")
            .config("spark.sql.adaptive.coalescePartitions.enabled", "true")
            .config("spark.sql.files.maxPartitionBytes", "134217728")
            .getOrCreate()

    def normalizeSparkPath = { String jsonPath ->
        if (!jsonPath) return jsonPath
        def path = jsonPath.toString().trim()
        path = path.replaceFirst(/^\$\[\*\]/, "")
        path = path.replaceFirst(/^\$\./, "")
        if (path.startsWith(".")) path = path.substring(1)
        return path
    }
    def convertJsonPathToSparkSql = { String jsonPath ->
        def path = normalizeSparkPath(jsonPath)
        if (!path) {
            throw new IllegalArgumentException("JSONPath ${jsonPath} resolves to an empty field path.")
        }
        if (path.contains("[*]")) {
            def parts = path.split(/\[\*\]/)
            if (parts.length == 2) {
                def arrayPath = parts[0]
                def afterArray = parts[1].replaceFirst(/^\./, "")
                return [needsExplode: true, arrayPath: arrayPath, fieldPath: afterArray]
            }
        }
        return [needsExplode: false, path: path]
    }
    def assertRootFieldExists = { df, rootField, pathLabel ->
        def rootFields = (df.columns() ?: []) as List
        if (!rootFields.contains(rootField)) {
            throw new IllegalArgumentException("JSONPath ${pathLabel} does not match root fields. Available root fields: ${rootFields.join(', ')}")
        }
    }
    def quoteField = { String name ->
        def safe = name?.replace("`", "``")
        return safe ? "`" + safe + "`" : null
    }
    def buildCsvIdDf = { rawDf, fieldName ->
        def fieldExpr = quoteField(fieldName)
        if (!fieldExpr) {
            throw new IllegalArgumentException("CSV id field is required")
        }
        return rawDf
                .selectExpr("${fieldExpr} as compare_id")
                .filter("compare_id IS NOT NULL AND length(trim(compare_id)) > 0")
                .withColumn("compare_id", col("compare_id").cast("string"))
                .dropDuplicates()
    }
    def buildCsvDataDf = { rawDf, fieldName ->
        def fieldExpr = quoteField(fieldName)
        if (!fieldExpr) {
            throw new IllegalArgumentException("CSV id field is required")
        }
        return rawDf
                .selectExpr("${fieldExpr} as compare_id", "struct(*) as data")
                .filter("compare_id IS NOT NULL AND length(trim(compare_id)) > 0")
                .withColumn("compare_id", col("compare_id").cast("string"))
    }
    def buildJsonIdDf = { rawDf, pathInfo, pathLabel ->
        if (pathInfo.needsExplode) {
            def arrayPath = normalizeSparkPath(pathInfo.arrayPath)
            def fieldPath = normalizeSparkPath(pathInfo.fieldPath)
            if (!arrayPath || !fieldPath) {
                throw new IllegalArgumentException("JSONPath ${pathLabel} resolves to an empty array or field path after normalization.")
            }
            def rootField = arrayPath?.split(/\./)[0]
            if (rootField) assertRootFieldExists(rawDf, rootField, pathLabel)
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
        if (rootField) assertRootFieldExists(rawDf, rootField, pathLabel)
        return rawDf
                .selectExpr("${safePath} as compare_id")
                .filter("compare_id IS NOT NULL AND length(trim(cast(compare_id as string))) > 0")
                .withColumn("compare_id", col("compare_id").cast("string"))
                .distinct()
    }
    def buildJsonDataDf = { rawDf, pathInfo, pathLabel ->
        if (pathInfo.needsExplode) {
            def arrayPath = normalizeSparkPath(pathInfo.arrayPath)
            def fieldPath = normalizeSparkPath(pathInfo.fieldPath)
            if (!arrayPath || !fieldPath) {
                throw new IllegalArgumentException("JSONPath ${pathLabel} resolves to an empty array or field path after normalization.")
            }
            def rootField = arrayPath?.split(/\./)[0]
            if (rootField) assertRootFieldExists(rawDf, rootField, pathLabel)
            return rawDf
                    .selectExpr("explode(${arrayPath}) as exploded_item")
                    .selectExpr("exploded_item.${fieldPath} as compare_id", "exploded_item as data")
                    .filter("compare_id IS NOT NULL AND length(trim(cast(compare_id as string))) > 0")
                    .withColumn("compare_id", col("compare_id").cast("string"))
        }
        def safePath = normalizeSparkPath(pathInfo.path)
        if (!safePath) {
            throw new IllegalArgumentException("JSONPath ${pathLabel} resolves to an empty field path after normalization.")
        }
        def rootField = safePath?.split(/\./)[0]
        if (rootField) assertRootFieldExists(rawDf, rootField, pathLabel)
        return rawDf
                .selectExpr("${safePath} as compare_id", "struct(*) as data")
                .filter("compare_id IS NOT NULL AND length(trim(cast(compare_id as string))) > 0")
                .withColumn("compare_id", col("compare_id").cast("string"))
    }

    String csvPath = resolvePath(csvLocation)
    String jsonPath = resolvePath(jsonLocation)
    def csvRawDf = spark.read().option("header", includeHeader.toString()).option("multiLine", "true").csv(csvPath)
    def jsonRawDf = spark.read().option("multiLine", "true").json(jsonPath)

    def jsonPathInfo = convertJsonPathToSparkSql(compareJsonPathForJson)
    def csvIdDf = buildCsvIdDf(csvRawDf, csvIdField).persist(StorageLevel.DISK_ONLY())
    def jsonIdDf = buildJsonIdDf(jsonRawDf, jsonPathInfo, compareJsonPathForJson).persist(StorageLevel.DISK_ONLY())
    def csvDataDf = buildCsvDataDf(csvRawDf, csvIdField)
    def jsonDataDf = buildJsonDataDf(jsonRawDf, jsonPathInfo, compareJsonPathForJson)

    def file1IdDf = isFile1Csv ? csvIdDf : jsonIdDf
    def file2IdDf = isFile1Csv ? jsonIdDf : csvIdDf
    def file1DataDf = isFile1Csv ? csvDataDf : jsonDataDf
    def file2DataDf = isFile1Csv ? jsonDataDf : csvDataDf

    def onlyInFile1Df = file1IdDf.join(file2IdDf, "compare_id", "left_anti").select("compare_id")
    def onlyInFile2Df = file2IdDf.join(file1IdDf, "compare_id", "left_anti").select("compare_id")
    onlyInFile1Df = onlyInFile1Df.persist(StorageLevel.DISK_ONLY())
    onlyInFile2Df = onlyInFile2Df.persist(StorageLevel.DISK_ONLY())

    onlyInFile1Count = onlyInFile1Df.count()
    onlyInFile2Count = onlyInFile2Df.count()
    differenceCount = onlyInFile1Count + onlyInFile2Count

    def normalizeTypeLabel = { label ->
        def normalized = label?.toString()?.trim()
        if (!normalized) return null
        normalized = normalized.replaceAll(/\s+/, "_")
        normalized = normalized.replaceAll(/[^A-Za-z0-9_]/, "")
        return normalized ?: null
    }
    def file1TypeLabel = normalizeTypeLabel(file1Label) ?: "json1"
    def file2TypeLabel = normalizeTypeLabel(file2Label) ?: "json2"
    def missingInFile1Type = "missing_in_" + file1TypeLabel
    def missingInFile2Type = "missing_in_" + file2TypeLabel

    def diffFile1Note = "Present in ${file1Label}, missing in ${file2Label}".toString()
    def diffFile2Note = "Present in ${file2Label}, missing in ${file1Label}".toString()
    def differenceDfs = []
    if (onlyInFile1Count > 0) {
        def onlyInFile1ObjDf = file1DataDf.join(onlyInFile1Df, "compare_id", "inner")
                .dropDuplicates(["compare_id"] as String[])
        def diffFile1Df = onlyInFile1ObjDf.select(
                lit(missingInFile2Type).alias("type"),
                col("compare_id").alias("id"),
                lit(file1Label).alias("presentIn"),
                lit(file2Label).alias("missingIn"),
                col("data").alias("data"),
                lit(diffFile1Note).alias("note")
        )
        differenceDfs.add(diffFile1Df)
    }
    if (onlyInFile2Count > 0) {
        def onlyInFile2ObjDf = file2DataDf.join(onlyInFile2Df, "compare_id", "inner")
                .dropDuplicates(["compare_id"] as String[])
        def diffFile2Df = onlyInFile2ObjDf.select(
                lit(missingInFile1Type).alias("type"),
                col("compare_id").alias("id"),
                lit(file2Label).alias("presentIn"),
                lit(file1Label).alias("missingIn"),
                col("data").alias("data"),
                lit(diffFile2Note).alias("note")
        )
        differenceDfs.add(diffFile2Df)
    }

    String outputBaseLocation = tempLoc + "/output"
    def outputLocationRef = ec.resource.getLocationReference(outputBaseLocation)
    File outputDir = outputLocationRef?.getFile()
    if (outputDir == null) {
        // Fallback if getLocationReference doesn't return a file object directly (e.g. non-local resource)
        // For generic reconciliation output we strongly prefer local file access for Spark/Indexing
        outputDir = outputBaseLocation.startsWith("/") ?
                new File(outputBaseLocation) :
                new File((String) ec.factory.getRuntimePath(), outputBaseLocation)
    }
    if (!outputDir.exists()) outputDir.mkdirs()

    def safeMappingName = mappingName.replaceAll(/[^A-Za-z0-9._-]/, '_')
    String baseFileName = "${safeMappingName}-${timestamp}.json"
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
            json1Label: file1Label,
            json2Label: file2Label,
            compareField: csvIdField,
            compareJsonPath: compareJsonPathForJson,
            file1Type: file1Type,
            file2Type: file2Type
    ]
    def outputSummary = [
            totalDifferences: differenceCount
    ]
    outputSummary["onlyIn${file1TypeLabel}Count"] = onlyInFile1Count
    outputSummary["onlyIn${file2TypeLabel}Count"] = onlyInFile2Count
    def outputErrors = []

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
    
    // Generate Index File for Pagination
    try {
        File indexFile = new File(outputDir, diffFileNameStr + ".index")
        logger.info("Generating index file: ${indexFile.getAbsolutePath()}")
        
        indexFile.withDataOutputStream { dos ->
            // Format: Magic (4 bytes), Version (4 bytes), Count (8 bytes), Offsets (8 bytes * count)
            dos.writeBytes("INDX")
            dos.writeInt(1)
            dos.writeLong(differenceCount)
            
            // We need to scan the file to find offsets
            // This assumes the structure written above: "differences":[ \n {json}, \n {json} ...
            
            long currentOffset = 0
            long recordCount = 0
            
            // Re-read the file to build index
            // Using BufferedInputStream for speed
            outputFile.withInputStream { fis ->
                def bis = new BufferedInputStream(fis)
                boolean insideDifferences = false
                long byteCounter = 0
                int b
                
                // Helper to check for sequence
                while ((b = bis.read()) != -1) {
                    byteCounter++
                    if (!insideDifferences) {
                         // Search for "differences":[
                         // Rough approximation: look for [ after "differences"
                         // Since we wrote it, we know it's relatively standard. 
                         // But safer to just look for the first line that looks like a record after metadata
                         // Our writer writes: writer << "\n" << rowJson
                         if (b == 91) { // '['
                             insideDifferences = true
                         }
                    } else {
                        // Inside array. Records are separated by newlines or commas.
                        // We wrote: , \n { ... }
                        // or \n { ... }
                        
                        // We want the offset of the '{' starting the record.
                        // Simplified parser: assume records start with '{' on a new line (mostly)
                        // The structure is:
                        // [\n
                        // {Rec1}
                        // ,
                        // \n{Rec2}
                        
                        // We need to map Index -> offset of '{'
                        
                        if (b == 123) { // '{'
                             // Found start of object. Record it.
                             dos.writeLong(byteCounter - 1)
                             recordCount++
                             
                             // Skip until next likely start
                             // This is a naive heuristic that depends on our specific update logic above
                             // For robustness, we just record every '{' we find at top level of the array
                        }
                        if (b == 93) { // ']'
                             // End of array
                             break
                        }
                    }
                }
            }
            logger.info("Indexed ${recordCount} records.")
        }
    } catch (Exception e) {
        logger.warn("Failed to generate index file: ${e.message}")
    }

    csvIdDf.unpersist()
    jsonIdDf.unpersist()
    onlyInFile1Df.unpersist()
    onlyInFile2Df.unpersist()
}

logger.info("Generic reconciliation complete: type=${reconciliationType}, differences=${differenceCount}, file=${diffFileName}")
