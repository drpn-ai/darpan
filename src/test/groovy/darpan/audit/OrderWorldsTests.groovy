package darpan.audit

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotEquals
import static org.junit.jupiter.api.Assertions.assertNull

/**
 * DAR-BE-250. Earliest step wins: findings naming one order are one world, and a world's later steps wait on its
 * earliest open one. Cases are gorjana's live repeats of 2026-11-12 (find_repeats.py), in recordsJson's shape.
 */
class OrderWorldsTests {

    private static final String OPEN = "AUDFS_OPEN"
    private static final String FIXED = "AUDFS_FIXED"
    private static final Map<String, Long> STEP = [R10: 40L, R1: 80L, R2: 120L, R9: 130L, R6: 160L]

    private static Map entry(String id, String statement, String state = OPEN) {
        return [row: [findingId: id, statementId: statement], state: state]
    }

    // R10 reads a NetSuite open line with its HotWax order; R1 reads the order's other, completed line.
    private static final Map R10_LINE = [NetSuite: [salesOrderLine: [[id: "SO7371002", internalId: "81531855"]]],
                                         OMS     : [order: [[id: "EXC-#GOR197136981-1", internalId: "M1210830"]]]]
    private static final Map R1_LINE = [OMS     : [orderItem: [[id: "M1210830/02", orderName: "EXC-#GOR197136981-1"]]],
                                        NetSuite: [salesOrderLine: [[id: "SO7371002", internalId: "81531855"]]]]

    @Test
    void aLaterStepOfTheSameOrderWaitsOnTheEarliest() {
        Map out = OrderWorlds.rank([entry("F-1", "R1"), entry("F-2", "R10")], ["F-1": R1_LINE, "F-2": R10_LINE], STEP)
        assertEquals("R10", out["F-1"].waitingOn, "R1's line waits on R10's: one order, step 4 before step 8")
        assertNull(out["F-2"].waitingOn)
        assertEquals(out["F-1"].world, out["F-2"].world)
    }

    @Test
    void aReturnJoinsItsExchangeByTheExchangesName() {
        // R2: the return of #GOR197457937, whose exchange is EXC-#GOR197457937-1; R1: the exchange's completed line.
        Map ret = [OMS: [return: [[id: "M277966", orderName: "#GOR197457937", exchange: [id: "EXC-#GOR197457937-1", netsuiteId: null]]]]]
        Map exchangeLine = [OMS: [orderItem: [[id: "M1248236/01", orderName: "EXC-#GOR197457937-1"]]]]
        Map out = OrderWorlds.rank([entry("F-r", "R2"), entry("F-x", "R1")], ["F-r": ret, "F-x": exchangeLine], STEP)
        assertEquals("R1", out["F-r"].waitingOn)
        assertNull(out["F-x"].waitingOn)
    }

    @Test
    void aMemoJoinsItsReturnAndTheExchangeNetSuiteHolds() {
        // R9's memo finding names the return and its exchange's NetSuite id; R10 holds that exchange's open line.
        Map memo = [OMS     : [return: [[id: "M276211", orderName: "#GOR195831165", exchange: [id: "EXC-#GOR195831165-1", netsuiteId: "81873738"]]]],
                    NetSuite: [creditMemo: [[id: "CM7681094", internalId: "81872694"]]]]
        Map line = [NetSuite: [salesOrderLine: [[id: "SO7397305", internalId: "81873738"]]],
                    OMS     : [order: [[id: "EXC-#GOR195831165-1", internalId: "M1238492"]]]]
        Map out = OrderWorlds.rank([entry("F-m", "R9"), entry("F-l", "R10")], ["F-m": memo, "F-l": line], STEP)
        assertEquals("R10", out["F-m"].waitingOn)
    }

    @Test
    void idsAreTypedSoAReturnIsNeverTakenForAnOrderWithTheSameDigits() {
        Map ret = [OMS: [return: [[id: "M277951"]]]]
        Map item = [OMS: [orderItem: [[id: "M277951/01"]]]]
        Map out = OrderWorlds.rank([entry("F-r", "R2"), entry("F-i", "R1")], ["F-r": ret, "F-i": item], STEP)
        assertNotEquals(out["F-r"].world, out["F-i"].world)
        assertNull(out["F-r"].waitingOn)
        assertEquals(["return:M277951"] as Set, OrderWorlds.keysOf(ret))
        assertEquals(["order:M277951"] as Set, OrderWorlds.keysOf(item))
    }

    @Test
    void onlyAnOpenFindingAtAStrictlyEarlierStepHoldsAnotherBack() {
        // Fixed today at R10: nothing holds R1's line back.
        assertNull(OrderWorlds.rank([entry("F-1", "R1"), entry("F-2", "R10", FIXED)], ["F-1": R1_LINE, "F-2": R10_LINE], STEP)["F-1"].waitingOn)
        // Two lines at one step: neither waits.
        Map other = [OMS: [orderItem: [[id: "M1210830/01", orderName: "EXC-#GOR197136981-1"]]]]
        Map same = OrderWorlds.rank([entry("F-1", "R1"), entry("F-3", "R1")], ["F-1": R1_LINE, "F-3": other], STEP)
        assertNull(same["F-1"].waitingOn)
        assertNull(same["F-3"].waitingOn)
        // A fixed finding never waits.
        assertNull(OrderWorlds.rank([entry("F-1", "R1", FIXED), entry("F-2", "R10")], ["F-1": R1_LINE, "F-2": R10_LINE], STEP)["F-1"].waitingOn)
    }

    @Test
    void aFindingThatNamesNoSharedOrderStandsAlone() {
        Map memo = [NetSuite: [creditMemo: [[id: "CM7677528"]]]]
        Map out = OrderWorlds.rank([entry("F-6", "R6"), entry("F-2", "R10")], ["F-6": memo, "F-2": R10_LINE], STEP)
        assertNull(out["F-6"].waitingOn)
        // A statement with no step sorts last; records missing altogether join nothing.
        Map loose = OrderWorlds.rank([entry("F-a", "RX"), entry("F-b", "R1")], [:], STEP)
        assertNull(loose["F-a"].waitingOn)
        assertNull(loose["F-b"].waitingOn)
    }
}
