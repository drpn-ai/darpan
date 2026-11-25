package darpan.audit

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull

/**
 * DAR-BE-360 (Aditi 2026-11-25: "add payment status to all orders and refunds", per system's own words). Each order and
 * return record says its payment status from what its read already gave: Shopify's financial status (no longer folded
 * into its status), HotWax's payment preferences, a HotWax return's credit, NetSuite's customer deposits. Pure: no Moqui.
 */
class PaymentStatusRecordFactsTests {

    @Test
    void shopifySplitsFinancialFromFulfillment() {
        Map facts = AuditReportAssembler.recordFacts([name: "#GOR1", displayFinancialStatus: "PARTIALLY_REFUNDED",
                displayFulfillmentStatus: "FULFILLED"], "order")
        assertEquals(["Fulfilled", "Partially refunded"], [facts.status, facts.paymentStatus])
    }

    @Test
    void aShopifyRecordWithOnlyAFinancialStatusHasNoStatusButItsPayment() {
        Map facts = AuditReportAssembler.recordFacts([displayFinancialStatus: "PAID"], "order")
        assertEquals([null, "Paid"], [facts.status, facts.paymentStatus])
    }

    @Test
    void aHotWaxOrderReadsItsPaymentPreferencesOnceEach() {
        // A tender taken and given back is two rows (DAR-BE-151); each status is said once, in read order.
        Map facts = AuditReportAssembler.recordFacts([statusId: "ORDER_COMPLETED", paymentPreferences: [
                [paymentMethodTypeId: "EXT_SHOPIFY", statusId: "PAYMENT_SETTLED", maxAmount: 80],
                [paymentMethodTypeId: "EXT_SHOPIFY", statusId: "PAYMENT_REFUNDED", maxAmount: 20],
                [paymentMethodTypeId: "GIFT_CARD", statusId: "PAYMENT_SETTLED", maxAmount: 10]]], "order")
        assertEquals(["Completed", "Settled, Refunded"], [facts.status, facts.paymentStatus])
    }

    @Test
    void aHotWaxOrderWithNoPaymentSaysSo() {
        assertEquals("No payment", AuditReportAssembler.recordFacts([statusId: "ORDER_APPROVED", hasPaymentPreference: "N"], "order").paymentStatus)
    }

    @Test
    void aHotWaxReturnIsRefundedWhenItsCreditIsAboveZero() {
        assertEquals("Refunded", AuditReportAssembler.recordFacts([statusId: "RETURN_COMPLETED", returnCredit: "60"], "return").paymentStatus)
        assertEquals("Not refunded", AuditReportAssembler.recordFacts([statusId: "RETURN_COMPLETED", returnCredit: "0"], "return").paymentStatus)
    }

    @Test
    void aHotWaxReturnReadWithoutCreditsSaysNothing() {
        assertNull(AuditReportAssembler.recordFacts([statusId: "RETURN_COMPLETED", returnTotal: "60"], "return").paymentStatus)
    }

    @Test
    void aNetSuiteOrderReadsItsDepositsStatuses() {
        Map facts = AuditReportAssembler.recordFacts([tranId: "SO1", deposits: [
                [tranId: "CD1", amount: 50, status: "Customer Deposit : Fully Applied"],
                [tranId: "CD2", amount: 10, status: "Customer Deposit : Not Deposited"],
                [tranId: "CD3", amount: 5, status: "Customer Deposit : Fully Applied"]]], "salesOrderLine")
        assertEquals("Fully Applied, Not Deposited", facts.paymentStatus)
    }

    @Test
    void anItemOrALineHasNoPaymentStatusOfItsOwn() {
        assertNull(AuditReportAssembler.recordFacts([sku: "A1", omsOrderStatusId: "ORDER_COMPLETED"], "orderItem").paymentStatus)
    }
}
