package darpan.reconciliation.automation

import darpan.peak.AllowanceSpec
import darpan.peak.PeakWindow
import darpan.peak.RunTimeMove
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull

/**
 * DAR-BE-328: the next run an automation page shows. effectiveNextStart is the scheduler's own peakStart, so page and
 * scan agree; peakHoursUntil is set only while now is inside peak hours. Seams only; nothing boots Moqui.
 */
class PeakNextRunTests {
    private static final ZoneId LA = ZoneId.of("America/Los_Angeles")

    @AfterEach
    void resetSeams() {
        AutomationPeakGate.peakWindowsLoader = null
        AutomationPeakGate.tenantZoneLoader = null
        AutomationPeakGate.peakAllowancesLoader = null
    }

    private static Timestamp la(int month, int day, int hour, int minute) {
        Timestamp.from(ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, LA).toInstant())
    }

    private static PeakWindow window() {
        new PeakWindow("P1", "Black Friday sale", LocalDate.of(2026, 11, 24), LocalDate.of(2026, 12, 1),
                LocalTime.of(8, 0), LocalTime.of(23, 0),
                [new RunTimeMove(["100518"] as Set, LocalDate.of(2026, 11, 28), LocalTime.of(6, 0))])
    }

    private static void windows(List<PeakWindow> peakWindows) {
        AutomationPeakGate.peakWindowsLoader = { def ec, String tenantId, LocalDate from -> peakWindows }
        AutomationPeakGate.tenantZoneLoader = { def ec, String tenantId -> LA }
        AutomationPeakGate.peakAllowancesLoader = { def ec, String tenantId -> [:] }
    }

    private static Map automation() {
        [automationId: "100518", companyUserGroupId: "T", nextScheduledFireTime: la(11, 28, 2, 15), lastScheduledFireTime: la(11, 27, 2, 15)]
    }

    private static Map next(Timestamp now) {
        AutomationPeakGate.peakNextRun(null, AutomationPeakGate.newPeakTick(now), automation())
    }

    @Test
    void movedStartIsShownWithItsPeriod() {
        windows([window()])
        Map result = next(la(11, 27, 12, 0))
        assertEquals(la(11, 28, 6, 0), result.effectiveNextStart)
        assertEquals("Black Friday sale", result.peakPeriodName)
        assertEquals(la(11, 27, 23, 0), result.peakHoursUntil)
    }

    @Test
    void noPeriodLeavesTheStoredFire() {
        windows([])
        Map result = next(la(11, 27, 12, 0))
        assertEquals(la(11, 28, 2, 15), result.effectiveNextStart)
        assertNull(result.peakPeriodName)
        assertNull(result.peakHoursUntil)
    }

    @Test
    void peakHoursUntilIsAbsentOutsideThem() {
        windows([window()])
        assertNull(next(la(11, 28, 2, 0)).peakHoursUntil)
        assertEquals(la(11, 28, 23, 0), next(la(11, 28, 10, 0)).peakHoursUntil)
    }

    @Test
    void aMoveAlreadyUsedTodayIsNotShown() {
        windows([window()])
        Map moved = automation() + [lastScheduledFireTime: la(11, 28, 1, 0)]
        Map result = AutomationPeakGate.peakNextRun(null, AutomationPeakGate.newPeakTick(la(11, 28, 3, 0)), moved)
        assertEquals(la(11, 28, 2, 15), result.effectiveNextStart)
        assertNull(result.peakPeriodName)
    }

    @Test
    void aFailingLoaderFailsOpen() {
        AutomationPeakGate.peakWindowsLoader = { def ec, String tenantId, LocalDate from -> throw new IllegalStateException("boom") }
        AutomationPeakGate.tenantZoneLoader = { def ec, String tenantId -> LA }
        AutomationPeakGate.peakAllowancesLoader = { def ec, String tenantId -> [:] }
        Map result = next(la(11, 27, 12, 0))
        assertEquals(la(11, 28, 2, 15), result.effectiveNextStart)
        assertNull(result.peakHoursUntil)
    }

    @Test
    void theSyncLagAllowanceIsShownWithNoPeakPeriod() {
        windows([])
        AutomationPeakGate.peakAllowancesLoader = { def ec, String tenantId -> ["100518": new AllowanceSpec(360, [:])] }
        Map paired = automation() + [windowTimeZone: "America/Los_Angeles"]
        Map result = AutomationPeakGate.peakNextRun(null, AutomationPeakGate.newPeakTick(la(11, 27, 12, 0)), paired)
        assertEquals(la(11, 28, 6, 0), result.effectiveNextStart, "the page shows the scheduler's 06:00, not the 02:15 fire")
        assertNull(result.peakPeriodName)
        assertNull(result.peakHoursUntil)
    }
}
