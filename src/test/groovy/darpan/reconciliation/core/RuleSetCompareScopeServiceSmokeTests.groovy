package darpan.reconciliation.core

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonSlurper
import org.apache.spark.sql.Dataset
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertIterableEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RuleSetCompareScopeServiceSmokeTests {
    private static final JsonSlurper JSON_SLURPER = new JsonSlurper()

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "ruleset-compare-scope-smoke")
        ReconciliationSmokeTestSupport.seedCompareScopeFixtures(ec)
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearErrors() {
        ec.message.clearErrors()
    }

    @Test
    void prepareCompareScopeReturnsNormalizedDatasetsForCallerPipeline() {
        Map<String, Object> prepared = ec.service.sync()
                .name("reconciliation.ReconciliationCoreServices.prepare#RuleSetCompareScope")
                .parameters([
                        ruleSetId    : "DARPAN_TEST_COMPARE_RS",
                        compareScopeId: "DARPAN_TEST_ORDER_JSON_SCOPE",
                        file1Location: "component://darpan/data/test/test-orders-1.json",
                        file2Location: "component://darpan/data/test/test-orders-2.json",
                        sparkMaster  : "local[1]",
                        sparkAppName : "RuleSetCompareScopeServiceSmokeTests"
                ])
                .disableAuthz()
                .call()

        assertEquals("DARPAN_TEST_COMPARE_RS", prepared.ruleSetId)
        assertEquals("DARPAN_TEST_ORDER_JSON_SCOPE", prepared.compareScopeId)
        assertEquals("Smoke-test compare scope for nested JSON order IDs.", prepared.compareScopeDescription)
        assertEquals("ORDER", prepared.objectType)
        assertEquals("JSON", prepared.file1Type)
        assertEquals("JSON", prepared.file2Type)
        assertEquals('$.data.orders.edges[*].node.id', prepared.file1IdExpression)
        assertEquals('$.data.orders.edges[*].node.id', prepared.file2IdExpression)
        assertEquals("SHOPIFY_GID_TAIL", prepared.file1IdNormalizer)
        assertEquals("TRAILING_DIGITS", prepared.file2IdNormalizer)
        assertEquals("SHOPIFY", prepared.file1Label)
        assertEquals("OMS", prepared.file2Label)
        assertTrue(((List) prepared.validationErrors).isEmpty())
        assertTrue(((List) prepared.processingWarnings).isEmpty())
        assertFalse(ec.message.hasError())

        Dataset file1IdDf = (Dataset) prepared.file1IdDf
        Dataset file2IdDf = (Dataset) prepared.file2IdDf
        Dataset file1DataDf = (Dataset) prepared.file1DataDf
        Dataset file2DataDf = (Dataset) prepared.file2DataDf

        assertEquals(3L, file1IdDf.count())
        assertEquals(3L, file2IdDf.count())
        assertEquals(3L, file1DataDf.count())
        assertEquals(3L, file2DataDf.count())
        assertIterableEquals(
                ["6470622019715", "6470622478467", "6470624575619"],
                collectCompareIds(file1IdDf)
        )
        assertIterableEquals(
                ["6470622478467", "6470624804995", "6470625460355"],
                collectCompareIds(file2IdDf)
        )

        Map<String, Object> reconciliation = ec.service.sync()
                .name("reconciliation.ReconciliationCoreServices.reconcile#IdDataFrames")
                .parameters([
                        df1       : file1IdDf,
                        df2       : file2IdDf,
                        idColumnName: "compare_id",
                        df1Label  : prepared.file1Label,
                        df2Label  : prepared.file2Label
                ])
                .disableAuthz()
                .call()

        assertEquals(2L, reconciliation.onlyInDf1Count)
        assertEquals(2L, reconciliation.onlyInDf2Count)
        assertEquals(4L, reconciliation.differenceCount)
        assertIterableEquals(
                ["6470622019715", "6470624575619"],
                collectCompareIds((Dataset) reconciliation.onlyInDf1)
        )
        assertIterableEquals(
                ["6470624804995", "6470625460355"],
                collectCompareIds((Dataset) reconciliation.onlyInDf2)
        )
    }

    @Test
    void baseDiffStageReturnsMissingDiffsAndMatchedPairsWithCorrectDirectionality() {
        Map<String, Object> result = ec.service.sync()
                .name("reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScopeBaseDiff")
                .parameters([
                        ruleSetId      : "DARPAN_TEST_COMPARE_RS",
                        compareScopeId : "DARPAN_TEST_ORDER_JSON_SCOPE",
                        file1Location  : "component://darpan/data/test/test-orders-1.json",
                        file2Location  : "component://darpan/data/test/test-orders-2.json",
                        sparkMaster    : "local[1]",
                        sparkAppName   : "RuleSetCompareScopeServiceSmokeTests"
                ])
                .disableAuthz()
                .call()

        assertEquals("DARPAN_TEST_ORDER_JSON_SCOPE", result.compareScopeId)
        assertEquals("ORDER", result.objectType)
        assertEquals(2L, result.missingInFile1Count)
        assertEquals(2L, result.missingInFile2Count)
        assertEquals(4L, result.differenceCount)
        assertEquals(1L, result.matchedPairCount)
        assertTrue(((List) result.validationErrors).isEmpty())
        assertTrue(((List) result.processingWarnings).isEmpty())
        assertFalse(ec.message.hasError())

        List<Map<String, Object>> missingDiffs = collectRows((Dataset) result.missingDiffDf)
        assertEquals(4, missingDiffs.size())
        assertIterableEquals(
                ["6470622019715", "6470624575619", "6470624804995", "6470625460355"],
                missingDiffs.collect { Map<String, Object> row -> row.id?.toString() }.sort()
        )

        Map<String, Object> missingInFile1 = missingDiffs.find { Map<String, Object> row ->
            row.type == "MISSING_IN_FILE_1" && row.id == "6470624804995"
        }
        assertEquals("OMS", missingInFile1.presentIn)
        assertEquals("SHOPIFY", missingInFile1.missingIn)
        assertTrue((missingInFile1.note as String).contains("missing in SHOPIFY"))
        assertTrue((missingInFile1.data as String).contains("6470624804995"))

        Map<String, Object> missingInFile2 = missingDiffs.find { Map<String, Object> row ->
            row.type == "MISSING_IN_FILE_2" && row.id == "6470622019715"
        }
        assertEquals("SHOPIFY", missingInFile2.presentIn)
        assertEquals("OMS", missingInFile2.missingIn)
        assertTrue((missingInFile2.note as String).contains("missing in OMS"))
        assertTrue((missingInFile2.data as String).contains("6470622019715"))

        List<Map<String, Object>> matchedPairs = collectRows((Dataset) result.matchedPairDf)
        assertEquals(1, matchedPairs.size())
        assertEquals("DARPAN_TEST_ORDER_JSON_SCOPE", matchedPairs[0].compareScopeId)
        assertEquals("ORDER", matchedPairs[0].objectType)
        assertEquals("6470622478467", matchedPairs[0].primaryId)
        assertEquals("gid://shopify/Order/6470622478467", matchedPairs[0].file1.node.id)
        assertEquals("gid://shopify/Order/6470622478467", matchedPairs[0].file2.node.id)
    }

    @Test
    void baseDiffStageReturnsEmptyMissingDiffDatasetWhenNoObjectsAreMissing() {
        Map<String, Object> result = ec.service.sync()
                .name("reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScopeBaseDiff")
                .parameters([
                        ruleSetId      : "DARPAN_TEST_COMPARE_RS",
                        compareScopeId : "DARPAN_TEST_ORDER_JSON_SCOPE",
                        file1Location  : "component://darpan/data/test/test-orders-1.json",
                        file2Location  : "component://darpan/data/test/test-orders-1.json",
                        sparkMaster    : "local[1]",
                        sparkAppName   : "RuleSetCompareScopeServiceSmokeTests"
                ])
                .disableAuthz()
                .call()

        assertEquals("DARPAN_TEST_ORDER_JSON_SCOPE", result.compareScopeId)
        assertEquals(0L, result.missingInFile1Count)
        assertEquals(0L, result.missingInFile2Count)
        assertEquals(0L, result.differenceCount)
        assertEquals(3L, result.matchedPairCount)
        assertTrue(((List) result.validationErrors).isEmpty())
        assertTrue(((List) result.processingWarnings).isEmpty())
        assertFalse(ec.message.hasError())
        assertTrue(result.missingDiffDf instanceof Dataset)
        assertEquals(0L, ((Dataset) result.missingDiffDf).count())
        assertTrue(collectRows((Dataset) result.missingDiffDf).isEmpty())
    }

    @Test
    void baseDiffStageRejectsDuplicatePrimaryIdsOnAFileSide() {
        Map<String, Object> result = ec.service.sync()
                .name("reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScopeBaseDiff")
                .parameters([
                        ruleSetId      : "DARPAN_TEST_COMPARE_RS",
                        compareScopeId : "DARPAN_TEST_ORDER_JSON_SCOPE",
                        file1Location  : "component://darpan/data/test/test-orders-duplicate-file1.json",
                        file2Location  : "component://darpan/data/test/test-orders-2.json",
                        sparkMaster    : "local[1]",
                        sparkAppName   : "RuleSetCompareScopeServiceSmokeTests"
                ])
                .disableAuthz()
                .call()

        assertTrue(result == null || result.isEmpty())
        assertTrue(ec.message.hasError())
        assertTrue(ec.message.errors.any { String message -> message.contains("nested JSON order IDs") })
        assertFalse(ec.message.errors.any { String message -> message.contains("DARPAN_TEST_ORDER_JSON_SCOPE") })
        assertTrue(ec.message.errors.any { String message -> message.contains("FILE_1") })
        assertTrue(ec.message.errors.any { String message -> message.contains("SHOPIFY") })
        assertTrue(ec.message.errors.any { String message -> message.contains("6470622478467") })
        assertTrue(ec.message.errors.any { String message -> message.contains("2 rows") })
        assertTrue(ec.message.errors.any { String message -> message.contains("primaryId must identify exactly one object per file side") })
    }

    @Test
    void prepareCompareScopeReportsHelpfulErrorWhenCsvSourceDoesNotExposeConfiguredIdColumn() {
        Map<String, Object> result = ec.service.sync()
                .name("reconciliation.ReconciliationCoreServices.prepare#RuleSetCompareScope")
                .parameters([
                        ruleSetId      : "DARPAN_TEST_COMPARE_RS",
                        compareScopeId : "DARPAN_TEST_ORDER_CSV_SCOPE",
                        file1Location  : "component://darpan/data/test/test-orders-1.json",
                        file2Location  : "component://darpan/data/test/test-orders-2.json",
                        sparkMaster    : "local[1]",
                        sparkAppName   : "RuleSetCompareScopeServiceSmokeTests"
                ])
                .disableAuthz()
                .call()

        assertTrue(result == null || result.isEmpty())
        assertTrue(ec.message.hasError())
        assertTrue(ec.message.errors.any { String message -> message.contains("expects CSV data") })
        assertTrue(ec.message.errors.any { String message -> message.contains("test-orders-1.json") })
        assertTrue(ec.message.errors.any { String message -> message.contains("looks like JSON") })
    }

    @Test
    void fullRuleSetCompareScopeServiceAppendsRuleDiffsToMissingObjectDiffs() {
        Map<String, Object> result = ec.service.sync()
                .name("reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope")
                .parameters([
                        ruleSetId      : "DARPAN_TEST_PRODUCT_COMPARE_RS",
                        compareScopeId : "DARPAN_TEST_PRODUCT_JSON_SCOPE",
                        file1Location  : "component://darpan/data/test/test-products-1.json",
                        file2Location  : "component://darpan/data/test/test-products-2.json",
                        sparkMaster    : "local[1]",
                        sparkAppName   : "RuleSetCompareScopeServiceSmokeTests",
                        ruleBatchSize  : 2
                ])
                .disableAuthz()
                .call()

        assertEquals("DARPAN_TEST_PRODUCT_COMPARE_RS", result.ruleSetId)
        assertEquals("DARPAN_TEST_PRODUCT_JSON_SCOPE", result.compareScopeId)
        assertEquals("PRODUCT", result.objectType)
        assertEquals(1L, result.missingInFile1Count)
        assertEquals(1L, result.missingInFile2Count)
        assertEquals(2L, result.missingObjectDifferenceCount)
        assertEquals(2L, result.ruleDifferenceCount)
        assertEquals(4L, result.differenceCount)
        assertEquals(3L, result.matchedPairCount)
        assertEquals(2, result.ruleCount)
        assertEquals(2, result.firedRuleCount)
        assertTrue(((List) result.validationErrors).isEmpty())
        assertTrue(((List) result.processingWarnings).isEmpty())
        assertFalse(ec.message.hasError())

        List<Map<String, Object>> diffs = collectRows((Dataset) result.diffDf)
        assertEquals(4, diffs.size())

        Map<String, Object> missingInFile1 = diffs.find { Map<String, Object> row ->
            row.diffType == "MISSING_IN_FILE_1" && row.primaryId == "P500"
        }
        assertTrue(missingInFile1 != null)
        assertEquals("DARPAN_TEST_PRODUCT_JSON_SCOPE", missingInFile1.compareScopeId)
        assertEquals("PRODUCT", missingInFile1.objectType)
        assertEquals("OMS", missingInFile1.presentIn)
        assertEquals("SHOPIFY", missingInFile1.missingIn)
        assertTrue((missingInFile1.data as String).contains("\"productId\":\"P500\""))

        Map<String, Object> missingInFile2 = diffs.find { Map<String, Object> row ->
            row.diffType == "MISSING_IN_FILE_2" && row.primaryId == "P300"
        }
        assertTrue(missingInFile2 != null)
        assertEquals("SHOPIFY", missingInFile2.presentIn)
        assertEquals("OMS", missingInFile2.missingIn)
        assertTrue((missingInFile2.data as String).contains("\"productId\":\"P300\""))

        Map<String, Object> skuDiff = diffs.find { Map<String, Object> row ->
            row.diffType == "FIELD_MISMATCH" && row.primaryId == "P200" && row.field == "sku"
        }
        assertTrue(skuDiff != null)
        assertEquals("DARPAN_TEST_PRODUCT_SKU_MISMATCH", skuDiff.ruleId)
        assertEquals("SKU-B", skuDiff.file1Value)
        assertEquals("SKU-B-ALT", skuDiff.file2Value)
        assertEquals("WARN", skuDiff.severity)
        assertEquals("SKU mismatch", skuDiff.message)

        Map<String, Object> priceDiff = diffs.find { Map<String, Object> row ->
            row.diffType == "FIELD_MISMATCH" && row.primaryId == "P400" && row.field == "price"
        }
        assertTrue(priceDiff != null)
        assertEquals("DARPAN_TEST_PRODUCT_PRICE_MISMATCH", priceDiff.ruleId)
        assertEquals("49.99", priceDiff.file1Value)
        assertEquals("59.99", priceDiff.file2Value)
        assertEquals("WARN", priceDiff.severity)
        assertEquals("Price mismatch", priceDiff.message)
    }

    @Test
    void fullRuleSetCompareScopeServicePreservesBaseDiffsWhenRuleExecutionFails() {
        Map<String, Object> result = ec.service.sync()
                .name("reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope")
                .parameters([
                        ruleSetId      : "DARPAN_TEST_PRODUCT_BROKEN_RS",
                        compareScopeId : "DARPAN_TEST_PRODUCT_BROKEN_JSON_SCOPE",
                        file1Location  : "component://darpan/data/test/test-products-1.json",
                        file2Location  : "component://darpan/data/test/test-products-2.json",
                        sparkMaster    : "local[1]",
                        sparkAppName   : "RuleSetCompareScopeServiceSmokeTests",
                        ruleBatchSize  : 2
                ])
                .disableAuthz()
                .call()

        assertEquals("DARPAN_TEST_PRODUCT_BROKEN_RS", result.ruleSetId)
        assertEquals("DARPAN_TEST_PRODUCT_BROKEN_JSON_SCOPE", result.compareScopeId)
        assertEquals(2L, result.missingObjectDifferenceCount)
        assertEquals(0L, result.ruleDifferenceCount)
        assertEquals(2L, result.differenceCount)
        assertEquals(3L, result.matchedPairCount)
        assertEquals(0, result.firedRuleCount)
        assertEquals(1, result.ruleCount)
        assertTrue(((List) result.validationErrors).isEmpty())
        assertFalse(ec.message.hasError())

        List<String> warnings = ((List) result.processingWarnings).collect { Object value -> value?.toString() }
        assertTrue(warnings.any { String message -> message.contains("DARPAN_TEST_PRODUCT_BROKEN_RS") })
        assertTrue(warnings.any { String message -> message.contains("broken DRL preservation behavior") })
        assertFalse(warnings.any { String message -> message.contains("DARPAN_TEST_PRODUCT_BROKEN_JSON_SCOPE") })
        assertTrue(warnings.any { String message -> message.contains("preserved base missing-object diffs") })

        List<Map<String, Object>> diffs = collectRows((Dataset) result.diffDf)
        assertEquals(2, diffs.size())
        assertTrue(diffs.every { Map<String, Object> row ->
            row.diffType in ["MISSING_IN_FILE_1", "MISSING_IN_FILE_2"]
        })
    }

    @Test
    void fullRuleSetCompareScopeServiceSkipsRuleExecutionCleanlyWhenNoRulesAreActive() {
        Map<String, Object> result = ec.service.sync()
                .name("reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope")
                .parameters([
                        ruleSetId      : "DARPAN_TEST_COMPARE_RS",
                        compareScopeId : "DARPAN_TEST_ORDER_JSON_SCOPE",
                        file1Location  : "component://darpan/data/test/test-orders-1.json",
                        file2Location  : "component://darpan/data/test/test-orders-2.json",
                        sparkMaster    : "local[1]",
                        sparkAppName   : "RuleSetCompareScopeServiceSmokeTests",
                ])
                .disableAuthz()
                .call()

        assertEquals("DARPAN_TEST_COMPARE_RS", result.ruleSetId)
        assertEquals("DARPAN_TEST_ORDER_JSON_SCOPE", result.compareScopeId)
        assertEquals(4L, result.differenceCount)
        assertEquals(4L, result.missingObjectDifferenceCount)
        assertEquals(0L, result.ruleDifferenceCount)
        assertEquals(0, result.ruleCount)
        assertEquals(0, result.firedRuleCount)
        assertTrue(((List) result.validationErrors).isEmpty())
        assertTrue(((List) result.processingWarnings).isEmpty())
        assertFalse(ec.message.hasError())
    }

    @Test
    void fullRuleSetCompareScopeServiceCollapsesDuplicatePrimaryIdsForBaseDiffOnlyRuns() {
        Map<String, Object> result = ec.service.sync()
                .name("reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope")
                .parameters([
                        ruleSetId      : "DARPAN_TEST_COMPARE_RS",
                        compareScopeId : "DARPAN_TEST_ORDER_JSON_SCOPE",
                        file1Location  : "component://darpan/data/test/test-orders-duplicate-file1.json",
                        file2Location  : "component://darpan/data/test/test-orders-2.json",
                        sparkMaster    : "local[1]",
                        sparkAppName   : "RuleSetCompareScopeServiceSmokeTests",
                ])
                .disableAuthz()
                .call()

        assertEquals("DARPAN_TEST_COMPARE_RS", result.ruleSetId)
        assertEquals("DARPAN_TEST_ORDER_JSON_SCOPE", result.compareScopeId)
        assertEquals(3L, result.differenceCount)
        assertEquals(3L, result.missingObjectDifferenceCount)
        assertEquals(0L, result.ruleDifferenceCount)
        assertEquals(1L, result.matchedPairCount)
        assertEquals(0, result.ruleCount)
        assertEquals(0, result.firedRuleCount)
        assertTrue(result.matchedPairDf == null)
        assertTrue(((List) result.validationErrors).isEmpty())
        assertFalse(ec.message.hasError())

        List<String> warnings = ((List) result.processingWarnings).collect { Object value -> value?.toString() }
        assertEquals(1, warnings.size())
        assertTrue(warnings.any { String message -> message.contains("collapsed duplicate primaryId values for base diff only") })
        assertTrue(warnings.any { String message -> message.contains("nested JSON order IDs") })
        assertFalse(warnings.any { String message -> message.contains("DARPAN_TEST_ORDER_JSON_SCOPE") })
        assertTrue(warnings.any { String message -> message.contains("FILE_1") })
        assertTrue(warnings.any { String message -> message.contains("SHOPIFY") })
        assertTrue(warnings.any { String message -> message.contains("6470622478467") })

        List<Map<String, Object>> diffs = collectRows((Dataset) result.diffDf)
        assertEquals(3, diffs.size())
        assertIterableEquals(
                ["6470622019715", "6470624804995", "6470625460355"],
                diffs.collect { Map<String, Object> row -> row.primaryId?.toString() }.sort()
        )
    }

    private static List<String> collectCompareIds(Dataset dataset) {
        return dataset.collectAsList()
                .collect { row -> row.getAs("compare_id")?.toString() }
                .findAll { String value -> value != null }
                .sort()
    }

    private static List<Map<String, Object>> collectRows(Dataset dataset) {
        return dataset.toJSON().collectAsList()
                .collect { String rowJson -> (Map<String, Object>) JSON_SLURPER.parseText(rowJson) }
    }
}
