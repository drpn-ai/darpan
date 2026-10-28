package darpan.conclusion.brain

import darpan.conclusion.brain.fact.Ask
import darpan.conclusion.brain.fact.Cause
import darpan.conclusion.brain.fact.Order
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-151 (design D6): one place knows every ask; it drops what cannot matter, dedupes, and reuses before asking. */
class BrainAskPlannerTests {

    private static final Map<String, Integer> RANKS = [orderPayments: 80]

    @BeforeEach
    void clear() { BrainAnswerStore.clear() }

    @AfterEach
    void reset() { BrainAnswerStore.clock = { System.currentTimeMillis() } }

    private static Order order(String id) { new Order(orderId: id) }

    @Test
    void theQuestionsRankCeilingComesFromThePack() {
        assertEquals(RANKS, BrainQuestions.mayEnableRanks(BrainEngine.forPacks(["oms-netsuite"])))
    }

    @Test
    void anAskTheBestCauseAlreadyOutranksIsDropped() {
        Order m1 = order("M1")
        Map plan = BrainAskPlanner.plan([new Ask("orderPayments", m1)], [(m1): Cause.expected(m1, "X", 80)], "T", RANKS)
        assertEquals(1, plan.dropped)
        assertEquals([:], plan.toFetch)
    }

    @Test
    void anAskABetterCauseCouldStillWinIsFetched() {
        Order m1 = order("M1")
        Map plan = BrainAskPlanner.plan([new Ask("orderPayments", m1)], [(m1): Cause.fix(m1, "NEVER", 10)], "T", RANKS)
        assertEquals([orderPayments: ["M1"]], plan.toFetch)
    }

    @Test
    void theSameOrderAskedTwiceIsFetchedOnce() {
        Map plan = BrainAskPlanner.plan([new Ask("orderPayments", order("M1")), new Ask("orderPayments", order("M1")),
                                         new Ask("orderPayments", order("M2"))], [:], "T", RANKS)
        assertEquals([orderPayments: ["M1", "M2"]], plan.toFetch)
    }

    @Test
    void anAnswerInTheStoreIsReusedNotFetched() {
        Map answer = [orderId: "M1", paymentPreferences: []]
        BrainAnswerStore.put("T", "orderPayments", "M1", answer)
        Map plan = BrainAskPlanner.plan([new Ask("orderPayments", order("M1"))], [:], "T", RANKS)
        assertEquals(1, plan.reused)
        assertEquals([:], plan.toFetch)
        assertEquals(answer, ((Map) plan.answered.orderPayments).M1)
    }

    @Test
    void aNoneFoundAnswerIsReusedToo() {
        BrainAnswerStore.put("T", "orderPayments", "M1", null)
        Map plan = BrainAskPlanner.plan([new Ask("orderPayments", order("M1"))], [:], "T", RANKS)
        assertEquals(1, plan.reused)
        assertTrue(((Map) plan.answered.orderPayments).containsKey("M1"))
        assertNull(((Map) plan.answered.orderPayments).M1)
    }

    @Test
    void anotherTenantsAnswerIsNeverReused() {
        BrainAnswerStore.put("OTHER", "orderPayments", "M1", [orderId: "M1"])
        assertEquals([orderPayments: ["M1"]], BrainAskPlanner.plan([new Ask("orderPayments", order("M1"))], [:], "T", RANKS).toFetch)
    }

    @Test
    void answersExpireAfterARun() {
        long now = 1_000_000L
        BrainAnswerStore.clock = { now }
        BrainAnswerStore.put("T", "orderPayments", "M1", [orderId: "M1"])
        now += BrainAnswerStore.RUN_TTL_MILLIS + 1
        assertTrue(!BrainAnswerStore.has("T", "orderPayments", "M1"))
    }
}
