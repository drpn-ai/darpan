import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.ReconciliationMappingSupport
import darpan.facade.reconciliation.ReconciliationOutputSupport

import java.sql.Timestamp

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeUpper

String mappingId = normalize(reconciliationMappingId)
String inputFile1Name = ReconciliationOutputSupport.sanitizeUploadFileName(file1Name, "file1")
String inputFile2Name = ReconciliationOutputSupport.sanitizeUploadFileName(file2Name, "file2")
String file1TextValue = file1Text?.toString()
String file2TextValue = file2Text?.toString()
String requestedFile1SystemEnumId = normalize(file1SystemEnumId)
String requestedFile2SystemEnumId = normalize(file2SystemEnumId)
boolean hasHeaderValue = hasHeader == null ? true :
        (hasHeader instanceof Boolean ? hasHeader : ["Y", "YES", "TRUE", "1", "ON"].contains(normalizeUpper(hasHeader)))

if (!mappingId) ec.message.addError("reconciliationMappingId is required")
if (!inputFile1Name) ec.message.addError("file1Name is required")
if (!file1TextValue) ec.message.addError("file1Text is required")
if (!inputFile2Name) ec.message.addError("file2Name is required")
if (!file2TextValue) ec.message.addError("file2Text is required")
if ((requestedFile1SystemEnumId && !requestedFile2SystemEnumId) || (!requestedFile1SystemEnumId && requestedFile2SystemEnumId)) {
    ec.message.addError("file1SystemEnumId and file2SystemEnumId must be provided together when overriding mapping defaults.")
}

def findEnum = { String enumId ->
    if (!enumId) return null
    return ec.entity.find("moqui.basic.Enumeration")
            .condition("enumId", enumId)
            .useCache(true)
            .one()
}

def mapping = null
List mappingMembers = []
List<Map> sortedSystemOptions = []

if (!ec.message.hasError()) {
    mapping = ec.entity.find("darpan.mapping.ReconciliationMapping")
            .condition("reconciliationMappingId", mappingId)
            .useCache(false)
            .one()
    if (mapping == null) {
        ec.message.addError("Mapping '${mappingId}' was not found.")
    } else {
        TenantAccessSupport.requireTenantRecordAccess(ec, mapping, "Mapping '${mappingId}' was not found.",
                "Mapping '${mappingId}' is not available in your active tenant.")
    }
}

if (!ec.message.hasError()) {
    TenantAccessSupport.requireActiveTenantWriteAccess(ec, "Your active tenant only has view access for reconciliation runs.")
}

if (!ec.message.hasError()) {
    mappingMembers = ec.entity.find("darpan.mapping.ReconciliationMappingMember")
            .condition("reconciliationMappingId", mappingId)
            .useCache(false)
            .list() ?: []
    if (mappingMembers.isEmpty()) {
        ec.message.addError("Mapping '${mappingId}' does not contain any system members.")
    }
}

if (!ec.message.hasError()) {
    List<String> mappingReadinessIssues = ReconciliationMappingSupport.collectReadinessIssues(ec, mappingMembers)
    if (!mappingReadinessIssues.isEmpty()) {
        ec.message.addError("Mapping '${mappingId}' is not ready. ${mappingReadinessIssues.join(' ')}")
    }
}

if (!ec.message.hasError()) {
    sortedSystemOptions = mappingMembers.collect { member ->
        def systemEnum = findEnum(member.systemEnumId as String)
        [
                enumId    : member.systemEnumId as String,
                label     : FacadeSupport.enumLabel(systemEnum ?: [enumId: member.systemEnumId]),
                sequenceNum: systemEnum?.sequenceNum ?: Integer.MAX_VALUE
        ]
    }.sort { Map left, Map right ->
        (left.sequenceNum <=> right.sequenceNum) ?:
                ((left.label ?: "") <=> (right.label ?: "")) ?:
                ((left.enumId ?: "") <=> (right.enumId ?: ""))
    }
}

String resolvedFile1SystemEnumId = requestedFile1SystemEnumId
String resolvedFile2SystemEnumId = requestedFile2SystemEnumId

if (!ec.message.hasError() && !resolvedFile1SystemEnumId && !resolvedFile2SystemEnumId) {
    List<String> mappingSystemIds = sortedSystemOptions.collect { it.enumId }.findAll { it }.unique()
    if (mappingSystemIds.size() == 2) {
        resolvedFile1SystemEnumId = mappingSystemIds[0]
        resolvedFile2SystemEnumId = mappingSystemIds[1]
    } else {
        ec.message.addError("Mapping '${mappingId}' requires explicit system selection because it exposes ${mappingSystemIds.size()} systems.")
    }
}

if (!ec.message.hasError() && resolvedFile1SystemEnumId == resolvedFile2SystemEnumId) {
    ec.message.addError("file1SystemEnumId and file2SystemEnumId must be different.")
}

