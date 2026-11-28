package darpan.reconciliation.core

import groovy.json.JsonSlurper
import org.apache.spark.sql.SparkSession
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.moqui.context.ExecutionContext
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.L10nFacade
import org.moqui.context.MessageFacade
import org.moqui.context.ResourceFacade
import org.moqui.context.UserFacade
import org.moqui.util.ContextStack

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-OPS-029 S01a. The mapping path (reconcile#FilesByMapping -> reconcile#UnifiedFiles) holds the compare invariants
 * the RuleSet path holds: every record lands in exactly one bucket and the counts add up to the rows written, a key on
 * two records of one side is a finding rather than a clean match (DAR-BE-165), a headerless CSV is read without a
 * header, and a key is normalised the same way on both sides. Spark but no Moqui: the ec is a stub.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReconciliationServicesUnifiedFilesTests {

    private SparkSession spark

    @TempDir
    File dir

    @BeforeAll
    void setup() {
        spark = SparkSession.builder().appName("ReconciliationServicesUnifiedFilesTests").master("local[1]")
                .config("spark.ui.enabled", "false").getOrCreate()
    }

    @AfterAll
    void cleanup() { if (spark != null) spark.stop() }

    private ExecutionContext stubEc(Map<String, Object> parameters) {
        ContextStack context = new ContextStack()
        context.putAll(parameters)
        List<String> errors = []
        ResourceFacade resource = [getLocationReference: { String location -> null },
                                   getProperties       : { -> [:] }] as ResourceFacade
        ExecutionContextFactory factory = [getRuntimePath: { -> dir.absolutePath }] as ExecutionContextFactory
        L10nFacade l10n = [format: { Object value, String pattern -> "20261128-120000" }] as L10nFacade
        UserFacade user = [getNowTimestamp: { -> Timestamp.valueOf("2026-11-28 12:00:00") }] as UserFacade
        MessageFacade message = [addError: { String m -> errors.add(m) }, hasError: { -> !errors.isEmpty() }] as MessageFacade
        return [getContext: { -> context }, getResource: { -> resource }, getFactory: { -> factory },
                getL10n: { -> l10n }, getUser: { -> user }, getMessage: { -> message }] as ExecutionContext
    }

    private File file(String name, String text) {
        File f = new File(dir, name)
        f.setText(text, "UTF-8")
        return f
    }

    /** Runs the unified compare and returns [result, document]. */
    private List<Map> run(File file1, String type1, String id1, File file2, String type2, String id2, Map extra = [:]) {
        Map<String, Object> parameters = [file1Location: file1.absolutePath, file2Location: file2.absolutePath,
                                          file1Type: type1, file2Type: type2, file1IdField: id1, file2IdField: id2,
                                          file1Label: "HotWax", file2Label: "Shopify", hasHeader: true,
                                          outputLocation: "runtime://out"] + extra
        Map result = ReconciliationServices.reconcileUnifiedFiles(stubEc(parameters))
        Map document = (Map) new JsonSlurper().parse(new File((String) result.diffLocation))
        return [result, document]
    }

    private static List<String> idsOfType(Map document, String type) {
        return ((List<Map>) document.differences).findAll { it.type == type }.collect { it.id as String }.sort()
    }

    @Test
    void aKeyOnTwoRecordsOfOneSideIsADuplicateFindingNotACleanMatch() {
        // A is on two HotWax records and one Shopify record: before, the anti-join matched it and the run read clean on A.
        File hotwax = file("hw.csv", "id,v\nA,1\nA,2\nB,3\n")
        File shopify = file("sh.csv", "id,v\nA,9\nC,4\n")
        def (Map result, Map document) = run(hotwax, "CSV", "id", shopify, "CSV", "id")

        assertEquals(["A", "A"], idsOfType(document, "DUPLICATE_IN_FILE_1"), "both copies of A are findings")
        assertEquals(["A"], idsOfType(document, "DUPLICATE_IN_FILE_2_PAIRED"), "Shopify's A is its partner row (S01b OC14)")
        assertTrue(((List<Map>) document.differences).find { it.type == "DUPLICATE_IN_FILE_2_PAIRED" }.note.toString().contains("HotWax"),
                "the partner row names the system whose key repeated, not FILE_1")
        assertEquals(["B"], idsOfType(document, "missing_in_Shopify"))
        assertEquals(["C"], idsOfType(document, "missing_in_HotWax"))
        assertEquals(1L, result.onlyInFile1Count)
        assertEquals(1L, result.onlyInFile2Count)
        assertEquals(3L, result.duplicateDifferenceCount, "2 repeats and the partner")
        assertEquals(0L, result.matchedCount, "A left the compare on both sides; nothing matched")
        assertEquals(5L, result.differenceCount, "1 + 1 missing, the 2 duplicate records and their partner")
        assertEquals(((List) document.differences).size(), (document.summary as Map).totalDifferences as int,
                "the summary counts exactly the rows written")
        assertTrue(((List<String>) result.processingWarnings).any { it.contains("HotWax") && it.contains("more than one record") },
                "the run says a key repeated: ${result.processingWarnings}")
    }

    @Test
    void everyRecordLandsInExactlyOneBucket() {
        // Left: A B C D D (5 records). Right: B C E (3 records). B, C match; A only left; D duplicate left; E only right.
        File left = file("left.csv", "id\nA\nB\nC\nD\nD\n")
        File right = file("right.csv", "id\nB\nC\nE\n")
        def (Map result, Map document) = run(left, "CSV", "id", right, "CSV", "id")

        long matched = (long) result.matchedCount
        assertEquals(2L, matched)
        assertEquals(5L, matched + (long) result.onlyInFile1Count + idsOfType(document, "DUPLICATE_IN_FILE_1").size(),
                "left = matched + only-left + duplicate records")
        assertEquals(3L, matched + (long) result.onlyInFile2Count + idsOfType(document, "DUPLICATE_IN_FILE_2").size(),
                "right = matched + only-right + duplicate records")
        assertEquals(matched, (document.summary as Map).matchedCount as long)
        assertEquals(((List) document.differences).size(), (document.summary as Map).totalDifferences as int)
    }

    @Test
    void aHeaderlessCsvIsReadWithoutAHeader() {
        // hasHeader false used to become true (`false ?: true`), so the first record was eaten as the header row.
        File left = file("nohead-left.csv", "A,1\nB,2\n")
        File right = file("nohead-right.csv", "A,1\n")
        def (Map result, Map document) = run(left, "CSV", "_c0", right, "CSV", "_c0", [hasHeader: false])

        assertEquals(["B"], idsOfType(document, "missing_in_Shopify"))
        assertEquals(1L, result.matchedCount, "A, the first line, is a record and matches")
        assertEquals(1L, result.differenceCount)
    }

    @Test
    void aKeyIsNormalisedTheSameWayOnBothSides() {
        // Trimmed on both sides; CASE_FOLD on both sides folds case; a JSON number and a CSV text of the same digits
        // match; leading zeros are significant (no side strips them).
        File json = file("side.json", '{"metadata":{},"records":[{"id":123},{"id":" ab "},{"id":"00123"}]}')
        File csv = file("side.csv", "id\n123\nAB\n")
        def (Map result, Map document) = run(json, "JSON", '$.records[*].id|CASE_FOLD', csv, "CSV", "id|CASE_FOLD")

        assertEquals(2L, result.matchedCount, "123 = '123' and ' ab ' = 'AB' after trim and case fold")
        assertEquals(["00123"], idsOfType(document, "missing_in_Shopify"), "00123 is not 123")
        assertEquals(0L, result.onlyInFile2Count)
    }
}
