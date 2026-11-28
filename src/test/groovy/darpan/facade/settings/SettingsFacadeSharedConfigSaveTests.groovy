package darpan.facade.settings

import darpan.reconciliation.support.SharedMoqui
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
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-005 Task 8, Finding A.
 *
 * <p>Task 5 made shared NsAuthConfig rows visible to a member tenant in
 * {@code list#NsAuthConfigs}, but {@code save#NsAuthConfig} kept the strict owner-only {@code TenantAccessSupport}.
 * {@code requireTenantRecordAccess} gate — the exact half-open "sees a config it cannot use" state
 * DAR-BE-005 exists to close. These tests prove the fix from both directions: a member tenant with
 * an active grant can now save the shared row it can already see, and a stranger with no standing
 * still cannot distinguish "exists, not mine" from "does not exist" through the response text.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SharedMoqui
class SettingsFacadeSharedConfigSaveTests {
    private static final String OWNER = "KREWE"
    private static final String MEMBER = "SHARED_CFG_MEMBER"
    private static final String STRANGER = "SHARED_CFG_STRANGER"
    private static final String TEST_USER_ID = "TEST_CUSTOMER_USER"
    private static final Timestamp TEST_FROM_DATE = Timestamp.valueOf("2026-04-23 00:00:00")
    private static final Timestamp GRANT_FROM_DATE = Timestamp.valueOf("2026-01-01 00:00:00")

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "settings-shared-config-save")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        seedSharedConfigTypeEnum()
        seedTenant(MEMBER)
        seedTenant(STRANGER)
        seedFixtures()
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

    // --- 1. the hole being closed: a member tenant with an active grant can now save --------------

    @Test
    void memberTenantWithActiveGrantCanSaveASharedNsAuthConfig() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, MEMBER)

        Map<String, Object> result = saveFacade("facade.SettingsFacadeServices.save#NsAuthConfig", [
                nsAuthConfigId: "SHARED_AUTH",
                description   : "Member edited shared auth",
                authType      : "BASIC",
                username      : "member-edited-user",
        ])

        assertTrue((Boolean) result.ok, result.errors?.toString())
        def stored = findOne("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "SHARED_AUTH"])
        assertEquals("Member edited shared auth", stored.description)
        // Ownership never transfers to the saving peer (decision 3) — only the row content changes.
        assertEquals(OWNER, stored.companyUserGroupId)
    }

    // --- 2. existence oracle stays closed --------------------------------------------------------

    @Test
    void strangerGetsTheCollapsedNotFoundTextNotTheOldDistinguishableTextForARealForeignNsAuthConfig() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, STRANGER)

        Map<String, Object> result = saveFacade("facade.SettingsFacadeServices.save#NsAuthConfig", [
                nsAuthConfigId: "ORACLE_AUTH",
                description   : "Stranger probe",
                authType      : "BASIC",
                username      : "stranger-user",
        ])

        assertFalse((Boolean) result.ok)
        // Pre-fix this was "NS Auth config 'ORACLE_AUTH' is not available in your active tenant." —
        // a distinguishable text that told a stranger the id exists somewhere.
        assertEquals(["NS Auth config 'ORACLE_AUTH' was not found."], (List<String>) result.errors)
    }

    @Test
    void aNeverUsedNsAuthConfigIdIsRefusedExactlyLikeAForeignOneAndCreatesNothing() {
        // DAR-BE-135: save#NsAuthConfig used to create a row under any free id, so a free id said
        // "created" where a foreign one said "not found" — the same oracle by another door. A named id
        // now only updates; a create leaves the id out and the server mints one.
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, STRANGER)
        Map<String, Object> params = [description: "Stranger probe", authType: "BASIC",
                                      username: "stranger-user", password: "stranger-password"]

        Map<String, Object> foreign = saveFacade("facade.SettingsFacadeServices.save#NsAuthConfig", params + [nsAuthConfigId: "ORACLE_AUTH"])
        ec.message.clearErrors()
        Map<String, Object> free = saveFacade("facade.SettingsFacadeServices.save#NsAuthConfig", params + [nsAuthConfigId: "STRANGER_OWN_NEW_AUTH"])

        assertEquals(["NS Auth config 'ORACLE_AUTH' was not found."], (List<String>) foreign.errors)
        assertEquals(["NS Auth config 'STRANGER_OWN_NEW_AUTH' was not found."], (List<String>) free.errors)
        assertNull(findOne("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "STRANGER_OWN_NEW_AUTH"]))
    }

    @Test
    void aStrangerCreatesItsOwnNsAuthConfigUnderAMintedId() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, STRANGER)

        Map<String, Object> result = saveFacade("facade.SettingsFacadeServices.save#NsAuthConfig", [
                description   : "Stranger's own new config",
                authType      : "BASIC",
                username      : "stranger-user",
                password      : "stranger-password",
        ])

        assertTrue((Boolean) result.ok, result.errors?.toString())
        String mintedId = (result.savedAuthConfig as Map).nsAuthConfigId as String
        assertTrue(mintedId ==~ /\d+/, "a new auth config takes a minted id, got ${mintedId}")
        assertEquals(STRANGER, findOne("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: mintedId]).companyUserGroupId)
    }

    /** DAR-BE-255: a blank private key is kept only while the token URL (the NetSuite account) is the same. */
    @Test
    void aMovedTokenUrlNeedsThePrivateKeyAgain() {
        String tokenUrl = "https://1111111.suitetalk.api.netsuite.com/services/rest/auth/oauth2/v1/token"
        upsertEntityValue("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "MOVE_AUTH"], [
                nsAuthConfigId: "MOVE_AUTH", companyUserGroupId: OWNER, createdByUserId: TEST_USER_ID, authType: "OAUTH2_M2M_JWT",
                tokenUrl: tokenUrl, clientId: "client", certId: "cert", privateKeyPem: "krewe-private-key", isActive: "Y"])
        Map<String, Object> base = [nsAuthConfigId: "MOVE_AUTH", authType: "OAUTH2_M2M_JWT", clientId: "client", certId: "cert"]

        Map<String, Object> same = saveFacade("facade.SettingsFacadeServices.save#NsAuthConfig", base + [tokenUrl: " " + tokenUrl + "/"])
        assertTrue((Boolean) same.ok, same.errors?.toString())
        assertEquals("krewe-private-key", findOne("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "MOVE_AUTH"]).privateKeyPem,
                "the same token URL keeps the stored key")

        ec.message.clearErrors()
        String otherAccount = "https://2222222.suitetalk.api.netsuite.com/services/rest/auth/oauth2/v1/token"
        Map<String, Object> refused = saveFacade("facade.SettingsFacadeServices.save#NsAuthConfig", base + [tokenUrl: otherAccount])
        assertFalse((Boolean) refused.ok, "a moved token URL kept the stored key: ${refused}")
        assertEquals(["Enter the private key again: the token URL changed, so the saved private key is not sent to the new address."],
                (List<String>) refused.errors)
        assertEquals("krewe-private-key", findOne("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "MOVE_AUTH"]).privateKeyPem)

        ec.message.clearErrors()
        Map<String, Object> moved = saveFacade("facade.SettingsFacadeServices.save#NsAuthConfig", base + [tokenUrl: otherAccount, privateKeyPem: "new-private-key"])
        assertTrue((Boolean) moved.ok, moved.errors?.toString())
        def row = findOne("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "MOVE_AUTH"])
        assertEquals([otherAccount, "new-private-key"], [row.tokenUrl, row.privateKeyPem])
    }

    // --- helpers ------------------------------------------------------------------------------

    private Map<String, Object> saveFacade(String serviceName, Map<String, Object> parameters) {
        return (Map<String, Object>) ec.service.sync()
                .name(serviceName)
                .parameters(parameters)
                .disableAuthz()
                .call()
    }

    private def findOne(String entityName, Map<String, Object> pkFields) {
        return ec.entity.find(entityName)
                .condition(pkFields)
                .disableAuthz()
                .useCache(false)
                .one()
    }

    private void deleteEntity(String entityName, Map<String, Object> pkFields) {
        ec.entity.find(entityName)
                .condition(pkFields)
                .disableAuthz()
                .deleteAll()
    }

    private void seedSharedConfigTypeEnum() {
        upsertEntityValue("moqui.basic.EnumerationType", [enumTypeId: "DarpanSharedConfigType"], [
                enumTypeId : "DarpanSharedConfigType",
                description: "Darpan API source config types that support cross-tenant sharing",
        ])
        upsertEntityValue("moqui.basic.Enumeration", [enumId: "SCFG_NS_AUTH"], [
                enumId     : "SCFG_NS_AUTH",
                description: "NetSuite auth config (darpan.reconciliation.NsAuthConfig)",
                enumTypeId : "DarpanSharedConfigType",
        ])
    }

    private void seedTenant(String tenantId) {
        upsertEntityValue("moqui.security.UserGroup", [userGroupId: tenantId], [
                userGroupId    : tenantId,
                description    : tenantId,
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID,
        ])
        upsertEntityValue("moqui.security.UserGroupMember", [
                userGroupId: tenantId,
                userId     : TEST_USER_ID,
                fromDate   : TEST_FROM_DATE,
        ], [
                userGroupId: tenantId,
                userId     : TEST_USER_ID,
                fromDate   : TEST_FROM_DATE,
        ])
        upsertEntityValue(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [
                tenantUserGroupId    : tenantId,
                userId               : TEST_USER_ID,
                permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID,
                fromDate             : TEST_FROM_DATE,
        ], [
                tenantUserGroupId    : tenantId,
                userId               : TEST_USER_ID,
                permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID,
                fromDate             : TEST_FROM_DATE,
        ])
    }

    private void seedFixtures() {
        upsertEntityValue("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "SHARED_AUTH"], [
                nsAuthConfigId    : "SHARED_AUTH",
                description       : "Krewe shared auth",
                companyUserGroupId: OWNER,
                createdByUserId   : TEST_USER_ID,
                authType          : "BASIC",
                username          : "krewe-user",
                password          : "krewe-password",
                apiToken          : "krewe-api-token",
                privateKeyPem     : "krewe-private-key",
                isActive          : "Y",
        ])
        upsertEntityValue("darpan.auth.ConfigTenantAccess", [
                configTypeEnumId : "SCFG_NS_AUTH",
                configId         : "SHARED_AUTH",
                tenantUserGroupId: MEMBER,
                fromDate         : GRANT_FROM_DATE,
        ], [
                configTypeEnumId : "SCFG_NS_AUTH",
                configId         : "SHARED_AUTH",
                tenantUserGroupId: MEMBER,
                fromDate         : GRANT_FROM_DATE,
                thruDate         : null,
                grantedByUserId  : TEST_USER_ID,
        ])

        // Foreign-owned rows for the stranger oracle tests below. Not shared with anyone.
        upsertEntityValue("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "ORACLE_AUTH"], [
                nsAuthConfigId    : "ORACLE_AUTH",
                description       : "Krewe unshared auth",
                companyUserGroupId: OWNER,
                createdByUserId   : TEST_USER_ID,
                authType          : "BASIC",
                username          : "krewe-oracle-user",
                password          : "krewe-oracle-password",
                isActive          : "Y",
        ])
    }

    private void upsertEntityValue(String entityName, Map<String, Object> pkFields, Map<String, Object> fields) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        ArtifactExecutionInfo aei = ec.artifactExecution.push(
                "seedSharedConfigSaveFixtures",
                ArtifactExecutionInfo.AT_OTHER,
                ArtifactExecutionInfo.AUTHZA_ALL,
                false
        )
        ec.artifactExecution.setAnonymousAuthorizedAll()
        try {
            def existing = ec.entity.find(entityName)
                    .condition(pkFields)
                    .disableAuthz()
                    .useCache(false)
                    .one()
            if (existing != null) return
            ReconciliationSmokeTestSupport.insertEntityDirect(ec, entityName, fields)
        } finally {
            ec.artifactExecution.pop(aei)
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
    }
}
