package darpan.audit

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals

/**
 * A check with no headline template (an agent's new check has none) names a mismatch by each system's value, not by the
 * engine's "Field comparison failed: billing_status = billing_status". A template still wins; a missing record keeps the
 * engine's note.
 */
class AuditDefaultHeadlineTests {

    private static final List<Map> PAIR = [[systemEnumId: "OMS_DB", recordType: "record", recordId: "ACME-1120"],
                                           [systemEnumId: "NETSUITE", recordType: "record", recordId: "ACME-1120"]]

    @Test
    void "a mismatch with no template names each system with its value, HotWax for the OMS"() {
        Map row = [id: "ACME-1120", type: "FIELD_MISMATCH", note: "Field comparison failed: billing_status = billing_status",
                   file1Value: "Billed", file2Value: "Pending Billing"]
        assertEquals("ACME-1120: Billed in HotWax, Pending Billing in NetSuite", AuditAutomationSupport.headline([:], row, PAIR))
    }

    @Test
    void "a template still wins, and a missing record keeps the engine's note"() {
        Map row = [id: "ACME-1120", type: "FIELD_MISMATCH", note: "n", file1Value: "Billed", file2Value: "Pending Billing"]
        assertEquals("Order ACME-1120 is not billed", AuditAutomationSupport.headline([headlineTemplate: "Order {recordKey} is not billed"], row, PAIR))
        Map missing = [id: "RF-7703", type: "MISSING_IN_FILE_2", note: "Present in Shopify, missing in NetSuite"]
        assertEquals("RF-7703: Present in Shopify, missing in NetSuite",
                AuditAutomationSupport.headline([:], missing, [[systemEnumId: "SHOPIFY_DB", recordType: "record", recordId: "RF-7703"]]))
    }

    @Test
    void "a record with no named type is shown by the side's key field, and a path or composite key keeps record"() {
        assertEquals("orderName", AuditAutomationSupport.keyRecordType("order_name"))
        assertEquals("refundId", AuditAutomationSupport.keyRecordType("refund_id"))
        assertEquals("tranId", AuditAutomationSupport.keyRecordType("tranId"))
        assertEquals(null, AuditAutomationSupport.keyRecordType("lines[0].sku"))
        assertEquals(null, AuditAutomationSupport.keyRecordType(null))
    }

    @Test
    void "a mismatch with only one side's record keeps the engine's note"() {
        Map row = [id: "ACME-1212", type: "FIELD_MISMATCH", note: "Field comparison failed: total_price = total_price",
                   file1Value: "227.95", file2Value: "217.95"]
        assertEquals("ACME-1212: Field comparison failed: total_price = total_price",
                AuditAutomationSupport.headline([:], row, [PAIR[0]]))
    }
}
