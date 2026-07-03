package darpan.reconciliation.core

import org.apache.spark.sql.types.ArrayType
import org.apache.spark.sql.types.DataType
import org.apache.spark.sql.types.DataTypes
import org.apache.spark.sql.types.StructField
import org.apache.spark.sql.types.StructType
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Audit 2026-06-11 #25 — unit-locks the Draft-07 JSON Schema -> Spark StructType converter that lets
 * the (opt-in) ingest path skip per-run schema inference. Pure type construction, no SparkSession.
 */
class SparkReadSchemaSupportTests {

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
