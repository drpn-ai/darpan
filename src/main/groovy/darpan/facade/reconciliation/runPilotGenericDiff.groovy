import darpan.facade.common.FacadeSupport
import darpan.facade.common.PilotAccessSupport
import darpan.facade.reconciliation.PilotMappingSupport
import darpan.facade.reconciliation.PilotReconciliationSupport
import darpan.reconciliation.core.ReconciliationServices

import java.sql.Timestamp

String mappingId = FacadeSupport.normalize(reconciliationMappingId)
String ruleSetIdValue = FacadeSupport.normalize(ruleSetId)
String compareScopeIdValue = FacadeSupport.normalize(compareScopeId)
String inputFile1Name = PilotReconciliationSupport.sanitizeUploadFileName(file1Name, "file1")
String inputFile2Name = PilotReconciliationSupport.sanitizeUploadFileName(file2Name, "file2")
String file1TextValue = file1Text?.toString()
String file2TextValue = file2Text?.toString()
String requestedFile1SystemEnumId = FacadeSupport.normalize(file1SystemEnumId)
String requestedFile2SystemEnumId = FacadeSupport.normalize(file2SystemEnumId)
boolean hasHeaderValue = FacadeSupport.normalizeBool(hasHeader, true)

if (mappingId && ruleSetIdValue) ec.message.addError("Provide either reconciliationMappingId or ruleSetId, not both.")
if (!mappingId && !ruleSetIdValue) ec.message.addError("Either reconciliationMappingId or ruleSetId is required.")
if (!inputFile1Name) ec.message.addError("file1Name is required")
if (!file1TextValue) ec.message.addError("file1Text is required")
if (!inputFile2Name) ec.message.addError("file2Name is required")
if (!file2TextValue) ec.message.addError("file2Text is required")
if ((requestedFile1SystemEnumId && !requestedFile2SystemEnumId) || (!requestedFile1SystemEnumId && requestedFile2SystemEnumId)) {
    ec.message.addError("file1SystemEnumId and file2SystemEnumId must be provided together when overriding defaults.")
}

def findEnum = { String enumId ->
    if (!enumId) return null
    return ec.entity.find("moqui.basic.Enumeration")
            .condition("enumId", enumId)
            .useCache(true)
            .one()
}

String runTypeValue = mappingId ? PilotReconciliationSupport.RUN_TYPE_MAPPING : PilotReconciliationSupport.RUN_TYPE_RULESET
String mappingNameValue = null
String ruleSetNameValue = null
String resolvedCompareScopeId = null
String compareScopeDescriptionValue = null
String objectTypeValue = null
String resolvedFile1SystemEnumId = requestedFile1SystemEnumId
String resolvedFile2SystemEnumId = requestedFile2SystemEnumId
String file1Label = null
String file2Label = null

def mapping = null
List mappingMembers = []
List<Map> sortedSystemOptions = []
Map compareScopeConfig = null

if (!ec.message.hasError() && mappingId) {
    mapping = ec.entity.find("darpan.mapping.ReconciliationMapping")
            .condition("reconciliationMappingId", mappingId)
            .useCache(false)
            .one()
    if (mapping == null) {
        ec.message.addError("Mapping '${mappingId}' was not found.")
    } else {
        PilotAccessSupport.requireCompanyRecordAccess(ec, mapping, "Mapping '${mappingId}' was not found.",
                "Mapping '${mappingId}' is not available in your active company.")
        mappingNameValue = FacadeSupport.normalize(mapping.mappingName)
    }
}

if (!ec.message.hasError() && mappingId) {
    mappingMembers = ec.entity.find("darpan.mapping.ReconciliationMappingMember")
            .condition("reconciliationMappingId", mappingId)
            .useCache(false)
            .list() ?: []
    if (mappingMembers.isEmpty()) {
        ec.message.addError("Mapping '${mappingId}' does not contain any system members.")
    }
}

if (!ec.message.hasError() && mappingId) {
    List<String> pilotReadinessIssues = PilotMappingSupport.collectPilotReadinessIssues(ec, mappingMembers)
    if (!pilotReadinessIssues.isEmpty()) {
        ec.message.addError("Mapping '${mappingId}' is not pilot-ready. ${pilotReadinessIssues.join(' ')}")
    }
}

if (!ec.message.hasError() && mappingId) {
    sortedSystemOptions = mappingMembers.collect { member ->
        def systemEnum = findEnum(member.systemEnumId as String)
        [
                enumId     : member.systemEnumId as String,
                label      : FacadeSupport.enumLabel(systemEnum ?: [enumId: member.systemEnumId]),
                sequenceNum: systemEnum?.sequenceNum ?: Integer.MAX_VALUE
        ]
    }.sort { Map left, Map right ->
        (left.sequenceNum <=> right.sequenceNum) ?:
                ((left.label ?: "") <=> (right.label ?: "")) ?:
                ((left.enumId ?: "") <=> (right.enumId ?: ""))
    }
}

