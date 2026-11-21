package darpan.reconciliation.conclusion

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals

/** DAR-OPS-026: resetForTests restores the tunable brain-findings cap; idempotent. */
class RunConclusionSupportResetTests {
    @Test
    void resetRestoresMaxBrainFindings() {
        RunConclusionSupport.maxBrainFindings = 3
        RunConclusionSupport.resetForTests()
        assertEquals(20_000, RunConclusionSupport.maxBrainFindings)
        RunConclusionSupport.resetForTests()
        assertEquals(20_000, RunConclusionSupport.maxBrainFindings)
    }
}
