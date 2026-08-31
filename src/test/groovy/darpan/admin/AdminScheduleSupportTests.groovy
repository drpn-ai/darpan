package darpan.admin

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
 * are what make that safe, exactly as in AdminTenantSupport.listTenants. These tests pin the gate
 * first, because a board that leaks another tenant's automation names is the failure that matters.
 */
class AdminScheduleSupportTests {

    private static Timestamp ts(String isoInstant) { return Timestamp.from(Instant.parse(isoInstant)) }

    @Test
    void scheduleBoardDeniedForNonSuperAdmin() {
        def message = new MessageFacadeStub()
        def ec = executionContext(
                user: new UserStub(userId: "PLAIN_USER"),
                entity: new EntityFacadeStub(finders: [
                        "moqui.security.UserGroupMember": new FinderStub(oneResult: [
                                userGroupId: "DARPAN_USER", userId: "PLAIN_USER"])]),
                message: message)

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
        assertEquals(4, board.inFlightCap)
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

    // ---- stubs (each admin test class carries its own, matching AdminTenantSupportTests) ----

    private def superAdminEc(Map finders) {
        finders["moqui.security.UserGroupMember"] = finders["moqui.security.UserGroupMember"] ?:
                new FinderStub(oneResult: [userGroupId: "DARPAN_SUPER_ADMIN", userId: "ADMIN_USER"])
        return executionContext(
                user: new UserStub(userId: "ADMIN_USER", username: "darpan.admin"),
                entity: new EntityFacadeStub(finders: finders))
    }


    private static def executionContext(Map overrides = [:]) {
        return new Expando(
                user: overrides.user ?: new UserStub(),
                entity: overrides.entity ?: new EntityFacadeStub(),
                message: overrides.message ?: new MessageFacadeStub(),
                service: overrides.service ?: new ServiceFacadeStub(),
                l10n: overrides.l10n ?: new Expando(timeZone: "UTC"),
                resource: new Expando(properties: [:])
        )
    }

    static class UserStub {
        String userId
        String username
        Timestamp nowTimestamp = new Timestamp(System.currentTimeMillis())
        Map<String, Object> preferences = [:]
        Map<String, Object> context = [:]
        Object userAccount = new Expando(timeZone: "UTC")

        Object getPreference(String preferenceKey) { return preferences[preferenceKey] }

        void setPreference(String preferenceKey, Object preferenceValue) { preferences[preferenceKey] = preferenceValue }
    }

    static class MessageFacadeStub {
        List<String> errors = []

        void addError(String error) { errors << error }

        void addMessage(String message) {}

        boolean hasError() { return !errors.isEmpty() }
    }

    static class EntityFacadeStub {
        Map<String, FinderStub> finders = [:]

        FinderStub find(String entityName) {
            FinderStub template = finders[entityName]
            if (template == null) {
                template = new FinderStub()
                finders[entityName] = template
            }
            return new FinderStub(oneResult: template.oneResult, listResult: template.listResult)
        }
    }

    static class FinderStub {
        Map<String, Object> conditions = [:]
        Object oneResult
        List listResult = []

        FinderStub condition(String field, Object value) {
            conditions[field] = value
            return this
        }

        FinderStub conditionDate(String fromField, String thruField, Object moment) { return this }

        FinderStub useCache(boolean useCache) { return this }

        Object one() {
            if (oneResult instanceof Map && !conditions.every { String field, Object value -> oneResult[field] == value }) {
                return null
            }
            return oneResult
        }

        List list() {
            return listResult.findAll { Object row ->
                if (!(row instanceof Map)) return true
                conditions.every { String field, Object value -> row[field] == value }
            }
        }

        FinderStub disableAuthz() { return this }
    }

    static class ServiceFacadeStub {
        List<ServiceCallStub> calls = []
        Map<String, Map> resultsByName = [:]
        ServiceCallStub lastCall

        ServiceCallStub sync() {
            lastCall = new ServiceCallStub(facade: this)
            calls << lastCall
            return lastCall
        }
    }

    static class ServiceCallStub {
        ServiceFacadeStub facade
        String serviceName
        Map<String, Object> parametersMap = [:]

        ServiceCallStub name(String serviceName) { return this.tap { it.serviceName = serviceName } }

        ServiceCallStub parameters(Map<String, Object> parametersMap) { return this.tap { it.parametersMap = parametersMap } }

        Map<String, Object> call() { return facade?.resultsByName?.get(serviceName) ?: [:] }
    }
}
