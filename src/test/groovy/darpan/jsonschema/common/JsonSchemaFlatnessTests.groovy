package darpan.jsonschema.common

import jsonschema.common.JsonSchemaUtil
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class JsonSchemaFlatnessTests {

    @Test
    void flatObjectSchemaIsFlat() {
        assertTrue(JsonSchemaUtil.isFlatFieldList('''
            {"type":"object","properties":{"orderId":{"type":"string"},"status":{"type":"string"}}}
        '''))
    }

    @Test
    void nestedObjectSchemaIsNotFlat() {
        assertFalse(JsonSchemaUtil.isFlatFieldList('''
            {"type":"object","properties":{"orderId":{"type":"string"},
             "customer":{"type":"object","properties":{"email":{"type":"string"}}}}}
        '''))
    }

    @Test
    void arrayBearingSchemaIsNotFlat() {
        assertFalse(JsonSchemaUtil.isFlatFieldList('''
            {"type":"object","properties":{"orderId":{"type":"string"},
             "items":{"type":"array","items":{"type":"object","properties":{"sku":{"type":"string"}}}}}}
        '''))
    }

    @Test
    void unparseableSchemaTextIsNotFlat() {
        assertFalse(JsonSchemaUtil.isFlatFieldList("not json at all"))
    }

    @Test
    void blankSchemaTextIsNotFlat() {
        assertFalse(JsonSchemaUtil.isFlatFieldList(""))
        assertFalse(JsonSchemaUtil.isFlatFieldList(null))
    }
}
