package darpan.facade.settings

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-079 — save#SftpServer must not overwrite another tenant's SFTP server, with authz ON.
 *
 * <p>SettingsFacadeTenantFilteringSmokeTests covers the same case but calls the service with
 * {@code disableAuthz()}, so the DARPAN_SCOPE_SFTP_SERVER filter never applies there and its
 * existence check sees the foreign row. In production the filter hides that row, the ownership
 * gate is skipped, and {@code store#} (whose refresh/update are PK-only, no filter) overwrites it.
 * Here the caller is a real DARPAN_USER member and the harness grant is removed, so the facade
 * service runs exactly as it does behind /rpc/json.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SftpServerCrossTenantSmokeTests {

    private static final String KREWE = "KREWE"
    private static final String GORJANA = "GORJANA"
    private static final String EDITOR_ID = "TEST_CUSTOMER_USER"
    private static final String EDITOR_USERNAME = "test.customer"
    private static final Timestamp FROM_DATE = Timestamp.valueOf("2026-04-21 00:00:00")
    private static final String SAVE_SERVICE = "facade.SettingsFacadeServices.save#SftpServer"

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "sftp-server-cross-tenant")

        // The security model under test: DARPAN_FACADE_APP grants and the DARPAN_ACTIVE_COMPANY_SCOPE
        // filter rows (incl. DARPAN_SCOPE_SFTP_SERVER). dummyFks: the file forward-references
        // reference data this bare H2 does not carry.
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)   // KREWE tenant + editor membership
        ReconciliationSmokeTestSupport.seedSftpServerFixtures(ec)

        // GORJANA is a tenant the editor does NOT belong to.
        insert("moqui.security.UserGroup", [userGroupId: GORJANA, description: "Gorjana",
                                            groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        insert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: EDITOR_ID, fromDate: FROM_DATE])

        insert("darpan.reconciliation.SftpServer", [sftpServerId: "VICTIM_SFTP", companyUserGroupId: GORJANA,
                createdByUserId: "GORJANA_ADMIN", scopeEnumId: "DARPAN_SFTP_TENANT", host: "gorjana.sftp.test",
                port: 22, username: "gorjana-user", password: "gorjana-secret"])
        insert("darpan.reconciliation.SftpServer", [sftpServerId: "OWN_SFTP", companyUserGroupId: KREWE,
                createdByUserId: EDITOR_ID, scopeEnumId: "DARPAN_SFTP_TENANT", host: "krewe.sftp.test",
                port: 22, username: "krewe-user", password: "krewe-secret"])

        // UserFacade caches a user's groups per login and reuses that login for the same username, so
        // the DARPAN_USER membership above is only seen after a real switch of user.
        insert("moqui.security.UserAccount", [userId: "SFTP_XT_OTHER", username: "sftp.xt.other",
                                              userFullName: "Other", currentPassword: "", disabled: "N"])
        assertTrue(ec.user.internalLoginUser("sftp.xt.other"))
        actAsKreweEditor()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void actAsKreweEditor() {
        assertTrue(ec.user.internalLoginUser(EDITOR_USERNAME), "could not log in ${EDITOR_USERNAME}")
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, KREWE)
        TenantAccessSupport.syncUserContext(ec)
        ec.message.clearErrors()
    }

    @Test
    void filterIsActiveUnderProductionAuthz() {
        // Control: proves the filter applies here, so the refusal below is not a hollow pass.
        Map<String, Object> out = withProductionAuthz {
            callService("facade.SettingsFacadeServices.list#SftpServers", [pageIndex: 0, pageSize: 20, query: ""])
        }
        assertTrue(out.ok as Boolean, "list#SftpServers failed under production authz: ${out.errors}")
        List<String> ids = ((out.servers ?: []) as List<Map>).collect { it.sftpServerId as String }
        assertTrue(ids.contains("OWN_SFTP"), "own server missing from ${ids}")
        assertFalse(ids.contains("VICTIM_SFTP"), "foreign server leaked into ${ids}")
    }

    @Test
    void editorCannotOverwriteAnotherTenantsServer() {
        Map<String, Object> out = withProductionAuthz {
            callService(SAVE_SERVICE, [sftpServerId: "VICTIM_SFTP", host: "attacker.sftp.test", port: 22,
                                username: "attacker", password: "attacker-secret"])
        }
        assertFalse(out.ok as Boolean, "cross-tenant save was accepted: ${out}")

        def victim = findRow("VICTIM_SFTP")
        assertEquals(GORJANA, victim.companyUserGroupId, "victim row changed owner")
        assertEquals("gorjana.sftp.test", victim.host, "victim row host was overwritten")
        assertEquals("gorjana-user", victim.username)
        assertEquals("gorjana-secret", victim.password, "victim credentials were overwritten")
        assertEquals("GORJANA_ADMIN", victim.createdByUserId)
    }

    @Test
    void editorCanStillUpdateOwnServerAndKeepStoredSecret() {
        Map<String, Object> out = withProductionAuthz {
            callService(SAVE_SERVICE, [sftpServerId: "OWN_SFTP", host: "krewe2.sftp.test", port: 2222, username: "krewe-user"])
        }
        assertTrue(out.ok as Boolean, "own update failed: ${out.errors}")
        def own = findRow("OWN_SFTP")
        assertEquals(KREWE, own.companyUserGroupId)
        assertEquals("krewe2.sftp.test", own.host)
        assertEquals("krewe-secret", own.password, "blank password must keep the stored one")
    }

    @Test
    void editorCanCreateANewServerOwnedByTheActiveTenant() {
        Map<String, Object> out = withProductionAuthz {
            callService(SAVE_SERVICE, [sftpServerId: "NEW_KREWE_SFTP", host: "new.sftp.test", port: 22,
                                username: "new-user", password: "new-secret"])
        }
        assertTrue(out.ok as Boolean, "create failed: ${out.errors}")
        def created = findRow("NEW_KREWE_SFTP")
        assertEquals(KREWE, created.companyUserGroupId)
        assertEquals(EDITOR_ID, created.createdByUserId)
    }

    private Map<String, Object> callService(String serviceName, Map<String, Object> params) {
        return (Map<String, Object>) ec.service.sync().name(serviceName).parameters(params).call()
    }

    private def findRow(String sftpServerId) {
        return ec.entity.find("darpan.reconciliation.SftpServer").condition("sftpServerId", sftpServerId)
                .disableAuthz().useCache(false).one()
    }

    private void insert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }

    /** Production posture: no harness grant on the stack, authz ON. Copied from MigrationAdminServiceSmokeTests. */
    private <T> T withProductionAuthz(Closure<T> body) {
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
            ec.message.clearErrors()
        }
    }
}
