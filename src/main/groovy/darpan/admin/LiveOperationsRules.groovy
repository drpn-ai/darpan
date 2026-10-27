package darpan.admin

import darpan.facade.reconciliation.RunHealth
import groovy.transform.CompileDynamic

import java.sql.Timestamp

/**
 * DAR-ADMIN-008. Every rule about what the admin live board calls a warning, and nowhere else.
 * Pure: plain Maps in, plain Maps out — so each threshold is pinned by LiveOperationsRulesTests without
 * stubbing an entity layer. A run's own health (healthy / slow / stuck) is RunHealth's, shared with the audit page.
 *
 * Warning shape: severity (critical | warning), code, title, detail, plus whatever identifies the
 * subject (tenantLabel, runResultId, automationId) and an optional `since` used for ordering.
 */
@CompileDynamic
class LiveOperationsRules {

    static final long MINUTE_MS = RunHealth.MINUTE_MS

    /** A scan tick is 5 minutes, so lateness under 10 is the scheduler working normally. */
    static final int OVERDUE_WARNING_MINUTES = 10
    static final int OVERDUE_CRITICAL_MINUTES = 30

    static final int HEAP_WARNING_PERCENT = 80
    static final int HEAP_CRITICAL_PERCENT = 90
    static final int CPU_WARNING_PERCENT = 85
    static final int CPU_CRITICAL_PERCENT = 95
    static final int DISK_WARNING_FREE_PERCENT = 15
    static final int DISK_CRITICAL_FREE_PERCENT = 5

    /** One warning per run whose health is not healthy. */
    static List<Map<String, Object>> runWarnings(List<Map> running) {
        return running.findAll { it.health != "healthy" }.collect { Map run ->
            [severity    : run.health == "stuck" ? "critical" : "warning",
             code        : run.health == "stuck" ? "RUN_STUCK" : "RUN_SLOW",
             title       : "${run.name} is ${run.health}".toString(),
             detail      : run.healthReason,
             tenantLabel : run.tenantLabel,
             runResultId : run.runResultId,
             automationId: run.automationId,
             since       : run.startedDate]
        }
    }

    /** Failed runs in the window, one critical warning per tenant + saved run, latest error carried. */
    static List<Map<String, Object>> failureWarnings(List<Map> failures, int windowHours) {
        return failures.groupBy { "${it.tenantUserGroupId}|${it.savedRunId}".toString() }.values().collect { List<Map> group ->
            Map latest = group.max { (it.completedDate as Timestamp)?.time ?: 0L }
            String count = group.size() == 1 ? "1 failure" : "${group.size()} failures"
            [severity   : "critical",
             code       : "RUN_FAILED",
             title      : "${latest.name} failed".toString(),
             detail     : "${count} in ${windowHours}h; latest: ${latest.errorMessage ?: 'no error message recorded'}".toString(),
             tenantLabel: latest.tenantLabel,
             runResultId: latest.runResultId,
             since      : latest.completedDate]
        }
    }

    /** Executions waiting to be retried after a failed attempt. */
    static List<Map<String, Object>> retryWarnings(List<Map> retrying) {
        return retrying.collect { Map execution ->
            String attempt = execution.maxRetryCount != null ?
                    "attempt ${(execution.retryCount as Integer) + 1} of ${(execution.maxRetryCount as Integer) + 1}" :
                    "retry ${execution.retryCount}"
            [severity    : "warning",
             code        : "RUN_RETRYING",
             title       : "${execution.name} is retrying".toString(),
             detail      : "Last attempt failed; ${attempt} pending".toString(),
             tenantLabel : execution.tenantLabel,
             automationId: execution.automationId,
             since       : execution.lastUpdatedDate]
        }
    }

    static List<Map<String, Object>> slotWarnings(int inFlight, int cap) {
        if (cap <= 0 || inFlight < cap) return []
        return [[severity: "warning", code: "SLOTS_AT_CAP",
                 title   : "Automation slots full (${inFlight} of ${cap})".toString(),
                 detail  : "Scheduled runs due now wait for a free slot; see overdue automations."]]
    }

