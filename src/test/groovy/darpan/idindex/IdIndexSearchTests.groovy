package darpan.idindex

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

/** DAR-BE-374: one read for the value, then a capped walk inside the index (REF to OWN, OWN back to the REFs that name it). */
class IdIndexSearchTests {

    /** An in-memory index: rows as RecordIdIndex would hold them. */
    private static Map deps(List<Map> rows, Map extra = [:]) {
        return [byIdNorm: { String v -> rows.findAll { it.idNorm == v } },
                byRecord: { List<Map> refs -> rows.findAll { r -> refs.any { it.connectionId == r.connectionId && it.recordKind == r.recordKind && it.recordKey == r.recordKey } } },
                refsTo  : { List<String> vs -> rows.findAll { it.role == "REF" && it.idNorm in vs } }] + extra
    }

    private static Map row(String sys, String conn, String kind, String key, String field, String raw, String role = "OWN", String refKind = null) {
        return [systemCode: sys, systemEnumId: sys, connectionId: conn, recordKind: kind, recordKey: key, fieldPath: field, idRaw: raw,
                idNorm: IdNormalizer.normalize(raw), role: role, refRecordKind: refKind, lastSeenAt: new java.sql.Timestamp(1000L)]
    }

    private static final List<Map> GOR123 = [
            row("SHOPIFY", "SHOP", "ORDER", "5551", "id", "5551"),
            row("SHOPIFY", "SHOP", "ORDER", "5551", "name", "#GOR123"),
            row("OMS", "OMS", "ORDER", "M100", "orderId", "M100"),
            row("OMS", "OMS", "ORDER", "M100", "orderName", "GOR123"),
            row("OMS", "OMS", "ORDER", "M100", "externalId", "5551", "REF", "ORDER"),
            row("NETSUITE", "NS", "ORDER", "400", "id", "400"),
            row("NETSUITE", "NS", "ORDER", "400", "otherrefnum", "GOR123"),
            row("NETSUITE", "NS", "ORDER", "400", "tranid", "SO1"),
            row("NETSUITE", "NS", "FULFILLMENT", "501", "id", "501"),
            row("NETSUITE", "NS", "FULFILLMENT", "501", "createdfrom", "400", "REF", "ORDER"),
            row("NETSUITE", "NS", "INVOICE", "601", "id", "601"),
            row("NETSUITE", "NS", "INVOICE", "601", "createdfrom", "400", "REF", "ORDER"),
    ]

    private static List<String> keys(Map out) { return (out.records as List<Map>).collect { "${it.systemCode}:${it.recordKind}:${it.recordKey}".toString() } }

    @Test
    void "the same value in three systems returns every record"() {
        Map out = IdIndexSearch.search(deps(GOR123, [maxHops: 0]), "#gor123")
        assertEquals(["SHOPIFY:ORDER:5551", "OMS:ORDER:M100", "NETSUITE:ORDER:400"] as Set, keys(out) as Set)
        Map shop = (out.records as List<Map>).find { it.systemCode == "SHOPIFY" }
        assertEquals("#GOR123", shop.foundBy.idRaw)
        assertEquals(["5551", "#GOR123"] as Set, (shop.ids as List<Map>)*.idRaw as Set, "each record carries its index IDs")
        assertEquals("GOR123", out.idNorm)
    }

    @Test
    void "a REF leads to the record that owns the value"() {
        Map out = IdIndexSearch.search(deps(GOR123), "501")
        assertTrue("NETSUITE:ORDER:400" in keys(out), keys(out).toString())
        Map so = (out.records as List<Map>).find { it.recordKey == "400" }
        assertEquals(["501", "createdfrom"], [so.foundBy.viaRecordKey, so.foundBy.viaFieldPath])
    }

    @Test
    void "an OWN value leads back to the records that name it"() {
        Map out = IdIndexSearch.search(deps(GOR123, [maxHops: 1]), "SO1")
        assertEquals("NETSUITE:ORDER:400", keys(out)[0], "the hit first")
        assertTrue(keys(out).containsAll(["NETSUITE:FULFILLMENT:501", "NETSUITE:INVOICE:601"]), keys(out).toString())
        Map ful = (out.records as List<Map>).find { it.recordKey == "501" }
        assertEquals(["400", "id"], [ful.foundBy.viaRecordKey, ful.foundBy.viaFieldPath], "reached through the order's own id")
    }

    @Test
    void "the walk follows the order across systems"() {
        assertEquals(["OMS:ORDER:M100", "SHOPIFY:ORDER:5551", "NETSUITE:ORDER:400", "NETSUITE:FULFILLMENT:501", "NETSUITE:INVOICE:601"] as Set,
                keys(IdIndexSearch.search(deps(GOR123), "M100")) as Set,
                "HotWax -> Shopify (externalId, and the shared name), -> NetSuite (the shared name), -> its fulfillment and invoice")
    }

    @Test
    void "a cycle stops: each record is listed once"() {
        List<Map> rows = [row("A", "C", "ORDER", "1", "id", "1"), row("A", "C", "ORDER", "1", "peer", "2", "REF"),
                          row("A", "C", "ORDER", "2", "id", "2"), row("A", "C", "ORDER", "2", "peer", "1", "REF")]
        Map out = IdIndexSearch.search(deps(rows), "1")
        assertEquals(["A:ORDER:1", "A:ORDER:2"], keys(out))
        assertFalse(out.capped as boolean)
    }

    @Test
    void "a hub value shared by too many records is not followed"() {
        List<Map> rows = [row("A", "C", "ORDER", "1", "id", "1"), row("A", "C", "ORDER", "1", "store", "STORE9", "REF")]
        (2..20).each { rows << row("A", "C", "ORDER", "${it}", "store", "STORE9", "REF") ; rows << row("A", "C", "STORE", "S", "id", "STORE9") }
        Map out = IdIndexSearch.search(deps(rows, [maxRowsPerValue: 5]), "1")
        assertTrue(out.capped as boolean)
        assertTrue((out.cappedReason as String).contains("STORE9"), out.cappedReason as String)
        assertEquals(["A:ORDER:1"], keys(out), "the hub was not followed")
    }

    @Test
    void "at most maxRecords records come back"() {
        Map out = IdIndexSearch.search(deps(GOR123, [maxRecords: 2]), "GOR123")
        assertEquals(2, (out.records as List).size())
        assertTrue(out.capped as boolean)
    }

    @Test
    void "a value that is not an id finds nothing"() {
        Map out = IdIndexSearch.search(deps(GOR123), "   ")
        assertEquals([], out.records)
        assertNull(out.idNorm)
    }
}
