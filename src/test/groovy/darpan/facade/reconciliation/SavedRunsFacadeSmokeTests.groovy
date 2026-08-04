package darpan.facade.reconciliation

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.source.SourceFilterSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SavedRunsFacadeSmokeTests {
    private static final String TEST_USER_ID = "TEST_CUSTOMER_USER"
    private static final String SAME_TENANT_USER_ID = "TEST_KREWE_VIEWER"
    private static final String OUTSIDE_TENANT_USER_ID = "TEST_GORJANA_EDITOR"
    private static final String KREWE = "KREWE"
    private static final String GORJANA = "GORJANA"
    private static final Timestamp TEST_FROM_DATE = Timestamp.valueOf("2026-04-23 00:00:00")

    private ExecutionContext ec

    // Task 7: (ruleSetId, compareScopeId) pairs from createRuleSetRun(), cleaned up after each test so
    // these throwaway fixtures don't linger in the shared PER_CLASS DB and shift the unfiltered,
    // paginated list#SavedRuns ordering other tests in this file (e.g.
    // csvRunCanBeCreatedListedAndExecutedThroughTheSavedRunFacade's "OrderIdSchemaMap" page-1 check)
    // depend on.
    private final List<Map<String, String>> ruleSetRunFixturesToCleanUp = []

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "saved-runs-facade-smoke")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SourceSystemConnectorSeedData.xml")
        ReconciliationSmokeTestSupport.seedSchemaBackedCsvMappingFixtures(ec)
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearErrors() {
        ec.message.clearErrors()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        replaceTenantPermission(KREWE, TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID)
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, KREWE)
        ec.message.clearErrors()
    }

    @AfterEach
    void cleanUpRuleSetRunFixtures() {
        // finally-clear so one bad entry can never poison every later test's cleanup pass — leaving a
        // stale entry in this PER_CLASS-shared list would otherwise re-throw on every subsequent test.
        try {
            ruleSetRunFixturesToCleanUp.each { Map<String, String> ids ->
                deleteRuleSetRunEntities(ids.ruleSetId, ids.compareScopeId)
            }
        } finally {
            ruleSetRunFixturesToCleanUp.clear()
        }
    }

    @Test
    void savedRunConfigTypeResolvesArbitrarySystemFromRegistry() {
        // Phase 3 (DAR-295): the saved-run expectedSourceConfigType now comes from a SourceSystemConnector
        // row, not a hardcoded switch — a brand-new system with no code branch resolves from data alone.
        ec.entity.makeValue(darpan.reconciliation.automation.SourceSystemConnectorSupport.ENTITY_NAME)
                .setAll([
                        systemEnumId            : "ACME3",
                        expectedSourceConfigType: "ACME_AUTH",
                        systemAliases           : "ACME3",
                        enabled                 : "Y",
                ])
                .create()
        assertEquals("ACME_AUTH", ReconciliationSavedRunSupport.expectedSourceConfigType(ec, "ACME3"))
        // canonical OMS/SHOPIFY/NETSUITE stay byte-identical (now sourced from the registry).
        assertEquals(ReconciliationSavedRunSupport.SOURCE_CONFIG_TYPE_HOTWAX_OMS_REST,
                ReconciliationSavedRunSupport.expectedSourceConfigType(ec, "OMS"))
        assertEquals(ReconciliationSavedRunSupport.SOURCE_CONFIG_TYPE_SHOPIFY_AUTH,
                ReconciliationSavedRunSupport.expectedSourceConfigType(ec, "SHOPIFY"))
        assertEquals(ReconciliationSavedRunSupport.SOURCE_CONFIG_TYPE_NETSUITE_AUTH,
                ReconciliationSavedRunSupport.expectedSourceConfigType(ec, "NETSUITE"))
        assertNull(ReconciliationSavedRunSupport.expectedSourceConfigType(ec, "SAPI"))
    }

    @Test
    void csvRunCanBeCreatedListedAndExecutedThroughTheSavedRunFacade() {
        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#CsvRun")
                .parameters([
                        runName           : "CSV Order Compare",
                        file1SystemEnumId : "OMS",
                        file2SystemEnumId : "SHOPIFY",
                        file1CompareColumn: "order_id",
                        file2CompareColumn: "order_id",
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals("CSV Order Compare", createResult.savedRun.runName)
        assertEquals("ruleset", createResult.savedRun.runType)
        assertNotNull(createResult.savedRun.savedRunId)
        assertNotNull(createResult.savedRun.ruleSetId)
        assertNotNull(createResult.savedRun.compareScopeId)
        assertTrue((createResult.savedRun.compareScopeId as String).contains("_CSV_SCOPE"))
        assertEquals("CSV compare scope for CSV Order Compare", createResult.savedRun.compareScopeDescription)
        assertEquals("OMS", createResult.savedRun.defaultFile1SystemEnumId)
        assertEquals("SHOPIFY", createResult.savedRun.defaultFile2SystemEnumId)
        assertEquals(null, ec.entity.find("darpan.rule.RuleSetCompareScope")
                .condition("compareScopeId", createResult.savedRun.compareScopeId)
                .useCache(false)
                .one()
                ?.objectType)

        Map<String, Object> listResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.list#SavedRuns")
                .parameters([
                        pageIndex: 0,
                        pageSize : 20,
                        query    : "",
                ])
                .disableAuthz()
                .call()

        List<Map<String, Object>> savedRuns = (List<Map<String, Object>>) (listResult.savedRuns ?: [])
        assertTrue(savedRuns.any { Map<String, Object> row -> row.savedRunId == createResult.savedRun.savedRunId })
        assertTrue(savedRuns.any { Map<String, Object> row -> row.savedRunId == "OrderIdSchemaMap" })

        Map<String, Object> runResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.run#SavedRunDiff")
                .parameters([
                        savedRunId: createResult.savedRun.savedRunId,
                        file1Name : "orders-1.csv",
                        file1Text : "order_id\nA100\nA200\nA300\n",
                        file2Name : "orders-2.csv",
                        file2Text : "order_id\nA200\nA300\nA400\n",
                        hasHeader : true,
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals(createResult.savedRun.savedRunId, runResult.runResult.savedRunId)
        assertEquals("ruleset", runResult.runResult.runType)
        assertEquals("OMS", runResult.runResult.file1SystemEnumId)
        assertEquals("SHOPIFY", runResult.runResult.file2SystemEnumId)
        assertTrue(((List) runResult.runResult.validationErrors).isEmpty())
        assertTrue(((List) runResult.runResult.processingWarnings).isEmpty())
        assertEquals(createResult.savedRun.savedRunId, runResult.runResult.generatedOutput.savedRunId)
        assertEquals(createResult.savedRun.savedRunId, runResult.runResult.generatedOutput.ruleSetId)
        assertEquals("CSV compare scope for CSV Order Compare", runResult.runResult.generatedOutput.compareScopeDescription)
        assertEquals(2L, runResult.runResult.generatedOutput.totalDifferences)
        assertEquals(1L, runResult.runResult.generatedOutput.onlyInFile1Count)
        assertEquals(1L, runResult.runResult.generatedOutput.onlyInFile2Count)

        def manifest = ec.entity.find("darpan.reconciliation.ReconciliationRunResult")
                .condition("reconciliationRunResultId", runResult.runResult.reconciliationRunResultId as String)
                .disableAuthz()
                .useCache(false)
                .one()
        assertNotNull(manifest)
        assertEquals("AUT_STAT_SUCCESS", manifest.statusEnumId)
        assertNotNull(manifest.startedDate)
        assertNotNull(manifest.completedDate)
        assertEquals(runResult.runResult.generatedOutput.fileName, manifest.resultDataManagerPath)

        Map<String, Object> outputListResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.list#GeneratedOutputs")
                .parameters([
                        savedRunId: createResult.savedRun.savedRunId,
                        pageIndex : 0,
                        pageSize  : 10,
                        query     : "",
                ])
                .disableAuthz()
                .call()

        List<Map<String, Object>> generatedOutputs = (List<Map<String, Object>>) (outputListResult.generatedOutputs ?: [])
        assertTrue(generatedOutputs.size() >= 1)
        Map<String, Object> matchingOutput = generatedOutputs.find { Map<String, Object> row ->
            row.fileName == runResult.runResult.generatedOutput.fileName
        }
        assertNotNull(matchingOutput)
        assertEquals(createResult.savedRun.savedRunId, matchingOutput.savedRunId)
        assertEquals("ruleset", matchingOutput.savedRunType)
    }

    @Test
    void ruleSetRunCreationCanonicalizesLegacySystemAliasesBeforePersistingCompareSources() {
        deleteLegacySystemSourceAliases()
        deleteRuleSetRunEntities("RS_RECON", "CS_RS_RECON_COMPARE_SCOPE")

        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                .parameters([
                        runName                 : "Recon",
                        file1SystemEnumId       : "DarSysOms",
                        file1FileTypeEnumId     : "DftCsv",
                        file1PrimaryIdExpression: "order_id",
                        file2SystemEnumId       : "DarSysShopify",
                        file2FileTypeEnumId     : "DftCsv",
                        file2PrimaryIdExpression: "order_id",
                        rules                   : [],
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals("CS_RS_RECON_COMPARE_SCOPE", createResult.savedRun.compareScopeId)
        assertEquals("OMS", createResult.savedRun.defaultFile1SystemEnumId)
        assertEquals("SHOPIFY", createResult.savedRun.defaultFile2SystemEnumId)

        List sources = ec.entity.find("darpan.rule.RuleSetCompareSource")
                .condition("compareScopeId", createResult.savedRun.compareScopeId)
                .useCache(false)
                .list() ?: []
        assertEquals("OMS", sources.find { it.fileSide == "FILE_1" }?.systemEnumId)
        assertEquals("SHOPIFY", sources.find { it.fileSide == "FILE_2" }?.systemEnumId)
    }

    @Test
    void ruleSetRunChildrenCarryDenormalizedTenantStampAndSweepBackfills() {
        deleteRuleSetRunEntities("RS_TENANT_STAMP", "CS_RS_TENANT_STAMP_COMPARE_SCOPE")

        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                .parameters([
                        runName                 : "Tenant Stamp",
                        file1SystemEnumId       : "OMS",
                        file1FileTypeEnumId     : "DftCsv",
                        file1PrimaryIdExpression: "order_id",
                        file2SystemEnumId       : "SHOPIFY",
                        file2FileTypeEnumId     : "DftCsv",
                        file2PrimaryIdExpression: "order_id",
                        rules                   : [[ruleText: "status is 'Pending'", severity: "HIGH"]],
                ])
                .disableAuthz()
                .call()
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        String compareScopeId = createResult.savedRun.compareScopeId as String
        String ruleSetId = createResult.savedRun.ruleSetId as String

        // MACH P1: children carry the denormalized owning-tenant stamp from the parent RuleSet.
        def ruleSet = ec.entity.find("darpan.rule.RuleSet").condition("ruleSetId", ruleSetId).useCache(false).one()
        assertEquals(KREWE, ruleSet.companyUserGroupId)
        def scope = ec.entity.find("darpan.rule.RuleSetCompareScope").condition("compareScopeId", compareScopeId).useCache(false).one()
        assertEquals(KREWE, scope.companyUserGroupId)
        List sources = ec.entity.find("darpan.rule.RuleSetCompareSource").condition("compareScopeId", compareScopeId).useCache(false).list()
        assertEquals(2, sources.size())
        sources.each { assertEquals(KREWE, it.companyUserGroupId) }
        List rules = ec.entity.find("darpan.rule.Rule").condition("ruleSetId", ruleSetId).useCache(false).list()
        assertFalse(rules.isEmpty())
        rules.each { assertEquals(KREWE, it.companyUserGroupId) }

        // Backfill sweep heals rows created before the stamp existed.
        scope.set("companyUserGroupId", null).update()
        sources.each { it.set("companyUserGroupId", null).update() }
        rules.each { it.set("companyUserGroupId", null).update() }
        Map<String, Object> sweepResult = ec.service.sync()
                .name("reconciliation.ReconciliationRuleEngineServices.sweep#RuleChildTenantStamps")
                .disableAuthz()
                .call()
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertTrue(((sweepResult.stampedCount ?: 0) as Integer) >= 3 + rules.size(),
                "sweep should stamp the nulled scope + sources + rules; got ${sweepResult.stampedCount}")
        assertEquals(KREWE, ec.entity.find("darpan.rule.RuleSetCompareScope")
                .condition("compareScopeId", compareScopeId).useCache(false).one().companyUserGroupId)
        ec.entity.find("darpan.rule.RuleSetCompareSource").condition("compareScopeId", compareScopeId)
                .useCache(false).list().each { assertEquals(KREWE, it.companyUserGroupId) }
        ec.entity.find("darpan.rule.Rule").condition("ruleSetId", ruleSetId)
                .useCache(false).list().each { assertEquals(KREWE, it.companyUserGroupId) }
    }

    @Test
    void jsonRuleSetRunAcceptsLegacySchemaSystemAliasesAndPersistsCanonicalSources() {
        upsertLegacySystemSourceAliases()
        String previousShopifySystemEnumId = updateJsonSchemaSystemEnumId("test-shopify-orders.schema.json", "DarSysShopify")
        String previousOmsSystemEnumId = updateJsonSchemaSystemEnumId("test-oms-orders.schema.json", "DarSysOms")

        try {
            Map<String, Object> createResult = ec.service.sync()
                    .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                    .parameters([
                            runName                 : "JSON Legacy Schema Alias Compare",
                            file1SystemEnumId       : "SHOPIFY",
                            file1FileTypeEnumId     : "DftJson",
                            file1SchemaFileName     : "test-shopify-orders.schema.json",
                            file1PrimaryIdExpression: '$.data.orders.edges[0].node.id|SHOPIFY_GID_TAIL',
                            file2SystemEnumId       : "OMS",
                            file2FileTypeEnumId     : "DftJson",
                            file2SchemaFileName     : "test-oms-orders.schema.json",
                            file2PrimaryIdExpression: '$.orders[0].order_id',
                            rules                   : [],
                    ])
                    .disableAuthz()
                    .call()

            assertFalse(ec.message.hasError(), ec.message.errors?.toString())
            assertEquals("SHOPIFY", createResult.savedRun.defaultFile1SystemEnumId)
            assertEquals("OMS", createResult.savedRun.defaultFile2SystemEnumId)

            List sources = ec.entity.find("darpan.rule.RuleSetCompareSource")
                    .condition("compareScopeId", createResult.savedRun.compareScopeId)
                    .useCache(false)
                    .list() ?: []
            assertEquals("SHOPIFY", sources.find { it.fileSide == "FILE_1" }?.systemEnumId)
            assertEquals("OMS", sources.find { it.fileSide == "FILE_2" }?.systemEnumId)
        } finally {
            updateJsonSchemaSystemEnumId("test-shopify-orders.schema.json", previousShopifySystemEnumId)
            updateJsonSchemaSystemEnumId("test-oms-orders.schema.json", previousOmsSystemEnumId)
            deleteLegacySystemSourceAliases()
        }
    }

    @Test
    void ruleSetRunResolverCanonicalizesLegacyStoredSystemAliasesForDefaultsAndApiMetadata() {
        upsertAutomationSourceTypeApi()
        upsertLegacySystemSourceAliases()
        seedOmsRestSourceConfig(KREWE, "KREWE_OMS_LEGACY")

        String savedRunId = null
        String compareScopeId = null
        try {
            Map<String, Object> createResult = ec.service.sync()
                    .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                    .parameters([
                            runName                   : "Legacy Source Alias",
                            file1SystemEnumId         : "OMS",
                            file1SourceTypeEnumId     : "AUT_SRC_API",
                            file1SystemMessageRemoteId: "HOTWAX_ORDERS_API",
                            file1SourceConfigId       : "KREWE_OMS_LEGACY",
                            file1PrimaryIdExpression  : "\$.records[*].orderId",
                            file2SystemEnumId         : "SHOPIFY",
                            file2FileTypeEnumId       : "DftCsv",
                            file2PrimaryIdExpression  : "order_id",
                            rules                     : [],
                    ])
                    .disableAuthz()
                    .call()

            assertFalse(ec.message.hasError(), ec.message.errors?.toString())
            savedRunId = createResult.savedRun.savedRunId as String
            compareScopeId = createResult.savedRun.compareScopeId as String
            updateCompareSourceSystemEnumId(compareScopeId, "FILE_1", "DarSysOms")
            updateCompareSourceSystemEnumId(compareScopeId, "FILE_2", "DarSysShopify")

            Map<String, Object> resolved = ReconciliationSavedRunSupport.resolveRuleSetRun(ec, savedRunId)
            assertNull(resolved.error)
            assertEquals("OMS", resolved.savedRun.defaultFile1SystemEnumId)
            assertEquals("SHOPIFY", resolved.savedRun.defaultFile2SystemEnumId)
            Map<String, Object> file1Option = ((List<Map<String, Object>>) resolved.savedRun.systemOptions).find { it.fileSide == "FILE_1" }
            Map<String, Object> file2Option = ((List<Map<String, Object>>) resolved.savedRun.systemOptions).find { it.fileSide == "FILE_2" }
            assertEquals("OMS", file1Option.enumId)
            assertEquals("SHOPIFY", file2Option.enumId)
            assertEquals("HOTWAX_OMS_REST", file1Option.sourceConfigType)
        } finally {
            if (savedRunId && compareScopeId) {
                deleteRuleSetRunEntities(savedRunId, compareScopeId)
            }
            deleteLegacySystemSourceAliases()
        }
    }

    @Test
    void apiSourceConfigResolutionCanonicalizesLegacySystemAliases() {
        seedOmsRestSourceConfig(KREWE, "KREWE_OMS_ALIAS")

        Map<String, Object> resolution = ReconciliationSavedRunSupport.resolveApiSourceConfig(
                ec,
                "file1",
                "DarSysOms",
                "KREWE_OMS_ALIAS",
                null,
                null
        )

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals("KREWE_OMS_ALIAS", resolution.sourceConfigId)
        assertEquals("HOTWAX_OMS_REST", resolution.sourceConfigType)
    }

    @Test
    void generatedOutputsAreSharedWithinTenantAndDeniedAcrossTenants() {
        Map<String, Object> runResult = createAndRunCsvSavedRun("CSV Tenant Shared Result")
        String savedRunId = runResult.runResult.savedRunId as String
        String outputFileName = runResult.runResult.generatedOutput.fileName as String
        assertTrue(outputFileName.startsWith("reconciliation-runs/${savedRunId}/"))

        loginAsTenantUser(
                SAME_TENANT_USER_ID,
                "test.krewe.viewer",
                KREWE,
                TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID
        )

        Map<String, Object> sameTenantListResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.list#GeneratedOutputs")
                .parameters([
                        savedRunId: savedRunId,
                        pageIndex : 0,
                        pageSize  : 10,
                        query     : "",
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        List<Map<String, Object>> sameTenantOutputs = (List<Map<String, Object>>) (sameTenantListResult.generatedOutputs ?: [])
        assertTrue(sameTenantOutputs.any { Map<String, Object> row -> row.fileName == outputFileName })

        Map<String, Object> sameTenantGetResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.get#GeneratedOutput")
                .parameters([fileName: outputFileName, format: "json"])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals(true, sameTenantGetResult.ok)
        assertEquals(outputFileName, sameTenantGetResult.outputFile.fileName)
        assertEquals("FILES", sameTenantGetResult.outputFile.sourceDetails.mode)
        List<Map<String, Object>> sourceFiles = (List<Map<String, Object>>) sameTenantGetResult.outputFile.sourceDetails.files
        assertEquals(2, sourceFiles.size())
        assertEquals("orders-1.csv", sourceFiles[0].fileName)
        assertEquals("orders-2.csv", sourceFiles[1].fileName)
        assertTrue((sourceFiles[0].filePath as String).startsWith("reconciliation-runs/${savedRunId}/"))

        String sourceFilePath = sourceFiles[0].filePath as String
        Map<String, Object> sameTenantSourceDownloadResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.get#GeneratedOutput")
                .parameters([fileName: sourceFilePath, format: "csv"])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals(true, sameTenantSourceDownloadResult.ok)
        assertEquals(sourceFilePath, sameTenantSourceDownloadResult.outputFile.fileName)
        assertEquals("orders-1.csv", sameTenantSourceDownloadResult.outputFile.downloadFileName)
        assertTrue((sameTenantSourceDownloadResult.outputFile.contentText as String).contains("A100"))

        loginAsTenantUser(
                OUTSIDE_TENANT_USER_ID,
                "test.gorjana.editor",
                GORJANA,
                TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID
        )

        Map<String, Object> outsideTenantListResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.list#GeneratedOutputs")
                .parameters([
                        savedRunId: savedRunId,
                        pageIndex : 0,
                        pageSize  : 10,
                        query     : "",
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertFalse(((List<Map<String, Object>>) (outsideTenantListResult.generatedOutputs ?: []))
                .any { Map<String, Object> row -> row.fileName == outputFileName })

        Map<String, Object> outsideTenantGetResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.get#GeneratedOutput")
                .parameters([fileName: outputFileName, format: "json"])
                .disableAuthz()
                .call()

        assertEquals(false, outsideTenantGetResult.ok)
        assertTrue((outsideTenantGetResult.errors ?: []).join(" ").contains("active tenant"))

        ec.message.clearErrors()
        Map<String, Object> outsideTenantSourceDownloadResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.get#GeneratedOutput")
                .parameters([fileName: sourceFilePath, format: "csv"])
                .disableAuthz()
                .call()

        assertEquals(false, outsideTenantSourceDownloadResult.ok)
        assertTrue((outsideTenantSourceDownloadResult.errors ?: []).join(" ").contains("active tenant"))

        ec.message.clearErrors()
        Map<String, Object> outsideTenantDeleteResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.delete#GeneratedOutput")
                .parameters([fileName: outputFileName])
                .disableAuthz()
                .call()

        assertEquals(false, outsideTenantDeleteResult.ok)
        assertTrue((outsideTenantDeleteResult.errors ?: []).join(" ").contains("active tenant"))

        ec.message.clearErrors()
        loginAsTenantUser(
                SAME_TENANT_USER_ID,
                "test.krewe.viewer",
                KREWE,
                TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID
        )

        Map<String, Object> stillVisibleResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.get#GeneratedOutput")
                .parameters([fileName: outputFileName, format: "json"])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals(true, stillVisibleResult.ok)
    }

    @Test
    void csvSavedRunRejectsJsonSchemaDocumentsBeforeSparkProjection() {
        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#CsvRun")
                .parameters([
                        runName           : "CSV Schema Guard",
                        file1SystemEnumId : "OMS",
                        file2SystemEnumId : "SHOPIFY",
                        file1CompareColumn: "orderId",
                        file2CompareColumn: "orderId",
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())

        String schemaDocument = '''{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "array",
  "items": {
    "type": "object",
    "properties": {
      "orderId": { "type": "string" }
    }
  }
}'''

        Map<String, Object> runResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.run#SavedRunDiff")
                .parameters([
                        savedRunId: createResult.savedRun.savedRunId,
                        file1Name : "Gorjana-OMS-Order.json",
                        file1Text : schemaDocument,
                        file2Name : "Gorjana-Shopify-Orders.json",
                        file2Text : schemaDocument,
                        hasHeader : true,
                ])
                .disableAuthz()
                .call()

        assertEquals(false, runResult.ok)
        assertTrue(((List) (runResult.errors ?: [])).size() > 0)
        assertTrue(ec.message.hasError())
        assertTrue(ec.message.errors.any { String message -> message.contains("expects CSV data") })
        assertTrue(ec.message.errors.any { String message -> message.contains("JSON Schema document") })
        assertTrue(ec.message.errors.any { String message -> message.contains("source data file") })
    }

    @Test
    void mappingsListOnlyReturnsActiveTenantRows() {
        seedCrossTenantMappingUsingReadableSchemas()

        Map<String, Object> listResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.list#Mappings")
                .parameters([
                        pageIndex: 0,
                        pageSize : 50,
                        query    : "",
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        List<Map<String, Object>> mappings = (List<Map<String, Object>>) (listResult.mappings ?: [])
        assertTrue(mappings.any { Map<String, Object> row -> row.reconciliationMappingId == "OrderIdSchemaMap" })
        assertFalse(mappings.any { Map<String, Object> row -> row.reconciliationMappingId == "GORJANA_MAPPING_WITH_KREWE_SCHEMAS" })
        assertTrue(mappings.every { Map<String, Object> row -> row.companyUserGroupId == KREWE })
    }

    @Test
    void viewOnlyActiveTenantCanListButCannotRunSavedRuns() {
        seedPermissionGroup(TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID, "Can view tenant-scoped Darpan data but cannot mutate it")
        replaceTenantPermission(KREWE, TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID)
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, KREWE)

        Map<String, Object> listResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.list#SavedRuns")
                .parameters([
                        pageIndex: 0,
                        pageSize : 20,
                        query    : "",
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        List<Map<String, Object>> savedRuns = (List<Map<String, Object>>) (listResult.savedRuns ?: [])
        assertTrue(savedRuns.any { Map<String, Object> row -> row.savedRunId == "OrderIdSchemaMap" })

        ec.message.clearErrors()
        Map<String, Object> runResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.run#SavedRunDiff")
                .parameters([
                        savedRunId: "OrderIdSchemaMap",
                        file1Name : "orders-1.csv",
                        file1Text : "order_id\nA100\n",
                        file2Name : "orders-2.csv",
                        file2Text : "order_id\nA200\n",
                        hasHeader : true,
                ])
                .disableAuthz()
                .call()

        assertEquals(false, runResult.ok)
        assertTrue((runResult.errors ?: []).join(" ").contains("view access"))
    }

    @Test
    void csvRunKeepsCompareScopeIdsWithinEntityLengthAndUsesCsvSuffix() {
        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#CsvRun")
                .parameters([
                        runName           : "CSV Compare Scope Name That Definitely Exceeds The Stored Id Limit",
                        file1SystemEnumId : "OMS",
                        file2SystemEnumId : "SHOPIFY",
                        file1CompareColumn: "order_id",
                        file2CompareColumn: "order_id",
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        String compareScopeId = createResult.savedRun.compareScopeId as String
        assertNotNull(compareScopeId)
        assertTrue(compareScopeId.size() <= ReconciliationSavedRunSupport.ENTITY_ID_MAX_LENGTH)
        assertTrue(compareScopeId.endsWith("_CSV_SCOPE"))
    }

    @Test
    void ruleSetRunCanBeCreatedWithInlineRulesAndExecutedThroughTheSavedRunFacade() {
        String skuMismatchRule = '''rule "ORDER_STATUS_MISMATCH"
when
    $m : Map(this["file1"] != null, this["file2"] != null)
    eval(reconciliation.rule.RuleDiffSupport.valuesDiffer(((Map) $m.get("file1")).get("status"), ((Map) $m.get("file2")).get("status")))
then
    reconciliation.rule.RuleDiffSupport.addFieldMismatch(
        $m,
        kcontext.getRule().getName(),
        "status",
        ((Map) $m.get("file1")).get("status"),
        ((Map) $m.get("file2")).get("status"),
        "WARN",
        "Status mismatch"
    );
end'''

        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                .parameters([
                        runName                 : "Order Status Compare",
                        file1SystemEnumId       : "OMS",
                        file1FileTypeEnumId     : "DftCsv",
                        file1PrimaryIdExpression: "order_id",
                        file2SystemEnumId       : "SHOPIFY",
                        file2FileTypeEnumId     : "DftCsv",
                        file2PrimaryIdExpression: "order_id",
                        rules                   : [
                                [
                                        sequenceNum: 10,
                                        ruleLogic  : skuMismatchRule,
                                        enabled    : "Y",
                                ],
                        ],
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals("Order Status Compare", createResult.savedRun.runName)
        assertEquals("ruleset", createResult.savedRun.runType)
        assertEquals("OMS", createResult.savedRun.defaultFile1SystemEnumId)
        assertEquals("SHOPIFY", createResult.savedRun.defaultFile2SystemEnumId)
        assertEquals(2, ((List) createResult.savedRun.systemOptions).size())
        assertEquals(null, ec.entity.find("darpan.rule.RuleSetCompareScope")
                .condition("compareScopeId", createResult.savedRun.compareScopeId)
                .useCache(false)
                .one()
                ?.objectType)

        Map<String, Object> runResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.run#SavedRunDiff")
                .parameters([
                        savedRunId: createResult.savedRun.savedRunId,
                        file1Name : "orders-1.csv",
                        file1Text : "order_id,status\nA100,open\nA200,closed\n",
                        file2Name : "orders-2.csv",
                        file2Text : "order_id,status\nA100,pending\nA200,closed\n",
                        hasHeader : true,
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals(createResult.savedRun.savedRunId, runResult.runResult.savedRunId)
        assertEquals("ruleset", runResult.runResult.runType)
        assertTrue(((List) runResult.runResult.validationErrors).isEmpty())
        assertTrue(((List) runResult.runResult.processingWarnings).isEmpty())
        assertEquals(1L, runResult.runResult.generatedOutput.totalDifferences)
        assertEquals(0L, (runResult.runResult.generatedOutput.onlyInFile1Count ?: 0) as Long)
        assertEquals(0L, (runResult.runResult.generatedOutput.onlyInFile2Count ?: 0) as Long)
    }

    @Test
    void ruleSetRunCanBeCreatedWithoutInitialRulesAndStillRunsBasicDiff() {
        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                .parameters([
                        runName                 : "Basic Diff Only",
                        file1SystemEnumId       : "OMS",
                        file1FileTypeEnumId     : "DftCsv",
                        file1PrimaryIdExpression: "order_id",
                        file2SystemEnumId       : "SHOPIFY",
                        file2FileTypeEnumId     : "DftCsv",
                        file2PrimaryIdExpression: "order_id",
                        rules                   : [],
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals("Basic Diff Only", createResult.savedRun.runName)
        assertEquals("ruleset", createResult.savedRun.runType)

        List rules = ec.entity.find("darpan.rule.Rule")
                .condition("ruleSetId", createResult.savedRun.ruleSetId)
                .useCache(false)
                .list() ?: []
        assertEquals(0, rules.size())

        Map<String, Object> runResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.run#SavedRunDiff")
                .parameters([
                        savedRunId: createResult.savedRun.savedRunId,
                        file1Name : "orders-1.csv",
                        file1Text : "order_id\nA100\nA200\nA300\n",
                        file2Name : "orders-2.csv",
                        file2Text : "order_id\nA200\nA300\nA400\n",
                        hasHeader : true,
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals(createResult.savedRun.savedRunId, runResult.runResult.savedRunId)
        assertEquals("ruleset", runResult.runResult.runType)
        assertTrue(((List) runResult.runResult.validationErrors).isEmpty())
        assertTrue(((List) runResult.runResult.processingWarnings).isEmpty())
        assertEquals(2L, runResult.runResult.generatedOutput.totalDifferences)
        assertEquals(1L, runResult.runResult.generatedOutput.onlyInFile1Count)
        assertEquals(1L, runResult.runResult.generatedOutput.onlyInFile2Count)
    }

    @Test
    void ruleSetRunCanPersistApiAndFileSourcesForMixedCreateRunSetup() {
        upsertEntity("moqui.basic.EnumerationType", [enumTypeId: "AutomationSourceType"], [
                enumTypeId : "AutomationSourceType",
                description: "Automation source type",
        ])
        upsertEntity("moqui.basic.Enumeration", [enumId: "AUT_SRC_API"], [
                enumId    : "AUT_SRC_API",
                enumTypeId: "AutomationSourceType",
                enumCode  : "API",
                description: "API source",
                sequenceNum: 1,
        ])
        ec.entity.find("moqui.service.message.SystemMessageRemote")
                .condition("systemMessageRemoteId", "HOTWAX_ORDERS_API")
                .disableAuthz()
                .useCache(false)
                .deleteAll()
        seedOmsRestSourceConfig(KREWE, "KREWE_OMS")

        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                .parameters([
                        runName                   : "Mixed API File Source",
                        description               : "OMS API against Shopify upload",
                        file1SystemEnumId         : "OMS",
                        file1SourceTypeEnumId     : "AUT_SRC_API",
                        file1SystemMessageRemoteId: "HOTWAX_ORDERS_API",
                        file1SourceConfigId       : "KREWE_OMS",
                        file1SourceConfigType     : "HOTWAX_OMS_REST",
                        file1PrimaryIdExpression  : "\$.records[*].orderId",
                        file2SystemEnumId         : "SHOPIFY",
                        file2FileTypeEnumId       : "DftCsv",
                        file2PrimaryIdExpression  : "order_id",
                        rules                     : [],
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertNotNull(ec.entity.find("moqui.service.message.SystemMessageRemote")
                .condition("systemMessageRemoteId", "HOTWAX_ORDERS_API")
                .disableAuthz()
                .useCache(false)
                .one())
        assertEquals("Mixed API File Source", createResult.savedRun.runName)

        List<Map<String, Object>> systemOptions = (List<Map<String, Object>>) (createResult.savedRun.systemOptions ?: [])
        Map<String, Object> file1Option = systemOptions.find { it.fileSide == "FILE_1" }
        Map<String, Object> file2Option = systemOptions.find { it.fileSide == "FILE_2" }
        assertEquals("AUT_SRC_API", file1Option.sourceTypeEnumId)
        assertEquals("HOTWAX_ORDERS_API", file1Option.systemMessageRemoteId)
        assertEquals("Orders API", file1Option.systemMessageRemoteLabel)
        assertEquals("KREWE_OMS", file1Option.sourceConfigId)
        assertEquals("HOTWAX_OMS_REST", file1Option.sourceConfigType)
        assertEquals("\$.records[*].orderId", file1Option.idFieldExpression)
        assertEquals("DftCsv", file2Option.fileTypeEnumId)
        assertEquals("order_id", file2Option.idFieldExpression)

        List sources = ec.entity.find("darpan.rule.RuleSetCompareSource")
                .condition("compareScopeId", createResult.savedRun.compareScopeId)
                .useCache(false)
                .list() ?: []
        def file1Source = sources.find { it.fileSide == "FILE_1" }
        def file2Source = sources.find { it.fileSide == "FILE_2" }
        assertEquals("AUT_SRC_API", file1Source.sourceTypeEnumId)
        assertEquals("HOTWAX_ORDERS_API", file1Source.systemMessageRemoteId)
        assertEquals("KREWE_OMS", file1Source.sourceConfigId)
        assertEquals("HOTWAX_OMS_REST", file1Source.sourceConfigType)
        assertEquals("\$.records[*].orderId", file1Source.primaryIdExpression)
        assertEquals("DftCsv", file2Source.fileTypeEnumId)
        assertEquals("order_id", file2Source.primaryIdExpression)

        ec.service.sync()
                .name("facade.ReconciliationFacadeServices.run#SavedRunDiff")
                .parameters([
                        savedRunId: createResult.savedRun.savedRunId,
                        file2Name : "shopify-orders.csv",
                        file2Text : "order_id\nA100\nA200\n",
                        hasHeader : true,
                ])
                .disableAuthz()
                .call()

        assertTrue(ec.message.hasError())
        assertTrue(ec.message.errors.any { String message -> message.contains("windowStartDate and windowEndDate are required") })
        assertFalse(ec.message.errors.any { String message -> message.contains("file1Name is required") })
        assertFalse(ec.message.errors.any { String message -> message.contains("file1Text is required") })
        ec.message.clearErrors()
    }

    @Test
    void ruleSetRunCreationTreatsDatabaseSourceLikeApiSource() {
        // MACH database-source epic Task 2 follow-up: AUT_SRC_DB must flow the same extract-vs-upload
        // path as AUT_SRC_API through create#RuleSetRun and run#SavedRunDiff — no fileTypeEnumId/schema
        // required at create time, and execution dispatches through the connector registry (not the
        // plain-upload "fileText is required" path), even though no DATABASE connector is registered yet.
        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                .parameters([
                        runName                 : "Database Source Compare",
                        file1SystemEnumId       : "DATABASE",
                        file1SourceTypeEnumId   : "AUT_SRC_DB",
                        file1SourceConfigId     : "TEST_DB_QUERY",
                        file1SourceConfigType   : "DATABASE_QUERY",
                        file1PrimaryIdExpression: "\$.records[*].orderId",
                        file2SystemEnumId       : "SHOPIFY",
                        file2FileTypeEnumId     : "DftCsv",
                        file2PrimaryIdExpression: "order_id",
                        rules                   : [],
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals("Database Source Compare", createResult.savedRun.runName)

        List<Map<String, Object>> systemOptions = (List<Map<String, Object>>) (createResult.savedRun.systemOptions ?: [])
        Map<String, Object> file1Option = systemOptions.find { it.fileSide == "FILE_1" }
        assertEquals("AUT_SRC_DB", file1Option.sourceTypeEnumId)
        assertEquals("TEST_DB_QUERY", file1Option.sourceConfigId)
        assertEquals("DATABASE_QUERY", file1Option.sourceConfigType)

        List sources = ec.entity.find("darpan.rule.RuleSetCompareSource")
                .condition("compareScopeId", createResult.savedRun.compareScopeId)
                .useCache(false)
                .list() ?: []
        def file1Source = sources.find { it.fileSide == "FILE_1" }
        assertEquals("AUT_SRC_DB", file1Source.sourceTypeEnumId)
        assertEquals("TEST_DB_QUERY", file1Source.sourceConfigId)
        assertEquals("DATABASE_QUERY", file1Source.sourceConfigType)
        assertNull(file1Source.fileTypeEnumId, "DB source is extracted, not uploaded — no fileTypeEnumId expected")
        assertNull(file1Source.schemaFileName)
        assertNull(file1Source.recordRootExpression)

        Map<String, Object> runResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.run#SavedRunDiff")
                .parameters([
                        savedRunId     : createResult.savedRun.savedRunId,
                        file2Name      : "shopify-orders.csv",
                        file2Text      : "order_id\nA100\nA200\n",
                        hasHeader      : true,
                        windowStartDate: "2026-06-01T00:00:00Z",
                        windowEndDate  : "2026-06-02T00:00:00Z",
                ])
                .disableAuthz()
                .call()

        assertTrue(ec.message.hasError())
        // No DATABASE SourceSystemConnector row exists yet (database-darpan component ships it later),
        // so extraction fails gracefully at connector resolution — proving the DB source took the
        // registry-driven extract path, not the plain-upload path (which would ask for file1Name/file1Text).
        assertTrue(ec.message.errors.any { String message -> message.contains("is not supported for manual saved-run execution") },
                "Unexpected errors: ${ec.message.errors}")
        assertFalse(ec.message.errors.any { String message -> message.contains("file1Name is required") })
        assertFalse(ec.message.errors.any { String message -> message.contains("file1Text is required") })
        ec.message.clearErrors()
    }

    @Test
    void ruleSetRunSaveTreatsDatabaseSourceLikeApiSourceAndRequiresSourceConfigId() {
        // Covers save#RuleSetRun's widened file{1,2}UsesConnectorSource gates (the create# twin is
        // covered above): switching an upload source to AUT_SRC_DB via save# must persist the DB
        // locator (sourceConfigId/Type) and null the upload-only columns, and a DB source missing
        // sourceConfigId must be rejected by save#'s new DB check.
        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                .parameters([
                        runName                 : "Database Source Save",
                        file1SystemEnumId       : "OMS",
                        file1FileTypeEnumId     : "DftCsv",
                        file1PrimaryIdExpression: "order_id",
                        file2SystemEnumId       : "SHOPIFY",
                        file2FileTypeEnumId     : "DftCsv",
                        file2PrimaryIdExpression: "order_id",
                        rules                   : [],
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        String savedRunId = createResult.savedRun.savedRunId as String
        String compareScopeId = createResult.savedRun.compareScopeId as String

        Map<String, Object> saveResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.save#RuleSetRun")
                .parameters([
                        savedRunId              : savedRunId,
                        runName                 : "Database Source Save",
                        file1SystemEnumId       : "DATABASE",
                        file1SourceTypeEnumId   : "AUT_SRC_DB",
                        file1SourceConfigId     : "TEST_DB_QUERY",
                        file1SourceConfigType   : "DATABASE_QUERY",
                        file1PrimaryIdExpression: "\$.records[*].orderId",
                        file2SystemEnumId       : "SHOPIFY",
                        file2FileTypeEnumId     : "DftCsv",
                        file2PrimaryIdExpression: "order_id",
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals(savedRunId, saveResult.savedRun.savedRunId)

        def file1Source = ec.entity.find("darpan.rule.RuleSetCompareSource")
                .condition("compareScopeId", compareScopeId)
                .condition("fileSide", "FILE_1")
                .useCache(false)
                .one()
        assertEquals("DATABASE", file1Source.systemEnumId)
        assertEquals("AUT_SRC_DB", file1Source.sourceTypeEnumId)
        assertEquals("TEST_DB_QUERY", file1Source.sourceConfigId)
        assertEquals("DATABASE_QUERY", file1Source.sourceConfigType)
        assertNull(file1Source.fileTypeEnumId, "DB source is extracted, not uploaded — no fileTypeEnumId expected")
        assertNull(file1Source.schemaFileName)
        assertNull(file1Source.recordRootExpression)

        // Upload side is untouched by the DB widening.
        def file2Source = ec.entity.find("darpan.rule.RuleSetCompareSource")
                .condition("compareScopeId", compareScopeId)
                .condition("fileSide", "FILE_2")
                .useCache(false)
                .one()
        assertEquals("DftCsv", file2Source.fileTypeEnumId)
        assertEquals("order_id", file2Source.primaryIdExpression)

        Map<String, Object> rejectedSaveResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.save#RuleSetRun")
                .parameters([
                        savedRunId              : savedRunId,
                        runName                 : "Database Source Save",
                        file1SystemEnumId       : "DATABASE",
                        file1SourceTypeEnumId   : "AUT_SRC_DB",
                        file1PrimaryIdExpression: "\$.records[*].orderId",
                        file2SystemEnumId       : "SHOPIFY",
                        file2FileTypeEnumId     : "DftCsv",
                        file2PrimaryIdExpression: "order_id",
                ])
                .disableAuthz()
                .call()

        assertTrue(ec.message.hasError())
        assertFalse((Boolean) rejectedSaveResult.ok)
        assertTrue(ec.message.errors.any { String message -> message.contains("file1 database source requires sourceConfigId") },
                "Unexpected errors: ${ec.message.errors}")
        ec.message.clearErrors()

        // The rejected save must not have clobbered the persisted DB locator.
        def file1SourceAfterReject = ec.entity.find("darpan.rule.RuleSetCompareSource")
                .condition("compareScopeId", compareScopeId)
                .condition("fileSide", "FILE_1")
                .useCache(false)
                .one()
        assertEquals("TEST_DB_QUERY", file1SourceAfterReject.sourceConfigId)
    }

    @Test
    void ruleSetRunCreationRejectsDatabaseSourceMissingSourceConfigId() {
        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                .parameters([
                        runName                 : "Database Source Missing Config",
                        file1SystemEnumId       : "DATABASE",
                        file1SourceTypeEnumId   : "AUT_SRC_DB",
                        file1PrimaryIdExpression: "\$.records[*].orderId",
                        file2SystemEnumId       : "SHOPIFY",
                        file2FileTypeEnumId     : "DftCsv",
                        file2PrimaryIdExpression: "order_id",
                        rules                   : [],
                ])
                .disableAuthz()
                .call()

        assertTrue(ec.message.hasError())
        assertFalse((Boolean) createResult.ok)
        assertTrue(ec.message.errors.any { String message -> message.contains("file1 database source requires sourceConfigId") },
                "Unexpected errors: ${ec.message.errors}")
        ec.message.clearErrors()
    }

    @Test
    void apiRuleSetRunFailsBeforeCompareSourceInsertWhenSourceTypeEnumIsMissing() {
        deleteRuleSetRunEntities("RS_API_ORDER_SYNC", "CS_RS_API_ORDER_SYNC_COMPARE_SCOPE")
        deleteAutomationSourceTypeReferences("AUT_SRC_API")
        ec.entity.find("moqui.basic.Enumeration")
                .condition("enumId", "AUT_SRC_API")
                .disableAuthz()
                .useCache(false)
                .deleteAll()
        upsertEntity("moqui.service.message.SystemMessageRemote", [systemMessageRemoteId: "OMS_REMOTE"], [
                systemMessageRemoteId: "OMS_REMOTE",
                description          : "OMS orders API",
                sendUrl              : "https://oms.example.test/orders",
        ])

        try {
            Map<String, Object> createResult = ec.service.sync()
                    .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                    .parameters([
                            runName                   : "API Order Sync",
                            file1SystemEnumId         : "OMS",
                            file1SourceTypeEnumId     : "AUT_SRC_API",
                            file1SystemMessageRemoteId: "OMS_REMOTE",
                            file1PrimaryIdExpression  : "\$.records[*].orderId",
                            file2SystemEnumId         : "SHOPIFY",
                            file2FileTypeEnumId       : "DftCsv",
                            file2PrimaryIdExpression  : "order_id",
                            rules                     : [],
                    ])
                    .disableAuthz()
                    .call()

            assertTrue(ec.message.hasError())
            assertFalse((Boolean) createResult.ok)
            assertTrue(ec.message.errors.any { String message ->
                message.contains("file1SourceTypeEnumId 'AUT_SRC_API' is not valid")
            })
            assertEquals(0, ec.entity.find("darpan.rule.RuleSetCompareSource")
                    .condition("compareScopeId", "CS_RS_API_ORDER_SYNC_COMPARE_SCOPE")
                    .disableAuthz()
                    .useCache(false)
                    .list()
                    .size())
        } finally {
            ec.message.clearErrors()
            upsertAutomationSourceTypeApi()
        }
    }

    @Test
    void ruleSetRunSelfHealsMissingShopifyOrdersRemote() {
        upsertEntity("moqui.basic.EnumerationType", [enumTypeId: "AutomationSourceType"], [
                enumTypeId : "AutomationSourceType",
                description: "Automation source type",
        ])
        upsertEntity("moqui.basic.Enumeration", [enumId: "AUT_SRC_API"], [
                enumId    : "AUT_SRC_API",
                enumTypeId: "AutomationSourceType",
                enumCode  : "API",
                description: "API source",
                sequenceNum: 1,
        ])
        ec.entity.find("moqui.service.message.SystemMessageRemote")
                .condition("systemMessageRemoteId", "SHOPIFY_REMOTE")
                .disableAuthz()
                .useCache(false)
                .deleteAll()
        seedShopifyAuthConfig(KREWE, "KREWE_SHOPIFY")

        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                .parameters([
                        runName                   : "Shopify API File Source",
                        description               : "Shopify API against HotWax upload",
                        file1SystemEnumId         : "SHOPIFY",
                        file1SourceTypeEnumId     : "AUT_SRC_API",
                        file1SystemMessageRemoteId: "SHOPIFY_REMOTE",
                        file1SourceConfigId       : "KREWE_SHOPIFY",
                        file1SourceConfigType     : "SHOPIFY_AUTH",
                        file1PrimaryIdExpression  : "\$.records[*].id",
                        file2SystemEnumId         : "OMS",
                        file2FileTypeEnumId       : "DftCsv",
                        file2PrimaryIdExpression  : "order_id",
                        rules                     : [],
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertNotNull(ec.entity.find("moqui.service.message.SystemMessageRemote")
                .condition("systemMessageRemoteId", "SHOPIFY_REMOTE")
                .disableAuthz()
                .useCache(false)
                .one())
        List<Map<String, Object>> systemOptions = (List<Map<String, Object>>) (createResult.savedRun.systemOptions ?: [])
        Map<String, Object> file1Option = systemOptions.find { it.fileSide == "FILE_1" }
        assertEquals("AUT_SRC_API", file1Option.sourceTypeEnumId)
        assertEquals("SHOPIFY_REMOTE", file1Option.systemMessageRemoteId)
        assertEquals("Admin GraphQL Orders", file1Option.systemMessageRemoteLabel)
        assertEquals("KREWE_SHOPIFY", file1Option.sourceConfigId)
        assertEquals("SHOPIFY_AUTH", file1Option.sourceConfigType)

        def file1Source = (ec.entity.find("darpan.rule.RuleSetCompareSource")
                .condition("compareScopeId", createResult.savedRun.compareScopeId)
                .condition("fileSide", "FILE_1")
                .useCache(false)
                .one())
        assertEquals("SHOPIFY_REMOTE", file1Source.systemMessageRemoteId)
        assertEquals("KREWE_SHOPIFY", file1Source.sourceConfigId)
    }

    @Test
    void apiRuleSetRunRequiresPrimaryIdExpressionBeforeCreatingCompareSources() {
        upsertEntity("moqui.basic.Enumeration", [enumId: "AUT_SRC_API"], [
                enumId    : "AUT_SRC_API",
                enumTypeId: "AutomationSourceType",
                enumCode  : "API",
                description: "API source",
                sequenceNum: 1,
        ])
        upsertEntity("moqui.service.message.SystemMessageRemote", [systemMessageRemoteId: "OMS_REMOTE"], [
                systemMessageRemoteId: "OMS_REMOTE",
                description          : "OMS orders API",
                sendUrl              : "https://oms.example.test/orders",
        ])

        ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                .parameters([
                        runName                   : "Missing API Primary ID",
                        file1SystemEnumId         : "OMS",
                        file1SourceTypeEnumId     : "AUT_SRC_API",
                        file1SystemMessageRemoteId: "OMS_REMOTE",
                        file2SystemEnumId         : "SHOPIFY",
                        file2FileTypeEnumId       : "DftCsv",
                        file2PrimaryIdExpression  : "order_id",
                        rules                     : [],
                ])
                .disableAuthz()
                .call()

        assertTrue(ec.message.hasError())
        assertTrue(ec.message.errors.any { String message -> message.contains("file1PrimaryIdExpression is required") })
        assertEquals(0, ec.entity.find("darpan.rule.RuleSet")
                .condition("ruleSetName", "Missing API Primary ID")
                .disableAuthz()
                .useCache(false)
                .list()
                .size())
    }

    @Test
    void jsonRuleSetRunUsesSavedSchemasForSourceSelectionAndExecutesThroughTheSavedRunFacade() {
        String noopRule = '''rule "NOOP_JSON_RULE"
when
    $m : Map()
then
end'''

        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                .parameters([
                        runName                 : "JSON Order Compare",
                        file1SystemEnumId       : "SHOPIFY",
                        file1FileTypeEnumId     : "DftJson",
                        file1SchemaFileName     : "test-shopify-orders.schema.json",
                        file1PrimaryIdExpression: '$.data.orders.edges[0].node.id|SHOPIFY_GID_TAIL',
                        file2SystemEnumId       : "OMS",
                        file2FileTypeEnumId     : "DftJson",
                        file2SchemaFileName     : "test-oms-orders.schema.json",
                        file2PrimaryIdExpression: '$.orders[0].order_id',
                        rules                   : [
                                [
                                        sequenceNum: 10,
                                        ruleLogic  : noopRule,
                                        enabled    : "Y",
                                ],
                        ],
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals("JSON Order Compare", createResult.savedRun.runName)
        assertEquals("ruleset", createResult.savedRun.runType)
        assertTrue((createResult.savedRun.compareScopeId as String).contains("_COMPARE_SCOPE"))
        assertFalse((createResult.savedRun.compareScopeId as String).contains("_CSV_SCOPE"))
        assertEquals("Compare scope for JSON Order Compare", createResult.savedRun.compareScopeDescription)
        assertEquals(null, ec.entity.find("darpan.rule.RuleSetCompareScope")
                .condition("compareScopeId", createResult.savedRun.compareScopeId)
                .useCache(false)
                .one()
                ?.objectType)

        List<Map<String, Object>> systemOptions = (List<Map<String, Object>>) (createResult.savedRun.systemOptions ?: [])
        assertEquals("test-shopify-orders.schema.json", systemOptions.find { it.fileSide == "FILE_1" }?.schemaFileName)
        assertEquals("test-oms-orders.schema.json", systemOptions.find { it.fileSide == "FILE_2" }?.schemaFileName)

        Map<String, Object> runResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.run#SavedRunDiff")
                .parameters([
                        savedRunId: createResult.savedRun.savedRunId,
                        file1Name : "orders-1.json",
                        file1Text : '{"data":{"orders":{"edges":[{"node":{"id":"gid://shopify/Order/1001","status":"open"}}]}}}',
                        file2Name : "orders-2.json",
                        file2Text : '{"orders":[{"order_id":"1001","status":"open"}]}',
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals(createResult.savedRun.savedRunId, runResult.runResult.savedRunId)
        assertEquals("ruleset", runResult.runResult.runType)
        assertTrue(((List) runResult.runResult.validationErrors).isEmpty())
        assertTrue(((List) runResult.runResult.processingWarnings).isEmpty())
        assertEquals("Compare scope for JSON Order Compare", runResult.runResult.generatedOutput.compareScopeDescription)
        assertEquals(0L, (runResult.runResult.generatedOutput.totalDifferences ?: 0) as Long)
        assertEquals(0L, (runResult.runResult.generatedOutput.onlyInFile1Count ?: 0) as Long)
        assertEquals(0L, (runResult.runResult.generatedOutput.onlyInFile2Count ?: 0) as Long)
    }

    @Test
    void ruleSetRunKeepsCompareScopeIdsWithinEntityLengthAndUsesNeutralSuffix() {
        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                .parameters([
                        runName                 : "RuleSet Compare Scope Name That Definitely Exceeds The Stored Id Limit",
                        file1SystemEnumId       : "OMS",
                        file1FileTypeEnumId     : "DftCsv",
                        file1PrimaryIdExpression: "order_id",
                        file2SystemEnumId       : "SHOPIFY",
                        file2FileTypeEnumId     : "DftCsv",
                        file2PrimaryIdExpression: "order_id",
                        rules                   : [],
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        String compareScopeId = createResult.savedRun.compareScopeId as String
        assertNotNull(compareScopeId)
        assertTrue(compareScopeId.size() <= ReconciliationSavedRunSupport.ENTITY_ID_MAX_LENGTH)
        assertTrue(compareScopeId.endsWith("_COMPARE_SCOPE"))
        assertFalse(compareScopeId.contains("_CSV_SCOPE"))
    }

    @Test
    void ruleSetRunSaveUpdatesRunSetupWithoutTouchingRules() {
        String noopRule = '''rule "NOOP_SAVE_RULE"
when
    $m : Map()
then
end'''

        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                .parameters([
                        runName                 : "RuleSet Save Source",
                        file1SystemEnumId       : "OMS",
                        file1FileTypeEnumId     : "DftCsv",
                        file1PrimaryIdExpression: "order_id",
                        file2SystemEnumId       : "SHOPIFY",
                        file2FileTypeEnumId     : "DftCsv",
                        file2PrimaryIdExpression: "order_id",
                        rules                   : [
                                [
                                        sequenceNum: 10,
                                        ruleLogic  : noopRule,
                                        enabled    : "Y",
                                ],
                        ],
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        String savedRunId = createResult.savedRun.savedRunId as String
        String compareScopeId = createResult.savedRun.compareScopeId as String
        assertNotNull(savedRunId)
        assertEquals(1, ec.entity.find("darpan.rule.Rule").condition("ruleSetId", savedRunId).useCache(false).list().size())

        Map<String, Object> saveResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.save#RuleSetRun")
                .parameters([
                        savedRunId              : savedRunId,
                        runName                 : "RuleSet Save Revised",
                        description             : "Updated non-rule setup.",
                        file1SystemEnumId       : "OMS",
                        file1FileTypeEnumId     : "DftCsv",
                        file1PrimaryIdExpression: "order_number",
                        file2SystemEnumId       : "SHOPIFY",
                        file2FileTypeEnumId     : "DftCsv",
                        file2PrimaryIdExpression: "shopify_order_number",
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals(savedRunId, saveResult.savedRun.savedRunId)
        assertEquals("RuleSet Save Revised", saveResult.savedRun.runName)
        assertEquals("Updated non-rule setup.", saveResult.savedRun.description)
        assertEquals(1, ec.entity.find("darpan.rule.Rule").condition("ruleSetId", savedRunId).useCache(false).list().size())
        assertEquals(1, ((List) saveResult.savedRun.rules).size())

        List sources = ec.entity.find("darpan.rule.RuleSetCompareSource")
                .condition("compareScopeId", compareScopeId)
                .useCache(false)
                .list() ?: []
        assertEquals("order_number", sources.find { it.fileSide == "FILE_1" }?.primaryIdExpression)
        assertEquals("shopify_order_number", sources.find { it.fileSide == "FILE_2" }?.primaryIdExpression)
    }

    @Test
    void ruleSetRunSavePersistsProvidedRulesAndReturnsEditableRuleMetadata() {
        String totalRule = '''rule "TOTAL_MISMATCH"
when
    $m : Map(this["file1"] != null, this["file2"] != null)
    eval(!java.util.Objects.equals(((Map) $m.get("file1")).get("total"), ((Map) $m.get("file2")).get("order_total")))
then
end'''
        String expression = '''{"type":"FIELD_COMPARISON","file1FieldPath":"total","file2FieldPath":"order_total","operator":"=","preActions":[{"fieldSide":"file1","action":"STRING_TO_INT"},{"fieldSide":"file2","action":"STRING_TO_NUMBER"}]}'''

        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                .parameters([
                        runName                 : "RuleSet Rule Save Source",
                        file1SystemEnumId       : "OMS",
                        file1FileTypeEnumId     : "DftCsv",
                        file1PrimaryIdExpression: "order_id",
                        file2SystemEnumId       : "SHOPIFY",
                        file2FileTypeEnumId     : "DftCsv",
                        file2PrimaryIdExpression: "order_id",
                        rules                   : [],
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        String savedRunId = createResult.savedRun.savedRunId as String

        Map<String, Object> saveResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.save#RuleSetRun")
                .parameters([
                        savedRunId              : savedRunId,
                        runName                 : "RuleSet Rule Save Source",
                        file1SystemEnumId       : "OMS",
                        file1FileTypeEnumId     : "DftCsv",
                        file1PrimaryIdExpression: "order_id",
                        file2SystemEnumId       : "SHOPIFY",
                        file2FileTypeEnumId     : "DftCsv",
                        file2PrimaryIdExpression: "order_id",
                        rules                   : [
                                [
                                        sequenceNum: 1,
                                        ruleLogic  : totalRule,
                                        ruleType   : "FIELD_COMPARISON",
                                        expression : expression,
                                        enabled    : "Y",
                                        severity   : "WARN",
                                ],
                        ],
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        List savedRules = (List) saveResult.savedRun.rules
        assertEquals(1, savedRules.size())
        assertEquals("total", savedRules[0].file1FieldPath)
        assertEquals("order_total", savedRules[0].file2FieldPath)
        assertEquals("=", savedRules[0].operator)
        assertEquals([
                [fieldSide: "file1", action: "STRING_TO_INT"],
                [fieldSide: "file2", action: "STRING_TO_NUMBER"],
        ], savedRules[0].preActions)
        assertEquals("FIELD_COMPARISON", savedRules[0].ruleType)
        assertNotNull(savedRules[0].ruleId)

        List ruleRows = ec.entity.find("darpan.rule.Rule").condition("ruleSetId", savedRunId).useCache(false).list() ?: []
        assertEquals(1, ruleRows.size())
        assertEquals(expression, ruleRows[0].expression)
    }

    @Test
    void createRuleSetRunRegeneratesFieldComparisonRuleLogicAndIgnoresMaliciousClientRuleLogic() {
        // Audit 2026-06-11 #1 durable fix (end-to-end): a client sends a benign FIELD_COMPARISON
        // expression but a MALICIOUS ruleLogic carrying an RCE consequence. The facade must regenerate
        // ruleLogic server-side from the expression, so the malicious string never reaches the compiler.
        String maliciousRuleLogic = 'rule "evil" when $m : Map() then java.lang.Runtime.getRuntime().exec("touch /tmp/pwn"); end'
        String expression = '{"type":"FIELD_COMPARISON","file1FieldPath":"status","file2FieldPath":"status","operator":"="}'

        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                .parameters([
                        runName                 : "Injection Attempt",
                        file1SystemEnumId       : "OMS",
                        file1FileTypeEnumId     : "DftCsv",
                        file1PrimaryIdExpression: "order_id",
                        file2SystemEnumId       : "SHOPIFY",
                        file2FileTypeEnumId     : "DftCsv",
                        file2PrimaryIdExpression: "order_id",
                        rules                   : [
                                [
                                        sequenceNum: 10,
                                        ruleType   : "FIELD_COMPARISON",
                                        expression : expression,
                                        ruleLogic  : maliciousRuleLogic,
                                        enabled    : "Y",
                                        severity   : "WARN",
                                ],
                        ],
                ])
                .disableAuthz()
                .call()

        // The save SUCCEEDS — the malicious ruleLogic is regenerated away, not rejected.
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        String savedRunId = createResult.savedRun.savedRunId as String

        List ruleRows = ec.entity.find("darpan.rule.Rule").condition("ruleSetId", savedRunId).useCache(false).list() ?: []
        assertEquals(1, ruleRows.size())
        String storedRuleLogic = ruleRows[0].ruleLogic as String
        // The stored/compiled ruleLogic is the server-regenerated FIELD_COMPARISON shape — NOT the client's.
        assertFalse(storedRuleLogic.contains("Runtime"), "malicious ruleLogic survived regeneration: ${storedRuleLogic}")
        assertFalse(storedRuleLogic.contains("getRuntime"), storedRuleLogic)
        assertTrue(storedRuleLogic.contains("RuleDiffSupport.addFieldMismatch"), storedRuleLogic)
        assertTrue(storedRuleLogic.contains('((Map) $m.get("file1")).get("status")'), storedRuleLogic)
        // The structured expression is preserved.
        assertEquals(expression, ruleRows[0].expression)
    }

    @Test
    void savedRunDeleteRemovesRuleSetChildrenAndGeneratedOutputs() {
        String noopRule = '''rule "NOOP_DELETE_RULE"
when
    $m : Map()
then
end'''

        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                .parameters([
                        runName                 : "Codex Cleanup Smoke",
                        file1SystemEnumId       : "OMS",
                        file1FileTypeEnumId     : "DftCsv",
                        file1PrimaryIdExpression: "order_id",
                        file2SystemEnumId       : "SHOPIFY",
                        file2FileTypeEnumId     : "DftCsv",
                        file2PrimaryIdExpression: "order_id",
                        rules                   : [
                                [
                                        sequenceNum: 10,
                                        ruleLogic  : noopRule,
                                        enabled    : "Y",
                                ],
                        ],
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        String savedRunId = createResult.savedRun.savedRunId as String
        String compareScopeId = createResult.savedRun.compareScopeId as String
        assertNotNull(savedRunId)
        assertNotNull(compareScopeId)

        Map<String, Object> runResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.run#SavedRunDiff")
                .parameters([
                        savedRunId: savedRunId,
                        file1Name : "orders-1.csv",
                        file1Text : "order_id\nA100\nA200\n",
                        file2Name : "orders-2.csv",
                        file2Text : "order_id\nA200\nA300\n",
                        hasHeader : true,
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals(savedRunId, runResult.runResult.generatedOutput.savedRunId)

        Map<String, Object> outputListResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.list#GeneratedOutputs")
                .parameters([
                        savedRunId: savedRunId,
                        pageIndex : 0,
                        pageSize  : 10,
                        query     : "",
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertTrue(((List) (outputListResult.generatedOutputs ?: [])).any { Map<String, Object> row ->
            row.fileName == runResult.runResult.generatedOutput.fileName
        })

        Map<String, Object> deleteResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.delete#SavedRun")
                .parameters([savedRunId: savedRunId])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError())
        assertEquals(true, deleteResult.deleted)
        assertEquals(savedRunId, deleteResult.deletedSavedRunId)
        assertEquals("ruleset", deleteResult.deletedRunType)
        assertEquals(1, deleteResult.deletedRuleCount)
        assertEquals(1, deleteResult.deletedCompareScopeCount)
        assertEquals(2, deleteResult.deletedCompareSourceCount)
        assertTrue((deleteResult.deletedGeneratedOutputCount as Integer) >= 1)

        assertNull(ec.entity.find("darpan.rule.RuleSet").condition("ruleSetId", savedRunId).useCache(false).one())
        assertNull(ec.entity.find("darpan.rule.RuleSetCompareScope").condition("compareScopeId", compareScopeId).useCache(false).one())
        assertEquals(0, ec.entity.find("darpan.rule.RuleSetCompareSource").condition("compareScopeId", compareScopeId).useCache(false).list().size())

        Map<String, Object> listResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.list#SavedRuns")
                .parameters([
                        pageIndex: 0,
                        pageSize : 50,
                        query    : "",
                ])
                .disableAuthz()
                .call()

        List<Map<String, Object>> savedRuns = (List<Map<String, Object>>) (listResult.savedRuns ?: [])
        assertFalse(savedRuns.any { Map<String, Object> row -> row.savedRunId == savedRunId })

        Map<String, Object> postDeleteOutputListResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.list#GeneratedOutputs")
                .parameters([
                        savedRunId: savedRunId,
                        pageIndex : 0,
                        pageSize  : 10,
                        query     : "",
                ])
                .disableAuthz()
                .call()

        assertTrue(((List) (postDeleteOutputListResult.generatedOutputs ?: [])).isEmpty())
    }

    @Test
    void excludeFiltersAreLoadedInSequenceOrderForTheSource() {
        // Fixture: a compare source with two exclusion rows created out of order.
        String compareScopeId = createCompareScopeWithSource("FILE_2")
        createExcludeFilterRow(compareScopeId, "FILE_2", 2, "statusId", "ORDER_CANCELLED")
        createExcludeFilterRow(compareScopeId, "FILE_2", 1, "salesChannelEnumId", "POS_SALES_CHANNEL,DRAFT_SALES_CHANNEL")

        def source = ec.entity.find("darpan.rule.RuleSetCompareSource")
                .condition("compareScopeId", compareScopeId).condition("fileSide", "FILE_2")
                .disableAuthz().useCache(false).one()

        List<Map<String, Object>> filters = ReconciliationSavedRunSupport.resolveExtractExcludeFilters(ec, source)

        assertEquals(2, filters.size())
        assertEquals(1, filters[0].sequenceNum)
        assertEquals("salesChannelEnumId", filters[0].fieldExpression)
        assertEquals("EXCLUDE_IN", filters[0].operator)
        assertEquals("POS_SALES_CHANNEL,DRAFT_SALES_CHANNEL", filters[0].filterValues)
        assertEquals(2, filters[1].sequenceNum)
    }

    @Test
    void aBoardConfiguredJsonPathExclusionActuallyExcludesARawRecord() {
        // FINAL-REVIEW CRITICAL 1a. The rules board writes fieldExpression as the field pill's own
        // JSONPath ('$.records[*].salesChannelEnumId'); SourceFilterSupport.firstMatchingRule scans
        // TOP-LEVEL RECORD KEYS. Before the fix these never met: the rule loaded fine, reported
        // excludedCount 0, raised no error, and excluded nothing. Every assertion below is on the real
        // save -> store -> dispatch -> match path, so it fails the moment that translation is dropped.
        String savedRunId = createRuleSetRun()
        saveRuleSetWithFilters(savedRunId, [
                [fieldExpression: '$.records[*].salesChannelEnumId', operator: "EXCLUDE_IN",
                 values         : ["POS_SALES_CHANNEL"]],
        ])

        // Storage keeps the operator-facing path, so the mark round-trips back onto the same pill.
        List rows = findFilterRows(savedRunId, "FILE_2")
        assertEquals(1, rows.size())
        assertEquals('$.records[*].salesChannelEnumId', rows[0].fieldExpression)

        String compareScopeId = ReconciliationSavedRunSupport.resolveRuleSetRun(ec, savedRunId).compareScope?.compareScopeId as String
        def source = ec.entity.find("darpan.rule.RuleSetCompareSource")
                .condition("compareScopeId", compareScopeId).condition("fileSide", "FILE_2")
                .disableAuthz().useCache(false).one()

        // The LOAD direction (what the UI hydrates its draft from) keeps the path.
        List<Map<String, Object>> loaded = ReconciliationSavedRunSupport.buildExcludeFilterResponse(ec, source)
        assertEquals(1, loaded.size())
        assertEquals('$.records[*].salesChannelEnumId', loaded[0].fieldExpression)

        // The DISPATCH direction (what the getter receives) is reduced to the record key.
        List<Map<String, Object>> dispatched = ReconciliationSavedRunSupport.resolveExtractExcludeFilters(ec, source)
        assertEquals(1, dispatched.size())
        assertEquals("salesChannelEnumId", dispatched[0].fieldExpression)

        // End to end: those dispatched rules, run through the getter's own parse + match, drop a raw
        // OMS-shaped record and keep one carrying a different channel.
        List<Map<String, Object>> parsed = SourceFilterSupport.parseRules(dispatched)
        assertNotNull(SourceFilterSupport.firstMatchingRule(
                [orderId: "O-1", salesChannelEnumId: "POS_SALES_CHANNEL"], parsed))
        assertNull(SourceFilterSupport.firstMatchingRule(
                [orderId: "O-2", salesChannelEnumId: "WEB_SALES_CHANNEL"], parsed))
    }

    @Test
    void savingAnExclusionOnAnUnresolvableFieldExpressionIsRejected() {
        // Fail closed at SAVE, not silently at extraction: an expression that reduces to no record
        // field could only ever match nothing.
        String savedRunId = createRuleSetRun()
        Map<String, Object> result = trySaveRuleSetWithFilters(savedRunId, [
                [fieldExpression: '$[*]', operator: "EXCLUDE_IN", values: ["POS_SALES_CHANNEL"]],
        ])

        assertTrue((result.errorMessage as String).contains("does not resolve to a record field"),
                "expected an unresolvable-field rejection, got: ${result.errorMessage}")
        assertEquals(0, findFilterRows(savedRunId, "FILE_2").size())
    }

    @Test
    void sourceWithNoExcludeFilterRowsReturnsAnEmptyList() {
        String compareScopeId = createCompareScopeWithSource("FILE_1")

        def source = ec.entity.find("darpan.rule.RuleSetCompareSource")
                .condition("compareScopeId", compareScopeId).condition("fileSide", "FILE_1")
                .disableAuthz().useCache(false).one()

        assertEquals([], ReconciliationSavedRunSupport.resolveExtractExcludeFilters(ec, source))
    }

    @Test
    void savingExcludeFiltersPersistsThemInSubmittedOrder() {
        String savedRunId = createRuleSetRun()
        saveRuleSetWithFilters(savedRunId, [
                [fieldExpression: "salesChannelEnumId", operator: "EXCLUDE_IN",
                 values: ["POS_SALES_CHANNEL", "DRAFT_SALES_CHANNEL"]],
                [fieldExpression: "statusId", operator: "EXCLUDE_IN", values: ["ORDER_CANCELLED"]],
        ])

        List rows = findFilterRows(savedRunId, "FILE_2")

        assertEquals(2, rows.size())
        assertEquals(1, rows[0].sequenceNum)
        assertEquals("salesChannelEnumId", rows[0].fieldExpression)
        assertEquals("POS_SALES_CHANNEL,DRAFT_SALES_CHANNEL", rows[0].filterValues)
        assertEquals(2, rows[1].sequenceNum)
        assertNotNull(rows[0].companyUserGroupId)
    }

    @Test
    void resavingReplacesRowsWithoutAccumulatingOrOrphaning() {
        String savedRunId = createRuleSetRun()
        saveRuleSetWithFilters(savedRunId, [[fieldExpression: "salesChannelEnumId", values: ["POS_SALES_CHANNEL"]]])
        saveRuleSetWithFilters(savedRunId, [[fieldExpression: "statusId", values: ["ORDER_CANCELLED"]]])

        List rows = findFilterRows(savedRunId, "FILE_2")

        assertEquals(1, rows.size())
        assertEquals("statusId", rows[0].fieldExpression)
    }

    @Test
    void explicitEmptyArrayClearsTheSidesRules() {
        String savedRunId = createRuleSetRun()
        saveRuleSetWithFilters(savedRunId, [[fieldExpression: "salesChannelEnumId", values: ["POS_SALES_CHANNEL"]]])
        saveRuleSetWithFilters(savedRunId, [])

        assertEquals(0, findFilterRows(savedRunId, "FILE_2").size())
    }

    @Test
    void omittingTheKeyLeavesExistingRulesAlone() {
        // An older or partial client must never silently wipe a configured exclusion.
        String savedRunId = createRuleSetRun()
        saveRuleSetWithFilters(savedRunId, [[fieldExpression: "salesChannelEnumId", values: ["POS_SALES_CHANNEL"]]])
        saveRuleSetWithoutFilterKeys(savedRunId)

        List rows = findFilterRows(savedRunId, "FILE_2")

        assertEquals(1, rows.size())
        assertEquals("salesChannelEnumId", rows[0].fieldExpression)
    }

    @Test
    void loadedRuleSetReturnsItsExcludeFilters() {
        String savedRunId = createRuleSetRun()
        saveRuleSetWithFilters(savedRunId, [[fieldExpression: "salesChannelEnumId",
                                             values         : ["POS_SALES_CHANNEL", "DRAFT_SALES_CHANNEL"]]])

        Map loaded = loadRuleSetRun(savedRunId)

        List filters = loaded.file2ExcludeFilters as List
        assertEquals(1, filters.size())
        assertEquals("salesChannelEnumId", filters[0].fieldExpression)
        assertEquals(["POS_SALES_CHANNEL", "DRAFT_SALES_CHANNEL"], filters[0].values)
    }

    // FILE_1's save/persist/load block is a structural mirror of FILE_2's throughout
    // ReconciliationFacadeServices.xml and buildExcludeFilterResponse, but every other test in this
    // file exercises FILE_2 only — prove FILE_1 independently so a copy-paste error there cannot hide.
    @Test
    void savingFile1ExcludeFiltersPersistsAndLoadsThemTheSameWayAsFile2() {
        String savedRunId = createRuleSetRun()
        Map<String, Object> result = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.save#RuleSetRun")
                .parameters(baseRuleSetSaveParams(savedRunId) +
                        ([file1ExcludeFilters: [[fieldExpression: "warehouseId", values: ["WH_DROPSHIP"]]]] as Map<String, Object>))
                .disableAuthz()
                .call()
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())

        List rows = findFilterRows(savedRunId, "FILE_1")
        assertEquals(1, rows.size())
        assertEquals("warehouseId", rows[0].fieldExpression)
        assertEquals("WH_DROPSHIP", rows[0].filterValues)
        assertEquals(0, findFilterRows(savedRunId, "FILE_2").size())

        Map loaded = loadRuleSetRun(savedRunId)
        List filters = loaded.file1ExcludeFilters as List
        assertEquals(1, filters.size())
        assertEquals("warehouseId", filters[0].fieldExpression)
        assertEquals(["WH_DROPSHIP"], filters[0].values)
    }

    // Pins validate-before-delete: both tests below first save a VALID exclusion, then attempt an
    // invalid save, then assert the original rule survived. A fresh-run-with-nothing-to-delete version
    // of this test would still pass even if a future refactor reordered the FILE_2 block to delete
    // existing rows before validating the new payload — which would destroy an operator's
    // already-configured exclusions on a rejected save.
    @Test
    void blankFieldExpressionIsRejectedAtSave() {
        String savedRunId = createRuleSetRun()
        saveRuleSetWithFilters(savedRunId, [[fieldExpression: "salesChannelEnumId", values: ["POS_SALES_CHANNEL"]]])

        Map result = trySaveRuleSetWithFilters(savedRunId, [[fieldExpression: "  ", values: ["POS_SALES_CHANNEL"]]])

        assertTrue(result.errorMessage.toString().toLowerCase().contains("field"))
        List rows = findFilterRows(savedRunId, "FILE_2")
        assertEquals(1, rows.size())
        assertEquals("salesChannelEnumId", rows[0].fieldExpression)
    }

    @Test
    void emptyValueListIsRejectedAtSave() {
        String savedRunId = createRuleSetRun()
        saveRuleSetWithFilters(savedRunId, [[fieldExpression: "salesChannelEnumId", values: ["POS_SALES_CHANNEL"]]])

        Map result = trySaveRuleSetWithFilters(savedRunId, [[fieldExpression: "statusId", values: []]])

        assertTrue(result.errorMessage.toString().toLowerCase().contains("value"))
        List rows = findFilterRows(savedRunId, "FILE_2")
        assertEquals(1, rows.size())
        assertEquals("salesChannelEnumId", rows[0].fieldExpression)
    }

    // ── Fix round 1: create#RuleSetRun must be symmetric with save#RuleSetRun, not silently drop
    // exclusion filters (Moqui strips undeclared in-parameters before the action script ever runs —
    // "undeclared" is fail-silent, not fail-closed). ──

    @Test
    void creatingRuleSetRunWithExcludeFiltersPersistsThem() {
        String savedRunId = createRuleSetRun(
                [file2ExcludeFilters: [[fieldExpression: "salesChannelEnumId", values: ["POS_SALES_CHANNEL"]]]] as Map<String, Object>)

        List rows = findFilterRows(savedRunId, "FILE_2")
        assertEquals(1, rows.size())
        assertEquals("salesChannelEnumId", rows[0].fieldExpression)
        assertEquals("POS_SALES_CHANNEL", rows[0].filterValues)
        assertNotNull(rows[0].companyUserGroupId)
    }

    @Test
    void creatingRuleSetRunWithoutExcludeFilterKeysPersistsNone() {
        String savedRunId = createRuleSetRun()

        assertEquals(0, findFilterRows(savedRunId, "FILE_1").size())
        assertEquals(0, findFilterRows(savedRunId, "FILE_2").size())
    }

    @Test
    void creatingRuleSetRunWithAnInvalidExcludeFilterRejectsTheCreate() {
        Map<String, Object> createParams = baseRuleSetCreateParams() +
                ([file2ExcludeFilters: [[fieldExpression: "  ", values: ["POS_SALES_CHANNEL"]]]] as Map<String, Object>)

        ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                .parameters(createParams)
                .disableAuthz()
                .call()

        assertTrue(ec.message.hasError())
        assertTrue(ec.message.errors.any { String message -> message.toLowerCase().contains("field") })
        // The invalid FILE_2 payload is validated after RuleSet/CompareScope/FILE_1 source rows are
        // already created within the same transaction — this proves the whole create rolled back
        // rather than leaving a partial RuleSet row behind under that run name.
        assertEquals(0, ec.entity.find("darpan.rule.RuleSet")
                .condition("ruleSetName", createParams.runName)
                .disableAuthz().useCache(false).list().size())
    }

    private void seedCrossTenantMappingUsingReadableSchemas() {
        upsertEntity("moqui.security.UserGroup", [userGroupId: GORJANA], [
                userGroupId    : GORJANA,
                description    : "Gorjana",
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID,
        ])
        upsertEntity("darpan.mapping.ReconciliationMapping", [reconciliationMappingId: "GORJANA_MAPPING_WITH_KREWE_SCHEMAS"], [
                reconciliationMappingId: "GORJANA_MAPPING_WITH_KREWE_SCHEMAS",
                mappingName            : "Gorjana Cross Tenant Mapping",
                description            : "Cross-tenant mapping that reuses active-tenant schemas",
                companyUserGroupId     : GORJANA,
                createdByUserId        : TEST_USER_ID,
        ])
        upsertEntity("darpan.mapping.ReconciliationMappingMember", [mappingMemberId: "GorjanaCrossTenantOms"], [
                mappingMemberId        : "GorjanaCrossTenantOms",
                reconciliationMappingId: "GORJANA_MAPPING_WITH_KREWE_SCHEMAS",
                systemEnumId           : "OMS",
                fileTypeEnumId         : "DftCsv",
                schemaFileName         : "test-oms-order-id.schema.json",
                idFieldExpression      : "order_id",
        ])
        upsertEntity("darpan.mapping.ReconciliationMappingMember", [mappingMemberId: "GorjanaCrossTenantShopify"], [
                mappingMemberId        : "GorjanaCrossTenantShopify",
                reconciliationMappingId: "GORJANA_MAPPING_WITH_KREWE_SCHEMAS",
                systemEnumId           : "SHOPIFY",
                fileTypeEnumId         : "DftCsv",
                schemaFileName         : "test-shopify-order-id.schema.json",
                idFieldExpression      : "order_id",
        ])
    }

    private void seedPermissionGroup(String permissionGroupId, String description) {
        upsertEntity("moqui.security.UserGroup", [userGroupId: permissionGroupId], [
                userGroupId    : permissionGroupId,
                description    : description,
                groupTypeEnumId: TenantAccessSupport.DARPAN_PERMISSION_GROUP_TYPE_ENUM_ID,
        ])
    }

    private void replaceTenantPermission(String tenantId, String permissionGroupId) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        ArtifactExecutionInfo aei = ec.artifactExecution.push(
                "replaceSavedRunTenantPermission",
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
                    .deleteAll()
        } finally {
            ec.artifactExecution.pop(aei)
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
        upsertEntity(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [
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

    private Map<String, Object> createAndRunCsvSavedRun(String runName) {
        loginAsTenantUser(
                TEST_USER_ID,
                "test.customer",
                KREWE,
                TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID
        )

        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#CsvRun")
                .parameters([
                        runName           : runName,
                        file1SystemEnumId : "OMS",
                        file2SystemEnumId : "SHOPIFY",
                        file1CompareColumn: "order_id",
                        file2CompareColumn: "order_id",
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())

        Map<String, Object> runResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.run#SavedRunDiff")
                .parameters([
                        savedRunId: createResult.savedRun.savedRunId,
                        file1Name : "orders-1.csv",
                        file1Text : "order_id\nA100\nA200\nA300\n",
                        file2Name : "orders-2.csv",
                        file2Text : "order_id\nA200\nA300\nA400\n",
                        hasHeader : true,
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        return runResult
    }

    private void deleteLegacySystemSourceAliases() {
        ["HOTWAX", "DarSysOms", "DarSysShopify", "DarSysNetSuite", "DarSysNetsuite", "DarSysSapi"].each { String enumId ->
            ec.entity.find("moqui.basic.Enumeration")
                    .condition("enumId", enumId)
                    .disableAuthz()
                    .useCache(false)
                    .deleteAll()
        }
    }

    private void upsertLegacySystemSourceAliases() {
        upsertEntity("moqui.basic.Enumeration", [enumId: "DarSysOms"], [
                enumId    : "DarSysOms",
                enumTypeId: "DarpanSystemSource",
                enumCode  : "HOTWAX",
                description: "HotWax legacy alias",
                sequenceNum: 1,
        ])
        upsertEntity("moqui.basic.Enumeration", [enumId: "DarSysShopify"], [
                enumId    : "DarSysShopify",
                enumTypeId: "DarpanSystemSource",
                enumCode  : "SHOPIFY",
                description: "Shopify legacy alias",
                sequenceNum: 2,
        ])
    }

    private String updateJsonSchemaSystemEnumId(String schemaName, String systemEnumId) {
        def schema = ec.entity.find("darpan.reconciliation.JsonSchema")
                .condition("schemaName", schemaName)
                .disableAuthz()
                .useCache(false)
                .one()
        assertNotNull(schema)
        String previousSystemEnumId = schema.systemEnumId as String
        schema.set("systemEnumId", systemEnumId)
        schema.update()
        return previousSystemEnumId
    }

    private void updateCompareSourceSystemEnumId(String compareScopeId, String fileSide, String systemEnumId) {
        def source = ec.entity.find("darpan.rule.RuleSetCompareSource")
                .condition("compareScopeId", compareScopeId)
                .condition("fileSide", fileSide)
                .disableAuthz()
                .useCache(false)
                .one()
        assertNotNull(source)
        source.set("systemEnumId", systemEnumId)
        source.update()
    }

    private void deleteRuleSetRunEntities(String ruleSetId, String compareScopeId) {
        // RuleSetCompareSourceFilter/KeyField FK-reference RuleSetCompareSource (RSCFL_SOURCE/
        // RSCKF_SOURCE on compareScopeId+fileSide) — delete children first or the parent delete below
        // throws a constraint violation for any fixture that created exclusion filters or key fields.
        ec.entity.find("darpan.rule.RuleSetCompareSourceFilter")
                .condition("compareScopeId", compareScopeId)
                .disableAuthz()
                .useCache(false)
                .deleteAll()
        ec.entity.find("darpan.rule.RuleSetCompareSourceKeyField")
                .condition("compareScopeId", compareScopeId)
                .disableAuthz()
                .useCache(false)
                .deleteAll()
        ec.entity.find("darpan.rule.RuleSetCompareSource")
                .condition("compareScopeId", compareScopeId)
                .disableAuthz()
                .useCache(false)
                .deleteAll()
        ec.entity.find("darpan.rule.RuleSetCompareScope")
                .condition("compareScopeId", compareScopeId)
                .disableAuthz()
                .useCache(false)
                .deleteAll()
        ec.entity.find("darpan.rule.Rule")
                .condition("ruleSetId", ruleSetId)
                .disableAuthz()
                .useCache(false)
                .deleteAll()
        ec.entity.find("darpan.rule.RuleSet")
                .condition("ruleSetId", ruleSetId)
                .disableAuthz()
                .useCache(false)
                .deleteAll()
    }

    private void deleteAutomationSourceTypeReferences(String sourceTypeEnumId) {
        ec.entity.find("darpan.rule.RuleSetCompareSource")
                .condition("sourceTypeEnumId", sourceTypeEnumId)
                .disableAuthz()
                .useCache(false)
                .deleteAll()
        ec.entity.find("darpan.reconciliation.ReconciliationAutomationSource")
                .condition("sourceTypeEnumId", sourceTypeEnumId)
                .disableAuthz()
                .useCache(false)
                .deleteAll()
    }

    private void upsertAutomationSourceTypeApi() {
        upsertEntity("moqui.basic.EnumerationType", [enumTypeId: "AutomationSourceType"], [
                enumTypeId : "AutomationSourceType",
                description: "Automation source type",
        ])
        upsertEntity("moqui.basic.Enumeration", [enumId: "AUT_SRC_API"], [
                enumId    : "AUT_SRC_API",
                enumTypeId: "AutomationSourceType",
                enumCode  : "API",
                description: "API source",
                sequenceNum: 1,
        ])
    }

    private void seedOmsRestSourceConfig(String tenantId, String configId) {
        upsertEntity("darpan.hotwax.HotWaxOmsRestSourceConfig", [omsRestSourceConfigId: configId], [
                omsRestSourceConfigId : configId,
                description           : "Krewe OMS Orders",
                companyUserGroupId    : tenantId,
                createdByUserId       : TEST_USER_ID,
                baseUrl               : "https://oms.example.invalid",
                ordersPath            : "/rest/s1/oms/orders",
                authType              : "NONE",
                connectTimeoutSeconds : 30L,
                readTimeoutSeconds    : 60L,
                isActive              : "Y",
                canReadOrders         : "Y",
                createdDate           : TEST_FROM_DATE,
                lastUpdatedDate       : TEST_FROM_DATE,
        ])
    }

    private void seedShopifyAuthConfig(String tenantId, String configId) {
        upsertEntity("darpan.shopify.ShopifyAuthConfig", [shopifyAuthConfigId: configId], [
                shopifyAuthConfigId: configId,
                description        : "Krewe Shopify",
                companyUserGroupId : tenantId,
                createdByUserId    : TEST_USER_ID,
                shopApiUrl         : "https://krewe.myshopify.com",
                apiVersion         : "2026-01",
                accessToken        : "shpat_test",
                isActive           : "Y",
                canReadOrders      : "Y",
        ])
    }

    private void loginAsTenantUser(String userId, String username, String tenantId, String permissionGroupId) {
        seedPermissionGroup(permissionGroupId, permissionGroupId)
        upsertEntity("moqui.security.UserGroup", [userGroupId: tenantId], [
                userGroupId    : tenantId,
                description    : tenantId,
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID,
        ])
        upsertEntity("moqui.security.UserAccount", [userId: userId], [
                userId        : userId,
                username      : username,
                userFullName  : username,
                currentPassword: "",
                disabled      : "N",
        ])
        upsertEntity("moqui.security.UserGroupMember", [
                userGroupId: tenantId,
                userId     : userId,
                fromDate   : TEST_FROM_DATE,
        ], [
                userGroupId: tenantId,
                userId     : userId,
                fromDate   : TEST_FROM_DATE,
        ])
        upsertEntity(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [
                tenantUserGroupId    : tenantId,
                userId               : userId,
                permissionUserGroupId: permissionGroupId,
                fromDate             : TEST_FROM_DATE,
        ], [
                tenantUserGroupId    : tenantId,
                userId               : userId,
                permissionUserGroupId: permissionGroupId,
                fromDate             : TEST_FROM_DATE,
        ])

        if (!ec.user.internalLoginUser(userId)) {
            ec.user.internalLoginUser(username)
        }
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, tenantId)
        ec.message.clearErrors()
    }

    // Creates a full rule-set compare scope (both file sides) through the real create#RuleSetRun
    // facade path, so the resulting RuleSetCompareScope/RuleSetCompareSource rows carry the same
    // denormalized companyUserGroupId tenant stamp production runs produce. fileSide only varies the
    // run name so repeated calls within one test do not collide.
    private String createCompareScopeWithSource(String fileSide) {
        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                .parameters([
                        runName                 : "Exclude Filter Fixture ${fileSide}",
                        file1SystemEnumId       : "OMS",
                        file1FileTypeEnumId     : "DftCsv",
                        file1PrimaryIdExpression: "order_id",
                        file2SystemEnumId       : "SHOPIFY",
                        file2FileTypeEnumId     : "DftCsv",
                        file2PrimaryIdExpression: "order_id",
                        rules                   : [],
                ])
                .disableAuthz()
                .call()
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        return createResult.savedRun.compareScopeId as String
    }

    // RuleSetCompareSourceFilter carries its own denormalized companyUserGroupId (mirroring
    // RuleSetCompareSourceKeyField), so the fixture stamps it explicitly to KREWE — the active tenant
    // this file logs in as by default (see clearErrors()) — the same way production create#RuleSetRun
    // stamps sibling child rows from the parent RuleSet.
    private void createExcludeFilterRow(String compareScopeId, String fileSide, int sequenceNum,
                                        String fieldExpression, String filterValues) {
        ec.service.sync().name("create#darpan.rule.RuleSetCompareSourceFilter").parameters([
                compareScopeId    : compareScopeId,
                fileSide          : fileSide,
                sequenceNum       : sequenceNum,
                fieldExpression   : fieldExpression,
                operator          : "EXCLUDE_IN",
                filterValues      : filterValues,
                companyUserGroupId: KREWE,
        ]).disableAuthz().call()
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
    }

    private void upsertEntity(String entityName, Map<String, Object> pkFields, Map<String, Object> fields) {
        def existing = ec.entity.find(entityName)
                .condition(pkFields)
                .disableAuthz()
                .useCache(false)
                .one()
        if (existing != null) return

        ReconciliationSmokeTestSupport.insertEntityDirect(ec, entityName, fields)
    }

    // ── Task 7 fixtures: save#RuleSetRun exclusion-filter persistence and list#SavedRuns load ──

    // RULE_SET has a unique (companyUserGroupId, ruleSetName) constraint (RULESET_TENANT_NAME) — every
    // call needs a unique runName, not just the generated ruleSetId (generateRuleSetId only dedupes
    // the id, not the name).
    private Map<String, Object> baseRuleSetCreateParams() {
        return [
                runName                 : "Exclude Filter Save Fixture ${UUID.randomUUID()}".toString(),
                file1SystemEnumId       : "OMS",
                file1FileTypeEnumId     : "DftCsv",
                file1PrimaryIdExpression: "order_id",
                file2SystemEnumId       : "SHOPIFY",
                file2FileTypeEnumId     : "DftCsv",
                file2PrimaryIdExpression: "order_id",
                rules                   : [],
        ] as Map<String, Object>
    }

    // extraParams layers on top of baseRuleSetCreateParams — used by the create-time exclude-filter
    // tests to pass file1ExcludeFilters/file2ExcludeFilters without duplicating the six required
    // source fields. Asserts success; use the raw ec.service.sync() call directly (see
    // creatingRuleSetRunWithAnInvalidExcludeFilterRejectsTheCreate) to test a rejected create.
    private String createRuleSetRun(Map<String, Object> extraParams = [:]) {
        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#RuleSetRun")
                .parameters(baseRuleSetCreateParams() + extraParams)
                .disableAuthz()
                .call()
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        String savedRunId = createResult.savedRun.savedRunId as String
        String compareScopeId = createResult.savedRun.compareScopeId as String
        ruleSetRunFixturesToCleanUp.add([ruleSetId: savedRunId, compareScopeId: compareScopeId])
        return savedRunId
    }

    // Every field save#RuleSetRun requires besides savedRunId itself; filter-specific tests only vary
    // the file2ExcludeFilters key layered on top of this, matching ruleSetRunSaveUpdatesRunSetupWithoutTouchingRules above.
    // runName is derived from savedRunId (not a fixed literal) so repeated saves within one test
    // re-assert the same name (no-op, no collision) while different tests' distinct rule sets never
    // collide on the RULESET_TENANT_NAME (companyUserGroupId, ruleSetName) unique constraint.
    private Map<String, Object> baseRuleSetSaveParams(String savedRunId) {
        return [
                savedRunId              : savedRunId,
                runName                 : "Exclude Filter Save Fixture ${savedRunId}".toString(),
                file1SystemEnumId       : "OMS",
                file1FileTypeEnumId     : "DftCsv",
                file1PrimaryIdExpression: "order_id",
                file2SystemEnumId       : "SHOPIFY",
                file2FileTypeEnumId     : "DftCsv",
                file2PrimaryIdExpression: "order_id",
        ] as Map<String, Object>
    }

    private Map<String, Object> saveRuleSetWithFilters(String savedRunId, List filters) {
        Map<String, Object> result = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.save#RuleSetRun")
                .parameters(baseRuleSetSaveParams(savedRunId) + ([file2ExcludeFilters: filters] as Map<String, Object>))
                .disableAuthz()
                .call()
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        return result
    }

    private void saveRuleSetWithoutFilterKeys(String savedRunId) {
        Map<String, Object> result = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.save#RuleSetRun")
                .parameters(baseRuleSetSaveParams(savedRunId))
                .disableAuthz()
                .call()
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
    }

    // Unlike saveRuleSetWithFilters, this never asserts success — it hands back whatever ec.message
    // accumulated so the caller can assert on the rejection instead of an uncaught exception. Moqui's
    // ServiceCallSyncImpl catches Throwable from service actions and converts it into ec.message
    // errors rather than letting it propagate, so validateExcludeFilterPayload's thrown
    // IllegalArgumentException surfaces here, not as a Groovy exception at the call site.
    private Map<String, Object> trySaveRuleSetWithFilters(String savedRunId, List filters) {
        Map<String, Object> result = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.save#RuleSetRun")
                .parameters(baseRuleSetSaveParams(savedRunId) + ([file2ExcludeFilters: filters] as Map<String, Object>))
                .disableAuthz()
                .call()
        String errorMessage = ec.message.hasError() ? ec.message.errors.join("; ") : ""
        return ((result ?: [:]) as Map<String, Object>) + ([errorMessage: errorMessage] as Map<String, Object>)
    }

    private Map<String, Object> loadRuleSetRun(String savedRunId) {
        Map<String, Object> listResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.list#SavedRuns")
                .parameters([pageIndex: 0, pageSize: 20, query: savedRunId])
                .disableAuthz()
                .call()
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        List<Map<String, Object>> savedRuns = (List<Map<String, Object>>) (listResult.savedRuns ?: [])
        return savedRuns.find { Map<String, Object> row -> row.savedRunId == savedRunId } as Map<String, Object>
    }

    // NOTE: adapted from the task brief, which named the first parameter "compareScopeId" and passed
    // savedRunId directly as if the two were interchangeable. They are not — generateCompareScopeId
    // derives a distinct id (CS_RS_..._COMPARE_SCOPE) from the ruleSetId, so querying on savedRunId
    // as compareScopeId would silently match zero rows. This resolves the real compareScopeId from
    // the savedRunId (ruleSetId) first.
    private List findFilterRows(String savedRunId, String fileSide) {
        String compareScopeId = ReconciliationSavedRunSupport.resolveRuleSetRun(ec, savedRunId).compareScope?.compareScopeId as String
        return ec.entity.find("darpan.rule.RuleSetCompareSourceFilter")
                .condition("compareScopeId", compareScopeId)
                .condition("fileSide", fileSide)
                .orderBy("sequenceNum").disableAuthz().useCache(false).list() ?: []
    }
}
