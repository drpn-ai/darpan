package darpan.reconciliation.automation

import darpan.facade.common.TenantScopedFinder
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
 * sweeping rows whose lastUpdatedStamp is older than the configured threshold (default 120 min).
 * Each transition writes a status message so operators can audit which runs were reaped.
 */
@CompileStatic
class StuckRunReaper {

    private static final Logger logger = LoggerFactory.getLogger(StuckRunReaper.class)

    static final String STATUS_PENDING = "AUT_STAT_PENDING"
    static final String STATUS_RUNNING = "AUT_STAT_RUNNING"
    static final String STATUS_FAILED  = "AUT_STAT_FAILED"
    static final int DEFAULT_STALE_MINUTES = 120

    static Map<String, Object> sweep(ExecutionContext ec, Object rawStaleMinutes) {
        int staleMinutes
        if (rawStaleMinutes instanceof Number) {
            staleMinutes = (rawStaleMinutes as Number).intValue()
        } else if (rawStaleMinutes != null && rawStaleMinutes.toString().trim()) {
            try { staleMinutes = Integer.parseInt(rawStaleMinutes.toString().trim()) }
            catch (NumberFormatException ignored) { staleMinutes = DEFAULT_STALE_MINUTES }
        } else {
            staleMinutes = DEFAULT_STALE_MINUTES
        }
        if (staleMinutes <= 0) staleMinutes = DEFAULT_STALE_MINUTES

        Timestamp cutoff = new Timestamp(System.currentTimeMillis() - (staleMinutes * 60_000L))

        int runResultsReaped = sweepEntity(ec, "darpan.reconciliation.ReconciliationRunResult", cutoff, staleMinutes)
        int executionsReaped = sweepEntity(ec, "darpan.reconciliation.ReconciliationAutomationExecution", cutoff, staleMinutes)

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
            for (EntityValue row : stale) {
                try {
                    row.set("statusEnumId", STATUS_FAILED)
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
                } catch (Throwable t) {
                    logger.warn("StuckRunReaper failed to flip ${entityName} row: ${t.message}")
                }
            }
        } catch (Throwable t) {
            logger.warn("StuckRunReaper ${entityName} sweep failed: ${t.message}")
        }
        return updated
    }
}
