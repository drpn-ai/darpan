package darpan.script

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-175: tenant scripts take last priority behind Darpan's core work. */
class ScriptLoadGateTests {
    private final Closure original = ScriptLoadGate.probe

    @AfterEach
    void restore() {
        ScriptLoadGate.probe = original
        [ScriptLoadGate.HEAP_PROPERTY, ScriptLoadGate.CPU_PROPERTY].each { System.setProperty(it, '101') }   // as build.gradle sets for tests
        ScriptLane.setEnabled(null)
    }

    private static void readings(Map r) { ScriptLoadGate.probe = { -> r as Map<String, Object> } }

    @Test
    void calmCoreLetsScriptsRun() {
        System.setProperty(ScriptLoadGate.HEAP_PROPERTY, '70'); System.setProperty(ScriptLoadGate.CPU_PROPERTY, '75')
        readings([runsQueued: 0, runsRunning: 2, runsCap: 8, heapPercent: 40, cpuPercent: 30])
        assertFalse(ScriptLoadGate.check().busy as boolean)
    }

    @Test
    void eachCoreSignalMakesScriptsWait() {
        System.setProperty(ScriptLoadGate.HEAP_PROPERTY, '70'); System.setProperty(ScriptLoadGate.CPU_PROPERTY, '75')
        Map calm = [runsQueued: 0, runsRunning: 1, runsCap: 8, heapPercent: 40, cpuPercent: 30]
        [[runsQueued: 3]: 'waiting to start', [runsRunning: 8]: 'at their limit', [heapPercent: 72]: 'memory is 72%',
         [cpuPercent: 90]: 'CPU is at 90%'].each { Map change, String said ->
            readings(calm + change)
            Map gate = ScriptLoadGate.check()
            assertTrue(gate.busy as boolean, "${change}")
            assertTrue((gate.reason as String).contains(said), "${change} -> ${gate.reason}")
        }
    }

    @Test
    void scriptsBackOffBeforeRunsDo() {
        assertTrue(ScriptLoadGate.DEFAULT_HOLD_AT_HEAP_PERCENT < 85, 'runs hold at 85% heap; scripts must hold earlier')
    }

    @Test
    void settingsAreSystemPropertiesAnd101SwitchesASignalOff() {
        readings([runsQueued: 0, runsRunning: 0, runsCap: 8, heapPercent: 95, cpuPercent: 99])
        System.setProperty(ScriptLoadGate.HEAP_PROPERTY, '101'); System.setProperty(ScriptLoadGate.CPU_PROPERTY, '101')
        assertFalse(ScriptLoadGate.check().busy as boolean)
        System.setProperty(ScriptLoadGate.CPU_PROPERTY, '95')
        assertTrue(ScriptLoadGate.check().busy as boolean)
    }

    @Test
    void liveHeapBacksUpAnUnknownHeapAfterGc() {
        System.setProperty(ScriptLoadGate.HEAP_PROPERTY, '70')
        Map calm = [runsQueued: 0, runsRunning: 1, runsCap: 8, heapPercent: null, cpuPercent: 30]
        readings(calm + [liveHeapPercent: 84])
        assertFalse((Boolean) ScriptLoadGate.check().busy)
        readings(calm + [liveHeapPercent: 85])
        assertTrue((Boolean) ScriptLoadGate.check().busy)
        assertTrue(ScriptLoadGate.check().reason.toString().contains('85%'))
        // a known after-GC reading wins: a young-GC spike in live heap does not hold scripts
        readings(calm + [heapPercent: 40, liveHeapPercent: 92])
        assertFalse((Boolean) ScriptLoadGate.check().busy)
    }

    @Test
    void aBrokenReadingDoesNotStopScripts() {
        ScriptLoadGate.probe = { -> throw new IllegalStateException('no MXBean') }
        assertFalse(ScriptLoadGate.check().busy as boolean)
    }

    @Test
    void theRunButtonIsRefusedWithThePlainReasonWhileBusy() {
        readings([runsQueued: 2])
        Map out = ScriptSupervisor.evaluate('EXPRESSION', '1 + 1', null)
        assertFalse(out.ok as boolean)
        assertEquals('BUSY', (out.error as Map).kind)
        assertTrue(((out.error as Map).message as String).contains('scripts run last'), "${out.error}")
        assertEquals(0L, out.units as long, 'not run at all')
        Map admitted = ScriptSupervisor.evaluateAdmitted('EXPRESSION', '1 + 1', null)
        assertTrue(admitted.ok as boolean, 'an already-admitted (scheduled) run is not refused a second time')
    }

    @Test
    void theLaneRefusesWhileBusy() {
        readings([runsQueued: 1])
        Map out = ScriptLane.run('T_GATE', 'JEXL', 1_000L) { -> [ok: true] }
        assertEquals('BUSY', (out.error as Map).kind)
    }
}
