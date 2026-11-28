package darpan.facade.reconciliation

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-424 (Aditi 2026-11-28: "Remove it"). The NetSuite RESTlet connection type is gone: nothing ran a RESTlet once the
 * inventory services were deleted (S41c), and NetSuite is read through a NetSuite connection (SuiteQL). A caller that still
 * names a RESTlet endpoint is refused out loud on every save path, never silently saved without it; and a NetSuite side named
 * by its auth config alone, the way every live run names it, still saves.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SharedMoqui
class NsRestletRetiredTests {
    private static final String TEST_USER_ID = "TEST_CUSTOMER_USER"
    private static final String TENANT = "NSRESTLET_RETIRED_TENANT"
    private static final String AUTH_CONFIG_ID = "NSRESTLET_RETIRED_AUTH"
    private static final Timestamp TEST_FROM_DATE = Timestamp.valueOf("2026-05-01 00:00:00")
    private static final String REFUSAL = "NetSuite RESTlet endpoints were removed (DAR-BE-424)"

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "nsrestlet-retired")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ReconciliationSmokeTestSupport.loadSeedData(ec,
                "component://darpan/data/DarpanSystemSourceSeedData.xml",
                "component://darpan/data/AutomationSeedData.xml",
                "component://darpan/data/SourceSystemConnectorSeedData.xml",
                "component://darpan/data/MappingSeedData.xml")
        seedPermissionGroup(TenantAccessSupport.DARPAN_TENANT_ADMIN_GROUP_ID, "Can manage tenant-scoped Darpan settings")
        seedTenant(TENANT, "NsRestlet Retired Tenant")
        upsertEntityValue("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: AUTH_CONFIG_ID], [
                nsAuthConfigId: AUTH_CONFIG_ID, description: "Auth for the retired-RESTlet tests", companyUserGroupId: TENANT,
                createdByUserId: TEST_USER_ID, authType: "NONE", isActive: "Y"])
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void resetState() {
        ec.message.clearErrors()
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, TENANT)
    }

    @Test
    void createRefusesARestletEndpointOnEitherSide() {
        ["file1NsRestletConfigId", "file2NsRestletConfigId"].each { String param ->
            ec.message.clearErrors()
            Map<String, Object> result = createRuleSetRun(netSuiteRun("Create with ${param}") + [(param): "ANY_ENDPOINT"])
            assertFalse((Boolean) result.ok, "${param} must be refused: ${result}")
            assertTrue((result.errors as List).any { it.toString().contains(REFUSAL) }, "${param}: ${result.errors}")
        }
    }

    @Test
    void saveRefusesARestletEndpoint() {
        Map<String, Object> created = createRuleSetRun(netSuiteRun("Save baseline"))
        assertTrue((Boolean) created.ok, created.errors?.toString())
        ec.message.clearErrors()
        Map<String, Object> saved = saveRuleSetRun(netSuiteRun("Save baseline") +
                [savedRunId: created.savedRun.savedRunId, file1NsRestletConfigId: "ANY_ENDPOINT"])
        assertFalse((Boolean) saved.ok, saved.toString())
        assertTrue((saved.errors as List).any { it.toString().contains(REFUSAL) }, "${saved.errors}")
    }

    @Test
    void aNetSuiteSideNamedByItsAuthConfigStillSaves() {
        Map<String, Object> created = createRuleSetRun(netSuiteRun("NetSuite by auth config"))
        assertTrue((Boolean) created.ok, "a NetSuite side needs no RESTlet: ${created.errors}")
        assertFalse(((List<Map>) created.savedRun.systemOptions).any { it.containsKey("nsRestletConfigId") },
                "no RESTlet field comes back: ${created.savedRun.systemOptions}")
    }

    @Test
    void anAutomationSourceNamingARestletIsRefused() {
        AutomationSaveSupport.validateSources(ec, AutomationFacadeSupport.INPUT_MODE_API_RANGE, [
                [fileSide: "FILE_1", sourceTypeEnumId: "AUT_SRC_API", systemEnumId: "NETSUITE", nsRestletConfigId: "ANY_ENDPOINT"],
                [fileSide: "FILE_2", sourceTypeEnumId: "AUT_SRC_API", systemEnumId: "OMS", systemMessageRemoteId: "ANY_REMOTE"],
        ] as List<Map<String, Object>>, null)
        assertTrue(ec.message.errors.any { it.contains("FILE_1: ${REFUSAL}") }, "${ec.message.errors}")
    }

    private static Map<String, Object> netSuiteRun(String runName) {
        return [runName                 : runName,
                file1SystemEnumId       : "NETSUITE",
                file1SourceTypeEnumId   : "AUT_SRC_API",
                file1SourceConfigId     : AUTH_CONFIG_ID,
                file1SourceConfigType   : "NETSUITE_AUTH",
                file1PrimaryIdExpression: "\$.records[*].nsId",
                file2SystemEnumId       : "OMS",
                file2FileTypeEnumId     : "DftCsv",
                file2PrimaryIdExpression: "order_id"] as Map<String, Object>
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
                "replaceNsRestletRetiredTenantPermission",
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

    private void upsertEntityValue(String entityName, Map<String, Object> pkFields, Map<String, Object> fields) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        ArtifactExecutionInfo aei = ec.artifactExecution.push(
                "seedNsRestletRetired",
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
