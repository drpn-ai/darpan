package darpan.script

import groovy.json.JsonOutput
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-175: the mistakes genuine users made (2026-11-02), and other common ones, refused before a script is saved. */
class ScriptCheckerTests {
    private static final String ORDERS = JsonOutput.toJson([orders: [[orderId: 'SO-1', lines: [[sku: 'A', ordered: 5, shipped: 3, price: 2.5]]],
                                                                     [orderId: 'SO-2', lines: [[sku: 'B', ordered: 2, shipped: 2, price: 4.0]]]]])

    private static Map check(String body, String sample = ORDERS, String profile = 'SCRIPT') { ScriptChecker.check(profile, body, sample) }
    private static List<String> rules(Map out) { (out.problems as List<Map>)*.rule }
    private static Map first(Map out) { (out.problems as List<Map>)[0] }

    // -- genuine scripts pass

    @Test
    void aGoodScriptPasses() {
        Map out = check('''var owed = {:};
for (var o : input.orders) {
  for (var l : o.lines) {
    var gap = l.ordered - l.shipped;
    if (gap > 0) { owed[l.sku] = (owed[l.sku] ?: 0) + gap; }
  }
}
var rows = [];
for (var sku : d:keys(owed)) { rows += {'sku': sku, 'units': owed[sku]}; }
d:sortBy(rows, 'units', 'desc')''')
        assertTrue(out.ok as boolean, "${out.problems}")
    }

    @Test
    void wordsInsideTextAndCommentsAreNotCode() {
        Map out = check("// while (true) {} and l.ordred\nvar note = 'see l.ordred, while (true) {}'; note")
        assertTrue(out.ok as boolean, "${out.problems}")
    }

    // -- loops

    @Test
    void whileTrueWithoutBreakIsRefused() {
        Map out = check('var t = 0; while (true) { t = t + 1; } t', null)
        assertEquals(['ENDLESS_LOOP'], rules(out))
        assertEquals(1, first(out).line)
        assertTrue(check('var t = 0; while (true) { t = t + 1; if (t > 3) { break; } } t', null).ok as boolean)
    }

    @Test
    void aLoopThatForgetsToCountUpIsRefusedBeforeItRuns() {
        Map out = check('var i = 0;\nvar t = 0;\nwhile (i < size(input.orders)) { t = t + 1; }\nt')
        assertEquals('NO_PROGRESS', first(out).rule)
        assertEquals(3, first(out).line)
        assertTrue((first(out).message as String).contains("'i'"), "${first(out)}")
        ['i = i + 1;', 'i += 1;', 'i++;'].each { String step ->
            assertTrue(check("var i = 0; var t = 0; while (i < size(input.orders)) { t = t + 1; ${step} } t").ok as boolean, step)
        }
    }

    // -- names

    @Test
    void aMisspeltFieldIsRefusedWithASuggestion() {
        Map out = check('var t = 0;\nfor (var o : input.orders) {\n  for (var l : o.lines) { t = t + (l.ordred - l.shipped); }\n}\nt')
        Map p = first(out)
        assertEquals('UNKNOWN_FIELD', p.rule)
        assertEquals(3, p.line)
        assertTrue((p.message as String).contains("'ordred'") && (p.message as String).contains("'ordered'"), "${p}")
    }

    @Test
    void optionalFieldsAndFieldsTheScriptMakesAreFine() {
        assertTrue(check("var l = input.orders[0].lines[0];\n(l.discount ?? 0) + l.ordered").ok as boolean)
        assertTrue(check("var l = input.orders[0].lines[0];\nl?.discount ?? 0").ok as boolean)
        assertTrue(check("var r = {'units': 1, 'total': 2}; r.units + r.total").ok as boolean)
        // In JEXL an unquoted map key is a VARIABLE, not a name: {total: 2} reads an undeclared 'total'. Refused.
        assertFalse(check("var r = {'units': 1, total: 2}; r.units").ok as boolean)
        assertTrue(check("var r = {:}; r.flag = true; r.flag").ok as boolean)
    }

    @Test
    void withoutASampleFieldsAreNotGuessed() {
        assertTrue(check('input.orders[0].ordred', null).ok as boolean)
    }

