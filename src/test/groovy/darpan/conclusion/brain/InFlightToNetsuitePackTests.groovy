package darpan.conclusion.brain

import darpan.conclusion.brain.fact.Cause
import darpan.conclusion.brain.fact.Conclusion
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-192 (Aditi 2026-11-21: "based on the pattern, should we add a grace to exchange sync?"). gorjana's month-close read
 * at 09:30 UTC; HotWax's daily catch-up sent 106 of R1's 115 "Not in NetSuite" orders at 10:47 UTC the same morning
 * (M1289823 EXC-#GOR195929958-1 -> SO7448318). Synced exchanges took p50 1.8 h, p90 13.8 h, max 17.6 h. An order NetSuite
 * lacks that was younger than the grace (24 h) when read is on its way, expected; past it, its real cause is named.
 */
class InFlightToNetsuitePackTests {

    /** gorjana's 2026-11-21 morning read (run folder 20261121-093026833). */
    static final long READ_AT = 1795253426833L

    @AfterEach
    void forget() { BrainAnswerStore.clear() }

    private static Map r1(String orderId, String name, Object total, Object omsOrderDate) {
        return [key: "${orderId}/01".toString(), bucket: "MISSING_FROM_FILE_2", sides: [
                FILE_1: [presence: "KEPT", record: [omsOrderId: orderId, orderItemSeqId: "01", orderGrandTotal: total, orderName: name,
                                                     omsOrderDate: omsOrderDate]],
                FILE_2: [presence: "ABSENT", firstFieldPresence: "ABSENT"]]]
    }

    private static Map r7(String orderId, String name, Object total, Object orderDate) {
        return [key: orderId, bucket: "MISSING_FROM_FILE_2", sides: [
                FILE_1: [presence: "KEPT", record: [orderId: orderId, orderName: name, grandTotal: total, statusId: "ORDER_APPROVED",
                                                     orderDate: orderDate]],
                FILE_2: [presence: "ABSENT", firstFieldPresence: "ABSENT"]]]
    }

    private static Map paid(String orderId, Object amount) {
        return [(orderId): [paymentPreferences: [[paymentMethodTypeId: "EXCHANGE_CREDIT", statusId: "PAYMENT_SETTLED", maxAmount: amount]],
                            paymentPreferenceSlotsFull: false]]
    }

    /** The exchange's return, credited in NetSuite: on its own, EXC_NOT_SENT (rank 50). */
    private static Map credited(String orderId, String returnId) {
        return [replacementReturn: [(orderId): [returnId: returnId, statusId: "RETURN_COMPLETED", returnChannelEnumId: "AFTSHIP_RTN_CHANNEL",
                                                returnCredit: 60, entryDate: 1795194406636L]],
                returnRma        : [(returnId): [tranId: "RMA385821", status: "Return Authorization : Pending Receipt"]],
                returnMemo       : [(returnId): [tranId: "CM7691176", amount: 272.85]]]
    }

    private static Map fire(String kind, Map finding, Map payments, Map more, Long readAt, Map params = [:]) {
        return BrainEngine.forPacks(["oms-netsuite"]).fire(
                BrainFactAdapter.facts(kind, finding, payments, [:], [:], more, readAt) as List<Object>, params)
    }

    private static List<String> codes(Map fired) { ((List<Conclusion>) fired.conclusions).collect { it.cause.code } }

    @Test
    void anExchangeReadSixteenHoursAfterItWasRungUpIsOnItsWay() {
        // M1289823 rung up 2026-11-20 17:07:14 UTC, read 16.39 h later; its return is credited, which alone would name 50.
        Map fired = fire("orderLine", r1("M1289823", "EXC-#GOR195929958-1", 275.4, "1795194434000"), paid("M1289823", 275.4),
                credited("M1289823", "M284109"), READ_AT)
        assertEquals(["NS_IN_FLIGHT"], codes(fired))
        Cause c = ((List<Conclusion>) fired.conclusions)[0].cause
        assertEquals(99, c.rank)
        assertTrue(c.expected, "inside the grace nothing is wrong yet")
        assertEquals("16.39 h old, grace 24 h", BrainConclude.fill("{order.ageHours} h old, grace {hours} h",
                c.because.find { it.getClass().simpleName == "OrderInFlightToNetsuite" }))
    }

    @Test
    void pastTheGraceTheRealCauseIsNamed() {
        // M1212896 EXC-#GOR197418622-1, rung up 2026-11-02 20:38 UTC, still not in NetSuite on 2026-11-21.
        Map fired = fire("orderLine", r1("M1212896", "EXC-#GOR197418622-1", 90.69, "1793651880000"), paid("M1212896", 90.69),
                credited("M1212896", "M270100"), READ_AT)
        assertEquals(["EXC_NOT_SENT"], codes(fired))
        // A smaller grace a tenant sets moves the line: M1289823 at 16.39 h is past a 12 h grace.
        assertEquals(["EXC_NOT_SENT"], codes(fire("orderLine", r1("M1289823", "EXC-#GOR195929958-1", 275.4, "1795194434000"),
                paid("M1289823", 275.4), credited("M1289823", "M284109"), READ_AT, [inFlightHours: 12])))
    }

    @Test
    void itOutranksEveryOrderCauseAndReachesR7() {
        // Unpaid (51) and in flight: in flight; an R7 approved order reads its numeric orderDate.
        assertEquals(["NS_IN_FLIGHT"], codes(fire("order", r7("M1294079", "#GOR197496257", 143.01, 1795248764000L),
                paid("M1294079", 10), [:], READ_AT)))
    }

    @Test
    void noAgeMeansNoGrace() {
        // No read time, no order date, or an order dated after the read: nothing is in flight, the finding stands.
        assertEquals(["EXC_NOT_SENT"], codes(fire("orderLine", r1("M1289823", "EXC-#GOR195929958-1", 275.4, "1795194434000"),
                paid("M1289823", 275.4), credited("M1289823", "M284109"), null)))
        assertEquals(["EXC_NOT_SENT"], codes(fire("orderLine", r1("M1289823", "EXC-#GOR195929958-1", 275.4, null),
                paid("M1289823", 275.4), credited("M1289823", "M284109"), READ_AT)))
        assertEquals([], codes(fire("orderLine", r1("M1", "#GOR1", 50, "1795194434000"), paid("M1", 50), [:], READ_AT,
                [inFlightHours: 0])), "a 0 h grace holds nothing")
    }

    /** Inside the grace no question is worth asking: the young order costs no lookup. */
    @Test
    void anOrderInFlightAsksNothing() {
        List<String> calls = []
        Map out = BrainConclude.run([kind: "orderLine", tenantId: "GORJANA", readAtMillis: READ_AT,
                findings: [r1("M1289823", "EXC-#GOR195929958-1", 275.4, "1795194434000")],
                fetchQuestion: { String q, List<String> ids -> calls.add(q); return [:] }])
        assertEquals("NS_IN_FLIGHT", ((Map) ((Map) out.byKey).get("M1289823/01")).code)
        assertEquals([], calls)
    }
}
