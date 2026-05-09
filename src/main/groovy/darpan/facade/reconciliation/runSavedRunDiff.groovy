import darpan.facade.common.DataManagerSupport
import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.ReconciliationApiWindowSupport
import darpan.facade.reconciliation.ReconciliationOutputSupport
import darpan.facade.reconciliation.ReconciliationSavedRunSupport
import darpan.reconciliation.core.ReconciliationServices
import darpan.reconciliation.notification.TenantNotificationSupport

import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDateTime

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeInt
import static darpan.common.ValueSupport.normalizeUpper

def toTimestampValue = { Object rawValue ->
    if (rawValue == null) return null
    if (rawValue instanceof Timestamp) return rawValue
    if (rawValue instanceof Date) return new Timestamp(rawValue.time)

    String normalized = normalize(rawValue)
    if (!normalized) return null
    try {
        return Timestamp.from(Instant.parse(normalized))
    } catch (Exception ignored) {
    }
    try {
        return Timestamp.valueOf(normalized)
    } catch (Exception ignored) {
    }
    try {
        return Timestamp.valueOf(normalized.replace("T", " "))
    } catch (Exception ignored) {
    }
    try {
        return Timestamp.valueOf(LocalDateTime.parse(normalized))
    } catch (Exception ignored) {
    }
    throw new IllegalArgumentException("Invalid timestamp '${normalized}'")
}

String savedRunIdValue = normalize(savedRunId)
String inputFile1Name = file1Name != null ? ReconciliationOutputSupport.sanitizeUploadFileName(file1Name as String, "file1") : null
String inputFile2Name = file2Name != null ? ReconciliationOutputSupport.sanitizeUploadFileName(file2Name as String, "file2") : null
String file1TextValue = file1Text?.toString()
String file2TextValue = file2Text?.toString()
String requestedFile1SystemEnumId = normalize(file1SystemEnumId)
String requestedFile2SystemEnumId = normalize(file2SystemEnumId)
boolean hasHeaderValue = hasHeader == null ? true :
        (hasHeader instanceof Boolean ? hasHeader : ["Y", "YES", "TRUE", "1", "ON"].contains(normalizeUpper(hasHeader)))
String windowStartLocalDateValue = normalize(windowStartLocalDate)
String windowEndLocalDateValue = normalize(windowEndLocalDate)
Timestamp requestedWindowStartDateValue = null
Timestamp requestedWindowEndDateValue = null
Timestamp windowStartDateValue = null
Timestamp windowEndDateValue = null

try {
    requestedWindowStartDateValue = toTimestampValue(windowStartDate)
    requestedWindowEndDateValue = toTimestampValue(windowEndDate)
    windowStartDateValue = requestedWindowStartDateValue
    windowEndDateValue = requestedWindowEndDateValue
    if (windowStartDateValue && windowEndDateValue) {
        Map<String, Object> normalizedApiWindow = ReconciliationApiWindowSupport.normalizeSavedRunApiWindow(
                ec,
                windowStartDateValue,
                windowEndDateValue,
                windowStartLocalDateValue,
                windowEndLocalDateValue
        )
        windowStartDateValue = (Timestamp) normalizedApiWindow.windowStartDate
        windowEndDateValue = (Timestamp) normalizedApiWindow.windowEndDate
    }
} catch (IllegalArgumentException e) {
    ec.message.addError(e.message)
}

if (!savedRunIdValue) ec.message.addError("savedRunId is required")
if ((requestedFile1SystemEnumId && !requestedFile2SystemEnumId) || (!requestedFile1SystemEnumId && requestedFile2SystemEnumId)) {
    ec.message.addError("file1SystemEnumId and file2SystemEnumId must be provided together when overriding saved run defaults.")
}

if (!ec.message.hasError()) {
    TenantAccessSupport.requireActiveTenantWriteAccess(ec, "Your active tenant only has view access for reconciliation runs.")
}

