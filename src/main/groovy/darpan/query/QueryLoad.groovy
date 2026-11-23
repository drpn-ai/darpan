package darpan.query

import darpan.reconciliation.automation.RunAdmission
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * DAR-BE-334. Darpan's reason for tenant queries to wait, given to moqui-jexl as jexl_busy_service: reconciliation runs
 * waiting to start, or at their cap. Heap and CPU holds are moqui-jexl's own (jexl_hold_heap_percent / _cpu_percent).
 */
@CompileStatic
class QueryLoad {
    private static final Logger logger = LoggerFactory.getLogger(QueryLoad)

    /** {runsRunning, runsQueued, runsCap}; replaced in tests */
    static final Closure<Map<String, Object>> DEFAULT_PROBE = { -> RunAdmission.SHARED.snapshot() as Map<String, Object> }
    static Closure<Map<String, Object>> probe = DEFAULT_PROBE

    static void resetForTests() { probe = DEFAULT_PROBE }

    /** Why queries must wait right now, or null. */
    static String busyReason() {
        // An instance with its scheduler off (scheduled_job_check_time 0: a rehearsal, or prod before cutover) sends no
        // scheduled query either; moqui-jexl's own scheduler does not read that setting.
        if (org.moqui.util.SystemBinding.getPropOrEnv("scheduled_job_check_time")?.trim() == "0") return "scheduled jobs are switched off on this instance"
        Map<String, Object> runs
        try {
            runs = probe.call()
        } catch (Throwable t) {
            logger.warn("Could not read reconciliation run load for queries, letting them run", t)
            return null
        }
        int queued = (runs?.runsQueued ?: runs?.queued ?: 0) as int
        Integer running = (runs?.runsRunning ?: runs?.running) as Integer
        Integer cap = (runs?.runsCap ?: runs?.cap) as Integer
        if (queued > 0) return "${queued} reconciliation run${queued == 1 ? ' is' : 's are'} waiting to start".toString()
        if (running != null && cap != null && running >= cap) return "reconciliation runs are at their limit (${running} of ${cap})".toString()
        return null
    }
}
