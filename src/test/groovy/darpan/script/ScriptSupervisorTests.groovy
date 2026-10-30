package darpan.script

import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-164. The tenant script sandbox, no Moqui boot: what a script can do, what each profile refuses, and the
 * hostile suite (sandbox design §8) — host access, runaway loops, memory growth, giant numbers, ReDoS.
 */
class ScriptSupervisorTests {

    private static final Map ORDER = [
            orderId : 'GOR-1001',
            placedAt: '2026-10-01T10:00:00Z',
            billedAt: '2026-10-02T16:30:00Z',
            payments: [[tender: 'CARD', amount: '40.00'], [tender: 'GIFT_CARD', amount: 10], [tender: 'CARD', amount: '5.5']],
    ]

    private static Map run(String profile, String body, Object input = ORDER) {
        return ScriptSupervisor.evaluate(profile, body, input)
    }

    private static void assertRefused(Map out, String kind) {
        assertFalse(out.ok as boolean, "expected a ${kind} refusal, got ${out}")
        assertEquals(kind, (out.error as Map).kind, "wrong kind: ${out.error}")
    }

    // -- what a tenant can write

    @Test
    void anExpressionReadsTheInputThroughTheLibrary() {
        Map out = run('EXPRESSION', "d:sum(input.payments, 'amount') > d:money('50') && d:hoursBetween(input.placedAt, input.billedAt) <= 36")
        assertTrue(out.ok as boolean, "${out}")
        assertEquals(true, out.result)
        assertTrue((out.units as long) > 0)
    }

    @Test
    void aScriptLoopsKeepsStateAndReturnsAMap() {
        String body = '''
            var paid = 0;
            var byTender = {:};
            for (var p : input.payments) {
                paid = paid + d:dec(p.amount);
                byTender[p.tender] = d:dec(byTender[p.tender] ?? 0) + d:dec(p.amount);
            }
            d:log('paid ' + paid);
            { 'orderId': input.orderId, 'paid': d:money(paid), 'tenders': d:join(d:sorted(d:keys(byTender)), '+'), 'byTender': byTender }
        '''
        Map out = run('SCRIPT', body)
        assertTrue(out.ok as boolean, "${out}")
        Map result = out.result as Map
        assertEquals('GOR-1001', result.orderId)
        assertEquals(new BigDecimal('55.50'), result.paid)
        assertEquals('CARD+GIFT_CARD', result.tenders)
        assertEquals(new BigDecimal('45.50'), (result.byTender as Map).CARD)
        assertEquals(['paid 55.50'], out.logs)
    }

    @Test
    void aScriptCanUseLambdasAndTheListHelpers() {
        Map out = run('SCRIPT', '''
            var big = (p) -> d:dec(p.amount) >= 10;
            var n = 0;
            for (var p : input.payments) { if (big(p)) { n = n + 1 } }
            [n, d:count(input.payments, 'tender', 'CARD'), d:groupCount(input.payments, 'tender'), d:len(input.orderId)]
        ''')
        assertTrue(out.ok as boolean, "${out}")
        assertEquals([2, 2, [CARD: 2, GIFT_CARD: 1], 8], out.result)
    }

    @Test
    void theSameInputAlwaysGivesTheSameResult() {
        String body = "var t = 0; for (var p : input.payments) { t = t + d:dec(p.amount) } t"
        assertEquals(run('SCRIPT', body).result, run('SCRIPT', body).result)
    }

    @Test
    void theInputIsReadOnlyAllTheWayDownAndALocalCopyIsNot() {
        Map input = [items: [[qty: 1]]]
        assertRefused(run('SCRIPT', "input.items[0].qty = 99; input.items[0].qty", input), 'NOT_ALLOWED')
        Map out = run('SCRIPT', "var row = {'qty': input.items[0].qty}; row.qty = row.qty + 98; row.qty", input)
        assertTrue(out.ok as boolean, "${out}")
        assertEquals(99, out.result)
        assertEquals(1, ((input.items as List)[0] as Map).qty)
    }

