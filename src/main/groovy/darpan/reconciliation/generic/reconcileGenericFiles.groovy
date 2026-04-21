import darpan.facade.common.PilotAccessSupport
import darpan.reconciliation.core.ReconciliationServices
import org.apache.spark.sql.Dataset
import org.slf4j.LoggerFactory

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

def logger = LoggerFactory.getLogger("darpan.reconciliation.generic.GenericReconciliation")

def normalize = { it?.toString()?.trim() }
def safeToken = { String raw, String fallback ->
    String normalized = normalize(raw)?.replaceAll(/[^A-Za-z0-9_-]/, "-")
    return normalized ?: fallback
}
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

String tempLoc = PilotAccessSupport.resolveGenericTempLocation(ec)
def baseDirRef = ec.resource.getLocationReference(tempLoc)
if (baseDirRef == null) {
    throw new IllegalStateException("Unable to resolve generic reconciliation temp directory: ${tempLoc}")
}
def inputDirRef = baseDirRef.makeDirectory("input")
if (!inputDirRef.getExists()) {
    File inputDirFile = inputDirRef.getFile()
    if (inputDirFile != null && !inputDirFile.exists()) inputDirFile.mkdirs()
}

String timestamp = ec.l10n.format(ec.user.nowTimestamp, "yyyyMMdd-HHmmssSSS")
String file1SafeName = (providedFile1Name ?: file1?.getName())?.replaceAll("[^A-Za-z0-9._-]", "_") ?: "file1"
String file2SafeName = (providedFile2Name ?: file2?.getName())?.replaceAll("[^A-Za-z0-9._-]", "_") ?: "file2"

def file1Ref = inputDirRef.makeFile(timestamp + "-file1-" + file1SafeName)
def file2Ref = inputDirRef.makeFile(timestamp + "-file2-" + file2SafeName)

if (file1) saveFileItem(file1, file1Ref)
else saveTextPayload(file1TextValue, file1Ref)

if (file2) saveFileItem(file2, file2Ref)
else saveTextPayload(file2TextValue, file2Ref)

String file1Location = file1Ref.getFile()?.getAbsolutePath() ?: file1Ref.getLocation()
String file2Location = file2Ref.getFile()?.getAbsolutePath() ?: file2Ref.getLocation()
String outputLocationValue = tempLoc + "/output"
String sparkMasterToUse = sparkMaster ?: (ec.resource.properties["spark.master"] ?: "local[*]")
String sparkAppNameToUse = sparkAppName ?: "GenericReconciliation"
String mappingNameValue = resolveMappingName(mappingIdValue)

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

    String defaultBaseName = "${safeToken(compareScopeConfig.compareScopeId as String, safeToken(ruleSetIdValue, 'ruleset'))}-diff-${timestamp}.json"
    Map outputMetadata = [
            timestamp                : ec.user.nowTimestamp?.toString(),
            file1Label               : compareScopeConfig.file1Label,
            file2Label               : compareScopeConfig.file2Label,
            file1Type                : file1Type,
            file2Type                : file2Type,
            reconciliation           : reconciliationType,
            reconciliationMappingId  : mappingIdValue,
            reconciliationMappingName: mappingNameValue,
            ruleSetId                : ruleSetIdValue,
            compareScopeId           : compareScopeConfig.compareScopeId,
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
            defaultBaseName,
            outputMetadata,
            outputSummary,
            validationErrors,
            processingWarnings
    )

    diffLocation = outputInfo.diffLocation
    diffFileName = outputInfo.diffFileName
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
                sparkMaster            : sparkMasterToUse,
                sparkAppName           : sparkAppNameToUse
        ])
        .call()

file1Type = reconcileResult.file1Type
file2Type = reconcileResult.file2Type
reconciliationType = reconcileResult.reconciliationType
diffLocation = reconcileResult.diffLocation
diffFileName = reconcileResult.diffFileName
differenceCount = reconcileResult.differenceCount
onlyInFile1Count = reconcileResult.onlyInFile1Count
onlyInFile2Count = reconcileResult.onlyInFile2Count
validationErrors = reconcileResult.validationErrors ?: []
processingWarnings = reconcileResult.processingWarnings ?: []

logger.info("Generic reconciliation complete via mapping bridge: mapping={} diff={} differences={}",
        mappingIdValue, diffFileName, differenceCount)
