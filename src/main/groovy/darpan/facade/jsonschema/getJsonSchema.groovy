import darpan.facade.common.FacadeSupport
import darpan.facade.common.PilotAccessSupport

String id = FacadeSupport.normalize(jsonSchemaId)
String name = FacadeSupport.normalize(schemaName)

if (!id && !name) {
    ec.message.addError("jsonSchemaId or schemaName is required")
}

def schema = null
if (!ec.message.hasError()) {
    if (id) {
        schema = ec.entity.find("darpan.reconciliation.JsonSchema")
            .condition("jsonSchemaId", id)
            .useCache(false)
            .one()
    }
    if (!schema && name) {
        schema = ec.entity.find("darpan.reconciliation.JsonSchema")
            .condition("schemaName", name)
            .useCache(false)
            .one()
    }
    PilotAccessSupport.requireOwnedRecordAccess(ec, schema, "Schema not found", "Schema is not available in your customer scope.")
}

if (!ec.message.hasError()) {
    schemaData = [
        jsonSchemaId: schema.jsonSchemaId,
        schemaName: schema.schemaName,
        description: schema.description,
        ownerUserId: schema.ownerUserId,
        schemaText: schema.schemaText,
        statusId: schema.statusId,
        createdDate: schema.createdDate,
        lastUpdatedStamp: schema.lastUpdatedStamp,
    ]
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
