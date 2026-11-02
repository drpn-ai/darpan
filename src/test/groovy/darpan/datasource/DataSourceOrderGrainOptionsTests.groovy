package darpan.datasource

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-176 (second proof: an order-grain read as data). The options such a read needs, on the made-up Acme system:
 * list paths and list functions, a drop step with its own sidecar, query selections chosen by the fields a run reads,
 * and keepRecordFields. No system is named.
 */
class DataSourceOrderGrainOptionsTests {

    private static Map runIt(Map definition, Map request, AcmeLedgerFixture.ScriptedTransport transport = new AcmeLedgerFixture.ScriptedTransport()) {
        assertEquals([], DataSourceDefinitionSupport.check("GRAPHQL", definition))
        List<List<Map>> batches = []
        Map result = new DataSourceRunner(definition, transport).run(request) { List<Map> batch -> batches << batch }
        return result + [records: batches.flatten(), transport: transport]
    }

    private static Map ids(List<String> keys, Map more = [:]) {
        return [mode: "ids", keys: keys, keyField: "invoiceNo"] + more
    }

    // ---------------------------------------------------------------- lists

    @Test
    void conditionsReadAListPathAsAnyOfItsValuesAndAnEmptyListIsBlank() {
        Map r = [tags: [], none: [:], lines: [[s: "OPEN"], [s: "DONE"]]]
        assertTrue(RecordConditions.matches(r, [field: "tags", op: "BLANK"]))
        assertTrue(RecordConditions.matches(r, [field: "none", op: "BLANK"]))
        assertFalse(RecordConditions.matches(r, [field: "lines", op: "BLANK"]))
        assertTrue(RecordConditions.matches(r, [field: "lines[*].s", op: "IN", values: ["DONE"]]))
        assertFalse(RecordConditions.matches(r, [field: "lines[*].s", op: "NOT_IN", values: ["DONE"]]))
        assertTrue(RecordConditions.matches(r, [field: "lines[*].s", op: "NOT_IN", values: ["GONE"]]))
        assertFalse(RecordConditions.matches(r, [field: "lines[*].s", op: "BLANK"]))
        assertTrue(RecordConditions.matches(r, [field: "tags[*].s", op: "BLANK"]))
        assertFalse(RecordConditions.matches(r, [field: "tags[*].s", op: "NOT_BLANK"]))
        assertEquals([], RecordConditions.check("t", [field: "lines[*].s", op: "IN", values: ["x"]]))
    }

    @Test
    void deriveValuesReadListsWithSizeDistinctSortedFirstAndAtLeast() {
        Map r = [opps: [[m: "CARD", ref: " r2 "], [m: "CASH", ref: "r1"], [m: "CARD", ref: ""]], slots: 3]
        assertEquals(["CARD", "CASH", "CARD"], DeriveRules.valueOf(r, [copy: "opps[*].m"]))
        assertEquals(3, DeriveRules.valueOf(r, [fn: "size", args: [[copy: "opps"]]]))
        assertEquals(0, DeriveRules.valueOf(r, [fn: "size", args: [[copy: "missing"]]]))
        assertEquals("CARD,CASH", DeriveRules.valueOf(r, [fn: "distinctSorted", sep: ",", args: [[copy: "opps[*].m"]]]))
        assertEquals("r1,r2", DeriveRules.valueOf(r, [fn: "distinctSorted", sep: ",", args: [[copy: "opps[*].ref"]]]))
        // No values give empty text, not null: "this record has none" is an answer.
        assertEquals("", DeriveRules.valueOf([:], [fn: "distinctSorted", sep: ",", args: [[copy: "opps[*].m"]]]))
        assertEquals("CARD", DeriveRules.valueOf(r, [fn: "first", args: [[copy: "opps[*].m"]]]))
        assertEquals(null, DeriveRules.valueOf(r, [fn: "first", args: [[copy: "missing[*].m"]]]))
        assertEquals(true, DeriveRules.valueOf(r, [fn: "atLeast", args: [[fn: "size", args: [[copy: "opps"]]], [copy: "slots"]]]))
        assertEquals(false, DeriveRules.valueOf(r, [fn: "atLeast", args: [[fn: "size", args: [[copy: "opps"]]], 4]]))
        assertEquals([], DeriveRules.check("t", [[set: "x", value: [fn: "distinctSorted", args: [[copy: "opps[*].m"]]]]]))
        assertTrue(DeriveRules.check("t", [[set: "x", value: [fn: "atLeast", args: [1]]]])[0].contains("atLeast compares two"))
    }

