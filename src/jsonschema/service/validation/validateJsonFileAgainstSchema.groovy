import jsonschema.common.JsonSchemaUtil
import jsonschema.common.SchemaValidator
import org.slf4j.LoggerFactory

def logger = LoggerFactory.getLogger("darpan.jsonschema.Validate")

if (!jsonFile) {
    throw new IllegalArgumentException("jsonFile is required")
}

// 1. Load Schema
def schemaNode = JsonSchemaUtil.loadSchemaNode(ec, jsonSchemaId, (filename ?: schemaFileName))

if (!schemaNode) {
    throw new IllegalArgumentException("Schema not found for provided ID or filename")
}

// 2. Validate using Streaming Helper
// Note: This helper supports both large arrays and small objects efficiently
Map result = [:]
jsonFile.getInputStream().withCloseable { stream ->
    result = SchemaValidator.validateStream(stream, schemaNode, 100)
}

// 3. Output
valid = result.valid
errorCount = result.count
errorMessages = result.errors

logger.info("JSON schema validation result: valid=${valid} errors=${errorCount}")

