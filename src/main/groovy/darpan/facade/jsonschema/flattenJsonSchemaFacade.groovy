import darpan.facade.common.FacadeSupport

String schemaId = FacadeSupport.normalize(jsonSchemaId)
String schemaNameValue = FacadeSupport.normalize(schemaName)

if (!schemaId && !schemaNameValue) {
    ec.message.addError("jsonSchemaId or schemaName is required")
}

if (!ec.message.hasError()) {
    Map result = ec.service.sync().name("jsonschema.JsonSchemaServices.flatten#JsonSchema")
        .parameters([jsonSchemaId: schemaId, schemaName: schemaNameValue])
        .call()

    if (ec.message.hasError()) {
        // keep error handling from service call
    } else {
        fieldList = result.fieldList ?: []
        jsonSchemaString = result.jsonSchemaString
    }
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
