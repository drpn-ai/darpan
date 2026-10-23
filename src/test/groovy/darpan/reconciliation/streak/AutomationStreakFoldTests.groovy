package darpan.reconciliation.streak

import org.junit.jupiter.api.Test

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

class AutomationStreakFoldTests {

    private static final ZoneId LA = ZoneId.of("America/Los_Angeles")
    private static final String DAILY_KEY = "0 0 2 * * ?|America/Los_Angeles"

    /** A daily 02:00 LA schedule: fires every day at 02:00 local, nothing in between. */
    private static Instant daily(int month, int day) {
        return ZonedDateTime.of(2026, month, day, 2, 0, 0, 0, LA).toInstant()
    }

    /** Next local fire time strictly after {@code after}, built from the DATE so DST never drifts it. */
    private static Closure<Instant> dailyAt(LocalTime time) {
        return { Instant after ->
            LocalDate day = after.atZone(LA).toLocalDate()
            Instant candidate = ZonedDateTime.of(day, time, LA).toInstant()
            return candidate.isAfter(after) ? candidate : ZonedDateTime.of(day.plusDays(1), time, LA).toInstant()
        }
    }

    private static final Closure<Instant> DAILY_NEXT = dailyAt(LocalTime.of(2, 0))

    private static Map slot(Instant scheduled, String status = "AUT_STAT_SUCCESS", Integer differences = 0) {
        return [scheduledDate: scheduled, completedDate: scheduled.plus(Duration.ofMinutes(20)),
                executions: [[statusEnumId: status, differenceCount: differences]]]
    }

    private static Map fold(Map state, List<Map> slots, String scheduleKey = DAILY_KEY) {
        return AutomationStreakFold.fold(state, slots, DAILY_NEXT, scheduleKey, LA)
    }

    private static List<Map> cleanDays(int month, int fromDay, int toDay) {
        return (fromDay..toDay).collect { slot(daily(month, it)) }
    }

    // ---- slot classification ---------------------------------------------------------------

    @Test
    void successWithZeroDifferencesIsClean() {
        assertEquals(AutomationStreakFold.CLEAN, AutomationStreakFold.classifySlot(slot(daily(3, 1))).outcome)
    }

    @Test
    void anyDifferenceBreaks() {
        Map result = AutomationStreakFold.classifySlot(slot(daily(3, 1), "AUT_STAT_SUCCESS", 1))
        assertEquals(AutomationStreakFold.BROKEN, result.outcome)
        assertEquals(AutomationStreakFold.REASON_DIFFERENCES, result.reason)
    }

    @Test
    void anUnknownDifferenceCountIsNotClean() {
        // Absence of a check is not clean: a SUCCESS row that never recorded a count proves nothing.
        assertEquals(AutomationStreakFold.BROKEN,
                AutomationStreakFold.classifySlot(slot(daily(3, 1), "AUT_STAT_SUCCESS", null)).outcome)
    }

    @Test
    void everyTerminalNonSuccessStatusBreaks() {
        ["AUT_STAT_FAILED", "AUT_STAT_DEAD_LETTER", "AUT_STAT_NO_DATA", "AUT_STAT_CANCELLED", "AUT_STAT_NOT_RUN"].each {
            assertEquals(AutomationStreakFold.BROKEN,
                    AutomationStreakFold.classifySlot(slot(daily(3, 1), it, 0)).outcome, it)
        }
    }

    @Test
    void anUnfinishedExecutionLeavesTheSlotPending() {
        ["AUT_STAT_PENDING", "AUT_STAT_RUNNING"].each {
            assertEquals(AutomationStreakFold.PENDING,
                    AutomationStreakFold.classifySlot(slot(daily(3, 1), it, null)).outcome, it)
        }
    }

