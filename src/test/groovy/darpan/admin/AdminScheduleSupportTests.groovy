package darpan.admin

import darpan.admin.AdminTestStubs.FinderStub
import darpan.admin.AdminTestStubs.MessageFacadeStub
import darpan.reconciliation.automation.AutomationExecutionSupport
import org.junit.jupiter.api.Test

import java.sql.Timestamp
import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Instance-wide schedule board: every tenant's scheduled automations on one timeline.
 *
 * Cross-tenant by design — the DARPAN_ADMIN_API artifact fence plus the super-admin content gate
 * are what make that safe, exactly as in admin.TenantAdminServices.get#TenantList. These tests pin the gate
 * first, because a board that leaks another tenant's automation names is the failure that matters.
 */
class AdminScheduleSupportTests {

    private static Timestamp ts(String isoInstant) { return Timestamp.from(Instant.parse(isoInstant)) }

    @Test
    void scheduleBoardDeniedForNonSuperAdmin() {
        def message = new MessageFacadeStub()
        def ec = AdminTestStubs.plainUserEc(message: message)

        assertNull(AdminScheduleSupport.getScheduleBoard(ec, 24, ts("2026-09-01T14:30:00Z")))
        assertTrue(message.hasError())
    }

    @Test
    void fireTimesFromEveryTenantComeBackInOneTimeOrderedTimeline() {
        def ec = superAdminEc([
                "darpan.reconciliation.ReconciliationAutomation": new FinderStub(listResult: [
                        [automationId: "AUT_G1", automationName: "Orders vs OMS", companyUserGroupId: "GORJANA",
                         scheduleExpr: "PT1H", windowTimeZone: "UTC", isActive: "Y",
                         nextScheduledFireTime: ts("2026-09-01T15:00:00Z")],
                        [automationId: "AUT_R1", automationName: "Returns recon", companyUserGroupId: "RAILS",
                         scheduleExpr: "0 30 14 * * ?", windowTimeZone: "UTC", isActive: "Y",
                         nextScheduledFireTime: ts("2026-09-01T14:30:00Z")],
                ]),
                "moqui.security.UserGroup": new FinderStub(listResult: [
                        [userGroupId: "GORJANA", description: "gorjana"],
                        [userGroupId: "RAILS", description: "Rails"],
                ]),
        ])

        Map board = AdminScheduleSupport.getScheduleBoard(ec, 2, ts("2026-09-01T14:00:00Z"))

        assertEquals([ts("2026-09-01T14:30:00Z"), ts("2026-09-01T15:00:00Z"), ts("2026-09-01T16:00:00Z")],
                board.fires*.fireTime)
        assertEquals(["Returns recon", "Orders vs OMS", "Orders vs OMS"], board.fires*.automationName)
        assertEquals(["Rails", "gorjana", "gorjana"], board.fires*.tenantLabel)
    }

    @Test
    void pausedAutomationsAreListedButNeverProjectedOntoTheTimeline() {
        def ec = superAdminEc([
                "darpan.reconciliation.ReconciliationAutomation": new FinderStub(listResult: [
                        [automationId: "AUT_G1", automationName: "Orders vs OMS", companyUserGroupId: "GORJANA",
                         scheduleExpr: "PT1H", windowTimeZone: "UTC", isActive: "Y",
                         nextScheduledFireTime: ts("2026-09-01T15:00:00Z")],
                        [automationId: "AUT_M1", automationName: "Inventory sync", companyUserGroupId: "MEPHISTO",
                         scheduleExpr: "PT1H", windowTimeZone: "UTC", isActive: "N",
                         nextScheduledFireTime: ts("2026-09-01T15:00:00Z")],
                ]),
                "moqui.security.UserGroup": new FinderStub(listResult: [
                        [userGroupId: "GORJANA", description: "gorjana"],
                        [userGroupId: "MEPHISTO", description: "Mephisto"],
                ]),
        ])

        Map board = AdminScheduleSupport.getScheduleBoard(ec, 2, ts("2026-09-01T14:00:00Z"))

        assertEquals(["AUT_G1"], board.fires*.automationId.unique())
        assertEquals(["Inventory sync"], board.paused*.automationName)
        assertEquals(["Mephisto"], board.paused*.tenantLabel)
    }