    @Test
    void aMisspeltLibraryFunctionIsRefusedWithASuggestion() {
        Map out = check("d:uper('abc')", null)
        assertEquals('UNKNOWN_FUNCTION', first(out).rule)
        assertTrue((first(out).message as String).contains('d:upper'), "${first(out)}")
    }

    @Test
    void assigningInsideAnIfIsRefused() {
        Map out = check('var x = 1; if (x = 2) { 1 } else { 0 }', null)
        assertEquals('ASSIGN_IN_CONDITION', first(out).rule)
        assertTrue(check('var x = 1; if (x == 2) { 1 } else { 0 }', null).ok as boolean)
        assertTrue(check('var x = 1; if (x <= 2) { 1 } else { 0 }', null).ok as boolean)
    }

    // -- the trial run on the sample

    @Test
    void aScriptThatFailsOnItsOwnSampleIsRefused() {
        Map out = check("var s = 'xxxxxxxxxx';\nfor (var i : 1..30) { s = s + s; }\ns")
        Map p = first(out)
        assertEquals('FAILS_ON_SAMPLE', p.rule)
        assertTrue((p.message as String).contains('MEMORY'), "${p}")
    }

    @Test
    void aScriptThatSpendsMostOfItsBudgetOnTheSampleIsRefused() {
        // A naive nested scan: 500 payments x 500 orders on the sample. Fine-looking code, too heavy for real data.
        String sample = JsonOutput.toJson([orders: (1..500).collect { [orderId: "SO-${it}"] }, payments: (1..500).collect { [orderId: "SO-${it}"] }])
        Map out = check('''var matched = 0;
for (var p : input.payments) {
  for (var o : input.orders) { if (o.orderId == p.orderId) { matched = matched + 1; } }
}
matched''', sample)
        assertEquals('TOO_HEAVY', first(out).rule, "${out.problems}")
        assertTrue((first(out).message as String).contains('map'), "${first(out)}")
    }

    @Test
    void theTrialRunIsSkippedNotRefusedWhileDarpanIsBusy() {
        Closure original = ScriptLoadGate.probe
        ScriptLoadGate.probe = { -> [runsQueued: 2] as Map<String, Object> }
        try {
            Map out = check('var x = input.orders; size(x)')
            assertTrue(out.ok as boolean, "${out.problems}")
            assertTrue((out.notes as List).any { (it as String).contains('busy') }, "${out.notes}")
        } finally {
            ScriptLoadGate.probe = original
        }
    }

    // Security review of 49dd046: the checker runs on a request thread, outside any script budget. A worst-case script
    // (60,000 characters of unknown field reads, many while loops) against a wide sample must stay quick and bounded.
    @Test
    void aPathologicalScriptIsCheckedQuicklyAndReportsAreCapped() {
        StringBuilder body = new StringBuilder('var x = input;\n')
        int n = 0
        while (body.length() < 58_000) { body.append("x.zz${n} + x.qq${n}\n"); if (n % 40 == 0) body.append('while (x) { x }\n'); n++ }
        Map wide = [rows: (1..4_000).collect { int i -> [("k${i}".toString()): i, ("zz${i}x".toString()): i] }]
        long started = System.nanoTime()
        Map out = ScriptChecker.check('SCRIPT', body.toString(), JsonOutput.toJson(wide))
        long ms = (System.nanoTime() - started).intdiv(1_000_000L)
        assertTrue(ms < 2_000L, "checked in ${ms} ms")
        assertFalse(out.ok as boolean)
        assertTrue((out.problems as List).size() <= ScriptChecker.MAX_PROBLEMS, "${(out.problems as List).size()} problems")
        assertTrue(rules(out).count { it == 'UNKNOWN_FIELD' } <= ScriptChecker.MAX_FIELD_REPORTS)
    }

    @Test
    void severalProblemsAreAllReported() {
        Map out = check("var i = 0; while (i < 3) { d:lowr('a'); }\ni", null)
        assertFalse(out.ok as boolean)
        assertTrue(rules(out).containsAll(['NO_PROGRESS', 'UNKNOWN_FUNCTION']), "${rules(out)}")
    }
}
