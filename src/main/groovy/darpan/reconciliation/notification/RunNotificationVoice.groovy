package darpan.reconciliation.notification

import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns a completed run's counts into a verdict bucket and, in later stages, into the Google Chat
 * message text. Deliberately free of Moqui and I/O so it runs in the fast unitTest pool.
 */
class RunNotificationVoice {

    static final String BUCKET_FAILED = "FAILED"
    static final String BUCKET_ISSUES = "ISSUES"
    static final String BUCKET_CLEAN = "CLEAN"
    static final String BUCKET_EVEN_SPLIT = "EVEN_SPLIT"
    static final String BUCKET_ONE_SIDED = "ONE_SIDED"
    static final String BUCKET_VALUE_DRIFT = "VALUE_DRIFT"
    static final String BUCKET_MIXED = "MIXED"

    /**
     * Fraction of the larger missing count that the smaller must reach to read as an even split.
     * Arbitrary, and gates a copy variant only — it must never affect a number the operator acts on.
     */
    static final double EVEN_SPLIT_FLOOR = 0.8d

    static int toCount(Object rawValue) {
        if (rawValue instanceof Number) return ((Number) rawValue).intValue()
        String text = ((rawValue)?.toString()?.trim())
        if (!text || !text.isInteger()) return 0
        return text.toInteger()
    }

    static Map<String, Object> classify(Map<String, Object> counts) {
        Map<String, Object> safeCounts = (counts ?: [:]) as Map<String, Object>

        // onlyInFile1Count == "present only in file 1" == "missing from file 2's system".
        // The names below pair with the label of the same number, so a mismatched pairing is
        // visible at the call site. See AutomationExecutionSupport.groovy:1743.
        int missingFromFile2Count = toCount(safeCounts.get("onlyInFile1Count"))
        int missingFromFile1Count = toCount(safeCounts.get("onlyInFile2Count"))
        int mismatchCount = toCount(safeCounts.get("ruleDifferenceCount"))
        int totalCount = missingFromFile1Count + missingFromFile2Count + mismatchCount

        String bucket = resolveBucket(safeCounts, missingFromFile1Count, missingFromFile2Count, mismatchCount)

        return [
                bucket               : bucket,
                missingFromFile1Count: missingFromFile1Count,
                missingFromFile2Count: missingFromFile2Count,
                mismatchCount        : mismatchCount,
                totalCount           : totalCount,
        ] as Map<String, Object>
    }

    private static String resolveBucket(Map<String, Object> counts, int missingFromFile1Count,
                                        int missingFromFile2Count, int mismatchCount) {
        if (counts.get("runFailed") == true) return BUCKET_FAILED
        if (counts.get("hasWarnings") == true) return BUCKET_ISSUES

        int missingTotal = missingFromFile1Count + missingFromFile2Count
        if (missingTotal == 0 && mismatchCount == 0) return BUCKET_CLEAN
        if (missingTotal == 0) return BUCKET_VALUE_DRIFT

        boolean bothSidesMissing = missingFromFile1Count > 0 && missingFromFile2Count > 0
        if (bothSidesMissing && mismatchCount == 0) {
            int larger = Math.max(missingFromFile1Count, missingFromFile2Count)
            int smaller = Math.min(missingFromFile1Count, missingFromFile2Count)
            if (larger > 0 && (smaller / (double) larger) >= EVEN_SPLIT_FLOOR) return BUCKET_EVEN_SPLIT
            return BUCKET_MIXED
        }
        if (!bothSidesMissing && mismatchCount == 0) return BUCKET_ONE_SIDED
        return BUCKET_MIXED
    }

    private static Closure linePicker = null
    private static final Random RANDOM = new Random()

    /**
     * One shuffled, partly-dealt cycle per pool. Keyed on slot name AND pool contents, because
     * every time-of-day pool is drawn under the slot name "timeOfDay" — keying on the slot alone
     * would deal Monday copy into a Saturday message.
     */
    private static final Map<String, List<String>> CYCLES = new ConcurrentHashMap<String, List<String>>()
    private static final Map<String, String> LAST_DEALT = new ConcurrentHashMap<String, String>()
    private static final Object CYCLE_LOCK = new Object()

    /**
     * Callers pass constant pools, so this map is naturally tiny. The bound only exists so a caller
     * passing freshly-built lists can never grow it without limit.
     */
    private static final int MAX_TRACKED_POOLS = 64

    static void setLinePicker(Closure picker) { linePicker = picker }

    /** Also clears the dealer, so one test's draws can never bias the next. */
    static void resetLinePicker() {
        linePicker = null
        resetLineHistory()
    }

    static void resetLineHistory() {
        synchronized (CYCLE_LOCK) {
            CYCLES.clear()
            LAST_DEALT.clear()
        }
    }

