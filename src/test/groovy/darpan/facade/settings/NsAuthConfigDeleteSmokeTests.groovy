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
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-114. delete#NsAuthConfig removes a profile only when nothing would be left pointing at it: the owner
 * may delete an unused, unshared profile; anything that still signs in with it is named instead; a peer or a
 * stranger is refused exactly as the Shopify delete refuses them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NsAuthConfigDeleteSmokeTests {
    private static final String OWNER = "KREWE"
    private static final String MEMBER = "NS_DELETE_MEMBER"
    private static final String STRANGER = "NS_DELETE_STRANGER"
    private static final String TEST_USER_ID = "TEST_CUSTOMER_USER"
    private static final Timestamp TEST_FROM_DATE = Timestamp.valueOf("2026-04-23 00:00:00")
    private static final Timestamp GRANT_FROM_DATE = Timestamp.valueOf("2026-01-01 00:00:00")
    private static final String SERVICE = "facade.SettingsFacadeServices.delete#NsAuthConfig"

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "ns-auth-config-delete")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        upsert("moqui.basic.EnumerationType", [enumTypeId: "DarpanSharedConfigType"],
                [description: "Darpan API source config types that support cross-tenant sharing"])
        upsert("moqui.basic.Enumeration", [enumId: "SCFG_NS_AUTH"],
                [description: "NetSuite auth config (darpan.reconciliation.NsAuthConfig)", enumTypeId: "DarpanSharedConfigType"])
        upsert("moqui.basic.EnumerationType", [enumTypeId: "DarpanSystemSource"], [description: "Darpan system source"])
        upsert("moqui.basic.Enumeration", [enumId: "NETSUITE"], [description: "NetSuite", enumTypeId: "DarpanSystemSource"])
        seedTenant(MEMBER)
        seedTenant(STRANGER)
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void resetState() {
        ec.message.clearErrors()
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, OWNER)
    }

    @Test
    void ownerDeletesAnUnusedProfileAndItsEndpointDecisions() {
        seedAuth("DEL_UNUSED")
        upsert("darpan.reconciliation.SourceConfigEndpointAccess",
                [configTypeEnumId: "SCFG_NS_AUTH", configId: "DEL_UNUSED", systemEnumId: "NETSUITE"],
                [isEnabled: "N", companyUserGroupId: OWNER])

        Map<String, Object> result = delete("DEL_UNUSED")

        assertTrue(result.ok as boolean, "${result.errors}")
        assertTrue(result.deleted as boolean)
        assertEquals("DEL_UNUSED", result.deletedNsAuthConfigId)
        assertNull(find("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "DEL_UNUSED"]))
        assertNull(find("darpan.reconciliation.SourceConfigEndpointAccess",
                [configTypeEnumId: "SCFG_NS_AUTH", configId: "DEL_UNUSED", systemEnumId: "NETSUITE"]))
    }

    @Test
    void aProfileStillInUseIsKeptAndEveryUserIsNamed() {
        seedAuth("DEL_USED")
        upsert("darpan.reconciliation.NsRestletConfig", [nsRestletConfigId: "DEL_USED_EP"], [
                description: "Invoice export", companyUserGroupId: OWNER, endpointUrl: "https://1.restlets.api.netsuite.com/x",
                httpMethod: "POST", nsAuthConfigId: "DEL_USED", connectTimeoutSeconds: 30, readTimeoutSeconds: 60, isActive: "Y"])
        ["DEL_USED_Q1", "DEL_USED_Q2"].each { String id ->
            upsert("darpan.reconciliation.NsSuiteQlSourceQuery", [nsSuiteQlSourceQueryId: id],
                    [nsAuthConfigId: "DEL_USED", recordType: "SalesOrd", companyUserGroupId: OWNER, isActive: "Y"])
        }
        upsert("darpan.rule.RuleSet", [ruleSetId: "DEL_USED_RS"], [ruleSetName: "Uses the profile", companyUserGroupId: OWNER,
                explosionPath: "records", primaryKeyPath: "internalId"])
        upsert("darpan.rule.RuleSetCompareScope", [compareScopeId: "DEL_USED_SC"], [ruleSetId: "DEL_USED_RS",
                scopeMode: "EVALUATE", objectType: "ORDER", companyUserGroupId: OWNER])
        upsert("darpan.rule.RuleSetCompareSource", [compareScopeId: "DEL_USED_SC", fileSide: "FILE_1"], [
                systemEnumId: "NETSUITE", sourceConfigType: "NETSUITE_AUTH",
                sourceConfigId: "DEL_USED", companyUserGroupId: OWNER])

        Map<String, Object> result = delete("DEL_USED")

        assertFalse(result.ok as boolean)
        assertFalse(result.deleted as boolean)
        assertEquals(["NS Auth config 'DEL_USED' is still used by 1 RESTlet endpoint, 2 NetSuite checks, 1 saved run. " +
                "Move them to another auth profile or delete them first."], result.errors)
        assertNotNull(find("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "DEL_USED"]))
    }

    @Test
    void theOwnerCannotDeleteAProfileStillSharedWithAnotherTenant() {
        seedAuth("DEL_SHARED")
        grant("DEL_SHARED", MEMBER)

        Map<String, Object> result = delete("DEL_SHARED")

        assertFalse(result.ok as boolean)
        assertEquals(["NS Auth config 'DEL_SHARED' is shared with other tenants. Stop sharing it with every tenant before deleting it."],
                result.errors)
        assertNotNull(find("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "DEL_SHARED"]))
    }

    @Test
    void aPeerIsToldToStopSharingRatherThanDeleting() {
        seedAuth("DEL_PEER")
        grant("DEL_PEER", MEMBER)
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, MEMBER)

        Map<String, Object> result = delete("DEL_PEER")

        assertFalse(result.ok as boolean)
        assertEquals(["NS Auth config 'DEL_PEER' is shared from another tenant. Stop sharing it instead of deleting it."], result.errors)
        assertNotNull(find("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "DEL_PEER"]))
    }

    @Test
    void aStrangerCannotTellAForeignProfileFromAMissingOne() {
        seedAuth("DEL_FOREIGN")
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, STRANGER)

        Map<String, Object> foreign = delete("DEL_FOREIGN")
        ec.message.clearErrors()
        Map<String, Object> missing = delete("DEL_NEVER_EXISTED")

        assertEquals(["NS Auth config 'DEL_FOREIGN' was not found."], foreign.errors)
        assertEquals(["NS Auth config 'DEL_NEVER_EXISTED' was not found."], missing.errors)
        assertNotNull(find("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "DEL_FOREIGN"]))
    }

    private Map<String, Object> delete(String nsAuthConfigId) {
        return (Map<String, Object>) ec.service.sync().name(SERVICE).parameters([nsAuthConfigId: nsAuthConfigId])
                .disableAuthz().call()
    }

    private def find(String entityName, Map<String, Object> pkFields) {
        return ec.entity.find(entityName).condition(pkFields).disableAuthz().useCache(false).one()
    }

    private void seedAuth(String id) {
        upsert("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: id], [description: id, companyUserGroupId: OWNER,
                createdByUserId: TEST_USER_ID, authType: "BASIC", username: "user", password: "secret", isActive: "Y"])
    }

    private void grant(String configId, String tenantId) {
        upsert("darpan.auth.ConfigTenantAccess",
                [configTypeEnumId: "SCFG_NS_AUTH", configId: configId, tenantUserGroupId: tenantId, fromDate: GRANT_FROM_DATE],
                [thruDate: null, grantedByUserId: TEST_USER_ID])
    }

    private void seedTenant(String tenantId) {
        upsert("moqui.security.UserGroup", [userGroupId: tenantId],
                [description: tenantId, groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        upsert("moqui.security.UserGroupMember", [userGroupId: tenantId, userId: TEST_USER_ID, fromDate: TEST_FROM_DATE], [:])
        upsert(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: tenantId,
                userId: TEST_USER_ID, permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID,
                fromDate: TEST_FROM_DATE], [:])
    }

    private void upsert(String entityName, Map<String, Object> pkFields, Map<String, Object> fields) {
        ec.artifactExecution.disableAuthz()
        ArtifactExecutionInfo aei = ec.artifactExecution.push("seedNsAuthDeleteFixtures", ArtifactExecutionInfo.AT_OTHER,
                ArtifactExecutionInfo.AUTHZA_ALL, false)
        ec.artifactExecution.setAnonymousAuthorizedAll()
        try {
            if (ec.entity.find(entityName).condition(pkFields).disableAuthz().useCache(false).one() != null) return
            ReconciliationSmokeTestSupport.insertEntityDirect(ec, entityName, pkFields + fields)
        } finally {
            ec.artifactExecution.pop(aei)
            ec.artifactExecution.enableAuthz()
        }
    }
}
