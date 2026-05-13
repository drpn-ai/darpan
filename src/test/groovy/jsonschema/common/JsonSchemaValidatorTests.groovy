package jsonschema.common

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test

import java.nio.charset.StandardCharsets

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class JsonSchemaValidatorTests {
    private static final ObjectMapper MAPPER = new ObjectMapper()

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))
    }

    @Test
    void emptyContentReturnsCountOne() {
        JsonNode schema = MAPPER.readTree('{"type":"object"}')
        Map result = JsonSchemaValidator.validate(stream(""), schema, 100)
        assertFalse(result.valid as boolean)
        assertEquals(1, result.count)
        assertEquals(["Empty JSON content"], result.errors)
        assertFalse(result.truncated as boolean)
    }

    @Test
    void objectValidatesAndReportsMissingRequired() {
        JsonNode schema = MAPPER.readTree('{"type":"object","required":["id"],"properties":{"id":{"type":"integer"}}}')

        Map ok = JsonSchemaValidator.validate(stream('{"id":1}'), schema, 100)
        assertTrue(ok.valid as boolean)
        assertEquals(0, ok.count)

        Map bad = JsonSchemaValidator.validate(stream('{"name":"x"}'), schema, 100)
        assertFalse(bad.valid as boolean)
        assertTrue((bad.count as int) >= 1)
    }

    @Test
    void arrayStreamValidatesEachItem() {
        JsonNode schema = MAPPER.readTree('{"type":"array","items":{"type":"object","required":["id"],"properties":{"id":{"type":"integer"}}}}')
        Map result = JsonSchemaValidator.validate(
                stream('[{"id":1},{"name":"missing"},{"id":3}]'), schema, 100)
        assertFalse(result.valid as boolean)
        assertTrue((result.errors as List).any { (it as String).startsWith("Item 1:") })
    }

    @Test
    void maxErrorsCapTruncatesResults() {
        JsonNode schema = MAPPER.readTree('{"type":"array","items":{"type":"object","required":["id"],"properties":{"id":{"type":"integer"}}}}')
        StringBuilder payload = new StringBuilder("[")
        20.times { idx ->
            if (idx > 0) payload.append(",")
            payload.append('{"name":"x"}')
        }
        payload.append("]")
        Map result = JsonSchemaValidator.validate(stream(payload.toString()), schema, 5)
        assertFalse(result.valid as boolean)
        assertEquals(5, result.count)
        assertTrue(result.truncated as boolean)
    }

    @Test
    void isSchemaArrayDetectsExplicitAndImplicitForms() {
        assertTrue(JsonSchemaValidator.isSchemaArray(MAPPER.readTree('{"type":"array"}')))
        assertTrue(JsonSchemaValidator.isSchemaArray(MAPPER.readTree('{"type":["null","array"]}')))
        assertTrue(JsonSchemaValidator.isSchemaArray(MAPPER.readTree('{"items":{"type":"string"}}')))
        assertFalse(JsonSchemaValidator.isSchemaArray(MAPPER.readTree('{"type":"object"}')))
    }
}
