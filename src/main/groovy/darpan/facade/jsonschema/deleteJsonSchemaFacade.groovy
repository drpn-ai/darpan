import darpan.facade.common.FacadeSupport

String schemaId = FacadeSupport.normalize(jsonSchemaId)
String schemaNameValue = FacadeSupport.normalize(schemaName)

if (!schemaId && !schemaNameValue) {
    ec.message.addError("jsonSchemaId or schemaName is required")
}

if (!ec.message.hasError()) {
    ec.service.sync().name("jsonschema.JsonSchemaServices.delete#JsonSchema")
        .parameters([jsonSchemaId: schemaId, schemaName: schemaNameValue])
        .call()

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
