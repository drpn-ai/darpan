package darpan.conclusion.brain

import darpan.conclusion.brain.fact.Ask
import darpan.conclusion.brain.fact.Conclusion
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows

/**
 * DAR-BE-229. The money tolerance, the currency shortfall share and the replacement-name prefixes are GraphParam facts
 * inserted from BrainEngine.PARAM_DEFAULTS, with an optional per-call override. The defaults give today's conclusions.
 */
class GraphParamPackTests {

    @AfterEach
    void forget() { BrainAnswerStore.clear() }

    private static Map shopify(String id, String name, Object total) {
        return [key: id, bucket: "MISSING_FROM_FILE_2", sides: [
                FILE_1: [presence: "KEPT", record: [id: id, name: name, displayFinancialStatus: "PAID", hasPaymentTransaction: "Y",
                        totalPriceSet: [shopMoney: [amount: total]], currentTotalPriceSet: [shopMoney: [amount: total]]]],
                FILE_2: [presence: "ABSENT", firstFieldPresence: "ABSENT"]]]
    }

    private static Map hc(String orderId, String name, Object total, String status, String shipMethod, Object paid) {
        return [orderId: orderId, orderName: name, grandTotal: total, statusId: status, identifications: [],
                shipGroups: [[shipGroupSeqId: "00001", shipmentMethodTypeId: shipMethod]],
                paymentPreferences: [[paymentMethodTypeId: "EXT_SHOP_PAYMENTS", statusId: "PAYMENT_SETTLED", maxAmount: paid]],
                paymentPreferenceSlotsFull: false]
    }

    private static Map fireShopify(Map shopifyOrder, Map hotwaxOrder, Map overrides = [:]) {
        Map world = [:]
        List<Object> facts = BrainFactAdapter.facts("shopifyOrder", shopifyOrder, [:], world, [:],
                [omsOrderByShopifyId: [(shopifyOrder.key as String): hotwaxOrder]]) as List<Object>
        return BrainEngine.forPacks(["oms-netsuite"]).fire(facts, overrides)
    }

    private static List<String> codes(Map fired) { ((List<Conclusion>) fired.conclusions).collect { it.cause.code } }

    private static Map orderLine(String orderId, String name) {
        return [key: "${orderId}/01".toString(), bucket: "MISSING_FROM_FILE_2", sides: [
                FILE_1: [presence: "KEPT", record: [omsOrderId: orderId, orderItemSeqId: "01", orderGrandTotal: 50, orderName: name]],
                FILE_2: [presence: "ABSENT", firstFieldPresence: "ABSENT"]]]
    }

    private static List<String> asked(String name, Map overrides = [:]) {
        Map answers = [(("O1")): [paymentPreferences: [[paymentMethodTypeId: "EXCHANGE_CREDIT", statusId: "PAYMENT_SETTLED", maxAmount: 50]],
                                  paymentPreferenceSlotsFull: false]]
        Map fired = BrainEngine.forPacks(["oms-netsuite"]).fire(
                BrainFactAdapter.facts("orderLine", orderLine("O1", name), answers, [:]) as List<Object>, overrides)
        return ((List<Ask>) fired.asks)*.question.findAll { it == "replacementReturn" }
    }

    @Test
    void theDefaultsAreTodaysValues() {
        assertEquals([moneyTolerance: new BigDecimal("0.01"), currencyShortfallShare: new BigDecimal("0.02"),
                      replacementPrefixes: "EXC|WTY", exchangePrefixes: "EXC"], BrainEngine.PARAM_DEFAULTS)
        assertEquals(4, BrainEngine.params([:]).size())
    }

    @Test
    void aShareOverrideFlipsTheCurrencyConclusion() {
        // Paid 3% under, shipped abroad: not currency at the default 2%.
        Map order = hc("o1", "N1", 100, "ORDER_COMPLETED", "INTL_STANDARD", 97)
        assertEquals([], codes(fireShopify(shopify("1", "N1", 100), order)))
        assertEquals(["FX_GAP"], codes(fireShopify(shopify("1", "N1", 100), order, [currencyShortfallShare: new BigDecimal("0.05")])))
        // Paid 1% under: currency by default, not at a 0.5% share.
        Map near = hc("o2", "N2", 100, "ORDER_COMPLETED", "INTL_STANDARD", 99)
        assertEquals(["FX_GAP"], codes(fireShopify(shopify("2", "N2", 100), near)))
        assertEquals([], codes(fireShopify(shopify("2", "N2", 100), near, [currencyShortfallShare: "0.005"])))
    }

    @Test
    void aToleranceOverrideFlipsTheApprovedAndPaidConclusion() {
        // Approved, paid 0.04 under its total: held in HotWax only when the tolerance reaches that far.
        Map order = hc("o3", "N3", 100, "ORDER_APPROVED", "STANDARD", 99.96)
        assertEquals([], codes(fireShopify(shopify("3", "N3", 100), order)))
        assertEquals(["HELD_IN_HC"], codes(fireShopify(shopify("3", "N3", 100), order, [moneyTolerance: new BigDecimal("0.05")])))
        Map exact = hc("o4", "N4", 100, "ORDER_APPROVED", "STANDARD", 99.99)
        assertEquals(["HELD_IN_HC"], codes(fireShopify(shopify("4", "N4", 100), exact)))
    }

    @Test
    void aPrefixOverrideChangesWhichOrdersAreReplacements() {
        assertEquals(["replacementReturn"], asked("EXC-1001-1"))
        assertEquals(["replacementReturn"], asked("wty-1001-1"))
        assertEquals([], asked("RPL-1001-1"))
        assertEquals(["replacementReturn"], asked("RPL-1001-1", [replacementPrefixes: "RPL"]))
        assertEquals([], asked("EXC-1001-1", [replacementPrefixes: "RPL"]))
    }

    @Test
    void anUnknownOrEmptyOverrideIsRefused() {
        assertThrows(IllegalArgumentException) { BrainEngine.params([noSuchValue: 1]) }
        assertThrows(IllegalArgumentException) { BrainEngine.params([moneyTolerance: null]) }
    }
}