def findEnum = { String enumId ->
    if (!enumId) return null
    return ec.entity.find("moqui.basic.Enumeration")
            .condition("enumId", enumId)
            .useCache(true)
            .one()
}
def enumLabel = { String enumId ->
    return FacadeSupport.enumLabel(findEnum(enumId) ?: [enumId: enumId])
}
def requireUploadInput = { String filePrefix, String inputName, String inputText ->
    if (!inputName) ec.message.addError("${filePrefix}Name is required")
    if (!inputText) ec.message.addError("${filePrefix}Text is required")
}
def sideToken = { String fileSide ->
    fileSide == ReconciliationSavedRunSupport.FILE_SIDE_1 ? "file1" : "file2"
}
def isApiSource = { Object source ->
    normalize(source?.sourceTypeEnumId) == ReconciliationSavedRunSupport.SOURCE_TYPE_API
}
def sourceLabel = { Object source, String fallback ->
    enumLabel(normalize(source?.systemEnumId) ?: fallback)
}
def runInternalService = { String serviceName, Map params ->
    def call = ec.service.sync()
            .name(serviceName)
            .parameters((params ?: [:]).findAll { entry -> entry.value != null })
    if (call?.metaClass?.respondsTo(call, "disableAuthz")) call = call.disableAuthz()
    return (call.call() ?: [:]) as Map
}
def resolveOutputFile = { Map serviceResult ->
    File outputFile = null
    String diffLocationValue = normalize(serviceResult?.diffLocation)
    if (diffLocationValue) {
        outputFile = diffLocationValue.startsWith("/") ?
                new File(diffLocationValue) :
                ec.resource.getLocationReference(diffLocationValue)?.getFile()
    }
    if ((outputFile == null || !outputFile.exists()) && serviceResult?.diffFileName) {
        String diffFileNameValue = normalize(serviceResult.diffFileName)
        if (diffFileNameValue?.contains("/")) {
            outputFile = DataManagerSupport.resolveDataManagerFile(ec, diffFileNameValue, false)
        }
        if (outputFile == null || !outputFile.exists()) {
            String scopedOutputLocation = TenantAccessSupport.resolveGenericOutputLocation(ec)
            File outputDir = ec.resource.getLocationReference(scopedOutputLocation)?.getFile()
            if (outputDir != null) outputFile = new File(outputDir, diffFileNameValue)
        }
    }
    return outputFile
}
def buildGeneratedOutputDescriptor = { Map serviceResult ->
    File outputFile = resolveOutputFile(serviceResult)
    long sizeBytes = outputFile?.exists() ? outputFile.length() : 0L
    Timestamp createdDate = outputFile?.exists() ?
            new Timestamp(outputFile.lastModified()) :
            (Timestamp) ec.user.nowTimestamp
    Map<String, Object> outputDocument = [:]
    if (outputFile?.exists() && ReconciliationOutputSupport.sourceFormatForFile(outputFile.name) == "json") {
        try {
            outputDocument = ReconciliationOutputSupport.parseGeneratedOutputText(outputFile.getText("UTF-8"))
        } catch (Exception ignored) {
            outputDocument = [:]
        }
    }
    Map descriptor = ReconciliationOutputSupport.buildGeneratedOutputDescriptor(
            serviceResult?.diffFileName as String,
            outputDocument,
            sizeBytes,
            createdDate
    )
    if (serviceResult?.reconciliationRunResultId) {
        descriptor.reconciliationRunResultId = serviceResult.reconciliationRunResultId
    }
    return descriptor
}
def persistRunResult = { Map<String, Object> fields ->
    String resultPath = DataManagerSupport.normalizeRelativePath(fields.resultDataManagerPath)
    String existingRunResultId = normalize(fields.reconciliationRunResultId)
    if (!resultPath && !existingRunResultId && !fields.statusEnumId) return null

    return ec.transaction.runUseOrBegin(30, "Error saving reconciliation run result", {
        def runResultValue = existingRunResultId ?
                ec.entity.find("darpan.reconciliation.ReconciliationRunResult")
                        .condition("reconciliationRunResultId", existingRunResultId)
                        .disableAuthz()
                        .useCache(false)
                        .one() :
                null
        boolean creating = runResultValue == null
        if (creating) runResultValue = ec.entity.makeValue("darpan.reconciliation.ReconciliationRunResult")

        [
                savedRunId             : normalize(fields.savedRunId),
                savedRunType           : normalize(fields.savedRunType),
                reconciliationRunId     : normalize(fields.reconciliationRunId),
                reconciliationMappingId : normalize(fields.reconciliationMappingId),
                ruleSetId              : normalize(fields.ruleSetId),
                compareScopeId         : normalize(fields.compareScopeId),
                companyUserGroupId     : normalize(fields.companyUserGroupId) ?: TenantAccessSupport.currentActiveTenantUserGroupId(ec),
                createdByUserId        : TenantAccessSupport.currentUserId(ec),
                file1Name              : normalize(fields.file1Name),
                file1DataManagerPath   : DataManagerSupport.normalizeRelativePath(fields.file1DataManagerPath),
                file2Name              : normalize(fields.file2Name),
                file2DataManagerPath   : DataManagerSupport.normalizeRelativePath(fields.file2DataManagerPath),
                resultDataManagerPath  : resultPath,
                statusEnumId           : normalize(fields.statusEnumId),
                reconciliationType     : normalize(fields.reconciliationType),
                differenceCount        : fields.differenceCount,
                onlyInFile1Count       : fields.onlyInFile1Count,
                onlyInFile2Count       : fields.onlyInFile2Count,
                startedDate            : fields.startedDate,
                completedDate          : fields.completedDate,
        ].each { entry ->
            if (entry.value != null) runResultValue.set(entry.key as String, entry.value)
        }
        if (creating) {
            runResultValue.createdDate = fields.createdDate ?: ec.user.nowTimestamp
            runResultValue.lastUpdatedDate = ec.user.nowTimestamp
            runResultValue.setSequencedIdPrimary()
            runResultValue.create()
        } else {
            runResultValue.lastUpdatedDate = ec.user.nowTimestamp
            runResultValue.update()
        }
        return runResultValue.reconciliationRunResultId
    })
}
def buildRunArtifactContext = { String runId ->
    String runToken = DataManagerSupport.safeToken(runId, "run")
    String timestamp = DataManagerSupport.formatRunTimestamp(ec)
    String runArtifactLocation = DataManagerSupport.resolveReconciliationRunLocation(ec, runToken, timestamp)
    File runArtifactDir = DataManagerSupport.resolveDirectoryFile(ec, runArtifactLocation, true)
    if (runArtifactDir == null) {
        throw new IllegalStateException("Unable to resolve reconciliation run data-manager directory: ${runArtifactLocation}")
    }
    return [runToken: runToken, location: runArtifactLocation]
}
def sourceResultForLocation = { Object source, String fileSide, String fileNameValue, String location, Integer recordCount = null ->
    def locationRef = ec.resource.getLocationReference(location)
    File locationFile = locationRef?.getFile()
    return [
            fileLocation   : locationFile?.getAbsolutePath() ?: location,
            dataManagerPath: DataManagerSupport.relativeDataManagerPath(ec, locationFile),
            fileName       : fileNameValue,
            fileTypeEnumId : normalize(source?.fileTypeEnumId) ?: "DftJson",
            schemaFileName : normalize(source?.schemaFileName),
            recordCount    : recordCount,
            fileSide       : fileSide,
    ].findAll { entry -> entry.value != null } as Map<String, Object>
}
def stageTextInput = { Object source, String fileSide, String inputName, String inputText, Map artifactContext ->
    String token = sideToken(fileSide)
    String safeName = ReconciliationOutputSupport.sanitizeUploadFileName(inputName, token)
    String location = DataManagerSupport.childLocation(
            artifactContext.location as String,
            DataManagerSupport.runArtifactFileName(artifactContext.runToken, token, safeName)
    )
    DataManagerSupport.writeText(ec, location, inputText)
    return sourceResultForLocation(source, fileSide, safeName, location, null)
}
def formatApiWindow = { Timestamp timestamp ->
    timestamp?.toInstant()?.toString()
}
Map<String, Object> tenantApiWindow = null
def resolveTenantApiWindow = {
    ReconciliationApiWindowSupport.preserveExactWindow(
            windowStartDateValue,
            windowEndDateValue,
            TenantAccessSupport.resolveActiveTenantTimeZone(ec)
    )
}
def extractHotWaxSource = { Object source, String fileSide, String label, Map artifactContext ->
    String configId = normalize(source?.sourceConfigId)
    if (!configId) {
        ec.message.addError("${label} API source requires a HotWax OMS source config.")
        return [:]
    }
    Map<String, Object> sourceApiWindow = tenantApiWindow ?: resolveTenantApiWindow()
    if (ec.message.hasError()) return [:]
    Timestamp sourceWindowStartDate = (Timestamp) sourceApiWindow.windowStartDate
    Timestamp sourceWindowEndDate = (Timestamp) sourceApiWindow.windowEndDate

    Map extraction = runInternalService("reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders", [
            omsRestSourceConfigId: configId,
            windowStart          : formatApiWindow(sourceWindowStartDate),
            windowEnd            : formatApiWindow(sourceWindowEndDate),
            outputLocation       : DataManagerSupport.childLocation(artifactContext.location as String, "${sideToken(fileSide)}-api"),
    ])
    ((List) (extraction.errors ?: [])).each { Object error -> ec.message.addError("${label}: ${error}") }
    if (ec.message.hasError()) return [:]

    String extractedLocation = normalize(extraction.fileLocation)
    if (!extractedLocation) {
        ec.message.addError("${label} API did not return an output file for the selected time period.")
        return [:]
    }
    return sourceResultForLocation(source, fileSide, normalize(extraction.fileName) ?: "${sideToken(fileSide)}-api.json",
            extractedLocation, normalizeInt(extraction.recordCount, null))
}
def extractShopifySource = { Object source, String fileSide, String label, Map artifactContext ->
    String configId = normalize(source?.sourceConfigId)
    if (!configId) {
        ec.message.addError("${label} API source requires a Shopify auth config.")
        return [:]
    }

    Map<String, Object> sourceApiWindow = tenantApiWindow ?: resolveTenantApiWindow()
    if (ec.message.hasError()) return [:]
    Timestamp sourceWindowStartDate = (Timestamp) sourceApiWindow.windowStartDate
    Timestamp sourceWindowEndDate = (Timestamp) sourceApiWindow.windowEndDate

    String token = sideToken(fileSide)
    String fileNameValue = ReconciliationOutputSupport.sanitizeUploadFileName("${label}-orders-api.json", "${token}-api.json")
    Map extraction = runInternalService("reconciliation.ShopifyOrderExtractionServices.extract#ShopifyOrders", [
            shopifyAuthConfigId   : configId,
            windowStart           : formatApiWindow(sourceWindowStartDate),
            windowEnd             : formatApiWindow(sourceWindowEndDate),
            preserveWindowInstants: true,
            outputLocation        : DataManagerSupport.childLocation(artifactContext.location as String, "${token}-api"),
            fileName              : DataManagerSupport.runArtifactFileName(artifactContext.runToken, token, fileNameValue),
    ])
    ((List) (extraction.errors ?: [])).each { Object error -> ec.message.addError("${label}: ${error}") }
    if (ec.message.hasError()) return [:]

    String extractedLocation = normalize(extraction.fileLocation)
    if (!extractedLocation) {
        ec.message.addError("${label} API did not return an output file for the selected time period.")
        return [:]
    }
    return sourceResultForLocation(source, fileSide, normalize(extraction.fileName) ?: fileNameValue,
            extractedLocation, normalizeInt(extraction.recordCount, null))
}
def extractApiSource = { Object source, String fileSide, Map artifactContext ->
    String label = sourceLabel(source, fileSide)
    String sourceConfigType = normalize(source?.sourceConfigType)
    switch (sourceConfigType) {
        case ReconciliationSavedRunSupport.SOURCE_CONFIG_TYPE_HOTWAX_OMS_REST:
            return extractHotWaxSource(source, fileSide, label, artifactContext)
        case ReconciliationSavedRunSupport.SOURCE_CONFIG_TYPE_SHOPIFY_AUTH:
            return extractShopifySource(source, fileSide, label, artifactContext)
        default:
            ec.message.addError("${label} API source type '${sourceConfigType ?: "unknown"}' is not supported for manual saved-run execution.")
            return [:]
    }
}
def writeRuleSetOutput = { Map serviceResult, Map savedRun, String file1Label, String file2Label, Map artifactContext ->
    Map output = ReconciliationServices.writeDiffDatasetOutput(
            ec,
            serviceResult.diffDf,
            artifactContext.location as String,
            DataManagerSupport.runArtifactFileName(artifactContext.runToken, "result", "result.json"),
            "${artifactContext.runToken}-diff.json",
            [
                    timestamp              : ec.user.nowTimestamp?.toString(),
                    file1Label             : file1Label,
                    file2Label             : file2Label,
                    file1Type              : serviceResult.file1Type,
                    file2Type              : serviceResult.file2Type,
                    reconciliation         : "RULESET",
                    companyUserGroupId     : savedRun.companyUserGroupId,
                    savedRunId             : savedRun.savedRunId,
                    savedRunName           : savedRun.runName,
                    savedRunType           : savedRun.runType ?: ReconciliationSavedRunSupport.RUN_TYPE_RULESET,
                    ruleSetId              : savedRun.ruleSetId,
                    compareScopeId         : savedRun.compareScopeId,
                    compareScopeDescription: serviceResult.compareScopeDescription ?: savedRun.compareScopeDescription,
            ],
            [
                    totalDifferences             : serviceResult.differenceCount,
                    onlyInFile1Count             : serviceResult.missingInFile2Count,
                    onlyInFile2Count             : serviceResult.missingInFile1Count,
                    missingObjectDifferenceCount : serviceResult.missingObjectDifferenceCount,
                    ruleDifferenceCount          : serviceResult.ruleDifferenceCount,
            ],
            (List) (serviceResult.validationErrors ?: []),
            (List) (serviceResult.processingWarnings ?: [])
    )
    serviceResult.diffLocation = output.diffLocation
    serviceResult.diffFileName = output.diffFileName
    return output
}

