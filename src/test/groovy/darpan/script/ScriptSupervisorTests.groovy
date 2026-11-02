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
 * The tenant script engine, no Moqui boot: what a script can do, what each profile refuses, and the hostile suite
 * (host access, runaway loops, memory growth, giant numbers, ReDoS). ScriptLoadGateTests covers the busy gate.
 */
class ScriptSupervisorTests {

    private static final Map ORDER = [
            orderId : 'GOR-1001',
            placedAt: '2026-10-01T10:00:00Z',
            billedAt: '2026-10-02T16:30:00Z',
            payments: [[tender: 'CARD', amount: '40.00'], [tender: 'GIFT_CARD', amount: 10], [tender: 'CARD', amount: '5.5']],
    ]

    // NOTE: evaluateAdmitted, not evaluate, so the engine is tested without the load gate reading this machine's CPU
    private static Map run(String profile, String body, Object input = ORDER) {
        return ScriptSupervisor.evaluateAdmitted(profile, body, input)
    }

    private static void assertRefused(Map out, String kind) {
        assertFalse(out.ok as boolean, "expected a ${kind} refusal, got ${out}")
        assertEquals(kind, (out.error as Map).kind, "wrong kind: ${out.error}")
    }

    // -- what a tenant can write

    @Test
    void anExpressionReadsTheInputThroughTheLibrary() {
        Map out = run('EXPRESSION', "d:sum(input.payments, 'amount') > d:round('50', 2) && d:hoursBetween(input.placedAt, input.billedAt) <= 36")
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
            { 'orderId': input.orderId, 'paid': d:round(paid, 2), 'tenders': d:join(d:sorted(d:keys(byTender)), '+'), 'byTender': byTender }
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
            [n, size(d:where(input.payments, 'tender', 'CARD')), d:groupCount(input.payments, 'tender'), size(input.orderId)]
        ''')
        assertTrue(out.ok as boolean, "${out}")
        assertEquals([2, 2, [CARD: 2, GIFT_CARD: 1], 8], out.result)
    }

    @Test
    void jexlsOwnBuiltInsCoverWhatTheLibraryLeavesOut() {
        // the d: library has no len, begins, ends, min, max, abs, count or money because these do the same
        Map line = [sku: 'A-1', qty: -3, note: 'abcd']
        [
                "size(input.note)"                                   : 4,
                "size(input.payments)"                               : 3,
                "empty(input.missing)"                               : true,
                "input.line.sku =^ 'A-'"                             : true,
                "input.line.sku =\$ '-1'"                           : true,
                "'CARD' =~ ['CARD', 'CASH']"                         : true,
                "var q = input.line.qty; q < 0 ? -q : q"             : 3,
                "size(d:where(input.payments, 'tender', 'CARD'))"    : 2,
                "d:round('2.499', 2)"                              : new BigDecimal('2.50'),
        ].each { String body, Object expected ->
            Map out = run('SCRIPT', body, ORDER + [line: line, note: 'abcd'])
            assertTrue(out.ok as boolean, "${body} -> ${out}")
            assertEquals(expected, out.result, body)
        }
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
        ScriptPermissions permissions = ScriptPermissions.INSTANCE
        assertFalse(permissions.allow(Runtime))
        assertFalse(permissions.allow(HashMap))
        assertFalse(permissions.allow(String.getMethod('length')))
        assertFalse(permissions.allow(Object.getMethod('getClass')))
        assertFalse(permissions.allow(ScriptLibrary.getMethod('getMetaClass')))
        assertFalse(permissions.allow(ScriptLibrary.getDeclaredConstructor()))
        assertTrue(permissions.allow(ScriptLibrary.getMethod('dec', Object)))
        assertTrue(permissions.allow(ScriptArithmetic.getMethod('forEach', Object)))
        assertTrue(permissions.compose('java.lang.*').is(permissions), 'compose must not widen the set')
    }

    @Test
    void aShiftPastTheNumberCapIsRefusedBeforeItIsBuilt() {
        // BigInteger.shiftLeft would build (or overflow on) a 2^31-bit number before any check after it ran
        assertRefused(run('SCRIPT', 'var big = 1; for (var i : 1..70) { big = big * 2; } big << 2147483647'), 'NUMBER')
        assertRefused(run('EXPRESSION', "1 << '2000000000'"), 'NUMBER')
        // a shift whose low 32 bits are 2^31 - 1 but whose long value is negative: checked as the whole number
        assertRefused(run('EXPRESSION', 'input.big << input.by', [big: new BigInteger('2').pow(70), by: new BigInteger('-4294967296') + 2147483647]), 'NUMBER')
        assertRefused(run('EXPRESSION', 'input.big << input.by', [big: new BigInteger('2').pow(70), by: new BigInteger('18446744073709551616') + 2000000000]), 'NUMBER')
        assertTrue(run('EXPRESSION', '1024 << -10').ok as boolean, 'a small negative shift is in range (Java long rules apply)')
        assertEquals(1024L, run('EXPRESSION', '1 << 10').result as long)
        // the amount is bounded by its type before it is converted: 1E999999999 as a BigInteger is a billion digits
        [new BigDecimal('1E999999999'), new BigDecimal('7.' + '1' * 100), new BigInteger('2').pow(4000), 1e300d, Double.NaN, '99999999999', [1]].each { Object amount ->
            try {
                ScriptArithmetic.shiftAmount(amount)
                assertTrue(false, "${amount} should be refused")
            } catch (ScriptBudget.LimitReached expected) {
                assertEquals('NUMBER', expected.kind)
            }
        }
        assertEquals(-3, ScriptArithmetic.shiftAmount(new BigDecimal('-3.7')))
        assertEquals(0, ScriptArithmetic.shiftAmount(new BigDecimal('1E-999999999')), 'under one is zero, never divided out')
        assertEquals(12, ScriptArithmetic.shiftAmount(' 12 '))
        assertEquals(256, ScriptArithmetic.shiftAmount(256L))
    }

    @Test
    void aHugeExponentLiteralIsRefusedBeforeTheScriptRuns() {
        // 1E999999999B passes no input cap, and a failing script's source is rendered through DecimalFormat in full
        ['1E999999999B + 1', '1E-999999999B - 1', '1E999999999B % 7', '1E5000B', 'var x = 2.5E+0012345B; input.nope.nope'].each { String body ->
            Map out = run('SCRIPT', body)
            assertRefused(out, 'NOT_ALLOWED')
            assertEquals(1, (out.error as Map).line)
        }
        assertTrue(run('EXPRESSION', '1E300B * 2 > 1E299B').ok as boolean, 'an exponent up to 1000 stays available')
        assertTrue(run('EXPRESSION', "size('4E1200 is a product id')").ok as boolean, 'text that looks like a number is only text')
        assertTrue(run('EXPRESSION', '1e5000 > 1').ok as boolean, 'a double literal past its range is Infinity, nothing is built')
        // an input integer past the number cap is refused before an operator builds on it
        assertRefused(run('EXPRESSION', 'input.x + 1', [x: new BigInteger('2').pow(300)]), 'NUMBER')
        assertEquals(new BigDecimal('2.50'), run('EXPRESSION', '1.25B * 2').result)
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

    // -- hostile suite: caps hold at the peak allocation, not after it

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
        assertRefused(run('SCRIPT', 'size(d:distinct(1..100000000))'), 'MEMORY')
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
        // a regex literal holding a quote cannot hide a template from the check
        assertRefused(run('SCRIPT', 'var r = ~/\'/;\nvar s = \'x\';\n`${s}${s}`'), 'NOT_ALLOWED')
        assertRefused(run('SCRIPT', "'a ` in text'"), 'NOT_ALLOWED')
    }

    // -- hostile suite: work that hides inside one operation

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

    // -- a tenant's question over a run result: units backordered per SKU (NetSuite sales orders)

    static final String BACKORDERS_BY_SKU = '''
        var bySku = {:};
        for (var d : input.differences) {
            for (var line : d.record.data.itemsBackordered ?? []) {
                var row = bySku[line.sku] ?? {'sku': line.sku, 'units': 0, 'lines': 0};
                row.units = row.units + d:dec(line.quantity);
                row.lines = row.lines + 1;
                bySku[line.sku] = row;
            }
        }
        { 'skus': size(bySku), 'units': d:sum(d:vals(bySku), 'units'), 'bySku': d:sortBy(d:vals(bySku), '-units') }
    '''

    @Test
    void backordersPerSkuFromNetSuiteSalesOrderDifferences() {
        // Rows as the result file holds them: the order's fields are JSON text in record.data.
        List raw = [
                [record: [id: '1', data: '{"order":"SO1","itemsBackordered":[{"quantity":"1","sku":"A:S"}]}']],
                [record: [id: '2', data: '{"order":"SO2","itemsBackordered":[{"quantity":"2","sku":"A:S"},{"quantity":"1","sku":"B:M"}]}']],
                [record: [id: '3', data: '{"order":"SO3","itemsBackordered":[]}']],
                [record: [id: '4', data: '{"order":"SO4"}']],
        ]
        List differences = raw.collect { TenantScriptSupport.withParsedData(it) }
        assertTrue(((differences[0] as Map).record as Map).data instanceof Map, 'record.data reaches the script parsed')

        Map out = run('SCRIPT', BACKORDERS_BY_SKU, [differences: differences])
        assertTrue(out.ok as boolean, "${out}")
        Map result = out.result as Map
        assertEquals(2, result.skus)
        assertEquals(new BigDecimal('4'), result.units)
        assertEquals([[sku: 'A:S', units: new BigDecimal('3'), lines: 2], [sku: 'B:M', units: new BigDecimal('1'), lines: 1]], result.bySku)
    }

    @Test
    void sortByTakesAMoquiOrderByWithinBounds() {
        Map out = run('EXPRESSION', "d:pluck(d:sortBy([{'k': 'b', 'n': 2}, {'k': 'a', 'n': 10}, {'k': 'c', 'n': 1}], '-n'), 'k')")
        assertEquals(['a', 'b', 'c'], out.result)
        assertEquals(['a', 'b', 'c'], run('EXPRESSION', "d:pluck(d:sortBy([{'k': 'c'}, {'k': 'a'}, {'k': 'b'}], 'k'), 'k')").result)
        assertFalse(run('EXPRESSION', "d:sortBy([1, 2], 'k')").ok as boolean, 'sortBy takes maps')
        // Moqui's comparator is unmetered, so the order-by is bounded before it sorts
        assertFalse(run('EXPRESSION', "d:sortBy([{'k': 1}], '" + (['k'] * 6).join(',') + "')").ok as boolean, 'at most 5 fields')
        assertFalse(run('EXPRESSION', "d:sortBy([{'k': 1}], '-')").ok as boolean, 'a bare prefix is not a field')
        assertEquals(['a', 'c', 'b'], run('EXPRESSION', "d:pluck(d:sortBy([{'k': 'b', 'n': 1}, {'k': 'c', 'n': 2}, {'k': 'a', 'n': 2}], '-n,k'), 'k')").result)
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
        assertEquals([], offenders, 'jexl3 may be referenced only from darpan.script')
    }
}
