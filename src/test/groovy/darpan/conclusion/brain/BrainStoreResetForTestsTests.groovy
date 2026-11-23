package darpan.conclusion.brain

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-OPS-026: BrainAnswerStore resetForTests restores its seams and state. Pure: no Moqui. */
class BrainStoreResetForTestsTests {

    @AfterEach
    void cleanup() {
        BrainAnswerStore.resetForTests()
    }

    @Test
    void "BrainAnswerStore resetForTests restores clock and maxEntries and empties the answers"() {
        BrainAnswerStore.resetForTests()
        long[] now = [1_000L]
        BrainAnswerStore.clock = { now[0] }
        BrainAnswerStore.maxEntries = 3
        BrainAnswerStore.put("t1", "q", "s1", [a: 1])
        assertTrue(BrainAnswerStore.has("t1", "q", "s1"))

        BrainAnswerStore.resetForTests()
        BrainAnswerStore.resetForTests()

        assertEquals(BrainAnswerStore.MAX_ENTRIES, BrainAnswerStore.maxEntries)
        assertFalse(BrainAnswerStore.has("t1", "q", "s1"))
        long before = System.currentTimeMillis()
        long read = BrainAnswerStore.clock.call() as long
        assertTrue(read >= before && read <= System.currentTimeMillis() + 1000)
        // a store written after reset is stamped by the real clock, not the frozen 1_000
        BrainAnswerStore.put("t1", "q", "s2", [a: 2])
        assertTrue(BrainAnswerStore.has("t1", "q", "s2"))
    }
}