    /**
     * Deals a line, pinned by tests through {@link #setLinePicker}.
     *
     * Selection is a shuffled cycle, not an independent draw per message. Uniform random WITH
     * replacement was the original approach and it is why this copy went stale in days: the
     * birthday bound puts the first repeat at roughly 1.25*sqrt(N) draws, so eight headlines
     * repeated inside about four messages and no amount of rewriting moved that. A cycle spends
     * every entry of a pool before reshuffling, which turns "N entries" into a guarantee of N
     * draws without repetition rather than an average of four.
     *
     * A deterministic seed off reconciliationRunResultId was rejected separately: ids are
     * sequential, so a modulo marches through the pool in visible order.
     */
    static String pickLine(List<String> pool, String slotName) {
        if (!pool) return null
        if (linePicker != null) return (String) linePicker.call(pool, slotName)
        if (pool.size() == 1) return pool.get(0)
        return dealFromCycle(pool, slotName)
    }

    private static String dealFromCycle(List<String> pool, String slotName) {
        String key = "${slotName}|${pool.size()}|${pool.hashCode()}".toString()
        synchronized (CYCLE_LOCK) {
            if (CYCLES.size() > MAX_TRACKED_POOLS) resetLineHistory()

            List<String> cycle = CYCLES.get(key)
            if (cycle == null || cycle.isEmpty()) {
                cycle = shuffledCycle(pool, LAST_DEALT.get(key))
                CYCLES.put(key, cycle)
            }
            String dealt = cycle.remove(0)
            LAST_DEALT.put(key, dealt)
            return dealt
        }
    }

    private static List<String> shuffledCycle(List<String> pool, String lastDealt) {
        List<String> cycle = new ArrayList<String>(pool)
        Collections.shuffle(cycle, RANDOM)
        // A fresh cycle can open on the entry the previous one closed with. That back-to-back
        // repeat is the single collision a cycle cannot rule out by construction, and it is also
        // the most visible one, so it is swapped away rather than tolerated.
        if (lastDealt != null && cycle.size() > 1 && cycle.get(0) == lastDealt) {
            Collections.swap(cycle, 0, 1 + RANDOM.nextInt(cycle.size() - 1))
        }
        return cycle
    }

    /**
     * The verdict clause. This is the ONE place a clean run asserts that it is clean — every other
     * pool adds a different dimension. Entries carry no terminator: the plain tier fuses a tail
     * clause straight onto them, and the renderer supplies the full stop otherwise.
     *
     * Sized to outlast a week on its own. The headline is the slot every clean message spends, so
     * one full cycle of it sets the repeat floor for the entire message.
     *
     * Wit is welcome here, but every entry still has to state the fact plainly enough to stand alone:
     * this is the only place the message says the run was clean, so an entry that is funny without
     * being clear costs the reader the one thing they came for.
     */
    static final List<String> CLEAN_HEADLINES = [
            "every record lined up",
            "everything lined up, both sides",
            "no gaps, no mismatches",
            "every record accounted for on both sides",
            "both systems came back with the same story",
            "clean the whole way through",
            "nothing out of place on either side",
            "every record matched, start to finish",
            "both sides lined up on every record",
            "not one record off",
            "nothing missing, nothing mismatched",
            "the two systems told the same story end to end",
            "every row found its pair",
            "zero on all three axes",
            "both sides came back identical",
            "no missing records, no value drift",
            "everything reconciled on the first pass",
            "the whole set lined up",
            "every record present on both sides, values and all",
            "not one thing to work through",
            "both sides balanced to the record",
            "nothing fell through",
            "all rows present, all values lined up",
            "no drift, no gaps",
            "matched end to end",
            "everything checks out on both sides",
            "nothing missing and nothing off",
            "every record squared away",
            "both sides came out even",
            "not a single one out of place",
            "the full set lined up, both directions",
            "no records adrift",
            "nothing left unmatched",
            "every record where it should be",
            "both sides in step, record for record",
            "the comparison came back empty",
            "no differences of any kind",
            "every record paired off",
            "everything lined up, predictably",
            "not one record out of line",
            "spotless on both sides",
            "the whole set matched, no drama",
            "matched so completely it's almost suspicious",
            "both sides identical, top to bottom",
            "zero differences, zero excitement",
            "every record lined up, first try",
            "nothing wrong with it anywhere",
            "a deeply unremarkable match",
            "both sides told the same dull story",
            "no surprises anywhere in it",
            "the dullest possible verdict",
            "everything matched and nothing happened",
            "no discrepancies at all",
            "everything present and correct",
            "both sides tallied, right down to the row",
            "the match came out perfect",
            "not one row misplaced",
            "all values intact on both sides",
            "a flawless pass",
            "the two sides are indistinguishable",
            "nothing at all out of order",
            "every last row lined up",
            "both sides came through untouched",
            "not a discrepancy in sight",
            "a textbook match",
            "everything squared, both directions",
            "not one row unaccounted for",
            "the whole comparison came out flat",
            "both sides came back word-perfect",
            "not a sliver of daylight",
            "every row present, every value matched",
            "the two sides are a carbon copy",
            "nothing here needs another pass",
            "both sides came out spotless",
            "a completely quiet run",
            "the comparison found precisely nothing",
            "both sides brought receipts and they match",
            "the pipeline understood the assignment",
    ].asImmutable()

