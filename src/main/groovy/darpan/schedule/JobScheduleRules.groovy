package darpan.schedule

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import groovy.transform.CompileDynamic

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * DAR-BE-305. What a scheduled job's cron may be, and when a resume's preview is still good. Pure: no entity or service
 * access. Quartz flavour (seconds field, `?`), the same parser the framework's job runner uses, evaluated in the JVM default zone like it.
 *
 * A cron is refused, with the reason, when it is blank, over 120 characters, does not parse, never fires within 400 days, or
 * its smallest gap between two runs (over the next 500 runs, at most three years ahead) is under the job's floor. The
 * last rule is what stops `0 * 3 * * ?` (every minute of 3 am) on a job that may run once a day.
 */
@CompileDynamic
class JobScheduleRules {
    static final int MAX_LENGTH = 120
    static final int MAX_DAYS_TO_FIRST_RUN = 400
    static final int RUNS_CHECKED = 500
    static final int HORIZON_DAYS = 1100
    static final long TOLERANCE_FLOOR_ROWS = 100L
    static final BigDecimal TOLERANCE_FRACTION = 0.05G

    /** The zone the framework's job runner evaluates a cron in (ScheduledJobRunner: ZonedDateTime.now()), so what is promised here is what runs. */
    static ZoneId zone() { return ZoneId.systemDefault() }

    private static final CronParser PARSER = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ))

    /** Null when `cron` is acceptable for a job that may run at most every `minIntervalMinutes` (null reads as 1). */
    static String cronProblem(String cron, Number minIntervalMinutes, Instant now) {
        String text = cron?.trim()
        if (!text) return "The cron is blank; pause the job instead of clearing its cron."
        if (text.length() > MAX_LENGTH) return "The cron is ${text.length()} characters; the most is ${MAX_LENGTH}.".toString()
        ExecutionTime time
        try {
            time = ExecutionTime.forCron(PARSER.parse(text))
        } catch (Exception e) {
            return "Not a valid Quartz cron (six fields: seconds minutes hours day-of-month month day-of-week, with ? in one of the two day fields): ${e.message}".toString()
        }
        ZonedDateTime start = ZonedDateTime.ofInstant(now, zone())
        Optional<ZonedDateTime> first
        try { first = time.nextExecution(start) } catch (Exception ignored) { first = Optional.empty() }
        if (!first.isPresent() || Duration.between(start, first.get()).toDays() > MAX_DAYS_TO_FIRST_RUN) {
            return "This cron never runs within ${MAX_DAYS_TO_FIRST_RUN} days.".toString()
        }
        int floor = Math.max(1, (minIntervalMinutes ?: 1) as int)
        Long gap = smallestGapSeconds(text, now)
        if (gap != null && gap < floor * 60L) {
            return "Runs every ${describeGap(gap)} at its tightest; this job may run at most every ${floor} minute${floor == 1 ? '' : 's'}.".toString()
        }
        return null
    }

    /** The next `count` runs at or after `from`, in order; empty when the cron does not parse or never runs. */
    static List<Instant> nextRuns(String cron, Instant from, int count) {
        List<Instant> out = []
        try {
            ExecutionTime time = ExecutionTime.forCron(PARSER.parse(cron.trim()))
            ZonedDateTime cursor = ZonedDateTime.ofInstant(from, zone())
            while (out.size() < count) {
                Optional<ZonedDateTime> next = time.nextExecution(cursor)
                if (!next.isPresent()) break
                out << next.get().toInstant()
                cursor = next.get()
            }
        } catch (Exception ignored) { return out }
        return out
    }

    /** The first run of `cron` at or after `at`, or null when the cron does not parse or never runs again. */
    static Instant firstRunAtOrAfter(String cron, Instant at) {
        try {
            ExecutionTime time = parsed(cron.trim())
            ZonedDateTime zoned = ZonedDateTime.ofInstant(at, zone())
            if (at.nano == 0 && time.isMatch(zoned)) return at
            Optional<ZonedDateTime> next = time.nextExecution(zoned)
            return next.isPresent() ? next.get().toInstant() : null
        } catch (Exception ignored) { return null }
    }

    /** Parsed once per distinct cron: a board asks for one scan cron once per fire. Bounded, so edits cannot grow it. */
    private static final Map<String, ExecutionTime> PARSED = new java.util.concurrent.ConcurrentHashMap<>()

    private static ExecutionTime parsed(String cron) {
        ExecutionTime t = PARSED.get(cron)
        if (t == null) {
            if (PARSED.size() > 64) PARSED.clear()
            t = ExecutionTime.forCron(PARSER.parse(cron))
            PARSED.put(cron, t)
        }
        return t
    }

    /** The smallest gap, in whole minutes (0 for a sub-minute gap), between consecutive runs; null when it has under two runs. */
    static Integer smallestGapMinutes(String cron, Instant from) {
        Long seconds = smallestGapSeconds(cron, from)
        return seconds == null ? null : (int) (seconds / 60L)
    }

    static Long smallestGapSeconds(String cron, Instant from) {
        List<Instant> runs = []
        try {
            ExecutionTime time = ExecutionTime.forCron(PARSER.parse(cron.trim()))
            ZonedDateTime limit = ZonedDateTime.ofInstant(from, zone()).plusDays(HORIZON_DAYS)
            ZonedDateTime cursor = ZonedDateTime.ofInstant(from, zone())
            while (runs.size() < RUNS_CHECKED) {
                Optional<ZonedDateTime> next = time.nextExecution(cursor)
                if (!next.isPresent() || next.get().isAfter(limit)) break
                runs << next.get().toInstant()
                cursor = next.get()
            }
        } catch (Exception ignored) { return null }
        if (runs.size() < 2) return null
        long best = Long.MAX_VALUE
        for (int i = 1; i < runs.size(); i++) best = Math.min(best, runs[i].epochSecond - runs[i - 1].epochSecond)
        return best
    }

    /**
     * Null when a resume that needs a preview may go ahead: the number shown to the person was `previewedRows`, today's dry run
     * counted `actualRows`. Refused with no preview, or when the count grew by more than 100 rows or 5% of what was shown
     * (whichever is more). A count that fell is fine: less would be deleted than was approved.
     */
    static String previewProblem(Long previewedRows, long actualRows) {
        return previewProblem(previewedRows, actualRows, "resuming this job")
    }

    /** As above; `action` names what needs the preview in the refusal ("resuming this job", "making a period shorter"). */
    static String previewProblem(Long previewedRows, long actualRows, String action) {
        if (previewedRows == null) return "Preview first: ${action} deletes data, so send the row count the preview showed (previewedRows).".toString()
        if (previewedRows < 0) return "previewedRows cannot be negative."
        long allowed = Math.max(TOLERANCE_FLOOR_ROWS, (previewedRows * TOLERANCE_FRACTION).longValue())
        if (actualRows > previewedRows + allowed) return "The numbers changed since your preview; preview again."
        return null
    }

    private static String describeGap(long seconds) {
        if (seconds < 60) return "${seconds} second${seconds == 1 ? '' : 's'}".toString()
        long m = seconds / 60
        return "${m} minute${m == 1 ? '' : 's'}".toString()
    }
}
