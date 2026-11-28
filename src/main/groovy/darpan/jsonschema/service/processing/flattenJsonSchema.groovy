import com.fasterxml.jackson.databind.ObjectMapper
import jsonschema.common.JsonSchemaConstants
import jsonschema.common.SchemaFlattener
import org.moqui.context.ExecutionContext

// DAR-OPS-029 S31: flatten#JsonSchema (the facade) resolves the active tenant's schema and passes its id. The upload and
// by-name inputs only the deleted legacy screens sent are gone.
ExecutionContext ec = context.ec
fieldList = []
jsonSchemaString = ""

def schema = ec.entity.find(JsonSchemaConstants.JSON_SCHEMA_ENTITY_NAME).condition("jsonSchemaId", jsonSchemaId).one()
if (!schema) {
    ec.message.addError("Schema not found for ID: ${jsonSchemaId}")
    return
}
String resolvedText = schema.schemaText
if (!resolvedText?.trim()) {
    ec.message.addError("Schema content is empty")
    return
}
jsonSchemaString = resolvedText

try {
    fieldList = SchemaFlattener.flatten((Map<String, Object>) new ObjectMapper().readValue(resolvedText, Map.class))
} catch (Exception e) {
    ec.message.addError("Error parsing schema: ${e.message}")
    ec.logger.error("Error parsing schema", e)
}
