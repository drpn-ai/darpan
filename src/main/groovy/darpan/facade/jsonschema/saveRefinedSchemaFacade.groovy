import darpan.facade.common.FacadeSupport
import darpan.facade.common.PilotAccessSupport

String schemaId = FacadeSupport.normalize(jsonSchemaId)
String schemaNameValue = FacadeSupport.normalize(schemaName)
String descriptionValue = description != null ? FacadeSupport.normalize(description) : null

if (!(fieldList instanceof Collection) || ((Collection) fieldList).isEmpty()) {
    ec.message.addError("fieldList is required and cannot be empty")
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
    if (schemaId || schemaNameValue) {
        PilotAccessSupport.requireOwnedRecordAccess(ec, schema, "Schema not found", "Schema is not available in your customer scope.")
    }
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
        def savedSchemaEntity = ec.entity.find("darpan.reconciliation.JsonSchema")
            .condition("jsonSchemaId", result.jsonSchemaId)
            .useCache(false)
            .one()
        if (savedSchemaEntity != null && !PilotAccessSupport.isSuperAdmin(ec) && !savedSchemaEntity.ownerUserId) {
            PilotAccessSupport.assignOwnerOnCreate(savedSchemaEntity, ec)
            savedSchemaEntity.update()
        }
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
