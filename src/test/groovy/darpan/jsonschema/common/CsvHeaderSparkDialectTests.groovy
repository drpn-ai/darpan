package darpan.jsonschema.common

import darpan.reconciliation.core.ReconciliationServices
import jsonschema.common.CsvHeaderSchemaInferrer
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Row
import org.apache.spark.sql.SparkSession
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals

/**
 * Differential test. The wizard's parser and the reconciliation runtime's reader must agree on
 * every header, or a schema built here names columns the run cannot find -- which surfaces as a
 * run that matches nothing, with no error anywhere.
 *
 * When this fails, change CsvHeaderSchemaInferrer.sparkDialectSettings to match Spark. Never
 * relax the assertion: Spark is what actually reads the file at run time.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CsvHeaderSparkDialectTests {

    private SparkSession spark = SparkSession.builder()
            .appName("csv-header-dialect")
            .master("local[1]")
            .config("spark.ui.enabled", "false")
            .getOrCreate()

    @AfterAll
    void stopSpark() {
        spark?.stop()
    }

    @Test
    void agreesWithSparkOnPlainHeader() {
        assertAgrees("orderId,status,total\n1001,OPEN,5.00\n")
    }

    @Test
    void agreesWithSparkOnQuotedCommaHeader() {
        assertAgrees('orderId,"city, state",total\n1001,"Austin, TX",5.00\n')
    }

    @Test
    void agreesWithSparkOnCrlfHeader() {
        assertAgrees("orderId,status,total\r\n1001,OPEN,5.00\r\n")
    }

    @Test
    void agreesWithSparkOnDoubledQuoteHeader() {
        assertAgrees('orderId,"say ""hi""",total\n1001,greeting,5.00\n')
    }

    @Test
    void agreesWithSparkOnBomHeader() {
        assertAgrees("﻿orderId,status\n1001,OPEN\n")
    }

    @Test
    void agreesWithSparkOnSingleColumnHeader() {
        assertAgrees("orderId\n1001\n1002\n")
    }

    private void assertAgrees(String csvText) {
        List<String> fromInferrer = CsvHeaderSchemaInferrer.parseHeader(csvText, true)
        List<String> fromSpark = sparkColumns(csvText)
        assertEquals(fromSpark, fromInferrer,
                "parser and Spark disagree on header: ${csvText.readLines().first()}")
    }

    /**
     * Calls the production reader rather than restating its options, so this mirror cannot drift
     * out of date the next time the read dialect changes.
     */
    private List<String> sparkColumns(String csvText) {
        Path csvFile = Files.createTempFile("dialect-", ".csv")
        try {
            // UTF-8 with no extra BOM handling, matching what the uploaded file carries.
            Files.write(csvFile, csvText.getBytes("UTF-8"))
            Dataset<Row> df = ReconciliationServices.readCsvDataset(spark, csvFile.toAbsolutePath().toString(), true)
            return df.columns().toList()
        } finally {
            Files.deleteIfExists(csvFile)
        }
    }
}