    /**
     * Fused onto the verdict when there is no streak and no time hook. These are continuations, not
     * sentences: each opens with a comma or a semicolon so it attaches grammatically. An earlier pool
     * held standalone sentences, every one of which re-asserted the headline's own claim.
     *
     * No tail opens with a dash. The verdict already spends one separating the run name from the
     * headline, so a dashed tail rendered "Orders Automation — no records adrift — nothing to
     * escalate": two dashes in one sentence, which reads as assembled however good the clause is.
     * Alternating comma and semicolon varies the rhythm instead, which the dash never did.
     *
     * This is the punchline slot — it lands last in a one-line message — so it carries most of what
     * personality the message has. The register is mixed on purpose: some entries are flat and some
     * are funny, and which one a reader gets is a coin toss. A uniformly witty pool is worse than a
     * dry one, because every message then arrives trying, and trying is what ages. Humour here is
     * always about the DATA behaving, never about a person: these post into customer chat spaces,
     * so nothing may read as a comment on anyone's competence or as a joke at their expense.
     */
    static final List<String> CLEAN_TAILS = [
            ", nothing to chase.",
            ", nothing owed.",
            "; nothing here needs a follow-up.",
            ", so this one's not going in anyone's standup.",
            ", and nobody has to be a hero today.",
            "; no thread, no \"quick question\".",
            ", so there's nothing to action.",
            "; nothing for the exceptions queue.",
            ", which means nobody has to open a spreadsheet.",
            "; no one needs to look at this.",
            ", and that's the whole report.",
            "; nothing to escalate.",
            ", so the queue stays empty.",
            "; no exceptions, no follow-up, no meeting.",
            ", nothing further.",
            "; that's the entire finding.",
            ", so you can close the tab.",
            "; nothing worth a second look.",
            ", and there's nothing sitting behind it.",
            "; nobody's month-end just got longer.",
            ", so nothing lands on anyone's desk.",
            "; nothing to hand off.",
            ", which is the boring outcome you wanted.",
            "; no digging required.",
            ", and nobody has to explain anything later.",
            "; file it and move on.",
            ", so there's no exception list to divide up.",
            "; nothing to write up.",
            "; a genuinely uneventful result.",
            ", which is the most boring thing you'll read today.",
            "; nothing for anyone to be brave about.",
            ", so the exceptions queue stays a rumour.",
            "; the spreadsheet stays shut.",
            ", and the pivot table can stay in its box.",
            "; no heroics required.",
            ", so nobody has to become a detective.",
            "; the data behaved.",
            ", and the two of them are still on speaking terms.",
            "; no plot twist.",
            ", so the only action item is nothing.",
            "; boring, on purpose.",
            ", which is exactly as thrilling as it should be.",
            "; nobody's afternoon just changed shape.",
            ", so the war room stays a meeting room.",
            "; the numbers are behaving themselves.",
            ", and that's the least dramatic outcome available.",
            ", so there's no list to hand around.",
            "; the exception list stayed empty.",
            ", which nobody will need to follow up.",
            "; a quiet one.",
            ", and the day continues undisturbed.",
            "; no drama, no detective work.",
            ", so nobody's calendar changes.",
            "; a run that behaved itself.",
            ", and there is genuinely nothing else to say.",
            "; that's all of it.",
            ", so nobody needs paging.",
            "; nothing to put in a ticket.",
            ", and the backlog is unchanged.",
            "; no reading between the lines required.",
            ", so today's exception review is going to be short.",
            "; short and uneventful.",
            ", and nothing here will age badly.",
            "; the integration did its job.",
            "; no one has to play referee.",
            ", and that's a full stop, not a comma.",
            ", which frees up the rest of the hour.",
            ", and nobody has to go looking.",
            "; no notes.",
            "; nailed it.",
            "; chef's kiss.",
            ", and we love to see it.",
            "; hard pass on any follow-up.",
            "; big nothing-to-see-here energy.",
            ", TL;DR nothing.",
            "; zero drama, zero notes.",
    ].asImmutable()

