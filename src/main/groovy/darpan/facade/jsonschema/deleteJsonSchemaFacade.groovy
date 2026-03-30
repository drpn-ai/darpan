import darpan.facade.common.FacadeSupport
import darpan.facade.common.PilotAccessSupport

String schemaId = FacadeSupport.normalize(jsonSchemaId)
String schemaNameValue = FacadeSupport.normalize(schemaName)

if (!schemaId && !schemaNameValue) {
    ec.message.addError("jsonSchemaId or schemaName is required")
}

if (!ec.message.hasError()) {
    def schema = null
    if (schemaId) {
        schema = ec.entity.find("darpan.reconciliation.JsonSchema")
            .condition("jsonSchemaId", schemaId)
            .useCache(false)
            .one()
    }
    if (!schema && schemaNameValue) {
        schema = ec.entity.find("darpan.reconciliation.JsonSchema")
            .condition("schemaName", schemaNameValue)
            .useCache(false)
            .one()
    }
    PilotAccessSupport.requireOwnedRecordAccess(ec, schema, "Schema not found", "Schema is not available in your customer scope.")

    if (!ec.message.hasError()) {
        ec.service.sync().name("jsonschema.JsonSchemaServices.delete#JsonSchema")
            .parameters([jsonSchemaId: schema.jsonSchemaId])
            .call()
    }

    if (!ec.message.hasError()) {
        deleted = true
        ec.message.addMessage("Schema deleted.")
    } else {
        deleted = false
    }
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
