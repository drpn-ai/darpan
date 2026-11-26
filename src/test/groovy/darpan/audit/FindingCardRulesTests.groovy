package darpan.audit

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-372 (Aditi 2026-11-26: "the finding card creator needs some standard rules so that these mistakes dont happen").
 * The card's rules that the backend owns, against record shapes as prod reads give them (Gorjana R10 F-126830's NetSuite
 * line, R13 F-130294's Shopify order; values made up). Pure: no Moqui.
 */
class FindingCardRulesTests {

    /** R10: a NetSuite sales order line carrying HotWax's item status and facility. */
    static final Map NETSUITE_LINE = [etailOrderId: "18995356336259", internalId: "81825328", itemName: "234-200-388-G",
            itemType: "InvtPart", lineAmount: "275", lineClosed: "F", lineId: "1", lineState: "OPEN",
            omsItemFacilityId: "PICKUP_REJECTED", omsItemShipGroupSeqId: "00002", omsItemShipmentMethodTypeId: "SHIPSI_SM_DAY_DLV",
            omsItemStatusId: "ITEM_APPROVED", orderId: "M1230020", orderLineId: "01", orderName: "#GOR197443011",
            orderOrigin: "HOTWAX", quantityBackordered: "1", quantityBilled: "0", quantityShipped: "0",
            salesChannel: "Web Channel", status: "B", tranDate: "11/7/2026", tranId: "SO7389324"]

    /** R13: a Shopify order cancelled in Shopify. */
    static final Map SHOPIFY_ORDER = [cancelledAt: "2026-11-12T03:00:00Z", createdAt: "2026-11-11T20:00:00Z", currencyCode: "USD",
            currentTotalPriceSet: [shopMoney: [amount: "0.0"]], currentTotalTaxSet: [shopMoney: [amount: "0.0"]],
            displayFinancialStatus: "PAID", displayFulfillmentStatus: "UNFULFILLED", hasPaymentTransaction: true, id: "19002956775555",
            isFulfilled: false, itemQuantity: 1, legacyResourceId: "19002956775555", name: "#GOR197458285",
            processedAt: "2026-11-11T20:00:00Z", requiresShipping: true, sourceName: "shopify_draft_order",
            subtotalLineItemsQuantity: 1, subtotalPriceSet: [shopMoney: [amount: "0.0"]], totalPrice: "0.00",
            totalPriceSet: [shopMoney: [amount: "0.0"]], updatedAt: "2026-11-12T03:00:00Z"]

    static final List<Map> SAMPLES = [NETSUITE_LINE, SHOPIFY_ORDER]

    /** Rule 2: a field that looks like a status, an amount or a date is placed in CARD_FIELDS before a card can drop it. */
    @Test
    void everyStatusAmountOrDateFieldIsPlaced() {
        Set<String> placed = AuditReportAssembler.CARD_FIELDS.values().flatten() as Set<String>
        List<String> unplaced = SAMPLES.collectMany { it.keySet() as List<String> }.unique()
                .findAll { it ==~ /(?i).*(status|total|amount|price|date|at)(id|set)?$/ }.findAll { !placed.contains(it) }
        assertEquals([], unplaced, "read fields the finding card neither shows nor names in CARD_FIELDS.notOnCard")
    }

    /** Rule 2 on R10: HotWax's status, the line's amount and its day come from the NetSuite line's own read. */
    @Test
    void aNetSuiteLineGivesHotWaxsStatusItsAmountAndItsDay() {
        Map facts = AuditReportAssembler.recordFacts(NETSUITE_LINE, "salesOrderLine")
        assertEquals(["Approved", "275.00", "2026-11-07", "Web Channel"],
                [facts.hotwaxStatus, (facts.lineTotal as Map)?.amount?.toString(), facts.orderDate, facts.salesChannel])
    }

    /** Rule 2 on R13: the Shopify order's status, payment, total and day. */
    @Test
    void aShopifyOrderGivesItsStatusTotalAndDay() {
        Map facts = AuditReportAssembler.recordFacts(SHOPIFY_ORDER, "order")
        assertEquals(["Unfulfilled", "Paid", "2026-11-11"], [facts.status, facts.paymentStatus, facts.orderDate])
        assertTrue(facts.orderTotal != null, "a 0.00 total is a total")
    }

    /**
     * Rules 2 and 7 on R13: the check reads Shopify and asks HotWax through its omsOrder lookup. The HotWax order the
     * lookup found is kept whole on the finding, so its card says HotWax's status, not a false "Missing".
     */
    @Test
    void aRecordALookupFoundIsKeptWholeOnTheFinding() {
        Map hotwax = [orderId: "M1300001", externalId: "19002956775555", statusId: "ORDER_COMPLETED"]
        Map row = [type: "FINDING", id: "19002956775555", data: SHOPIFY_ORDER,
                   lookedUp: [[name: "omsOrder", systemEnumId: "OMS_GQL", record: hotwax]]]
        List<Map> records = AuditAutomationSupport.recordsOf([file1RecordType: "order"], row, [systemEnumId: "SHOPIFY"], null)
        Map kept = records.find { it.systemEnumId == "OMS" }
        assertEquals(["order", "M1300001", hotwax], [kept?.recordType, kept?.recordId, kept?.recordData])
        assertEquals(1, records.count { it.systemEnumId == "OMS" }, "kept once")
        assertEquals("Completed", AuditReportAssembler.recordFacts(kept.recordData as Map, "order").status)
        assertEquals([], AuditAutomationSupport.lookedUpRecords([lookedUp: [[systemEnumId: "OMS_GQL", record: [statusId: "X"]]]]),
                "a record nothing names is left out")
    }

    /** Rule 1: a check's systems are the ones its words name, in their order, each once; HotWax and OMS are one. */
    @Test
    void aChecksSystemsAreTheOnesItsWordsName() {
        assertEquals(["Shopify", "OMS"], AuditReportAssembler.systemsNamed("Orders cancelled in Shopify and not cancelled in HotWax"))
        assertEquals(["OMS", "NetSuite"], AuditReportAssembler.systemsNamed("HotWax approved orders with no NetSuite sales order"))
        assertEquals(["NetSuite"], AuditReportAssembler.systemsNamed("NetSuite invoices and memos with no Etail id"))
        assertEquals(["OMS", "NetSuite"], AuditReportAssembler.systemsNamed("OMS returns against NetSuite credit memos and HotWax refunds"))
        assertEquals([], AuditReportAssembler.systemsNamed("Credit memos - Happiness Guarantee POS Tax Variance"))
    }

    /** Rule 5: a line is named by its HotWax item, else the line its headline names; never by the finding id. */
    @Test
    void aLineIsNamedByItsItemNeverItsFindingId() {
        Map r10 = [id: "F-126830", headline: "Line M1230020/01 is open in NetSuite and not at the warehouse in HotWax",
                   records: [NetSuite: [salesOrderLine: [[id: "SO7389324"]]]]]
        assertEquals("M1230020/01", AuditReportAssembler.lineItemId(r10))
        Map withItem = [id: "F-1", headline: "Anything", records: [OMS: [orderItem: [[id: "M1168785/01"]]]]]
        assertEquals("M1168785/01", AuditReportAssembler.lineItemId(withItem))
        assertFalse(AuditReportAssembler.lineItemId([id: "F-2", headline: "Order is late"]) == "F-2")
    }
}
