package darpan.reconciliation.generic

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
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "generic-reconciliation-smoke")
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
        ReconciliationSmokeTestSupport.seedPilotCompanyScope(ec)
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

        Map<String, Object> diffDocument = parseOutputFile(result.diffLocation as String)
        assertEquals("DARPAN_TEST_PRODUCT_COMPARE_RS", diffDocument.metadata.ruleSetId)
        assertEquals("DARPAN_TEST_PRODUCT_JSON_SCOPE", diffDocument.metadata.compareScopeId)
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

    private String readFixtureText(String relativePath) {
        return Files.readString(backendRoot.resolve("runtime/component/darpan").resolve(relativePath))
    }

    private static Map<String, Object> parseOutputFile(String diffLocation) {
        File outputFile = new File(diffLocation)
        assertTrue(outputFile.exists())
        return (Map<String, Object>) JSON_SLURPER.parseText(outputFile.getText("UTF-8"))
    }
}
