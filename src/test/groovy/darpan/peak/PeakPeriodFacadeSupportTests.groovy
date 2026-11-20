package darpan.peak

import org.junit.jupiter.api.Test

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

import static org.junit.jupiter.api.Assertions.assertEquals

class PeakPeriodFacadeSupportTests {
    private static final ZoneId LA = ZoneId.of('America/Los_Angeles')

    private static Map period(String name, String from, String thru, String start = null, String end = null, String active = 'Y') {
        [periodName: name, fromDate: LocalDate.parse(from), thruDate: LocalDate.parse(thru), peakHoursStart: start,
         peakHoursEnd: end, isActive: active]
    }

    private static final Map BLACK_FRIDAY = period('Black Friday sale', '2026-11-24', '2026-12-01')

    private static PeakWindow window(String start = '08:00', String end = '23:00') {
        new PeakWindow('P1', 'Black Friday sale', LocalDate.of(2026, 11, 24), LocalDate.of(2026, 12, 1),
                LocalTime.parse(start), LocalTime.parse(end), [])
    }

    private static Map automation(String id, String cron, Map extra = [:]) {
        [automationId: id, label: "Run ${id}".toString(), scheduleExpr: cron, windowTimeZone: 'America/Los_Angeles'] + extra
    }

    @Test
    void overlapRefusedInclusive() {
        assertEquals(['Overlaps Black Friday sale (Nov 24 – Dec 1).'],
                PeakPeriodFacadeSupport.validatePeriod(period('Cyber', '2026-12-01', '2026-12-03'), [BLACK_FRIDAY]))
    }

    @Test
    void adjacentPeriodsAllowed() {
        assertEquals([], PeakPeriodFacadeSupport.validatePeriod(period('Cyber', '2026-12-02', '2026-12-03'), [BLACK_FRIDAY]))
    }

    @Test
    void inactiveOtherPeriodIgnored() {
        Map off = period('Black Friday sale', '2026-11-24', '2026-12-01', null, null, 'N')
        assertEquals([], PeakPeriodFacadeSupport.validatePeriod(period('Cyber', '2026-12-01', '2026-12-03'), [off]))
    }

    @Test
    void endBeforeStartRefused() {
        assertEquals(['The end date is before the start date.'],
                PeakPeriodFacadeSupport.validatePeriod(period('Cyber', '2026-12-03', '2026-12-01'), []))
    }

    @Test
    void nameAndDatesRequired() {
        assertEquals(['Name is required.'], PeakPeriodFacadeSupport.validatePeriod(period(' ', '2026-12-01', '2026-12-03'), []))
        assertEquals(['Dates are required.'],
                PeakPeriodFacadeSupport.validatePeriod([periodName: 'X', fromDate: null, thruDate: LocalDate.of(2026, 12, 3)], []))
    }

    @Test
    void halfPeakHoursRefused() {
        assertEquals(['Set both peak-hour times, or neither.'],
                PeakPeriodFacadeSupport.validatePeriod(period('Cyber', '2026-12-01', '2026-12-03', '08:00', null), []))
    }

    @Test
    void equalPeakHoursRefused() {
        assertEquals(['Peak hours cannot start and end at the same time.'],
                PeakPeriodFacadeSupport.validatePeriod(period('Cyber', '2026-12-01', '2026-12-03', '08:00', '08:00'), []))
    }

    @Test
    void malformedPeakHoursRefused() {
        assertEquals(['Time must be HH:mm.'],
                PeakPeriodFacadeSupport.validatePeriod(period('Cyber', '2026-12-01', '2026-12-03', '8am', '23:00'), []))
    }

    @Test
    void runTimeNeedsExactlyOneTarget() {
        Set<String> targets = ['AUTO1', 'AUD1'] as Set
        assertEquals(['Choose a run.'], PeakPeriodFacadeSupport.validateRunTime([runTime: '06:00'], BLACK_FRIDAY, targets))
        assertEquals(['Choose one run, not both.'],
                PeakPeriodFacadeSupport.validateRunTime([automationId: 'AUTO1', auditId: 'AUD1', runTime: '06:00'], BLACK_FRIDAY, targets))
        assertEquals(['Choose a run.'],
                PeakPeriodFacadeSupport.validateRunTime([automationId: 'OTHER_TENANT', runTime: '06:00'], BLACK_FRIDAY, targets))
        assertEquals([], PeakPeriodFacadeSupport.validateRunTime([automationId: 'AUTO1', runTime: '06:00'], BLACK_FRIDAY, targets))
        assertEquals([], PeakPeriodFacadeSupport.validateRunTime([auditId: 'AUD1', runTime: '06:00'], BLACK_FRIDAY, targets))
    }

    @Test
    void runTimeDateOutsidePeriodRefused() {
        Set<String> targets = ['AUTO1'] as Set
        assertEquals(['That date is outside this period.'], PeakPeriodFacadeSupport.validateRunTime(
                [automationId: 'AUTO1', onDate: LocalDate.of(2026, 12, 2), runTime: '06:00'], BLACK_FRIDAY, targets))
        assertEquals([], PeakPeriodFacadeSupport.validateRunTime(
                [automationId: 'AUTO1', onDate: LocalDate.of(2026, 12, 1), runTime: '06:00'], BLACK_FRIDAY, targets))
    }

    @Test
    void runTimeMustBeHhMm() {
        Set<String> targets = ['AUTO1'] as Set
        assertEquals(['Time must be HH:mm.'], PeakPeriodFacadeSupport.validateRunTime([automationId: 'AUTO1'], BLACK_FRIDAY, targets))
        assertEquals(['Time must be HH:mm.'],
                PeakPeriodFacadeSupport.validateRunTime([automationId: 'AUTO1', runTime: '6am'], BLACK_FRIDAY, targets))
        assertEquals(['Time must be HH:mm.'],
                PeakPeriodFacadeSupport.validateRunTime([automationId: 'AUTO1', runTime: '25:00'], BLACK_FRIDAY, targets))
    }

    @Test
    void heldRunsListsDailyRunInsidePeakHours() {
        List<Map> rows = PeakPeriodFacadeSupport.heldRuns([automation('A1', '0 0 10 * * ?')], window(), LA)
        assertEquals([[targetLabel: 'Run A1', normalTime: '10:00', startsAt: '23:00', reason: '10:00 is in peak hours']], rows)
    }

    @Test
    void heldRunsFoldsAuditChecksToOneRow() {
        Map audit = [auditId: 'AUD1', auditName: 'Month close']
        List<Map> rows = PeakPeriodFacadeSupport.heldRuns([
                automation('C1', '0 0 12 * * ?', audit), automation('C2', '0 0 12 * * ?', audit)], window(), LA)
        assertEquals(1, rows.size())
        assertEquals('Month close', rows[0].targetLabel)
        assertEquals('12:00', rows[0].normalTime)
    }

    @Test
    void runOutsidePeakHoursNotListed() {
        assertEquals([], PeakPeriodFacadeSupport.heldRuns([automation('A1', '0 15 2 * * ?')], window(), LA))
    }

    @Test
    void noPeakHoursHoldsNothing() {
        PeakWindow none = new PeakWindow('P1', 'x', LocalDate.of(2026, 11, 24), LocalDate.of(2026, 12, 1), null, null, [])
        assertEquals([], PeakPeriodFacadeSupport.heldRuns([automation('A1', '0 0 10 * * ?')], none, LA))
    }
}
