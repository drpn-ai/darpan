package jsonschema.common

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

class JsonSchemaInferenceUtilTests {
    private static final ObjectMapper MAPPER = new ObjectMapper()

    @Test
    void buildSchemaForFlatObjectInfersPrimitiveTypes() {
        JsonNode node = MAPPER.readTree('{"id":1,"name":"a","active":true,"price":1.5,"missing":null}')
        Map<String, Object> schema = JsonSchemaInferenceUtil.buildSchema(node, false)
        assertEquals("object", schema.type)
        Map props = schema.properties as Map
        assertEquals("integer", props.id.type)
        assertEquals("string", props.name.type)
        assertEquals("boolean", props.active.type)
        assertEquals("number", props.price.type)
        assertEquals("null", props.missing.type)
        assertNull(schema.required)
        assertNull(schema.additionalProperties)
    }

    @Test
    void buildSchemaStrictModeAddsRequiredAndAdditionalProperties() {
        JsonNode node = MAPPER.readTree('{"id":1,"name":"a"}')
        Map<String, Object> schema = JsonSchemaInferenceUtil.buildSchema(node, true)
        assertEquals(["id", "name"], schema.required)
        assertEquals(false, schema.additionalProperties)
    }

    @Test
    void buildSchemaForArrayMergesObjectShapes() {
        JsonNode node = MAPPER.readTree('[{"id":1,"name":"a"},{"id":2,"qty":3}]')
        Map<String, Object> schema = JsonSchemaInferenceUtil.buildSchema(node, false)
        assertEquals("array", schema.type)
        Map items = schema.items as Map
        assertEquals("object", items.type)
        Map merged = items.properties as Map
        assertNotNull(merged.id)
        assertNotNull(merged.name)
        assertNotNull(merged.qty)
    }

    @Test
    void buildSchemaForEmptyArrayProducesEmptyItems() {
        JsonNode node = MAPPER.readTree('[]')
        Map<String, Object> schema = JsonSchemaInferenceUtil.buildSchema(node, false)
        assertEquals("array", schema.type)
        assertEquals([:], schema.items)
    }

    @Test
    void mergeTypeListsDropsIntegerWhenNumberPresent() {
        assertEquals(["number"], JsonSchemaInferenceUtil.mergeTypeLists(["integer", "number"]))
        assertEquals(["string"], JsonSchemaInferenceUtil.mergeTypeLists(["string", "string"]))
        assertEquals([], JsonSchemaInferenceUtil.mergeTypeLists([null, "", null]))
    }

    @Test
    void isObjectArrayPrimitivePredicates() {
        assertTrue(JsonSchemaInferenceUtil.isObjectOnly([type: "object"]))
        assertTrue(JsonSchemaInferenceUtil.isObjectOnly([properties: [a: [type: "string"]]]))
        assertFalse(JsonSchemaInferenceUtil.isObjectOnly([type: "string"]))

        assertTrue(JsonSchemaInferenceUtil.isArrayOnly([type: "array"]))
        assertTrue(JsonSchemaInferenceUtil.isArrayOnly([items: [type: "string"]]))
        assertFalse(JsonSchemaInferenceUtil.isArrayOnly([type: "object"]))

        assertTrue(JsonSchemaInferenceUtil.isPrimitiveOnly([type: "string"]))
        assertFalse(JsonSchemaInferenceUtil.isPrimitiveOnly([type: "object"]))
        assertFalse(JsonSchemaInferenceUtil.isPrimitiveOnly([:]))
    }
}
