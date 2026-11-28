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
        Map facts = AuditRecordFacts.recordFacts([name: "#GOR1", displayFinancialStatus: "PARTIALLY_REFUNDED",
                displayFulfillmentStatus: "FULFILLED"], "order")
        assertEquals(["Fulfilled", "Partially refunded"], [facts.status, facts.paymentStatus])
    }

    @Test
    void aShopifyRecordWithOnlyAFinancialStatusHasNoStatusButItsPayment() {
        Map facts = AuditRecordFacts.recordFacts([displayFinancialStatus: "PAID"], "order")
        assertEquals([null, "Paid"], [facts.status, facts.paymentStatus])
    }

    @Test
    void aHotWaxOrderReadsItsPaymentPreferencesOnceEach() {
        // A tender taken and given back is two rows (DAR-BE-151); each status is said once, in read order.
        Map facts = AuditRecordFacts.recordFacts([statusId: "ORDER_COMPLETED", paymentPreferences: [
                [paymentMethodTypeId: "EXT_SHOPIFY", statusId: "PAYMENT_SETTLED", maxAmount: 80],
                [paymentMethodTypeId: "EXT_SHOPIFY", statusId: "PAYMENT_REFUNDED", maxAmount: 20],
                [paymentMethodTypeId: "GIFT_CARD", statusId: "PAYMENT_SETTLED", maxAmount: 10]]], "order")
        assertEquals(["Completed", "Settled, Refunded"], [facts.status, facts.paymentStatus])
    }

    @Test
    void aHotWaxOrderWithNoPaymentSaysSo() {
        assertEquals("No payment", AuditRecordFacts.recordFacts([statusId: "ORDER_APPROVED", hasPaymentPreference: "N"], "order").paymentStatus)
    }

    @Test
    void aHotWaxReturnIsRefundedWhenItsCreditIsAboveZero() {
        assertEquals("Refunded", AuditRecordFacts.recordFacts([statusId: "RETURN_COMPLETED", returnCredit: "60"], "return").paymentStatus)
        assertEquals("Not refunded", AuditRecordFacts.recordFacts([statusId: "RETURN_COMPLETED", returnCredit: "0"], "return").paymentStatus)
    }

    @Test
    void aHotWaxReturnReadWithoutCreditsSaysNothing() {
        assertNull(AuditRecordFacts.recordFacts([statusId: "RETURN_COMPLETED", returnTotal: "60"], "return").paymentStatus)
    }

    @Test
    void aNetSuiteOrderReadsItsDepositsStatuses() {
        Map facts = AuditRecordFacts.recordFacts([tranId: "SO1", deposits: [
                [tranId: "CD1", amount: 50, status: "Customer Deposit : Fully Applied"],
                [tranId: "CD2", amount: 10, status: "Customer Deposit : Not Deposited"],
                [tranId: "CD3", amount: 5, status: "Customer Deposit : Fully Applied"]]], "salesOrderLine")
        assertEquals("Fully Applied, Not Deposited", facts.paymentStatus)
    }

    // What this proves: a record that carries no payment field has none (null, not ""). The record type is not consulted:
    // an item or line shows none only because its read carries no payment field (S99: a domain owner may want it gated).
    @Test
    void aRecordWithNoPaymentFieldHasNoPaymentStatus() {
        assertNull(AuditRecordFacts.recordFacts([sku: "A1", omsOrderStatusId: "ORDER_COMPLETED"], "orderItem").paymentStatus)
    }
}
