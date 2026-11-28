package darpan.reconciliation.core

import darpan.reconciliation.source.KeepArtifacts
import org.apache.spark.sql.SparkSession
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.moqui.context.ExecutionContext
import org.moqui.context.ResourceFacade

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-OPS-029 S01a, OC36's call site. An ingest with no Keep takes every record of its extract, so an earlier Spark Keep
 * pass's artifacts beside it (its .keep.json summary and set-aside rows) go: otherwise the conclude pass and a sync pulse
 * would keep skipping records this compare took. KeepStepSupportTests owns forgetKeep's own contract.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReconciliationServicesForgetKeepTests {

    private SparkSession spark

    @TempDir
    File dir

    @BeforeAll
    void setup() {
        spark = SparkSession.builder().appName("ReconciliationServicesForgetKeepTests").master("local[1]")
                .config("spark.ui.enabled", "false").getOrCreate()
    }

    @AfterAll
    void cleanup() { if (spark != null) spark.stop() }

    @Test
    void anIngestWithNoKeepClearsAnEarlierKeepPassesArtifacts() {
        File extract = new File(dir, "orders.json")
        extract.setText('{"metadata":{},"records":[{"id":"A"},{"id":"B"}]}', "UTF-8")
        KeepArtifacts.writeSummary(extract, [keptCount: 1, setAsideCount: 1])
        KeepArtifacts.rowsFile(extract).setText("1\n", "UTF-8")
        assertTrue(KeepArtifacts.keepFile(extract).isFile(), "the earlier pass's summary is there to start with")

        ResourceFacade resource = [getLocationReference: { String location -> null }] as ResourceFacade
        ExecutionContext ec = [getResource: { -> resource }] as ExecutionContext
        Map ingest = ReconciliationServices.ingestFile(ec, spark, extract.absolutePath, "JSON", [idExpr: '$.records[*].id'],
                true, "HotWax", [], null)

        assertEquals(2L, ReconciliationServices.countDataset(ingest.dataDf as org.apache.spark.sql.Dataset), "every record is in the compare")
        assertFalse(KeepArtifacts.keepFile(extract).exists(), "the stale Keep summary is gone")
        assertFalse(KeepArtifacts.rowsFile(extract).exists(), "the stale set-aside rows are gone")
    }
}
