package darpan.script

import darpan.admin.SystemLoadReader
import darpan.reconciliation.automation.RunAdmission
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Tenant scripts run last: one starts only while reconciliation runs have room, heap after GC is under
 * darpan.scripts.holdAtHeapPercent (70) and process CPU is under darpan.scripts.holdAtCpuPercent (75). Over 100 turns one off.
 */
@CompileStatic
class ScriptLoadGate {
    protected final static Logger logger = LoggerFactory.getLogger(ScriptLoadGate.class)

    // NOTE: live heap reads ~90% on a healthy JVM just before a young GC, so it is used only while heap after GC is unknown
    static final int LIVE_HEAP_PERCENT = 85

    /** {runsRunning, runsQueued, runsCap, heapPercent, liveHeapPercent, cpuPercent}, null when unknown; replaced in tests */
    static Closure<Map<String, Object>> probe = { -> readLoad() }

    static int holdAtHeapPercent() { Integer.getInteger('darpan.scripts.holdAtHeapPercent', 70) }
    static int holdAtCpuPercent() { Integer.getInteger('darpan.scripts.holdAtCpuPercent', 75) }
    static int scheduledMaxWaitMinutes() { Integer.getInteger('darpan.scripts.scheduledMaxWaitMinutes', 60) }

    /** Why scripts must wait right now, or null when Darpan has room for one. */
    static String busyReason() {
        Map<String, Object> load
        try {
            load = probe.call()
        } catch (Throwable t) {
            logger.warn("Could not read load for tenant scripts, letting them run", t)
            return null
        }
        int queued = (load.runsQueued ?: 0) as int
        Integer running = load.runsRunning as Integer, cap = load.runsCap as Integer
        Integer heap = load.heapPercent as Integer, liveHeap = load.liveHeapPercent as Integer, cpu = load.cpuPercent as Integer

        if (queued > 0) return "${queued} reconciliation run${queued == 1 ? ' is' : 's are'} waiting to start".toString()
        if (running != null && cap != null && running >= cap) return "reconciliation runs are at their limit (${running} of ${cap})".toString()
        if (holds(heap, holdAtHeapPercent())) return "memory is ${heap}% used".toString()
        if (heap == null && holds(liveHeap, Math.max(holdAtHeapPercent(), LIVE_HEAP_PERCENT))) return "memory is ${liveHeap}% used".toString()
        if (holds(cpu, holdAtCpuPercent())) return "CPU is at ${cpu}%".toString()
        return null
    }

    // NOTE: a threshold over 100 is off, process CPU can read over 100% (seen at 140% on macOS)
    private static boolean holds(Integer reading, int holdAt) { reading != null && holdAt <= 100 && reading >= holdAt }

    static String busyMessage(String reason) { "Darpan is busy with its own work (${reason}), and scripts run last. Try again in a minute.".toString() }

    private static Map<String, Object> readLoad() {
        Map<String, Object> runs = RunAdmission.SHARED.snapshot()
        Runtime runtime = Runtime.runtime
        int liveHeapPercent = (int) Math.round(100d * (runtime.totalMemory() - runtime.freeMemory()) / runtime.maxMemory())
        return [runsRunning: runs.running, runsQueued: runs.queued, runsCap: runs.cap, heapPercent: SystemLoadReader.heapAfterGcPercent(),
                liveHeapPercent: liveHeapPercent, cpuPercent: SystemLoadReader.processCpuPercent()] as Map<String, Object>
    }
}
