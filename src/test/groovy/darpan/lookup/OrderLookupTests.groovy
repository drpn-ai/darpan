package darpan.lookup

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

/** DAR-CLI-008 part 3: the follow-the-order lookup engine, through its seams (no Moqui). */
class OrderLookupTests {

    private static Map read(String id, String system, String kind, Map extra = [:]) {
        return [readId: id, systemCode: system, recordKind: kind, keyField: "k", queryTemplate: "q", isFind: "N", fieldsDefault: null] + extra
    }

    private static Map link(String id, String from, String field, String to) { [linkId: id, fromReadId: from, fromField: field, toReadId: to] }

    private static final List<Map> READS = [
            read("SHOP_BY_ID", "SHOPIFY", "ORDER", [isFind: "Y", valueShape: "[0-9]{1,20}"]),
            read("NS_BY_INTERNAL", "NETSUITE", "ORDER", [isFind: "Y", valueShape: "[0-9]{1,12}"]),
            read("OMS_BY_EXT", "OMS", "ORDER"),
            read("OMS_ITEMS", "OMS", "ORDER_ITEM"),
            read("NS_BY_EXT", "NETSUITE", "ORDER"),
    ]
    private static final List<Map> LINKS = [
            link("L1", "SHOP_BY_ID", "id", "OMS_BY_EXT"),
            link("L2", "OMS_BY_EXT", "externalId", "SHOP_BY_ID"),   // back to Shopify: the cycle must stop
            link("L3", "OMS_BY_EXT", "orderId", "NS_BY_EXT"),
            link("L4", "OMS_BY_EXT", "orderId", "OMS_ITEMS"),
            link("L5", "NS_BY_EXT", "missingField", "OMS_BY_EXT"),
    ]

    /** answers: "READ|connection|value" -> records or [error: reason]. */
    private static Map deps(Map<String, Object> answers, Map overrides = [:]) {
        List<String> calls = []
        Map d = [reads      : READS, links: LINKS,
                 connections: { String system -> system == "SHOPIFY" ? [[id: "S1", label: "US store"]] : [[id: "${system}1".toString(), label: system]] },
                 query      : { Map r, Map c, String v ->
                     String key = "${r.readId}|${c.id}|${v}".toString()
                     calls << key
                     Object a = answers.get(key)
                     a instanceof Map ? a : [records: (a ?: [])]
                 },
                 findings   : { Collection<String> ids -> ids.contains("OMS100") ? [[findingId: "F-1", recordKey: "OMS100"]] : [] },
                 maxReads   : 30, deadlineMillis: 1000L, now: { 0L }, calls: calls]
        return d + overrides
    }

    private static final Map CHAIN = [
            "SHOP_BY_ID|S1|5550001"   : [[id: "5550001", name: "#1001", email: "jane@example.com"]],
            "OMS_BY_EXT|OMS1|5550001" : [[orderId: "OMS100", externalId: "5550001", statusId: "ORDER_APPROVED"]],
            "NS_BY_EXT|NETSUITE1|OMS100": [[id: "9001", externalid: "OMS100", status: "Pending Fulfillment"]],
            "OMS_ITEMS|OMS1|OMS100"   : [[orderId: "OMS100", orderItemSeqId: "00101", statusId: "ITEM_APPROVED"]],
    ]

    @Test
    void "a value matching two find reads tries both, a miss is not an error"() {
        Map d = deps(CHAIN)
        Map out = OrderLookup.run(d, "5550001", false)
        assertTrue(d.calls.contains("SHOP_BY_ID|S1|5550001"))
        assertTrue(d.calls.contains("NS_BY_INTERNAL|NETSUITE1|5550001"), "it also looks like a NetSuite internal id")
        assertEquals([], out.skipped)
    }

    @Test
    void "it follows the order breadth-first and reads the cycle back to Shopify only once"() {
        Map d = deps(CHAIN)
        Map out = OrderLookup.run(d, "5550001", false)
        assertEquals(["SHOP_BY_ID", "OMS_BY_EXT", "NS_BY_EXT", "OMS_ITEMS"], (out.records as List<Map>)*.readId)
        assertEquals(1, d.calls.count { it.startsWith("SHOP_BY_ID|") }, "Shopify read once though HotWax links back to it")
        Map ns = (out.records as List<Map>).find { it.readId == "NS_BY_EXT" }
        assertEquals([value: "OMS100", linkId: "L3", fromReadId: "OMS_BY_EXT"], ns.foundBy)
        assertEquals("NETSUITE", ns.system)
        assertEquals([[findingId: "F-1", recordKey: "OMS100"]], out.findings, "findings of any id seen along the chain")
    }

    @Test
    void "findings match ids a record carries in its linked fields, even when no read follows them"() {
        List<Map> reads = [read("OMS_ONLY", "OMS", "ORDER", [isFind: "Y", keyField: "externalId"])]
        List<Map> links = [link("LX", "OMS_ONLY", "orderId", "NOT_INSTALLED_READ")]
        Map d = deps(["OMS_ONLY|OMS1|5550001": [[orderId: "OMS100", externalId: "5550001"]]], [reads: reads, links: links])
        Map out = OrderLookup.run(d, "5550001", false)
        assertEquals([[findingId: "F-1", recordKey: "OMS100"]], out.findings)
    }