    // Time closers carry the time consequence and nothing else. Saying "clean" here would repeat
    // the verdict the headline already delivered, which is what made an earlier draft read as
    // three shuffled synonyms. One sentence each: a second one competes with the first.
    static final List<String> EARLY_MORNING_LINES = [
            "Sorted before the office was even open.",
            "Done before anyone was at a desk.",
            "Finished while the building was still empty.",
            "Beat the first coffee to it.",
            "Nothing waiting when people log on.",
            "Handled before the day started.",
            "The inbox will be quieter than usual.",
            "Overnight work, already done.",
            "Nobody has to start the day with this.",
            "Wrapped before the first standup.",
            "Done while the office was still dark.",
            "The overnight shift outperformed.",
            "Whoever scheduled this at dawn was right.",
            "Finished while the kettle was still cold.",
            "One fewer thing to be awake for.",
            "The early shift did not need you.",
            "Sorted before the traffic started.",
            "Finished before the first meeting invite.",
            "Nothing here will interrupt breakfast.",
            "Already done by the time you read this.",
            "The dawn run went smoothly.",
            "Off the list before the day began.",
    ].asImmutable()

    static final List<String> FRIDAY_AFTERNOON_LINES = [
            "Nothing following you into Friday evening.",
            "Good way to head into the weekend.",
            "Nothing carrying over to Monday.",
            "The weekend starts unencumbered.",
            "No Sunday-night thought about this one.",
            "Friday closes out quiet.",
            "Nothing left open going into the weekend.",
            "That's the week done on a good note.",
            "Nobody's weekend just got interesting.",
            "Shut the laptop.",
            "Nothing to bring back on Monday.",
            "Nothing to ruin a Friday.",
            "The weekend is yours, uncontested.",
            "No Friday-evening surprises left in the tank.",
            "Nothing here will follow you home.",
            "Nothing to think about on Sunday night.",
            "Friday earned its reputation today.",
            "Nothing tailing you out the door.",
            "One less thing between you and the weekend.",
            "A quiet way to finish the week.",
            "Nothing left to hand over before Monday.",
            "The week signs off without incident.",
    ].asImmutable()

    static final List<String> MONDAY_MORNING_LINES = [
            "Nothing waiting for you at the top of the week.",
            "Decent way to start the week.",
            "The week opens with nothing outstanding.",
            "No backlog inherited from the weekend.",
            "Monday starts empty.",
            "Nothing from the weekend needs unpicking.",
            "The week starts one item lighter.",
            "Nothing to triage before the standup.",
            "Good first read of the week.",
            "Monday, and nothing needs chasing.",
            "Monday could have gone worse.",
            "A gentle start, for once.",
            "Nothing to sour the first coffee.",
            "The week is behaving so far.",
            "The week starts on the front foot.",
            "Nothing to spoil a Monday, for once.",
            "First run of the week, and it behaved.",
            "Monday is off to an uneventful start.",
            "Nothing carried over from Friday.",
            "The week begins with a blank slate.",
            "No Monday-morning archaeology required.",
            "A calm opening to the week.",
    ].asImmutable()

    static final List<String> MIDDAY_LINES = [
            "Nothing to interrupt the afternoon.",
            "Back to your lunch.",
            "The afternoon stays yours.",
            "Nothing landing on the afternoon.",
            "Lunch uninterrupted.",
            "Nothing for the afternoon queue.",
            "Midday, and nothing needs attention.",
            "The rest of the day is unaffected.",
            "No afternoon detour.",
            "Carry on with the day.",
            "Eat your lunch in peace.",
            "The afternoon remains unspoiled.",
            "Nothing to chew on but lunch.",
            "No reason to skip the break.",
            "The afternoon is safe.",
            "Nothing to spoil the middle of the day.",
            "Lunch stays lunch.",
            "The day carries on unchanged.",
            "Nothing to think about over coffee.",
            "The afternoon starts with a clear desk.",
            "No midday scramble.",
            "Nothing here needs your afternoon.",
    ].asImmutable()

    static final List<String> LATE_EVENING_LINES = [
            "Nothing to keep anyone up.",
            "Done for the night.",
            "Nobody has to look at this before morning.",
            "Late, and still nothing outstanding.",
            "Nothing waiting in the morning either.",
            "The night run behaved.",
            "Nothing to page anyone about.",
            "Quiet end to the day.",
            "No late-night spreadsheet tonight.",
            "Nothing that needs waking anyone.",
            "Whatever you were dreading tonight, it was not this.",
            "The night shift behaved itself.",
            "Nothing to dream about.",
            "One less reason to check your phone.",
            "The late run finished quietly.",
            "Nothing to hand to the morning shift.",
            "The day ends without incident.",
            "Nothing here justifies a late night.",
            "Everything settled before midnight.",
            "No overnight surprises queued up.",
            "The last run of the day behaved.",
            "Sleep on it, or do not think about it at all.",
    ].asImmutable()