    @Test
    void firesLandingOnOneScanTickCountAsConcurrentEvenWhenTheirDueTimesDiffer() {
        // 15:01, 15:03 and 15:05 are three different due times but ONE submission tick (15:05),
        // so the scheduler starts all three together. Counting on the raw due time would show
        // three quiet minutes and hide the actual burst.
        def ec = superAdminEc([
                "darpan.reconciliation.ReconciliationAutomation": new FinderStub(listResult: [
                        [automationId: "AUT_A", automationName: "Orders vs OMS", companyUserGroupId: "GORJANA",
                         scheduleExpr: "0 1 15 * * ?", windowTimeZone: "UTC", isActive: "Y",
                         nextScheduledFireTime: ts("2026-09-01T15:01:00Z")],
                        [automationId: "AUT_B", automationName: "Returns recon", companyUserGroupId: "RAILS",
                         scheduleExpr: "0 3 15 * * ?", windowTimeZone: "UTC", isActive: "Y",
                         nextScheduledFireTime: ts("2026-09-01T15:03:00Z")],
                        [automationId: "AUT_C", automationName: "Inventory sync", companyUserGroupId: "MEPHISTO",
                         scheduleExpr: "0 5 15 * * ?", windowTimeZone: "UTC", isActive: "Y",
                         nextScheduledFireTime: ts("2026-09-01T15:05:00Z")],
                ]),
                "moqui.security.UserGroup": new FinderStub(listResult: [
                        [userGroupId: "GORJANA", description: "gorjana"],
                        [userGroupId: "RAILS", description: "Rails"],
                        [userGroupId: "MEPHISTO", description: "Mephisto"],
                ]),
        ])

        Map board = AdminScheduleSupport.getScheduleBoard(ec, 1, ts("2026-09-01T15:00:00Z"))

        assertEquals([ts("2026-09-01T15:05:00Z")] * 3, board.fires*.submitsAt)
        assertEquals([3, 3, 3], board.fires*.concurrentAtTick)
        assertEquals(AutomationExecutionSupport.maxConcurrentExecutions(), board.inFlightCap)  // DAR-BE-143 raised it 4 -> 8
    }

    @Test
    void executionsStillRunningAreReportedAgainstTheInstanceCap() {
        def ec = superAdminEc([
                "darpan.reconciliation.ReconciliationAutomation": new FinderStub(listResult: [
                        [automationId: "AUT_G1", automationName: "Orders vs OMS", companyUserGroupId: "GORJANA",
                         scheduleExpr: "PT1H", windowTimeZone: "UTC", isActive: "Y",
                         nextScheduledFireTime: ts("2026-09-01T15:00:00Z")],
                ]),
                "moqui.security.UserGroup": new FinderStub(listResult: [
                        [userGroupId: "GORJANA", description: "gorjana"],
                ]),
                "darpan.reconciliation.ReconciliationAutomationExecution": new FinderStub(listResult: [
                        [automationExecutionId: "EX_1", automationId: "AUT_G1", companyUserGroupId: "GORJANA",
                         statusEnumId: "AUT_STAT_RUNNING", startedDate: ts("2026-09-01T14:54:00Z")],
                        [automationExecutionId: "EX_0", automationId: "AUT_G1", companyUserGroupId: "GORJANA",
                         statusEnumId: "AUT_STAT_COMPLETED", startedDate: ts("2026-09-01T13:54:00Z")],
                ]),
        ])

        Map board = AdminScheduleSupport.getScheduleBoard(ec, 2, ts("2026-09-01T15:00:00Z"))

        assertEquals(["EX_1"], board.inFlight*.automationExecutionId)
        assertEquals(["Orders vs OMS"], board.inFlight*.automationName)
        assertEquals(["gorjana"], board.inFlight*.tenantLabel)
    }

    @Test
    void submitsAtFollowsTheLiveScanCronAndIsEmptyWhileTheScanIsPaused() {
        Map automation = [automationId: "AUT_A", automationName: "Orders vs OMS", companyUserGroupId: "GORJANA",
                          scheduleExpr: "0 2 15 * * ?", windowTimeZone: "UTC", isActive: "Y", nextScheduledFireTime: ts("2026-09-01T15:02:00Z")]
        Map finders = ["darpan.reconciliation.ReconciliationAutomation": new FinderStub(listResult: [automation]),
                       "moqui.security.UserGroup": new FinderStub(listResult: [[userGroupId: "GORJANA", description: "gorjana"]]),
                       "moqui.service.job.ServiceJob": new FinderStub(oneResult: [jobName: AdminScheduleSupport.SCAN_JOB,
                               cronExpression: "0 0/10 * * * ?", paused: "N"])]
        Map board = AdminScheduleSupport.getScheduleBoard(superAdminEc(finders), 1, ts("2026-09-01T15:00:00Z"))
        assertEquals([ts("2026-09-01T15:10:00Z")], board.fires*.submitsAt)
        assertEquals("0 0/10 * * * ?", board.scanCron)
        assertEquals(false, board.scanPaused)

        finders["moqui.service.job.ServiceJob"] = new FinderStub(oneResult: [jobName: AdminScheduleSupport.SCAN_JOB,
                cronExpression: "0 0/10 * * * ?", paused: "Y"])
        Map paused = AdminScheduleSupport.getScheduleBoard(superAdminEc(finders), 1, ts("2026-09-01T15:00:00Z"))
        assertEquals([null], paused.fires*.submitsAt)
        assertEquals([null], paused.fires*.concurrentAtTick)
        assertEquals(true, paused.scanPaused)
        assertNull(paused.scanCron)
    }

    /** A super admin, with the scan job live on its default cron unless the test sets one. */
    private static def superAdminEc(Map finders) {
        finders["moqui.service.job.ServiceJob"] = finders["moqui.service.job.ServiceJob"] ?:
                new FinderStub(oneResult: [jobName: AdminScheduleSupport.SCAN_JOB, cronExpression: "0 0/5 * * * ?", paused: "N"])
        return AdminTestStubs.superAdminEc(finders)
    }
}
