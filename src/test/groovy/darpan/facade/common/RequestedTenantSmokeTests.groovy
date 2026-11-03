package darpan.facade.common

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ExecutionContext

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-CLI-003: a request names its tenant (X-Darpan-Tenant); the saved preference never moves. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RequestedTenantSmokeTests {
    private static final String TEST_USER_ID = "TEST_CUSTOMER_USER"
    private static final String TENANT_A = ReconciliationSmokeTestSupport.TEST_COMPANY_USER_GROUP_ID
    private static final String TENANT_B = "REQ_TENANT_B"
    private static final String FOREIGN = "REQ_TENANT_FOREIGN"
    private static final String ENDED = "REQ_TENANT_ENDED"
    private static final Timestamp FROM = Timestamp.valueOf("2026-04-23 00:00:00")

    private ExecutionContext ec
    private String savedRunB

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "requested-tenant-smoke")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/DarpanSystemSourceSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SourceSystemConnectorSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SystemMessageRemoteSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ReconciliationSmokeTestSupport.seedSchemaBackedCsvMappingFixtures(ec)
        seedTenant(TENANT_B, "Tenant B", null)
        seedTenant(ENDED, "Ended", Timestamp.valueOf("2026-05-01 00:00:00"))
        seedTenant(FOREIGN, "Foreign", null, false)
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, TENANT_A)
        TenantAccessSupport.withRequestedTenant(TENANT_B) {
            Map created = ec.service.sync().name("facade.ReconciliationFacadeServices.create#CsvRun").parameters([
                    runName: "Requested B", file1SystemEnumId: "OMS", file2SystemEnumId: "SHOPIFY",
                    file1CompareColumn: "order_id", file2CompareColumn: "order_id"]).disableAuthz().call()
            assertFalse(ec.message.hasError(), ec.message.errorsString)
            savedRunB = created.savedRun.savedRunId as String
        }
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void reset() {
        ec.message.clearErrors()
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, TENANT_A)
    }

    private List<Map> listRuns() {
        Map out = ec.service.sync().name("facade.ReconciliationFacadeServices.list#SavedRuns")
                .parameters([pageIndex: 0, pageSize: 200, query: ""]).disableAuthz().call()
        return (List<Map>) (out.savedRuns ?: [])
    }

    @Test
    void "withRequestedTenant(B) reads B saved runs while the preference stays A"() {
        List<Map> underB = TenantAccessSupport.withRequestedTenant(TENANT_B) { listRuns() }
        assertTrue(underB.any { it.savedRunId == savedRunB }, "B's run is listed under B")
        assertTrue(underB.every { it.companyUserGroupId == null || it.companyUserGroupId == TENANT_B }, underB*.companyUserGroupId.toString())
        assertFalse(listRuns().any { it.savedRunId == savedRunB }, "without a request the tenant is A, which does not see B's run")
        assertEquals(TENANT_A, ec.user.getPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY))
    }

    @Test
    void "a foreign tenant is refused"() {
        assertThrows(ArtifactAuthorizationException) {
            TenantAccessSupport.withRequestedTenant(FOREIGN) { TenantAccessSupport.currentActiveTenantUserGroupId(ec) }
        }
        assertEquals(TENANT_A, ec.user.getPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY))
    }

    @Test
    void "an ended membership is refused"() {
        assertThrows(ArtifactAuthorizationException) {
            TenantAccessSupport.withRequestedTenant(ENDED) { TenantAccessSupport.currentActiveTenantUserGroupId(ec) }
        }
    }

    @Test
    void "no requested tenant keeps today behaviour"() {
        assertEquals(TENANT_A, TenantAccessSupport.currentActiveTenantUserGroupId(ec))
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, TENANT_B)
        assertEquals(TENANT_B, TenantAccessSupport.currentActiveTenantUserGroupId(ec))
    }

    @Test
    void "syncUserContext puts the requested tenant on the user context"() {
        TenantAccessSupport.withRequestedTenant(TENANT_B) { TenantAccessSupport.syncUserContext(ec) }
        assertEquals(TENANT_B, ec.user.context.activeTenantUserGroupId)
        TenantAccessSupport.syncUserContext(ec)
        assertEquals(TENANT_A, ec.user.context.activeTenantUserGroupId)
        assertEquals(TENANT_A, ec.user.getPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY))
    }

    @Test
    void "a run started under the requested tenant is recorded under it"() {
        Map out = TenantAccessSupport.withRequestedTenant(TENANT_B) {
            ec.service.sync().name("facade.ReconciliationFacadeServices.run#SavedRunDiff").parameters([
                    savedRunId: savedRunB, file1Name: "a.csv", file1Text: "order_id\nA1\nA2\n",
                    file2Name: "b.csv", file2Text: "order_id\nA2\nA3\n", hasHeader: true]).disableAuthz().call()
        }
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        String resultId = out.runResult.reconciliationRunResultId as String
        assertNotNull(resultId)
        def row = ec.entity.find("darpan.reconciliation.ReconciliationRunResult")
                .condition("reconciliationRunResultId", resultId).disableAuthz().useCache(false).one()
        assertEquals(TENANT_B, row.companyUserGroupId)
        assertEquals(TENANT_A, ec.user.getPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY))
    }

    private void seedTenant(String tenantId, String label, Timestamp thruDate, boolean member = true) {
        ec.entity.makeValue("moqui.security.UserGroup").setAll([
                userGroupId: tenantId, description: label,
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]).createOrUpdate()
        if (!member) return
        ec.entity.makeValue("moqui.security.UserGroupMember").setAll([
                userGroupId: tenantId, userId: TEST_USER_ID, fromDate: FROM, thruDate: thruDate]).createOrUpdate()
        ec.entity.makeValue(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME).setAll([
                tenantUserGroupId: tenantId, userId: TEST_USER_ID,
                permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID,
                fromDate: FROM, thruDate: thruDate]).createOrUpdate()
    }
}
