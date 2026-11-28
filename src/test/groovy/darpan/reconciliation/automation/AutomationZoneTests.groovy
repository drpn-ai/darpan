package darpan.reconciliation.automation

import darpan.reconciliation.support.FakeEntityFind
import org.junit.jupiter.api.Test

import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneId

import static org.junit.jupiter.api.Assertions.assertEquals

/**
 * DAR-OPS-029 S99-020: an automation's zone is its own windowTimeZone, else its TENANT's zone (the standing rule), never a
 * silent UTC. Every reader (windows, fire times, peak gate, quiet-day line, error events, retries, the admin roster, the
 * streak, the backtest) goes through AutomationWindows.automationZone. No Moqui: the tenant setting is a FakeEntityFind row.
 */
class AutomationZoneTests {

    private static Map ecWithTenantZone(String tenantId, String zone) {
        List<Map> rows = [[companyUserGroupId: tenantId, timeZone: zone]]
        return [entity: [find: { String name -> new FakeEntityFind(["companyUserGroupId"], rows) }]]
    }

    @Test
    void theAutomationsOwnZoneWins() {
        Map ec = ecWithTenantZone("T1", "Asia/Kolkata")
        assertEquals(ZoneId.of("America/New_York"),
                AutomationWindows.automationZone(ec, [windowTimeZone: "America/New_York", companyUserGroupId: "T1"]))
    }

    @Test
    void noZoneReadsTheTenantsZoneNotUtc() {
        Map ec = ecWithTenantZone("T1", "Asia/Kolkata")
        assertEquals(ZoneId.of("Asia/Kolkata"), AutomationWindows.automationZone(ec, [windowTimeZone: null, companyUserGroupId: "T1"]))
        assertEquals(ZoneId.of("Asia/Kolkata"), AutomationWindows.automationZone(ec, [windowTimeZone: "  ", companyUserGroupId: "T1"]))
    }

    @Test
    void aStoredValueThatIsNotAZoneAlsoReadsTheTenantsZone() {
        Map ec = ecWithTenantZone("T1", "Asia/Kolkata")
        assertEquals(ZoneId.of("Asia/Kolkata"), AutomationWindows.automationZone(ec, [windowTimeZone: "Not/AZone", companyUserGroupId: "T1"]))
    }

    @Test
    void noZoneAndNoTenantIsUtc() {
        Map ec = ecWithTenantZone("T1", "Asia/Kolkata")
        assertEquals(ZoneId.of("UTC"), AutomationWindows.automationZone(ec, [windowTimeZone: null]))
    }

    /**
     * DAR-OPS-029 S99-017: a daily duration schedule (P1D) keeps its local wall time across a DST change, as cron does; an
     * hourly one (PT1H) stays exact elapsed time. Los Angeles leaves DST on 2026-11-01.
     */
    @Test
    void aDailyDurationKeepsLocalWallTimeAcrossDst() {
        Map automation = [scheduleExpr: "P1D", windowTimeZone: "America/Los_Angeles", companyUserGroupId: "T1"]
        Timestamp sat9am = Timestamp.from(Instant.parse("2026-10-31T16:00:00Z"))      // 09:00 PDT
        Timestamp next = AutomationWindows.resolveNextScheduledFireTime(automation, sat9am, sat9am)
        assertEquals(Instant.parse("2026-11-01T17:00:00Z"), next.toInstant(), "09:00 PST, not 08:00")

        Timestamp last = AutomationWindows.resolveLastDurationFireTime("P1D", sat9am,
                Timestamp.from(Instant.parse("2026-11-01T17:30:00Z")), ZoneId.of("America/Los_Angeles"))
        assertEquals(Instant.parse("2026-11-01T17:00:00Z"), last.toInstant())

        Map hourly = [scheduleExpr: "PT1H", windowTimeZone: "America/Los_Angeles", companyUserGroupId: "T1"]
        Timestamp beforeFallBack = Timestamp.from(Instant.parse("2026-11-01T08:30:00Z"))   // 01:30 PDT
        assertEquals(Instant.parse("2026-11-01T09:30:00Z"),
                AutomationWindows.resolveNextScheduledFireTime(hourly, beforeFallBack, beforeFallBack).toInstant(), "hourly stays exact")
    }
}
