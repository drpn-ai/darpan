package darpan.reconciliation.automation

import darpan.facade.reconciliation.ReconciliationApiWindowSupport

import darpan.audit.AuditAutomationSupport
import darpan.config.DarpanSettings
import darpan.audit.AuditRecheckSupport
import darpan.audit.AuditRecordFacts
import darpan.common.DarpanEntityConstants
import darpan.common.TransactionDetachSupport
import darpan.dataset.KeptCopySupport
import darpan.email.EmailCopyReader
import darpan.ops.ErrorChatSupport
import darpan.ops.RunErrorKinds
import darpan.facade.common.DataManagerSupport
import darpan.facade.common.FacadeSupport
import darpan.facade.common.SharedConfigAccessSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.facade.reconciliation.ReconciliationSavedRunSupport
import darpan.facade.reconciliation.MissingDiffVerificationSupport
import darpan.facade.reconciliation.RunConclusionStep
import darpan.facade.reconciliation.RunObservability
import darpan.facade.reconciliation.RunVerificationSupport
import darpan.reconciliation.core.ReconciliationServices
import darpan.reconciliation.core.RuleSetCompareScopeAdapter
import darpan.reconciliation.notification.TenantNotificationSupport
import darpan.reconciliation.source.KeepPlanSupport
import darpan.reconciliation.source.KeepStepSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.apache.spark.sql.Dataset
import org.moqui.impl.service.ScheduledJobRunner

import java.sql.Timestamp
import java.util.concurrent.Future
import java.util.Locale
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
import java.time.format.DateTimeFormatter

