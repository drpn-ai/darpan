package darpan.facade.reconciliation

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import darpan.facade.common.DarpanMdcSupport
import java.sql.Timestamp

/**
 * Single instrumentation seam for reconciliation run observability. Foundation increment:
 * status lifecycle + per-stage timeline + structured logging. The watchdog/cancel (Phase 3),
 * UI (Phase 4), and alerting (Phase 5) build on these constants and write methods.
 */
class RunObservability {

    private static final Logger logger = LoggerFactory.getLogger(RunObservability.class)

    static final String RUN_RESULT_ENTITY = "darpan.reconciliation.ReconciliationRunResult"
    static final String RUN_STEP_ENTITY   = "darpan.reconciliation.ReconciliationRunStep"

    static final String STATUS_PENDING  = "AUT_STAT_PENDING"
    static final String STATUS_RUNNING  = "AUT_STAT_RUNNING"
    static final String STATUS_SUCCESS  = "AUT_STAT_SUCCESS"
    static final String STATUS_FAILED   = "AUT_STAT_FAILED"
    static final String STATUS_NO_DATA  = "AUT_STAT_NO_DATA"
    static final String STATUS_SKIP_DUP = "AUT_STAT_SKIP_DUP"
    static final String STATUS_CANCELLED = "AUT_STAT_CANCELLED"

    static final String CANCEL_REASON = "Run cancelled by an operator."

    static final Set<String> ACTIVE_STATUSES   = [STATUS_PENDING, STATUS_RUNNING].toSet()
    static final Set<String> TERMINAL_STATUSES = [STATUS_SUCCESS, STATUS_FAILED, STATUS_NO_DATA, STATUS_SKIP_DUP, STATUS_CANCELLED].toSet()

    static final String STAGE_RESOLVE       = "RESOLVE"
    static final String STAGE_EXTRACT_FILE1 = "EXTRACT_FILE1"
    static final String STAGE_EXTRACT_FILE2 = "EXTRACT_FILE2"
    static final String STAGE_COMPARE       = "COMPARE"
    static final String STAGE_WRITE_OUTPUT  = "WRITE_OUTPUT"
    /** Verification pass: point-lookup recheck of missing-in-side diffs against lookup-capable
     *  sources; runs after WRITE_OUTPUT because it verifies (and may rewrite) the written artifact. */
    static final String STAGE_VERIFY        = "VERIFY"
    static final String STAGE_NOTIFY        = "NOTIFY"

    static final Map<String, Integer> STAGE_SEQUENCE = [
            (STAGE_RESOLVE)      : 1,
            (STAGE_EXTRACT_FILE1): 2,
            (STAGE_EXTRACT_FILE2): 3,
            (STAGE_COMPARE)      : 4,
            (STAGE_WRITE_OUTPUT) : 5,
            (STAGE_VERIFY)       : 6,
            (STAGE_NOTIFY)       : 7,
    ]

    static boolean isTerminalStatus(String status) { TERMINAL_STATUSES.contains(status) }
    static boolean isActiveStatus(String status) { ACTIVE_STATUSES.contains(status) }
    static int stageSequenceOf(String stageCode) { (STAGE_SEQUENCE[stageCode] ?: 0) as int }

