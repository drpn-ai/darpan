package darpan.conclusion.brain

import darpan.conclusion.brain.fact.Ask
import darpan.conclusion.brain.fact.Cause
import darpan.conclusion.brain.fact.Conclusion
import darpan.conclusion.brain.fact.Order
import darpan.conclusion.brain.fact.ReplacementReturn
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-192. HotWax holds an exchange or warranty replacement back from NetSuite until its return has a linked credit memo.
 * Shapes probed live on gorjana 2026-11-04 (OmsReturnGateProbe + SuiteQL, OmsReplacementLinkProbe): the replacement's
 * return still open (M258769 for EXC-#GOR197307497-1), its RMA closed at 0 with no memo (M197605 RMA288700 for
 * EXC-#GOR192290196-1, NetSuite "invalid reference"), an RMA and no memo (M244082 RMA338431 for WTY-#GOR196553714-1), a lost
 * warranty claim with no memo (M258529, AFTSHP_HG_LOSS), a replacement its payments do not settle (EXC-#GOR191697343-1,
 * 958.50 against 288.19 of credit). R7 (approved orders) and R1 (completed lines) both reach the one cause on the order.
 */
class HeldByReturnPackTests {

    @AfterEach
    void forget() { BrainAnswerStore.clear() }

    private static Map r7(String orderId, String name, Object total, String status = "ORDER_APPROVED") {
        return [key: orderId, bucket: "MISSING_FROM_FILE_2", sides: [
                FILE_1: [presence: "KEPT", record: [orderId: orderId, orderName: name, grandTotal: total, statusId: status]],
                FILE_2: [presence: "ABSENT", firstFieldPresence: "ABSENT"]]]
    }

    private static Map r1(String orderId, String name, Object total) {
        return [key: "${orderId}/01".toString(), bucket: "MISSING_FROM_FILE_2", sides: [
                FILE_1: [presence: "KEPT", record: [omsOrderId: orderId, orderItemSeqId: "01", orderGrandTotal: total, orderName: name]],
                FILE_2: [presence: "ABSENT", firstFieldPresence: "ABSENT"]]]
    }

    private static Map credit(Object amount) {
        return [paymentPreferences: [[paymentMethodTypeId: "EXCHANGE_CREDIT", statusId: "PAYMENT_SETTLED", maxAmount: amount]],
                paymentPreferenceSlotsFull: false]
    }

    private static Map ret(String returnId, String status, String channel = "AFTSHIP_RTN_CHANNEL") {
        return [returnId: returnId, statusId: status, returnChannelEnumId: channel, returnCredit: 60, entryDate: 1791556167543L]
    }

    private static Map more(Map byOrder, Map rmas = null, Map memos = null) {
        Map m = [replacementReturn: byOrder]
        if (rmas != null) m.returnRma = rmas
        if (memos != null) m.returnMemo = memos
        return m
    }

    private static Map fire(String kind, List<Map> findings, Map payments, Map moreAnswers) {
        Map world = [:]
        return BrainEngine.forPacks(["oms-netsuite"]).fire(findings.collectMany {
            BrainFactAdapter.facts(kind, it, payments, world, [:], moreAnswers)
        } as List<Object>)
    }

    private static List<String> codes(Map fired) { ((List<Conclusion>) fired.conclusions).collect { it.cause.code } }

    @Test
    void aReplacementNotInNetsuiteAsksForItsReturnAndAnOrdinaryOrderDoesNot() {
        Map fired = fire("order", [r7("M1201553", "EXC-#GOR197307497-1", 297), r7("M1211344", "#GOR197427010", 63.6)], [:], [:])
        List<Ask> asks = ((List<Ask>) fired.asks).findAll { it.question == "replacementReturn" }
        assertEquals(["M1201553"], asks.collect { ((Order) it.subject).orderId })
    }

    @Test
    void aCompletedReturnAsksNetsuiteForItsRmaAndMemoForTheReplacement() {
        Map fired = fire("order", [r7("M1167220", "WTY-#GOR196553714-1", 275)], [M1167220: credit(275)],
                more([M1167220: ret("M244082", "RETURN_COMPLETED", "AFTSHP_OL_WTY")]))
        List<Ask> asks = ((List<Ask>) fired.asks).findAll { it.question in ["returnRma", "returnMemo"] }
        assertEquals(["returnMemo", "returnRma"], asks*.question.sort())
        assertTrue(asks.every { ((ReplacementReturn) it.subject).returnId == "M244082" && it.forOrder.orderId == "M1167220" })
    }

    @Test
    void aReplacementWaitsOnItsOwnOpenReturn() {
        Map fired = fire("order", [r7("M1201553", "EXC-#GOR197307497-1", 297)], [M1201553: credit(297)],
                more([M1201553: ret("M258769", "RETURN_REQUESTED")]))
        assertEquals(["RET_OPEN"], codes(fired))
        Cause c = ((List<Conclusion>) fired.conclusions)[0].cause
        assertEquals(56, c.rank)
        assertFalse(c.expected)
        assertEquals("M258769 RETURN_REQUESTED since 2026-10-09",
                BrainConclude.fill("{ret.returnId} {ret.statusId} since {ret.entered}", c.because.find { it.getClass().simpleName == "ReplacementWaitsOnItsReturn" }))
    }

    @Test
    void aClosedRmaWithNoMemoIsNamedOverTheUnpaidReplacement() {
        // EXC-#GOR192290196-1 totals 65 and nothing pays it (51); its return's RMA is closed at 0 with no memo (55).
        Map fired = fire("orderLine", [r1("M832311", "EXC-#GOR192290196-1", 65)], [M832311: [paymentPreferences: [], paymentPreferenceSlotsFull: false]],
                more([M832311: ret("M197605", "RETURN_COMPLETED")], [M197605: [tranId: "RMA288700", status: "Return Authorization : Closed"]],
                        [M197605: null]))
        assertEquals(["RMA_CLOSED"], codes(fired), "R1's line takes its order's cause")
    }

    @Test
    void anRmaWithNoMemoAndAReturnWithNoRmaAreTheirOwnCauses() {
        Map rmaNoMemo = fire("order", [r7("M1167220", "WTY-#GOR196553714-1", 275)], [M1167220: credit(275)],
                more([M1167220: ret("M244082", "RETURN_COMPLETED", "AFTSHP_OL_WTY")],
                        [M244082: [tranId: "RMA338431", status: "Return Authorization : Pending Refund"]], [M244082: null]))
        assertEquals(["RMA_NO_MEMO"], codes(rmaNoMemo))
        Map noRma = fire("order", [r7("M962204", "EXC-#GOR194140392-1", 517.28)], [M962204: credit(517.28)],
                more([M962204: ret("M224321", "RETURN_COMPLETED")], [M224321: null], [M224321: null]))
        assertEquals(["RET_NO_RMA"], codes(noRma))
    }

    @Test
    void aLostWarrantyClaimWantsOnlyItsMemo() {
        Map noMemo = fire("order", [r7("M1141364", "WTY-#GOR193061307-1", 40)], [M1141364: credit(40)],
                more([M1141364: ret("M258529", "RETURN_COMPLETED", "AFTSHP_HG_LOSS")], [M258529: null], [M258529: null]))
        assertEquals(["LOST_NO_MEMO"], codes(noMemo), "a lost claim never gets an RMA, so no RMA is not its cause")
        Map memo = fire("order", [r7("M1141364", "WTY-#GOR193061307-1", 40)], [M1141364: credit(40)],
                more([M1141364: ret("M258529", "RETURN_COMPLETED", "AFTSHP_HG_LOSS")], [M258529: null], [M258529: [tranId: "CM7676185", amount: 40]]))
        assertEquals([], codes(memo))
    }

    @Test
    void aReplacementItsPaymentsDoNotSettleIsNotPaidFor() {
        Map fired = fire("order", [r7("M1199671", "EXC-#GOR191697343-1", 958.5)], [M1199671: credit(288.19)],
                more([M1199671: ret("M267891", "RETURN_COMPLETED")], [M267891: [tranId: "RMA365629", status: "Return Authorization : Pending Receipt"]],
                        [M267891: [tranId: "CM1", amount: 288.19]]))
        assertEquals(["REPL_UNPAID"], codes(fired))
        assertEquals("288.19 of 958.5", BrainConclude.fill("{settled} of {order.total}",
                ((List<Conclusion>) fired.conclusions)[0].cause.because.find { it.getClass().simpleName == "PaymentsNotTheTotal" }))
    }

    @Test
    void controlsDoNotConclude() {
        // An orderless return cannot be found from its replacement (EXC-#GOR196200155-1): nothing; its payments settle it.
        assertEquals([], codes(fire("order", [r7("M1088720", "EXC-#GOR196200155-1", 70.77)], [M1088720: credit(70.77)], more([M1088720: null]))))
        // A complete chain (RMA and memo, paid in full) holds nothing.
        assertEquals([], codes(fire("order", [r7("M1097719", "EXC-#GOR196245476-1", 85.1)], [M1097719: credit(85.1)],
                more([M1097719: ret("M249948", "RETURN_COMPLETED")], [M249948: [tranId: "RMA370800", status: "Pending Receipt"]],
                        [M249948: [tranId: "CM7676067", amount: 85.1]]))))
        // An R7 order HotWax has moved on from (completed) is R1's, not R7's.
        assertEquals([], codes(fire("order", [r7("M1201553", "EXC-#GOR197307497-1", 297, "ORDER_COMPLETED")], [M1201553: credit(297)],
                more([M1201553: ret("M258769", "RETURN_REQUESTED")]))))
    }

    /** The questions are asked in rounds: the replacement's return first, then NetSuite for that return; then it concludes. */
    @Test
    void theConcludePassAsksInRoundsUntilTheChainIsAnswered() {
        List<String> calls = []
        Map out = BrainConclude.run([kind: "order", tenantId: "GORJANA", findings: [r7("M1167220", "WTY-#GOR196553714-1", 275)],
                fetchQuestion: { String q, List<String> ids ->
                    calls.add("${q}:${ids.join(',')}".toString())
                    switch (q) {
                        case "orderPayments": return [M1167220: credit(275)]
                        case "replacementReturn": return [M1167220: ret("M244082", "RETURN_COMPLETED", "AFTSHP_OL_WTY")]
                        case "returnRma": return [M244082: [tranId: "RMA338431", status: "Return Authorization : Pending Refund"]]
                        case "returnMemo": return [:]
                        default: return [:]
                    }
                }])
        assertEquals(["orderPayments:M1167220", "replacementReturn:M1167220", "returnMemo:M244082", "returnRma:M244082"], calls.sort())
        assertTrue(calls.indexOf("replacementReturn:M1167220") >= 0)
        assertEquals("RMA_NO_MEMO", ((Map) ((Map) out.byKey).get("M1167220")).code)
        assertEquals([], out.warnings)
    }
}