    @Test
    void aRuntimeErrorNamesTheLine() {
        Map out = run('SCRIPT', "var a = 1;\nvar b = d:dec('not a number');\nb")
        assertFalse(out.ok as boolean)
        assertEquals('ERROR', (out.error as Map).kind)
        assertTrue((out.error as Map).message.toString().contains('not a number'), "${out.error}")
        assertEquals(2, (out.error as Map).line)
    }

    // -- profiles

    @Test
    void theExpressionProfileRefusesStatementsLoopsLambdasAndVariables() {
        assertRefused(run('EXPRESSION', 'for (var p : input.payments) { 1 }'), 'NOT_ALLOWED')
        assertRefused(run('EXPRESSION', 'var x = 1'), 'NOT_ALLOWED')
        assertRefused(run('EXPRESSION', '(x) -> x'), 'NOT_ALLOWED')
        assertRefused(run('EXPRESSION', 'while (true) { }'), 'NOT_ALLOWED')
    }

    @Test
    void anUnknownProfileOrEmptyOrOversizedSourceIsRefusedBeforeRunning() {
        assertRefused(run('GROOVY', '1'), 'INVALID')
        assertRefused(run('SCRIPT', '   '), 'INVALID')
        assertRefused(run('EXPRESSION', '1 + ' * 1000 + '1'), 'INVALID')
    }

    @Test
    void validateListsTheNamesAndRefusesAnythingButInput() {
        Map ok = ScriptSupervisor.validate('SCRIPT', 'var x = input.a; x + 1')
        assertTrue(ok.ok as boolean, "${ok}")
        assertEquals(['input'], ok.variables)
        Map unknown = ScriptSupervisor.validate('SCRIPT', 'secret + input.a')
        assertRefused(unknown, 'ACCESS')
        assertTrue((unknown.error as Map).message.toString().contains("'secret'"))
    }

    // -- hostile suite: host access

    @Test
    void noMethodCallsNoConstructorsNoPragmas() {
        assertRefused(run('SCRIPT', 'input.getClass()'), 'NOT_ALLOWED')
        assertRefused(run('SCRIPT', "''.class.forName('java.lang.Runtime')"), 'NOT_ALLOWED')
        assertRefused(run('SCRIPT', "input.orderId.length()"), 'NOT_ALLOWED')
        assertRefused(run('SCRIPT', "new('java.lang.ProcessBuilder', 'id')"), 'NOT_ALLOWED')
        assertRefused(run('SCRIPT', "#pragma jexl.namespace.rt java.lang.Runtime\n1"), 'NOT_ALLOWED')
        assertRefused(run('SCRIPT', "#pragma jexl.import java.lang\n1"), 'NOT_ALLOWED')
    }

    @Test
    void dotClassReachesNoJavaClass() {
        // On a map, .class is just a key that is not there.
        Map onMap = run('SCRIPT', 'input.class')
        assertTrue(onMap.ok as boolean, "${onMap}")
        assertNull(onMap.result)
        ["'abc'.class", 'var n = 1; n.class', '(x -> x).class', 'input.payments.class', 'input.class.name'].each { String body ->
            Map out = run('SCRIPT', body)
            assertFalse(out.ok as boolean, "${body} must fail, got ${out}")
        }
    }

    @Test
    void theInputCannotBeReassigned() {
        assertRefused(run('SCRIPT', 'input = 5'), 'NOT_ALLOWED')
    }

    @Test
    void groovyObjectMethodsOnTheLibraryAreUnreachable() {
        assertFalse(run('SCRIPT', "d:getMetaClass()").ok as boolean)
        assertFalse(run('SCRIPT', "d:invokeMethod('dec', ['1'])").ok as boolean)
        assertFalse(run('SCRIPT', "d:asList(input.payments, 'x')").ok as boolean)
    }

