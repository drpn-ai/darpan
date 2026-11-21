package darpan.script

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull

class ScriptLoadGateResetForTestsTests {
    @Test
    void resetForTestsReplacesASwappedProbeWithTheRealOneAndIsIdempotent() {
        ScriptLoadGate.probe = { -> [runsQueued: 3] as Map<String, Object> }
        assertEquals("3 reconciliation runs are waiting to start", ScriptLoadGate.busyReason())
        Closure swapped = ScriptLoadGate.probe
        ScriptLoadGate.resetForTests()
        assertNotNull(ScriptLoadGate.probe)
        assertEquals(false, ScriptLoadGate.probe.is(swapped))
        // the real probe reports no queued runs for the swapped fake's "3 waiting" reason
        Map<String, Object> load = ScriptLoadGate.probe.call()
        assertEquals(true, load.containsKey("runsQueued") && load.containsKey("cpuPercent"))
        ScriptLoadGate.resetForTests()
        assertNotNull(ScriptLoadGate.probe)
        assertEquals(true, ScriptLoadGate.probe.call().containsKey("runsCap"))
    }
}
