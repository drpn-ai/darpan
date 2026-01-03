import org.slf4j.LoggerFactory
import groovy.json.JsonSlurper
import com.jayway.jsonpath.JsonPath

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
def file1Label = system1Enum?.description ?: file1SystemEnumId
def file2Label = system2Enum?.description ?: file2SystemEnumId

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
def baseDirRef = ec.resource.getLocationReference('runtime://tmp/reconciliation/generic')
if (baseDirRef == null) {
    throw new IllegalStateException("Unable to resolve generic reconciliation temp directory")
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

file1Ref.putStream(file1.getInputStream())
file2Ref.putStream(file2.getInputStream())
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
                outputLocation: 'runtime://tmp/reconciliation/generic/output',
                sparkMaster: sparkMaster,
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
                outputLocation: 'runtime://tmp/reconciliation/generic/output',
                sparkMaster: sparkMaster,
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
    processingWarnings.add([warningMessage: "Mixed file types (CSV + JSON). Converting to CSV for comparison."])
    
    // Strategy: Convert JSON to CSV (simpler approach)
    // Identify which is CSV and which is JSON
    def csvLocation
    def csvSystemEnumId
    def jsonLocation
    def jsonLabel
    def isFile1Csv = (file1Type == 'CSV')
    
    if (isFile1Csv) {
        csvLocation = file1Ref.getLocation()
        csvSystemEnumId = file1SystemEnumId
        jsonLocation = file2Ref.getFile()?.getAbsolutePath() ?: file2Ref.getLocation()
        jsonLabel = file2Label ?: "File 2 (JSON)"
    } else {
        csvLocation = file2Ref.getLocation()
        csvSystemEnumId = file2SystemEnumId
        jsonLocation = file1Ref.getFile()?.getAbsolutePath() ?: file1Ref.getLocation()
        jsonLabel = file1Label ?: "File 1 (JSON)"
    }
    
    // Extract IDs from JSON and create temp CSV
    def jsonIdField = isFile1Csv ? file2IdField : file1IdField
    def compareJsonPathForJson = jsonIdField?.startsWith('$') ? jsonIdField : "\$.${jsonIdField}"
    if (!compareJsonPathForJson) {
        compareJsonPathForJson = '$.id'
        processingWarnings.add([warningMessage: "No JSONPath specified for JSON file, using default: \$.id"])
    }
    
    logger.info("Converting JSON to CSV using JSONPath: ${compareJsonPathForJson}")
    
    // Use a simple groovy script to extract IDs from JSON and create CSV
    def tempCsvRef = inputDirRef.makeFile(timestamp + '-converted-from-json.csv')
    def slurper = new JsonSlurper()
    def jsonFilePath = jsonLocation?.startsWith('file:') ? jsonLocation.replaceFirst('^file:', '') : jsonLocation
    def jsonFile = new File(jsonFilePath)
    def jsonData = jsonFile.withInputStream { stream -> slurper.parse(stream) }
    
    // Extract IDs using JSONPath
    def ids = JsonPath.read(jsonData, compareJsonPathForJson)
    def idList = []
    if (ids instanceof List) {
        idList = ids.collect { it?.toString() }.findAll { it != null && it.trim() }
    } else if (ids != null) {
        idList = [ids.toString()]
    }
    
    logger.info("Extracted ${idList.size()} IDs from JSON file")
    
    // Write to temp CSV
    tempCsvRef.getFile().withWriter('UTF-8') { writer ->
        writer.write("${idField ?: 'id'}\\n")
        idList.each { id -> writer.write("${id}\\n") }
    }
    
    logger.info("Created temporary CSV from JSON: ${tempCsvRef.getLocation()}")
    
    // Now do CSV comparison
    def csv1Loc, csv2Loc, csv1Sys, csv2Sys
    if (isFile1Csv) {
        csv1Loc = csvLocation
        csv2Loc = tempCsvRef.getLocation()
        csv1Sys = csvSystemEnumId
        csv2Sys = "JSON_CONVERTED"
    } else {
        csv1Loc = tempCsvRef.getLocation()
        csv2Loc = csvLocation
        csv1Sys = "JSON_CONVERTED"
        csv2Sys = csvSystemEnumId
    }
    
    result = ec.service.sync()
            .name("ReconciliationCsvServices.compare#CsvFiles")
            .parameters([
                csv1Location: csv1Loc,
                csv2Location: csv2Loc,
                csv1SystemEnumId: csv1Sys,
                csv2SystemEnumId: csv2Sys,
                idField: idField,
                hasHeader: hasHeader,
                outputLocation: 'runtime://tmp/reconciliation/generic/output',
                outputFileName: "mixed-reconciliation-${timestamp}.csv",
                sparkMaster: sparkMaster,
                sparkAppName: sparkAppName
            ])
            .call()
    
    diffLocation = result.diffLocation
    diffFileName = result.diffFileName
    onlyInFile1Count = result.onlyInCsv1Count
    onlyInFile2Count = result.onlyInCsv2Count
    differenceCount = result.diffRowCount
}

logger.info("Generic reconciliation complete: type=${reconciliationType}, differences=${differenceCount}, file=${diffFileName}")
