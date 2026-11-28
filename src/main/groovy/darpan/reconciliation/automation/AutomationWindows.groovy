package darpan.reconciliation.automation

import org.moqui.impl.service.ScheduledJobRunner

import java.sql.Timestamp
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

import darpan.config.DarpanSettings

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeInt
import static darpan.common.ValueSupport.readField
import static darpan.reconciliation.automation.AutomationRuntimeSupport.nowTimestamp

/**
 * DAR-OPS-029 S06 (split out of AutomationExecutionSupport). An automation's read windows (relative to its fire time, in its
 * windowTimeZone, half-open [start, end), chunked on calendar months past splitWindowDays) and its fire times (cron or
 * ISO-8601 duration): when it next fires and which past fire it still owes. No writes.
 */
class AutomationWindows {

    static final String WINDOW_PREVIOUS_DAY = "AUT_WIN_PREV_DAY"
    static final String WINDOW_PREVIOUS_WEEK = "AUT_WIN_PREV_WEEK"
    static final String WINDOW_PREVIOUS_MONTH = "AUT_WIN_PREV_MONTH"
    static final String WINDOW_LAST_DAYS = "AUT_WIN_LAST_DAYS"
    static final String WINDOW_LAST_WEEKS = "AUT_WIN_LAST_WEEKS"
    static final String WINDOW_LAST_MONTHS = "AUT_WIN_LAST_MONTHS"
    static final String WINDOW_CUSTOM = "AUT_WIN_CUSTOM"
    static final String WINDOW_STATE = "AUT_WIN_STATE"

    // Security (HIGH gap 2, reworked 2026-06-30): hard DoS sanity ceiling on the resolved window span,
    // SEPARATE from the per-automation `maxWindowDays` operational default (entity default=28). This
    // ceiling exists only to stop the 1970->9999 (~2.9M-day) explosion; it must never reject a real
    // window, so it is deliberately huge (10 years). Tunable in the admin app Settings (darpan.reconciliation.automation.maxWindowSpanDays).
    static int maxWindowSpanDays() {
        return DarpanSettings.intValue("darpan.reconciliation.automation.maxWindowSpanDays")
    }

    /**
     * Fallback for {@code ReconciliationAutomation.splitWindowDays} when the row carries no usable
     * value, kept equal to that field's entity default (entity/ReconciliationEntities.xml). A window
     * longer than this is chunked on calendar-month boundaries; a shorter one runs as a single child
     * window (DAR-BE-042). Plain Maps in tests and rows written before the column existed have no
     * default, so the constant has to state it rather than rely on the schema.
     */
    static final int DEFAULT_SPLIT_WINDOW_DAYS = 28

    /**
     * Whether the automation was saved with the retired state-based window (AUT_WIN_STATE): a population by record status
     * rather than a date window. No connector reads that way any more (DAR-BE-052); the pulse and the automation facade still
     * ask, to refuse such a row.
     */
    static boolean isStateWindowMode(def automation) {
        return WINDOW_STATE == normalize(readField(automation, "relativeWindowTypeEnumId"))
    }