if (!ec.message.hasError() && mappingId && !resolvedFile1SystemEnumId && !resolvedFile2SystemEnumId) {
    List<String> mappingSystemIds = sortedSystemOptions.collect { it.enumId }.findAll { it }.unique()
    if (mappingSystemIds.size() == 2) {
        resolvedFile1SystemEnumId = mappingSystemIds[0]
        resolvedFile2SystemEnumId = mappingSystemIds[1]
    } else {
        ec.message.addError("Mapping '${mappingId}' requires explicit system selection because it exposes ${mappingSystemIds.size()} systems.")
    }
}

if (!ec.message.hasError() && mappingId && resolvedFile1SystemEnumId == resolvedFile2SystemEnumId) {
    ec.message.addError("file1SystemEnumId and file2SystemEnumId must be different.")
}

if (!ec.message.hasError() && mappingId) {
    List<String> mappingSystemIds = mappingMembers.collect { FacadeSupport.normalize(it.systemEnumId) }.findAll { it }.unique()
    if (!mappingSystemIds.contains(resolvedFile1SystemEnumId)) {
        ec.message.addError("Mapping '${mappingId}' does not include system '${resolvedFile1SystemEnumId}'.")
    }
    if (!mappingSystemIds.contains(resolvedFile2SystemEnumId)) {
        ec.message.addError("Mapping '${mappingId}' does not include system '${resolvedFile2SystemEnumId}'.")
    }
}

if (!ec.message.hasError() && mappingId) {
    file1Label = FacadeSupport.enumLabel(findEnum(resolvedFile1SystemEnumId) ?: [enumId: resolvedFile1SystemEnumId])
    file2Label = FacadeSupport.enumLabel(findEnum(resolvedFile2SystemEnumId) ?: [enumId: resolvedFile2SystemEnumId])
}

if (!ec.message.hasError() && ruleSetIdValue) {
    try {
        compareScopeConfig = ReconciliationServices.resolveRuleSetCompareScopeConfig(
                ec,
                ruleSetIdValue,
                compareScopeIdValue,
                requestedFile1SystemEnumId,
                requestedFile2SystemEnumId
        )
    } catch (IllegalArgumentException e) {
        ec.message.addError(e.message)
    }
}

if (!ec.message.hasError() && ruleSetIdValue) {
    def ruleSet = ec.entity.find("darpan.rule.RuleSet")
            .condition("ruleSetId", ruleSetIdValue)
            .disableAuthz()
            .useCache(false)
            .one()
    if (ruleSet == null) {
        ec.message.addError("RuleSet '${ruleSetIdValue}' was not found.")
    } else {
        ruleSetNameValue = FacadeSupport.normalize(ruleSet.ruleSetName) ?: ruleSetIdValue
    }
}

if (!ec.message.hasError() && ruleSetIdValue) {
    resolvedCompareScopeId = FacadeSupport.normalize(compareScopeConfig?.compareScopeId)
    def compareScope = ec.entity.find("darpan.rule.RuleSetCompareScope")
            .condition("compareScopeId", resolvedCompareScopeId)
            .condition("ruleSetId", ruleSetIdValue)
            .disableAuthz()
            .useCache(false)
            .one()
    if (compareScope == null) {
        ec.message.addError("Compare scope '${resolvedCompareScopeId ?: compareScopeIdValue}' was not found for RuleSet '${ruleSetIdValue}'.")
    } else {
        compareScopeDescriptionValue = FacadeSupport.normalize(compareScope.description)
        objectTypeValue = FacadeSupport.normalize(compareScope.objectType)
    }
    resolvedFile1SystemEnumId = FacadeSupport.normalize(compareScopeConfig?.file1SystemEnumId)
    resolvedFile2SystemEnumId = FacadeSupport.normalize(compareScopeConfig?.file2SystemEnumId)
    file1Label = FacadeSupport.normalize(compareScopeConfig?.file1Label) ?: "File 1"
    file2Label = FacadeSupport.normalize(compareScopeConfig?.file2Label) ?: "File 2"
}

if (!ec.message.hasError() && ruleSetIdValue && resolvedFile1SystemEnumId == resolvedFile2SystemEnumId) {
    ec.message.addError("file1SystemEnumId and file2SystemEnumId must be different.")
}

