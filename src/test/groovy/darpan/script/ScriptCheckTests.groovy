package darpan.script

import groovy.json.JsonOutput
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/** The trial run on the sample input that save#TenantScript does before it saves a script. */
class ScriptCheckTests {
    private static final String ORDERS = '{"orders": [{"orderId": "SO-1", "lines": [{"sku": "A", "ordered": 5, "shipped": 3}]}]}'

    private static Map check(String body, String sample = ORDERS) { ScriptSupervisor.check('SCRIPT', body, sample) }

    private static Map problem(Map out) {
        assertFalse(out.ok as boolean, "expected a refusal, got ${out}")
        return (out.problems as List<Map>)[0]
    }

    @Test
    void aGoodScriptPasses() {
        Map out = check('''var owed = 0;
for (var o : input.orders) { for (var l : o.lines) { owed = owed + (l.ordered - l.shipped); } }
owed''')
        assertTrue(out.ok as boolean)
        assertEquals([], out.problems)
        assertEquals([], out.notes)
    }

    @Test
    void aMisspeltFieldIsRefusedWithItsLineAndTheRecordsFields() {
        Map found = problem(check('''var t = 0;
for (var o : input.orders) {
  for (var l : o.lines) { t = t + (l.ordred - l.shipped); }
}
t'''))
        assertEquals('FAILS_ON_SAMPLE', found.rule)
        assertEquals(3, found.line)
        assertTrue((found.message as String).contains("'ordred' is not a field") && (found.message as String).contains('ordered'), "${found}")
    }

    @Test
    void aLoopThatNeverEndsIsRefusedWithTheUsualCause() {
        Map found = problem(check('''var i = 0;
var t = 0;
while (i < size(input.orders)) { t = t + 1; }
t'''))
        assertEquals('FAILS_ON_SAMPLE', found.rule)
        assertEquals(3, found.line)
        assertTrue((found.message as String).contains('A loop that never ends'), "${found}")
    }

    @Test
    void textThatKeepsGrowingIsRefusedWithTheUsualCause() {
        Map found = problem(check('''var s = 'xxxxxxxxxx';
for (var i : 1..30) { s = s + s; }
s'''))
        assertEquals('FAILS_ON_SAMPLE', found.rule)
        assertTrue((found.message as String).contains('keeps growing'), "${found}")
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
    void aSampleThatIsNotJsonIsRefused() {
        assertEquals('BAD_SAMPLE', problem(check('1', '{oops')).rule)
    }

    @Test
    void withoutASampleNothingRuns() {
        assertTrue(check('input.orders[0].ordred', null).ok as boolean)
    }

    @Test
    void whatATrialRunCannotSeeIsSaved() {
        // the accepted trade-off: valid JEXL that runs cleanly on the sample, so nothing stops it
        assertTrue(check('var x = 1; if (x = 2) { 1 } else { 0 }').ok as boolean, '= in a condition')
        assertTrue(check('input.orders[0].ordred').ok as boolean, 'a misspelt field that is only returned reads as empty')
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
