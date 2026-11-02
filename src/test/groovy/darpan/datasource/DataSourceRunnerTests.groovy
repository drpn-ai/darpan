package darpan.datasource

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-176. The step runner turns a definition into records with no system knowledge: reads by ids or window, child
 * reads by an earlier step's ids, name lookups copied onto records, children placed under a nested group (a stand-in
 * group when theirs was not returned), one record per leaf and per unit, running numbers, and counts. The made-up
 * system is AcmeLedgerFixture.
 */
class DataSourceRunnerTests {

    private static final List<Map> EXPECTED = [
            [invoiceNo: "A1", lineNo: "01", unitNo: 1, statusNo: 1, status: "OPEN", warehouse: "W1", warehouseLabel: "North", partTitle: "Bolt"],
            [invoiceNo: "A1", lineNo: "02", unitNo: 1, statusNo: 1, status: "SHIPPED", warehouse: "W2", warehouseLabel: "South", partTitle: "Nut"],
            [invoiceNo: "A1", lineNo: "02", unitNo: 2, statusNo: 2, status: "SHIPPED", warehouse: "W2", warehouseLabel: "South", partTitle: "Nut"],
            // Line 03 names section 9, which A2 did not return: it is kept under a stand-in section with no warehouse.
            [invoiceNo: "A2", lineNo: "03", unitNo: 1, statusNo: 1, status: "OPEN", warehouse: null, partTitle: "Bolt"],
            [invoiceNo: "A2", lineNo: "03", unitNo: 2, statusNo: 2, status: "OPEN", warehouse: null, partTitle: "Bolt"],
            [invoiceNo: "A2", lineNo: "03", unitNo: 3, statusNo: 3, status: "OPEN", warehouse: null, partTitle: "Bolt"],
            // Line 04's quantity "1.0" gives no unit under STRICT_INT, as the parse rule says.
    ]

    private static Map runIt(Map request, AcmeLedgerFixture.ScriptedTransport transport = new AcmeLedgerFixture.ScriptedTransport(),
            Map definition = AcmeLedgerFixture.definition()) {
        assertEquals([], DataSourceDefinitionSupport.check("GRAPHQL", definition))
        List<List<Map>> batches = []
        DataSourceRunner runner = new DataSourceRunner(definition, transport)
        Map result = runner.run(request) { List<Map> batch -> batches << batch }
        return result + [records: batches.flatten(), batchSizes: batches*.size(), transport: transport]
    }

    @Test
    void byIdsTheRunnerBuildsEveryUnitRecordFromFourReads() {
        Map r = runIt([mode: "ids", keys: ["A1", "A2", "A3", "A1", " "], keyField: "invoiceNo"])
        assertEquals(EXPECTED, r.records)
        assertEquals([linesWithoutPart: 1L, quantityNotOne: 2L], r.counts)
        assertEquals(3, r.rootRecords)
        // batchRecords 2: A1+A2, then A3 (no lines, so no part or warehouse reads for it).
        assertEquals([6, 0], r.batchSizes)
        List<String> ops = ((AcmeLedgerFixture.ScriptedTransport) r.transport).calls*.document
        // A batch is finished as soon as it fills, before the next chunk is read: nothing waits for the whole read.
        assertEquals(["AcmeInvoices", "AcmeLines", "AcmeLines", "AcmeParts", "AcmeWarehouses", "AcmeWarehouses", "AcmeInvoices", "AcmeLines"], ops)
        Map firstCall = ((AcmeLedgerFixture.ScriptedTransport) r.transport).calls[0]
        assertEquals([filter: "invoiceNo in (A1,A2)", first: 3], firstCall.variables)
    }

    @Test
    void byWindowEachDayIsReadAndTheRecordsAreTheSame() {
        long from = java.time.Instant.parse("2026-10-01T00:00:00Z").toEpochMilli()
        long thru = java.time.Instant.parse("2026-10-03T00:00:00Z").toEpochMilli()
        Map r = runIt([mode: "window", from: from, thru: thru])
        assertEquals(EXPECTED, r.records)
        List<Map> invoiceCalls = ((AcmeLedgerFixture.ScriptedTransport) r.transport).calls.findAll { it.document == "AcmeInvoices" }
        assertEquals(["issued>=2026-10-01T00:00:00Z issued<2026-10-02T00:00:00Z", "issued>=2026-10-02T00:00:00Z issued<2026-10-03T00:00:00Z"],
                invoiceCalls*.variables*.filter)
    }

    @Test
    void aRunMayAskByAnyKeyFieldTheDefinitionAllowsAndNoOther() {
        Map r = runIt([mode: "ids", keys: ["C1"], keyField: "ref"])
        assertEquals(["A1"], r.records*.invoiceNo.unique())
        assertEquals("customerRef in (C1)", ((AcmeLedgerFixture.ScriptedTransport) r.transport).calls[0].variables.filter)
        IllegalArgumentException wrongField = assertThrows(IllegalArgumentException) { runIt([mode: "ids", keys: ["A1"], keyField: "issued"]) }
        assertTrue(wrongField.message.contains("is asked by invoiceNo, ref, not 'issued'"))
        IllegalArgumentException badKey = assertThrows(IllegalArgumentException) { runIt([mode: "ids", keys: ["A1", "a b"], keyField: "invoiceNo"]) }
        assertTrue(badKey.message.contains("refuses the key(s) a b"))
    }

