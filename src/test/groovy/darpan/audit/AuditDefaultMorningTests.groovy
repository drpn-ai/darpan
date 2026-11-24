package darpan.audit

import org.junit.jupiter.api.Test

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

import static org.junit.jupiter.api.Assertions.assertEquals

/**
 * DAR-BE-347 (no Moqui). A blank date shows the last morning that was due: from the tenant's midnight until today's
 * scheduled run, today has no runs, so showing it reads "0 of N checks ran" when nothing is wrong.
 */
class AuditDefaultMorningTests {

    private static final ZoneId LA = ZoneId.of("America/Los_Angeles")
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata")
    private static final LocalDate NOV_23 = LocalDate.of(2026, 11, 23)
    private static final LocalDate NOV_24 = LocalDate.of(2026, 11, 24)

    private static ZonedDateTime la(int day, int hour, int minute) { ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, LA) }

    @Test
    void beforeTodaysRunShowsYesterday() {
        // Malbon, 2026-11-24 15:02 IST = 01:34 PST; its audit runs at 01:45 Los Angeles.
        assertEquals(NOV_23, AuditReportGraph.defaultMorning(la(24, 1, 34), LA, "01:45", LA, true, false, true))
        assertEquals(NOV_23, AuditReportGraph.defaultMorning(la(24, 0, 0), LA, "01:45", LA, true, false, true),
                "the first minute of the tenant's day")
    }

    @Test
    void fromTheScheduledTimeShowsToday() {
        assertEquals(NOV_24, AuditReportGraph.defaultMorning(la(24, 1, 45), LA, "01:45", LA, true, false, true))
        assertEquals(NOV_24, AuditReportGraph.defaultMorning(la(24, 23, 59), LA, "01:45", LA, true, false, true))
    }

    @Test
    void aCheckThatAlreadyRanTodayShowsToday() {
        assertEquals(NOV_24, AuditReportGraph.defaultMorning(la(24, 1, 0), LA, "01:45", LA, true, true, true),
                "a play before the scheduled time reports on today")
    }

    @Test
    void anAuditOffScheduleShowsToday() {
        assertEquals(NOV_24, AuditReportGraph.defaultMorning(la(24, 1, 0), LA, "01:45", LA, false, false, true))
    }

    @Test
    void aPeriodThatHadNotStartedYesterdayShowsToday() {
        // 1 Dec before the run, with 2026-12 asked for: 30 Nov is not one of its mornings.
        assertEquals(LocalDate.of(2026, 12, 1),
                AuditReportGraph.defaultMorning(ZonedDateTime.of(2026, 12, 1, 0, 30, 0, 0, LA), LA, "01:45", LA, true, false, false))
    }

    @Test
    void theScheduledTimeIsReadInTheScheduleZone() {
        // Tenant in IST, schedule 01:45 Los Angeles: 24 Nov's run fires at 01:45 PST on 24 Nov = 15:15 IST.
        ZonedDateTime before = ZonedDateTime.of(2026, 11, 24, 15, 2, 0, 0, IST)
        ZonedDateTime after = ZonedDateTime.of(2026, 11, 24, 15, 20, 0, 0, IST)
        assertEquals(NOV_23, AuditReportGraph.defaultMorning(before, IST, "01:45", LA, true, false, true))
        assertEquals(NOV_24, AuditReportGraph.defaultMorning(after, IST, "01:45", LA, true, false, true))
    }
}
