package darpan.reconciliation.notification

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.AfterEach
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue
import java.time.ZoneId
import java.time.ZonedDateTime
import static org.junit.jupiter.api.Assertions.assertNotNull

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

    @AfterEach
    void resetPicker() {
        RunNotificationVoice.resetLinePicker()
    }

    @Test
    void injectedPickerControlsLineSelection() {
        RunNotificationVoice.setLinePicker { List<String> pool, String slotName -> pool.first() }
        assertEquals(RunNotificationVoice.CLEAN_HEADLINES.first(),
                RunNotificationVoice.pickLine(RunNotificationVoice.CLEAN_HEADLINES, "headline"))
    }

    @Test
    void pickLineReturnsNullForAnEmptyPool() {
        assertEquals(null, RunNotificationVoice.pickLine([], "headline"))
    }

    @Test
    void defaultPickerAlwaysReturnsAPoolMember() {
        // No seeding: production selection is genuinely random. Sequential result ids under a
        // modulo would march through the pool in order, which reads as more robotic, not less.
        30.times {
            assertTrue(RunNotificationVoice.CLEAN_HEADLINES
                    .contains(RunNotificationVoice.pickLine(RunNotificationVoice.CLEAN_HEADLINES, "headline")))
        }
    }

    @Test
    void copyCorpusNeverUsesTheBannedAgreeWording() {
        // Project copy rule: systems "line up", they never "agree".
        (RunNotificationVoice.CLEAN_HEADLINES + RunNotificationVoice.CLEAN_SUBLINES).each { String line ->
            assertFalse(line.toLowerCase().contains("agree"), "banned wording in copy: ${line}")
        }
    }

    @Test
    void timeOfDayLineReadsTheZonedMomentNotTheServerClock() {
        RunNotificationVoice.setLinePicker { List<String> pool, String slotName -> pool.first() }
        // 2026-08-21 is a Friday. 15:00 in Asia/Kolkata is 09:30 UTC — the same instant would read
        // as morning in UTC and afternoon in IST.
        ZonedDateTime fridayAfternoonIst = ZonedDateTime.of(
                2026, 8, 21, 15, 0, 0, 0, ZoneId.of("Asia/Kolkata"))
        assertTrue(RunNotificationVoice.timeOfDayLine(fridayAfternoonIst).toLowerCase().contains("friday"))
    }

    @Test
    void timeOfDayLineHandlesEarlyMorning() {
        RunNotificationVoice.setLinePicker { List<String> pool, String slotName -> pool.first() }
        ZonedDateTime earlyWednesday = ZonedDateTime.of(
                2026, 8, 19, 6, 15, 0, 0, ZoneId.of("Asia/Kolkata"))
        assertNotNull(RunNotificationVoice.timeOfDayLine(earlyWednesday))
    }

    @Test
    void timeOfDayLineIsNullWithoutAMoment() {
        assertEquals(null, RunNotificationVoice.timeOfDayLine(null))
    }

    @Test
    void streakLineIsNullBelowTwoPriorCleanRuns() {
        assertEquals(null, RunNotificationVoice.streakLine(0))
        assertEquals(null, RunNotificationVoice.streakLine(1))
    }

    @Test
    void streakLineCountsTheCurrentRunToo() {
        RunNotificationVoice.setLinePicker { List<String> pool, String slotName -> pool.first() }
        // Three prior clean runs plus this one is a streak of four.
        assertTrue(RunNotificationVoice.streakLine(3).contains("4"))
    }

    @Test
    void streakLineSaysAndCountingAtTheLookbackCap() {
        RunNotificationVoice.setLinePicker { List<String> pool, String slotName -> pool.first() }
        assertTrue(RunNotificationVoice.streakLine(RunNotificationVoice.LOOKBACK_LIMIT)
                .toLowerCase().contains("counting"))
    }

    @Test
    void cleanRunOmitsTheDetailsBlock() {
        RunNotificationVoice.setLinePicker { List<String> pool, String slotName -> pool.first() }
        List<String> lines = RunNotificationVoice.renderLines(
                cleanModel() + [runName: "Production Orders Automation"])
        assertFalse(lines.join("\n").contains("*Details*"),
                "three zeros is noise on a clean run: ${lines}")
    }

    @Test
    void detailsBlockNamesTheOppositeSystemForEachMissingCount() {
        RunNotificationVoice.setLinePicker { List<String> pool, String slotName -> pool.first() }
        Map model = RunNotificationVoice.classify(
                [onlyInFile1Count: 39, onlyInFile2Count: 0, ruleDifferenceCount: 8]) +
                [runName: "Production Orders Automation",
                 file1SystemLabel: "HotWax", file2SystemLabel: "Shopify",
                 priorCleanRuns: 0, completedMoment: null]
        String text = RunNotificationVoice.renderLines(model).join("\n")

        // onlyInFile1Count is the HotWax-side count, so it is what SHOPIFY is missing.
        assertTrue(text.contains("Missing from Shopify: 39"), text)
        assertTrue(text.contains("Missing from HotWax: 0"), text)
        assertTrue(text.contains("Mismatches: 8"), text)
    }

    @Test
    void detailsBlockPrintsAllThreeLinesIncludingZeros() {
        RunNotificationVoice.setLinePicker { List<String> pool, String slotName -> pool.first() }
        Map model = RunNotificationVoice.classify(
                [onlyInFile1Count: 39, onlyInFile2Count: 0, ruleDifferenceCount: 0]) +
                [runName: "R", file1SystemLabel: "A", file2SystemLabel: "B",
                 priorCleanRuns: 0, completedMoment: null]
        String text = RunNotificationVoice.renderLines(model).join("\n")
        // An omitted line cannot be told apart from an axis that was never checked.
        assertTrue(text.contains("Missing from A: 0"), text)
        assertTrue(text.contains("Mismatches: 0"), text)
    }

    @Test
    void detailsHeaderUsesSingleAsteriskBoldForGoogleChat() {
        RunNotificationVoice.setLinePicker { List<String> pool, String slotName -> pool.first() }
        Map model = RunNotificationVoice.classify(
                [onlyInFile1Count: 1, onlyInFile2Count: 0, ruleDifferenceCount: 0]) +
                [runName: "R", file1SystemLabel: "A", file2SystemLabel: "B",
                 priorCleanRuns: 0, completedMoment: null]
        String text = RunNotificationVoice.renderLines(model).join("\n")
        assertTrue(text.contains("*Details*"), text)
        assertFalse(text.contains("**Details**"), "double asterisks render literally in Google Chat")
    }

    @Test
    void headlineCountsMissingPlusMismatches() {
        RunNotificationVoice.setLinePicker { List<String> pool, String slotName -> pool.first() }
        Map model = RunNotificationVoice.classify(
                [onlyInFile1Count: 39, onlyInFile2Count: 0, ruleDifferenceCount: 8]) +
                [runName: "Production Orders Automation", file1SystemLabel: "A",
                 file2SystemLabel: "B", priorCleanRuns: 0, completedMoment: null]
        assertTrue(RunNotificationVoice.renderLines(model).first().contains("47"))
    }

    @Test
    void failedRunsCarryNoFlavourAtAll() {
        RunNotificationVoice.setLinePicker { List<String> pool, String slotName -> pool.first() }
        Map model = RunNotificationVoice.classify(
                [onlyInFile1Count: 0, onlyInFile2Count: 0, ruleDifferenceCount: 0, runFailed: true]) +
                [runName: "R", file1SystemLabel: "A", file2SystemLabel: "B",
                 priorCleanRuns: 9, completedMoment: null]
        String text = RunNotificationVoice.renderLines(model).join("\n")
        RunNotificationVoice.CLEAN_SUBLINES.each { assertFalse(text.contains(it), text) }
        assertFalse(text.toLowerCase().contains("in a row"), text)
    }

    private static Map cleanModel() {
        return RunNotificationVoice.classify(
                [onlyInFile1Count: 0, onlyInFile2Count: 0, ruleDifferenceCount: 0]) +
                [file1SystemLabel: "HotWax", file2SystemLabel: "Shopify",
                 priorCleanRuns: 0, completedMoment: null]
    }

    /**
     * Deliberate deviation from the written plan, which returned a bare headline for these buckets.
     * A run that failed partway still produced a partial result, and TenantNotificationServiceSmokeTests
     * had encoded that intent since the audit ("Differences are still reported so the partial result
     * is visible"). Only the flavour is suppressed above ISSUES, never the numbers.
     */
    @Test
    void failedAndIssueRunsStillReportTheirCounts() {
        RunNotificationVoice.setLinePicker { List<String> pool, String slotName -> pool.first() }
        // A list of pairs, not a map literal: an unquoted Groovy map key is a String, so
        // [true: ...] would hand the closure "true" rather than the boolean.
        [[true, RunNotificationVoice.BUCKET_FAILED], [false, RunNotificationVoice.BUCKET_ISSUES]]
                .each { List pair ->
            boolean failed = (boolean) pair[0]
            String expectedBucket = (String) pair[1]
            Map model = RunNotificationVoice.classify([onlyInFile1Count: 1, onlyInFile2Count: 3,
                                                       ruleDifferenceCount: 2,
                                                       runFailed: failed, hasWarnings: !failed]) +
                    [runName: "R", file1SystemLabel: "SHOPIFY", file2SystemLabel: "OMS",
                     priorCleanRuns: 0, completedMoment: null]
            assertEquals(expectedBucket, model.bucket)
            String text = RunNotificationVoice.renderLines(model).join("\n")
            assertTrue(text.contains("Missing from OMS: 1"), text)
            assertTrue(text.contains("Missing from SHOPIFY: 3"), text)
            assertTrue(text.contains("Mismatches: 2"), text)
        }
    }
}