    /**
     * One summary warning for the automations the scheduler is behind on. The cap defers them without
     * enqueuing a PENDING row, so lateness against nextScheduledFireTime is the only queue there is.
     */
    static List<Map<String, Object>> overdueWarnings(List<Map> overdue) {
        List<Map> late = overdue.findAll { (it.lateMs as Long) >= OVERDUE_WARNING_MINUTES * MINUTE_MS }
        if (!late) return []
        Map worst = late.max { it.lateMs as Long }
        boolean critical = (worst.lateMs as Long) >= OVERDUE_CRITICAL_MINUTES * MINUTE_MS
        String subject = late.size() == 1 ? "1 automation" : "${late.size()} automations"
        return [[severity: critical ? "critical" : "warning", code: "AUTOMATIONS_OVERDUE",
                 title   : "${subject} behind schedule".toString(),
                 detail  : "Oldest is ${RunHealth.duration(worst.lateMs as Long)} late: ${worst.name}".toString(),
                 tenantLabel: worst.tenantLabel, automationId: worst.automationId, since: worst.dueAt]]
    }

    /** Null figures (unreadable on this JVM) raise nothing: an unknown is not a pass, but it is not a warning either. */
    static List<Map<String, Object>> systemWarnings(Map system) {
        List<Map<String, Object>> warnings = []
        Integer heap = system?.heapAfterGcPercent as Integer
        if (heap != null && heap >= HEAP_WARNING_PERCENT) {
            warnings << [severity: heap >= HEAP_CRITICAL_PERCENT ? "critical" : "warning", code: "HEAP_HIGH",
                         title   : "Memory ${heap}% used after GC".toString(),
                         detail  : "Live heap the collector could not free; sustained, this ends in OutOfMemoryError."]
        }
        Integer cpu = system?.processCpuPercent as Integer
        if (cpu != null && cpu >= CPU_WARNING_PERCENT) {
            warnings << [severity: cpu >= CPU_CRITICAL_PERCENT ? "critical" : "warning", code: "CPU_HIGH",
                         title   : "CPU ${cpu}% (this JVM)".toString(),
                         detail  : "Instant reading; check the load average beside it before acting."]
        }
        Integer diskFree = system?.diskFreePercent as Integer
        if (diskFree != null && diskFree < DISK_WARNING_FREE_PERCENT) {
            warnings << [severity: diskFree < DISK_CRITICAL_FREE_PERCENT ? "critical" : "warning", code: "DISK_LOW",
                         title   : "Disk ${diskFree}% free on the runtime volume".toString(),
                         detail  : "Run extracts and result files are written here."]
        }
        Map pool = system?.workerPool as Map
        if (pool != null && (pool.max as Integer) > 0 && (pool.active as Integer) >= (pool.max as Integer) && (pool.queued as Integer) > 0) {
            warnings << [severity: "warning", code: "WORKER_POOL_SATURATED",
                         title   : "Worker pool saturated (${pool.active} of ${pool.max} busy, ${pool.queued} queued)".toString(),
                         detail  : "Async services, including the automation scan, wait for a thread."]
        }
        return warnings
    }

    /** Critical before warning; within a severity, oldest first; undated last. */
    static List<Map> sortWarnings(List<Map> warnings) {
        return warnings.sort(false) { Map a, Map b ->
            int bySeverity = severityRank(a.severity as String) <=> severityRank(b.severity as String)
            if (bySeverity != 0) return bySeverity
            Long aSince = (a.since as Timestamp)?.time ?: Long.MAX_VALUE
            Long bSince = (b.since as Timestamp)?.time ?: Long.MAX_VALUE
            return aSince <=> bSince
        }
    }

    private static int severityRank(String severity) { return severity == "critical" ? 0 : 1 }

}
