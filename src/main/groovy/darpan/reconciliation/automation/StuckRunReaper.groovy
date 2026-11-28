package darpan.reconciliation.automation

import darpan.common.DarpanEntityConstants
import darpan.config.DarpanSettings
import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.streak.AutomationStreakSupport
import groovy.transform.CompileStatic

import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityFind
import org.moqui.entity.EntityValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp

/**
 * Audit H2.5 / H3.3 — Reaper that releases reconciliation rows wedged in PENDING/RUNNING after
 * a crash. Without this, the row sits there forever:
 *   - ReconciliationOutputSupport.ACTIVE_STATUSES includes RUNNING, so the UI gates "in-flight"
 *     and refuses to let the user re-trigger.
 *   - AutomationExecutionSupport.findOrCreateExecution treats only PENDING as "reusable" so the
 *     scanner's duplicate check returns true and skips the same window forever.
 *
 * The reaper runs as a Moqui ServiceJob (see ReconciliationJobSeedData.xml) every 10 minutes,
 * sweeping rows whose lastUpdatedStamp is older than the reaperStaleMinutes setting. A reaped row
 * keeps {@link #REAPED_REASON} in errorMessage (unless it already says why it failed), and its run is
 * reported as a run error: Darpan's error space gets the card, a notify-me subscriber only the neutral line.
 */
@CompileStatic
class StuckRunReaper {

    private static final Logger logger = LoggerFactory.getLogger(StuckRunReaper.class)

    static final String STATUS_PENDING = "AUT_STAT_PENDING"
    static final String STATUS_RUNNING = "AUT_STAT_RUNNING"
    static final String STATUS_FAILED  = "AUT_STAT_FAILED"
    /** DAR-BE-015: the error Darpan's card, the daily email and the run-result page show for a run the reaper stopped. */
    static final String REAPED_REASON = "The run stalled and was stopped by the stuck-run watchdog."

    /** The threshold is the reaperStaleMinutes setting (DAR-BE-304), read at each sweep; RunHealth shows the same one. */
    static Map<String, Object> sweep(ExecutionContext ec) {
        int staleMinutes = DarpanSettings.intValue("darpan.reconciliation.runHealth.reaperStaleMinutes")
        Timestamp cutoff = new Timestamp(ec.getUser().getNowTimestamp().time - (staleMinutes * 60_000L))

        // DAR-BE-015: executions first. A reaped automation run is a run error (notifyRunError): its client destinations get the
        // neutral line, which claims the run's result row, so the run-result sweep below then tells its subscribers nothing more.
        int executionsReaped = sweepEntity(ec, DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION, cutoff)
        int runResultsReaped = sweepEntity(ec, DarpanEntityConstants.RECONCILIATION_RUN_RESULT, cutoff)

        logger.info("StuckRunReaper completed: runResults={} executions={} cutoff={}",
                runResultsReaped, executionsReaped, cutoff)

        return [
                cutoffTimestamp     : cutoff,
                reapedRunResultCount: runResultsReaped,
                reapedExecutionCount: executionsReaped,
        ]
    }

    /** Reaps each stale row on its own: one row that fails to flip is logged and never stops the rest. */
    private static int sweepEntity(ExecutionContext ec, String entityName, Timestamp cutoff) {
        List<EntityValue> stale
        try {
            EntityFind finder = (EntityFind) TenantScopedFinder.findGlobalUnscoped(ec, entityName,
                    "system-cron cross-tenant sweep; StuckRunReaper reaps stale rows across all tenants")
            finder.condition("statusEnumId", "in", [STATUS_PENDING, STATUS_RUNNING] as List<Object>)
            finder.condition("lastUpdatedStamp", "less", (Object) cutoff)
            stale = finder.list()
        } catch (Throwable t) {
            logger.warn("StuckRunReaper {} sweep failed: {}", entityName, t.toString())
            return 0
        }
        boolean executions = DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION == entityName
        int updated = 0
        for (EntityValue row : stale) {
            try {
                markFailed(ec, row, executions)
                updated++
            } catch (Throwable t) {
                logger.warn("StuckRunReaper failed to flip {} row: {}", entityName, t.toString())
                continue
            }
            try {
                if (executions) afterExecutionReaped(ec, row) else afterRunResultReaped(ec, row)
            } catch (Throwable t) {
                logger.warn("StuckRunReaper could not report a reaped {} row: {}", entityName, t.toString())
            }
        }
        return updated
    }

