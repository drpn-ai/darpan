package darpan.reconciliation.generic

import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GenericReconciliationServiceSmokeTests {
    private static final JsonSlurper JSON_SLURPER = new JsonSlurper()

    private ExecutionContext ec
    private Path backendRoot

    @BeforeAll
    void setup() {
        backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "reconciliation-generic-smoke")
        ReconciliationSmokeTestSupport.seedCompareScopeFixtures(ec)
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
    }

    @Test
    void genericServiceRoutesRuleSetCompareScopeAndWritesUnifiedOutput() {
        Map<String, Object> result = ec.service.sync()
                .name("reconciliation.ReconciliationGenericServices.reconcile#GenericFiles")
                .parameters([
                        ruleSetId    : "DARPAN_TEST_PRODUCT_COMPARE_RS",
                        file1Name    : "products-1.json",
                        file1Text    : readFixtureText("data/test/test-products-1.json"),
                        file2Name    : "products-2.json",
                        file2Text    : readFixtureText("data/test/test-products-2.json"),
                        sparkMaster  : "local[1]",
                        sparkAppName : "GenericReconciliationServiceSmokeTests"
                ])
                .disableAuthz()
                .call()

        assertEquals("JSON", result.reconciliationType)
        assertEquals(4L, result.differenceCount)
        assertEquals(1L, result.onlyInFile1Count)
        assertEquals(1L, result.onlyInFile2Count)
        assertTrue(((List) result.validationErrors).isEmpty())
        assertTrue(((List) result.processingWarnings).isEmpty())
        assertFalse(ec.message.hasError())
        assertNotNull(result.diffLocation)
        assertNotNull(result.diffFileName)
        assertNotNull(result.reconciliationRunResultId)
        assertRunArtifacts(result, "DARPAN_TEST_PRODUCT_COMPARE_RS", ".json", ".json")
        assertRunResultManifest(result, [
                savedRunId     : "DARPAN_TEST_PRODUCT_COMPARE_RS",
                savedRunType   : "ruleset",
                ruleSetId      : "DARPAN_TEST_PRODUCT_COMPARE_RS",
                compareScopeId : "DARPAN_TEST_PRODUCT_JSON_SCOPE",
                file1Name      : "products-1.json",
                file2Name      : "products-2.json",
        ])
        assertGeneratedOutputListIncludesManifest(result, "DARPAN_TEST_PRODUCT_COMPARE_RS")

        Map<String, Object> diffDocument = parseOutputFile(result.diffLocation as String)
        assertEquals("DARPAN_TEST_PRODUCT_COMPARE_RS", diffDocument.metadata.ruleSetId)
        assertEquals("DARPAN_TEST_PRODUCT_JSON_SCOPE", diffDocument.metadata.compareScopeId)
        assertEquals("Smoke-test compare scope for product field mismatches.", diffDocument.metadata.compareScopeDescription)
        assertEquals("PRODUCT", diffDocument.metadata.objectType)
        assertEquals("JSON", diffDocument.metadata.reconciliation)
        assertEquals(4, diffDocument.summary.totalDifferences)
        assertEquals(1, diffDocument.summary.onlyInFile1Count)
        assertEquals(1, diffDocument.summary.onlyInFile2Count)
        assertEquals(2, diffDocument.summary.missingObjectDifferenceCount)
        assertEquals(2, diffDocument.summary.ruleDifferenceCount)
        assertTrue(((List) diffDocument.validationErrors).isEmpty())
        assertTrue(((List) diffDocument.processingWarnings).isEmpty())

        List<Map<String, Object>> differences = ((List) diffDocument.differences).collect { it as Map<String, Object> }
        assertEquals(4, differences.size())
        assertTrue(differences.any { Map<String, Object> row ->
            row.diffType == "MISSING_IN_FILE_2" && row.primaryId == "P300"
        })
        assertTrue(differences.any { Map<String, Object> row ->
            row.diffType == "MISSING_IN_FILE_1" && row.primaryId == "P500"
        })
        assertTrue(differences.any { Map<String, Object> row ->
            row.diffType == "FIELD_MISMATCH" &&
                    row.primaryId == "P200" &&
                    row.field == "sku" &&
                    row.file1Value == "SKU-B" &&
                    row.file2Value == "SKU-B-ALT"
        })
        assertTrue(differences.any { Map<String, Object> row ->
            row.diffType == "FIELD_MISMATCH" &&
                    row.primaryId == "P400" &&
                    row.field == "price" &&
                    row.file1Value == "49.99" &&
                    row.file2Value == "59.99"
        })
    }

    @Test
    void genericServiceReconcilesSchemaBackedMappingBridgePath() {
        Map<String, Object> result = ec.service.sync()
                .name("reconciliation.ReconciliationGenericServices.reconcile#GenericFiles")
                .parameters([
                        reconciliationMappingId: "OrderIdSchemaMap",
                        file1SystemEnumId      : "SHOPIFY",
                        file2SystemEnumId      : "OMS",
                        file1Name              : "orders-1.csv",
                        file1Text              : "order_id\nA100\nA200\nA300\n",
                        file2Name              : "orders-2.csv",
                        file2Text              : "order_id\nA200\nA300\nA400\n",
                        hasHeader              : true,
                        sparkMaster            : "local[1]",
                        sparkAppName           : "GenericReconciliationServiceSmokeTests"
                ])
                .disableAuthz()
                .call()

        assertEquals("CSV", result.reconciliationType)
        assertEquals(2L, result.differenceCount)
        assertEquals(1L, result.onlyInFile1Count)
        assertEquals(1L, result.onlyInFile2Count)
        assertTrue(((List) result.validationErrors).isEmpty())
        assertTrue(((List) result.processingWarnings).isEmpty())
        assertFalse(ec.message.hasError())
        assertNotNull(result.diffLocation)
        assertNotNull(result.diffFileName)
        assertNotNull(result.reconciliationRunResultId)
        assertRunArtifacts(result, "OrderIdSchemaMap", ".csv", ".csv")
        assertRunResultManifest(result, [
                savedRunId             : "OrderIdSchemaMap",
                savedRunType           : "mapping",
                reconciliationMappingId: "OrderIdSchemaMap",
                file1Name              : "orders-1.csv",
                file2Name              : "orders-2.csv",
        ])
        assertGeneratedOutputListIncludesManifest(result, "OrderIdSchemaMap")

        Map<String, Object> diffDocument = parseOutputFile(result.diffLocation as String)
        assertEquals("OrderIdSchemaMap", diffDocument.metadata.reconciliationMappingId)
        assertEquals("CSV", diffDocument.metadata.reconciliation)
        assertEquals(2, diffDocument.summary.totalDifferences)
        assertEquals(1, diffDocument.summary.onlyInFile1Count)
        assertEquals(1, diffDocument.summary.onlyInFile2Count)

        List<Map<String, Object>> differences = ((List) diffDocument.differences).collect { it as Map<String, Object> }
        assertEquals(2, differences.size())
        assertTrue(differences.any { Map<String, Object> row -> row.id == "A100" })
        assertTrue(differences.any { Map<String, Object> row -> row.id == "A400" })
    }

    @Test
    void purgeGeneratedOutputFilesAllowsScheduledInvocationWithoutLoggedInUser() {
        String baseOutputLocation = "runtime://tmp/reconciliation/purge-smoke"
        ec.user.logoutUser()
        ec.message.clearErrors()

        try {
            String scopedOutputLocation = TenantAccessSupport.resolveScopedRuntimeLocation(ec, baseOutputLocation)
            File outputDirectory = ec.resource.getLocationReference(scopedOutputLocation).getFile()
            assertNotNull(outputDirectory)
            outputDirectory.mkdirs()

            File oldOutput = new File(outputDirectory, "old-result.json")
            oldOutput.text = "{}"
            oldOutput.setLastModified(System.currentTimeMillis() - 16L * 24L * 60L * 60L * 1000L)

            Map<String, Object> result = ec.service.sync()
                    .name("reconciliation.ReconciliationGenericServices.purge#GeneratedOutputFiles")
                    .parameters([
                            retentionDays : 15,
                            outputLocation: baseOutputLocation
                    ])
                    .call()

            assertFalse(ec.message.hasError())
            assertEquals(1, result.scannedCount)
            assertEquals(1, result.deletedCount)
            assertFalse(oldOutput.exists())
        } finally {
            ReconciliationSmokeTestSupport.seedCompanyScope(ec)
            ec.message.clearErrors()
        }
    }

    @Test
    void purgeGeneratedOutputFilesDefaultsToDataManagerRunResultsOnly() {
        ec.user.logoutUser()
        ec.message.clearErrors()

        try {
            String runLocation = DataManagerSupport.resolveReconciliationRunLocation(ec, "PurgeRun", "20260430-010000000")
            File runDirectory = ec.resource.getLocationReference(runLocation).getFile()
            assertNotNull(runDirectory)
            runDirectory.mkdirs()

            File oldResult = new File(runDirectory, "PurgeRun_result.json")
            oldResult.text = "{}"
            oldResult.setLastModified(System.currentTimeMillis() - 16L * 24L * 60L * 60L * 1000L)

            File oldSource = new File(runDirectory, "PurgeRun_file1.csv")
            oldSource.text = "id\nA100\n"
            oldSource.setLastModified(System.currentTimeMillis() - 16L * 24L * 60L * 60L * 1000L)

            Map<String, Object> result = ec.service.sync()
                    .name("reconciliation.ReconciliationGenericServices.purge#GeneratedOutputFiles")
                    .parameters([retentionDays: 15])
                    .call()

            assertFalse(ec.message.hasError())
            assertTrue((result.scannedCount as Integer) >= 1)
            assertTrue((result.deletedCount as Integer) >= 1)
            assertFalse(oldResult.exists())
            assertTrue(oldSource.exists())
            assertEquals(DataManagerSupport.resolveReconciliationRunsLocation(ec), result.outputLocation)
        } finally {
            ReconciliationSmokeTestSupport.seedCompanyScope(ec)
            ec.message.clearErrors()
        }
    }

    private String readFixtureText(String relativePath) {
        return Files.readString(backendRoot.resolve("runtime/component/darpan").resolve(relativePath))
    }

    private static void assertRunArtifacts(Map<String, Object> result, String runId, String file1Extension, String file2Extension) {
        String diffLocation = result.diffLocation as String
        String diffFileName = result.diffFileName as String
        File outputFile = new File(diffLocation)
        File runDirectory = outputFile.parentFile

        assertTrue(diffLocation.contains("${File.separator}reconciliation-runs${File.separator}${runId}${File.separator}"))
        assertEquals("${runId}_result.json".toString(), outputFile.name)
        assertTrue(diffFileName.startsWith("reconciliation-runs/${runId}/"))
        assertTrue(diffFileName.endsWith("/${runId}_result.json"))
        assertTrue(new File(runDirectory, "${runId}_file1${file1Extension}").exists())
        assertTrue(new File(runDirectory, "${runId}_file2${file2Extension}").exists())
    }

    private void assertRunResultManifest(Map<String, Object> result, Map<String, Object> expected) {
        def runResult = ec.entity.find("darpan.reconciliation.ReconciliationRunResult")
                .condition("reconciliationRunResultId", result.reconciliationRunResultId as String)
                .disableAuthz()
                .useCache(false)
                .one()

        assertNotNull(runResult)
        assertEquals(expected.savedRunId, runResult.savedRunId)
        assertEquals(expected.savedRunType, runResult.savedRunType)
        assertEquals(expected.reconciliationMappingId, runResult.reconciliationMappingId)
        assertEquals(expected.ruleSetId, runResult.ruleSetId)
        assertEquals(expected.compareScopeId, runResult.compareScopeId)
        assertEquals("KREWE", runResult.companyUserGroupId)
        assertEquals("TEST_CUSTOMER_USER", runResult.createdByUserId)
        assertEquals(expected.file1Name, runResult.file1Name)
        assertEquals(expected.file2Name, runResult.file2Name)
        assertEquals(result.diffFileName, runResult.resultDataManagerPath)
        assertTrue((runResult.file1DataManagerPath as String).startsWith("reconciliation-runs/${expected.savedRunId}/"))
        assertTrue((runResult.file2DataManagerPath as String).startsWith("reconciliation-runs/${expected.savedRunId}/"))
        assertEquals(((Number) result.differenceCount).longValue(), ((Number) runResult.differenceCount).longValue())
        assertEquals(((Number) result.onlyInFile1Count).longValue(), ((Number) runResult.onlyInFile1Count).longValue())
        assertEquals(((Number) result.onlyInFile2Count).longValue(), ((Number) runResult.onlyInFile2Count).longValue())
    }

    private void assertGeneratedOutputListIncludesManifest(Map<String, Object> result, String savedRunId) {
        ec.message.clearErrors()
        Map<String, Object> listResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.list#GeneratedOutputs")
                .parameters([savedRunId: savedRunId, pageSize: 20])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError())
        List<Map<String, Object>> rows = (listResult.generatedOutputs ?: []) as List<Map<String, Object>>
        Map<String, Object> row = rows.find { Map<String, Object> item -> item.fileName == result.diffFileName }
        assertNotNull(row)
        assertEquals(result.reconciliationRunResultId, row.reconciliationRunResultId)
    }

    private static Map<String, Object> parseOutputFile(String diffLocation) {
        File outputFile = new File(diffLocation)
        assertTrue(outputFile.exists())
        return (Map<String, Object>) JSON_SLURPER.parseText(outputFile.getText("UTF-8"))
    }
}
