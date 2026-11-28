package darpan.reconciliation.core

import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Encoders
import org.apache.spark.sql.Row
import org.apache.spark.sql.SparkSession
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

import static org.apache.spark.sql.functions.col
import static org.apache.spark.sql.functions.to_json
import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Characterization of the JSON id/data frame builders: record-root and exploded-array keys, single and
 * composite, with an id normalizer. The id frame is distinct; the data frame keeps every record. Spark
 * but no Moqui, so this lands in the fast unitTest pool.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompareDatasetJsonFrameTests {

    private SparkSession spark

    @BeforeAll
    void setup() {
        spark = SparkSession.builder().appName("CompareDatasetJsonFrameTests").master("local[1]")
                .config("spark.ui.enabled", "false").getOrCreate()
    }

    @AfterAll
    void cleanup() { if (spark != null) spark.stop() }

    private Dataset json(String... jsonLines) {
        return spark.read().json(spark.createDataset(jsonLines.toList(), Encoders.STRING()))
    }

    private static List<Map> paths(String... jsonPaths) {
        return jsonPaths.collect { String p -> CompareIdExpressionSupport.convertJsonPathToSpark(p) }
    }

    private static List<String> ids(Dataset df) {
        return df.select("compare_id").collectAsList().collect { Row r -> r.getString(0) }.sort()
    }

    private static final String SEP = ReconciliationServices.COMPOSITE_KEY_DELIMITER

    @Test
    void rootKeysAreTrimmedNormalizedAndDistinctInTheIdFrameOnly() {
        Dataset raw = json('{"id":" ABC ","n":1}', '{"id":"abc","n":2}', '{"id":"  ","n":3}', '{"n":4}')
        Dataset idDf = CompareDatasetSupport.buildJsonIdDf(raw, paths('$.id'), "File 1", ["CASE_FOLD"], ['$.id'])
        Dataset dataDf = CompareDatasetSupport.buildJsonDataDf(raw, paths('$.id'), "File 1", ["CASE_FOLD"], ['$.id'])

        assertEquals(["abc"], ids(idDf))
        assertEquals(["abc", "abc"], ids(dataDf))
        assertEquals(["compare_id", "data"], dataDf.columns() as List)
        List<Long> ns = dataDf.select("data.n").collectAsList().collect { Row r -> r.getLong(0) }.sort()
        assertEquals([1L, 2L], ns, "root data is the whole record")
    }

    @Test
    void arrayKeysExplodeOneRowPerItemAndCarryTheItemAsData() {
        Dataset raw = json('{"orders":[{"id":"O1","q":1},{"id":"O2","q":2},{"id":"O1","q":3}]}')
        Dataset idDf = CompareDatasetSupport.buildJsonIdDf(raw, paths('$.orders[*].id'), "File 2", [null], ['$.orders[*].id'])
        Dataset dataDf = CompareDatasetSupport.buildJsonDataDf(raw, paths('$.orders[*].id'), "File 2", [null], ['$.orders[*].id'])

        assertEquals(["O1", "O2"], ids(idDf))
        assertEquals(["O1", "O1", "O2"], ids(dataDf))
        List<Long> qs = dataDf.select("data.q").collectAsList().collect { Row r -> r.getLong(0) }.sort()
        assertEquals([1L, 2L, 3L], qs, "exploded data is the array item")
    }

    @Test
    void anEmptyArrayYieldsEmptyFramesWithTheStandardColumns() {
        Dataset raw = json('{"orders":[]}')
        Dataset idDf = CompareDatasetSupport.buildJsonIdDf(raw, paths('$.orders[*].id'), "File 2", [null], ['$.orders[*].id'])
        Dataset dataDf = CompareDatasetSupport.buildJsonDataDf(raw, paths('$.orders[*].id'), "File 2", [null], ['$.orders[*].id'])
        assertEquals(0L, idDf.count())
        assertEquals(0L, dataDf.count())
        assertTrue((idDf.columns() as List).contains("compare_id"))
        assertTrue((dataDf.columns() as List).containsAll(["compare_id", "data"]))
    }

    @Test
    void compositeKeysJoinEveryFieldAtBothLevels() {
        Dataset rootRaw = json('{"a":"x","b":"1"}', '{"a":"y","b":"2"}')
        assertEquals(["x${SEP}1".toString(), "y${SEP}2".toString()],
                ids(CompareDatasetSupport.buildJsonIdDf(rootRaw, paths('$.a', '$.b'), "File 1", [null, null], ['$.a', '$.b'])))
        assertEquals(["x${SEP}1".toString(), "y${SEP}2".toString()],
                ids(CompareDatasetSupport.buildJsonDataDf(rootRaw, paths('$.a', '$.b'), "File 1", [null, null], ['$.a', '$.b'])))

        Dataset arrayRaw = json('{"rows":[{"a":"x","b":"1"},{"a":"y","b":"2"}]}')
        List<Map> arrayPaths = paths('$.rows[*].a', '$.rows[*].b')
        assertEquals(["x${SEP}1".toString(), "y${SEP}2".toString()],
                ids(CompareDatasetSupport.buildJsonIdDf(arrayRaw, arrayPaths, "File 1", [null, null], ['$.rows[*].a', '$.rows[*].b'])))
        assertEquals(["x${SEP}1".toString(), "y${SEP}2".toString()],
                ids(CompareDatasetSupport.buildJsonDataDf(arrayRaw, arrayPaths, "File 1", [null, null], ['$.rows[*].a', '$.rows[*].b'])))
    }

    private Dataset csv(String... lines) {
        // ReconciliationServices.readCsvDataset's options, read from memory instead of a path.
        return spark.read().option("header", "true").csv(spark.createDataset(lines.toList(), Encoders.STRING())).na().fill("")
    }

    private static List<Map> csvKeys(String... columns) {
        return columns.collect { String c -> [idExpr: c, idNormalizer: null] as Map }
    }

    @Test
    void csvKeysAreTrimmedAndDistinctInTheIdFrameOnly() {
        Dataset raw = csv("id,n", " A ,1", "A,2", ",3", "B,4")
        assertEquals(["A", "B"], ids(CompareDatasetSupport.buildCsvIdDf(raw, csvKeys("id"), "File 1")))
        Dataset dataDf = CompareDatasetSupport.buildCsvDataDf(raw, csvKeys("id"), "File 1")
        assertEquals(["A", "A", "B"], ids(dataDf), "a blank single-field key leaves the frame (S01b F-E3)")
        assertEquals(["compare_id", "data"], dataDf.columns() as List)
        assertEquals(["1", "2", "4"], dataDf.select("data.n").collectAsList().collect { Row r -> r.getString(0) }.sort(),
                "csv data is the whole row")
    }

    @Test
    void csvCompositeKeysJoinEveryFieldAndABlankFieldFailsLoudly() {
        Dataset raw = csv("a,b", "x,1", "y,2")
        List<String> expected = ["x${SEP}1".toString(), "y${SEP}2".toString()]
        assertEquals(expected, ids(CompareDatasetSupport.buildCsvIdDf(raw, csvKeys("a", "b"), "File 1")))
        assertEquals(expected, ids(CompareDatasetSupport.buildCsvDataDf(raw, csvKeys("a", "b"), "File 1")))
        assertThrows(IllegalArgumentException) { CompareDatasetSupport.buildCsvIdDf(csv("a,b", "x,"), csvKeys("a", "b"), "File 1") }
        assertThrows(IllegalArgumentException) { CompareDatasetSupport.buildCsvDataDf(csv("a,b", "x,"), csvKeys("a", "b"), "File 1") }
    }

    @Test
    void aMissingCsvKeyColumnNamesTheColumnsThatExist() {
        def e = assertThrows(IllegalArgumentException) {
            CompareDatasetSupport.buildCsvIdDf(csv("orderId,n", "A,1"), csvKeys("id"), "OMS")
        }
        assertTrue(e.message.contains("OMS: CSV compare source expected column 'id'"), e.message)
        assertTrue(e.message.contains("[orderId, n]"), e.message)
    }

    @Test
    void aNumberAndItsTextGiveTheSameKeyOnEitherSide() {
        // S01b I5: one normalisation for both sides, whatever the source type: cast to text, then trim.
        List<String> jsonKeys = ids(CompareDatasetSupport.buildJsonIdDf(json('{"id":7}', '{"id":7007}'), paths('$.id'), "File 1", [null], ['$.id']))
        List<String> csvKeys = ids(CompareDatasetSupport.buildCsvIdDf(csv("id", " 7 ", "007"), csvKeys("id"), "File 2"))
        assertEquals(["7", "7007"], jsonKeys)
        assertEquals(["007", "7"], csvKeys, "leading zeros are not stripped: 007 and 7 are different keys")
        assertEquals(["ab"], ids(CompareDatasetSupport.buildJsonIdDf(json('{"id":"Ab"}'), paths('$.id'), "File 1", ["CASE_FOLD"], ['$.id'])))
        assertEquals(["ab"], ids(CompareDatasetSupport.buildCsvIdDf(csv("id", "aB "), [[idExpr: "id", idNormalizer: "CASE_FOLD"]], "File 2")))
    }

    // DAR-BE-185 (moved from SparkReadSchemaSupportTests with the saved read schema, S01b F-E2). Spark 4 turns ANSI mode on,
    // where a bad cast throws instead of yielding null. Ingest reads JSON with inference only, which never coerces: a
    // non-number in a number-looking column keeps its text, and the only cast on the compare path is to string (total).

    @Test
    void numbersAndBooleansCompareAsTheirText() {
        Row inferred = json('{"compare_id":"A","amount":1.50,"qty":3,"paid":true}').select(
                CompareDatasetSupport.applyIdNormalizer(col("amount"), null),
                CompareDatasetSupport.applyIdNormalizer(col("qty"), null),
                CompareDatasetSupport.applyIdNormalizer(col("paid"), null)).collectAsList().get(0)
        // Pinned from Spark 3.5.1: the text a value compares as. Money read as a double: DAR-BE-416.
        assertEquals(["1.5", "3", "true"], (0..2).collect { inferred.get(it) })
    }

    @Test
    void aCompareOverMessyRowsStillFinishesAndKeepsTheBadValuesAsText() {
        Dataset raw1 = json('{"id":" A ","qty":"abc","amount":""}', '{"id":"B","qty":"1","amount":"x"}')
        Dataset raw2 = json('{"id":"a","qty":3,"amount":1.50}', '{"id":7,"qty":-1,"amount":0}')
        Dataset id1 = CompareDatasetSupport.buildJsonIdDf(raw1, paths('$.id'), "File 1", ["CASE_FOLD"], ['$.id'])
        Dataset id2 = CompareDatasetSupport.buildJsonIdDf(raw2, paths('$.id'), "File 2", ["CASE_FOLD"], ['$.id'])
        Dataset data1 = CompareDatasetSupport.buildJsonDataDf(raw1, paths('$.id'), "File 1", ["CASE_FOLD"], ['$.id'])
        Dataset data2 = CompareDatasetSupport.buildJsonDataDf(raw2, paths('$.id'), "File 2", ["CASE_FOLD"], ['$.id'])

        Dataset onlyIn1 = id1.join(id2, id1.col("compare_id").equalTo(id2.col("compare_id")), "left_anti").distinct()
        Dataset onlyIn2 = id2.join(id1, id1.col("compare_id").equalTo(id2.col("compare_id")), "left_anti").distinct()
        Dataset missing = CompareDatasetSupport.unionDatasets(
                CompareDatasetSupport.buildMissingDiffRows(data1, onlyIn1, "MISSING", "File 1", "File 2", "n"),
                CompareDatasetSupport.buildMissingDiffRows(data2, onlyIn2, "MISSING", "File 2", "File 1", "n"))
        Dataset matched = CompareDatasetSupport.buildMatchedIdDataset(id1, id2)
        Dataset pairs = CompareDatasetSupport.buildMatchedPairDataset(data1, data2, matched, "S1", "order")
                .select(col("primaryId"), to_json(col("file1")), to_json(col("file2")))

        // Pinned from Spark 3.5.1, row for row: "abc" and "" stay text, nothing became a null that could compare equal.
        assertEquals(['[MISSING,7,File 2,File 1,{"amount":0.0,"id":"7","qty":-1},n]',
                      '[MISSING,b,File 1,File 2,{"amount":"x","id":"B","qty":"1"},n]'],
                missing.collectAsList().collect { Row r -> r.toString() }.sort())
        assertEquals(['[a,{"amount":"","id":" A ","qty":"abc"},{"amount":1.5,"id":"a","qty":3}]'],
                pairs.collectAsList().collect { Row r -> r.toString() }.sort())
    }

    // OC15 (Aditi, F-E3 option a): a record whose single-field key is null or blank leaves the compare, and is counted.

    @Test
    void jsonFramesCountTheKeylessRecordsTheDataFrameLeavesOut() {
        Dataset raw = json('{"id":" ABC ","n":1}', '{"id":"abc","n":2}', '{"id":"  ","n":3}', '{"n":4}')
        Map frames = CompareDatasetSupport.buildJsonFrames(raw, paths('$.id'), "File 1", ["CASE_FOLD"], ['$.id'])
        assertEquals(["abc"], ids((Dataset) frames.idDf))
        assertEquals(["abc", "abc"], ids((Dataset) frames.dataDf))
        assertEquals(2L, frames.keylessCount, "the blank id and the absent id")
        // Same frames as the one-at-a-time builders.
        assertEquals(ids(CompareDatasetSupport.buildJsonDataDf(raw, paths('$.id'), "File 1", ["CASE_FOLD"], ['$.id'])), ids((Dataset) frames.dataDf))
    }

    @Test
    void explodedAndEmptyJsonFramesCountKeylessItems() {
        Map frames = CompareDatasetSupport.buildJsonFrames(json('{"orders":[{"id":"O1"},{"q":2},{"id":""}]}'),
                paths('$.orders[*].id'), "File 2", [null], ['$.orders[*].id'])
        assertEquals(["O1"], ids((Dataset) frames.dataDf))
        assertEquals(2L, frames.keylessCount)
        Map empty = CompareDatasetSupport.buildJsonFrames(json('{"orders":[]}'), paths('$.orders[*].id'), "File 2", [null], ['$.orders[*].id'])
        assertEquals(0L, empty.keylessCount)
        assertEquals(0L, ((Dataset) empty.idDf).count())
    }

    @Test
    void csvFramesCountKeylessRowsAndACompositeKeyStillFailsLoudly() {
        Map frames = CompareDatasetSupport.buildCsvFrames(csv("id,n", " A ,1", "A,2", ",3", "B,4"), csvKeys("id"), "File 1")
        assertEquals(["A", "B"], ids((Dataset) frames.idDf))
        assertEquals(["A", "A", "B"], ids((Dataset) frames.dataDf))
        assertEquals(1L, frames.keylessCount)
        assertThrows(IllegalArgumentException) { CompareDatasetSupport.buildCsvFrames(csv("a,b", "x,"), csvKeys("a", "b"), "File 1") }
    }

    @Test
    void theKeylessWarningNamesTheSideAndTheCount() {
        assertEquals("Compare scope 'Orders' FILE_2 (NetSuite) has 3 record(s) with a blank key; they are left out of the compare.",
                CompareDatasetSupport.keylessRecordWarning("Compare scope 'Orders' FILE_2 (NetSuite)", 3L))
    }

    @Test
    void aNumericKeyReadsTheSameInTheCompareAndInTheConcludePass() {
        // S01b I5 parity (S03a lead): one record anywhere with a decimal key makes Spark infer the whole column as a double,
        // so 7 rendered "7.0" and an 11-digit id "1.2345678901E10", while the conclude pass (ConclusionKeySupport, Groovy over
        // the parsed record) reads "7" and "12345678901": its evidence lookups missed. Both builders, the same records.
        // 1234567890123456 has 16 digits: still exact in a double (below 2^53), so it must not read "1.234567890123456E15".
        List<String> records = ['{"id":7,"n":1}', '{"id":7.5,"n":2}', '{"id":12345678901,"n":3}', '{"id":1234567890123456,"n":4}']
        List<String> spark = ids(CompareDatasetSupport.buildJsonIdDf(json(records as String[]), paths('$.id'), "File 1", [null], ['$.id']))
        List<String> groovy = records.collect { String r ->
            darpan.reconciliation.conclusion.ConclusionKeySupport.compareKey((Map) new groovy.json.JsonSlurper().parseText(r), ['$.id'], null)
        }.sort()
        assertEquals(groovy, spark)
        assertEquals(["12345678901", "1234567890123456", "7", "7.5"], spark)
        // A decimal literal reads the same in both: 7.0 is "7", 1.50 is "1.5" (Session 012's a0ba8e9e on the Groovy side).
        List<String> decimals = ['{"id":7.0}', '{"id":1.50}']
        assertEquals(decimals.collect { String r ->
            darpan.reconciliation.conclusion.ConclusionKeySupport.compareKey((Map) new groovy.json.JsonSlurper().parseText(r), ['$.id'], null)
        }.sort(), ids(CompareDatasetSupport.buildJsonIdDf(json(decimals as String[]), paths('$.id'), "File 1", [null], ['$.id'])))
        assertEquals(["1.5", "7"], ids(CompareDatasetSupport.buildJsonIdDf(json(decimals as String[]), paths('$.id'), "File 1", [null], ['$.id'])))
        // OC140: a key edged by a tab or CR is the same key in both passes (Spark trims as Java's String.trim; b76b5f77).
        List<String> edged = ['{"id":" K1\\t"}', '{"id":"K2\\r"}']
        assertEquals(edged.collect { String r ->
            darpan.reconciliation.conclusion.ConclusionKeySupport.compareKey((Map) new groovy.json.JsonSlurper().parseText(r), ['$.id'], null)
        }.sort(), ids(CompareDatasetSupport.buildJsonIdDf(json(edged as String[]), paths('$.id'), "File 1", [null], ['$.id'])))
        // An integer-only column was always read as a long: unchanged.
        assertEquals(["12345678901", "7"], ids(CompareDatasetSupport.buildJsonIdDf(json('{"id":7}', '{"id":12345678901}'), paths('$.id'), "File 1", [null], ['$.id'])))
    }

    @Test
    void aCompositeFieldHoldingTheDelimiterFailsLoudlyRatherThanForgeAnotherKey() {
        // S01b I6 (QA refute): "X<US>Y"+"Z" and "X"+"Y<US>Z" would compose the same key.
        String us = ReconciliationServices.COMPOSITE_KEY_DELIMITER
        Dataset raw = spark.createDataFrame([org.apache.spark.sql.RowFactory.create("X${us}Y".toString(), "Z"),
                                             org.apache.spark.sql.RowFactory.create("X", "Y${us}Z".toString())],
                new org.apache.spark.sql.types.StructType().add("a", "string").add("b", "string"))
        def e = assertThrows(IllegalArgumentException) {
            CompareDatasetSupport.buildJsonIdDf(raw, paths('$.a', '$.b'), "File 1", [null, null], ['$.a', '$.b'])
        }
        assertTrue(e.message.contains("must not contain the key delimiter"), e.message)
        assertThrows(IllegalArgumentException) { CompareDatasetSupport.buildCsvFrames(raw, csvKeys("a", "b"), "File 1") }
    }

    @Test
    void aBlankCompositeFieldFailsLoudlyAtBothLevels() {
        Dataset rootRaw = json('{"a":"x","b":" "}')
        assertThrows(IllegalArgumentException) {
            CompareDatasetSupport.buildJsonIdDf(rootRaw, paths('$.a', '$.b'), "File 1", [null, null], ['$.a', '$.b'])
        }
        Dataset arrayRaw = json('{"rows":[{"a":"x","b":"1"},{"a":"y"}]}')
        assertThrows(IllegalArgumentException) {
            CompareDatasetSupport.buildJsonDataDf(arrayRaw, paths('$.rows[*].a', '$.rows[*].b'), "File 1", [null, null], ['$.rows[*].a', '$.rows[*].b'])
        }
    }
}
