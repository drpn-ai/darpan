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
import static org.junit.jupiter.api.Assertions.assertNull
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
        seedPermissionGroup(TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID, "Can view tenant-scoped Darpan data but cannot mutate it")
        replaceTenantPermission(KREWE, TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID)
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

    @Test
    void credentialBearingListResponsesOnlyExposeIndicators() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, KREWE)

        Map<String, Object> sftpResult = listFacade("facade.SettingsFacadeServices.list#SftpServers")
        List<Map<String, Object>> servers = (List<Map<String, Object>>) (sftpResult.servers ?: [])
        assertEquals(["KREWE_SFTP"], servers.collect { Map<String, Object> row -> row.sftpServerId })
        assertTrue(servers.first().hasPassword as boolean)
        assertTrue(servers.first().hasPrivateKey as boolean)
        assertNoRawCredentialFields(servers)

        ec.message.clearErrors()
        Map<String, Object> authResult = listFacade("facade.SettingsFacadeServices.list#NsAuthConfigs")
        List<Map<String, Object>> authConfigs = (List<Map<String, Object>>) (authResult.authConfigs ?: [])
        assertEquals(["KREWE_AUTH"], authConfigs.collect { Map<String, Object> row -> row.nsAuthConfigId })
        assertTrue(authConfigs.first().hasPassword as boolean)
        assertTrue(authConfigs.first().hasApiToken as boolean)
        assertTrue(authConfigs.first().hasPrivateKeyPem as boolean)
        assertNoRawCredentialFields(authConfigs)

        ec.message.clearErrors()
        Map<String, Object> endpointResult = listFacade("facade.SettingsFacadeServices.list#NsRestletConfigs")
        List<Map<String, Object>> endpoints = (List<Map<String, Object>>) (endpointResult.restletConfigs ?: [])
        assertEquals(["KREWE_ENDPOINT"], endpoints.collect { Map<String, Object> row -> row.nsRestletConfigId })
        assertNoRawCredentialFields(endpoints)
    }

    @Test
    void activeKreweTenantCannotUpdateGorjanaSettings() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, KREWE)

        Map<String, Object> sftpResult = saveFacade("facade.SettingsFacadeServices.save#SftpServer", [
                sftpServerId: "GORJANA_SFTP",
                description : "Cross-tenant SFTP edit",
                host        : "leaked.sftp.test",
                port        : 22,
                username    : "leaked-user",
        ])
        assertFalse((Boolean) sftpResult.ok)
        assertTrue((sftpResult.errors ?: []).join(" ").contains("not available in your active tenant"))
        assertEquals("gorjana.sftp.test", findOne("darpan.reconciliation.SftpServer", [sftpServerId: "GORJANA_SFTP"]).host)

        ec.message.clearErrors()
        Map<String, Object> authResult = saveFacade("facade.SettingsFacadeServices.save#NsAuthConfig", [
                nsAuthConfigId: "GORJANA_AUTH",
                description   : "Cross-tenant Auth edit",
                authType      : "BASIC",
                username      : "leaked-auth-user",
        ])
        assertFalse((Boolean) authResult.ok)
        assertTrue((authResult.errors ?: []).join(" ").contains("not available in your active tenant"))
        assertEquals("Gorjana Auth", findOne("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "GORJANA_AUTH"]).description)

        ec.message.clearErrors()
        Map<String, Object> endpointResult = saveFacade("facade.SettingsFacadeServices.save#NsRestletConfig", [
                nsRestletConfigId: "GORJANA_ENDPOINT",
                description      : "Cross-tenant Endpoint edit",
                endpointUrl      : "https://leaked.example.com/restlet",
                httpMethod       : "POST",
                nsAuthConfigId   : "GORJANA_AUTH",
                headersJson      : "{}",
        ])
        assertFalse((Boolean) endpointResult.ok)
        assertTrue((endpointResult.errors ?: []).join(" ").contains("not available in your active tenant"))
        assertEquals("https://gorjana.example.com/restlet", findOne("darpan.reconciliation.NsRestletConfig", [nsRestletConfigId: "GORJANA_ENDPOINT"]).endpointUrl)
    }

    @Test
    void viewOnlyTenantCanReadButCannotCreateOrUpdateSettings() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, KREWE)

        assertTenantVisibleRows(KREWE, "KREWE_SFTP", "KREWE_AUTH", "KREWE_ENDPOINT")

        ec.message.clearErrors()
        Map<String, Object> createResult = saveFacade("facade.SettingsFacadeServices.save#SftpServer", [
                sftpServerId: "KREWE_VIEW_ONLY_CREATE",
                description : "Blocked view-only create",
                host        : "blocked.sftp.test",
                port        : 22,
                username    : "blocked-user",
        ])
        assertFalse((Boolean) createResult.ok)
        assertTrue((createResult.errors ?: []).join(" ").contains("view access"))
        assertNull(findOne("darpan.reconciliation.SftpServer", [sftpServerId: "KREWE_VIEW_ONLY_CREATE"]))

        ec.message.clearErrors()
        Map<String, Object> updateResult = saveFacade("facade.SettingsFacadeServices.save#NsAuthConfig", [
                nsAuthConfigId: "KREWE_AUTH",
                description   : "Blocked view-only update",
                authType      : "BASIC",
                username      : "blocked-auth-user",
        ])
        assertFalse((Boolean) updateResult.ok)
        assertTrue((updateResult.errors ?: []).join(" ").contains("view access"))
        assertEquals("Krewe Auth", findOne("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "KREWE_AUTH"]).description)
    }

    @Test
    void newSettingsStampActiveTenantAndCreator() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, GORJANA)

        Map<String, Object> sftpResult = saveFacade("facade.SettingsFacadeServices.save#SftpServer", [
                sftpServerId: "GORJANA_CREATED_SFTP",
                description : "Created SFTP",
                host        : "created.sftp.test",
                port        : 22,
                username    : "created-user",
        ])
        assertTrue((Boolean) sftpResult.ok, sftpResult.errors?.toString())
        assertTenantOwnership("darpan.reconciliation.SftpServer", [sftpServerId: "GORJANA_CREATED_SFTP"])

        ec.message.clearErrors()
        Map<String, Object> authResult = saveFacade("facade.SettingsFacadeServices.save#NsAuthConfig", [
                nsAuthConfigId: "GORJANA_CREATED_AUTH",
                description   : "Created Auth",
                authType      : "BASIC",
                username      : "created-auth-user",
                password      : "secret",
        ])
        assertTrue((Boolean) authResult.ok, authResult.errors?.toString())
        assertTenantOwnership("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "GORJANA_CREATED_AUTH"])

        ec.message.clearErrors()
        Map<String, Object> endpointResult = saveFacade("facade.SettingsFacadeServices.save#NsRestletConfig", [
                nsRestletConfigId: "GORJANA_CREATED_ENDPOINT",
                description      : "Created Endpoint",
                endpointUrl      : "https://created.example.com/restlet",
                httpMethod       : "POST",
                nsAuthConfigId   : "GORJANA_CREATED_AUTH",
                headersJson      : "{}",
        ])
        assertTrue((Boolean) endpointResult.ok, endpointResult.errors?.toString())
        assertTenantOwnership("darpan.reconciliation.NsRestletConfig", [nsRestletConfigId: "GORJANA_CREATED_ENDPOINT"])
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

    private Map<String, Object> saveFacade(String serviceName, Map<String, Object> parameters) {
        return (Map<String, Object>) ec.service.sync()
                .name(serviceName)
                .parameters(parameters)
                .disableAuthz()
                .call()
    }

    private void assertTenantOwnership(String entityName, Map<String, Object> pkFields) {
        def record = findOne(entityName, pkFields)
        assertEquals(GORJANA, record.companyUserGroupId)
        assertEquals(TEST_USER_ID, record.createdByUserId)
    }

    private def findOne(String entityName, Map<String, Object> pkFields) {
        return ec.entity.find(entityName)
                .condition(pkFields)
                .disableAuthz()
                .useCache(false)
                .one()
    }

    private void seedPermissionGroup(String permissionGroupId, String description) {
        upsertEntity("moqui.security.UserGroup", [userGroupId: permissionGroupId], [
                userGroupId    : permissionGroupId,
                description    : description,
                groupTypeEnumId: TenantAccessSupport.DARPAN_PERMISSION_GROUP_TYPE_ENUM_ID,
        ])
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
        replaceTenantPermission(tenantId, TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID)
    }

    private void replaceTenantPermission(String tenantId, String permissionGroupId) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        ArtifactExecutionInfo aei = ec.artifactExecution.push(
                "replaceSettingsTenantPermission",
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

    private void seedSettingsFixtures() {
        upsertEntityValue("darpan.reconciliation.SftpServer", [sftpServerId: "KREWE_SFTP"], [
                sftpServerId      : "KREWE_SFTP",
                description       : "Krewe SFTP",
                companyUserGroupId: KREWE,
                createdByUserId   : TEST_USER_ID,
                host              : "krewe.sftp.test",
                port              : 22,
                username          : "krewe-user",
                password          : "krewe-sftp-password",
                privateKey        : "krewe-sftp-private-key",
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
                password          : "gorjana-sftp-password",
                privateKey        : "gorjana-sftp-private-key",
                remoteAttributes  : "Y",
        ])

        upsertEntityValue("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "KREWE_AUTH"], [
                nsAuthConfigId    : "KREWE_AUTH",
                description       : "Krewe Auth",
                companyUserGroupId: KREWE,
                createdByUserId   : TEST_USER_ID,
                authType          : "BASIC",
                username          : "krewe-auth-user",
                password          : "krewe-auth-password",
                apiToken          : "krewe-auth-api-token",
                privateKeyPem     : "krewe-auth-private-key",
                isActive          : "Y",
        ])
        upsertEntityValue("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "GORJANA_AUTH"], [
                nsAuthConfigId    : "GORJANA_AUTH",
                description       : "Gorjana Auth",
                companyUserGroupId: GORJANA,
                createdByUserId   : TEST_USER_ID,
                authType          : "BASIC",
                username          : "gorjana-auth-user",
                password          : "gorjana-auth-password",
                apiToken          : "gorjana-auth-api-token",
                privateKeyPem     : "gorjana-auth-private-key",
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
                authType             : "BEARER",
                username             : "legacy-krewe-user",
                password             : "legacy-krewe-password",
                apiToken             : "legacy-krewe-api-token",
                privateKeyPem        : "legacy-krewe-private-key",
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
                authType             : "BEARER",
                username             : "legacy-gorjana-user",
                password             : "legacy-gorjana-password",
                apiToken             : "legacy-gorjana-api-token",
                privateKeyPem        : "legacy-gorjana-private-key",
        ])
    }

    private static void assertNoRawCredentialFields(Object payload) {
        if (payload instanceof Map) {
            Map map = (Map) payload
            ["password", "privateKey", "apiToken", "privateKeyPem", "llmApiKey"].each { String fieldName ->
                assertFalse(map.containsKey(fieldName), "Response must not expose ${fieldName}: ${map}")
            }
            map.values().each { Object value -> assertNoRawCredentialFields(value) }
        } else if (payload instanceof Iterable) {
            ((Iterable) payload).each { Object value -> assertNoRawCredentialFields(value) }
        }
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
