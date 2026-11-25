package darpan.audit

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull

/**
 * DAR-BE-368 (Aditi 2026-11-25: "include the line item details, expecially the SKU", "show both shopify and HW"). An order
 * record lists its lines from what its read gave: Shopify's lineItems, HotWax's orderItems, each line keyed by the Shopify
 * line id so the two pair. Pure: no Moqui.
 */
class OrderLinesRecordFactsTests {

    @Test
    void aShopifyOrderSaysEachLinesSkuQuantityAndFulfilment() {
        Map facts = AuditReportAssembler.recordFacts([name: "#9281CAN", displayFulfillmentStatus: "UNFULFILLED", lineItems: [
                [id: "71", sku: "SKU-A", name: "Anorak", quantity: 2, currentQuantity: 2, unfulfilledQuantity: 2],
                [id: "72", sku: "SKU-B", name: "Cap", quantity: 3, currentQuantity: 3, unfulfilledQuantity: 1],
                [id: "73", sku: "SKU-C", name: "Belt", quantity: 1, currentQuantity: 1, unfulfilledQuantity: 0],
                [id: "74", sku: "SKU-D", name: "Sock", quantity: 1, currentQuantity: 0, unfulfilledQuantity: 0],
                [id: "75", sku: null, name: "Gift card", quantity: 1]]], "order")
        assertEquals([[id: "71", sku: "SKU-A", name: "Anorak", quantity: 2, status: "Unfulfilled"],
                      [id: "72", sku: "SKU-B", name: "Cap", quantity: 3, status: "1 of 3 unfulfilled"],
                      [id: "73", sku: "SKU-C", name: "Belt", quantity: 1, status: "Fulfilled"],
                      [id: "74", sku: "SKU-D", name: "Sock", quantity: 1, status: "Removed"],
                      [id: "75", sku: null, name: "Gift card", quantity: 1, status: null]], facts.lines)
    }

    @Test
    void aHotWaxOrderSaysEachItemsShopifyLineSkuQuantityAndStatus() {
        Map facts = AuditReportAssembler.recordFacts([orderName: "#9281CAN", statusId: "ORDER_COMPLETED", orderItems: [
                [orderItemSeqId: "00101", externalId: "71", productId: "P1", sku: "SKU-A", quantity: 2, statusId: "ITEM_COMPLETED"],
                [orderItemSeqId: "00102", externalId: null, productId: "P2", sku: null, quantity: 1, statusId: "ITEM_CANCELLED"]]], "order")
        assertEquals([[id: "71", sku: "SKU-A", name: null, quantity: 2, status: "Completed"],
                      [id: "00102", sku: null, name: null, quantity: 1, status: "Cancelled"]], facts.lines)
    }

    @Test
    void aRecordWhoseReadGaveNoLinesHasNone() {
        assertNull(AuditReportAssembler.recordFacts([name: "#1", displayFulfillmentStatus: "FULFILLED"], "order").lines)
        assertEquals([], AuditReportAssembler.recordFacts([name: "#1", lineItems: []], "order").lines)
    }
}
