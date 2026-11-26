package darpan.facade.peak

import darpan.reconciliation.support.SharedMoqui
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
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-328: cross-tenant containment for the peak-period facade. Tenant A (KREWE) owns a period and a run time; tenant B
 * (GORJANA, the caller) must not list, read, change, delete or move into them, and cannot name A's automation in a run time.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SharedMoqui
class PeakPeriodFacadeCrossTenantSmokeTests {
    private static final String TEST_USER_ID = "TEST_CUSTOMER_USER"
    private static final String A = "KREWE"
    private static final String B = "GORJANA"
    private static final Timestamp TEST_FROM_DATE = Timestamp.valueOf("2026-04-23 00:00:00")

    private ExecutionContext ec
    private String periodA
    private String runTimeA
    private static final String PAIR_A = "PAIR_A"
    private static final String PAIR_B = "PAIR_B"
    private static final String PAIR_C = "PAIR_C"
    private static final String PAIR_D = "PAIR_D"

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "peak-period-facade-cross-tenant-smoke")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/DarpanSystemSourceSeedData.xml")
        // DAR-BE-377: the connector rows say which endpoints a pulse can read upstream
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SourceSystemConnectorSeedData.xml")
        seedTenant(A, "Krewe")
        seedTenant(B, "Gorjana")
        automation("AUTO_A", A)
        automation("AUTO_B", B)
        automation("AUTO_A_SHOP", A)
        // Unscheduled, so it is not a peak target or a held run: it only carries the pair whose upstream a pulse cannot read
        // (NetSuite: its connector names no pulseCreatedField, DAR-BE-377).
        ec.entity.makeValue("darpan.reconciliation.ReconciliationAutomation").setAll([automationId: "AUTO_A_SHOP", scheduleExpr: null]).update()
        pair(PAIR_A, "AUTO_A", A, "OMS_GQL", "NETSUITE")
        pair(PAIR_B, "AUTO_B", B, "OMS_GQL", "NETSUITE")
        pair(PAIR_C, "AUTO_A_SHOP", A, "NETSUITE", "OMS_GQL")
        // PAIR_A's check keys both sides by one plain field; PAIR_D's check has no key at all, so it cannot pulse.
        ec.entity.makeValue("darpan.rule.RuleSet").setAll([ruleSetId: "RS_A", ruleSetName: "Check A", companyUserGroupId: A,
                explosionPath: "records", primaryKeyPath: "orderId"]).createOrUpdate()
        ec.entity.makeValue("darpan.rule.RuleSetCompareScope").setAll([compareScopeId: "CS_A", ruleSetId: "RS_A", scopeMode: "EVALUATE",
                objectType: "ORDER", companyUserGroupId: A]).createOrUpdate()
        ec.entity.makeValue("darpan.reconciliation.ReconciliationAutomation").setAll([automationId: "AUTO_A", compareScopeId: "CS_A"]).update()
        ["FILE_1", "FILE_2"].each { String side ->
            ec.entity.makeValue("darpan.rule.RuleSetCompareSource").setAll([compareScopeId: "CS_A", fileSide: side, systemEnumId: "OMS_GQL",
                    recordRootExpression: "records", primaryIdExpression: "orderId", companyUserGroupId: A]).createOrUpdate()
        }
        automation("AUTO_A_NOKEY", A)
        ec.entity.makeValue("darpan.reconciliation.ReconciliationAutomation").setAll([automationId: "AUTO_A_NOKEY", scheduleExpr: null]).update()
        pair(PAIR_D, "AUTO_A_NOKEY", A, "OMS_GQL", "NETSUITE")
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
        assertEquals([], got.heldRuns,
                "A's daily 10:00 run would wait for peak hours, but its run time moves it to 06:00 every day, before them")
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

    @Test
    void aPeriodCannotGiveAPulseToAPairWhoseCheckCannotPulse() {
        actAs(A)
        String period = (String) call("save#PeakPeriod", [periodName: "No-key sale", fromDate: "2027-04-01", thruDate: "2027-04-05"]).peakPeriodId
        Map refused = call("save#PeakPeriodSync", [peakPeriodId: period, syncPairId: PAIR_D, pulseEveryMinutes: 60])
        assertFalse(refused.ok as boolean)
        assertEquals([darpan.pulse.SyncPulseFacadeSupport.E_KEY], refused.errors)
        assertNull(syncRow(period, PAIR_D), "a refused pulse must not store a row")
        assertTrue(call("save#PeakPeriodSync", [peakPeriodId: period, syncPairId: PAIR_D, allowanceMinutes: 90]).ok as boolean,
                "an allowance-only override needs no pulse")
        assertEquals(90, syncRow(period, PAIR_D).allowanceMinutes as int)
        // Another tenant cannot reach A's pair at all.
        actAs(B)
        assertFalse(call("save#PeakPeriodSync", [peakPeriodId: period, syncPairId: PAIR_D, pulseEveryMinutes: 60]).ok as boolean)
    }

    @Test
    void syncOverrideReplacesClearsAndAcceptsOff() {
        actAs(A)
        String period = (String) call("save#PeakPeriod", [periodName: "Sync sale", fromDate: "2027-03-01", thruDate: "2027-03-05"]).peakPeriodId
        assertTrue(call("save#PeakPeriodSync", [peakPeriodId: period, syncPairId: PAIR_A, allowanceMinutes: 90, pulseEveryMinutes: 0]).ok as boolean)
        assertEquals(90, syncRow(period, PAIR_A).allowanceMinutes as int)
        assertNotNull(syncRow(period, PAIR_A).pulseEveryMinutes, "pulse 0 (off) must be stored as 0, not null")
        assertEquals(0, syncRow(period, PAIR_A).pulseEveryMinutes as int)
        // Clearing the pulse keeps the allowance; the row is replaced, not merged.
        assertTrue(call("save#PeakPeriodSync", [peakPeriodId: period, syncPairId: PAIR_A, allowanceMinutes: 90]).ok as boolean)
        assertEquals(90, syncRow(period, PAIR_A).allowanceMinutes as int)
        assertNull(syncRow(period, PAIR_A).pulseEveryMinutes)
        assertTrue(call("save#PeakPeriodSync", [peakPeriodId: period, syncPairId: PAIR_A, pulseEveryMinutes: 120]).ok as boolean)
        assertNull(syncRow(period, PAIR_A).allowanceMinutes)
        assertEquals(120, syncRow(period, PAIR_A).pulseEveryMinutes as int)
        Map unreadable = call("save#PeakPeriodSync", [peakPeriodId: period, syncPairId: PAIR_C, pulseEveryMinutes: 60])
        assertFalse(unreadable.ok as boolean)
        assertEquals(["A pulse needs HotWax or Shopify orders as the upstream side."], unreadable.errors)
        assertTrue(call("save#PeakPeriodSync", [peakPeriodId: period, syncPairId: PAIR_C, allowanceMinutes: 30, pulseEveryMinutes: 0]).ok as boolean,
                "an off pulse and an allowance are fine on any upstream")
        assertTrue(call("delete#PeakPeriodSync", [peakPeriodId: period, syncPairId: PAIR_C]).ok as boolean)
        Map bad = call("save#PeakPeriodSync", [peakPeriodId: period, syncPairId: PAIR_A, allowanceMinutes: 5])
        assertFalse(bad.ok as boolean)
        assertEquals(["Allowance must be 15 minutes to 48 hours."], bad.errors)
        Map got = call("get#PeakPeriod", [peakPeriodId: period])
        Map row = ((List) got.syncRows).find { it.syncPairId == PAIR_A }
        assertNotNull(row)
        assertEquals("HotWax \u2192 NetSuite orders", row.label)
        assertNull(row.periodAllowanceMinutes)
        assertEquals(120, row.periodPulseMinutes as int)
        assertEquals(60, row.normalAllowanceMinutes as int)
        assertTrue(((List) got.syncRows).every { it.syncPairId != PAIR_B }, "B's pair is not one of A's sync rows")
        assertTrue(call("delete#PeakPeriodSync", [peakPeriodId: period, syncPairId: PAIR_A]).ok as boolean)
        assertNull(syncRow(period, PAIR_A))
        assertTrue(call("delete#PeakPeriodSync", [peakPeriodId: period, syncPairId: PAIR_A]).ok as boolean, "deleting a missing override is fine")
        // The cascade: deleting a period removes its overrides.
        assertTrue(call("save#PeakPeriodSync", [peakPeriodId: period, syncPairId: PAIR_A, allowanceMinutes: 30]).ok as boolean)
        assertTrue(call("delete#PeakPeriod", [peakPeriodId: period]).ok as boolean)
        assertNull(syncRow(period, PAIR_A), "the period's sync override survived its delete")
    }

    @Test
    void anotherTenantsPairOrPeriodTakesNoSyncOverride() {
        Map foreignPair = call("save#PeakPeriodSync", [peakPeriodId: periodA, syncPairId: PAIR_A, allowanceMinutes: 30])
        assertFalse(foreignPair.ok as boolean)
        assertNull(syncRow(periodA, PAIR_A))
        actAs(A)
        Map foreignPairOfB = call("save#PeakPeriodSync", [peakPeriodId: periodA, syncPairId: PAIR_B, allowanceMinutes: 30])
        assertFalse(foreignPairOfB.ok as boolean)
        assertEquals(["Sync pair '${PAIR_B}' was not found.".toString()], foreignPairOfB.errors)
        assertNull(syncRow(periodA, PAIR_B))
        // B cannot delete an override of A's period.
        actAs(A)
        assertTrue(call("save#PeakPeriodSync", [peakPeriodId: periodA, syncPairId: PAIR_A, allowanceMinutes: 45]).ok as boolean)
        actAs(B)
        assertFalse(call("delete#PeakPeriodSync", [peakPeriodId: periodA, syncPairId: PAIR_A]).ok as boolean)
        assertNotNull(syncRow(periodA, PAIR_A), "another tenant's sync override was deleted")
        assertFalse(call("get#PeakPeriod", [peakPeriodId: periodA]).ok as boolean)
        // Deleting A's period as B leaves its overrides.
        assertFalse(call("delete#PeakPeriod", [peakPeriodId: periodA]).ok as boolean)
        assertNotNull(syncRow(periodA, PAIR_A))
    }

    private def syncRow(String period, String pair) {
        return ec.entity.find("darpan.pulse.PeakPeriodSync").condition("peakPeriodId", period).condition("syncPairId", pair)
                .disableAuthz().useCache(false).one()
    }

    private void pair(String id, String automationId, String tenant, String upstream, String target) {
        String api = AutomationExecutionSupport.AUTOMATION_SOURCE_API
        ec.entity.makeValue("darpan.reconciliation.ReconciliationAutomationSource").setAll([automationId: automationId, fileSide: "FILE_1",
                companyUserGroupId: tenant, sourceTypeEnumId: api, systemEnumId: upstream]).createOrUpdate()
        ec.entity.makeValue("darpan.reconciliation.ReconciliationAutomationSource").setAll([automationId: automationId, fileSide: "FILE_2",
                companyUserGroupId: tenant, sourceTypeEnumId: api, systemEnumId: target]).createOrUpdate()
        ec.entity.makeValue("darpan.pulse.SyncPair").setAll([syncPairId: id, companyUserGroupId: tenant, automationId: automationId,
                upstreamSide: "FILE_1", allowanceMinutes: 60, isActive: "Y"]).createOrUpdate()
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
