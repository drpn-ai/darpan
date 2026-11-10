package darpan.conclusion.brain

import darpan.conclusion.brain.fact.Ask
import darpan.conclusion.brain.fact.Cause
import darpan.conclusion.brain.fact.Conclusion
import darpan.conclusion.brain.fact.Order
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse

/**
 * DAR-BE-151 (Aditi 2026-11-06, on gorjana R1: "when an exchange order is not in NS... check the parent order in the
 * unfulfilled in NS list"; the 2026-10-28 R2 cause on F-100189). HotWax holds an exchange until its original ships, so an
 * exchange NetSuite does not have, whose original NetSuite has not fulfilled, waits on that original. The original is the
 * order its return was raised against (the replacementReturn answer's orderId); NetSuite is asked for it once
 * (originalInNetsuite, by HotWax order id). Shapes probed live 2026-11-06 (SuiteQL by otherrefnum): M1223508
 * (EXC-#GOR197410604-1) of M1191688, SO7352829 Pending Fulfillment, 2 lines backordered; M1222307 of M1220603, SO7380188
 * Pending Fulfillment; controls M1224113 of SO4581003 Billed, M1212896 of SO7362141 Billed.
 */
class ExchangeWaitsOnOriginalPackTests {

    @AfterEach
    void forget() { BrainAnswerStore.clear() }

    private static Map r1(String orderId, String name, Object total = 50) {
        return [key: "${orderId}/01".toString(), bucket: "MISSING_FROM_FILE_2", sides: [
                FILE_1: [presence: "KEPT", record: [omsOrderId: orderId, orderItemSeqId: "01", orderGrandTotal: total, orderName: name]],
                FILE_2: [presence: "ABSENT", firstFieldPresence: "ABSENT"]]]
    }

    private static Map r7(String orderId, String name, Object total = 50) {
        return [key: orderId, bucket: "MISSING_FROM_FILE_2", sides: [
                FILE_1: [presence: "KEPT", record: [orderId: orderId, orderName: name, grandTotal: total, statusId: "ORDER_APPROVED"]],
                FILE_2: [presence: "ABSENT", firstFieldPresence: "ABSENT"]]]
    }

    /** The replacement's return, raised against its original order. */
    private static Map ret(String returnId, String originalOrderId, String status = "RETURN_COMPLETED") {
        return [returnId: returnId, orderId: originalOrderId, statusId: status, returnChannelEnumId: "AFTSHIP_RTN_CHANNEL",
                returnCredit: 50, entryDate: 1793903178000L]
    }

    private static Map so(String tranId, String status) { [orderId: null, tranId: tranId, status: "Sales Order : ${status}".toString()] }

    private static Map fire(String kind, List<Map> findings, Map moreAnswers) {
        Map world = [:]
        Map paid = findings.collectEntries { Map f ->
            Map rec = (Map) ((Map) ((Map) f.sides).FILE_1).record
            [((rec.omsOrderId ?: rec.orderId) as String): [paymentPreferences: [[paymentMethodTypeId: "EXCHANGE_CREDIT",
                    statusId: "PAYMENT_SETTLED", maxAmount: rec.orderGrandTotal ?: rec.grandTotal]], paymentPreferenceSlotsFull: false]]
        }
        return BrainEngine.forPacks(["oms-netsuite"]).fire(findings.collectMany {
            BrainFactAdapter.facts(kind, it, paid, world, [:], moreAnswers)
        } as List<Object>)
    }

    private static List<String> codes(Map fired) { ((List<Conclusion>) fired.conclusions).collect { it.cause.code } }

    // A completed return with its RMA and memo in NetSuite: nothing about the return holds the exchange.
    private static Map chain(String replacement, String returnId, String original, Map originalAnswer) {
        Map m = [replacementReturn: [(replacement): ret(returnId, original)],
                 returnRma        : [(returnId): [tranId: "RMA1", status: "Return Authorization : Pending Receipt"]],
                 returnMemo       : [(returnId): [tranId: "CM1", amount: 50]]]
        if (originalAnswer != null) m.originalInNetsuite = originalAnswer
        return m
    }