    /** Create-or-adopt the run row in RUNNING with startedDate + currentStage=RESOLVE. Returns runId. */
    static String beginRun(def ec, Map<String, Object> ctx) {
        String runId = norm(ctx.get("reconciliationRunResultId"))
        Timestamp now = nowSafe(ec)
        try {
            ec.transaction.runUseOrBegin(30, "Error starting reconciliation run", {
                def run = runId ? ec.entity.find(RUN_RESULT_ENTITY).condition("reconciliationRunResultId", runId).useCache(false).one() : null
                if (run == null) {
                    run = ec.entity.makeValue(RUN_RESULT_ENTITY)
                    if (runId) run.set("reconciliationRunResultId", runId)
                    ["savedRunId", "savedRunType", "reconciliationRunId", "reconciliationMappingId",
                     "ruleSetId", "compareScopeId", "companyUserGroupId", "createdByUserId",
                     "file1Name", "file2Name", "reconciliationType"].each { String k ->
                        if (ctx.get(k) != null) run.set(k, ctx.get(k))
                    }
                    run.set("statusEnumId", STATUS_RUNNING)
                    run.set("startedDate", now)
                    run.set("currentStage", STAGE_RESOLVE)
                    run.set("lastHeartbeatDate", now)
                    run.set("createdDate", now)
                    run.set("lastUpdatedDate", now)
                    if (!runId) run.setSequencedIdPrimary()
                    run.create()
                    runId = norm(run.get("reconciliationRunResultId"))
                } else {
                    run.set("statusEnumId", STATUS_RUNNING)
                    if (run.get("startedDate") == null) run.set("startedDate", now)
                    run.set("currentStage", STAGE_RESOLVE)
                    run.set("lastHeartbeatDate", now)
                    run.set("lastUpdatedDate", now)
                    run.update()
                }
            })
        } catch (Throwable t) {
            logger.warn("RunObservability.beginRun best-effort failure (runId=${runId}): ${t.message}")
        }
        if (runId) {
            DarpanMdcSupport.stampRun(runId, norm(ctx.get("savedRunId")))
            DarpanMdcSupport.stampStage(STAGE_RESOLVE)
        }
        logger.info("recon run begin savedRunId={} runId={}", norm(ctx.get("savedRunId")), runId)
        return runId
    }

    /** Create a step row in RUNNING, advance the run's currentStage, stamp MDC stage. Returns the step value. */
    static Object beginStep(def ec, String runId, Map<String, Object> ctx, String stageCode) {
        Timestamp now = nowSafe(ec)
        def step = null
        try {
            ec.transaction.runUseOrBegin(30, "Error starting reconciliation step", {
                step = ec.entity.makeValue(RUN_STEP_ENTITY)
                step.set("reconciliationRunResultId", runId)
                if (ctx.get("companyUserGroupId") != null) step.set("companyUserGroupId", ctx.get("companyUserGroupId"))
                step.set("stageCode", stageCode)
                step.set("stageSequence", stageSequenceOf(stageCode))
                step.set("statusEnumId", STATUS_RUNNING)
                step.set("startedDate", now)
                step.set("heartbeatDate", now)
                step.set("createdDate", now)
                step.set("lastUpdatedDate", now)
                step.setSequencedIdPrimary()
                step.create()
                def run = ec.entity.find(RUN_RESULT_ENTITY).condition("reconciliationRunResultId", runId).useCache(false).one()
                if (run != null) {
                    run.set("currentStage", stageCode)
                    // progressPercent mirrors the current stage's advisory progress; the prior
                    // stage's value must not survive the transition or the UI pairs it with the
                    // new stage label.
                    run.set("progressPercent", null)
                    run.set("lastHeartbeatDate", now)
                    run.set("lastUpdatedDate", now)
                    run.update()
                }
            })
        } catch (Throwable t) {
            logger.warn("RunObservability.beginStep best-effort failure (runId=${runId}, stage=${stageCode}): ${t.message}")
        }
        DarpanMdcSupport.stampStage(stageCode)
        logger.info("recon stage begin stage={} runId={}", stageCode, runId)
        return step
    }