    static final List<String> WEEKEND_LINES = [
            "Nobody's weekend needs interrupting.",
            "The weekend stays a weekend.",
            "Nothing here worth a weekend message.",
            "Weekend run, no weekend work.",
            "Nothing that needs anyone on a day off.",
            "No reason to open a laptop today.",
            "It ran, and nothing came of it.",
            "Nothing rolling into Monday.",
            "The on-call phone stays quiet.",
            "Nothing here needs a weekend.",
            "The weekend remains uninterrupted, as intended.",
            "Barely worth the notification.",
            "The on-call rotation stays theoretical.",
            "Weekend behaviour: exemplary.",
            "Nothing that needs a Saturday or a Sunday.",
            "The weekend carries on without you.",
            "No reason to look at this until Monday.",
            "Nothing here is worth a day off.",
            "The quiet weekend continues.",
            "Nothing to interrupt whatever you are doing.",
            "The weekend remains entirely yours.",
            "Filed under: not urgent.",
    ].asImmutable()

    /**
     * Picks a time-of-day flavour line for an already-zoned moment. The zone must be the tenant's,
     * not the server's — completedDate is a server timestamp, and reading it in the JVM default zone
     * computes "Friday afternoon" five and a half hours out for a team on IST, flipping the day for
     * anything after 18:30.
     *
     * Returning null outside these windows is deliberate. It is also load-bearing: a null here with
     * no streak is what drops the message to a single fused line, so most clean runs are one
     * sentence and the ones that earn a second line read as observed rather than mechanical. The
     * windows are widened only where the observation is genuinely different — a run finishing late
     * at night, or on a day nobody is working — never merely to fire more often.
     *
     * Order is precedence, not coverage. Early morning outranks the weekend because "before anyone
     * was at a desk" is the sharper reading of a 5am Saturday than "it's the weekend" is.
     */
    static String timeOfDayLine(ZonedDateTime moment) {
        if (moment == null) return null
        int hour = moment.hour
        DayOfWeek day = moment.dayOfWeek
        boolean weekend = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY

        if (hour < 7) return pickLine(EARLY_MORNING_LINES, "timeOfDay")
        if (hour >= 21) return pickLine(LATE_EVENING_LINES, "timeOfDay")
        if (weekend) return pickLine(WEEKEND_LINES, "timeOfDay")
        if (day == DayOfWeek.FRIDAY && hour >= 12) return pickLine(FRIDAY_AFTERNOON_LINES, "timeOfDay")
        if (day == DayOfWeek.MONDAY && hour < 12) return pickLine(MONDAY_MORNING_LINES, "timeOfDay")
        if (hour >= 12 && hour < 15) return pickLine(MIDDAY_LINES, "timeOfDay")
        return null
    }

    /**
     * Streak closers open with a connective so they read as continuing the verdict rather than
     * starting a second, unrelated thought. One sentence each — an earlier draft appended a
     * punchline to each ("It's getting cocky"), giving the message two closers competing for the
     * last word.
     */
    static final List<String> STREAK_TEMPLATES = [
            "That's {n} in a row now.",
            "Makes it {n} straight.",
            "{n} in a row, and whatever you changed is holding.",
            "{n} straight, if anyone's counting.",
            "That's {n} on the trot.",
            "{n} in a row now, which is starting to look deliberate.",
            "Number {n} in an unbroken run.",
            "{n} consecutive, no breaks.",
            "That makes {n} without an exception.",
            "{n} in a row, and the pattern is holding.",
            "Run {n} of a streak nobody has interrupted yet.",
            "{n} back to back.",
            "That's {n} runs without anyone stepping in.",
            "{n} straight, and the pipeline hasn't blinked.",
            "{n} in a row and still boring.",
            "The streak stands at {n}.",
            "{n} of these in sequence now.",
            "{n} straight — do not touch anything.",
            "{n} in a row, and it is starting to look smug.",
            "{n} in a row, which is starting to look like process rather than luck.",
            "That's {n}, and nobody wants to jinx it.",
            "{n} straight, and the streak is now load-bearing.",
            "{n} in a row, so whatever the routine is, keep it.",
            "{n} without incident, which is the whole idea.",
            "{n} in a row, and the interesting thing is how uninteresting it is.",
            "{n} in a row, and the graph is a flat line.",
            "That's {n}, and none of them needed a person.",
            "{n} straight, which is either discipline or momentum.",
            "{n} in a row, and the pattern has stopped being news.",
            "{n} on the board now.",
            "That's {n} without a single intervention.",
            "{n} in a row, and the routine is earning its keep.",
            "{n} consecutive, which is a habit at this point.",
            "{n} straight, and nothing has drifted yet.",
            "That's {n}, quietly.",
            "{n} in a row, if you are keeping score.",
            "{n} uninterrupted.",
            "{n} in a row, and the trend line is refusing to be interesting.",
            "{n} straight, and the automation understood the assignment.",
    ].asImmutable()

