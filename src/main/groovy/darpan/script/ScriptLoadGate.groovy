package darpan.script

import darpan.admin.SystemLoadReader
import darpan.reconciliation.automation.RunAdmission
import groovy.transform.CompileStatic

import java.lang.management.ManagementFactory

/**
 * DAR-BE-175: tenant scripts take LAST priority. A script starts only while Darpan's core work has room; the bar is
 * lower than the one reconciliation runs use, so scripts back off first.
 *
 * <p>Busy when any of: reconciliation runs are queued, or running at their cap (RunAdmission); old-generation heap
 * after the last GC at or above holdAtHeapPercent (default 70; runs hold at 85); this process's CPU at or above
 * holdAtCpuPercent (default 75). A Run button press is refused with the reason; a scheduled script waits and, if the
 * core stays busy, its run EXPIRES (never forced): see ScriptDeploymentSupport.scanDue.</p>
 *
 * <p>Settings are JVM system properties, like RunAdmission's: darpan.scripts.holdAtHeapPercent,
 * darpan.scripts.holdAtCpuPercent, darpan.scripts.scheduledMaxWaitMinutes. A value of 101 or more switches that
 * signal off. Java thread priority is not used: the JVM ignores it on Linux.</p>
 */
@CompileStatic
class ScriptLoadGate {
    static final String HEAP_PROPERTY = 'darpan.scripts.holdAtHeapPercent'
    static final String CPU_PROPERTY = 'darpan.scripts.holdAtCpuPercent'
    static final String MAX_WAIT_PROPERTY = 'darpan.scripts.scheduledMaxWaitMinutes'
    static final int DEFAULT_HOLD_AT_HEAP_PERCENT = 70
    static final int DEFAULT_HOLD_AT_CPU_PERCENT = 75
    static final int DEFAULT_SCHEDULED_MAX_WAIT_MINUTES = 60

    /** The readings; replaceable in tests. {runsRunning, runsQueued, runsCap, heapPercent, cpuPercent}; null = unknown. */
    static Closure<Map<String, Object>> probe = { -> defaultReadings() }

    static int holdAtHeapPercent() { intProperty(HEAP_PROPERTY, DEFAULT_HOLD_AT_HEAP_PERCENT) }
    static int holdAtCpuPercent() { intProperty(CPU_PROPERTY, DEFAULT_HOLD_AT_CPU_PERCENT) }
    static int scheduledMaxWaitMinutes() { intProperty(MAX_WAIT_PROPERTY, DEFAULT_SCHEDULED_MAX_WAIT_MINUTES) }

    /** {busy, reason, readings}. reason is a short plain sentence when busy. Never throws. */
    static Map<String, Object> check() {
        Map<String, Object> r
        try { r = probe.call() } catch (Throwable ignored) { r = [:] }
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

    /** The message a refused Run button press shows. */
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
        } catch (Throwable ignored) {
            return null
        }
    }

    private static int intProperty(String name, int fallback) {
        String value = System.getProperty(name)
        return value?.isInteger() ? value.toInteger() : fallback
    }
}