if (!ec.message.hasError()) {
    Map<String, Object> serviceParams = [
            file1Name    : inputFile1Name,
            file1Text    : file1TextValue,
            file2Name    : inputFile2Name,
            file2Text    : file2TextValue,
            hasHeader    : hasHeaderValue,
            sparkMaster  : sparkMaster,
            sparkAppName : sparkAppName ?: "PilotGenericDiff"
    ]
    if (resolvedFile1SystemEnumId) serviceParams.file1SystemEnumId = resolvedFile1SystemEnumId
    if (resolvedFile2SystemEnumId) serviceParams.file2SystemEnumId = resolvedFile2SystemEnumId
    if (mappingId) serviceParams.reconciliationMappingId = mappingId
    if (ruleSetIdValue) {
        serviceParams.ruleSetId = ruleSetIdValue
        if (resolvedCompareScopeId) serviceParams.compareScopeId = resolvedCompareScopeId
    }

    Map serviceResult = ec.service.sync()
            .name("reconciliation.ReconciliationGenericServices.reconcile#GenericFiles")
            .parameters(serviceParams)
            .call()

    if (!ec.message.hasError()) {
        File outputFile = null
        String diffLocationValue = FacadeSupport.normalize(serviceResult.diffLocation)
        if (diffLocationValue) {
            outputFile = diffLocationValue.startsWith("/") ?
                    new File(diffLocationValue) :
                    ec.resource.getLocationReference(diffLocationValue)?.getFile()
        }
        if ((outputFile == null || !outputFile.exists()) && serviceResult.diffFileName) {
            String scopedOutputLocation = PilotAccessSupport.resolveGenericOutputLocation(ec)
            File outputDir = ec.resource.getLocationReference(scopedOutputLocation)?.getFile()
            if (outputDir != null) outputFile = new File(outputDir, serviceResult.diffFileName.toString())
        }

        long sizeBytes = outputFile?.exists() ? outputFile.length() : 0L
        Timestamp createdDate = outputFile?.exists() ?
                new Timestamp(outputFile.lastModified()) :
                (Timestamp) ec.user.nowTimestamp

        Map<String, Object> outputDocument = PilotReconciliationSupport.parseGeneratedOutputFile(outputFile)
        if (outputDocument.isEmpty()) {
            outputDocument = [
                    metadata: [
                            companyUserGroupId       : PilotAccessSupport.currentActiveCompanyUserGroupId(ec),
                            reconciliationMappingId  : mapping?.reconciliationMappingId,
                            reconciliationMappingName: mappingNameValue,
                            ruleSetId                : ruleSetIdValue,
                            ruleSetName              : ruleSetNameValue,
                            compareScopeId           : resolvedCompareScopeId,
                            compareScopeDescription  : compareScopeDescriptionValue,
                            objectType               : objectTypeValue,
                            reconciliation           : serviceResult.reconciliationType,
                            file1Label               : file1Label,
                            file2Label               : file2Label,
                    ],
                    summary : [
                            totalDifferences           : serviceResult.differenceCount,
                            onlyInFile1Count           : serviceResult.onlyInFile1Count,
                            onlyInFile2Count           : serviceResult.onlyInFile2Count,
                            missingObjectDifferenceCount: serviceResult.missingObjectDifferenceCount,
                            ruleDifferenceCount        : serviceResult.ruleDifferenceCount,
                    ]
            ]
        }

        Map<String, Object> generatedOutput = PilotReconciliationSupport.buildGeneratedOutputDescriptor(
                serviceResult.diffFileName as String,
                outputDocument,
                sizeBytes,
                createdDate
        )

        generatedOutput.runType = generatedOutput.runType ?: runTypeValue
        generatedOutput.runName = generatedOutput.runName ?: (mappingNameValue ?: compareScopeDescriptionValue ?: ruleSetNameValue)
        generatedOutput.reconciliationMappingId = generatedOutput.reconciliationMappingId ?: mapping?.reconciliationMappingId
        generatedOutput.mappingName = generatedOutput.mappingName ?: mappingNameValue
        generatedOutput.ruleSetId = generatedOutput.ruleSetId ?: ruleSetIdValue
        generatedOutput.ruleSetName = generatedOutput.ruleSetName ?: ruleSetNameValue
        generatedOutput.compareScopeId = generatedOutput.compareScopeId ?: resolvedCompareScopeId
        generatedOutput.compareScopeDescription = generatedOutput.compareScopeDescription ?: compareScopeDescriptionValue
        generatedOutput.objectType = generatedOutput.objectType ?: objectTypeValue
        generatedOutput.file1Label = generatedOutput.file1Label ?: file1Label
        generatedOutput.file2Label = generatedOutput.file2Label ?: file2Label

        runResult = [
                runType                : generatedOutput.runType ?: runTypeValue,
                runName                : generatedOutput.runName ?: (mappingNameValue ?: compareScopeDescriptionValue ?: ruleSetNameValue),
                reconciliationMappingId: generatedOutput.reconciliationMappingId,
                mappingName            : generatedOutput.mappingName,
                ruleSetId              : generatedOutput.ruleSetId,
                ruleSetName            : generatedOutput.ruleSetName,
                compareScopeId         : generatedOutput.compareScopeId,
                compareScopeDescription: generatedOutput.compareScopeDescription,
                objectType             : generatedOutput.objectType,
                file1Name              : inputFile1Name,
                file2Name              : inputFile2Name,
                file1SystemEnumId      : resolvedFile1SystemEnumId,
                file1SystemLabel       : generatedOutput.file1Label ?: file1Label,
                file2SystemEnumId      : resolvedFile2SystemEnumId,
                file2SystemLabel       : generatedOutput.file2Label ?: file2Label,
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
