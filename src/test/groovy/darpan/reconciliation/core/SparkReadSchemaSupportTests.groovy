package darpan.reconciliation.core

import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Encoders
import org.apache.spark.sql.Row
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types.ArrayType
import org.apache.spark.sql.types.DataType
import org.apache.spark.sql.types.DataTypes
import org.apache.spark.sql.types.StructField
import org.apache.spark.sql.types.StructType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

import static org.apache.spark.sql.functions.col
import static org.apache.spark.sql.functions.to_json
import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Audit 2026-06-11 #25 — unit-locks the Draft-07 JSON Schema -> Spark StructType converter that lets
 * the (opt-in) ingest path skip per-run schema inference. The converter tests are pure type construction.
 *
 * DAR-BE-185: the messy-data tests below read real rows through a local SparkSession. Spark 4 turns ANSI
 * mode on by default, where a bad cast throws instead of yielding null; these pin what Spark 3.5.1 did with
 * a non-number in a number column, an empty number cell, and number/boolean text, so the upgrade cannot
 * turn a messy source row into a failed run.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SparkReadSchemaSupportTests {

    private SparkSession spark

    private SparkSession spark() {
        if (spark == null) {
            spark = SparkSession.builder().appName("SparkReadSchemaSupportTests").master("local[1]")
                    .config("spark.ui.enabled", "false").getOrCreate()
        }
        return spark
    }

    @AfterAll
    void cleanup() { if (spark != null) spark.stop() }

    private Dataset json(StructType schema, String... jsonLines) {
        def reader = spark().read()
        if (schema != null) reader = reader.schema(schema)
        return reader.json(spark().createDataset(jsonLines.toList(), Encoders.STRING()))
    }

    private static List<Map> paths(String... jsonPaths) {
        return jsonPaths.collect { String p -> CompareIdExpressionSupport.convertJsonPathToSpark(p) }
    }

    @Test
    void aNonNumberInAnIntegerColumnReadsAsNull() {
        StructType schema = SparkReadSchemaSupport.toReadSchema(
                '{"type":"object","properties":{"compare_id":{"type":"string"},"qty":{"type":"integer"}}}')
        List<Row> rows = json(schema, '{"compare_id":"A","qty":"abc"}').collectAsList()
        // Values pinned from Spark 3.5.1: the record survives, only the bad field is null.
        assertEquals(1, rows.size())
        assertEquals("A", rows[0].getAs("compare_id"))
        assertNull(rows[0].getAs("qty"))
    }

    @Test
    void anEmptyStringInANumberColumnReadsAsNull() {
        StructType schema = SparkReadSchemaSupport.toReadSchema(
                '{"type":"object","properties":{"compare_id":{"type":"string"},"amount":{"type":"number"}}}')
        Dataset df = spark().read().schema(schema).option("header", "true")
                .csv(spark().createDataset(["compare_id,amount", 'A,""', "B,"], Encoders.STRING()))
        List<Row> rows = df.orderBy("compare_id").collectAsList()
        // Pinned from Spark 3.5.1: a quoted-empty and a bare-empty cell under a number column both read as null.
        assertEquals(["A=null", "B=null"], rows.collect { Row r -> "${r.getAs('compare_id')}=${r.getAs('amount')}".toString() })
    }

    @Test
    void numbersAndBooleansStillCompareAsTheirText() {
        Dataset df = json(null, '{"compare_id":"A","amount":1.50,"qty":3,"paid":true}')
        Row inferred = df.select(
                CompareDatasetSupport.applyIdNormalizer(col("amount"), null),
                CompareDatasetSupport.applyIdNormalizer(col("qty"), null),
                CompareDatasetSupport.applyIdNormalizer(col("paid"), null)).collectAsList().get(0)
        StructType schema = SparkReadSchemaSupport.toReadSchema(
                '{"type":"object","properties":{"amount":{"type":"number"},"qty":{"type":"integer"},"paid":{"type":"boolean"}}}')
        Row declared = json(schema, '{"compare_id":"A","amount":1.50,"qty":3,"paid":true}').select(
                CompareDatasetSupport.applyIdNormalizer(col("amount"), null),
                CompareDatasetSupport.applyIdNormalizer(col("qty"), null),
                CompareDatasetSupport.applyIdNormalizer(col("paid"), null)).collectAsList().get(0)
        // Pinned from Spark 3.5.1: the text a value compares as, inferred or declared.
        assertEquals(["1.5", "3", "true"], (0..2).collect { inferred.get(it) })
        assertEquals(["1.5", "3", "true"], (0..2).collect { declared.get(it) })
    }

    @Test
    void aCompareOverMessyRowsStillFinishes() {
        Dataset raw1 = json(null, '{"id":" A ","qty":"abc","amount":""}', '{"id":"B","qty":"1","amount":"x"}')
        Dataset raw2 = json(null, '{"id":"a","qty":3,"amount":1.50}', '{"id":7,"qty":-1,"amount":0}')
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

        List<String> missingRows = missing.collectAsList().collect { Row r -> r.toString() }.sort()
        List<String> pairRows = pairs.collectAsList().collect { Row r -> r.toString() }.sort()
        // Pinned from Spark 3.5.1, row for row.
        assertEquals(['[MISSING,7,File 2,File 1,{"amount":0.0,"id":"7","qty":-1},n]',
                      '[MISSING,b,File 1,File 2,{"amount":"x","id":"B","qty":"1"},n]'], missingRows)
        assertEquals(['[a,{"amount":"","id":" A ","qty":"abc"},{"amount":1.5,"id":"a","qty":3}]'], pairRows)
    }

    private static DataType fieldType(StructType struct, String name) {
        StructField field = struct.fields().find { it.name() == name }
        assertNotNull(field, "expected field '${name}' in ${struct.fieldNames()}")
        return field.dataType()
    }

    @Test
    void objectRootMapsPrimitiveTypes() {
        StructType schema = SparkReadSchemaSupport.toReadSchema('''
            {"$schema":"http://json-schema.org/draft-07/schema#","type":"object","properties":{
                "order_id":{"type":"string"},
                "total":{"type":"number"},
                "qty":{"type":"integer"},
                "paid":{"type":"boolean"}}}''')
        assertNotNull(schema)
        assertEquals(DataTypes.StringType, fieldType(schema, "order_id"))
        assertEquals(DataTypes.DoubleType, fieldType(schema, "total"))
        assertEquals(DataTypes.LongType, fieldType(schema, "qty"))
        assertEquals(DataTypes.BooleanType, fieldType(schema, "paid"))
    }

    @Test
    void nestedObjectBecomesNestedStruct() {
        StructType schema = SparkReadSchemaSupport.toReadSchema(
                '{"type":"object","properties":{"id":{"type":"string"},"customer":{"type":"object","properties":{"name":{"type":"string"}}}}}')
        DataType customer = fieldType(schema, "customer")
        assertTrue(customer instanceof StructType)
        assertEquals(DataTypes.StringType, fieldType((StructType) customer, "name"))
    }

    @Test
    void arrayFieldBecomesArrayOfStruct() {
        StructType schema = SparkReadSchemaSupport.toReadSchema(
                '{"type":"object","properties":{"items":{"type":"array","items":{"type":"object","properties":{"sku":{"type":"string"}}}}}}')
        DataType items = fieldType(schema, "items")
        assertTrue(items instanceof ArrayType)
        DataType element = ((ArrayType) items).elementType()
        assertTrue(element instanceof StructType)
        assertEquals(DataTypes.StringType, fieldType((StructType) element, "sku"))
    }

    @Test
    void arrayRootResolvesToElementStruct() {
        // spark.read().json() on an array-root file yields one row per element, so the read schema is
        // the element struct.
        StructType schema = SparkReadSchemaSupport.toReadSchema(
                '{"type":"array","items":{"type":"object","properties":{"id":{"type":"string"}}}}')
        assertNotNull(schema)
        assertEquals(DataTypes.StringType, fieldType(schema, "id"))
    }

    @Test
    void nullableTypeUnionPicksNonNullType() {
        StructType schema = SparkReadSchemaSupport.toReadSchema(
                '{"type":"object","properties":{"amount":{"type":["number","null"]}}}')
        assertEquals(DataTypes.DoubleType, fieldType(schema, "amount"))
    }

    @Test
    void refResolvesAgainstDefinitions() {
        StructType schema = SparkReadSchemaSupport.toReadSchema('''
            {"type":"object","properties":{"line":{"$ref":"#/definitions/Line"}},
             "definitions":{"Line":{"type":"object","properties":{"sku":{"type":"string"}}}}}''')
        DataType line = fieldType(schema, "line")
        assertTrue(line instanceof StructType)
        assertEquals(DataTypes.StringType, fieldType((StructType) line, "sku"))
    }

    @Test
    void anyOfCombinatorWithPropertiesBecomesStruct() {
        StructType schema = SparkReadSchemaSupport.toReadSchema(
                '{"anyOf":[{"type":"object","properties":{"id":{"type":"string"}}},{"type":"null"}]}')
        assertNotNull(schema)
        assertEquals(DataTypes.StringType, fieldType(schema, "id"))
    }

    @Test
    void unusableSchemasReturnNull() {
        assertNull(SparkReadSchemaSupport.toReadSchema("not json"))
        assertNull(SparkReadSchemaSupport.toReadSchema("123"))
        assertNull(SparkReadSchemaSupport.toReadSchema('{"type":"string"}'))
        assertNull(SparkReadSchemaSupport.toReadSchema('{"type":"array","items":{"type":"string"}}'))
        assertNull(SparkReadSchemaSupport.toReadSchema('{"type":"object","properties":{}}'))
        assertNull(SparkReadSchemaSupport.toReadSchema(null))
        assertNull(SparkReadSchemaSupport.toReadSchema(""))
    }

    @Test
    void savedReadSchemaIsOptInAndDefaultsOff() {
        String previous = System.getProperty(SparkReadSchemaSupport.USE_SAVED_READ_SCHEMA_PROPERTY)
        try {
            System.clearProperty(SparkReadSchemaSupport.USE_SAVED_READ_SCHEMA_PROPERTY)
            assertEquals(false, SparkReadSchemaSupport.savedReadSchemaEnabled())
            System.setProperty(SparkReadSchemaSupport.USE_SAVED_READ_SCHEMA_PROPERTY, "true")
            assertEquals(true, SparkReadSchemaSupport.savedReadSchemaEnabled())
            System.setProperty(SparkReadSchemaSupport.USE_SAVED_READ_SCHEMA_PROPERTY, "false")
            assertEquals(false, SparkReadSchemaSupport.savedReadSchemaEnabled())
        } finally {
            if (previous == null) System.clearProperty(SparkReadSchemaSupport.USE_SAVED_READ_SCHEMA_PROPERTY)
            else System.setProperty(SparkReadSchemaSupport.USE_SAVED_READ_SCHEMA_PROPERTY, previous)
        }
    }
}
