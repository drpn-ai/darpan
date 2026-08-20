package darpan.reconciliation.core


import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Row
import org.apache.spark.sql.SparkSession
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * A blank CSV cell must reach the result as an empty value, not vanish.
 *
 * Spark's CSV reader defaults nullValue to "", so an empty cell arrives as null -- and both
 * Dataset.toJSON() (which writes the differences array) and to_json (which writes the record
 * payloads on missing rows) DROP null fields entirely. The user-visible effect is a difference
 * record with no file1Value at all, which reads as "this column was never here" when the truth is
 * "this column was blank". Those are different findings and the report must not conflate them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CsvBlankCellReadTests {

    private SparkSession spark = SparkSession.builder()
            .appName("csv-blank-cell")
            .master("local[1]")
            .config("spark.ui.enabled", "false")
            .getOrCreate()

    @AfterAll
    void stopSpark() {
        spark?.stop()
    }

    @Test
    void blankCellReadsAsEmptyStringRatherThanNull() {
        Dataset<Row> df = read("external-id,address-line-1,address-line-2\n4055,Moon Security,\n")
        Row row = df.collectAsList().get(0)

        assertEquals("", row.getAs("address-line-2"),
                "a blank CSV cell must survive the read as an empty value")
    }

    @Test
    void blankCellKeepsItsColumnInTheEmittedJson() {
        Dataset<Row> df = read("external-id,address-line-1,address-line-2\n4055,Moon Security,\n")
        String json = df.toJSON().collectAsList().get(0)

        // toJSON omits null fields, so this is the assertion that actually pins the reported shape:
        // the column has to still be there, carrying an empty value.
        assertTrue(json.contains('"address-line-2":""'),
                "blank column disappeared from the emitted JSON instead of being reported empty: ${json}")
    }

    @Test
    void quotedEmptyCellIsAlsoReportedEmpty() {
        Dataset<Row> df = read('external-id,city\n"4055",""\n')
        String json = df.toJSON().collectAsList().get(0)

        assertTrue(json.contains('"city":""'),
                "quoted-empty column disappeared from the emitted JSON: ${json}")
    }

    private Dataset<Row> read(String csvText) {
        Path csvFile = Files.createTempFile("blank-cell-", ".csv")
        Files.write(csvFile, csvText.getBytes("UTF-8"))
        csvFile.toFile().deleteOnExit()
        return ReconciliationServices.readCsvDataset(spark, csvFile.toAbsolutePath().toString(), true)
    }
}
