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

    static final List<String> CLEAN_HEADLINES = [
            "spotless.",
            "nothing to see here. In the best way.",
            "all lined up.",
            "clean.",
            "not a single one out of place.",
            "no notes.",
            "everything lined up.",
            "quiet. Properly quiet.",
    ].asImmutable()

    static final List<String> CLEAN_SUBLINES = [
            "All lined up. Nobody has to be a hero today.",
            "No follow-ups. No thread. No \"quick question\".",
            "This one's not going in anyone's standup.",
            "Nothing to chase. Go be unreachable for twenty minutes.",
            "Zero across the board. Have the second coffee.",
            "Both systems told the same story. Rare. Enjoy it.",
            "Every record lined up. Nothing owed.",
            "Not one out of place. Take the win.",
    ].asImmutable()

    static final List<String> FRIDAY_AFTERNOON_LINES = [
            "Friday afternoon, and it's clean. Go.",
            "Friday, and nothing's outstanding. Good timing.",
    ].asImmutable()

    static final List<String> EARLY_MORNING_LINES = [
            "Clean before 7am. Better colleague than most.",
            "Sorted before the office filled up.",
    ].asImmutable()

    static final List<String> MONDAY_MORNING_LINES = [
            "Monday morning. Nothing's on fire.",
            "Week starts clean. Rare and welcome.",
    ].asImmutable()

    static final List<String> MIDDAY_LINES = [
            "Clean run over lunch. Undisturbed.",
            "Mid-day, all lined up. Carry on.",
    ].asImmutable()

    /**
     * Picks a time-of-day flavour line for an already-zoned moment. The zone must be the tenant's,
     * not the server's — completedDate is a server timestamp, and reading it in the JVM default zone
     * computes "Friday afternoon" five and a half hours out for a team on IST, flipping the day for
     * anything after 18:30.
     *
     * Returning null outside these windows is deliberate: most runs get no time-of-day line, which is
     * what keeps the ones that do land feeling observed rather than mechanical.
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

    static final List<String> STREAK_TEMPLATES = [
            "{n} in a row. It's getting cocky.",
            "{n} straight. Somebody should say something nice to it.",
            "{n} clean runs back to back. Unbothered.",
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
                    : "${runName} finished. Not cleanly.".toString())
            // The FLAVOUR is switched off above ISSUES — the NUMBERS are not. A run that failed
            // partway still produced a partial result, and those counts are the operator's only
            // handle on what did get compared. Dropping them would trade one silent alert
            // (the all-zeros clean-looking run this whole change exists to fix) for another.
            // A failure that produced no output at all never reaches here: the service's
            // noOutput branch renders it without counts, because zeros there mean "never computed".
            lines.addAll(detailsBlock(missingFromFile1, missingFromFile2, mismatches, file1Label, file2Label))
            return lines
        }

        if (bucket == BUCKET_CLEAN) {
            lines << "${runName} — ${pickLine(CLEAN_HEADLINES, "headline")}".toString()
            String subline = pickLine(CLEAN_SUBLINES, "subline")
            if (subline) lines << subline
            String timeLine = timeOfDayLine((ZonedDateTime) safeModel.get("completedMoment"))
            if (timeLine) lines << timeLine
            String streak = streakLine(toCount(safeModel.get("priorCleanRuns")))
            if (streak) lines << streak
            return lines
        }

        lines << "${runName} — ${total} to look at.".toString()
        lines << sublineFor(bucket, missingFromFile1, missingFromFile2, mismatches, file1Label, file2Label)
        lines.addAll(detailsBlock(missingFromFile1, missingFromFile2, mismatches, file1Label, file2Label))
        return lines
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

    private static String sublineFor(String bucket, int missingFromFile1, int missingFromFile2,
                                     int mismatches, String file1Label, String file2Label) {
        if (bucket == BUCKET_EVEN_SPLIT) {
            return "Near enough the same number on each side. That's not coincidence. Check the key before you chase records."
        }
        if (bucket == BUCKET_ONE_SIDED) {
            String haveLabel = missingFromFile2 > 0 ? file1Label : file2Label
            String lackLabel = missingFromFile2 > 0 ? file2Label : file1Label
            return "${haveLabel} has them. ${lackLabel} doesn't. All one direction, so this is a delivery gap.".toString()
        }
        if (bucket == BUCKET_VALUE_DRIFT) {
            return "Nothing's missing. They just don't line up on the values."
        }
        int missingTotal = missingFromFile1 + missingFromFile2
        return "${missingTotal} went out and never checked in. ${mismatches} more don't match on the values.".toString()
    }
}
