import darpan.common.DarpanEntityConstants
import darpan.facade.common.DataManagerSupport
import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.facade.reconciliation.ReconciliationApiWindowSupport
import darpan.facade.reconciliation.ReconciliationOutputSupport
import darpan.facade.reconciliation.ReconciliationSavedRunSupport
import darpan.facade.reconciliation.RunVerificationSupport
import darpan.facade.reconciliation.RunObservability
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import darpan.reconciliation.core.ReconciliationServices
import darpan.reconciliation.notification.TenantNotificationSupport
import groovy.json.JsonOutput
import org.slf4j.LoggerFactory

import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDateTime

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeBool
import static darpan.common.ValueSupport.normalizeInt

def logger = LoggerFactory.getLogger("darpan.facade.reconciliation.SavedRunDiff")

// The ENTIRE run executes detached from any caller/request transaction: the JSON-RPC screen path
// wraps this service call in the request's JTA transaction (60s default timeout), which Bitronix
// marks rollback-only long before a real extract finishes — killing the run at the next entity
// touch and rolling back the run + step rows invisibly. Detaching lets observability writes
// commit live in their own short transactions (this service is transaction="ignore" by design);
// the caller's transaction is resumed untouched for the response path. Out-parameters are
// unaffected: they are undeclared binding writes, which pass through this closure to the script
// binding. See SavedRunTransactionDetachTests.
return ReconciliationSavedRunSupport.runDetachedFromCallerTransaction(ec) { ->

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
boolean hasHeaderValue = normalizeBool(hasHeader, true)
String windowStartLocalDateValue = normalize(windowStartLocalDate)
String windowEndLocalDateValue = normalize(windowEndLocalDate)
Timestamp requestedWindowStartDateValue = null
Timestamp requestedWindowEndDateValue = null
Timestamp windowStartDateValue = null
Timestamp windowEndDateValue = null
// The zone the window was anchored in. normalizeCalendarWindow has always returned it and the
// caller has always dropped it, which is why a finished run could not say which calendar day its
// window covered — the screen had to guess with the viewer's zone.
String windowTimeZoneValue = null

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
        windowTimeZoneValue = normalizedApiWindow.timeZone?.toString()?.trim() ?: null
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

// --- Observability (run status lifecycle): RunObservability owns statusEnumId from here on.
// beginRun creates the run row in RUNNING; every observability write is best-effort and commits
// in its own short transaction, so these calls are safe inside this transaction="ignore" service.
// Pre-validated or access-denied requests (ec errors already present here) never mint a run row:
// obsRunId stays null and every observability call below is skipped or a no-op. Errors that occur
// AFTER beginRun (resolution/source validation) still end the minted row terminal FAILED.
String obsRunId = ec.message.hasError() ? null : RunObservability.beginRun(ec, [
        savedRunId        : savedRunIdValue,
        companyUserGroupId: TenantAccessSupport.currentActiveTenantUserGroupId(ec),
        createdByUserId   : TenantAccessSupport.currentUserId(ec),
        // Recorded on the run itself so the run-result screen can name the window while the run is
        // still going: no diff document exists before WRITE_OUTPUT, and a manual run has no
        // execution row. Null for file-mode runs, which beginRun skips.
        windowStartDate   : windowStartDateValue,
        windowEndDate     : windowEndDateValue,
        windowTimeZone    : windowTimeZoneValue,
])
Map<String, Object> obsCtx = [companyUserGroupId: TenantAccessSupport.currentActiveTenantUserGroupId(ec)]
boolean obsTerminalWritten = false
def obsStep = obsRunId ? RunObservability.beginStep(ec, obsRunId, obsCtx, RunObservability.STAGE_RESOLVE) : null
String obsStage = RunObservability.STAGE_RESOLVE
boolean obsRuleExecutionFailed = false
boolean obsNoData = false

def enumLabel = { String enumId ->
    return FacadeSupport.enumLabel(FacadeSupport.findEnum(ec, enumId) ?: [enumId: enumId])
}
def requireUploadInput = { String filePrefix, String inputName, String inputText ->
    if (!inputName) ec.message.addError("${filePrefix}Name is required")
    if (!inputText) ec.message.addError("${filePrefix}Text is required")
}
def sideToken = { String fileSide ->
    fileSide == ReconciliationSavedRunSupport.FILE_SIDE_1 ? "file1" : "file2"
}
def isApiSource = { Object source ->
    // Both AUT_SRC_API and AUT_SRC_DB are extracted (non-upload) sources: extractApiSource below
    // dispatches through the SourceSystemConnector registry keyed by systemEnumId/sourceConfigType,
    // not by sourceTypeEnumId, so a DB source flows the same registry-driven extraction path as an
    // API source (MACH database-source epic Task 2 follow-up).
    String sourceType = normalize(source?.sourceTypeEnumId)
    sourceType == ReconciliationSavedRunSupport.SOURCE_TYPE_API || sourceType == ReconciliationSavedRunSupport.SOURCE_TYPE_DB
}
def sourceLabel = { Object source, String fallback ->
    enumLabel(normalize(source?.systemEnumId) ?: fallback)
}
def runInternalService = { String serviceName, Map params ->
    return (ec.service.sync()
            .name(serviceName)
            .parameters((params ?: [:]).findAll { entry -> entry.value != null })
            .disableAuthz()
            .call() ?: [:]) as Map
}
// Lifted from resolveOutputFile's primary resolution branch below (exact same location-string ->
// File semantics) so the exchange verify pass can resolve the manifest sidecar location, which has
// no serviceResult-style diffFileName fallback to search for.
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
// Writes run-result ARTIFACT fields only (paths, names, counts, dates). statusEnumId is owned
// by RunObservability (beginRun/completeRun/failRun) — this closure must never set it. It always
// updates the run row created by RunObservability.beginRun (found by reconciliationRunResultId)
// and never creates a competing row for the same run.
def persistRunResult = { Map<String, Object> fields ->
    String resultPath = DataManagerSupport.normalizeRelativePath(fields.resultDataManagerPath)
    String existingRunResultId = normalize(fields.reconciliationRunResultId)
    // Degenerate case: if RunObservability.beginRun's write failed, obsRunId comes back null and
    // existingRunResultId is never populated. A later persist call that still has a
    // resultDataManagerPath falls through this guard and creates a fresh run-result row here,
    // whose statusEnumId is never set to RUNNING (the entity default applies instead). Accepted
    // best-effort behavior for now; revisit when the watchdog/cleanup phase lands.
    if (!resultPath && !existingRunResultId) return null

    return ec.transaction.runUseOrBegin(30, "Error saving reconciliation run result", {
        def runResultValue = existingRunResultId ?
                TenantScopedFinder.findTenantScoped(ec, DarpanEntityConstants.RECONCILIATION_RUN_RESULT)
                        .condition("reconciliationRunResultId", existingRunResultId)
                        .useCache(false)
                        .one() :
                null
        boolean creating = runResultValue == null
        if (creating) runResultValue = ec.entity.makeValue(DarpanEntityConstants.RECONCILIATION_RUN_RESULT)

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
// Registry-driven dispatch (MACH P1): the SourceSystemConnector row supplies the extract
// service name, config parameter name, window parameter names, and preserveWindowInstants —
// the same resolver the automation path uses, so connector dispatch lives in one place and
// onboarding a new source needs no edit here.
def extractApiSource = { Object source, String fileSide, Map artifactContext, Map progressContext = null ->
    String label = sourceLabel(source, fileSide)
    String sourceConfigType = normalize(source?.sourceConfigType)
    Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, normalize(source?.systemEnumId))
    if (connector == null || normalize(connector.expectedSourceConfigType) != sourceConfigType) {
        connector = SourceSystemConnectorSupport.resolveByExpectedSourceConfigType(ec, sourceConfigType)
    }
    String extractServiceName = connector == null ? null : normalize(connector.extractServiceName)
    // A connector without an extractServiceName (e.g. NETSUITE) does not support interactive
    // extraction — same outcome as no connector at all.
    if (extractServiceName == null) {
        ec.message.addError("${label} API source type '${sourceConfigType ?: "unknown"}' is not supported for manual saved-run execution.")
        return [:]
    }
    // Same defense-in-depth fence as the automation sink: a registry row cannot point the
    // authz-relaxed dispatch at an arbitrary internal service.
    if (!SourceSystemConnectorSupport.isAllowedExtractorServiceShape(extractServiceName)) {
        ec.message.addError("${label} connector for '${sourceConfigType}' has an invalid extract service configuration.")
        return [:]
    }
    String configId = normalize(source?.sourceConfigId)
    if (!configId) {
        ec.message.addError("${label} API source requires a ${connector.endpointLabel ?: sourceConfigType} config.")
        return [:]
    }

    Map<String, Object> sourceApiWindow = tenantApiWindow ?: resolveTenantApiWindow()
    if (ec.message.hasError()) return [:]
    Timestamp sourceWindowStartDate = (Timestamp) sourceApiWindow.windowStartDate
    Timestamp sourceWindowEndDate = (Timestamp) sourceApiWindow.windowEndDate

    String token = sideToken(fileSide)
    String fileNameValue = ReconciliationOutputSupport.sanitizeUploadFileName("${label}-orders-api.json", "${token}-api.json")
    Map<String, Object> extractParams = [
            (normalize(connector.configParameterName) ?: "sourceConfigId"): configId,
            (normalize(connector.dateFromParameterName) ?: "windowStart") : formatApiWindow(sourceWindowStartDate),
            (normalize(connector.dateToParameterName) ?: "windowEnd")     : formatApiWindow(sourceWindowEndDate),
            outputLocation: DataManagerSupport.childLocation(artifactContext.location as String, "${token}-api"),
            fileName      : DataManagerSupport.runArtifactFileName(artifactContext.runToken, token, fileNameValue),
    ] as Map<String, Object>
    if (connector.preserveWindowInstants) extractParams.preserveWindowInstants = true

    // Config over code, mirrors AutomationExecutionSupport.applyWindowFieldParameter: the connector
    // names the record date field its extract window filters on. Blank leaves the parameter unset so
    // the extractor keeps its own default (orderDate). Without this, an interactive run and a
    // scheduled automation of the same saved run would query different date fields the moment
    // windowFieldName is set to anything other than that default (e.g. lastUpdatedTxStamp).
    String windowFieldName = normalize(connector.windowFieldName)
    if (windowFieldName) extractParams.windowFieldName = windowFieldName

    // Registry-driven record projection: when the connector declares a keep-fields parameter, pass
    // the fields reconciliation actually needs so the extractor trims records before writing
    // (99k-order OMS windows drop from ~1.4 GB to tens of MB). Projection is disabled (full records
    // sent) when any rule's field path on this side can't be reduced to a top-level record field,
    // including raw-DRL/presence-only rules with no structured path on either side — see
    // resolveExtractKeepFields.
    String keepFieldsParameterName = normalize(connector.keepFieldsParameterName)
    if (keepFieldsParameterName) {
        List<String> keepFields = ReconciliationSavedRunSupport.resolveExtractKeepFields(ec, source, connector.keepFieldsBase)
        if (keepFields) extractParams[keepFieldsParameterName] = keepFields
    }

    // Registry-driven record exclusion: when the connector declares a filter parameter, hand it the
    // source's configured rules so unwanted records are dropped inside the getter, before they reach
    // the extract file. Connectors that declare no parameter never receive filters.
    String filterParameterName = normalize(connector.filterParameterName)
    if (filterParameterName) {
        List<Map<String, Object>> excludeFilters = ReconciliationSavedRunSupport.resolveExtractExcludeFilters(ec, source)
        if (excludeFilters) extractParams[filterParameterName] = excludeFilters
    }

    // Live extract progress: the extractor heartbeats a running record count onto this stage's
    // step so the live view can show the count climbing during a multi-minute paged extract. The
    // count is the progress and needs no denominator; expectedRecordCount is optional and only
    // adds a percent, which only the second side can have (it divides against the first side's
    // finished count for the same window). Gating the whole thing on a denominator is what left
    // the first extract reporting nothing at all.
    if (progressContext?.reconciliationRunResultId) {
        extractParams.reconciliationRunResultId = progressContext.reconciliationRunResultId
        extractParams.progressStageCode = progressContext.progressStageCode
        if (progressContext?.expectedRecordCount) {
            extractParams.expectedRecordCount = progressContext.expectedRecordCount
        }
    }

    Map extraction = runInternalService(extractServiceName, extractParams)
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
def writeRuleSetOutput = { Map serviceResult, Map savedRun, String file1Label, String file2Label, Map artifactContext ->
    Map output = ReconciliationServices.writeDiffDatasetOutput(
            ec,
            serviceResult.diffDf,
            artifactContext.location as String,
            DataManagerSupport.runArtifactFileName(artifactContext.runToken, "result", "result.json"),
            "${artifactContext.runToken}-diff.json",
            [
                    timestamp              : ReconciliationServices.formatMetadataTimestamp(ec.user.nowTimestamp),
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

// Exchange pair verify stage (spec 2026-07-30): the OMS-side extract may have written an
// exchange-manifest sidecar (excluded exchange orders). When one side's connector declares
// pairLookupServiceName and the other declares exchangeStateLookupServiceName, each manifest
// pair is point-checked against both sources of record. Best-effort like the missing-diff pass.


def runExchangeVerificationPass = { Map serviceResult, Object file1Source, Object file2Source,
                                    Map file1Result, Map file2Result, String file1Label, String file2Label,
                                    String runOwnerUserGroupId ->
    if (serviceResult.ruleExecutionFailed == true) return
    // Side identification (by declared capability, not system id), the manifest sidecar, the fenced
    // OMS pair lookup and the Shopify sweep all live in RunVerificationSupport now, shared with the
    // scheduled path. The window guard lives there too: presence semantics need one.
    Map prepared = RunVerificationSupport.prepareExchangePairPass([
            ec                 : ec,
            diffFile           : resolveOutputFile(serviceResult),
            sides              : [[source: file1Source, extractResult: file1Result, label: file1Label, fileSide: "FILE_1"],
                                  [source: file2Source, extractResult: file2Result, label: file2Label, fileSide: "FILE_2"]],
            windowStartMillis  : windowStartDate?.time,
            windowEndMillis    : windowEndDate?.time,
            runOwnerUserGroupId: runOwnerUserGroupId,
            dispatcher         : { String serviceName, Map serviceParams -> runInternalService(serviceName, serviceParams) },
    ])
    if (prepared == null) return

    def exchangeStep = obsRunId ? RunObservability.beginStep(ec, obsRunId, obsCtx, RunObservability.STAGE_VERIFY_EXCHANGE) : null
    Map verification
    try {
        verification = (Map) ((Closure) prepared.run).call()
    } catch (Throwable t) {
        verification = [performed: true, appendedCount: 0, lookupFailed: true, sweepExchangeCount: 0,
                warnings: ["Exchange presence check failed: ${normalize(t.message) ?: t.class.simpleName}".toString()], auditNote: null] as Map
    }
    if (ec.message.hasError()) {
        verification.warnings = ((verification.warnings ?: []) as List) + ((ec.message.getErrors() ?: []) as List)
        verification.lookupFailed = true
        ec.message.clearErrors()
    }
    RunVerificationSupport.applyExchangeOutcome(serviceResult, verification, (String) prepared.omsFileSide)
    RunObservability.endStep(ec, exchangeStep,
            verification.lookupFailed ? RunObservability.STATUS_FAILED : RunObservability.STATUS_SUCCESS,
            [recordCount : verification.sweepExchangeCount ?: 0,
             errorMessage: verification.lookupFailed && verification.warnings ? verification.warnings.first().toString() : null,
             metricsJson : JsonOutput.toJson(RunVerificationSupport.exchangePairMetrics(
                     verification, (String) prepared.omsLabel, (String) prepared.shopifyLabel))])
}

// The missing-diff pass. Every decision, the STAGE_VERIFY_MISSING step, the count fold and the skip report
// live in RunVerificationSupport, called identically by AutomationExecutionSupport — an automation
// is the thing that FIRES a run, and after the trigger it must execute the same process. What stays
// here is this script's own dispatch seam and observability handles.
//
// No kill switch on this path: `…automation.verifyMissingDiffs` was introduced to give operators a
// way to stop a slow scheduled pass, and a manual run has always verified unconditionally. Wiring it
// in here would silently change what the Run button does on any deployment already setting it.
def runVerificationPass = { Map serviceResult, Object file1Source, Object file2Source, String file1Label, String file2Label,
                            String runOwnerUserGroupId ->
    RunVerificationSupport.runMissingDiffPass([
            ec                 : ec,
            runResultId        : obsRunId,
            stepCtx            : obsCtx,
            serviceResult      : serviceResult,
            diffFile           : { resolveOutputFile(serviceResult) },
            file1Source        : file1Source,
            file2Source        : file2Source,
            file1Label         : file1Label,
            file2Label         : file2Label,
            runOwnerUserGroupId: runOwnerUserGroupId,
            dispatcher         : { String serviceName, Map serviceParams -> runInternalService(serviceName, serviceParams) },
    ])
}

// Return presence verify stage (DAR-BE-018; reduced 2026-08-18 — returns-refund-grain-alignment plan,
// Task 3): resolve each side's connector, gate on the pair of returns connectors' resolved
// systemEnumIds (never on hardcoded source names, so an orders reconciliation never enters this path
// and no existing saved run changes behaviour), open a STAGE_VERIFY_RETURNS step, call the file-facing filter,
// complete the step. Once the Shopify/OMS grains matched (Task 1), the generic ruleset compare already
// found the real missing-in-Shopify / missing-in-OMS rows via an ordinary join — this stage no longer
// re-derives presence itself. It now only GRADES rows the compare already reported missing, converting
// grace-window-young ones to pending (removed from the diff) and gating pre-window Shopify events out
// of the missing-in-OMS count; see ReturnPresenceVerificationSupport's class doc. That grading reads
// each row's OWN embedded date straight out of the diff document the compare already wrote.
//
// It does take the OMS extract again as of 2026-08-20, for the superseded-sibling rule alone: that
// rule asks "did OMS book a return for this ORDER at all", which no row in the diff can answer, since
// a matched event is by definition not in the diff. Grace and window grading remain file-free.
def runReturnPresenceVerificationPass = { Map serviceResult, Object file1Source, Object file2Source,
                                          Map file1Result, Map file2Result,
                                          String file1Label, String file2Label, String runOwnerUserGroupId ->
    if (serviceResult.ruleExecutionFailed == true) return
    // Side identification, the OMS extract sidecar and the cancelled-order lookup all live in
    // RunVerificationSupport now, shared with the scheduled path. Keeping them here is what let the
    // two entry points run different verification for the same run; the only things that stay local
    // are this script's observability handles and its count fold.
    Map prepared = RunVerificationSupport.prepareReturnPresencePass([
            ec                 : ec,
            diffFile           : resolveOutputFile(serviceResult),
            sides              : [[source: file1Source, extractResult: file1Result, label: file1Label],
                                  [source: file2Source, extractResult: file2Result, label: file2Label]],
            file1Label         : file1Label,
            file2Label         : file2Label,
            // May be null for a saved run with no window (e.g. two static files) — the filter
            // degrades to grace-only behaviour then.
            windowStartMillis  : windowStartDate?.time,
            runOwnerUserGroupId: runOwnerUserGroupId,
            dispatcher         : { String serviceName, Map serviceParams -> runInternalService(serviceName, serviceParams) },
    ])
    if (prepared == null) return

    long missingInFile1 = RunVerificationSupport.missingCount(serviceResult, "missingInFile1Count")
    long missingInFile2 = RunVerificationSupport.missingCount(serviceResult, "missingInFile2Count")
    def returnsStep = obsRunId ? RunObservability.beginStep(ec, obsRunId, obsCtx, RunObservability.STAGE_VERIFY_RETURNS) : null
    Map returnsVerification
    boolean checkFailed = false
    try {
        returnsVerification = (Map) ((Closure) prepared.run).call()
    } catch (Throwable t) {
        checkFailed = true
        returnsVerification = [performed: false, rewritten: false, removedCount: 0,
                warnings: ["Return presence check failed: ${normalize(t.message) ?: t.class.simpleName}".toString()]] as Map
    }
    if (ec.message.hasError()) {
        returnsVerification.warnings = ((returnsVerification.warnings ?: []) as List) + ((ec.message.getErrors() ?: []) as List)
        ec.message.clearErrors()
    }
    // The filter REMOVES rows from the diff file it already found (grace/window suppression), the
    // mirror image of the old append — so counts move DOWN here, which is exactly what
    // applyVerificationOutcome's clamped subtraction does for the missing-diff pass.
    RunVerificationSupport.applyVerificationOutcome(serviceResult, returnsVerification, missingInFile1, missingInFile2)
    RunObservability.endStep(ec, returnsStep,
            checkFailed ? RunObservability.STATUS_FAILED : RunObservability.STATUS_SUCCESS,
            [recordCount : (returnsVerification.removedCount ?: 0),
             errorMessage: checkFailed && returnsVerification.warnings ? returnsVerification.warnings.first().toString() : null,
             metricsJson : JsonOutput.toJson(RunVerificationSupport.returnPresenceMetrics(
                     returnsVerification, (String) prepared.omsLabel, (String) prepared.shopifyLabel))])
}

try {
    def mapping = null
    if (!ec.message.hasError()) {
        mapping = ec.entity.find(DarpanEntityConstants.RECONCILIATION_MAPPING)
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
        // Observability: the legacy mapping path runs as a single COMPARE stage — extraction and
        // output writing happen inside run#GenericDiff, so there are no per-side EXTRACT brackets.
        RunObservability.endStep(ec, obsStep, RunObservability.STATUS_SUCCESS, [:])
        obsStep = obsRunId ? RunObservability.beginStep(ec, obsRunId, obsCtx, RunObservability.STAGE_COMPARE) : null
        obsStage = RunObservability.STAGE_COMPARE
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
        if (!ec.message.hasError()) {
            RunObservability.endStep(ec, obsStep, RunObservability.STATUS_SUCCESS, [recordCount: runResult.differenceCount])
            obsStep = null
        }
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
            // Same system is fine when the two sides are different sources - that is exactly how two
            // OFBiz instances are compared (both DATABASE, different source configs). Same system AND
            // same source is still a self-comparison. Mirrors the create#/save#RuleSetRun rule.
            String file1SourceConfigIdForPairing = normalize(file1Source?.sourceConfigId) ?: ""
            String file2SourceConfigIdForPairing = normalize(file2Source?.sourceConfigId) ?: ""
            if (resolvedFile1SystemEnumId == resolvedFile2SystemEnumId
                    && file1SourceConfigIdForPairing == file2SourceConfigIdForPairing) {
                ec.message.addError("file1 and file2 are the same source — use a different system or a different source config.")
            }

            if (!ec.message.hasError() && hasApiInput) {
                if (!windowStartDateValue || !windowEndDateValue) {
                    ec.message.addError("windowStartDate and windowEndDate are required when a saved-run source is API- or database-backed.")
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
                // Observability: saved-run/rule-set/source resolution completed.
                RunObservability.endStep(ec, obsStep, RunObservability.STATUS_SUCCESS, [:])
                obsStep = null
                String file1Label = enumLabel(resolvedFile1SystemEnumId)
                String file2Label = enumLabel(resolvedFile2SystemEnumId)

                if (hasApiInput) {
                    tenantApiWindow = resolveTenantApiWindow()
                    if (!ec.message.hasError()) {
                        Map artifactContext = buildRunArtifactContext(savedRun.savedRunId as String)
                        Map file1Result = [:]
                        Map file2Result = [:]
                        String reconciliationRunResultId = persistRunResult([
                            reconciliationRunResultId: obsRunId,
                            savedRunId          : savedRun.savedRunId,
                            savedRunType        : savedRun.runType ?: ReconciliationSavedRunSupport.RUN_TYPE_RULESET,
                            ruleSetId           : savedRun.ruleSetId,
                            compareScopeId      : savedRun.compareScopeId,
                            companyUserGroupId  : savedRun.companyUserGroupId,
                            file1Name           : file1UsesApiSource ? null : inputFile1Name,
                            file2Name           : file2UsesApiSource ? null : inputFile2Name,
                            createdDate         : ec.user.nowTimestamp,
                            startedDate         : ec.user.nowTimestamp,
                        ])
                        // Self-review #16: hold Spark Datasets persisted by reconcile#RuleSetCompareScope so
                        // the finally unpersists them on every exit path of this saved-run execution.
                        List ruleSetPersistedSources = []
                        try {
                            RunObservability.checkpointCancel(ec, obsRunId)
                            obsStep = obsRunId ? RunObservability.beginStep(ec, obsRunId, obsCtx, RunObservability.STAGE_EXTRACT_FILE1) : null
                            obsStage = RunObservability.STAGE_EXTRACT_FILE1
                            file1Result = file1UsesApiSource ?
                                    extractApiSource(file1Source, ReconciliationSavedRunSupport.FILE_SIDE_1, artifactContext,
                                            [reconciliationRunResultId: obsRunId, progressStageCode: RunObservability.STAGE_EXTRACT_FILE1]) :
                                    stageTextInput(file1Source, ReconciliationSavedRunSupport.FILE_SIDE_1, inputFile1Name, file1TextValue, artifactContext)
                            if (!ec.message.hasError()) {
                                RunObservability.endStep(ec, obsStep, RunObservability.STATUS_SUCCESS, [recordCount: file1Result.recordCount])
                                RunObservability.recordSourceArtifact(ec, obsRunId, "file1", file1Result.fileName, file1Result.dataManagerPath)
                                RunObservability.checkpointCancel(ec, obsRunId)
                                obsStep = obsRunId ? RunObservability.beginStep(ec, obsRunId, obsCtx, RunObservability.STAGE_EXTRACT_FILE2) : null
                                obsStage = RunObservability.STAGE_EXTRACT_FILE2
                            }
                            file2Result = !ec.message.hasError() && file2UsesApiSource ?
                                    extractApiSource(file2Source, ReconciliationSavedRunSupport.FILE_SIDE_2, artifactContext,
                                            [reconciliationRunResultId: obsRunId, progressStageCode: RunObservability.STAGE_EXTRACT_FILE2,
                                             expectedRecordCount: file1Result.recordCount]) :
                                    !ec.message.hasError() ? stageTextInput(file2Source, ReconciliationSavedRunSupport.FILE_SIDE_2, inputFile2Name, file2TextValue, artifactContext) : [:]
                            if (!ec.message.hasError()) {
                                RunObservability.endStep(ec, obsStep, RunObservability.STATUS_SUCCESS, [recordCount: file2Result.recordCount])
                                RunObservability.recordSourceArtifact(ec, obsRunId, "file2", file2Result.fileName, file2Result.dataManagerPath)
                                obsStep = null
                                obsNoData = file1Result.recordCount == 0 && file2Result.recordCount == 0
                            }

                            if (!ec.message.hasError()) {
                                RunObservability.checkpointCancel(ec, obsRunId)
                                obsStep = obsRunId ? RunObservability.beginStep(ec, obsRunId, obsCtx, RunObservability.STAGE_COMPARE) : null
                                obsStage = RunObservability.STAGE_COMPARE
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
                                ruleSetPersistedSources = (serviceResult.persistedSources ?: []) as List
                                if (!ec.message.hasError()) {
                                    obsRuleExecutionFailed = serviceResult.ruleExecutionFailed == true
                                    // The compare stage materializes its own output: the diffs stream
                                    // straight from the Spark Dataset to the artifact and are never all
                                    // held in memory, so producing the file is the tail of COMPARE rather
                                    // than a results write. The verification passes then correct that
                                    // artifact and the counts, and WRITE_OUTPUT below finalizes both --
                                    // so the results a person downloads are the verified ones, and the
                                    // timeline no longer reads as if they were settled before checking.
                                    writeRuleSetOutput(serviceResult, savedRun, file1Label, file2Label, artifactContext)
                                    RunObservability.endStep(ec, obsStep,
                                            obsRuleExecutionFailed ? RunObservability.STATUS_FAILED : RunObservability.STATUS_SUCCESS,
                                            [recordCount: serviceResult.differenceCount, errorMessage: obsRuleExecutionFailed ? "rule execution failed" : null])
                                    obsStep = null
                                    runVerificationPass(serviceResult, file1Source, file2Source, file1Label, file2Label,
                                            savedRun.companyUserGroupId as String)
                                    runExchangeVerificationPass(serviceResult, file1Source, file2Source,
                                            (Map) file1Result, (Map) file2Result, file1Label, file2Label,
                                            savedRun.companyUserGroupId as String)
                                    runReturnPresenceVerificationPass(serviceResult, file1Source, file2Source,
                                            (Map) file1Result, (Map) file2Result,
                                            file1Label, file2Label, savedRun.companyUserGroupId as String)
                                    obsStep = obsRunId ? RunObservability.beginStep(ec, obsRunId, obsCtx, RunObservability.STAGE_WRITE_OUTPUT) : null
                                    obsStage = RunObservability.STAGE_WRITE_OUTPUT
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
                                        completedDate            : ec.user.nowTimestamp,
                                        reconciliationType       : serviceResult.reconciliationType ?: serviceResult.objectType,
                                        differenceCount          : serviceResult.differenceCount,
                                        onlyInFile1Count         : serviceResult.missingInFile2Count,
                                        onlyInFile2Count         : serviceResult.missingInFile1Count,
                                    ])
                                    RunObservability.endStep(ec, obsStep, RunObservability.STATUS_SUCCESS,
                                            [recordCount: serviceResult.differenceCount])
                                    obsStep = null
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
                                        ruleDifferenceCount      : serviceResult.ruleDifferenceCount,
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
                                        statusEnumId             : (serviceResult.ruleExecutionFailed == true) ? ReconciliationOutputSupport.STATUS_FAILED : ReconciliationOutputSupport.STATUS_SUCCEEDED,
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
                                completedDate            : ec.user.nowTimestamp,
                            ])
                            throw t
                        } finally {
                            darpan.reconciliation.core.ReconciliationServices.unpersistDatasets(ruleSetPersistedSources)
                            if (reconciliationRunResultId && ec.message.hasError() && !runResult?.reconciliationRunResultId) {
                                persistRunResult([
                                    reconciliationRunResultId: reconciliationRunResultId,
                                    file1Name                : file1Result.fileName,
                                    file1DataManagerPath     : file1Result.dataManagerPath,
                                    file2Name                : file2Result.fileName,
                                    file2DataManagerPath     : file2Result.dataManagerPath,
                                    completedDate            : ec.user.nowTimestamp,
                                ])
                            }
                        }
                    }
                } else {
                    String reconciliationRunResultId = persistRunResult([
                            reconciliationRunResultId: obsRunId,
                            savedRunId          : savedRun.savedRunId,
                            savedRunType        : savedRun.runType ?: ReconciliationSavedRunSupport.RUN_TYPE_RULESET,
                            ruleSetId           : savedRun.ruleSetId,
                            compareScopeId      : savedRun.compareScopeId,
                            companyUserGroupId  : savedRun.companyUserGroupId,
                            file1Name           : inputFile1Name,
                            file2Name           : inputFile2Name,
                            createdDate         : ec.user.nowTimestamp,
                            startedDate         : ec.user.nowTimestamp,
                    ])
                    // Observability: reconcile#GenericFiles stages, compares, and writes output in one
                    // inseparable service call, so a single COMPARE step covers all of it here.
                    obsStep = obsRunId ? RunObservability.beginStep(ec, obsRunId, obsCtx, RunObservability.STAGE_COMPARE) : null
                    obsStage = RunObservability.STAGE_COMPARE
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
                                    ruleDifferenceCount      : serviceResult.ruleDifferenceCount,
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
                            RunObservability.endStep(ec, obsStep, RunObservability.STATUS_SUCCESS, [recordCount: serviceResult.differenceCount])
                            obsStep = null
                        }
                    } catch (Throwable t) {
                        if (reconciliationRunResultId) {
                            persistRunResult([
                                    reconciliationRunResultId: reconciliationRunResultId,
                                    completedDate            : ec.user.nowTimestamp,
                            ])
                        }
                        throw t
                    } finally {
                        if (reconciliationRunResultId && ec.message.hasError() && !runResult?.reconciliationRunResultId) {
                            persistRunResult([
                                    reconciliationRunResultId: reconciliationRunResultId,
                                    completedDate            : ec.user.nowTimestamp,
                            ])
                        }
                    }
                }
            }
        }
    }

    if (!ec.message.hasError() && runResult?.reconciliationRunResultId) {
        // Observability: NOTIFY is best-effort and must never fail the run — a notification
        // failure is contained here (step FAILED + warn) and the run still ends SUCCESS/NO_DATA.
        obsStep = obsRunId ? RunObservability.beginStep(ec, obsRunId, obsCtx, RunObservability.STAGE_NOTIFY) : null
        obsStage = RunObservability.STAGE_NOTIFY
        try {
            TenantNotificationSupport.notifyRunCompleted(ec, (Map<String, Object>) runResult)
            RunObservability.endStep(ec, obsStep, RunObservability.STATUS_SUCCESS, [:])
        } catch (Throwable notifyError) {
            RunObservability.endStep(ec, obsStep, RunObservability.STATUS_FAILED,
                    [errorMessage: notifyError.message ?: notifyError.toString()])
            logger.warn("run#SavedRunDiff notify stage failed (best-effort, run outcome preserved): ${notifyError.message ?: notifyError}", notifyError)
        }
        obsStep = null
    }

    // --- Observability terminal guarantee: no path may leave a minted run row RUNNING.
    // (Pre-validated requests never minted a row: obsRunId is null and nothing is written.) ---
    if (obsRunId) {
        // A cancel outranks whatever the abort looked like on the way out: an extractor may have
        // turned the cancel throw into an errors list, and reporting that as FAILED would tell the
        // operator their own cancellation was a run failure.
        if (RunObservability.isCancelRequested(ec, obsRunId)) {
            RunObservability.cancelRun(ec, obsRunId, obsStep, obsStage)
        } else if (ec.message.hasError()) {
            RunObservability.failRun(ec, obsRunId, obsStep, obsStage,
                    ec.message.errors ? ec.message.errors.join("; ") : "reconciliation run failed")
            // Task 7: the success-path notify above never runs on this branch (ec.message has errors), so
            // notify FAILED here — best-effort, mutually exclusive with the success notify via the
            // notifiedDate CAS guard, and never allowed to mask the real run failure.
            try {
                TenantNotificationSupport.notifyRunCompleted(ec, [
                        reconciliationRunResultId: obsRunId,
                        companyUserGroupId       : normalize(runResult?.companyUserGroupId) ?: normalize(obsCtx?.companyUserGroupId),
                        runName                  : normalize(runResult?.runName) ?: normalize(runResult?.savedRunId),
                        savedRunId               : normalize(runResult?.savedRunId),
                        resultDataManagerPath    : normalize(runResult?.resultDataManagerPath),
                        statusEnumId             : "AUT_STAT_FAILED",
                        processingWarnings       : ec.message.errors ? [ec.message.errors.join("; ")] : [],
                ])
            } catch (Throwable notifyError) {
                logger.warn("run#SavedRunDiff failure notification failed (best-effort): ${notifyError.message ?: notifyError}")
            }
        } else {
            String obsRowStatus = null
            try {
                obsRowStatus = normalize(TenantScopedFinder.findTenantScoped(ec, DarpanEntityConstants.RECONCILIATION_RUN_RESULT)
                        .condition("reconciliationRunResultId", obsRunId)
                        .useCache(false)
                        .one()?.statusEnumId)
            } catch (Throwable statusReadError) {
                logger.warn("run#SavedRunDiff terminal status read failed (best-effort): ${statusReadError.message ?: statusReadError}")
            }
            if (obsRuleExecutionFailed || RunObservability.STATUS_FAILED == obsRowStatus) {
                // A nested reconcile service recorded FAILED (rule execution did not fully evaluate)
                // while the facade still returns its normal envelope — preserve that FAILED terminal.
                RunObservability.failRun(ec, obsRunId, obsStep, RunObservability.STAGE_COMPARE, "rule execution failed")
            } else {
                RunObservability.completeRun(ec, obsRunId,
                        obsNoData ? RunObservability.STATUS_NO_DATA : RunObservability.STATUS_SUCCESS, [:])
            }
        }
    }
    obsTerminalWritten = true
} catch (RunObservability.RunCancelledException cancelled) {
    // The operator asked for this: end the run CANCELLED and return a normal envelope rather
    // than propagating an exception the caller would report as a crash.
    if (obsRunId) RunObservability.cancelRun(ec, obsRunId, obsStep, obsStage)
    obsTerminalWritten = true
    ec.message.addMessage(cancelled.message)
} catch (Throwable obsError) {
    if (obsRunId) {
        if (RunObservability.isCancelRequested(ec, obsRunId)) {
            RunObservability.cancelRun(ec, obsRunId, obsStep, obsStage)
        } else {
            RunObservability.failRun(ec, obsRunId, obsStep, obsStage, obsError.message ?: obsError.toString())
        }
    }
    obsTerminalWritten = true
    throw obsError
} finally {
    if (!obsTerminalWritten && obsRunId) {
        RunObservability.failRun(ec, obsRunId, obsStep, obsStage, "run exited without terminal status")
    }
}


Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
}
