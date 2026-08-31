package darpan.reconciliation.automation

import org.junit.jupiter.api.Test

import java.sql.Timestamp
import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertEquals

/**
 * Projection of an automation's schedule forward across a window, for the admin schedule board.
 *
 * These tests pin the board to the SAME resolver the scanner uses
 * (AutomationExecutionSupport.resolveNextScheduledFireTime, called at line 717 of the scan path),
 * so a projected fire time and the time the scanner actually advances to cannot diverge.
 */
class SchedulePlannerTests {

    private static Timestamp ts(String isoInstant) { return Timestamp.from(Instant.parse(isoInstant)) }

    @Test
    void projectsHourlyDurationScheduleAcrossTheWindow() {
        Map automation = [scheduleExpr         : "PT1H",
                          windowTimeZone       : "UTC",
                          nextScheduledFireTime: ts("2026-09-01T15:00:00Z")]

        List<Timestamp> fires = SchedulePlanner.projectFireTimes(automation,
                ts("2026-09-01T14:30:00Z"), ts("2026-09-01T18:00:00Z"), 50)

        assertEquals([ts("2026-09-01T15:00:00Z"), ts("2026-09-01T16:00:00Z"),
                      ts("2026-09-01T17:00:00Z"), ts("2026-09-01T18:00:00Z")], fires)
    }

    @Test
    void submitsAtTheNextScanTickWhenTheFireTimeIsOffGrid() {
        // scan_ReconciliationAutomations_5m runs "0 0/5 * * * ?" (ReconciliationJobSeedData.xml),
        // so an automation due at 15:02 is not submitted until the 15:05 tick. Rendering 15:02 on
        // the board would sell operators a minute-precision the scheduler does not have.
        assertEquals(ts("2026-09-01T15:05:00Z"), SchedulePlanner.submitsAt(ts("2026-09-01T15:02:00Z")))
    }

    @Test
    void projectsDailyCronScheduleOncePerDay() {
        Map automation = [scheduleExpr         : "0 0 2 * * ?",
                          windowTimeZone       : "UTC",
                          nextScheduledFireTime: ts("2026-09-01T02:00:00Z")]

        List<Timestamp> fires = SchedulePlanner.projectFireTimes(automation,
                ts("2026-09-01T00:00:00Z"), ts("2026-09-03T12:00:00Z"), 50)

        assertEquals([ts("2026-09-01T02:00:00Z"), ts("2026-09-02T02:00:00Z"),
                      ts("2026-09-03T02:00:00Z")], fires)
    }

    @Test
    void holdsLocalClockTimeAcrossADaylightSavingChange() {
        // US DST ends 2026-11-01. A 06:00 America/Los_Angeles automation stays at 06:00 LOCAL,
        // which means its UTC instant shifts 13:00 -> 14:00. This is what the board's "set in"
        // column exists to explain: two tenants an hour apart today may collide tomorrow.
        Map automation = [scheduleExpr         : "0 0 6 * * ?",
                          windowTimeZone       : "America/Los_Angeles",
                          nextScheduledFireTime: ts("2026-10-31T13:00:00Z")]

        List<Timestamp> fires = SchedulePlanner.projectFireTimes(automation,
                ts("2026-10-31T00:00:00Z"), ts("2026-11-02T23:00:00Z"), 50)

        assertEquals([ts("2026-10-31T13:00:00Z"), ts("2026-11-01T14:00:00Z"),
                      ts("2026-11-02T14:00:00Z")], fires)
    }

    @Test
    void projectsNothingForAnAutomationWithNoScheduleExpression() {
        Map automation = [scheduleExpr: null, windowTimeZone: "UTC", nextScheduledFireTime: null]

        assertEquals([], SchedulePlanner.projectFireTimes(automation,
                ts("2026-09-01T00:00:00Z"), ts("2026-09-02T00:00:00Z"), 50))
    }
}
