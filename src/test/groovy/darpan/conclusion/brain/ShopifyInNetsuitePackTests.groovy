package darpan.conclusion.brain

import darpan.conclusion.brain.fact.Ask
import darpan.conclusion.brain.fact.Conclusion
import darpan.conclusion.brain.fact.ShopifyOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-204. Dolls Kill "Every Shopify order is in NetSuite within a day": a Shopify order NetSuite has no sales order
 * for (by name, otherrefnum) takes its HotWax order's cause. Records as read on prod 2026-11-10 (darpan CLI, read-only:
 * HotWax OMS GraphQL by orderName, NetSuite SuiteQL; Shopify side from the Shopify-NetSuite workbook of 2026-11-06).
 */
class ShopifyInNetsuitePackTests {

    @AfterEach
    void forget() { BrainAnswerStore.clear() }

    private static Map shopify(String id, String name, String status, Object total, Object current, String hasTxn = "Y") {
        return [key: id, bucket: "MISSING_FROM_FILE_2", sides: [
                FILE_1: [presence: "KEPT", record: [id: id, name: name, displayFinancialStatus: status, hasPaymentTransaction: hasTxn,
                        totalPriceSet: [shopMoney: [amount: total]], currentTotalPriceSet: [shopMoney: [amount: current]]]],
                FILE_2: [presence: "ABSENT", firstFieldPresence: "ABSENT"]]]
    }

    private static Map hc(String orderId, String name, Object total, String status, String shipMethod, List<List> payments,
                          String netsuiteOrderId = null) {
        return [orderId: orderId, orderName: name, grandTotal: total, statusId: status,
                identifications: netsuiteOrderId ? [[orderIdentificationTypeId: "NETSUITE_ORDER_ID", idValue: netsuiteOrderId]] : [],
                shipGroups: [[shipGroupSeqId: "00001", shipmentMethodTypeId: shipMethod]],
                paymentPreferences: payments.collect { [paymentMethodTypeId: it[0], statusId: it[1], maxAmount: it[2]] },
                paymentPreferenceSlotsFull: false]
    }

    private static Map fire(List<Map> findings, Map byShopifyId) {
        Map world = [:]
        return BrainEngine.forPacks(["oms-netsuite"]).fire(findings.collectMany {
            BrainFactAdapter.facts("shopifyOrder", it, [:], world, [:], [omsOrderByShopifyId: byShopifyId])
        } as List<Object>)
    }

    private static Map<String, String> codes(Map fired) {
        ((List<Conclusion>) fired.conclusions).collectEntries { [(it.finding.key): it.cause.code] }
    }

    @Test
    void aShopifyOrderAsksHotwaxForItsOrderFirstAndConcludesNothingUntilThen() {
        Map fired = fire([shopify("7432475803905", "DK5053928", "PAID", 8.02, 8.02, "N")], null)
        List<Ask> asks = ((List<Ask>) fired.asks).findAll { it.question == "omsOrderByShopifyId" }
        assertEquals(["7432475803905"], asks.collect { ((ShopifyOrder) it.subject).shopifyId })
        assertEquals([:], codes(fired))
    }