    /**
     * Used once the streak reaches the lookback cap, where the true length is unknown and only a
     * floor can be stated. This pool exists because a single fixed line here was the worst
     * repetition in the whole message: a tenant whose automation stayed clean printed the identical
     * second line on every run, forever, and the healthier they were the longer it lasted.
     */
    static final List<String> LONG_STREAK_TEMPLATES = [
            "{n} and counting.",
            "{n} in a row, and that's only as far back as this looks.",
            "At least {n} straight now.",
            "{n} deep, which is where the lookback stops.",
            "{n} and still going.",
            "{n} consecutive, minimum.",
            "The last {n} all went the same way.",
            "{n} in a row, and the counter gave up looking further.",
            "{n} straight, and that is only where we stopped counting.",
            "At least {n}, possibly more.",
            "{n} in a row, and the history runs out before the streak does.",
            "{n} deep, floor not ceiling.",
            "{n} consecutive and the lookback gave up first.",
            "{n} straight, with more behind it than this can see.",
    ].asImmutable()

    /** How far back the streak lookback reads. Beyond it only a floor can honestly be stated. */
    static final int LOOKBACK_LIMIT = 20

    /**
     * @param priorCleanRuns consecutive clean runs BEFORE this one. The rendered streak includes the
     *        current run, so 3 prior reads as "4 in a row".
     */
    static String streakLine(int priorCleanRuns) {
        if (priorCleanRuns < 2) return null
        if (priorCleanRuns >= LOOKBACK_LIMIT) {
            return pickLine(LONG_STREAK_TEMPLATES, "streakCapped")
                    ?.replace("{n}", Integer.toString(LOOKBACK_LIMIT))
        }
        String template = pickLine(STREAK_TEMPLATES, "streak")
        return template?.replace("{n}", Integer.toString(priorCleanRuns + 1))
    }

    static List<String> renderLines(Map<String, Object> model) {
        Map<String, Object> safeModel = (model ?: [:]) as Map<String, Object>
        String bucket = ((safeModel.get("bucket"))?.toString()?.trim()) ?: BUCKET_CLEAN
        String runName = ((safeModel.get("runName"))?.toString()?.trim()) ?: "reconciliation run"
        String file1Label = ((safeModel.get("file1SystemLabel"))?.toString()?.trim()) ?: "File 1"
        String file2Label = ((safeModel.get("file2SystemLabel"))?.toString()?.trim()) ?: "File 2"
        int missingFromFile1 = toCount(safeModel.get("missingFromFile1Count"))
        int missingFromFile2 = toCount(safeModel.get("missingFromFile2Count"))
        int mismatches = toCount(safeModel.get("mismatchCount"))
        int total = toCount(safeModel.get("totalCount"))

        List<String> lines = []

        if (bucket == BUCKET_FAILED || bucket == BUCKET_ISSUES) {
            // Humour is off from here up, and so is variation: these two lines are the ones a Chat
            // filter or an on-call runbook is most likely to key on, and a rotating corpus would
            // make bad news harder to match than good news. The XML service adds the status and
            // warning lines.
            lines << (bucket == BUCKET_FAILED
                    ? "${runName} did not finish.".toString()
                    : "${runName} finished, but not cleanly.".toString())
            // The FLAVOUR is switched off above ISSUES — the NUMBERS are not. A run that failed
            // partway still produced a partial result, and those counts are the operator's only
            // handle on what did get compared. Dropping them would trade one silent alert
            // (the all-zeros clean-looking run this whole change exists to fix) for another.
            // A failure that produced no output at all never reaches here: the service's
            // noOutput branch renders it without counts, because zeros there mean "never computed".
            lines.addAll(detailsBlock(missingFromFile1, missingFromFile2, mismatches, file1Label, file2Label))
            return lines
        }

        if (bucket == BUCKET_CLEAN) return renderClean(runName, safeModel)

        lines << headlineFor(bucket, runName, total, missingFromFile2, mismatches, file1Label, file2Label)
        lines << diagnosisFor(bucket, mismatches)
        lines.addAll(detailsBlock(missingFromFile1, missingFromFile2, mismatches, file1Label, file2Label))
        return lines
    }

    /**
     * A clean run renders as a verdict plus AT MOST ONE closer.
     *
     * An earlier shape drew a headline, a subline and a time line from three independent pools and
     * printed all of them (plus a streak, so a clean run on a streak stacked four lines). Every pool
     * asserted the same proposition, so lines two onward carried no information line one had not
     * already spent, and each arrived with its own subject and its own punchline. Independent random
     * draws across synonymous pools do not produce variety; they produce noise.
     *
     * Closers are ranked by information value. A streak is a fact the operator does not already
     * have. A time-of-day hook is context. With neither there is nothing worth a second line, so the
     * tail fuses into the verdict and the whole message is one sentence — that length change is the
     * point, because a message whose shape varies with what is actually true reads as written rather
     * than assembled.
     */
    private static List<String> renderClean(String runName, Map<String, Object> model) {
        String verdict = "${runName} — ${pickLine(CLEAN_HEADLINES, "headline")}".toString()

        String streak = streakLine(toCount(model.get("priorCleanRuns")))
        if (streak) return [verdict + ".", streak]

        String timeLine = timeOfDayLine((ZonedDateTime) model.get("completedMoment"))
        if (timeLine) return [verdict + ".", timeLine]

        String tail = pickLine(CLEAN_TAILS, "tail")
        return [tail ? (verdict + tail) : (verdict + ".")]
    }

