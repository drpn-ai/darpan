package darpan.peak

import org.junit.jupiter.api.Test

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows

class PeakPeriodSupportTests {
    private static final ZoneId LA = ZoneId.of('America/Los_Angeles')

    private static Instant la(int month, int day, int hour, int minute, int year = 2026) {
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, LA).toInstant()
    }

    private static PeakWindow window(LocalTime peakStart = LocalTime.of(8, 0), LocalTime peakEnd = LocalTime.of(23, 0),
                                     List<RunTimeMove> moves = [move(LocalDate.of(2026, 11, 28), LocalTime.of(6, 0))],
                                     LocalDate from = LocalDate.of(2026, 11, 24), LocalDate thru = LocalDate.of(2026, 12, 1)) {
        new PeakWindow('P1', 'Black Friday', from, thru, peakStart, peakEnd, moves)
    }

    private static RunTimeMove move(LocalDate onDate, LocalTime runTime, Set<String> ids = ['100518'] as Set) {
        new RunTimeMove(ids, onDate, runTime)
    }

    private static Instant eff(Instant fire, List<PeakWindow> ws, String id = '100518', boolean applyMoves = true) {
        PeakPeriodSupport.effectiveStart(fire, id, ws, LA, applyMoves)
    }

    @Test
    void noWindowLeavesFireUnchanged() {
        Instant fire = la(11, 28, 2, 15)
        assertEquals(fire, PeakPeriodSupport.effectiveStart(fire, '100518', [], LA, true))
    }

    @Test
    void moveOnItsDateStartsAtTheMovedTime() {
        assertEquals(la(11, 28, 6, 0), eff(la(11, 28, 2, 15), [window()]))
    }

    @Test
    void moveOnAnotherDateDoesNothing() {
        Instant fire = la(11, 27, 2, 15)
        assertEquals(fire, eff(fire, [window()]))
    }

    @Test
    void moveForEveryDateWhenOnDateNull() {
        PeakWindow w = window(LocalTime.of(8, 0), LocalTime.of(23, 0), [move(null, LocalTime.of(6, 0))])
        assertEquals(la(11, 25, 6, 0), eff(la(11, 25, 2, 15), [w]))
    }

    @Test
    void moveEarlierThanFireIsAllowed() {
        PeakWindow w = window(LocalTime.of(8, 0), LocalTime.of(23, 0), [move(LocalDate.of(2026, 11, 28), LocalTime.of(1, 0))])
        assertEquals(la(11, 28, 1, 0), eff(la(11, 28, 2, 15), [w]))
    }

    @Test
    void otherAutomationIsNotMoved() {
        Instant fire = la(11, 28, 2, 15)
        assertEquals(fire, eff(fire, [window()], '100520'))
    }

    @Test
    void movesIgnoredWhenApplyMovesFalse() {
        Instant fire = la(11, 28, 2, 15)
        assertEquals(fire, eff(fire, [window()], '100518', false))
    }

    @Test
    void fireInsidePeakHoursWaitsUntilTheyEnd() {
        assertEquals(la(11, 27, 23, 0), eff(la(11, 27, 10, 0), [window()]))
    }

    @Test
    void movedIntoPeakHoursAlsoWaits() {
        PeakWindow w = window(LocalTime.of(8, 0), LocalTime.of(23, 0), [move(LocalDate.of(2026, 11, 28), LocalTime.of(12, 0))])
        assertEquals(la(11, 28, 23, 0), eff(la(11, 28, 2, 15), [w]))
    }

    @Test
    void peakEndIsExclusive() {
        assertEquals(la(11, 27, 23, 0), eff(la(11, 27, 23, 0), [window()]))
        assertEquals(la(11, 27, 7, 59), eff(la(11, 27, 7, 59), [window()]))
        assertEquals(la(11, 27, 23, 0), eff(la(11, 27, 8, 0), [window()]))
    }

    @Test
    void midnightCrossingHoldsNextMorning() {
        PeakWindow w = window(LocalTime.of(20, 0), LocalTime.of(2, 0))
        assertEquals(la(11, 26, 2, 0), eff(la(11, 26, 1, 0), [w]))
    }

    @Test
    void midnightCrossingNotHeldBeforeFirstDate() {
        PeakWindow w = window(LocalTime.of(20, 0), LocalTime.of(2, 0))
        Instant fire = la(11, 24, 1, 0)
        assertEquals(fire, eff(fire, [w]))
    }

    @Test
    void midnightCrossingHeldAfterLastDate() {
        PeakWindow w = window(LocalTime.of(20, 0), LocalTime.of(2, 0))
        assertEquals(la(12, 2, 2, 0), eff(la(12, 2, 1, 0), [w]))
    }

    @Test
    void oneDayPeriod() {
        PeakWindow w = window(LocalTime.of(8, 0), LocalTime.of(23, 0), [], LocalDate.of(2026, 11, 27), LocalDate.of(2026, 11, 27))
        assertEquals(la(11, 27, 23, 0), eff(la(11, 27, 9, 0), [w]))
        Instant other = la(11, 28, 9, 0)
        assertEquals(other, eff(other, [w]))
    }

    @Test
    void heldByInstantWhenAutomationZoneDiffers() {
        assertEquals(Instant.parse('2026-11-28T07:00:00Z'), eff(Instant.parse('2026-11-27T17:00:00Z'), [window()]))
    }

    @Test
    void dstGapRunTimeResolvesForward() {
        PeakWindow w = window(LocalTime.of(8, 0), LocalTime.of(23, 0), [move(LocalDate.of(2026, 3, 8), LocalTime.of(2, 30))],
                LocalDate.of(2026, 3, 6), LocalDate.of(2026, 3, 10))
        Instant expected = ZonedDateTime.of(2026, 3, 8, 3, 30, 0, 0, LA).toInstant()
        assertEquals(expected, eff(la(3, 8, 0, 30), [w]))
    }

    @Test
    void parseHhMmRejectsGarbage() {
        assertThrows(IllegalArgumentException) { PeakPeriodSupport.parseHhMm('25:00') }
        assertNull(PeakPeriodSupport.parseHhMm(''))
        assertEquals(LocalTime.of(6, 5), PeakPeriodSupport.parseHhMm('06:05'))
    }
}
