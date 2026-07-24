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

    static final Set<String> ACTIVE_STATUSES   = [STATUS_PENDING, STATUS_RUNNING].toSet()
    static final Set<String> TERMINAL_STATUSES = [STATUS_SUCCESS, STATUS_FAILED, STATUS_NO_DATA, STATUS_SKIP_DUP].toSet()

    static final String STAGE_RESOLVE       = "RESOLVE"
    static final String STAGE_EXTRACT_FILE1 = "EXTRACT_FILE1"
    static final String STAGE_EXTRACT_FILE2 = "EXTRACT_FILE2"
    static final String STAGE_COMPARE       = "COMPARE"
    static final String STAGE_WRITE_OUTPUT  = "WRITE_OUTPUT"
    static final String STAGE_NOTIFY        = "NOTIFY"

    static final Map<String, Integer> STAGE_SEQUENCE = [
            (STAGE_RESOLVE)      : 1,
            (STAGE_EXTRACT_FILE1): 2,
            (STAGE_EXTRACT_FILE2): 3,
            (STAGE_COMPARE)      : 4,
            (STAGE_WRITE_OUTPUT) : 5,
            (STAGE_NOTIFY)       : 6,
    ]

    static boolean isTerminalStatus(String status) { TERMINAL_STATUSES.contains(status) }
    static boolean isActiveStatus(String status) { ACTIVE_STATUSES.contains(status) }
    static int stageSequenceOf(String stageCode) { (STAGE_SEQUENCE[stageCode] ?: 0) as int }

    /** Create-or-adopt the run row in RUNNING with startedDate + currentStage=RESOLVE. Returns runId. */
    static String beginRun(def ec, Map<String, Object> ctx) {
        String runId = norm(ctx.get("reconciliationRunResultId"))
        Timestamp now = (Timestamp) ec.user.nowTimestamp
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
        DarpanMdcSupport.stampRun(runId, norm(ctx.get("savedRunId")))
        DarpanMdcSupport.stampStage(STAGE_RESOLVE)
        logger.info("recon run begin savedRunId={} runId={}", norm(ctx.get("savedRunId")), runId)
        return runId
    }

    /** Create a step row in RUNNING, advance the run's currentStage, stamp MDC stage. Returns the step value. */
    static Object beginStep(def ec, String runId, Map<String, Object> ctx, String stageCode) {
        Timestamp now = (Timestamp) ec.user.nowTimestamp
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

    /** Bump heartbeat + optional progress on the step (and mirror onto the run for cheap live display). */
    static void heartbeat(def ec, Object step, Map<String, Object> progress) {
        if (step == null) return
        Timestamp now = (Timestamp) ec.user.nowTimestamp
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
        Timestamp now = (Timestamp) ec.user.nowTimestamp
        try {
            ec.transaction.runUseOrBegin(30, "Error ending reconciliation step", {
                step.set("statusEnumId", statusEnumId)
                step.set("completedDate", now)
                if (metrics?.get("recordCount") != null) step.set("recordCount", metrics.get("recordCount"))
                if (metrics?.get("errorMessage") != null) step.set("errorMessage", metrics.get("errorMessage"))
                if (metrics?.get("metricsJson") != null) step.set("metricsJson", metrics.get("metricsJson"))
                step.set("lastUpdatedDate", now)
                step.update()
            })
        } catch (Throwable t) {
            logger.warn("RunObservability.endStep best-effort failure: ${t.message}")
        }
        Object started = step.get("startedDate")
        long durationMs = (started instanceof Timestamp) ? (now.time - ((Timestamp) started).time) : -1L
        logger.info("recon stage end stage={} status={} rows={} durationMs={}",
                step.get("stageCode"), statusEnumId, step.get("recordCount"), durationMs)
    }

    /** Terminal SUCCESS/NO_DATA. Sets completedDate, progress=100, clears MDC. */
    static void completeRun(def ec, String runId, String terminalStatusEnumId, Map<String, Object> summary) {
        Timestamp now = (Timestamp) ec.user.nowTimestamp
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
        Timestamp now = (Timestamp) ec.user.nowTimestamp
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

    private static String norm(Object v) { v?.toString()?.trim() ?: null }
}