    @Test
    void anExchangeNotInNetsuiteAsksNetsuiteForItsOriginalOnce() {
        Map fired = fire("orderLine", [r1("M1223508", "EXC-#GOR197410604-1")], chain("M1223508", "M270001", "M1191688", null))
        List<Ask> asks = ((List<Ask>) fired.asks).findAll { it.question == "originalInNetsuite" }
        assertEquals(["M1191688"], asks.collect { ((Order) it.subject).orderId }, "asked for the original, by its HotWax id")
        assertEquals(["M1223508"], asks.collect { it.forOrder.orderId })
        // A warranty replacement is not an exchange: its original is not asked (Aditi named exchanges).
        Map wty = fire("orderLine", [r1("M1167220", "WTY-#GOR196553714-1")], chain("M1167220", "M244082", "M1100001", null))
        assertEquals([], ((List<Ask>) wty.asks).findAll { it.question == "originalInNetsuite" })
    }

    @Test
    void anExchangeWhoseOriginalNetsuiteHasNotFulfilledWaitsOnIt() {
        Map fired = fire("orderLine", [r1("M1223508", "EXC-#GOR197410604-1")],
                chain("M1223508", "M270001", "M1191688", [M1191688: so("SO7352829", "Pending Fulfillment")]))
        assertEquals(["ORIG_OPEN"], codes(fired), "R1's line takes its order's cause")
        Cause c = ((List<Conclusion>) fired.conclusions)[0].cause
        assertEquals(58, c.rank)
        assertFalse(c.expected)
        assertEquals("M1191688: SO7352829 Sales Order : Pending Fulfillment", BrainConclude.fill(
                "{original.orderId}: {original.netsuiteTranId} {original.netsuiteStatus}",
                c.because.find { it.getClass().simpleName == "ExchangeWaitsOnOriginal" }))
        // Partly shipped is still not fulfilled.
        assertEquals(["ORIG_OPEN"], codes(fire("orderLine", [r1("M1223508", "EXC-#GOR197410604-1")],
                chain("M1223508", "M270001", "M1191688", [M1191688: so("SO7352829", "Pending Billing/Partially Fulfilled")]))))
    }

    @Test
    void itOutranksWhatTheReturnLacksAndReachesR7() {
        // The return has no RMA and no memo (53), and the original is unfulfilled (58): the original is named.
        Map m = [replacementReturn : [M1222307: ret("M270002", "M1220603")], returnRma: [M270002: null], returnMemo: [M270002: null],
                 originalInNetsuite: [M1220603: so("SO7380188", "Pending Fulfillment")]]
        assertEquals(["ORIG_OPEN"], codes(fire("orderLine", [r1("M1222307", "EXC-#GOR197434705-1")], m)))
        assertEquals(["ORIG_OPEN"], codes(fire("order", [r7("M1222307", "EXC-#GOR197434705-1")], m)), "R7 reaches the same cause")
    }

    @Test
    void controlsDoNotWaitOnTheirOriginal() {
        // The original is billed (M1224113's SO4581003): not this cause.
        assertEquals([], codes(fire("orderLine", [r1("M1224113", "EXC-#GOR194804616-1")],
                chain("M1224113", "M270003", "M1000003", [M1000003: so("SO4581003", "Billed")]))))
        // NetSuite has no sales order for the original: not in the unfulfilled list, so not this cause.
        assertEquals([], codes(fire("orderLine", [r1("M1224113", "EXC-#GOR194804616-1")],
                chain("M1224113", "M270003", "M1000003", [M1000003: null]))))
        // Not answered yet: nothing is concluded from a question not asked.
        assertEquals([], codes(fire("orderLine", [r1("M1223508", "EXC-#GOR197410604-1")], chain("M1223508", "M270001", "M1191688", null))))
        // The exchange's return named no order: nothing to ask.
        Map orphan = [replacementReturn: [M1223508: ret("M270001", null)]]
        assertEquals([], ((List<Ask>) fire("orderLine", [r1("M1223508", "EXC-#GOR197410604-1")], orphan).asks)
                .findAll { it.question == "originalInNetsuite" })
    }

    // Prod gorjana R1 (OMS_ORDER_ITEMS_GQL) names the order omsOrderName, not orderName: an exchange line must still be known.
    @Test
    void anR1LineNamedByOmsOrderNameIsStillAnExchange() {
        Map line = r1("M1223508", null)
        ((Map) ((Map) ((Map) line.sides).FILE_1).record).omsOrderName = "EXC-#GOR197410604-1"
        Map fired = fire("orderLine", [line], chain("M1223508", "M270001", "M1191688", [M1191688: so("SO7352829", "Pending Fulfillment")]))
        assertEquals(["ORIG_OPEN"], codes(fired))
    }

    @Test
    void theQuestionIsWorthAskingOverTheReturnCauses() {
        assertEquals(58, BrainQuestions.mayEnableRanks(BrainEngine.forPacks(["oms-netsuite"])).get("originalInNetsuite"))
    }
}
