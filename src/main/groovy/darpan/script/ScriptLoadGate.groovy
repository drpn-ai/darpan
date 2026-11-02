package darpan.script

import darpan.admin.SystemLoadReader
import darpan.reconciliation.automation.RunAdmission
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.lang.management.ManagementFactory

/**
 * Tenant scripts run last: a script starts only while Darpan's own work has room. Busy when reconciliation runs are
 * queued or at their cap, when heap after GC is at or above darpan.scripts.holdAtHeapPercent (70, below the 85 runs
 * hold at), or when process CPU is at or above darpan.scripts.holdAtCpuPercent (75). A value of 101 turns a signal off.
 *
 * NOTE: Java thread priority is not used, the JVM ignores it on Linux.
 */
@CompileStatic
class ScriptLoadGate {
    protected final static Logger logger = LoggerFactory.getLogger(ScriptLoadGate.class)

    static final String HEAP_PROPERTY = 'darpan.scripts.holdAtHeapPercent'
    static final String CPU_PROPERTY = 'darpan.scripts.holdAtCpuPercent'
    static final String MAX_WAIT_PROPERTY = 'darpan.scripts.scheduledMaxWaitMinutes'
    static final int DEFAULT_HOLD_AT_HEAP_PERCENT = 70
    static final int DEFAULT_HOLD_AT_CPU_PERCENT = 75
    static final int DEFAULT_SCHEDULED_MAX_WAIT_MINUTES = 60

    /** {runsRunning, runsQueued, runsCap, heapPercent, cpuPercent}, a null value is unknown; replaced in tests */
    static Closure<Map<String, Object>> probe = { -> defaultReadings() }

    static int holdAtHeapPercent() { intProperty(HEAP_PROPERTY, DEFAULT_HOLD_AT_HEAP_PERCENT) }
    static int holdAtCpuPercent() { intProperty(CPU_PROPERTY, DEFAULT_HOLD_AT_CPU_PERCENT) }
    static int scheduledMaxWaitMinutes() { intProperty(MAX_WAIT_PROPERTY, DEFAULT_SCHEDULED_MAX_WAIT_MINUTES) }

    /** {busy, reason, readings}; never throws */
    static Map<String, Object> check() {
        Map<String, Object> r
        try {
            r = probe.call()
        } catch (Throwable t) {
            // a broken reading must not stop every script, so treat it as calm
            logger.warn("Could not read load for script admission, treating as not busy", t)
            r = [:]
        }
        String reason = null
        Integer queued = r.runsQueued as Integer, running = r.runsRunning as Integer, cap = r.runsCap as Integer
        Integer heap = r.heapPercent as Integer, cpu = r.cpuPercent as Integer
        if (queued != null && queued > 0) {
            reason = "${queued} reconciliation run${queued == 1 ? ' is' : 's are'} waiting to start".toString()
        } else if (running != null && cap != null && running >= cap) {
            reason = "reconciliation runs are at their limit (${running} of ${cap})".toString()
        } else if (heap != null && heap >= holdAtHeapPercent()) {
            reason = "memory is ${heap}% used (scripts wait above ${holdAtHeapPercent()}%)".toString()
        } else if (cpu != null && cpu >= holdAtCpuPercent()) {
            reason = "CPU is at ${cpu}% (scripts wait above ${holdAtCpuPercent()}%)".toString()
        }
        return [busy: reason != null, reason: reason, readings: r] as Map<String, Object>
    }

    static String busyMessage(String reason) {
        return "Darpan is busy with its own work (${reason}), and scripts run last. Try again in a minute.".toString()
    }

    private static Map<String, Object> defaultReadings() {
        Map<String, Object> runs = RunAdmission.SHARED.snapshot()
        return [runsRunning: runs.running, runsQueued: runs.queued, runsCap: runs.cap,
                heapPercent: SystemLoadReader.heapAfterGcPercent(), cpuPercent: processCpuPercent()] as Map<String, Object>
    }

    private static Integer processCpuPercent() {
        try {
            def os = ManagementFactory.operatingSystemMXBean
            if (!(os instanceof com.sun.management.OperatingSystemMXBean)) return null
            double load = ((com.sun.management.OperatingSystemMXBean) os).processCpuLoad
            return load < 0 ? null : (int) Math.round(load * 100)
        } catch (Throwable t) {
            logger.debug("Could not read process CPU load", t)
            return null
        }
    }

    private static int intProperty(String name, int fallback) {
        String value = System.getProperty(name)
        return value?.isInteger() ? value.toInteger() : fallback
    }
}
