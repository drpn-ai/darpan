package darpan.conclusion.brain

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-226. An answer lives for its question's freshness class, never crosses tenants, and the cap drops expired ones first. */
class BrainAnswerStoreTests {

    long now = 1_000_000L

    @BeforeEach
    void start() {
        BrainAnswerStore.clear()
        BrainAnswerStore.clock = { now }
    }

    @AfterEach
    void reset() {
        BrainAnswerStore.clear()
        BrainAnswerStore.clock = { System.currentTimeMillis() }
        BrainAnswerStore.maxEntries = BrainAnswerStore.MAX_ENTRIES
    }

    @Test
    void aStableAnswerIsReusedAtMinuteSixteenAndARunAnswerIsNot() {
        assertEquals(BrainQuestionCatalog.FRESH_STABLE, BrainQuestionCatalog.entry("productNetsuiteId").freshness)
        assertEquals(BrainQuestionCatalog.FRESH_RUN, BrainQuestionCatalog.entry("orderPayments").freshness)
        BrainAnswerStore.put("T1", "productNetsuiteId", "P1", [netsuiteId: "77"])
        BrainAnswerStore.put("T1", "orderPayments", "M1", [orderId: "M1"])

        now += 16 * 60_000L
        assertEquals([netsuiteId: "77"], BrainAnswerStore.get("T1", "productNetsuiteId", "P1"))
        assertFalse(BrainAnswerStore.has("T1", "orderPayments", "M1"))

        now += BrainAnswerStore.STABLE_TTL_MILLIS
        assertFalse(BrainAnswerStore.has("T1", "productNetsuiteId", "P1"), "a stable answer still expires")
    }

    @Test
    void aStoredNothingFoundIsAnAnswerForItsWholeLife() {
        BrainAnswerStore.put("T1", "productNetsuiteId", "P9", null)
        now += 60 * 60_000L
        assertTrue(BrainAnswerStore.has("T1", "productNetsuiteId", "P9"))
        assertNull(BrainAnswerStore.get("T1", "productNetsuiteId", "P9"))
    }

    @Test
    void oneTenantsAnswerIsNeverServedToAnother() {
        BrainAnswerStore.put("T1", "orderPayments", "M1", [orderId: "M1", owner: "T1"])
        assertFalse(BrainAnswerStore.has("T2", "orderPayments", "M1"))
        assertNull(BrainAnswerStore.get("T2", "orderPayments", "M1"))
        BrainAnswerStore.put("T2", "orderPayments", "M1", [orderId: "M1", owner: "T2"])
        assertEquals("T1", BrainAnswerStore.get("T1", "orderPayments", "M1").owner)
        assertEquals("T2", BrainAnswerStore.get("T2", "orderPayments", "M1").owner)
    }

    @Test
    void anAnswerWithNoTenantIsNotKept() {
        BrainAnswerStore.put(null, "orderPayments", "M1", [orderId: "M1"])
        BrainAnswerStore.put("", "orderPayments", "M1", [orderId: "M1"])
        assertFalse(BrainAnswerStore.has(null, "orderPayments", "M1"))
        assertFalse(BrainAnswerStore.has("", "orderPayments", "M1"))
    }

    @Test
    void atTheCapExpiredEntriesGoFirst() {
        BrainAnswerStore.maxEntries = 4
        BrainAnswerStore.put("T1", "orderPayments", "OLD1", [:])
        BrainAnswerStore.put("T1", "orderPayments", "OLD2", [:])
        now += 60_000L
        BrainAnswerStore.put("T1", "productNetsuiteId", "KEEP1", [:])
        BrainAnswerStore.put("T1", "productNetsuiteId", "KEEP2", [:])
        now += BrainAnswerStore.RUN_TTL_MILLIS          // OLD1 and OLD2 (run) have expired; the stable two have not
        BrainAnswerStore.put("T1", "orderPayments", "NEW", [:])

        assertTrue(BrainAnswerStore.has("T1", "productNetsuiteId", "KEEP1"), "an unexpired answer survives while expired ones remain")
        assertTrue(BrainAnswerStore.has("T1", "productNetsuiteId", "KEEP2"))
        assertTrue(BrainAnswerStore.has("T1", "orderPayments", "NEW"))
    }

    @Test
    void atTheCapWithNothingExpiredTheOldestGoNotEverything() {
        BrainAnswerStore.maxEntries = 4
        ["A", "B", "C", "D"].each { String id -> BrainAnswerStore.put("T1", "productNetsuiteId", id, [:]); now += 1_000L }
        BrainAnswerStore.put("T1", "productNetsuiteId", "E", [:])

        assertFalse(BrainAnswerStore.has("T1", "productNetsuiteId", "A"), "the oldest goes")
        ["B", "C", "D", "E"].each { assertTrue(BrainAnswerStore.has("T1", "productNetsuiteId", it), "${it} stays") }
    }
}