    private static Map parallel(Map d, String mode, int threads) {
        d.steps = ((List<Map>) d.steps).collect { Map st ->
            if (st.id != "invoices") return st
            Map bind = new LinkedHashMap((Map) st.bind)
            bind[mode] = ((Map) bind[mode]) + [parallel: threads]
            st + [bind: bind]
        }
        d.batchRecords = 1
        return d
    }

    private static List<String> keys(List<Map> records) {
        return records.collect { "${it.invoiceNo}|${it.lineNo}|${it.unitNo}|${it.statusNo}|${it.warehouse}|${it.partTitle}".toString() }.sort()
    }

    @Test
    void piecesReadInParallelGiveTheSameRecordsAndCounts() {
        long from = java.time.Instant.parse("2026-10-01T00:00:00Z").toEpochMilli()
        long thru = java.time.Instant.parse("2026-10-04T00:00:00Z").toEpochMilli()
        Map serial = runIt([mode: "window", from: from, thru: thru])
        Map window = runIt([mode: "window", from: from, thru: thru], new AcmeLedgerFixture.ScriptedTransport(),
                parallel(AcmeLedgerFixture.definition(), "window", 3))
        assertEquals(keys(EXPECTED), keys((List<Map>) window.records))
        assertEquals(serial.counts, window.counts)
        assertEquals(3, window.rootRecords)
        Map ids = runIt([mode: "ids", keys: ["A1", "A2", "A3"], keyField: "invoiceNo"], new AcmeLedgerFixture.ScriptedTransport(),
                parallel(AcmeLedgerFixture.definition(), "ids", 2))
        assertEquals(keys(EXPECTED), keys((List<Map>) ids.records))
        assertEquals(serial.counts, ids.counts)
    }

    @Test
    void aFailingPieceFailsTheWholeRun() {
        long from = java.time.Instant.parse("2026-10-01T00:00:00Z").toEpochMilli()
        long thru = java.time.Instant.parse("2026-10-04T00:00:00Z").toEpochMilli()
        AcmeLedgerFixture.ScriptedTransport t = new AcmeLedgerFixture.ScriptedTransport(failOn: "2026-10-02T00")
        DataSourceCallException e = assertThrows(DataSourceCallException) {
            new DataSourceRunner(parallel(AcmeLedgerFixture.definition(), "window", 3), t).run([mode: "window", from: from, thru: thru]) { }
        }
        assertEquals("BROKEN", e.code)
    }

    @Test
    void aCostRefusalHalvesThePageUntilItFits() {
        AcmeLedgerFixture.ScriptedTransport t = new AcmeLedgerFixture.ScriptedTransport(refuseCostAbove: 1)
        Map r = runIt([mode: "ids", keys: ["A1", "A2", "A3"], keyField: "invoiceNo"], t)
        assertEquals(EXPECTED, r.records)
        assertEquals(1, r.pageSizes.invoices)
        assertEquals(1, r.pageSizes.lines)
    }

    @Test
    void theParseRuleIsDataNotCode() {
        Map d = AcmeLedgerFixture.definition()
        d.steps = ((List<Map>) d.steps).collect { it.id == "perUnit" ? it + [parse: "NUMERIC"] : it }
        Map r = runIt([mode: "ids", keys: ["A2"], keyField: "invoiceNo"], new AcmeLedgerFixture.ScriptedTransport(), d)
        // NUMERIC reads "1.0" as one unit, so line 04 now has a record.
        assertEquals(["04", "03", "03", "03"], r.records*.lineNo)
        assertEquals([0, 1, 2, 3, 0, 0], ["0", 1, 2.7, 3, null, "x"].withIndex().collect { v, i -> i == 4 ? DataSourceRunner.units(v, "NUMERIC", 0) : DataSourceRunner.units(v, "NUMERIC", 1) })
        assertEquals(0, DataSourceRunner.units("1.0", "STRICT_INT", 1))
        assertEquals(1, DataSourceRunner.units(null, "STRICT_INT", 1))
    }

    @Test
    void anAnswerWithoutItsConnectionOrWithARepeatedCursorFailsInsteadOfLookingEmpty() {
        def missing = new Expando(execute: { String doc, Map v, int res -> [somethingElse: [:]] })
        DataSourceCallException e = assertThrows(DataSourceCallException) {
            new DataSourceRunner(AcmeLedgerFixture.definition(), missing).run([mode: "ids", keys: ["A1"], keyField: "invoiceNo"]) { }
        }
        assertEquals("NO_CONNECTION", e.code)
        def looping = new Expando(execute: { String doc, Map v, int res ->
            [invoices: [edges: [], pageInfo: [hasNextPage: true, endCursor: "same"]]]
        })
        DataSourceCallException loop = assertThrows(DataSourceCallException) {
            new DataSourceRunner(AcmeLedgerFixture.definition(), looping).run([mode: "ids", keys: ["A1"], keyField: "invoiceNo"]) { }
        }
        assertEquals("CURSOR_REPEATED", loop.code)
    }
}
