package darpan.conclusion.brain

import darpan.conclusion.brain.fact.Answered
import darpan.conclusion.brain.fact.Conclusion
import darpan.conclusion.brain.fact.Finding
import darpan.conclusion.brain.fact.Order
import darpan.conclusion.brain.fact.Payment
import darpan.conclusion.brain.fact.Return
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertSame
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-151. A finding as RunConclusionSupport builds it ([key, bucket, sides]) becomes typed facts. gorjana prod 2026-10-28. */
class BrainFactAdapterTests {

    private static Map r1(String orderId, Object total, String nsFirstField = "ABSENT") {
        return [key: "${orderId}/01".toString(), bucket: "MISSING_FROM_FILE_2", sides: [
                FILE_1: [presence: "KEPT", record: [omsOrderId: orderId, orderItemSeqId: "01", orderGrandTotal: total]],
                FILE_2: [presence: "ABSENT", firstFieldPresence: nsFirstField]]]
    }

    private static Map r2(String returnId, String orderId, Object total, String nsOrderId = null) {
        return [key: returnId, bucket: "MISSING_FROM_FILE_2", sides: [
                FILE_1: [presence: "KEPT", record: [returnId: returnId, orderId: orderId, orderGrandTotal: total, orderNetsuiteId: nsOrderId]],
                FILE_2: [presence: "ABSENT", firstFieldPresence: "ABSENT"]]]
    }

    private static Map answer(List rows, boolean slotsFull = false) {
        return [orderId: "M1088731", grandTotal: 371.54, paymentPreferences: rows, paymentPreferenceSlotsFull: slotsFull]
    }

    private static final List GIFT_CARD_GIVEN_BACK = [
            [paymentMethodTypeId: "EXT_SHOP_GFT_CARD", statusId: "PAYMENT_SETTLED", maxAmount: "181"],
            [paymentMethodTypeId: "EXT_SHOP_GFT_CARD", statusId: "PAYMENT_REFUNDED", maxAmount: "181"]]

    private static <T> List<T> of(List facts, Class<T> type) { facts.findAll { type.isInstance(it) } as List<T> }

    @Test
    void anR1LineBecomesAFindingAboutItsOrder() {
        List facts = BrainFactAdapter.facts("orderLine", r1("M1088731", 371.54), [:])
        Finding f = of(facts, Finding)[0]
        assertEquals("orderLine", f.kind)
        assertEquals("M1088731/01", f.key)
        assertFalse(f.netsuiteHasOrderLine)
        assertEquals(new BigDecimal("371.54"), f.order.total)
        assertSame(f.order, of(facts, Order)[0])
        assertTrue(BrainFactAdapter.facts("orderLine", r1("M1088731", 371.54, "KEPT"), [:]).find { it instanceof Finding }.netsuiteHasOrderLine)
    }

    @Test
    void anR2ReturnBecomesAFindingAboutItsReturnsOrder() {
        List facts = BrainFactAdapter.facts("return", r2("M248496", "M1088731", 371.54), [:])
        Finding f = of(facts, Finding)[0]
        Return ret = of(facts, Return)[0]
        assertEquals("return", f.kind)
        assertSame(ret, f.ret)
        assertEquals("M1088731", ret.order.orderId)
        assertNull(ret.order.netsuiteOrderId)
    }

    @Test
    void anAnsweredOrderGetsItsPaymentsAndAnAnsweredMarker() {
        List facts = BrainFactAdapter.facts("orderLine", r1("M1088731", 371.54), [M1088731: answer(GIFT_CARD_GIVEN_BACK)])
        List<Payment> payments = of(facts, Payment)
        assertEquals(["SETTLED", "REFUNDED"], payments*.state)
        assertEquals([new BigDecimal("181"), new BigDecimal("181")], payments*.amount)
        assertEquals(["EXT_SHOP_GFT_CARD"], payments*.tender.unique())
        assertTrue(of(facts, Order)[0].paymentsComplete)
        assertEquals("orderPayments", of(facts, Answered)[0].question)
    }

