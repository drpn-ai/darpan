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

    // DAR-OPS-029 S11 (for S10). Lines past the firstKeyRecords cap are counted per side (S03a); the finding names them per
    // system, so the page can say "N more lines" instead of showing an order as whole when it is not.
    @Test
    void linesLeftOffTheOrderAreCountedPerSystem() {
        assertEquals([NETSUITE_SUITEQL: 2, OMS_ORDER_ITEMS_GQL: 5],
                AuditAutomationSupport.orderLinesOmittedOf([firstKeyRecordsOmitted: [file2: 2, file1: 5]], OMS, NS))
        assertEquals([OMS_ORDER_ITEMS_GQL: 7], AuditAutomationSupport.orderLinesOmittedOf([firstKeyRecordsOmitted: [file1: 5, file2: 2]], OMS, OMS),
                "two sides of one system add up")
        assertEquals([NETSUITE_SUITEQL: 2], AuditAutomationSupport.orderLinesOmittedOf([firstKeyRecordsOmitted: [file1: 0, file2: 2]], OMS, NS),
                "a side with none left off is not named")
        assertEquals(null, AuditAutomationSupport.orderLinesOmittedOf([firstKeyRecordsOmitted: [file1: 3]], [:], NS),
                "a side with no system cannot be named")
        assertEquals(null, AuditAutomationSupport.orderLinesOmittedOf([type: "MISSING_IN_FILE_2", id: "R1"], OMS, NS), "none left off: absent")
    }

    // DAR-OPS-029 S99-012 (S10's read side). The ledger keeps them by root system; the report names them by its keys, and an
    // order of several lines says the most any line saw (each line's read of the order cut the same lines off).
    @Test
    void anOrderSaysTheMostLinesAnyOfItsLinesSawLeftOffPerSystem() {
        assertEquals([NetSuite: 2, OMS: 7], AuditOrderUnits.linesOmitted([[orderLinesOmittedJson: '{"NETSUITE":2,"OMS":4}'],
                [orderLinesOmittedJson: '{"OMS":7}'], [orderLinesOmittedJson: null]]))
        assertEquals([OMS: 4], AuditOrderUnits.linesOmitted([[orderLinesOmittedJson: '{"OMS":4}']]), "one line: its own")
        assertEquals(null, AuditOrderUnits.linesOmitted([[orderLinesOmittedJson: null], [findingId: "F1"]]), "none left off: absent")
        assertEquals(null, AuditOrderUnits.linesOmitted([]))
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
        List<Map> out = AuditOrderUnits.orderLines("M1132010", lines, context)
        assertEquals([line: "1", item: "M1132010/01", sku: "203-104-G", status: "Completed", finding: "F-100500",
                      cause: "Not shipped after 24 hours", state: "pending"], out[0])
        assertEquals([line: "2", item: "M1132010/02", sku: "212-120-G", status: "Approved", quantity: 2, setAside: true], out[1],
                "the line R1 never read is still listed, with no finding")
        assertEquals([system: "NetSuite", item: "Store Credit - Redemption", itemType: "Payment",
                      amount: [amount: "-89.30", currency: null], status: "Open"], out[2])
    }

    @Test
    void anOrderLineRecordReadsItsUnitsSetAsideItemAndAmount() {
        Map facts = AuditRecordFacts.recordFacts([itemType: "Payment", itemName: "Store Credit - Redemption", lineAmount: "-89.3",
                                                      lineState: "OPEN", presence: "EXCLUDED", units: 1], "orderLine")
        assertEquals("Store Credit - Redemption", facts.item)
        assertEquals("Payment", facts.itemType)
        assertEquals("-89.30", ((Map) facts.amount).amount)
        assertEquals(true, facts.setAside)
        assertEquals(1, facts.quantity)
        assertEquals("Open", facts.status)
    }
}
