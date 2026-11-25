package darpan.reconciliation.automation

import darpan.common.DarpanEntityConstants
import darpan.config.DarpanSettings
import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.notification.TenantNotificationSupport
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
 * sweeping rows whose lastUpdatedStamp is older than the reaperStaleMinutes setting.
 * Each transition writes a status message so operators can audit which runs were reaped.
 */
@CompileStatic
class StuckRunReaper {

    private static final Logger logger = LoggerFactory.getLogger(StuckRunReaper.class)

    static final String STATUS_PENDING = "AUT_STAT_PENDING"
    static final String STATUS_RUNNING = "AUT_STAT_RUNNING"
    static final String STATUS_FAILED  = "AUT_STAT_FAILED"
    /** DAR-BE-015: the error Darpan's card and the daily email show for a run the reaper stopped. */
    static final String REAPED_REASON = "The run stalled and was stopped by the stuck-run watchdog."

    /** The threshold is the reaperStaleMinutes setting (DAR-BE-304), read at each sweep; RunHealth shows the same one. */
    static Map<String, Object> sweep(ExecutionContext ec) {
        int staleMinutes = DarpanSettings.intValue("darpan.reconciliation.runHealth.reaperStaleMinutes")

        Timestamp cutoff = new Timestamp(System.currentTimeMillis() - (staleMinutes * 60_000L))

        // DAR-BE-015: executions first. A reaped automation run is a run error (notifyRunError): its client destinations get the
        // neutral line, which claims the run's result row, so the run-result sweep below then tells its subscribers nothing more.
        int executionsReaped = sweepEntity(ec, DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION, cutoff, staleMinutes)
        int runResultsReaped = sweepEntity(ec, DarpanEntityConstants.RECONCILIATION_RUN_RESULT, cutoff, staleMinutes)

        logger.info("StuckRunReaper completed: runResults={} executions={} cutoff={}",
                runResultsReaped, executionsReaped, cutoff)

        return [
                cutoffTimestamp     : cutoff,
                reapedRunResultCount: runResultsReaped,
                reapedExecutionCount: executionsReaped,
        ]
    }

    private static int sweepEntity(ExecutionContext ec, String entityName, Timestamp cutoff, int staleMinutes) {
        if (ec == null || ec.getEntity() == null) return 0
        int updated = 0
        String message = "Reaped: row left in RUNNING/PENDING > ${staleMinutes} minutes (lastUpdatedStamp before ${cutoff}).".toString()

        try {
            EntityFind finder = (EntityFind) TenantScopedFinder.findGlobalUnscoped(ec, entityName,
                    "system-cron cross-tenant sweep; StuckRunReaper reaps stale rows across all tenants")
            finder.condition("statusEnumId", "in", [STATUS_PENDING, STATUS_RUNNING] as List<Object>)
            finder.condition("lastUpdatedStamp", "less", (Object) cutoff)
            List<EntityValue> stale = finder.list()

            if (stale == null) return 0
            boolean executions = DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION == entityName
            for (EntityValue row : stale) {
                try {
                    row.set("statusEnumId", STATUS_FAILED)
                    if (executions) {
                        // DAR-BE-015: a reaped run ended now, so the daily error email (it reads executions by completedDate) lists it.
                        Timestamp reapedAt = ec.getUser().getNowTimestamp()
                        row.set("completedDate", reapedAt)
                        row.set("lastUpdatedDate", reapedAt)
                        if (!row.get("errorMessage")?.toString()?.trim()) row.set("errorMessage", REAPED_REASON)
                    }
                    // Append (or set) a statusMessage if the entity carries one.
                    try {
                        Object existing = row.get("statusMessage")
                        String existingText = (existing != null) ? existing.toString() : ""
                        String combined = existingText && existingText.trim() ?
                                "${existingText}\n${message}".toString() : message
                        row.set("statusMessage", combined)
                    } catch (Throwable ignored) {
                        // Entity has no statusMessage field; ignore.
                    }
                    row.update()
                    updated++
                    if (executions) {
                        notifyReapedExecution(ec, row)
                        // DAR-BE-107: a reaper-killed slot breaks the streak (decision 6, 2026-10-22).
                        AutomationStreakSupport.recordSlots(ec, row.get("automationId"))
                    }
                    // Task 7: the reaper has no automation/interactive-run context to carry a chatSpaceId,
                    // so subscriber spaces (ReconciliationRunNotifySubscription) are the only destinations.
                    // Best-effort — a notify failure must never stop the sweep from reaping other rows.
                    if (DarpanEntityConstants.RECONCILIATION_RUN_RESULT == entityName) {
                        try {
                            TenantNotificationSupport.notifyRunCompleted(ec, [
                                    reconciliationRunResultId: row.get("reconciliationRunResultId"),
                                    companyUserGroupId       : row.get("companyUserGroupId"),
                                    runName                  : row.get("savedRunId"),
                                    savedRunId               : row.get("savedRunId"),
                                    resultDataManagerPath    : row.get("resultDataManagerPath"),
                                    statusEnumId             : STATUS_FAILED,
                                    terminationReason        : "Run stalled and was marked FAILED by the stuck-run watchdog.",
                            ] as Map<String, Object>)
                        } catch (Throwable notifyError) {
                            logger.warn("StuckRunReaper notification failed (best-effort): ${notifyError.message}")
                        }
                    }
                } catch (Throwable t) {
                    logger.warn("StuckRunReaper failed to flip ${entityName} row: ${t.message}")
                }
            }
        } catch (Throwable t) {
            logger.warn("StuckRunReaper ${entityName} sweep failed: ${t.message}")
        }
        return updated
    }

    /**
     * DAR-BE-015: a reaped execution is a FAILED run error like any other. notifyRunError posts Darpan's card (with this reason),
     * gives a client destination only the neutral line and purges the run's subscriptions. Never throws: the sweep goes on.
     */
    private static void notifyReapedExecution(ExecutionContext ec, EntityValue row) {
        try {
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
        } catch (Throwable t) {
            logger.warn("StuckRunReaper run error notification failed (best-effort): {}", t.getClass().name)
        }
    }
}
