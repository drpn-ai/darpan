package darpan.reconciliation.streak

import org.junit.jupiter.api.Test

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

class StreakAwardsTests {

    private static final ZoneId LA = ZoneId.of("America/Los_Angeles")
    private static final Instant START = ZonedDateTime.of(2026, 3, 1, 2, 20, 0, 0, LA).toInstant()

    private static Instant day(int month, int day) {
        return ZonedDateTime.of(2026, month, day, 2, 20, 0, 0, LA).toInstant()
    }

    // ---- days ----------------------------------------------------------------------------------

    @Test
    void daysCountInclusiveOnTheChecksOwnMidnight() {
        assertEquals(1, StreakAwards.daysAt(START, START, LA))
        assertEquals(30, StreakAwards.daysAt(START, day(3, 30), LA))
        // 23:30 LA on 1 March is already 2 March in UTC: the check's own zone decides the day.
        Instant lateEvening = ZonedDateTime.of(2026, 3, 1, 23, 30, 0, 0, LA).toInstant()
        assertEquals(1, StreakAwards.daysAt(START, lateEvening, LA))
        assertEquals(0, StreakAwards.daysAt(null, START, LA))
    }

    // ---- badges --------------------------------------------------------------------------------

    @Test
    void aStreakEarnsEachRungItReachesOnce() {
        assertEquals([], StreakAwards.badgesEarned(4, START, [:]))
        assertEquals([[key: "HIGH_FIVE", timesEarned: 1]], StreakAwards.badgesEarned(5, START, [:]))

        Map<String, Map> earned = [HIGH_FIVE: [timesEarned: 1, lastEarnedStreakStartDate: START]]
        assertEquals([], StreakAwards.badgesEarned(6, START, earned), "the same streak never earns a badge twice")
        assertEquals(["FIFTEEN_DAYS_OF_FAME"], StreakAwards.badgesEarned(15, START, earned)*.key)
    }

    @Test
    void aSlotThatJumpsSeveralRungsEarnsThemAll() {
        // A monthly check goes from day 1 to day 32 in one slot.
        assertEquals(["HIGH_FIVE", "FIFTEEN_DAYS_OF_FAME", "MONTH_DOZE"], StreakAwards.badgesEarned(32, START, [:])*.key)
    }

    @Test
    void theFirstTwoBadgesAreOnceEverAndMonthDozeUpReEarnOnANewStreak() {
        Instant secondStreak = day(5, 1)
        Map<String, Map> earned = [
                HIGH_FIVE           : [timesEarned: 1, lastEarnedStreakStartDate: START],
                FIFTEEN_DAYS_OF_FAME: [timesEarned: 1, lastEarnedStreakStartDate: START],
                MONTH_DOZE          : [timesEarned: 1, lastEarnedStreakStartDate: START],
        ]
        assertEquals([[key: "MONTH_DOZE", timesEarned: 2]], StreakAwards.badgesEarned(30, secondStreak, earned))
    }

    @Test
    void noLiveStreakEarnsNothing() {
        assertEquals([], StreakAwards.badgesEarned(400, null, [:]))
    }

    // ---- Big Haul ------------------------------------------------------------------------------

    private static Map slot(List<Map> executions) {
        return [scheduledDate: START, completedDate: START, executions: executions]
    }

    @Test
    void bigHaulNeedsTenThousandRecordsInOneCleanRun() {
        Map big = slot([[statusEnumId: "AUT_STAT_SUCCESS", differenceCount: 0, file1RecordCount: 9_000, file2RecordCount: 10_000]])
        assertEquals(10_000L, StreakAwards.slotRecords(big), "the larger side of the window counts")
        assertTrue(StreakAwards.isBigHaul(big))

        Map tooFew = slot([[statusEnumId: "AUT_STAT_SUCCESS", differenceCount: 0, file1RecordCount: 9_999, file2RecordCount: 9_999]])
        assertFalse(StreakAwards.isBigHaul(tooFew))

        Map dirty = slot([[statusEnumId: "AUT_STAT_SUCCESS", differenceCount: 1, file1RecordCount: 50_000, file2RecordCount: 50_000]])
        assertFalse(StreakAwards.isBigHaul(dirty), "a big run with a difference is not a haul")
    }

    @Test
    void bigHaulSumsTheWindowsOfOneSlot() {
        Map split = slot([[statusEnumId: "AUT_STAT_SUCCESS", differenceCount: 0, file1RecordCount: 6_000, file2RecordCount: 5_000],
                          [statusEnumId: "AUT_STAT_SUCCESS", differenceCount: 0, file1RecordCount: 4_000, file2RecordCount: "4500"]])
        assertEquals(10_500L, StreakAwards.slotRecords(split))
        assertTrue(StreakAwards.isBigHaul(split))
    }

    // ---- Black Friday --------------------------------------------------------------------------

    @Test
    void blackFridayIsTheDayAfterTheFourthThursdayThroughCyberMonday() {
        assertEquals([LocalDate.of(2026, 11, 27), LocalDate.of(2026, 11, 30)], StreakAwards.blackFridayWeekend(2026))
        assertEquals([LocalDate.of(2025, 11, 28), LocalDate.of(2025, 12, 1)], StreakAwards.blackFridayWeekend(2025))
        // November 2029 starts on a Thursday: the fourth Thursday is the 22nd.
        assertEquals(LocalDate.of(2029, 11, 23), StreakAwards.blackFridayWeekend(2029)[0])
    }

    @Test
    void theQuestIsAskedAboutOnlyInTheTwoWeeksFromCyberMonday() {
        assertNull(StreakAwards.blackFridayYearToCheck(LocalDate.of(2026, 11, 29)))
        assertEquals(2026, StreakAwards.blackFridayYearToCheck(LocalDate.of(2026, 11, 30)))
        assertEquals(2026, StreakAwards.blackFridayYearToCheck(LocalDate.of(2026, 12, 14)))
        assertNull(StreakAwards.blackFridayYearToCheck(LocalDate.of(2026, 12, 15)))
        assertNull(StreakAwards.blackFridayYearToCheck(LocalDate.of(2026, 7, 1)))
    }

    @Test
    void survivingNeedsALiveStreakFromBeforeFridayThatReachedMonday() {
        Instant beforeFriday = ZonedDateTime.of(2026, 11, 20, 2, 20, 0, 0, LA).toInstant()
        Instant monday = ZonedDateTime.of(2026, 11, 30, 2, 0, 0, 0, LA).toInstant()
        Instant sunday = ZonedDateTime.of(2026, 11, 29, 2, 0, 0, 0, LA).toInstant()
        Instant saturday = ZonedDateTime.of(2026, 11, 28, 2, 20, 0, 0, LA).toInstant()

        assertTrue(StreakAwards.survivedBlackFriday([streakStartDate: beforeFriday, lastSlotDate: monday], LA, 2026))
        assertFalse(StreakAwards.survivedBlackFriday([streakStartDate: beforeFriday, lastSlotDate: sunday], LA, 2026),
                "the fold has not reached Cyber Monday yet")
        assertFalse(StreakAwards.survivedBlackFriday([streakStartDate: saturday, lastSlotDate: monday], LA, 2026),
                "a streak that restarted on Saturday did not hold through Friday")
        assertFalse(StreakAwards.survivedBlackFriday([streakStartDate: null, lastSlotDate: monday], LA, 2026))
    }
}
