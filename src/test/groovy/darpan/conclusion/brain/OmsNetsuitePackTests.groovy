package darpan.conclusion.brain

import darpan.conclusion.brain.fact.Ask
import darpan.conclusion.brain.fact.Conclusion
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-151 / DAR-BE-150. The oms-netsuite pack on gorjana prod orders as the 2026-10-28 probe read them: a payment taken
 * and given back in full, less than the order total, is "Partial payment given back" on R1 and R2 alike.
 */
class OmsNetsuitePackTests {

    private static final String CODE = "CONC_PARTIAL_PAYMENT_GIVEN_BACK"

    private static Map r1(String orderId, Object total, String seq = "01") {
        return [key: "${orderId}/${seq}".toString(), bucket: "MISSING_FROM_FILE_2", sides: [
                FILE_1: [presence: "KEPT", record: [omsOrderId: orderId, orderItemSeqId: seq, orderGrandTotal: total]],
                FILE_2: [presence: "ABSENT", firstFieldPresence: "ABSENT"]]]
    }

    private static Map r2(String returnId, String orderId, Object total) {
        return [key: returnId, bucket: "MISSING_FROM_FILE_2", sides: [
                FILE_1: [presence: "KEPT", record: [returnId: returnId, orderId: orderId, orderGrandTotal: total]],
                FILE_2: [presence: "ABSENT", firstFieldPresence: "ABSENT"]]]
    }

    private static Map paid(String tender, String state, Object amount) {
        return [paymentMethodTypeId: tender, statusId: "PAYMENT_" + state, maxAmount: amount]
    }

    private static Map answer(List rows) { [paymentPreferences: rows, paymentPreferenceSlotsFull: false] }

    private static Map fire(List<List> parts) {
        return BrainEngine.forPacks(["oms-netsuite"]).fire(parts.collectMany { it } as List<Object>)
    }

    private static List facts(String kind, Map finding, Map answers) { BrainFactAdapter.facts(kind, finding, answers) }

    private static List<String> codes(Map fired) { ((List<Conclusion>) fired.conclusions).collect { it.cause.code } }

    private static final List M1088731 = [paid("EXT_SHOP_GFT_CARD", "SETTLED", "181"), paid("EXT_SHOP_GFT_CARD", "REFUNDED", "181")]

    @Test
    void aGiftCardSwipeGivenBackConcludesOnR1AndR2() {
        Map answers = [M1088731: answer(M1088731)]
        Map fired = fire([facts("orderLine", r1("M1088731", 371.54), answers), facts("return", r2("M248496", "M1088731", 371.54), answers)])
        assertEquals([CODE, CODE], codes(fired))
        assertEquals(["M1088731/01", "M248496"] as Set, ((List<Conclusion>) fired.conclusions)*.finding*.key as Set)
        Conclusion c = ((List<Conclusion>) fired.conclusions)[0]
        assertEquals(80, c.cause.rank)
        assertTrue(c.cause.expected)
    }

    @Test
    void cashAndGiftCardPlusStoreCreditConcludeToo() {
        assertEquals([CODE], codes(fire([facts("orderLine", r1("M1090357", 109), [M1090357: answer([
                paid("EXT_SHOP_CASH", "SETTLED", 10), paid("EXT_SHOP_CASH", "REFUNDED", 10)])])])))
        assertEquals([CODE], codes(fire([facts("orderLine", r1("M1145316", 1416.45), [M1145316: answer([
                paid("SHOP_STORE_CREDIT", "SETTLED", 209.47), paid("EXT_SHOP_GFT_CARD", "SETTLED", 244.95),
                paid("SHOP_STORE_CREDIT", "REFUNDED", "209.470"), paid("EXT_SHOP_GFT_CARD", "REFUNDED", 244.95)])])])))
    }

    @Test
    void controlsDoNotConclude() {
        // M1190441: part of a card payment refunded. M1155880: an exchange (card taken, exchange credit refunded).
        // M1114606: paid in full, refunded in full: a real sale.
        assertEquals([], codes(fire([facts("orderLine", r1("M1190441", 84.44), [M1190441: answer([
                paid("EXT_SHOP_PAYMENTS", "SETTLED", 129.91), paid("EXT_SHOP_PAYMENTS", "REFUNDED", 45.47)])])])))
        assertEquals([], codes(fire([facts("orderLine", r1("M1155880", 200), [M1155880: answer([
                paid("EXT_SHOP_PAYMENTS", "SETTLED", 200), paid("EXCHANGE_CREDIT", "REFUNDED", 200)])])])))
        assertEquals([], codes(fire([facts("orderLine", r1("M1114606", 0.01), [M1114606: answer([
                paid("EXT_SHOP_CASH", "SETTLED", 0.01), paid("EXT_SHOP_CASH", "REFUNDED", 0.01)])])])))
    }

