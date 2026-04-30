import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.core.ReconciliationServices
import org.apache.spark.sql.Dataset
import org.slf4j.LoggerFactory

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

def logger = LoggerFactory.getLogger("darpan.reconciliation.generic.GenericReconciliation")

def normalize = { it?.toString()?.trim() }
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
def saveTextPayload = { String payload, targetRef ->
    byte[] bytes = (payload ?: "").getBytes(StandardCharsets.UTF_8)
    File targetFile = targetRef.getFile()
    if (targetFile != null) {
        targetFile.parentFile?.mkdirs()
        targetFile.withOutputStream { outputStream ->
            outputStream.write(bytes)
        }
        return
    }
    targetRef.putStream(new ByteArrayInputStream(bytes))
}
def resolveMappingName = { String mappingId ->
    String normalized = normalize(mappingId)
    if (!normalized) return null
    def mapping = ec.entity.find("darpan.mapping.ReconciliationMapping")
            .condition("reconciliationMappingId", normalized)
            .disableAuthz()
            .useCache(true)
            .one()
    return normalize(mapping?.mappingName)
}
def resolveRuleSet = { String ruleSetId ->
    String normalized = normalize(ruleSetId)
    if (!normalized) return null
    return ec.entity.find("darpan.rule.RuleSet")
            .condition("ruleSetId", normalized)
            .disableAuthz()
            .useCache(true)
            .one()
}
def persistRunResult = { Map<String, Object> fields ->
    String resultPath = DataManagerSupport.normalizeRelativePath(fields.resultDataManagerPath)
    if (!resultPath) return null

    return ec.transaction.runUseOrBegin(30, "Error saving reconciliation run result", {
        def runResultValue = ec.entity.makeValue("darpan.reconciliation.ReconciliationRunResult")
        runResultValue.savedRunId = normalize(fields.savedRunId)
        runResultValue.savedRunType = normalize(fields.savedRunType)
        runResultValue.reconciliationRunId = normalize(fields.reconciliationRunId)
        runResultValue.reconciliationMappingId = normalize(fields.reconciliationMappingId)
        runResultValue.ruleSetId = normalize(fields.ruleSetId)
        runResultValue.compareScopeId = normalize(fields.compareScopeId)
        runResultValue.companyUserGroupId = normalize(fields.companyUserGroupId) ?: TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        runResultValue.createdByUserId = TenantAccessSupport.currentUserId(ec)
        runResultValue.file1Name = normalize(fields.file1Name)
        runResultValue.file1DataManagerPath = DataManagerSupport.normalizeRelativePath(fields.file1DataManagerPath)
        runResultValue.file2Name = normalize(fields.file2Name)
        runResultValue.file2DataManagerPath = DataManagerSupport.normalizeRelativePath(fields.file2DataManagerPath)
        runResultValue.resultDataManagerPath = resultPath
        runResultValue.reconciliationType = normalize(fields.reconciliationType)
        runResultValue.differenceCount = fields.differenceCount
        runResultValue.onlyInFile1Count = fields.onlyInFile1Count
        runResultValue.onlyInFile2Count = fields.onlyInFile2Count
        runResultValue.createdDate = ec.user.nowTimestamp
        runResultValue.setSequencedIdPrimary()
        runResultValue.create()
        return runResultValue.reconciliationRunResultId
    })
}

String providedFile1Name = normalize(file1Name)
String providedFile2Name = normalize(file2Name)
String file1TextValue = file1Text?.toString()
String file2TextValue = file2Text?.toString()
String ruleSetIdValue = normalize(ruleSetId)
String compareScopeIdValue = normalize(compareScopeId)
String mappingIdValue = normalize(reconciliationMappingId)
String requestedFile1SystemEnumId = normalize(file1SystemEnumId)
String requestedFile2SystemEnumId = normalize(file2SystemEnumId)

if (!file1 && !file1TextValue) {
    throw new IllegalArgumentException("Either file1 or file1Text is required")
}
if (!file2 && !file2TextValue) {
    throw new IllegalArgumentException("Either file2 or file2Text is required")
}
if (!ruleSetIdValue && !mappingIdValue) {
    throw new IllegalArgumentException("Either ruleSetId or reconciliationMappingId is required")
}
if (!ruleSetIdValue && (!requestedFile1SystemEnumId || !requestedFile2SystemEnumId)) {
    throw new IllegalArgumentException("file1SystemEnumId and file2SystemEnumId are required when using reconciliationMappingId")
}

