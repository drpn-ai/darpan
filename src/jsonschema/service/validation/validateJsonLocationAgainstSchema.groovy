import jsonschema.common.JsonSchemaUtil
import jsonschema.common.SchemaValidator
import org.slf4j.LoggerFactory

def logger = LoggerFactory.getLogger("darpan.jsonschema.ValidateLocation")

if (!jsonLocation) throw new IllegalArgumentException("jsonLocation is required")
if (!schemaFileName) throw new IllegalArgumentException("schemaFileName is required")

// 1. Resolve JSON File
String jsonPath = JsonSchemaUtil.resolveFilePath(ec, jsonLocation)
File jsonFile = new File(jsonPath)
if (!jsonFile.exists()) {
    throw new IllegalArgumentException("JSON file not found: ${jsonPath}")
}

// 2. Load Schema Node
def schemaNode = JsonSchemaUtil.loadSchemaNode(ec, null, schemaFileName)
if (!schemaNode) {
     throw new IllegalArgumentException("Schema not found: ${schemaFileName}")
}

// 3. Validate
logger.info("Starting validation for ${jsonPath} against ${schemaFileName}")
Map result = [:]

jsonFile.withInputStream { stream ->
    result = SchemaValidator.validateStream(stream, schemaNode, 100)
}

// 4. Return results
valid = result.valid
errorCount = result.count
errorMessages = result.errors

logger.info("Validation complete: valid=${valid}, errors=${errorCount}")

