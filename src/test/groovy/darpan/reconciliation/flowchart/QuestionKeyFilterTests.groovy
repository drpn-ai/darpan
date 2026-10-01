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
    void file1KeysAreWrittenOutDistinct() {
        Dataset data = rows('{"compare_id":"K2","data":{}}', '{"compare_id":"K1","data":{}}', '{"compare_id":"K2","data":{}}')
        File out = tmp.resolve("out/keys.txt").toFile()
        long n = RuleSetCompareScopeAdapter.writeSideIds([FILE_1: [dataDf: data, idDf: data.select("compare_id")]],
                "FILE_1", out.absolutePath)
        assertEquals(2L, n)
        assertEquals(["K1", "K2"] as Set, QuestionKeyFiles.read(out))
    }

    @Test
    void theNothingMatchedWarningIsOnlyForTwoSourceQuestions() {
        Map keptNone = [keptNone: true, idCount: 4L]
        // A one-source (EVALUATE) child's FILE_1 holds only failures, so matching nothing is the correct
        // all-pass answer, not a key problem (live: "Billed, never shipped" = 0).
        assertNull(RuleSetCompareScopeAdapter.keyMismatchWarning(true, keptNone, "Scope"))
        assertTrue(RuleSetCompareScopeAdapter.keyMismatchWarning(false, keptNone, "Scope").contains("none of the 4 keys"))
        assertNull(RuleSetCompareScopeAdapter.keyMismatchWarning(false, [keptNone: false, idCount: 4L], "Scope"))
    }
}
