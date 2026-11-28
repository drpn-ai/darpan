package darpan.reconciliation.automation

import darpan.peak.AllowanceSpec
import darpan.peak.PeakWindow
import darpan.peak.RunTimeMove
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-328: the scheduler starts a moved run at its moved time and holds scheduled runs and retries out of peak hours,
 * and every start it makes still carries the ORIGINAL scheduledFireTime (read window, execution row, retry dedupe).
 * Peak windows and the tenant zone come through the AutomationExecutionSupport seams; nothing here boots Moqui.
 */
class PeakScheduleScanTests {
    private static final ZoneId LA = ZoneId.of("America/Los_Angeles")
    private static final String TENANT = "T"
    private static final String EXECUTE = "reconciliation.ReconciliationAutomationServices.execute#Automation"

    private int loaderCalls = 0
    private List<PeakWindow> windows = []
    private Closure<List<PeakWindow>> loader = { def ec, String tenantId, LocalDate fromDate -> loaderCalls++; windows }

    @AfterEach
    void resetSeams() {
        AutomationPeakGate.peakWindowsLoader = null
        AutomationPeakGate.tenantZoneLoader = null
        AutomationPeakGate.peakAllowancesLoader = null
    }

    private static Timestamp la(int month, int day, int hour, int minute) {
        Timestamp.from(ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, LA).toInstant())
    }

    /** Task 2's window W: Black Friday, Nov 24 - Dec 1, peak hours 08:00-23:00, 100518 moved on Nov 28. */
    private static PeakWindow window(LocalTime moveTo = LocalTime.of(6, 0), LocalTime peakStart = LocalTime.of(8, 0),
                                     LocalTime peakEnd = LocalTime.of(23, 0)) {
        new PeakWindow("P1", "Black Friday", LocalDate.of(2026, 11, 24), LocalDate.of(2026, 12, 1), peakStart, peakEnd,
                [new RunTimeMove(["100518"] as Set, LocalDate.of(2026, 11, 28), moveTo)])
    }

    private FakeEc ec(List<PeakWindow> peakWindows) {
        windows = peakWindows
        AutomationPeakGate.peakWindowsLoader = loader
        AutomationPeakGate.tenantZoneLoader = { def ec, String tenantId -> LA }
        AutomationPeakGate.peakAllowancesLoader = { def ec, String tenantId -> allowanceCalls++; allowances }
        return new FakeEc()
    }

    private int allowanceCalls = 0
    private Map<String, AllowanceSpec> allowances = [:]

    private static PeakWindow hoursOnly(LocalTime peakStart = LocalTime.of(8, 0), LocalTime peakEnd = LocalTime.of(23, 0)) {
        new PeakWindow("P1", "Black Friday", LocalDate.of(2026, 11, 24), LocalDate.of(2026, 12, 1), peakStart, peakEnd, [])
    }

    private static Instant startOf(FakeEc ec, FakeRow automation, Timestamp now) {
        AutomationPeakGate.peakStart(ec, AutomationPeakGate.newPeakTick(now),
                (automation.nextScheduledFireTime as Timestamp).toInstant(), automation.automationId as String, TENANT, true,
                automation.lastScheduledFireTime as Timestamp, automation)
    }

    // --- DAR-BE-328 plan 3: a check with a sync pair waits for its read window's end plus the sync lag allowance ----------

    @Test
    void allowanceDelaysTheDailyCheck() {
        FakeEc ec = ec([])
        allowances = ["100518": new AllowanceSpec(360, [:])]
        FakeRow automation = addAutomation(ec)

        Map early = scan(ec, la(11, 28, 2, 16))

        assertEquals(0, early.dueCount, "the Nov 27 window closed at 00:00; six hours of allowance run to 06:00")
        assertTrue(executed(ec).isEmpty())
        assertEquals(la(11, 28, 2, 15), automation.nextScheduledFireTime, "a run waiting on its allowance keeps its schedule")
        assertEquals(1, allowanceCalls, "allowances load once per tenant per tick")
        assertEquals(la(11, 28, 6, 0).toInstant(), startOf(ec, automation, la(11, 28, 2, 16)))

        scan(ec, la(11, 28, 6, 1))

        assertEquals(1, executed(ec).size())
        assertEquals(la(11, 28, 2, 15), executed(ec)[0].scheduledFireTime, "the read window is the 02:15 one, never 06:00")
        assertEquals(la(11, 29, 2, 15), automation.nextScheduledFireTime)
    }

    @Test
    void allowanceBeatsAnEarlierMove() {
        FakeEc ec = ec([window(LocalTime.of(5, 0))])
        allowances = ["100518": new AllowanceSpec(360, [:])]
        FakeRow automation = addAutomation(ec)

        assertEquals(la(11, 28, 6, 0).toInstant(), startOf(ec, automation, la(11, 28, 1, 0)), "moved to 05:00, allowed from 06:00")

        scan(ec, la(11, 28, 5, 1))
        assertTrue(executed(ec).isEmpty())

        scan(ec, la(11, 28, 6, 1))
        assertEquals(1, executed(ec).size())
        assertEquals(la(11, 28, 2, 15), executed(ec)[0].scheduledFireTime)
    }

    @Test
    void allowanceThenPeakHold() {
        FakeEc ec = ec([hoursOnly()])
        allowances = ["100518": new AllowanceSpec(540, [:])]
        FakeRow automation = addAutomation(ec)

        assertEquals(la(11, 28, 23, 0).toInstant(), startOf(ec, automation, la(11, 28, 1, 0)),
                "allowed from 09:00, which is inside peak hours 08:00-23:00: it waits for 23:00")
    }

    @Test
    void periodAllowanceOverridesNormal() {
        FakeEc ec = ec([new PeakWindow("P1", "Black Friday", LocalDate.of(2026, 11, 24), LocalDate.of(2026, 12, 1), null, null, [])])
        allowances = ["100518": new AllowanceSpec(120, [P1: 360])]
        FakeRow inPeriod = addAutomation(ec)
        assertEquals(la(11, 28, 6, 0).toInstant(), startOf(ec, inPeriod, la(11, 28, 0, 0)), "the period's six hours on Nov 28")

        FakeRow before = addAutomation(ec, "100518", "0 15 2 * * ?", la(11, 23, 2, 15), la(11, 22, 2, 15))
        assertEquals(la(11, 23, 2, 15).toInstant(), startOf(ec, before, la(11, 23, 0, 0)),
                "Nov 23 is before the period: normal two hours, 00:00 + 2h is before the 02:15 fire")
    }

    @Test
    void theDayAfterThePeriodStillWaitsForThePeriodAllowance() {
        FakeEc ec = ec([new PeakWindow("P1", "Black Friday", LocalDate.of(2026, 11, 24), LocalDate.of(2026, 12, 1), null, null, [])])
        allowances = ["100518": new AllowanceSpec(120, [P1: 360])]
        FakeRow dayAfter = addAutomation(ec, "100518", "0 15 2 * * ?", la(12, 2, 2, 15), la(12, 1, 2, 15))

        assertEquals(la(12, 2, 6, 0).toInstant(), startOf(ec, dayAfter, la(12, 2, 0, 0)),
                "the Dec 2 run reads Dec 1, a sale day: the period's six hours, not the normal two")
    }

    @Test
    void thePeriodsFirstDayDoesNotWaitWhenItReadsTheDayBefore() {
        FakeEc ec = ec([new PeakWindow("P1", "Black Friday", LocalDate.of(2026, 11, 24), LocalDate.of(2026, 12, 1), null, null, [])])
        allowances = ["100518": new AllowanceSpec(120, [P1: 360])]
        FakeRow first = addAutomation(ec, "100518", "0 15 2 * * ?", la(11, 24, 2, 15), la(11, 23, 2, 15))

        assertEquals(la(11, 24, 2, 15).toInstant(), startOf(ec, first, la(11, 24, 0, 0)),
                "the Nov 24 run reads Nov 23, a normal day: two hours end at 02:00, before the fire")
    }

    @Test
    void aLongOutageStepsOnlyThroughTheFiresWithinTheAllowanceAndADay() {
        FakeEc ec = ec([])
        allowances = ["100518": new AllowanceSpec(360, [:])]
        FakeRow automation = addAutomation(ec, "100518", "0 15 2 * * ?", la(11, 20, 2, 15), la(11, 19, 2, 15))

        scan(ec, la(11, 28, 6, 1))

        assertEquals(1, executed(ec).size())
        assertEquals(la(11, 20, 2, 15), executed(ec)[0].scheduledFireTime)
        assertEquals(la(11, 27, 2, 15), automation.nextScheduledFireTime,
                "now minus (six hours + one day) is Nov 27 00:01: the fires of Nov 21-26 are skipped, not run one per tick")
    }

    @Test
    void noPairNoChange() {
        FakeEc ec = ec([])
        allowances = ["OTHER": new AllowanceSpec(360, [:])]
        FakeRow automation = addAutomation(ec)

        assertEquals(la(11, 28, 2, 15).toInstant(), startOf(ec, automation, la(11, 28, 1, 0)))
        scan(ec, la(11, 28, 2, 16))
        assertEquals(1, executed(ec).size())
    }

    @Test
    void aLongAllowanceNeverSkipsADay() {
        FakeEc ec = ec([])
        allowances = ["100518": new AllowanceSpec(1620, [:])]
        FakeRow automation = addAutomation(ec)

        scan(ec, la(11, 29, 2, 16))
        assertTrue(executed(ec).isEmpty(), "Nov 28's window closed at Nov 28 00:00; 27 hours run to Nov 29 03:00")

        scan(ec, la(11, 29, 3, 1))
        assertEquals(1, executed(ec).size())
        assertEquals(la(11, 28, 2, 15), executed(ec)[0].scheduledFireTime)
        assertEquals(la(11, 29, 2, 15), automation.nextScheduledFireTime, "the next fire is Nov 29's, already past, not Nov 30's")

        scan(ec, la(11, 29, 3, 6))
        assertEquals(1, executed(ec).size(), "Nov 29's fire waits for its own bound, Nov 30 03:00: no catch-up burst")

        scan(ec, la(11, 30, 3, 1))
        assertEquals(2, executed(ec).size())
        assertEquals(la(11, 29, 2, 15), executed(ec)[1].scheduledFireTime, "Nov 29's window is read too")
        assertEquals(la(11, 30, 2, 15), automation.nextScheduledFireTime)
    }

    @Test
    void noPairStillSkipsPastNow() {
        FakeEc ec = ec([])
        FakeRow automation = addAutomation(ec)

        scan(ec, la(11, 30, 5, 0))

        assertEquals(1, executed(ec).size())
        assertEquals(la(11, 28, 2, 15), executed(ec)[0].scheduledFireTime)
        assertEquals(la(12, 1, 2, 15), automation.nextScheduledFireTime, "without a pair a late run still skips the fires already past")
    }

    @Test
    void aFailingAllowanceLoaderStartsRunsAtTheirNormalTime() {
        FakeEc ec = ec([])
        AutomationPeakGate.peakAllowancesLoader = { def e, String t -> throw new IllegalStateException("boom") }
        addAutomation(ec)

        scan(ec, la(11, 28, 2, 16))

        assertEquals(1, executed(ec).size(), "an allowance bug never stops scheduling")
    }

    /** 100518: daily 02:15 Los Angeles, waiting on the Nov 28 02:15 fire. */
    private static FakeRow addAutomation(FakeEc ec, String automationId = "100518", String scheduleExpr = "0 15 2 * * ?",
                                         Timestamp next = la(11, 28, 2, 15), Timestamp last = la(11, 27, 2, 15)) {
        return ec.add("darpan.reconciliation.ReconciliationAutomation", [
                automationId         : automationId,
                automationName       : automationId,
                companyUserGroupId   : TENANT,
                inputModeEnumId      : AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE,
                scheduleExpr         : scheduleExpr,
                windowTimeZone       : "America/Los_Angeles",
                isActive             : "Y",
                nextScheduledFireTime: next,
                lastScheduledFireTime: last,
        ])
    }

    private static Map scan(FakeEc ec, Timestamp now) {
        return AutomationExecutionSupport.scanDueAutomations(ec, [nowTimestamp: now, limit: 100])
    }

    private static List<Map> executed(FakeEc ec) { ec.service.calls.findAll { it.name == EXECUTE }*.params }

    @Test
    void noPeriodScanBehavesAsBefore() {
        FakeEc ec = ec([])
        FakeRow automation = addAutomation(ec)

        Map result = scan(ec, la(11, 28, 2, 16))

        assertEquals(1, result.dueCount)
        assertEquals(1, executed(ec).size())
        assertEquals(la(11, 28, 2, 15), executed(ec)[0].scheduledFireTime)
        assertEquals(la(11, 28, 2, 15), automation.lastScheduledFireTime)
        assertEquals(la(11, 29, 2, 15), automation.nextScheduledFireTime)
    }

    @Test
    void movedLaterIsNotDueBeforeTheMovedTime() {
        FakeEc ec = ec([window()])
        FakeRow automation = addAutomation(ec)

        Map result = scan(ec, la(11, 28, 2, 16))

        assertEquals(0, result.dueCount)
        assertTrue(executed(ec).isEmpty())
        assertEquals(la(11, 28, 2, 15), automation.nextScheduledFireTime, "a run waiting on its moved time keeps its schedule")
        assertEquals(la(11, 27, 2, 15), automation.lastScheduledFireTime)
    }

    @Test
    void movedLaterRunsWithOriginalFireTime() {
        FakeEc ec = ec([window()])
        FakeRow automation = addAutomation(ec)

        scan(ec, la(11, 28, 6, 1))

        assertEquals(1, executed(ec).size())
        assertEquals(la(11, 28, 2, 15), executed(ec)[0].scheduledFireTime, "the read window is the 02:15 one, never 06:00")
        assertEquals(la(11, 29, 2, 15), automation.nextScheduledFireTime)
    }

    @Test
    void movedEarlierRunsOnceAndNextFireIsTomorrow() {
        FakeEc ec = ec([window(LocalTime.of(1, 0))])
        FakeRow automation = addAutomation(ec)

        scan(ec, la(11, 28, 1, 1))

        assertEquals(1, executed(ec).size())
        assertEquals(la(11, 28, 2, 15), executed(ec)[0].scheduledFireTime)
        assertEquals(la(11, 28, 2, 15), automation.lastScheduledFireTime)
        assertEquals(la(11, 29, 2, 15), automation.nextScheduledFireTime)

        scan(ec, la(11, 28, 2, 16))

        assertEquals(1, executed(ec).size(), "the 02:15 fire already ran at 01:00; 02:16 starts nothing")
    }

    @Test
    void heldRetryWaitsForPeakEnd() {
        FakeEc ec = ec([window()])
        FakeRow row = ec.add("darpan.reconciliation.ReconciliationAutomationExecution", [
                automationExecutionId: "EXEC_1",
                automationId         : "100518",
                companyUserGroupId   : TENANT,
                statusEnumId         : AutomationExecutionSupport.STATUS_PENDING,
                retryCount           : 0,
                maxRetryCount        : 3,
                scheduledDate        : la(11, 27, 2, 15),
                nextRetryDate        : la(11, 27, 9, 0),
        ])

        Map held = scan(ec, la(11, 27, 9, 1))

        assertTrue(executed(ec).isEmpty(), "a retry inside peak hours waits")
        assertEquals(0, row.retryCount, "a held retry is not claimed: no retry is burnt on it")
        assertEquals(la(11, 27, 9, 0), row.nextRetryDate)
        assertEquals(AutomationExecutionSupport.STATUS_PENDING, row.statusEnumId)
        assertEquals(la(11, 27, 23, 0), held.retryResults[0].peakHeldUntil)

        scan(ec, la(11, 27, 23, 1))

        assertEquals(1, executed(ec).size())
        assertEquals(la(11, 27, 2, 15), executed(ec)[0].scheduledFireTime, "the retry re-reads the original window")
        assertEquals(1, row.retryCount)
    }

    @Test
    void deletingThePeriodReleasesTheHeldRun() {
        FakeEc ec = ec([window()])
        FakeRow automation = addAutomation(ec)

        Map first = scan(ec, la(11, 28, 10, 0))

        assertTrue(executed(ec).isEmpty(), "10:00 is inside peak hours: the moved run waits for 23:00")
        assertEquals(la(11, 28, 2, 15), automation.nextScheduledFireTime)
        assertEquals(1, first.peakHeldCount)

        windows = []
        scan(ec, la(11, 28, 10, 5))

        assertEquals(1, executed(ec).size())
        assertEquals(la(11, 28, 2, 15), executed(ec)[0].scheduledFireTime)
    }

    @Test
    void loaderCalledOncePerTenantPerTick() {
        FakeEc ec = ec([window()])
        addAutomation(ec, "100518")
        addAutomation(ec, "100520")
        ec.add("darpan.reconciliation.ReconciliationAutomationExecution", [
                automationExecutionId: "EXEC_2", automationId: "100521", companyUserGroupId: TENANT,
                statusEnumId: AutomationExecutionSupport.STATUS_PENDING, retryCount: 0, maxRetryCount: 3,
                scheduledDate: la(11, 28, 2, 15), nextRetryDate: la(11, 28, 6, 0),
        ])

        scan(ec, la(11, 28, 6, 1))

        assertEquals(1, loaderCalls, "the scan and its retry pass share one load per tenant per tick")
        assertEquals(["100518", "100520", "100521"] as Set, executed(ec)*.automationId as Set)

        scan(ec, la(11, 28, 6, 6))
        assertEquals(2, loaderCalls, "nothing is kept across ticks: a changed period applies on the next one")
    }

    @Test
    void aFailingPeakLoaderStartsRunsAtTheirNormalTime() {
        FakeEc ec = ec([])
        AutomationPeakGate.peakWindowsLoader = { def e, String t, LocalDate d -> throw new IllegalStateException("boom") }
        addAutomation(ec)

        scan(ec, la(11, 28, 2, 16))

        assertEquals(1, executed(ec).size(), "a peak-period bug never stops scheduling")
        assertEquals(la(11, 28, 2, 15), executed(ec)[0].scheduledFireTime)
    }

    @Test
    void aMoveOnlyMovesTheFirstFireOfItsDate() {
        // An hourly automation moved to 06:00 on Nov 28: the 00:00 fire waits for 06:00, but once it has run, the 07:00
        // fire must not also start at "06:00" (and the 08:00 one after it): that would start a run every tick.
        FakeEc ec = ec([window(LocalTime.of(6, 0), null, null)])
        FakeRow automation = addAutomation(ec, "100518", "0 0 * * * ?", la(11, 28, 7, 0), la(11, 28, 0, 0))

        scan(ec, la(11, 28, 6, 5))

        assertTrue(executed(ec).isEmpty())
        assertEquals(la(11, 28, 7, 0), automation.nextScheduledFireTime)
    }

    @Test
    void movedEarlierNeverStartsBeforeItsReadWindowCloses() {
        // windowTimeZone London, tenant Los Angeles: the 02:15 London fire of Nov 28 is 18:15 LA on Nov 27 and reads
        // Nov 27 London, which closes at 16:00 LA. A move to 06:00 LA on Nov 27 must wait for 16:00, or the run reads ten
        // hours of orders still to come and never checks them.
        PeakWindow w = new PeakWindow("P1", "Black Friday", LocalDate.of(2026, 11, 24), LocalDate.of(2026, 12, 1), null, null,
                [new RunTimeMove(["100518"] as Set, LocalDate.of(2026, 11, 27), LocalTime.of(6, 0))])
        FakeEc ec = ec([w])
        Timestamp fire = london(11, 28, 2, 15)
        FakeRow automation = addAutomation(ec, "100518", "0 15 2 * * ?", fire, london(11, 27, 2, 15))
        automation.windowTimeZone = "Europe/London"
        Timestamp windowEnd = london(11, 28, 0, 0)
        assertEquals(la(11, 27, 16, 0), windowEnd)

        scan(ec, la(11, 27, 6, 1))

        assertTrue(executed(ec).isEmpty(), "06:00 LA is before the London read window closes")
        assertEquals(windowEnd.toInstant(), AutomationPeakGate.peakStart(ec, AutomationPeakGate.newPeakTick(la(11, 27, 6, 1)),
                fire.toInstant(), "100518", TENANT, true, automation.lastScheduledFireTime as Timestamp, automation))

        scan(ec, la(11, 27, 16, 1))

        assertEquals(1, executed(ec).size())
        assertEquals(fire, executed(ec)[0].scheduledFireTime, "it still reads the fire's own window")
    }

    private static Timestamp london(int month, int day, int hour, int minute) {
        Timestamp.from(ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, ZoneId.of("Europe/London")).toInstant())
    }

    @Test
    void resolveCandidateFireTimeAnswersTheStoredFireBeforeAndAtItsTime() {
        Map automation = [scheduleExpr: "0 15 2 * * ?", windowTimeZone: "America/Los_Angeles",
                          nextScheduledFireTime: la(11, 28, 2, 15)]
        assertEquals(la(11, 28, 2, 15), AutomationWindows.resolveCandidateFireTime(automation, la(11, 28, 1, 0)))
        assertEquals(la(11, 28, 2, 15), AutomationWindows.resolveCandidateFireTime(automation, la(11, 28, 2, 15)))
    }

    // --- a minimal Moqui stand-in: entity finds by equality, an async service recorder --------------------------------

    private static class FakeEc {
        FakeEntity entity = new FakeEntity()
        FakeService service = new FakeService()
        Object transaction = null
        Map user = [userId: "tester"]

        FakeRow add(String entityName, Map fields) {
            FakeRow row = new FakeRow(fields)
            entity.rows[entityName] << row
            return row
        }
    }

    private static class FakeEntity {
        Map<String, List<FakeRow>> rows = [:].withDefault { [] }

        FakeFind find(String entityName) { new FakeFind(rows: rows[entityName]) }
    }

    private static class FakeFind {
        List<FakeRow> rows
        Map<String, Object> conditions = [:]

        FakeFind condition(String field, Object value) { conditions[field] = value; this }

        FakeFind disableAuthz() { this }

        FakeFind useCache(boolean ignored) { this }

        List<FakeRow> list() { rows.findAll { row -> conditions.every { k, v -> row[k] == v } } }

        long count() { list().size() }

        FakeRow one() { list().find() }
    }

    private static class FakeRow extends LinkedHashMap<String, Object> {
        FakeRow(Map fields) { super(fields) }

        FakeRow set(String field, Object value) { put(field, value); this }

        FakeRow update() { this }
    }

    private static class FakeService {
        List<FakeCall> calls = []

        FakeCall async() { new FakeCall(service: this) }
    }

    private static class FakeCall {
        FakeService service
        String name
        Map params

        FakeCall name(String name) { this.name = name; this }

        FakeCall parameters(Map params) { this.params = params; this }

        FakeCall disableAuthz() { this }

        void call() { service.calls << this }
    }
}
