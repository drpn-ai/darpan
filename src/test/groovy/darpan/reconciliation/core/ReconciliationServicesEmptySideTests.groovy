package darpan.reconciliation.core

import org.apache.spark.sql.Dataset
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
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-OPS-029 S01a, invariant E2 (no hollow green) for a side that holds nothing. A zero-byte extract and one with no
 * records key are not reads at all: the compare refuses them loudly. A records array that is empty is a true empty window
 * (OC72): the compare runs, the other side reads all missing, and the run says the side read no records, so a blind
 * compare is never silent. Spark but no Moqui: the ec is a stub.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReconciliationServicesEmptySideTests {

    private SparkSession spark

    @TempDir
    File dir

    @BeforeAll
    void setup() {
        spark = SparkSession.builder().appName("ReconciliationServicesEmptySideTests").master("local[1]")
                .config("spark.ui.enabled", "false").getOrCreate()
    }

    @AfterAll
    void cleanup() { if (spark != null) spark.stop() }

    private ExecutionContext stubEc(Map<String, Object> parameters) {
        ContextStack context = new ContextStack()
        context.putAll(parameters)
        List<String> errors = []
        ResourceFacade resource = [getLocationReference: { String location -> null }, getProperties: { -> [:] }] as ResourceFacade
        ExecutionContextFactory factory = [getRuntimePath: { -> dir.absolutePath }] as ExecutionContextFactory
        L10nFacade l10n = [format: { Object value, String pattern -> "20261128-140000" }] as L10nFacade
        UserFacade user = [getNowTimestamp: { -> Timestamp.valueOf("2026-11-28 14:00:00") }] as UserFacade
        MessageFacade message = [addError: { String m -> errors.add(m) }, hasError: { -> !errors.isEmpty() }] as MessageFacade
        return [getContext: { -> context }, getResource: { -> resource }, getFactory: { -> factory },
                getL10n: { -> l10n }, getUser: { -> user }, getMessage: { -> message }] as ExecutionContext
    }

    private File file(String name, String text) {
        File f = new File(dir, name)
        f.setText(text, "UTF-8")
        return f
    }

    private Map run(File shopify) {
        File hotwax = file("hw-${shopify.name}", '{"metadata":{},"records":[{"id":"A"},{"id":"B"}]}')
        return ReconciliationServices.reconcileUnifiedFiles(stubEc([
                file1Location: hotwax.absolutePath, file2Location: shopify.absolutePath, file1Type: "JSON", file2Type: "JSON",
                file1IdField : '$.records[*].id', file2IdField: '$.records[*].id', file1Label: "HotWax", file2Label: "Shopify",
                outputLocation: "runtime://out"]))
    }

    @Test
    void aZeroByteExtractIsRefusedNotReadAsEmpty() {
        assertThrows(RuntimeException) { run(file("zero.json", "")) }
    }

    @Test
    void anExtractWithNoRecordsKeyIsRefusedNotReadAsEmpty() {
        assertThrows(RuntimeException) { run(file("nokey.json", '{"metadata":{"sourceType":"SHOPIFY"}}')) }
    }

    @Test
    void anEmptyRecordsArrayRunsAndSaysTheSideReadNoRecords() {
        Map result = run(file("empty.json", '{"metadata":{},"records":[]}'))

        assertEquals(2L, result.onlyInFile1Count, "every HotWax key reads as missing from Shopify")
        assertEquals(0L, result.matchedCount)
        assertTrue(((List<String>) result.processingWarnings).any { it.startsWith("FILE_2 (Shopify) read no records") },
                "the blind side is named: ${result.processingWarnings}")
        assertTrue(((List<String>) result.processingWarnings).every { !it.startsWith("FILE_1") }, "HotWax read records")
    }

    @Test
    void theWarningNamesTheSideAndSaysKeptWhenKeepSetEverythingAside() {
        Dataset none = spark.range(0).toDF("compare_id")
        Dataset some = spark.range(2).toDF("compare_id")
        assertTrue(ReconciliationServices.emptySideWarning("FILE_2", "Shopify", none, null).startsWith("FILE_2 (Shopify) read no records"))
        assertTrue(ReconciliationServices.emptySideWarning("FILE_1", "HotWax", none, [keptCount: 0, setAsideCount: 7])
                .startsWith("FILE_1 (HotWax) kept no records (7 set aside)"))
        assertNull(ReconciliationServices.emptySideWarning("FILE_1", "HotWax", some, null))
        assertNull(ReconciliationServices.emptySideWarning("FILE_1", "HotWax", null, null), "an evaluate run has no second side")
    }

    @Test
    void aPreparedCompareNamesEachEmptySideButNotARestrictedOrAllDuplicateOne() {
        Dataset none = spark.range(0).toDF("compare_id")
        Dataset some = spark.range(2).toDF("compare_id")
        Map prepared = [file1Label: "HotWax", file2Label: "Shopify", file1DataDf: none, file2DataDf: none]
        assertEquals(2, ReconciliationServices.emptySideWarnings(prepared, null).size())
        assertEquals(["FILE_2"], ReconciliationServices.emptySideWarnings(prepared, "/tmp/keys.txt").collect { it.split(" ")[0] },
                "a FILE_1 narrowed to a parent's keys has its own warning")
        assertEquals([], ReconciliationServices.emptySideWarnings(prepared + [file2DuplicateDataDf: some], "/tmp/keys.txt"),
                "a side whose every record is a duplicate read records")
        assertEquals([], ReconciliationServices.emptySideWarnings([file1DataDf: some, file2DataDf: some], null))
    }
}
