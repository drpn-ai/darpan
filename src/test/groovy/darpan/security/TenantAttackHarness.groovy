package darpan.security

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Connection
import java.sql.Statement
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-277 — shared fixture for the adversarial tenant-isolation tests.
 *
 * <p>Two made-up tenants (ADV_TENANT_A, ADV_TENANT_B). The attacker, adv.attacker, is a member (editor) of
 * tenant A only; adv.victim is a member of tenant B only. Every attack runs as the attacker against B's rows
 * with the production authz posture: the harness's anonymous-authorized-all frame removed, authz ON, the
 * security seed (DARPAN_FACADE_APP grants + DARPAN_ACTIVE_COMPANY_SCOPE filter) loaded.</p>
 */
class TenantAttackHarness {

    static final String TENANT_A = "ADV_TENANT_A"
    static final String TENANT_B = "ADV_TENANT_B"
    static final String ATTACKER_ID = "ADV_ATTACKER"
    static final String ATTACKER = "adv.attacker"
    static final String VICTIM_ID = "ADV_VICTIM"
    static final String VICTIM = "adv.victim"
    static final String PREF_KEY = TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY
    static final Timestamp FROM_DATE = Timestamp.valueOf("2026-04-21 00:00:00")

    /** A facade service every DARPAN_USER is granted; its grant is what binds the entity filter. */
    static final String FACADE_GRANT = "facade.ReconciliationFacadeServices.list#Reconciliations"

    final ExecutionContext ec

    TenantAttackHarness(String dbName) {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, dbName)
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/DarpanSystemSourceSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SourceSystemConnectorSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)   // permission-group enums and DARPAN_COMPANY_EDITOR

        seedTenant(TENANT_A, "Adversarial A")
        seedTenant(TENANT_B, "Adversarial B")
        seedUser(ATTACKER_ID, ATTACKER, TENANT_A)
        seedUser(VICTIM_ID, VICTIM, TENANT_B)
        // UserFacade caches groups per login: switch user once so the memberships above are seen.
        actAs(VICTIM, TENANT_B)
        actAs(ATTACKER, TENANT_A)
    }

    void close() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    void seedTenant(String tenantId, String label) {
        insert("moqui.security.UserGroup", [userGroupId: tenantId, description: label,
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
    }

    void seedUser(String userId, String username, String tenantId) {
        insert("moqui.security.UserAccount", [userId: userId, username: username, userFullName: username,
                currentPassword: "", disabled: "N", emailAddress: "${username}@example.invalid".toString()])
        insert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: userId, fromDate: FROM_DATE])
        insert("moqui.security.UserGroupMember", [userGroupId: tenantId, userId: userId, fromDate: FROM_DATE])
        insert(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: tenantId,
                userId: userId, permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, fromDate: FROM_DATE])
    }

    void actAs(String username, String tenant) {
        assertTrue(ec.user.internalLoginUser(username), "could not log in ${username}")
        if (tenant) ec.user.setPreference(PREF_KEY, tenant)
        TenantAccessSupport.syncUserContext(ec)
        ec.message.clearErrors()
    }

    void asAttacker() {
        actAs(ATTACKER, TENANT_A)
        assertEquals(TENANT_A, ec.user.context.activeTenantUserGroupId, "test precondition: attacker active in A")
    }

    /** Calls a service exactly as /rpc/json would for the logged-in user: production authz, no inherited grant. */
    Map<String, Object> call(String service, Map<String, Object> params = [:]) {
        return asProduction {
            Map<String, Object> out
            try {
                out = ec.service.sync().name(service).parameters(params).call() as Map<String, Object>
            } catch (Throwable t) {
                out = [thrown: t.toString()]
            }
            Map<String, Object> result = new LinkedHashMap<>(out ?: [:])
            result._errors = new ArrayList(ec.message.errors) + (ec.message.validationErrors*.toString() ?: [])
            ec.message.clearErrors()
            return result
        } as Map<String, Object>
    }

    /** Production posture: no harness grant on the stack, authz ON. */
    def <T> T asProduction(Closure<T> body) {
        def harnessFrame = ec.artifactExecution.peek()
        assertEquals("smokeTests", harnessFrame?.name, "test precondition: unexpected artifact stack")
        ec.artifactExecution.pop(harnessFrame)
        boolean wasDisabled = ec.artifactExecution.authzDisabled
        ec.artifactExecution.enableAuthz()
        try {
            assertFalse(ec.artifactExecution.authzDisabled, "test precondition: authz must be ON")
            assertTrue(ec.artifactExecution.stack.isEmpty(), "test precondition: no inherited grant on the stack")
            return body.call()
        } finally {
            if (wasDisabled) ec.artifactExecution.disableAuthz()
            ec.artifactExecution.push("smokeTests", ArtifactExecutionInfo.AT_OTHER, ArtifactExecutionInfo.AUTHZA_ALL, false)
            ec.artifactExecution.setAnonymousAuthorizedAll()
        }
    }

    /** Runs {@code body} inside a facade service's frame, authorized as the engine authorizes a facade call. */
    def <T> T underFacadeGrant(Closure<T> body) {
        return asProduction {
            def aei = ec.artifactExecution.push(FACADE_GRANT, ArtifactExecutionInfo.AT_SERVICE,
                    ArtifactExecutionInfo.AUTHZA_ALL, true)
            try { return body.call() } finally { ec.artifactExecution.pop(aei) }
        }
    }

    void insert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }

    /** Raw row, no filter, no authz. */
    def raw(String entityName, Map<String, Object> pk) {
        return ec.entity.find(entityName).condition(pk).disableAuthz().useCache(false).one()
    }

    List rawList(String entityName, Map<String, Object> cond) {
        return ec.entity.find(entityName).condition(cond).disableAuthz().useCache(false).list()
    }

    void withoutReferentialIntegrity(Closure body) {
        Connection connection = ec.entity.getConnection(ec.entity.getEntityGroupName("darpan.reconciliation.Reconciliation"))
        try {
            Statement statement = connection.createStatement()
            statement.execute("SET REFERENTIAL_INTEGRITY FALSE")
            try { body.call() } finally {
                statement.execute("SET REFERENTIAL_INTEGRITY TRUE")
                statement.close()
            }
        } finally {
            connection.close()
        }
    }
}
