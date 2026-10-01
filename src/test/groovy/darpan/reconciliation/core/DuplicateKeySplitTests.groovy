package darpan.reconciliation.core

import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Encoders
import org.apache.spark.sql.Row
import org.apache.spark.sql.SparkSession
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertSame

/**
 * DAR-BE-063 Task 12. A side that opts into duplicateKeyHandling=FINDING reports every key that repeats
 * as findings instead of throwing (ruled run) or silently collapsing it into a match (base-diff run).
 * The repeated key leaves the compare on BOTH sides: otherwise the other side's record for it would read
 * as a false "missing". Spark but no Moqui, so this lands in the fast unitTest pool.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DuplicateKeySplitTests {

    private SparkSession spark

    @BeforeAll
    void setup() {
        spark = SparkSession.builder().appName("DuplicateKeySplitTests").master("local[1]")
                .config("spark.ui.enabled", "false").getOrCreate()
    }

    @AfterAll
    void cleanup() { if (spark != null) spark.stop() }

    private Dataset rows(String... jsonLines) {
        return spark.read().json(spark.createDataset(jsonLines.toList(), Encoders.STRING()))
    }

    private static Map ingest(Dataset dataDf) {
        return [dataDf: dataDf, idDf: dataDf.select("compare_id")]
    }

    private static List<String> ids(Dataset df) {
        return df.select("compare_id").collectAsList().collect { Row r -> r.getString(0) }.sort()
    }

    private Dataset alpha() {
        rows('{"compare_id":"K1","data":{"id":"K1"}}',
             '{"compare_id":"K2","data":{"id":"K2"}}',
             '{"compare_id":"K3","data":{"id":"K3"}}')
    }

    private Dataset beta() {
        rows('{"compare_id":"K1","data":{"id":"K1","n":"a"}}',
             '{"compare_id":"K2","data":{"id":"K2","n":"b"}}',
             '{"compare_id":"K2","data":{"id":"K2","n":"c"}}',
             '{"compare_id":"K3","data":{"id":"K3","n":"d"}}')
    }

    @Test
    void duplicateCompareIdsAreTheKeysThatRepeat() {
        assertEquals(["K2"], ids(CompareDatasetSupport.duplicateCompareIds(beta())))
        assertEquals([], ids(CompareDatasetSupport.duplicateCompareIds(alpha())))
    }

    @Test
    void aFindingSideTakesTheRepeatedKeyOutOfBothSides() {
        Map split = RuleSetCompareScopeAdapter.splitDuplicateKeys(
                [FILE_1: ingest(alpha()), FILE_2: ingest(beta())], [FILE_1: null, FILE_2: "FINDING"])
        Map sides = (Map) split.ingestBySide
        assertEquals(["K1", "K3"], ids((Dataset) sides.FILE_1.dataDf), "the other side must not report K2 missing")
        assertEquals(["K1", "K3"], ids((Dataset) sides.FILE_1.idDf))
        assertEquals(["K1", "K3"], ids((Dataset) sides.FILE_2.dataDf))
        assertEquals(["K1", "K3"], ids((Dataset) sides.FILE_2.idDf))
        assertEquals(["K2", "K2"], ids((Dataset) split.duplicateDataBySide.FILE_2), "every record under the key is kept")
        assertNull(split.duplicateDataBySide.FILE_1)
        assertEquals(1L, split.duplicateKeyCountBySide.FILE_2)
    }

    @Test
    void withoutFindingNothingChanges() {
        Map input = [FILE_1: ingest(alpha()), FILE_2: ingest(beta())]
        Map split = RuleSetCompareScopeAdapter.splitDuplicateKeys(input, [FILE_1: null, FILE_2: null])
        assertSame(input, split.ingestBySide)
        assertEquals([:], split.duplicateDataBySide)
    }

    @Test
    void duplicateRowsUseTheMissingRowSchemaWithAnEmptyMissingIn() {
        Dataset dup = beta().filter("compare_id = 'K2'")
        Dataset out = CompareDatasetSupport.buildDuplicateRows(dup, "DUPLICATE_IN_FILE_2", "NetSuite", "Duplicated in NetSuite")
        Dataset missing = CompareDatasetSupport.buildMissingDiffRows(alpha(), alpha().select("compare_id"),
                "MISSING_IN_FILE_2", "Shopify", "NetSuite", "n")
        assertEquals(missing.schema(), out.schema(), "downstream readers are written against the missing-row shape")
        List<Row> got = out.collectAsList()
        assertEquals(2, got.size(), "one row per record, not per key")
        got.each { Row r ->
            assertEquals("DUPLICATE_IN_FILE_2", r.getAs("type"))
            assertEquals("NetSuite", r.getAs("presentIn"))
            assertEquals("", r.getAs("missingIn"), "the verify pass selects by missingIn; an empty one is skipped")
        }
    }
}
