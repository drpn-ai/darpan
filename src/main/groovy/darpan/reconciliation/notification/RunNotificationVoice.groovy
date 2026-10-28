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
     * The verdict: a whole-sentence template with the run's name at {run}. This is the ONE place a clean
     * run asserts that it is clean; every other pool adds a different dimension. Entries carry no
     * terminator: the plain tier fuses a tail clause straight onto them, and the renderer supplies the
     * full stop otherwise.
     *
     * DAR-BE-121. The name moves. When every entry was "{run} — <clause>", a deep pool of synonyms
     * still read as one message reworded, around twenty times a day; the sentence's shape is what a
     * reader recognises, so the shape varies (name first, name then a colon, name last, a label then
     * the name) and the old dashed form is one shape among several.
     *
     * Wit is welcome here, but every entry still has to state the fact plainly enough to stand alone:
     * this is the only place the message says the run was clean, so an entry that is funny without
     * being clear costs the reader the one thing they came for.
     */
    static final List<String> CLEAN_HEADLINES = [
            "{run} \u2014 every record lined up",
            "{run} \u2014 no gaps, no mismatches",
            "{run} \u2014 both systems came back with the same story",
            "{run} \u2014 not one record off",
            "{run} \u2014 every row found its pair",
            "{run} \u2014 zero on all three axes",
            "{run} \u2014 nothing fell through",
            "{run} \u2014 matched end to end",
            "{run} \u2014 no records adrift",
            "{run} \u2014 both sides in step, record for record",
            "{run} \u2014 the comparison came back empty",
            "{run} \u2014 spotless on both sides",
            "{run} \u2014 matched so completely it's almost suspicious",
            "{run} \u2014 zero differences, zero excitement",
            "{run} \u2014 a deeply unremarkable match",
            "{run} \u2014 a textbook match",
            "{run} \u2014 not a sliver of daylight",
            "{run} \u2014 the two sides are a carbon copy",
            "{run} \u2014 both sides brought receipts and they match",
            "{run} \u2014 the pipeline understood the assignment",
            "{run} came back clean",
            "{run} lined up end to end",
            "{run} found nothing to fix",
            "{run} matched every record on both sides",
            "{run} turned up zero differences",
            "{run} ran clean",
            "{run} checked out on both sides",
            "{run} closed with no gaps at all",
            "{run} balanced to the record",
            "{run} found every record where it should be",
            "{run} came through without a single difference",
            "{run} reconciled with nothing left over",
            "{run} matched record for record",
            "{run} produced an empty list of differences",
            "{run} is fully reconciled",
            "{run} gets a clean bill of health",
            "{run} is all square",
            "{run} has nothing waiting for anyone",
            "{run} finished with nothing missing and nothing mismatched",
            "{run} came back without a single gap",
            "{run}: zero missing, zero mismatched",
            "{run}: no differences",
            "{run}: nothing to chase",
            "{run}: both sides line up",
            "{run}: every record accounted for",
            "{run}: all clear",
            "{run}: a full match",
            "{run}: nothing out of place",
            "Nothing to chase in {run}",
            "No differences in {run}",
            "Every record lined up in {run}",
            "Clean pass from {run}",
            "All square in {run}",
            "Zero gaps in {run}",
            "Not one difference in {run}",
            "Good news from {run}: every record lined up",
            "Both sides line up in {run}",
            "Nothing missing and nothing mismatched in {run}",
            "A clean sheet for {run}",
            "Clean finish for {run}",
            "All present and correct in {run}",
            "No loose ends in {run}",
            "Every record accounted for in {run}",
            "Full match in {run}",
            "Zero differences from {run}",
            "Nothing out of place in {run}",
            "Not a single gap or mismatch in {run}",
            "Everything reconciled in {run}",
            "A perfect match on {run}",
            "A tidy one from {run}: nothing to fix",
            "No surprises in {run}: every record lined up",
            "Both systems tell the same story in {run}",
            "The two sides of {run} line up to the last row",
            "Every row in {run} found its pair",
            "All records present and matching in {run}",
            "Clean bill of health for {run}",
            "All clear on {run}",
            "Nothing slipped through in {run}",
            "Every record made it across in {run}",
            "Not a thing to fix in {run}",
            "There is nothing to look at in {run}",
            "Not one record out of line in {run}",
            "Spotless finish from {run}",
            "Nothing adrift in {run}",
            "No gaps and no drift in {run}",
            "Every value lined up in {run}",
            "Straight match across the board in {run}",
            "Both sides of {run} came back identical",
            "Every last row of {run} lined up",
            "The whole of {run} matched",
            "Your {run} came back clean",
            "Quiet one from {run}: every record lined up",
            "Not much to say about {run}: it matched completely",
            "Not a lot to add on {run}: every record lined up",
            "Fully reconciled: {run}",
            "All clear: {run} found nothing missing or mismatched",
            "Clean run: {run}",
            "Matched in full: {run}",
            "Bottom line on {run}: no differences",
            "Verdict on {run}: everything lined up",
            "Done and clean: {run}",
            "Nothing to do: {run} matched completely",
            "Zero to chase: {run} lined up",
            "One-line update: {run} matched every record",
            "Short version: {run} came back clean",
            "Status of {run}: fully lined up",
            "{run} matched every record it was given",
            "{run} found every pair in one piece",
            "{run} lined up row for row",
            "{run} came up empty on differences",
            "{run} reconciled with zero gaps and zero mismatches",
            "{run} turned up no missing records and no mismatched values",
            "{run} came back a perfect match",
            "{run} has zero differences to show",
            "{run} squared up completely",
            "{run} matched even the fussy fields",
            "{run} lined up with nothing missing or mismatched",
            "{run} found both sides in full step",
            "{run} had no gaps or mismatches on either side",
            "{run} matched on every count",
            "{run} came through with every record paired",
            "{run} paired off every last record",
            "{run} matched one for one",
            "{run} left no record unmatched",
            "{run} closed out a full match",
            "{run} found nothing missing or mismatched anywhere",
            "{run} matched all the way down",
            "{run} lined up like it was rehearsed",
            "{run} \u2014 not a cell out of step",
            "{run} \u2014 a full set, fully matched",
            "{run} \u2014 matched to the last decimal",
            "{run} \u2014 both sides tally to the unit",
            "{run} \u2014 the data lined up without a fuss",
            "{run} \u2014 every row came home in pairs",
            "{run} \u2014 every record had a partner",
            "{run} \u2014 a match from first row to last",
            "{run}: no record without a match",
            "{run}: no gaps or mismatches found",
            "{run}: one hundred percent matched",
            "{run}: two ledgers, zero gaps",
            "{run}: the sides line up perfectly",
            "{run}: no mismatches and no missing records",
            "{run}: every field in every row lined up",
            "{run}: fully paired up",
            "{run}: zero unreconciled items",
            "{run}: every count is zero",
            "{run}: no daylight on any record",
            "Not one record off in {run}",
            "Matches all round in {run}, with no gaps",
            "No gaps or mismatches turned up in {run}",
            "Zero missing records and zero mismatches in {run}",
            "Pair for pair, {run} lined up completely",
            "A full match across {run}",
            "The tallies match in {run}",
            "Every pair complete in {run}",
            "No record left unpaired in {run}",
            "The gap count in {run} is zero",
            "Matched to the final row in {run}",
            "Complete match on both sides of {run}",
            "Not a missing record or a mismatched value in {run}",
            "All records paired up in {run}",
            "Nothing missing or mismatched on either side of {run}",
            "A full house of matches in {run}",
            "Everything in {run} lined up with its twin",
            "Gaps found in {run}: none",
            "Mismatches in {run}: zero",
            "Both systems line up completely in {run}",
            "In {run}, both sides line up to the letter",
            "In {run}, no record went missing and no value drifted",
            "Across {run}, everything matched",
            "For {run}, nothing came up missing or mismatched",
            "On {run}, both sides came out even",
            "From {run}, a complete match",
            "Gap-free and mismatch-free: {run}",
            "The data in {run} lined up neatly",
            "Each record in {run} met its match",
            "Each side of {run} held the same records",
            "Same records, same values, both sides of {run}",
            "Two sides, one story, zero gaps in {run}",
            "A match with no footnotes in {run}",
            "No asterisks on {run}: it matched completely",
            "Nothing to reconcile in {run}: it already matched",
            "Zero unmatched rows in {run}",
            "Values held steady and nothing went missing in {run}",
            "All records lined up in {run}, no stragglers",
            "Not one stray record in {run}",
            "Everything matched up in {run}, top to bottom",
            "A perfect pairing for {run}",
            "Every record showed up on both sides of {run}",
            "No orphans in {run}: every record has a pair",
            "Zero gaps, zero drift, zero mismatches in {run}",
            "Zero gaps or mismatches, start to finish: {run}",
            "No gaps, no mismatches: {run}",
            "Lined up completely: {run}",
            "Everything lined up: {run}",
            "All paired: {run}",
            "Clean sweep, no gaps: {run}",
            "Glad tidings from {run}: zero gaps, zero mismatches",
            "Scoreboard for {run}: zero gaps, zero mismatches",
            "Final tally for {run}: every record matched",
            "The word on {run}: lined up",
            "All calm on {run}: not a gap or mismatch in sight",
            "Gap check for {run}: no gaps, no mismatches",
            "Even stevens: {run} lined up in full",
            "Every record present: {run}",
            "Match confirmed: {run}",
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
            ", so the inbox can stay as it is.",
            "; nothing joins the to-do list.",
            ", and the coffee can stay warm.",
            ", and no report needs pulling.",
            ", so the issue tracker gets a rest.",
            "; the data did exactly what it said it would.",
            ", and there's no fire to put out.",
            "; no one has to reopen anything.",
            ", and the next step is no step.",
            "; nothing to untangle.",
            ", so no one has to compose a reply.",
            "; the rows simply got along.",
            ", and the data kept its side of the deal.",
            "; nothing is hiding in the details.",
            ", so no one goes tracing anything.",
            "; the figures speak for themselves.",
            ", and the data stays pleasantly dull.",
            "; nobody needs a mop.",
            ", so the checklist has nothing new on it.",
            "; a non-event, in the best way.",
            ", which leaves everyone's plans intact.",
            "; the data kept to the script.",
            ", and there's no fine print.",
            "; nothing to flag.",
            ", and there's nothing to sign off on.",
            "; no caveats attached.",
            ", so the data can take a bow.",
            "; the kind of news that fits in one line.",
            ", and no one has to ping a vendor.",
            "; the data needs no chaperone.",
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
            "Finished before sunrise.",
            "Done before the alarms went off.",
            "Wrapped up in the small hours.",
            "The night owls and the early birds can both stand down.",
            "Done at an hour when even the servers are yawning.",
            "Done before the commute.",
            "Completed before the first email of the day.",
            "The morning starts with one less thing on the list.",
            "Finished long before the working day had any say in it.",
            "Done before the first train.",
            "The result was in before anyone thought to ask.",
            "A pre-dawn result, ready for the first read of the day.",
            "Ran while everyone was asleep.",
            "Settled in the quiet hours.",
            "The early start paid off without anyone having to make one.",
            "The morning shift inherits nothing from this.",
            "Squared away before daybreak.",
            "One less thing for the morning handover.",
            "Done in the dark, so the daylight is free.",
            "The first look of the day can skip this one.",
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
            "Friday stays light.",
            "Nothing to add to the end-of-week list.",
            "The weekly wrap-up just got shorter.",
            "Friday afternoon, and the week is closing tidily.",
            "Nothing to flag in the Friday handover.",
            "The last stretch of the week stays quiet.",
            "The out-of-office can go on without a caveat.",
            "The weekend can start on time.",
            "Friday afternoon carries on as planned.",
            "Nothing to squeeze in before the week ends.",
            "Leave on time today.",
            "No last-minute Friday scramble.",
            "Nothing for the weekend on-call to inherit.",
            "Friday gets to stay Friday.",
            "Nothing standing between the team and an early finish.",
            "Nothing that needs a Friday-afternoon decision.",
            "The Friday status update writes itself.",
            "Friday wins this one.",
            "Good news to take into Saturday.",
            "The week's books close without an asterisk.",
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
            "The first standup of the week has one less item.",
            "Monday's inbox is a little lighter.",
            "A quiet start before the week gets busy.",
            "Monday morning, and one thing is already ticked off.",
            "The weekly planning can skip this one.",
            "A soft landing into the week.",
            "Monday gets a head start.",
            "The week begins without a catch-up list.",
            "Monday's to-do list stays as it was.",
            "The first hours of the week stay free for other things.",
            "Monday is easier than advertised.",
            "The start-of-week review can be brief.",
            "Nothing for the Monday sync to dwell on.",
            "A good omen for the week, if you believe in those.",
            "Monday morning, and the week already has some momentum.",
            "The week's priorities stay where you left them.",
            "Plenty of Monday left for everything else.",
            "A Monday that asks nothing of you.",
            "The week is getting off lightly.",
            "The coffee can be enjoyed rather than gulped.",
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
            "Halfway through the day with nothing added to it.",
            "The second half of the day starts on time.",
            "Nothing to bring to the post-lunch standup.",
            "The sandwich can stay the priority.",
            "No reason to eat at your desk.",
            "Nothing to log before the afternoon meetings.",
            "The afternoon agenda holds.",
            "Nothing to rework after lunch.",
            "The lunch break gets to be a break.",
            "Nothing to take into the two o'clock.",
            "Plenty of daylight left for other work.",
            "The post-lunch slump gets no extra help from this.",
            "Something easy to read between bites.",
            "Noon came and went without anything to chase.",
            "The afternoon can go to the work that was already planned.",
            "Nothing to reshuffle the afternoon around.",
            "The midpoint of the day passes quietly.",
            "Nothing to prep for the afternoon sync.",
            "The rest of the working day keeps its shape.",
            "Lunch can run long, if it wants to.",
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
            "The evening stays off the clock.",
            "Phones can go face down tonight.",
            "Wrapped up as the day wound down.",
            "Tomorrow starts without this on it.",
            "No reason to keep the laptop open.",
            "The night is quiet on this front.",
            "Done before the lights went out.",
            "The overnight on-call has one less thing to watch.",
            "A late finish that asks nothing of anyone.",
            "Nothing to read again in the morning.",
            "Close the lid and call it a night.",
            "No midnight follow-up required.",
            "Nothing to keep the screen lit past bedtime.",
            "Nothing worth a second glance before bed.",
            "The night can stay uneventful.",
            "The evening wind-down continues as planned.",
            "Nothing to text anyone about tonight.",
            "Bedtime is unaffected.",
            "A late run, with nothing to say about it.",
            "The evening carries on undisturbed.",
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
            "Weekend plans can go ahead as they were.",
            "The weekend run kept to itself.",
            "Monday can deal with whatever comes next.",
            "No reason to check in from the sofa.",
            "Enjoy the rest of the weekend.",
            "Brunch is unaffected.",
            "Nothing worth leaving the garden for.",
            "The weekend can carry on at its own pace.",
            "Ran on a day off and stayed out of the way.",
            "Sunday-evening dread gets nothing from this.",
            "No weekend detour required.",
            "Nothing to interrupt the lie-in.",
            "Weekday reliability, weekend hours.",
            "The phone can go back in the pocket.",
            "The weekend schedule holds.",
            "Ran while the office was closed, and nothing needs it open.",
            "The errands can come first today.",
            "The weekend has better things to do than this.",
            "Nothing here to bother anyone with before the week starts.",
            "The weekend backlog stays empty.",
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
        List<Map> mismatchFields = mismatchTallies(safeModel.get("mismatchFields"))

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
            lines.addAll(detailsBlock(missingFromFile1, missingFromFile2, mismatches, file1Label, file2Label, mismatchFields))
            return lines
        }

        if (bucket == BUCKET_CLEAN) return renderClean(runName, safeModel)

        lines << headlineFor(bucket, runName, total, missingFromFile2, mismatches, file1Label, file2Label)
        lines << ((bucket == BUCKET_VALUE_DRIFT && mismatchFields) ?
                valueDriftFact(mismatchFields, file1Label, file2Label) : diagnosisFor(bucket, mismatches))
        lines.addAll(detailsBlock(missingFromFile1, missingFromFile2, mismatches, file1Label, file2Label, mismatchFields))
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
        String verdict = (pickLine(CLEAN_HEADLINES, "headline") ?: "{run} matched every record").replace("{run}", runName)

        // DAR-BE-108: a badge or side quest this run earned takes the closer's place; it already says the streak.
        List<String> earned = [model.get("badgeLine"), model.get("questLine")].findAll { it } as List<String>
        // DAR-BE-138: the celebration's astronomy fact closes it.
        if (earned) return [verdict + "."] + earned + ([model.get("factLine")].findAll { it } as List<String>)

        // DAR-BE-144: the stored streak is the only streak, in days and with no ceiling.
        String streak = StreakMilestoneVoice.streakLine(toCount(model.get("storedStreakDays")))
        if (streak) return [verdict + ".", streak]

        String timeLine = timeOfDayLine((ZonedDateTime) model.get("completedMoment"))
        if (timeLine) return [verdict + ".", timeLine]

        String tail = pickLine(CLEAN_TAILS, "tail")
        return [tail ? (verdict + tail) : (verdict + ".")]
    }

    /**
     * The count block. Both missing lines print even at zero: each names a system, and its zero says
     * that side came back complete. The mismatch line prints only when there are mismatches
     * (DAR-BE-121) — at zero it names nothing and is just noise under a missing-records headline.
     * Single-asterisk bold — Google Chat renders `*Details*`; `**Details**` shows the asterisks.
     *
     * DAR-BE-155: the mismatch line names the fields that drifted (busiest three, then "+N more"), so the
     * reader is not left to guess from a diagnosis line that cannot know which field moved.
     */
    private static List<String> detailsBlock(int missingFromFile1, int missingFromFile2,
                                             int mismatches, String file1Label, String file2Label,
                                             List<Map> mismatchFields) {
        List<String> lines = [
                "",
                "*Details*",
                // Inversion: missingFromFile2Count is the count of records only file 1 had.
                "Missing from ${file2Label}: ${missingFromFile2}".toString(),
                "Missing from ${file1Label}: ${missingFromFile1}".toString(),
        ]
        if (mismatches > 0) lines.add("Mismatches: ${mismatches}${mismatchFieldsSuffix(mismatchFields)}".toString())
        return lines
    }

    private static String mismatchFieldsSuffix(List<Map> fields) {
        if (!fields) return ""
        if (fields.size() == 1) return " (${fields.first().field})".toString()
        List<String> named = fields.take(3).collect { "${it.field}: ${it.count}".toString() }
        if (fields.size() > 3) named.add("+${fields.size() - 3} more".toString())
        return " (${named.join(', ')})".toString()
    }

    /** RuleDiffSupport.fieldTally rows the run sent, busiest first; empty when the run did not say. */
    private static List<Map> mismatchTallies(Object raw) {
        if (!(raw instanceof List)) return []
        return ((List) raw).findAll { it instanceof Map && ((Map) it).field?.toString()?.trim() && toCount(((Map) it).count) > 0 }
                // Chat link and format syntax is stripped from the rule's label (security review on bb64b03).
                .collect { Map it -> [field: it.field.toString().replaceAll(/[<>|*~`]/, "").trim(), count: toCount(it.count), file1Value: it.file1Value,
                                      file2Value: it.file2Value, sameValues: it.sameValues == true] }
                .sort { -((int) it.count) }
    }

    /**
     * DAR-BE-155: when the run says which fields drifted, the diagnosis is that fact, not a pooled reading.
     * RAILS read "Partial refunds ... amount drift" over a status mismatch (isCompleted = isFulfilled, Y against N)
     * because the pool could not know the field. The values are named only when every mismatch on the field agrees,
     * and only when both are flags, codes or numbers: a record value goes to a chat space, so an email, a name or
     * chat markup is never spoken (security review on bb64b03); the line then says the two sides differ.
     */
    private static String valueDriftFact(List<Map> fields, String file1Label, String file2Label) {
        Map top = fields.first()
        String value1 = spoken(top.file1Value)
        String value2 = spoken(top.file2Value)
        String values = top.sameValues && value1 && value2 ?
                "${file1Label} has ${value1} and ${file2Label} has ${value2}".toString() : null
        String unnamed = top.sameValues ? "${file1Label} and ${file2Label} hold different values".toString() :
                "the values differ from record to record"
        if (fields.size() == 1) {
            if (!values) return "On ${top.field}, ${unnamed}.".toString()
            return "On ${top.field}, ${values}${((int) top.count) > 1 ? ' every time' : ''}.".toString()
        }
        int total = (int) fields.sum { it.count }
        String lead = ((int) top.count) * 2 > total ? "Most are on ${top.field}" : "The largest share is on ${top.field}"
        return values ? "${lead}: ${values}.".toString() : "${lead}, where ${unnamed}.".toString()
    }

    private static final java.util.regex.Pattern SPEAKABLE_VALUE =
            ~/^(?:[A-Z0-9_]{1,32}|[YyNn]|true|false|-?\d{1,15}(?:\.\d{1,6})?)$/

    /** A blank reads "nothing"; a flag, code or number reads as itself; anything else is not spoken (null). */
    private static String spoken(Object value) {
        String text = value?.toString()?.trim()
        if (!text) return "nothing"
        return SPEAKABLE_VALUE.matcher(text).matches() ? text : null
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
            "{n} to review",
            "{n} to trace",
            "{n} to run to ground",
            "{n} to settle",
            "{n} to clear up",
            "{n} to follow up on",
            "{n} to dig into",
            "{n} to investigate",
            "{n} to close out",
            "{n} to pick through",
            "{n} to untangle",
            "{n} to put right",
            "{n} to straighten out",
            "{n} to tidy up",
            "{n} to examine",
            "{n} to verify",
            "{n} to inspect",
            "{n} to triage",
            "{n} to iron out",
            "{n} to fix",
            "{n} to explain",
            "{n} to go over",
            "{n} to bring into line",
            "{n} to get to the bottom of",
            "{n} to comb through",
            "{n} to sift through",
            "{n} to pin down",
            "{n} to unpick",
            "{n} to make sense of",
            "{n} to see to",
            "{n} to deal with",
            "{n} to handle",
            "{n} to tackle",
            "{n} to wrap up",
            "{n} to cross-check",
            "{n} to walk through",
            "{n} to puzzle out",
            "{n} to button up",
            "{n} to put to bed",
            "{n} to get across the line",
            "{n} to field",
            "{n} to action",
            "{n} to reckon with",
            "{n} to break down",
            "{n} to diagnose",
            "{n} to tease apart",
            "{n} open",
            "{n} unresolved",
            "{n} pending",
            "{n} awaiting a look",
            "{n} in question",
            "{n} out of step",
            "{n} out of balance",
            "{n} adrift",
            "{n} not lining up",
            "{n} short of a match",
            "{n} on the slate",
            "{n} in the queue",
            "{n} in the inbox",
            "{n} on the board",
            "{n} on the radar",
            "{n} on the table",
            "{n} on deck",
            "{n} up next",
            "{n} in the frame",
            "{n} in the net",
            "{n} in play",
            "{n} raised",
            "{n} caught",
            "{n} surfaced",
            "{n} turned up",
            "{n} spotted",
            "{n} logged",
            "{n} picked up by the check",
            "{n} kicked out by the comparison",
            "{n} parked for review",
            "{n} worth a look",
            "{n} with questions attached",
            "{n} for a second pair of eyes",
            "{n} carrying a flag",
            "{n} in total",
            "a tally of {n}",
            "open count at {n}",
            "{n} unexplained",
            "{n} left over",
            "{n} at last count",
            "{n} as of this run",
            "{n} from this pass",
            "{n} landed in exceptions",
            "{n} under the microscope",
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
            "A sync that is running behind produces exactly this pattern, so check when the short side last received a batch.",
            "Look for a status or channel filter on the short side that quietly drops some of what the other side sends.",
            "If the gaps cluster near the end of the window, the records may simply not have crossed over yet.",
            "A webhook that stopped firing leaves this kind of trail, with older records present and newer ones absent.",
            "The extract on the short side may have stopped paging early, which leaves the tail of the data behind.",
            "Check whether the short side excludes test, draft or cancelled records that the other side still carries.",
            "A credential or scope change on the short side can cut off part of a feed without raising any error.",
            "When one system holds records the other never received, the integration log is a better first stop than the rule set.",
            "Records created just before the cutoff often land on one side a few minutes after the extract has already run.",
            "A store, location or sales channel added recently may not be mapped on the short side yet.",
            "If a job on the short side failed and retried, the retry may not have picked up everything the first attempt missed.",
            "A narrower date window on the short side would produce this shape with nothing actually lost.",
            "Worth checking whether the missing records share a type, a source or a date, since that usually names the cause.",
            "One-directional gaps tend to trace back to a single stage in the pipeline, which makes them easier to pin down than they look.",
            "The fuller side is the reference here; the question is what stopped the rest from reaching the other one.",
            "If the same records show up again on the next run, it is a standing filter rather than a delay.",
            "Records that never left the source system often sit in a pending or held state that the export skips.",
            "A changed endpoint or file location can leave one side reading only part of the set.",
            "Throttling or rate limits on the short side can cut an extract short without failing the run outright.",
            "If the gap appeared suddenly, look at what changed in the integration around that time.",
            "Archived or purged records on the short side can make history look incomplete when nothing went wrong in transit.",
            "Records from a channel the short side does not ingest will keep landing here until it is mapped.",
            "A queue backlog on the receiving side would explain this, and that kind of backlog usually drains on its own.",
            "Check the error or dead-letter queue on the receiving side, since rejected records often end up there.",
            "If a key were wrong, the other side would show gaps too, so this points at delivery.",
            "A validation step on the receiving side may be rejecting these records before they are stored.",
            "Timezone handling in the short side's date filter can shave records off one end of the window.",
            "If the short side imports on a schedule, compare that schedule with when this run started.",
            "Partial imports usually stop at a point in time, so sort the missing records by date and look for the edge.",
            "Records merged or deleted on the short side after they synced will also surface here.",
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
            "A key that gained a prefix, a suffix or leading zeros on one side would leave the same records unpaired in both directions.",
            "Compare a handful of keys from each side by eye; a formatting difference usually jumps out quickly.",
            "Case or whitespace differences in the key are enough to split every affected record into two unpaired halves.",
            "If the two date windows are offset, records fall off the start of one and the end of the other in roughly equal measure.",
            "A timezone shift between the two extracts can push the edges of the window apart by a few hours at each end.",
            "When an identifier is regenerated or renumbered, each old record shows up as missing on one side and its new self on the other.",
            "Read the missing records from each side together; the pairs often turn out to be the same records under different keys.",
            "A join on the wrong field can look perfect for most records and fail symmetrically on the rest.",
            "If the source system switched from one id field to another partway through, this is the shape it leaves.",
            "Records that were split or merged in one system will fail to pair in both directions.",
            "A recent change to the key mapping is the first thing worth ruling out here.",
            "A composite key with one part formatted differently on each side produces exactly this kind of mirror image.",
            "Numeric keys stored as text on one side and as numbers on the other can lose leading zeros and break the pairing.",
            "An external reference that is set in one system but blank in the other fails on both sides of the comparison.",
            "Exchanges, replacements and reissued documents often carry a new id on one side and the original on the other.",
            "Check whether one side keys on an internal id and the other on an external reference.",
            "A separator change in a composite key, such as a hyphen becoming an underscore, would explain this.",
            "Before re-pulling either system, confirm both extracts used the same window boundaries.",
            "Encoding differences, like an accented character or a non-breaking space in the key, can quietly split matching records.",
            "When the gaps mirror each other, re-running the feeds rarely helps, because nothing is actually lost in transit.",
            "A rule that normalises the key on one side but not the other produces this pattern after any format change.",
            "If the comparison recently switched to a different key field, this is the expected side effect until both sides use it.",
            "Records that moved between stores, locations or accounts may carry a different key on each side.",
            "Look for a record in each list with the same amount and date; that pair usually reveals the key problem.",
            "Truncation of long keys on one side would leave the same records unpaired in both lists.",
            "A key that embeds a date or version stamp stops pairing the moment either side updates it.",
            "If the same records appear on both lists every run, the key is wrong; if the lists change each run, the windows are.",
            "Duplicate keys on one side can steal a pairing and leave a twin unpaired on each side.",
            "Comparing the earliest and latest timestamps in each extract shows quickly whether the windows line up.",
            "A change in which date field drives the window, such as created versus updated, shifts records across both edges.",
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
            "The pairing is correct, so the question is which field moved.",
            "Nothing needs re-fetching; something needs re-checking.",
            "If the mismatches all sit on one field, the mapping for that field is the place to start.",
            "Edits made after the last sync read as mismatches until the next sync carries them over.",
            "Check whether a field was recently renamed or remapped, since the comparison may now be reading a different column.",
            "If the mismatches started on a specific date, a configuration change around then is the likeliest cause.",
            "Look at a few mismatches side by side; drift from formatting looks very different from drift from real changes.",
            "If the mismatched values look newer on one side, that side is ahead and the other usually catches up.",
            "A field that one side overwrites on every sync and the other keeps as entered will drift whenever it is edited.",
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
            "A sync that is running behind explains both at once: the newest records have not arrived and recent edits have not been applied.",
            "If the mismatched records and the missing ones fall in the same date range, a single delayed job is the likeliest cause.",
            "Check whether the mismatches sit on recently edited records, since those often travel with the same lag as the gaps.",
            "A field mapping change can break some pairings outright and alter values on the records that still pair.",
            "When the gaps run one way and the mismatches cluster on one field, the two are most likely unrelated.",
            "See whether the missing records and the mismatched ones share a source, a store or a channel.",
            "A partially completed import can leave some records absent and others half-written with stale values.",
            "If a rule changed recently, the mismatches may be new while the gaps are an older, separate story.",
            "Mismatches on status fields alongside missing records often mean one side is mid-update.",
            "Records that failed validation on one side may be missing outright or stored with defaults in place of real values.",
            "A timezone mismatch can move records out of one side's window and shift the date fields on the rest.",
            "Sorting both lists by date usually shows quickly whether they come from the same incident.",
            "A key change that only reached part of the data can leave some records unpaired and others paired with the wrong counterpart.",
            "Duplicate records on one side can produce both a gap and a mismatch from the same underlying entry.",
            "When both kinds appear right after an integration change, that change is the first place to look.",
            "Mismatched amounts next to missing records can mean adjustments were posted as new records on one side.",
            "A cancelled or reversed transaction can vanish from one side while its counterpart on the other side changes value.",
            "If the missing records are all recent and the mismatches are all older, they are almost certainly separate issues.",
            "A rate limit or timeout during the extract can drop some records and return cached copies of others.",
            "Split or merged records produce both symptoms, since part of the record pairs and part has nothing to pair with.",
            "Mismatches on records near the window edge may simply be records caught halfway through an update.",
            "Lines added after the sync show up as gaps while edited lines on the same documents show up as mismatches.",
            "If either system was restored from a backup, older values and missing recent records would appear together.",
            "Exchanges and replacements often create one record with no counterpart and another whose amount no longer matches.",
            "Both symptoms on the same records point at one cause, and on different records they point at two.",
            "A one-off backfill on one side can bring in records the other lacks and overwrite values the other still holds.",
            "Compare the newest mismatch with the newest gap; if their timestamps are close, they likely share a cause.",
            "Anything that resolves on the next run was timing, and anything that stays is configuration.",
            "A sync that writes headers and lines in separate passes can leave some documents missing and others with incomplete totals.",
            "Pending or held records are a common thread, missing where they are skipped and mismatched on status where both sides carry them.",
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
            "A sync lag on one side combined with a filter on the other is the most common way to end up with this shape.",
            "Start with the larger direction, since that is where a feed or filter problem is most likely to be hiding.",
            "The smaller direction may be ordinary timing at the window edges, with the real gap sitting in the larger one.",
            "If the two extracts cover windows of different lengths, the side with the longer window carries the extra records.",
            "Check whether the records missing from each side have different sources, types or channels, since that usually separates the causes.",
            "Records created close to extract time can be missing from whichever system was read first.",
            "A key fault and a feed gap can overlap, with a mild key issue behind the smaller direction and an outage behind the larger.",
            "Records deleted on one side after they synced appear as gaps even though the feed did its job.",
            "A one-off import into one system can leave it holding records the other never received.",
            "If the smaller gap repeats from run to run while the larger one changes, they have different causes.",
            "A store, channel or location that only one side covers adds records to one direction only.",
            "Records rejected by validation tend to differ between the two systems, which produces uneven gaps in both directions.",
            "Re-running the extract for the larger direction first will show how much of the gap is just timing.",
            "Check whether either side was paused or rate-limited during the extract window.",
            "Cancellations handled differently by each system can remove records from one side while new ones are still arriving on the other.",
            "Look at the dates on each list separately; a gap clustered at one end of the window usually means a timing difference.",
            "Draft or test records that one system holds and the other ignores often make up the smaller direction.",
            "Records that move between accounts or locations may drop out of one side's scope while appearing in the other's.",
            "A backlog on the receiving queue and a cleanup job on the sending side can create gaps running in opposite directions.",
            "The oldest missing record in each direction often shows when each gap began.",
            "If the larger gap appeared suddenly, the cause is probably a recent change; a steady small gap is usually long-standing.",
            "A system that batches its exports will look short at one point in its cycle and complete at another.",
            "Manual entries made directly in one system often explain the smaller direction, since they bypass the integration.",
            "A sync that only carries records forward from a set date will leave older history missing on the receiving side.",
            "Records that changed status after the extract can fall out of one side's filter while staying in the other's.",
            "Different retention periods on the two systems make older records disappear from one side first.",
            "Returns and exchanges are often recorded on different schedules, which can open gaps of different sizes in each direction.",
            "A record type that one system splits apart and the other keeps whole will leave unpaired records in both directions.",
            "If the smaller direction is made up of very recent records, it will probably resolve itself by the next run.",
            "Check whether either integration has a page or batch limit that was reached during this window.",
    ].asImmutable()
}
