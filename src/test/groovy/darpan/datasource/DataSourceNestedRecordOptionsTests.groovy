package darpan.datasource

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-176 (a read whose records carry nested, built lines, and a caller's switches). On a made-up helpdesk: declared
 * options (query text per value, a field on each record), MATCHES / AFTER_NOW, firstWhere / multiply / sum / daysBetween,
 * many-joins sorted with exactly the declared line fields (an empty list when none), a join that skips the record itself
 * (notOn), child reads by only some records' ids (bind.ids.when), number on an earlier step, and record.keepAlways.
 * No system is named.
 */
class DataSourceNestedRecordOptionsTests {

    // ---------------------------------------------------------------- functions and conditions

    @Test
    void matchesAndAfterNowReadTextAndInstants() {
        assertTrue(RecordConditions.matches([id: "12345"], [field: "id", op: "MATCHES", values: ["\\d+"]]))
        assertFalse(RecordConditions.matches([id: "WARRANTY-1"], [field: "id", op: "MATCHES", values: ["\\d+"]]))
        assertTrue(RecordConditions.matches([id: " 77 "], [field: "id", op: "MATCHES", values: ["\\d+"]]), "trimmed, as ids are")
        assertFalse(RecordConditions.matches([:], [field: "id", op: "MATCHES", values: [".*"]]), "nothing matches no value")
        assertTrue(RecordConditions.matches([thru: "2999-01-01T00:00:00Z"], [field: "thru", op: "AFTER_NOW"]))
        assertFalse(RecordConditions.matches([thru: "2001-01-01T00:00:00Z"], [field: "thru", op: "AFTER_NOW"]))
        assertFalse(RecordConditions.matches([thru: "soon"], [field: "thru", op: "AFTER_NOW"]), "not an instant: not after now")
        assertTrue(RecordConditions.check("t", [field: "id", op: "MATCHES", values: ["(unclosed"]])[0].contains("not a pattern"))
        assertTrue(RecordConditions.check("t", [field: "id", op: "MATCHES", values: ["x" * 201]])[0].contains("200"))
        assertTrue(RecordConditions.check("t", [field: "id", op: "MATCHES", values: ["a", "b"]])[0].contains("one pattern"))
    }

    @Test
    void firstWhereMultiplySumAndDaysBetween() {
        Map r = [ids: [[type: "OLD", value: "9", thru: "2001-01-01T00:00:00Z"], [type: "OLD", value: "WARRANTY-1"],
                       [type: "OLD", value: " 42 "], [type: "NEW", value: "7"]],
                 lines: [[amount: 1.5], [amount: null], [amount: "2"]], price: "2.50", qty: 3, entered: 172_800_000L + 5, made: 0L]
        List active = [[field: "type", op: "IN", values: ["OLD"]], [field: "value", op: "MATCHES", values: ["\\d+"]],
                       [any: [[field: "thru", op: "BLANK"], [field: "thru", op: "AFTER_NOW"]]]]
        assertEquals(" 42 ", DeriveRules.valueOf(r, [fn: "firstWhere", list: "ids", when: active, field: "value"]))
        assertEquals(null, DeriveRules.valueOf(r, [fn: "firstWhere", list: "ids", when: [[field: "type", op: "IN", values: ["NONE"]]], field: "value"]))
        assertEquals(new BigDecimal("7.50"), DeriveRules.valueOf(r, [fn: "multiply", args: [[copy: "price"], [copy: "qty"]]]))
        assertEquals(new BigDecimal("0.00"), DeriveRules.valueOf(r, [fn: "multiply", args: [[copy: "price"], [copy: "missing"]]]), "blank counts as 0 (scale kept, as the coded product)")
        assertEquals(new BigDecimal("3.5"), DeriveRules.valueOf(r, [fn: "sum", args: [[copy: "lines[*].amount"]]]))
        assertEquals(BigDecimal.ZERO, DeriveRules.valueOf([:], [fn: "sum", args: [[copy: "lines[*].amount"]]]))
        assertEquals(2L, DeriveRules.valueOf(r, [fn: "daysBetween", args: [[copy: "entered"], [copy: "made"]]]))
        assertEquals(-1L, DeriveRules.valueOf([a: 0L, b: 5L], [fn: "daysBetween", args: [[copy: "a"], [copy: "b"]]]), "whole days, floored")
        assertEquals(null, DeriveRules.valueOf([a: 0L], [fn: "daysBetween", args: [[copy: "a"], [copy: "b"]]]))
        assertEquals([], DeriveRules.check("t", [[set: "x", value: [fn: "firstWhere", list: "ids", when: active, field: "value"]]]))
        assertTrue(DeriveRules.check("t", [[set: "x", value: [fn: "firstWhere", list: "ids", field: "value"]]])[0].contains("firstWhere needs"))
    }

    @Test
    void aNumberWithAHugeExponentIsNotANumberSoNothingExpandsIt() {
        // Security review: "1e999999999" parses for free, but 1e999999999 + 1, or its plain text, is a billion digits.
        Map r = [a: "1e999999999", b: "1", c: "-1E-999999999"]
        assertEquals(BigDecimal.ONE, DeriveRules.valueOf(r, [fn: "sum", args: [[copy: "a"], [copy: "b"], [copy: "c"]]]))
        assertEquals(BigDecimal.ZERO, DeriveRules.valueOf(r, [fn: "multiply", args: [[copy: "a"], [copy: "b"]]]))
        assertEquals("1e999999999", DeriveRules.valueOf(r, [fn: "decimalPlain", args: [[copy: "a"]]]), "kept as it came")
        assertEquals("1e999999999", DeriveRules.valueOf(r, [fn: "decimal", args: [[copy: "a"]]]))
        assertEquals(new BigDecimal("120.5"), DeriveRules.valueOf([x: "1.205E2"], [fn: "decimalPlain", args: [[copy: "x"]]]), "a small exponent still reads")
    }

    // ---------------------------------------------------------------- a made-up helpdesk

    static final String TICKETS = '''query DeskTickets($q: String, $first: Int, $after: String) {
  tickets(q: $q, first: $first, after: $after) { nodes { no state kind } page { more next } }
}'''
    static final String LINES = '''query DeskLines($q: String, $first: Int, $after: String) {
  lines(q: $q, first: $first, after: $after) { nodes { no seq price qty extra } page { more next } }
}'''
    static final Map<String, List<Map>> ROWS = [
            tickets: [[no: "T1", state: "OPEN", kind: "A"], [no: "T2", state: "CLOSED", kind: "B"], [no: "T3", state: "OPEN", kind: "B"]],
            lines  : [[no: "T1", seq: "02", price: "2.5", qty: 2], [no: "T1", seq: "01", price: "1", qty: null, extra: "x"],
                      [no: "T3", seq: "01", price: "4", qty: 1]],
    ]

    /** Answers `field:a,b` terms (all must hold), paged. */
    static class DeskTransport {
        final List<Map> calls = Collections.synchronizedList(new ArrayList<Map>())

        Map execute(String document, Map variables, int reservation) {
            String root = document.contains("DeskTickets") ? "tickets" : "lines"
            calls << [root: root, q: variables.q]
            List<Map> rows = ROWS[root].findAll { Map row ->
                ((variables.q as String) ?: "").split(" ").findAll { it }.every { String term ->
                    int c = term.indexOf(':')
                    (term.substring(c + 1).split(",") as List).contains(row.get(term.substring(0, c))?.toString())
                }
            }
            int first = (variables.first as Number).intValue()
            int after = variables.after ? (variables.after as String).substring(1) as int : 0
            boolean more = after + first < rows.size()
            return [(root): [nodes: rows.drop(after).take(first).collect { new LinkedHashMap(it) },
                             page: [more: more, next: more ? "c${after + first}".toString() : null]]]
        }
    }

    static Map desk() {
        Map page = [firstVariable: "first", afterVariable: "after", pageSize: 10]
        return [
                connection: [urlTemplate: "{baseUrl}/graphql", auth: [kindField: "authType", kinds: [TOKEN: [method: "BEARER", tokenFields: ["token"]]]]],
                limits: [maxCostPerCall: 100],
                options: [[name: "onlyClosed", type: "BOOLEAN", default: false, field: "optOnlyClosed",
                           text: ["true": "state:CLOSED ", "false": ""]]],
                steps: [
                        [id: "tickets", kind: "query", document: TICKETS, connectionAt: "tickets", recordsAt: "nodes[*]", hasNextAt: "page.more",
                         cursorAt: "page.next", paging: page, reservation: 10,
                         bind: [ids: [variables: [q: "{onlyClosed}no:{ids}"], from: "LOOKUP_KEYS", chunk: 10]]],
                        [id: "notAsked", kind: "drop", name: "notAsked", when: [[field: "optOnlyClosed", op: "IN", values: [true]],
                                                                             [field: "state", op: "NOT_IN", values: ["CLOSED"]]]],
                        [id: "lines", kind: "query", document: LINES, connectionAt: "lines", recordsAt: "nodes[*]", hasNextAt: "page.more",
                         cursorAt: "page.next", paging: page, reservation: 10,
                         bind: [ids: [variables: [q: "no:{ids}"], from: "tickets.no", chunk: 10, when: [[field: "kind", op: "IN", values: ["A"]]]]]],
                        [id: "amounts", kind: "derive", on: "lines", rules: [[set: "lineAmount", value: [fn: "multiply", args: [[copy: "price"], [copy: "qty"]]]]]],
                        [id: "lineRank", kind: "number", on: "lines", field: "lineRank", partitionBy: ["no"], orderBy: [[path: "seq", as: "TEXT"]]],
                        [id: "linesIn", kind: "join", into: "tickets", from: "lines", on: [[into: "no", from: "no"]], many: true, as: "lines",
                         sortBy: [[path: "seq", as: "TEXT"]], fields: ["seq", "lineRank", "lineAmount"], optionalFields: ["extra"], always: true],
                        [id: "peer", kind: "join", into: "tickets", from: "tickets", on: [[into: "kind", from: "kind"]],
                         notOn: [[into: "no", from: "no"]], copy: [peer: "no"]],
                        [id: "totals", kind: "derive", rules: [[set: "total", value: [fn: "sum", args: [[copy: "lines[*].lineAmount"]]]]]],
                ],
                record: [fields: ["no", "state", "total", "lines"], optionalFields: ["peer"], keepRecordFields: true, keepAlways: ["lines"]],
        ]
    }

    private static Map runIt(Map request, DeskTransport t = new DeskTransport(), Map d = desk()) {
        assertEquals([], DataSourceDefinitionSupport.check("GRAPHQL", d))
        List<Map> records = []
        Map r = new DataSourceRunner(d, t).run(request) { List<Map> b -> records.addAll(b) }
        return r + [records: records, transport: t]
    }

    @Test
    void nestedLinesAreSortedPickedAndTotalledAndOnlySomeRecordsChildrenAreRead() {
        Map r = runIt([mode: "ids", keys: ["T1", "T2", "T3"]])
        Map<String, Map> byNo = ((List<Map>) r.records).collectEntries { [(it.no): it] }
        assertEquals([[seq: "01", lineRank: 1, lineAmount: BigDecimal.ZERO, extra: "x"], [seq: "02", lineRank: 2, lineAmount: new BigDecimal("5.0")]],
                byNo.T1.lines)
        assertEquals([], byNo.T2.lines, "no lines read: an empty list, not none")
        assertEquals([], byNo.T3.lines, "kind B: its lines are never asked for")
        assertEquals([new BigDecimal("5.0"), BigDecimal.ZERO], [byNo.T1.total, byNo.T2.total])
        assertEquals([null, "T3", "T2"], [byNo.T1.peer, byNo.T2.peer, byNo.T3.peer], "the same kind, never itself")
        assertEquals(["no:T1,T2,T3", "no:T1"], ((DeskTransport) r.transport).calls*.q)
    }

    @Test
    void anOptionPicksItsQueryTextAndItsFieldDecidesWhatIsKept() {
        Map r = runIt([mode: "ids", keys: ["T1", "T2", "T3"], options: [onlyClosed: true]])
        assertEquals("state:CLOSED no:T1,T2,T3", ((DeskTransport) r.transport).calls[0].q)
        assertEquals(["T2"], ((List<Map>) r.records)*.no)
        IllegalArgumentException bad = assertThrows(IllegalArgumentException) {
            new DataSourceRunner(desk(), new DeskTransport()).run([mode: "ids", keys: ["T1"], options: [onlyClosed: "maybe"]]) { }
        }
        assertTrue(bad.message.contains("onlyClosed is true or false"), bad.message)
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException) {
            new DataSourceRunner(desk(), new DeskTransport()).run([mode: "ids", keys: ["T1"], options: [everything: true]]) { }
        }
        assertTrue(unknown.message.contains("takes no option everything"), unknown.message)
    }

    @Test
    void keepAlwaysKeepsTheLinesWhateverTheRunKeeps() {
        Map r = runIt([mode: "ids", keys: ["T2"], keep: ["no"]])
        assertEquals([[no: "T2", lines: []]], r.records)
    }

    @Test
    void optionsAndTheNewJoinOptionsAreChecked() {
        Map d = desk()
        d.options = [[name: "onlyClosed", type: "TEXT", text: ["yes": "x"]], [name: "a b", type: "BOOLEAN"]]
        d.steps = ((List<Map>) d.steps).collect { Map s ->
            s.id == "linesIn" ? s + [sortBy: [5], fields: ["a b"], always: "yes"] :
            s.id == "peer" ? s + [notOn: [[into: "no"]]] :
            s.id == "lines" ? s + [bind: [ids: ((Map) ((Map) s.bind).ids) + [variables: [q: "{nope}no:{ids}"], when: [[field: "kind", op: "SOMETIMES"]]]]] : s
        }
        List<String> errors = DataSourceDefinitionSupport.check("GRAPHQL", d)
        ["options are BOOLEAN", "text maps true and false", "an option needs a name", "sortBy is a list of {path, as}",
         "fields lists the line fields", "always is true or false", "notOn is a list of {into, from}", "not {nope}", "not 'SOMETIMES'"].each { String w ->
            assertTrue(errors.any { it.contains(w) }, "${w} in ${errors}")
        }
    }
}
