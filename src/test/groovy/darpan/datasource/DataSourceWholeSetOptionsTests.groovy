package darpan.datasource

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-176 (a whole-catalogue read as data). The options such a read needs, on made-up systems: bind.all (read every
 * record, no window, no ids) on the first step and on later ones, wholeSet (every step sees the whole read as one batch,
 * capped by limits.maxRecordsInMemory), joins that take only some joined records (fromWhen) and the last match, number's
 * group size and collected values, and the without / list-flattening join functions. No system is named.
 */
class DataSourceWholeSetOptionsTests {

    // ---------------------------------------------------------------- a made-up catalogue

    static final String ITEMS = '''query CatItems($filter: String, $first: Int, $after: String) {
  items(filter: $filter, first: $first, after: $after) { nodes { sku kind } page { more next } }
}'''
    static final String TAGS = '''query CatTags($filter: String!, $first: Int, $after: String) {
  tags(filter: $filter, first: $first, after: $after) { nodes { sku code ended } page { more next } }
}'''
    static final List<Map> ITEM_ROWS = [[sku: "D", kind: "X"], [sku: "A", kind: "X"], [sku: "B", kind: "X"], [sku: "C", kind: "Y"]]
    static final List<Map> TAG_ROWS = [
            [sku: "A", code: "1", ended: null], [sku: "A", code: "2", ended: null],   // the last one counts
            [sku: "B", code: "9", ended: "2026-01-01"],                               // ended: not taken
            [sku: "B", code: "", ended: null],                                         // blank: not taken
            [sku: "D", code: "2", ended: null],
    ]

    static class CatalogueTransport {
        final List<Map> calls = Collections.synchronizedList(new ArrayList<Map>())

        Map execute(String document, Map variables, int reservation) {
            boolean items = document.contains("CatItems")
            calls << [op: items ? "items" : "tags", filter: variables.filter, first: variables.first]
            List<Map> rows = items ? ITEM_ROWS : TAG_ROWS
            int first = (variables.first as Number).intValue()
            int after = variables.after ? (variables.after as String).substring(1) as int : 0
            List<Map> page = rows.drop(after).take(first).collect { new LinkedHashMap(it) }
            boolean more = after + first < rows.size()
            return [(items ? "items" : "tags"): [nodes: page, page: [more: more, next: more ? "c${after + first}".toString() : null]]]
        }
    }

    static Map catalogue() {
        Map page = [firstVariable: "first", afterVariable: "after", pageSize: 2]
        return [
                connection: [urlTemplate: "{baseUrl}/graphql", auth: [kindField: "authType", kinds: [TOKEN: [method: "BEARER", tokenFields: ["token"]]]]],
                limits: [maxCostPerCall: 100],
                wholeSet: true,
                steps: [
                        [id: "items", kind: "query", document: ITEMS, connectionAt: "items", recordsAt: "nodes[*]", hasNextAt: "page.more",
                         cursorAt: "page.next", bind: [all: [variables: [filter: "everything"]]], paging: page, reservation: 10],
                        [id: "tags", kind: "query", document: TAGS, connectionAt: "tags", recordsAt: "nodes[*]", hasNextAt: "page.more",
                         cursorAt: "page.next", bind: [all: [variables: [filter: "type:CODE"]]], paging: page, reservation: 10],
                        [id: "code", kind: "join", into: "items", from: "tags", on: [[into: "sku", from: "sku"]], copy: [code: "code"],
                         fromWhen: [[field: "ended", op: "BLANK"], [field: "code", op: "NOT_BLANK"]], last: true],
                        [id: "rank", kind: "number", field: "rank", partitionBy: ["code"], orderBy: [[path: "sku", as: "TEXT"]],
                         sizeField: "groupSize", collect: [field: "sku", as: "group"]],
                        [id: "facts", kind: "derive", rules: [
                                [set: "others", value: [fn: "join", sep: ", ", args: [[fn: "without", args: [[copy: "group"], [copy: "sku"]]]]]]]],
                ],
                record: [fields: ["sku", "code", "rank", "groupSize", "others"]],
        ]
    }

    private static Map runIt(Map definition, Map request, def transport) {
        assertEquals([], DataSourceDefinitionSupport.check("GRAPHQL", definition))
        List<List<Map>> batches = []
        Map r = new DataSourceRunner(definition, transport).run(request) { List<Map> b -> batches << b }
        return r + [records: batches.flatten(), batchSizes: batches*.size()]
    }

    @Test
    void aWholeSetReadTakesEveryRecordOnceAndSeesThemAllInOneBatch() {
        CatalogueTransport t = new CatalogueTransport()
        Map r = runIt(catalogue(), [mode: "all"], t)
        assertEquals([4], r.batchSizes)
        Map<String, Map> bySku = ((List<Map>) r.records).collectEntries { [(it.sku): it] }
        assertEquals([sku: "A", code: "2", rank: 1, groupSize: 2, others: "D"], bySku.A)
        assertEquals([sku: "D", code: "2", rank: 2, groupSize: 2, others: "A"], bySku.D)
        assertEquals(null, bySku.B.code, "an ended code and a blank one are not taken")
        // Each query swept once, page by page, with its own fixed variables.
        assertEquals(["everything", "everything"], t.calls.findAll { it.op == "items" }*.filter)
        assertEquals(["type:CODE", "type:CODE", "type:CODE"], t.calls.findAll { it.op == "tags" }*.filter)
    }

