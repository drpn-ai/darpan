package darpan.facade.reconciliation

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals

/** DAR-OPS-026: resetForTests restores the capture clock; idempotent. */
class RunConclusionStepResetTests {
    @Test
    void resetRestoresCaptureClock() {
        RunConclusionStep.@lastCaptureMillis = Long.MAX_VALUE / 2 as long
        RunConclusionStep.resetForTests()
        assertEquals(0L, RunConclusionStep.@lastCaptureMillis)
        RunConclusionStep.resetForTests()
        assertEquals(0L, RunConclusionStep.@lastCaptureMillis)
    }
}
