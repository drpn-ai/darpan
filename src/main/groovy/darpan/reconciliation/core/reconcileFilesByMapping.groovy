import darpan.common.DarpanEntityConstants
import darpan.facade.common.TenantScopedFinder
import darpan.facade.reconciliation.ReconciliationMappingSupport
import darpan.reconciliation.core.ReconciliationServices
import org.slf4j.LoggerFactory

import static darpan.common.ValueSupport.normalize

def logger = LoggerFactory.getLogger("darpan.reconciliation.core.FileRoutingReconciliation")

def resolveFileTypeCode = { String enumId ->
    def normalized = normalize(enumId)
    if (!normalized) return null
    def enumValue = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.basic.Enumeration",
            "framework reference data: file type enumeration lookup")
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
def safeNameFromLocation = { String location, String fallback ->
    def loc = normalize(location) ?: ""
    def parts = loc.tokenize("/\\")
    return parts ? parts[-1] : (fallback ?: "file")
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
    // The shared parser and normalizer list: the copies here rejected CASE_FOLD, which every other path accepts.
    def parsedExpr = ReconciliationServices.splitIdExpression(memberConfig.idFieldExpression ?: memberConfig.systemFieldName ?: "id")
    def inlineNormalizer = ReconciliationServices.resolveIdNormalizer(parsedExpr.normalizer)
    def memberNormalizer = ReconciliationServices.resolveIdNormalizer(memberConfig.idValueNormalizer)
    def finalNormalizer = memberNormalizer ?: inlineNormalizer
    if (memberNormalizer && inlineNormalizer && memberNormalizer != inlineNormalizer) {
        processingWarnings.add("Mapping member normalizer ${memberNormalizer} overrides inline normalizer ${inlineNormalizer} for system ${systemEnumId}")
    }
    def baseExpr = parsedExpr.idExpr ?: "id"
    return finalNormalizer ? "${baseExpr}|${finalNormalizer}" : baseExpr
}

// ReconciliationMappingMember has no companyUserGroupId — gate via parent ReconciliationMapping (directly-owned).
def mappingMemberFinder = TenantScopedFinder.findTenantScopedChildren(
        ec, DarpanEntityConstants.RECONCILIATION_MAPPING_MEMBER,
        DarpanEntityConstants.RECONCILIATION_MAPPING, "reconciliationMappingId", reconciliationMappingId,
        "reconciliationMappingId")
if (mappingMemberFinder == null) {
    throw new IllegalArgumentException("No mapping members found for mapping ${reconciliationMappingId}")
}
def mappingMembers = mappingMemberFinder.useCache(true).list()
if (!mappingMembers) {
    throw new IllegalArgumentException("No mapping members found for mapping ${reconciliationMappingId}")
}

List<String> persistedReadinessIssues = ReconciliationMappingSupport.collectReadinessIssues(ec, mappingMembers)
if (!persistedReadinessIssues.isEmpty()) {
    throw new IllegalArgumentException("Mapping ${reconciliationMappingId} is not runnable until every system member references a saved schema. ${persistedReadinessIssues.join(' ')}")
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

List<Map<String, Object>> effectiveMembers = configBySystem.collect { String systemEnumId, Map memberConfig ->
    [
            systemEnumId     : systemEnumId,
            fileTypeEnumId   : memberConfig.fileTypeEnumId,
            schemaFileName   : memberConfig.schemaFileName,
            idFieldExpression: memberConfig.idFieldExpression,
            systemFieldName  : memberConfig.systemFieldName
    ]
}
List<String> effectiveReadinessIssues = ReconciliationMappingSupport.collectReadinessIssues(ec, effectiveMembers)
if (!effectiveReadinessIssues.isEmpty()) {
    throw new IllegalArgumentException("Effective mapping configuration for ${reconciliationMappingId} is not runnable. ${effectiveReadinessIssues.join(' ')}")
}

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

def file1Label = normalize(file1Label) ?: ReconciliationServices.resolveEnumLabel(ec, file1SystemEnumId, "File 1")
def file2Label = normalize(file2Label) ?: ReconciliationServices.resolveEnumLabel(ec, file2SystemEnumId, "File 2")
def mappingRecord = TenantScopedFinder.findTenantScopedByIdQuiet(
        ec, DarpanEntityConstants.RECONCILIATION_MAPPING, "reconciliationMappingId", reconciliationMappingId)

reconciliationType = ReconciliationServices.determineReconciliationType(file1Type, file2Type)

def outputBase = outputLocation ?: "runtime://tmp/reconciliation/router/output"
def outputFileNameValue = normalize(outputFileName)
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
                outputFileName        : outputFileNameValue,
                reconciliationMappingId: reconciliationMappingId,
                reconciliationMappingName: mappingRecord?.mappingName,
                companyUserGroupId    : mappingRecord?.companyUserGroupId,
                sparkAppName          : sparkAppNameToUse,
                processingWarnings    : processingWarnings
        ])
        .call()

diffLocation = result.diffLocation
diffFileName = result.diffFileName
differenceCount = result.differenceCount
onlyInFile1Count = result.onlyInFile1Count
onlyInFile2Count = result.onlyInFile2Count
duplicateDifferenceCount = result.duplicateDifferenceCount
matchedCount = result.matchedCount
reconciliationType = result.reconciliationType ?: reconciliationType
validationErrors = result.validationErrors ?: []
processingWarnings = result.processingWarnings ?: processingWarnings

context.file1Type = file1Type
context.file2Type = file2Type
context.reconciliationType = reconciliationType
context.processingWarnings = processingWarnings
context.validationErrors = validationErrors ?: []

logger.info("Reconciliation routed: type=${reconciliationType} diff=${diffFileName} differences=${differenceCount}")
