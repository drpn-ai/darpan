package jsonschema.common

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

class SchemaFlattenerTests {

    @Test
    void flattenSimpleObjectExposesFieldsAndDepth() {
        Map schema = [
                type      : "object",
                properties: [
                        id  : [type: "integer"],
                        name: [type: "string"]
                ],
                required  : ["id"]
        ]
        List<Map<String, Object>> rows = SchemaFlattener.flatten(schema)
        assertEquals(2, rows.size())
        assertEquals("id", rows[0].fieldPath)
        assertEquals("integer", rows[0].type)
        assertEquals(true, rows[0].required)
        assertEquals(1, rows[0].depth)
        assertEquals("name", rows[1].fieldPath)
        assertEquals(false, rows[1].required)
    }

    @Test
    void flattenNestedObjectsAndArraysIncludesItemsMarker() {
        Map schema = [
                type      : "object",
                properties: [
                        items: [
                                type : "array",
                                items: [
                                        type      : "object",
                                        properties: [sku: [type: "string"]]
                                ]
                        ]
                ]
        ]
        List<Map<String, Object>> rows = SchemaFlattener.flatten(schema)
        List<String> paths = rows.collect { it.fieldPath as String }
        assertTrue(paths.contains("items"))
        assertTrue(paths.contains("items.[0]"))
        assertTrue(paths.contains("items.[0].sku"))
    }

    @Test
    void flattenFollowsDefinitionsRef() {
        Map schema = [
                definitions: [
                        Order: [
                                type      : "object",
                                properties: [orderId: [type: "string"]]
                        ]
                ],
                type       : "object",
                properties : [
                        order: ['$ref': "#/definitions/Order"]
                ]
        ]
        List<Map<String, Object>> rows = SchemaFlattener.flatten(schema)
        List<String> paths = rows.collect { it.fieldPath as String }
        assertTrue(paths.contains("order"))
        assertTrue(paths.contains("order.orderId"))
    }

    @Test
    void flattenHandlesCombinatorByPickingFirstWithProperties() {
        Map schema = [
                type      : "object",
                properties: [
                        payload: [
                                anyOf: [
                                        [type: "string"],
                                        [type: "object", properties: [code: [type: "string"]]]
                                ]
                        ]
                ]
        ]
        List<Map<String, Object>> rows = SchemaFlattener.flatten(schema)
        List<String> paths = rows.collect { it.fieldPath as String }
        assertTrue(paths.contains("payload"))
        assertTrue(paths.contains("payload.code"))
    }
}
