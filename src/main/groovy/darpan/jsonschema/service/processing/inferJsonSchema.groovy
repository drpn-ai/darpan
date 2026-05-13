/*
 * Service to infer a draft JSON Schema from a JSON file and return it as a flattened list of fields
 * for the UI Wizard "Refine" step.
 */

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.saasquatch.jsonschemainferrer.JsonSchemaInferrer
import com.saasquatch.jsonschemainferrer.SpecVersion
import jsonschema.common.JsonSchemaConstants
import jsonschema.common.SchemaFlattener
import org.slf4j.LoggerFactory

def logger = LoggerFactory.getLogger("darpan.jsonschema.Infer")

if (!jsonFile) {
    ec.message.addError("No JSON file uploaded")
    return
}

try {
    ObjectMapper mapper = new ObjectMapper()
    JsonSchemaInferrer inferrer = JsonSchemaInferrer.newBuilder()
            .setSpecVersion(SpecVersion.DRAFT_07)
            .build()

    JsonNode sampleJson = null

    jsonFile.getInputStream().withCloseable { stream ->
        JsonFactory factory = mapper.getFactory()
        JsonParser parser = factory.createParser(stream)
        try {
            JsonToken token = parser.nextToken()
            if (token == null) throw new IllegalArgumentException("Empty JSON file")

            if (token == JsonToken.START_ARRAY) {
                logger.info("JSON root is Array. Sampling up to ${JsonSchemaConstants.DEFAULT_SAMPLE_SIZE_LIMIT} items...")
                ArrayNode arrayNode = mapper.createArrayNode()
                int count = 0
                while (parser.nextToken() != JsonToken.END_ARRAY && count < JsonSchemaConstants.DEFAULT_SAMPLE_SIZE_LIMIT) {
                    arrayNode.add(mapper.readTree(parser))
                    count++
                }
                sampleJson = arrayNode
                logger.info("Sampled ${count} items from array.")
            } else {
                logger.info("JSON root is Object. Reading full object (single record)...")
                sampleJson = mapper.readTree(parser)
            }
        } finally {
            parser.close()
        }
    }

    if (sampleJson == null) {
        ec.message.addError("Could not read valid JSON data from file")
        return
    }

    JsonNode jsonSchemaNode = inferrer.inferForSample(sampleJson)
    Map schemaMap = mapper.convertValue(jsonSchemaNode, Map.class)

    logger.info("Generated schema map using library. Flattening...")
    List<Map<String, Object>> flattenedFields = SchemaFlattener.flatten(schemaMap)

    resultList = flattenedFields
    jsonSchemaString = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonSchemaNode)

    logger.info("Flattened fields count: ${flattenedFields.size()}")
} catch (Exception e) {
    ec.message.addError("Error inferring schema: ${e.message}")
    logger.error("Error inferring schema", e)
}
