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
            assertTrue(tail.startsWith(",") || tail.startsWith(";"),
                    "a tail attaches to the verdict; it is not its own sentence: ${tail}".toString())
            // The verdict already spends an em dash between the run name and the headline, so a
            // dashed tail puts two in one sentence.
            assertFalse(tail.contains("\u2014"),
                    "a dashed tail doubles the verdict's own dash: ${tail}".toString())
        }
    }

    @Test
    void everyCloserCarriesASinglePunchline() {
        // Two punchlines in one breath compete and cancel. One sentence per closer.
        allCloserLines().each { String closer ->
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
        (allCloserLines() + RunNotificationVoice.CLEAN_TAILS).each { String closer ->
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

    // -------------------------------------------------------------------------------------
    // Corpus size and non-repetition.
    //
    // A clean run is the message an operator sees most, and pool size alone never fixed it:
    // uniform random WITH replacement collides on the birthday bound, so the first repeat lands
    // at roughly 1.25*sqrt(N) draws. Eight headlines therefore repeated inside about four
    // messages however well written they were. Size raises the ceiling; the cycle dealer below
    // is what actually makes a repeat rare, by spending a pool out before reshuffling it.
    // -------------------------------------------------------------------------------------

    /**
     * Clean runs an operator may plausibly see in a week — a twice-hourly automation on a working
     * week, not a daily one. The corpus must outlast it without repeating a message.
     */
    private static final int WEEK_OF_RUNS = 75

    /** Every pool that can supply the closing line of a clean run. */
    private static List<String> allCloserLines() {
        return RunNotificationVoice.STREAK_TEMPLATES + RunNotificationVoice.LONG_STREAK_TEMPLATES +
                RunNotificationVoice.EARLY_MORNING_LINES + RunNotificationVoice.FRIDAY_AFTERNOON_LINES +
                RunNotificationVoice.MONDAY_MORNING_LINES + RunNotificationVoice.MIDDAY_LINES +
                RunNotificationVoice.LATE_EVENING_LINES + RunNotificationVoice.WEEKEND_LINES
    }

    private static List<String> allDiagnosisLines() {
        return RunNotificationVoice.ONE_SIDED_DIAGNOSES + RunNotificationVoice.EVEN_SPLIT_DIAGNOSES +
                RunNotificationVoice.VALUE_DRIFT_DIAGNOSES +
                RunNotificationVoice.MIXED_MISMATCH_DIAGNOSES +
                RunNotificationVoice.MIXED_MISSING_DIAGNOSES
    }

    private static List<List<String>> everyPool() {
        return [RunNotificationVoice.CLEAN_HEADLINES, RunNotificationVoice.CLEAN_TAILS,
                RunNotificationVoice.STREAK_TEMPLATES, RunNotificationVoice.LONG_STREAK_TEMPLATES,
                RunNotificationVoice.EARLY_MORNING_LINES, RunNotificationVoice.FRIDAY_AFTERNOON_LINES,
                RunNotificationVoice.MONDAY_MORNING_LINES, RunNotificationVoice.MIDDAY_LINES,
                RunNotificationVoice.LATE_EVENING_LINES, RunNotificationVoice.WEEKEND_LINES,
                RunNotificationVoice.ONE_SIDED_DIAGNOSES, RunNotificationVoice.EVEN_SPLIT_DIAGNOSES,
                RunNotificationVoice.VALUE_DRIFT_DIAGNOSES,
                RunNotificationVoice.MIXED_MISMATCH_DIAGNOSES,
                RunNotificationVoice.MIXED_MISSING_DIAGNOSES, RunNotificationVoice.SHAPE_FRAMES]
    }

    @Test
    void theHeadlinePoolAloneCoversAFullWeekOfCleanRuns() {
        // The headline is the one slot every clean message spends, so it sets the repeat floor for
        // the whole message. One full dealer cycle has to outlast a week on its own.
        assertTrue(RunNotificationVoice.CLEAN_HEADLINES.size() >= WEEK_OF_RUNS,
                "headline pool is ${RunNotificationVoice.CLEAN_HEADLINES.size()}, under a week of runs".toString())
    }

    @Test
    void everySupportingPoolIsDeepEnoughToAvoidAVisiblePattern() {
        Map<String, Integer> floors = [
                tails         : 60, streaks: 30, cappedStreaks: 12,
                earlyMorning  : 18, fridayAfternoon: 18, mondayMorning: 18,
                midday        : 18, lateEvening: 18, weekend: 18]
        Map<String, List<String>> pools = [
                tails         : RunNotificationVoice.CLEAN_TAILS,
                streaks       : RunNotificationVoice.STREAK_TEMPLATES,
                cappedStreaks : RunNotificationVoice.LONG_STREAK_TEMPLATES,
                earlyMorning  : RunNotificationVoice.EARLY_MORNING_LINES,
                fridayAfternoon: RunNotificationVoice.FRIDAY_AFTERNOON_LINES,
                mondayMorning : RunNotificationVoice.MONDAY_MORNING_LINES,
                midday        : RunNotificationVoice.MIDDAY_LINES,
                lateEvening   : RunNotificationVoice.LATE_EVENING_LINES,
                weekend       : RunNotificationVoice.WEEKEND_LINES]
        floors.each { String name, Integer floor ->
            assertTrue(pools.get(name).size() >= floor,
                    "${name} pool is ${pools.get(name).size()}, below its floor of ${floor}".toString())
        }
        allDiagnosisLines() // touched so an empty diagnosis pool fails loudly below
        [RunNotificationVoice.ONE_SIDED_DIAGNOSES, RunNotificationVoice.EVEN_SPLIT_DIAGNOSES,
         RunNotificationVoice.VALUE_DRIFT_DIAGNOSES, RunNotificationVoice.MIXED_MISMATCH_DIAGNOSES,
         RunNotificationVoice.MIXED_MISSING_DIAGNOSES].each { List<String> pool ->
            assertTrue(pool.size() >= 10, "diagnosis pool is only ${pool.size()} deep: ${pool}".toString())
        }
    }

    @Test
    void noPoolContainsADuplicateEntry() {
        // A duplicated line halves the effective cycle length silently.
        everyPool().each { List<String> pool ->
            assertEquals(pool.size(), pool.toSet().size(),
                    "pool holds a duplicate entry: ${pool}".toString())
        }
    }

    @Test
    void theDealerSpendsEveryEntryBeforeReshuffling() {
        RunNotificationVoice.resetLinePicker()
        List<String> pool = RunNotificationVoice.CLEAN_HEADLINES
        List<String> dealt = (1..pool.size()).collect {
            RunNotificationVoice.pickLine(pool, "headline")
        }
        assertEquals(pool.size(), dealt.toSet().size(),
                "a single cycle repeated a line, so selection is still with replacement: ${dealt}".toString())
        assertEquals(pool.toSet(), dealt.toSet())
    }

    @Test
    void theDealerNeverRepeatsBackToBackAcrossACycleBoundary() {
        // A fresh shuffle can otherwise open on the entry the previous cycle closed with, which is
        // the one adjacent repeat a cycle dealer cannot rule out by construction.
        RunNotificationVoice.resetLinePicker()
        List<String> pool = RunNotificationVoice.CLEAN_TAILS
        List<String> dealt = (1..(pool.size() * 4)).collect {
            RunNotificationVoice.pickLine(pool, "tail")
        }
        dealt.eachWithIndex { String line, int index ->
            if (index > 0) {
                assertFalse(line == dealt.get(index - 1),
                        "back-to-back repeat at draw ${index}: ${line}".toString())
            }
        }
    }

    @Test
    void poolsSharingASlotNameKeepSeparateCycles() {
        // Every time-of-day pool is drawn under the slot name "timeOfDay". Keying the dealer on the
        // slot name alone would deal Monday copy into a Saturday message.
        RunNotificationVoice.resetLinePicker()
        20.times {
            assertTrue(RunNotificationVoice.MIDDAY_LINES.contains(
                    RunNotificationVoice.pickLine(RunNotificationVoice.MIDDAY_LINES, "timeOfDay")))
            assertTrue(RunNotificationVoice.WEEKEND_LINES.contains(
                    RunNotificationVoice.pickLine(RunNotificationVoice.WEEKEND_LINES, "timeOfDay")))
        }
    }

    @Test
    void aWeekOfCleanRunsProducesNoRepeatedMessage() {
        RunNotificationVoice.resetLinePicker()
        // 16:00 Wednesday hits no time window, so this is the leanest clean message there is:
        // one fused line, headline plus tail, and nothing else to carry variety.
        ZonedDateTime plainWednesday = ZonedDateTime.of(
                2026, 8, 19, 16, 0, 0, 0, ZoneId.of("Asia/Kolkata"))
        List<String> rendered = (1..WEEK_OF_RUNS).collect {
            RunNotificationVoice.renderLines(cleanModel() +
                    [runName: "Production Orders Automation", priorCleanRuns: 0,
                     completedMoment: plainWednesday]).join("\n")
        }
        assertEquals(WEEK_OF_RUNS, rendered.toSet().size(),
                "a week of clean runs repeated a message: ${rendered.countBy { it }.findAll { it.value > 1 }}".toString())
    }

    @Test
    void aWeekOfStreakingCleanRunsProducesNoRepeatedMessage() {
        RunNotificationVoice.resetLinePicker()
        // The likeliest real shape: a daily automation that has been clean for a while, so the
        // streak closer wins every time and the message is headline plus streak.
        List<String> rendered = (1..WEEK_OF_RUNS).collect { int index ->
            RunNotificationVoice.renderLines(cleanModel() +
                    [runName: "Production Orders Automation", priorCleanRuns: 4,
                     completedMoment: null]).join("\n")
        }
        assertEquals(WEEK_OF_RUNS, rendered.toSet().size(),
                "a week of streaking clean runs repeated a message: ${rendered.countBy { it }.findAll { it.value > 1 }}".toString())
    }

    @Test
    void aCappedStreakStillVariesItsClosingLine() {
        RunNotificationVoice.resetLinePicker()
        // At the lookback cap the renderer used to return one frozen string, so an automation that
        // had been clean for twenty runs printed an identical second line forever. That is the
        // staleness this whole change exists to remove, and it got worse the healthier a tenant was.
        List<String> dealt = (1..RunNotificationVoice.LONG_STREAK_TEMPLATES.size()).collect {
            RunNotificationVoice.streakLine(RunNotificationVoice.LOOKBACK_LIMIT)
        }
        assertEquals(RunNotificationVoice.LONG_STREAK_TEMPLATES.size(), dealt.toSet().size(),
                "capped streak copy is still frozen: ${dealt}".toString())
    }

    @Test
    void everyStreakTemplateKeepsTheCountPlaceholder() {
        (RunNotificationVoice.STREAK_TEMPLATES + RunNotificationVoice.LONG_STREAK_TEMPLATES)
                .each { String template ->
            assertTrue(template.contains("{n}"),
                    "a streak line that drops the number is just flavour: ${template}".toString())
        }
    }

    @Test
    void everyCappedStreakLineRendersTheLookbackLimit() {
        RunNotificationVoice.resetLinePicker()
        RunNotificationVoice.LONG_STREAK_TEMPLATES.size().times {
            String line = RunNotificationVoice.streakLine(RunNotificationVoice.LOOKBACK_LIMIT)
            assertTrue(line.contains(Integer.toString(RunNotificationVoice.LOOKBACK_LIMIT)), line)
            assertFalse(line.contains("{n}"), "unrendered placeholder: ${line}".toString())
        }
    }

    @Test
    void everyDiagnosisLineIsASingleSentenceCarryingNoCounts() {
        // Same discipline the first entry of each pool was already held to: the Details block owns
        // the arithmetic, and stacked fragments are the mechanical rhythm this voice avoids.
        allDiagnosisLines().each { String line ->
            assertEquals(1, line.count("."),
                    "diagnosis is more than one sentence: ${line}".toString())
            assertFalse(line ==~ /.*\d.*/, "diagnosis repeats a count: ${line}".toString())
        }
    }

    @Test
    void everyDiagnosisPoolEntryStaysReachableThroughTheRenderer() {
        // Each bucket must draw from its own pool, not fall back to a shared default.
        everyShapeBucket().each { Map bucketCase ->
            RunNotificationVoice.resetLinePicker()
            Set<String> seen = (1..40).collect {
                RunNotificationVoice.renderLines(bucketCase.model as Map).get(1)
            }.toSet()
            assertTrue(seen.size() >= 10,
                    "${bucketCase.label} only ever renders ${seen.size()} diagnosis lines: ${seen}".toString())
        }
    }

    @Test
    void noPoolAnywhereUsesTheBannedAgreeWording() {
        // Project copy rule: systems "line up", they never "agree". Checked across the whole corpus
        // rather than the two clean pools, and as a substring so "disagreement" is caught too.
        (RunNotificationVoice.CLEAN_HEADLINES + RunNotificationVoice.CLEAN_TAILS +
                allCloserLines() + allDiagnosisLines()).each { String line ->
            assertFalse(line.toLowerCase().contains("agree"), "banned wording in copy: ${line}".toString())
        }
    }

    @Test
    void weekendRunsGetTheirOwnCloser() {
        RunNotificationVoice.setLinePicker { List<String> pool, String slotName -> pool.first() }
        // 2026-08-22 is a Saturday. A run landing on a day nobody is working is a different fact
        // about the reader's day than "midday", which is what it used to be flattened into.
        ZonedDateTime saturdayMidday = ZonedDateTime.of(
                2026, 8, 22, 12, 0, 0, 0, ZoneId.of("Asia/Kolkata"))
        assertEquals(RunNotificationVoice.WEEKEND_LINES.first(),
                RunNotificationVoice.timeOfDayLine(saturdayMidday))
    }

    @Test
    void lateEveningRunsGetTheirOwnCloser() {
        RunNotificationVoice.setLinePicker { List<String> pool, String slotName -> pool.first() }
        ZonedDateTime wednesdayNight = ZonedDateTime.of(
                2026, 8, 19, 22, 30, 0, 0, ZoneId.of("Asia/Kolkata"))
        assertEquals(RunNotificationVoice.LATE_EVENING_LINES.first(),
                RunNotificationVoice.timeOfDayLine(wednesdayNight))
    }

    @Test
    void earlyMorningOutranksTheWeekendWindow() {
        RunNotificationVoice.setLinePicker { List<String> pool, String slotName -> pool.first() }
        // Saturday 05:30 is both, and "before anyone was at a desk" is the sharper observation.
        ZonedDateTime saturdayDawn = ZonedDateTime.of(
                2026, 8, 22, 5, 30, 0, 0, ZoneId.of("Asia/Kolkata"))
        assertEquals(RunNotificationVoice.EARLY_MORNING_LINES.first(),
                RunNotificationVoice.timeOfDayLine(saturdayDawn))
    }

    @Test
    void aPlainWeekdayAfternoonStillHasNoTimeCloser() {
        // Load-bearing: a null here with no streak is what collapses the message to one fused line,
        // so the message SHAPE varies with what is actually true. Widening the windows must not
        // quietly make every clean run two lines again.
        RunNotificationVoice.setLinePicker { List<String> pool, String slotName -> pool.first() }
        ZonedDateTime wednesdayLateAfternoon = ZonedDateTime.of(
                2026, 8, 19, 16, 0, 0, 0, ZoneId.of("Asia/Kolkata"))
        assertEquals(null, RunNotificationVoice.timeOfDayLine(wednesdayLateAfternoon))
        ZonedDateTime tuesdayMidMorning = ZonedDateTime.of(
                2026, 8, 18, 10, 0, 0, 0, ZoneId.of("Asia/Kolkata"))
        assertEquals(null, RunNotificationVoice.timeOfDayLine(tuesdayMidMorning))
    }

    // -------------------------------------------------------------------------------------
    // Register guards.
    //
    // The clean-run copy is allowed to be funny — it is the one message nobody has to action, and
    // a flat one stops being read. But it posts into CUSTOMER Google Chat spaces, not an internal
    // channel, so "funny" has a hard boundary: it may be about the data behaving, never about a
    // person. These tests hold that boundary mechanically, because the pools are the part of this
    // file most likely to be extended quickly and least likely to be reviewed carefully.
    // -------------------------------------------------------------------------------------

    private static List<String> wholeCorpus() {
        return RunNotificationVoice.CLEAN_HEADLINES + RunNotificationVoice.CLEAN_TAILS +
                allCloserLines() + allDiagnosisLines() + RunNotificationVoice.SHAPE_FRAMES
    }

    @Test
    void noCopyAnywhereReadsAsUnprofessionalInACustomerChatSpace() {
        // Word boundaries, not substrings: "assembled" and "asset" are not profanity, and a
        // substring match would ban them and quietly shrink the corpus instead of failing loudly.
        List<String> banned = ["damn", "damned", "hell", "crap", "wtf", "sucks", "sucked", "screwed",
                               "bloody", "ass", "idiot", "idiots", "stupid", "dumb", "moron", "lazy",
                               "incompetent", "useless", "clueless", "amateur", "sloppy"]
        wholeCorpus().each { String line ->
            banned.each { String word ->
                assertFalse(line.toLowerCase() ==~ /.*\b${word}\b.*/,
                        "copy that posts to a customer chat space cannot say \"${word}\": ${line}".toString())
            }
        }
    }

    @Test
    void noCopyBlamesTheReaderOrAnyoneElse() {
        // Humour about the data behaving is in scope. Humour that lands on a person is not, and the
        // reader of a reconciliation notification is often the person who owns the pipeline.
        List<String> blaming = ["your fault", "you broke", "you forgot", "you missed", "your mistake",
                                "someone messed", "someone broke", "whoever broke", "finally"]
        wholeCorpus().each { String line ->
            blaming.each { String phrase ->
                assertFalse(line.toLowerCase().contains(phrase),
                        "copy points at a person rather than the data: ${line}".toString())
            }
        }
    }

    @Test
    void noCopyShoutsOrCarriesCharactersGoogleChatWillNotRenderPlainly() {
        // Quiet confidence is the voice; an exclamation mark is the pep-talk register it rules out.
        // The character check is practical rather than stylistic: this payload is plain text, and a
        // smart quote or an emoji pasted in from a doc renders inconsistently across Chat clients.
        wholeCorpus().each { String line ->
            assertFalse(line.contains("!"), "copy shouts: ${line}".toString())
            line.toCharArray().each { char c ->
                boolean plain = ((int) c) < 128 || ((int) c) == 0x2014
                assertTrue(plain,
                        "non-plain character U+${Integer.toHexString((int) c)} in copy: ${line}".toString())
            }
        }
    }

    /**
     * Words too common to count as an echo. Without these the check would fire on almost every
     * pair, because "nothing" is the single most useful word in both halves of this message.
     */
    private static final Set<String> FILLER_WORDS = [
            "nothing", "anything", "everything", "something", "nobody", "anybody", "anyone",
            "someone", "everyone", "another", "because", "without", "whatever", "though",
            "should", "before", "after", "which", "there", "their", "still", "almost",
            "really", "actually", "entire", "entirely", "genuinely"] as Set

    /** Six-character stems, so "boringly" and "boring" register as the same word. */
    private static Set<String> contentStems(String line) {
        return ((line.toLowerCase() =~ /[a-z]+/).collect { it as String })
                .findAll { String word -> word.length() >= 6 && !FILLER_WORDS.contains(word) }
                .collect { String word -> word.substring(0, 6) } as Set
    }

    @Test
    void noHeadlineAndTailPairRepeatsAContentWord() {
        // The headline and the tail are drawn independently, so EVERY pair is reachable and the
        // whole grid has to hold. It shipped one round with "both sides told the same boring story,
        // which is the most boring thing you'll read today" reachable, which is the failure mode a
        // bigger corpus makes MORE likely, not less: more entries means more chances that two of
        // them reach for the same joke.
        //
        // Scoped to the fused one-line form on purpose. Headline and tail land in one sentence,
        // where an echo is glaring; a closer is a separate sentence on its own line, where the same
        // repetition reads as a callback rather than a stumble.
        RunNotificationVoice.CLEAN_HEADLINES.each { String headline ->
            Set<String> headlineStems = contentStems(headline)
            RunNotificationVoice.CLEAN_TAILS.each { String tail ->
                Collection<String> shared = headlineStems.intersect(contentStems(tail))
                assertTrue(shared.isEmpty(),
                        "headline and tail echo ${shared}: ${headline}${tail}".toString())
            }
        }
    }

    @Test
    void everyShapeFrameReadsCorrectlyAtOneAndAtMany() {
        // A frame is interpolated with a raw count, so it must be number-agnostic. "{n} differences"
        // and "{n} need a look" both render "1 differences" on a single-difference run — which is
        // the run an operator is most likely to be reading closely, not least.
        List<String> agreementTraps = ["differences", "records", "need ", "are ", "items"]
        RunNotificationVoice.SHAPE_FRAMES.each { String frame ->
            assertTrue(frame.contains("{n}"), "frame drops the count: ${frame}".toString())
            agreementTraps.each { String trap ->
                assertFalse(frame.toLowerCase().contains(trap),
                        "frame breaks at a count of one: ${frame}".toString())
            }
        }
    }

    @Test
    void aRecurringProblemDoesNotRenderAByteIdenticalHeadlineEveryDay() {
        // This surface was a single hardcoded string for three rounds. A clean run at least varied;
        // a tenant working through a recurring one-sided gap got the same headline every day for as
        // long as the problem lasted, which is the reader with the LEAST patience for it.
        RunNotificationVoice.resetLinePicker()
        Set<String> headlines = (1..RunNotificationVoice.SHAPE_FRAMES.size()).collect {
            RunNotificationVoice.renderLines(shapeModel(39, 0, 0)).first()
        }.toSet()
        assertEquals(RunNotificationVoice.SHAPE_FRAMES.size(), headlines.size(),
                "recurring-problem headline still repeats: ${headlines}".toString())
        // The facts inside the frame are NOT variable: every rendering still carries the count and
        // names the system that is short.
        headlines.each { String headline ->
            assertTrue(headline.contains("39"), headline)
            assertTrue(headline.contains("all missing from Shopify"), headline)
        }
    }
}
