import darpan.facade.common.FacadeSupport

String schemaId = FacadeSupport.normalize(jsonSchemaId)
String schemaNameValue = FacadeSupport.normalize(schemaName)
String descriptionValue = description != null ? FacadeSupport.normalize(description) : null

if (!(fieldList instanceof Collection) || ((Collection) fieldList).isEmpty()) {
    ec.message.addError("fieldList is required and cannot be empty")
}

if (!ec.message.hasError()) {
    Map result = ec.service.sync().name("jsonschema.JsonSchemaServices.save#RefinedSchema")
        .parameters([
            jsonSchemaId: schemaId,
            schemaName: schemaNameValue,
            description: descriptionValue,
            fieldList: fieldList,
        ])
        .call()

    if (!ec.message.hasError()) {
        savedSchema = [
            jsonSchemaId: result.jsonSchemaId,
            schemaName: result.schemaName,
            filename: result.filename,
        ]
        ec.message.addMessage("Saved refined schema ${result.schemaName}.")
    }
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
