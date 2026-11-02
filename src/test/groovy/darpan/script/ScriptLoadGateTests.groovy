package darpan.script

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/** Tenant scripts take last priority behind Darpan's own work. */
class ScriptLoadGateTests {
    private static final Map CALM = [runsQueued: 0, runsRunning: 1, runsCap: 8, heapPercent: 40, liveHeapPercent: 60, cpuPercent: 30]
    private final Closure original = ScriptLoadGate.probe

    @AfterEach
    void restore() {
        ScriptLoadGate.probe = original
        holdAt(101, 101)   // as build.gradle sets for tests
    }

    private static void holdAt(int heapPercent, int cpuPercent) {
        System.setProperty('darpan.scripts.holdAtHeapPercent', heapPercent.toString())
        System.setProperty('darpan.scripts.holdAtCpuPercent', cpuPercent.toString())
    }

    private static void load(Map readings) { ScriptLoadGate.probe = { -> readings as Map<String, Object> } }

    @Test
    void eachCoreSignalMakesScriptsWait() {
        holdAt(70, 75)
        load(CALM)
        assertNull(ScriptLoadGate.busyReason())
        [[runsQueued: 3]: 'waiting to start', [runsRunning: 8]: 'at their limit', [heapPercent: 72]: 'memory is 72%',
         [cpuPercent: 90]: 'CPU is at 90%'].each { Map change, String said ->
            load(CALM + change)
            assertTrue(String.valueOf(ScriptLoadGate.busyReason()).contains(said), "${change}")
        }
    }

    @Test
    void liveHeapStandsInOnlyWhileHeapAfterGcIsUnknown() {
        holdAt(70, 75)
        load(CALM + [heapPercent: null, liveHeapPercent: 84])
        assertNull(ScriptLoadGate.busyReason())
        load(CALM + [heapPercent: null, liveHeapPercent: 85])
        assertEquals('memory is 85% used', ScriptLoadGate.busyReason())
        load(CALM + [liveHeapPercent: 92])
        assertNull(ScriptLoadGate.busyReason(), 'a young-GC spike in live heap does not hold scripts')
    }

    @Test
    void oneHundredOneSwitchesASignalOff() {
        load(CALM + [heapPercent: null, liveHeapPercent: 99, cpuPercent: 140])
        holdAt(101, 101)
        assertNull(ScriptLoadGate.busyReason())
        holdAt(101, 95)
        assertEquals('CPU is at 140%', ScriptLoadGate.busyReason())
    }

    @Test
    void aBrokenReadingLetsScriptsRun() {
        ScriptLoadGate.probe = { -> throw new IllegalStateException('no MXBean') }
        assertNull(ScriptLoadGate.busyReason())
    }

    @Test
    void theRunButtonIsRefusedWhileBusyButAClaimedScheduledRunIsNot() {
        load([runsQueued: 2])
        Map out = ScriptSupervisor.evaluate('EXPRESSION', '1 + 1', null)
        assertEquals('BUSY', (out.error as Map).kind)
        assertTrue(((out.error as Map).message as String).contains('scripts run last'), "${out.error}")
        assertEquals(0L, out.units as long, 'not run at all')
        assertTrue(ScriptSupervisor.evaluateAdmitted('EXPRESSION', '1 + 1', null).ok as boolean)
    }
}
