package darpan.reconciliation.automation

import darpan.common.DarpanEntityConstants
import darpan.common.TransactionDetachSupport
import darpan.facade.common.DataManagerSupport
import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.facade.reconciliation.ReconciliationSavedRunSupport
import darpan.facade.reconciliation.RunObservability
import darpan.reconciliation.core.ReconciliationServices
import darpan.reconciliation.notification.TenantNotificationSupport
import groovy.json.JsonSlurper
import org.apache.spark.sql.Dataset
import org.moqui.impl.service.ScheduledJobRunner

import java.sql.Timestamp
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

import static darpan.common.ValueSupport.fileNameFromPath
import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeInt
import static darpan.common.ValueSupport.readField
import static darpan.reconciliation.automation.AutomationRuntimeSupport.currentUserId
import static darpan.reconciliation.automation.AutomationRuntimeSupport.loadAutomation
import static darpan.reconciliation.automation.AutomationRuntimeSupport.loadAutomationSourceFilters
import static darpan.reconciliation.automation.AutomationRuntimeSupport.loadAutomationSources
import static darpan.reconciliation.automation.AutomationRuntimeSupport.normalizeDataManagerPath
import static darpan.reconciliation.automation.AutomationRuntimeSupport.nowTimestamp
import static darpan.reconciliation.automation.AutomationRuntimeSupport.requireNormalized
import static darpan.reconciliation.automation.AutomationRuntimeSupport.runInTransaction
import static darpan.reconciliation.automation.AutomationRuntimeSupport.safeMetadataJson
import static darpan.reconciliation.automation.AutomationRuntimeSupport.sanitizeErrorDetail
import static darpan.reconciliation.automation.AutomationRuntimeSupport.sanitizeErrorMessage
import static darpan.reconciliation.automation.AutomationRuntimeSupport.truncate
import static darpan.reconciliation.automation.AutomationRuntimeSupport.updateAutomationExecution

class AutomationExecutionSupport {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(AutomationExecutionSupport)
    // Audit 2026-06-11 #8, reworked by DAR-BE-002: the ceiling on how many automation executions may be
    // in flight at once, bounding shared service-pool and Spark-driver load. It used to be a per-scan
    // dispatch width because the scan JOINED every execution it dispatched, so "concurrent within one
    // scan" and "concurrent at all" were the same number. The scan is fire-and-forget now, so the bound
    // is measured against executions already RUNNING; a tick that would exceed it defers the remaining
    // due automations to the next tick rather than stampeding Moqui's worker pool (bounded at 32+
    // threads, far above the Spark concurrency this cap exists to allow). Override via the system
    // property for tenants with heavier scheduler/Spark capacity.
    static final int MAX_CONCURRENT_EXECUTIONS =
            (System.getProperty("darpan.reconciliation.automation.maxConcurrentExecutions") ?: "4").isInteger() ?
                    (System.getProperty("darpan.reconciliation.automation.maxConcurrentExecutions") ?: "4").toInteger() : 4
    // Security (HIGH gap 2, reworked 2026-06-30): hard DoS sanity ceiling on the resolved window span,
    // SEPARATE from the per-automation `maxWindowDays` operational default (entity default=28). This
    // ceiling exists only to stop the 1970->9999 (~2.9M-day) explosion; it must never reject a real
    // window, so it is deliberately huge (10 years). Operators can tune it via the system property.
    static final int MAX_WINDOW_SPAN_DAYS =
            (System.getProperty("darpan.reconciliation.automation.maxWindowSpanDays") ?: "3660").isInteger() ?
                    Math.max(1, (System.getProperty("darpan.reconciliation.automation.maxWindowSpanDays") ?: "3660").toInteger()) : 3660
    static final String AUTOMATION_INPUT_API_RANGE = "AUT_IN_API_RANGE"
    static final String AUTOMATION_INPUT_SFTP_FILES = "AUT_IN_SFTP_FILES"
    static final String AUTOMATION_SOURCE_API = "AUT_SRC_API"
    static final String AUTOMATION_SOURCE_DB = "AUT_SRC_DB"
    // Extract-service name aliases (single source of truth = ReconciliationSavedRunSupport), kept for
    // test references. Dispatch is now data-driven: the allow-list comes from the registry
    // (SourceSystemConnectorSupport.allowedServiceNames) + a naming guard, so the old
    // ALLOWED_EXTRACT_SERVICE_NAMES set, the per-system window-parameter constants, and the duplicate
    // OMS/SHOPIFY system-enum-id constants were removed here (DAR-299 constants cleanup).
    static final String HOTWAX_OMS_ORDERS_EXTRACT_SERVICE = ReconciliationSavedRunSupport.HOTWAX_OMS_ORDERS_EXTRACT_SERVICE
    static final String SHOPIFY_ORDERS_EXTRACT_SERVICE = ReconciliationSavedRunSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE
    static final String SHOPIFY_GRAPHQL_EXECUTE_SERVICE = ReconciliationSavedRunSupport.SHOPIFY_GRAPHQL_EXECUTE_SERVICE

    static final String STATUS_PENDING = "AUT_STAT_PENDING"
    static final String STATUS_RUNNING = "AUT_STAT_RUNNING"
    static final String STATUS_SUCCEEDED = "AUT_STAT_SUCCESS"
    static final String STATUS_FAILED = "AUT_STAT_FAILED"
    static final String STATUS_NO_DATA = "AUT_STAT_NO_DATA"
    static final String STATUS_SKIPPED_DUPLICATE = "AUT_STAT_SKIP_DUP"
    static final String STATUS_DEAD_LETTER = "AUT_STAT_DEAD_LETTER"
    // Terminal state for a run an operator stopped. Same enum value as
    // RunObservability.STATUS_CANCELLED (seeded as AutomationExecStatus in data/AutomationSeedData.xml,
    // so it satisfies both rows' statusEnumId FK); declared here for the same reason every other status
    // above is — this file's own transitions read from its own constants.
    static final String STATUS_CANCELLED = "AUT_STAT_CANCELLED"

    // Transient-failure retry policy (DAR-300). A transient failure requeues the execution to PENDING
    // with a backoff nextRetryDate; the 5-min scanner re-drives due rows, incrementing retryCount, and
    // dead-letters once retryCount reaches maxRetryCount. Permanent (config/fence) failures skip retry
    // and go straight to FAILED.
    static final int MAX_RETRY_COUNT_DEFAULT =
            Math.max(0, (System.getProperty("darpan.reconciliation.automation.maxRetryCount") ?: "3").toInteger())
    static final long RETRY_BACKOFF_BASE_MINUTES =
            Math.max(1L, (System.getProperty("darpan.reconciliation.automation.retryBackoffBaseMinutes") ?: "5").toLong())
    static final long RETRY_BACKOFF_CAP_MINUTES =
            Math.max(RETRY_BACKOFF_BASE_MINUTES, (System.getProperty("darpan.reconciliation.automation.retryBackoffCapMinutes") ?: "60").toLong())
    // Lower-cased substrings that mark a failure as permanent (config/fence/validation) — a retry cannot
    // fix these, so they go straight to FAILED instead of being requeued.
    private static final List<String> PERMANENT_FAILURE_MARKERS = [
            "not in the allowed service list",
            "does not match an allowed extractor service name pattern",
            "no source connector is registered",
            "requires sourceconfigid",
            "is not valid for",
            "cannot read orders",
            "is inactive",
            "was not found",
            "not available in your active tenant",
            "is not configured",
    ]

    static final String WINDOW_PREVIOUS_DAY = "AUT_WIN_PREV_DAY"
    static final String WINDOW_PREVIOUS_WEEK = "AUT_WIN_PREV_WEEK"
    static final String WINDOW_PREVIOUS_MONTH = "AUT_WIN_PREV_MONTH"
    static final String WINDOW_LAST_DAYS = "AUT_WIN_LAST_DAYS"
    static final String WINDOW_LAST_WEEKS = "AUT_WIN_LAST_WEEKS"
    static final String WINDOW_LAST_MONTHS = "AUT_WIN_LAST_MONTHS"
    static final String WINDOW_CUSTOM = "AUT_WIN_CUSTOM"
    static final String WINDOW_STATE = "AUT_WIN_STATE"

    static final String FILE_SIDE_1 = "FILE_1"
    static final String FILE_SIDE_2 = "FILE_2"

    private static final Set<String> REUSABLE_EXECUTION_STATUSES = [STATUS_PENDING] as Set

    private static final Closure DEFAULT_SOURCE_EXTRACTOR = { def ec, def automation, def source, Map<String, Object> window,
            Map<String, Object> params ->
        return callConfiguredSourceExtractor(ec, automation, source, window, params)
    }
    private static final Closure DEFAULT_RECONCILE_RUNNER = { def ec, def automation, def file1Source, def file2Source,
            Map<String, Object> file1Result, Map<String, Object> file2Result, Map<String, Object> window,
            Map<String, Object> params ->
        return callRuleSetCompareScope(ec, automation, file1Source, file2Source, file1Result, file2Result, window, params)
    }

    private static Closure sourceExtractor = DEFAULT_SOURCE_EXTRACTOR
    private static Closure reconcileRunner = DEFAULT_RECONCILE_RUNNER

    static void setSourceExtractor(Closure extractor) {
        sourceExtractor = extractor ?: DEFAULT_SOURCE_EXTRACTOR
    }

    static void setReconcileRunner(Closure runner) {
        reconcileRunner = runner ?: DEFAULT_RECONCILE_RUNNER
    }

    static void resetExecutionHooks() {
        sourceExtractor = DEFAULT_SOURCE_EXTRACTOR
        reconcileRunner = DEFAULT_RECONCILE_RUNNER
    }

    /**
     * Entry point for both the scheduler (via {@code execute#Automation}) and interactive
     * {@code run#AutomationNow}.
     *
     * <p>The scheduled path has no interactive user — {@code authenticate="anonymous-all"} logs in
     * {@code _NA_}, which belongs to no tenant — so every tenant-scoped read in the reconcile
     * pipeline it calls would fail closed (UAT 2026-07-31: "RuleSet ... is not accessible in your
     * active tenant" from {@code prepare#RuleSetCompareScope}, on every scheduled run). The runner
     * already trusts {@code automation.companyUserGroupId} as its tenant anchor for its own reads;
     * publish that same anchor so the pipeline downstream scopes to it too.</p>
     *
     * <p>Interactive callers are unaffected: a user-derived tenant always wins in
     * {@link TenantAccessSupport#currentActiveTenantUserGroupId}.</p>
     */
    static Map<String, Object> executeAutomation(def ec, Map params) {
        // Detached from the caller's request transaction, exactly like the interactive saved-run
        // path (see runSavedRunDiff.groovy). run#AutomationNow arrives inside the JSON-RPC
        // request's JTA transaction, whose 60s timeout is far shorter than a real API-range run;
        // joining it meant the execution row was invisible until the whole run committed, and a
        // gateway timeout rolled it back leaving no trace at all (prod 2026-08-05, automation
        // 100000: 61.5s then a severed connection and "No previous runs"). Detached, each write
        // commits in its own short transaction, so the PENDING/RUNNING row is readable while the
        // run is still going and the UI can follow it live.
        return (Map<String, Object>) TransactionDetachSupport.runDetachedFromCallerTransaction(ec) { ->
            Map<String, Object> input = params ?: [:]
            String automationId = requireNormalized(input.automationId, "automationId is required")
            def automation = loadAutomation(ec, automationId)
            return TenantAccessSupport.withSystemTenant(resolveSystemTenantId(ec, automation)) {
                return executeAutomationForTenant(ec, input, automation)
            }
        }
    }

