package darpan.reconciliation.streak

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * DAR-BE-107: one automation's clean-day streak, folded forward one finished scheduled slot at a time.
 *
 * <p>The streak used to be recomputed from run history on every Chat notification
 * (a run-count lookback, deleted by DAR-BE-144), which is why it stopped at 20: each extra step of
 * lookback is more rows read per notification, and a year of an hourly check is 8,760 runs. Here the
 * state is stored and only slots newer than its cursor are ever read, so it has no ceiling.</p>
 *
 * <p>Moqui-free on purpose (the loader and the entity live elsewhere), so every rule below is a
 * unit test in the fast pool. The rules are Aditi's decisions of 2026-10-21/22 on DAR-BE-107:</p>
 * <ul>
 *   <li>A SLOT is one automation + scheduledDate (every child window of it). It is clean only when
 *       every window SUCCEEDED with zero differences; on the automation path differenceCount already
 *       includes value mismatches (reconcile#RuleSetCompareScope sums missing + rule + duplicate).</li>
 *   <li>Failed, dead-lettered, no-data, cancelled, not-run and reaper-killed slots BREAK it.</li>
 *   <li>A scheduled slot that never fired at all breaks it (detected from the gap to the next slot).</li>
 *   <li>A slot counts at most once: re-runs and re-drives never add.</li>
 *   <li>The streak counts DAYS, on the check's own midnight; quiet days between slots carry it.</li>
 *   <li>A schedule or zone change carries the streak; the slot the change skips is not a miss.</li>
 *   <li>An off-schedule run (Run now) neither adds nor breaks, even when it finds differences; an
 *       operator cancel breaks, like a kill; a pause carries the streak (2026-10-23).</li>
 * </ul>
 *
 * <p>State keys: streakStartDate (completion of the first clean slot; null = no live streak),
 * lastSlotDate (fold cursor), lastScheduledSlotDate (gap anchor; off-schedule runs never move it),
 * scheduleKey, bestDays/bestStartDate/bestEndDate (the longest CLOSED streak), lastBreakDate,
 * lastBreakReason.</p>
 */
class AutomationStreakFold {

    static final String CLEAN = "CLEAN"
    static final String BROKEN = "BROKEN"
    static final String PENDING = "PENDING"
    static final String IGNORED = "IGNORED"

    static final String REASON_DIFFERENCES = "DIFFERENCES"
    static final String REASON_MISSED = "MISSED"
    static final String REASON_UNCOUNTED = "UNCOUNTED"

    /**
     * How long past a slot's fire time the read side waits before calling it overdue. It only guards
     * what a READ shows while the scheduler is down; the fold records the miss itself once the next
     * slot arrives. Long enough to cover a slow run plus its transient retries.
     */
    static final Duration OVERDUE_GRACE = Duration.ofHours(6)

    private static final String STATUS_SUCCESS = "AUT_STAT_SUCCESS"
    private static final Set<String> UNFINISHED = ["AUT_STAT_PENDING", "AUT_STAT_RUNNING"] as Set<String>

    /**
     * One slot's outcome. {@code slot.executions} is every execution row of the slot (one per child
     * window), each with statusEnumId and differenceCount.
     */
    static Map classifySlot(Map slot) {
        List<Map> executions = (slot?.executions ?: []) as List<Map>
        if (!executions) return [outcome: IGNORED]
        List<String> statuses = executions.collect { ((it.statusEnumId)?.toString()?.trim()) ?: "" }
        // A slot is not finished until every window is: a pending retry can still change its answer.
        if (statuses.any { UNFINISHED.contains(it) }) return [outcome: PENDING]
        String failedStatus = statuses.find { it != STATUS_SUCCESS }
        if (failedStatus) return [outcome: BROKEN, reason: failedStatus]
        if (executions.any { it.differenceCount == null }) return [outcome: BROKEN, reason: REASON_UNCOUNTED]
        if (executions.any { toLong(it.differenceCount) != 0L }) return [outcome: BROKEN, reason: REASON_DIFFERENCES]
        return [outcome: CLEAN]
    }

    /**
     * Folds finished slots into {@code state} and returns the new state (the input is not modified).
     *
     * @param nextFireAfter the schedule: the first fire time strictly after an instant, or null when the
     *        schedule has no next fire (or cannot be read). A slot whose scheduledDate is not itself a
     *        fire time is off-schedule (Run now) and is skipped without breaking anything.
     * @param scheduleKey identifies the schedule and zone the slots were fired under; when it differs
     *        from the stored key, the gap across the change is not checked.
     * @param zone the check's own zone, whose midnight ends a day.
     */
    static Map fold(Map state, List<Map> slots, Closure<Instant> nextFireAfter, String scheduleKey, ZoneId zone) {
        Map next = new LinkedHashMap(state ?: [:])
        List<Map> ordered = (slots ?: []).findAll { it?.scheduledDate != null }
                .sort { (it.scheduledDate as Instant) } as List<Map>

        for (Map slot : ordered) {
            Instant scheduled = slot.scheduledDate as Instant
            Instant cursor = next.lastSlotDate as Instant
            if (cursor != null && !scheduled.isAfter(cursor)) continue

            Map classified = classifySlot(slot)
            if (classified.outcome == PENDING) break

            if (!isOnSchedule(scheduled, nextFireAfter)) {
                next.lastSlotDate = scheduled
                continue
            }

            Instant anchor = next.lastScheduledSlotDate as Instant
            boolean sameSchedule = next.scheduleKey == null || next.scheduleKey == scheduleKey
            if (anchor != null && sameSchedule) {
                Instant expected = safeNext(nextFireAfter, anchor)
                if (expected != null && expected.isBefore(scheduled)) {
                    closeStreak(next, expected, REASON_MISSED, zone)
                }
            }

            if (classified.outcome == CLEAN) {
                if (next.streakStartDate == null) {
                    next.streakStartDate = (slot.completedDate ?: scheduled) as Instant
                }
            } else if (classified.outcome == BROKEN) {
                closeStreak(next, (slot.completedDate ?: scheduled) as Instant, classified.reason as String, zone)
            }

            next.lastSlotDate = scheduled
            next.lastScheduledSlotDate = scheduled
            next.scheduleKey = scheduleKey
        }
        return next
    }

    /**
     * Pausing an automation carries its streak (Aditi, 2026-10-23): a paused check has nothing
     * scheduled, and days with nothing scheduled carry. Dropping the gap anchor does both halves —
     * the read side has no slot to call overdue, and the first slot after resuming has no gap to be
     * judged against. Call it when the automation is paused.
     */
    static Map pause(Map state) {
        Map next = new LinkedHashMap(state ?: [:])
        next.lastScheduledSlotDate = null
        return next
    }

    /** Whole days of the live streak at {@code now}, inclusive of its first and current day. */
    static int currentDays(Map state, Instant now, ZoneId zone, Closure<Instant> nextFireAfter) {
        Instant start = state?.streakStartDate as Instant
        if (start == null || now == null) return 0
        if (isOverdue(state, now, nextFireAfter)) return 0
        return (int) (ChronoUnit.DAYS.between(localDate(start, zone), localDate(now, zone)) + 1)
    }

    /** The longest streak this automation has had, counting the live one. */
    static int bestDays(Map state, Instant now, ZoneId zone, Closure<Instant> nextFireAfter) {
        return Math.max(toInt(state?.bestDays), currentDays(state, now, zone, nextFireAfter))
    }

    /** True when a scheduled slot is past due by more than the grace period and has not been folded. */
    static boolean isOverdue(Map state, Instant now, Closure<Instant> nextFireAfter) {
        Instant anchor = state?.lastScheduledSlotDate as Instant
        if (anchor == null || now == null) return false
        Instant expected = safeNext(nextFireAfter, anchor)
        return expected != null && expected.plus(OVERDUE_GRACE).isBefore(now)
    }

    /** The AutomationStreak date-time columns, which the fold holds as Instants. */
    static final List<String> DATE_FIELDS = ["streakStartDate", "lastSlotDate", "lastScheduledSlotDate",
            "bestStartDate", "bestEndDate", "lastBreakDate"].asImmutable()

    /** Every AutomationStreak column the fold owns (the row adds automationId and companyUserGroupId). */
    static final List<String> STATE_FIELDS = (DATE_FIELDS + ["scheduleKey", "bestDays", "lastBreakReason"]).asImmutable()

    /** Fold state from a stored AutomationStreak row (an EntityValue or a plain Map); null = a fresh state. */
    static Map stateFromRow(Map row) {
        if (row == null) return [:]
        Map state = [:]
        STATE_FIELDS.each { String field ->
            Object value = row.get(field)
            if (value == null) return
            if (DATE_FIELDS.contains(field)) state[field] = ((Date) value).toInstant()
            else if (field == "bestDays") state[field] = toInt(value)
            else state[field] = value.toString()
        }
        return state
    }

    /**
     * Column values for an AutomationStreak write. Every column is present, nulls included: a broken streak
     * must CLEAR streakStartDate on update, and a field left out of the map would keep the old value.
     */
    static Map<String, Object> rowFromState(Map state) {
        Map<String, Object> row = [:]
        STATE_FIELDS.each { String field ->
            Object value = state?.get(field)
            if (DATE_FIELDS.contains(field)) row[field] = value == null ? null : new java.sql.Timestamp(((Instant) value).toEpochMilli())
            else if (field == "bestDays") row[field] = value == null ? null : (Long) toLong(value)
            else row[field] = value
        }
        return row
    }

    private static void closeStreak(Map state, Instant brokenAt, String reason, ZoneId zone) {
        Instant start = state.streakStartDate as Instant
        if (start != null) {
            // The days the streak fully covered: from its first day up to, not including, the day it broke.
            // At least one, because a streak only exists once a clean slot started it.
            int closedDays = (int) Math.max(1L, ChronoUnit.DAYS.between(localDate(start, zone), localDate(brokenAt, zone)))
            if (closedDays > toInt(state.bestDays)) {
                state.bestDays = closedDays
                state.bestStartDate = start
                state.bestEndDate = brokenAt
            }
        }
        state.streakStartDate = null
        state.lastBreakDate = brokenAt
        state.lastBreakReason = reason
    }

    private static boolean isOnSchedule(Instant scheduled, Closure<Instant> nextFireAfter) {
        Instant fire = safeNext(nextFireAfter, scheduled.minusMillis(1))
        // An unreadable schedule cannot prove a run off-schedule, so it counts.
        return fire == null || fire == scheduled
    }

    private static Instant safeNext(Closure<Instant> nextFireAfter, Instant after) {
        try {
            return nextFireAfter?.call(after) as Instant
        } catch (Throwable ignored) {
            return null
        }
    }

    private static LocalDate localDate(Instant instant, ZoneId zone) {
        return instant.atZone(zone).toLocalDate()
    }

    private static long toLong(Object value) {
        if (value instanceof Number) return ((Number) value).longValue()
        String text = value?.toString()?.trim()
        return (text && text.isLong()) ? text.toLong() : 0L
    }

    private static int toInt(Object value) {
        return (int) toLong(value)
    }
}
