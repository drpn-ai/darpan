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
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-063 Task 12, DAR-BE-165. Every key that repeats on a side is reported as findings, never thrown on
 * (ruled run) or silently collapsed into a match (base-diff run).
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
    void aRepeatedKeyLeavesBothSides() {
        Map split = RuleSetCompareScopeAdapter.splitDuplicateKeys([FILE_1: ingest(alpha()), FILE_2: ingest(beta())])
        Map sides = (Map) split.ingestBySide
        assertEquals(["K1", "K3"], ids((Dataset) sides.FILE_1.dataDf), "the other side must not report K2 missing")
        assertEquals(["K1", "K3"], ids((Dataset) sides.FILE_1.idDf))
        assertEquals(["K1", "K3"], ids((Dataset) sides.FILE_2.dataDf))
        assertEquals(["K1", "K3"], ids((Dataset) sides.FILE_2.idDf))
        assertEquals(["K2", "K2"], ids((Dataset) split.duplicateDataBySide.FILE_2), "every record under the key is kept")
        assertNull(split.duplicateDataBySide.FILE_1, "a side with no repeated key has no duplicate frame")
        assertEquals(1L, split.duplicateKeyCountBySide.FILE_2)
        assertNull(split.duplicateKeyCountBySide.FILE_1)
    }

    @Test
    void noRepeatedKeyLeavesTheInputAlone() {
        Map input = [FILE_1: ingest(alpha()), FILE_2: ingest(alpha())]
        Map split = RuleSetCompareScopeAdapter.splitDuplicateKeys(input)
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

    /** K2 once on FILE_1, twice on FILE_2; K4 twice on FILE_2 and absent from FILE_1; K5 twice on BOTH sides. */
    private Map<String, Dataset> mixed() {
        return [FILE_1: rows('{"compare_id":"K1","data":{}}', '{"compare_id":"K2","data":{}}',
                             '{"compare_id":"K5","data":{"n":"a"}}', '{"compare_id":"K5","data":{"n":"b"}}'),
                FILE_2: rows('{"compare_id":"K1","data":{}}', '{"compare_id":"K2","data":{"n":"a"}}', '{"compare_id":"K2","data":{"n":"b"}}',
                             '{"compare_id":"K4","data":{"n":"a"}}', '{"compare_id":"K4","data":{"n":"b"}}',
                             '{"compare_id":"K5","data":{"n":"c"}}', '{"compare_id":"K5","data":{"n":"d"}}')]
    }

    private static List<String> idsOrEmpty(Object df) { return df == null ? [] : ids((Dataset) df) }

    @Test
    void everyKeyedRecordLandsInExactlyOnePlace() {
        // S01b I1, conservation: per side, input = kept + own duplicates + partners (key repeated only on the other side).
        Map<String, Dataset> input = mixed()
        Map split = RuleSetCompareScopeAdapter.splitDuplicateKeys([FILE_1: ingest(input.FILE_1), FILE_2: ingest(input.FILE_2)])
        Map partnersBySide = [FILE_1: ["K2"], FILE_2: []]
        ["FILE_1", "FILE_2"].each { String side ->
            List<String> kept = ids((Dataset) ((Map) split.ingestBySide)[side].dataDf)
            List<String> own = idsOrEmpty(split.duplicateDataBySide[side])
            List<String> partners = (List<String>) partnersBySide[side]
            assertEquals(ids(input[side]), (kept + own + partners).sort(), "${side}: every record is in exactly one place")
        }
    }

    @Test
    void aRepeatedKeyIsNeverMatched() {
        // S01b I2: no repeated key, on either side, survives into the frames the matched set is joined from.
        Map<String, Dataset> input = mixed()
        Map split = RuleSetCompareScopeAdapter.splitDuplicateKeys([FILE_1: ingest(input.FILE_1), FILE_2: ingest(input.FILE_2)])
        Map sides = (Map) split.ingestBySide
        Dataset matched = CompareDatasetSupport.buildMatchedIdDataset((Dataset) sides.FILE_1.idDf, (Dataset) sides.FILE_2.idDf)
        assertEquals(["K1"], ids(matched))
        assertEquals(["K5", "K5"], ids((Dataset) split.duplicateDataBySide.FILE_1))
        assertEquals(["K2", "K2", "K4", "K4", "K5", "K5"], ids((Dataset) split.duplicateDataBySide.FILE_2))
        assertEquals([FILE_1: 1L, FILE_2: 3L], split.duplicateKeyCountBySide)
    }

    @Test
    void theSplitMirrorsWhenTheSidesSwap() {
        // S01b I3: direction-symmetric.
        Map<String, Dataset> input = mixed()
        Map forward = RuleSetCompareScopeAdapter.splitDuplicateKeys([FILE_1: ingest(input.FILE_1), FILE_2: ingest(input.FILE_2)])
        Map swapped = RuleSetCompareScopeAdapter.splitDuplicateKeys([FILE_1: ingest(input.FILE_2), FILE_2: ingest(input.FILE_1)])
        assertEquals(ids((Dataset) forward.ingestBySide.FILE_1.dataDf), ids((Dataset) swapped.ingestBySide.FILE_2.dataDf))
        assertEquals(ids((Dataset) forward.ingestBySide.FILE_2.dataDf), ids((Dataset) swapped.ingestBySide.FILE_1.dataDf))
        assertEquals(idsOrEmpty(forward.duplicateDataBySide.FILE_1), idsOrEmpty(swapped.duplicateDataBySide.FILE_2))
        assertEquals(idsOrEmpty(forward.duplicateDataBySide.FILE_2), idsOrEmpty(swapped.duplicateDataBySide.FILE_1))
    }

    @Test
    void theRepeatedKeyWarningEndsWithTheTailApplyHiddenWithdraws() {
        // S01b I4 / DAR-BE-337: RunConclusionSupport.applyHidden withdraws the warning by this tail.
        String warning = RuleSetCompareScopeAdapter.duplicateKeyWarning("Compare scope 'Orders' FILE_2 (NetSuite)", 3L)
        assertEquals("Compare scope 'Orders' FILE_2 (NetSuite) has 3 key(s) on more than one record; " +
                RuleSetCompareScopeAdapter.DUPLICATE_KEY_WARNING_TAIL, warning)
    }

    @Test
    void everyFrameTheSplitPersistsIsHandedBackForRelease() {
        // Review I3: a persisted frame nobody returns stays in Spark's CacheManager for the JVM's life.
        Map split = RuleSetCompareScopeAdapter.splitDuplicateKeys([FILE_1: ingest(alpha()), FILE_2: ingest(beta())])
        List<Dataset> frames = (List<Dataset>) split.persistedFrames
        assertEquals(1, frames.size())
        frames.each { Dataset f -> assertTrue(f.storageLevel().useDisk() || f.storageLevel().useMemory(), "it is cached") }
        frames.each { Dataset f -> f.unpersist() }
    }
}