String runIdValue = ruleSetIdValue ?: mappingIdValue
String runToken = DataManagerSupport.safeToken(runIdValue, "run")
String timestamp = DataManagerSupport.formatRunTimestamp(ec)
String runArtifactLocation = DataManagerSupport.resolveReconciliationRunLocation(ec, runToken, timestamp)
File runArtifactDir = DataManagerSupport.resolveDirectoryFile(ec, runArtifactLocation, true)
if (runArtifactDir == null) {
    throw new IllegalStateException("Unable to resolve reconciliation run data-manager directory: ${runArtifactLocation}")
}

String file1SafeName = (providedFile1Name ?: file1?.getName())?.replaceAll("[^A-Za-z0-9._-]", "_") ?: "file1"
String file2SafeName = (providedFile2Name ?: file2?.getName())?.replaceAll("[^A-Za-z0-9._-]", "_") ?: "file2"
String resultFileName = DataManagerSupport.runArtifactFileName(runToken, "result", "result.json")

def file1Ref = ec.resource.getLocationReference(DataManagerSupport.childLocation(runArtifactLocation,
        DataManagerSupport.runArtifactFileName(runToken, "file1", file1SafeName)))
def file2Ref = ec.resource.getLocationReference(DataManagerSupport.childLocation(runArtifactLocation,
        DataManagerSupport.runArtifactFileName(runToken, "file2", file2SafeName)))
if (file1Ref == null || file2Ref == null) {
    throw new IllegalStateException("Unable to resolve reconciliation input artifact files under ${runArtifactLocation}")
}

if (file1) saveFileItem(file1, file1Ref)
else saveTextPayload(file1TextValue, file1Ref)

if (file2) saveFileItem(file2, file2Ref)
else saveTextPayload(file2TextValue, file2Ref)

String file1Location = file1Ref.getFile()?.getAbsolutePath() ?: file1Ref.getLocation()
String file2Location = file2Ref.getFile()?.getAbsolutePath() ?: file2Ref.getLocation()
String file1DataManagerPath = DataManagerSupport.relativeDataManagerPath(ec, file1Ref.getFile())
String file2DataManagerPath = DataManagerSupport.relativeDataManagerPath(ec, file2Ref.getFile())
String outputLocationValue = runArtifactLocation
String sparkMasterToUse = sparkMaster ?: (ec.resource.properties["spark.master"] ?: "local[*]")
String sparkAppNameToUse = sparkAppName ?: "GenericReconciliation"
String mappingNameValue = resolveMappingName(mappingIdValue)
def ruleSetValue = resolveRuleSet(ruleSetIdValue)
String ruleSetNameValue = normalize(ruleSetValue?.ruleSetName) ?: ruleSetIdValue

if (ruleSetIdValue) {
    Map compareScopeConfig = ReconciliationServices.resolveRuleSetCompareScopeConfig(
            ec,
            ruleSetIdValue,
            compareScopeIdValue,
            requestedFile1SystemEnumId,
            requestedFile2SystemEnumId
    )

    Map reconcileResult = ec.service.sync()
            .name("reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope")
            .parameters([
                    ruleSetId     : ruleSetIdValue,
                    compareScopeId: compareScopeConfig.compareScopeId,
                    file1Location : file1Location,
                    file2Location : file2Location,
                    file1Name     : file1SafeName,
                    file2Name     : file2SafeName,
                    file1Label    : compareScopeConfig.file1Label,
                    file2Label    : compareScopeConfig.file2Label,
                    hasHeader     : hasHeader,
                    sparkMaster   : sparkMasterToUse,
                    sparkAppName  : sparkAppNameToUse
            ])
            .call()

    file1Type = reconcileResult.file1Type
    file2Type = reconcileResult.file2Type
    reconciliationType = ReconciliationServices.determineReconciliationType(file1Type as String, file2Type as String)
    differenceCount = reconcileResult.differenceCount
    onlyInFile1Count = reconcileResult.missingInFile2Count
    onlyInFile2Count = reconcileResult.missingInFile1Count
    validationErrors = (reconcileResult.validationErrors ?: []) as List
    processingWarnings = (reconcileResult.processingWarnings ?: []) as List

    Map outputMetadata = [
            timestamp                : ec.user.nowTimestamp?.toString(),
            companyUserGroupId       : normalize(ruleSetValue?.companyUserGroupId),
            savedRunId               : ruleSetIdValue,
            savedRunName             : ruleSetNameValue,
            savedRunType             : "ruleset",
            file1Label               : compareScopeConfig.file1Label,
            file2Label               : compareScopeConfig.file2Label,
            file1Type                : file1Type,
            file2Type                : file2Type,
            reconciliation           : reconciliationType,
            reconciliationMappingId  : mappingIdValue,
            reconciliationMappingName: mappingNameValue,
            ruleSetId                : ruleSetIdValue,
            ruleSetName              : ruleSetNameValue,
            compareScopeId           : compareScopeConfig.compareScopeId,
            compareScopeDescription  : compareScopeConfig.compareScopeDescription,
            objectType               : reconcileResult.objectType
    ]
    Map outputSummary = [
            totalDifferences           : differenceCount,
            onlyInFile1Count           : onlyInFile1Count,
            onlyInFile2Count           : onlyInFile2Count,
            missingObjectDifferenceCount: reconcileResult.missingObjectDifferenceCount,
            ruleDifferenceCount        : reconcileResult.ruleDifferenceCount
    ]
    Map outputInfo = ReconciliationServices.writeDiffDatasetOutput(
            ec,
            (Dataset) reconcileResult.diffDf,
            outputLocationValue,
            null,
            resultFileName,
            outputMetadata,
            outputSummary,
            validationErrors,
            processingWarnings
    )

    diffLocation = outputInfo.diffLocation
    diffFileName = DataManagerSupport.relativeDataManagerPath(ec, new File(diffLocation as String)) ?: outputInfo.diffFileName
    reconciliationRunResultId = persistRunResult([
            savedRunId              : ruleSetIdValue,
            savedRunType            : "ruleset",
            reconciliationMappingId : mappingIdValue,
            ruleSetId               : ruleSetIdValue,
            compareScopeId          : compareScopeConfig.compareScopeId,
            companyUserGroupId      : ruleSetValue?.companyUserGroupId,
            file1Name               : file1SafeName,
            file1DataManagerPath    : file1DataManagerPath,
            file2Name               : file2SafeName,
            file2DataManagerPath    : file2DataManagerPath,
            resultDataManagerPath   : diffFileName,
            reconciliationType      : reconciliationType,
            differenceCount         : differenceCount,
            onlyInFile1Count        : onlyInFile1Count,
            onlyInFile2Count        : onlyInFile2Count,
    ])
    logger.info("Generic reconciliation complete via RuleSet path: ruleSet={} compareScope={} diff={} differences={}",
            ruleSetIdValue, compareScopeConfig.compareScopeId, diffFileName, differenceCount)
    return
}

