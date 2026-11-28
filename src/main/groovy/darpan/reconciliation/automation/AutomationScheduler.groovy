package darpan.reconciliation.automation

import darpan.audit.AuditAutomationSupport
import darpan.common.DarpanEntityConstants
import darpan.config.DarpanSettings
import darpan.facade.common.TenantScopedFinder
import darpan.peak.AllowanceSpec

import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneId

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeInt
import static darpan.common.ValueSupport.readField
import static darpan.reconciliation.automation.AutomationPeakGate.longestAllowanceMinutes
import static darpan.reconciliation.automation.AutomationPeakGate.newPeakTick
import static darpan.reconciliation.automation.AutomationPeakGate.peakAllowanceOf
import static darpan.reconciliation.automation.AutomationPeakGate.peakStart
import static darpan.reconciliation.automation.AutomationRetryPolicy.resolveMaxRetryCount
import static darpan.reconciliation.automation.AutomationRetryPolicy.retryBackoffMillis
import static darpan.reconciliation.automation.AutomationRunNotifier.notifyRunError
import static darpan.reconciliation.automation.AutomationRunRecords.TRIGGER_SCHEDULED
import static darpan.reconciliation.automation.AutomationRunRecords.executionMap
import static darpan.reconciliation.automation.AutomationRunRecords.persistFailureRunResult
import static darpan.reconciliation.automation.AutomationRunRecords.triggerOf
import static darpan.reconciliation.automation.AutomationRuntimeSupport.loadAutomation
import static darpan.reconciliation.automation.AutomationRuntimeSupport.nowTimestamp
import static darpan.reconciliation.automation.AutomationRuntimeSupport.runInTransaction
import static darpan.reconciliation.automation.AutomationRuntimeSupport.sanitizeErrorMessage
import static darpan.reconciliation.automation.AutomationRuntimeSupport.updateAutomationExecution
import static darpan.reconciliation.automation.AutomationWindows.resolveCandidateFireTime
import static darpan.reconciliation.automation.AutomationWindows.resolveNextScheduledFireTime
import static darpan.reconciliation.automation.AutomationWindows.toTimestamp

/**
 * DAR-OPS-029 S06 (split out of AutomationExecutionSupport). The 5-minute scan (scan#DueAutomations): which automations are due
 * (peak periods and sync lag allowances applied), how many may start (the in-flight cap and each tenant's RunAdmission lane),
 * submitting each fire-and-forget and advancing its schedule, re-driving due retries and dead-lettering the exhausted; and the
 * operator re-drives (reprocess an execution, retry from its last extract).
 */
class AutomationScheduler {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(AutomationScheduler)

    // Audit 2026-06-11 #8, reworked by DAR-BE-002: the ceiling on how many automation executions may be
    // in flight at once, bounding shared service-pool and Spark-driver load. It used to be a per-scan
    // dispatch width because the scan JOINED every execution it dispatched, so "concurrent within one
    // scan" and "concurrent at all" were the same number. The scan is fire-and-forget now, so the bound
    // is measured against executions already RUNNING; a tick that would exceed it defers the remaining
    // due automations to the next tick rather than stampeding Moqui's worker pool (bounded at 32+
    // threads, far above the Spark concurrency this cap exists to allow). A super admin edits it in the admin
    // app Settings (darpan.reconciliation.automation.maxConcurrentExecutions) for tenants with heavier capacity.
    // DAR-BE-143: 4 -> 8, with each tenant held to 5 of them (RunAdmission), so a second tenant's audit runs beside the
    // first (gorjana's play already ran its 5 automations at once, unbounded); play now counts against it too.
    static int maxConcurrentExecutions() {
        return DarpanSettings.intValue("darpan.reconciliation.automation.maxConcurrentExecutions")
    }

    /** DAR-BE-207: the RunAdmission label of an automation's execution, so an audit can ask whether one is still queued. */
    static String admissionLabel(String automationId) {
        return "automation ${automationId}".toString()
    }

    /** DAR-BE-143: which execution starts when (RunAdmission.SHARED); a test may set its own. */
    private static RunAdmission runAdmission = RunAdmission.SHARED

    static void setRunAdmission(RunAdmission admission) { runAdmission = admission ?: RunAdmission.SHARED }

    static RunAdmission getRunAdmission() { return runAdmission }

