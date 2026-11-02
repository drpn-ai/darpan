package darpan.script

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-175 POC: the lane every in-process script runs on. */
class ScriptLaneTests {
    @AfterEach
    void reset() { ScriptLane.setEnabled(null) }

    private static String kind(Map out) { (out.error as Map)?.kind as String }

    @Test
    void aScriptRunsOnALaneThreadNotTheCallersAndIsLogged() {
        String caller = Thread.currentThread().name
        Map out = ScriptLane.run('T_LANE_A', 'JEXL', 1_000L) { -> [ok: true, result: Thread.currentThread().name, units: 1L, logs: []] }
        assertTrue(out.ok as boolean)
        assertTrue((out.result as String).startsWith('darpan-script-lane-'), "ran on ${out.result}")
        assertTrue(out.result != caller)
        assertEquals('JEXL', out.engine)
    }

    @Test
    void theOffSwitchRefusesEveryScript() {
        ScriptLane.setEnabled(false)
        Map out = ScriptLane.run('T_LANE_B', 'GROOVY', 1_000L) { -> [ok: true] }
        assertFalse(out.ok as boolean)
        assertEquals('SWITCHED_OFF', kind(out))
        assertFalse(ScriptLane.status().enabled as boolean)
    }

    @Test
    void noTenantNoScript() {
        assertEquals('NO_TENANT', kind(ScriptLane.run(null, 'JEXL', 1_000L) { -> [ok: true] }))
    }

    @Test
    void oneTenantRunsOneScriptAtATime() {
        CountDownLatch inside = new CountDownLatch(1), release = new CountDownLatch(1)
        Thread first = Thread.start {
            ScriptLane.run('T_LANE_C', 'JEXL', 5_000L) { -> inside.countDown(); release.await(5, TimeUnit.SECONDS); [ok: true] }
        }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        Map second = ScriptLane.run('T_LANE_C', 'JEXL', 1_000L) { -> [ok: true] }
        assertEquals('TENANT_BUSY', kind(second))
        Map otherTenant = ScriptLane.run('T_LANE_D', 'JEXL', 1_000L) { -> [ok: true] }
        assertTrue(otherTenant.ok as boolean, 'another tenant is not held up')
        release.countDown(); first.join(5_000L)
        assertTrue(ScriptLane.run('T_LANE_C', 'JEXL', 1_000L) { -> [ok: true] }.ok as boolean, 'free again once it ended')
    }

    @Test
    void aScriptThatIgnoresItsDeadlineIsReportedStuckAndHoldsItsTenantUntilItEnds() {
        // Stand-in for a long library call: busy work that never looks at the interrupt flag.
        Map out = ScriptLane.run('T_LANE_E', 'GROOVY', 100L) { ->
            long until = System.nanoTime() + 2_500_000_000L
            long x = 0L
            while (System.nanoTime() < until) { x++ }
            [ok: true, result: x]
        }
        assertEquals('STUCK', kind(out))
        assertTrue((ScriptLane.status().stuck as int) >= 1, "status: ${ScriptLane.status()}")
        assertEquals('TENANT_BUSY', kind(ScriptLane.run('T_LANE_E', 'GROOVY', 100L) { -> [ok: true] }), 'cannot pile up more')
        long deadline = System.currentTimeMillis() + 10_000L
        while ((ScriptLane.status().stuck as int) > 0 && System.currentTimeMillis() < deadline) Thread.sleep(100L)
        assertEquals(0, ScriptLane.status().stuck as int, 'no longer stuck once it really ended')
        assertTrue(ScriptLane.run('T_LANE_E', 'GROOVY', 1_000L) { -> [ok: true] }.ok as boolean)
    }

    @Test
    void jexlAndGroovyBothRunThroughTheLane() {
        Map jexl = ScriptLane.run('T_LANE_F', 'JEXL', 2_000L) { -> ScriptSupervisor.evaluateHere('SCRIPT', 'var t = 0; for (var i : 1..3) { t = t + i; } t', null) }
        Map groovy = ScriptLane.run('T_LANE_G', 'GROOVY', GroovyGuard.WALL_LIMIT_MS) { -> GroovyGuard.evaluate('(1..3).sum()', null) }
        assertTrue(jexl.ok as boolean, "${jexl.error}"); assertEquals(6, (jexl.result as Number).intValue())
        assertTrue(groovy.ok as boolean, "${groovy.error}"); assertEquals(6, (groovy.result as Number).intValue())
    }
}