    @Test
    void oneDirtyChildWindowBreaksTheWholeSlot() {
        Map split = slot(daily(3, 1))
        split.executions = [[statusEnumId: "AUT_STAT_SUCCESS", differenceCount: 0],
                            [statusEnumId: "AUT_STAT_SUCCESS", differenceCount: 3]]
        assertEquals(AutomationStreakFold.BROKEN, AutomationStreakFold.classifySlot(split).outcome)
    }

    @Test
    void aPendingChildOutranksAFailedSibling() {
        // The retry may still turn the slot around only if nothing else already failed it; but the
        // slot is not FINISHED until every window is, so the fold must wait rather than guess.
        Map split = slot(daily(3, 1))
        split.executions = [[statusEnumId: "AUT_STAT_FAILED", differenceCount: null],
                            [statusEnumId: "AUT_STAT_PENDING", differenceCount: null]]
        assertEquals(AutomationStreakFold.PENDING, AutomationStreakFold.classifySlot(split).outcome)
    }

    // ---- folding ---------------------------------------------------------------------------

    @Test
    void cleanDailySlotsCountInclusiveDays() {
        Map state = fold([:], cleanDays(3, 1, 5))
        assertEquals(daily(3, 1).plus(Duration.ofMinutes(20)), state.streakStartDate)
        // Evening of the fifth day: 1st..5th inclusive.
        Instant evening = ZonedDateTime.of(2026, 3, 5, 20, 0, 0, 0, LA).toInstant()
        assertEquals(5, AutomationStreakFold.currentDays(state, evening, LA, DAILY_NEXT))
    }

    @Test
    void thereIsNoLookbackCap() {
        LocalDate first = LocalDate.of(2026, 1, 1)
        List<Map> slots = (0..<400).collect { slot(ZonedDateTime.of(first.plusDays(it), LocalTime.of(2, 0), LA).toInstant()) }
        Map state = fold([:], slots)
        Instant lastEvening = ZonedDateTime.of(first.plusDays(399), LocalTime.of(20, 0), LA).toInstant()
        assertEquals(400, AutomationStreakFold.currentDays(state, lastEvening, LA, DAILY_NEXT))
    }

    @Test
    void aDifferenceEndsTheStreakAndTheNextCleanSlotStartsAFreshOne() {
        List<Map> slots = cleanDays(3, 1, 10) + [slot(daily(3, 11), "AUT_STAT_SUCCESS", 2)] + cleanDays(3, 12, 13)
        Map state = fold([:], slots)
        assertEquals(daily(3, 12).plus(Duration.ofMinutes(20)), state.streakStartDate)
        assertEquals(10, state.bestDays)
        assertEquals(AutomationStreakFold.REASON_DIFFERENCES, state.lastBreakReason)
    }

    @Test
    void aBrokenStreakStaysAtZeroUntilTheNextCleanSlot() {
        Map state = fold([:], cleanDays(3, 1, 3) + [slot(daily(3, 4), "AUT_STAT_FAILED", null)])
        assertNull(state.streakStartDate)
        Instant evening = ZonedDateTime.of(2026, 3, 4, 20, 0, 0, 0, LA).toInstant()
        assertEquals(0, AutomationStreakFold.currentDays(state, evening, LA, DAILY_NEXT))
    }

    @Test
    void aPendingSlotStopsTheFoldAndALaterFoldPicksItUp() {
        List<Map> firstPass = cleanDays(3, 1, 2) + [slot(daily(3, 3), "AUT_STAT_PENDING", null)] + cleanDays(3, 4, 4)
        Map state = fold([:], firstPass)
        assertEquals(daily(3, 2), state.lastSlotDate, "the cursor must not pass an unfinished slot")

        // The retry finished with differences; the 4th is re-offered and folded after it.
        Map later = fold(state, [slot(daily(3, 3), "AUT_STAT_SUCCESS", 7)] + cleanDays(3, 4, 4))
        assertEquals(daily(3, 4).plus(Duration.ofMinutes(20)), later.streakStartDate)
        assertEquals(daily(3, 4), later.lastSlotDate)
    }