    // ---------------------------------------------------------------- drop

    private static Map withDrop(Map drop) {
        Map d = AcmeLedgerFixture.definition()
        List<Map> steps = new ArrayList<Map>((List<Map>) d.steps)
        // Right after the first read: the lines read that follows takes its ids from what is left.
        steps.add(1, drop)
        d.steps = steps
        return d
    }

    @Test
    void aDropStepRemovesRecordsBeforeLaterReadsCountsThemAndKeepsThemOutOfTheRuleSidecar() {
        Map d = withDrop([id: "notC2", kind: "drop", name: "droppedForCustomer", on: "invoices",
                          when: [[field: "customerRef", op: "IN", values: ["C2"]]],
                          sidecar: [suffix: "dropped-invoices", max: 5,
                                    fields: [invoice: [copy: "invoiceNo"], customer: [fn: "text", args: [[copy: "customerRef"]]],
                                             firstSection: [fn: "first", args: [[copy: "sections[*].sectionNo"]]]]]])
        Map r = runIt(d, ids(["A1", "A2", "A3"]))
        assertEquals(["A1"], r.records*.invoiceNo.unique())
        assertEquals(1L, r.counts.droppedForCustomer)
        // The sections are still a connection when the drop runs (the unwrap comes later): first gives nothing, not an error.
        assertEquals([[suffix: "dropped-invoices", entries: [[invoice: "A2", customer: "C2", firstSection: null]], truncated: false]], r.sidecars)
        List<Map> lineCalls = ((AcmeLedgerFixture.ScriptedTransport) r.transport).calls.findAll { it.document == "AcmeLines" }
        assertFalse(lineCalls.any { (it.variables.filter as String).contains("A2") }, "a dropped record's children are never read")
        assertEquals([:], r.excludedByRule)
    }

    @Test
    void aDropSidecarStopsAtItsMaximumAndSaysSo() {
        Map d = withDrop([id: "notC2C3", kind: "drop", name: "dropped", when: [[field: "customerRef", op: "IN", values: ["C2", "C3"]]],
                          sidecar: [suffix: "dropped", max: 1, fields: [invoice: [copy: "invoiceNo"]]]])
        Map r = runIt(d, ids(["A1", "A2", "A3"]))
        assertEquals(2L, r.counts.dropped)
        assertEquals(1, ((List) ((Map) r.sidecars[0]).entries).size())
        assertEquals(true, ((Map) r.sidecars[0]).truncated)
    }

    @Test
    void aDropStepIsCheckedLikeEveryOtherStep() {
        List<String> errors = DataSourceDefinitionSupport.check("GRAPHQL", withDrop([id: "bad", kind: "drop", name: "x",
                sidecar: [suffix: "Bad Suffix", max: 0, fields: [a: [fn: "nope", args: [1]]]]]))
        assertTrue(errors.any { it.contains("a drop needs when") }, errors.toString())
        assertTrue(errors.any { it.contains("sidecar suffix") }, errors.toString())
        assertTrue(errors.any { it.contains("sidecar max") }, errors.toString())
        assertTrue(errors.any { it.contains("fn is one of") }, errors.toString())
    }

    // ---------------------------------------------------------------- selections

    private static final String INVOICES_WITH_SLOT = '''query AcmeInvoices($filter: String, $first: Int, $after: String) {
  invoices(filter: $filter, first: $first, after: $after) {
    edges { node { invoiceNo customerRef issued {{extras}} sections(first: 3) { edges { node { sectionNo warehouse } } } } }
    pageInfo { hasNextPage endCursor }
  }
}'''

