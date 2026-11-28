package darpan.admin

import darpan.admin.AdminTestStubs.MessageFacadeStub
import darpan.admin.AdminTestStubs.UserStub
import darpan.peak.PeakWindow
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.RunAdmission
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-ADMIN-008. The admin live board's assembly: which rows it reads, how it names and classifies
 * them, and that a failed source is reported as unavailable instead of rendering as zero.
 * Thresholds are LiveOperationsRulesTests' job; these tests only check the rules are wired.
 */
class AdminLiveOperationsSupportTests {

    private static final Timestamp NOW = ts("2026-10-27T10:00:00Z")
    private static final Map QUIET_SYSTEM = [heapAfterGcPercent: 40, processCpuPercent: 10, diskFreePercent: 60,
                                             workerPool: [active: 1, max: 16, queued: 0]]

    private static Timestamp ts(String isoInstant) { return Timestamp.from(Instant.parse(isoInstant)) }

    private static Timestamp minutesAgo(int minutes) { return new Timestamp(NOW.time - minutes * 60_000L) }

    private static Map<String, Object> board(def ec, Map system = QUIET_SYSTEM) {
        return AdminLiveOperationsSupport.getLiveOperations(ec, NOW, { system })
    }

    @Test
    void liveBoardDeniedForNonSuperAdmin() {
        def message = new MessageFacadeStub()
        def ec = AdminTestStubs.plainUserEc(message: message)

        assertNull(AdminLiveOperationsSupport.getLiveOperations(ec, NOW, { QUIET_SYSTEM }))
        assertTrue(message.hasError())
    }

    @Test
    void runningRowsAreClassifiedAsAuditAutomationOrManualRunAndNamed() {
        def ec = superAdminEc(baseTables() + [
                "darpan.reconciliation.ReconciliationRunResult": [
                        runRow("RR_AUDIT", "GORJANA", "RS_A", "EXTRACT_FILE1", 5),
                        runRow("RR_AUTO", "RAILS", "RS_B", "COMPARE", 8),
                        runRow("RR_MANUAL", "RAILS", "RS_C", "RESOLVE", 1),
                ],
                "darpan.reconciliation.ReconciliationAutomationExecution": [
                        [automationExecutionId: "EX_A", automationId: "AUT_A", companyUserGroupId: "GORJANA",
                         statusEnumId: "AUT_STAT_RUNNING", reconciliationRunResultId: "RR_AUDIT", retryCount: 0],
                        [automationExecutionId: "EX_B", automationId: "AUT_B", companyUserGroupId: "RAILS",
                         statusEnumId: "AUT_STAT_RUNNING", reconciliationRunResultId: "RR_AUTO", retryCount: 1],
                ],
                "darpan.rule.RuleSet": [[ruleSetId: "RS_C", ruleSetName: "Returns by hand"]],
        ])

        Map live = board(ec)

        Map<String, Map> byId = live.running.collectEntries { [(it.runResultId): it] }
        assertEquals(["audit", "automation", "run"], ["RR_AUDIT", "RR_AUTO", "RR_MANUAL"].collect { byId[it].kind })
        assertEquals(["Month close", "Orders vs OMS", "Returns by hand"], ["RR_AUDIT", "RR_AUTO", "RR_MANUAL"].collect { byId[it].name })
        assertEquals("gorjana", byId.RR_AUDIT.tenantLabel)
        assertEquals(2, byId.RR_AUTO.attempt)
        assertEquals(2, live.slots.inFlight)
        assertEquals([], live.unavailable)
    }

    @Test
    void runSlowerThanItsLastSuccessCarriesTheUsualDurationAndRaisesAWarning() {
        def ec = superAdminEc(baseTables() + [
                "darpan.reconciliation.ReconciliationRunResult": [
                        runRow("RR_SLOW", "RAILS", "RS_C", "COMPARE", 45),
                        [reconciliationRunResultId: "RR_PREV", companyUserGroupId: "RAILS", savedRunId: "RS_C",
                         statusEnumId: "AUT_STAT_SUCCESS", startedDate: minutesAgo(1500), completedDate: minutesAgo(1490)],
                ],
        ])

        Map live = board(ec)

        Map run = live.running.find { it.runResultId == "RR_SLOW" }
        assertEquals(10 * 60_000L, run.usualDurationMs)
        assertEquals("slow", run.health)
        assertEquals(["RUN_SLOW"], live.warnings*.code)
    }

    @Test
    void failuresInTheLastDayBecomeOneCriticalWarningPerRunAndOlderOnesAreIgnored() {
        def ec = superAdminEc(baseTables() + [
                "darpan.reconciliation.ReconciliationRunResult": [
                        [reconciliationRunResultId: "RR_F1", companyUserGroupId: "RAILS", savedRunId: "RS_B",
                         statusEnumId: "AUT_STAT_FAILED", completedDate: minutesAgo(30), errorMessage: "OMS 504"],
                        [reconciliationRunResultId: "RR_F0", companyUserGroupId: "RAILS", savedRunId: "RS_B",
                         statusEnumId: "AUT_STAT_FAILED", completedDate: minutesAgo(3000), errorMessage: "ancient"],
                ],
        ])

        Map live = board(ec)

        assertEquals(1, live.recentFailures.count)
        assertEquals(["RUN_FAILED"], live.warnings*.code)
        assertEquals("Orders vs OMS failed", live.warnings[0].title)
        assertTrue(live.warnings[0].detail.contains("OMS 504"))
    }

