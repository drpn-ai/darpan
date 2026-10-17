package darpan.reconciliation.core

import groovy.json.JsonSlurper
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Encoders
import org.apache.spark.sql.SparkSession
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.moqui.context.ExecutionContext
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.L10nFacade
import org.moqui.context.ResourceFacade
import org.moqui.context.UserFacade

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Characterization of where writeDiffDatasetOutput puts its file: an unresolvable runtime:// location
 * falls back under the runtime path, the name gains ".json" when it lacks it, and a taken name is
 * suffixed -1, -2, ... rather than overwritten. Spark but no Moqui — the ec is a stub.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiffDatasetOutputFileTests {

    private SparkSession spark

    @TempDir
    File runtimeDir

    @BeforeAll
    void setup() {
        spark = SparkSession.builder().appName("DiffDatasetOutputFileTests").master("local[1]")
                .config("spark.ui.enabled", "false").getOrCreate()
    }

    @AfterAll
    void cleanup() { if (spark != null) spark.stop() }

    private ExecutionContext stubEc() {
        ResourceFacade resource = [getLocationReference: { String location -> null }] as ResourceFacade
        ExecutionContextFactory factory = [getRuntimePath: { -> runtimeDir.absolutePath }] as ExecutionContextFactory
        L10nFacade l10n = [format: { Object value, String pattern -> "20261017-101500" }] as L10nFacade
        UserFacade user = [getNowTimestamp: { -> Timestamp.valueOf("2026-10-17 10:15:00") }] as UserFacade
        return [getResource: { -> resource }, getFactory: { -> factory }, getL10n: { -> l10n },
                getUser: { -> user }] as ExecutionContext
    }

    private Dataset diffs(String... jsonLines) {
        return spark.read().json(spark.createDataset(jsonLines.toList(), Encoders.STRING()))
    }

    private Map<String, Object> write(ExecutionContext ec, String folder, String outputFileName, String defaultBaseName) {
        return ReconciliationServices.writeDiffDatasetOutput(ec, diffs('{"id":"A1"}'), "runtime://out/${folder}".toString(),
                outputFileName, defaultBaseName, [file1Label: "OMS"], [totalDifferences: 1], [], [])
    }

    @Test
    void aTakenNameIsSuffixedRatherThanOverwritten() {
        ExecutionContext ec = stubEc()
        File outDir = new File(runtimeDir, "out/suffix")

        Map<String, Object> first = write(ec, "suffix", "orders", null)
        Map<String, Object> second = write(ec, "suffix", "orders.json", null)
        Map<String, Object> third = write(ec, "suffix", "orders", "ignored.json")

        assertEquals("orders.json", first.diffFileName)
        assertEquals("orders-1.json", second.diffFileName)
        assertEquals("orders-2.json", third.diffFileName)
        assertEquals(new File(outDir, "orders-1.json").absolutePath, second.diffLocation)
        assertEquals(["orders-1.json", "orders-2.json", "orders.json"], outDir.list().toList().sort())

        Map doc = (Map) new JsonSlurper().parse(new File(outDir, "orders.json"))
        assertEquals([[id: "A1"]], doc.differences)
        assertEquals("OMS", doc.metadata.file1Label)
    }

    @Test
    void theNameFallsBackToTheDefaultThenToATimestamp() {
        ExecutionContext ec = stubEc()
        assertEquals("fallback-base.json", write(ec, "fallback", "  ", "fallback-base").diffFileName)
        assertEquals("diff-20261017-101500.json", write(ec, "fallback", null, null).diffFileName)
        assertTrue(new File(runtimeDir, "out/fallback/diff-20261017-101500.json").isFile())
    }
}
