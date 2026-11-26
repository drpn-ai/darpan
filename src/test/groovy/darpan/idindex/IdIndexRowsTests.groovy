package darpan.idindex

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

/** DAR-BE-374: a record becomes one index row per (field, value), keyed by the record's own id. */
class IdIndexRowsTests {

    private static final Map SHOPIFY = [systemEnumId: "SHOPIFY", systemCode: "SHOPIFY", recordKind: "ORDER", idField: "id", stampField: "updatedAt"]
    private static final List<Map> SHOPIFY_FIELDS = [
            [fieldId: "NAME", fieldPath: "name", role: "OWN"],
            [fieldId: "ID", fieldPath: "id", role: "OWN"],
            [fieldId: "LINE", recordKind: "ORDER", fieldPath: "lineItems[*].id", role: "OWN"],
            [fieldId: "REFUND", fieldPath: "refunds[*].id", role: "OWN"],
    ]
    private static final Map NETSUITE = [systemEnumId: "NETSUITE_SUITEQL", systemCode: "NETSUITE", recordKindField: "type",
                                         recordKindMapJson: '{"SalesOrd":"ORDER","ItemShip":"FULFILLMENT"}', idField: "id", stampField: "lastmodifieddate"]
    private static final List<Map> NETSUITE_FIELDS = [
            [fieldId: "ID", fieldPath: "id", role: "OWN"],
            [fieldId: "TRANID", fieldPath: "tranid", role: "OWN"],
            [fieldId: "CREATED_FROM", recordKind: "FULFILLMENT", fieldPath: "createdfrom", role: "REF", refRecordKind: "ORDER"],
    ]

    @Test
    void "a list path explodes into one row per value"() {
        Map order = [id: "gid://shopify/Order/9", name: "#GOR9", updatedAt: "2026-11-20T10:00:00Z",
                     lineItems: [[id: "gid://shopify/LineItem/1"], [id: "gid://shopify/LineItem/2"], [id: "gid://shopify/LineItem/3"]]]
        List<Map> rows = IdIndexRows.rows(SHOPIFY, SHOPIFY_FIELDS, order, "SHOP_A")
        assertEquals(["9"] * 5, rows*.recordKey)
        assertEquals(["GOR9", "9", "1", "2", "3"], rows*.idNorm)
        assertEquals(["#GOR9", "gid://shopify/Order/9", "gid://shopify/LineItem/1", "gid://shopify/LineItem/2", "gid://shopify/LineItem/3"], rows*.idRaw)
        assertTrue(rows.every { it.connectionId == "SHOP_A" && it.systemCode == "SHOPIFY" && it.systemEnumId == "SHOPIFY" && it.recordKind == "ORDER" })
    }

    @Test
    void "a record without its own key yields nothing"() {
        assertEquals([], IdIndexRows.rows(SHOPIFY, SHOPIFY_FIELDS, [name: "#GOR9"], "SHOP_A"))
    }

    @Test
    void "a NetSuite record's kind comes from its type, and an unmapped type is skipped"() {
        List<Map> ship = IdIndexRows.rows(NETSUITE, NETSUITE_FIELDS, [id: "501", tranid: "IF1", type: "ItemShip", createdfrom: "400"], "NS")
        assertEquals(["FULFILLMENT"] * 3, ship*.recordKind)
        Map ref = ship.find { it.role == "REF" }
        assertEquals(["400", "createdfrom", "ORDER"], [ref.idNorm, ref.fieldPath, ref.refRecordKind])
        List<Map> so = IdIndexRows.rows(NETSUITE, NETSUITE_FIELDS, [id: "400", tranid: "SO1", type: "SalesOrd", createdfrom: "x"], "NS")
        assertEquals(["400", "SO1"], so*.idNorm, "a field for another record kind does not apply")
        assertEquals([], IdIndexRows.rows(NETSUITE, NETSUITE_FIELDS, [id: "7", tranid: "JE1", type: "Journal"], "NS"))
    }

    @Test
    void "the same value in one field is one row, in two fields two rows"() {
        Map order = [id: "9", name: "9", lineItems: [[id: "L1"], [id: "L1"]]]
        List<Map> rows = IdIndexRows.rows(SHOPIFY, SHOPIFY_FIELDS, order, "SHOP_A")
        assertEquals([["name", "9"], ["id", "9"], ["lineItems[*].id", "L1"]], rows.collect { [it.fieldPath, it.idNorm] })
    }

    @Test
    void "rows built from Spark-extracted values match rows built from the record"() {
        Map order = [id: "gid://shopify/Order/9", name: "#GOR9", refunds: [[id: "R1"]], updatedAt: "t"]
        Map extracted = [key: "gid://shopify/Order/9", kind: null, stamp: "t",
                         values: ["name": ["#GOR9"], "id": ["gid://shopify/Order/9"], "lineItems[*].id": [], "refunds[*].id": ["R1"]]]
        assertEquals(IdIndexRows.rows(SHOPIFY, SHOPIFY_FIELDS, order, "C"), IdIndexRows.rowsFromValues(SHOPIFY, SHOPIFY_FIELDS, extracted, "C"))
    }

    @Test
    void "a kind hint (a NetSuite run side's query record type) decides the kind when given"() {
        Map ns = NETSUITE + [recordKindField: null]
        List<Map> rows = IdIndexRows.rows(ns, NETSUITE_FIELDS, [id: "501", tranid: "IF1", createdfrom: "400"], "NS", "ItemShip")
        assertEquals(["FULFILLMENT"] * 3, rows*.recordKind)
        assertEquals([], IdIndexRows.rows(ns, NETSUITE_FIELDS, [id: "7"], "NS", "Journal"), "an unmapped record type is skipped")
    }

    @Test
    void "a field with no path or no role writes nothing"() {
        List<Map> fields = SHOPIFY_FIELDS + [[fieldId: "BAD1", fieldPath: "name", role: null], [fieldId: "BAD2", fieldPath: null, role: "OWN"]]
        assertEquals(["name", "id"], IdIndexRows.rows(SHOPIFY, fields, [id: "9", name: "#9"], "C")*.fieldPath)
    }
}