if (!ec.message.hasError()) {
    List<String> mappingSystemIds = mappingMembers.collect { normalize(it.systemEnumId) }.findAll { it }.unique()
    if (!mappingSystemIds.contains(resolvedFile1SystemEnumId)) {
        ec.message.addError("Mapping '${mappingId}' does not include system '${resolvedFile1SystemEnumId}'.")
    }
    if (!mappingSystemIds.contains(resolvedFile2SystemEnumId)) {
        ec.message.addError("Mapping '${mappingId}' does not include system '${resolvedFile2SystemEnumId}'.")
    }
}

String file1Label = null
String file2Label = null
if (!ec.message.hasError()) {
    file1Label = FacadeSupport.enumLabel(findEnum(resolvedFile1SystemEnumId) ?: [enumId: resolvedFile1SystemEnumId])
    file2Label = FacadeSupport.enumLabel(findEnum(resolvedFile2SystemEnumId) ?: [enumId: resolvedFile2SystemEnumId])

    Map serviceResult = ec.service.sync()
            .name("reconciliation.ReconciliationGenericServices.reconcile#GenericFiles")
            .parameters([
                    reconciliationMappingId: mappingId,
                    file1Name              : inputFile1Name,
                    file1Text              : file1TextValue,
                    file2Name              : inputFile2Name,
                    file2Text              : file2TextValue,
                    file1SystemEnumId      : resolvedFile1SystemEnumId,
                    file2SystemEnumId      : resolvedFile2SystemEnumId,
                    hasHeader              : hasHeaderValue,
                    sparkMaster            : sparkMaster,
                    sparkAppName           : sparkAppName ?: "GenericDiff"
            ])
            .call()

    if (!ec.message.hasError()) {
        File outputFile = null
        String diffLocationValue = normalize(serviceResult.diffLocation)
        if (diffLocationValue) {
            outputFile = diffLocationValue.startsWith("/") ?
                    new File(diffLocationValue) :
                    ec.resource.getLocationReference(diffLocationValue)?.getFile()
        }
        if ((outputFile == null || !outputFile.exists()) && serviceResult.diffFileName) {
            String scopedOutputLocation = TenantAccessSupport.resolveGenericOutputLocation(ec)
            File outputDir = ec.resource.getLocationReference(scopedOutputLocation)?.getFile()
            if (outputDir != null) outputFile = new File(outputDir, serviceResult.diffFileName.toString())
        }

        long sizeBytes = outputFile?.exists() ? outputFile.length() : 0L
        Timestamp createdDate = outputFile?.exists() ?
                new Timestamp(outputFile.lastModified()) :
                (Timestamp) ec.user.nowTimestamp

        Map generatedOutputDocument = [
                metadata: [
                        reconciliationMappingId  : mapping.reconciliationMappingId,
                        reconciliationMappingName: mapping.mappingName,
                        companyUserGroupId       : TenantAccessSupport.currentActiveTenantUserGroupId(ec),
                        reconciliation           : serviceResult.reconciliationType,
                        file1Label               : file1Label,
                        file2Label               : file2Label,
                ],
                summary : [
                        totalDifferences: serviceResult.differenceCount,
                        onlyInFile1Count: serviceResult.onlyInFile1Count,
                        onlyInFile2Count: serviceResult.onlyInFile2Count,
                ]
        ]

        Map generatedOutput = ReconciliationOutputSupport.buildGeneratedOutputDescriptor(
                serviceResult.diffFileName as String,
                generatedOutputDocument,
                sizeBytes,
                createdDate
        )
        if (serviceResult.reconciliationRunResultId) {
            generatedOutput.reconciliationRunResultId = serviceResult.reconciliationRunResultId
        }

        runResult = [
                reconciliationMappingId: mapping.reconciliationMappingId,
                mappingName            : mapping.mappingName,
                companyUserGroupId     : mapping.companyUserGroupId ?: TenantAccessSupport.currentActiveTenantUserGroupId(ec),
                reconciliationRunResultId: serviceResult.reconciliationRunResultId,
                resultDataManagerPath  : serviceResult.diffFileName,
                differenceCount        : serviceResult.differenceCount,
                onlyInFile1Count       : serviceResult.onlyInFile1Count,
                onlyInFile2Count       : serviceResult.onlyInFile2Count,
                file1Name              : inputFile1Name,
                file2Name              : inputFile2Name,
                file1SystemEnumId      : resolvedFile1SystemEnumId,
                file1SystemLabel       : file1Label,
                file2SystemEnumId      : resolvedFile2SystemEnumId,
                file2SystemLabel       : file2Label,
                validationErrors       : (serviceResult.validationErrors ?: []) as List,
                processingWarnings     : (serviceResult.processingWarnings ?: []) as List,
                generatedOutput        : generatedOutput
        ]
    }
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
