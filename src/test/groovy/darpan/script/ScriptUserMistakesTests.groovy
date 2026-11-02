package darpan.script

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-175: two traps a genuine user fell into (genuine-user runs, 2026-11-02).
 * (1) JEXL list + list / list += x returned "[I@6eeb90e5..." as a SUCCESS: + on a list now joins or appends, counted
 *     and capped like any list. (2) A misspelt field gave "null operand: NullOperand" (JEXL) or "minus() on null
 *     object" (Groovy): the error now names the field, the line, and what the record does have.
 */
class ScriptUserMistakesTests {
    private static final Map ORDERS = [orders: [[orderId: 'SO-1', lines: [[sku: 'A', ordered: 5, shipped: 3, price: 2.5]]]]]

    private static Map jexl(String body, Object input = null) { ScriptSupervisor.evaluateHere('SCRIPT', body, input) }

    // -- (1) lists and +

    @Test
    void listPlusListJoinsTheLists() {
        Map out = jexl('var l = [1]; l = l + [2, 3]; l')
        assertTrue(out.ok as boolean, "${out.error}")
        assertEquals([1, 2, 3], (out.result as List).collect { (it as Number).intValue() })
    }

    @Test
    void plusEqualsAppendsAnItemIncludingText() {
        Map out = jexl("var l = []; l += 'a'; l += 'b'; l")
        assertTrue(out.ok as boolean, "${out.error}")
        assertEquals(['a', 'b'], out.result)
        Map maps = jexl("var rows = []; rows += {'sku': 'A'}; rows += {'sku': 'B'}; size(rows)")
        assertTrue(maps.ok as boolean, "${maps.error}")
        assertEquals(2, (maps.result as Number).intValue())
    }

    @Test
    void aListBuiltWithPlusIsStillCountedAndCapped() {
        Map out = jexl('var l = []; for (var i : 1..20000) { l += i; } size(l)')
        assertFalse(out.ok as boolean)
        assertEquals('MEMORY', (out.error as Map).kind, "${out.error}")
    }

    @Test
    void appendingInALoopStaysCheapLikeAGenuineUserWouldWriteIt() {
        // 6,000 appends (the genuine-user order-line count) must not cost a fresh copy each time.
        Map out = jexl('var rows = []; for (var i : 1..6000) { rows += i; } size(rows)')
        assertTrue(out.ok as boolean, "${out.error}")
        assertEquals(6000, (out.result as Number).intValue())
        assertTrue((out.units as long) < 50_000L, "units ${out.units}")
    }

    @Test
    void appendingToAListFromTheInputLeavesTheInputAlone() {
        Map input = [lines: [1, 2]]
        Map out = jexl('var l = input.lines; l += 3; [size(l), size(input.lines)]', input)
        assertTrue(out.ok as boolean, "${out.error}")
        assertEquals([3, 2], (out.result as List).collect { (it as Number).intValue() })
    }

    @Test
    void plusNeverTurnsAListIntoTextAnyMore() {
        ['var l = [1]; l = l + [2]; l', 'var l = [1]; l += 2; l', "var l = ['x']; l + 'y'"].each { String body ->
            Map out = jexl(body)
            assertFalse(String.valueOf(out.result).contains('@'), "${body} -> ${out.result}")
        }
    }

    @Test
    void plusOnAMapIsRefusedWithAClearMessage() {
        Map out = jexl("var m = {'a': 1}; m + {'b': 2}")
        assertFalse(out.ok as boolean)
        assertEquals('ACCESS', (out.error as Map).kind)
        assertTrue(((out.error as Map).message as String).contains('map'), "${out.error}")
        Map num = jexl('1 + [2]')
        assertFalse(num.ok as boolean, 'a number plus a list is not a list operation')
        assertTrue(((num.error as Map).message as String).contains('list first'), "${num.error}")
    }

    @Test
    void numbersAndTextStillAddAsBefore() {
        assertEquals(3, (jexl('1 + 2').result as Number).intValue())
        assertEquals('ab', jexl("'a' + 'b'").result)
        assertEquals('n=1', jexl("'n=' + 1").result)
    }