    @Test
    void anOrderNotInNetsuiteWithNoAnswerAsksForItsPayments() {
        Map fired = fire([facts("orderLine", r1("M1088731", 371.54), [:])])
        assertEquals(["orderPayments"], ((List<Ask>) fired.asks)*.question)
        assertEquals("M1088731", ((List<Ask>) fired.asks)[0].subject.orderId)
        assertEquals([], codes(fired))
    }

    @Test
    void anAnsweredOrderIsNotAskedAgain() {
        assertEquals([], fire([facts("orderLine", r1("M1088731", 371.54), [M1088731: null])]).asks)
    }

    /** Review Focus 4. */
    @Test
    void twoLinesOfOneOrderAskOnceAndConcludeAlike() {
        assertEquals(1, ((List) fire([facts("orderLine", r1("M1088731", 371.54, "01"), [:]),
                                      facts("orderLine", r1("M1088731", 371.54, "02"), [:])]).asks).size())
        Map answers = [M1088731: answer(M1088731)]
        assertEquals([CODE, CODE], codes(fire([facts("orderLine", r1("M1088731", 371.54, "01"), answers),
                                               facts("orderLine", r1("M1088731", 371.54, "02"), answers)])))
    }

    /** Review Focus 5. */
    @Test
    void aMissingTotalNeverConcludes() {
        assertEquals([], codes(fire([facts("orderLine", r1("M1088731", null), [M1088731: answer(M1088731)])])))
    }

    /** One world: an R1 line NetSuite has, and an R2 return whose order carries its NetSuite id, are about ONE order. */
    @Test
    void anOrderNetsuiteHasIsNeitherAskedNorConcluded() {
        Map line = r1("M1088731", 371.54)
        line.sides.FILE_2.firstFieldPresence = "KEPT"
        Map ret = r2("M248496", "M1088731", 371.54)
        ret.sides.FILE_1.record.orderNetsuiteId = "NS1"
        Map world = [:]
        Map answers = [M1088731: answer(M1088731)]
        Map fired = fire([BrainFactAdapter.facts("orderLine", line, answers, world), BrainFactAdapter.facts("return", ret, answers, world)])
        assertEquals([], codes(fired))
        assertEquals([], fired.asks)
    }

    /** Final review I1: "given back in full" is per amount, not per tender: two gift cards, one given back, is a kept payment. */
    @Test
    void twoGiftCardsWithOnlyOneGivenBackDoNotConclude() {
        assertEquals([], codes(fire([facts("orderLine", r1("M1", 371.54), [M1: answer([
                paid("EXT_SHOP_GFT_CARD", "SETTLED", 100), paid("EXT_SHOP_GFT_CARD", "SETTLED", 81),
                paid("EXT_SHOP_GFT_CARD", "REFUNDED", 100)])])])))
        assertEquals([CODE], codes(fire([facts("orderLine", r1("M1", 371.54), [M1: answer([
                paid("EXT_SHOP_GFT_CARD", "SETTLED", 100), paid("EXT_SHOP_GFT_CARD", "SETTLED", 81),
                paid("EXT_SHOP_GFT_CARD", "REFUNDED", 181)])])])), "two swipes given back in one refund is still all given back")
    }

    /** Final review I1: a taken row with no amount proves nothing, and must never fail the conclude pass. */
    @Test
    void aTakenRowWithNoAmountNeverConcludesAndNeverThrows() {
        assertEquals([], codes(fire([facts("orderLine", r1("M1", 371.54), [M1: answer([
                paid("EXT_SHOP_GFT_CARD", "SETTLED", 181), paid("EXT_SHOP_GFT_CARD", "REFUNDED", 181),
                paid("EXT_SHOP_GFT_CARD", "SETTLED", null)])])])))
    }

    /** Final review (re-graded Important): only a finding MISSING from NetSuite takes its order's cause; a duplicate is its own defect. */
    @Test
    void aDuplicateRowOfAPartlyPaidOrderKeepsItsOwnConclusion() {
        Map answers = [M1088731: answer(M1088731)]
        Map world = [:]
        Map dup = r1("M1088731", 371.54, "02") + [bucket: "DUPLICATE_IN_FILE_2"]
        Map fired = fire([BrainFactAdapter.facts("orderLine", r1("M1088731", 371.54), answers, world),
                          BrainFactAdapter.facts("orderLine", dup, answers, world)])
        assertEquals(["M1088731/01"], ((List<Conclusion>) fired.conclusions)*.finding*.key)
    }

    /** Review Focus 5, with the fallback ruled on: a total the record lacks is taken from the OMS answer for the same order. */
    @Test
    void aTotalOnlyTheOmsAnswerCarriesIsUsed() {
        Map a = answer(M1088731) + [grandTotal: 371.54]
        assertEquals([CODE], codes(fire([facts("orderLine", r1("M1088731", null), [M1088731: a])])))
    }

}