    private static Map<String, Object> executeAutomationForTenant(def ec, Map<String, Object> input, def automation) {
        String automationId = normalize(readField(automation, "automationId"))
        String inputModeEnumId = normalize(readField(automation, "inputModeEnumId"))

        if (inputModeEnumId == AUTOMATION_INPUT_SFTP_FILES) {
            return SftpAutomationSupport.runSftpFileAutomation(ec, normalizeSftpExecutionParams(input))
        }
        if (inputModeEnumId != AUTOMATION_INPUT_API_RANGE) {
            throw new IllegalArgumentException("Automation ${automationId} must use ${AUTOMATION_INPUT_API_RANGE} or ${AUTOMATION_INPUT_SFTP_FILES}")
        }

        Timestamp scheduledFireTime = resolveScheduledFireTime(ec, input)
        List<Map<String, Object>> windows = resolveWindows(automation, input + [scheduledFireTime: scheduledFireTime])
        Map<String, Object> sourcesBySide = loadAutomationSources(ec, automationId)
        def file1Source = requireApiSource(automation, sourcesBySide[FILE_SIDE_1], FILE_SIDE_1)
        def file2Source = requireApiSource(automation, sourcesBySide[FILE_SIDE_2], FILE_SIDE_2)
        Map<String, Object> executionParams = new LinkedHashMap<String, Object>(input)
        Map<String, Object> sourceExtractorConfigDefaults = resolveSourceExtractorConfigDefaults(ec, automation, [file1Source, file2Source])
        if (sourceExtractorConfigDefaults) executionParams.sourceExtractorConfigDefaults = sourceExtractorConfigDefaults

        List<Map<String, Object>> executionResults = []
        windows.eachWithIndex { Map<String, Object> window, int index ->
            Map<String, Object> executionState = findOrCreateExecution(ec, automation, scheduledFireTime, window, index + 1)
            def execution = executionState.execution
            String automationExecutionId = normalize(readField(execution, "automationExecutionId"))

            if (executionState.duplicate == true) {
                executionResults << [
                        automationExecutionId: automationExecutionId,
                        statusEnumId         : STATUS_SKIPPED_DUPLICATE,
                        childWindowStartDate : window.childWindowStartDate,
                        childWindowEndDate   : window.childWindowEndDate,
                ]
                return
            }

            // Audit 2026-06-11 #16: hold the Spark Datasets persisted by reconcile#RuleSetCompareScope
            // so the finally below unpersists them on every exit path of this automation execution.
            List autoPersistedSources = []
            // Task 7 (gchat notifications): visible in the catch below so a failure AFTER the run-result
            // row is minted (e.g. the execution-row status write itself fails) still notifies FAILED.
            // Task 2b: the row is now minted at RUNNING rather than at terminal, so this is populated for
            // essentially the whole execution; it stays null only when the best-effort mint itself failed,
            // in which case the terminal paths fall back to creating the row exactly as they used to.
            String mintedRunResultId = null
            // Task 2b: kept SEPARATE from mintedRunResultId. Before 2b "a run-result row exists" and
            // "this run produced output" were the same fact, and notifyAutomationFailure's payload shape
            // keys off the latter. Early minting breaks that equivalence, so track output explicitly.
            boolean runOutputPersisted = false
            // The row this attempt replaces, if any: a retry re-drives the SAME execution row, and
            // nothing ever clears its run-result id, so on attempt N+1 this still names attempt N's row.
            String supersededRunResultId = normalize(readField(execution, "reconciliationRunResultId"))
            // The ReconciliationRunStep row currently open, or null between stages. Minting the run row
            // at RUNNING put automations on the live progress view, but automations wrote no step rows at
            // all, so that view synthesized a Pending row per canonical stage and NONE of them could ever
            // advance — a 7-step progress list frozen for the whole run. These are the same phase
            // boundaries the heartbeat and cancel checkpoints already use. Visible to the catch blocks
            // below so an abort closes whatever stage was open instead of orphaning it RUNNING.
            def openStep = null
            Map<String, Object> stepCtx = [companyUserGroupId: readField(automation, "companyUserGroupId")]
            try {
                Timestamp startedTimestamp = nowTimestamp(ec)
                // Task 2b: mint the run-result row as the execution goes RUNNING and carry its id in the
                // SAME execution-row update. Two updates would leave a window where an ACTIVE execution
                // has no run-result id — precisely the window the "Run now" UI poll watches, so a row it
                // could never match is a redirect that never fires.
                mintedRunResultId = beginAutomationRunResult(ec, automation, startedTimestamp)
                updateAutomationExecution(ec, execution, [
                        statusEnumId             : STATUS_RUNNING,
                        startedDate              : startedTimestamp,
                        lastUpdatedDate          : startedTimestamp,
                        reconciliationRunResultId: mintedRunResultId,
                ])
                // Carry "notify me" forward onto this attempt's row. A subscription is anchored to one
                // run-result row, so without this a subscriber from the previous attempt could never be
                // told how the run actually ended — the retry notifies against a row they are not on —
                // and their row would sit there forever, pinning its chat space against deletion.
                // (A previous attempt that already notified had its subscriptions purged by that claim,
                // so this is a no-op there.)
                TenantNotificationSupport.reassignRunSubscriptions(ec, supersededRunResultId, mintedRunResultId)

                // RESOLVE covers everything between minting the row and the first extract: window
                // resolution, source lookup and the subscription carry-forward above. Opened and closed
                // together because there is no cancel checkpoint inside it to interleave with.
                openStep = RunObservability.beginStep(ec, mintedRunResultId, stepCtx, RunObservability.STAGE_RESOLVE)
                RunObservability.endStep(ec, openStep, RunObservability.STATUS_SUCCESS, [:])
                openStep = null

                openStep = RunObservability.beginStep(ec, mintedRunResultId, stepCtx, RunObservability.STAGE_EXTRACT_FILE1)
                Map<String, Object> file1Result = normalizeSourceResult(sourceExtractor.call(ec, automation, file1Source, window, executionParams), file1Source)
                RunObservability.endStep(ec, openStep, RunObservability.STATUS_SUCCESS, [recordCount: file1Result.recordCount])
                openStep = null
                // Task 2c: refresh the clock after source-1 extraction, a real (possibly slow) I/O call —
                // see heartbeatAutomationRun's javadoc for why lastUpdatedStamp, not lastHeartbeatDate, is
                // what actually protects this run from StuckRunReaper.
                heartbeatAutomationRun(ec, automation, execution, mintedRunResultId)
                // Task 6: the same phase boundaries are the run's cancel checkpoints. Minting the row at
                // RUNNING put this run on the live progress view, which offers "Cancel run" — until now
                // nothing on the automation side ever read the flag that button sets, so the run finished
                // normally and reported SUCCESS to an operator who believed they had stopped it.
                // Cancellation stays cooperative (RunObservability.requestCancel only stamps the request):
                // this throws RunCancelledException out of the run loop, caught below.
                RunObservability.checkpointCancel(ec, mintedRunResultId)
                openStep = RunObservability.beginStep(ec, mintedRunResultId, stepCtx, RunObservability.STAGE_EXTRACT_FILE2)
                Map<String, Object> file2Result = normalizeSourceResult(sourceExtractor.call(ec, automation, file2Source, window, executionParams), file2Source)
                RunObservability.endStep(ec, openStep, RunObservability.STATUS_SUCCESS, [recordCount: file2Result.recordCount])
                openStep = null
                // Task 2c: after source-2 extraction too — a NO_DATA window returns just below without
                // ever reaching the reconcile heartbeat, so this is the last one it gets before its own
                // terminal close (which also bumps lastUpdatedStamp).
                heartbeatAutomationRun(ec, automation, execution, mintedRunResultId)
                RunObservability.checkpointCancel(ec, mintedRunResultId)

                if (!hasData(file1Result) || !hasData(file2Result)) {
                    Timestamp completedTimestamp = nowTimestamp(ec)
                    Map<String, Object> noDataFields = executionUpdateFields(file1Result, file2Result, [:]) + [
                            statusEnumId    : STATUS_NO_DATA,
                            completedDate   : completedTimestamp,
                            safeMetadataJson: safeMetadataJson([
                                    mode              : "API_DATE_RANGE",
                                    dataAvailable     : false,
                                    file1DataAvailable: hasData(file1Result),
                                    file2DataAvailable: hasData(file2Result),
                                    childWindowStart  : window.childWindowStartDate,
                                    childWindowEnd    : window.childWindowEndDate,
                            ]),
                            lastUpdatedDate : completedTimestamp,
                    ]
                    updateAutomationExecution(ec, execution, noDataFields)
                    // Task 2b terminal guarantee: a window with no data still minted a row at RUNNING, so
                    // it has to be closed here or it sits ACTIVE forever (and the stuck-run reaper would
                    // eventually flip it to FAILED and alert on a run that simply had nothing to compare).
                    // NO_DATA still does not notify — that contract is unchanged.
                    completeAutomationRunResult(ec, automation, mintedRunResultId, STATUS_NO_DATA, completedTimestamp, [:])
                    // ...and because it does not notify, nothing else will ever clean up a "notify me"
                    // subscription taken against this row while it was RUNNING (purgeRunSubscriptions
                    // only runs after a won notification claim). An orphan there never fires AND pins
                    // its chat space against deletion forever, so purge it here.
                    TenantNotificationSupport.purgeSubscriptionsForUnnotifiedRun(ec, mintedRunResultId)
                    executionResults << noDataFields + [
                            automationExecutionId: automationExecutionId,
                            childWindowStartDate : window.childWindowStartDate,
                            childWindowEndDate   : window.childWindowEndDate,
                    ]
                    return
                }

                openStep = RunObservability.beginStep(ec, mintedRunResultId, stepCtx, RunObservability.STAGE_COMPARE)
                Map<String, Object> reconcileResult = normalizeReconcileResult(
                        reconcileRunner.call(ec, automation, file1Source, file2Source, file1Result, file2Result, window, executionParams)
                )
                RunObservability.endStep(ec, openStep, RunObservability.STATUS_SUCCESS, [:])
                openStep = null
                // Task 2c: around the reconcile call — reconcile (a Spark job) is typically the single
                // longest phase, so this is the boundary that matters most. Placed AFTER rather than
                // immediately before: the two extraction heartbeats above already leave the clock fresh
                // going into reconcile, so an "around" heartbeat here instead of a near-duplicate one right
                // before it gives the persist/notify work below its own protected window too.
                heartbeatAutomationRun(ec, automation, execution, mintedRunResultId)
                // Task 6: the last checkpoint before any output is persisted or anyone is notified, so a
                // cancel that lands during the reconcile still ends the run CANCELLED rather than
                // publishing a result the operator asked not to have.
                RunObservability.checkpointCancel(ec, mintedRunResultId)
                autoPersistedSources = (reconcileResult.persistedSources ?: []) as List
                requireReconcileOutput(ec, reconcileResult)
                ensureAutomationResultArtifact(ec, automation, file1Source, file2Source, reconcileResult, window, executionParams)
                String resultDataManagerPath = normalizeDataManagerPath(ec,
                        reconcileResult.resultDataManagerPath ?: reconcileResult.diffLocation ?: reconcileResult.diffFileName)
                openStep = RunObservability.beginStep(ec, mintedRunResultId, stepCtx, RunObservability.STAGE_WRITE_OUTPUT)
                String reconciliationRunResultId = persistAutomationRunResult(ec, automation, mintedRunResultId,
                        file1Result, file2Result, reconcileResult, resultDataManagerPath)
                RunObservability.endStep(ec, openStep, RunObservability.STATUS_SUCCESS, [:])
                openStep = null
                mintedRunResultId = reconciliationRunResultId ?: mintedRunResultId
                // Output is what was persisted, not what exists: a blank resultDataManagerPath now still
                // resolves to the pre-minted row (it must end terminal), but nothing was written.
                runOutputPersisted = normalize(resultDataManagerPath) != null

                Timestamp completedTimestamp = nowTimestamp(ec)
                // Audit 2026-06-11 #4: a rule build/eval failure does not throw, so an automation run
                // would otherwise record SUCCEEDED despite the ruleset not fully evaluating. Mark it
                // FAILED so the execution status and completion alert reflect the broken sync check.
                boolean ruleExecutionFailed = reconcileResult.ruleExecutionFailed == true
                // Task 6 fix round 1: the API path reaches a FAILED terminal WITHOUT throwing too — a rule
                // build/eval failure is only the flag above — so neither checkpoint nor catch can see a
                // cancel that lands in the window between the last checkpoint (:331) and here, which spans
                // the artifact write and the run-result persist and can run for seconds on a large diff.
                // Left unguarded, the operator is told FAILED and ALERTED about their own cancellation.
                // Exactly the guard SftpAutomationSupport already carries on its own non-throwing error
                // branch, gated the same way: only the failure branch is outranked, so a run that genuinely
                // completed keeps its own outcome instead of being retitled by a late cancel.
                if (ruleExecutionFailed && RunObservability.isCancelRequested(ec, mintedRunResultId)) {
                    executionResults << cancelledExecutionResult(ec, automation, execution, mintedRunResultId,
                            automationExecutionId, window)
                    return
                }
                Map<String, Object> successFields = executionUpdateFields(file1Result, file2Result, reconcileResult) + [
                        statusEnumId              : ruleExecutionFailed ? STATUS_FAILED : STATUS_SUCCEEDED,
                        completedDate             : completedTimestamp,
                        resultFileName            : normalize(reconcileResult.resultFileName) ?: fileNameFromPath(reconcileResult.diffFileName) ?: fileNameFromPath(resultDataManagerPath),
                        resultDataManagerPath     : resultDataManagerPath,
                        reconciliationRunResultId : reconciliationRunResultId,
                        safeMetadataJson          : safeMetadataJson([
                                mode                : "API_DATE_RANGE",
                                dataAvailable       : true,
                                childWindowStart    : window.childWindowStartDate,
                                childWindowEnd      : window.childWindowEndDate,
                                sourceExtractorMode : "configured",
                                reconciliationRunner: "RuleSetCompareScope",
                                validationErrors    : reconcileResult.validationErrors ?: [],
                                processingWarnings  : reconcileResult.processingWarnings ?: [],
                        ]),
                        lastUpdatedDate           : completedTimestamp,
                ]
                updateAutomationExecution(ec, execution, successFields)
                TenantNotificationSupport.notifyRunCompleted(ec, [
                        reconciliationRunResultId: reconciliationRunResultId,
                        runName                  : normalize(readField(automation, "automationName")),
                        savedRunId               : normalize(readField(automation, "savedRunId")),
                        reconciliationRunId      : normalize(readField(automation, "reconciliationRunId")),
                        companyUserGroupId       : normalize(readField(automation, "companyUserGroupId")),
                        chatSpaceId              : normalize(readField(automation, "chatSpaceId")),
                        resultDataManagerPath    : resultDataManagerPath,
                        file1SystemEnumId        : normalize(readField(file1Source, "systemEnumId")),
                        file2SystemEnumId        : normalize(readField(file2Source, "systemEnumId")),
                        file1SystemLabel         : normalize(reconcileResult.file1Label),
                        file2SystemLabel         : normalize(reconcileResult.file2Label),
                        differenceCount          : successFields.differenceCount,
                        onlyInFile1Count         : successFields.onlyInFile1Count,
                        onlyInFile2Count         : successFields.onlyInFile2Count,
                        statusEnumId             : successFields.statusEnumId,
                        processingWarnings       : (reconcileResult.processingWarnings ?: []) as List,
                ])
                executionResults << successFields + [
                        automationExecutionId: automationExecutionId,
                        childWindowStartDate : window.childWindowStartDate,
                        childWindowEndDate   : window.childWindowEndDate,
                ]
            } catch (RunObservability.RunCancelledException cancelled) {
                // Close whatever stage was open so the timeline ends CANCELLED with it, rather than
                // leaving one row stuck RUNNING under a terminal run. Best-effort, like every other
                // observability write — never let bookkeeping change how the attempt is classified.
                RunObservability.endStep(ec, openStep, RunObservability.STATUS_CANCELLED, [:])
                openStep = null
                // The operator asked for this at one of the checkpoints above. End the attempt CANCELLED
                // and carry on with the remaining windows — letting the throw reach the failure path
                // below would classify the cancel as a transient failure, requeue the run they just
                // stopped for retry, and record FAILED on the row they are watching.
                executionResults << cancelledExecutionResult(ec, automation, execution, mintedRunResultId,
                        automationExecutionId, window)
            } catch (Throwable t) {
                // A cancel OUTRANKS an abort that merely looks like a failure (mirrors
                // runSavedRunDiff.groovy:1017): an extractor can catch the cancel throw from its own
                // progress checkpoint and surface it as an errors list or a wrapped service exception, and
                // reporting THAT as FAILED would tell the operator their own cancellation was a run
                // failure — and, worse here than on the interactive path, requeue the stopped run.
                if (RunObservability.isCancelRequested(ec, mintedRunResultId)) {
                    RunObservability.endStep(ec, openStep, RunObservability.STATUS_CANCELLED, [:])
                    openStep = null
                    executionResults << cancelledExecutionResult(ec, automation, execution, mintedRunResultId,
                            automationExecutionId, window)
                    return
                }
                // The stage that was running when this blew up is the one an operator needs to see
                // marked FAILED — it names WHERE the run died, which the run-level error alone does not.
                RunObservability.endStep(ec, openStep, RunObservability.STATUS_FAILED, [errorMessage: sanitizeErrorMessage(t)])
                openStep = null
                Timestamp completedTimestamp = nowTimestamp(ec)
                Map<String, Object> failureFields = buildFailureFields(ec, execution, t, completedTimestamp, window)
                updateAutomationExecution(ec, execution, failureFields)
                // Task 2b terminal guarantee: close the row minted at RUNNING on EVERY failure exit,
                // including a transient one that requeues the execution to PENDING. Left RUNNING it would
                // be an active run nobody is running, which the reaper turns into a spurious FAILED alert
                // two hours later. The re-drive mints its own row, so each attempt owns exactly one.
                if (mintedRunResultId) {
                    completeAutomationRunResult(ec, automation, mintedRunResultId, STATUS_FAILED, completedTimestamp, [
                            // ReconciliationRunResult.errorMessage is text-medium = VARCHAR(255); anything
                            // longer makes the whole update throw, which would leave the row RUNNING — the
                            // one outcome this close is here to prevent. RunObservability.failRun caps the
                            // same column at 255 for the same reason. errorDetail is text-very-long (CLOB).
                            errorMessage: truncate(sanitizeErrorMessage(t), 255),
                            errorDetail : truncate(sanitizeErrorDetail(t), 12000),
                    ])
                }
                // UAT 2026-07-31: this notify used to be guarded by `if (mintedRunResultId)`, i.e. only a
                // run that had ALREADY produced output could report its own failure. Every run that died
                // earlier — the entire scheduled-automation outage — failed in total silence. A terminal
                // failure now mints its own FAILED run-result row so it is both notified and visible in
                // run history. A failure queued for retry is NOT terminal and stays quiet.
                // (Task 2b: the row is normally minted at RUNNING, so persistFailureRunResult is reached
                // only when that mint failed — it stays the create-a-row-to-notify-on fallback, and can
                // no longer produce a second row for a run that already has one.)
                if (failureFields.statusEnumId == STATUS_FAILED) {
                    // notifyRunCompleted purges this run's subscriptions itself once its claim is won,
                    // so nothing may delete them ahead of this call — that would drop the subscriber
                    // destinations the alert is about to resolve.
                    notifyAutomationFailure(ec, automation,
                            mintedRunResultId ?: persistFailureRunResult(ec, automation, t, completedTimestamp),
                            runOutputPersisted, sanitizeErrorMessage(t))
                }
                // A requeued attempt deliberately does NOT purge its subscriptions here. The chain is
                // not over: either the re-drive carries them onto its own row (see the mint above), or
                // retries run out and reprocessDueRetries sends the DEAD_LETTER alert against this very
                // row id — which is the one thing an ad hoc subscriber most needs, and purging here
                // would silently delete the only destination that alert could reach them through.
                executionResults << failureFields + [
                        automationExecutionId: automationExecutionId,
                        childWindowStartDate : window.childWindowStartDate,
                        childWindowEndDate   : window.childWindowEndDate,
                    ]
            } finally {
                ReconciliationServices.unpersistDatasets(autoPersistedSources)
            }
        }

        Map statusCounts = executionResults.countBy { it.statusEnumId }
        return [
                automationId          : automationId,
                scheduledFireTime     : scheduledFireTime,
                executedCount         : statusCounts[STATUS_SUCCEEDED] ?: 0,
                noDataCount           : statusCounts[STATUS_NO_DATA] ?: 0,
                failedCount           : statusCounts[STATUS_FAILED] ?: 0,
                skippedDuplicateCount : statusCounts[STATUS_SKIPPED_DUPLICATE] ?: 0,
                // Task 6: without its own counter a cancelled window is absent from every count in this
                // envelope — the scanner log and run#AutomationNow's response would report a run that
                // simply did not happen, which is the shape of the bug this whole plan started from.
                cancelledCount        : statusCounts[STATUS_CANCELLED] ?: 0,
                executionResults      : executionResults,
        ]
    }