    // DAR-ADMIN-009: gorjana's two compares failed at 16:00, were retried from their extracts and succeeded; the board
    // still called both critical for 24 hours.
    @Test
    void aFailureALaterSuccessOfTheSameRunSupersededIsListedAsRecoveredAndRaisesNoWarning() {
        def ec = superAdminEc(baseTables() + [
                "darpan.reconciliation.ReconciliationRunResult": [
                        [reconciliationRunResultId: "RR_F1", companyUserGroupId: "RAILS", savedRunId: "RS_B",
                         statusEnumId: "AUT_STAT_FAILED", completedDate: minutesAgo(60), errorMessage: "UNRESOLVED_COLUMN"],
                        [reconciliationRunResultId: "RR_OK", companyUserGroupId: "RAILS", savedRunId: "RS_B",
                         statusEnumId: "AUT_STAT_SUCCESS", startedDate: minutesAgo(31), completedDate: minutesAgo(30)],
                        [reconciliationRunResultId: "RR_F2", companyUserGroupId: "GORJANA", savedRunId: "RS_A",
                         statusEnumId: "AUT_STAT_FAILED", completedDate: minutesAgo(20), errorMessage: "still broken"],
                        [reconciliationRunResultId: "RR_OLD_OK", companyUserGroupId: "GORJANA", savedRunId: "RS_A",
                         statusEnumId: "AUT_STAT_SUCCESS", startedDate: minutesAgo(100), completedDate: minutesAgo(90)],
                ],
        ])

        Map live = board(ec)

        assertEquals(2, live.recentFailures.count)
        assertEquals(1, live.recentFailures.recovered)
        Map<String, Map> byId = live.recentFailures.items.collectEntries { [(it.runResultId): it] }
        assertEquals(minutesAgo(30), byId.RR_F1.recoveredAt, "a later success recovers it")
        assertNull(byId.RR_F2.recoveredAt, "a success BEFORE the failure does not")
        assertEquals(["Month close automation failed"], live.warnings.findAll { it.code == "RUN_FAILED" }*.title)
    }

    @AfterEach
    void resetPeakSeams() {
        AutomationExecutionSupport.peakWindowsLoader = null
        AutomationExecutionSupport.tenantZoneLoader = null
    }

    @Test
    void aRunHeldForPeakHoursIsNotOverdueButAGenuinelyLateOneStillIs() {
        // DAR-BE-328: RAILS is inside its peak hours (09:00-12:00 UTC) at NOW (10:00): its 09:20 fire waits for 12:00.
        AutomationExecutionSupport.peakWindowsLoader = { def e, String tenantId, LocalDate d ->
            tenantId == "RAILS" ? [new PeakWindow("P1", "Sale", LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 31),
                    LocalTime.of(9, 0), LocalTime.of(12, 0), [])] : []
        }
        AutomationExecutionSupport.tenantZoneLoader = { def e, String tenantId -> ZoneId.of("UTC") }
        Map<String, List<Map>> tables = baseTables()
        tables["darpan.reconciliation.ReconciliationAutomation"] = [
                [automationId: "AUT_A", automationName: "Month close", companyUserGroupId: "GORJANA", savedRunId: "RS_A",
                 isActive: "Y", nextScheduledFireTime: minutesAgo(40)],
                [automationId: "AUT_H", automationName: "Held", companyUserGroupId: "RAILS", savedRunId: "RS_H",
                 isActive: "Y", nextScheduledFireTime: minutesAgo(40), windowTimeZone: "UTC"],
        ]
        def ec = superAdminEc(tables)

        Map live = board(ec)

