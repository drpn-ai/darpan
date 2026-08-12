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
 * DAR-BE-005 Task 8, Finding A.
 *
 * <p>Task 5 made shared NsAuthConfig / NsRestletConfig rows visible to a member tenant in
 * {@code list#NsAuthConfigs} / {@code list#NsRestletConfigs}, but {@code save#NsAuthConfig} and
 * {@code save#NsRestletConfig} kept the strict owner-only {@code TenantAccessSupport}.
 * {@code requireTenantRecordAccess} gate — the exact half-open "sees a config it cannot use" state
 * DAR-BE-005 exists to close. These tests prove the fix from both directions: a member tenant with
 * an active grant can now save the shared row it can already see, and a stranger with no standing
 * still cannot distinguish "exists, not mine" from "does not exist" through the response text.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
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

    @Test
    void memberTenantWithActiveGrantCanSaveASharedNsRestletConfig() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, MEMBER)

        Map<String, Object> result = saveFacade("facade.SettingsFacadeServices.save#NsRestletConfig", [
                nsRestletConfigId: "SHARED_ENDPOINT",
                description      : "Member edited shared endpoint",
                endpointUrl      : "https://member-edit.suitetalk.api.netsuite.com/restlet",
                httpMethod       : "POST",
                nsAuthConfigId   : "SHARED_AUTH",
                headersJson      : "{}",
        ])

        assertTrue((Boolean) result.ok, result.errors?.toString())
        def stored = findOne("darpan.reconciliation.NsRestletConfig", [nsRestletConfigId: "SHARED_ENDPOINT"])
        assertEquals("Member edited shared endpoint", stored.description)
        assertEquals(OWNER, stored.companyUserGroupId)
    }

    // --- 2. existence oracle stays closed --------------------------------------------------------
    //
    // save#NsRestletConfig's referenced-NsAuthConfig check (SettingsFacadeServices.xml ~:797) has no
    // create fallback — an endpoint cannot silently create the auth config it references — so it is
    // a pure existence check, exactly like the Seam B validators in ReconciliationSavedRunSupport.
    // That makes it the one place under save#NsAuthConfig / save#NsRestletConfig where "a real
    // foreign-owned id" and "an id that does not exist at all" can be compared for byte-identical
    // text: query the SAME id before and after deleting the real row.
    //
    // The PRIMARY existence checks (~:604 in save#NsAuthConfig, ~:785 in save#NsRestletConfig) do NOT
    // have this property, and that is not a gap in the fix: both are create-or-update checks, so a
    // genuinely free id is a legitimate create (ok=true, no error), not a denial — there is no
    // "not found" branch to compare against an id that never existed. The last test below documents
    // that directly rather than asserting an equality that cannot hold.

    @Test
    void strangerGetsIdenticalErrorTextForARealForeignReferencedNsAuthConfigAndANonexistentOneWithinNsRestletConfigSave() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, STRANGER)

        Map<String, Object> realResult = saveFacade("facade.SettingsFacadeServices.save#NsRestletConfig", [
                nsRestletConfigId: "ORACLE_REF_ENDPOINT_1",
                description      : "Stranger probe",
                endpointUrl      : "https://stranger-probe.suitetalk.api.netsuite.com/restlet",
                httpMethod       : "POST",
                nsAuthConfigId   : "ORACLE_REF_AUTH",
                headersJson      : "{}",
        ])
        assertFalse((Boolean) realResult.ok)
        List<String> realErrors = (List<String>) realResult.errors

        // Remove the referenced auth config so the SAME id is now genuinely nonexistent, then probe
        // again with a fresh (never-used) nsRestletConfigId so the endpoint's own existence check
        // (site :785) stays silent both times and the referenced-config check is what's compared.
        deleteEntity("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "ORACLE_REF_AUTH"])
        ec.message.clearErrors()
        Map<String, Object> missingResult = saveFacade("facade.SettingsFacadeServices.save#NsRestletConfig", [
                nsRestletConfigId: "ORACLE_REF_ENDPOINT_2",
                description      : "Stranger probe",
                endpointUrl      : "https://stranger-probe.suitetalk.api.netsuite.com/restlet",
                httpMethod       : "POST",
                nsAuthConfigId   : "ORACLE_REF_AUTH",
                headersJson      : "{}",
        ])
        assertFalse((Boolean) missingResult.ok)
        List<String> missingErrors = (List<String>) missingResult.errors

        assertEquals(missingErrors, realErrors,
                "a stranger must see the identical text for a real foreign-owned referenced auth " +
                "config and a nonexistent one — otherwise the response becomes a cross-tenant " +
                "existence oracle")
        assertEquals(["NsAuthConfig ORACLE_REF_AUTH not found."], realErrors)
    }

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
        // a distinguishable text that told a stranger the id exists somewhere. Finding A collapses
        // it to the same "was not found" text a real not-found denial produces (see the reference
        // check test above for the byte-identical comparison, which this primary check cannot do —
        // see the next test for why).
        assertEquals(["NS Auth config 'ORACLE_AUTH' was not found."], (List<String>) result.errors)
    }

    @Test
    void strangerCreatingWithANeverUsedNsAuthConfigIdSucceedsInsteadOfDenying() {
        // Documents why the "real foreign vs nonexistent" equality test above is done via the
        // referenced-config check rather than this primary one: save#NsAuthConfig is create-or-update,
        // so a genuinely free id has no row to deny access to — it is a legitimate create, not a
        // "not found" denial. There is nothing to close here; a stranger claiming a never-used id is
        // ordinary tenant self-service, unrelated to the shared-config access gate this task fixes.
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, STRANGER)

        Map<String, Object> result = saveFacade("facade.SettingsFacadeServices.save#NsAuthConfig", [
                nsAuthConfigId: "STRANGER_OWN_NEW_AUTH",
                description   : "Stranger's own new config",
                authType      : "BASIC",
                username      : "stranger-user",
                password      : "stranger-password",
        ])

        assertTrue((Boolean) result.ok, result.errors?.toString())
        def stored = findOne("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "STRANGER_OWN_NEW_AUTH"])
        assertEquals(STRANGER, stored.companyUserGroupId)
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
        upsertEntityValue("moqui.basic.Enumeration", [enumId: "SCFG_NS_RESTLET"], [
                enumId     : "SCFG_NS_RESTLET",
                description: "NetSuite Restlet config (darpan.reconciliation.NsRestletConfig)",
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
        upsertEntityValue("darpan.reconciliation.NsRestletConfig", [nsRestletConfigId: "SHARED_ENDPOINT"], [
                nsRestletConfigId    : "SHARED_ENDPOINT",
                description          : "Krewe shared endpoint",
                companyUserGroupId   : OWNER,
                createdByUserId      : TEST_USER_ID,
                endpointUrl          : "https://krewe.suitetalk.api.netsuite.com/restlet",
                httpMethod           : "POST",
                nsAuthConfigId       : "SHARED_AUTH",
                headersJson          : "{}",
                connectTimeoutSeconds: 30,
                readTimeoutSeconds   : 60,
                isActive             : "Y",
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
        upsertEntityValue("darpan.auth.ConfigTenantAccess", [
                configTypeEnumId : "SCFG_NS_RESTLET",
                configId         : "SHARED_ENDPOINT",
                tenantUserGroupId: MEMBER,
                fromDate         : GRANT_FROM_DATE,
        ], [
                configTypeEnumId : "SCFG_NS_RESTLET",
                configId         : "SHARED_ENDPOINT",
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
        upsertEntityValue("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "ORACLE_REF_AUTH"], [
                nsAuthConfigId    : "ORACLE_REF_AUTH",
                description       : "Krewe unshared referenced auth",
                companyUserGroupId: OWNER,
                createdByUserId   : TEST_USER_ID,
                authType          : "BASIC",
                username          : "krewe-oracle-ref-user",
                password          : "krewe-oracle-ref-password",
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