    /**
     * Stamp one side's extracted artifact (name + data-manager path) onto the run row as soon as
     * that extract stage finishes. The full run row is only persisted after WRITE_OUTPUT, so
     * without this a live viewer sees no compared files until the whole run is done — and for
     * API-sourced runs the row starts with no file name at all. Best-effort: never fails a run.
     */
    static void recordSourceArtifact(def ec, Object runId, String side, Object fileName, Object dataManagerPath) {
        String runIdValue = norm(runId)
        String fileNameValue = norm(fileName)
        String pathValue = norm(dataManagerPath)
        if (!runIdValue || (!fileNameValue && !pathValue)) return
        boolean file1 = side == "file1"
        try {
            ec.transaction.runUseOrBegin(30, "Error recording reconciliation source artifact", {
                def run = ec.entity.find(RUN_RESULT_ENTITY).condition("reconciliationRunResultId", runIdValue).useCache(false).one()
                if (run == null) return
                if (fileNameValue) run.set(file1 ? "file1Name" : "file2Name", fileNameValue)
                if (pathValue) run.set(file1 ? "file1DataManagerPath" : "file2DataManagerPath", pathValue)
                run.set("lastUpdatedDate", nowSafe(ec))
                run.update()
            })
        } catch (Throwable t) {
            logger.warn("RunObservability.recordSourceArtifact best-effort failure (runId=${runIdValue}, side=${side}): ${t.message}")
        }
    }

    /** Bump heartbeat + optional progress on the step (and mirror onto the run for cheap live display). */
    /**
     * Advisory extract progress from a source extractor: finds the RUNNING step for the stage and
     * heartbeats the processed count onto it. The count is the progress — a percent is reported only
     * when the caller knows an expected total, which is the case for the second extract of a run
     * (it can divide against the first side's finished count) but never for the first, where nothing
     * has completed yet. Never throws — progress must never fail a run.
     */
    static void heartbeatStageProgress(def ec, Object runId, String stageCode, Object processedCount, Object expectedCount) {
        // Cancel check first, and deliberately outside the swallow-everything block below: a
        // multi-minute paged extract is where a cancel most needs to take effect, and this tick
        // is the only place the run loop is reachable mid-stage.
        checkpointCancel(ec, runId)
        try {
            String runIdValue = norm(runId)
            if (!runIdValue) return
            Integer processed = processedCount as Integer
            if (processed == null) return
            def step = ec.entity.find(RUN_STEP_ENTITY)
                    .condition("reconciliationRunResultId", runIdValue)
                    .condition("stageCode", norm(stageCode))
                    .condition("statusEnumId", STATUS_RUNNING)
                    .useCache(false).one()
            if (step == null) return
            Map<String, Object> progress = [recordCount: processed] as Map<String, Object>
            Integer expected = expectedCount as Integer
            if (expected != null && expected > 0) {
                progress.put("progressPercent", Math.min(99, (int) Math.floor(processed * 100.0d / expected)))
            }
            heartbeat(ec, step, progress)
        } catch (Exception ignored) {
        }
    }

    static void heartbeat(def ec, Object step, Map<String, Object> progress) {
        if (step == null) return
        Timestamp now = nowSafe(ec)
        try {
            ec.transaction.runUseOrBegin(30, "Error heartbeating reconciliation step", {
                step.set("heartbeatDate", now)
                if (progress?.get("recordCount") != null) step.set("recordCount", progress.get("recordCount"))
                step.set("lastUpdatedDate", now)
                step.update()
                def run = ec.entity.find(RUN_RESULT_ENTITY).condition("reconciliationRunResultId", norm(step.get("reconciliationRunResultId"))).useCache(false).one()
                if (run != null) {
                    run.set("lastHeartbeatDate", now)
                    if (progress?.get("progressPercent") != null) run.set("progressPercent", progress.get("progressPercent"))
                    run.set("lastUpdatedDate", now)
                    run.update()
                }
            })
        } catch (Throwable t) {
            logger.warn("RunObservability.heartbeat best-effort failure: ${t.message}")
        }
    }

