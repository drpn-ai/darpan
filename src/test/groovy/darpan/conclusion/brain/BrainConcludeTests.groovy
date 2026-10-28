package darpan.conclusion.brain

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-151. A scope's brain pass: fire, plan the asks, fetch, fire again, conclude by key. */
class BrainConcludeTests {

    private static final String CODE = "CONC_PARTIAL_PAYMENT_GIVEN_BACK"
    private static final String KEY = "M1088731\u001F01"

    @BeforeEach
    void clear() { BrainAnswerStore.clear() }

    private static Map r1() {
        return [key: KEY, bucket: "MISSING_FROM_FILE_2", sides: [
                FILE_1: [presence: "KEPT", record: [omsOrderId: "M1088731", orderItemSeqId: "01", orderGrandTotal: 371.54]],
                FILE_2: [presence: "ABSENT", firstFieldPresence: "ABSENT"]]]
    }

    private static Map r2() {
        return [key: "M248496", bucket: "MISSING_FROM_FILE_2", sides: [
                FILE_1: [presence: "KEPT", record: [returnId: "M248496", orderId: "M1088731", orderGrandTotal: 371.54]],
                FILE_2: [presence: "ABSENT", firstFieldPresence: "ABSENT"]]]
    }

    private static final Map PAYMENTS = [M1088731: [orderId: "M1088731", grandTotal: 371.54, paymentPreferenceSlotsFull: false,
            paymentPreferences: [[paymentMethodTypeId: "EXT_SHOP_GFT_CARD", statusId: "PAYMENT_SETTLED", maxAmount: "181"],
                                 [paymentMethodTypeId: "EXT_SHOP_GFT_CARD", statusId: "PAYMENT_REFUNDED", maxAmount: "181"]]]]

    private static Map run(String kind, Map finding, Closure fetch) {
        return BrainConclude.run([kind: kind, tenantId: "GORJANA", findings: [finding], fetchQuestion: fetch,
                                  labelFor: { String code -> code == CODE ? "Partial payment given back" : code }])
    }

    @Test
    void roundTwoConcludesWhatRoundOneAsked() {
        List asked = []
        Map out = run("orderLine", r1(), { String q, List ids -> asked << [q, ids]; PAYMENTS })
        assertEquals([["orderPayments", ["M1088731"]]], asked)
        Map c = (Map) out.byKey[KEY]
        assertEquals(CODE, c.code)
        assertEquals("Partial payment given back", c.label)
        assertEquals(1, out.asked)
        assertTrue(((List<Map>) c.checks).any { it.label == "The order is not in NetSuite" })
        assertTrue(((List<Map>) c.checks).any { it.label == "Part of the order was paid and given back" && it.value == "181 of 371.54" })
        assertEquals(CODE, ((List<Map>) c.path)[0].code)
    }

    /** Review Focus 2. */
    @Test
    void aFailedQuestionLeavesTheFindingToTheTreeAndIsNotRemembered() {
        Map out = run("orderLine", r1(), { String q, List ids -> throw new IllegalStateException("OMS 504") })
        assertEquals([:], out.byKey)
        assertEquals(1, ((List) out.warnings).size())
        assertTrue(((List) out.warnings)[0].toString().contains("OMS 504"))
        assertFalse(BrainAnswerStore.has("GORJANA", "orderPayments", "M1088731"))
    }

    @Test
    void anOrderNothingWasFoundForIsRememberedAndNotConcluded() {
        Map out = run("orderLine", r1(), { String q, List ids -> [:] })
        assertEquals([:], out.byKey)
        assertTrue(BrainAnswerStore.has("GORJANA", "orderPayments", "M1088731"))
    }

    /** One brain: R2 reuses the payments R1 asked for minutes earlier; nothing is asked twice. */
    @Test
    void aSecondCheckReusesTheFirstChecksAnswers() {
        run("orderLine", r1(), { String q, List ids -> PAYMENTS })
        int calls = 0
        Map out = run("return", r2(), { String q, List ids -> calls++; PAYMENTS })
        assertEquals(0, calls)
        assertEquals(1, out.reused)
        assertEquals(CODE, ((Map) out.byKey.M248496).code)
    }
}
