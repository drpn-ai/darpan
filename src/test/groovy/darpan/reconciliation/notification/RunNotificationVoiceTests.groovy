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
        (RunNotificationVoice.CLEAN_HEADLINES + RunNotificationVoice.CLEAN_TAILS).each { String line ->
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
        RunNotificationVoice.CLEAN_TAILS.each { assertFalse(text.contains(it), text) }
        assertFalse(text.toLowerCase().contains("in a row"), text)
    }

    @Test
    void cleanRunWithAStreakRendersTheVerdictAndTheStreakOnly() {
        RunNotificationVoice.setLinePicker { List<String> pool, String slotName -> pool.first() }
        // Early morning AND a streak: both closers are available, and only the streak may fire.
        ZonedDateTime earlyFriday = ZonedDateTime.of(2026, 8, 21, 5, 30, 0, 0, ZoneId.of("Asia/Kolkata"))
        List<String> lines = RunNotificationVoice.renderLines(
                cleanModel() + [runName: "Production Orders Automation",
                                priorCleanRuns: 3, completedMoment: earlyFriday])

        assertEquals(2, lines.size(), "a clean run carries exactly one closer: ${lines}".toString())
        assertTrue(lines.get(1).contains("4"), lines.toString())
        RunNotificationVoice.EARLY_MORNING_LINES.each { String timeLine ->
            assertFalse(lines.contains(timeLine), "time closer stacked on the streak: ${lines}".toString())
        }
    }

    @Test
    void cleanRunWithoutAStreakFallsBackToTheTimeOfDayCloser() {
        RunNotificationVoice.setLinePicker { List<String> pool, String slotName -> pool.first() }
        ZonedDateTime earlyWednesday = ZonedDateTime.of(2026, 8, 19, 5, 30, 0, 0, ZoneId.of("Asia/Kolkata"))
        List<String> lines = RunNotificationVoice.renderLines(
                cleanModel() + [runName: "SM Prod Orders Automation",
                                priorCleanRuns: 0, completedMoment: earlyWednesday])

        assertEquals(2, lines.size(), "a clean run carries exactly one closer: ${lines}".toString())
        assertEquals(RunNotificationVoice.EARLY_MORNING_LINES.first(), lines.get(1))
    }

    @Test
    void cleanRunWithNoStreakAndNoTimeHookCollapsesToOneFusedLine() {
        RunNotificationVoice.setLinePicker { List<String> pool, String slotName -> pool.first() }
        // 16:00 on a Wednesday hits none of the time windows, so there is no closer to spend a
        // second line on. Three lines of identical rhythm on every clean run is what made the
        // message read as assembled rather than written.
        ZonedDateTime plainWednesday = ZonedDateTime.of(2026, 8, 19, 16, 0, 0, 0, ZoneId.of("Asia/Kolkata"))
        List<String> lines = RunNotificationVoice.renderLines(
                cleanModel() + [runName: "Production Orders Automation",
                                priorCleanRuns: 0, completedMoment: plainWednesday])

        assertEquals(1, lines.size(), "with no context hook the verdict and tail fuse: ${lines}".toString())
        String only = lines.first()
        assertTrue(only.startsWith("Production Orders Automation \u2014 "), only)
        assertTrue(only.contains(RunNotificationVoice.CLEAN_HEADLINES.first()), only)
        assertTrue(only.endsWith(RunNotificationVoice.CLEAN_TAILS.first()), only)
    }

    @Test
    void cleanHeadlinesEndWithoutPunctuationSoTheyCanFuse() {
        RunNotificationVoice.CLEAN_HEADLINES.each { String headline ->
            assertFalse(headline.endsWith(".") || headline.endsWith("!"),
                    "a headline must fuse with a tail clause, so it carries no terminator: ${headline}".toString())
        }
    }

    @Test
    void cleanTailsAreGrammaticalContinuationsNotStandaloneSentences() {
        RunNotificationVoice.CLEAN_TAILS.each { String tail ->
            assertTrue(tail.startsWith(",") || tail.startsWith(" \u2014"),
                    "a tail attaches to the verdict; it is not its own sentence: ${tail}".toString())
        }
    }

    @Test
    void everyCloserCarriesASinglePunchline() {
        // Two punchlines in one breath compete and cancel. One sentence per closer.
        List<String> closers = RunNotificationVoice.STREAK_TEMPLATES +
                RunNotificationVoice.EARLY_MORNING_LINES + RunNotificationVoice.FRIDAY_AFTERNOON_LINES +
                RunNotificationVoice.MONDAY_MORNING_LINES + RunNotificationVoice.MIDDAY_LINES
        closers.each { String closer ->
            assertEquals(1, closer.count("."), "closer must be a single sentence: ${closer}".toString())
        }
    }

    @Test
    void noCloserRestatesTheVerdictsCleanlinessClaim() {
        // Dimension ownership: the headline is the ONLY place cleanliness is asserted, and every
        // closer adds a different axis - time, or the length of the series. A closer that says it
        // again is the synonym collision this restructure exists to remove. Applies to the streak
        // templates too: "clean the whole way through." followed by "That's 4 clean runs in a row"
        // echoes the verdict one line after making it.
        List<String> closers = RunNotificationVoice.EARLY_MORNING_LINES +
                RunNotificationVoice.FRIDAY_AFTERNOON_LINES + RunNotificationVoice.MONDAY_MORNING_LINES +
                RunNotificationVoice.MIDDAY_LINES + RunNotificationVoice.STREAK_TEMPLATES +
                RunNotificationVoice.CLEAN_TAILS
        closers.each { String closer ->
            assertFalse(closer.toLowerCase().contains("clean"),
                    "the headline already said it was clean: ${closer}".toString())
        }
    }

    private static Map shapeModel(int onlyIn1, int onlyIn2, int ruleDiffs) {
        return RunNotificationVoice.classify(
                [onlyInFile1Count: onlyIn1, onlyInFile2Count: onlyIn2, ruleDifferenceCount: ruleDiffs]) +
                [runName: "Production Orders Automation",
                 file1SystemLabel: "HotWax", file2SystemLabel: "Shopify",
                 priorCleanRuns: 0, completedMoment: null]
    }

    /** Every bucket that renders a headline + diagnosis pair, with counts that produce it. */
    private static List<Map> everyShapeBucket() {
        return [[label: "ONE_SIDED", model: shapeModel(39, 0, 0)],
                [label: "EVEN_SPLIT", model: shapeModel(12, 12, 0)],
                [label: "VALUE_DRIFT", model: shapeModel(0, 0, 8)],
                [label: "MIXED missing+mismatched", model: shapeModel(39, 0, 8)],
                [label: "MIXED missing only", model: shapeModel(1, 3, 0)]]
    }

    @Test
    void diagnosisLinesCarryNoNumbersBecauseTheDetailsBlockOwnsThem() {
        RunNotificationVoice.setLinePicker { List<String> pool, String slotName -> pool.first() }
        // The old sublines re-narrated the very counts printed three lines below them
        // ("39 went out and never checked in" over "Missing from Shopify: 39"). Prose may name the
        // SHAPE of a run; the Details block owns the arithmetic.
        everyShapeBucket().each { Map bucketCase ->
            String diagnosis = RunNotificationVoice.renderLines(bucketCase.model as Map).get(1)
            assertFalse(diagnosis ==~ /.*\d.*/,
                    "${bucketCase.label} diagnosis repeats a count: ${diagnosis}".toString())
        }
    }

    @Test
    void diagnosisLinesAreSingleSentences() {
        RunNotificationVoice.setLinePicker { List<String> pool, String slotName -> pool.first() }
        // Stacked fragments ("Nothing's missing. They just don't line up.") are the mechanical
        // rhythm that made these read as assembled.
        everyShapeBucket().each { Map bucketCase ->
            String diagnosis = RunNotificationVoice.renderLines(bucketCase.model as Map).get(1)
            assertEquals(1, diagnosis.count("."),
                    "${bucketCase.label} diagnosis is more than one sentence: ${diagnosis}".toString())
        }
    }

    @Test
    void oneSidedHeadlineNamesTheSystemMissingTheRecords() {
        RunNotificationVoice.setLinePicker { List<String> pool, String slotName -> pool.first() }
        // onlyInFile1Count 39 means HotWax has them and SHOPIFY does not.
        String headline = RunNotificationVoice.renderLines(shapeModel(39, 0, 0)).first()
        assertTrue(headline.contains("39 to look at, all missing from Shopify"), headline)
    }

    @Test
    void evenSplitHeadlineNamesTheSplitSoTheDiagnosisNeedNot() {
        RunNotificationVoice.setLinePicker { List<String> pool, String slotName -> pool.first() }
        String headline = RunNotificationVoice.renderLines(shapeModel(12, 12, 0)).first()
        assertTrue(headline.contains("24 to look at, near-evenly split"), headline)
    }

    @Test
    void valueDriftHeadlineSaysNothingIsActuallyMissing() {
        RunNotificationVoice.setLinePicker { List<String> pool, String slotName -> pool.first() }
        String headline = RunNotificationVoice.renderLines(shapeModel(0, 0, 8)).first()
        assertTrue(headline.contains("8 to look at, all value mismatches"), headline)
    }

    @Test
    void mixedHeadlineDistinguishesMissingOnlyFromMissingPlusMismatched() {
        RunNotificationVoice.setLinePicker { List<String> pool, String slotName -> pool.first() }
        // MIXED is a catch-all: it covers "both sides missing, lopsided" AND "missing plus value
        // mismatches". One headline for both told the operator the wrong thing about the first,
        // and the old diagnosis printed "0 more don't match on the values" for it.
        String bothAxes = RunNotificationVoice.renderLines(shapeModel(39, 0, 8)).first()
        assertTrue(bothAxes.contains("47 to look at, missing and mismatched"), bothAxes)

        String missingOnly = RunNotificationVoice.renderLines(shapeModel(1, 3, 0)).first()
        assertTrue(missingOnly.contains("4 to look at, missing on both sides"), missingOnly)
    }

    @Test
    void issuesHeaderReadsAsOneClauseNotTwoFragments() {
        RunNotificationVoice.setLinePicker { List<String> pool, String slotName -> pool.first() }
        Map model = RunNotificationVoice.classify(
                [onlyInFile1Count: 0, onlyInFile2Count: 0, ruleDifferenceCount: 0, hasWarnings: true]) +
                [runName: "API Order Sync", file1SystemLabel: "A", file2SystemLabel: "B",
                 priorCleanRuns: 0, completedMoment: null]
        String headline = RunNotificationVoice.renderLines(model).first()
        assertEquals("API Order Sync finished, but not cleanly.", headline)
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