def mapping = null
if (!ec.message.hasError()) {
    mapping = ec.entity.find("darpan.mapping.ReconciliationMapping")
            .condition("reconciliationMappingId", savedRunIdValue)
            .useCache(false)
            .one()
}

if (!ec.message.hasError() && mapping != null) {
    TenantAccessSupport.requireTenantRecordAccess(ec, mapping, "Saved run '${savedRunIdValue}' was not found.",
            "Saved run '${savedRunIdValue}' is not available in your active tenant.")
}

if (!ec.message.hasError() && mapping != null) {
    requireUploadInput("file1", inputFile1Name, file1TextValue)
    requireUploadInput("file2", inputFile2Name, file2TextValue)
}

if (!ec.message.hasError() && mapping != null) {
    Map legacyResult = ec.service.sync()
            .name("facade.ReconciliationFacadeServices.run#GenericDiff")
            .parameters([
                    reconciliationMappingId: savedRunIdValue,
                    file1Name              : inputFile1Name,
                    file1Text              : file1TextValue,
                    file2Name              : inputFile2Name,
                    file2Text              : file2TextValue,
                    file1SystemEnumId      : requestedFile1SystemEnumId,
                    file2SystemEnumId      : requestedFile2SystemEnumId,
                    hasHeader              : hasHeaderValue,
                    sparkMaster            : sparkMaster,
                    sparkAppName           : sparkAppName ?: "SavedRunDiff"
            ])
            .call()

    Map legacyRunResult = (legacyResult?.runResult ?: [:]) as Map
    Map generatedOutput = legacyRunResult.generatedOutput instanceof Map ? (Map) legacyRunResult.generatedOutput : [:]
    runResult = [
            savedRunId             : mapping.reconciliationMappingId,
            runName                : mapping.mappingName,
            runType                : ReconciliationSavedRunSupport.RUN_TYPE_MAPPING,
            reconciliationMappingId: mapping.reconciliationMappingId,
            companyUserGroupId     : legacyRunResult.companyUserGroupId ?: mapping.companyUserGroupId,
            reconciliationRunResultId: legacyRunResult.reconciliationRunResultId,
            resultDataManagerPath  : legacyRunResult.resultDataManagerPath ?: generatedOutput.fileName,
            differenceCount        : legacyRunResult.differenceCount,
            onlyInFile1Count       : legacyRunResult.onlyInFile1Count,
            onlyInFile2Count       : legacyRunResult.onlyInFile2Count,
            ruleSetId              : null,
            compareScopeId         : null,
            compareScopeDescription: null,
            file1Name              : legacyRunResult.file1Name,
            file2Name              : legacyRunResult.file2Name,
            file1SystemEnumId      : legacyRunResult.file1SystemEnumId,
            file1SystemLabel       : legacyRunResult.file1SystemLabel,
            file2SystemEnumId      : legacyRunResult.file2SystemEnumId,
            file2SystemLabel       : legacyRunResult.file2SystemLabel,
            validationErrors       : (legacyRunResult.validationErrors ?: []) as List,
            processingWarnings     : (legacyRunResult.processingWarnings ?: []) as List,
            generatedOutput        : generatedOutput,
    ]
}