        assertEquals(["AUT_A"], live.overdue*.automationId, "the held run starts at 12:00; it is not late")
        assertEquals(40 * 60_000L, live.overdue[0].lateMs)
        assertEquals(["critical"], live.warnings.findAll { it.code == "AUTOMATIONS_OVERDUE" }*.severity)
    }

    @Test
    void overdueListsActiveAutomationsPastTheirFireTimeButNotPausedOrStillRunningOnes() {
        Map<String, List<Map>> tables = baseTables()
        tables["darpan.reconciliation.ReconciliationAutomation"] = [
                [automationId: "AUT_A", automationName: "Month close", companyUserGroupId: "GORJANA", savedRunId: "RS_A",
                 isActive: "Y", nextScheduledFireTime: minutesAgo(40)],
                [automationId: "AUT_B", automationName: "Orders vs OMS", companyUserGroupId: "RAILS", savedRunId: "RS_B",
                 isActive: "Y", nextScheduledFireTime: minutesAgo(40)],
                [automationId: "AUT_P", automationName: "Paused", companyUserGroupId: "RAILS", savedRunId: "RS_P",
                 isActive: "N", nextScheduledFireTime: minutesAgo(400)],
                [automationId: "AUT_F", automationName: "Future", companyUserGroupId: "RAILS", savedRunId: "RS_F",
                 isActive: "Y", nextScheduledFireTime: new Timestamp(NOW.time + 60_000L)],
        ]
        tables["darpan.reconciliation.ReconciliationAutomationExecution"] = [
                [automationExecutionId: "EX_B", automationId: "AUT_B", companyUserGroupId: "RAILS",
                 statusEnumId: "AUT_STAT_RUNNING", reconciliationRunResultId: "RR_B"],
        ]
        def ec = superAdminEc(tables)

        Map live = board(ec)

        assertEquals(["AUT_A"], live.overdue*.automationId)
        assertEquals(40 * 60_000L, live.overdue[0].lateMs)
        assertEquals(["critical"], live.warnings.findAll { it.code == "AUTOMATIONS_OVERDUE" }*.severity)
    }

    @Test
    void unreadableSystemIsReportedAsUnavailableAndTheRestStillLoads() {
        def ec = superAdminEc(baseTables())

        Map live = AdminLiveOperationsSupport.getLiveOperations(ec, NOW, { throw new IllegalStateException("no MXBean") })

        assertNull(live.system)
        assertEquals(["system"], live.unavailable*.source)
        assertEquals("no MXBean", live.unavailable[0].reason)
        assertEquals(AutomationExecutionSupport.maxConcurrentExecutions(), live.slots.cap)
    }

    @Test
    void slotsCarryWhatWaitsForAdmissionAndEachTenantsShare() {
        def ec = superAdminEc(baseTables())
        java.util.concurrent.Executor hold = { Runnable r -> } as java.util.concurrent.Executor
        RunAdmission admission = new RunAdmission(1, 1, 85, { -> null }, hold)
        admission.submit("GORJANA", "a1", { -> } as Runnable)
        admission.submit("GORJANA", "a2", { -> } as Runnable)
        AutomationExecutionSupport.setRunAdmission(admission)
        try {
            Map live = AdminLiveOperationsSupport.getLiveOperations(ec, NOW, { e -> [:] })
            assertEquals(1, live.slots.queued)
            assertEquals(1, live.slots.perTenantCap)
            assertEquals(false, live.slots.heldForMemory)
            assertEquals([GORJANA: [running: 1, queued: 1]], live.slots.byTenant)
        } finally {
            AutomationExecutionSupport.setRunAdmission(null)
        }
    }

    @Test
    void systemPressureSurfacesAsWarnings() {
        def ec = superAdminEc(baseTables())

        Map live = board(ec, [heapAfterGcPercent: 93, processCpuPercent: 20, diskFreePercent: 60, workerPool: null])

        assertEquals(["HEAP_HIGH"], live.warnings*.code)
        assertEquals("critical", live.warnings[0].severity)
    }

    // ---- fixtures ----

    private static Map runRow(String id, String tenant, String savedRunId, String stage, int startedMinutesAgo) {
        return [reconciliationRunResultId: id, companyUserGroupId: tenant, savedRunId: savedRunId,
                statusEnumId: "AUT_STAT_RUNNING", currentStage: stage, progressPercent: 30,
                startedDate: minutesAgo(startedMinutesAgo), lastHeartbeatDate: minutesAgo(startedMinutesAgo)]
    }

    private static Map baseTables() {
        return [
                "moqui.security.UserGroup": [
                        [userGroupId: "GORJANA", description: "gorjana"],
                        [userGroupId: "RAILS", description: "Rails"],
                ],
                "darpan.reconciliation.ReconciliationAutomation": [
                        [automationId: "AUT_A", automationName: "Month close automation", companyUserGroupId: "GORJANA",
                         savedRunId: "RS_A", isActive: "Y", nextScheduledFireTime: new Timestamp(NOW.time + 60_000L)],
                        [automationId: "AUT_B", automationName: "Orders vs OMS", companyUserGroupId: "RAILS",
                         savedRunId: "RS_B", isActive: "Y", nextScheduledFireTime: new Timestamp(NOW.time + 60_000L)],
                ],
                // As the real model: no auditId on a check; it reaches its audit through a statement.
                "darpan.audit.AuditCheck": [
                        [checkId: "CHK_1", companyUserGroupId: "GORJANA", automationId: "AUT_A"],
                ],
                "darpan.audit.AuditStatementCheck": [
                        [checkId: "CHK_1", statementId: "ST_1", companyUserGroupId: "GORJANA"],
                ],
                "darpan.audit.AuditStatement": [
                        [statementId: "ST_1", auditId: "AUD_G", companyUserGroupId: "GORJANA"],
                ],
                "darpan.audit.Audit": [
                        [auditId: "AUD_G", companyUserGroupId: "GORJANA", auditName: "Month close"],
                ],
        ]
    }

    /** A super admin whose clock reads NOW. */
    private static def superAdminEc(Map tables) {
        return AdminTestStubs.superAdminEc(tables, [user: new UserStub(userId: AdminTestStubs.SUPER_ADMIN_ID,
                username: "darpan.admin", nowTimestamp: NOW)])
    }
}
