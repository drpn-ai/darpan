package darpan.reconciliation.core

import darpan.common.FactText
import darpan.reconciliation.source.KeepRules
import groovy.json.JsonParserType
import darpan.common.ValueSupport
import groovy.json.JsonSlurper
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.types.DataTypes
import org.apache.spark.sql.types.StructType
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

    // ------------------------------------------------------------------------------------ DAR-BE-318 Keep field text

    private static List<Map> inj(String field, String text, int n = 0) {
        return [[column: KeepRules.INJECTED_PREFIX + n, field: field, text: text] as Map]
    }

    private List<Map> linesOf(String name, String text, List<Map> injections) {
        File source = extract(name, text)
        File lines = JsonRecordLines.forRecordArray(source.absolutePath, paths('$.records[*].id'), dir.parentFile, injections)
        return lines.readLines("UTF-8").findAll { it }.collect { (Map) new JsonSlurper().parseText(it) }
    }

    @Test
    void noInjectionsIsByteIdentical() {
        String golden = '{"id":"a","v":24.00,"n":{"x":[1,2.50,"s"]},"t":null,"b":true}\n{"id":"b"}\n'
        File source = extract("plain.json", '{"metadata":{"records":"no"},"records":[{"id":"a","v":24.00,"n":{"x":[1,2.50,"s"]},"t":null,"b":true},"skip",{"id":"b"}]}')
        List<Map> infos = paths('$.records[*].id')
        for (List<Map> none : [null, [] as List<Map>]) {
            File lines = none == null ? JsonRecordLines.forRecordArray(source.absolutePath, infos, dir.parentFile) :
                    JsonRecordLines.forRecordArray(source.absolutePath, infos, dir.parentFile, none)
            assertEquals(golden, lines.getText("UTF-8"))
        }
    }

    @Test
    void groovyTextIsTheValuesToString() {
        String json = '{"records":[{"id":"1","v":24.00},{"id":"2","v":1.0E7},{"id":"3","v":1.0E+7},{"id":"4","v":7},' +
                '{"id":"5","v":true},{"id":"6","v":"x"},{"id":"7","v":[1, 2]},{"id":"8","v":{"a":1}}]}'
        List<Map> got = linesOf("groovy.json", json, inj("v", "GROOVY"))
        assertEquals(["24.00", "1.0E7", "1.0E+7", "7", "true", "x", "[1, 2]", "{a=1}"], got.collect { it.get("_darpanKeep_0") })
    }

    @Test
    void factTextIsTheFlatText() {
        String json = '{"records":[{"id":"1","v":24.00},{"id":"2","v":1.0E7},{"id":"3","v":"000123"},{"id":"4","v":[1,{"b":2.50}]},' +
                '{"id":"5","v":{"a":1}},{"id":"6","v":false}]}'
        List<Map> got = linesOf("fact.json", json, inj("v", "FACT"))
        List<Map> memory = (List<Map>) ((Map) new JsonSlurper().parseText(json)).get("records")
        got.eachWithIndex { Map line, int i ->
            assertEquals(FactText.flat(memory[i]).get("v"), line.get("_darpanKeep_0"), "record ${i}")
        }
        assertEquals("24", got[0].get("_darpanKeep_0"))
        assertEquals("10000000", got[1].get("_darpanKeep_0"))
        assertEquals("000123", got[2].get("_darpanKeep_0"))
    }

    @Test
    void onlyTheTrimmedCaseSensitiveKeyMatches() {
        String json = '{"records":[{"id":"1","Status":"A"},{"id":"2","status":"B"},{"id":"3"," status ":"C"},' +
                '{"id":"4","Status":"first","status":"second"},{"id":"5","status":null,"Status":"x"}]}'
        List<Map> got = linesOf("keys.json", json, inj("status", "GROOVY"))
        assertTrue(!got[0].containsKey("_darpanKeep_0"), "Status is another key")
        assertEquals("B", got[1].get("_darpanKeep_0"))
        assertEquals("C", got[2].get("_darpanKeep_0"), "the key is trimmed")
        assertEquals("second", got[3].get("_darpanKeep_0"))
        assertTrue(!got[4].containsKey("_darpanKeep_0"), "the first match is null, so absent")
    }

    @Test
    void anAbsentOrNullValueLeavesTheColumnOut() {
        List<Map> got = linesOf("absent.json", '{"records":[{"id":"1"},{"id":"2","v":null},{"id":"3","v":""},{"id":"4","n":{"v":"deep"}}]}',
                inj("v", "GROOVY"))
        assertTrue(!got[0].containsKey("_darpanKeep_0"))
        assertTrue(!got[1].containsKey("_darpanKeep_0"))
        assertEquals("", got[2].get("_darpanKeep_0"))
        assertTrue(!got[3].containsKey("_darpanKeep_0"), "only top-level keys")
    }

    @Test
    void everyLineCarriesItsRowOrdinal() {
        List<Map> got = linesOf("rows.json", '{"records":[{"id":"a"},"skip",{"id":"b"},7,{"id":"c","v":1}]}',
                [[column: "_darpanKeep_0", field: "v", text: "GROOVY"] as Map, [column: "_darpanKeep_1", field: "v", text: "FACT"] as Map])
        assertEquals([0, 1, 2], got.collect { ((Number) it.get(KeepRules.ROW_COLUMN)).intValue() })
        assertEquals("1", got[2].get("_darpanKeep_0"))
        assertEquals("1", got[2].get("_darpanKeep_1"))
    }

    @Test
    void theSavedReadSchemaGainsTheInjectedColumns() {
        StructType document = new StructType().add("records", DataTypes.createArrayType(new StructType().add("id", DataTypes.StringType)))
        List<Map> infos = paths('$.records[*].id')
        StructType plain = JsonRecordLines.recordSchema(document, infos, null)
        assertEquals(["id"], plain.fieldNames() as List)
        StructType with = JsonRecordLines.recordSchema(document, infos, inj("v", "GROOVY"))
        assertEquals(["id", "_darpanKeep_0", "_darpanRow"], with.fieldNames() as List)
        assertEquals(DataTypes.StringType, with.apply("_darpanKeep_0").dataType())
        assertEquals(DataTypes.LongType, with.apply("_darpanRow").dataType())
        assertNull(JsonRecordLines.recordSchema(null, infos, inj("v", "GROOVY")))
    }

    @Test
    void aPaddedStringKeepsItsPaddingAndMatchesWhatEvaluateCompares() {
        List<Map> got = linesOf("pad.json", '{"records":[{"id":"1","v":"  Ab c \\t"}]}', inj("v", "GROOVY"))
        assertEquals("  Ab c \t", got[0].get("_darpanKeep_0"))
        Map parsed = (Map) ((List) ((Map) new JsonSlurper().parseText('{"records":[{"v":"  Ab c \\t"}]}')).get("records"))[0]
        assertEquals(ValueSupport.normalize(parsed.get("v")), ((String) got[0].get("_darpanKeep_0")).trim())
    }

    @Test
    void aDoubleInsideAContainerIsPinnedByLiteral() {
        String json = '{"records":[{"id":"1","v":{"a":24.00,"b":[1.0E+7,2.50]}}]}'
        assertEquals("{a=24.00, b=[1.0E+7, 2.50]}", linesOf("dbl.json", json, inj("v", "GROOVY"))[0].get("_darpanKeep_0"))
        assertEquals('{"a":24.00,"b":[1.0E+7,2.50]}', linesOf("dbl2.json", json, inj("v", "FACT"))[0].get("_darpanKeep_0"))
        Object inMemory = ((Map) ((List) ((Map) new JsonSlurper().setType(JsonParserType.CHARACTER_SOURCE).parseText(json)).get("records"))[0]).get("v")
        assertEquals(ValueSupport.normalize(inMemory), linesOf("dbl3.json", json, inj("v", "GROOVY"))[0].get("_darpanKeep_0"))
    }

    @Test
    void thePaddedKeyBeforeThePlainKeyWinsInRecordOrder() {
        String json = '{"records":[{"id":"1"," status ":"first","status":"second"}]}'
        assertEquals("first", linesOf("order.json", json, inj("status", "GROOVY"))[0].get("_darpanKeep_0"))
        Map row = (Map) ((List) ((Map) new JsonSlurper().parseText(json)).get("records"))[0]
        assertEquals(ValueSupport.normalize(row.find { k, v -> ValueSupport.normalize(k) == "status" }?.value), "first")
    }
}