    static Map<String, Object> scanDueAutomations(def ec, Map params) {
        Map<String, Object> input = params ?: [:]
        Timestamp now = toTimestamp(input.nowTimestamp) ?: nowTimestamp(ec)
        int limit = normalizeInt(input.limit) ?: 100

        // DAR-BE-328: an automation is due when its peak-period start (a run-time move, then peak hours) has come, which
        // can be before its fire (moved earlier) or after it (moved later, or held out of peak hours). It always runs
        // with the fire it was waiting on as scheduledFireTime, so its read window and execution row never change; one
        // that is not due yet keeps its schedule, as a deferral does.
        Map<String, Object> peakTick = newPeakTick(now)
        List<Map<String, Object>> peakHeldResults = []
        List<Map<String, Object>> dueAutomationEntries = loadActiveAutomations(ec)
                .collect { automation ->
                    [
                            automation       : automation,
                            scheduledFireTime: resolveCandidateFireTime(automation, now),
                    ]
                }
                .findAll { entry ->
                    Timestamp fire = (Timestamp) entry.scheduledFireTime
                    if (fire == null) return false
                    def automation = entry.automation
                    Instant start = peakStart(ec, peakTick, fire.toInstant(), normalize(readField(automation, "automationId")),
                            normalize(readField(automation, "companyUserGroupId")), true,
                            toTimestamp(readField(automation, "lastScheduledFireTime")), automation)
                    if (!start.isAfter(now.toInstant())) return true
                    if (!fire.after(now)) {
                        peakHeldResults << [automationId: normalize(readField(automation, "automationId")),
                                            scheduledFireTime: fire, peakHeldUntil: Timestamp.from(start)]
                    }
                    return false
                }
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
        // DAR-BE-143: first start what waited here for memory, then submit only what starts at once in its tenant's
        // lane; the rest stays due, so the next tick re-drives it and a restart loses nothing.
        runAdmission.drain()
        List<Map<String, Object>> scanResults = []
        List<Map<String, Object>> deferredResults = []
        dueAutomationEntries.each { Map<String, Object> dueEntry ->
            def automation = dueEntry.automation
            String automationId = normalize(readField(automation, "automationId"))
            Timestamp scheduledFireTime = toTimestamp(dueEntry.scheduledFireTime)

            String tenantId = normalize(readField(automation, "companyUserGroupId"))
            if (scanResults.size() >= submissionBudget || !runAdmission.canStartNow(tenantId)) {
                // Deliberately visible, never a silent truncation: an operator reading the ServiceJobRun
                // must be able to tell "nothing was due" apart from "the cap was saturated".
                deferredResults << [automationId: automationId, scheduledFireTime: scheduledFireTime, deferred: true]
                return
            }

            Map<String, Object> executeParams = [
                    automationId     : automationId,
                    scheduledFireTime: scheduledFireTime,
                    nowTimestamp     : now,
                    runTrigger       : TRIGGER_SCHEDULED,
            ]
            ["outputLocation", "sparkMaster", "sparkAppName"].each { key ->
                if (input[key] != null) executeParams[key] = input[key]
            }

            Map<String, Object> submitResult = submitExecuteAutomationService(ec, executeParams, tenantId)
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
            // DAR-BE-328 plan 3: an automation with a sync lag allowance steps to the very next fire, even one already
            // past: an allowance longer than a day starts this fire after the next one, and skipping past now would
            // never read that next fire's window. Each fire still waits for its own bound (window end + allowance) in
            // peakStart, so a catch-up is one run per window, never a burst.
            // After a long outage the stepping is bounded: fires older than now minus (the longest allowance + one day)
            // are skipped, as the no-pair path skips every fire already past.
            AllowanceSpec pairAllowance = peakAllowanceOf(ec, peakTick, tenantId, automationId)
            Timestamp steppingFloor = scheduledFireTime
            if (pairAllowance != null) {
                Timestamp cutoff = new Timestamp(now.time - (longestAllowanceMinutes(pairAllowance) + 24L * 60L) * 60_000L)
                if (cutoff.after(steppingFloor)) steppingFloor = cutoff
            }
            Timestamp nextFireTime = pairAllowance != null ?
                    resolveNextScheduledFireTime(automation, scheduledFireTime, steppingFloor) :
                    resolveNextScheduledFireTime(automation, scheduledFireTime, now)
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
                submissionBudget - scanResults.size(), peakTick)

        // Only the cap deferrals are a saturation signal; a refused submission already logged its own
        // error above, and saying "in flight" about it would misattribute the cause.
        int capDeferredCount = deferredResults.count { Map<String, Object> entry -> !entry.containsKey("submitResult") } as int
        if (capDeferredCount > 0) {
            logger.warn("Automation scan deferred {} due automation(s) to the next tick: {} execution(s) already in flight (cap {}), admission {}",
                    capDeferredCount, inFlightCount, maxConcurrentExecutions(), runAdmission.snapshot())
        }

        return [
                scanTimestamp  : now,
                dueCount       : dueAutomationEntries.size(),
                scanResults    : scanResults,
                deferredCount  : deferredResults.size(),
                deferredResults: deferredResults,
                retryDueCount  : retryResults.size(),
                retryResults   : retryResults,
                peakHeldCount  : peakHeldResults.size(),
                peakHeldResults: peakHeldResults,
        ]
    }

