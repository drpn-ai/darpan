package darpan.agent

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-CLI-003 Task 4: the query log is tenant-owned and only a tenant admin lists it. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentQueryLogCrossTenantSmokeTests {
    private static final String TEST_USER_ID = "TEST_CUSTOMER_USER"
    private static final String TENANT_A = "KREWE"
    private static final String TENANT_B = "GORJANA"
    private static final String TENANT_EDITOR_ONLY = "DOLLSKILL"
    private static final Timestamp FROM = Timestamp.valueOf("2026-04-23 00:00:00")

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "agent-query-log-cross-tenant-smoke")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: TenantAccessSupport.DARPAN_TENANT_ADMIN_GROUP_ID,
                description: "Darpan tenant admin"]).createOrUpdate()
        seedTenant(TENANT_A, "Krewe", TenantAccessSupport.DARPAN_TENANT_ADMIN_GROUP_ID)
        seedTenant(TENANT_B, "Gorjana", TenantAccessSupport.DARPAN_TENANT_ADMIN_GROUP_ID)
        seedTenant(TENANT_EDITOR_ONLY, "Dolls Kill", TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID)
        seedLog("LOG_A", TENANT_A)
        seedLog("LOG_B", TENANT_B)
        seedLog("LOG_D", TENANT_EDITOR_ONLY)
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void clear() { ec.message.clearErrors() }

    @Test
    void "tenant A's admin lists only A's query log rows"() {
        Map result = listAs(TENANT_A)
        assertTrue(result.ok as boolean, "${result.errors}")
        List ids = (result.logs as List).collect { it.agentQueryLogId }
        assertTrue(ids.contains("LOG_A"), "own row missing: ${ids}")
        assertFalse(ids.contains("LOG_B"), "another tenant's row leaked: ${ids}")
        assertFalse(ids.contains("LOG_D"), "another tenant's row leaked: ${ids}")
        assertFalse((result.logs as List).any { it.containsKey("companyUserGroupId") })
    }

    @Test
    void "a non-admin is refused"() {
        Map result = listAs(TENANT_EDITOR_ONLY)
        assertFalse(result.ok as boolean, "an editor listed the query log")
        assertTrue((result.logs as List).isEmpty())
    }

    @Test
    void "positive control: B's admin sees B's row"() {
        Map result = listAs(TENANT_B)
        assertTrue(result.ok as boolean, "${result.errors}")
        assertEquals(["LOG_B"], (result.logs as List).collect { it.agentQueryLogId })
    }

    @Test
    void "run writes one tenant-stamped row, and a failed query still leaves its row"() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, TENANT_A)
        AgentQueryLane.reset()
        Map ok = AgentQuerySupport.run(ec, [systemEnumId: "NETSUITE", connectionId: "C1", queryText: "SELECT 1"], { [rows: [[a: 1]]] })
        Map failed = AgentQuerySupport.run(ec, [systemEnumId: "NETSUITE", connectionId: "C1", queryText: "SELECT 2", secrets: ["hunter2"]],
                { throw new IllegalStateException("bad hunter2") })
        assertEquals(1, ok.rowCount)
        assertEquals("ERROR", failed.code)
        List rows = ec.entity.find("darpan.agent.AgentQueryLog").condition("companyUserGroupId", TENANT_A)
                .condition("connectionId", "C1").disableAuthz().useCache(false).list()
        assertEquals(["ERROR", "OK"], rows.collect { it.outcome as String }.sort())
        assertTrue(rows.every { it.userId == TEST_USER_ID && it.companyUserGroupId == TENANT_A })
        assertFalse(rows.any { it.queryText.toString().contains("hunter2") })
        assertEquals(0, AgentQueryLane.inFlight(TENANT_A))
    }

    private Map listAs(String tenantId) {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, tenantId)
        ec.message.clearErrors()
        Map result = (Map) ec.service.sync().name("facade.AgentFacadeServices.list#AgentQueryLogs")
                .parameters([pageSize: 100]).disableAuthz().call()
        return result
    }

    private void seedLog(String id, String tenantId) {
        ec.entity.makeValue("darpan.agent.AgentQueryLog").setAll([
                agentQueryLogId: id, companyUserGroupId: tenantId, userId: TEST_USER_ID, systemEnumId: "NETSUITE",
                connectionId: "SEED", queryHash: "h", queryText: "SELECT 1", rowCount: 1, durationMs: 5, outcome: "OK",
                createdDate: new Timestamp(System.currentTimeMillis()),
        ]).createOrUpdate()
    }

    private void seedTenant(String tenantId, String label, String permissionGroupId) {
        ec.entity.makeValue("moqui.security.UserGroup").setAll([
                userGroupId: tenantId, description: label,
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID,
        ]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserGroupMember").setAll([
                userGroupId: tenantId, userId: TEST_USER_ID, fromDate: FROM,
        ]).createOrUpdate()
        ec.entity.makeValue(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME).setAll([
                tenantUserGroupId: tenantId, userId: TEST_USER_ID, permissionUserGroupId: permissionGroupId, fromDate: FROM,
        ]).createOrUpdate()
    }
}