    private static Map withSelections(List<Map> selections) {
        Map d = AcmeLedgerFixture.definition()
        d.steps = ((List<Map>) d.steps).collect { Map s ->
            s.id == "invoices" ? s + [document: INVOICES_WITH_SLOT, selections: selections] : s
        }
        d.record = (Map) d.record + [optionalFields: ((List) ((Map) d.record).optionalFields) + ["region"]]
        return d
    }

    private static final List<Map> EXTRAS = [
            [at: "extras", when: [[field: "wanted[*]", op: "IN", values: ["note"]]], text: "note", cost: 100],
            [at: "extras", when: [[field: "wanted[*]", op: "IN", values: ["region"]]], text: "region { code }", cost: 150,
             set: [region: "NORTH"]],
    ]

    private static Map firstInvoiceCall(Map r) {
        return ((AcmeLedgerFixture.ScriptedTransport) r.transport).calls.find { it.document == "AcmeInvoices" }
    }

    @Test
    void aSelectionIsAskedOnlyWhenTheRunReadsItsFieldAndItsCostSizesThePage() {
        Map none = runIt(withSelections(EXTRAS), ids(["A1"]))
        assertFalse((firstInvoiceCall(none).text as String).contains("note"))
        assertFalse((firstInvoiceCall(none).text as String).contains("region"))
        assertEquals(3, firstInvoiceCall(none).variables.first)   // 900 / 300
        assertEquals(null, none.records[0].region)

        Map note = runIt(withSelections(EXTRAS), ids(["A1"], [keep: ["invoiceNo", "note"]]))
        assertTrue((firstInvoiceCall(note).text as String).contains("issued note sections"))
        assertEquals(2, firstInvoiceCall(note).variables.first)   // 900 / 400

        // A filter's field asks for it too; a selection's fixed values land on every record it read.
        List<Map> rules = darpan.reconciliation.source.SourceFilterSupport.parseRules(
                [[fieldExpression: "region", operator: "EXCLUDE_IN", filterValues: "NOWHERE", sequenceNum: 1]])
        Map d = withSelections(EXTRAS)
        // region is on the invoice: the split copies it onto each record, as any invoice field.
        d.steps = ((List<Map>) d.steps).collect { Map st -> st.id == "perLine" ? st + [copy: (Map) st.copy + [region: "invoice.region"]] : st } +
                [[id: "rules", kind: "filter", fields: ["region", "status"]]]
        Map region = runIt(d, ids(["A1"], [filters: rules]))
        assertTrue((firstInvoiceCall(region).text as String).contains("region { code }"))
        assertEquals(2, firstInvoiceCall(region).variables.first)  // 900 / 450
        assertEquals(["A1"], region.records*.invoiceNo.unique(), "the rule reads the selected field, so nothing is field-absent")
        assertEquals(["NORTH"], region.records*.region.unique())
        assertEquals([:], region.fieldAbsentByRule)
    }

    @Test
    void selectionTextCannotLeaveItsSelectionAndEveryDocumentItCanMakeIsChecked() {
        Closure<List<String>> errorsFor = { List<Map> sel -> DataSourceDefinitionSupport.check("GRAPHQL", withSelections(sel)) }
        assertTrue(errorsFor([[at: "extras", text: "note } } mutation x { y"]]).any { it.contains("balanced") })
        assertTrue(errorsFor([[at: "extras", text: 'note(q: "x")']]).any { it.contains('no " # $') })
        assertTrue(errorsFor([[at: "extras", text: 'note # comment']]).any { it.contains('no " # $') })
        assertTrue(errorsFor([[at: "extras", text: 'note(first: $first)']]).any { it.contains('no " # $') })
        assertTrue(errorsFor([[at: "other", text: "note"]]).any { it.contains("no {{other}}") })
        assertTrue(errorsFor([]).any { it.contains("{{extras}} has no selection") })
        // Introspection hidden in one selection is found: each selection is checked inside the whole document.
        assertTrue(errorsFor([[at: "extras", text: "note", when: [[field: "wanted[*]", op: "IN", values: ["note"]]]],
                              [at: "extras", text: "__type(name: x) { name }", when: [[field: "wanted[*]", op: "IN", values: ["t"]]]]])
                .any { it.toLowerCase().contains("introspection") || it.contains("__") }, "introspection refused")
        assertTrue(errorsFor([[at: "extras", text: "note", cost: -1]]).any { it.contains("cost") })
    }

