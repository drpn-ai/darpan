package jsonschema.common

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class JsonSchemaValidator {
    private static final Logger logger = LoggerFactory.getLogger(JsonSchemaValidator.class)
    private static final ObjectMapper DEFAULT_MAPPER = new ObjectMapper()

    /**
     * Stream-validate a JSON payload against a schema. The payload is parsed incrementally so
     * large array files do not need to be materialised in memory.
     *
     * @param input     Input stream of JSON content. Caller owns the stream lifecycle.
     * @param schema    Parsed JSON Schema node.
     * @param maxErrors Maximum number of error messages to collect (must be positive).
     * @return Map with keys {valid, errors, count, truncated}.
     */
    static Map<String, Object> validate(InputStream input, JsonNode schema, int maxErrors) {
        if (input == null) return resultFor(false, ["Input stream is null"], false)
        if (schema == null) return resultFor(false, ["Schema is null"], false)

        int errorLimit = maxErrors > 0 ? maxErrors : JsonSchemaConstants.DEFAULT_VALIDATION_ERROR_LIMIT
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
        JsonSchema rootSchema = factory.getSchema(schema)
        List<String> errors = []
        boolean valid = true
        boolean truncated = false

        JsonParser parser = null
        try {
            JsonFactory jsonFactory = DEFAULT_MAPPER.getFactory()
            parser = jsonFactory.createParser(input)

            JsonToken token = parser.nextToken()
            if (token == null) return resultFor(false, ["Empty JSON content"], false)

            if (token == JsonToken.START_ARRAY && isSchemaArray(schema)) {
                logger.debug("Validating array stream...")
                JsonNode itemsNode = schema.get("items")
                JsonSchema itemSchema = (itemsNode != null && itemsNode.isObject()) ?
                        factory.getSchema(itemsNode) :
                        null
                if (itemSchema == null) {
                    logger.warn("Schema is array but 'items' is complex or missing.")
                }

                int index = 0
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    if (errors.size() >= errorLimit) {
                        truncated = true
                        break
                    }
                    JsonNode itemNode = DEFAULT_MAPPER.readTree(parser)
                    if (itemSchema != null) {
                        Set<ValidationMessage> messages = itemSchema.validate(itemNode)
                        messages.each { msg ->
                            if (errors.size() < errorLimit) errors.add("Item ${index}: ${msg.getMessage()}")
                        }
                        if (!messages.isEmpty()) valid = false
                    }
                    index++
                }
            } else {
                logger.debug("Validating full object...")
                JsonNode node = DEFAULT_MAPPER.readTree(parser)
                Set<ValidationMessage> messages = rootSchema.validate(node)
                messages.each { msg ->
                    if (errors.size() < errorLimit) errors.add(msg.getMessage())
                }
                if (!messages.isEmpty()) {
                    valid = false
                    if (messages.size() > errorLimit) truncated = true
                }
            }
        } catch (Exception e) {
            logger.error("Validation error", e)
            errors.add("System Error: ${e.message}")
            valid = false
        } finally {
            try {
                parser?.close()
            } catch (Exception ignored) {
            }
        }

        return resultFor(valid, errors, truncated)
    }

    static boolean isSchemaArray(JsonNode schema) {
        if (schema == null) return false
        JsonNode typeNode = schema.get("type")
        if (typeNode != null) {
            if (typeNode.isTextual() && typeNode.asText() == "array") return true
            if (typeNode.isArray()) {
                for (JsonNode t : typeNode) {
                    if (t.asText() == "array") return true
                }
            }
        }
        return schema.has("items")
    }

    private static Map<String, Object> resultFor(boolean valid, List<String> errors, boolean truncated) {
        return [valid: valid, errors: errors, count: errors.size(), truncated: truncated]
    }
}
