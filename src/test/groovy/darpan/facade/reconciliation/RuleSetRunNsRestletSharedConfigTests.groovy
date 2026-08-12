package darpan.facade.reconciliation

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
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-005 review finding B4 — {@code create#RuleSetRun} / {@code save#RuleSetRun}
 * (ReconciliationFacadeServices.xml) resolved {@code NsRestletConfig} via a bare, authz-enabled
 * {@code ec.entity.find} (silently filtered by DARPAN_ACTIVE_COMPANY_SCOPE) and then gated owner-only
 * via {@code TenantAccessSupport.requireTenantRecordAccess} with a distinguishing "not available in
 * your active tenant" message — even though {@code list#NsRestletConfigs} already returns shared
 * restlets and {@code save#NsRestletConfig} already accepts a peer editor (Task 5/7). So a peer could
 * see and edit a shared restlet in Settings but never reference it from a rule set or saved run. Fixed
 * by routing the read through {@code TenantScopedFinder.findGlobalUnscoped} and gating with
 * {@code SharedConfigAccessSupport.canActiveTenantUseConfig(..., CONFIG_TYPE_NS_RESTLET, ...)},
 * collapsing the denial to the same "was not found" text a nonexistent id produces.
 *
 * <p>The NsRestletConfig's own {@code nsAuthConfigId} field is deliberately left {@code null} here and
 * each tenant's {@code file1SourceConfigId} points at an NsAuthConfig row that tenant OWNS outright.
 * That isolates the property under test — access to the shared NsRestletConfig row — from the
 * separate, already-correct NsAuthConfig validator (Task 6's {@code validateNetSuiteAuthConfig}), so a
 * failure here can only come from the restlet gate this task fixes, never a false negative from an
 * unrelated auth-config sharing gap.</p>
 *
 * <p>Both {@code create#RuleSetRun} and {@code save#RuleSetRun} carry byte-identical NsRestletConfig
 * read/gate blocks (confirmed by diff before the fix), so full positive/negative/zero-grant coverage
 * lives on {@code create#RuleSetRun} and {@code save#RuleSetRun} gets a peer-success plus a
 * stranger-denial test proving the second call site was fixed too, not left as a hopeful assumption.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RuleSetRunNsRestletSharedConfigTests {
    private static final String GRANT_ENTITY_NAME = "darpan.auth.ConfigTenantAccess"
    private static final String CONFIG_TYPE = "SCFG_NS_RESTLET"
    private static final String TEST_USER_ID = "TEST_CUSTOMER_USER"
    private static final String OWNER = "NSRESTLET_RUN_OWNER"
    private static final String MEMBER = "NSRESTLET_RUN_MEMBER"
    private static final String STRANGER = "NSRESTLET_RUN_STRANGER"
    private static final Timestamp TEST_FROM_DATE = Timestamp.valueOf("2026-05-01 00:00:00")

    private static final String OWNER_AUTH_CONFIG_ID = "NSRESTLET_RUN_OWNER_AUTH"
    private static final String MEMBER_AUTH_CONFIG_ID = "NSRESTLET_RUN_MEMBER_AUTH"
    private static final String STRANGER_AUTH_CONFIG_ID = "NSRESTLET_RUN_STRANGER_AUTH"
    private static final String SHARED_RESTLET_ID = "NSRESTLET_RUN_SHARED"

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "rulesetrun-nsrestlet-shared-config")
        // initMoqui's own internalLoginUser("john.doe") fails in this isolated test DB (no such user
        // seeded) and falls back to an anonymous session, which cannot hold user preferences —
        // seedCompanyScope performs the real internalLoginUser(TEST_CUSTOMER_USER) that every
        // ec.user.setPreference(ACTIVE_TENANT_PREFERENCE_KEY, ...) call below depends on. The KREWE
        // fixtures it also seeds are unused here and harmless.
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ReconciliationSmokeTestSupport.loadSeedData(ec,
                "component://darpan/data/DarpanSystemSourceSeedData.xml",
                "component://darpan/data/AutomationSeedData.xml",
                "component://darpan/data/SourceSystemConnectorSeedData.xml",
                "component://darpan/data/MappingSeedData.xml")

        seedPermissionGroup(TenantAccessSupport.DARPAN_TENANT_ADMIN_GROUP_ID, "Can manage tenant-scoped Darpan settings")
        seedTenant(OWNER, "NsRestlet Run Owner")
        seedTenant(MEMBER, "NsRestlet Run Member")
        seedTenant(STRANGER, "NsRestlet Run Stranger")
        seedConfigTypeEnumeration()

        seedNsAuthConfig(OWNER_AUTH_CONFIG_ID, OWNER)
        seedNsAuthConfig(MEMBER_AUTH_CONFIG_ID, MEMBER)
        seedNsAuthConfig(STRANGER_AUTH_CONFIG_ID, STRANGER)
        seedNsRestletConfig(SHARED_RESTLET_ID, OWNER, "Shared restlet fixture")
        seedGrant(SHARED_RESTLET_ID, MEMBER)
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

    // --- create#RuleSetRun -------------------------------------------------

    @Test
    void createAllowsAPeerTenantWithAnActiveGrantToReferenceTheSharedRestlet() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, MEMBER)

        Map<String, Object> result = createRuleSetRun([
                runName                  : "Member NetSuite Restlet Run",
                file1SystemEnumId        : "NETSUITE",
                file1SourceTypeEnumId    : "AUT_SRC_API",
                file1NsRestletConfigId   : SHARED_RESTLET_ID,
                file1SourceConfigId      : MEMBER_AUTH_CONFIG_ID,
                file1SourceConfigType    : "NETSUITE_AUTH",
                file1PrimaryIdExpression : "\$.records[*].nsId",
                file2SystemEnumId        : "OMS",
                file2FileTypeEnumId      : "DftCsv",
                file2PrimaryIdExpression : "order_id",
        ])

        assertTrue((Boolean) result.ok,
                "the whole point of this fix: a peer tenant holding an active grant on a shared " +
                "restlet must be able to reference it from a NEW rule set run — errors: ${result.errors}")
        assertEquals(SHARED_RESTLET_ID,
                ((List<Map<String, Object>>) result.savedRun.systemOptions)
                        .find { it.fileSide == "FILE_1" }?.nsRestletConfigId)
    }

    @Test
    void createDeniesAStrangerWithTheSameTextForARealAndNonexistentRestletId() {
        // Same literal id used for BOTH calls (sequenced: nonexistent first, then a real foreign row
        // under that exact id) — the "was not found" message echoes the caller-supplied id, so two
        // different ids would trivially produce different text regardless of whether the collapse
        // holds. Mirrors ShopifySharedConfigAccessTests.getAuthConfigGivesAStrangerTheIdenticalMessage....
        String targetRestletId = "NSRESTLET_RUN_STRANGER_TARGET"
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, STRANGER)

        Map<String, Object> missingResult = createRuleSetRun(strangerParameters(targetRestletId, "Stranger Missing"))
        assertFalse((Boolean) missingResult.ok)
        List<String> missingErrors = (missingResult.errors ?: []) as List<String>
        assertFalse(missingErrors.isEmpty())

        // A rejected call leaves ec.message non-empty; ServiceCallSyncImpl refuses to even start the
        // next service ("Found error(s) before service...") while errors are still pending.
        ec.message.clearErrors()
        seedNsRestletConfig(targetRestletId, OWNER, "Stranger target restlet — real, foreign, unshared")
        Map<String, Object> foreignResult = createRuleSetRun(strangerParameters(targetRestletId, "Stranger Foreign"))
        assertFalse((Boolean) foreignResult.ok)
        List<String> foreignErrors = (foreignResult.errors ?: []) as List<String>
        assertFalse(foreignErrors.isEmpty())

        assertEquals(missingErrors, foreignErrors,
                "a stranger tenant (no ownership, no grant) must get byte-identical text whether the " +
                "restlet id is real-but-foreign or does not exist at all — divergence here is a " +
                "cross-tenant existence oracle")
        assertTrue(foreignErrors.any { it.contains("was not found") },
                "denial text must collapse to the plain not-found message, never the old " +
                "distinguishable 'not available in your active tenant': ${foreignErrors}")
        assertFalse(foreignErrors.any { it.contains("not available in your active tenant") },
                "the old distinguishing message must be gone: ${foreignErrors}")
    }

    @Test
    void createZeroGrantsPreservesOwnerAcceptAndForeignReject() {
        String configId = "NSRESTLET_RUN_ZERO_GRANT"
        seedNsRestletConfig(configId, OWNER, "Zero grant restlet fixture")
        // Deliberately no seedGrant call — proves behavior is unchanged from pre-DAR-BE-005 with an
        // empty ConfigTenantAccess table for this specific restlet.

        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, OWNER)
        Map<String, Object> ownerResult = createRuleSetRun([
                runName                 : "Owner Zero Grant Run",
                file1SystemEnumId       : "NETSUITE",
                file1SourceTypeEnumId   : "AUT_SRC_API",
                file1NsRestletConfigId  : configId,
                file1SourceConfigId     : OWNER_AUTH_CONFIG_ID,
                file1SourceConfigType   : "NETSUITE_AUTH",
                file1PrimaryIdExpression: "\$.records[*].nsId",
                file2SystemEnumId       : "OMS",
                file2FileTypeEnumId     : "DftCsv",
                file2PrimaryIdExpression: "order_id",
        ])
        assertTrue((Boolean) ownerResult.ok, "the owner must still succeed with zero grants: ${ownerResult.errors}")

        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, STRANGER)
        Map<String, Object> foreignResult = createRuleSetRun([
                runName                 : "Stranger Zero Grant Run",
                file1SystemEnumId       : "NETSUITE",
                file1SourceTypeEnumId   : "AUT_SRC_API",
                file1NsRestletConfigId  : configId,
                file1SourceConfigId     : STRANGER_AUTH_CONFIG_ID,
                file1SourceConfigType   : "NETSUITE_AUTH",
                file1PrimaryIdExpression: "\$.records[*].nsId",
                file2SystemEnumId       : "OMS",
                file2FileTypeEnumId     : "DftCsv",
                file2PrimaryIdExpression: "order_id",
        ])
        assertFalse((Boolean) foreignResult.ok,
                "with zero grants a foreign tenant must still be rejected exactly as before")
    }

    // --- save#RuleSetRun (second byte-identical call site) -----------------

    @Test
    void saveAllowsAPeerTenantWithAnActiveGrantToReferenceTheSharedRestlet() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, MEMBER)
        Map<String, Object> baseline = createRuleSetRun([
                runName                 : "Member Baseline For Save",
                file1SystemEnumId       : "OMS",
                file1FileTypeEnumId     : "DftCsv",
                file1PrimaryIdExpression: "order_id",
                file2SystemEnumId       : "SHOPIFY",
                file2FileTypeEnumId     : "DftCsv",
                file2PrimaryIdExpression: "order_id",
        ])
        assertTrue((Boolean) baseline.ok, baseline.errors?.toString())
        String savedRunId = baseline.savedRun.savedRunId as String

        Map<String, Object> saveResult = saveRuleSetRun([
                savedRunId               : savedRunId,
                runName                  : "Member Baseline For Save",
                file1SystemEnumId        : "NETSUITE",
                file1SourceTypeEnumId    : "AUT_SRC_API",
                file1NsRestletConfigId   : SHARED_RESTLET_ID,
                file1SourceConfigId      : MEMBER_AUTH_CONFIG_ID,
                file1SourceConfigType    : "NETSUITE_AUTH",
                file1PrimaryIdExpression : "\$.records[*].nsId",
                file2SystemEnumId        : "SHOPIFY",
                file2FileTypeEnumId      : "DftCsv",
                file2PrimaryIdExpression : "order_id",
        ])

        assertTrue((Boolean) saveResult.ok,
                "save#RuleSetRun is the second byte-identical call site — a peer must be able to add " +
                "a reference to the shared restlet when editing its own saved run: ${saveResult.errors}")
        assertEquals(SHARED_RESTLET_ID,
                ((List<Map<String, Object>>) saveResult.savedRun.systemOptions)
                        .find { it.fileSide == "FILE_1" }?.nsRestletConfigId)
    }

    @Test
    void saveDeniesAStrangerWithTheSameTextForARealAndNonexistentRestletId() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, STRANGER)
        Map<String, Object> baseline = createRuleSetRun([
                runName                 : "Stranger Baseline For Save",
                file1SystemEnumId       : "OMS",
                file1FileTypeEnumId     : "DftCsv",
                file1PrimaryIdExpression: "order_id",
                file2SystemEnumId       : "SHOPIFY",
                file2FileTypeEnumId     : "DftCsv",
                file2PrimaryIdExpression: "order_id",
        ])
        assertTrue((Boolean) baseline.ok, baseline.errors?.toString())
        String savedRunId = baseline.savedRun.savedRunId as String
        // Same literal id for both calls — see createDeniesAStrangerWithTheSameTextForAReal... above.
        String targetRestletId = "NSRESTLET_SAVE_STRANGER_TARGET"

        Map<String, Object> missingResult = saveRuleSetRun(
                strangerSaveParameters(savedRunId, targetRestletId, "Stranger Save Missing"))
        assertFalse((Boolean) missingResult.ok)
        List<String> missingErrors = (missingResult.errors ?: []) as List<String>
        assertFalse(missingErrors.isEmpty())

        ec.message.clearErrors()
        seedNsRestletConfig(targetRestletId, OWNER, "Stranger save target restlet — real, foreign, unshared")
        Map<String, Object> foreignResult = saveRuleSetRun(
                strangerSaveParameters(savedRunId, targetRestletId, "Stranger Save Foreign"))
        assertFalse((Boolean) foreignResult.ok)
        List<String> foreignErrors = (foreignResult.errors ?: []) as List<String>
        assertFalse(foreignErrors.isEmpty())

        assertEquals(missingErrors, foreignErrors,
                "save#RuleSetRun must also give a stranger byte-identical text for a real-but-foreign " +
                "restlet id as for a nonexistent one")
    }

    // --- helpers -------------------------------------------------------

    private Map<String, Object> strangerParameters(String restletId, String label) {
        return [
                runName                 : label,
                file1SystemEnumId       : "NETSUITE",
                file1SourceTypeEnumId   : "AUT_SRC_API",
                file1NsRestletConfigId  : restletId,
                // STRANGER always owns its own auth config outright, so validateNetSuiteAuthConfig
                // never contributes an error — isolates the assertion to the restlet gate alone.
                file1SourceConfigId     : STRANGER_AUTH_CONFIG_ID,
                file1SourceConfigType   : "NETSUITE_AUTH",
                file1PrimaryIdExpression: "\$.records[*].nsId",
                file2SystemEnumId       : "OMS",
                file2FileTypeEnumId     : "DftCsv",
                file2PrimaryIdExpression: "order_id",
        ]
    }

    private Map<String, Object> strangerSaveParameters(String savedRunId, String restletId, String label) {
        return strangerParameters(restletId, label) + ([savedRunId: savedRunId] as Map<String, Object>)
    }

    private Map<String, Object> createRuleSetRun(Map<String, Object> overrides) {
        Map<String, Object> parameters = [rules: []] + overrides
        return (Map<String, Object>) ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                .parameters(parameters)
                .disableAuthz()
                .call()
    }

    private Map<String, Object> saveRuleSetRun(Map<String, Object> overrides) {
        Map<String, Object> parameters = [rules: []] + overrides
        return (Map<String, Object>) ec.service.sync()
                .name("facade.ReconciliationFacadeServices.save#RuleSetRun")
                .parameters(parameters)
                .disableAuthz()
                .call()
    }

    private void seedPermissionGroup(String permissionGroupId, String description) {
        upsertEntityValue("moqui.security.UserGroup", [userGroupId: permissionGroupId], [
                userGroupId    : permissionGroupId,
                description    : description,
                groupTypeEnumId: TenantAccessSupport.DARPAN_PERMISSION_GROUP_TYPE_ENUM_ID,
        ])
    }

    private void seedTenant(String tenantId, String label) {
        upsertEntityValue("moqui.security.UserGroup", [userGroupId: tenantId], [
                userGroupId    : tenantId,
                description    : label,
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
        replaceTenantPermission(tenantId, TenantAccessSupport.DARPAN_TENANT_ADMIN_GROUP_ID)
    }

    private void replaceTenantPermission(String tenantId, String permissionGroupId) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        ArtifactExecutionInfo aei = ec.artifactExecution.push(
                "replaceNsRestletRunTenantPermission",
                ArtifactExecutionInfo.AT_OTHER,
                ArtifactExecutionInfo.AUTHZA_ALL,
                false
        )
        ec.artifactExecution.setAnonymousAuthorizedAll()
        try {
            ec.entity.find(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME)
                    .condition("tenantUserGroupId", tenantId)
                    .condition("userId", TEST_USER_ID)
                    .disableAuthz()
                    .useCache(false)
                    .list()
                    .each { it.delete() }
        } finally {
            ec.artifactExecution.pop(aei)
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
        upsertEntityValue(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [
                tenantUserGroupId    : tenantId,
                userId               : TEST_USER_ID,
                permissionUserGroupId: permissionGroupId,
                fromDate             : TEST_FROM_DATE,
        ], [
                tenantUserGroupId    : tenantId,
                userId               : TEST_USER_ID,
                permissionUserGroupId: permissionGroupId,
                fromDate             : TEST_FROM_DATE,
        ])
    }

    private void seedNsAuthConfig(String configId, String ownerTenantId) {
        upsertEntityValue("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: configId], [
                nsAuthConfigId    : configId,
                description       : "Auth config for ${configId}".toString(),
                companyUserGroupId: ownerTenantId,
                createdByUserId   : TEST_USER_ID,
                authType          : "NONE",
                isActive          : "Y",
        ])
    }

    private void seedNsRestletConfig(String configId, String ownerTenantId, String description) {
        upsertEntityValue("darpan.reconciliation.NsRestletConfig", [nsRestletConfigId: configId], [
                nsRestletConfigId : configId,
                description       : description,
                companyUserGroupId: ownerTenantId,
                createdByUserId   : TEST_USER_ID,
                endpointUrl       : "https://${configId.toLowerCase()}.example.netsuite.com/restlet".toString(),
                httpMethod        : "POST",
                // nsAuthConfigId deliberately left null — see class Javadoc: decouples this fixture
                // from the separate NsAuthConfig sharing gate so only the restlet gate is under test.
                isActive          : "Y",
        ])
    }

    /** ConfigTenantAccess.configTypeEnumId has a DB FK to moqui.basic.Enumeration. Production loads
     *  this row from darpan/data/SecuritySeedData.xml; this isolated test DB does not auto-load seed
     *  data, so it is hand-seeded here too (matches ShopifySharedConfigAccessTests' own pattern). */
    private void seedConfigTypeEnumeration() {
        upsertEntityValue("moqui.basic.EnumerationType", [enumTypeId: "DarpanSharedConfigType"], [
                enumTypeId : "DarpanSharedConfigType",
                description: "Darpan API source config types that support cross-tenant sharing",
        ])
        upsertEntityValue("moqui.basic.Enumeration", [enumId: CONFIG_TYPE], [
                enumId     : CONFIG_TYPE,
                description: "NetSuite Restlet config (darpan.reconciliation.NsRestletConfig)",
                enumTypeId : "DarpanSharedConfigType",
        ])
    }

    private void seedGrant(String configId, String tenantUserGroupId) {
        upsertEntityValue(GRANT_ENTITY_NAME, [
                configTypeEnumId : CONFIG_TYPE,
                configId         : configId,
                tenantUserGroupId: tenantUserGroupId,
        ], [
                configTypeEnumId : CONFIG_TYPE,
                configId         : configId,
                tenantUserGroupId: tenantUserGroupId,
                fromDate         : TEST_FROM_DATE,
                thruDate         : null,
                grantedByUserId  : TEST_USER_ID,
        ])
    }

    private void upsertEntityValue(String entityName, Map<String, Object> pkFields, Map<String, Object> fields) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        ArtifactExecutionInfo aei = ec.artifactExecution.push(
                "seedRuleSetRunNsRestletSharedConfig",
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

            ec.service.sync()
                    .name("store#${entityName}")
                    .parameters(fields)
                    .disableAuthz()
                    .call()
        } finally {
            ec.artifactExecution.pop(aei)
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
    }
}
