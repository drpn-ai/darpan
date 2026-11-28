package darpan.reconciliation.automation

import darpan.common.DarpanEntityConstants
import darpan.facade.common.TenantScopedFinder
import darpan.facade.reconciliation.RunObservability
import darpan.reconciliation.notification.TenantNotificationSupport

import java.sql.Timestamp

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeInt
import static darpan.common.ValueSupport.readField
import static darpan.reconciliation.automation.AutomationRuntimeSupport.currentUserId
import static darpan.reconciliation.automation.AutomationRuntimeSupport.nowTimestamp
import static darpan.reconciliation.automation.AutomationRuntimeSupport.runInTransaction
import static darpan.reconciliation.automation.AutomationRuntimeSupport.safeMetadataJson
import static darpan.reconciliation.automation.AutomationRuntimeSupport.sanitizeErrorDetail
import static darpan.reconciliation.automation.AutomationRuntimeSupport.sanitizeErrorMessage
import static darpan.reconciliation.automation.AutomationRuntimeSupport.truncate
import static darpan.reconciliation.automation.AutomationRuntimeSupport.updateAutomationExecution
import static darpan.reconciliation.automation.AutomationWindows.toTimestamp

/**
 * DAR-OPS-029 S06 (split out of AutomationExecutionSupport). An automation run's rows: its ReconciliationAutomationExecution
 * (one per window and fire, created claimed by its creator, a queued one claimed by exactly one re-drive), the
 * ReconciliationRunResult minted at RUNNING and closed terminal, the heartbeat that keeps both off the stuck-run reaper, the
 * tenant every system write asserts, and what started the run (its trigger).
 */
class AutomationRunRecords {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(AutomationRunRecords)

    /** DAR-BE-015: what started a run (ReconciliationAutomationExecution.triggerType). */
    static final String TRIGGER_SCHEDULED = "scheduled"

    static final String TRIGGER_MANUAL = "manual"

    static final String TRIGGER_PLAY = "play"

    static final Set<String> REUSABLE_EXECUTION_STATUSES = [AutomationExecutionSupport.STATUS_PENDING] as Set

    /** DAR-BE-015: what started a run, from execute#Automation's input. A blank runTrigger is a scheduled run unless the input says a person asked. */
    static String runTriggerOf(Map input) {
        String given = normalize(input?.runTrigger)
        if (given in [TRIGGER_SCHEDULED, TRIGGER_MANUAL, TRIGGER_PLAY]) return given
        if (normalize(input?.replayFromExecutionId) || input?.auditVerifyKeys) return TRIGGER_MANUAL
        if (input?.auditWindowFromDate != null) return TRIGGER_PLAY
        return TRIGGER_SCHEDULED
    }

    /** DAR-BE-015: what started an execution row; a row from before triggerType existed was scheduled. */
    static String triggerOf(Object execution) {
        return normalize(readField(execution, "triggerType")) ?: TRIGGER_SCHEDULED
    }

    /** An execution row (entity value or map) as a plain map, so a closing write's fields can be laid over it. */
    protected static Map<String, Object> executionMap(def execution) {
        return execution instanceof Map ? new LinkedHashMap<String, Object>((Map) execution) : [:]
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
            Map<String, Object> window, int sequenceNum, String triggerType = null) {
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
            // DAR-OPS-029 F-E2: a queued row is RE-USED only by the caller that claims it; any other caller skips it as a duplicate.
            boolean claimed = REUSABLE_EXECUTION_STATUSES.contains(normalize(readField(existing, "statusEnumId"))) &&
                    claimQueuedExecution(ec, existing)
            return [execution: existing, duplicate: !claimed, reused: claimed]
        }

