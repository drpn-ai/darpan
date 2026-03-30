import darpan.facade.common.PilotAccessSupport
import org.slf4j.LoggerFactory

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

def logger = LoggerFactory.getLogger("darpan.reconciliation.generic.GenericReconciliation")

def normalize = { it?.toString()?.trim() }
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

String providedFile1Name = normalize(file1Name)
String providedFile2Name = normalize(file2Name)
String file1TextValue = file1Text?.toString()
String file2TextValue = file2Text?.toString()

// Validate required inputs
if (!file1 && !file1TextValue) {
    throw new IllegalArgumentException("Either file1 or file1Text is required")
}
if (!file2 && !file2TextValue) {
    throw new IllegalArgumentException("Either file2 or file2Text is required")
}
if (!file1SystemEnumId || !file2SystemEnumId) {
    throw new IllegalArgumentException("System selection is required for both files")
}
if (!reconciliationMappingId) {
    throw new IllegalArgumentException("Mapping selection is required")
}

def tempLoc = PilotAccessSupport.resolveGenericTempLocation(ec)
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
def file1SafeName = (providedFile1Name ?: file1?.getName())?.replaceAll('[^A-Za-z0-9._-]', '_') ?: 'file1'
def file2SafeName = (providedFile2Name ?: file2?.getName())?.replaceAll('[^A-Za-z0-9._-]', '_') ?: 'file2'

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

if (file1) saveFileItem(file1, file1Ref)
else saveTextPayload(file1TextValue, file1Ref)

if (file2) saveFileItem(file2, file2Ref)
else saveTextPayload(file2TextValue, file2Ref)

def file1Location = file1Ref.getFile()?.getAbsolutePath() ?: file1Ref.getLocation()
def file2Location = file2Ref.getFile()?.getAbsolutePath() ?: file2Ref.getLocation()

def system1Label = resolveEnumLabel(file1SystemEnumId, file1SystemEnumId)
def system2Label = resolveEnumLabel(file2SystemEnumId, file2SystemEnumId)

def reconcileResult = ec.service.sync()
        .name("reconciliation.ReconciliationCoreServices.reconcile#FilesByMapping")
        .parameters([
                reconciliationMappingId: reconciliationMappingId,
                file1Location          : file1Location,
                file2Location          : file2Location,
                file1Name              : file1SafeName,
                file2Name              : file2SafeName,
                file1SystemEnumId      : file1SystemEnumId,
                file2SystemEnumId      : file2SystemEnumId,
                file1Label             : system1Label,
                file2Label             : system2Label,
                hasHeader              : hasHeader,
                outputLocation         : tempLoc + '/output',
                sparkMaster            : sparkMaster ?: (ec.resource.properties['spark.master'] ?: "local[*]"),
                sparkAppName           : sparkAppName
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

logger.info("Generic reconciliation complete: type=${reconciliationType} diff=${diffFileName} differences=${differenceCount}")
