package darpan.reconciliation.notification

import java.time.DayOfWeek
import java.time.ZonedDateTime

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

    static void setLinePicker(Closure picker) { linePicker = picker }

    static void resetLinePicker() { linePicker = null }

    /**
     * Genuinely random in production, pinned by tests through {@link #setLinePicker}. A deterministic
     * seed off reconciliationRunResultId was rejected: ids are sequential, so a modulo marches
     * through the pool in visible order.
     */
    static String pickLine(List<String> pool, String slotName) {
        if (!pool) return null
        if (linePicker != null) return (String) linePicker.call(pool, slotName)
        return pool.get(RANDOM.nextInt(pool.size()))
    }

    /**
     * The verdict clause. This is the ONE place a clean run asserts that it is clean — every other
     * pool adds a different dimension. Entries carry no terminator: the plain tier fuses a tail
     * clause straight onto them, and the renderer supplies the full stop otherwise.
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
    ].asImmutable()

    /**
     * Fused onto the verdict when there is no streak and no time hook. These are continuations, not
     * sentences: each must open with a comma or a dash so it attaches grammatically. The old pool
     * held standalone sentences, every one of which re-asserted the headline's own claim.
     */
    static final List<String> CLEAN_TAILS = [
            ", nothing to chase.",
            ", nothing owed.",
            " — nothing here needs a follow-up.",
            ", so this one's not going in anyone's standup.",
            ", and nobody has to be a hero today.",
            " — no thread, no \"quick question\".",
    ].asImmutable()

    // Time closers carry the time consequence and nothing else. Saying "clean" here would repeat
    // the verdict the headline already delivered, which is exactly what made the old message read
    // as three shuffled synonyms. One sentence each: a second one competes with the first.
    static final List<String> FRIDAY_AFTERNOON_LINES = [
            "Nothing following you into Friday evening.",
            "Good way to head into the weekend.",
    ].asImmutable()

    static final List<String> EARLY_MORNING_LINES = [
            "Sorted before the office was even open.",
            "Done before anyone was at a desk.",
    ].asImmutable()

    static final List<String> MONDAY_MORNING_LINES = [
            "Nothing waiting for you at the top of the week.",
            "Decent way to start the week.",
    ].asImmutable()

    static final List<String> MIDDAY_LINES = [
            "Nothing to interrupt the afternoon.",
            "Back to your lunch.",
    ].asImmutable()

    /**
     * Picks a time-of-day flavour line for an already-zoned moment. The zone must be the tenant's,
     * not the server's — completedDate is a server timestamp, and reading it in the JVM default zone
     * computes "Friday afternoon" five and a half hours out for a team on IST, flipping the day for
     * anything after 18:30.
     *
     * Returning null outside these windows is deliberate. It is also load-bearing: a null here with
     * no streak is what drops the message to a single fused line, so most clean runs are one
     * sentence and the ones that earn a second line read as observed rather than mechanical.
     */
    static String timeOfDayLine(ZonedDateTime moment) {
        if (moment == null) return null
        int hour = moment.hour
        DayOfWeek day = moment.dayOfWeek

        if (hour < 7) return pickLine(EARLY_MORNING_LINES, "timeOfDay")
        if (day == DayOfWeek.FRIDAY && hour >= 12) return pickLine(FRIDAY_AFTERNOON_LINES, "timeOfDay")
        if (day == DayOfWeek.MONDAY && hour < 12) return pickLine(MONDAY_MORNING_LINES, "timeOfDay")
        if (hour >= 12 && hour < 15) return pickLine(MIDDAY_LINES, "timeOfDay")
        return null
    }

    /**
     * Streak closers open with a connective so they read as continuing the verdict rather than
     * starting a second, unrelated thought. One sentence each — the old entries each appended a
     * punchline ("It's getting cocky"), giving the message two closers competing for the last word.
     */
    static final List<String> STREAK_TEMPLATES = [
            "That's {n} in a row now.",
            "Makes it {n} straight.",
            "{n} in a row, and whatever you changed is holding.",
    ].asImmutable()

    /** How far back the streak lookback reads. Rendered as "N and counting." once reached. */
    static final int LOOKBACK_LIMIT = 20

    /**
     * @param priorCleanRuns consecutive clean runs BEFORE this one. The rendered streak includes the
     *        current run, so 3 prior reads as "4 in a row".
     */
    static String streakLine(int priorCleanRuns) {
        if (priorCleanRuns < 2) return null
        if (priorCleanRuns >= LOOKBACK_LIMIT) {
            return "${LOOKBACK_LIMIT} and counting.".toString()
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
            // Humour is off from here up. The XML service adds the status and warning lines.
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
     * The old shape drew a headline, a subline and a time line from three independent pools and
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
     * The old headline was a bare total for every bucket, which deferred the whole diagnosis to the
     * line below and left four very different runs looking identical at a glance.
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
        return "${runName} — ${total} to look at, ${shape}.".toString()
    }

    /**
     * What the shape implies, and nothing else.
     *
     * These lines carry no numbers on purpose. The old ones re-narrated the very counts printed
     * three lines below them — "39 went out and never checked in" sitting above
     * "Missing from Shopify: 39" — so the prose and the Details block said the same thing twice, in
     * two registers. The block owns the arithmetic; this line owns the reading of it.
     *
     * One sentence each, and each opens by referring back to the headline ("That shape", "Those
     * are", "Both sides") so the two lines read as one thought rather than two stacked fragments.
     */
    private static String diagnosisFor(String bucket, int mismatches) {
        if (bucket == BUCKET_EVEN_SPLIT) {
            return "Two sides short by nearly the same amount rarely means two separate outages — check the join key before chasing records."
        }
        if (bucket == BUCKET_ONE_SIDED) {
            return "That shape usually points at delivery rather than matching."
        }
        if (bucket == BUCKET_VALUE_DRIFT) {
            return "Both sides hold every record; they just don't line up on what's inside them."
        }
        if (mismatches > 0) {
            return "Those are usually two different causes — worth splitting before you start."
        }
        return "Both directions, but lopsided, which is likelier to be two separate gaps than one."
    }
}
