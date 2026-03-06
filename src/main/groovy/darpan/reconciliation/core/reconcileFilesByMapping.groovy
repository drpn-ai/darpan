import org.slf4j.LoggerFactory

def logger = LoggerFactory.getLogger("darpan.reconciliation.core.FileRoutingReconciliation")

def normalize = { it?.toString()?.trim() }
def resolveFileTypeCode = { String enumId ->
    def normalized = normalize(enumId)
    if (!normalized) return null
    def enumValue = ec.entity.find("moqui.basic.Enumeration")
            .condition("enumId", normalized)
            .useCache(true)
            .one()
    return normalize(enumValue?.enumCode)
}
def detectFileTypeFromName = { String name ->
    def lower = name?.toLowerCase()
    if (lower?.endsWith(".csv")) return "CSV"
    if (lower?.endsWith(".json")) return "JSON"
    return null
}
def splitIdExpression = { String expr ->
    def raw = normalize(expr)
    if (!raw) return [idExpr: null, normalizer: null]
    int separatorIndex = raw.indexOf("|")
    if (separatorIndex < 0) return [idExpr: raw, normalizer: null]
    String idExpr = normalize(raw.substring(0, separatorIndex))
    String idNormalizer = normalize(raw.substring(separatorIndex + 1))
    return [idExpr: idExpr, normalizer: idNormalizer]
}
def normalizeIdNormalizer = { String rawNormalizer ->
    def code = normalize(rawNormalizer)
    if (!code) return null
    def normalized = code.replace("-", "_").replace(" ", "_").toUpperCase()
    switch (normalized) {
        case "SHOPIFY_GID_TAIL":
        case "SHOPIFY_GID":
        case "SHOPIFY_GID_NUMERIC":
        case "GID_TAIL":
            return "SHOPIFY_GID_TAIL"
        case "TRAILING_DIGITS":
        case "DIGITS":
            return "TRAILING_DIGITS"
        default:
            throw new IllegalArgumentException("Unsupported ID normalizer '${rawNormalizer}'. Supported values: SHOPIFY_GID_TAIL, TRAILING_DIGITS")
    }
}
def normalizeJsonPath = { String idExpr ->
    def raw = normalize(idExpr)
    if (!raw) return "\$.id"
    if (raw.startsWith("\$")) return raw
    if (raw.contains("[") || raw.contains(".")) return raw
    return "\$[*].${raw}"
}
def safeNameFromLocation = { String location, String fallback ->
    def loc = normalize(location) ?: ""
    def parts = loc.tokenize("/\\")
    return parts ? parts[-1] : (fallback ?: "file")
}
def resolveEnumLabel = { String enumId, String fallback ->
    def normalized = normalize(enumId)
    if (!normalized) return fallback
    def enumValue = ec.entity.find("moqui.basic.Enumeration")
            .condition("enumId", normalized)
            .useCache(true)
            .one()
    def code = normalize(enumValue?.enumCode)
    if (code) return code
    def description = normalize(enumValue?.description)
    if (description) return description
    return normalized
}

if (!reconciliationMappingId) {
    throw new IllegalArgumentException("reconciliationMappingId is required")
}
if (!file1Location || !file2Location) {
    throw new IllegalArgumentException("file1Location and file2Location are required")
}
if (!file1SystemEnumId || !file2SystemEnumId) {
    throw new IllegalArgumentException("file1SystemEnumId and file2SystemEnumId are required")
}

List<String> processingWarnings = (processingWarnings ?: []) as List<String>

def resolveIdFieldForCompare = { Map memberConfig, String systemEnumId ->
    def parsedExpr = splitIdExpression(memberConfig.idFieldExpression ?: memberConfig.systemFieldName ?: "id")
    def inlineNormalizer = normalizeIdNormalizer(parsedExpr.normalizer)
    def memberNormalizer = normalizeIdNormalizer(memberConfig.idValueNormalizer)
    def finalNormalizer = memberNormalizer ?: inlineNormalizer
    if (memberNormalizer && inlineNormalizer && memberNormalizer != inlineNormalizer) {
        processingWarnings.add("Mapping member normalizer ${memberNormalizer} overrides inline normalizer ${inlineNormalizer} for system ${systemEnumId}")
    }
    def baseExpr = parsedExpr.idExpr ?: "id"
    return finalNormalizer ? "${baseExpr}|${finalNormalizer}" : baseExpr
}

def mappingMembers = ec.entity.find("darpan.mapping.ReconciliationMappingMember")
        .condition("reconciliationMappingId", reconciliationMappingId)
        .useCache(true)
        .list()
if (!mappingMembers) {
    throw new IllegalArgumentException("No mapping members found for mapping ${reconciliationMappingId}")
}

def configBySystem = [:]
mappingMembers.each { member ->
    configBySystem[normalize(member.systemEnumId)] = [
            fileTypeEnumId   : member.fileTypeEnumId,
            schemaFileName   : member.schemaFileName,
            idFieldExpression: member.idFieldExpression ?: member.systemFieldName,
            idValueNormalizer: member.idValueNormalizer,
            systemFieldName  : member.systemFieldName
    ]
}