    /**
     * Terminal CANCELLED close for one execution attempt, plus the result-list entry describing it.
     *
     * <p>Deliberately NOT written through the failure path: {@code buildFailureFields} classifies an
     * unrecognised throw as transient, so a cancel would requeue the very run the operator just stopped
     * and stamp FAILED on the row they are watching. Requirement: a cancelled run ends terminal
     * CANCELLED on BOTH rows and is never reported as a failure.</p>
     */
    protected static Map<String, Object> cancelledExecutionResult(def ec, def automation, def execution,
            String runResultId, String automationExecutionId, Map<String, Object> window) {
        return cancelAutomationExecution(ec, automation, execution, runResultId, [
                mode            : "API_DATE_RANGE",
                childWindowStart: window?.childWindowStartDate,
                childWindowEnd  : window?.childWindowEndDate,
        ]) + [
                automationExecutionId: automationExecutionId,
                childWindowStartDate : window?.childWindowStartDate,
                childWindowEndDate   : window?.childWindowEndDate,
        ]
    }

    /**
     * Ends one automation attempt CANCELLED: the execution row, the run-result row minted at RUNNING, and
     * that row's notify-me subscriptions. Shared by the API-range runner and {@code SftpAutomationSupport}.
     *
     * <p>Ordering: the run-result close is deliberately NOT inside the execution write's try, because if
     * that write is what failed the minted row would be stranded RUNNING — the one outcome every terminal
     * close in this file exists to prevent (the reaper turns it into a spurious FAILED alert two hours
     * later). Same shape as the SFTP failure close.</p>
     *
     * <p><b>Notification decision:</b> a cancelled run does NOT notify, and purges its subscriptions
     * instead. Two reasons. The only alert shape reachable from here is
     * {@code notifyAutomationFailure}, which renders "Status: FAILED" — precisely what a cancel must not
     * report — and adding a CANCELLED alert shape would be a new notification contract, not this fix.
     * And the person who pressed Cancel already knows. The subscriptions cannot simply be left, though:
     * a cancel is terminal and never requeues, so unlike a transient failure there is no successor
     * attempt to carry them to — this is genuinely the end of the chain, and an orphan can never fire
     * AND pins its chat space against deletion forever. Same reasoning, and the same helper, as the
     * NO_DATA close.</p>
     *
     * @return the fields written to the execution row (the caller folds these into its result entry)
     */
    protected static Map<String, Object> cancelAutomationExecution(def ec, def automation, def execution,
            String runResultId, Map<String, Object> metadata) {
        Timestamp cancelledAt = nowTimestamp(ec)
        Map<String, Object> cancelFields = [
                statusEnumId    : STATUS_CANCELLED,
                completedDate   : cancelledAt,
                errorMessage    : RunObservability.CANCEL_REASON,
                safeMetadataJson: safeMetadataJson(((metadata ?: [:]) + [cancelled: true]) as Map<String, Object>),
                lastUpdatedDate : cancelledAt,
        ]
        try {
            updateAutomationExecution(ec, execution, cancelFields)
        } catch (Throwable executionWriteError) {
            logger.warn("Could not record automation execution CANCELLED (best-effort): ${executionWriteError.message}")
        }
        completeAutomationRunResult(ec, automation, runResultId, STATUS_CANCELLED, cancelledAt,
                [errorMessage: RunObservability.CANCEL_REASON])
        TenantNotificationSupport.purgeSubscriptionsForUnnotifiedRun(ec, runResultId)
        return cancelFields
    }

    static Map<String, Object> scanDueAutomations(def ec, Map params) {
        Map<String, Object> input = params ?: [:]
        Timestamp now = toTimestamp(input.nowTimestamp) ?: nowTimestamp(ec)
        int limit = normalizeInt(input.limit) ?: 100

        List<Map<String, Object>> dueAutomationEntries = loadActiveAutomations(ec)
                .collect { automation ->
                    [
                            automation       : automation,
                            scheduledFireTime: resolveDueScheduledFireTime(automation, now),
                    ]
                }
                .findAll { entry -> entry.scheduledFireTime != null }
                .sort { left, right ->
                    ((Timestamp) left.scheduledFireTime) <=> ((Timestamp) right.scheduledFireTime)
                }
                .take(limit)

        // DAR-BE-002: submit, advance the schedule, return. This scan is a ServiceJob on a 5-minute
        // cron; it used to dispatch each execution as a future and then JOIN it with a 1800s timed get,
        // so total wall clock was ceil(N / maxConcurrent) x slowest-in-chunk (~301s observed) and the
        // scheduler overlapped itself. Every execution is now independently observable on its own live
        // progress view (executeAutomation mints its ReconciliationAutomationExecution + run-result rows
        // at RUNNING), so the scan no longer has to wait around to report outcomes.
        //
        // The 2026-06-11 #8 flood protection survives as an IN-FLIGHT cap rather than a dispatch width:
        // budget = cap - executions already RUNNING. Due automations past the budget are deferred with
        // their schedule untouched, so the very next tick re-drives the same window instead of losing it.
        int inFlightCount = countInFlightExecutions(ec)
        int submissionBudget = remainingSubmissionBudget(inFlightCount)
        List<Map<String, Object>> scanResults = []
        List<Map<String, Object>> deferredResults = []
        dueAutomationEntries.each { Map<String, Object> dueEntry ->
            def automation = dueEntry.automation
            String automationId = normalize(readField(automation, "automationId"))
            Timestamp scheduledFireTime = toTimestamp(dueEntry.scheduledFireTime)

            if (scanResults.size() >= submissionBudget) {
                // Deliberately visible, never a silent truncation: an operator reading the ServiceJobRun
                // must be able to tell "nothing was due" apart from "the cap was saturated".
                deferredResults << [automationId: automationId, scheduledFireTime: scheduledFireTime, deferred: true]
                return
            }

            Map<String, Object> executeParams = [
                    automationId     : automationId,
                    scheduledFireTime: scheduledFireTime,
                    nowTimestamp     : now,
            ]
            ["outputLocation", "sparkMaster", "sparkAppName"].each { key ->
                if (input[key] != null) executeParams[key] = input[key]
            }

            Map<String, Object> submitResult = submitExecuteAutomationService(ec, executeParams)
            if (submitResult.submitted != true) {
                // The submission itself was refused (saturated queue, dispatch error). Leave the schedule
                // alone so the next tick retries: nothing was enqueued, so there is nothing to double-fire.
                deferredResults << [automationId: automationId, scheduledFireTime: scheduledFireTime,
                                    deferred: true, submitResult: submitResult]
                return
            }

            // Advance the moment the execution is enqueued, NOT when it finishes. Advancing after the
            // run would leave a long execution still "due" on the next 5-minute tick and submit it a
            // second time while the first is mid-flight.
            Timestamp nextFireTime = resolveNextScheduledFireTime(automation, scheduledFireTime, now)
            updateAutomation(ec, automation, [
                    lastScheduledFireTime: scheduledFireTime,
                    nextScheduledFireTime: nextFireTime,
                    lastUpdatedDate      : now,
            ])
            scanResults << [
                    automationId         : automationId,
                    scheduledFireTime    : scheduledFireTime,
                    nextScheduledFireTime: nextFireTime,
                    executeResult        : submitResult,
            ]
        }

        List<Map<String, Object>> retryResults = reprocessDueRetries(ec, now, limit, input,
                submissionBudget - scanResults.size())

        // Only the cap deferrals are a saturation signal; a refused submission already logged its own
        // error above, and saying "in flight" about it would misattribute the cause.
        int capDeferredCount = deferredResults.count { Map<String, Object> entry -> !entry.containsKey("submitResult") } as int
        if (capDeferredCount > 0) {
            logger.warn("Automation scan deferred {} due automation(s) to the next tick: {} execution(s) already in flight (cap {})",
                    capDeferredCount, inFlightCount, MAX_CONCURRENT_EXECUTIONS)
        }

        return [
                scanTimestamp  : now,
                dueCount       : dueAutomationEntries.size(),
                scanResults    : scanResults,
                deferredCount  : deferredResults.size(),
                deferredResults: deferredResults,
                retryDueCount  : retryResults.size(),
                retryResults   : retryResults,
        ]
    }

    /**
     * How many more executions this tick may enqueue: {@link #MAX_CONCURRENT_EXECUTIONS} minus the
     * executions already RUNNING. Stale RUNNING rows (crashed JVM, killed container) cannot wedge the
     * scheduler forever — sweep#StuckReconciliationRuns reaps them to FAILED on its own 10-minute cron.
     */
    protected static int remainingSubmissionBudget(int inFlightCount) {
        int cap = MAX_CONCURRENT_EXECUTIONS > 0 ? MAX_CONCURRENT_EXECUTIONS : 1
        return Math.max(0, cap - inFlightCount)
    }

