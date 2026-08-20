package darpan.reconciliation.notification

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals

class RunNotificationVoiceTests {

    private static Map counts(int onlyIn1, int onlyIn2, int ruleDiffs, Map extra = [:]) {
        return ([onlyInFile1Count: onlyIn1, onlyInFile2Count: onlyIn2,
                 ruleDifferenceCount: ruleDiffs] + extra) as Map
    }

    @Test
    void allZeroCountsClassifyClean() {
        assertEquals(RunNotificationVoice.BUCKET_CLEAN,
                RunNotificationVoice.classify(counts(0, 0, 0)).bucket)
    }

    @Test
    void failedStatusOutranksEveryShapeBucket() {
        Map result = RunNotificationVoice.classify(counts(39, 0, 0, [runFailed: true]))
        assertEquals(RunNotificationVoice.BUCKET_FAILED, result.bucket)
    }

    @Test
    void warningsOutrankShapeButNotFailure() {
        Map result = RunNotificationVoice.classify(counts(39, 0, 0, [hasWarnings: true]))
        assertEquals(RunNotificationVoice.BUCKET_ISSUES, result.bucket)
    }

    @Test
    void balancedMissingCountsClassifyEvenSplit() {
        assertEquals(RunNotificationVoice.BUCKET_EVEN_SPLIT,
                RunNotificationVoice.classify(counts(12, 12, 0)).bucket)
    }

    @Test
    void nearlyBalancedMissingCountsStillClassifyEvenSplit() {
        // 9 / 12 = 0.75 -> below the 0.8 floor, so this is NOT an even split.
        assertEquals(RunNotificationVoice.BUCKET_MIXED,
                RunNotificationVoice.classify(counts(12, 9, 0)).bucket)
        // 10 / 12 = 0.83 -> at or above the floor.
        assertEquals(RunNotificationVoice.BUCKET_EVEN_SPLIT,
                RunNotificationVoice.classify(counts(12, 10, 0)).bucket)
    }

    @Test
    void singleSidedMissingCountsClassifyOneSided() {
        assertEquals(RunNotificationVoice.BUCKET_ONE_SIDED,
                RunNotificationVoice.classify(counts(39, 0, 0)).bucket)
    }

    @Test
    void mismatchesWithNothingMissingClassifyValueDrift() {
        assertEquals(RunNotificationVoice.BUCKET_VALUE_DRIFT,
                RunNotificationVoice.classify(counts(0, 0, 8)).bucket)
    }

    @Test
    void missingAndMismatchesTogetherClassifyMixed() {
        assertEquals(RunNotificationVoice.BUCKET_MIXED,
                RunNotificationVoice.classify(counts(39, 0, 8)).bucket)
    }

    @Test
    void totalCountsMissingPlusMismatches() {
        // The old differenceCount excluded mismatches entirely. The headline number must not.
        assertEquals(47, RunNotificationVoice.classify(counts(39, 0, 8)).totalCount)
    }

    @Test
    void missingCountsInvertToTheOppositeSystem() {
        // onlyInFile1Count means "present only in file 1" == "missing from file 2's system".
        Map result = RunNotificationVoice.classify(counts(39, 4, 0))
        assertEquals(39, result.missingFromFile2Count)
        assertEquals(4, result.missingFromFile1Count)
    }

    @Test
    void nullAndNonNumericCountsAreTreatedAsZero() {
        Map result = RunNotificationVoice.classify(
                [onlyInFile1Count: null, onlyInFile2Count: "", ruleDifferenceCount: "not a number"])
        assertEquals(RunNotificationVoice.BUCKET_CLEAN, result.bucket)
        assertEquals(0, result.totalCount)
    }
}