def file1Key = normalize(file1SystemEnumId)
def file2Key = normalize(file2SystemEnumId)

if (!configBySystem[file1Key]) {
    throw new IllegalArgumentException("Mapping ${reconciliationMappingId} does not include system ${file1SystemEnumId}")
}
if (!configBySystem[file2Key]) {
    throw new IllegalArgumentException("Mapping ${reconciliationMappingId} does not include system ${file2SystemEnumId}")
}

// Apply overrides when provided
if (file1FileTypeEnumId) configBySystem[file1Key].fileTypeEnumId = file1FileTypeEnumId
if (file2FileTypeEnumId) configBySystem[file2Key].fileTypeEnumId = file2FileTypeEnumId
if (file1SchemaFileName) configBySystem[file1Key].schemaFileName = file1SchemaFileName
if (file2SchemaFileName) configBySystem[file2Key].schemaFileName = file2SchemaFileName

def file1SafeName = normalize(file1Name) ?: safeNameFromLocation(file1Location, "file1")
def file2SafeName = normalize(file2Name) ?: safeNameFromLocation(file2Location, "file2")

def file1Config = configBySystem[file1Key] ?: [:]
def file2Config = configBySystem[file2Key] ?: [:]

def file1Type = resolveFileTypeCode(file1Config.fileTypeEnumId) ?: detectFileTypeFromName(file1SafeName)
def file2Type = resolveFileTypeCode(file2Config.fileTypeEnumId) ?: detectFileTypeFromName(file2SafeName)
if (!file1Type) {
    processingWarnings.add("File type auto-detected as CSV for ${file1SafeName} because mapping ${reconciliationMappingId} has no fileTypeEnumId")
    file1Type = "CSV"
}
if (!file2Type) {
    processingWarnings.add("File type auto-detected as CSV for ${file2SafeName} because mapping ${reconciliationMappingId} has no fileTypeEnumId")
    file2Type = "CSV"
}

file1Type = file1Type.toUpperCase()
file2Type = file2Type.toUpperCase()

def file1IdField = resolveIdFieldForCompare(file1Config, file1SystemEnumId)
def file2IdField = resolveIdFieldForCompare(file2Config, file2SystemEnumId)
def file1Schema = file1Config.schemaFileName
def file2Schema = file2Config.schemaFileName

def file1Label = normalize(file1Label) ?: resolveEnumLabel(file1SystemEnumId, "File 1")
def file2Label = normalize(file2Label) ?: resolveEnumLabel(file2SystemEnumId, "File 2")

def reconType = "${file1Type}_${file2Type}"
reconciliationType = (reconType == "CSV_CSV") ? "CSV" : (reconType == "JSON_JSON" ? "JSON" : "MIXED")

def outputBase = outputLocation ?: "runtime://tmp/reconciliation/router/output"
def sparkMasterToUse = sparkMaster ?: (ec.resource.properties['spark.master'] ?: "local[*]")
def sparkAppNameToUse = sparkAppName ?: "ReconciliationRouter"
validationErrors = []

logger.info("Routing reconciliation: type=${reconciliationType} file1=${file1SafeName}(${file1Type}) file2=${file2SafeName}(${file2Type}) mapping=${reconciliationMappingId}")

def result = ec.service.sync()
        .name("reconciliation.ReconciliationCoreServices.reconcile#UnifiedFiles")
        .parameters([
                file1Location         : file1Location,
                file2Location         : file2Location,
                file1Type             : file1Type,
                file2Type             : file2Type,
                file1IdField          : file1IdField,
                file2IdField          : file2IdField,
                file1SchemaFileName   : file1Schema,
                file2SchemaFileName   : file2Schema,
                file1Label            : file1Label,
                file2Label            : file2Label,
                hasHeader             : hasHeader,
                outputLocation        : outputBase,
                reconciliationMappingId: reconciliationMappingId,
                reconciliationMappingName: ec.entity.find("darpan.mapping.ReconciliationMapping").condition("reconciliationMappingId", reconciliationMappingId).useCache(true).one()?.mappingName,
                sparkMaster           : sparkMasterToUse,
                sparkAppName          : sparkAppNameToUse,
                processingWarnings    : processingWarnings
        ])
        .call()

diffLocation = result.diffLocation
diffFileName = result.diffFileName
differenceCount = result.differenceCount
onlyInFile1Count = result.onlyInFile1Count
onlyInFile2Count = result.onlyInFile2Count
reconciliationType = result.reconciliationType ?: reconciliationType
validationErrors = result.validationErrors ?: []
processingWarnings = result.processingWarnings ?: processingWarnings

context.file1Type = file1Type
context.file2Type = file2Type
context.reconciliationType = reconciliationType
context.processingWarnings = processingWarnings
context.validationErrors = validationErrors ?: []

logger.info("Reconciliation routed: type=${reconciliationType} diff=${diffFileName} differences=${differenceCount}")
