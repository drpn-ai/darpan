package darpan.script

import groovy.json.JsonOutput
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/** The trial run on the sample input that save#TenantScript does before it saves a script. */
class ScriptCheckTests {
    private static final String ORDERS = '{"orders": [{"orderId": "SO-1", "lines": [{"sku": "A", "ordered": 5, "shipped": 3}]}]}'

    private static Map check(String body, String sample = ORDERS) { ScriptSupervisor.check('SCRIPT', body, sample) }

    private static Map problem(Map out) {
        assertEquals(false, out.ok, "${out}")
        return (out.problems as List<Map>)[0]
    }

    @Test
    void aGoodScriptPasses() {
        Map out = check('var owed = 0;\nfor (var o : input.orders) { for (var l : o.lines) { owed = owed + (l.ordered - l.shipped); } }\nowed')
        assertTrue(out.ok as boolean, "${out.problems}")
    }

    @Test
    void aMisspeltFieldIsRefusedWithItsLineAndTheRecordsFields() {
        Map found = problem(check('var t = 0;\nfor (var o : input.orders) {\n  for (var l : o.lines) { t = t + (l.ordred - l.shipped); }\n}\nt'))
        assertEquals('FAILS_ON_SAMPLE', found.rule)
        assertEquals(3, found.line)
        assertTrue((found.message as String).contains("'ordred' is not a field") && (found.message as String).contains('ordered'), "${found}")
    }

    @Test
    void aLoopThatNeverEndsIsRefusedWithTheUsualCause() {
        Map found = problem(check('var i = 0;\nvar t = 0;\nwhile (i < size(input.orders)) { t = t + 1; }\nt'))
        assertEquals('FAILS_ON_SAMPLE', found.rule)
        assertTrue((found.message as String).contains('A loop that never ends'), "${found}")
    }

    @Test
    void aMisspeltLibraryFunctionIsRefused() {
        Map found = problem(check("d:uper('abc')"))
        assertEquals('FAILS_ON_SAMPLE', found.rule)
        assertTrue((found.message as String).contains("'uper' is not a Darpan function"), "${found}")
    }

    @Test
    void aScriptThatSpendsMostOfItsBudgetOnTheSampleIsRefused() {
        // a naive nested scan, 500 payments x 500 orders: fine on a few rows, too heavy for real data
        String sample = JsonOutput.toJson([orders: (1..500).collect { [orderId: "SO-${it}"] }, payments: (1..500).collect { [orderId: "SO-${it}"] }])
        Map found = problem(check('''var matched = 0;
for (var p : input.payments) {
  for (var o : input.orders) { if (o.orderId == p.orderId) { matched = matched + 1; } }
}
matched''', sample))
        assertEquals('TOO_HEAVY', found.rule)
        assertTrue((found.message as String).contains('map keyed by id'), "${found}")
    }

    @Test
    void aBadSampleIsRefusedAndNoSampleRunsNothing() {
        assertEquals('BAD_SAMPLE', problem(check('1', '{oops')).rule)
        assertTrue(check('input.orders[0].ordred', null).ok as boolean)
    }

    @Test
    void theTrialRunIsSkippedNotRefusedWhileDarpanIsBusy() {
        Closure original = ScriptLoadGate.probe
        ScriptLoadGate.probe = { -> [runsQueued: 2] as Map<String, Object> }
        try {
            Map out = check('size(input.orders)')
            assertTrue(out.ok as boolean, "${out.problems}")
            assertTrue((out.notes as List)[0].toString().contains('busy'), "${out.notes}")
        } finally {
            ScriptLoadGate.probe = original
        }
    }
}
