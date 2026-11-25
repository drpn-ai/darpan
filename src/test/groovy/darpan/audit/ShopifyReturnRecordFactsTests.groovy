package darpan.audit

import org.junit.jupiter.api.Test

import java.time.ZoneId

import static org.junit.jupiter.api.Assertions.assertEquals

/**
 * DAR-BE-359 (Aditi 2026-11-25: "why is it mostly dashes"). RAILS R6 F-133618's Shopify return record held only what the
 * completed-returns read gave (closedAt, orderId, orderName, ...), so its finding read "Shopify — | Total — | Day —".
 * The read now carries the order's status, total and date; a return's day falls back to its closedAt. Pure: no Moqui.
 */
class ShopifyReturnRecordFactsTests {

    private static final ZoneId LA = ZoneId.of("America/Los_Angeles")

    /** F-133618's stored record, as prod held it on 2026-11-25. */
    private static final Map STORED = [closedAt: "2026-11-02T23:43:37Z", orderId: "18932332134710", orderName: "8683CAN",
                                       originalUnfulfilledQuantity: 0, returnCount: 1, returnIds: "37716066614"]

    @Test
    void aStoredReturnWithOnlyItsClosedAtStillHasItsDay() {
        // 23:43Z on the 2nd is still the 2nd in LA; nothing else was read, so nothing else is said.
        Map facts = AuditReportAssembler.recordFacts(STORED, "return", LA)
        assertEquals(["2026-11-02", null, null, null], [facts.returnDate, facts.orderDate, facts.orderTotal, facts.status])
    }

    @Test
    void theWidenedReadGivesStatusTotalAndOrderDay() {
        Map facts = AuditReportAssembler.recordFacts(STORED + [orderDate: "2026-10-29T17:05:00Z", displayFinancialStatus: "REFUNDED",
                displayFulfillmentStatus: "FULFILLED", totalPrice: "182.3", currencyCode: "CAD", totalRefunded: "182.3"], "return", LA)
        assertEquals("Refunded · Fulfilled", facts.status)
        assertEquals([amount: "182.30", currency: "CAD"], facts.orderTotal)
        assertEquals(["2026-10-29", "2026-11-02"], [facts.orderDate, facts.returnDate])
    }

    @Test
    void anOmsReturnsOwnDateStillWinsOverClosedAt() {
        assertEquals("2026-10-16", AuditReportAssembler.recordFacts([returnDate: 1792187864000L, closedAt: "2026-11-02T23:43:37Z"], "return", LA).returnDate)
    }
}
