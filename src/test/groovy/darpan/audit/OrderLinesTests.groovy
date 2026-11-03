package darpan.audit

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-UI-099 (Aditi 2026-11-03: "always make sure all lines are present within the order.. i noticed sometimes an item is
 * show missing.. also in the case of store credit, a store credit line item is added"). gorjana October R1: M1132010 had
 * line 01 as a finding and line 02 still approved, so the order finding showed one line; NetSuite SO7365765 (M1205413)
 * carries "Store Credit - Redemption" for 89.30 with no HotWax line id. Pure: no Moqui.
 */
class OrderLinesTests {

    private static final String SEP = "\u001F"
    private static final Map OMS = [systemEnumId: "OMS_ORDER_ITEMS_GQL"]
    private static final Map NS = [systemEnumId: "NETSUITE_SUITEQL"]

    private static Map row(String key, Map firstKeyRecords) { [type: "MISSING_IN_FILE_2", id: key, firstKeyRecords: firstKeyRecords] }

    @Test
    void theOtherLinesOfTheOrderBecomeOrderLineRecordsOncePerLine() {
        Map r = row("M1132010${SEP}01", [
                file1: [[key: "M1132010${SEP}01", presence: "KEPT", record: [orderItemSeqId: "01", statusId: "ITEM_COMPLETED"]],
                        [key: "M1132010${SEP}02", presence: "EXCLUDED", record: [orderItemSeqId: "02", statusId: "ITEM_APPROVED", sku: "212-120-G"]],
                        [key: "M1132010${SEP}02", presence: "EXCLUDED", record: [orderItemSeqId: "02", statusId: "ITEM_APPROVED", sku: "212-120-G"]]],
                file2: [[key: null, presence: "EXCLUDED", record: [itemType: "Payment", itemName: "Store Credit - Redemption", lineAmount: "-89.3"]]]])
        List<Map> recs = AuditAutomationSupport.orderLineRecords(r, OMS, NS)
        assertEquals([["OMS_ORDER_ITEMS_GQL", "M1132010/02"], ["NETSUITE_SUITEQL", "Store Credit - Redemption"]],
                recs.collect { [it.systemEnumId, it.recordId] }, "the row's own line is not repeated")
        assertTrue(recs.every { it.recordType == "orderLine" })
        assertEquals(2, recs[0].recordData.units, "two units of line 02")
        assertEquals("EXCLUDED", recs[0].recordData.presence)
    }

    @Test
    void aRowWithoutOrderLinesAddsNone() {
        assertEquals([], AuditAutomationSupport.orderLineRecords([type: "MISSING_IN_FILE_2", id: "R1"], OMS, NS))
    }

    @Test
    void anOrderListsEveryLineInOrderThenTheLinesOnlyNetsuiteHas() {
        List<Map> lines = [
                [id: "F-100500", cause: "Not shipped after 24 hours", state: "pending",
                 records: [OMS: [orderItem: [[id: "M1132010/01", sku: "203-104-G", status: "Completed"]]]]],
        ]
        List<Map> context = [
                [system: "OMS", record: [id: "M1132010/02", sku: "212-120-G", status: "Approved", quantity: 2, setAside: true]],
                [system: "NetSuite", record: [id: "Store Credit - Redemption", item: "Store Credit - Redemption", itemType: "Payment",
                                              amount: [amount: "-89.30", currency: null], status: "Open"]],
        ]
        List<Map> out = AuditReportAssembler.orderLines("M1132010", lines, context)
        assertEquals([line: "1", item: "M1132010/01", sku: "203-104-G", status: "Completed", finding: "F-100500",
                      cause: "Not shipped after 24 hours", state: "pending"], out[0])
        assertEquals([line: "2", item: "M1132010/02", sku: "212-120-G", status: "Approved", quantity: 2, setAside: true], out[1],
                "the line R1 never read is still listed, with no finding")
        assertEquals([system: "NetSuite", item: "Store Credit - Redemption", itemType: "Payment",
                      amount: [amount: "-89.30", currency: null], status: "Open"], out[2])
    }

    @Test
    void anOrderLineRecordReadsItsUnitsSetAsideItemAndAmount() {
        Map facts = AuditReportAssembler.recordFacts([itemType: "Payment", itemName: "Store Credit - Redemption", lineAmount: "-89.3",
                                                      lineState: "OPEN", presence: "EXCLUDED", units: 1], "orderLine")
        assertEquals("Store Credit - Redemption", facts.item)
        assertEquals("Payment", facts.itemType)
        assertEquals("-89.30", ((Map) facts.amount).amount)
        assertEquals(true, facts.setAside)
        assertEquals(1, facts.quantity)
        assertEquals("Open", facts.status)
    }
}
