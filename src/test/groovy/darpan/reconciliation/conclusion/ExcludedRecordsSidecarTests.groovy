package darpan.reconciliation.conclusion

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class ExcludedRecordsSidecarTests {

    @TempDir
    File dir

    private static final Map RULE = [sequenceNum: 1, fieldExpression: "quantityShipped"]

    @Test
    void namesSitNextToTheExtract() {
        assertEquals("oms-units.excluded.json", ExcludedRecordsSidecar.fileNameFor("oms-units.json"))
        assertEquals("extract.excluded.json", ExcludedRecordsSidecar.fileNameFor("extract"))
    }

    @Test
    void collectorCapsButKeepsCounting() {
        Map c = ExcludedRecordsSidecar.newCollector()
        (ExcludedRecordsSidecar.MAX_ENTRIES + 3).times { int i -> ExcludedRecordsSidecar.collect(c, [id: i], RULE) }
        assertEquals(ExcludedRecordsSidecar.MAX_ENTRIES, ((List) c.records).size())
        assertTrue(c.truncated as boolean)
        assertEquals(ExcludedRecordsSidecar.MAX_ENTRIES + 3, c.total)
        assertEquals("1:quantityShipped", ((Map) ((List) c.records)[0])._excludedBy)
    }

    @Test
    void collectingDoesNotMutateTheCallersRecord() {
        Map record = [id: 1]
        ExcludedRecordsSidecar.collect(ExcludedRecordsSidecar.newCollector(), record, RULE)
        assertFalse(record.containsKey("_excludedBy"))
    }

    @Test
    void roundTripsAndReportsAbsence() {
        File extract = new File(dir, "ns.json")
        extract.text = '{"records":[]}'
        assertFalse(ExcludedRecordsSidecar.read(extract).present as boolean)

        Map c = ExcludedRecordsSidecar.newCollector()
        ExcludedRecordsSidecar.collect(c, [orderId: "M1", orderLineId: "01"], RULE)
        ExcludedRecordsSidecar.write(new File(dir, ExcludedRecordsSidecar.fileNameFor("ns.json")), c, "ns.json")

        Map back = ExcludedRecordsSidecar.read(extract)
        assertTrue(back.present as boolean)
        assertFalse(back.truncated as boolean)
        assertEquals("M1", ((Map) ((List) back.records)[0]).orderId)
    }

    @Test
    void aCorruptSidecarReadsAsAbsentNotEmpty() {
        File extract = new File(dir, "ns.json")
        extract.text = '{}'
        new File(dir, "ns.excluded.json").text = '{"records":['
        assertFalse(ExcludedRecordsSidecar.read(extract).present as boolean,
                "a sidecar we cannot read must not claim nothing was excluded")
    }

    @Test
    void mergingPagesKeepsTheCapAndTheTrueTotal() {
        Map into = ExcludedRecordsSidecar.newCollector()
        Map page = ExcludedRecordsSidecar.newCollector()
        (ExcludedRecordsSidecar.MAX_ENTRIES - 1).times { int i -> ExcludedRecordsSidecar.collect(into, [id: i], RULE) }
        3.times { int i -> ExcludedRecordsSidecar.collect(page, [id: "p${i}"], RULE) }
        ExcludedRecordsSidecar.merge(into, page)
        assertEquals(ExcludedRecordsSidecar.MAX_ENTRIES, ((List) into.records).size())
        assertEquals(ExcludedRecordsSidecar.MAX_ENTRIES + 2, into.total)
        assertTrue(into.truncated as boolean)
        assertEquals("1:quantityShipped", ((Map) ((List) into.records).last())._excludedBy, "merged rows keep their rule tag")
    }

    @Test
    void mergingNothingIsHarmless() {
        Map into = ExcludedRecordsSidecar.newCollector()
        ExcludedRecordsSidecar.merge(into, null)
        assertEquals(0, into.total)
    }
}