    @Test
    void eachProbedOrderTakesItsCause() {
        Map fired = fire([
                // A Shipped Suite claim replacement: Shopify PAID with no transaction, HotWax no payment row.
                shopify("7432475803905", "DK5053928", "PAID", 8.02, 8.02, "N"),
                // PayPal payment failed: Shopify EXPIRED, HotWax PAYMENT_NOT_RECEIVED, still approved.
                shopify("7433687466241", "DK5061564", "EXPIRED", 56.19, 56.19, "N"),
                // A store sale with no payment: Shopify PENDING, $0 received.
                shopify("7448881463553", "DK5123020", "PENDING", 251.37, 251.37, "N"),
                // 360° Protection removed after the sale (before the 11-07 Data Manager fix).
                shopify("7435192041729", "DK5071285", "PAID", 88.67, 85.32),
                // Paid from abroad, 1.4% under.
                shopify("7430222643457", "DK5041811", "PAID", 46.27, 46.27),
                // Approved and paid, held by an open task in HotWax.
                shopify("7431815364865", "DK5051629", "PAID", 176.43, 176.43),
                shopify("7435414929665", "DK5072413", "PARTIALLY_REFUNDED", 100.26, 85.55),
                // A POS sale partly paid by gift card, all given back. The probe read 5 payment slots; the sixth (13.70
                // settled) is the workbook's "$75.88 of $191.84, all by gift card, fully refunded".
                shopify("7446497100033", "DK5114469", "REFUNDED", 191.84, 0),
        ], [
                "7432475803905": hc("166035", "DK5053928", 8.02, "ORDER_COMPLETED", "INTL_STANDARD", []),
                "7433687466241": hc("179784", "DK5061564", 56.19, "ORDER_APPROVED", "INTL_STANDARD",
                        [["EXT_SHOP_PAYPAL", "PAYMENT_NOT_RECEIVED", 56.19]]),
                "7448881463553": hc("257402", "DK5123020", 251.37, "ORDER_COMPLETED", "POS_COMPLETED", []),
                "7435192041729": hc("192189", "DK5071285", 88.67, "ORDER_COMPLETED", "STANDARD",
                        [["EXT_SHOP_PAYMENTS", "PAYMENT_SETTLED", 85.32]]),
                "7430222643457": hc("139749", "DK5041811", 46.27, "ORDER_COMPLETED", "INTL_STANDARD",
                        [["EXT_GIFT_CARD", "PAYMENT_SETTLED", 42.85], ["EXT_SHOP_PAYMENTS", "PAYMENT_SETTLED", 2.77]]),
                "7431815364865": hc("161384", "DK5051629", 149.37, "ORDER_APPROVED", "STANDARD",
                        [["EXT_SHOP_PAYMENTS", "PAYMENT_SETTLED", 149.37], ["EXT_SHOP_OTHR_GTWAY", "PAYMENT_SETTLED", 27.06]]),
                "7435414929665": hc("194898", "DK5072413", 100.26, "ORDER_APPROVED", "INTL_STANDARD",
                        [["EXT_GIFT_CARD", "PAYMENT_SETTLED", 100.26]]),
                "7446497100033": hc("247076", "DK5114469", 191.84, "ORDER_COMPLETED", "POS_COMPLETED",
                        [["EXT_GIFT_CARD", "PAYMENT_SETTLED", 21.68], ["EXT_GIFT_CARD", "PAYMENT_REFUNDED", 21.68],
                         ["EXT_GIFT_CARD", "PAYMENT_REFUNDED", 40.5], ["EXT_GIFT_CARD", "PAYMENT_REFUNDED", 13.7],
                         ["EXT_GIFT_CARD", "PAYMENT_SETTLED", 40.5], ["EXT_GIFT_CARD", "PAYMENT_SETTLED", 13.7]]),
        ])
        assertEquals([
                "7432475803905": "PAID_NO_TXN",
                "7433687466241": "PAY_EXPIRED",
                "7448881463553": "POS_UNPAID",
                "7435192041729": "LINE_REMOVED",
                "7430222643457": "FX_GAP",
                "7431815364865": "HELD_IN_HC",
                "7435414929665": "HELD_IN_HC",
                "7446497100033": "CONC_PARTIAL_PAYMENT_GIVEN_BACK",
        ], codes(fired))
        assertTrue(((List<Ask>) fired.asks).every { it.question != "orderPayments" }, "the HotWax answer carried the payments")
    }

    @Test
    void aShopifyOrderHotwaxDoesNotHaveIsNotInHotwax() {
        assertEquals(["9999": "NOT_IN_HC"], codes(fire([shopify("9999", "DK9999999", "PAID", 10, 10)], ["9999": null])))
    }

    @Test
    void anOrderHotwaxSentToNetsuiteUnderAnotherNameSaysSo() {
        // DK5042209 (released 11-08) as it would read if NetSuite's sales order lacked the name: HotWax holds its NetSuite id.
        Map fired = fire([shopify("7430297321729", "DK5042209", "PAID", 100.34, 100.34)], [
                "7430297321729": hc("141024", "DK5042209", 100.34, "ORDER_APPROVED", "STANDARD",
                        [["EXT_SHOP_PAYMENTS", "PAYMENT_SETTLED", 100.34]], "13652037")])
        assertEquals(["7430297321729": "NS_ID_NO_SO"], codes(fired))
    }

    @Test
    void aGapAboveTwoPercentOrAtHomeIsNotCurrency() {
        Map fired = fire([shopify("1", "DK1", "PAID", 100, 100), shopify("2", "DK2", "PAID", 100, 100)], [
                "1": hc("o1", "DK1", 100, "ORDER_COMPLETED", "INTL_STANDARD", [["EXT_SHOP_PAYMENTS", "PAYMENT_SETTLED", 97]]),
                "2": hc("o2", "DK2", 100, "ORDER_COMPLETED", "STANDARD", [["EXT_SHOP_PAYMENTS", "PAYMENT_SETTLED", 99]])])
        assertEquals([:], codes(fired))
    }

    @Test
    void anApprovedPaidHotwaxOrderOfAnotherCheckIsNotHeldInHotwax() {
        // Control: gorjana R7 reads HotWax orders, not Shopify ones; none of DAR-BE-204's causes may reach it.
        Map r7 = [key: "M1", bucket: "MISSING_FROM_FILE_2", sides: [
                FILE_1: [presence: "KEPT", record: [orderId: "M1", orderName: "#GOR1", grandTotal: 50, statusId: "ORDER_APPROVED"]],
                FILE_2: [presence: "ABSENT", firstFieldPresence: "ABSENT"]]]
        Map fired = BrainEngine.forPacks(["oms-netsuite"]).fire(BrainFactAdapter.facts("order", r7,
                [M1: [paymentPreferences: [[paymentMethodTypeId: "EXT_SHOP_PAYMENTS", statusId: "PAYMENT_SETTLED", maxAmount: 50]],
                      paymentPreferenceSlotsFull: false]], [:]) as List<Object>)
        assertEquals([:], codes(fired))
    }
}
