package darpan.admin

import darpan.facade.reconciliation.RunHealth
import org.junit.jupiter.api.Test

import java.sql.Timestamp
import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-ADMIN-008. The warning rules behind the admin live board, pinned without any entity stubs.
 *
 * The rule that matters most is the one that is easy to get wrong: heartbeats only tick inside the
 * extract stages, so a long COMPARE with a silent heartbeat is NOT stuck. Those cases come first.
 */
class LiveOperationsRulesTests {

    private static final Timestamp NOW = ts("2026-10-27T10:00:00Z")

    private static Timestamp ts(String isoInstant) { return Timestamp.from(Instant.parse(isoInstant)) }

    private static Timestamp minutesAgo(int minutes) { return new Timestamp(NOW.time - minutes * 60_000L) }

    // ---- run health ----

    @Test
    void longCompareWithASilentHeartbeatIsHealthyWhenItHasNoHistoryToCompareAgainst() {
        Map health = RunHealth.of([currentStage: "COMPARE", startedDate: minutesAgo(50),
                                                    lastHeartbeatDate: minutesAgo(40)], NOW)
        assertEquals("healthy", health.health)
    }

    @Test
    void extractThatStoppedReportingProgressIsStuck() {
        Map health = RunHealth.of([currentStage: "EXTRACT_FILE2", startedDate: minutesAgo(30),
                                                    lastHeartbeatDate: minutesAgo(16)], NOW)
        assertEquals("stuck", health.health)
        assertTrue(health.reason.contains("16m"), health.reason as String)
    }

    @Test
    void extractStillTickingIsHealthy() {
        Map health = RunHealth.of([currentStage: "EXTRACT_FILE1", startedDate: minutesAgo(30),
                                                    lastHeartbeatDate: minutesAgo(1)], NOW)
        assertEquals("healthy", health.health)
    }

    @Test
    void anyStageSilentForNinetyMinutesIsStuckBecauseTheReaperIsAboutToFailIt() {
        Map health = RunHealth.of([currentStage: "COMPARE", startedDate: minutesAgo(100),
                                                    lastHeartbeatDate: minutesAgo(95)], NOW)
        assertEquals("stuck", health.health)
        assertTrue(health.reason.contains("120m"), health.reason as String)
    }

    @Test
    void runTakingTwiceAsLongAsItsLastSuccessIsSlow() {
        Map health = RunHealth.of([currentStage: "COMPARE", startedDate: minutesAgo(40),
                                                    lastHeartbeatDate: minutesAgo(30), usualDurationMs: 15 * 60_000L], NOW)
        assertEquals("slow", health.health)
        assertTrue(health.reason.contains("15m"), health.reason as String)
    }

    @Test
    void shortRunIsNeverSlowEvenAtManyTimesItsUsualDuration() {
        // 2 minutes against a 30-second history is 4x, but nobody needs a warning for a 2-minute run.
        Map health = RunHealth.of([currentStage: "COMPARE", startedDate: minutesAgo(2),
                                                    lastHeartbeatDate: minutesAgo(2), usualDurationMs: 30_000L], NOW)
        assertEquals("healthy", health.health)
    }

    @Test
    void cancelNotHonouredAfterFiveMinutesIsSlow() {
        Map health = RunHealth.of([currentStage: "COMPARE", startedDate: minutesAgo(20),
                                                    lastHeartbeatDate: minutesAgo(10), cancelRequestedDate: minutesAgo(6)], NOW)
        assertEquals("slow", health.health)
        assertTrue(health.reason.toLowerCase().contains("cancel"), health.reason as String)
    }

    // ---- failures ----

    @Test
    void repeatedFailuresOfOneRunCollapseIntoOneCriticalWarningCarryingTheLatestError() {
        List warnings = LiveOperationsRules.failureWarnings([
                [tenantUserGroupId: "RAILS", tenantLabel: "Rails", savedRunId: "RS_1", name: "Orders vs OMS",
                 completedDate: minutesAgo(120), errorMessage: "older"],
                [tenantUserGroupId: "RAILS", tenantLabel: "Rails", savedRunId: "RS_1", name: "Orders vs OMS",
                 completedDate: minutesAgo(10), errorMessage: "OMS 504"],
        ], 24)
        assertEquals(1, warnings.size())
        assertEquals("critical", warnings[0].severity)
        assertEquals("RUN_FAILED", warnings[0].code)
        assertTrue(warnings[0].detail.contains("2 failures"), warnings[0].detail as String)
        assertTrue(warnings[0].detail.contains("OMS 504"), warnings[0].detail as String)
    }

    // ---- overload ----

    @Test
    void slotsAtCapWarn() {
        assertEquals(["SLOTS_AT_CAP"], LiveOperationsRules.slotWarnings(4, 4)*.code)
        assertEquals([], LiveOperationsRules.slotWarnings(3, 4))
    }

    @Test
    void overdueAutomationsWarnAtTenMinutesAndGoCriticalAtThirty() {
        // One scan tick is 5 minutes, so lateness under 10 is the scheduler working normally.
        assertEquals([], LiveOperationsRules.overdueWarnings([[name: "A", lateMs: 9 * 60_000L]]))
        assertEquals(["warning"], LiveOperationsRules.overdueWarnings([[name: "A", lateMs: 12 * 60_000L]])*.severity)
        List critical = LiveOperationsRules.overdueWarnings([[name: "A", lateMs: 12 * 60_000L],
                                                             [name: "B", lateMs: 31 * 60_000L]])
        assertEquals(["critical"], critical*.severity)
        assertTrue(critical[0].title.contains("2 automations"), critical[0].title as String)
    }

    @Test
    void systemWarningsFollowTheThresholds() {
        Map healthySystem = [heapAfterGcPercent: 60, processCpuPercent: 40, diskFreePercent: 50,
                             workerPool: [active: 2, max: 16, queued: 0]]
        assertEquals([], LiveOperationsRules.systemWarnings(healthySystem))

        Map hot = [heapAfterGcPercent: 92, processCpuPercent: 86, diskFreePercent: 4,
                   workerPool: [active: 16, max: 16, queued: 7]]
        Map<String, String> bySeverity = LiveOperationsRules.systemWarnings(hot).collectEntries { [(it.code): it.severity] }
        assertEquals([HEAP_HIGH: "critical", CPU_HIGH: "warning", DISK_LOW: "critical", WORKER_POOL_SATURATED: "warning"],
                bySeverity)
    }

    @Test
    void unreadableSystemFiguresRaiseNothing() {
        assertEquals([], LiveOperationsRules.systemWarnings([heapAfterGcPercent: null, processCpuPercent: null,
                                                             diskFreePercent: null, workerPool: null]))
    }

    @Test
    void criticalWarningsSortAheadOfWarningsThenOldestFirst() {
        List sorted = LiveOperationsRules.sortWarnings([
                [severity: "warning", code: "W_OLD", since: minutesAgo(50)],
                [severity: "critical", code: "C_NEW", since: minutesAgo(1)],
                [severity: "critical", code: "C_OLD", since: minutesAgo(30)],
                [severity: "warning", code: "W_NONE"],
        ])
        assertEquals(["C_OLD", "C_NEW", "W_OLD", "W_NONE"], sorted*.code)
    }
}