    /**
     * The count block. Every line is printed even at zero: an omitted line cannot be told apart from
     * an axis that was never checked, which is the ambiguity this whole change exists to remove.
     * Single-asterisk bold — Google Chat renders `*Details*`; `**Details**` shows the asterisks.
     */
    private static List<String> detailsBlock(int missingFromFile1, int missingFromFile2,
                                             int mismatches, String file1Label, String file2Label) {
        return [
                "",
                "*Details*",
                // Inversion: missingFromFile2Count is the count of records only file 1 had.
                "Missing from ${file2Label}: ${missingFromFile2}".toString(),
                "Missing from ${file1Label}: ${missingFromFile1}".toString(),
                "Mismatches: ${mismatches}".toString(),
        ]
    }

    /**
     * Scale and shape in one line, so a reader knows what KIND of run this is before reading further.
     * An earlier headline was a bare total for every bucket, which deferred the whole diagnosis to
     * the line below and left four very different runs looking identical at a glance.
     *
     * The COUNT and the SHAPE are fixed content — they are what an operator scans — but the frame
     * around them is drawn from a pool. It was a single hardcoded string for three rounds, which
     * made this the most repetitive surface left in the whole message: a clean run at least varies,
     * whereas a tenant working through a recurring one-sided gap saw one byte-identical headline
     * every day for as long as the problem lasted, with only the diagnosis line beneath it moving.
     * The frames are number-agnostic on purpose ("{n} flagged", never "{n} differences") so a run
     * with exactly one difference does not render as "1 differences".
     *
     * MIXED is a catch-all over two genuinely different shapes — both sides missing but lopsided, and
     * missing plus value mismatches — so it picks its shape phrase from the mismatch count rather
     * than describing the second and mis-describing the first.
     */
    private static String headlineFor(String bucket, String runName, int total, int missingFromFile2,
                                      int mismatches, String file1Label, String file2Label) {
        String shape
        if (bucket == BUCKET_ONE_SIDED) {
            // missingFromFile2 > 0 means file 1 held them, so file 2's system is the one short.
            shape = "all missing from ${missingFromFile2 > 0 ? file2Label : file1Label}".toString()
        } else if (bucket == BUCKET_EVEN_SPLIT) {
            shape = "near-evenly split"
        } else if (bucket == BUCKET_VALUE_DRIFT) {
            shape = "all value mismatches"
        } else {
            shape = mismatches > 0 ? "missing and mismatched" : "missing on both sides"
        }
        String frame = (pickLine(SHAPE_FRAMES, "shapeFrame") ?: "{n} to look at")
                .replace("{n}", Integer.toString(total))
        return "${runName} — ${frame}, ${shape}.".toString()
    }

    /**
     * What the shape implies, and nothing else.
     *
     * These lines carry no numbers on purpose. An earlier draft re-narrated the very counts printed
     * three lines below them — "39 went out and never checked in" sitting above
     * "Missing from Shopify: 39" — so the prose and the Details block said the same thing twice, in
     * two registers. The block owns the arithmetic; this line owns the reading of it.
     *
     * One sentence each, and each refers back to the headline's shape so the two lines read as one
     * thought rather than two stacked fragments. Pooled per bucket rather than fixed: a run with
     * differences is not the run an operator sees most, but a tenant working through a recurring
     * one-sided gap sees the same bucket every day, and one frozen sentence stops being read.
     */
    private static String diagnosisFor(String bucket, int mismatches) {
        if (bucket == BUCKET_EVEN_SPLIT) return pickLine(EVEN_SPLIT_DIAGNOSES, "diagnosisEvenSplit")
        if (bucket == BUCKET_ONE_SIDED) return pickLine(ONE_SIDED_DIAGNOSES, "diagnosisOneSided")
        if (bucket == BUCKET_VALUE_DRIFT) return pickLine(VALUE_DRIFT_DIAGNOSES, "diagnosisValueDrift")
        if (mismatches > 0) return pickLine(MIXED_MISMATCH_DIAGNOSES, "diagnosisMixedMismatch")
        return pickLine(MIXED_MISSING_DIAGNOSES, "diagnosisMixedMissing")
    }