    /** Close a step with a terminal status + optional record count; logs the stage duration. */
    static void endStep(def ec, Object step, String statusEnumId, Map<String, Object> metrics) {
        if (step == null) return
        Timestamp now = nowSafe(ec)
        try {
            ec.transaction.runUseOrBegin(30, "Error ending reconciliation step", {
                step.set("statusEnumId", statusEnumId)
                step.set("completedDate", now)
                if (metrics?.get("recordCount") != null) step.set("recordCount", metrics.get("recordCount"))
                if (metrics?.get("errorMessage") != null) {
                    String errorMessage = metrics.get("errorMessage").toString()
                    step.set("errorMessage", errorMessage.length() > 255 ? errorMessage.substring(0, 255) : errorMessage)
                }
                if (metrics?.get("metricsJson") != null) step.set("metricsJson", metrics.get("metricsJson"))
                step.set("lastUpdatedDate", now)
                step.update()
            })
        } catch (Throwable t) {
            logger.warn("RunObservability.endStep best-effort failure: ${t.message}")
        }
        try {
            Object started = step.get("startedDate")
            long durationMs = (started instanceof Timestamp) ? (now.time - ((Timestamp) started).time) : -1L
            logger.info("recon stage end stage={} status={} rows={} durationMs={}",
                    step.get("stageCode"), statusEnumId, step.get("recordCount"), durationMs)
        } catch (Throwable t) {
            logger.warn("RunObservability.endStep best-effort duration logging failure: ${t.message}")
        }
    }

    /** Terminal SUCCESS/NO_DATA. Sets completedDate, progress=100, clears MDC. */
    static void completeRun(def ec, String runId, String terminalStatusEnumId, Map<String, Object> summary) {
        Timestamp now = nowSafe(ec)
        try {
            ec.transaction.runUseOrBegin(30, "Error completing reconciliation run", {
                def run = ec.entity.find(RUN_RESULT_ENTITY).condition("reconciliationRunResultId", runId).useCache(false).one()
                if (run != null) {
                    run.set("statusEnumId", terminalStatusEnumId)
                    run.set("completedDate", now)
                    run.set("progressPercent", 100)
                    run.set("lastHeartbeatDate", now)
                    run.set("lastUpdatedDate", now)
                    run.update()
                }
            })
        } catch (Throwable t) {
            logger.warn("RunObservability.completeRun best-effort failure (runId=${runId}): ${t.message}")
        }
        logger.info("recon run complete status={} runId={}", terminalStatusEnumId, runId)
        DarpanMdcSupport.clearRun()
    }

    /** Terminal FAILED with a reason; closes the open step if provided; clears MDC. */
    static void failRun(def ec, String runId, Object openStepOrNull, String stageCode, String reason) {
        Timestamp now = nowSafe(ec)
        String shortReason = reason == null ? null : (reason.length() > 255 ? reason.substring(0, 255) : reason)
        try {
            if (openStepOrNull != null && !isTerminalStatus(norm(openStepOrNull.get("statusEnumId")))) {
                endStep(ec, openStepOrNull, STATUS_FAILED, [errorMessage: shortReason])
            }
            ec.transaction.runUseOrBegin(30, "Error failing reconciliation run", {
                def run = ec.entity.find(RUN_RESULT_ENTITY).condition("reconciliationRunResultId", runId).useCache(false).one()
                if (run != null) {
                    run.set("statusEnumId", STATUS_FAILED)
                    if (stageCode != null) run.set("currentStage", stageCode)
                    run.set("errorMessage", shortReason)
                    if (reason != null) run.set("errorDetail", reason)
                    run.set("completedDate", now)
                    run.set("lastUpdatedDate", now)
                    run.update()
                }
            })
        } catch (Throwable t) {
            logger.warn("RunObservability.failRun best-effort failure (runId=${runId}): ${t.message}")
        }
        logger.warn("recon run failed stage={} runId={} reason={}", stageCode, runId, shortReason)
        DarpanMdcSupport.clearRun()
    }