    /** FAILED, with the reason unless the row already says why; a reaped execution also ends now. */
    private static void markFailed(ExecutionContext ec, EntityValue row, boolean execution) {
        row.set("statusEnumId", STATUS_FAILED)
        if (!row.get("errorMessage")?.toString()?.trim()) row.set("errorMessage", REAPED_REASON)
        if (execution) {
            // DAR-BE-015: a reaped run ended now, so the daily error email (it reads executions by completedDate) lists it.
            Timestamp reapedAt = ec.getUser().getNowTimestamp()
            row.set("completedDate", reapedAt)
            row.set("lastUpdatedDate", reapedAt)
        }
        row.update()
    }

    private static void afterExecutionReaped(ExecutionContext ec, EntityValue row) {
        notifyReapedExecution(ec, row)
        // DAR-BE-107: a reaper-killed slot breaks the streak (decision 6, 2026-10-22).
        AutomationStreakSupport.recordSlots(ec, row.get("automationId"))
    }

    /**
     * DAR-OPS-029 S07a: a reaped run result is a failed run like any other (DAR-BE-362's notifySavedRunError: one card to
     * Darpan's error space, the neutral line to its notify-me subscribers, never the error). A run already told is told
     * nothing more, and an automation's run is left to its execution (which reports it when it ends or is reaped), so a
     * run result that goes stale a sweep before its execution posts no second card. notifySavedRunError never throws.
     */
    private static void afterRunResultReaped(ExecutionContext ec, EntityValue row) {
        if (row.get("notifiedDate") != null) return
        if (ownedByAnExecution(ec, row.get("reconciliationRunResultId"))) return
        AutomationExecutionSupport.notifySavedRunError(ec, [
                reconciliationRunResultId: row.get("reconciliationRunResultId"),
                companyUserGroupId       : row.get("companyUserGroupId"),
                savedRunId               : row.get("savedRunId"),
                resultDataManagerPath    : row.get("resultDataManagerPath"),
        ] as Map, REAPED_REASON)
    }

    private static boolean ownedByAnExecution(ExecutionContext ec, Object runResultId) {
        if (runResultId == null) return false
        EntityFind finder = (EntityFind) TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION,
                "system-cron cross-tenant sweep; StuckRunReaper checks whether a reaped run belongs to an automation execution")
        return finder.condition("reconciliationRunResultId", runResultId).count() > 0
    }

    /**
     * DAR-BE-015: a reaped execution is a FAILED run error like any other. notifyRunError posts Darpan's card (with this reason),
     * gives a client destination only the neutral line and purges the run's subscriptions.
     */
    private static void notifyReapedExecution(ExecutionContext ec, EntityValue row) {
        Object automationId = row.get("automationId")
        if (automationId == null) return
        EntityFind finder = (EntityFind) TenantScopedFinder.findGlobalUnscoped(ec, "darpan.reconciliation.ReconciliationAutomation",
                "system-cron cross-tenant sweep; StuckRunReaper reads the automation of a run it reaped to report its error")
        EntityValue automation = finder.condition("automationId", automationId).one()
        if (automation == null) {
            logger.warn("StuckRunReaper: automation {} of reaped execution {} not found; no error card", automationId, row.get("automationExecutionId"))
            return
        }
        Map<String, Object> execution = new LinkedHashMap<String, Object>()
        for (Map.Entry<String, Object> field : ((Map<String, Object>) row).entrySet()) execution.put(field.key, field.value)
        Object runResultId = row.get("reconciliationRunResultId")
        AutomationExecutionSupport.notifyRunError(ec, automation, execution, runResultId != null ? runResultId.toString() : null, REAPED_REASON)
    }
}
