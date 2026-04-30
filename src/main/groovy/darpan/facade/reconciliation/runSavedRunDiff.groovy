import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.ReconciliationOutputSupport
import darpan.facade.reconciliation.ReconciliationSavedRunSupport

import java.sql.Timestamp

String savedRunIdValue = FacadeSupport.normalize(savedRunId)
String inputFile1Name = ReconciliationOutputSupport.sanitizeUploadFileName(file1Name, "file1")
String inputFile2Name = ReconciliationOutputSupport.sanitizeUploadFileName(file2Name, "file2")
String file1TextValue = file1Text?.toString()
String file2TextValue = file2Text?.toString()
String requestedFile1SystemEnumId = FacadeSupport.normalize(file1SystemEnumId)
String requestedFile2SystemEnumId = FacadeSupport.normalize(file2SystemEnumId)
boolean hasHeaderValue = FacadeSupport.normalizeBool(hasHeader, true)

if (!savedRunIdValue) ec.message.addError("savedRunId is required")
if (!inputFile1Name) ec.message.addError("file1Name is required")
if (!file1TextValue) ec.message.addError("file1Text is required")
if (!inputFile2Name) ec.message.addError("file2Name is required")
if (!file2TextValue) ec.message.addError("file2Text is required")
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
def resolveOutputFile = { Map serviceResult ->
    File outputFile = null
    String diffLocationValue = FacadeSupport.normalize(serviceResult?.diffLocation)
    if (diffLocationValue) {
        outputFile = diffLocationValue.startsWith("/") ?
                new File(diffLocationValue) :
                ec.resource.getLocationReference(diffLocationValue)?.getFile()
    }
    if ((outputFile == null || !outputFile.exists()) && serviceResult?.diffFileName) {
        String scopedOutputLocation = TenantAccessSupport.resolveGenericOutputLocation(ec)
        File outputDir = ec.resource.getLocationReference(scopedOutputLocation)?.getFile()
        if (outputDir != null) outputFile = new File(outputDir, serviceResult.diffFileName.toString())
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
            savedRunId           : mapping.reconciliationMappingId,
            runName              : mapping.mappingName,
            runType              : ReconciliationSavedRunSupport.RUN_TYPE_MAPPING,
            reconciliationMappingId: mapping.reconciliationMappingId,
            reconciliationRunResultId: legacyRunResult.reconciliationRunResultId,
            ruleSetId            : null,
            compareScopeId       : null,
            compareScopeDescription: null,
            file1Name            : legacyRunResult.file1Name,
            file2Name            : legacyRunResult.file2Name,
            file1SystemEnumId    : legacyRunResult.file1SystemEnumId,
            file1SystemLabel     : legacyRunResult.file1SystemLabel,
            file2SystemEnumId    : legacyRunResult.file2SystemEnumId,
            file2SystemLabel     : legacyRunResult.file2SystemLabel,
            validationErrors     : (legacyRunResult.validationErrors ?: []) as List,
            processingWarnings   : (legacyRunResult.processingWarnings ?: []) as List,
            generatedOutput      : generatedOutput,
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

        String defaultFile1SystemEnumId = FacadeSupport.normalize(sourceBySide[ReconciliationSavedRunSupport.FILE_SIDE_1]?.systemEnumId)
        String defaultFile2SystemEnumId = FacadeSupport.normalize(sourceBySide[ReconciliationSavedRunSupport.FILE_SIDE_2]?.systemEnumId)
        String resolvedFile1SystemEnumId = requestedFile1SystemEnumId ?: defaultFile1SystemEnumId
        String resolvedFile2SystemEnumId = requestedFile2SystemEnumId ?: defaultFile2SystemEnumId
        if (resolvedFile1SystemEnumId == resolvedFile2SystemEnumId) {
            ec.message.addError("file1SystemEnumId and file2SystemEnumId must be different.")
        }

        if (!ec.message.hasError()) {
            String file1Label = FacadeSupport.enumLabel(findEnum(resolvedFile1SystemEnumId) ?: [enumId: resolvedFile1SystemEnumId])
            String file2Label = FacadeSupport.enumLabel(findEnum(resolvedFile2SystemEnumId) ?: [enumId: resolvedFile2SystemEnumId])

            Map serviceResult = ec.service.sync()
                    .name("reconciliation.ReconciliationGenericServices.reconcile#GenericFiles")
                    .parameters([
                            ruleSetId        : savedRun.ruleSetId,
                            compareScopeId   : savedRun.compareScopeId,
                            file1Name        : inputFile1Name,
                            file1Text        : file1TextValue,
                            file2Name        : inputFile2Name,
                            file2Text        : file2TextValue,
                            file1SystemEnumId: resolvedFile1SystemEnumId,
                            file2SystemEnumId: resolvedFile2SystemEnumId,
                            hasHeader        : hasHeaderValue,
                            sparkMaster      : sparkMaster,
                            sparkAppName     : sparkAppName ?: "SavedRunDiff"
                    ])
                    .call()

            if (!ec.message.hasError()) {
                runResult = [
                        savedRunId            : savedRun.savedRunId,
                        runName               : savedRun.runName,
                        runType               : savedRun.runType,
                        reconciliationMappingId: null,
                        reconciliationRunResultId: serviceResult.reconciliationRunResultId,
                        ruleSetId             : savedRun.ruleSetId,
                        compareScopeId        : savedRun.compareScopeId,
                        compareScopeDescription: savedRun.compareScopeDescription,
                        file1Name             : inputFile1Name,
                        file2Name             : inputFile2Name,
                        file1SystemEnumId     : resolvedFile1SystemEnumId,
                        file1SystemLabel      : file1Label,
                        file2SystemEnumId     : resolvedFile2SystemEnumId,
                        file2SystemLabel      : file2Label,
                        validationErrors      : (serviceResult.validationErrors ?: []) as List,
                        processingWarnings    : (serviceResult.processingWarnings ?: []) as List,
                        generatedOutput       : buildGeneratedOutputDescriptor(serviceResult),
                ]
            }
        }
    }
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