    // ---------------------------------------------------------------- cost refusals in parallel

    @Test
    void aCostRefusalSeenAfterAnotherReaderHalvedThePageIsRetriedNotFatal() {
        // Every first-step read of 3 is refused; the second reader's refusal arrives once the first has already halved the
        // shared size to 1. Judged by the shared size, that refusal looked like "even 1 is refused" and failed the run.
        java.util.concurrent.CountDownLatch bothAsked = new java.util.concurrent.CountDownLatch(2)
        AcmeLedgerFixture.ScriptedTransport inner = new AcmeLedgerFixture.ScriptedTransport()
        def transport = new Expando(execute: { String doc, Map v, int res ->
            if (doc.contains("AcmeInvoices") && (v.first as int) > 1) {
                bothAsked.countDown()
                bothAsked.await(5, java.util.concurrent.TimeUnit.SECONDS)
                if (Thread.currentThread().name.endsWith("-2")) Thread.sleep(200)
                throw new DataSourceCallException("TOO_EXPENSIVE", "too expensive", true)
            }
            return inner.execute(doc, v, res)
        })
        Map d = AcmeLedgerFixture.definition()
        d.steps = ((List<Map>) d.steps).collect { Map st ->
            st.id == "invoices" ? st + [bind: (Map) st.bind + [window: ((Map) ((Map) st.bind).window) + [parallel: 2]]] : st
        }
        long from = java.time.Instant.parse("2026-10-01T00:00:00Z").toEpochMilli()
        long thru = java.time.Instant.parse("2026-10-03T00:00:00Z").toEpochMilli()
        List<Map> records = []
        Map r = new DataSourceRunner(d, transport).run([mode: "window", from: from, thru: thru]) { List<Map> b -> records.addAll(b) }
        assertEquals(6, records.size())
        assertEquals(1, r.pageSizes.invoices)
    }

    // ---------------------------------------------------------------- keepRecordFields

    private static Map keeping() {
        Map d = AcmeLedgerFixture.definition()
        d.record = (Map) d.record + [keepRecordFields: true, keepWith: [warehouse: ["warehouseLabel"]]]
        return d
    }

    @Test
    void keepRecordFieldsTrimsEachRecordWithItsCompanionsAndRefusesAFieldTheSourceHasNot() {
        Map r = runIt(keeping(), ids(["A1"], [keep: ["lineNo", "invoiceNo", "warehouse"]]))
        assertEquals([invoiceNo: "A1", lineNo: "01", warehouse: "W1", warehouseLabel: "North"], r.records[0])
        assertEquals([invoiceNo: "A1", lineNo: "02", warehouse: "W2", warehouseLabel: "South"], r.records[1])

        // Rules still read the whole record: status is filtered on though not kept.
        List<Map> rules = darpan.reconciliation.source.SourceFilterSupport.parseRules(
                [[fieldExpression: "status", operator: "EXCLUDE_IN", filterValues: "SHIPPED", sequenceNum: 1]])
        Map d = keeping()
        d.steps = ((List<Map>) d.steps) + [[id: "rules", kind: "filter", fields: ["status"]]]
        Map filtered = runIt(d, ids(["A1"], [keep: ["invoiceNo", "lineNo"], filters: rules]))
        assertEquals([[invoiceNo: "A1", lineNo: "01"]], filtered.records)

        IllegalArgumentException e = assertThrows(IllegalArgumentException) {
            new DataSourceRunner(keeping(), new AcmeLedgerFixture.ScriptedTransport()).run(ids(["A1"], [keep: ["invoiceNo", "colour"]])) { }
        }
        assertTrue(e.message.contains("cannot keep [colour]"), e.message)
    }

    @Test
    void withoutTheOptInKeepRecordFieldsChangesNothing() {
        Map plain = runIt(AcmeLedgerFixture.definition(), ids(["A1"]))
        Map kept = runIt(AcmeLedgerFixture.definition(), ids(["A1"], [keep: ["invoiceNo"]]))
        assertEquals(plain.records, kept.records)
    }
}
