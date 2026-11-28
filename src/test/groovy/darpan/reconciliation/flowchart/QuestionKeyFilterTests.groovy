package darpan.reconciliation.flowchart

import darpan.reconciliation.core.RuleSetCompareScopeAdapter
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Encoders
import org.apache.spark.sql.Row
import org.apache.spark.sql.SparkSession
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-UI-048. A child question sees only its parent's branch keys. They arrive as a file and are
 * semi-joined on compare_id at prepare, so every connector gets the same answer whether or not it could
 * have filtered at the source.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuestionKeyFilterTests {

    private SparkSession spark
    @TempDir Path tmp

    @BeforeAll
    void setup() {
        spark = SparkSession.builder().appName("QuestionKeyFilterTests").master("local[1]")
                .config("spark.ui.enabled", "false").getOrCreate()
    }

    @AfterAll
    void cleanup() { if (spark != null) spark.stop() }

    private Dataset rows(String... jsonLines) {
        return spark.read().json(spark.createDataset(jsonLines.toList(), Encoders.STRING()))
    }

    private Map ingestBySide() {
        Dataset data = rows('{"compare_id":"K1","data":{"id":"K1"}}', '{"compare_id":"K2","data":{"id":"K2"}}',
                '{"compare_id":"K3","data":{"id":"K3"}}')
        return [FILE_1: [dataDf: data, idDf: data.select("compare_id")]]
    }

    private static List<String> ids(Dataset df) {
        return df.select("compare_id").collectAsList().collect { Row r -> r.getString(0) }.sort()
    }

    @Test
    void keyFilesRoundTripSortedAndTrimmed() {
        File f = tmp.resolve("k.txt").toFile()
        assertEquals(3L, QuestionKeyFiles.write(f, ["K3", "K1", "K2"]))
        assertEquals("K1\nK2\nK3\n", f.text)
        f.text = " K9 \n\nK8\n"
        assertEquals(["K8", "K9"] as Set, QuestionKeyFiles.read(f))
        assertEquals([] as Set, QuestionKeyFiles.read(tmp.resolve("missing.txt").toFile()))
    }

    @Test
    void file1KeepsOnlyTheHandedDownKeys() {
        File keys = tmp.resolve("in.txt").toFile()
        QuestionKeyFiles.write(keys, ["K1", "K3", "K9"])
        Map out = RuleSetCompareScopeAdapter.restrictSideToIdFile(ingestBySide(), "FILE_1", keys.absolutePath)
        Map side = (Map) ((Map) out.ingestBySide).FILE_1
        assertEquals(["K1", "K3"], ids((Dataset) side.dataDf))
        assertEquals(["K1", "K3"], ids((Dataset) side.idDf))
        assertEquals(3L, out.idCount)
        assertFalse(out.keptNone as boolean)
    }

    @Test
    void aKeyFileThatMatchesNothingIsReportedNotSilent() {
        File keys = tmp.resolve("none.txt").toFile()
        QuestionKeyFiles.write(keys, ["X1", "X2"])
        Map out = RuleSetCompareScopeAdapter.restrictSideToIdFile(ingestBySide(), "FILE_1", keys.absolutePath)
        assertTrue(out.keptNone as boolean)
        assertEquals(2L, out.idCount)
    }

    @Test
    void aSideThatIsNotThereIsLeftAlone() {
        File keys = tmp.resolve("in2.txt").toFile()
        QuestionKeyFiles.write(keys, ["K1"])
        Map original = ingestBySide()
        Map out = RuleSetCompareScopeAdapter.restrictSideToIdFile(original, "FILE_2", keys.absolutePath)
        assertTrue(out.ingestBySide.is(original))
    }

    @Test
    void file1KeysAreWrittenOutSortedAndDistinct() {
        Dataset data = rows('{"compare_id":"K2","data":{}}', '{"compare_id":"K3","data":{}}', '{"compare_id":"K1","data":{}}',
                '{"compare_id":"K2","data":{}}')
        File out = tmp.resolve("out/keys.txt").toFile()
        long n = RuleSetCompareScopeAdapter.writeSideIds([FILE_1: [dataDf: data.repartition(3), idDf: data.repartition(3).select("compare_id")]],
                "FILE_1", out.absolutePath)
        assertEquals(3L, n)
        // S01b I7: the same keys give the same file, whatever Spark's partition order (QuestionKeyFiles.write sorts too).
        assertEquals("K1\nK2\nK3\n", out.text)
    }

    @Test
    void theNothingMatchedWarningForAOneSourceQuestionNeedsTheKeysToDifferOnlyInForm() {
        Map keptNone = [keptNone: true, idCount: 4L]
        // A two-source child's FILE_1 is the whole window: matching none of the keys is always a key problem.
        assertTrue(RuleSetCompareScopeAdapter.keyMismatchWarning(false, keptNone, "Scope").contains("none of the 4 keys"))
        assertNull(RuleSetCompareScopeAdapter.keyMismatchWarning(false, [keptNone: false, idCount: 4L], "Scope"))
        // A one-source (EVALUATE) child's FILE_1 holds only failures, so matching nothing is usually the correct
        // all-pass answer (live: "Billed, never shipped" = 0) ...
        assertNull(RuleSetCompareScopeAdapter.keyMismatchWarning(true, keptNone, "Scope"))
        // ... unless the keys match once case and outer whitespace are folded (OC139: branch "ABC", finding "abc").
        assertTrue(RuleSetCompareScopeAdapter.keyMismatchWarning(true, keptNone + [matchesWhenFolded: true], "Scope")
                .contains("none of the 4 keys"))
    }

    @Test
    void aOneSourceChildWhoseKeysDifferOnlyInCaseIsFlagged() {
        // OC139: the parent handed down upper-case keys; the child's normalizer folded case.
        File keys = tmp.resolve("upper.txt").toFile()
        QuestionKeyFiles.write(keys, ["K1", "K3"])
        Dataset lower = rows('{"compare_id":"k1","data":{}}', '{"compare_id":"k3","data":{}}')
        Map out = RuleSetCompareScopeAdapter.restrictSideToIdFile([FILE_1: [dataDf: lower, idDf: lower.select("compare_id")]],
                "FILE_1", keys.absolutePath)
        assertTrue(out.keptNone as boolean)
        assertTrue(out.matchesWhenFolded as boolean)
        assertTrue(RuleSetCompareScopeAdapter.keyMismatchWarning(true, out, "Scope").contains("none of the 2 keys"))
        // A genuine all-pass child (no key, folded or not) stays quiet.
        Map allPass = RuleSetCompareScopeAdapter.restrictSideToIdFile(ingestBySide(), "FILE_1", tmp.resolve("none2.txt").toFile().with {
            QuestionKeyFiles.write(it, ["X1"]); it.absolutePath })
        assertFalse(allPass.matchesWhenFolded as boolean)
        assertNull(RuleSetCompareScopeAdapter.keyMismatchWarning(true, allPass, "Scope"))
    }

    @Test
    void aKeyEndingInATabOrCarriageReturnIsTheSameKeyAfterTheKeysFileRoundTrip() {
        // OC140: the compare trims a key the way the keys file is read back (Java trim: every char <= U+0020),
        // so "K1\t" and "K2\r" are asked about in the child question, not dropped as unasked.
        Dataset raw = spark.read().json(spark.createDataset([' {"id":" K1\\t"}', '{"id":"K2\\r"}', '{"id":"K3"}'],
                org.apache.spark.sql.Encoders.STRING()))
        List<Map> path = [darpan.reconciliation.core.CompareIdExpressionSupport.convertJsonPathToSpark('$.id')]
        Dataset data = darpan.reconciliation.core.CompareDatasetSupport.buildJsonDataDf(raw, path, "File 1", [null], ['$.id'])
        assertEquals(["K1", "K2", "K3"], ids(data))
        File out = tmp.resolve("trim/keys.txt").toFile()
        RuleSetCompareScopeAdapter.writeSideIds([FILE_1: [dataDf: data, idDf: data.select("compare_id")]], "FILE_1", out.absolutePath)
        Map kept = RuleSetCompareScopeAdapter.restrictSideToIdFile([FILE_1: [dataDf: data, idDf: data.select("compare_id")]],
                "FILE_1", out.absolutePath)
        assertEquals(["K1", "K2", "K3"], ids((Dataset) ((Map) kept.ingestBySide).FILE_1.dataDf))
    }
}
