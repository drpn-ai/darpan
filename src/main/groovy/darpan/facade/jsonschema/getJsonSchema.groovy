import darpan.facade.common.FacadeSupport
import darpan.facade.common.PilotAccessSupport

String id = FacadeSupport.normalize(jsonSchemaId)
String name = FacadeSupport.normalize(schemaName)
String systemEnumTypeId = "DarpanSystemSource"

String jsonSchemaEntityName = "darpan.reconciliation.JsonSchema"
String jsonSchemaGroupName = ec.entity.getEntityGroupName(jsonSchemaEntityName) ?: "default"
def jsonSchemaDatasourceFactory = ec.entity.getDatasourceFactory(jsonSchemaGroupName)
jsonSchemaDatasourceFactory?.checkAndAddTable(jsonSchemaEntityName)

def findSystemEnum = { Object rawSystemId, boolean useCache = true ->
    String normalized = FacadeSupport.normalize(rawSystemId)
    if (!normalized) return null

    return ec.entity.find("moqui.basic.Enumeration")
        .condition("enumTypeId", systemEnumTypeId)
        .condition("enumId", normalized)
        .useCache(useCache)
        .one()
}

def resolveSystemLabel = { Object rawSystemId, String fallback = null, boolean useCache = true ->
    String normalized = FacadeSupport.normalize(rawSystemId)
    if (!normalized) return fallback

    def systemEnum = findSystemEnum(rawSystemId, useCache)
    if (systemEnum == null) return fallback ?: normalized

    return FacadeSupport.enumLabel(systemEnum)
}

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
    String systemEnumId = FacadeSupport.normalize(schema.systemEnumId)
    schemaData = [
        jsonSchemaId: schema.jsonSchemaId,
        schemaName: schema.schemaName,
        description: schema.description,
        systemEnumId: systemEnumId,
        systemLabel: resolveSystemLabel(systemEnumId, null, true),
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
