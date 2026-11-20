package darpan.facade.peak

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.sql.Date
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-328: cross-tenant containment for the peak-period facade. Tenant A (KREWE) owns a period and a run time; tenant B
 * (GORJANA, the caller) must not list, read, change, delete or move into them, and cannot name A's automation in a run time.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PeakPeriodFacadeCrossTenantSmokeTests {
    private static final String TEST_USER_ID = "TEST_CUSTOMER_USER"
    private static final String A = "KREWE"
    private static final String B = "GORJANA"
    private static final Timestamp TEST_FROM_DATE = Timestamp.valueOf("2026-04-23 00:00:00")

    private ExecutionContext ec
    private String periodA
    private String runTimeA

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "peak-period-facade-cross-tenant-smoke")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml")
        seedTenant(A, "Krewe")
        seedTenant(B, "Gorjana")
        automation("AUTO_A", A)
        automation("AUTO_B", B)
        actAs(A)
        periodA = (String) call("save#PeakPeriod", [periodName: "Black Friday sale", fromDate: "2026-11-24", thruDate: "2026-12-01",
                                                    peakHoursStart: "08:00", peakHoursEnd: "23:00"]).peakPeriodId
        runTimeA = (String) call("save#PeakPeriodRunTime", [peakPeriodId: periodA, automationId: "AUTO_A", runTime: "06:00"]).runTimeId
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void actAsB() {
        ec.message.clearErrors()
        actAs(B)
    }

    @Test
    void positiveControlTheOwnerSeesAndEditsItsOwnPeriod() {
        actAs(A)
        assertNotNull(periodA, "setup is broken: A could not create its own period")
        assertNotNull(runTimeA, "setup is broken: A could not create its own run time")
        assertTrue(((List) call("list#PeakPeriods", [:]).peakPeriods).any { it.peakPeriodId == periodA })
        Map got = call("get#PeakPeriod", [peakPeriodId: periodA])
        assertTrue(got.ok as boolean, "${got.errors}")
        assertEquals("Black Friday sale", got.peakPeriod.periodName)
        assertEquals(1, ((List) got.runTimes).size())
        assertEquals([[targetLabel: "AUTO_A", normalTime: "10:00", startsAt: "23:00", reason: "10:00 is in peak hours"]], got.heldRuns,
                "A's daily 10:00 run waits for peak hours to end")
        assertEquals("06:00", ((List) got.runTimes)[0].runTime)
        assertEquals("10:00", ((List) got.runTimes)[0].normalTime)
        assertEquals("AUTO_A", ((List) got.targets).find { it.automationId == "AUTO_A" }?.automationId)
        assertTrue(((List) got.targets).every { it.automationId != "AUTO_B" }, "B's automation is not one of A's targets")
        assertTrue(call("save#PeakPeriod", [peakPeriodId: periodA, periodName: "Black Friday sale", fromDate: "2026-11-24",
                                            thruDate: "2026-12-01", peakHoursStart: "08:00", peakHoursEnd: "23:00"]).ok as boolean)
    }

    @Test
    void anotherTenantsPeriodIsNotListedOrReadable() {
        assertTrue(((List) call("list#PeakPeriods", [:]).peakPeriods).every { it.peakPeriodId != periodA })
        Map got = call("get#PeakPeriod", [peakPeriodId: periodA])
        assertFalse(got.ok as boolean)
        assertEquals(["Peak period '${periodA}' was not found.".toString()], got.errors)
    }

    @Test
    void savingAnotherTenantsPeriodIsRefusedAndChangesNothing() {
        Map result = call("save#PeakPeriod", [peakPeriodId: periodA, periodName: "Stolen", fromDate: "2027-01-01", thruDate: "2027-01-02"])
        assertFalse(result.ok as boolean)
        def row = readPeriod()
        assertEquals(A, row.companyUserGroupId as String, "the period was re-parented into the caller's tenant")
        assertEquals("Black Friday sale", row.periodName as String)
    }

    @Test
    void deletingAnotherTenantsPeriodIsRefusedAndKeepsItsRunTimes() {
        assertFalse(call("delete#PeakPeriod", [peakPeriodId: periodA]).ok as boolean)
        assertNotNull(readPeriod(), "another tenant's period was deleted")
        assertNotNull(readRunTime(), "another tenant's run time was deleted")
    }

    @Test
    void anotherTenantsPeriodTakesNoRunTimes() {
        long before = runTimeCount()
        Map result = call("save#PeakPeriodRunTime", [peakPeriodId: periodA, automationId: "AUTO_B", runTime: "05:00"])
        assertFalse(result.ok as boolean)
        assertEquals(["Peak period '${periodA}' was not found.".toString()], result.errors)
        assertEquals(before, runTimeCount())
    }

    @Test
    void anotherTenantsRunTimeCannotBeChangedOrDeleted() {
        assertFalse(call("delete#PeakPeriodRunTime", [runTimeId: runTimeA]).ok as boolean)
        assertNotNull(readRunTime(), "another tenant's run time was deleted")
        B_ownPeriodThenForeignRunTimeId()
    }

    private void B_ownPeriodThenForeignRunTimeId() {
        // B's own period naming A's run time id: the run time is not B's, so it is refused and left alone.
        String periodB = (String) call("save#PeakPeriod", [periodName: "B sale", fromDate: "2026-12-10", thruDate: "2026-12-12"]).peakPeriodId
        assertNotNull(periodB)
        Map result = call("save#PeakPeriodRunTime", [runTimeId: runTimeA, peakPeriodId: periodB, automationId: "AUTO_B", runTime: "04:00"])
        assertFalse(result.ok as boolean)
        assertEquals("06:00", readRunTime().runTime as String)
        assertEquals(A, readRunTime().companyUserGroupId as String)
    }

    @Test
    void aRunTimeCannotNameAnotherTenantsAutomation() {
        actAs(A)
        Map result = call("save#PeakPeriodRunTime", [peakPeriodId: periodA, automationId: "AUTO_B", runTime: "05:00"])
        assertFalse(result.ok as boolean)
        assertEquals(["Choose a run."], result.errors)
    }

    private Map call(String name, Map params) {
        ec.message.clearErrors()
        return (Map) ec.service.sync().name("facade.PeakPeriodFacadeServices.${name}".toString()).parameters(params).disableAuthz().call()
    }

    private void actAs(String tenant) { ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, tenant) }

    private def readPeriod() {
        return ec.entity.find("darpan.peak.PeakPeriod").condition("peakPeriodId", periodA).disableAuthz().useCache(false).one()
    }

    private def readRunTime() {
        return ec.entity.find("darpan.peak.PeakPeriodRunTime").condition("runTimeId", runTimeA).disableAuthz().useCache(false).one()
    }

    private long runTimeCount() {
        return ec.entity.find("darpan.peak.PeakPeriodRunTime").disableAuthz().useCache(false).count()
    }

    private void automation(String id, String tenant) {
        ec.entity.makeValue("darpan.reconciliation.ReconciliationAutomation").setAll([automationId: id, automationName: id,
                companyUserGroupId: tenant, inputModeEnumId: AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE,
                savedRunId: "PK_RUN", scheduleExpr: "0 0 10 * * ?", windowTimeZone: "UTC", isActive: "Y"]).createOrUpdate()
    }

    private void seedTenant(String tenantId, String label) {
        ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: tenantId, description: label,
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: tenantId, userId: TEST_USER_ID,
                fromDate: TEST_FROM_DATE]).createOrUpdate()
        ec.entity.makeValue(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME).setAll([tenantUserGroupId: tenantId,
                userId: TEST_USER_ID, permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID,
                fromDate: TEST_FROM_DATE]).createOrUpdate()
    }
}
