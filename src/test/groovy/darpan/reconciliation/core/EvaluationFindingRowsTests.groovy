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
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-049: findings from a single-sided EVALUATE scope.
 *
 * THE POINT OF THIS CLASS IS THE SCHEMA-PARITY TEST. An evaluate scope has no second side to diff
 * against, so its findings cannot come from buildMissingDiffRows — but everything downstream (the
 * output document, the differences UI, runResultDiffDetails, the verification passes that re-read the
 * document) is written against that row shape. If the two builders drift, findings render as blanks in
 * a UI that does not switch on the row type and therefore cannot warn about it. So parity is asserted
 * against the real builder rather than against a copied literal list.
 *
 * Spark but no Moqui, so this lands in the fast unitTest pool.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EvaluationFindingRowsTests {

    private SparkSession spark

    @BeforeAll
    void setup() {
        spark = SparkSession.builder().appName("EvaluationFindingRowsTests").master("local[1]")
                .config("spark.ui.enabled", "false").getOrCreate()
    }

    @AfterAll
    void cleanup() { if (spark != null) spark.stop() }

    /** compare_id plus a nested `data` struct — the shape ingestFile produces. */
    private Dataset sourceRows(String... jsonLines) {
        return spark.read().json(spark.createDataset(jsonLines.toList(), Encoders.STRING()))
    }

    private Dataset twoOrders() {
        return sourceRows(
                '{"compare_id":"M100","data":{"orderId":"M100","status":"B"}}',
                '{"compare_id":"M101","data":{"orderId":"M101","status":"G"}}')
    }

    // ---------------------------------------------------------------- the parity contract

    @Test
    void findingRowsUseTheSameSchemaAsMissingDiffRows() {
        Dataset data = twoOrders()
        Dataset missing = CompareDatasetSupport.buildMissingDiffRows(
                data, data.select("compare_id"), "MISSING_IN_FILE_2", "OMS", "NetSuite", "note")
        Dataset findings = CompareDatasetSupport.buildEvaluationFindingRows(
                data, "FINDING", "NetSuite", "orders with no fulfillment")

        assertEquals(missing.schema().treeString(), findings.schema().treeString(),
                "an evaluate finding must be indistinguishable in shape from a diff row, or it renders " +
                        "as blanks in a UI that does not switch on row type")
    }

    // ---------------------------------------------------------------- content

    @Test
    void everySourceRowBecomesAFinding() {
        // The extractor owns the predicate in EVALUATE mode, so its rows ARE the findings — none are
        // filtered here. A builder that dropped rows would under-report silently.
        assertEquals(2L, CompareDatasetSupport.buildEvaluationFindingRows(
                twoOrders(), "FINDING", "NetSuite", "note").count())
    }

    @Test
    void findingCarriesTypeIdSourceAndNote() {
        List<Row> rows = CompareDatasetSupport.buildEvaluationFindingRows(
                twoOrders(), "FINDING", "NetSuite", "orders with no fulfillment")
                .orderBy("id").collectAsList()

        assertEquals("FINDING", rows[0].getAs("type"))
        assertEquals("M100", rows[0].getAs("id"))
        assertEquals("NetSuite", rows[0].getAs("presentIn"))
        assertEquals("orders with no fulfillment", rows[0].getAs("note"))
        // The row's own fields survive as JSON, exactly as a diff row's `data` does.
        assertTrue(((String) rows[0].getAs("data")).contains("M100"), rows[0].getAs("data") as String)
        assertEquals("M101", rows[1].getAs("id"))
    }

    @Test
    void missingInIsEmptyRatherThanNull() {
        // Nothing is missing on an evaluate run, but the column must stay a StringType so the schema
        // matches and the frame can still union with diff rows. A lit(null) would be NullType and
        // break both.
        Row row = (Row) CompareDatasetSupport.buildEvaluationFindingRows(
                twoOrders(), "FINDING", "NetSuite", "note").first()
        assertNotNull(row.getAs("missingIn"))
        assertEquals("", row.getAs("missingIn"))
    }

    @Test
    void aNullNoteDoesNotProduceANullTypeColumn() {
        Dataset findings = CompareDatasetSupport.buildEvaluationFindingRows(twoOrders(), "FINDING", "NetSuite", null)
        assertEquals("string", findings.schema().apply("note").dataType().typeName())
        assertEquals("", ((Row) findings.first()).getAs("note"))
    }

    @Test
    void nullSourceYieldsNullRatherThanThrowing() {
        // Mirrors buildMissingDiffRows, whose callers rely on a null frame meaning "nothing to union".
        assertNull(CompareDatasetSupport.buildEvaluationFindingRows(null, "FINDING", "NetSuite", "note"))
    }

    @Test
    void reconciliationServicesExposesTheDelegate() {
        // The service XML reaches these statics through ReconciliationServices, so the delegate has to
        // exist or the evaluate service fails at runtime with no compile-time warning.
        assertEquals(2L, ReconciliationServices.buildEvaluationFindingRows(
                twoOrders(), "FINDING", "NetSuite", "note").count())
    }
}