    static List<Map<String, Object>> resolveWindows(def automation, Map params = [:]) {
        Map<String, Object> input = params ?: [:]
        Timestamp scheduledFireTime = toTimestamp(input.scheduledFireTime ?: input.scheduledDate ?: input.nowTimestamp) ?:
                new Timestamp(System.currentTimeMillis())
        ZoneId zone = resolveZoneId(readField(automation, "windowTimeZone"))
        ZonedDateTime scheduledLocal = scheduledFireTime.toInstant().atZone(zone)
        String windowType = normalize(readField(automation, "relativeWindowTypeEnumId")) ?: WINDOW_LAST_DAYS
        int windowCount = normalizeInt(readField(automation, "relativeWindowCount")) ?: 1

        if (WINDOW_STATE == windowType) {
            // Exactly one segment, always. The bounds are the scheduled day in the automation's zone:
            // a real, valid, non-empty window that satisfies every downstream consumer, and that the
            // extractor never sees because state-mode dispatch omits the date parameters entirely.
            ZonedDateTime stateStart = scheduledLocal.toLocalDate().atStartOfDay(zone)
            ZonedDateTime stateEnd = stateStart.plusDays(1)
            Timestamp stateStartTimestamp = Timestamp.from(stateStart.toInstant())
            Timestamp stateEndTimestamp = Timestamp.from(stateEnd.toInstant())
            return [[
                    sequenceNum         : 1,
                    windowStartDate     : stateStartTimestamp,
                    windowEndDate       : stateEndTimestamp,
                    childWindowStartDate: stateStartTimestamp,
                    childWindowEndDate  : stateEndTimestamp,
            ]] as List<Map<String, Object>>
        }

        ZonedDateTime windowStart
        ZonedDateTime windowEnd
        switch (windowType) {
            case WINDOW_PREVIOUS_DAY:
                LocalDate previousDay = scheduledLocal.toLocalDate().minusDays(1)
                windowStart = previousDay.atStartOfDay(zone)
                windowEnd = previousDay.plusDays(1).atStartOfDay(zone)
                break
            case WINDOW_PREVIOUS_WEEK:
                LocalDate currentWeekStart = scheduledLocal.toLocalDate()
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                LocalDate previousWeekStart = currentWeekStart.minusWeeks(1)
                windowStart = previousWeekStart.atStartOfDay(zone)
                windowEnd = currentWeekStart.atStartOfDay(zone)
                break
            case WINDOW_PREVIOUS_MONTH:
                YearMonth previousMonth = YearMonth.from(scheduledLocal.toLocalDate()).minusMonths(1)
                windowStart = previousMonth.atDay(1).atStartOfDay(zone)
                windowEnd = previousMonth.plusMonths(1).atDay(1).atStartOfDay(zone)
                break
            case WINDOW_LAST_WEEKS:
                LocalDate weekEnd = scheduledLocal.toLocalDate()
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                windowStart = weekEnd.minusWeeks(Math.max(windowCount, 1)).atStartOfDay(zone)
                windowEnd = weekEnd.atStartOfDay(zone)
                break
            case WINDOW_LAST_MONTHS:
                YearMonth monthEnd = YearMonth.from(scheduledLocal.toLocalDate())
                windowStart = monthEnd.minusMonths(Math.max(windowCount, 1)).atDay(1).atStartOfDay(zone)
                windowEnd = monthEnd.atDay(1).atStartOfDay(zone)
                break
            case WINDOW_CUSTOM:
                Object customStart = input.windowStartDate ?: input.childWindowStartDate ?: readField(automation, "customWindowStartDate")
                Object customEnd = input.windowEndDate ?: input.childWindowEndDate ?: readField(automation, "customWindowEndDate")
                windowStart = requireTimestamp(customStart, "windowStartDate is required for custom automation windows")
                        .toInstant().atZone(zone)
                windowEnd = requireTimestamp(customEnd, "windowEndDate is required for custom automation windows")
                        .toInstant().atZone(zone)
                break
            case WINDOW_LAST_DAYS:
            default:
                LocalDate dayEnd = scheduledLocal.toLocalDate()
                windowStart = dayEnd.minusDays(Math.max(windowCount, 1)).atStartOfDay(zone)
                windowEnd = dayEnd.atStartOfDay(zone)
                break
        }

        if (!windowStart.isBefore(windowEnd)) {
            throw new IllegalArgumentException("Automation window start must be before window end")
        }

        // Security (HIGH gap 2): hard DoS sanity cap on the window span, INDEPENDENT of the
        // per-automation `maxWindowDays` field. A caller-supplied WINDOW_CUSTOM range like
        // 1970-01-01..9999-12-31 (~2.9M days) otherwise produces ~120k month segments, each inserting a
        // ReconciliationAutomationExecution row inside a transaction -> DB bloat / heap OOM / ghost
        // PENDING rows.
        //
        // IMPORTANT: `maxWindowDays` is an OPERATIONAL window default (entity default=28 days), NOT a
        // security cap. Using it here would REJECT legitimate windows: a 31-day month
        // (WINDOW_PREVIOUS_MONTH / LAST_MONTHS=1 on a 30/31-day month, ordinary CUSTOM month ranges)
        // exceeds 28 and would be wrongly refused. The DoS guard must NEVER reject a legitimate window,
        // so it uses a separate, deliberately huge sanity ceiling (10 years) that only the attack
        // pathological ranges can breach. Operators can tune it in the admin app Settings (darpan.reconciliation.automation.maxWindowSpanDays).
        long __spanDays = ChronoUnit.DAYS.between(windowStart, windowEnd)
        int __maxSpanDays = maxWindowSpanDays()
        if (__spanDays > __maxSpanDays) {
            throw new IllegalArgumentException(
                    "Automation window span (${__spanDays} days) exceeds the maximum of ${__maxSpanDays} days.")
        }

        Timestamp parentStart = Timestamp.from(windowStart.toInstant())
        Timestamp parentEnd = Timestamp.from(windowEnd.toInstant())

        // DAR-BE-042: chunk on calendar months only when the window is long enough to need chunking.
        // This cut used to apply to EVERY window regardless of length. A 7-day previous-week span that
        // happened to straddle the 1st therefore became two child windows, and because
        // findOrCreateExecution keys on the CHILD bounds those are two DISTINCT executions rather than a
        // duplicate — so one weekly fire produced two execution rows, two run-result rows and two
        // notifications, each reconciling only part of the week. It bit roughly once a month (the week
        // containing the 1st) and never bit the daily automations, whose one-day window cannot contain a
        // month start.
        //
        // The threshold is the automation's own `splitWindowDays` (entity default 28). That field was
        // already created, updated, persisted and published through the facade and the API contract, but
        // NOTHING read it — so operators could set a chunk size that did nothing while an unconfigurable
        // month rule ran instead. Reading it here makes the documented knob the one that decides.
        // A null or non-positive value falls back to the entity default rather than meaning "split
        // everything", which is the behaviour this fix exists to remove.
        Integer configuredSplitWindowDays = normalizeInt(readField(automation, "splitWindowDays"))
        int splitWindowDays = (configuredSplitWindowDays != null && configuredSplitWindowDays > 0) ?
                configuredSplitWindowDays : DEFAULT_SPLIT_WINDOW_DAYS
        List<Map<String, ZonedDateTime>> windowSegments = __spanDays > splitWindowDays ?
                splitOnCalendarMonthBoundaries(windowStart, windowEnd, zone) :
                [[start: windowStart, end: windowEnd] as Map<String, ZonedDateTime>]

        return windowSegments.withIndex().collect { Map<String, ZonedDateTime> segment, int index ->
            [
                    sequenceNum         : index + 1,
                    windowStartDate     : parentStart,
                    windowEndDate       : parentEnd,
                    childWindowStartDate: Timestamp.from(segment.start.toInstant()),
                    childWindowEndDate  : Timestamp.from(segment.end.toInstant()),
            ]
        }
    }