String system1Label = ReconciliationServices.resolveEnumLabel(ec, requestedFile1SystemEnumId, requestedFile1SystemEnumId)
String system2Label = ReconciliationServices.resolveEnumLabel(ec, requestedFile2SystemEnumId, requestedFile2SystemEnumId)

Map reconcileResult = ec.service.sync()
        .name("reconciliation.ReconciliationCoreServices.reconcile#FilesByMapping")
        .parameters([
                reconciliationMappingId: mappingIdValue,
                file1Location          : file1Location,
                file2Location          : file2Location,
                file1Name              : file1SafeName,
                file2Name              : file2SafeName,
                file1SystemEnumId      : requestedFile1SystemEnumId,
                file2SystemEnumId      : requestedFile2SystemEnumId,
                file1Label             : system1Label,
                file2Label             : system2Label,
                hasHeader              : hasHeader,
                outputLocation         : outputLocationValue,
                outputFileName         : resultFileName,
                sparkMaster            : sparkMasterToUse,
                sparkAppName           : sparkAppNameToUse
        ])
        .call()

file1Type = reconcileResult.file1Type
file2Type = reconcileResult.file2Type
reconciliationType = reconcileResult.reconciliationType
diffLocation = reconcileResult.diffLocation
diffFileName = diffLocation ? (DataManagerSupport.relativeDataManagerPath(ec, new File(diffLocation as String)) ?: reconcileResult.diffFileName) : reconcileResult.diffFileName
differenceCount = reconcileResult.differenceCount
onlyInFile1Count = reconcileResult.onlyInFile1Count
onlyInFile2Count = reconcileResult.onlyInFile2Count
validationErrors = reconcileResult.validationErrors ?: []
processingWarnings = reconcileResult.processingWarnings ?: []
reconciliationRunResultId = persistRunResult([
        savedRunId             : mappingIdValue,
        savedRunType           : "mapping",
        reconciliationMappingId: mappingIdValue,
        file1Name              : file1SafeName,
        file1DataManagerPath   : file1DataManagerPath,
        file2Name              : file2SafeName,
        file2DataManagerPath   : file2DataManagerPath,
        resultDataManagerPath  : diffFileName,
        reconciliationType     : reconciliationType,
        differenceCount        : differenceCount,
        onlyInFile1Count       : onlyInFile1Count,
        onlyInFile2Count       : onlyInFile2Count,
])

logger.info("Generic reconciliation complete via mapping bridge: mapping={} diff={} differences={}",
        mappingIdValue, diffFileName, differenceCount)
