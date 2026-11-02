package darpan.script

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Two traps a genuine user fell into: list + list returned "[I@6eeb90e5..." as a success, and a misspelt field gave
 * "null operand: NullOperand". + on a list now joins or appends, and the error names the field, its line and the
 * fields the record does have.
 */
class ScriptUserMistakesTests {
    private static final Map ORDERS = [orders: [[orderId: 'SO-1', lines: [[sku: 'A', ordered: 5, shipped: 3, price: 2.5]]]]]

    private static Map jexl(String body, Object input = null) { ScriptSupervisor.evaluateAdmitted('SCRIPT', body, input) }

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
}
