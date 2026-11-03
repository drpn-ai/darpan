package darpan.conclusion.brain

import darpan.conclusion.brain.fact.Ask
import darpan.conclusion.brain.fact.BestCause
import darpan.conclusion.brain.fact.Cause
import darpan.conclusion.brain.fact.Conclusion
import darpan.conclusion.brain.fact.Order
import darpan.conclusion.brain.fact.Product
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-186. gorjana R1, October 2026, as probed live on 2026-11-03: M1090448 (#GOR197325417, Park Meadows) sold 2 x
 * 238-007-G-OLD, product 10273, which has no NETSUITE_PRODUCT_ID; its live twin 238-007-G (10278, same UPC) carries 28792.
 * The order never reached NetSuite and R1 said only "Not in NetSuite". The brain asks for the item's product, and concludes
 * "Sold on a retired SKU" (66) over the plain "Product not in NetSuite" (65).
 */
class RetiredSkuPackTests {

    @AfterEach
    void forget() { BrainAnswerStore.clear() }

    private static Map r1(String orderId, String productId, String sku, String seq = "01") {
        return [key: "${orderId}/${seq}".toString(), bucket: "MISSING_FROM_FILE_2", sides: [
                FILE_1: [presence: "KEPT", record: [omsOrderId: orderId, orderItemSeqId: seq, orderGrandTotal: 697.06,
                                                   productId: productId, sku: sku]],
                FILE_2: [presence: "ABSENT", firstFieldPresence: "ABSENT"]]]
    }

    private static Map product(String productId, String sku, String netsuiteId) {
        return [productId: productId, internalName: sku, netsuiteProductId: netsuiteId]
    }

    private static Map paidInFull(Object amount) {
        return [paymentPreferences: [[paymentMethodTypeId: "EXT_SHOP_PAYMENTS", statusId: "PAYMENT_SETTLED", maxAmount: amount]],
                paymentPreferenceSlotsFull: false]
    }

    private static Map fire(List<Map> findings, Map payments, Map products) {
        Map world = [:]
        return BrainEngine.forPacks(["oms-netsuite"]).fire(findings.collectMany {
            BrainFactAdapter.facts("orderLine", it, payments, world, products)
        } as List<Object>)
    }

    private static List<String> codes(Map fired) { ((List<Conclusion>) fired.conclusions).collect { it.cause.code } }

    @Test
    void anOrderNotInNetsuiteAsksForItsItemsProducts() {
        Map fired = fire([r1("M1090448", "10273", "238-007-G-OLD")], [:], [:])
        Ask ask = ((List<Ask>) fired.asks).find { it.question == "productNetsuiteId" }
        assertEquals("10273", ((Product) ask.subject).productId)
        assertEquals("M1090448", ask.forOrder.orderId, "the order's best cause decides whether the answer can still matter")
        assertEquals([], codes(fired))
    }

    @Test
    void aRetiredOldSkuWithNoNetsuiteIdConcludesSoldOnARetiredSku() {
        Map fired = fire([r1("M1090448", "10273", "238-007-G-OLD", "01"), r1("M1090448", "10273", "238-007-G-OLD", "02")],
                [M1090448: paidInFull(697.06)], ["10273": product("10273", "238-007-G-OLD", null)])
        assertEquals(["CONC_RETIRED_SKU", "CONC_RETIRED_SKU"], codes(fired), "both lines take their order's cause")
        Cause c = ((List<Conclusion>) fired.conclusions)[0].cause
        assertEquals(66, c.rank)
        assertFalse(c.expected, "a fix is owed in HotWax")
        assertEquals("238-007-G-OLD -> 238-007-G",
                BrainConclude.fill("{product.sku} -> {liveSku}", c.because.find { it.getClass().simpleName == "SoldOnRetiredSku" }))
    }

    @Test
    void theSkuCanComeFromTheAnswerWhenTheLineHadNone() {
        assertEquals(["CONC_RETIRED_SKU"], codes(fire([r1("M1100892", "10274", null)], [M1100892: paidInFull(856.05)],
                ["10274": product("10274", "238-009-G-OLD", null)])))
    }

    @Test
    void aProductWithNoNetsuiteIdAndALiveSkuIsProductNotInNetsuite() {
        assertEquals(["CONC_PRODUCT_NOT_IN_NETSUITE"], codes(fire([r1("M1", "12360", "HG-NECK")], [M1: paidInFull(10)],
                ["12360": product("12360", "HG-NECK", null)])))
    }

    @Test
    void controlsDoNotConclude() {
        // 10278 238-007-G carries its NetSuite id; a product HotWax does not have at all answers nothing (absent) and stays unanswered.
        assertEquals([], codes(fire([r1("M2", "10278", "238-007-G")], [M2: paidInFull(10)], ["10278": product("10278", "238-007-G", "28792")])))
        Map inNetsuite = r1("M1090448", "10273", "238-007-G-OLD")
        inNetsuite.sides.FILE_2.firstFieldPresence = "KEPT"
        Map fired = fire([inNetsuite], [:], [:])
        assertEquals([], fired.asks, "an order NetSuite has asks nothing about its products")
    }

    @Test
    void partialPaymentGivenBackStillOutranksARetiredSku() {
        Map payments = [M1090448: [paymentPreferences: [
                [paymentMethodTypeId: "EXT_SHOP_GFT_CARD", statusId: "PAYMENT_SETTLED", maxAmount: 100],
                [paymentMethodTypeId: "EXT_SHOP_GFT_CARD", statusId: "PAYMENT_REFUNDED", maxAmount: 100]], paymentPreferenceSlotsFull: false]]
        Map fired = fire([r1("M1090448", "10273", "238-007-G-OLD")], payments, ["10273": product("10273", "238-007-G-OLD", null)])
        assertEquals(["CONC_PARTIAL_PAYMENT_GIVEN_BACK"], codes(fired))
        assertTrue(((Map) fired.bestCauses).values().every { ((Cause) it).rank == 80 })
    }

    @Test
    void theProductQuestionUnlocksUpToRank66() {
        assertEquals(66, BrainQuestions.mayEnableRanks(BrainEngine.forPacks(["oms-netsuite"])).productNetsuiteId)
    }

    @Test
    void aProductAskDroppedForOneOrderIsStillAskedForAnother() {
        Order settled = new Order(orderId: "M1")
        Order open = new Order(orderId: "M2")
        Product old = new Product(productId: "10273")
        Map plan = BrainAskPlanner.plan([new Ask("productNetsuiteId", old, settled), new Ask("productNetsuiteId", old, open)],
                [(settled): Cause.expected(settled, "CONC_PARTIAL_PAYMENT_GIVEN_BACK", 80)], "GORJANA", [productNetsuiteId: 66])
        assertEquals([productNetsuiteId: ["10273"]], plan.toFetch)
        assertEquals(1, plan.dropped)
    }

    @Test
    void theDefaultLookupReadsHotwaxProductsById() {
        Map lookup = BrainQuestions.defaultLookup("productNetsuiteId", "gorjana_oms")
        assertEquals("OMS_PRODUCTS_GQL", lookup.systemEnumId)
        assertEquals("productId", lookup.keyField)
        assertEquals("gorjana_oms", lookup.sourceConfigId)
    }

    @Test
    void theR1ExtractKeepsTheItemsProductAndSku() {
        assertTrue(BrainFactAdapter.recordFieldsFor("ORDER_LINE", "FILE_1").containsAll(["productId", "sku"]))
    }
}
