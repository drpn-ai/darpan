package darpan.conclusion.brain

import darpan.conclusion.brain.fact.Answered
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

    /** Final review I2: the extract keeps what the adapter reads, or an R2 return's order id is projected away and nothing is asked. */
    @Test
    void theRecordFieldsTheBrainReadsAreNamedPerKindAndSide() {
        assertEquals(["returnId", "orderId", "orderNetsuiteId", "orderGrandTotal"], BrainFactAdapter.recordFieldsFor("RETURN", "FILE_1"))
        assertEquals(["omsOrderId", "orderGrandTotal"], BrainFactAdapter.recordFieldsFor("ORDER_LINE", "FILE_1"))
        assertEquals([], BrainFactAdapter.recordFieldsFor("RETURN", "FILE_2"))
        assertEquals([], BrainFactAdapter.recordFieldsFor(null, "FILE_1"))
    }

}
