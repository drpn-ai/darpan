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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SettingsFacadeTenantFilteringSmokeTests {
    private static final String TEST_USER_ID = "TEST_CUSTOMER_USER"
    private static final String KREWE = "KREWE"
    private static final String GORJANA = "GORJANA"
    private static final Timestamp TEST_FROM_DATE = Timestamp.valueOf("2026-04-23 00:00:00")

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "settings-tenant-filtering-smoke")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        seedTenant(GORJANA, "Gorjana")
        seedSettingsFixtures()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearErrors() {
        ec.message.clearErrors()
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, KREWE)
    }

    @Test
    void settingsListsOnlyReturnRowsForTheActiveTenant() {
        assertTenantVisibleRows(KREWE, "KREWE_SFTP", "KREWE_AUTH", "KREWE_ENDPOINT")
        assertTenantVisibleRows(GORJANA, "GORJANA_SFTP", "GORJANA_AUTH", "GORJANA_ENDPOINT")
    }

    private void assertTenantVisibleRows(String tenantId, String expectedSftpId, String expectedAuthId, String expectedEndpointId) {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, tenantId)
        ec.message.clearErrors()

        Map<String, Object> sftpResult = listFacade("facade.SettingsFacadeServices.list#SftpServers")
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        List<Map<String, Object>> servers = (List<Map<String, Object>>) (sftpResult.servers ?: [])
        assertEquals([expectedSftpId], servers.collect { Map<String, Object> row -> row.sftpServerId })
        assertTrue(servers.every { Map<String, Object> row -> row.companyUserGroupId == tenantId })

        ec.message.clearErrors()
        Map<String, Object> authResult = listFacade("facade.SettingsFacadeServices.list#NsAuthConfigs")
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        List<Map<String, Object>> authConfigs = (List<Map<String, Object>>) (authResult.authConfigs ?: [])
        assertEquals([expectedAuthId], authConfigs.collect { Map<String, Object> row -> row.nsAuthConfigId })
        assertTrue(authConfigs.every { Map<String, Object> row -> row.companyUserGroupId == tenantId })

        ec.message.clearErrors()
        Map<String, Object> endpointResult = listFacade("facade.SettingsFacadeServices.list#NsRestletConfigs")
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        List<Map<String, Object>> endpoints = (List<Map<String, Object>>) (endpointResult.restletConfigs ?: [])
        assertEquals([expectedEndpointId], endpoints.collect { Map<String, Object> row -> row.nsRestletConfigId })
        assertTrue(endpoints.every { Map<String, Object> row -> row.companyUserGroupId == tenantId })
        assertTrue(endpoints.every { Map<String, Object> row -> row.nsAuthConfigId == expectedAuthId })
    }

    private Map<String, Object> listFacade(String serviceName) {
        return (Map<String, Object>) ec.service.sync()
                .name(serviceName)
                .parameters([
                        pageIndex: 0,
                        pageSize : 20,
                        query    : "",
                ])
                .disableAuthz()
                .call()
    }

    private void seedTenant(String tenantId, String label) {
        upsertEntity("moqui.security.UserGroup", [userGroupId: tenantId], [
                userGroupId     : tenantId,
                description     : label,
                groupTypeEnumId : TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID,
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

    private void seedSettingsFixtures() {
        upsertEntityValue("darpan.reconciliation.SftpServer", [sftpServerId: "KREWE_SFTP"], [
                sftpServerId      : "KREWE_SFTP",
                description       : "Krewe SFTP",
                companyUserGroupId: KREWE,
                createdByUserId   : TEST_USER_ID,
                host              : "krewe.sftp.test",
                port              : 22,
                username          : "krewe-user",
                password          : "secret",
                remoteAttributes  : "Y",
        ])
        upsertEntityValue("darpan.reconciliation.SftpServer", [sftpServerId: "GORJANA_SFTP"], [
                sftpServerId      : "GORJANA_SFTP",
                description       : "Gorjana SFTP",
                companyUserGroupId: GORJANA,
                createdByUserId   : TEST_USER_ID,
                host              : "gorjana.sftp.test",
                port              : 22,
                username          : "gorjana-user",
                password          : "secret",
                remoteAttributes  : "Y",
        ])

        upsertEntityValue("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "KREWE_AUTH"], [
                nsAuthConfigId    : "KREWE_AUTH",
                description       : "Krewe Auth",
                companyUserGroupId: KREWE,
                createdByUserId   : TEST_USER_ID,
                authType          : "BASIC",
                username          : "krewe-auth-user",
                password          : "secret",
                isActive          : "Y",
        ])
        upsertEntityValue("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "GORJANA_AUTH"], [
                nsAuthConfigId    : "GORJANA_AUTH",
                description       : "Gorjana Auth",
                companyUserGroupId: GORJANA,
                createdByUserId   : TEST_USER_ID,
                authType          : "BASIC",
                username          : "gorjana-auth-user",
                password          : "secret",
                isActive          : "Y",
        ])

        upsertEntityValue("darpan.reconciliation.NsRestletConfig", [nsRestletConfigId: "KREWE_ENDPOINT"], [
                nsRestletConfigId    : "KREWE_ENDPOINT",
                description          : "Krewe Endpoint",
                companyUserGroupId   : KREWE,
                createdByUserId      : TEST_USER_ID,
                endpointUrl          : "https://krewe.example.com/restlet",
                httpMethod           : "POST",
                nsAuthConfigId       : "KREWE_AUTH",
                headersJson          : "{}",
                connectTimeoutSeconds: 30,
                readTimeoutSeconds   : 60,
                isActive             : "Y",
        ])
        upsertEntityValue("darpan.reconciliation.NsRestletConfig", [nsRestletConfigId: "GORJANA_ENDPOINT"], [
                nsRestletConfigId    : "GORJANA_ENDPOINT",
                description          : "Gorjana Endpoint",
                companyUserGroupId   : GORJANA,
                createdByUserId      : TEST_USER_ID,
                endpointUrl          : "https://gorjana.example.com/restlet",
                httpMethod           : "POST",
                nsAuthConfigId       : "GORJANA_AUTH",
                headersJson          : "{}",
                connectTimeoutSeconds: 30,
                readTimeoutSeconds   : 60,
                isActive             : "Y",
        ])
    }

    private void upsertEntity(String entityName, Map<String, Object> pkFields, Map<String, Object> fields) {
        storeIfMissing(entityName, pkFields, fields)
    }

    private void upsertEntityValue(String entityName, Map<String, Object> pkFields, Map<String, Object> fields) {
        storeIfMissing(entityName, pkFields, fields)
    }

    private void storeIfMissing(String entityName, Map<String, Object> pkFields, Map<String, Object> fields) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        ArtifactExecutionInfo aei = ec.artifactExecution.push(
                "seedSettingsTenantFiltering",
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