    @Test
    void aSlotAlreadyCountedNeverCountsAgain() {
        // Re-runs never add: one scheduled slot counts at most once, even if it is offered again
        // after the fold has moved past it (e.g. a re-drive of an old slot ending dirty).
        Map state = fold([:], cleanDays(3, 1, 5))
        Map again = fold(state, [slot(daily(3, 3), "AUT_STAT_SUCCESS", 9)])
        assertEquals(state.streakStartDate, again.streakStartDate)
        assertEquals(state.lastSlotDate, again.lastSlotDate)
    }

    @Test
    void anOffScheduleRunNeitherAddsNorBreaksNorMovesTheGapAnchor() {
        Instant runNow = ZonedDateTime.of(2026, 3, 2, 14, 37, 0, 0, LA).toInstant()
        List<Map> slots = cleanDays(3, 1, 2) + [slot(runNow, "AUT_STAT_SUCCESS", 4)] + cleanDays(3, 3, 3)
        Map state = fold([:], slots)
        assertEquals(daily(3, 1).plus(Duration.ofMinutes(20)), state.streakStartDate)
        assertEquals(daily(3, 3), state.lastScheduledSlotDate)
    }

    @Test
    void aMissedSlotBreaksTheStreak() {
        // The 4th never fired (server down): the next slot's gap proves a scheduled check went unrun.
        Map state = fold([:], cleanDays(3, 1, 3) + cleanDays(3, 5, 6))
        assertEquals(daily(3, 5).plus(Duration.ofMinutes(20)), state.streakStartDate)
        assertEquals(AutomationStreakFold.REASON_MISSED, state.lastBreakReason)
        assertEquals(3, state.bestDays)
    }

    @Test
    void aScheduleOrZoneChangeDoesNotBreakTheStreak() {
        // Rule 8: the slot a change skips is not a miss. The 4th is skipped by the change.
        Map state = fold([:], cleanDays(3, 1, 3))
        Map after = fold(state, cleanDays(3, 5, 6), "0 0 2 * * ?|America/New_York")
        assertEquals(state.streakStartDate, after.streakStartDate)
        assertEquals("0 0 2 * * ?|America/New_York", after.scheduleKey)
    }

    @Test
    void quietDaysOfAWeeklyCheckCarryTheStreak() {
        Closure<Instant> weeklyNext = { Instant after ->
            ZonedDateTime local = after.atZone(LA)
            ZonedDateTime candidate = ZonedDateTime.of(2026, 3, 2, 2, 0, 0, 0, LA) // a Monday
            while (!candidate.isAfter(local)) candidate = candidate.plusWeeks(1)
            return candidate.toInstant()
        }
        List<Map> slots = [slot(daily(3, 2)), slot(daily(3, 9))]
        Map state = AutomationStreakFold.fold([:], slots, weeklyNext, "weekly|LA", LA)
        Instant friday = ZonedDateTime.of(2026, 3, 13, 20, 0, 0, 0, LA).toInstant()
        assertEquals(12, AutomationStreakFold.currentDays(state, friday, LA, weeklyNext))
    }

    @Test
    void anOverdueSlotReadsAsNoStreakWithoutBeingStored() {
        Map state = fold([:], cleanDays(3, 1, 5))
        // The 6th and 7th never ran and it is now the 8th: the scheduler is down.
        Instant eighth = ZonedDateTime.of(2026, 3, 8, 20, 0, 0, 0, LA).toInstant()
        assertEquals(0, AutomationStreakFold.currentDays(state, eighth, LA, DAILY_NEXT))
        assertTrue(AutomationStreakFold.isOverdue(state, eighth, DAILY_NEXT))
        // The 6th's slot is due at 02:00 and still inside the grace period at 02:30.
        Instant sixthEarly = ZonedDateTime.of(2026, 3, 6, 2, 30, 0, 0, LA).toInstant()
        assertFalse(AutomationStreakFold.isOverdue(state, sixthEarly, DAILY_NEXT))
    }