    static Timestamp resolveNextScheduledFireTime(def automation, Timestamp scheduledFireTime, Timestamp now) {
        String scheduleExpr = normalize(readField(automation, "scheduleExpr"))
        if (!scheduleExpr) return null
        ZoneId zone = resolveZoneId(readField(automation, "windowTimeZone"))
        Timestamp base = scheduledFireTime ?: now ?: new Timestamp(System.currentTimeMillis())

        try {
            ZonedDateTime cursor = base.toInstant().atZone(zone).plusNanos(1)
            ZonedDateTime nowLocal = (now ?: base).toInstant().atZone(zone)
            Optional<ZonedDateTime> next = ScheduledJobRunner.getExecutionTime(scheduleExpr).nextExecution(cursor)
            while (next.present && !next.get().isAfter(nowLocal)) {
                next = ScheduledJobRunner.getExecutionTime(scheduleExpr).nextExecution(next.get().plusNanos(1))
            }
            return next.present ? Timestamp.from(next.get().toInstant()) : null
        } catch (Throwable ignored) {
            Duration duration = parseScheduleDuration(scheduleExpr)
            if (duration == null || duration.isZero() || duration.isNegative()) return null
            ZonedDateTime next = base.toInstant().atZone(zone).plus(duration)
            ZonedDateTime nowLocal = (now ?: base).toInstant().atZone(zone)
            while (!next.isAfter(nowLocal)) next = next.plus(duration)
            return Timestamp.from(next.toInstant())
        }
    }

    /**
     * DAR-BE-328: the fire this automation is waiting on, past or future: nextScheduledFireTime when set, else the last
     * cron or duration fire not yet run. A peak-period move may start it before it is due.
     */
    static Timestamp resolveCandidateFireTime(def automation, Timestamp now) {
        Timestamp nowValue = now ?: new Timestamp(System.currentTimeMillis())
        Timestamp nextFire = toTimestamp(readField(automation, "nextScheduledFireTime"))
        if (nextFire != null) return nextFire

        String scheduleExpr = normalize(readField(automation, "scheduleExpr"))
        if (!scheduleExpr) return null

        Timestamp lastScheduledFireTime = toTimestamp(readField(automation, "lastScheduledFireTime"))
        ZoneId zone = resolveZoneId(readField(automation, "windowTimeZone"))
        Timestamp cronFireTime = resolveLastCronFireTime(scheduleExpr, nowValue, zone)
        if (cronFireTime != null) {
            return lastScheduledFireTime == null || lastScheduledFireTime.before(cronFireTime) ? cronFireTime : null
        }

        Timestamp durationFireTime = resolveLastDurationFireTime(scheduleExpr, lastScheduledFireTime, nowValue, zone)
        if (durationFireTime != null) {
            return lastScheduledFireTime == null || lastScheduledFireTime.before(durationFireTime) ? durationFireTime : null
        }
        return null
    }

