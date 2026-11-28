package darpan.reconciliation.generic

import darpan.reconciliation.notification.RunNotificationVoice
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-OPS-029 S02 (OC20, no hollow green): reconcile#GenericFiles hands its caller the rule stage's own counts. It
 * returned neither ruleDifferenceCount nor ruleExecutionFailed, so run#SavedRunDiff read null on the upload path and an
 * upload run whose only differences were value mismatches was classified CLEAN. Not @SharedMoqui: it starts Spark.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GenericFilesRuleCountSmokeTests {
    private ExecutionContext ec

    // The same two products on both sides; only P200's sku differs.
    private static final String FILE_1 = '[{"data":{"products":[{"productId":"P100","sku":"SKU-A","price":"10.00"},{"productId":"P200","sku":"SKU-B","price":"20.00"}]}}]'
    private static final String FILE_2 = '[{"data":{"products":[{"productId":"P100","sku":"SKU-A","price":"10.00"},{"productId":"P200","sku":"SKU-B-ALT","price":"20.00"}]}}]'

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "generic-files-rule-count")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompareScopeFixtures(ec)
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

    private Map<String, Object> reconcile(String ruleSetId) {
        return ec.service.sync()
                .name("reconciliation.ReconciliationGenericServices.reconcile#GenericFiles")
                .parameters([ruleSetId: ruleSetId, file1Name: "products-1.json", file1Text: FILE_1,
                             file2Name: "products-2.json", file2Text: FILE_2, sparkAppName: "GenericFilesRuleCountSmokeTests"])
                .disableAuthz()
                .call()
    }

    @Test
    void aRunWithOnlyValueMismatchesCarriesItsRuleCountAndIsNotClean() {
        Map<String, Object> result = reconcile("DARPAN_TEST_PRODUCT_COMPARE_RS")

        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertEquals(0L, result.onlyInFile1Count as Long)
        assertEquals(0L, result.onlyInFile2Count as Long)
        assertEquals(1L, result.ruleDifferenceCount as Long, "the rule stage's count must reach the caller")
        assertEquals(["sku"], (result.ruleDifferenceFields as List<Map>)*.field)
        assertEquals(false, result.ruleExecutionFailed)
        assertEquals(0L, result.duplicateDifferenceCount as Long)
        Map summary = (Map) ((Map) new groovy.json.JsonSlurper().parse(new File(result.diffLocation as String))).summary
        assertEquals(1, summary.ruleDifferenceCount)
        assertEquals(0, summary.duplicateDifferenceCount, "the document summary carries the duplicate count")
        Map verdict = RunNotificationVoice.classify([onlyInFile1Count: result.onlyInFile1Count,
                onlyInFile2Count: result.onlyInFile2Count, ruleDifferenceCount: result.ruleDifferenceCount])
        assertNotEquals(RunNotificationVoice.BUCKET_CLEAN, verdict.bucket, "a value mismatch is not a clean run")
    }

    @Test
    void aRuleSetThatDoesNotCompileSaysSo() {
        Map<String, Object> result = reconcile("DARPAN_TEST_PRODUCT_BROKEN_RS")
        assertEquals(true, result.ruleExecutionFailed, "a rule stage that did not run must not read as zero differences")
        assertTrue(!((result.ruleDifferenceFields ?: []) as List))
    }
}