    /**
     * How many more executions this tick may enqueue: {@link #maxConcurrentExecutions} minus the
     * executions already RUNNING. Stale RUNNING rows (crashed JVM, killed container) cannot wedge the
     * scheduler forever — sweep#StuckReconciliationRuns reaps them to FAILED on its own 10-minute cron.
     */
    protected static int remainingSubmissionBudget(int inFlightCount) {
        int cap = Math.max(1, maxConcurrentExecutions())
        return Math.max(0, cap - inFlightCount)
    }

    protected static int countInFlightExecutions(def ec) {
        try {
            return TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION,
                            "system-cron cross-tenant sweep: count in-flight executions to bound submissions")
                    .condition("statusEnumId", AutomationExecutionSupport.STATUS_RUNNING)
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
            Integer submissionBudget = null, Map<String, Object> peakTick = null) {
        Map<String, Object> tick = peakTick ?: newPeakTick(now)
        int remainingBudget = submissionBudget != null ? Math.max(0, submissionBudget) :
                remainingSubmissionBudget(countInFlightExecutions(ec))
        List pendingRows = TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION,
                        "system-cron retry pickup: PENDING automation executions past nextRetryDate")
                .condition("statusEnumId", AutomationExecutionSupport.STATUS_PENDING)
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
                        statusEnumId   : AutomationExecutionSupport.STATUS_DEAD_LETTER,
                        completedDate  : stamp,
                        maxRetryCount  : maxRetry,
                        lastUpdatedDate: stamp,
                ])
                // Dead-lettering means the automation has given up entirely — the one outcome an
                // operator most needs told about, and previously the quietest. Best-effort, and never
                // allowed to stop the rest of the retry sweep. DAR-BE-015: the card (with the last error)
                // goes to Darpan's error space; a client destination gets only the neutral line.
                try {
                    def deadLetterAutomation = loadAutomation(ec, automationId)
                    String lastError = normalize(readField(row, "errorMessage"))
                    String failureReason = "Automation gave up after ${maxRetry} retries. " +
                            "Last error: ${lastError ?: "unknown"}"
                    String runResultId = normalize(readField(row, "reconciliationRunResultId")) ?:
                            persistFailureRunResult(ec, deadLetterAutomation,
                                    new IllegalStateException(failureReason), stamp)
                    notifyRunError(ec, deadLetterAutomation, executionMap(row) + [statusEnumId: AutomationExecutionSupport.STATUS_DEAD_LETTER],
                            runResultId, lastError ?: failureReason)
                } catch (Throwable deadLetterNotifyError) {
                    logger.warn("Dead-letter notification failed (best-effort): ${deadLetterNotifyError.getClass().name}")
                }
                results << [automationExecutionId: execId, automationId: automationId, statusEnumId: AutomationExecutionSupport.STATUS_DEAD_LETTER]
                return
            }

            String tenantId = normalize(readField(row, "companyUserGroupId"))
            // DAR-BE-328: a retry inside peak hours waits for them to end, unclaimed (retryCount and nextRetryDate as
            // they are), and re-reads its original window when it runs. Moves never apply to a retry.
            Instant retryStart = peakStart(ec, tick, (scheduledDate ?: now).toInstant(), automationId, tenantId, false)
            if (retryStart.isAfter(now.toInstant())) {
                results << [automationExecutionId: execId, automationId: automationId, peakHeldUntil: Timestamp.from(retryStart)]
                return
            }
            if (remainingBudget <= 0 || !runAdmission.canStartNow(tenantId)) {
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
                    // DAR-BE-015: the re-drive is the same run, started the way the first attempt was.
                    runTrigger       : triggerOf(row),
            ]
            ["outputLocation", "sparkMaster", "sparkAppName"].each { String key -> if (input[key] != null) executeParams[key] = input[key] }
            Map<String, Object> executeResult = submitExecuteAutomationService(ec, executeParams, tenantId)
            if (executeResult.submitted == true) remainingBudget--
            results << [automationExecutionId: execId, automationId: automationId, retryCount: currentRetry + 1, executeResult: executeResult]
        }
        return results
    }

    /**
     * DAR-BE-142. Operator retry from the last extract: a FAILED or dead-lettered window whose reads all finished is run
     * again as a new execution over the same window, its extracts replayed (ExtractReplaySupport) instead of read. An
     * audit automation keeps the window the failed attempt read, so its findings land on the same morning's report.
     * Refused up front, in words, when the attempt cannot be replayed. Answers what was submitted.
     */
    static Map<String, Object> retryFromExtracts(def ec, Map params) {
        String executionId = normalize(params?.automationExecutionId)
        if (!executionId) return [submitted: false, error: "automationExecutionId is required"]
        def execution = TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION,
                        "operator retry from extracts: load automation execution by id")
                .condition("automationExecutionId", executionId).useCache(false).one()
        if (!execution) return [submitted: false, error: "Automation execution '${executionId}' was not found.".toString()]
        String status = normalize(readField(execution, "statusEnumId"))
        if (!(status in [AutomationExecutionSupport.STATUS_FAILED, AutomationExecutionSupport.STATUS_DEAD_LETTER])) {
            return [submitted: false, error: "Automation execution '${executionId}' is ${status}; only a failed window can be retried from its extracts.".toString()]
        }
        String automationId = normalize(readField(execution, "automationId"))
        def automation = loadAutomation(ec, automationId)
        String tenantId = normalize(readField(automation, "companyUserGroupId"))
        // DAR-OPS-029 OC34 (S11, Aditi D8): an audit's replay of an older read never overwrites what a later run of its checks
        // recorded; refused here, in words, rather than run and silently writing nothing.
        List<Map> auditChecks = AuditAutomationSupport.checksFor(ec, automation)
        if (auditChecks && AuditAutomationSupport.laterRunRecorded(ec, tenantId, auditChecks.collect { (String) it.checkId },
                AuditAutomationSupport.replayReadStamp(ec, tenantId, executionId), executionId)) {
            return [submitted: false, error: ("Not retried: a later run of this audit's checks is already recorded, and replaying " +
                    "this older read would overwrite it. Run the check again instead.").toString()]
        }
        Map<String, Map<String, Object>> files
        try {
            files = ExtractReplaySupport.replayResults(ec, tenantId, executionId, !AutomationExecutionSupport.isEvaluateScope(ec, automation))
        } catch (IllegalStateException refused) {
            return [submitted: false, error: refused.message]
        }

        Timestamp start = toTimestamp(readField(execution, "childWindowStartDate") ?: readField(execution, "windowStartDate"))
        Timestamp end = toTimestamp(readField(execution, "childWindowEndDate") ?: readField(execution, "windowEndDate"))
        if (start == null || end == null) return [submitted: false, error: "Execution ${executionId} recorded no window to replay.".toString()]
        Map<String, Object> executeParams = [automationId: automationId, scheduledFireTime: nowTimestamp(ec), replayFromExecutionId: executionId]
        if (auditChecks) {
            // The audit's own days in the zone its windows are cut in (AuditAutomationSupport.zoneOf), end exclusive:
            // [Oct 1 00:00, Oct 28 00:00) is Oct 1 to 27.
            ZoneId zone = AutomationWindows.automationZone(ec, automation)
            executeParams.auditWindowFromDate = java.sql.Date.valueOf(start.toInstant().atZone(zone).toLocalDate())
            executeParams.auditWindowThruDate = java.sql.Date.valueOf(new Timestamp(end.time - 1).toInstant().atZone(zone).toLocalDate())
            executeParams.auditFullRead = true
        } else {
            executeParams.windowStartDate = start
            executeParams.windowEndDate = end
        }
        Map submitted = submitExecuteAutomationService(ec, executeParams, tenantId)
        return [submitted            : submitted.submitted == true,
                queued               : submitted.queued == true,
                error                : submitted.error,
                automationId         : automationId,
                replayFromExecutionId: executionId,
                extracts             : files.collect { String side, Map f -> [side: side, fileName: f.fileName, recordCount: f.recordCount] }]
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
        if (!(status in [AutomationExecutionSupport.STATUS_FAILED, AutomationExecutionSupport.STATUS_DEAD_LETTER])) {
            return [automationExecutionId: executionId, statusEnumId: status, requeued: false,
                    error: "Automation execution '${executionId}' is ${status}; only ${AutomationExecutionSupport.STATUS_FAILED} or ${AutomationExecutionSupport.STATUS_DEAD_LETTER} executions can be reprocessed.".toString()]
        }

        Integer requestedMax = normalizeInt(input.maxRetryCount)
        int maxRetry = requestedMax != null ? Math.max(0, requestedMax) : resolveMaxRetryCount(execution)
        Timestamp stamp = nowTimestamp(ec)
        updateAutomationExecution(ec, execution, [
                statusEnumId   : AutomationExecutionSupport.STATUS_PENDING,
                retryCount     : 0,
                maxRetryCount  : maxRetry,
                nextRetryDate  : stamp,
                errorMessage   : "",
                lastUpdatedDate: stamp,
        ])
        return [automationExecutionId: executionId, statusEnumId: AutomationExecutionSupport.STATUS_PENDING, requeued: true]
    }

    /**
     * DAR-BE-002: hand execute#Automation to the background and return AT ONCE — no future,
     * no join. The returned map is a submission ACK, not an outcome: the execution's real result lives on
     * its own ReconciliationAutomationExecution + ReconciliationRunResult rows, both minted at RUNNING,
     * which is what the live progress view follows.
     *
     * <p>executeParams must keep carrying automationId + scheduledFireTime: the detached execution has no
     * ambient tenant (the scheduler runs as anonymous {@code _NA_}) and resolves its tenant anchor from
     * the automation's companyUserGroupId, exactly as the joined path did.</p>
     *
     * <p>DAR-BE-143: on a running Moqui the execution goes through {@link RunAdmission} in its tenant's lane and may
     * wait for a slot (answer queued: true). It runs on RunAdmission's threads as Moqui's own async runner runs a
     * service, but as anonymous, whoever asked: the automation's tenant then applies, as on a scheduled run, rather than
     * whichever tenant the person who pressed play has switched to by the time it starts (DAR-BE-141).</p>
     *
     * <p>Contexts with no Moqui factory (unit harnesses) keep Moqui's async facade; those with no async facade
     * either fall back to running inline rather than dropping the window — the same fallback the future-based
     * dispatcher had.</p>
     */
    protected static Map<String, Object> submitExecuteAutomationService(def ec, Map<String, Object> executeParams,
                                                                       String tenantId = null) {
        String automationId = normalize(executeParams?.automationId)
        def factory = moquiFactoryOf(ec)
        if (factory != null) {
            Map<String, Object> params = new HashMap<String, Object>(executeParams ?: [:])
            Map admitted = runAdmission.submit(tenantId, admissionLabel(automationId), { ->
                new org.moqui.impl.service.ServiceCallAsyncImpl.AsyncServiceInfo(factory, null,
                        "reconciliation.ReconciliationAutomationServices.execute#Automation", params).runInternal()
            } as Runnable)
            if (admitted.submitted != true) {
                logger.error("Could not submit automation execution for {}: {}", automationId, admitted.error)
                return [submitted: false, submittedAsync: true, error: admitted.error]
            }
            if (admitted.queued == true) {
                logger.info("Automation execution {} waits for a slot (tenant {}, position {})", automationId, tenantId, admitted.position)
            }
            return [submitted: true, submittedAsync: true, queued: admitted.queued == true] +
                    (admitted.position != null ? [queuePosition: admitted.position] : [:])
        }
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

    /** The running Moqui's factory, or null in a harness that has none. */
    private static def moquiFactoryOf(def ec) {
        def factory
        try { factory = ec?.factory } catch (MissingPropertyException ignored) { return null }
        return factory instanceof org.moqui.impl.context.ExecutionContextFactoryImpl ? factory : null
    }

    protected static Map<String, Object> callExecuteAutomationService(def ec, Map<String, Object> executeParams) {
        return AutomationExecutionSupport.dispatchInternalService(ec,
                "reconciliation.ReconciliationAutomationServices.execute#Automation", executeParams)
    }

    protected static List loadActiveAutomations(def ec) {
        List rows = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.reconciliation.ReconciliationAutomation",
                        "system-cron cross-tenant sweep; no active tenant in scheduler context")
                .condition("isActive", "Y")
                .useCache(false)
                .list() ?: []
        // DAR-BE-072: archiving (the trash can, DAR-BE-061) leaves isActive alone — pause and delete are separate
        // switches — so the sweep must skip archived rows itself, or a "deleted" automation keeps firing. In Groovy
        // rather than SQL so NULL (rows from before the column existed) still counts as not archived.
        return rows.findAll { normalize(readField(it, "isArchived")) != "Y" }
    }

    protected static void updateAutomation(def ec, def automation, Map<String, Object> fields) {
        if (!automation) return
        runInTransaction(ec, "Error updating reconciliation automation", {
            fields.each { entry -> automation.set(entry.key as String, entry.value) }
            automation.update()
            return null
        })
    }

    /** DAR-OPS-026: the admission seam back to the shared RunAdmission. Test-only. */
    static void resetForTests() {
        RunAdmission.resetForTests()
        runAdmission = RunAdmission.SHARED
    }
}