    /** Review Focus 1: a read whose slots filled may have cut a row off, so it proves nothing. */
    @Test
    void aCutShortAnswerGivesAnAnsweredMarkerButNoPayments() {
        List facts = BrainFactAdapter.facts("orderLine", r1("M1088731", 371.54), [M1088731: answer(GIFT_CARD_GIVEN_BACK, true)])
        assertEquals([], of(facts, Payment))
        assertFalse(of(facts, Order)[0].paymentsComplete)
        assertEquals(1, of(facts, Answered).size())
        List none = BrainFactAdapter.facts("orderLine", r1("M1088731", 371.54), [M1088731: null])
        assertEquals(1, of(none, Answered).size(), "asked, none found")
        assertEquals([], of(none, Payment))
    }

    /** Design D3: within one world each real thing is one fact; a second finding about the order adds what it knows. */
    @Test
    void twoFindingsOfOneOrderShareOneOrderFactInAWorld() {
        Map world = [:]
        List first = BrainFactAdapter.facts("orderLine", r1("M1088731", 371.54), [M1088731: answer(GIFT_CARD_GIVEN_BACK)], world)
        List second = BrainFactAdapter.facts("return", r2("M248496", "M1088731", 371.54, "NS1"), [M1088731: answer(GIFT_CARD_GIVEN_BACK)], world)
        assertSame(of(first, Order)[0], of(second, Return)[0].order)
        assertEquals([], of(second, Order), "the order is already in the world: not inserted twice")
        assertEquals([], of(second, Payment), "nor its payments")
        assertEquals("NS1", of(first, Order)[0].netsuiteOrderId)
    }

    /** Review Focus 3. */
    @Test
    void aRecordWithNoOrderIdGivesOnlyTheFinding() {
        List facts = BrainFactAdapter.facts("return", r2("M260128", null, 0), [:])
        assertEquals(1, facts.size())
        assertNull(((Finding) facts[0]).ret)
    }

    /** Review Focus 5. */
    @Test
    void aTotalThatIsNotANumberIsNull() {
        assertNull(of(BrainFactAdapter.facts("orderLine", r1("M1", "n/a"), [:]), Order)[0].total)
        assertNull(of(BrainFactAdapter.facts("orderLine", r1("M1", null), [:]), Order)[0].total)
    }

    /** DAR-BE-192: an order's date arrives as epoch millis (a number, or R1's string of digits) or an ISO date-time. */
    @Test
    void anOrdersDateReadsInEveryShapeTheReadsGiveIt() {
        assertEquals(1795194434000L, BrainFactAdapter.epochMillis("1795194434000"))
        assertEquals(1795248764000L, BrainFactAdapter.epochMillis(1795248764000L))
        assertEquals(1795194434000L, BrainFactAdapter.epochMillis("2026-11-20T17:07:14Z"))
        assertEquals(1795194434000L, BrainFactAdapter.epochMillis("2026-11-20T09:07:14-08:00"))
        assertNull(BrainFactAdapter.epochMillis("20 Nov"))
        assertNull(BrainFactAdapter.epochMillis(""))
        assertNull(BrainFactAdapter.epochMillis(null))
    }

    /** Final review I2: the extract keeps what the adapter reads, or an R2 return's order id is projected away and nothing is asked. */
    @Test
    void theRecordFieldsTheBrainReadsAreNamedPerKindAndSide() {
        assertEquals(["returnId", "orderId", "orderNetsuiteId", "orderGrandTotal"], BrainFactAdapter.recordFieldsFor("RETURN", "FILE_1"))
        // DAR-BE-186: an R1 line keeps its product and SKU, for the productNetsuiteId question.
        // DAR-BE-192: and its order's date, for the in-flight grace (R1 omsOrderDate, R7 orderDate).
        assertEquals(["omsOrderId", "orderGrandTotal", "productId", "sku", "orderName", "omsOrderName", "omsOrderDate"], BrainFactAdapter.recordFieldsFor("ORDER_LINE", "FILE_1"))
        assertEquals(["orderId", "orderName", "grandTotal", "statusId", "orderDate"], BrainFactAdapter.recordFieldsFor("ORDER", "FILE_1"))
        // DAR-BE-354: a Shopify order keeps its createdAt, for the in-flight grace.
        assertTrue(BrainFactAdapter.recordFieldsFor("SHOPIFY_ORDER", "FILE_1").contains("createdAt"))
        assertEquals([], BrainFactAdapter.recordFieldsFor("RETURN", "FILE_2"))
        assertEquals([], BrainFactAdapter.recordFieldsFor(null, "FILE_1"))
    }

