import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import darpan.facade.common.FacadeSupport
import jsonschema.common.JsonSchemaUtil

ObjectMapper mapper = new ObjectMapper()

String jsonTextValue = FacadeSupport.normalize(jsonText)
String schemaId = FacadeSupport.normalize(jsonSchemaId)
String schemaNameValue = FacadeSupport.normalize(schemaName)

if (!jsonTextValue) ec.message.addError("jsonText is required")
if (!schemaId && !schemaNameValue) ec.message.addError("jsonSchemaId or schemaName is required")

if (!ec.message.hasError()) {
    try {
        JsonNode payloadNode = mapper.readTree(jsonTextValue)
        String schemaText = JsonSchemaUtil.loadSchemaText(ec, schemaId, schemaNameValue)
        if (!schemaText) {
            ec.message.addError("Schema not found")
        } else {
            JsonNode schemaNode = mapper.readTree(schemaText)
            def schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(schemaNode)
            Set<ValidationMessage> validationMessages = schema.validate(payloadNode)

            valid = validationMessages.isEmpty()
            errorCount = validationMessages.size()
            errorMessages = validationMessages.collect { it.message ?: it.toString() }
            if (!valid) {
                ec.message.addMessage("Validation failed with ${errorCount} errors.")
            }
        }
    } catch (Exception e) {
        ec.message.addError("Validation error: ${e.message}")
    }
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