    @Test
    void "every store of a system is searched and each record names its store"() {
        Map d = deps(["SHOP_BY_ID|S1|5550001": [[id: "5550001"]], "SHOP_BY_ID|S2|5550001": [[id: "5550001", name: "#EU1"]]],
                [connections: { String s -> s == "SHOPIFY" ? [[id: "S1", label: "US store"], [id: "S2", label: "EU store"]] : [] }])
        Map out = OrderLookup.run(d, "5550001", false)
        assertEquals(["S1", "S2"], (out.records as List<Map>)*.connectionId)
        assertEquals(["US store", "EU store"], (out.records as List<Map>)*.connectionLabel)
    }

    @Test
    void "a link inside one system stays on the connection the record came from"() {
        Map d = deps(CHAIN, [connections: { String s -> s == "OMS" ? [[id: "OMS1", label: "a"], [id: "OMS2", label: "b"]] :
                s == "SHOPIFY" ? [[id: "S1", label: "US"]] : [[id: "NETSUITE1", label: "ns"]] }])
        OrderLookup.run(d, "5550001", false)
        assertTrue(d.calls.contains("OMS_BY_EXT|OMS2|5550001"), "a cross-system link searches every OMS connection")
        assertFalse(d.calls.contains("OMS_ITEMS|OMS2|OMS100"), "items are read where their order was found")
        assertTrue(d.calls.contains("OMS_ITEMS|OMS1|OMS100"))
    }

    @Test
    void "a refused, throttled or uninstalled read is listed as skipped and the lookup still answers"() {
        Map answers = new LinkedHashMap(CHAIN)
        answers.put("OMS_BY_EXT|OMS1|5550001", [error: "THROTTLED: retry in 10 s"])
        answers.put("NS_BY_INTERNAL|NETSUITE1|5550001", [error: "not installed"])
        Map out = OrderLookup.run(deps(answers), "5550001", false)
        assertEquals(["SHOP_BY_ID"], (out.records as List<Map>)*.readId)
        assertEquals([[readId: "NS_BY_INTERNAL", connectionId: "NETSUITE1", reason: "not installed"],
                      [readId: "OMS_BY_EXT", connectionId: "OMS1", reason: "THROTTLED: retry in 10 s"]] as Set, (out.skipped as List) as Set)
    }

    @Test
    void "the read cap stops reading and says so"() {
        Map out = OrderLookup.run(deps(CHAIN, [maxReads: 2]), "5550001", false)
        assertEquals(2, out.readsUsed)
        assertTrue(out.capped as boolean)
        assertTrue((out.skipped as List<Map>).any { it.reason == "read cap (2)" })
    }

    @Test
    void "the time limit stops reading and says so"() {
        long[] clock = [0L]
        Map d = deps(CHAIN, [query: { Map r, Map c, String v -> clock[0] += 600L; [records: (CHAIN["${r.readId}|${c.id}|${v}".toString()] ?: [])] },
                             now  : { clock[0] }])
        Map out = OrderLookup.run(d, "5550001", false)
        assertTrue(out.capped as boolean)
        assertTrue((out.skipped as List<Map>).any { it.reason == "time limit" })
    }

    @Test
    void "a value that fits no find read is answered with nothing read"() {
        Map out = OrderLookup.run(deps(CHAIN), "not an id!", false)
        assertEquals([], out.records)
        assertEquals(0, out.readsUsed)
    }

    @Test
    void "default fields leave out customer details, all keeps every field"() {
        List<Map> reads = READS.collect { it.readId == "SHOP_BY_ID" ? it + [fieldsDefault: "id, name"] : it }
        Map lean = OrderLookup.run(deps(CHAIN, [reads: reads]), "5550001", false)
        assertEquals([id: "5550001", name: "#1001"], (lean.records as List<Map>)[0].record)
        Map full = OrderLookup.run(deps(CHAIN, [reads: reads]), "5550001", true)
        assertEquals("jane@example.com", ((full.records as List<Map>)[0].record as Map).email)
        assertEquals([a: [b: 1]], OrderLookup.project([a: [b: 1, c: 2], d: 3], ["a.b"], false), "dotted paths keep their nesting")
    }

    @Test
    void "SuiteQL gets the value only after its shape check, quoted"() {
        assertEquals("SELECT id FROM transaction WHERE tranid = '#GOR123'",
                OrderLookup.renderSuiteQl("SELECT id FROM transaction WHERE tranid = {value}", "#GOR123", null))
        ["M1' OR '1'='1", "M 1", ""].each { String bad ->
            assertThrows(IllegalArgumentException) { OrderLookup.renderSuiteQl("SELECT 1 FROM t WHERE x = {value}", bad, null) }
        }
        assertThrows(IllegalArgumentException) { OrderLookup.renderSuiteQl("SELECT 1 FROM t WHERE x = {value}", "ABC", "[0-9]+") }
    }
}
