import com.fasterxml.jackson.databind.ObjectMapper
import jsonschema.common.JsonSchemaConstants
import jsonschema.common.SchemaFlattener
import org.moqui.context.ExecutionContext

ExecutionContext ec = context.ec
ec.logger.info("flattenJsonSchema called with: ${schemaFileName}")

fieldList = []
jsonSchemaString = ""

String resolvedText = null
if (schemaFile != null && schemaFile.getSize() > 0) {
    resolvedText = schemaFile.getString("UTF-8")
} else if (jsonSchemaId) {
    def schema = ec.entity.find(JsonSchemaConstants.JSON_SCHEMA_ENTITY_NAME)
            .condition("jsonSchemaId", jsonSchemaId)
            .one()
    if (!schema) {
        ec.message.addError("Schema not found for ID: ${jsonSchemaId}")
        return
    }
    resolvedText = schema.schemaText
} else if (filename || schemaFileName || schemaName) {
    String lookupName = schemaName ?: (filename ?: schemaFileName)
    def schema = ec.entity.find(JsonSchemaConstants.JSON_SCHEMA_ENTITY_NAME)
            .condition("schemaName", lookupName)
            .one()
    if (!schema) {
        ec.message.addError("Schema not found for Name: ${lookupName}")
        return
    }
    resolvedText = schema.schemaText
    ec.logger.info("Found schema by Name '${lookupName}', text length: ${resolvedText?.length()}")
} else {
    ec.logger.warn("No jsonSchemaId, schemaName, filename, or schemaFile provided")
    return
}

if (!resolvedText || resolvedText.trim().isEmpty()) {
    ec.logger.error("schema text is empty for inputs: ID=${jsonSchemaId}, Name=${schemaName}, File=${filename}")
    ec.message.addError("Schema content is empty")
    return
}

jsonSchemaString = resolvedText

try {
    ObjectMapper mapper = new ObjectMapper()
    Map<String, Object> schemaMap = mapper.readValue(resolvedText, Map.class)
    ec.logger.info("Schema parsed, keys: ${schemaMap.keySet()}")

    fieldList = SchemaFlattener.flatten(schemaMap)

    ec.logger.info("Flattened fieldList size: ${fieldList.size()}")
} catch (Exception e) {
    ec.message.addError("Error parsing schema: ${e.message}")
    ec.logger.error("Error parsing schema", e)
}