    /**
     * How the headline frames its count. Every entry must read correctly at 1 and at 47, so no
     * frame may carry a plural noun or a conjugated verb — "{n} differences" and "{n} need a look"
     * both break on a single-difference run, which is exactly the run an operator is most likely
     * to be reading closely.
     */
    static final List<String> SHAPE_FRAMES = [
            "{n} to look at",
            "{n} to work through",
            "{n} on the list",
            "{n} flagged",
            "{n} to reconcile",
            "{n} outstanding",
            "{n} to chase down",
            "{n} to account for",
            "{n} needing attention",
            "{n} to sort out",
            "{n} for the exceptions queue",
            "{n} to get through",
    ].asImmutable()

    static final List<String> ONE_SIDED_DIAGNOSES = [
            "That shape usually points at delivery rather than matching.",
            "One side short and the other whole usually means a feed, not a rule.",
            "When it all runs one direction, look at what did not arrive before you look at how it matched.",
            "A one-way gap is usually an ingestion story.",
            "Nothing came back from the other direction, which narrows it to delivery.",
            "One-sided gaps rarely start in the comparison itself.",
            "A gap that runs one way is almost always about what arrived, not about how it matched.",
            "Check the extract on the short side before touching the rules.",
            "The comparison worked; one of the feeds did not deliver everything.",
            "Whole on one side and short on the other is a transport symptom.",
            "Start with the window and the filter on the side that came up short.",
            "This reads like an upstream gap rather than a matching failure.",
    ].asImmutable()

    static final List<String> EVEN_SPLIT_DIAGNOSES = [
            "Two sides short by nearly the same amount rarely means two separate outages — check the join key before chasing records.",
            "Near-equal gaps on both sides is the signature of a key that is not matching, not of records that went missing.",
            "Both sides missing about the same amount usually means the same records failing to find each other.",
            "Symmetry like that points at the join, not at the data.",
            "When both sides are short by the same amount, suspect the key before the feed.",
            "That balance is the tell — the records are probably there, under a key that does not line up.",
            "Matched counts on both sides usually means the records exist and the key does not find them.",
            "Look at the key format on both sides before you open a single record.",
            "Two mirrored gaps is one problem wearing two hats.",
            "This is the shape a changed identifier makes.",
            "Before chasing either side, confirm the two keys are built the same way.",
            "Equal and opposite gaps rarely have equal and opposite causes.",
    ].asImmutable()

    static final List<String> VALUE_DRIFT_DIAGNOSES = [
            "Both sides hold every record; they just don't line up on what's inside them.",
            "Nothing is missing anywhere; the difference is entirely inside the records.",
            "Every record arrived on both sides, but the contents drifted.",
            "This is a values problem, not a delivery problem.",
            "The records found each other and then failed to match on the fields.",
            "Both sides are complete; the fields are where they part ways.",
            "Presence is fine everywhere; it is the contents that have moved apart.",
            "Look at the comparison rules and the field mapping, not at the extracts.",
            "Delivery worked perfectly; the fields are what parted company.",
            "Rounding, formatting and timezone are the usual three suspects here.",
            "The pairing is correct, so the question is which field moved.",
            "Nothing needs re-fetching; something needs re-checking.",
    ].asImmutable()

    static final List<String> MIXED_MISMATCH_DIAGNOSES = [
            "Those are usually two different causes — worth splitting before you start.",
            "Missing records and drifted values rarely share a root cause, so treat them as two lists.",
            "Two problems in one run — separate them before you start pulling threads.",
            "Splitting the missing from the mismatched will usually halve the work.",
            "One of these is a delivery question and the other is a field question.",
            "Work them separately; they almost never resolve together.",
            "Two failure modes in one run, and they almost never share a fix.",
            "Take the missing records first; the mismatches will be easier afterwards.",
            "One list is about delivery and the other is about fields, so split them now.",
            "Treating these as one pile is how a half-hour becomes an afternoon.",
            "The two counts below are separate investigations.",
            "Fix the gaps first, then see how many mismatches survive.",
    ].asImmutable()

    static final List<String> MIXED_MISSING_DIAGNOSES = [
            "Both directions, but lopsided, which is likelier to be two separate gaps than one.",
            "Uneven gaps on both sides usually means two causes rather than one shared key problem.",
            "Both sides are short, but not equally, so a single key fault is unlikely.",
            "The imbalance argues against one common cause.",
            "Two directions and two different sizes usually means two different stories.",
            "Lopsided both ways rarely reduces to a single explanation.",
            "Both sides short by different amounts points at two independent gaps.",
            "If it were the key, the two numbers would be much closer together.",
            "Unequal gaps in both directions usually means two feeds, two problems.",
            "The asymmetry is the useful part here.",
            "Check each direction separately; they are unlikely to resolve together.",
            "Two different sizes usually means two different windows.",
    ].asImmutable()
}
