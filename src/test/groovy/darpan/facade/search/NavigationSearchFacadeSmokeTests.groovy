package darpan.facade.search

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NavigationSearchFacadeSmokeTests {
    private static final String TEST_USER_ID = "TEST_CUSTOMER_USER"
    private static final String KREWE = "KREWE"
    private static final String GORJANA = "GORJANA"
    private static final Timestamp TEST_FROM_DATE = Timestamp.valueOf("2026-04-25 00:00:00")

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "navigation-search-facade-smoke")
        ReconciliationSmokeTestSupport.seedSchemaBackedCsvMappingFixtures(ec)
        seedTenant(GORJANA, "Gorjana")
        seedSearchFixtures()
        seedGeneratedOutputFixture()
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
    void searchReturnsNavigationTargetsForScopedDarpanRecords() {
        Map<String, Object> sftpResult = search("edit krewe sftp server")
        Map<String, Object> sftpTarget = firstTarget(sftpResult, "sftp-server", "KREWE_SEARCH_SFTP")
        assertNotNull(sftpTarget)
        assertEquals("settings-sftp-edit", sftpTarget.routeName)
        assertEquals("/settings/sftp/edit/KREWE_SEARCH_SFTP", sftpTarget.routePath)
        assertEquals("KREWE_SEARCH_SFTP", ((Map) sftpTarget.routeParams).sftpServerId)

        Map<String, Object> schemaResult = search("shopify active schema")
        Map<String, Object> schemaTarget = firstTarget(schemaResult, "schema", "KreweSearchShopifySchema")
        assertNotNull(schemaTarget)
        assertEquals("schemas-editor", schemaTarget.routeName)
        assertEquals("KreweSearchShopifySchema", ((Map) schemaTarget.routeParams).jsonSchemaId)

        Map<String, Object> savedRunResult = search("order id saved run")
        Map<String, Object> savedRunTarget = firstTarget(savedRunResult, "saved-run", "OrderIdSchemaMap")
        assertNotNull(savedRunTarget)
        assertEquals("reconciliation-run-history", savedRunTarget.routeName)
        assertEquals("/reconciliation/run-history/OrderIdSchemaMap", savedRunTarget.routePath)

        Map<String, Object> outputResult = search("csv order compare result")
        Map<String, Object> outputTarget = firstTarget(outputResult, "run-result", "search-csv-order-compare-result.json")
        assertNotNull(outputTarget)
        assertEquals("reconciliation-run-result", outputTarget.routeName)
        assertEquals("OrderIdSchemaMap", ((Map) outputTarget.routeParams).savedRunId)
        assertEquals("search-csv-order-compare-result.json", ((Map) outputTarget.routeParams).outputFileName)
        assertEquals("CSV Order Compare", ((Map) outputTarget.routeQuery).runName)
    }

    @Test
    void searchHonorsTypeFiltersAndBlankQueryProtection() {
        Map<String, Object> filteredResult = search("krewe", ["sftp-server"])
        List<Map<String, Object>> filteredRows = (List<Map<String, Object>>) filteredResult.results
        assertEquals(["sftp-server"] as Set, filteredRows.collect { it.type } as Set)
        assertTrue(filteredRows.any { it.sourceId == "KREWE_SEARCH_SFTP" })

        Map<String, Object> blankResult = search("   ")
        assertEquals([], blankResult.results)
        assertEquals(0, ((Map) blankResult.pagination).totalCount)
    }

    @Test
    void searchDoesNotReturnRecordsOutsideTheActiveTenant() {
        Map<String, Object> kreweResult = search("gorjana sftp")
        assertEquals([], ((List) kreweResult.results).findAll { Map<String, Object> row -> row.sourceId == "GORJANA_SEARCH_SFTP" })

        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, GORJANA)
        ec.message.clearErrors()

        Map<String, Object> gorjanaResult = search("gorjana sftp")
        Map<String, Object> gorjanaTarget = firstTarget(gorjanaResult, "sftp-server", "GORJANA_SEARCH_SFTP")
        assertNotNull(gorjanaTarget)
        assertEquals("settings-sftp-edit", gorjanaTarget.routeName)
    }

    private Map<String, Object> search(String query, List<String> types = null) {
        Map<String, Object> result = (Map<String, Object>) ec.service.sync()
                .name("facade.SearchFacadeServices.search#NavigationTargets")
                .parameters([
                        query    : query,
                        types    : types,
                        pageIndex: 0,
                        pageSize : 20,
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals(true, result.ok)
        return result
    }

    private static Map<String, Object> firstTarget(Map<String, Object> result, String type, String sourceId) {
        return ((List<Map<String, Object>>) (result.results ?: [])).find { Map<String, Object> row ->
            row.type == type && row.sourceId == sourceId
        }
    }

    private void seedSearchFixtures() {
        upsertEntityValue("darpan.reconciliation.SftpServer", [sftpServerId: "KREWE_SEARCH_SFTP"], [
                sftpServerId      : "KREWE_SEARCH_SFTP",
                description       : "Krewe Search SFTP",
                companyUserGroupId: KREWE,
                createdByUserId   : TEST_USER_ID,
                host              : "search-krewe.sftp.test",
                port              : 22,
                username          : "krewe-search",
                password          : "secret",
                remoteAttributes  : "Y",
        ])
        upsertEntityValue("darpan.reconciliation.SftpServer", [sftpServerId: "GORJANA_SEARCH_SFTP"], [
                sftpServerId      : "GORJANA_SEARCH_SFTP",
                description       : "Gorjana Search SFTP",
                companyUserGroupId: GORJANA,
                createdByUserId   : TEST_USER_ID,
                host              : "search-gorjana.sftp.test",
                port              : 22,
                username          : "gorjana-search",
                password          : "secret",
                remoteAttributes  : "Y",
        ])
        upsertEntityValue("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "KREWE_SEARCH_AUTH"], [
                nsAuthConfigId    : "KREWE_SEARCH_AUTH",
                description       : "Krewe Search Auth",
                companyUserGroupId: KREWE,
                createdByUserId   : TEST_USER_ID,
                authType          : "BASIC",
                username          : "krewe-auth",
                password          : "secret",
                isActive          : "Y",
        ])
        upsertEntityValue("darpan.reconciliation.NsRestletConfig", [nsRestletConfigId: "KREWE_SEARCH_ENDPOINT"], [
                nsRestletConfigId    : "KREWE_SEARCH_ENDPOINT",
                description          : "Krewe Search Endpoint",
                companyUserGroupId   : KREWE,
                createdByUserId      : TEST_USER_ID,
                endpointUrl          : "https://krewe.example.com/search-restlet",
                httpMethod           : "POST",
                nsAuthConfigId       : "KREWE_SEARCH_AUTH",
                headersJson          : "{}",
                connectTimeoutSeconds: 30,
                readTimeoutSeconds   : 60,
                isActive             : "Y",
        ])
        upsertEntityValue("darpan.reconciliation.JsonSchema", [jsonSchemaId: "KreweSearchShopifySchema"], [
                jsonSchemaId      : "KreweSearchShopifySchema",
                schemaName        : "krewe-search-shopify-active.schema.json",
                description       : "Shopify active schema used by navigation search",
                systemEnumId      : "SHOPIFY",
                companyUserGroupId: KREWE,
                createdByUserId   : TEST_USER_ID,
                statusId          : "Active",
                createdDate       : TEST_FROM_DATE,
                schemaText        : '{"type":"object","properties":{"order_id":{"type":"string"}}}',
        ])
    }

    private void seedGeneratedOutputFixture() {
        String outputLocation = TenantAccessSupport.resolveGenericOutputLocation(ec)
        File outputDir = ec.resource.getLocationReference(outputLocation)?.getFile()
        assertNotNull(outputDir)
        outputDir.mkdirs()

        File outputFile = new File(outputDir, "search-csv-order-compare-result.json")
        outputFile.setText(JsonOutput.toJson([
                metadata: [
                        companyUserGroupId          : KREWE,
                        savedRunId                  : "OrderIdSchemaMap",
                        savedRunName                : "CSV Order Compare",
                        savedRunType                : "mapping",
                        reconciliationMappingId     : "OrderIdSchemaMap",
                        reconciliationMappingName   : "Order ID",
                        reconciliationType          : "generic",
                        file1Label                  : "OMS",
                        file2Label                  : "Shopify",
                ],
                summary : [
                        totalDifferences : 2,
                        onlyInFile1Count : 1,
                        onlyInFile2Count : 1,
                ],
        ]), "UTF-8")
    }

    private void seedTenant(String tenantId, String label) {
        upsertEntity("moqui.security.UserGroup", [userGroupId: tenantId], [
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

    private void upsertEntity(String entityName, Map<String, Object> pkFields, Map<String, Object> fields) {
        storeIfMissing(entityName, pkFields, fields)
    }

    private void upsertEntityValue(String entityName, Map<String, Object> pkFields, Map<String, Object> fields) {
        storeIfMissing(entityName, pkFields, fields)
    }

    private void storeIfMissing(String entityName, Map<String, Object> pkFields, Map<String, Object> fields) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        ArtifactExecutionInfo aei = ec.artifactExecution.push(
                "seedNavigationSearch",
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
