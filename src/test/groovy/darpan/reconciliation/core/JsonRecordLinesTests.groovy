package darpan.reconciliation.core

import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Row
import org.apache.spark.sql.SparkSession
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-166. An extract's records, one per line, read by Spark in partitions, must give the compare exactly what
 * the multiLine read of the whole document gave: the same keys and the same record per key. Spark but no Moqui.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JsonRecordLinesTests {

    private SparkSession spark

    @TempDir
    File dir

    @BeforeAll
    void setup() {
        spark = SparkSession.builder().appName("JsonRecordLinesTests").master("local[2]")
                .config("spark.ui.enabled", "false").getOrCreate()
    }

    @AfterAll
    void cleanup() { if (spark != null) spark.stop() }

    private File extract(String name, String text) {
        File f = new File(dir, name)
        f.setText(text, "UTF-8")
        return f
    }

    private static List<Map> paths(String... jsonPaths) {
        return jsonPaths.collect { String p -> CompareIdExpressionSupport.convertJsonPathToSpark(p) }
    }

    private static final String ORDERS = '''{"metadata":{"sourceType":"OMS","records":"not these"},"records":[
 {"orderName":"SMUS#1","grandTotal":90.90,"shipGroups":[{"items":[{"sku":"A","qty":1}]}],"note":"line\\nbreak"},
 "not a record",
 {"orderName":"SMUS#2","grandTotal":12345678901234567890.123456789,"shipGroups":[]},
 {"orderName":"SMUS#1","grandTotal":5,"isCompleted":"Y"}
],"trailer":{"count":3}}'''

    @Test
    void eachObjectUnderTheArrayIsOneLineAndNothingElseIs() {
        File source = extract("orders.json", ORDERS)
        File lines = JsonRecordLines.forRecordArray(source.absolutePath, paths('$.records[*].orderName'), dir.parentFile)

        assertEquals(new File(dir, "orders.json" + JsonRecordLines.SUFFIX), lines, "beside the extract")
        List<String> written = lines.readLines("UTF-8").findAll { it }
        assertEquals(3, written.size(), "three objects; the string, metadata and trailer are not records")
        assertTrue(written[0].contains('"grandTotal":90.90'), "a decimal keeps its own digits: ${written[0]}")
        assertTrue(written[0].contains('"note":"line\\nbreak"'), "an escaped newline stays escaped, so a record is one line")
        assertTrue(written[1].contains('12345678901234567890.123456789'), written[1])
        assertEquals([] as List, dir.list().findAll { it.endsWith(".partial") } as List, "no work file is left")
    }

    @Test
    void theLinesGiveTheCompareWhatTheWholeDocumentGave() {
        // Objects only: a string in the array makes the whole-document read widen every record to a string.
        File source = extract("same.json", ORDERS.replace(' "not a record",\n', ''))
        List<Map> infos = paths('$.records[*].orderName')
        Dataset whole = spark.read().option("multiLine", "true").json(source.absolutePath)
        Dataset oldIds = CompareDatasetSupport.buildJsonIdDf(whole, infos, "HotWax", [null], ['$.records[*].orderName'])
        Dataset oldData = CompareDatasetSupport.buildJsonDataDf(whole, infos, "HotWax", [null], ['$.records[*].orderName'])

        File lines = JsonRecordLines.forRecordArray(source.absolutePath, infos, dir.parentFile)
        List<Map> rootInfos = JsonRecordLines.recordRootPathInfos(infos)
        Dataset rows = spark.read().json(lines.absolutePath)
        Dataset newIds = CompareDatasetSupport.buildJsonIdDf(rows, rootInfos, "HotWax", [null], ['$.records[*].orderName'])
        Dataset newData = CompareDatasetSupport.buildJsonDataDf(rows, rootInfos, "HotWax", [null], ['$.records[*].orderName'])

        assertEquals(sorted(oldIds.toJSON()), sorted(newIds.toJSON()))
        assertEquals(sorted(oldData.toJSON()), sorted(newData.toJSON()), "same key, same record, same shape")
        assertEquals(["SMUS#1", "SMUS#1", "SMUS#2"], newData.select("compare_id").collectAsList().collect { Row r -> r.getString(0) }.sort(),
                "a repeated key keeps both records, so DAR-BE-165 still reports it")
    }

    @Test
    void anythingButOneTopLevelArrayIsReadWholeAsBefore() {
        File source = extract("plain.json", ORDERS)
        assertNull(JsonRecordLines.forRecordArray(source.absolutePath, paths('$.orderName'), dir.parentFile), "a root key explodes nothing")
        assertNull(JsonRecordLines.forRecordArray(source.absolutePath, paths('$.data.records[*].orderName'), dir.parentFile), "a nested array")
        assertNull(JsonRecordLines.forRecordArray(source.absolutePath, paths('$.records[*].orderName', '$.trailer[*].count'), dir.parentFile),
                "keys under two arrays")
        assertNull(JsonRecordLines.forRecordArray(source.absolutePath, paths('$.missing[*].orderName'), dir.parentFile), "no such array")
        File empty = extract("empty.json", '{"metadata":{},"records":[]}')
        assertNull(JsonRecordLines.forRecordArray(empty.absolutePath, paths('$.records[*].orderName'), dir.parentFile), "no record to read")
        File array = extract("array.json", '[{"orderName":"X"}]')
        assertNull(JsonRecordLines.forRecordArray(array.absolutePath, paths('$.records[*].orderName'), dir.parentFile), "not an object")
        assertEquals([] as List, dir.list().findAll { it.endsWith(".partial") || it.endsWith(JsonRecordLines.SUFFIX) } as List,
                "a refusal leaves nothing behind")
    }

    @Test
    void anExtractOutsideTheDataManagerGetsATempFileNotASibling() {
        // A fixture or resource file is read through the same path; only a run's own folder gains the lines file.
        File source = extract("fixture.json", ORDERS)
        File lines = JsonRecordLines.forRecordArray(source.absolutePath, paths('$.records[*].orderName'), null)
        assertTrue(lines.parentFile != dir, "not beside the fixture: ${lines}")
        assertEquals(3, lines.readLines("UTF-8").findAll { it }.size())
        assertEquals(["fixture.json"], dir.list().findAll { it.startsWith("fixture") } as List)
        lines.delete()
    }

    @Test
    void rowsGoToTheDriverInBoundedPartitionsHoweverTheShuffleCoalesced() {
        // On SM's October run adaptive execution left all 125,602 matched pairs in one partition, and toLocalIterator
        // brought it to the driver whole. Here: 5,000 rows forced into one partition come back as 3 of at most 2,000.
        Dataset one = spark.range(5000).toDF("n").coalesce(1)
        assertEquals(1, one.rdd().getNumPartitions())
        Dataset bounded = ReconciliationServices.boundedPartitions(one, 2000)
        assertEquals(3, bounded.rdd().getNumPartitions())
        assertTrue(bounded.rdd().glom().toJavaRDD().collect().every { Object part -> ((Object[]) part).length <= 2000 })
        Iterator<String> rows = bounded.toJSON().toLocalIterator()
        int seen = 0
        while (rows.hasNext()) { rows.next(); seen++ }
        assertEquals(5000, seen, "every row still arrives")
        assertEquals(1, ReconciliationServices.boundedPartitions(spark.range(0).toDF("n"), 2000).rdd().getNumPartitions())
    }

    @Test
    void aSavedDocumentSchemaGivesItsRecordSchema() {
        def schema = new org.apache.spark.sql.types.StructType()
                .add("metadata", new org.apache.spark.sql.types.StructType().add("sourceType", "string"))
                .add("records", org.apache.spark.sql.types.DataTypes.createArrayType(
                        new org.apache.spark.sql.types.StructType().add("orderName", "string").add("grandTotal", "double")))
        assertEquals(["orderName", "grandTotal"], JsonRecordLines.recordSchema(schema, paths('$.records[*].orderName')).fieldNames() as List)
        assertNull(JsonRecordLines.recordSchema(null, paths('$.records[*].orderName')))
        assertNull(JsonRecordLines.recordSchema(schema, paths('$.orderName')))
    }

    private static List<String> sorted(Dataset<String> json) {
        return json.collectAsList().sort()
    }
}
