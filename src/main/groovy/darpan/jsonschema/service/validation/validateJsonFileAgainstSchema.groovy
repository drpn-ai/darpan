import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jsonschema.common.JsonSchemaConstants
import jsonschema.common.JsonSchemaUtil
import jsonschema.common.JsonSchemaValidator
import org.slf4j.LoggerFactory

def logger = LoggerFactory.getLogger("darpan.jsonschema.Validate")

if (!jsonFile) throw new IllegalArgumentException("jsonFile is required")

String schemaText = JsonSchemaUtil.loadSchemaText(ec, jsonSchemaId, (filename ?: schemaFileName))
if (!schemaText) throw new IllegalArgumentException("Schema not found for provided ID or filename")

ObjectMapper mapper = new ObjectMapper()
JsonNode schemaNode = mapper.readTree(schemaText)

Map result = jsonFile.getInputStream().withCloseable { stream ->
    JsonSchemaValidator.validate(stream, schemaNode, JsonSchemaConstants.DEFAULT_VALIDATION_ERROR_LIMIT)
}

valid = result.valid
errorCount = result.count
errorMessages = result.errors

logger.info("JSON schema validation result: valid=${valid} errors=${errorCount} truncated=${result.truncated}")
