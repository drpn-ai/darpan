package darpan.admin

import org.junit.jupiter.api.Test

import java.sql.Timestamp
import java.time.Instant

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
        def ec = executionContext(
                user: new UserStub(userId: "PLAIN_USER"),
                entity: new EntityFacadeStub(finders: [
                        "moqui.security.UserGroupMember": new FinderStub(oneResult: [
                                userGroupId: "DARPAN_USER", userId: "PLAIN_USER"])]),
                message: message)

        assertNull(AdminLiveOperationsSupport.getLiveOperations(ec, NOW, { QUIET_SYSTEM }))
        assertTrue(message.hasError())
    }

    @Test
    void runningRowsAreClassifiedAsAuditAutomationOrManualRunAndNamed() {
        def ec = superAdminEc(baseFinders() + [
                "darpan.reconciliation.ReconciliationRunResult": new FinderStub(listResult: [
                        runRow("RR_AUDIT", "GORJANA", "RS_A", "EXTRACT_FILE1", 5),
                        runRow("RR_AUTO", "RAILS", "RS_B", "COMPARE", 8),
                        runRow("RR_MANUAL", "RAILS", "RS_C", "RESOLVE", 1),
                ]),
                "darpan.reconciliation.ReconciliationAutomationExecution": new FinderStub(listResult: [
                        [automationExecutionId: "EX_A", automationId: "AUT_A", companyUserGroupId: "GORJANA",
                         statusEnumId: "AUT_STAT_RUNNING", reconciliationRunResultId: "RR_AUDIT", retryCount: 0],
                        [automationExecutionId: "EX_B", automationId: "AUT_B", companyUserGroupId: "RAILS",
                         statusEnumId: "AUT_STAT_RUNNING", reconciliationRunResultId: "RR_AUTO", retryCount: 1],
                ]),
                "darpan.rule.RuleSet": new FinderStub(listResult: [[ruleSetId: "RS_C", ruleSetName: "Returns by hand"]]),
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
        def ec = superAdminEc(baseFinders() + [
                "darpan.reconciliation.ReconciliationRunResult": new FinderStub(listResult: [
                        runRow("RR_SLOW", "RAILS", "RS_C", "COMPARE", 45),
                        [reconciliationRunResultId: "RR_PREV", companyUserGroupId: "RAILS", savedRunId: "RS_C",
                         statusEnumId: "AUT_STAT_SUCCESS", startedDate: minutesAgo(1500), completedDate: minutesAgo(1490)],
                ]),
        ])

        Map live = board(ec)

        Map run = live.running.find { it.runResultId == "RR_SLOW" }
        assertEquals(10 * 60_000L, run.usualDurationMs)
        assertEquals("slow", run.health)
        assertEquals(["RUN_SLOW"], live.warnings*.code)
    }

    @Test
    void failuresInTheLastDayBecomeOneCriticalWarningPerRunAndOlderOnesAreIgnored() {
        def ec = superAdminEc(baseFinders() + [
                "darpan.reconciliation.ReconciliationRunResult": new FinderStub(listResult: [
                        [reconciliationRunResultId: "RR_F1", companyUserGroupId: "RAILS", savedRunId: "RS_B",
                         statusEnumId: "AUT_STAT_FAILED", completedDate: minutesAgo(30), errorMessage: "OMS 504"],
                        [reconciliationRunResultId: "RR_F0", companyUserGroupId: "RAILS", savedRunId: "RS_B",
                         statusEnumId: "AUT_STAT_FAILED", completedDate: minutesAgo(3000), errorMessage: "ancient"],
                ]),
        ])

        Map live = board(ec)

        assertEquals(1, live.recentFailures.count)
        assertEquals(["RUN_FAILED"], live.warnings*.code)
        assertEquals("Orders vs OMS failed", live.warnings[0].title)
        assertTrue(live.warnings[0].detail.contains("OMS 504"))
    }

    @Test
    void overdueListsActiveAutomationsPastTheirFireTimeButNotPausedOrStillRunningOnes() {
        Map finders = baseFinders()
        finders["darpan.reconciliation.ReconciliationAutomation"] = new FinderStub(listResult: [
                [automationId: "AUT_A", automationName: "Month close", companyUserGroupId: "GORJANA", savedRunId: "RS_A",
                 isActive: "Y", nextScheduledFireTime: minutesAgo(40)],
                [automationId: "AUT_B", automationName: "Orders vs OMS", companyUserGroupId: "RAILS", savedRunId: "RS_B",
                 isActive: "Y", nextScheduledFireTime: minutesAgo(40)],
                [automationId: "AUT_P", automationName: "Paused", companyUserGroupId: "RAILS", savedRunId: "RS_P",
                 isActive: "N", nextScheduledFireTime: minutesAgo(400)],
                [automationId: "AUT_F", automationName: "Future", companyUserGroupId: "RAILS", savedRunId: "RS_F",
                 isActive: "Y", nextScheduledFireTime: new Timestamp(NOW.time + 60_000L)],
        ])
        finders["darpan.reconciliation.ReconciliationAutomationExecution"] = new FinderStub(listResult: [
                [automationExecutionId: "EX_B", automationId: "AUT_B", companyUserGroupId: "RAILS",
                 statusEnumId: "AUT_STAT_RUNNING", reconciliationRunResultId: "RR_B"],
        ])
        def ec = superAdminEc(finders)

        Map live = board(ec)

        assertEquals(["AUT_A"], live.overdue*.automationId)
        assertEquals(40 * 60_000L, live.overdue[0].lateMs)
        assertEquals(["critical"], live.warnings.findAll { it.code == "AUTOMATIONS_OVERDUE" }*.severity)
    }

    @Test
    void unreadableSystemIsReportedAsUnavailableAndTheRestStillLoads() {
        def ec = superAdminEc(baseFinders())

        Map live = AdminLiveOperationsSupport.getLiveOperations(ec, NOW, { throw new IllegalStateException("no MXBean") })

        assertNull(live.system)
        assertEquals(["system"], live.unavailable*.source)
        assertEquals("no MXBean", live.unavailable[0].reason)
        assertEquals(4, live.slots.cap)
    }

    @Test
    void systemPressureSurfacesAsWarnings() {
        def ec = superAdminEc(baseFinders())

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

    private static Map baseFinders() {
        return [
                "moqui.security.UserGroup": new FinderStub(listResult: [
                        [userGroupId: "GORJANA", description: "gorjana"],
                        [userGroupId: "RAILS", description: "Rails"],
                ]),
                "darpan.reconciliation.ReconciliationAutomation": new FinderStub(listResult: [
                        [automationId: "AUT_A", automationName: "Month close automation", companyUserGroupId: "GORJANA",
                         savedRunId: "RS_A", isActive: "Y", nextScheduledFireTime: new Timestamp(NOW.time + 60_000L)],
                        [automationId: "AUT_B", automationName: "Orders vs OMS", companyUserGroupId: "RAILS",
                         savedRunId: "RS_B", isActive: "Y", nextScheduledFireTime: new Timestamp(NOW.time + 60_000L)],
                ]),
                "darpan.audit.AuditCheck": new FinderStub(listResult: [
                        [checkId: "CHK_1", companyUserGroupId: "GORJANA", automationId: "AUT_A", auditId: "AUD_G"],
                ]),
                "darpan.audit.Audit": new FinderStub(listResult: [
                        [auditId: "AUD_G", companyUserGroupId: "GORJANA", auditName: "Month close"],
                ]),
        ]
    }

    // ---- stubs (each admin test class carries its own, matching AdminScheduleSupportTests) ----

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
                l10n: new Expando(timeZone: "UTC"),
                resource: new Expando(properties: [:])
        )
    }

    static class UserStub {
        String userId
        String username
        Timestamp nowTimestamp = NOW
        Map<String, Object> context = [:]
        Object userAccount = new Expando(timeZone: "UTC")

        Object getPreference(String preferenceKey) { return null }
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
            FinderStub template = finders[entityName] ?: new FinderStub()
            return new FinderStub(oneResult: template.oneResult, listResult: template.listResult)
        }
    }

    /** Equality, the two comparisons the board uses, orderBy("-field") and limit. */
    static class FinderStub {
        List<Closure<Boolean>> predicates = []
        Object oneResult
        List listResult = []
        String orderField
        boolean descending
        Integer max

        FinderStub condition(String field, Object value) {
            predicates << { Map row -> row[field] == value }
            return this
        }

        FinderStub condition(String field, Object operator, Object value) {
            switch (operator.toString()) {
                case "GREATER_THAN_EQUAL_TO": predicates << { Map row -> row[field] != null && row[field] >= value }; break
                case "LESS_THAN": predicates << { Map row -> row[field] != null && row[field] < value }; break
                case "NOT_EQUAL": predicates << { Map row -> row[field] != value }; break
                default: throw new IllegalArgumentException("stub does not model ${operator}")
            }
            return this
        }

        FinderStub orderBy(String field) {
            descending = field.startsWith("-")
            orderField = descending ? field.substring(1) : field
            return this
        }

        FinderStub limit(Integer max) { return this.tap { it.max = max } }

        FinderStub useCache(boolean useCache) { return this }

        FinderStub conditionDate(String fromField, String thruField, Object moment) { return this }

        FinderStub disableAuthz() { return this }

        Object one() {
            if (oneResult instanceof Map) {
                return predicates.every { it.call(oneResult) } ? oneResult : null
            }
            List rows = list()
            return rows ? rows[0] : null
        }

        List list() {
            List rows = listResult.findAll { Object row -> !(row instanceof Map) || predicates.every { it.call(row as Map) } }
            if (orderField) {
                rows = rows.sort(false) { Map a, Map b -> (a[orderField] <=> b[orderField]) * (descending ? -1 : 1) }
            }
            return max != null ? rows.take(max) : rows
        }
    }
}