    /** Dolls Kill R7 2026-11-25 (DK5051629 as probed 2026-11-10): approved and paid in HotWax, no NetSuite sales order. */
    private static Map shopify(Object createdAt) {
        return [key: "7431815364865", bucket: "MISSING_FROM_FILE_2", sides: [
                FILE_1: [presence: "KEPT", record: [id: "7431815364865", name: "DK5051629", displayFinancialStatus: "PAID",
                        hasPaymentTransaction: "Y", createdAt: createdAt,
                        totalPriceSet: [shopMoney: [amount: 176.43]], currentTotalPriceSet: [shopMoney: [amount: 176.43]]]],
                FILE_2: [presence: "ABSENT", firstFieldPresence: "ABSENT"]]]
    }

    private static final Map HOTWAX_HAS_IT = [omsOrderByShopifyId: ["7431815364865": [orderId: "161384", orderName: "DK5051629",
            grandTotal: 176.43, statusId: "ORDER_APPROVED", identifications: [], shipGroups: [[shipmentMethodTypeId: "STANDARD"]],
            paymentPreferences: [[paymentMethodTypeId: "EXT_SHOP_PAYMENTS", statusId: "PAYMENT_SETTLED", maxAmount: 149.37],
                                 [paymentMethodTypeId: "EXT_SHOP_OTHR_GTWAY", statusId: "PAYMENT_SETTLED", maxAmount: 27.06]],
            paymentPreferenceSlotsFull: false]]]

    /** 2026-11-25 12:00 UTC. */
    private static final long READ_AT = 1795608000000L

    private static List shopifyFacts(Object createdAt, Long readAt = READ_AT) {
        return BrainFactAdapter.facts("shopifyOrder", shopify(createdAt), [:], [:], [:], HOTWAX_HAS_IT, readAt)
    }

    /** DAR-BE-354: a Shopify order is aged from its Shopify createdAt to the read, as R1 and R7 orders are from theirs. */
    @Test
    void aShopifyOrderIsAgedFromItsCreatedAt() {
        assertEquals(new BigDecimal("2.00"), of(shopifyFacts("2026-11-25T10:00:00Z"), Order)[0].ageHours)
        assertEquals(new BigDecimal("0.18"), of(shopifyFacts("2026-11-25T03:49:00-08:00"), Order)[0].ageHours, "11 minutes, offset kept")
        assertNull(of(shopifyFacts(null), Order)[0].ageHours, "no createdAt: no age")
        assertNull(of(shopifyFacts("yesterday"), Order)[0].ageHours, "a createdAt that is no date: no age")
        assertNull(of(shopifyFacts("2026-11-25T10:00:00Z", null), Order)[0].ageHours, "no read time: no age")
    }

    /** DAR-BE-354: 281 of 283 R7 'held in HotWax' findings were under 24 h old and gone by the next check. */
    @Test
    void aYoungShopifyOrderIsOnItsWayAndAnOldOneIsHeld() {
        Closure<List<String>> codes = { Object createdAt ->
            ((List<Conclusion>) BrainEngine.forPacks(["oms-netsuite"]).fire(shopifyFacts(createdAt) as List<Object>).conclusions)
                    .collect { it.cause.code }
        }
        assertEquals(["NS_IN_FLIGHT"], codes("2026-11-25T10:00:00Z"))
        assertEquals(["HELD_IN_HC"], codes("2026-11-23T10:00:00Z"))
        assertEquals(["HELD_IN_HC"], codes(null))
    }

}