    /**
     * Ask a running reconciliation to stop. Cancellation is cooperative: this only stamps the
     * request, and the run ends itself at its next checkpoint (a stage boundary, or an extract
     * progress tick during a long paged extract). Returns false when the run is already terminal.
     */
    static boolean requestCancel(def ec, String runId, Object requestedByUserId) {
        Timestamp now = nowSafe(ec)
        boolean requested = false
        try {
            ec.transaction.runUseOrBegin(30, "Error requesting reconciliation run cancel", {
                def run = ec.entity.find(RUN_RESULT_ENTITY).condition("reconciliationRunResultId", runId).useCache(false).one()
                if (run == null || isTerminalStatus(norm(run.get("statusEnumId")))) return
                run.set("cancelRequestedDate", now)
                run.set("cancelRequestedByUserId", norm(requestedByUserId))
                run.set("lastUpdatedDate", now)
                run.update()
                requested = true
            })
        } catch (Throwable t) {
            logger.warn("RunObservability.requestCancel failure (runId=${runId}): ${t.message}")
        }
        if (requested) logger.info("recon run cancel requested runId={} byUserId={}", runId, norm(requestedByUserId))
        return requested
    }

    static boolean isCancelRequested(def ec, Object runId) {
        String runIdValue = norm(runId)
        if (!runIdValue) return false
        try {
            def run = ec.entity.find(RUN_RESULT_ENTITY).condition("reconciliationRunResultId", runIdValue).useCache(false).one()
            return run?.get("cancelRequestedDate") != null
        } catch (Throwable t) {
            // A failed read must not stop a healthy run; the next checkpoint tries again.
            logger.warn("RunObservability.isCancelRequested read failure (runId=${runIdValue}): ${t.message}")
            return false
        }
    }

    /** Throw out of the run loop when a cancel has been requested. No-op otherwise. */
    static void checkpointCancel(def ec, Object runId) {
        if (isCancelRequested(ec, runId)) throw new RunCancelledException(norm(runId))
    }

    /** Terminal CANCELLED; closes the open step so the timeline shows where the run stopped. */
    static void cancelRun(def ec, String runId, Object openStepOrNull, String stageCode) {
        Timestamp now = nowSafe(ec)
        try {
            if (openStepOrNull != null && !isTerminalStatus(norm(openStepOrNull.get("statusEnumId")))) {
                endStep(ec, openStepOrNull, STATUS_CANCELLED, [errorMessage: CANCEL_REASON])
            }
            ec.transaction.runUseOrBegin(30, "Error cancelling reconciliation run", {
                def run = ec.entity.find(RUN_RESULT_ENTITY).condition("reconciliationRunResultId", runId).useCache(false).one()
                if (run != null) {
                    run.set("statusEnumId", STATUS_CANCELLED)
                    if (stageCode != null) run.set("currentStage", stageCode)
                    run.set("errorMessage", CANCEL_REASON)
                    run.set("completedDate", now)
                    run.set("lastUpdatedDate", now)
                    run.update()
                }
            })
        } catch (Throwable t) {
            logger.warn("RunObservability.cancelRun best-effort failure (runId=${runId}): ${t.message}")
        }
        logger.info("recon run cancelled stage={} runId={}", stageCode, runId)
        DarpanMdcSupport.clearRun()
    }

    /** Raised by a cancel checkpoint to unwind the run loop. */
    static class RunCancelledException extends RuntimeException {
        final String reconciliationRunResultId
        RunCancelledException(String runId) {
            super(CANCEL_REASON)
            this.reconciliationRunResultId = runId
        }
    }

    private static String norm(Object v) { v?.toString()?.trim() ?: null }

    /** Best-effort clock read: falls back to wall-clock time if ec.user is unavailable/throws. */
    private static Timestamp nowSafe(def ec) {
        try {
            return (Timestamp) ec.user.nowTimestamp
        } catch (Throwable t) {
            logger.warn("RunObservability.nowSafe falling back to system clock: ${t.message}")
            return new Timestamp(System.currentTimeMillis())
        }
    }
}
