import org.slf4j.LoggerFactory
import groovy.json.JsonSlurper

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
    if (fileName.endsWith('.csv')) return 'CSV'
    if (fileName.endsWith('.json')) return 'JSON'
    
    // Fallback: peek at content
    try {
        def inputStream = fileItem.getInputStream()
        def firstBytes = new byte[500]
        def bytesRead = inputStream.read(firstBytes)
        inputStream.close()
        
        if (bytesRead > 0) {
            def content = new String(firstBytes, 0, bytesRead).trim()
            if (content.startsWith('{') || content.startsWith('[')) {
                return 'JSON'
            }
        }
    } catch (Exception e) {
        logger.warn("Could not peek at file content: ${e.message}")
    }
    
    // Default to CSV
    return 'CSV'
}

// Detect file types
file1Type = detectFileType(file1, file1SystemEnumId)
file2Type = detectFileType(file2, file2SystemEnumId)

logger.info("File type detection: file1=${file1Type}, file2=${file2Type}")

def reconType = "${file1Type}_${file2Type}"

// Save files to temp location
def tempLoc = ec.resource.properties['reconciliation.temp.location'] ?: 'runtime://tmp/reconciliation/generic'
def baseDirRef = ec.resource.getLocationReference(tempLoc)
if (baseDirRef == null) {
    throw new IllegalStateException("Unable to resolve generic reconciliation temp directory: ${tempLoc}")
}
def inputDirRef = baseDirRef.makeDirectory('input')
if (!inputDirRef.getExists()) {
    def inputDirFile = inputDirRef.getFile()
    if (inputDirFile != null && !inputDirFile.exists()) inputDirFile.mkdirs()
}

def timestamp = ec.l10n.format(ec.user.nowTimestamp, 'yyyyMMdd-HHmmssSSS')
def file1SafeName = file1.getName()?.replaceAll('[^A-Za-z0-9._-]', '_') ?: 'file1'
def file2SafeName = file2.getName()?.replaceAll('[^A-Za-z0-9._-]', '_') ?: 'file2'

def file1Ref = inputDirRef.makeFile(timestamp + '-file1-' + file1SafeName)
def file2Ref = inputDirRef.makeFile(timestamp + '-file2-' + file2SafeName)

def saveFileItem = { fileItem, targetRef ->
    File targetFile = targetRef.getFile()
    if (targetFile != null) {
        try {
            fileItem.write(targetFile)
            return
        } catch (Exception e) {
            logger.warn("Failed to write directly to file, fallback to stream: ${e.message}")
        }
    }
    targetRef.putStream(fileItem.getInputStream())
}

saveFileItem(file1, file1Ref)
saveFileItem(file2, file2Ref)

logger.info("Saved files: file1=${file1Ref.getLocation()}, file2=${file2Ref.getLocation()}")
def file1Location = file1Ref.getFile()?.getAbsolutePath() ?: file1Ref.getLocation()
def file2Location = file2Ref.getFile()?.getAbsolutePath() ?: file2Ref.getLocation()

// Route to appropriate reconciliation service
def result

if (reconType == "CSV_CSV") {
    reconciliationType = "CSV"
    result = ec.service.sync()
            .name("ReconciliationCsvServices.compare#CsvFiles")
            .parameters([
                csv1Location: file1Location,
                csv2Location: file2Location,
                reconciliationMappingId: reconciliationMappingId,
                csv1SystemEnumId: file1SystemEnumId,
                csv2SystemEnumId: file2SystemEnumId,
                idField: file1IdField, // Mapping override if needed
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
    reconciliationType = "JSON"
    
    // Schema is required for JSON
    def schemaFileName1 = file1Schema
    def schemaFileName2 = file2Schema
    
    // JSON Paths
    def compareJsonPath1 = file1IdField?.startsWith('$') ? file1IdField : "\$[*].${file1IdField}"
    def compareJsonPath2 = file2IdField?.startsWith('$') ? file2IdField : "\$[*].${file2IdField}"

    result = ec.service.sync()
            .name("ReconciliationJsonServices.reconcile#JsonFiles")
            .parameters([
                json1Location: file1Location,
                json2Location: file2Location,
                schemaFileName: schemaFileName1 ?: '',  // Can be empty if not validating
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
    logger.info("Mixed file types detected: ${reconType}. Delegating to MixedReconciliation service.")
    
    result = ec.service.sync()
            .name("ReconciliationMixedServices.reconcile#MixedFiles")
            .parameters([
                file1Location: file1Location,
                file2Location: file2Location,
                file1Type: file1Type,
                file2Type: file2Type,
                file1Label: file1Label,
                file2Label: file2Label,
                
                // Pass mapping configurations
                csvIdField: (file1Type == 'CSV' ? file1IdField : file2IdField),
                jsonIdField: (file1Type == 'JSON' ? file1IdField : file2IdField),
                jsonSchemaFileName: (file1Type == 'JSON' ? file1Schema : file2Schema),
                
                hasHeader: hasHeader,
                outputLocation: tempLoc + '/output',
                outputFileName: "mixed-${timestamp}.json",
                sparkMaster: sparkMaster ?: (ec.resource.properties['spark.master'] ?: "local[*]"),
                sparkAppName: sparkAppName
            ])
            .call()
            
    diffLocation = result.diffLocation
    diffFileName = result.diffFileName
    onlyInFile1Count = result.onlyInFile1Count
    onlyInFile2Count = result.onlyInFile2Count
    differenceCount = result.differenceCount
}

logger.info("Generic reconciliation complete: type=${reconciliationType}, differences=${differenceCount}, file=${diffFileName}")