    // -- (2) a misspelt field

    @Test
    void jexlNamesTheMisspeltFieldItsLineAndTheRecordsFields() {
        Map out = jexl('var t = 0;\nfor (var o : input.orders) {\n  for (var l : o.lines) { t = t + (l.ordred - l.shipped); }\n}\nt', ORDERS)
        assertFalse(out.ok as boolean)
        Map error = out.error as Map
        String message = error.message as String
        assertEquals('ERROR', error.kind)
        assertEquals('ordred', error.field)
        assertTrue(message.contains("'ordred'") && message.contains('line 3'), message)
        assertTrue(message.contains('ordered') && message.contains('shipped'), "lists the record's fields: ${message}")
        assertFalse(message.contains('NullOperand'), message)
    }

    @Test
    void jexlExplainsAnEmptyValueThatIsNotAMisspelling() {
        Map out = jexl('var x = null;\nx + 1')
        assertFalse(out.ok as boolean)
        String message = (out.error as Map).message as String
        assertTrue(message.contains('empty') && message.contains('line 2'), message)
        assertFalse(message.contains('NullOperand'), message)
    }

    @Test
    void readingAnOptionalFieldWithADefaultStillWorks() {
        Map out = jexl("var l = input.orders[0].lines[0]; (l.discount ?? 0) + l.ordered", ORDERS)
        assertTrue(out.ok as boolean, "${out.error}")
        assertEquals(5, (out.result as Number).intValue())
    }

    @Test
    void groovyNamesTheMisspeltFieldAndItsLine() {
        Map out = GroovyGuard.evaluate('def lines = input.orders.collectMany { it.lines }\nlines.sum { it.ordred - it.shipped }', ORDERS)
        assertFalse(out.ok as boolean)
        Map error = out.error as Map
        String message = error.message as String
        assertEquals('ERROR', error.kind)
        assertEquals('ordred', error.field)
        assertTrue(message.contains("'ordred'") && message.contains('line 2'), message)
        assertFalse(message.contains('NullPointerException'), message)
    }

    // Security review of dd60a7c: the field hint must not walk what the script built. A script that grows its input
    // copy and then hits a null must get the hint from the caller's untouched input, with bounded work.
    @Test
    void theFieldHintReadsTheOriginalInputNotWhatTheScriptGrew() {
        long started = System.nanoTime()
        Map out = GroovyGuard.evaluate('input.junk = (1..200_000).collect { [k: it] }\ninput.orders[0].lines[0].ordred - 1', ORDERS)
        long ms = (System.nanoTime() - started).intdiv(1_000_000L)
        assertFalse(out.ok as boolean)
        Map error = out.error as Map
        assertTrue(error.kind in ['ERROR', 'MEMORY'], "${error}")
        if (error.kind == 'ERROR') {
            assertEquals('ordred', error.field, "${error}")
            assertFalse((error.message as String).contains(', k,') || (error.message as String).contains('junk'), 'keys come from the original input')
        }
        assertTrue(ms < 5_000L, "took ${ms} ms")
    }

    @Test
    void theInputWalkIsBoundedByEntriesAndSafeOnCycles() {
        Map cyclic = [a: 1]; cyclic.self = cyclic
        Map wide = [rows: (1..300_000).collect { [k: it] }]
        long started = System.nanoTime()
        assertEquals(['a', 'self'] as Set, GroovyGuard.inputKeys(cyclic))
        assertTrue(GroovyGuard.inputKeys(wide).contains('rows'))
        assertTrue((System.nanoTime() - started).intdiv(1_000_000L) < 2_000L)
    }

    @Test
    void groovyExplainsAnEmptyValueThatIsNotAMisspelling() {
        Map out = GroovyGuard.evaluate('def x = null\nx + 1', null)
        assertFalse(out.ok as boolean)
        String message = (out.error as Map).message as String
        assertTrue(message.contains('empty') && message.contains('line 2'), message)
    }
}