import darpan.reconciliation.flowchart.RunFlowchartSupport
import darpan.peak.AllowanceSpec
import darpan.peak.PeakPeriodLoader
import darpan.peak.PeakPeriodSupport
import darpan.peak.PeakWindow
import static darpan.common.ValueSupport.fileNameFromPath
import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeInt
import static darpan.common.ValueSupport.readField
import static darpan.reconciliation.automation.AutomationNoDataJudge.NOTICE_QUIET_DAY
import static darpan.reconciliation.automation.AutomationNoDataJudge.NOTICE_RUN_ERROR
import static darpan.reconciliation.automation.AutomationNoDataJudge.hasData
import static darpan.reconciliation.automation.AutomationNoDataJudge.keptCountsBySide
import static darpan.reconciliation.automation.AutomationNoDataJudge.noDataNotice
import static darpan.reconciliation.automation.AutomationPeakGate.longestAllowanceMinutes
import static darpan.reconciliation.automation.AutomationPeakGate.peakAllowanceOf
import static darpan.reconciliation.automation.AutomationWindows.resolveScheduledFireTime
import static darpan.reconciliation.automation.AutomationWindows.resolveZoneId
import static darpan.reconciliation.automation.AutomationWindows.toTimestamp
import static darpan.reconciliation.automation.AutomationRetryPolicy.buildFailureFields
import static darpan.reconciliation.automation.AutomationRetryPolicy.resolveMaxRetryCount
import static darpan.reconciliation.automation.AutomationRetryPolicy.retryBackoffMillis
import static darpan.reconciliation.automation.AutomationRunRecords.TRIGGER_SCHEDULED
import static darpan.reconciliation.automation.AutomationRunRecords.executionUpdateFields
import static darpan.reconciliation.automation.AutomationRunRecords.onlyInCount
import static darpan.reconciliation.automation.AutomationRunRecords.stampExtract
import static darpan.reconciliation.automation.AutomationRunRecords.triggerOf
import static darpan.reconciliation.automation.AutomationSourceConfig.sideConfigIds
import static darpan.reconciliation.automation.AutomationSourceConfig.sourceLabel
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
    static final String AUTOMATION_INPUT_API_RANGE = "AUT_IN_API_RANGE"
    static final String AUTOMATION_INPUT_SFTP_FILES = "AUT_IN_SFTP_FILES"
    static final String AUTOMATION_SOURCE_API = "AUT_SRC_API"
    static final String AUTOMATION_SOURCE_DB = "AUT_SRC_DB"
    // Extract-service name aliases (single source of truth = ReconciliationSavedRunSupport), kept for
    // test references. Dispatch is now data-driven: the allow-list comes from the registry
    // (SourceSystemConnectorSupport.allowedServiceNames) + a naming guard, so the old
    // ALLOWED_EXTRACT_SERVICE_NAMES set, the per-system window-parameter constants, and the duplicate
    // OMS/SHOPIFY system-enum-id constants were removed here (DAR-299 constants cleanup).
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

    /**
     * Kill switch for the missing-diff verification pass on the SCHEDULED path. ON unless the
     * property is explicitly "false".
     *
     * DEFAULT ON since 2026-08-26. A scheduled run and an interactive run over the same window must
     * report the same differences; while this defaulted off they did not, and could disagree by
     * orders of magnitude (532 vs 2 on gorjana automation 100616). An unverified count is not a
     * safer count — it is a wrong one that reads as authoritative, and it is the number that reaches
     * the operator through run alerts. Verification is a correctness feature, so it is on everywhere
     * and stays on unless someone deliberately turns it off.
     *
     * The transaction ceiling that once justified defaulting off does not apply here:
     * execute#Automation is transaction="ignore" and executeAutomation suspends any caller
     * transaction, so the scheduled body runs with no ambient transaction and a ~46s stage has
     * nothing to time out against. The 60s ceiling belongs to the request path and was fixed
     * 2026-08-05.
     *
     * A setting (admin app Settings, or the system property of the same name) rather than an entity field on purpose: a kill switch must be flippable on a
     * sick instance without an entity migration, a contract regeneration and a UI change (the same
     * reason maxRetryCount and the retry backoffs are settings here). It is deliberately NOT
     * per-tenant or per-automation configuration — "which automations tell the truth" is not a
     * setting anyone should be able to hold wrong.
     *
     * FAIL DIRECTION INVERTED with the default: an unrecognised value now leaves verification ON,
     * because on is the safe state. Only an exact "false" disables it. The pass already fails closed
     * internally — a failed, capped or throwing lookup reclassifies nothing — so leaving it armed on
     * a typo cannot suppress a real difference.
     */
    static final String VERIFY_MISSING_DIFFS_PROPERTY = "darpan.reconciliation.automation.verifyMissingDiffs"

    // DAR-OPS-029 S06: window and fire-time maths moved to AutomationWindows, the peak gate to AutomationPeakGate. These
    // delegates serve callers in other slices; each owner repoints, then S99 deletes them.
    static final String WINDOW_PREVIOUS_DAY = AutomationWindows.WINDOW_PREVIOUS_DAY
    static final String WINDOW_PREVIOUS_WEEK = AutomationWindows.WINDOW_PREVIOUS_WEEK
    static final String WINDOW_PREVIOUS_MONTH = AutomationWindows.WINDOW_PREVIOUS_MONTH
    static final String WINDOW_LAST_DAYS = AutomationWindows.WINDOW_LAST_DAYS
    static final String WINDOW_LAST_WEEKS = AutomationWindows.WINDOW_LAST_WEEKS
    static final String WINDOW_LAST_MONTHS = AutomationWindows.WINDOW_LAST_MONTHS
    static final String WINDOW_CUSTOM = AutomationWindows.WINDOW_CUSTOM
    static final String WINDOW_STATE = AutomationWindows.WINDOW_STATE

    @Deprecated static boolean isStateWindowMode(def automation) { return AutomationWindows.isStateWindowMode(automation) }
    @Deprecated static List<Map<String, Object>> resolveWindows(def automation, Map params = [:]) { return AutomationWindows.resolveWindows(automation, params) }
    @Deprecated static Timestamp resolveNextScheduledFireTime(def automation, Timestamp scheduledFireTime, Timestamp now) {
        return AutomationWindows.resolveNextScheduledFireTime(automation, scheduledFireTime, now)
    }
    @Deprecated static Timestamp resolveCandidateFireTime(def automation, Timestamp now) { return AutomationWindows.resolveCandidateFireTime(automation, now) }
    @Deprecated static Map<String, Object> newPeakTick(Timestamp now) { return AutomationPeakGate.newPeakTick(now) }
    @Deprecated static Instant peakStart(def ec, Map<String, Object> tick, Instant fire, String automationId, String tenantId,
                                         boolean applyMoves, Timestamp lastFire = null, def automation = null) {
        return AutomationPeakGate.peakStart(ec, tick, fire, automationId, tenantId, applyMoves, lastFire, automation)
    }
    @Deprecated static Map<String, Object> peakNextRun(def ec, Map<String, Object> tick, def automation) { return AutomationPeakGate.peakNextRun(ec, tick, automation) }
    @Deprecated static Instant peakHoursEndNow(def ec, String tenantId, Timestamp now) { return AutomationPeakGate.peakHoursEndNow(ec, tenantId, now) }
    @Deprecated static Instant readWindowEnd(def automation, Instant fire) { return AutomationPeakGate.readWindowEnd(automation, fire) }
    @Deprecated static LocalDate lastReadDay(Instant windowEnd, ZoneId tenantZone) { return AutomationPeakGate.lastReadDay(windowEnd, tenantZone) }
    // the peak loader seams other slices' tests assign (AutomationExecutionSupport.peakWindowsLoader = { ... })
    @Deprecated static Closure<List<PeakWindow>> getPeakWindowsLoader() { return AutomationPeakGate.peakWindowsLoader }
    @Deprecated static void setPeakWindowsLoader(Closure<List<PeakWindow>> loader) { AutomationPeakGate.setPeakWindowsLoader(loader) }
    @Deprecated static Closure<ZoneId> getTenantZoneLoader() { return AutomationPeakGate.tenantZoneLoader }
    @Deprecated static void setTenantZoneLoader(Closure<ZoneId> loader) { AutomationPeakGate.setTenantZoneLoader(loader) }
    @Deprecated static Closure<Map<String, AllowanceSpec>> getPeakAllowancesLoader() { return AutomationPeakGate.peakAllowancesLoader }
    @Deprecated static void setPeakAllowancesLoader(Closure<Map<String, AllowanceSpec>> loader) { AutomationPeakGate.setPeakAllowancesLoader(loader) }

    // DAR-OPS-029 S06: run-error and quiet-day notices moved to AutomationRunNotifier. These delegates serve callers in other
    // slices (and the notify seams their tests assign); each owner repoints, then S99 deletes them.
    @Deprecated static void notifyRunError(def ec, def automation, Map execution, String runResultId, String reason) {
        AutomationRunNotifier.notifyRunError(ec, automation, execution, runResultId, reason)
    }
    @Deprecated static void notifySavedRunError(def ec, Map run, String reason) { AutomationRunNotifier.notifySavedRunError(ec, run, reason) }
    @Deprecated static void notifySavedRunCompleted(def ec, Map<String, Object> runResult, boolean rulesFailed) {
        AutomationRunNotifier.notifySavedRunCompleted(ec, runResult, rulesFailed)
    }
    @Deprecated static void notifySavedRunNoData(def ec, Map run, Map<String, String> finding, def file1Source, def file2Source,
                                                 Map file1Result, Map file2Result, boolean evaluateMode,
                                                 Timestamp windowStart, Timestamp windowEnd, String windowTimeZone) {
        AutomationRunNotifier.notifySavedRunNoData(ec, run, finding, file1Source, file2Source, file1Result, file2Result, evaluateMode,
                windowStart, windowEnd, windowTimeZone)
    }
    @Deprecated static String runErrorUrl(def ec, def automation, Map execution, String runResultId) {
        return AutomationRunNotifier.runErrorUrl(ec, automation, execution, runResultId)
    }
    @Deprecated static String ruleFailureReason(Map result) { return AutomationRunNotifier.ruleFailureReason(result) }
    @Deprecated static Closure getErrorCardPoster() { return AutomationRunNotifier.errorCardPoster }
    @Deprecated static void setErrorCardPoster(Closure poster) { AutomationRunNotifier.errorCardPoster = poster }
    @Deprecated static Closure getErrorNoticeCopy() { return AutomationRunNotifier.errorNoticeCopy }
    @Deprecated static void setErrorNoticeCopy(Closure copy) { AutomationRunNotifier.errorNoticeCopy = copy }

    // DAR-OPS-029 S06: the run's rows (execution, run result, heartbeat, tenant assertion, trigger) moved to AutomationRunRecords.
    // These delegates serve callers in other slices (SftpAutomationSupport, StuckRunReaper, TenantAccessSupport); each owner
    // repoints, then S99 deletes them.
    @Deprecated static Map<String, Object> findOrCreateExecution(def ec, def automation, Timestamp scheduledFireTime,
                                                                 Map<String, Object> window, int sequenceNum, String triggerType = null) {
        return AutomationRunRecords.findOrCreateExecution(ec, automation, scheduledFireTime, window, sequenceNum, triggerType)
    }
    @Deprecated static String assertSystemWriteTenant(def ec, def automation) { return AutomationRunRecords.assertSystemWriteTenant(ec, automation) }
    @Deprecated static String resolveSystemTenantId(def ec, def automation) { return AutomationRunRecords.resolveSystemTenantId(ec, automation) }
    @Deprecated static String beginAutomationRunResult(def ec, def automation, Timestamp startedAt) {
        return AutomationRunRecords.beginAutomationRunResult(ec, automation, startedAt)
    }
    @Deprecated static Map<String, Object> cancelAutomationExecution(def ec, def automation, def execution, String runResultId,
                                                                     Map<String, Object> metadata) {
        return AutomationRunRecords.cancelAutomationExecution(ec, automation, execution, runResultId, metadata)
    }
    @Deprecated static void completeAutomationRunResult(def ec, def automation, String runResultId, String terminalStatusEnumId,
                                                        Timestamp completedAt, Map<String, Object> extraFields) {
        AutomationRunRecords.completeAutomationRunResult(ec, automation, runResultId, terminalStatusEnumId, completedAt, extraFields)
    }
    @Deprecated static Map<String, Object> executionMap(def execution) { return AutomationRunRecords.executionMap(execution) }
    @Deprecated static def findAutomationRunResult(def ec, String runResultId, String expectedTenantId) {
        return AutomationRunRecords.findAutomationRunResult(ec, runResultId, expectedTenantId)
    }
    @Deprecated static void heartbeatAutomationRun(def ec, def automation, def execution, String runResultId) {
        AutomationRunRecords.heartbeatAutomationRun(ec, automation, execution, runResultId)
    }
    @Deprecated static String persistAutomationRunResult(def ec, def automation, String existingRunResultId, Map<String, Object> file1Result,
                                                         Map<String, Object> file2Result, Map<String, Object> reconcileResult,
                                                         String resultDataManagerPath) {
        return AutomationRunRecords.persistAutomationRunResult(ec, automation, existingRunResultId, file1Result, file2Result,
                reconcileResult, resultDataManagerPath)
    }
    @Deprecated static String persistFailureRunResult(def ec, def automation, Throwable t, Timestamp failedAt) {
        return AutomationRunRecords.persistFailureRunResult(ec, automation, t, failedAt)
    }
    @Deprecated static String runTriggerOf(Map input) { return AutomationRunRecords.runTriggerOf(input) }

    // DAR-OPS-029 S06: which connection a side reads through moved to AutomationSourceConfig. These delegates serve callers in
    // other slices (AutomationSaveSupport, DatasetSupport, RunVerificationSupport, SyncPulseReader); each owner repoints, S99 deletes.
    @Deprecated static String findSingleActiveConfigId(def ec, String companyUserGroupId, String configEntityName, String configIdField,
                                                       String systemEnumId) {
        return AutomationSourceConfig.findSingleActiveConfigId(ec, companyUserGroupId, configEntityName, configIdField, systemEnumId)
    }
    @Deprecated static String readSourceRowConfigId(def source, String configParameterName) {
        return AutomationSourceConfig.readSourceRowConfigId(source, configParameterName)
    }
    @Deprecated static Map<String, Object> resolveSourceExtractorMetadata(def ec, def source, Map<String, Object> configDefaults = [:]) {
        return AutomationSourceConfig.resolveSourceExtractorMetadata(ec, source, configDefaults)
    }
    @Deprecated static Map<String, Object> resolveSourceExtractorConfigDefaults(def ec, def automation, Collection sources) {
        return AutomationSourceConfig.resolveSourceExtractorConfigDefaults(ec, automation, sources)
    }

    // DAR-OPS-029 S06: the scan, the in-flight cap, admission, retry pickup and the operator re-drives moved to AutomationScheduler.
    // These delegates serve callers in other slices (service XML, admin, audit, pulse, RunAdmission and their tests); each owner
    // repoints, then S99 deletes them.
    @Deprecated static int maxConcurrentExecutions() { return AutomationScheduler.maxConcurrentExecutions() }
    @Deprecated static String admissionLabel(String automationId) { return AutomationScheduler.admissionLabel(automationId) }
    @Deprecated static RunAdmission getRunAdmission() { return AutomationScheduler.getRunAdmission() }
    @Deprecated static void setRunAdmission(RunAdmission admission) { AutomationScheduler.setRunAdmission(admission) }
    @Deprecated static Map<String, Object> scanDueAutomations(def ec, Map params) { return AutomationScheduler.scanDueAutomations(ec, params) }
    @Deprecated static int remainingSubmissionBudget(int inFlightCount) { return AutomationScheduler.remainingSubmissionBudget(inFlightCount) }
    @Deprecated static List<Map<String, Object>> reprocessDueRetries(def ec, Timestamp now, int limit, Map<String, Object> input,
                                                                     Integer submissionBudget = null, Map<String, Object> peakTick = null) {
        return AutomationScheduler.reprocessDueRetries(ec, now, limit, input, submissionBudget, peakTick)
    }
    @Deprecated static Map<String, Object> retryFromExtracts(def ec, Map params) { return AutomationScheduler.retryFromExtracts(ec, params) }
    @Deprecated static Map<String, Object> reprocessAutomationExecution(def ec, Map params) {
        return AutomationScheduler.reprocessAutomationExecution(ec, params)
    }
    @Deprecated static Map<String, Object> submitExecuteAutomationService(def ec, Map<String, Object> executeParams, String tenantId = null) {
        return AutomationScheduler.submitExecuteAutomationService(ec, executeParams, tenantId)
    }
    @Deprecated static List loadActiveAutomations(def ec) { return AutomationScheduler.loadActiveAutomations(ec) }

    static final String FILE_SIDE_1 = "FILE_1"
    static final String FILE_SIDE_2 = "FILE_2"

    private static final Closure DEFAULT_SOURCE_EXTRACTOR = { def ec, def automation, def source, Map<String, Object> window,
            Map<String, Object> params ->
        return callConfiguredSourceExtractor(ec, automation, source, window, params)
    }
    private static final Closure DEFAULT_RECONCILE_RUNNER = { def ec, def automation, def file1Source, def file2Source,
            Map<String, Object> file1Result, Map<String, Object> file2Result, Map<String, Object> window,
            Map<String, Object> params ->
        // DAR-BE-093: no second source means an EVALUATE scope (see executeAutomation).
        return file2Source == null ?
                callRuleSetEvaluateScope(ec, automation, file1Source, file1Result, window, params) :
                callRuleSetCompareScope(ec, automation, file1Source, file2Source, file1Result, file2Result, window, params)
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

    /** DAR-BE-015 test seam: replaces AuditAutomationSupport.recordWindow for a NO_DATA close. Null in production. */
    static Closure noDataWindowRecorder = null

    /** DAR-OPS-026: restores every mutable seam in this class (hooks, peak loaders, admission) to its default. Test-only. */
    static void resetForTests() {
        noDataWindowRecorder = null
        AutomationRunNotifier.resetForTests()
        resetExecutionHooks()
        AutomationPeakGate.resetForTests()
        AutomationScheduler.resetForTests()
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
                Map<String, Object> result = null
                try {
                    result = executeAutomationForTenant(ec, input, automation)
                    return result
                } finally {
                    // DAR-BE-207: after every ledger write of this execution, success or failure, an audit's run update
                    // is asked for; it goes out only once the whole audit is idle.
                    darpan.audit.AuditRunUpdateSupport.afterExecution(ec, automation, input, result)
                }
            }
        }
    }

    /**
     * DAR-UI-048. One flowchart walk per resolved window, with the same execution-row bookkeeping
     * (dedupe, RUNNING, terminal) as a single-rule automation. The walker owns every question's run row;
     * the execution row points at the first one.
     */
    private static Map<String, Object> runReconciliationAutomation(def ec, Map<String, Object> input, def automation) {
        String automationId = normalize(readField(automation, "automationId"))
        String reconciliationId = normalize(readField(automation, "savedRunId"))
        Timestamp scheduledFireTime = resolveScheduledFireTime(ec, input)
        List<Map<String, Object>> windows = resolveWindows(automation, input + [scheduledFireTime: scheduledFireTime])
        List<Map<String, Object>> executionResults = []
        windows.eachWithIndex { Map<String, Object> window, int index ->
            Map<String, Object> executionState = findOrCreateExecution(ec, automation, scheduledFireTime, window, index + 1, runTriggerOf(input))
            def execution = executionState.execution
            String automationExecutionId = normalize(readField(execution, "automationExecutionId"))
            if (executionState.duplicate == true) {
                executionResults << [automationExecutionId: automationExecutionId, statusEnumId: STATUS_SKIPPED_DUPLICATE]
                return
            }
            Timestamp started = nowTimestamp(ec)
            updateAutomationExecution(ec, execution, [statusEnumId: STATUS_RUNNING, startedDate: started, lastUpdatedDate: started])
            String status
            String firstRunResultId = null
            String error = null
            try {
                Map summary = RunFlowchartSupport.runReconciliation(ec, [reconciliationId: reconciliationId,
                        windowStartDate: window.childWindowStartDate, windowEndDate: window.childWindowEndDate,
                        // Final review C1: no user on the scheduled path; the tenant is already asserted.
                        systemTenantRun: true,
                        // Final review I6: StuckRunReaper fails a RUNNING execution row untouched for the
                        // reaperStaleMinutes setting (admin app Settings); a long walk touches it before every question.
                        onQuestionStart: { Map q -> updateAutomationExecution(ec, execution, [lastUpdatedDate: nowTimestamp(ec)]) }])
                status = summary.statusEnumId as String
                firstRunResultId = summary.firstRunResultId as String
            } catch (Throwable t) {
                status = STATUS_FAILED
                // DAR-OPS-029 (S34): the one redactor, as every other run error, so a token in a 401 never reaches the digest
                error = truncate(sanitizeErrorMessage(t), 3900)
                logger.error("Flowchart automation {} failed: {}", automationId, error)
            }
            Timestamp completed = nowTimestamp(ec)
            updateAutomationExecution(ec, execution, [statusEnumId: status, completedDate: completed, lastUpdatedDate: completed,
                    reconciliationRunResultId: firstRunResultId, errorMessage: error])
            executionResults << [automationExecutionId: automationExecutionId, statusEnumId: status]
        }
        return [automationId: automationId, executionResults: executionResults]
    }

    /** DAR-BE-116: off in the admin app Settings (darpan.reconciliation.automation.parallelSourceExtracts), or with the system property of that name. */
    static final String PARALLEL_SOURCE_EXTRACTS_PROPERTY = "darpan.reconciliation.automation.parallelSourceExtracts"
    private static final java.util.concurrent.ExecutorService SIDE_EXTRACT_POOL = java.util.concurrent.Executors.newFixedThreadPool(8, { Runnable r ->
        Thread t = new Thread(r, "automation-side2-extract"); t.daemon = true; t
    } as java.util.concurrent.ThreadFactory)

    /** DAR-BE-192: the run option that has a side read by the other side's keys instead of by the window. */
    static final String READ_BY_OTHER_SIDE_KEYS = "readByOtherSideKeys"

    /**
     * DAR-BE-192. A side whose run options set readByOtherSideKeys (an option a connector declares only when its extract
     * reads by key) is read by the keys the other side's records carry, never by the window: a record made long after its
     * twin still pairs (gorjana: a NetSuite credit memo made weeks after its HotWax return was entered). Either side may
     * carry it; when side 1 does, side 2 is read first.
     */
    static boolean readsByOtherSideKeys(def source) {
        Object parameters = parseJsonMap(readField(source, "safeMetadataJson"))?.parameters
        return parameters instanceof Map && ((Map) parameters).get(READ_BY_OTHER_SIDE_KEYS) == true
    }

    /**
     * DAR-BE-192. `source` read by the keys of the side already read (otherResult, otherSide): every key that side's first
     * key field holds, once, AuditRecheckSupport.MAX_KEYS a read, the later reads appended to the first one's file. No keys
     * (the other side is empty): nothing is read, and the run is judged as having no data on that side.
     */
    protected static Map<String, Object> readByOtherSideKeys(def ec, def automation, Map<String, Object> otherResult, String otherSide,
                                                            def source, Map<String, Object> window, Map<String, Object> windowParams,
                                                            Closure extract) {
        String scopeId = normalize(readField(automation, "compareScopeId"))
        String side = normalize(readField(source, "fileSide"))
        String otherKey = AuditRecheckSupport.firstKeyField(ec, scopeId, otherSide)
        String ownKey = AuditRecheckSupport.firstKeyField(ec, scopeId, side)
        if (!otherKey || !ownKey) throw new IllegalStateException("${side} is read by ${otherSide}'s keys, and a side has no plain key field")
        File otherFile = RunVerificationSupport.resolveLocationFile(ec, normalize(otherResult?.fileLocation))
        // DAR-BE-318 (plan 3b-2b, Task 14, Ruling 3b2b-11): only the other side's KEPT records give keys. Its extract now holds
        // the records its Keep sets aside too (a Spark side's read is unfiltered), and those must not widen this read.
        Map otherKeep = otherResult?.get(SIDE_KEEP) instanceof Map ? (Map) otherResult.get(SIDE_KEEP) :
                sideKeepOfResult(ec, automation, loadAutomationSources(ec, normalize(readField(automation, "automationId")))[otherSide],
                        otherResult, windowParams, windowParams?.get(KEEP_IN_SPARK) == true)
        Set<String> keys = otherFile == null ? new LinkedHashSet<String>() : KeepStepSupport.keptValues(ec, otherFile, otherKeep, otherKey)
        return readByKeys(keys, ownKey) { List<String> batch ->
            Map<String, Object> read = normalizeSourceResult(extract.call(ec, automation, source, window,
                    new LinkedHashMap<String, Object>(windowParams) + [lookupKeys: batch] as Map<String, Object>), source)
            File f = RunVerificationSupport.resolveLocationFile(ec, normalize(read.fileLocation))
            if (f == null) throw new IllegalStateException("${side}'s read by ${otherSide}'s keys wrote no file")
            return [result: read, file: f]
        }
    }

    /**
     * DAR-BE-192. Reads the keys (each once, in record order) through readBatch(keys) -> [result, file], AuditRecheckSupport.MAX_KEYS
     * at a time; every later batch's records are appended to the first batch's file (by ownKey) and counted on its result.
     * [:] when there are no keys.
     */
    static Map<String, Object> readByKeys(Collection<String> keys, String ownKey, Closure readBatch) {
        Map<String, Object> first = null
        File target = null
        (keys as List<String>).collate(AuditRecheckSupport.MAX_KEYS).each { List<String> batch ->
            Map read = (Map) readBatch.call(batch)
            if (first == null) { first = (Map<String, Object>) read.result; target = (File) read.file; return }
            first.recordCount = ((first.recordCount ?: 0) as int) + AuditRecheckSupport.append(target, (File) read.file, ownKey, [] as Set<String>)
        }
        return first ?: [:]
    }

    /**
     * Side 2's extract on its own thread and ExecutionContext: the same user, the run's system tenant, and the same
     * extractor (whose service call relaxes authz at its one audited seam). Null, so the caller reads side 2 after side 1,
     * when switched off (as the test tasks do).
     */
    static boolean parallelSourceExtractsOn() {
        return DarpanSettings.booleanValue(PARALLEL_SOURCE_EXTRACTS_PROPERTY)
    }

    protected static Future<Map<String, Object>> startParallelSourceExtract(def ec, def automation, def source,
                                                                         Map<String, Object> window, Map windowParams) {
        if (!parallelSourceExtractsOn()) return null
        def factory = ec.factory
        String username = normalize(ec.user?.username)
        // DAR-BE-133: a scheduled run has no user, only the anonymous login its anonymous-all service gave it, which is
        // what lets an authenticate="true" extract run; the worker must carry that too.
        boolean callerAnonymous = ec.user.loggedInAnonymous
        String systemTenant = TenantAccessSupport.activeSystemTenantUserGroupId()
        // DAR-BE-341: a user's run carries the tenant this request resolved (X-Darpan-Tenant / requested ThreadLocal), which
        // the worker cannot see; without it side 2 resolved the user's saved tenant, its compare scope read as none, and it
        // was read without its rules' fields. A run with no user keeps the system tenant alone.
        String callerTenant = username ? TenantAccessSupport.currentActiveTenantUserGroupId(ec) : null
        // The worker carries the run thread's authz state, as side 1 reads the connector registry with it.
        boolean callerAuthzDisabled = ec.artifactExecution.authzDisabled
        // Its own folder: two same-system sides starting together (an NS-vs-NS check) would otherwise write one file.
        Map<String, Object> sideParams = new LinkedHashMap<String, Object>(windowParams ?: [:])
        sideParams.sideOutputLocation = DataManagerSupport.resolveReconciliationRunLocation(ec, windowParams?.automationExecutionId,
                DataManagerSupport.formatRunTimestamp(ec) + "-" + normalize(readField(source, "fileSide")))
        return SIDE_EXTRACT_POOL.submit({ ->
            def sideEc = factory.getExecutionContext()
            try {
                if (username) sideEc.user.internalLoginUser(username)
                else if (callerAnonymous) sideEc.user.loginAnonymousIfNoUser()
                if (callerAuthzDisabled) sideEc.artifactExecution.disableAuthz()
                return (Map<String, Object>) TenantAccessSupport.withSystemTenant(systemTenant) { ->
                    TenantAccessSupport.withCallerTenant(sideEc, callerTenant) { ->
                        sourceExtractor.call(sideEc, automation, source, window, sideParams)
                    }
                }
            } finally {
                sideEc.destroy()
            }
        } as java.util.concurrent.Callable<Map<String, Object>>)
    }

    /** Side 2's result, or the very exception its extract threw, so the run fails and classifies exactly as before. */
    protected static Map<String, Object> joinParallelSourceExtract(Future<Map<String, Object>> side) {
        try {
            return side.get()
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.cause ?: e
        }
    }

    private static Map<String, Object> executeAutomationForTenant(def ec, Map<String, Object> input, def automation) {
        String automationId = normalize(readField(automation, "automationId"))
        String inputModeEnumId = normalize(readField(automation, "inputModeEnumId"))

        // DAR-UI-048: a "reconciliation" automation points at a run's flowchart, not a rule set. It
        // must branch BEFORE the DAR-BE-060 gate, which reads savedRunId as a RuleSet id.
        if (normalize(readField(automation, "savedRunType"))?.toLowerCase() == RunFlowchartSupport.SAVED_RUN_TYPE) {
            return runReconciliationAutomation(ec, input, automation)
        }

        // DAR-BE-060, SCHEDULED path. Gated here as well as in runSavedRunDiff because an
        // automation is a second pipeline implementation rather than a scheduler in front of the
        // first — a disable applied only to the manual path would leave this one firing nightly
        // while the UI showed the run as off. Pausing the AUTOMATION is a separate switch; this
        // one is the RUN it points at, and disabling the run must stop every way of starting it.
        String ruleSetId = normalize(readField(automation, "ruleSetId")) ?: normalize(readField(automation, "savedRunId"))
        if (ruleSetId) {
            // findGlobalUnscoped, not a bare disableAuthz: the DisableAuthzRatchetTest floor is
            // there precisely so a new unscoped read has to say why. This one is deliberate —
            // a SCHEDULED execution may carry no ambient tenant (an anonymous _NA_ context has
            // none), and a tenant-scoped read would then find nothing and let a disabled run
            // fire. The automation row already resolved its tenant; this only reads a flag off
            // the run it points at and never returns the row to a caller.
            def ruleSet = darpan.facade.common.TenantScopedFinder.findGlobalUnscoped(ec, "darpan.rule.RuleSet",
                        "DAR-BE-060 disabled-run gate on the scheduled path; a scheduled context may have no " +
                        "ambient tenant, and a scoped read that finds nothing would let a disabled run fire")
                    .condition("ruleSetId", ruleSetId).useCache(false).one()
            if (ruleSet != null && ReconciliationSavedRunSupport.isRunArchived(ruleSet)) {
                throw new IllegalStateException("Automation ${automationId} points at run ${ruleSetId}, " +
                        "which is archived. Ask a Darpan admin to restore the run.")
            }
            if (ruleSet != null && !ReconciliationSavedRunSupport.isRunEnabled(ruleSet)) {
                throw new IllegalStateException("Automation ${automationId} points at run ${ruleSetId}, " +
                        "which is disabled. Ask a Darpan admin to enable the run.")
            }
        }

        if (inputModeEnumId == AUTOMATION_INPUT_SFTP_FILES) {
            return SftpAutomationSupport.runSftpFileAutomation(ec, normalizeSftpExecutionParams(input))
        }
        if (inputModeEnumId != AUTOMATION_INPUT_API_RANGE) {
            throw new IllegalArgumentException("Automation ${automationId} must use ${AUTOMATION_INPUT_API_RANGE} or ${AUTOMATION_INPUT_SFTP_FILES}")
        }

        Timestamp scheduledFireTime = resolveScheduledFireTime(ec, input)
        // DAR-BE-092: an automation that runs an audit's checks reads the audit's windows (the period to date, plus any
        // extended period) instead of its own, and writes the audit ledger after each one.
        List<Map> auditChecks = AuditAutomationSupport.checksFor(ec, automation)
        Map audit = auditChecks ? AuditAutomationSupport.auditFor(ec, normalize(readField(automation, "companyUserGroupId")), auditChecks) : null
        List<Map<String, Object>> windows = audit ?
                AuditAutomationSupport.resolveWindows(ec, automation, audit, scheduledFireTime, input) :
                resolveWindows(automation, input + [scheduledFireTime: scheduledFireTime])
        Map<String, Object> sourcesBySide = loadAutomationSources(ec, automationId)
        // DAR-BE-093: an EVALUATE scope reads ONE source (DAR-BE-049), as runSavedRunDiff already runs it: no FILE_2
        // source, no second extract, evaluate#RuleSetCompareScope instead of the compare, and none of the three
        // verification passes (each compares two sides). save#Automation still requires two sources, so only a
        // configured automation reaches this, e.g. an audit check that reads one system (gorjana's R2).
        boolean evaluateMode = isEvaluateScope(ec, automation)
        def file1Source = requireApiSource(automation, sourcesBySide[FILE_SIDE_1], FILE_SIDE_1)
        def file2Source = evaluateMode ? requireNoSecondSource(automation, sourcesBySide[FILE_SIDE_2]) :
                requireApiSource(automation, sourcesBySide[FILE_SIDE_2], FILE_SIDE_2)
        Map<String, Object> executionParams = new LinkedHashMap<String, Object>(input)
        Map<String, Object> sourceExtractorConfigDefaults = resolveSourceExtractorConfigDefaults(ec, automation, [file1Source, file2Source])
        if (sourceExtractorConfigDefaults) executionParams.sourceExtractorConfigDefaults = sourceExtractorConfigDefaults
        // DAR-BE-284: the connection each side reads through, so each audit finding record says where it came from.
        Map<String, String> auditSideConfigIds = auditChecks ? sideConfigIds(ec, file1Source, file2Source, sourceExtractorConfigDefaults) : null
        // DAR-BE-142: a retry from the last extract reads nothing; every side is the failed attempt's file. A by-key
        // lookup (the audit's open-finding re-checks) still reads, as it reads records the window's file may not hold.
        String replayFrom = normalize(input.replayFromExecutionId)
        Closure extract = sourceExtractor
        if (replayFrom) {
            if (windows.size() != 1) {
                throw new IllegalStateException("A retry from the last extract replays one window; automation ${automationId} resolved ${windows.size()}.")
            }
            Map<String, Map<String, Object>> replay = ExtractReplaySupport.replayResults(ec,
                    normalize(readField(automation, "companyUserGroupId")), replayFrom, !evaluateMode)
            extract = { def extractEc, def extractAutomation, def source, Map<String, Object> extractWindow, Map extractParams ->
                // DAR-BE-356: so does a read of what changed since the recent read, which the replayed window file is not.
                extractParams?.lookupKeys || extractParams?.changedSince != null ? sourceExtractor.call(extractEc, extractAutomation, source, extractWindow, extractParams) :
                        new LinkedHashMap<String, Object>(replay[normalize(readField(source, "fileSide"))])
            }
        }

        List<Map<String, Object>> executionResults = []
        windows.eachWithIndex { Map<String, Object> window, int index ->
            Map<String, Object> executionState = findOrCreateExecution(ec, automation, scheduledFireTime, window, index + 1, runTriggerOf(input))
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

            // Per-window params: executionParams is built ONCE outside this loop, but the execution id
            // is minted per child window, so the extractors get their own copy carrying it. Extractors
            // name their default output folder after this id (extractOmsOrders.groovy:
            // resolveReconciliationRunLocation(ec, automationExecutionId ?: configIdValue, timestamp)),
            // and without it they fall back to the SOURCE CONFIG id — which every tenant sharing that
            // OMS connection also falls back to, for a file name that is identical across tenants for
            // the same window. Two runs then write the same path and one compares the other's extract
            // (sm-darpan 2026-08-22: a KG Canada run reconciled 98 Dolce Vita orders).
            Map<String, Object> windowParams = new LinkedHashMap<String, Object>(executionParams)
            windowParams.automationExecutionId = automationExecutionId
            // DAR-BE-318 (plan 3b-2b, Tasks 14 and 16b): the compare takes each side's Keep (keepBySide), and so does an evaluate
            // run's, so a Spark side's reads leave Keep to it.
            windowParams.put(KEEP_IN_SPARK, true)

            // Audit 2026-06-11 #16: hold the Spark Datasets persisted by reconcile#RuleSetCompareScope
            // so the finally below unpersists them on every exit path of this automation execution.
            List autoPersistedSources = []
            // Task 7 (gchat notifications): visible in the catch below so a failure AFTER the run-result
            // row is minted (e.g. the execution-row status write itself fails) still notifies FAILED.
            // Task 2b: the row is now minted at RUNNING rather than at terminal, so this is populated for
            // essentially the whole execution; it stays null only when the best-effort mint itself failed,
            // in which case the terminal paths fall back to creating the row exactly as they used to.
            String mintedRunResultId = null
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
            // DAR-BE-092: what the window read, kept for the audit ledger on every exit.
            Map<String, Object> file1Result = null
            Map<String, Object> file2Result = null
            Map<String, Object> reconcileResult = null
            if (audit) AuditAutomationSupport.attachKeyFiles(window)
            try {
                Timestamp startedTimestamp = nowTimestamp(ec)
                // Task 2b: mint the run-result row as the execution goes RUNNING and carry its id in the
                // SAME execution-row update. Two updates would leave a window where an ACTIVE execution
                // has no run-result id — precisely the window the "Run now" UI poll watches, so a row it
                // could never match is a redirect that never fires.
                mintedRunResultId = beginAutomationRunResult(ec, automation, startedTimestamp)
                // DAR-BE-129: the extracts heartbeat their record count (and see a cancel) on this row's stages.
                windowParams.reconciliationRunResultId = mintedRunResultId
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

                // DAR-BE-116: side 2 is read while side 1 is. On a check pairing OMS with another system, the other side
                // reads from the OMS read's start too, with slack; checks between other systems keep the window.
                Object recentRead = window?.auditRecentReadStartDate
                if (recentRead != null && file2Source != null) {
                    boolean oms1 = darpan.audit.AuditRecentReadSupport.sourceReadsRecentOnly(ec, file1Source)
                    boolean oms2 = darpan.audit.AuditRecentReadSupport.sourceReadsRecentOnly(ec, file2Source)
                    if (oms1 != oms2) window.auditOtherSideStartDate = darpan.audit.AuditRecentReadSupport.otherSideStart(
                            (java.sql.Timestamp) recentRead, (java.sql.Timestamp) window.childWindowStartDate)
                }
                // DAR-BE-119: the audit's re-checks read within the check's whole window (DAR-BE-156: neither the recent start nor
                // the other side's), which is what a full read would hold, restricted to the keys or to what changed.
                Map<String, Object> recheckWindow = new LinkedHashMap<String, Object>(window) +
                        [auditRecentReadStartDate: null, auditOtherSideStartDate: null] as Map<String, Object>
                // DAR-BE-356: the window's records that changed since the recent read started (an order placed Nov 3 and
                // completed Nov 8 is outside every orderDate read after Nov 5), from an extractor that can say so; null otherwise.
                Closure<Map> readChangedSince = { def source ->
                    String changedService = normalize(extractTarget(ec, source, windowParams)?.serviceName)
                    if (!declaresParameter(ec, changedService, "changedSince")) return null
                    Map<String, Object> byChange = new LinkedHashMap<String, Object>(windowParams) + [changedSince: window.auditRecentReadStartDate,
                            sideOutputLocation: DataManagerSupport.resolveReconciliationRunLocation(ec, windowParams?.automationExecutionId,
                                    DataManagerSupport.formatRunTimestamp(ec) + "-" + normalize(readField(source, "fileSide")) + "-changed")]
                    return normalizeSourceResult(extract.call(ec, automation, source, recheckWindow, byChange), source)
                }
                // DAR-BE-320: a one-sided check whose source OMS rule does not cover (NetSuite) reads by what changed: the
                // window's records that could have moved since the recent start, the new days among them, is its read; a query
                // its extractor refuses keeps the whole window. Pairs keep their reads (AuditRecentReadSupport.readsByChange).
                // Never on a retry from the last extract: a replayed file is not a read of what changed (DAR-BE-356).
                boolean side1ByChange = recentRead != null && evaluateMode && audit != null && !replayFrom &&
                        sourceReadsByChange(ec, automation, file1Source, windowParams)
                Map byChangeFirst = [changed: null]
                // DAR-BE-320 (read economy): checks of one morning that read the same source over the same window from the
                // same start share one read (gorjana R10, R11 and R12 read one NetSuite query): the first reads, the rest copy.
                Closure<Map> readChangedShared = { def source ->
                    String sideOut = DataManagerSupport.resolveReconciliationRunLocation(ec, windowParams?.automationExecutionId,
                            DataManagerSupport.formatRunTimestamp(ec) + "-" + normalize(readField(source, "fileSide")) + "-shared")
                    String sharedKey = darpan.audit.AuditSharedReadSupport.key([readField(automation, "companyUserGroupId"),
                            extractTarget(ec, source, windowParams)?.serviceName] +
                            SHARED_READ_SOURCE_FIELDS.collect { String name -> declaredField(source, name) } +
                            [window.childWindowStartDate, window.childWindowEndDate, window.auditRecentReadStartDate])
                    Map shared = darpan.audit.AuditSharedReadSupport.readOnce(ec, normalize(readField(automation, "companyUserGroupId")),
                            sharedKey, normalize(windowParams?.automationExecutionId), { -> readChangedSince(source) },
                            { String name -> DataManagerSupport.childLocation(sideOut, name) },
                            { -> RunObservability.checkpointCancel(ec, mintedRunResultId); heartbeatAutomationRun(ec, automation, execution, mintedRunResultId) })
                    return shared.shared ? normalizeSourceResult(shared.result, source) : (Map) shared.result
                }
                // DAR-BE-192: a side read by the other side's keys waits for them. Side 1 can be that side (it holds the
                // expected value of a money check), and then side 2 is read first.
                boolean side1ByKeys = !evaluateMode && file2Source != null && readsByOtherSideKeys(file1Source)
                boolean side2ByKeys = !evaluateMode && !side1ByKeys && file2Source != null && readsByOtherSideKeys(file2Source)
                // DAR-BE-173: side 2 read in parallel gets its own RUNNING step from the moment its read starts, so its
                // page heartbeats (heartbeatStageProgress finds the RUNNING step of its stage) land and both sides show a
                // live count. Begun before side 1's step, which stays the run's current stage as before. A side read by
                // the other side's keys is never read in parallel (DAR-BE-192).
                boolean side2InParallel = !evaluateMode && !replayFrom && !side2ByKeys && parallelSourceExtractsOn()
                Object parallelStep2 = side2InParallel ?
                        RunObservability.beginStep(ec, mintedRunResultId, stepCtx, RunObservability.STAGE_EXTRACT_FILE2) : null
                Future<Map<String, Object>> parallelSide2 = side2InParallel ?
                        startParallelSourceExtract(ec, automation, file2Source, window, windowParams) : null
                openStep = RunObservability.beginStep(ec, mintedRunResultId, stepCtx, RunObservability.STAGE_EXTRACT_FILE1)
                // A failure or a cancel before side 2 is joined cancels side 2's read too (DAR-BE-124).
                try {
                    if (side1ByKeys) {
                        file2Result = normalizeSourceResult(parallelSide2 != null ? joinParallelSourceExtract(parallelSide2) :
                                extract.call(ec, automation, file2Source, window, windowParams), file2Source)
                        parallelSide2 = null
                    }
                    if (side1ByChange) {
                        byChangeFirst = darpan.audit.AuditRecentReadSupport.readByChangeFirst(window, true, { -> readChangedShared(file1Source) })
                        if (byChangeFirst.changed == null) logger.info("${FILE_SIDE_1} reads the whole window, not by what changed: ${byChangeFirst.reason}")
                    }
                    file1Result = byChangeFirst.changed != null ? (Map<String, Object>) byChangeFirst.changed : normalizeSourceResult(side1ByKeys ?
                            readByOtherSideKeys(ec, automation, file2Result, FILE_SIDE_2, file1Source, window, windowParams, extract) :
                            extract.call(ec, automation, file1Source, window, windowParams), file1Source)
                    RunObservability.endStep(ec, openStep, RunObservability.STATUS_SUCCESS, [recordCount: file1Result.recordCount])
                    openStep = null
                    stampExtract(ec, execution, "file1", file1Result)
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
                } catch (Throwable beforeJoin) {
                    parallelSide2?.cancel(true)
                    RunObservability.endStep(ec, parallelStep2, RunObservability.STATUS_FAILED,
                            [errorMessage: "Stopped: the other side's read did not finish"])
                    throw beforeJoin
                }
                if (!evaluateMode) {
                    openStep = parallelStep2 ?: RunObservability.beginStep(ec, mintedRunResultId, stepCtx, RunObservability.STAGE_EXTRACT_FILE2)
                    if (!side1ByKeys) file2Result = normalizeSourceResult(side2ByKeys ?
                            readByOtherSideKeys(ec, automation, file1Result, FILE_SIDE_1, file2Source, window, windowParams, extract) :
                            parallelSide2 != null ? joinParallelSourceExtract(parallelSide2) :
                            extract.call(ec, automation, file2Source, window, windowParams), file2Source)
                    RunObservability.endStep(ec, openStep, RunObservability.STATUS_SUCCESS, [recordCount: file2Result.recordCount])
                    openStep = null
                    stampExtract(ec, execution, "file2", file2Result)
                }
                // DAR-BE-119: the audit's open findings the recent read did not reach, read again by key and appended, so they
                // close or are concluded again from today's facts, within recheckWindow.
                Map recheck = AuditRecheckSupport.appendOpenFindings(ec, automation, auditChecks, window,
                        [[source: file1Source, result: file1Result, readRecent: byChangeFirst.changed != null],
                         [source: file2Source, result: file2Result]], { def source, List<String> keys ->
                    Map<String, Object> byKey = new LinkedHashMap<String, Object>(windowParams) + [lookupKeys: keys, lookupWithinWindow: true,
                            sideOutputLocation: DataManagerSupport.resolveReconciliationRunLocation(ec, windowParams?.automationExecutionId,
                                    DataManagerSupport.formatRunTimestamp(ec) + "-" + normalize(readField(source, "fileSide")) + "-recheck")]
                    return normalizeSourceResult(extract.call(ec, automation, source, recheckWindow, byKey), source)
                }, { -> openStep = RunObservability.beginStep(ec, mintedRunResultId, stepCtx, RunObservability.STAGE_RECHECK) }, { def source ->
                    // DAR-BE-320: a side read by change holds what changed already; nothing more to ask of it
                    source.is(file1Source) && byChangeFirst.changed != null ? null : readChangedSince(source)
                })
                if (openStep != null) {
                    // DAR-BE-156: the run page's own row for the re-check. A failed re-check fails its step, not the run: the
                    // recent read is still compared, and the check's reason says the older findings were not re-checked.
                    RunObservability.endStep(ec, openStep, recheck?.failure ? RunObservability.STATUS_FAILED : RunObservability.STATUS_SUCCESS,
                            AuditRecheckSupport.stepMetrics(recheck, [FILE_1: AuditAutomationSupport.rootSystem(normalize(readField(file1Source, "systemEnumId"))),
                                                                      FILE_2: AuditAutomationSupport.rootSystem(normalize(readField(file2Source, "systemEnumId")))]))
                    openStep = null
                }
                // Task 2c: after source-2 extraction too — a NO_DATA window returns just below without
                // ever reaching the reconcile heartbeat, so this is the last one it gets before its own
                // terminal close (which also bumps lastUpdatedStamp).
                heartbeatAutomationRun(ec, automation, execution, mintedRunResultId)
                RunObservability.checkpointCancel(ec, mintedRunResultId)

                // DAR-BE-123: a verify's empty side is its answer (the record is still missing), not "no data".
                boolean verifying = window?.auditVerifyKeys
                // DAR-BE-318 (plan 3b-2b, Task 14): also how a window closes when a side's Keep, run in Spark at the compare,
                // kept nothing: today that side's read filtered every record away and reported no data.
                Closure closeAsNoData = { ->
                    // DAR-BE-393: a side that kept nothing alerts only when its last run kept nothing there too
                    Map priorKept = !verifying && AutomationNoDataJudge.keptNothingSide(file1Result, file2Result, reconcileResult) ?
                            AutomationExecutionSupport.priorKeptCounts(ec, automation, automationExecutionId) : null
                    // A verify reads one side by design: its empty side is its answer, not a failed read.
                    Map<String, String> finding = AutomationNoDataJudge.closeFinding(verifying,
                            AuditRecordFacts.systemKey(AuditAutomationSupport.rootSystem(normalize(readField(file1Source, "systemEnumId")))),
                            AuditRecordFacts.systemKey(AuditAutomationSupport.rootSystem(normalize(readField(file2Source, "systemEnumId")))),
                            file1Result, file2Result, reconcileResult, evaluateMode, priorKept)
                    // DAR-BE-393: a quiet day compared nothing, so the audit closes no finding on the keys the compare listed
                    if (finding == null && !verifying) AuditAutomationSupport.forgetExaminedKeys(window)
                    String sideReason = finding?.reason
                    Timestamp completedTimestamp = nowTimestamp(ec)
                    Map<String, Object> noDataFields = executionUpdateFields(file1Result, file2Result, [:]) + [
                            statusEnumId    : STATUS_NO_DATA,
                            noDataKind      : finding?.kind,
                            errorMessage    : sideReason,
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
                    completeAutomationRunResult(ec, automation, mintedRunResultId, STATUS_NO_DATA, completedTimestamp, [:])
                    // DAR-BE-015: a side that read nothing (or kept nothing) while the other read is a check that did
                    // not run: Darpan gets the card, a client destination the neutral line (notifyRunError purges).
                    // DAR-BE-390: a quiet day (both sides read 0) tells the run's own destinations so in one line. A verify
                    // still does not notify, and because it does not, nothing else will ever clean up a "notify me"
                    // subscription taken against this row while it was RUNNING (purgeRunSubscriptions only runs after a
                    // won notification claim). An orphan there never fires AND pins its chat space against deletion
                    // forever, so purge it here.
                    Map<String, Object> closedExecution = executionMap(execution) + noDataFields
                    String notice = noDataNotice(RunErrorKinds.of(closedExecution), verifying, file1Result, file2Result, reconcileResult, evaluateMode)
                    if (notice == NOTICE_RUN_ERROR) {
                        AutomationRunNotifier.notifyRunError(ec, automation, closedExecution, mintedRunResultId, sideReason)
                    } else if (notice == NOTICE_QUIET_DAY) {
                        AutomationRunNotifier.notifyQuietDay(ec, automation, file1Source, file2Source, window, mintedRunResultId, evaluateMode,
                                file1Result, file2Result)
                    } else {
                        TenantNotificationSupport.purgeSubscriptionsForUnnotifiedRun(ec, mintedRunResultId)
                    }
                    Closure recorder = AutomationExecutionSupport.noDataWindowRecorder ?: AuditAutomationSupport.&recordWindow
                    recorder.call(ec, automation, auditChecks, window, file1Source, file2Source,
                            file1Result, file2Result, null, null, automationExecutionId, auditSideConfigIds, sideReason)
                    executionResults << noDataFields + [
                            automationExecutionId: automationExecutionId,
                            childWindowStartDate : window.childWindowStartDate,
                            childWindowEndDate   : window.childWindowEndDate,
                    ]
                }
                if (verifying ? (!normalize(file1Result?.fileLocation) || (!evaluateMode && !normalize(file2Result?.fileLocation))) :
                        (!hasData(file1Result) || (!evaluateMode && !hasData(file2Result)))) {
                    // DAR-BE-393: the compare (and the Spark Keep in it) is skipped, so the side that read records is kept
                    // here: a store-scoped side whose store sold nothing then reads as nothing, and the day is quiet.
                    if (!verifying && !evaluateMode) {
                        AutomationNoDataJudge.keepTheReadSide(file1Result, file2Result) { String keptSide ->
                            AutomationExecutionSupport.keptCountOf(ec, automation, keptSide == FILE_SIDE_1 ? file1Source : file2Source,
                                    keptSide == FILE_SIDE_1 ? file1Result : file2Result, keptSide, windowParams)
                        }
                    }
                    closeAsNoData.call()
                    return
                }

                openStep = RunObservability.beginStep(ec, mintedRunResultId, stepCtx, RunObservability.STAGE_COMPARE)
                reconcileResult = normalizeReconcileResult(
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
                // DAR-BE-318 (plan 3b-2b, Task 14, Ruling 3b2b-11): a side whose Keep ran in Spark read every record, so its
                // recorded count is what Keep kept (what its filtering read used to count), and a side that kept nothing
                // closes the window as no data, as its filtering read did. A verify's empty side stays its answer.
                Map<String, Integer> keptBySide = keptCountsBySide(reconcileResult)
                if (keptBySide.containsKey(FILE_SIDE_1)) file1Result.recordCount = keptBySide.get(FILE_SIDE_1)
                if (keptBySide.containsKey(FILE_SIDE_2) && file2Result != null) file2Result.recordCount = keptBySide.get(FILE_SIDE_2)
                if (!verifying && keptBySide.values().contains(0)) {
                    if (keptBySide.get(FILE_SIDE_1) == 0) file1Result.dataAvailable = false
                    if (keptBySide.get(FILE_SIDE_2) == 0 && file2Result != null) file2Result.dataAvailable = false
                    closeAsNoData.call()
                    return
                }
                // The diff document has to EXIST before verification, because every pass reads it off
                // disk and rewrites it in place. This call is what sets reconcileResult.diffLocation:
                // reconcile#RuleSetCompareScope declares no diffLocation/diffFileName out-parameter and
                // hands back only Spark Datasets, so while this sat BELOW the passes, resolveDiffFile
                // returned null on every scheduled run and all three skipped -- the missing-diff pass
                // loudly (DIFF_ARTIFACT_UNREADABLE), the other two in silence. Same shape as the
                // sourceConfigId gap fixed in d40082d, one layer further out.
                //
                // Verification still runs BEFORE any count is persisted or anyone is notified, which is
                // what the previous ordering was actually protecting; the summary written here is
                // corrected in place by each pass, exactly as it is on the interactive path
                // (runSavedRunDiff.groovy writes at writeDiffDatasetOutput, then verifies).
                ensureAutomationResultArtifact(ec, automation, file1Source, file2Source, reconcileResult, window, executionParams)
                if (!evaluateMode) {
                    // DAR-BE-180: free order + line pairing of rebuilt returns, before any paid lookup.
                    // Same kill switch as the passes it feeds; the same call runSavedRunDiff makes.
                    if (isMissingDiffVerificationEnabled()) {
                        RunVerificationSupport.pairRebuiltReturns([ec: ec, serviceResult: reconcileResult,
                                diffFile: { resolveDiffFile(ec, reconcileResult) },
                                sides: [[source: file1Source, label: normalize(reconcileResult?.file1Label) ?: "file1"],
                                        [source: file2Source, label: normalize(reconcileResult?.file2Label) ?: "file2"]],
                                file1Label: normalize(reconcileResult?.file1Label) ?: "file1",
                                file2Label: normalize(reconcileResult?.file2Label) ?: "file2"])
                    }
                    // Verification passes, in the same order the interactive path runs them. The free returns
                    // grading goes first (DAR-BE-346) so the paid lookup's share gate counts only rows it
                    // could not explain. The returns and exchange passes are no-ops unless this run's
                    // connector pair is the one they apply to.
                    verifyReturnPresenceIfEnabled(ec, automation, file1Source, file2Source, file1Result, file2Result,
                            reconcileResult, mintedRunResultId, stepCtx, window, executionParams)
                    // Runs by default; a no-op only if VERIFY_MISSING_DIFFS_PROPERTY is explicitly "false".
                    verifyMissingDiffsIfEnabled(ec, automation, file1Source, file2Source, reconcileResult,
                            mintedRunResultId, stepCtx, executionParams, file1Result, file2Result, window)
                    verifyExchangePairsIfEnabled(ec, automation, file1Source, file2Source, file1Result, file2Result,
                            reconcileResult, mintedRunResultId, stepCtx, window, executionParams)
                }
                // DAR-UI-044: the same conclude seam the interactive path calls.
                concludeIfConfigured(ec, automation, file1Source, file2Source, file1Result, file2Result,
                        reconcileResult, mintedRunResultId, stepCtx)
                String resultDataManagerPath = normalizeDataManagerPath(ec,
                        reconcileResult.resultDataManagerPath ?: reconcileResult.diffLocation ?: reconcileResult.diffFileName)
                openStep = RunObservability.beginStep(ec, mintedRunResultId, stepCtx, RunObservability.STAGE_WRITE_OUTPUT)
                String reconciliationRunResultId = persistAutomationRunResult(ec, automation, mintedRunResultId,
                        file1Result, file2Result, reconcileResult, resultDataManagerPath)
                RunObservability.endStep(ec, openStep, RunObservability.STATUS_SUCCESS, [:])
                openStep = null
                mintedRunResultId = reconciliationRunResultId ?: mintedRunResultId

                Timestamp completedTimestamp = nowTimestamp(ec)
                // Audit 2026-06-11 #4: a rule build/eval failure does not throw, so an automation run
                // would otherwise record SUCCEEDED despite the ruleset not fully evaluating. Mark it
                // FAILED so the execution status and completion alert reflect the broken sync check.
                boolean ruleExecutionFailed = reconcileResult.ruleExecutionFailed == true
                // Task 6 fix round 1: the API path reaches a FAILED terminal WITHOUT throwing too — a rule
                // build/eval failure is only the flag above — so neither checkpoint nor catch can see a
                // cancel that lands in the window between the last checkpoint (after the compare) and here, which spans
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
                // DAR-BE-015: the rule's error is the row's error, so the daily error email shows why the run FAILED.
                String ruleError = ruleExecutionFailed ? (ruleFailureReason(reconcileResult) ?: "A rule failed to build or evaluate.") : null
                if (ruleError) successFields.errorMessage = truncate(ruleError, 3900)
                updateAutomationExecution(ec, execution, successFields)
                // DAR-BE-092: health, findings, closings, before anyone is told the run finished. A rule failure
                // makes the check notRun, so nothing closes on a compare that did not fully evaluate.
                AuditAutomationSupport.recordWindow(ec, automation, auditChecks, window, file1Source, file2Source,
                        file1Result, file2Result, reconcileResult,
                        ruleExecutionFailed ? new IllegalStateException("A rule failed to build or evaluate.") : null, automationExecutionId,
                        auditSideConfigIds)
                // DAR-BE-015: a rule that failed to build or evaluate is a run error: the rule's error goes to Darpan's card,
                // a client destination gets only the neutral line, never the run's payload (it carries the raw rule error).
                if (ruleExecutionFailed) {
                    notifyRunError(ec, automation, executionMap(execution) + successFields, mintedRunResultId, ruleError)
                // DAR-BE-123: a verify answers the person who asked, on the finding; the run's space is not told.
                } else if (!window?.auditVerifyKeys) notifyRunCompletedWithStage(ec, mintedRunResultId, stepCtx, [
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
                        ruleDifferenceCount      : reconcileResult.ruleDifferenceCount,
                        ruleDifferenceFields     : reconcileResult.ruleDifferenceFields,
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
                // DAR-BE-092: the failed read (or compare) is recorded, so the check is partial or notRun and closes nothing.
                AuditAutomationSupport.recordWindow(ec, automation, auditChecks, window, file1Source, file2Source,
                        file1Result, file2Result, reconcileResult, t, automationExecutionId, auditSideConfigIds)
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
                    // DAR-BE-015: the error goes to Darpan's error space; a client destination gets only the neutral
                    // line (notifyRunError). It purges this run's subscriptions itself, so nothing may delete them
                    // ahead of this call — that would drop the subscriber destinations it is about to resolve.
                    notifyRunError(ec, automation, executionMap(execution) + failureFields,
                            mintedRunResultId ?: persistFailureRunResult(ec, automation, t, completedTimestamp),
                            sanitizeErrorMessage(t))
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
                AuditAutomationSupport.discardKeyFiles(window)
            }
        }

        Map statusCounts = executionResults.countBy { it.statusEnumId }
        // DAR-BE-207: the morning and period the audit's run update reports (a play's last window).
        Map<String, Object> auditRun = audit && windows ?
                [auditId: audit.auditId, auditReportDate: windows.last().auditReportDate, auditPeriod: windows.last().auditPeriod] : [:]
        return auditRun + [
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
                // DAR-OPS-029 F-E1 (conservation): a window queued for retry (PENDING) is a bucket too, and the buckets add up to
                // windowCount, so a requeued window never reads as a run that did not happen.
                retryQueuedCount      : statusCounts[STATUS_PENDING] ?: 0,
                windowCount           : windows.size(),
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
     * Read live, not cached in a static, so the kill switch can be flipped without a restart.
     *
     * <p>NAMED for the missing-diff pass, but since 2026-08-27 it gates ALL THREE verification
     * passes on this path (missing-diff, exchange-pair, return-presence). One switch, because the
     * question an operator actually asks is "is this instance verifying its scheduled runs?" — a
     * per-pass switch would recreate, as configuration, the very split this work removed. Renaming
     * it would silently disarm the kill switch on any deployment already setting the old name.</p>
     *
     * <p>No per-automation allow-list. The canary one existed only to roll this out while the
     * default was off, and it deletes with the flip by design. Keeping it would have left a
     * supported way to run most automations unverified while the switch still read "on" — which is
     * exactly the silent disagreement between the two run paths that flipping the default ends.</p>
     */
    static boolean isMissingDiffVerificationEnabled() {
        return DarpanSettings.booleanValue(VERIFY_MISSING_DIFFS_PROPERTY)
    }

    /**
     * Recheck rows the compare reported missing against each side's primary datastore, the same pass
     * {@code runSavedRunDiff.groovy} has always run and this path never has.
     *
     * <p>Shares {@link RunVerificationSupport} with the interactive path rather than copying its
     * closures, so the two entry points cannot drift again — this file and runSavedRunDiff are
     * already kept in sync by hand-written "mirrors" comments, and a fourth such point would
     * guarantee a repeat of the 100616 divergence.</p>
     *
     * <p>Returns whether the pass actually ran. Best-effort throughout: a lookup failure degrades to
     * a processing warning and never fails a run that has already produced a complete compare.</p>
     */
    protected static boolean verifyMissingDiffsIfEnabled(def ec, def automation, def file1Source, def file2Source,
                                                         Map<String, Object> reconcileResult,
                                                         String runResultId, Map stepCtx,
                                                         Map<String, Object> executionParams = null,
                                                         Map file1Result = null, Map file2Result = null,
                                                         Map window = null) {
        // ONE implementation, called identically by runSavedRunDiff.groovy. What is left here is the
        // scheduled path's own vocabulary -- its kill switch, its tenant anchor, its dispatch seam,
        // and the config defaults its extractor resolved -- and nothing about how the pass decides,
        // runs, folds counts, or reports. Copying that block was what let a triggered run and a
        // manual run of the same window publish different numbers for a whole release.
        return RunVerificationSupport.runMissingDiffPass([
                ec                 : ec,
                runResultId        : runResultId,
                stepCtx            : stepCtx,
                enabled            : isMissingDiffVerificationEnabled(),
                serviceResult      : reconcileResult,
                diffFile           : { resolveDiffFile(ec, reconcileResult) },
                file1Source        : file1Source,
                file2Source        : file2Source,
                file1Label         : normalize(reconcileResult?.file1Label),
                file2Label         : normalize(reconcileResult?.file2Label),
                // DAR-BE-351: the share gate is an audit window's alone; every other run looks up every missing row.
                auditRun           : window?.auditReportDate != null,
                // DAR-BE-320: each side's extract count (the share gate) and the scope (the lookup's id kind).
                file1RecordCount   : file1Result?.recordCount instanceof Number ? file1Result.recordCount : null,
                file2RecordCount   : file2Result?.recordCount instanceof Number ? file2Result.recordCount : null,
                compareScopeId     : normalize(readField(automation, "compareScopeId")),
                // The RUN OWNER's tenant, never the session's: the scheduler authenticates as
                // anonymous _NA_, which belongs to no tenant, so a session-derived value would
                // resolve the wrong source config (the 2026-07-31 scheduled-automation failure).
                runOwnerUserGroupId: normalize(readField(automation, "companyUserGroupId")),
                // Routed through the single audited dispatch seam rather than opening its own
                // disableAuthz site (DisableAuthzRatchetTest pins the count exactly).
                dispatcher         : internalDispatcher(ec),
                // The SAME defaults the extractor resolved for this execution. An API source keeps no
                // config id of its own (see RunVerificationSupport.resolveSourceConfigId), so without
                // these the lookup resolves nothing on exactly the OMS/Shopify runs it exists for.
                runConfigDefaults  : runConfigDefaultsOf(executionParams),
        ])
    }

    /**
     * The return-presence pass on the scheduled path — the grace/window-edge gate, cancellation-refund
     * suppression and superseded-sibling suppression that an OMS_RETURNS/SHOPIFY_RETURN_REFS run needs.
     *
     * <p>Interactive-only until 2026-08-27. A scheduled returns run therefore published raw compare
     * counts where an interactive rerun of the same window published corrected ones — the same class
     * of split as the missing-diff pass, on the runs where it matters most.</p>
     *
     * <p>Every resolution decision lives in RunVerificationSupport's prepareReturnPresencePass,
     * shared with {@code runSavedRunDiff.groovy}, so the two entry points cannot drift apart again;
     * since DAR-OPS-029 S99-044 its step, settle and count fold are there too (runReturnPresencePass).
     * What stays here is the kill switch and this path's handles. Returns whether the pass actually ran. Best-effort throughout: nothing here may
     * fail a run that has already produced a complete compare.</p>
     */
    protected static boolean verifyReturnPresenceIfEnabled(def ec, def automation, def file1Source, def file2Source,
                                                           Map file1Result, Map file2Result,
                                                           Map<String, Object> reconcileResult,
                                                           String runResultId, Map stepCtx,
                                                           Map<String, Object> window = null,
                                                           Map<String, Object> executionParams = null) {
        if (!isMissingDiffVerificationEnabled()) return false
        String file1Label = normalize(reconcileResult?.file1Label) ?: "file1"
        String file2Label = normalize(reconcileResult?.file2Label) ?: "file2"
        // DAR-OPS-029 S99-044: the pass, its STAGE_VERIFY_RETURNS step, the settle and the count fold are RunVerificationSupport's,
        // called identically by runSavedRunDiff. The RUN OWNER's tenant, never the session's (the scheduler authenticates as _NA_).
        return RunVerificationSupport.runReturnPresencePass([
                ec                 : ec,
                runResultId        : runResultId,
                stepCtx            : stepCtx,
                serviceResult      : reconcileResult,
                alertRun           : checkAlertRun(automation, runResultId),
                diffFile           : { resolveDiffFile(ec, reconcileResult) },
                sides              : [[source: file1Source, extractResult: file1Result, label: file1Label],
                                      [source: file2Source, extractResult: file2Result, label: file2Label]],
                file1Label         : file1Label,
                file2Label         : file2Label,
                windowStartMillis  : windowEdgeMillis(window, "Start"),
                runOwnerUserGroupId: normalize(readField(automation, "companyUserGroupId")),
                dispatcher         : internalDispatcher(ec),
                runConfigDefaults  : runConfigDefaultsOf(executionParams),
        ])
    }

    /**
     * The exchange-pair pass on the scheduled path: Shopify exchange orders that never reached OMS.
     *
     * <p>Same shape as {@link #verifyReturnPresenceIfEnabled}, over
     * {@code RunVerificationSupport.runExchangePairPass}. This pass APPENDS rows the compare never
     * found, so its fold moves counts up rather than down.</p>
     */
    protected static boolean verifyExchangePairsIfEnabled(def ec, def automation, def file1Source, def file2Source,
                                                          Map file1Result, Map file2Result,
                                                          Map<String, Object> reconcileResult,
                                                          String runResultId, Map stepCtx,
                                                          Map<String, Object> window = null,
                                                          Map<String, Object> executionParams = null) {
        if (!isMissingDiffVerificationEnabled()) return false
        String file1Label = normalize(reconcileResult?.file1Label) ?: "file1"
        String file2Label = normalize(reconcileResult?.file2Label) ?: "file2"
        // DAR-OPS-029 S99-044: the pass, its STAGE_VERIFY_EXCHANGE step, the settle and the fold are RunVerificationSupport's.
        return RunVerificationSupport.runExchangePairPass([
                ec                 : ec,
                runResultId        : runResultId,
                stepCtx            : stepCtx,
                serviceResult      : reconcileResult,
                alertRun           : checkAlertRun(automation, runResultId),
                diffFile           : { resolveDiffFile(ec, reconcileResult) },
                sides              : [[source: file1Source, extractResult: file1Result, label: file1Label, fileSide: FILE_SIDE_1],
                                      [source: file2Source, extractResult: file2Result, label: file2Label, fileSide: FILE_SIDE_2]],
                windowStartMillis  : windowEdgeMillis(window, "Start"),
                windowEndMillis    : windowEdgeMillis(window, "End"),
                runOwnerUserGroupId: normalize(readField(automation, "companyUserGroupId")),
                dispatcher         : internalDispatcher(ec),
                runConfigDefaults  : runConfigDefaultsOf(executionParams),
        ])
    }

    /** OC18: who a verify check's alerts-room text names on the scheduled path (RunVerificationSupport.settleCheckErrors). */
    static Map checkAlertRun(def automation, String runResultId) {
        return [companyUserGroupId       : normalize(readField(automation, "companyUserGroupId")),
                runName                  : normalize(readField(automation, "automationName")) ?: normalize(readField(automation, "automationId")),
                reconciliationRunResultId: runResultId]
    }

    /**
     * Notify a completed scheduled run, inside its own STAGE_NOTIFY step.
     *
     * <p>Both run paths notify, but only the interactive one opened a NOTIFY step, so a scheduled
     * run's alert was invisible on the timeline and "did the alert fire?" was unanswerable from the
     * UI. Mirrors {@code runSavedRunDiff.groovy}'s notify stage, best-effort included: a notification
     * failure is recorded and logged, never allowed to change the outcome of a run whose results are
     * already written.</p>
     */
    protected static void notifyRunCompletedWithStage(def ec, String runResultId, Map stepCtx,
                                                      Map<String, Object> payload) {
        def notifyStep = runResultId ?
                RunObservability.beginStep(ec, runResultId, stepCtx, RunObservability.STAGE_NOTIFY) : null
        try {
            TenantNotificationSupport.notifyRunCompleted(ec, payload)
            RunObservability.endStep(ec, notifyStep, RunObservability.STATUS_SUCCESS, [:])
        } catch (Throwable notifyError) {
            RunObservability.endStep(ec, notifyStep, RunObservability.STATUS_FAILED,
                    [errorMessage: notifyError.message ?: notifyError.toString()])
            logger.warn("execute#Automation notify stage failed (best-effort, run outcome preserved): " +
                    "${notifyError.message ?: notifyError}", notifyError)
        }
    }

    /**
     * The run's reporting-window start or end ("Start"/"End") as epoch millis: the child window's, else the whole window's. Null
     * means this run has no window, which degrades the passes to grace-only behaviour, as for a saved run with no window.
     */
    protected static Long windowEdgeMillis(Map<String, Object> window, String edge) {
        Object at = window?.get("childWindow${edge}Date".toString()) ?: window?.get("window${edge}Date".toString())
        if (at instanceof java.util.Date) return ((java.util.Date) at).time
        if (at instanceof Number) return ((Number) at).longValue()
        return null
    }

    /** The single audited dispatch seam as the verification passes call it: null parameters dropped. */
    private static Closure internalDispatcher(def ec) {
        return { String serviceName, Map serviceParams ->
            return dispatchInternalService(ec, serviceName,
                    (serviceParams ?: [:]).findAll { entry -> entry.value != null } as Map<String, Object>)
        }
    }

    /** The config defaults the extractor resolved for this execution, which an API source's lookups need (no id of its own). */
    private static Map<String, Object> runConfigDefaultsOf(Map executionParams) {
        return executionParams?.get("sourceExtractorConfigDefaults") instanceof Map ?
                (Map<String, Object>) executionParams.get("sourceExtractorConfigDefaults") : null
    }

    /**
     * DAR-UI-044: names each verified finding via the shared RunConclusionStep seam. Guarded end to end,
     * including resolving the diff file: a conclusion is never allowed to fail a run whose compare
     * succeeded, so anything that goes wrong here is a skipped stage, not a failed run.
     */
    protected static void concludeIfConfigured(def ec, def automation, def file1Source, def file2Source,
                                               Map file1Result, Map file2Result, Map<String, Object> reconcileResult,
                                               String runResultId, Map stepCtx) {
        try {
            Map concluded = RunConclusionStep.runIfConfigured([ec: ec,
                    compareScopeId: normalize(reconcileResult?.compareScopeId) ?: normalize(readField(automation, "compareScopeId")),
                    diffFile: resolveDiffFile(ec, reconcileResult), file1Source: file1Source, file2Source: file2Source,
                    file1Result: file1Result, file2Result: file2Result, runResultId: runResultId, stepCtx: stepCtx])
            // DAR-BE-157: rows a HIDE rule dropped are not differences this run reports.
            darpan.reconciliation.conclusion.RunConclusionSupport.applyHidden(reconcileResult, (Map) concluded?.hiddenByBucket, "differenceCount", (Collection) concluded?.repeatSidesCleared)
        } catch (Throwable concludeError) {
            // DAR-OPS-029 F-C3: a conclusion never fails a run whose compare succeeded, but its failure is logged, not swallowed
            logger.warn("Conclusions skipped for automation {} run {}: {}", readField(automation, "automationId"), runResultId,
                    sanitizeErrorMessage(concludeError))
            if (ec?.message?.hasError()) ec.message.clearErrors()
        }
    }

    /** The diff document the compare just wrote, which the verification pass rewrites in place. */
    protected static File resolveDiffFile(def ec, Map<String, Object> reconcileResult) {
        String location = normalize(reconcileResult?.diffLocation)
        if (location) {
            File located = location.startsWith("/") ? new File(location)
                    : ec.resource.getLocationReference(location)?.getFile()
            if (located != null && located.isFile()) return located
        }
        String diffFileName = normalize(reconcileResult?.diffFileName)
        if (diffFileName?.contains("/")) return DataManagerSupport.resolveDataManagerFile(ec, diffFileName, false)
        return null
    }

    protected static Map<String, Object> normalizeSftpExecutionParams(Map<String, Object> input) {
        Map<String, Object> sftpParams = [:]
        sftpParams.putAll(input)
        if (sftpParams.scheduledFireTime && !sftpParams.scheduledDate) sftpParams.scheduledDate = sftpParams.scheduledFireTime
        return sftpParams
    }

    /**
     * A connector row can name a service from a component that is not in this image - database-darpan
     * is absent from every production Dockerfile today. Moqui's own failure for that case is
     * "Unknown service", which reads as a bug rather than a missing install. Say what is actually
     * wrong so an operator knows the fix is a deployment change, not a code change.
     */
    static void requireExtractorServiceInstalled(def ec, String serviceName) {
        if (ec.service.isServiceDefined(serviceName)) return
        if (serviceName?.startsWith("reconciliation.DatabaseExtractionServices.")) {
            throw new IllegalStateException("The database source component is not installed on this " +
                    "deployment, so database sources cannot run here.")
        }
        throw new IllegalStateException("Extractor service '${serviceName}' is registered but not " +
                "installed on this deployment.")
    }

    static Map<String, Object> callConfiguredSourceExtractor(def ec, def automation, def source,
            Map<String, Object> window, Map<String, Object> params) {
        Map<String, Object> target = extractTarget(ec, source, params)
        Map<String, Object> metadata = (Map<String, Object>) target.metadata
        String serviceName = (String) target.serviceName
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
        requireExtractorServiceInstalled(ec, serviceName)

        Map<String, Object> serviceParams = [:]
        if (metadata.parameters instanceof Map) serviceParams.putAll((Map<String, Object>) metadata.parameters)
        serviceParams.remove(READ_BY_OTHER_SIDE_KEYS)   // DAR-BE-192: the run's own choice, not the extract's
        serviceParams.automationId = normalize(readField(automation, "automationId"))
        // Every extract service declares this ("Automation execution id used to choose the default
        // data-manager output folder"); it is what keeps one run's extract file off another run's path.
        serviceParams.automationExecutionId = normalize(params?.automationExecutionId)
        if (params?.sideOutputLocation) serviceParams.outputLocation = params.sideOutputLocation
        serviceParams.companyUserGroupId = normalize(readField(automation, "companyUserGroupId"))
        serviceParams.fileSide = normalize(readField(source, "fileSide"))
        serviceParams.systemEnumId = normalize(readField(source, "systemEnumId"))
        serviceParams.sourceTypeEnumId = normalize(readField(source, "sourceTypeEnumId"))
        // DAR-BE-116 / DAR-BE-122: an audit reads HotWax OMS (rate-limited) from its checks' last complete read
        // (AuditRecentReadSupport).
        Object recentStart = window?.auditRecentReadStartDate
        if (recentStart != null && darpan.audit.AuditRecentReadSupport.sourceReadsRecentOnly(ec, source)) {
            window = new LinkedHashMap<String, Object>(window) + [childWindowStartDate: recentStart] as Map<String, Object>
        } else if (window?.auditOtherSideStartDate != null) {
            // The other side of an OMS-paired check: a little earlier than OMS, so a twin dated just before is read.
            window = new LinkedHashMap<String, Object>(window) + [childWindowStartDate: window.auditOtherSideStartDate] as Map<String, Object>
        }
        serviceParams.windowStartDate = window.childWindowStartDate
        serviceParams.windowEndDate = window.childWindowEndDate
        // DAR-BE-129: live progress, as the interactive path has always sent it (RunExtractSupport): the run's row and
        // this side's extract stage, to an extractor that reports them. Before, an automation's extract showed no
        // count until it finished (an audit month's 20-40 minute OMS reads read as stuck).
        if (params?.reconciliationRunResultId &&
                ec.service.getServiceDefinition(serviceName)?.inParameterNameList?.contains("reconciliationRunResultId")) {
            serviceParams.reconciliationRunResultId = params.reconciliationRunResultId
            serviceParams.progressStageCode = serviceParams.fileSide == FILE_SIDE_1 ?
                    RunObservability.STAGE_EXTRACT_FILE1 : RunObservability.STAGE_EXTRACT_FILE2
        }

        // Date-parameter names + the Shopify preserve-window flag are now data-driven: look the connector
        // up by the service being invoked (what the pre-registry switch keyed on). A source-level override
        // still wins; fall back to fromDate/toDate when no connector matches.
        // DAR-BE-176: data-defined endpoints share one extract service, so the source's own endpoint decides first.
        Map<String, Object> connectorForService = (Map<String, Object>) target.connector
        applyWindowParameters(serviceParams, automation, source, connectorForService, window)
        if (connectorForService?.preserveWindowInstants) serviceParams.preserveWindowInstants = true

        // DAR-BE-318 (plan 3b-2b, Task 14), DAR-BE-338: the side's Keep plan, from the automation's own filter rows.
        // The read gets the rows only when Keep runs there (sideKeepOf); a Spark side's Keep runs at the compare's ingest.
        Map sideKeep = sideKeepOf(ec, automation, source, connectorForService, serviceName, new LinkedHashMap<String, Object>(serviceParams),
                params?.get(KEEP_IN_SPARK) == true)
        applyExcludeFilterParameter(serviceParams, connectorForService, (List<Map<String, Object>>) sideKeep.readFilters)
        applyStatusParameter(serviceParams, connectorForService, source)
        applyWindowFieldParameter(serviceParams, connectorForService)
        // DAR-BE-239: the fields this side's rules read. Before, a scheduled run sent none, its HotWax read never asked
        // for paymentRefNums, and the transaction rule passed every order.
        List<String> readFields = ReconciliationSavedRunSupport.extractReadFields(ec, serviceName,
                readField(automation, "compareScopeId"), serviceParams.fileSide)
        // DAR-BE-318 (Ruling 3b2b-4): a Spark side's read keeps the fields its Keep reads, which the read no longer filters on.
        if (KeepStepSupport.applies(sideKeep)) {
            List<String> keepFields = ((Collection<String>) sideKeep.fields ?: []) as List<String>
            if (keepFields && declaresParameter(ec, serviceName, ReconciliationSavedRunSupport.READ_FIELDS_PARAMETER)) {
                readFields = ((readFields ?: []) + keepFields).unique()
            }
            if (keepFields && declaresParameter(ec, serviceName, KeepPlanSupport.KEEP_FIELDS_PARAMETER)) {
                serviceParams.put(KeepPlanSupport.KEEP_FIELDS_PARAMETER, new ArrayList<String>(keepFields))
            }
        }
        // DAR-BE-368: plus the fields the source's own parameters ask for (HotWax orderItems, so an audit finding shows the
        // order's lines), kept beside the rules' fields, not overwritten by them. Only a service that declares readFields
        // reads them (extractReadFields): Moqui drops the parameter for any other.
        readFields = withAskedReadFields(serviceParams.get(ReconciliationSavedRunSupport.READ_FIELDS_PARAMETER), readFields)
        if (readFields) serviceParams.put(ReconciliationSavedRunSupport.READ_FIELDS_PARAMETER, readFields)
        // DAR-BE-119: a read by key (an audit re-checking its older open findings), sent only to an extractor that
        // declares it: any other would ignore the keys and read the whole window.
        // DAR-BE-123: verifying findings reads only their records, by this side's own first key field.
        List verifyKeys = window?.auditVerifyKeys instanceof List ? AuditRecheckSupport.firstKeys((List) window.auditVerifyKeys).toList() : null
        if (params?.lookupKeys || verifyKeys) {
            if (!ec.service.getServiceDefinition(serviceName)?.inParameterNameList?.contains("lookupKeys")) {
                throw new IllegalStateException("${serviceName} cannot read by key")
            }
            serviceParams.lookupKeys = params?.lookupKeys ?: verifyKeys
            serviceParams.lookupKeyField = AuditRecheckSupport.firstKeyField(ec,
                    normalize(readField(automation, "compareScopeId")), normalize(readField(source, "fileSide")))
            if (!serviceParams.lookupKeyField) throw new IllegalStateException("${readField(source, 'fileSide')} has no key to read by")
            // DAR-BE-156: a re-check keeps the window too, where the extractor can (NetSuite); one reading by primary key
            // (the OMS orderId) needs none.
            if (params?.lookupWithinWindow && ec.service.getServiceDefinition(serviceName)?.inParameterNameList?.contains("lookupWithinWindow")) {
                serviceParams.lookupWithinWindow = true
            }
        }
        // DAR-BE-356: an audit's read of the window's records that changed since its recent read started, sent only to an
        // extractor that declares it: any other would read the whole window instead.
        if (params?.changedSince != null) {
            if (!declaresParameter(ec, serviceName, "changedSince")) throw new IllegalStateException("${serviceName} cannot read changed records")
            serviceParams.changedSince = params.changedSince
        }
        // DAR-BE-318 plan 3a: the window from the tenant's kept copy when the copy covers it; otherwise the live read below.
        Map served = KeptCopySupport.sliceIfServed(ec, KeptCopySupport.side(connectorForService, serviceParams, [
                tenantId   : serviceParams.companyUserGroupId, windowStart: serviceParams.windowStartDate,
                windowEnd  : serviceParams.windowEndDate,
                outputRunId: serviceParams.automationExecutionId ?: serviceParams.automationId,
                // DAR-BE-318 (plan 3b-2b): an effective-SPARK side's plan, so the slice leaves Keep to Spark and a projecting
                // source keeps the fields Spark's Keep reads (KeptCopySupport.callProjection); any other side slices as today.
                sideKeep   : KeepPlanSupport.SPARK == sideKeep.placement ? sideKeep : null]))
        if (served.served) {
            return KeptCopySupport.extractResult(served) + [readWindowFrom: serviceParams.windowStartDate,
                                                            readWindowThru: serviceParams.windowEndDate]
        }
        logger.info("${serviceParams.fileSide} reads live, not from a kept copy: ${served.reason}")

        def call = ec.service.sync().name(serviceName).parameters(serviceParams)
        if (call?.metaClass?.respondsTo(call, "disableAuthz")) call = call.disableAuthz()
        // Counted first: only an error this call adds is its failure (a side's login can leave one of its own).
        int errorsBefore = ((ec.message.getErrors() ?: []) as List).size()
        // A caller's own live extractor (params.liveExtractor, a Closure(serviceName, serviceParams)), for tests, as
        // RunExtractSupport.extractApiSource takes its serviceCaller; absent in production, which calls the service.
        Closure liveExtractor = params?.liveExtractor instanceof Closure ? (Closure) params.liveExtractor : null
        // OC158 (S14): when this side BEGAN reading, before the fetch. The ID index decides by it whether a connection drop came
        // after the read; the extract file's modified time is when the write ended, so a fetch straddling a drop read as later.
        Timestamp readStartedAt = nowTimestamp(ec)
        Map<String, Object> result = ((liveExtractor != null ? liveExtractor.call(serviceName, serviceParams) : call.call()) ?: [:]) as Map<String, Object>
        List<String> errors = (result.errors instanceof Collection ? (Collection) result.errors : [])
                .collect { Object error -> normalize(error) }
                .findAll { String error -> error } as List<String>
        // DAR-BE-130: a service that throws is caught by Moqui, which leaves the error on the message facade and
        // answers an empty map; taken as it came, that empty map closed the run as "no data".
        List added = ((ec.message.getErrors() ?: []) as List).drop(errorsBefore)
        if (added) {
            errors += added.collect { normalize(it) }.findAll { it } as List<String>
            ec.message.clearErrors()
        }
        if (errors) throw new IllegalStateException(errors.join("; "))
        // DAR-BE-374: what this run read feeds the ID index, in the background
        darpan.idindex.IdIndexRunHook.afterLiveRead(ec, normalize(serviceParams.companyUserGroupId), normalize(connectorForService?.systemEnumId),
                normalize(serviceParams[normalize(connectorForService?.configParameterName) ?: "sourceConfigId"]),
                normalize(result.fileLocation ?: result.dataManagerPath), readStartedAt)
        // DAR-BE-320 phase 1b: the window THIS side read (HotWax reads from its last covered day, the other side a
        // little earlier), recorded on the audit's source read.
        result = new LinkedHashMap<String, Object>(result)
        if (!result.containsKey("readWindowFrom")) result.readWindowFrom = serviceParams.windowStartDate
        if (!result.containsKey("readWindowThru")) result.readWindowThru = serviceParams.windowEndDate
        result.put(SIDE_KEEP, sideKeep)
        return result
    }

    /**
     * DAR-BE-318 (plan 3b-2b, Task 14). The key a side's extract result carries its Keep plan under, so the compare
     * (keepBySide), the read by the other side's keys (keptValues) and the side's recorded count take the same plan the
     * read was sent.
     */
    static final String SIDE_KEEP = "sideKeep"

    /**
     * DAR-BE-318 (plan 3b-2b, Task 14). The dispatch parameter by which a caller says its compare takes keepBySide, so a
     * Spark side's read may leave Keep to Spark. The run loop sets it on every run, an evaluate run's included (Task 16b: both
     * compares take keepBySide); the sync pulse sets it as SyncPulseReader decides, then runs the side's Keep over its own
     * extract; any other caller keeps every side's filters at the read.
     */
    static final String KEEP_IN_SPARK = "keepInSpark"

    /**
     * The extract a source dispatches to, resolved as callConfiguredSourceExtractor dispatches it (without its checks):
     * [metadata, serviceName, connector]. DAR-BE-176: a data-defined endpoint's connector names the extract to run, whatever
     * an older snapshot says, so cutting an endpoint over to its data definition (and back) is the connector row alone: no
     * stored automation is rewritten, so no migration. The connector is the source endpoint's own row when it names that
     * extract, else the row registered for the extract.
     */
    protected static Map<String, Object> extractTarget(def ec, def source, Map params) {
        Map<String, Object> metadata = resolveSourceExtractorMetadata(ec, source,
                params?.sourceExtractorConfigDefaults instanceof Map ? (Map<String, Object>) params.sourceExtractorConfigDefaults : [:])
        String serviceName = normalize(metadata.extractServiceName ?: metadata.serviceName)
        String endpoint = normalize(readField(source, "systemEnumId"))
        Map<String, Object> bySystem = endpoint ? SourceSystemConnectorSupport.resolve(ec, endpoint) : null
        if (normalize(bySystem?.dataSourceDefinitionId) && normalize(bySystem?.extractServiceName)) {
            serviceName = normalize(bySystem.extractServiceName)
        }
        Map<String, Object> connector = !serviceName ? null : bySystem?.extractServiceName == serviceName ? bySystem :
                SourceSystemConnectorSupport.resolveByExtractServiceName(ec, serviceName)
        return [metadata: metadata, serviceName: serviceName, connector: connector]
    }

    /**
     * DAR-BE-318 (plan 3b-2b, Task 14), DAR-BE-338. One automation side's Keep plan (KeepPlanSupport's shape), from the
     * automation's OWN filter rows (ReconciliationAutomationSourceFilter), read for the automation's tenant only: an automation
     * runs on its own filter copy, so a filter edit on its run reaches it only through sync#Automation. Its keys and record root
     * are the compare scope's, as the compare ingests them.
     * <ul>
     * <li>NONE (no filter parameter: uploads, databases): no rows are read, nothing is filtered, as today.</li>
     * <li>READ (a data-defined endpoint, Ruling 3b2b-16): the read gets the rows and filters, as today.</li>
     * <li>SPARK: the read gets no rows; Spark runs the connector's built-ins and the rows at the compare's ingest.</li>
     * </ul>
     * Where a SPARK side's Keep runs is KeepPlanSupport.effectiveSideKeep's one decision, the same as the interactive run's: it
     * keeps today's read (KeepPlanSupport.todaysRead) until its extract stops filtering (Ruling 3b2b-23), when the caller's compare
     * takes no keepBySide (keepInSpark false, KEEP_IN_SPARK), and when it is not keyed on top-level records.
     * A side whose data is a replayed file (landed, sideKeepOfResult) its read already filtered keeps today's read
     * (KeepPlanSupport.landedFilteredAtRead, asked by effectiveSideKeep).
     */
    static Map sideKeepOf(def ec, def automation, def source, Map connector, String serviceName, Map extractOptions,
                          boolean keepInSpark, File landed = null) {
        String placement = KeepPlanSupport.placement(connector)
        // NONE reads no filter rows: the gate hands back its empty plan as for any NONE side
        if (placement == KeepPlanSupport.NONE) return KeepPlanSupport.effectiveSideKeep(ec, [], connector, extractOptions, null, null, [], keepInSpark)
        String fileSide = normalize(readField(source, "fileSide"))
        List<Map<String, Object>> rows = loadAutomationSourceFilters(ec, normalize(readField(automation, "automationId")),
                fileSide, normalize(readField(automation, "companyUserGroupId")))
        // the side's keys and record root are the compare scope's, as the compare ingests the side (RuleSetCompareScopeAdapter)
        // and as the audit re-check and the sync pulse read them: Keep must decide on what is ingested (DAR-BE-338 review I1)
        Map shape = placement == KeepPlanSupport.SPARK ? sideKeyShape(ec, automation, fileSide) : [recordRoot: null, keyFields: []]
        return KeepPlanSupport.effectiveSideKeep(ec, rows, connector, extractOptions,
                serviceName ? ec.service.getServiceDefinition(serviceName) : null,
                (String) shape.recordRoot, (List<String>) shape.keyFields, keepInSpark, landed, fileSide)
    }

    /**
     * A side's record root and key field expressions: the compare scope's (RunConclusionStep.keySpec, as RunConclusionStep.keySpecFor
     * reads it), with its rows read for the AUTOMATION's tenant by an explicit condition, since a scheduled run has no signed-in
     * user to pin the tenant.
     */
    private static Map sideKeyShape(def ec, def automation, String fileSide) {
        String compareScopeId = normalize(readField(automation, "compareScopeId"))
        String tenantId = normalize(readField(automation, "companyUserGroupId"))
        if (!compareScopeId || !tenantId || !fileSide) return [recordRoot: null, keyFields: []]
        String why = "automation path reads its compare scope's key spec; explicit companyUserGroupId condition applied"
        List keyRows = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.rule.RuleSetCompareSourceKeyField", why)
                .condition("compareScopeId", compareScopeId).condition("fileSide", fileSide)
                .condition("companyUserGroupId", tenantId).orderBy("sequenceNum").useCache(false).list() ?: []
        // list(), not one(): one() on the full PK would drop the tenant pin.
        def sourceRow = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.rule.RuleSetCompareSource", why)
                .condition("compareScopeId", compareScopeId).condition("fileSide", fileSide)
                .condition("companyUserGroupId", tenantId).useCache(false).list()?.find()
        return [recordRoot: normalize(readField(sourceRow, "recordRootExpression")),
                keyFields : (List<String>) RunConclusionStep.keySpec(keyRows.collect { normalize(readField(it, "fieldExpression")) },
                        normalize(readField(sourceRow, "primaryIdExpression")), null, null).keyFields]
    }

    /**
     * The Keep plan a side's extract result carries (SIDE_KEEP), or, for a result that carries none (a retry replaying an
     * earlier attempt's file), the plan the side's extract would be sent now. DAR-BE-318 (Task 16e, review 16b-I1): a replayed
     * file its read already filtered (KeepPlanSupport.landedFilteredAtRead) keeps today's read, as the interactive retry's landed side
     * does (runSavedRunDiff): Spark Keep over it would write a zero-count .keep.json that the conclude pass reads as "not
     * found", and a side Spark cannot keep would fail although its data is filtered.
     */
    static Map sideKeepOfResult(def ec, def automation, def source, Map result, Map params, boolean keepInSpark = true) {
        if (result?.get(SIDE_KEEP) instanceof Map) return (Map) result.get(SIDE_KEEP)
        if (source == null) return null
        Map<String, Object> target = extractTarget(ec, source, params)
        Map<String, Object> options = ((Map) target.metadata)?.parameters instanceof Map ?
                new LinkedHashMap<String, Object>((Map) ((Map) target.metadata).parameters) : [:]
        // a replay's landed file, resolved as ExtractReplaySupport.replayResults resolved it when it checked the file is on disk
        // (review 16e M2: resolveLocationFile lacks its runtime:// fallback); null for any other result
        String landedAt = normalize(result?.get("replayedFromExecutionId")) == null ? null :
                normalize(result.get("fileLocation") ?: result.get("dataManagerPath"))
        File landed = landedAt ? DataManagerSupport.resolveDirectoryFile(ec, landedAt, false) : null
        return sideKeepOf(ec, automation, source, (Map) target.connector, (String) target.serviceName, options, keepInSpark, landed)
    }

    /**
     * DAR-BE-320. AuditRecentReadSupport.readsByChange for the one side of an audit's one-sided check: its endpoint's connector
     * row, whether the extract it dispatches to declares changedSince, and its first compare key as a record field (what the
     * re-check reads open findings by). A key that cannot be resolved is no candidate: the side reads the whole window.
     */
    static boolean sourceReadsByChange(def ec, def automation, def source, Map params) {
        String systemEnumId = normalize(readField(source, "systemEnumId"))
        try {
            boolean declares = declaresParameter(ec, normalize(extractTarget(ec, source, params)?.serviceName), "changedSince")
            if (!declares) return false
            Map connector = systemEnumId ? SourceSystemConnectorSupport.resolveBySystemEnumId(ec, systemEnumId) : null
            String keyField = AuditRecheckSupport.firstKeyField(ec, normalize(readField(automation, "compareScopeId")),
                    normalize(readField(source, "fileSide")))
            return darpan.audit.AuditRecentReadSupport.readsByChange(true, systemEnumId, connector, declares, keyField)
        } catch (Exception e) {
            logger.warn("${readField(source, 'fileSide')} (${systemEnumId}) reads the whole window: ${e.message}")
            return false
        }
    }

    /**
     * DAR-BE-320. A source's fields that steer what its extract reads (an automation source names its query in
     * safeMetadataJson, a run's compare source in sourceConfigId): two checks share a read only when all of them agree.
     */
    static final List<String> SHARED_READ_SOURCE_FIELDS = ["systemEnumId", "sourceConfigId", "safeMetadataJson",
            "extractParametersJson", "systemMessageRemoteId", "databaseSourceQueryId",
            "apiRequestTemplateJson", "extractStatusIds"].asImmutable()

    /** A field of a source row or map, null when its entity does not declare it (EntityValue.get throws then). */
    static Object declaredField(def source, String name) {
        if (source == null) return null
        // an EntityValue is a Map too: ask it first
        if (source instanceof org.moqui.entity.EntityValue) return ((org.moqui.entity.EntityValue) source).isField(name) ? source.get(name) : null
        return readField(source, name)
    }

    /** Whether serviceName's definition declares the in-parameter (ServiceDefinition.inParameterNameList). */
    protected static boolean declaresParameter(def ec, String serviceName, String parameterName) {
        if (!serviceName || !parameterName) return false
        return ec.service.getServiceDefinition(serviceName)?.inParameterNameList?.contains(parameterName) ? true : false
    }

    /**
     * Date-parameter names are data-driven: a source-level override wins, then the connector, then
     * fromDate/toDate. Every extract is sent its window: the one connector that could read by status with
     * no window (REST OMS_TRANSFER_ORDERS) was retired with HotWax REST (DAR-BE-052).
     */
    protected static Map<String, Object> applyWindowParameters(Map<String, Object> serviceParams,
                                                               def automation, def source,
                                                               Map<String, Object> connector,
                                                               Map<String, Object> window) {
        String dateFromParameterName = normalize(readField(source, "dateFromParameterName")) ?:
                (normalize(connector?.dateFromParameterName) ?: "fromDate")
        String dateToParameterName = normalize(readField(source, "dateToParameterName")) ?:
                (normalize(connector?.dateToParameterName) ?: "toDate")
        // DAR-BE-057, the scheduled half. A DATE-granular source is sent the calendar day rather than
        // the instant, read in the automation's own windowTimeZone — the zone its window was built in.
        // The interactive path does the same thing in RunExtractSupport.buildExtractParams; both are
        // one branch because the divergence between these two dispatchers is already documented debt
        // and this is not the place to widen it.
        if (connector?.windowDateOnly == true) {
            Object zone = AutomationWindows.automationZone(null, automation).id   // S99-020: no zone = the tenant's
            serviceParams[dateFromParameterName] = ReconciliationApiWindowSupport
                    .calendarDateIn((java.sql.Timestamp) window.childWindowStartDate, zone)
            serviceParams[dateToParameterName] = ReconciliationApiWindowSupport
                    .calendarDateIn((java.sql.Timestamp) window.childWindowEndDate, zone)
            return serviceParams
        }
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

    /**
     * DAR-BE-368. The read fields a source's own parameters ask for (asked) first, then the rules' (readFields), each once.
     * No rules' fields (null: the service does not declare readFields, or the side has no scope) leaves asked as it is.
     */
    static List<String> withAskedReadFields(Object asked, List<String> readFields) {
        if (!(asked instanceof Collection) || readFields == null) return readFields
        return (((Collection) asked).collect { normalize(it) }.findAll { it } + readFields).unique() as List<String>
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
                // DAR-BE-092 / DAR-BE-091: an audit window's examined records, both sides.
                file1KeysOutLocation: window?.auditFile1KeysOut,
                file2KeysOutLocation: window?.auditFile2KeysOut,
        ].findAll { it.value != null } as Map<String, Object>
        // DAR-BE-318 (plan 3b-2b, Task 14): each side whose Keep runs in Spark, keyed FILE_1/FILE_2; sent only when one does,
        // so a compare with no Spark Keep is called exactly as before.
        Map<String, Map> keepBySide = [:]
        Map sideKeep1 = sideKeepOfResult(ec, automation, file1Source, file1Result, params)
        Map sideKeep2 = sideKeepOfResult(ec, automation, file2Source, file2Result, params)
        if (KeepStepSupport.applies(sideKeep1)) keepBySide.put(FILE_SIDE_1, sideKeep1)
        if (KeepStepSupport.applies(sideKeep2)) keepBySide.put(FILE_SIDE_2, sideKeep2)
        if (keepBySide) serviceParams.keepBySide = keepBySide

        return dispatchInternalService(ec,
                "reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope", serviceParams)
    }

    /**
     * DAR-BE-093: an EVALUATE scope's stage, with runSavedRunDiff's parameters. Every FILE_1 row is a finding row, so
     * the examined keys are FILE_1's alone.
     */
    protected static Map<String, Object> callRuleSetEvaluateScope(def ec, def automation, def file1Source,
            Map<String, Object> file1Result, Map<String, Object> window, Map<String, Object> params) {
        Map<String, Object> serviceParams = [
                ruleSetId           : normalize(readField(automation, "ruleSetId")) ?: normalize(readField(automation, "savedRunId")),
                compareScopeId      : normalize(readField(automation, "compareScopeId")),
                file1Location       : file1Result.fileLocation ?: file1Result.dataManagerPath,
                file1Name           : file1Result.fileName,
                file1FileTypeEnumId : file1Result.fileTypeEnumId ?: readField(file1Source, "fileTypeEnumId"),
                file1SchemaFileName : file1Result.schemaFileName ?: readField(file1Source, "schemaFileName"),
                hasHeader           : params.containsKey("hasHeader") ? params.hasHeader : Boolean.TRUE,
                // Server-side only, as callRuleSetCompareScope.
                sparkMaster         : (ec.resource.properties["spark.master"] ?: "local[*]"),
                sparkAppName        : normalize(params.sparkAppName) ?: "AutomationRuleSetEvaluateScope",
                file1KeysOutLocation: window?.auditFile1KeysOut,
        ].findAll { it.value != null } as Map<String, Object>
        // DAR-BE-318 (Task 16b): the side's Keep when it runs in Spark, as callRuleSetCompareScope sends it; sent only then, so an
        // evaluate run with no Spark Keep is called exactly as before.
        Map sideKeep1 = sideKeepOfResult(ec, automation, file1Source, file1Result, params)
        if (KeepStepSupport.applies(sideKeep1)) serviceParams.keepBySide = [(FILE_SIDE_1): sideKeep1]

        return dispatchInternalService(ec,
                "reconciliation.ReconciliationCoreServices.evaluate#RuleSetCompareScope", serviceParams)
    }

    /**
     * The single authz-relaxed service-dispatch seam on the automation path.
     *
     * <p>Callers reach internal reconciliation services under the automation's own tenant anchor,
     * not a session's — the scheduler authenticates as anonymous {@code _NA_}, which belongs to no
     * tenant. Consolidated so there is ONE audited site rather than a copy per call site; every
     * caller is responsible for having established the tenant anchor before dispatching.</p>
     *
     * <p>Deliberately does not filter null parameters: two of its callers pass maps that have
     * already been filtered, and adding a second filter here would change what the third sends.</p>
     */
    protected static Map<String, Object> dispatchInternalService(def ec, String serviceName,
                                                                 Map<String, Object> serviceParams) {
        def call = ec.service.sync().name(serviceName).parameters(serviceParams)
        if (call?.metaClass?.respondsTo(call, "disableAuthz")) call = call.disableAuthz()
        return (call.call() ?: [:]) as Map<String, Object>
    }

    /** DAR-BE-093: whether the automation's compare scope is EVALUATE (one source). */
    protected static boolean isEvaluateScope(def ec, def automation) {
        String compareScopeId = normalize(readField(automation, "compareScopeId"))
        String tenantId = normalize(readField(automation, "companyUserGroupId"))
        if (!compareScopeId || !tenantId) return false
        def scope = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.rule.RuleSetCompareScope",
                        "scheduled path reads its compare scope's mode; explicit companyUserGroupId condition applied")
                // list(), not one(): one() drops a non-PK condition beside the full PK, and with it the tenant pin.
                .condition("compareScopeId", compareScopeId).condition("companyUserGroupId", tenantId).useCache(false).list().find()
        return normalize(readField(scope, "scopeMode"))?.toUpperCase() == RuleSetCompareScopeAdapter.SCOPE_MODE_EVALUATE
    }

    /** An EVALUATE scope takes one source; a FILE_2 source beside it is a configuration error, as the engine says. */
    protected static def requireNoSecondSource(def automation, def source) {
        if (source) {
            throw new IllegalArgumentException("Automation ${normalize(readField(automation, 'automationId'))} runs an " +
                    "${RuleSetCompareScopeAdapter.SCOPE_MODE_EVALUATE} scope, which takes one source, but has a ${FILE_SIDE_2} source.")
        }
        return null
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

    static Map<String, Object> normalizeSourceResult(Object rawResult, def source) {
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
        // DAR-BE-015: the records the recent read returned, kept apart because Keep later overwrites recordCount with the
        // kept count. 0 (never null) for a side with no data or no reported count, so "read nothing" is distinct from
        // "unknown". An audit re-check (AuditRecheckSupport) may later add appended open-finding records to recordCount,
        // so recordCount can exceed readCount.
        result.readCount = (result.dataAvailable == true && result.recordCount != null) ? result.recordCount : 0
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

    /**
     * The stored diff document's summary counts. DAR-OPS-029 (S01a/S03a/S02 conservation gap; runSavedRunDiff's F23):
     * duplicateDifferenceCount is passed through whenever the compare reports it, so a stored document shows
     * totalDifferences = onlyInFile1Count + onlyInFile2Count + ruleDifferenceCount + duplicateDifferenceCount.
     */
    static Map<String, Object> automationDiffSummary(Map<String, Object> reconcileResult) {
        return [
                totalDifferences             : reconcileResult.differenceCount,
                onlyInFile1Count             : onlyInCount(reconcileResult, FILE_SIDE_1),
                onlyInFile2Count             : onlyInCount(reconcileResult, FILE_SIDE_2),
                missingObjectDifferenceCount : reconcileResult.missingObjectDifferenceCount,
                ruleDifferenceCount          : reconcileResult.ruleDifferenceCount,
                duplicateDifferenceCount     : reconcileResult.duplicateDifferenceCount,
        ].findAll { it.value != null } as Map<String, Object>
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
        // No second source (EVALUATE): no second system to name, as runSavedRunDiff writes it.
        String file2Label = file2Source == null ? null : (normalize(reconcileResult.file2Label) ?:
                sourceLabel(ec, normalize(readField(file2Source, "systemEnumId")), "File 2"))
        Map<String, Object> output = ReconciliationServices.writeDiffDatasetOutput(
                ec,
                diffDf,
                runArtifactLocation,
                DataManagerSupport.runArtifactFileName(runToken, "result", "result.json"),
                "${runToken}-diff.json",
                [
                        timestamp              : ReconciliationServices.formatMetadataTimestamp(nowTimestamp(ec)),
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
                automationDiffSummary(reconcileResult),
                (List) (reconcileResult.validationErrors ?: []),
                (List) (reconcileResult.processingWarnings ?: [])
        )
        reconcileResult.diffLocation = output.diffLocation
        reconcileResult.diffFileName = output.diffFileName
        return reconcileResult
    }

    /** @deprecated DAR-OPS-029 S06: moved to AutomationNoDataJudge; S99 deletes this once AuditAutomationSmokeTests (S11) repoints. */
    @Deprecated
    static Map<String, String> noDataFinding(String side1Label, Integer read1, Integer kept1, String side2Label, Integer read2, Integer kept2,
                                             Map priorKept = null) {
        return AutomationNoDataJudge.noDataFinding(side1Label, read1, kept1, side2Label, read2, kept2, priorKept)
    }

    /** @deprecated DAR-OPS-029 S06: moved to AutomationNoDataJudge; the committed AuditAutomationSmokeTests (S11) still calls it. S99 deletes. */
    @Deprecated
    static String noDataReason(String side1Label, Integer read1, Integer kept1, String side2Label, Integer read2, Integer kept2) {
        return AutomationNoDataJudge.noDataReason(side1Label, read1, kept1, side2Label, read2, kept2)
    }

    /**
     * DAR-BE-393. What a side's Spark Keep keeps of its extract, run as the read by the other side's keys runs it
     * (KeepStepSupport.keptValues over the side's first key field, writing the Keep artifact beside the extract). Null when the
     * side has no Spark Keep (a read that filtered already counted what it kept), no file or no plain key field, or Keep fails.
     */
    static Integer keptCountOf(def ec, def automation, def source, Map result, String side, Map windowParams) {
        try {
            Map sideKeep = result?.get(SIDE_KEEP) instanceof Map ? (Map) result.get(SIDE_KEEP) :
                    sideKeepOfResult(ec, automation, source, result, windowParams, windowParams?.get(KEEP_IN_SPARK) == true)
            if (!KeepStepSupport.applies(sideKeep)) return null
            File file = RunVerificationSupport.resolveLocationFile(ec, normalize(result?.fileLocation))
            String key = AuditRecheckSupport.firstKeyField(ec, normalize(readField(automation, "compareScopeId")), side)
            if (file == null || !key) return null
            return KeepStepSupport.keptValues(ec, file, sideKeep, key).size()
        } catch (Throwable keepError) {
            logger.warn("Keep of {} before an empty day is judged failed for automation {}: {}", side,
                    readField(automation, "automationId"), keepError.getClass().name)
            return null
        }
    }

    /**
     * DAR-BE-393. The kept counts of this automation's last finished run before this execution ([file1, file2]), or null when
     * there is none: the first kept-nothing day after a run that kept records on that side is a quiet day.
     */
    static Map<String, Integer> priorKeptCounts(def ec, def automation, Object automationExecutionId) {
        try {
            // the run's own automation, its tenant named explicitly (a scheduled run has no active tenant to scope by)
            def prior = TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION,
                            "DAR-BE-393: an empty day reads its automation's last run's kept counts")
                    .condition("companyUserGroupId", normalize(readField(automation, "companyUserGroupId")))
                    .condition("automationId", normalize(readField(automation, "automationId")))
                    .condition("automationExecutionId", org.moqui.entity.EntityCondition.ComparisonOperator.NOT_EQUAL, normalize(automationExecutionId))
                    .condition("statusEnumId", org.moqui.entity.EntityCondition.ComparisonOperator.IN, [STATUS_SUCCEEDED, STATUS_NO_DATA])
                    .orderBy(["-createdDate", "-automationExecutionId"]).limit(1).useCache(false).list()?.find { true }
            return prior == null ? null : [file1: normalizeInt(prior.file1RecordCount), file2: normalizeInt(prior.file2RecordCount)]
        } catch (Throwable lookupError) {
            logger.warn("Last run's kept counts unreadable for automation {}: {}", readField(automation, "automationId"), lookupError.getClass().name)
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