    @Test
    void permissionsAdmitOnlyTheLibraryAndTheArithmeticOverloads() {
        ScriptPermissions p = ScriptPermissions.INSTANCE
        assertFalse(p.allow(Runtime))
        assertFalse(p.allow(HashMap))
        assertFalse(p.allow(String.getMethod('length')))
        assertFalse(p.allow(Object.getMethod('getClass')))
        assertFalse(p.allow(ScriptLibrary.getMethod('getMetaClass')))
        assertFalse(p.allow(ScriptLibrary.getDeclaredConstructor()))
        assertTrue(p.allow(ScriptLibrary.getMethod('dec', Object)))
        assertTrue(p.allow(ScriptArithmetic.getMethod('forEach', Object)))
        assertTrue(p.compose('java.lang.*').is(p), 'compose must not widen the set')
    }

    @Test
    void textPatternMatchingIsRefused() {
        assertRefused(run('SCRIPT', "'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!' =~ '(a+)+b'"), 'ACCESS')
        Map inList = run('EXPRESSION', "'CARD' =~ ['CARD', 'CASH']")
        assertTrue(inList.ok as boolean, "membership in a list stays available: ${inList}")
    }

    // -- hostile suite: runaway work and memory

    @Test
    void anEmptyEndlessLoopRunsOutOfUnits() {
        assertRefused(run('SCRIPT', 'while (true) { }'), 'UNITS')
    }

    @Test
    void aHugeRangeRunsOutOfUnits() {
        assertRefused(run('SCRIPT', 'for (var i : 1..100000000) { }'), 'UNITS')
    }

    @Test
    void endlessRecursionIsStopped() {
        Map out = run('SCRIPT', 'var f = (n) -> f(n + 1); f(0)')
        assertFalse(out.ok as boolean, "${out}")
    }

    @Test
    void doublingTextHitsTheTextCap() {
        assertRefused(run('SCRIPT', "var s = 'x'; while (true) { s = s + s }"), 'MEMORY')
    }

    @Test
    void squaringANumberHitsTheNumberCap() {
        assertRefused(run('SCRIPT', 'var n = 3; while (true) { n = n * n }'), 'NUMBER')
    }

    @Test
    void growingAMapHitsTheCollectionCap() {
        assertRefused(run('SCRIPT', 'var m = {:}; for (var i : 1..20000) { m[i] = i } m'), 'MEMORY')
    }

    @Test
    void returningAFunctionIsNotData() {
        assertRefused(run('SCRIPT', 'var f = (x) -> x; f'), 'OUTPUT')
    }

    @Test
    void anOversizedResultIsRefused() {
        Map out = run('SCRIPT', "var m = {:}; for (var i : 1..5000) { m[i] = 'xxxxxxxxxxxxxxxxxxxx' } m")
        assertRefused(out, 'MEMORY')
    }

    // -- hostile suite: caps hold at the peak allocation (security review of 52334af)

    @Test
    void replaceIsRefusedBeforeItBuildsAnOversizedText() {
        Map input = [t: 'a' * 1000, big: 'x' * 1000]
        assertRefused(run('SCRIPT', "d:replace(input.t, 'a', input.big)", input), 'MEMORY')
    }

    @Test
    void joinIsRefusedBeforeItBuildsAnOversizedText() {
        Map input = [parts: (1..100).collect { 'y' * 1000 }]
        assertRefused(run('SCRIPT', "d:join(input.parts, '')", input), 'MEMORY')
        assertFalse(run('SCRIPT', "d:join([input.parts], ',')", input).ok as boolean, 'join refuses a list of lists')
    }

    @Test
    void selfAddDoublingHitsTheTextCap() {
        assertRefused(run('SCRIPT', "var s = 'x'; while (true) { s += s }"), 'MEMORY')
    }

    @Test
    void textCannotBeJoinedWithACollection() {
        assertRefused(run('SCRIPT', "'paid: ' + input.payments"), 'ACCESS')
        Map ok = run('SCRIPT', "'paid: ' + d:str(input.payments[0])")
        assertTrue(ok.ok as boolean, "${ok}")
        assertTrue(ok.result.toString().startsWith('paid: {tender: CARD'), "${ok.result}")
    }

    @Test
    void aHugeRangeIsNeverCopiedIntoMemory() {
        assertRefused(run('SCRIPT', 'd:sum(1..100000000)'), 'MEMORY')
        assertRefused(run('SCRIPT', 'd:len(d:distinct(1..100000000))'), 'MEMORY')
    }

