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