    @Test
    void aPausedAutomationCarriesItsStreakThroughThePauseAndPastTheResume() {
        Map paused = AutomationStreakFold.pause(fold([:], cleanDays(3, 1, 5)))
        Instant fortnightLater = ZonedDateTime.of(2026, 3, 20, 20, 0, 0, 0, LA).toInstant()
        assertEquals(20, AutomationStreakFold.currentDays(paused, fortnightLater, LA, DAILY_NEXT))

        // Resumed on the 21st: the 6th..20th never fired, and that gap is not a miss.
        Map resumed = fold(paused, cleanDays(3, 21, 22))
        assertEquals(daily(3, 1).plus(Duration.ofMinutes(20)), resumed.streakStartDate)
        assertNull(resumed.lastBreakReason)
        // Once resumed, the gap rule applies again.
        Map missed = fold(resumed, cleanDays(3, 24, 24))
        assertEquals(AutomationStreakFold.REASON_MISSED, missed.lastBreakReason)
    }

    // ---- the stored row ---------------------------------------------------------------------

    @Test
    void aStateSurvivesTheRoundTripThroughItsRow() {
        Map state = fold([:], cleanDays(3, 1, 3) + [slot(daily(3, 4), "AUT_STAT_FAILED", null)] + cleanDays(3, 5, 6))
        Map row = AutomationStreakFold.rowFromState(state)
        assertTrue(row.streakStartDate instanceof java.sql.Timestamp)
        assertEquals(3L, row.bestDays)
        assertEquals(state, AutomationStreakFold.stateFromRow(row))
        // The fold resumes from a stored row exactly as from the live state.
        assertEquals(fold(state, cleanDays(3, 7, 8)), fold(AutomationStreakFold.stateFromRow(row), cleanDays(3, 7, 8)))
    }

    @Test
    void aWriteCarriesEveryColumnSoABreakClearsTheStoredStart() {
        Map row = AutomationStreakFold.rowFromState(fold([:], [slot(daily(3, 1), "AUT_STAT_FAILED", null)]))
        assertEquals(AutomationStreakFold.STATE_FIELDS.toSet(), row.keySet())
        assertTrue(row.containsKey("streakStartDate"))
        assertNull(row.streakStartDate)
    }

    @Test
    void noRowReadsAsAFreshState() {
        assertEquals([:], AutomationStreakFold.stateFromRow(null))
    }

    @Test
    void bestDaysIncludesTheLiveStreakOnceItOvertakesTheRecord() {
        Map state = fold([:], cleanDays(3, 1, 3) + [slot(daily(3, 4), "AUT_STAT_FAILED", null)] + cleanDays(3, 5, 12))
        assertEquals(3, state.bestDays, "stored best only moves when a streak closes")
        Instant evening = ZonedDateTime.of(2026, 3, 12, 20, 0, 0, 0, LA).toInstant()
        assertEquals(8, AutomationStreakFold.bestDays(state, evening, LA, DAILY_NEXT))
    }

    @Test
    void daysFollowTheChecksOwnMidnight() {
        // A slot that completes at 23:50 LA is the 1st in LA even though it is the 2nd in UTC.
        Instant lateFirst = ZonedDateTime.of(2026, 3, 1, 23, 30, 0, 0, LA).toInstant()
        Map lateSlot = [scheduledDate: lateFirst, completedDate: lateFirst.plus(Duration.ofMinutes(20)),
                        executions: [[statusEnumId: "AUT_STAT_SUCCESS", differenceCount: 0]]]
        Closure<Instant> lateNext = dailyAt(LocalTime.of(23, 30))
        Map state = AutomationStreakFold.fold([:], [lateSlot], lateNext, "late|LA", LA)
        Instant secondEvening = ZonedDateTime.of(2026, 3, 2, 20, 0, 0, 0, LA).toInstant()
        assertEquals(2, AutomationStreakFold.currentDays(state, secondEvening, LA, lateNext))
    }
}
