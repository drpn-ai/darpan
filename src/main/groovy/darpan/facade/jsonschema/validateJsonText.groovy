import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import darpan.facade.common.FacadeSupport
import darpan.facade.common.PilotAccessSupport

ObjectMapper mapper = new ObjectMapper()

String jsonTextValue = FacadeSupport.normalize(jsonText)
String schemaId = FacadeSupport.normalize(jsonSchemaId)
String schemaNameValue = FacadeSupport.normalize(schemaName)

if (!jsonTextValue) ec.message.addError("jsonText is required")
if (!schemaId && !schemaNameValue) ec.message.addError("jsonSchemaId or schemaName is required")

if (!ec.message.hasError()) {
    try {
        JsonNode payloadNode = mapper.readTree(jsonTextValue)
        def schemaRecord = null
        if (schemaId) {
            schemaRecord = ec.entity.find("darpan.reconciliation.JsonSchema")
                .condition("jsonSchemaId", schemaId)
                .useCache(false)
                .one()
        }
        if (!schemaRecord && schemaNameValue) {
            schemaRecord = ec.entity.find("darpan.reconciliation.JsonSchema")
                .condition("schemaName", schemaNameValue)
                .useCache(false)
                .one()
        }
        PilotAccessSupport.requireCompanyRecordAccess(ec, schemaRecord, "Schema not found", "Schema is not available in your active company.")
        if (ec.message.hasError()) {
            // error already recorded
        } else {
            JsonNode schemaNode = mapper.readTree(schemaRecord.schemaText)
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