if (!ec.message.hasError() && mapping == null) {
    Map<String, Object> resolvedRuleSetRun = ReconciliationSavedRunSupport.resolveRuleSetRun(ec, savedRunIdValue)
    if (resolvedRuleSetRun.error) {
        ec.message.addError(resolvedRuleSetRun.error as String)
    } else if (resolvedRuleSetRun.savedRun == null) {
        ec.message.addError("Saved run '${savedRunIdValue}' was not found.")
    } else {
        Map<String, Object> savedRun = (Map<String, Object>) resolvedRuleSetRun.savedRun
        Map<String, Object> sourceBySide = (Map<String, Object>) resolvedRuleSetRun.sourceBySide
        def file1Source = sourceBySide[ReconciliationSavedRunSupport.FILE_SIDE_1]
        def file2Source = sourceBySide[ReconciliationSavedRunSupport.FILE_SIDE_2]
        boolean file1UsesApiSource = isApiSource(file1Source)
        boolean file2UsesApiSource = isApiSource(file2Source)
        boolean hasApiInput = file1UsesApiSource || file2UsesApiSource

        String defaultFile1SystemEnumId = normalize(file1Source?.systemEnumId)
        String defaultFile2SystemEnumId = normalize(file2Source?.systemEnumId)
        String resolvedFile1SystemEnumId = requestedFile1SystemEnumId ?: defaultFile1SystemEnumId
        String resolvedFile2SystemEnumId = requestedFile2SystemEnumId ?: defaultFile2SystemEnumId
        if (resolvedFile1SystemEnumId == resolvedFile2SystemEnumId) {
            ec.message.addError("file1SystemEnumId and file2SystemEnumId must be different.")
        }

        if (!ec.message.hasError() && hasApiInput) {
            if (!windowStartDateValue || !windowEndDateValue) {
                ec.message.addError("windowStartDate and windowEndDate are required when a saved-run source is API-backed.")
            } else if (!windowStartDateValue.before(windowEndDateValue)) {
                ec.message.addError("windowStartDate must be before windowEndDate.")
            }
            if (!file1UsesApiSource) requireUploadInput("file1", inputFile1Name, file1TextValue)
            if (!file2UsesApiSource) requireUploadInput("file2", inputFile2Name, file2TextValue)
        } else if (!ec.message.hasError()) {
            requireUploadInput("file1", inputFile1Name, file1TextValue)
            requireUploadInput("file2", inputFile2Name, file2TextValue)
        }

        if (!ec.message.hasError()) {
            String file1Label = enumLabel(resolvedFile1SystemEnumId)
            String file2Label = enumLabel(resolvedFile2SystemEnumId)

            if (hasApiInput) {
                tenantApiWindow = resolveTenantApiWindow()
                if (!ec.message.hasError()) {
                    Map artifactContext = buildRunArtifactContext(savedRun.savedRunId as String)
                    Map file1Result = [:]
                    Map file2Result = [:]
                    String reconciliationRunResultId = persistRunResult([
                        savedRunId          : savedRun.savedRunId,
                        savedRunType        : savedRun.runType ?: ReconciliationSavedRunSupport.RUN_TYPE_RULESET,
                        ruleSetId           : savedRun.ruleSetId,
                        compareScopeId      : savedRun.compareScopeId,
                        companyUserGroupId  : savedRun.companyUserGroupId,
                        file1Name           : file1UsesApiSource ? null : inputFile1Name,
                        file2Name           : file2UsesApiSource ? null : inputFile2Name,
                        statusEnumId        : ReconciliationOutputSupport.STATUS_RUNNING,
                        createdDate         : ec.user.nowTimestamp,
                        startedDate         : ec.user.nowTimestamp,
                    ])
                    try {
                        file1Result = file1UsesApiSource ?
                                extractApiSource(file1Source, ReconciliationSavedRunSupport.FILE_SIDE_1, artifactContext) :
                                stageTextInput(file1Source, ReconciliationSavedRunSupport.FILE_SIDE_1, inputFile1Name, file1TextValue, artifactContext)
                        file2Result = !ec.message.hasError() && file2UsesApiSource ?
                                extractApiSource(file2Source, ReconciliationSavedRunSupport.FILE_SIDE_2, artifactContext) :
                                !ec.message.hasError() ? stageTextInput(file2Source, ReconciliationSavedRunSupport.FILE_SIDE_2, inputFile2Name, file2TextValue, artifactContext) : [:]

                        if (!ec.message.hasError()) {
                            Map serviceResult = runInternalService("reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope", [
                                ruleSetId          : savedRun.ruleSetId,
                                compareScopeId     : savedRun.compareScopeId,
                                file1Location      : file1Result.fileLocation,
                                file2Location      : file2Result.fileLocation,
                                file1Name          : file1Result.fileName,
                                file2Name          : file2Result.fileName,
                                file1FileTypeEnumId: file1Result.fileTypeEnumId,
                                file2FileTypeEnumId: file2Result.fileTypeEnumId,
                                file1SchemaFileName: file1Result.schemaFileName,
                                file2SchemaFileName: file2Result.schemaFileName,
                                file1Label         : file1Label,
                                file2Label         : file2Label,
                                hasHeader          : hasHeaderValue,
                                sparkMaster        : sparkMaster,
                                sparkAppName       : sparkAppName ?: "SavedRunDiff"
                            ])
                            if (!ec.message.hasError()) {
                                writeRuleSetOutput(serviceResult, savedRun, file1Label, file2Label, artifactContext)
                                String resultDataManagerPath = serviceResult.diffLocation ?
                                        (DataManagerSupport.relativeDataManagerPath(ec, new File(serviceResult.diffLocation as String)) ?: serviceResult.diffFileName) :
                                        serviceResult.diffFileName
                                reconciliationRunResultId = persistRunResult([
                                    reconciliationRunResultId: reconciliationRunResultId,
                                    savedRunId               : savedRun.savedRunId,
                                    savedRunType             : savedRun.runType ?: ReconciliationSavedRunSupport.RUN_TYPE_RULESET,
                                    ruleSetId                : savedRun.ruleSetId,
                                    compareScopeId           : savedRun.compareScopeId,
                                    companyUserGroupId       : savedRun.companyUserGroupId,
                                    file1Name                : file1Result.fileName,
                                    file1DataManagerPath     : file1Result.dataManagerPath,
                                    file2Name                : file2Result.fileName,
                                    file2DataManagerPath     : file2Result.dataManagerPath,
                                    resultDataManagerPath    : resultDataManagerPath,
                                    statusEnumId             : ReconciliationOutputSupport.STATUS_SUCCEEDED,
                                    completedDate            : ec.user.nowTimestamp,
                                    reconciliationType       : serviceResult.reconciliationType ?: serviceResult.objectType,
                                    differenceCount          : serviceResult.differenceCount,
                                    onlyInFile1Count         : serviceResult.missingInFile2Count,
                                    onlyInFile2Count         : serviceResult.missingInFile1Count,
                                ])
                                serviceResult.reconciliationRunResultId = reconciliationRunResultId
                                serviceResult.diffFileName = resultDataManagerPath
                                runResult = [
                                    savedRunId               : savedRun.savedRunId,
                                    runName                  : savedRun.runName,
                                    runType                  : savedRun.runType,
                                    reconciliationMappingId  : null,
                                    companyUserGroupId       : savedRun.companyUserGroupId,
                                    reconciliationRunResultId: reconciliationRunResultId,
                                    resultDataManagerPath    : resultDataManagerPath,
                                    differenceCount          : serviceResult.differenceCount,
                                    onlyInFile1Count         : serviceResult.missingInFile2Count,
                                    onlyInFile2Count         : serviceResult.missingInFile1Count,
                                    ruleSetId                : savedRun.ruleSetId,
                                    compareScopeId           : savedRun.compareScopeId,
                                    compareScopeDescription  : savedRun.compareScopeDescription,
                                    file1Name                : file1Result.fileName,
                                    file2Name                : file2Result.fileName,
                                    file1SystemEnumId        : resolvedFile1SystemEnumId,
                                    file1SystemLabel         : file1Label,
                                    file2SystemEnumId        : resolvedFile2SystemEnumId,
                                    file2SystemLabel         : file2Label,
                                    validationErrors         : (serviceResult.validationErrors ?: []) as List,
                                    processingWarnings       : (serviceResult.processingWarnings ?: []) as List,
                                    generatedOutput          : buildGeneratedOutputDescriptor(serviceResult),
                                ]
                            }
                        }
                    } catch (Throwable t) {
                        persistRunResult([
                            reconciliationRunResultId: reconciliationRunResultId,
                            file1Name                : file1Result.fileName,
                            file1DataManagerPath     : file1Result.dataManagerPath,
                            file2Name                : file2Result.fileName,
                            file2DataManagerPath     : file2Result.dataManagerPath,
                            statusEnumId             : ReconciliationOutputSupport.STATUS_FAILED,
                            completedDate            : ec.user.nowTimestamp,
                        ])
                        throw t
                    } finally {
                        if (reconciliationRunResultId && ec.message.hasError() && !runResult?.reconciliationRunResultId) {
                            persistRunResult([
                                reconciliationRunResultId: reconciliationRunResultId,
                                file1Name                : file1Result.fileName,
                                file1DataManagerPath     : file1Result.dataManagerPath,
                                file2Name                : file2Result.fileName,
                                file2DataManagerPath     : file2Result.dataManagerPath,
                                statusEnumId             : ReconciliationOutputSupport.STATUS_FAILED,
                                completedDate            : ec.user.nowTimestamp,
                            ])
                        }
                    }
                }
            } else {
                String reconciliationRunResultId = persistRunResult([
                        savedRunId          : savedRun.savedRunId,
                        savedRunType        : savedRun.runType ?: ReconciliationSavedRunSupport.RUN_TYPE_RULESET,
                        ruleSetId           : savedRun.ruleSetId,
                        compareScopeId      : savedRun.compareScopeId,
                        companyUserGroupId  : savedRun.companyUserGroupId,
                        file1Name           : inputFile1Name,
                        file2Name           : inputFile2Name,
                        statusEnumId        : ReconciliationOutputSupport.STATUS_RUNNING,
                        createdDate         : ec.user.nowTimestamp,
                        startedDate         : ec.user.nowTimestamp,
                ])
                try {
                    Map serviceResult = ec.service.sync()
                            .name("reconciliation.ReconciliationGenericServices.reconcile#GenericFiles")
                            .parameters([
                                    reconciliationRunResultId: reconciliationRunResultId,
                                    ruleSetId                : savedRun.ruleSetId,
                                    compareScopeId           : savedRun.compareScopeId,
                                    file1Name                : inputFile1Name,
                                    file1Text                : file1TextValue,
                                    file2Name                : inputFile2Name,
                                    file2Text                : file2TextValue,
                                    file1SystemEnumId        : resolvedFile1SystemEnumId,
                                    file2SystemEnumId        : resolvedFile2SystemEnumId,
                                    hasHeader                : hasHeaderValue,
                                    sparkMaster              : sparkMaster,
                                    sparkAppName             : sparkAppName ?: "SavedRunDiff"
                            ])
                            .call()

                    if (!ec.message.hasError()) {
                        runResult = [
                                savedRunId               : savedRun.savedRunId,
                                runName                  : savedRun.runName,
                                runType                  : savedRun.runType,
                                reconciliationMappingId  : null,
                                companyUserGroupId       : savedRun.companyUserGroupId,
                                reconciliationRunResultId: serviceResult.reconciliationRunResultId,
                                resultDataManagerPath    : serviceResult.diffFileName,
                                differenceCount          : serviceResult.differenceCount,
                                onlyInFile1Count         : serviceResult.onlyInFile1Count,
                                onlyInFile2Count         : serviceResult.onlyInFile2Count,
                                ruleSetId                : savedRun.ruleSetId,
                                compareScopeId           : savedRun.compareScopeId,
                                compareScopeDescription  : savedRun.compareScopeDescription,
                                file1Name                : inputFile1Name,
                                file2Name                : inputFile2Name,
                                file1SystemEnumId        : resolvedFile1SystemEnumId,
                                file1SystemLabel         : file1Label,
                                file2SystemEnumId        : resolvedFile2SystemEnumId,
                                file2SystemLabel         : file2Label,
                                validationErrors         : (serviceResult.validationErrors ?: []) as List,
                                processingWarnings       : (serviceResult.processingWarnings ?: []) as List,
                                generatedOutput          : buildGeneratedOutputDescriptor(serviceResult),
                        ]
                    }
                } catch (Throwable t) {
                    if (reconciliationRunResultId) {
                        persistRunResult([
                                reconciliationRunResultId: reconciliationRunResultId,
                                statusEnumId             : ReconciliationOutputSupport.STATUS_FAILED,
                                completedDate            : ec.user.nowTimestamp,
                        ])
                    }
                    throw t
                } finally {
                    if (reconciliationRunResultId && ec.message.hasError() && !runResult?.reconciliationRunResultId) {
                        persistRunResult([
                                reconciliationRunResultId: reconciliationRunResultId,
                                statusEnumId             : ReconciliationOutputSupport.STATUS_FAILED,
                                completedDate            : ec.user.nowTimestamp,
                        ])
                    }
                }
            }
        }
    }
}

if (!ec.message.hasError() && runResult?.reconciliationRunResultId) {
    TenantNotificationSupport.notifyRunCompleted(ec, (Map<String, Object>) runResult)
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
