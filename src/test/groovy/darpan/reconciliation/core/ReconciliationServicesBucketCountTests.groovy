package darpan.reconciliation.core

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Row
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.moqui.context.ExecutionContext

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-OPS-029 S01a, invariant E1 (conservation) on reconcile#RuleSetCompareScope. The one rule: a duplicate key is
 * surfaced, never collapsed into a clean match, and every difference row sits in exactly one bucket. Each summary
 * count is the number of rows of its own bucket, and the buckets are disjoint:
 *   missingObjectDifferenceCount = rows typed MISSING_IN_FILE_1/2
 *   duplicateDifferenceCount     = rows typed DUPLICATE_IN_FILE_1/2 (one per record under a repeated key)
 *   ruleDifferenceCount          = every other row (what the rule engine emitted from matched pairs)
 *   differenceCount              = the sum = every row of diffDf
 * A run with rule differences AND a repeated key is the case where a reader that files duplicate rows under "rule"
 * disagrees with ruleDifferenceCount (U02's lead): the compare keeps them apart, so the reader must too.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReconciliationServicesBucketCountTests {

    private ExecutionContext ec

    @TempDir
    File dir

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "s01a-bucket-counts")
        ReconciliationSmokeTestSupport.seedCompareScopeFixtures(ec)
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    private static String products(List<List<String>> rows) {
        String items = rows.collect { List<String> r -> """{"productId":"${r[0]}","sku":"${r[1]}","price":"${r[2]}"}""" }.join(",")
        return """[{"data":{"products":[${items}]}}]"""
    }

    @Test
    void everyDifferenceRowSitsInExactlyOneBucketAndEachCountIsItsBucket() {
        // FILE_1 is test-products-1.json with P100 on two records; FILE_2 is the shipped test-products-2.json.
        File file1 = new File(dir, "products-dup.json")
        file1.setText(products([["P100", "SKU-A", "10.00"], ["P100", "SKU-A", "10.00"], ["P200", "SKU-B", "20.00"],
                                ["P300", "SKU-C", "30.00"], ["P400", "SKU-D", "49.99"]]), "UTF-8")
        ec.message.clearErrors()
        Map<String, Object> result = ec.service.sync()
                .name("reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope")
                .parameters([ruleSetId    : "DARPAN_TEST_PRODUCT_COMPARE_RS", compareScopeId: "DARPAN_TEST_PRODUCT_JSON_SCOPE",
                             file1Location: file1.absolutePath, file2Location: "component://darpan/data/test/test-products-2.json",
                             sparkMaster  : "local[1]", sparkAppName: "ReconciliationServicesBucketCountTests"])
                .disableAuthz()
                .call()
        try {
            assertFalse(ec.message.hasError(), ec.message.errorsString)
            List<String> types = ((Dataset) result.diffDf).select("diffType").collectAsList().collect { Row r -> r.getString(0) }
            int missing = types.count { it in ["MISSING_IN_FILE_1", "MISSING_IN_FILE_2"] } as int
            int duplicates = types.count { it?.startsWith("DUPLICATE_IN_FILE_") } as int
            int rule = types.size() - missing - duplicates

            assertEquals(3, duplicates, "both P100 records and the other side's P100 (its partner row, S01b OC14) are findings: ${types}")
            assertTrue(rule > 0, "the run has rule differences too: ${types}")
            assertEquals((long) missing, result.missingObjectDifferenceCount)
            assertEquals((long) duplicates, result.duplicateDifferenceCount)
            assertEquals((long) rule, result.ruleDifferenceCount, "ruleDifferenceCount holds no duplicate row")
            assertEquals((long) types.size(), result.differenceCount, "differenceCount is every row, each once")
            assertEquals((long) missing, (long) result.missingInFile1Count + (long) result.missingInFile2Count)

            // Records, per side: matched + missing from the other side + its rows under a repeated key. S01b OC14: FILE_2's
            // one P100 record is its partner row (DUPLICATE_IN_FILE_2_PAIRED), so every record of both sides is in a bucket.
            long matched = (long) result.matchedPairCount
            long underRepeated1 = types.count { it?.startsWith("DUPLICATE_IN_FILE_1") } as long
            long underRepeated2 = types.count { it?.startsWith("DUPLICATE_IN_FILE_2") } as long
            assertEquals(5L, matched + (long) result.missingInFile2Count + underRepeated1)
            assertEquals(4L, matched + (long) result.missingInFile1Count + underRepeated2)
        } finally {
            ReconciliationServices.unpersistDatasets((List) result.persistedSources)
        }
    }
}
