package darpan.facade.reconciliation

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * The verification pass's decision + count-adjustment logic, lifted out of runSavedRunDiff.groovy's
 * script-local closures so BOTH run entry points can reach it (design
 * 2026-08-26-reconciliation-pipeline-unification, step 2).
 *
 * Why this seam and not a straight copy: the scheduled path currently runs NO verification at all
 * (`grep -c STAGE_VERIFY` over darpan/reconciliation/automation/ returns 0), which on gorjana
 * automation 100616 meant a scheduled run reporting ~532 differences where the verified interactive
 * rerun reported 2. Copying the closures into the automation path would make a fourth hand-synced
 * divergence point in a file pair already kept in sync by comment. These tests pin the arithmetic
 * that a copy would get subtly wrong.
 */
class RunVerificationSupportTests {

    // --- when the pass should run at all -------------------------------------------------------

    @Test
    void doesNotRunWhenRuleExecutionFailed() {
        // Partial diffs are preserved for investigation and must never be rewritten.
        assertFalse(RunVerificationSupport.shouldVerifyMissingDiffs(
                [ruleExecutionFailed: true, missingInFile1Count: 5, missingInFile2Count: 5]))
    }

    @Test
    void doesNotRunWhenNeitherSideReportsAnythingMissing() {
        assertFalse(RunVerificationSupport.shouldVerifyMissingDiffs(
                [missingInFile1Count: 0, missingInFile2Count: 0]))
    }

    @Test
    void runsWhenEitherSideReportsSomethingMissing() {
        assertTrue(RunVerificationSupport.shouldVerifyMissingDiffs(
                [missingInFile1Count: 3, missingInFile2Count: 0]))
        assertTrue(RunVerificationSupport.shouldVerifyMissingDiffs(
                [missingInFile1Count: 0, missingInFile2Count: 3]))
    }

    @Test
    void toleratesAbsentCountKeys() {
        assertFalse(RunVerificationSupport.shouldVerifyMissingDiffs([:]))
        assertFalse(RunVerificationSupport.shouldVerifyMissingDiffs(null))
    }

    // --- count adjustment ----------------------------------------------------------------------

    @Test
    void appliesRemovedCountsToEveryDerivedTotal() {
        Map serviceResult = [
                differenceCount              : 532L,
                missingInFile1Count          : 500L,
                missingInFile2Count          : 32L,
                missingObjectDifferenceCount : 532L,
        ]
        Map verification = [rewritten: true, removedCount: 530L,
                            removedMissingInFile1: 498L, removedMissingInFile2: 32L]

        RunVerificationSupport.applyVerificationOutcome(serviceResult, verification, 500L, 32L)

        assertEquals(2L, serviceResult.differenceCount)
        assertEquals(2L, serviceResult.missingInFile1Count)
        assertEquals(0L, serviceResult.missingInFile2Count)
        assertEquals(2L, serviceResult.missingObjectDifferenceCount,
                "missingObjectDifferenceCount must track removals too, or the summary contradicts itself")
    }

    @Test
    void leavesEveryCountUntouchedWhenTheDiffWasNotRewritten() {
        Map serviceResult = [differenceCount: 532L, missingInFile1Count: 500L,
                             missingInFile2Count: 32L, missingObjectDifferenceCount: 532L]

        RunVerificationSupport.applyVerificationOutcome(serviceResult,
                [rewritten: false, removedCount: 530L], 500L, 32L)

        assertEquals(532L, serviceResult.differenceCount,
                "a pass that rewrote nothing must not move the counts")
        assertEquals(500L, serviceResult.missingInFile1Count)
        assertEquals(32L, serviceResult.missingInFile2Count)
        assertEquals(532L, serviceResult.missingObjectDifferenceCount)
    }

    @Test
    void neverDrivesACountBelowZero() {
        // A lookup that reports more removals than the compare found must clamp, not go negative —
        // a negative difference count renders as nonsense and breaks downstream alert thresholds.
        Map serviceResult = [differenceCount: 5L, missingInFile1Count: 3L,
                             missingInFile2Count: 2L, missingObjectDifferenceCount: 5L]

        RunVerificationSupport.applyVerificationOutcome(serviceResult,
                [rewritten: true, removedCount: 99L, removedMissingInFile1: 99L,
                 removedMissingInFile2: 99L], 3L, 2L)

        assertEquals(0L, serviceResult.differenceCount)
        assertEquals(0L, serviceResult.missingInFile1Count)
        assertEquals(0L, serviceResult.missingInFile2Count)
        assertEquals(0L, serviceResult.missingObjectDifferenceCount)
    }

    @Test
    void leavesMissingObjectDifferenceCountAloneWhenTheCompareDidNotReportOne() {
        Map serviceResult = [differenceCount: 10L, missingInFile1Count: 10L, missingInFile2Count: 0L]

        RunVerificationSupport.applyVerificationOutcome(serviceResult,
                [rewritten: true, removedCount: 4L, removedMissingInFile1: 4L], 10L, 0L)

        assertEquals(6L, serviceResult.differenceCount)
        assertNull(serviceResult.missingObjectDifferenceCount,
                "an absent key must stay absent rather than being invented as a negative")
    }

    // --- audit notes ---------------------------------------------------------------------------

    @Test
    void appendsAuditNoteAndWarningsToProcessingWarnings() {
        Map serviceResult = [processingWarnings: ["earlier warning"]]

        RunVerificationSupport.applyVerificationOutcome(serviceResult,
                [rewritten: false, auditNote: "checked 532, removed 530",
                 warnings: ["lookup capped"]], 0L, 0L)

        assertEquals(["earlier warning", "checked 532, removed 530", "lookup capped"],
                serviceResult.processingWarnings,
                "existing warnings must be preserved and the audit note must lead the appended block")
    }

    @Test
    void doesNotCreateAProcessingWarningsKeyWhenThereIsNothingToSay() {
        Map serviceResult = [differenceCount: 1L]

        RunVerificationSupport.applyVerificationOutcome(serviceResult, [rewritten: false], 0L, 0L)

        assertNull(serviceResult.processingWarnings,
                "an empty verification must not add an empty warnings array to the result document")
    }
}