    protected static List<Map<String, ZonedDateTime>> splitOnCalendarMonthBoundaries(ZonedDateTime start,
            ZonedDateTime end, ZoneId zone) {
        List<Map<String, ZonedDateTime>> segments = []
        ZonedDateTime segmentStart = start
        while (segmentStart.isBefore(end)) {
            ZonedDateTime nextMonthStart = YearMonth.from(segmentStart.toLocalDate()).plusMonths(1)
                    .atDay(1).atStartOfDay(zone)
            ZonedDateTime segmentEnd = nextMonthStart.isBefore(end) ? nextMonthStart : end
            segments << [start: segmentStart, end: segmentEnd]
            segmentStart = segmentEnd
        }
        return segments
    }

    protected static Timestamp resolveLastCronFireTime(String scheduleExpr, Timestamp now, ZoneId zone) {
        try {
            ZonedDateTime nowLocal = now.toInstant().atZone(zone)
            Optional<ZonedDateTime> last = ScheduledJobRunner.getExecutionTime(scheduleExpr).lastExecution(nowLocal)
            return last.present ? Timestamp.from(last.get().toInstant()) : null
        } catch (Throwable ignored) {
            return null
        }
    }

    protected static Timestamp resolveLastDurationFireTime(String scheduleExpr, Timestamp lastScheduledFireTime,
            Timestamp now, ZoneId zone) {
        Duration duration = parseScheduleDuration(scheduleExpr)
        if (duration == null || duration.isZero() || duration.isNegative() || lastScheduledFireTime == null) return null

        ZonedDateTime nowLocal = now.toInstant().atZone(zone)
        ZonedDateTime candidate = lastScheduledFireTime.toInstant().atZone(zone).plus(duration)
        Timestamp latestDue = null
        while (!candidate.isAfter(nowLocal)) {
            latestDue = Timestamp.from(candidate.toInstant())
            candidate = candidate.plus(duration)
        }
        return latestDue
    }

    protected static Timestamp resolveScheduledFireTime(def ec, Map<String, Object> input) {
        return toTimestamp(input.scheduledFireTime ?: input.scheduledDate ?: input.nowTimestamp) ?: nowTimestamp(ec)
    }

    protected static Timestamp requireTimestamp(Object rawValue, String message) {
        Timestamp timestamp = toTimestamp(rawValue)
        if (!timestamp) throw new IllegalArgumentException(message)
        return timestamp
    }

    protected static Timestamp toTimestamp(Object rawValue) {
        if (rawValue == null) return null
        if (rawValue instanceof Timestamp) return rawValue
        if (rawValue instanceof Date) return new Timestamp(rawValue.time)
        if (rawValue instanceof Instant) return Timestamp.from((Instant) rawValue)
        if (rawValue instanceof ZonedDateTime) return Timestamp.from(((ZonedDateTime) rawValue).toInstant())
        if (rawValue instanceof LocalDateTime) return Timestamp.valueOf((LocalDateTime) rawValue)

        String normalized = normalize(rawValue)
        if (!normalized) return null
        try {
            return Timestamp.from(Instant.parse(normalized))
        } catch (Exception ignored) {
        }
        try {
            return Timestamp.valueOf(normalized)
        } catch (Exception ignored) {
        }
        try {
            return Timestamp.valueOf(LocalDateTime.parse(normalized))
        } catch (Exception ignored) {
        }
        throw new IllegalArgumentException("Invalid timestamp '${normalized}'")
    }

    protected static ZoneId resolveZoneId(Object rawZoneId) {
        String normalized = normalize(rawZoneId) ?: "UTC"
        try {
            return ZoneId.of(normalized)
        } catch (Exception ignored) {
            return ZoneId.of("UTC")
        }
    }

    protected static Duration parseScheduleDuration(String scheduleExpr) {
        try {
            return Duration.parse(scheduleExpr)
        } catch (Exception ignored) {
            return null
        }
    }
}
