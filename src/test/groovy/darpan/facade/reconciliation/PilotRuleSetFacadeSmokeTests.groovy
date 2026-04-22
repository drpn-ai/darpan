package darpan.facade.reconciliation

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
class PilotRuleSetFacadeSmokeTests {
    private ExecutionContext ec
    private Path backendRoot

    @BeforeAll
    void setup() {
        backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "pilot-ruleset-facade-smoke")
        ReconciliationSmokeTestSupport.seedPilotCompanyScope(ec)
        ReconciliationSmokeTestSupport.seedCompareScopeFixtures(ec)
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
    void listPilotRuleSetCompareScopesReturnsPilotRows() {
        Map<String, Object> result = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.list#PilotRuleSetCompareScopes")
                .parameters([
                        pageIndex: 0,
                        pageSize : 20,
                        query    : ""
                ])
                .call()

        assertFalse(ec.message.hasError())
        assertEquals(3, ((List) result.compareScopes).size())

        Map<String, Object> productScope = ((List<Map<String, Object>>) result.compareScopes).find {
            it.compareScopeId == "DARPAN_TEST_PRODUCT_JSON_SCOPE"
        }
        assertNotNull(productScope)
        assertEquals("DARPAN_TEST_PRODUCT_COMPARE_RS", productScope.ruleSetId)
        assertEquals("Darpan Test Product Compare RuleSet", productScope.ruleSetName)
        assertEquals("PRODUCT", productScope.objectType)
        assertEquals("SHOPIFY", productScope.file1SystemEnumId)
        assertEquals("OMS", productScope.file2SystemEnumId)
        assertEquals("productId", productScope.file1PrimaryIdExpression)
        assertEquals("productId", productScope.file2PrimaryIdExpression)
    }

    @Test
    void runPilotGenericDiffRoutesRuleSetFacadeAndListsCompareScopeOutputs() {
        Map<String, Object> runResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.run#PilotGenericDiff")
                .parameters([
                        ruleSetId    : "DARPAN_TEST_PRODUCT_COMPARE_RS",
                        compareScopeId: "DARPAN_TEST_PRODUCT_JSON_SCOPE",
                        file1Name    : "products-1.json",
                        file1Text    : readFixtureText("data/test/test-products-1.json"),
                        file2Name    : "products-2.json",
                        file2Text    : readFixtureText("data/test/test-products-2.json"),
                        sparkMaster  : "local[1]",
                        sparkAppName : "PilotRuleSetFacadeSmokeTests"
                ])
                .call()

        assertFalse(ec.message.hasError())
        assertEquals("ruleset", runResult.runResult.runType)
        assertEquals("DARPAN_TEST_PRODUCT_COMPARE_RS", runResult.runResult.ruleSetId)
        assertEquals("DARPAN_TEST_PRODUCT_JSON_SCOPE", runResult.runResult.compareScopeId)
        assertEquals("PRODUCT", runResult.runResult.objectType)
        assertEquals("SHOPIFY", runResult.runResult.file1SystemEnumId)
        assertEquals("OMS", runResult.runResult.file2SystemEnumId)
        assertEquals(4L, runResult.runResult.generatedOutput.totalDifferences)
        assertEquals(2L, runResult.runResult.generatedOutput.ruleDifferenceCount)
        assertEquals(2L, runResult.runResult.generatedOutput.missingObjectDifferenceCount)

        Map<String, Object> listResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.list#PilotGeneratedOutputs")
                .parameters([
                        ruleSetId    : "DARPAN_TEST_PRODUCT_COMPARE_RS",
                        compareScopeId: "DARPAN_TEST_PRODUCT_JSON_SCOPE",
                        pageIndex    : 0,
                        pageSize     : 10,
                        query        : ""
                ])
                .call()

        assertFalse(ec.message.hasError())
        List<Map<String, Object>> generatedOutputs = (List<Map<String, Object>>) listResult.generatedOutputs
        assertTrue(generatedOutputs.size() >= 1)
        Map<String, Object> latestOutput = generatedOutputs.first()
        assertEquals("ruleset", latestOutput.runType)
        assertEquals("DARPAN_TEST_PRODUCT_COMPARE_RS", latestOutput.ruleSetId)
        assertEquals("DARPAN_TEST_PRODUCT_JSON_SCOPE", latestOutput.compareScopeId)
        assertEquals(2L, latestOutput.ruleDifferenceCount)

        Map<String, Object> outputFileResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.get#PilotGeneratedOutput")
                .parameters([
                        fileName: latestOutput.fileName,
                        format  : "json"
                ])
                .call()

        assertFalse(ec.message.hasError())
        assertTrue(((String) outputFileResult.outputFile.createdDate).contains("T"))
        assertTrue(((String) outputFileResult.outputFile.createdDate).endsWith("Z"))

        Map<String, Object> outputPayload = (Map<String, Object>) new JsonSlurper()
                .parseText((String) outputFileResult.outputFile.contentText)
        String metadataTimestamp = outputPayload?.metadata?.timestamp as String
        assertNotNull(metadataTimestamp)
        assertTrue(metadataTimestamp.contains("T"))
        assertTrue(metadataTimestamp.endsWith("Z"))
    }

    private String readFixtureText(String relativePath) {
        return Files.readString(backendRoot.resolve("runtime/component/darpan").resolve(relativePath))
    }
}
