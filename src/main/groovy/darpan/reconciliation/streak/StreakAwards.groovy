package darpan.reconciliation.streak

import darpan.reconciliation.notification.StreakMilestoneVoice

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * DAR-BE-108: what one folded slot earns. Moqui-free, like AutomationStreakFold, so every rule is a fast unit test.
 *
 * <p>Badges belong to an automation and count clean days (DAR-UI-059, Aditi 2026-10-22): the ladder is
 * StreakMilestoneVoice.BADGE_LADDER; High Five and Fifteen Days of Fame are earned once per automation, ever; from Month
 * Doze up each streak that reaches a badge earns it again. Side quests are earned once per tenant. Only the two quests
 * with a GIF are live (every quest message carries one): Big Haul and Black Friday Survivor.</p>
 */
class StreakAwards {

    static final String BIG_HAUL = "BIG_HAUL"
    static final String BLACK_FRIDAY_SURVIVOR = "BLACK_FRIDAY_SURVIVOR"

    /** Big Haul: one clean run that checked at least this many records. */
    static final long BIG_HAUL_MIN_RECORDS = 10_000L

    /** How long after Cyber Monday a fold still asks whether the tenant survived that Black Friday. */
    static final int BLACK_FRIDAY_LOOK_DAYS = 14

    /** Whole days of a streak at {@code at}, inclusive of its first day, on the check's own midnight. */
    static int daysAt(Instant streakStart, Instant at, ZoneId zone) {
        if (streakStart == null || at == null) return 0
        return (int) (ChronoUnit.DAYS.between(streakStart.atZone(zone).toLocalDate(), at.atZone(zone).toLocalDate()) + 1)
    }

    /**
     * The badges a streak of {@code days} earns now, lowest first, each as [key, timesEarned]. {@code earned} is the
     * automation's badge records by key ([timesEarned, lastEarnedStreakStartDate]); a streak earns a badge at most once.
     */
    static List<Map<String, Object>> badgesEarned(int days, Instant streakStart, Map<String, Map> earned) {
        if (streakStart == null) return []
        List<Map<String, Object>> out = []
        StreakMilestoneVoice.BADGE_LADDER.each { Map badge ->
            if (days < (badge.days as Integer)) return
            Map record = earned?.get(badge.key)
            int times = toInt(record?.timesEarned)
            if (times == 0) {
                out << [key: badge.key, timesEarned: 1]
            } else if (badge.reEarnable && record.lastEarnedStreakStartDate != streakStart) {
                out << [key: badge.key, timesEarned: times + 1]
            }
        }
        return out
    }

    /** Records one slot checked: per window the larger side, summed over the slot's windows. */
    static long slotRecords(Map slot) {
        return ((slot?.executions ?: []) as List<Map>).sum(0L) { Map execution ->
            Math.max(toLong(execution.file1RecordCount), toLong(execution.file2RecordCount))
        } as long
    }

    static boolean isBigHaul(Map slot) {
        return AutomationStreakFold.classifySlot(slot).outcome == AutomationStreakFold.CLEAN &&
                slotRecords(slot) >= BIG_HAUL_MIN_RECORDS
    }

    /** Black Friday to Cyber Monday of {@code year}: the day after the fourth Thursday of November, plus three days. */
    static List<LocalDate> blackFridayWeekend(int year) {
        LocalDate thanksgiving = LocalDate.of(year, 11, 1).with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY))
        LocalDate friday = thanksgiving.plusDays(1)
        return [friday, friday.plusDays(3)]
    }

    /** The year whose Black Friday a fold at {@code date} should ask about, or null outside the look-back after it. */
    static Integer blackFridayYearToCheck(LocalDate date) {
        if (date == null) return null
        LocalDate monday = blackFridayWeekend(date.year)[1]
        return (!date.isBefore(monday) && !date.isAfter(monday.plusDays(BLACK_FRIDAY_LOOK_DAYS))) ? date.year : null
    }

    /**
     * True when this automation's live streak covers Black Friday through Cyber Monday of {@code year}: it started on or
     * before the Friday and the fold has reached the Monday without breaking it.
     */
    static boolean survivedBlackFriday(Map state, ZoneId zone, int year) {
        Instant start = state?.streakStartDate as Instant
        Instant reached = state?.lastSlotDate as Instant
        if (start == null || reached == null) return false
        List<LocalDate> weekend = blackFridayWeekend(year)
        return !start.atZone(zone).toLocalDate().isAfter(weekend[0]) && !reached.atZone(zone).toLocalDate().isBefore(weekend[1])
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