    protected static int countInFlightExecutions(def ec) {
        try {
            return TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION,
                            "system-cron cross-tenant sweep: count in-flight executions to bound submissions")
                    .condition("statusEnumId", STATUS_RUNNING)
                    .useCache(false)
                    .count() as int
        } catch (Throwable countError) {
            // Fail CLOSED on the flood guard, not open: if the in-flight count is unreadable, assume the
            // pool is busy rather than firing an unbounded batch at the Spark driver. The next tick is
            // five minutes away.
            logger.error("Could not count in-flight automation executions; deferring this scan's submissions", countError)
            return Integer.MAX_VALUE
        }
    }

    /**
     * Retry pickup (DAR-300): re-drive PENDING automation executions whose nextRetryDate is due, called
     * from the 5-min scanner so transient-failure requeues fire without a new cron. Uses an equality find
     * on statusEnumId then filters nextRetryDate in memory (only requeued rows carry one). retryCount
     * advances at pickup; a row that has reached maxRetryCount is dead-lettered instead of re-driven,
     * which bounds the loop even if the automation's window config changed under it.
     *
     * <p>DAR-BE-002: re-drives are SUBMITTED, not joined — this runs inside the same 5-minute scan, so a
     * synchronous re-drive here would keep the sweep long even after the scheduled path stopped waiting.
     * It shares the scan's in-flight submission budget: a re-drive past the budget is left unclaimed
     * (retryCount untouched, nextRetryDate still due) so the next tick picks it up unchanged.
     * Dead-lettering costs no worker and is never budget-gated.</p>
     */
    protected static List<Map<String, Object>> reprocessDueRetries(def ec, Timestamp now, int limit, Map<String, Object> input,
            Integer submissionBudget = null) {
        int remainingBudget = submissionBudget != null ? Math.max(0, submissionBudget) :
                remainingSubmissionBudget(countInFlightExecutions(ec))
        List pendingRows = TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION,
                        "system-cron retry pickup: PENDING automation executions past nextRetryDate")
                .condition("statusEnumId", STATUS_PENDING)
                .useCache(false)
                .list() ?: []
        List due = pendingRows.findAll { row ->
            Timestamp nrd = toTimestamp(readField(row, "nextRetryDate"))
            nrd != null && !nrd.after(now)
        }.sort { a, b -> toTimestamp(readField(a, "nextRetryDate")) <=> toTimestamp(readField(b, "nextRetryDate")) }
        if (limit > 0 && due.size() > limit) due = due.take(limit)

        List<Map<String, Object>> results = []
        Set<String> redriven = new LinkedHashSet<>()
        due.each { row ->
            String execId = normalize(readField(row, "automationExecutionId"))
            String automationId = normalize(readField(row, "automationId"))
            Timestamp scheduledDate = toTimestamp(readField(row, "scheduledDate"))
            int currentRetry = normalizeInt(readField(row, "retryCount")) ?: 0
            int maxRetry = resolveMaxRetryCount(row)
            Timestamp stamp = nowTimestamp(ec)

            if (currentRetry >= maxRetry) {
                updateAutomationExecution(ec, row, [
                        statusEnumId   : STATUS_DEAD_LETTER,
                        completedDate  : stamp,
                        maxRetryCount  : maxRetry,
                        lastUpdatedDate: stamp,
                ])
                // Dead-lettering means the automation has given up entirely — the one outcome an
                // operator most needs told about, and previously the quietest. Best-effort, and never
                // allowed to stop the rest of the retry sweep.
                try {
                    def deadLetterAutomation = loadAutomation(ec, automationId)
                    String failureReason = "Automation gave up after ${maxRetry} retries. " +
                            "Last error: ${normalize(readField(row, "errorMessage")) ?: "unknown"}"
                    String runResultId = normalize(readField(row, "reconciliationRunResultId")) ?:
                            persistFailureRunResult(ec, deadLetterAutomation,
                                    new IllegalStateException(failureReason), stamp)
                    notifyAutomationFailure(ec, deadLetterAutomation, runResultId, false, failureReason)
                } catch (Throwable deadLetterNotifyError) {
                    logger.warn("Dead-letter notification failed (best-effort): ${deadLetterNotifyError.message}")
                }
                results << [automationExecutionId: execId, automationId: automationId, statusEnumId: STATUS_DEAD_LETTER]
                return
            }

            if (remainingBudget <= 0) {
                // Do NOT claim what cannot be submitted: leaving retryCount and nextRetryDate untouched
                // means the next tick re-drives this exact row without having burnt a retry on it.
                results << [automationExecutionId: execId, automationId: automationId, deferred: true]
                return
            }

            // Claim the row: advance retryCount and lease nextRetryDate forward so a concurrent/next scan
            // cannot immediately re-pick it while this re-drive is in flight. (updateAutomationExecution
            // ignores null values, so we push the date out rather than clearing it; a reused row moves to
            // RUNNING, and a fresh failure overwrites nextRetryDate with the next backoff.)
            updateAutomationExecution(ec, row, [
                    retryCount     : currentRetry + 1,
                    maxRetryCount  : maxRetry,
                    nextRetryDate  : new Timestamp(now.time + retryBackoffMillis(currentRetry + 1)),
                    lastUpdatedDate: stamp,
            ])

            String dedupeKey = automationId + "|" + (scheduledDate != null ? scheduledDate.time : "null")
            if (!redriven.add(dedupeKey)) {
                results << [automationExecutionId: execId, automationId: automationId, redriveDeduped: true]
                return
            }

            Map<String, Object> executeParams = [
                    automationId     : automationId,
                    scheduledFireTime: scheduledDate,
                    nowTimestamp     : now,
            ]
            ["outputLocation", "sparkMaster", "sparkAppName"].each { String key -> if (input[key] != null) executeParams[key] = input[key] }
            Map<String, Object> executeResult = submitExecuteAutomationService(ec, executeParams)
            if (executeResult.submitted == true) remainingBudget--
            results << [automationExecutionId: execId, automationId: automationId, retryCount: currentRetry + 1, executeResult: executeResult]
        }
        return results
    }

    protected static int resolveMaxRetryCount(def execution) {
        Integer configured = normalizeInt(readField(execution, "maxRetryCount"))
        return configured != null ? configured : MAX_RETRY_COUNT_DEFAULT
    }

    /**
     * Operator re-drive (DAR-300 item 5): reset a FAILED or dead-lettered execution back to PENDING with
     * a fresh retry budget and nextRetryDate=now, so the 5-min scanner re-drives it. Tenant ownership +
     * run-permission are gated by the facade service (reprocess#AutomationExecution); this performs the
     * state transition and returns any user error in the result map (no ec.message, to stay unit-testable).
     */
    static Map<String, Object> reprocessAutomationExecution(def ec, Map params) {
        Map<String, Object> input = params ?: [:]
        String executionId = normalize(input.automationExecutionId)
        if (!executionId) return [requeued: false, error: "automationExecutionId is required"]

        def execution = TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION,
                        "operator reprocess: load automation execution by id for re-drive")
                .condition("automationExecutionId", executionId)
                .useCache(false)
                .one()
        if (!execution) {
            return [automationExecutionId: executionId, requeued: false,
                    error: "Automation execution '${executionId}' was not found.".toString()]
        }
        String status = normalize(readField(execution, "statusEnumId"))
        if (!(status in [STATUS_FAILED, STATUS_DEAD_LETTER])) {
            return [automationExecutionId: executionId, statusEnumId: status, requeued: false,
                    error: "Automation execution '${executionId}' is ${status}; only ${STATUS_FAILED} or ${STATUS_DEAD_LETTER} executions can be reprocessed.".toString()]
        }

        Integer requestedMax = normalizeInt(input.maxRetryCount)
        int maxRetry = requestedMax != null ? Math.max(0, requestedMax) : resolveMaxRetryCount(execution)
        Timestamp stamp = nowTimestamp(ec)
        updateAutomationExecution(ec, execution, [
                statusEnumId   : STATUS_PENDING,
                retryCount     : 0,
                maxRetryCount  : maxRetry,
                nextRetryDate  : stamp,
                errorMessage   : "",
                lastUpdatedDate: stamp,
        ])
        return [automationExecutionId: executionId, statusEnumId: STATUS_PENDING, requeued: true]
    }

    /**
     * Failure-transition fields with transient-vs-permanent classification (DAR-300). Permanent
     * (config/fence) failures go terminal (FAILED). Transient failures requeue to PENDING with a backoff
     * nextRetryDate for the scanner to re-drive; retryCount advances at pickup time.
     */
    protected static Map<String, Object> buildFailureFields(def ec, def execution, Throwable t,
            Timestamp failedAt, Map<String, Object> window) {
        boolean permanent = isPermanentFailure(t)
        int maxRetry = resolveMaxRetryCount(execution)
        boolean willRetry = !permanent && maxRetry > 0

        Map<String, Object> fields = [
                errorMessage    : truncate(sanitizeErrorMessage(t), 3900),
                errorDetail     : truncate(sanitizeErrorDetail(t), 12000),
                safeMetadataJson: safeMetadataJson([
                        mode            : "API_DATE_RANGE",
                        childWindowStart: window.childWindowStartDate,
                        childWindowEnd  : window.childWindowEndDate,
                        errorMessage    : sanitizeErrorMessage(t),
                        failureClass    : permanent ? "permanent" : "transient",
                        willRetry       : willRetry,
                ]),
                lastUpdatedDate : failedAt,
        ]
        if (willRetry) {
            int currentRetry = normalizeInt(readField(execution, "retryCount")) ?: 0
            fields.statusEnumId = STATUS_PENDING
            fields.maxRetryCount = maxRetry
            fields.nextRetryDate = new Timestamp(failedAt.time + retryBackoffMillis(currentRetry + 1))
            // completedDate intentionally left unset — the window is queued for retry, not finished.
        } else {
            fields.statusEnumId = STATUS_FAILED
            fields.completedDate = failedAt
            // A prior transient nextRetryDate (if any) is left as-is: FAILED is excluded from the
            // PENDING-only retry pickup, so it can't be re-driven. updateAutomationExecution ignores
            // nulls, so clearing it here would be a no-op anyway.
        }
        return fields
    }

    /**
     * Transient (retry) vs permanent (terminal) failure classification. Permanent = config/fence errors a
     * retry cannot fix (bad input, allow-list/naming-guard/registry/validation); everything else defaults
     * to transient so genuine flakiness (timeouts, 5xx, IO) is retried.
     */
    protected static boolean isPermanentFailure(Throwable t) {
        if (t == null) return false
        if (t instanceof IllegalArgumentException) return true
        StringBuilder sb = new StringBuilder()
        Throwable cur = t
        int depth = 0
        while (cur != null && depth < 8) {
            if (cur.message) sb.append(cur.message).append(' ')
            cur = cur.cause
            depth++
        }
        String msg = sb.toString().toLowerCase()
        return msg && PERMANENT_FAILURE_MARKERS.any { msg.contains(it) }
    }

    protected static long retryBackoffMillis(int attempt) {
        int a = Math.max(1, attempt)
        int shift = Math.min(a - 1, 20)
        long minutes = Math.min(RETRY_BACKOFF_BASE_MINUTES * (1L << shift), RETRY_BACKOFF_CAP_MINUTES)
        return minutes * 60_000L
    }

    /**
     * State-based automations define their population by record status rather than by a date window.
     * The window they still carry is bookkeeping only — it keeps execution rows, the findOrCreateExecution
     * dedup key, run metadata and the UI window display working unchanged — and is never sent to the
     * extractor (see applyWindowParameters).
     */
    static boolean isStateWindowMode(def automation) {
        return WINDOW_STATE == normalize(readField(automation, "relativeWindowTypeEnumId"))
    }

    static List<Map<String, Object>> resolveWindows(def automation, Map params = [:]) {
        Map<String, Object> input = params ?: [:]
        Timestamp scheduledFireTime = toTimestamp(input.scheduledFireTime ?: input.scheduledDate ?: input.nowTimestamp) ?:
                new Timestamp(System.currentTimeMillis())
        ZoneId zone = resolveZoneId(readField(automation, "windowTimeZone"))
        ZonedDateTime scheduledLocal = scheduledFireTime.toInstant().atZone(zone)
        String windowType = normalize(readField(automation, "relativeWindowTypeEnumId")) ?: WINDOW_LAST_DAYS
        int windowCount = normalizeInt(readField(automation, "relativeWindowCount")) ?: 1

        if (WINDOW_STATE == windowType) {
            // Exactly one segment, always. The bounds are the scheduled day in the automation's zone:
            // a real, valid, non-empty window that satisfies every downstream consumer, and that the
            // extractor never sees because state-mode dispatch omits the date parameters entirely.
            ZonedDateTime stateStart = scheduledLocal.toLocalDate().atStartOfDay(zone)
            ZonedDateTime stateEnd = stateStart.plusDays(1)
            Timestamp stateStartTimestamp = Timestamp.from(stateStart.toInstant())
            Timestamp stateEndTimestamp = Timestamp.from(stateEnd.toInstant())
            return [[
                    sequenceNum         : 1,
                    windowStartDate     : stateStartTimestamp,
                    windowEndDate       : stateEndTimestamp,
                    childWindowStartDate: stateStartTimestamp,
                    childWindowEndDate  : stateEndTimestamp,
            ]] as List<Map<String, Object>>
        }

        ZonedDateTime windowStart
        ZonedDateTime windowEnd
        switch (windowType) {
            case WINDOW_PREVIOUS_DAY:
                LocalDate previousDay = scheduledLocal.toLocalDate().minusDays(1)
                windowStart = previousDay.atStartOfDay(zone)
                windowEnd = previousDay.plusDays(1).atStartOfDay(zone)
                break
            case WINDOW_PREVIOUS_WEEK:
                LocalDate currentWeekStart = scheduledLocal.toLocalDate()
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                LocalDate previousWeekStart = currentWeekStart.minusWeeks(1)
                windowStart = previousWeekStart.atStartOfDay(zone)
                windowEnd = currentWeekStart.atStartOfDay(zone)
                break
            case WINDOW_PREVIOUS_MONTH:
                YearMonth previousMonth = YearMonth.from(scheduledLocal.toLocalDate()).minusMonths(1)
                windowStart = previousMonth.atDay(1).atStartOfDay(zone)
                windowEnd = previousMonth.plusMonths(1).atDay(1).atStartOfDay(zone)
                break
            case WINDOW_LAST_WEEKS:
                LocalDate weekEnd = scheduledLocal.toLocalDate()
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                windowStart = weekEnd.minusWeeks(Math.max(windowCount, 1)).atStartOfDay(zone)
                windowEnd = weekEnd.atStartOfDay(zone)
                break
            case WINDOW_LAST_MONTHS:
                YearMonth monthEnd = YearMonth.from(scheduledLocal.toLocalDate())
                windowStart = monthEnd.minusMonths(Math.max(windowCount, 1)).atDay(1).atStartOfDay(zone)
                windowEnd = monthEnd.atDay(1).atStartOfDay(zone)
                break
            case WINDOW_CUSTOM:
                Object customStart = input.windowStartDate ?: input.childWindowStartDate ?: readField(automation, "customWindowStartDate")
                Object customEnd = input.windowEndDate ?: input.childWindowEndDate ?: readField(automation, "customWindowEndDate")
                windowStart = requireTimestamp(customStart, "windowStartDate is required for custom automation windows")
                        .toInstant().atZone(zone)
                windowEnd = requireTimestamp(customEnd, "windowEndDate is required for custom automation windows")
                        .toInstant().atZone(zone)
                break
            case WINDOW_LAST_DAYS:
            default:
                LocalDate dayEnd = scheduledLocal.toLocalDate()
                windowStart = dayEnd.minusDays(Math.max(windowCount, 1)).atStartOfDay(zone)
                windowEnd = dayEnd.atStartOfDay(zone)
                break
        }

        if (!windowStart.isBefore(windowEnd)) {
            throw new IllegalArgumentException("Automation window start must be before window end")
        }

        // Security (HIGH gap 2): hard DoS sanity cap on the window span, INDEPENDENT of the
        // per-automation `maxWindowDays` field. A caller-supplied WINDOW_CUSTOM range like
        // 1970-01-01..9999-12-31 (~2.9M days) otherwise produces ~120k month segments, each inserting a
        // ReconciliationAutomationExecution row inside a transaction -> DB bloat / heap OOM / ghost
        // PENDING rows.
        //
        // IMPORTANT: `maxWindowDays` is an OPERATIONAL window default (entity default=28 days), NOT a
        // security cap. Using it here would REJECT legitimate windows: a 31-day month
        // (WINDOW_PREVIOUS_MONTH / LAST_MONTHS=1 on a 30/31-day month, ordinary CUSTOM month ranges)
        // exceeds 28 and would be wrongly refused. The DoS guard must NEVER reject a legitimate window,
        // so it uses a separate, deliberately huge sanity ceiling (10 years) that only the attack
        // pathological ranges can breach. Operators can tune it via the server property below.
        long __spanDays = ChronoUnit.DAYS.between(windowStart, windowEnd)
        if (__spanDays > MAX_WINDOW_SPAN_DAYS) {
            throw new IllegalArgumentException(
                    "Automation window span (${__spanDays} days) exceeds the maximum of ${MAX_WINDOW_SPAN_DAYS} days.")
        }

        Timestamp parentStart = Timestamp.from(windowStart.toInstant())
        Timestamp parentEnd = Timestamp.from(windowEnd.toInstant())
        return splitOnCalendarMonthBoundaries(windowStart, windowEnd, zone).withIndex().collect { Map<String, ZonedDateTime> segment, int index ->
            [
                    sequenceNum         : index + 1,
                    windowStartDate     : parentStart,
                    windowEndDate       : parentEnd,
                    childWindowStartDate: Timestamp.from(segment.start.toInstant()),
                    childWindowEndDate  : Timestamp.from(segment.end.toInstant()),
            ]
        }
    }

    static Timestamp resolveNextScheduledFireTime(def automation, Timestamp scheduledFireTime, Timestamp now) {
        String scheduleExpr = normalize(readField(automation, "scheduleExpr"))
        if (!scheduleExpr) return null
        ZoneId zone = resolveZoneId(readField(automation, "windowTimeZone"))
        Timestamp base = scheduledFireTime ?: now ?: new Timestamp(System.currentTimeMillis())

        try {
            ZonedDateTime cursor = base.toInstant().atZone(zone).plusNanos(1)
            ZonedDateTime nowLocal = (now ?: base).toInstant().atZone(zone)
            Optional<ZonedDateTime> next = ScheduledJobRunner.getExecutionTime(scheduleExpr).nextExecution(cursor)
            while (next.present && !next.get().isAfter(nowLocal)) {
                next = ScheduledJobRunner.getExecutionTime(scheduleExpr).nextExecution(next.get().plusNanos(1))
            }
            return next.present ? Timestamp.from(next.get().toInstant()) : null
        } catch (Throwable ignored) {
            Duration duration = parseScheduleDuration(scheduleExpr)
            if (duration == null || duration.isZero() || duration.isNegative()) return null
            ZonedDateTime next = base.toInstant().atZone(zone).plus(duration)
            ZonedDateTime nowLocal = (now ?: base).toInstant().atZone(zone)
            while (!next.isAfter(nowLocal)) next = next.plus(duration)
            return Timestamp.from(next.toInstant())
        }
    }

    static Timestamp resolveDueScheduledFireTime(def automation, Timestamp now) {
        Timestamp nowValue = now ?: new Timestamp(System.currentTimeMillis())
        Timestamp nextFire = toTimestamp(readField(automation, "nextScheduledFireTime"))
        if (nextFire != null) return nextFire.after(nowValue) ? null : nextFire

        String scheduleExpr = normalize(readField(automation, "scheduleExpr"))
        if (!scheduleExpr) return null

        Timestamp lastScheduledFireTime = toTimestamp(readField(automation, "lastScheduledFireTime"))
        ZoneId zone = resolveZoneId(readField(automation, "windowTimeZone"))
        Timestamp cronFireTime = resolveLastCronFireTime(scheduleExpr, nowValue, zone)
        if (cronFireTime != null) {
            return lastScheduledFireTime == null || lastScheduledFireTime.before(cronFireTime) ? cronFireTime : null
        }

        Timestamp durationFireTime = resolveLastDurationFireTime(scheduleExpr, lastScheduledFireTime, nowValue, zone)
        if (durationFireTime != null) {
            return lastScheduledFireTime == null || lastScheduledFireTime.before(durationFireTime) ? durationFireTime : null
        }
        return null
    }

    protected static Map<String, Object> normalizeSftpExecutionParams(Map<String, Object> input) {
        Map<String, Object> sftpParams = [:]
        sftpParams.putAll(input)
        if (sftpParams.scheduledFireTime && !sftpParams.scheduledDate) sftpParams.scheduledDate = sftpParams.scheduledFireTime
        return sftpParams
    }

    /**
     * Fail-closed tenant assertion for system/cron write paths (MACH P0: the cron write path must
     * explicitly assert tenant identity instead of silently stamping under disableAuthz).
     *
     * <p>Scheduler/SFTP-poller writes carry no active user tenant, so the companyUserGroupId stamped
     * on ReconciliationAutomationExecution / ReconciliationRunResult rows comes from the automation
     * record itself. A blank tenant would create a row invisible to every tenant-scoped read; a
     * stale one would surface in the wrong tenant space. Both abort the write.</p>
     *
     * @return the validated companyUserGroupId (never blank)
     * @throws IllegalStateException when the automation has no companyUserGroupId or the group no longer exists
     */
    protected static String assertSystemWriteTenant(def ec, def automation) {
        String automationId = normalize(readField(automation, "automationId"))
        String tenantId = normalize(readField(automation, "companyUserGroupId"))
        if (!tenantId) {
            throw new IllegalStateException("Automation ${automationId} has no companyUserGroupId; " +
                    "refusing system write without an asserted tenant identity")
        }
        if (resolveSystemTenantId(ec, automation) == null) {
            throw new IllegalStateException("Automation ${automationId} companyUserGroupId ${tenantId} " +
                    "does not match any UserGroup; refusing system write with a stale tenant identity")
        }
        return tenantId
    }

    /**
     * Non-throwing counterpart to {@link #assertSystemWriteTenant}: returns the automation's
     * companyUserGroupId only if it still names a real UserGroup, else null.
     *
     * <p>Used to publish the system tenant context at the top of a run. It must NOT throw: an
     * automation with a blank or stale tenant has always failed later, fail-closed, at the write
     * assertion — with a message naming the problem — and that stays the behaviour rather than
     * moving the failure earlier and changing what operators see.</p>
     */
    protected static String resolveSystemTenantId(def ec, def automation) {
        String tenantId = normalize(readField(automation, "companyUserGroupId"))
        if (!tenantId) return null
        def group = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.security.UserGroup",
                        "system tenant assertion — validating automation companyUserGroupId against UserGroup")
                .condition("userGroupId", tenantId)
                .useCache(true)
                .one()
        return group == null ? null : tenantId
    }

    protected static Map<String, Object> findOrCreateExecution(def ec, def automation, Timestamp scheduledFireTime,
            Map<String, Object> window, int sequenceNum) {
        String automationId = normalize(readField(automation, "automationId"))
        Timestamp childStart = toTimestamp(window.childWindowStartDate)
        Timestamp childEnd = toTimestamp(window.childWindowEndDate)

        def existing = TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION,
                        "system-cron cross-tenant sweep: findOrCreateExecution keyed by automationId and scheduled time")
                .condition("automationId", automationId)
                .condition("scheduledDate", scheduledFireTime)
                .condition("childWindowStartDate", childStart)
                .condition("childWindowEndDate", childEnd)
                .useCache(false)
                .one()
        if (existing) {
            String statusEnumId = normalize(readField(existing, "statusEnumId"))
            return [
                    execution: existing,
                    duplicate: !REUSABLE_EXECUTION_STATUSES.contains(statusEnumId),
                    reused   : REUSABLE_EXECUTION_STATUSES.contains(statusEnumId),
            ]
        }

        String assertedTenantId = assertSystemWriteTenant(ec, automation)
        def created = runInTransaction(ec, "Error creating reconciliation automation execution", {
            Timestamp createdTimestamp = nowTimestamp(ec)
            def execution = ec.entity.makeValue(DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION)
            execution.set("automationId", automationId)
            execution.set("companyUserGroupId", assertedTenantId)
            execution.set("createdByUserId", normalize(readField(automation, "createdByUserId")) ?: currentUserId(ec))
            execution.set("statusEnumId", STATUS_PENDING)
            execution.set("scheduledDate", scheduledFireTime)
            execution.set("childWindowSequenceNum", sequenceNum)
            execution.set("windowStartDate", toTimestamp(window.windowStartDate))
            execution.set("windowEndDate", toTimestamp(window.windowEndDate))
            execution.set("childWindowStartDate", childStart)
            execution.set("childWindowEndDate", childEnd)
            execution.set("createdDate", createdTimestamp)
            execution.set("lastUpdatedDate", createdTimestamp)
            execution.setSequencedIdPrimary()
            execution.create()
            return execution
        })
        return [execution: created, duplicate: false, reused: false]
    }

    protected static List<Map<String, ZonedDateTime>> splitOnCalendarMonthBoundaries(ZonedDateTime start,
            ZonedDateTime end, ZoneId zone) {
        List<Map<String, ZonedDateTime>> segments = []
        ZonedDateTime segmentStart = start
        while (segmentStart.isBefore(end)) {
            ZonedDateTime nextMonthStart = YearMonth.from(segmentStart.toLocalDate()).plusMonths(1)
                    .atDay(1).atStartOfDay(zone)
            ZonedDateTime segmentEnd = nextMonthStart.isBefore(end) ? nextMonthStart : end
            segments << [start: segmentStart, end: segmentEnd]
            segmentStart = segmentEnd
        }
        return segments
    }

    protected static Timestamp resolveLastCronFireTime(String scheduleExpr, Timestamp now, ZoneId zone) {
        try {
            ZonedDateTime nowLocal = now.toInstant().atZone(zone)
            Optional<ZonedDateTime> last = ScheduledJobRunner.getExecutionTime(scheduleExpr).lastExecution(nowLocal)
            return last.present ? Timestamp.from(last.get().toInstant()) : null
        } catch (Throwable ignored) {
            return null
        }
    }

    protected static Timestamp resolveLastDurationFireTime(String scheduleExpr, Timestamp lastScheduledFireTime,
            Timestamp now, ZoneId zone) {
        Duration duration = parseScheduleDuration(scheduleExpr)
        if (duration == null || duration.isZero() || duration.isNegative() || lastScheduledFireTime == null) return null

        ZonedDateTime nowLocal = now.toInstant().atZone(zone)
        ZonedDateTime candidate = lastScheduledFireTime.toInstant().atZone(zone).plus(duration)
        Timestamp latestDue = null
        while (!candidate.isAfter(nowLocal)) {
            latestDue = Timestamp.from(candidate.toInstant())
            candidate = candidate.plus(duration)
        }
        return latestDue
    }

    protected static Map<String, Object> callConfiguredSourceExtractor(def ec, def automation, def source,
            Map<String, Object> window, Map<String, Object> params) {
        Map<String, Object> metadata = resolveSourceExtractorMetadata(ec, source,
                params?.sourceExtractorConfigDefaults instanceof Map ? (Map<String, Object>) params.sourceExtractorConfigDefaults : [:])
        String serviceName = normalize(metadata.extractServiceName ?: metadata.serviceName)
        if (!serviceName) {
            String systemEnumId = normalize(readField(source, "systemEnumId")) ?: "unknown system"
            String fileSide = normalize(readField(source, "fileSide")) ?: "source"
            throw new IllegalStateException("No source connector is registered for ${systemEnumId} ${fileSide} (no SourceSystemConnector row resolved an extract service).")
        }
        // Security (HIGH gap 6, defense-in-depth 2026-06-30): the save-time allowlist
        // (AutomationFacadeSupport) gates new writes, but this sink invokes the persisted serviceName
        // verbatim via a sync service call with authz disabled on EVERY scheduled run. Re-check
        // the allowlist here so pre-existing rows or any future write path that bypasses the save check
        // cannot drive arbitrary authz-disabled service execution from stored metadata. These three are
        // exactly the values the legitimate save path produces, so no real source is rejected.
        if (!SourceSystemConnectorSupport.allowedServiceNames(ec).contains(serviceName)) {
            throw new IllegalStateException("API source extractor service '${serviceName}' is not in the allowed service list.")
        }
        // Defense-in-depth naming guard: even a registry-registered name must match a recognized
        // extractor/execute shape before this authz-relaxed sink invokes it (blocks a hostile/
        // misconfigured connector row from pointing dispatch at an arbitrary internal service).
        if (!SourceSystemConnectorSupport.isAllowedExtractorServiceShape(serviceName)) {
            throw new IllegalStateException("API source extractor service '${serviceName}' does not match an allowed extractor service name pattern.")
        }

        Map<String, Object> serviceParams = [:]
        if (metadata.parameters instanceof Map) serviceParams.putAll((Map<String, Object>) metadata.parameters)
        serviceParams.automationId = normalize(readField(automation, "automationId"))
        serviceParams.companyUserGroupId = normalize(readField(automation, "companyUserGroupId"))
        serviceParams.fileSide = normalize(readField(source, "fileSide"))
        serviceParams.systemEnumId = normalize(readField(source, "systemEnumId"))
        serviceParams.sourceTypeEnumId = normalize(readField(source, "sourceTypeEnumId"))
        serviceParams.windowStartDate = window.childWindowStartDate
        serviceParams.windowEndDate = window.childWindowEndDate

        // Date-parameter names + the Shopify preserve-window flag are now data-driven: look the connector
        // up by the service being invoked (what the pre-registry switch keyed on). A source-level override
        // still wins; fall back to fromDate/toDate when no connector matches.
        Map<String, Object> connectorForService = SourceSystemConnectorSupport.resolveByExtractServiceName(ec, serviceName)
        applyWindowParameters(serviceParams, automation, source, connectorForService, window)
        if (connectorForService?.preserveWindowInstants) serviceParams.preserveWindowInstants = true

        applyExcludeFilterParameter(serviceParams, connectorForService,
                loadAutomationSourceFilters(ec,
                        normalize(readField(automation, "automationId")),
                        normalize(readField(source, "fileSide"))))
        applyStatusParameter(serviceParams, connectorForService, source)
        applyWindowFieldParameter(serviceParams, connectorForService)

        def call = ec.service.sync().name(serviceName).parameters(serviceParams)
        if (call?.metaClass?.respondsTo(call, "disableAuthz")) call = call.disableAuthz()
        Map<String, Object> result = (call.call() ?: [:]) as Map<String, Object>
        List<String> errors = (result.errors instanceof Collection ? (Collection) result.errors : [])
                .collect { Object error -> normalize(error) }
                .findAll { String error -> error } as List<String>
        if (errors) throw new IllegalStateException(errors.join("; "))
        return result
    }

    /**
     * Date-parameter names are data-driven: a source-level override wins, then the connector, then
     * fromDate/toDate. A state-mode automation on a connector that declares supportsStateExtract sends
     * NO date parameters at all — its population is defined by status. The automation still carries a
     * window (bookkeeping only), so the connector flag is what decides, not the presence of a window.
     *
     * Fail safe: a state-mode automation on a connector that cannot do state extraction still receives
     * its window rather than an unbounded request. That combination is rejected at save time.
     */
    protected static Map<String, Object> applyWindowParameters(Map<String, Object> serviceParams,
                                                               def automation, def source,
                                                               Map<String, Object> connector,
                                                               Map<String, Object> window) {
        if (isStateWindowMode(automation) && connector?.supportsStateExtract) return serviceParams

        String dateFromParameterName = normalize(readField(source, "dateFromParameterName")) ?:
                (normalize(connector?.dateFromParameterName) ?: "fromDate")
        String dateToParameterName = normalize(readField(source, "dateToParameterName")) ?:
                (normalize(connector?.dateToParameterName) ?: "toDate")
        serviceParams[dateFromParameterName] = window.childWindowStartDate
        serviceParams[dateToParameterName] = window.childWindowEndDate
        return serviceParams
    }

    /** Registry-driven: only a connector that declares a filter parameter receives exclusion rules. */
    protected static Map<String, Object> applyExcludeFilterParameter(Map<String, Object> serviceParams,
                                                                     Map<String, Object> connector,
                                                                     List<Map<String, Object>> excludeFilters) {
        String filterParameterName = normalize(connector?.get("filterParameterName"))
        if (filterParameterName && excludeFilters) serviceParams.put(filterParameterName, excludeFilters)
        return serviceParams
    }

    /** Registry-driven, same shape as applyExcludeFilterParameter: only a connector that declares a
     *  status parameter ever receives one. */
    protected static Map<String, Object> applyStatusParameter(Map<String, Object> serviceParams,
                                                              Map<String, Object> connector,
                                                              def source) {
        String statusParameterName = normalize(connector?.get("statusParameterName"))
        if (!statusParameterName) return serviceParams

        List<String> statusIds = (normalize(readField(source, "extractStatusIds")) ?: "")
                .tokenize(",")
                .collect { String value -> normalize(value) }
                .findAll { String value -> value } as List<String>
        if (statusIds) serviceParams.put(statusParameterName, statusIds)
        return serviceParams
    }

    /** Config over code: the connector names the record date field its extract window filters on.
     *  Blank leaves the parameter unset so the extractor keeps its own default (orderDate), which is
     *  what every pre-existing connector row does. */
    protected static Map<String, Object> applyWindowFieldParameter(Map<String, Object> serviceParams,
                                                                   Map<String, Object> connector) {
        String windowFieldName = normalize(connector?.get("windowFieldName"))
        if (windowFieldName) serviceParams.windowFieldName = windowFieldName
        return serviceParams
    }

    protected static Map<String, Object> resolveSourceExtractorConfigDefaults(def ec, def automation, Collection sources) {
        String companyUserGroupId = normalize(readField(automation, "companyUserGroupId"))
        if (!companyUserGroupId) return [:]

        Map<String, Object> defaults = [:]
        (sources ?: []).each { source ->
            Map<String, Object> metadata = parseJsonMap(readField(source, "safeMetadataJson"))
            if (normalize(metadata.extractServiceName ?: metadata.serviceName)) return
            Map parameters = metadata.parameters instanceof Map ? (Map) metadata.parameters : [:]
            Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, normalize(readField(source, "systemEnumId")))
            String configParameterName = normalize(connector?.configParameterName)
            if (!configParameterName) return
            if (normalize(parameters[configParameterName]) || defaults.containsKey(configParameterName)) return
            // A connector whose config id lives on the source row (DATABASE -> databaseSourceQueryId) carries
            // its own id per source, so no shared tenant default is needed and the canReadOrders-filtered
            // findSingleActiveConfigId (which cannot match DatabaseSourceQuery) must not run. Null for the
            // API/SFTP connectors, whose id is not a row column, so their default resolution is unchanged.
            if (readSourceRowConfigId(source, configParameterName)) return
            String configId = findSingleActiveConfigId(ec, companyUserGroupId,
                    normalize(connector.configEntityName), configParameterName)
            if (configId) defaults[configParameterName] = configId
        }
        return defaults
    }

    protected static Map<String, Object> resolveSourceExtractorMetadata(def ec, def source, Map<String, Object> configDefaults = [:]) {
        Map<String, Object> metadata = parseJsonMap(readField(source, "safeMetadataJson"))
        if (normalize(metadata.extractServiceName ?: metadata.serviceName)) return metadata

        String systemEnumId = normalize(readField(source, "systemEnumId"))
        Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, systemEnumId)
        // No connector, or a connector with no extract service (e.g. NETSUITE, which has no automation
        // extractor): leave the metadata service unset — callConfiguredSourceExtractor reports it as not
        // registered, exactly as the pre-registry switch did for unmatched systems.
        if (!connector || !normalize(connector.extractServiceName)) return metadata

        String companyUserGroupId = normalize(readField(source, "companyUserGroupId"))
        Map<String, Object> parameters = metadata.parameters instanceof Map ?
                new LinkedHashMap<>((Map<String, Object>) metadata.parameters) : [:]

        String configParameterName = normalize(connector.configParameterName)
        if (configParameterName) {
            // Preserve today's behavior exactly for the API/SFTP connectors (OMS/Shopify): those store the
            // config id in safeMetadataJson.parameters, never as a column on the source row, so the row read
            // below is null for them and the resolution falls through the SAME chain as before
            // (source param -> automation default -> the single active config for the tenant).
            //
            // AUT_SRC_DB regression fix: a connector whose configParameterName names a REAL column on the
            // automation source row (DATABASE -> databaseSourceQueryId) resolves that id DIRECTLY from the
            // admin-chosen row value. This bypasses findSingleActiveConfigId, whose canReadOrders="Y" filter
            // does not apply to DatabaseSourceQuery and whose "single active" pick would ignore the admin's
            // choice on a multi-query tenant. The id still flows into the extract service, which re-checks
            // tenant scope. Otherwise (no config id anywhere) leave the metadata service unset.
            String configId = readSourceRowConfigId(source, configParameterName) ?:
                    normalize(parameters[configParameterName]) ?:
                    normalize(configDefaults?[configParameterName]) ?:
                    findSingleActiveConfigId(ec, companyUserGroupId,
                            normalize(connector.configEntityName), configParameterName)
            if (!configId) return metadata
            parameters[configParameterName] = configId
        }

        metadata.parameters = parameters
        metadata.extractServiceName = connector.extractServiceName
        return metadata
    }

    /**
     * The admin-chosen config id stored DIRECTLY on the automation source row, when the connector's
     * configParameterName names a real column on that row (config over code: DATABASE ->
     * databaseSourceQueryId). Returns null for connectors whose config id is not a source-row column
     * (OMS/Shopify keep it in safeMetadataJson.parameters), so those dispatch paths stay byte-identical.
     *
     * A Moqui EntityValue is also a Map, but its containsKey only reflects fields actually loaded, so the
     * definition-backed isField check gates the read: reading an unknown field via EntityValue.get()
     * throws. A plain Map (unit-test source) has no isField, so key presence is the column-present signal.
     */
    protected static String readSourceRowConfigId(def source, String configParameterName) {
        if (source == null || !configParameterName) return null
        if (source.metaClass.respondsTo(source, "isField", String)) {
            return source.isField(configParameterName) ? normalize(readField(source, configParameterName)) : null
        }
        if (source instanceof Map) {
            return ((Map) source).containsKey(configParameterName) ?
                    normalize(((Map) source).get(configParameterName)) : null
        }
        return normalize(readField(source, configParameterName))
    }

    protected static String findSingleActiveConfigId(def ec, String companyUserGroupId, String configEntityName, String configIdField) {
        if (!companyUserGroupId || !configEntityName || !configIdField) return null
        try {
            List rows = TenantScopedFinder.findGlobalUnscoped(ec, configEntityName,
                            "config lookup keyed by explicit companyUserGroupId from automation record")
                    .condition("companyUserGroupId", companyUserGroupId)
                    .condition("isActive", "Y")
                    .condition("canReadOrders", "Y")
                    .useCache(false)
                    .limit(2)
                    .list() ?: []
            return rows.size() == 1 ? normalize(readField(rows.first(), configIdField)) : null
        } catch (Exception e) {
            logger.warn("Failed to detect single active source config for ${configEntityName}", e)
            return null
        }
    }

    protected static String findSingleActiveOmsRestSourceConfigId(def ec, String companyUserGroupId) {
        return findSingleActiveConfigId(ec, companyUserGroupId,
                DarpanEntityConstants.HOT_WAX_OMS_REST_SOURCE_CONFIG, "omsRestSourceConfigId")
    }

    protected static String findSingleActiveShopifyAuthConfigId(def ec, String companyUserGroupId) {
        return findSingleActiveConfigId(ec, companyUserGroupId,
                DarpanEntityConstants.SHOPIFY_AUTH_CONFIG, "shopifyAuthConfigId")
    }

    protected static String sourceLabel(def ec, String systemEnumId, String fallback) {
        String enumId = normalize(systemEnumId)
        if (!enumId) return fallback
        try {
            def enumeration = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.basic.Enumeration",
                            "framework reference data: enumeration label lookup")
                    .condition("enumId", enumId)
                    .useCache(true)
                    .one()
            return normalize(readField(enumeration, "description")) ?: normalize(readField(enumeration, "enumCode")) ?: enumId
        } catch (Throwable ignored) {
            return enumId
        }
    }

    protected static Map<String, Object> callRuleSetCompareScope(def ec, def automation, def file1Source, def file2Source,
            Map<String, Object> file1Result, Map<String, Object> file2Result, Map<String, Object> window,
            Map<String, Object> params) {
        Map<String, Object> serviceParams = [
                ruleSetId           : normalize(readField(automation, "ruleSetId")) ?: normalize(readField(automation, "savedRunId")),
                compareScopeId      : normalize(readField(automation, "compareScopeId")),
                file1Location       : file1Result.fileLocation ?: file1Result.dataManagerPath,
                file2Location       : file2Result.fileLocation ?: file2Result.dataManagerPath,
                file1Name           : file1Result.fileName,
                file2Name           : file2Result.fileName,
                file1FileTypeEnumId : file1Result.fileTypeEnumId ?: readField(file1Source, "fileTypeEnumId"),
                file2FileTypeEnumId : file2Result.fileTypeEnumId ?: readField(file2Source, "fileTypeEnumId"),
                file1SchemaFileName : file1Result.schemaFileName ?: readField(file1Source, "schemaFileName"),
                file2SchemaFileName : file2Result.schemaFileName ?: readField(file2Source, "schemaFileName"),
                hasHeader           : params.containsKey("hasHeader") ? params.hasHeader : Boolean.TRUE,
                // Security (HIGH gaps 3,5): IGNORE any caller-supplied sparkMaster. Resolve server-side
                // only so an authenticated API caller cannot redirect the tenant's Spark job to an
                // attacker cluster. PWA never sends this; operators set spark.master via server property.
                sparkMaster         : (ec.resource.properties["spark.master"] ?: "local[*]"),
                sparkAppName        : normalize(params.sparkAppName) ?: "AutomationRuleSetCompareScope",
        ].findAll { it.value != null } as Map<String, Object>

        def call = ec.service.sync()
                .name("reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope")
                .parameters(serviceParams)
        if (call?.metaClass?.respondsTo(call, "disableAuthz")) call = call.disableAuthz()
        return (call.call() ?: [:]) as Map<String, Object>
    }

    protected static Map<String, Object> callExecuteAutomationService(def ec, Map<String, Object> executeParams) {
        def call = ec.service.sync()
                .name("reconciliation.ReconciliationAutomationServices.execute#Automation")
                .parameters(executeParams)
        if (call?.metaClass?.respondsTo(call, "disableAuthz")) call = call.disableAuthz()
        return (call.call() ?: [:]) as Map<String, Object>
    }

    /**
     * DAR-BE-002: hand execute#Automation to Moqui's async worker pool and return AT ONCE — no future,
     * no join. The returned map is a submission ACK, not an outcome: the execution's real result lives on
     * its own ReconciliationAutomationExecution + ReconciliationRunResult rows, both minted at RUNNING,
     * which is what the live progress view follows.
     *
     * <p>executeParams must keep carrying automationId + scheduledFireTime: the detached execution has no
     * ambient tenant (the scheduler runs as anonymous {@code _NA_}) and resolves its tenant anchor from
     * the automation's companyUserGroupId, exactly as the joined path did.</p>
     *
     * <p>Contexts with no async facade (unit harnesses, degraded runtimes) fall back to running inline
     * rather than dropping the window — the same fallback the future-based dispatcher had.</p>
     */
    protected static Map<String, Object> submitExecuteAutomationService(def ec, Map<String, Object> executeParams) {
        String automationId = normalize(executeParams?.automationId)
        if (!ec?.service?.metaClass?.respondsTo(ec.service, "async")) {
            try {
                return (callExecuteAutomationService(ec, executeParams) ?: [:]) + [submitted: true, submittedAsync: false]
            } catch (Throwable inlineError) {
                logger.error("Inline automation execution failed for {}: {}", automationId, sanitizeErrorMessage(inlineError))
                return [submitted: false, submittedAsync: false, error: sanitizeErrorMessage(inlineError)]
            }
        }

        try {
            def call = ec.service.async()
                    .name("reconciliation.ReconciliationAutomationServices.execute#Automation")
                    .parameters(executeParams)
            if (call?.metaClass?.respondsTo(call, "disableAuthz")) call = call.disableAuthz()
            call.call()
            return [submitted: true, submittedAsync: true]
        } catch (Throwable submitError) {
            // A refused submission (saturated queue, dispatch error) must be loud and must NOT be
            // reported as a run: the caller leaves the schedule unadvanced so the next tick retries.
            logger.error("Could not submit automation execution for {}: {}", automationId, sanitizeErrorMessage(submitError))
            return [submitted: false, submittedAsync: true, error: sanitizeErrorMessage(submitError)]
        }
    }

    protected static List loadActiveAutomations(def ec) {
        return TenantScopedFinder.findGlobalUnscoped(ec, "darpan.reconciliation.ReconciliationAutomation",
                        "system-cron cross-tenant sweep; no active tenant in scheduler context")
                .condition("isActive", "Y")
                .useCache(false)
                .list() ?: []
    }

    protected static def requireApiSource(def automation, def source, String fileSide) {
        String automationId = normalize(readField(automation, "automationId"))
        if (!source) throw new IllegalArgumentException("Automation ${automationId} is missing ${fileSide} source")

        String automationTenant = normalize(readField(automation, "companyUserGroupId"))
        String sourceTenant = normalize(readField(source, "companyUserGroupId"))
        if (automationTenant && sourceTenant && automationTenant != sourceTenant) {
            throw new IllegalArgumentException("Automation ${automationId} ${fileSide} source belongs to tenant ${sourceTenant}, not ${automationTenant}")
        }

        String sourceTypeEnumId = normalize(readField(source, "sourceTypeEnumId"))
        if (sourceTypeEnumId != AUTOMATION_SOURCE_API && sourceTypeEnumId != AUTOMATION_SOURCE_DB) {
            throw new IllegalArgumentException("Automation ${automationId} ${fileSide} source must use ${AUTOMATION_SOURCE_API} or ${AUTOMATION_SOURCE_DB}")
        }
        return source
    }

    protected static Map<String, Object> normalizeSourceResult(Object rawResult, def source) {
        Map<String, Object> result = rawResult instanceof Map ? new LinkedHashMap<String, Object>((Map) rawResult) : [:]
        result.dataAvailable = result.containsKey("dataAvailable") ?
                toBoolean(result.dataAvailable) :
                Boolean.valueOf(result.fileLocation || result.dataManagerPath || result.location)
        result.fileLocation = normalize(result.fileLocation ?: result.dataManagerPath ?: result.location)
        result.dataManagerPath = normalize(result.dataManagerPath ?: result.fileLocation)
        result.fileName = normalize(result.fileName) ?: fileNameFromPath(result.fileLocation)
        result.fileTypeEnumId = normalize(result.fileTypeEnumId) ?: normalize(readField(source, "fileTypeEnumId"))
        result.schemaFileName = normalize(result.schemaFileName) ?: normalize(readField(source, "schemaFileName"))
        result.recordCount = normalizeInt(result.recordCount)
        return result
    }

    protected static Map<String, Object> normalizeReconcileResult(Object rawResult) {
        return rawResult instanceof Map ? new LinkedHashMap<String, Object>((Map) rawResult) : [:]
    }

    /**
     * Gorjana prod 2026-07-28 regression: a message-level error in the compare chain never throws —
     * Moqui sync calls short-circuit once ec.message has errors and every pipeline stage guards its
     * out-params with !ec.message.hasError() — so the compare returns an EMPTY map and the execution
     * was stamped SUCCEEDED with no differenceCount, no result artifact, and no run-result row.
     * Fail loudly instead: consume the accumulated errors (leftover facade state would short-circuit
     * every later sync call in the same scan sweep) and throw so buildFailureFields classifies the
     * failure (permanent -> FAILED, transient -> PENDING retry). SUCCEEDED therefore always carries
     * compare output.
     */
    protected static void requireReconcileOutput(def ec, Map<String, Object> reconcileResult) {
        String messageErrors = consumeMessageFacadeErrors(ec)
        boolean hasOutput = reconcileResult.diffDf != null || reconcileResult.differenceCount != null ||
                normalize(reconcileResult.resultDataManagerPath ?: reconcileResult.diffLocation ?: reconcileResult.diffFileName)
        if (messageErrors == null && hasOutput) return
        throw new IllegalStateException(messageErrors != null ?
                "Reconcile compare stage reported errors: ${messageErrors}".toString() :
                "Reconcile compare stage returned no result (no difference count and no diff artifact)")
    }

    /** Accumulated ec.message error text, cleared as it is read; null when there are none (or the ec has no message facade). */
    protected static String consumeMessageFacadeErrors(def ec) {
        try {
            def message = ec?.message
            if (message == null || message.hasError() != true) return null
            String errorsText = normalize(message.getErrorsString())
            message.clearErrors()
            return errorsText ?: "unspecified error"
        } catch (Exception ignored) {
            return null
        }
    }

    protected static Map<String, Object> ensureAutomationResultArtifact(def ec, def automation, def file1Source, def file2Source,
            Map<String, Object> reconcileResult, Map<String, Object> window, Map<String, Object> params) {
        if (!reconcileResult) return [:]
        if (normalize(reconcileResult.resultDataManagerPath ?: reconcileResult.diffLocation ?: reconcileResult.diffFileName)) {
            return reconcileResult
        }

        Dataset diffDf = reconcileResult.diffDf instanceof Dataset ? (Dataset) reconcileResult.diffDf : null
        if (diffDf == null) return reconcileResult

        String runToken = DataManagerSupport.safeToken(
                normalize(readField(automation, "savedRunId")) ?: normalize(readField(automation, "automationId")),
                "automation"
        )
        String runArtifactLocation = normalize(params?.outputLocation) ?:
                DataManagerSupport.resolveReconciliationRunLocation(ec, runToken, DataManagerSupport.formatRunTimestamp(ec))
        File runArtifactDir = DataManagerSupport.resolveDirectoryFile(ec, runArtifactLocation, true)
        if (runArtifactDir == null) {
            throw new IllegalStateException("Unable to resolve reconciliation automation result directory: ${runArtifactLocation}")
        }

        String file1Label = normalize(reconcileResult.file1Label) ?:
                sourceLabel(ec, normalize(readField(file1Source, "systemEnumId")), "File 1")
        String file2Label = normalize(reconcileResult.file2Label) ?:
                sourceLabel(ec, normalize(readField(file2Source, "systemEnumId")), "File 2")
        Map<String, Object> output = ReconciliationServices.writeDiffDatasetOutput(
                ec,
                diffDf,
                runArtifactLocation,
                DataManagerSupport.runArtifactFileName(runToken, "result", "result.json"),
                "${runToken}-diff.json",
                [
                        timestamp              : nowTimestamp(ec)?.toString(),
                        automationId           : normalize(readField(automation, "automationId")),
                        automationName         : normalize(readField(automation, "automationName")),
                        companyUserGroupId     : normalize(readField(automation, "companyUserGroupId")),
                        savedRunId             : normalize(readField(automation, "savedRunId")),
                        savedRunType           : normalize(readField(automation, "savedRunType")) ?: "ruleset",
                        ruleSetId              : normalize(reconcileResult.ruleSetId) ?: normalize(readField(automation, "ruleSetId")),
                        compareScopeId         : normalize(reconcileResult.compareScopeId) ?: normalize(readField(automation, "compareScopeId")),
                        compareScopeDescription: normalize(reconcileResult.compareScopeDescription),
                        file1Label             : file1Label,
                        file2Label             : file2Label,
                        file1Type              : normalize(reconcileResult.file1Type),
                        file2Type              : normalize(reconcileResult.file2Type),
                        reconciliation         : "RULESET",
                        objectType             : normalize(reconcileResult.objectType),
                        childWindowStart       : toTimestamp(window?.childWindowStartDate)?.toString(),
                        childWindowEnd         : toTimestamp(window?.childWindowEndDate)?.toString(),
                ].findAll { it.value != null } as Map<String, Object>,
                [
                        totalDifferences             : reconcileResult.differenceCount,
                        onlyInFile1Count             : reconcileResult.missingInFile2Count ?: reconcileResult.onlyInFile1Count,
                        onlyInFile2Count             : reconcileResult.missingInFile1Count ?: reconcileResult.onlyInFile2Count,
                        missingObjectDifferenceCount : reconcileResult.missingObjectDifferenceCount,
                        ruleDifferenceCount          : reconcileResult.ruleDifferenceCount,
                ].findAll { it.value != null } as Map<String, Object>,
                (List) (reconcileResult.validationErrors ?: []),
                (List) (reconcileResult.processingWarnings ?: [])
        )
        reconcileResult.diffLocation = output.diffLocation
        reconcileResult.diffFileName = output.diffFileName
        return reconcileResult
    }

    protected static boolean hasData(Map<String, Object> sourceResult) {
        return sourceResult.dataAvailable == true && normalize(sourceResult.fileLocation)
    }

    protected static Map<String, Object> executionUpdateFields(Map<String, Object> file1Result, Map<String, Object> file2Result,
            Map<String, Object> reconcileResult) {
        return [
                file1Name        : normalize(file1Result.fileName),
                file1DataManagerPath: normalize(file1Result.dataManagerPath),
                file2Name        : normalize(file2Result.fileName),
                file2DataManagerPath: normalize(file2Result.dataManagerPath),
                file1RecordCount : normalizeInt(file1Result.recordCount),
                file2RecordCount : normalizeInt(file2Result.recordCount),
                differenceCount  : normalizeInt(reconcileResult.differenceCount),
                onlyInFile1Count : normalizeInt(reconcileResult.onlyInFile1Count ?: reconcileResult.missingInFile2Count),
                onlyInFile2Count : normalizeInt(reconcileResult.onlyInFile2Count ?: reconcileResult.missingInFile1Count),
        ].findAll { it.value != null } as Map<String, Object>
    }

    /**
     * Mints the run's {@code ReconciliationRunResult} row in {@code AUT_STAT_RUNNING} at the moment the
     * execution goes RUNNING, and returns its id so the caller can stamp it onto the execution row in
     * the same update (Task 2b).
     *
     * <p>Why here and not at terminal: an ACTIVE execution row that carries no run-result id is an
     * in-flight run with nothing to follow. The "Run now" UI polls for exactly that pair before it can
     * open the live progress view, and the identity fields below are all known at start — nothing has to
     * wait for the reconcile to finish.</p>
     *
     * <p>Best-effort by design, exactly like {@code RunObservability.beginRun} on the interactive path:
     * observability must never be what fails a run. A failed mint returns null,
     * {@code updateAutomationExecution} drops the null id, and the terminal paths fall back to creating
     * the row themselves — the pre-Task-2b behaviour.</p>
     */
    protected static String beginAutomationRunResult(def ec, def automation, Timestamp startedAt) {
        try {
            String assertedTenantId = assertSystemWriteTenant(ec, automation)
            return runInTransaction(ec, "Error starting reconciliation automation run result", {
                def runResultValue = ec.entity.makeValue(DarpanEntityConstants.RECONCILIATION_RUN_RESULT)
                runResultValue.set("savedRunId", normalize(readField(automation, "savedRunId")))
                runResultValue.set("savedRunType", normalize(readField(automation, "savedRunType")) ?: "ruleset")
                runResultValue.set("reconciliationRunId", normalize(readField(automation, "reconciliationRunId")))
                runResultValue.set("reconciliationMappingId", normalize(readField(automation, "reconciliationMappingId")))
                runResultValue.set("ruleSetId", normalize(readField(automation, "ruleSetId")))
                runResultValue.set("compareScopeId", normalize(readField(automation, "compareScopeId")))
                runResultValue.set("companyUserGroupId", assertedTenantId)
                runResultValue.set("createdByUserId", normalize(readField(automation, "createdByUserId")) ?: currentUserId(ec))
                // The entity default is AUT_STAT_SUCCESS, so RUNNING must be explicit here — and every
                // terminal path must be explicit too, because the default no longer covers them.
                runResultValue.set("statusEnumId", STATUS_RUNNING)
                runResultValue.set("startedDate", startedAt)
                runResultValue.set("lastHeartbeatDate", startedAt)
                runResultValue.set("createdDate", startedAt)
                runResultValue.set("lastUpdatedDate", startedAt)
                // currentStage is deliberately left unset: automations emit no per-stage steps, and a
                // stage label frozen at RESOLVE for the whole run would misreport progress.
                runResultValue.setSequencedIdPrimary()
                runResultValue.create()
                return normalize(readField(runResultValue, "reconciliationRunResultId"))
            }) as String
        } catch (Throwable mintError) {
            logger.warn("Could not mint automation run result at RUNNING (best-effort): ${mintError.message}")
            return null
        }
    }

    /**
     * Closes the pre-minted run-result row with a terminal status (Task 2b). No-op when no row was
     * minted. Best-effort: the run's own outcome has already been recorded on the execution row and must
     * never be replaced by a failure to record it here.
     */
    protected static void completeAutomationRunResult(def ec, def automation, String runResultId,
            String terminalStatusEnumId, Timestamp completedAt, Map<String, Object> extraFields) {
        if (!normalize(runResultId)) return
        try {
            runInTransaction(ec, "Error completing reconciliation automation run result", {
                def runResultValue = findAutomationRunResult(ec, runResultId,
                        normalize(readField(automation, "companyUserGroupId")))
                if (runResultValue == null) return null
                (extraFields ?: [:]).each { key, value -> if (value != null) runResultValue.set(key as String, value) }
                runResultValue.set("statusEnumId", terminalStatusEnumId)
                runResultValue.set("completedDate", completedAt)
                runResultValue.set("lastHeartbeatDate", completedAt)
                runResultValue.set("lastUpdatedDate", completedAt)
                runResultValue.update()
                return null
            })
        } catch (Throwable completeError) {
            logger.warn("Could not close automation run result ${runResultId} as ${terminalStatusEnumId} (best-effort): ${completeError.message}")
        }
    }

    /**
     * Loads a run-result row by the id this runner minted, re-pinned to the automation's own tenant so a
     * system-context read can never reach another tenant's row. A blank tenant cannot happen on the run
     * path ({@code findOrCreateExecution} already asserted it) and is left unconditioned rather than
     * silently matching nothing.
     */
    protected static def findAutomationRunResult(def ec, String runResultId, String expectedTenantId) {
        def finder = TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.RECONCILIATION_RUN_RESULT,
                        "automation run-result read keyed by the id this runner minted — companyUserGroupId re-pinned to the automation's asserted tenant")
                .condition("reconciliationRunResultId", runResultId)
        if (normalize(expectedTenantId)) finder = finder.condition("companyUserGroupId", normalize(expectedTenantId))
        return finder.useCache(false).one()
    }

    /**
     * Task 2c: best-effort heartbeat at automation phase boundaries.
     *
     * <p>Task 2b mints the {@code ReconciliationRunResult} row at RUNNING, which newly exposes it to
     * {@code StuckRunReaper} — the reaper flips any {@code ReconciliationRunResult} or
     * {@code ReconciliationAutomationExecution} row sitting in PENDING/RUNNING to FAILED (and, for the
     * run-result row, notifies) once its {@code lastUpdatedStamp} — Moqui's auto-maintained column, NOT
     * {@code lastHeartbeatDate} — goes stale (default 120 min, {@code StuckRunReaper.groovy:36}). Any
     * {@code .update()} bumps {@code lastUpdatedStamp} regardless of which fields change; this refreshes
     * {@code lastHeartbeatDate} (and, on the execution row, {@code lastUpdatedDate}) purely so that
     * {@code .update()} has something real to write.</p>
     *
     * <p>Mirrors the interactive path's beginStep/endStep heartbeats, moved to automation phase
     * boundaries since automations write no {@code ReconciliationRunStep} rows (non-goal, unchanged).
     * Called after each source extraction and after the reconcile call in
     * {@code executeAutomationForTenant} — the three points where a real, potentially slow I/O call
     * (a Shopify/OMS extract, or the Spark reconcile) has just finished.</p>
     *
     * <p><b>Residual limitation, stated plainly:</b> this is a BOUNDARY heartbeat, not a progress
     * heartbeat. It resets the clock only between phases, so a run is still falsely reaped if a SINGLE
     * phase — one very large Shopify/OMS extract, or one very large reconcile — exceeds the threshold
     * on its own. That is exactly the guarantee the interactive path's beginStep/endStep heartbeats
     * give today; this task extends the same guarantee to automations, it does not improve on it.</p>
     *
     * <p>Never touches {@code statusEnumId} or {@code notifiedDate}, and never fails the run: the
     * run-result write and the execution write are independently caught and logged at warn, exactly
     * like every other best-effort helper in this file.</p>
     */
    protected static void heartbeatAutomationRun(def ec, def automation, def execution, String runResultId) {
        Timestamp now
        try {
            now = nowTimestamp(ec)
        } catch (Throwable t) {
            logger.warn("Automation heartbeat could not resolve current time (best-effort): ${t.message}")
            return
        }
        if (normalize(runResultId)) {
            try {
                runInTransaction(ec, "Error heartbeating reconciliation automation run result", {
                    def runResultValue = findAutomationRunResult(ec, runResultId,
                            normalize(readField(automation, "companyUserGroupId")))
                    if (runResultValue != null) {
                        runResultValue.set("lastHeartbeatDate", now)
                        runResultValue.set("lastUpdatedDate", now)
                        runResultValue.update()
                    }
                    return null
                })
            } catch (Throwable t) {
                logger.warn("Automation heartbeat failed to refresh run result ${runResultId} (best-effort): ${t.message}")
            }
        }
        try {
            updateAutomationExecution(ec, execution, [lastUpdatedDate: now])
        } catch (Throwable t) {
            logger.warn("Automation heartbeat failed to refresh execution row (best-effort): ${t.message}")
        }
    }

    /**
     * Writes the run's result artifact onto its {@code ReconciliationRunResult} row and ends it terminal.
     *
     * <p>Task 2b: {@code existingRunResultId} is the row minted at RUNNING, so this UPDATES that row
     * rather than creating a second one — one execution attempt owns exactly one run-result row. It
     * still creates the row when no id was minted (degenerate best-effort path), which is the original
     * pre-2b behaviour and mirrors {@code runSavedRunDiff.groovy}'s persistRunResult closure.</p>
     *
     * <p>A blank {@code resultDataManagerPath} no longer means "no row": it means the row exists but
     * nothing was written to it, so it resolves to the pre-minted id and is still ended terminal. Only a
     * blank path with no pre-minted row returns null, exactly as before.</p>
     */
    protected static String persistAutomationRunResult(def ec, def automation, String existingRunResultId,
            Map<String, Object> file1Result, Map<String, Object> file2Result, Map<String, Object> reconcileResult,
            String resultDataManagerPath) {
        if (!normalize(resultDataManagerPath) && !normalize(existingRunResultId)) return null

        String assertedTenantId = assertSystemWriteTenant(ec, automation)
        return runInTransaction(ec, "Error saving reconciliation automation run result", {
            def runResultValue = normalize(existingRunResultId) ?
                    findAutomationRunResult(ec, existingRunResultId, assertedTenantId) : null
            boolean creating = runResultValue == null
            if (creating) runResultValue = ec.entity.makeValue(DarpanEntityConstants.RECONCILIATION_RUN_RESULT)

            Timestamp completedTimestamp = nowTimestamp(ec)
            [
                    savedRunId             : normalize(readField(automation, "savedRunId")),
                    savedRunType           : normalize(readField(automation, "savedRunType")) ?: "ruleset",
                    reconciliationRunId    : normalize(readField(automation, "reconciliationRunId")),
                    reconciliationMappingId: normalize(readField(automation, "reconciliationMappingId")),
                    ruleSetId              : normalize(readField(automation, "ruleSetId")),
                    compareScopeId         : normalize(readField(automation, "compareScopeId")),
                    companyUserGroupId     : assertedTenantId,
                    createdByUserId        : normalize(readField(automation, "createdByUserId")) ?: currentUserId(ec),
                    file1Name              : normalize(file1Result.fileName),
                    file1DataManagerPath   : normalize(file1Result.dataManagerPath),
                    file2Name              : normalize(file2Result.fileName),
                    file2DataManagerPath   : normalize(file2Result.dataManagerPath),
                    resultDataManagerPath  : normalize(resultDataManagerPath),
                    reconciliationType     : normalize(reconcileResult.reconciliationType ?: reconcileResult.objectType),
                    differenceCount        : normalizeInt(reconcileResult.differenceCount),
                    onlyInFile1Count       : normalizeInt(reconcileResult.onlyInFile1Count ?: reconcileResult.missingInFile2Count),
                    onlyInFile2Count       : normalizeInt(reconcileResult.onlyInFile2Count ?: reconcileResult.missingInFile1Count),
            ].each { key, value -> if (value != null) runResultValue.set(key as String, value) }
            // Explicit terminal status: the row was minted RUNNING, and on the create path the entity
            // default (AUT_STAT_SUCCESS) already produced this value — so this is the same end state,
            // just no longer left to a default. A ruleExecutionFailed run keeps recording SUCCESS here,
            // as it always has; only the execution row and the alert report FAILED for that case.
            runResultValue.set("statusEnumId", STATUS_SUCCEEDED)
            runResultValue.set("completedDate", completedTimestamp)
            runResultValue.set("lastHeartbeatDate", completedTimestamp)
            runResultValue.set("lastUpdatedDate", completedTimestamp)
            if (creating) {
                runResultValue.set("createdDate", completedTimestamp)
                runResultValue.setSequencedIdPrimary()
                runResultValue.create()
            } else {
                runResultValue.update()
            }
            return normalize(readField(runResultValue, "reconciliationRunResultId"))
        }) as String
    }

    /**
     * Mints a FAILED {@code ReconciliationRunResult} for a run that died before producing any output.
     *
     * <p>The notification path is anchored on that entity — {@code notifyRunCompleted} returns
     * {@code NO_RESULT_ID} without a row, and the {@code notifiedDate} claim-then-deliver CAS is the
     * only dedupe there is. Minting the row is therefore what makes a no-output failure notifiable at
     * all; it also puts the failed run in run history, where it was previously invisible.</p>
     *
     * <p>{@code AUT_STAT_FAILED} is terminal and not in {@code ACTIVE_STATUSES}, so this cannot block
     * a re-trigger. Best-effort: a failure to record must never replace the failure being recorded.</p>
     *
     * <p>Task 2b narrowed when this runs. The API-range path now mints its row at RUNNING, so the only
     * caller that reaches this is a run whose mint itself failed — it can no longer add a second row to
     * a run that already has one. The dead-letter path still calls it for an execution that never
     * carried a run-result id.</p>
     */
    protected static String persistFailureRunResult(def ec, def automation, Throwable t, Timestamp failedAt) {
        try {
            String assertedTenantId = assertSystemWriteTenant(ec, automation)
            return runInTransaction(ec, "Error saving reconciliation automation failure result", {
                def runResultValue = ec.entity.makeValue(DarpanEntityConstants.RECONCILIATION_RUN_RESULT)
                runResultValue.set("savedRunId", normalize(readField(automation, "savedRunId")))
                runResultValue.set("savedRunType", normalize(readField(automation, "savedRunType")) ?: "ruleset")
                runResultValue.set("reconciliationRunId", normalize(readField(automation, "reconciliationRunId")))
                runResultValue.set("reconciliationMappingId", normalize(readField(automation, "reconciliationMappingId")))
                runResultValue.set("ruleSetId", normalize(readField(automation, "ruleSetId")))
                runResultValue.set("compareScopeId", normalize(readField(automation, "compareScopeId")))
                runResultValue.set("companyUserGroupId", assertedTenantId)
                runResultValue.set("createdByUserId", normalize(readField(automation, "createdByUserId")) ?: currentUserId(ec))
                runResultValue.set("statusEnumId", STATUS_FAILED)
                runResultValue.set("errorMessage", truncate(sanitizeErrorMessage(t), 3900))
                runResultValue.set("errorDetail", truncate(sanitizeErrorDetail(t), 12000))
                runResultValue.set("createdDate", failedAt)
                runResultValue.set("completedDate", failedAt)
                runResultValue.setSequencedIdPrimary()
                runResultValue.create()
                return normalize(readField(runResultValue, "reconciliationRunResultId"))
            }) as String
        } catch (Throwable persistError) {
            logger.warn("Could not record automation failure run result (best-effort): ${persistError.message}")
            return null
        }
    }

    /**
     * Best-effort terminal-failure notification. Never allowed to throw — the run's own failure is the
     * thing that matters and must reach the caller unchanged.
     *
     * @param outputProduced true when the run got far enough to persist a real result; drives the
     *        payload away from reporting difference counts it never computed.
     */
    protected static void notifyAutomationFailure(def ec, def automation, String runResultId,
            boolean outputProduced, String reason) {
        if (!runResultId) return
        try {
            TenantNotificationSupport.notifyRunCompleted(ec, [
                    reconciliationRunResultId: runResultId,
                    runName                  : normalize(readField(automation, "automationName")),
                    savedRunId               : normalize(readField(automation, "savedRunId")),
                    reconciliationRunId      : normalize(readField(automation, "reconciliationRunId")),
                    companyUserGroupId       : normalize(readField(automation, "companyUserGroupId")),
                    chatSpaceId              : normalize(readField(automation, "chatSpaceId")),
                    statusEnumId             : STATUS_FAILED,
                    noOutputProduced         : !outputProduced,
                    // A run that produced output keeps the original shape (reason as a warning line).
                    // A no-output run has no warnings to report, so the reason IS the message.
                    terminationReason        : outputProduced ? null : reason,
                    processingWarnings       : outputProduced && reason ? [reason] : [],
            ])
        } catch (Throwable notifyError) {
            logger.warn("Automation failure notification failed (best-effort): ${notifyError.message}")
        }
    }

    protected static void updateAutomation(def ec, def automation, Map<String, Object> fields) {
        if (!automation) return
        runInTransaction(ec, "Error updating reconciliation automation", {
            fields.each { entry -> automation.set(entry.key as String, entry.value) }
            automation.update()
            return null
        })
    }

    protected static Timestamp resolveScheduledFireTime(def ec, Map<String, Object> input) {
        return toTimestamp(input.scheduledFireTime ?: input.scheduledDate ?: input.nowTimestamp) ?: nowTimestamp(ec)
    }

    protected static Timestamp requireTimestamp(Object rawValue, String message) {
        Timestamp timestamp = toTimestamp(rawValue)
        if (!timestamp) throw new IllegalArgumentException(message)
        return timestamp
    }

    protected static Timestamp toTimestamp(Object rawValue) {
        if (rawValue == null) return null
        if (rawValue instanceof Timestamp) return rawValue
        if (rawValue instanceof Date) return new Timestamp(rawValue.time)
        if (rawValue instanceof Instant) return Timestamp.from((Instant) rawValue)
        if (rawValue instanceof ZonedDateTime) return Timestamp.from(((ZonedDateTime) rawValue).toInstant())
        if (rawValue instanceof LocalDateTime) return Timestamp.valueOf((LocalDateTime) rawValue)

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
            return Timestamp.valueOf(LocalDateTime.parse(normalized))
        } catch (Exception ignored) {
        }
        throw new IllegalArgumentException("Invalid timestamp '${normalized}'")
    }

    protected static ZoneId resolveZoneId(Object rawZoneId) {
        String normalized = normalize(rawZoneId) ?: "UTC"
        try {
            return ZoneId.of(normalized)
        } catch (Exception ignored) {
            return ZoneId.of("UTC")
        }
    }

    protected static Duration parseScheduleDuration(String scheduleExpr) {
        try {
            return Duration.parse(scheduleExpr)
        } catch (Exception ignored) {
            return null
        }
    }

    protected static Map<String, Object> parseJsonMap(Object rawJson) {
        String text = normalize(rawJson)
        if (!text) return [:]
        try {
            Object parsed = new JsonSlurper().parseText(text)
            return parsed instanceof Map ? (Map<String, Object>) parsed : [:]
        } catch (Exception ignored) {
            return [:]
        }
    }

    protected static boolean toBoolean(Object rawValue) {
        if (rawValue == Boolean.TRUE) return true
        if (rawValue == Boolean.FALSE || rawValue == null) return false
        return ["Y", "true", "TRUE", "yes", "YES", "1"].contains(normalize(rawValue))
    }
}