    @Test
    void aHugeRangeReturnedAsTheResultIsRefusedInsideTheBudget() {
        assertRefused(run('SCRIPT', '1..100000000'), 'MEMORY')
        assertRefused(run('EXPRESSION', '1..100000000'), 'MEMORY')
    }

    @Test
    void anyBacktickIsRefusedWithItsLine() {
        Map out = run('SCRIPT', 'var s = \'x\';\nvar t = `${s}${s}`;\nt')
        assertRefused(out, 'NOT_ALLOWED')
        assertEquals(2, (out.error as Map).line)
        // A regex literal holding a quote cannot hide a template from the check (second security review).
        assertRefused(run('SCRIPT', 'var r = ~/\'/;\nvar s = \'x\';\n`${s}${s}`'), 'NOT_ALLOWED')
        assertRefused(run('SCRIPT', "'a ` in text'"), 'NOT_ALLOWED')
    }

    // -- hostile suite: second security review of e5ad242

    @Test
    void deepEqualityIsChargedAtEveryDepth() {
        List rows = (1..100).collect { int i -> (1..100).collectEntries { int j -> ["k${j}".toString(), i * j] } }
        Map out = run('SCRIPT', 'input.a == input.b', [a: rows, b: rows.collect { new LinkedHashMap(it) }])
        assertTrue(out.ok as boolean, "${out}")
        assertEquals(true, out.result)
        assertTrue((out.units as long) >= 10_000, "a 10,000-value comparison must cost at least 10,000 units, cost ${out.units}")
        assertEquals(false, run('SCRIPT', 'input.a == input.b', [a: [[x: 1]], b: [[x: 2]]]).result)
    }

    @Test
    void aHugeExponentDecimalNeverPrintsItsDigits() {
        assertRefused(run('SCRIPT', 'd:str(input.x)', [x: new BigDecimal('1E999999999')]), 'INPUT')
        assertRefused(run('SCRIPT', "d:str(d:dec('1E999999999'))"), 'NUMBER')
        assertRefused(run('SCRIPT', "d:dec('1E900') * d:dec('1E900')"), 'NUMBER')
        Map fine = run('SCRIPT', "d:str(d:dec('1E20'))")
        assertTrue(fine.ok as boolean, "${fine}")
        assertEquals('100000000000000000000', fine.result)
    }

    @Test
    void oneSharedInputStringCannotInflateTheResult() {
        Map out = run('SCRIPT', "{'a': input.big, 'b': input.big, 'c': input.big}", [big: 'z' * 30_000])
        assertRefused(out, 'MEMORY')
        assertTrue((out.error as Map).message.toString().contains('characters of JSON'), "${out.error}")
    }

    @Test
    void aRangeWhoseSizeOverflowsIsStillCapped() {
        assertRefused(run('SCRIPT', 'd:sum(1..3000000000)'), 'MEMORY')
        assertRefused(run('SCRIPT', '1..3000000000'), 'MEMORY')
    }

    @Test
    void logRendersAMapWithoutItsToString() {
        Map out = run('SCRIPT', 'd:log(input.payments); 1')
        assertTrue(out.ok as boolean, "${out}")
        assertTrue((out.logs as List)[0].toString().startsWith('[{tender: CARD, amount: 40.00}'), "${out.logs}")
    }

    // -- the choke point

    @Test
    void onlyDarpanScriptReferencesJexl() {
        Path main = Paths.get('src/main/groovy')
        List<String> offenders = []
        Files.walk(main).filter { Path f -> f.toString().endsWith('.groovy') || f.toString().endsWith('.java') }.each { Path f ->
            if (Files.readString(f).contains('org.apache.commons.jexl3') && !main.relativize(f).toString().startsWith('darpan/script/')) {
                offenders << main.relativize(f).toString()
            }
        }
        assertEquals([], offenders, 'jexl3 may be referenced only from darpan.script (sandbox design §12)')
    }
}