        String assertedTenantId = assertSystemWriteTenant(ec, automation)
        def created = runInTransaction(ec, "Error creating reconciliation automation execution", {
            Timestamp createdTimestamp = nowTimestamp(ec)
            def execution = ec.entity.makeValue(DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION)
            execution.set("automationId", automationId)
            execution.set("companyUserGroupId", assertedTenantId)
            execution.set("createdByUserId", normalize(readField(automation, "createdByUserId")) ?: currentUserId(ec))
            // DAR-OPS-029 F-E2: created claimed by its creator (RUNNING), never left PENDING for another caller to claim first. A crash
            // before the run starts leaves a RUNNING row the stuck-run reaper ends, not an orphan PENDING one nothing re-drives.
            execution.set("statusEnumId", AutomationExecutionSupport.STATUS_RUNNING)
            execution.set("scheduledDate", scheduledFireTime)
            // DAR-BE-015: kept across retries (a re-drive reuses this row), so a dead letter knows what started it.
            execution.set("triggerType", triggerType ?: TRIGGER_SCHEDULED)
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

    /**
     * DAR-OPS-029 F-E2. Claims a queued (PENDING) execution for this caller: the row is read FOR UPDATE inside a transaction (the
     * caller's, else its own), so a second claimer waits on the first's lock and then reads it RUNNING. True for exactly one caller.
     */
    protected static boolean claimQueuedExecution(def ec, def execution) {
        String executionId = normalize(readField(execution, "automationExecutionId"))
        return (boolean) runInTransaction(ec, "Error claiming a queued automation execution", {
            def row = TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION,
                            "claim a queued automation execution by id, read for update so one caller wins")
                    .condition("automationExecutionId", executionId).useCache(false).forUpdate(true).one()
            if (row == null || !REUSABLE_EXECUTION_STATUSES.contains(normalize(readField(row, "statusEnumId")))) return false
            Timestamp claimedAt = nowTimestamp(ec)
            row.set("statusEnumId", AutomationExecutionSupport.STATUS_RUNNING)
            row.set("lastUpdatedDate", claimedAt)
            row.update()
            // the caller's copy of the row reads as what it now is
            execution.set("statusEnumId", AutomationExecutionSupport.STATUS_RUNNING)
            return true
        })
    }

    /**
     * DAR-BE-142. Each side's file and count go on the execution as soon as that side is read, not only when the window
     * succeeds, so a window that fails later still names the extracts a retry can replay. Best effort.
     */
    protected static void stampExtract(def ec, def execution, String field, Map<String, Object> result) {
        if (!AutomationNoDataJudge.hasData(result)) return
        try {
            updateAutomationExecution(ec, execution, [("${field}Name".toString())          : normalize(result.fileName),
                                                      ("${field}DataManagerPath".toString()): normalize(result.dataManagerPath),
                                                      ("${field}RecordCount".toString())    : normalizeInt(result.recordCount)])
        } catch (Throwable t) {
            logger.warn("Could not stamp the {} extract on execution {}: {}", field, readField(execution, "automationExecutionId"), t.message)
        }
    }

    /**
     * DAR-OPS-029 OC135. A side's only-in count, read in ONE order by the stored document's summary, the execution row and the
     * run-result row: the compare's missingInFile* (the side's records the other side lacks, which the verification passes
     * correct), else onlyInFile*. A real 0 is a count and is stored as 0 (`?:` read it as missing, so a two-sided run with
     * nothing only-in showed as single-sided in the app); absent on both (an evaluate, which never checked a second side)
     * stays absent.
     */
    static Object onlyInCount(Map reconcileResult, String side) {
        Object missingFromOther = reconcileResult?.get(side == AutomationExecutionSupport.FILE_SIDE_1 ? "missingInFile2Count" : "missingInFile1Count")
        return missingFromOther != null ? missingFromOther : reconcileResult?.get(side == AutomationExecutionSupport.FILE_SIDE_1 ? "onlyInFile1Count" : "onlyInFile2Count")
    }

    protected static Map<String, Object> executionUpdateFields(Map<String, Object> file1Result, Map<String, Object> file2Result,
            Map<String, Object> reconcileResult) {
        return [
                file1Name        : normalize(file1Result.fileName),
                file1DataManagerPath: normalize(file1Result.dataManagerPath),
                file2Name        : normalize(file2Result?.fileName),
                file2DataManagerPath: normalize(file2Result?.dataManagerPath),
                file1RecordCount : normalizeInt(file1Result.recordCount),
                file2RecordCount : normalizeInt(file2Result?.recordCount),
                file1ReadCount   : normalizeInt(file1Result.readCount),
                file2ReadCount   : normalizeInt(file2Result?.readCount),
                differenceCount  : normalizeInt(reconcileResult.differenceCount),
                onlyInFile1Count : normalizeInt(onlyInCount(reconcileResult, AutomationExecutionSupport.FILE_SIDE_1)),
                onlyInFile2Count : normalizeInt(onlyInCount(reconcileResult, AutomationExecutionSupport.FILE_SIDE_2)),
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
                runResultValue.set("statusEnumId", AutomationExecutionSupport.STATUS_RUNNING)
                runResultValue.set("startedDate", startedAt)
                runResultValue.set("lastHeartbeatDate", startedAt)
                runResultValue.set("createdDate", startedAt)
                runResultValue.set("lastUpdatedDate", startedAt)
                // currentStage is left unset here: the run loop's RunObservability.beginStep calls set it as each stage opens.
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
     * Task 2c: best-effort heartbeat at automation phase boundaries.
     *
     * <p>Task 2b mints the {@code ReconciliationRunResult} row at RUNNING, which newly exposes it to
     * {@code StuckRunReaper} — the reaper flips any {@code ReconciliationRunResult} or
     * {@code ReconciliationAutomationExecution} row sitting in PENDING/RUNNING to FAILED (and, for the
     * run-result row, notifies) once its {@code lastUpdatedStamp} — Moqui's auto-maintained column, NOT
     * {@code lastHeartbeatDate} — goes stale (the {@code darpan.reconciliation.runHealth.reaperStaleMinutes} setting, admin app Settings). Any
     * {@code .update()} bumps {@code lastUpdatedStamp} regardless of which fields change; this refreshes
     * {@code lastHeartbeatDate} (and, on the execution row, {@code lastUpdatedDate}) purely so that
     * {@code .update()} has something real to write.</p>
     *
     * <p>Complements the step rows' own heartbeats (RunObservability.beginStep/endStep) at the automation phase
     * boundaries. Called after each source extraction and after the reconcile call in
     * {@code executeAutomationForTenant} — the three points where a real, potentially slow I/O call
     * (a Shopify/OMS extract, or the Spark reconcile) has just finished.</p>
     *
     * <p>This is a BOUNDARY heartbeat, not a progress heartbeat: it resets the clock only between phases.
     * Inside an extract phase, the extract's own page heartbeats (RunObservability.heartbeatStageProgress)
     * keep the run-result row fresh and, since DAR-BE-328, touch this execution row too (at most once a
     * minute, RunObservability.touchAutomationExecution), so an extract slowed on purpose by a peak period
     * is not reaped while its pages still move. A single phase with no page heartbeat of its own (one very
     * large reconcile) is still falsely reaped if it alone exceeds the threshold.</p>
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
     * Loads a run-result row by the id this runner minted, re-pinned to the automation's own tenant so a
     * system-context read can never reach another tenant's row. A blank tenant cannot happen on the run
     * path ({@code findOrCreateExecution} already asserted it) and is left unconditioned rather than
     * silently matching nothing.
     */
    protected static def findAutomationRunResult(def ec, String runResultId, String expectedTenantId) {
        // The owner is checked on the row: a companyUserGroupId condition beside the full PK is dropped
        // by one() (DAR-BE-077), so the old re-pin never applied.
        if (normalize(expectedTenantId)) {
            return TenantScopedFinder.findOneOwnedByTenant(ec, DarpanEntityConstants.RECONCILIATION_RUN_RESULT,
                    "reconciliationRunResultId", runResultId, normalize(expectedTenantId),
                    "automation run-result read keyed by the id this runner minted — owner re-checked on the row")
        }
        return TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.RECONCILIATION_RUN_RESULT,
                        "automation run-result read keyed by the id this runner minted")
                .condition("reconciliationRunResultId", runResultId).useCache(false).one()
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
                    file2Name              : normalize(file2Result?.fileName),
                    file2DataManagerPath   : normalize(file2Result?.dataManagerPath),
                    resultDataManagerPath  : normalize(resultDataManagerPath),
                    reconciliationType     : normalize(reconcileResult.reconciliationType ?: reconcileResult.objectType),
                    differenceCount        : normalizeInt(reconcileResult.differenceCount),
                    onlyInFile1Count       : normalizeInt(onlyInCount(reconcileResult, AutomationExecutionSupport.FILE_SIDE_1)),
                    onlyInFile2Count       : normalizeInt(onlyInCount(reconcileResult, AutomationExecutionSupport.FILE_SIDE_2)),
            ].each { key, value -> if (value != null) runResultValue.set(key as String, value) }
            // Explicit terminal status (the row was minted RUNNING). DAR-OPS-029 F-E4 (Aditi 2026-11-28): a run whose rules did not
            // fully evaluate ends FAILED here too, as its execution row and the interactive path do, so run history never shows a
            // success; the rule's reason is the row's error (text-medium, 255).
            boolean rulesFailed = reconcileResult?.ruleExecutionFailed == true
            runResultValue.set("statusEnumId", rulesFailed ? AutomationExecutionSupport.STATUS_FAILED : AutomationExecutionSupport.STATUS_SUCCEEDED)
            if (rulesFailed) runResultValue.set("errorMessage", truncate(AutomationRunNotifier.ruleFailureReason(reconcileResult) ?: "A rule failed to build or evaluate.", 255))
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
                runResultValue.set("statusEnumId", AutomationExecutionSupport.STATUS_FAILED)
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
     * Ends one automation attempt CANCELLED: the execution row, the run-result row minted at RUNNING, and
     * that row's notify-me subscriptions. Shared by the API-range runner and {@code SftpAutomationSupport}.
     *
     * <p>Ordering: the run-result close is deliberately NOT inside the execution write's try, because if
     * that write is what failed the minted row would be stranded RUNNING — the one outcome every terminal
     * close in this file exists to prevent (the reaper turns it into a spurious FAILED alert two hours
     * later). Same shape as the SFTP failure close.</p>
     *
     * <p><b>Notification decision:</b> a cancelled run does NOT notify, and purges its subscriptions
     * instead. A cancel is not a run error (DAR-BE-015: no card, no "did not run" line), and the person
     * who pressed Cancel already knows. The subscriptions cannot simply be left, though:
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
                statusEnumId    : AutomationExecutionSupport.STATUS_CANCELLED,
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
        completeAutomationRunResult(ec, automation, runResultId, AutomationExecutionSupport.STATUS_CANCELLED, cancelledAt,
                [errorMessage: RunObservability.CANCEL_REASON])
        TenantNotificationSupport.purgeSubscriptionsForUnnotifiedRun(ec, runResultId)
        return cancelFields
    }
}
