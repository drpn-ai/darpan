package darpan.script

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-175 POC: a tenant's Groovy script inside Moqui, guarded for genuine users. */
class GroovyGuardTests {
    private static final Map INPUT = [lines: [[sku: 'MUG-01', ordered: 5, shipped: 3], [sku: 'TEE-02', ordered: 4, shipped: 4],
                                              [sku: 'CAP-03', ordered: 7, shipped: 2]]]

    private static String kind(Map out) { (out.error as Map)?.kind as String }

    @Test
    void anOrdinaryScriptRunsWithInputTheDLibraryClosuresAndALog() {
        Map out = GroovyGuard.evaluate('''
            def owed = { line -> Math.max(0, line.ordered - line.shipped) }
            def total = input.lines.sum { owed(it) }
            d.log("lines checked: ${input.lines.size()}")
            [total: total, money: d.money(12.345), top: input.lines.sort { -it.ordered }.collect { it.sku }]
        ''', INPUT)
        assertTrue(out.ok as boolean, "${out.error}")
        assertEquals(7, (out.result as Map).total)
        assertEquals(12.35, ((out.result as Map).money as BigDecimal).doubleValue(), 0.0001)
        assertEquals(['CAP-03', 'MUG-01', 'TEE-02'], (out.result as Map).top)
        assertEquals(['lines checked: 3'], out.logs)
        assertTrue((out.units as long) > 0L)
    }

    @Test
    void anEndlessLoopIsStoppedByTheInjectedCheckpoint() {
        Map out = GroovyGuard.evaluate('while (true) { }', null)
        assertFalse(out.ok as boolean)
        assertTrue(kind(out) in ['UNITS', 'TIME', 'MEMORY'], "${out.error}")
        assertTrue((out.elapsedMs as long) < 5_000L, "stopped in ${out.elapsedMs} ms")
    }

    @Test
    void catchingTheLimitInsideTheScriptDoesNotKeepItRunning() {
        Map out = GroovyGuard.evaluate('while (true) { try { while (true) { } } catch (Throwable t) { } }', null)
        assertFalse(out.ok as boolean)
        assertTrue(kind(out) in ['UNITS', 'TIME', 'MEMORY'], "${out.error}")
    }

    @Test
    void aListThatKeepsGrowingIsStoppedByTheAllocationMeter() {
        Map out = GroovyGuard.evaluate('def l = []; while (true) { l << new byte[100_000] }', null)
        assertFalse(out.ok as boolean)
        assertEquals('MEMORY', kind(out), "${out.error}")
    }

    @Test
    void dangerousCallsAreRefusedBeforeTheScriptRuns() {
        ['System.exit(0)', '"ls /".execute().text', 'sleep(60_000)', 'input.getClass().classLoader',
         'import java.io.File\nnew File("/etc/passwd").text', 'new java.io.File("/etc/passwd").text',
         'Thread.start { }', 'def m() { 1 }; m()', 'this.binding.variables', 'evaluate("1 + 1")'].each { String body ->
            Map out = GroovyGuard.evaluate(body, INPUT)
            assertFalse(out.ok as boolean, "should be refused: ${body}")
            assertEquals('NOT_ALLOWED', kind(out), "${body} -> ${out.error}")
            assertEquals(0L, out.units as long, "${body} never ran")
        }
    }

    @Test
    void theInputIsACopyAndTheResultMustBePlainAndSmall() {
        Map input = [n: 1]
        Map changed = GroovyGuard.evaluate('input.n = 99; input.n', input)
        assertTrue(changed.ok as boolean, "${changed.error}")
        assertEquals(1, input.n, 'the caller\'s input is untouched')
        Map big = GroovyGuard.evaluate('(1..20_000).collect { "row-$it" }', null)
        assertFalse(big.ok as boolean)
        assertEquals('SIZE', kind(big), "${big.error}")
    }

    // A difference from JEXL, recorded rather than hidden: JEXL caps every list at 10,000 entries in its arithmetic
    // layer, and the d: library only counts collections that are not already Lists. A Groovy range IS a List, so
    // d.sorted(1..50_000) builds 50,000 entries; only the output cap (and, for bigger work, the allocation meter) stops it.
    @Test
    void groovyListsAreNotCappedLikeJexlOnlyTheOutputAndAllocationLimitsApply() {
        Map out = GroovyGuard.evaluate('d.sorted(1..50_000)', null)
        assertFalse(out.ok as boolean)
        assertEquals('SIZE', kind(out), "${out.error}")
        Map jexl = ScriptSupervisor.evaluateHere('SCRIPT', 'd:sorted(1..50000)', null)
        assertFalse(jexl.ok as boolean)
        assertEquals('MEMORY', (jexl.error as Map).kind, "JEXL stops it while building: ${jexl.error}")
    }

    @Test
    void editedScriptsDoNotGrowTheCompileCachePastItsBound() {
        (1..GroovyGuard.CACHE_SIZE + 20).each { int i -> assertTrue(GroovyGuard.evaluate("${i} + 1", null).ok as boolean) }
        assertTrue(GroovyGuard.cachedScripts() <= GroovyGuard.CACHE_SIZE, "cache holds ${GroovyGuard.cachedScripts()}")
    }
}