    @Test
    void aWholeSetLargerThanItsCapFailsInsteadOfTruncating() {
        Map d = catalogue()
        d.limits = [maxCostPerCall: 100, maxRecordsInMemory: 3]
        DataSourceCallException e = assertThrows(DataSourceCallException) {
            new DataSourceRunner(d, new CatalogueTransport()).run([mode: "all"]) { }
        }
        assertEquals("TOO_MANY_RECORDS", e.code)
    }

    @Test
    void aWholeSetCannotBeReadByWindowOrIdsAndBindAllTakesNoPlaceholders() {
        Map d = catalogue()
        d.steps = ((List<Map>) d.steps).collect { Map s ->
            s.id == "items" ? s + [bind: [all: [variables: [filter: "after {from}"]],
                                          window: [variables: [filter: "{from}"], field: "x", sliceHours: 24]]] : s
        }
        List<String> errors = DataSourceDefinitionSupport.check("GRAPHQL", d)
        assertTrue(errors.any { it.contains("wholeSet reads with bind.all only") }, errors.toString())
        assertTrue(errors.any { it.contains("bind.all takes fixed variables") }, errors.toString())
        IllegalArgumentException mode = assertThrows(IllegalArgumentException) {
            new DataSourceRunner(catalogue(), new CatalogueTransport()).run([mode: "window", from: 0L, thru: 1L]) { }
        }
        assertTrue(mode.message.contains("cannot be read by window"), mode.message)
    }

    @Test
    void joinOptionsAreChecked() {
        Map d = catalogue()
        d.steps = ((List<Map>) d.steps).collect { Map s -> s.id == "code" ? s + [fromWhen: [[field: "ended", op: "SOON"]], last: "yes"] : s }
        List<String> errors = DataSourceDefinitionSupport.check("GRAPHQL", d)
        assertTrue(errors.any { it.contains("not 'SOON'") }, errors.toString())
        assertTrue(errors.any { it.contains("last is true or false") }, errors.toString())
        d = catalogue()
        d.steps = ((List<Map>) d.steps).collect { Map s -> s.id == "rank" ? s + [sizeField: "a b", collect: [field: "sku"]] : s }
        errors = DataSourceDefinitionSupport.check("GRAPHQL", d)
        assertTrue(errors.any { it.contains("sizeField is a field name") }, errors.toString())
        assertTrue(errors.any { it.contains("collect is {field, as}") }, errors.toString())
    }

    @Test
    void withoutAndListJoinAreDeriveFunctions() {
        Map r = [group: ["A", "B", "C"], me: "B"]
        assertEquals(["A", "C"], DeriveRules.valueOf(r, [fn: "without", args: [[copy: "group"], [copy: "me"]]]))
        assertEquals("A, C", DeriveRules.valueOf(r, [fn: "join", sep: ", ", args: [[fn: "without", args: [[copy: "group"], [copy: "me"]]]]]))
        assertEquals(null, DeriveRules.valueOf([group: ["B"], me: "B"], [fn: "join", sep: ", ", args: [[fn: "without", args: [[copy: "group"], [copy: "me"]]]]]))
        assertEquals("HOTWAX:7", DeriveRules.valueOf([id: 7], [fn: "join", sep: ":", args: ["HOTWAX", [copy: "id"]]]))
    }

    // ---------------------------------------------------------------- bind.all on a later step of a batched read

    @Test
    void aLaterStepMayReadEverythingOncePerBatch() {
        Map d = AcmeLedgerFixture.definition()
        d.steps = ((List<Map>) d.steps).collect { Map s -> s.id == "parts" ? s + [bind: [all: [variables: [filter: "*"]]]] : s }
        AcmeLedgerFixture.ScriptedTransport t = new AcmeLedgerFixture.ScriptedTransport()
        Map r = runIt(d, [mode: "ids", keys: ["A1", "A2"], keyField: "invoiceNo"], t)
        assertEquals(["Bolt", "Nut", "Nut", "Bolt", "Bolt", "Bolt"], ((List<Map>) r.records)*.partTitle)
        assertEquals(["*"], t.calls.findAll { it.document == "AcmeParts" }*.variables*.filter)
    }

    // ---------------------------------------------------------------- how the edge picks a mode

    @Test
    void theEdgeReadsByIdsWindowOrEverythingAsTheFirstStepAllows() {
        Map all = catalogue()
        assertEquals("all", DataDefinedExtract.modeFor(all, true, false))     // keys a whole read cannot use are ignored, as before
        assertEquals("all", DataDefinedExtract.modeFor(all, false, true))     // so is a window
        Map acme = AcmeLedgerFixture.definition()
        assertEquals("ids", DataDefinedExtract.modeFor(acme, true, true))
        assertEquals("window", DataDefinedExtract.modeFor(acme, false, true))
        assertEquals("window", DataDefinedExtract.modeFor(acme, false, false)) // and the window check then asks for one
    }
}
