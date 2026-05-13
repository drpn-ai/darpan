import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jsonschema.common.JsonSchemaConstants
import jsonschema.common.JsonSchemaUtil
import jsonschema.common.JsonSchemaValidator
import org.slf4j.LoggerFactory

def logger = LoggerFactory.getLogger("darpan.jsonschema.ValidateLocation")

if (!jsonLocation) throw new IllegalArgumentException("jsonLocation is required")
if (!schemaFileName) throw new IllegalArgumentException("schemaFileName is required")

def jsonRef = ec.resource.getLocationReference(jsonLocation)
if (jsonRef == null) throw new IllegalArgumentException("JSON location not resolvable: ${jsonLocation}")
if (jsonRef.supportsExists() && !jsonRef.getExists()) {
    throw new IllegalArgumentException("JSON file not found: ${jsonLocation}")
}

ObjectMapper mapper = new ObjectMapper()
String schemaText = JsonSchemaUtil.loadSchemaText(ec, null, schemaFileName)
if (!schemaText) throw new IllegalArgumentException("Schema not found: ${schemaFileName}")

JsonNode schemaNode = mapper.readTree(schemaText)

logger.info("Starting validation for ${jsonLocation} against ${schemaFileName}")
Map result = jsonRef.openStream().withCloseable { stream ->
    JsonSchemaValidator.validate(stream, schemaNode, JsonSchemaConstants.DEFAULT_VALIDATION_ERROR_LIMIT)
}

valid = result.valid
errorCount = result.count
errorMessages = result.errors

logger.info("Validation complete: valid=${valid}, errors=${errorCount}, truncated=${result.truncated}")
