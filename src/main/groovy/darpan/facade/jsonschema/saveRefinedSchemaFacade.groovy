import darpan.facade.common.FacadeSupport
import darpan.facade.common.PilotAccessSupport

String schemaId = FacadeSupport.normalize(jsonSchemaId)
String schemaNameValue = FacadeSupport.normalize(schemaName)
String descriptionValue = description != null ? FacadeSupport.normalize(description) : null
String requestedSystemEnumIdValue = systemEnumId != null ? FacadeSupport.normalize(systemEnumId) : null
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
            systemEnumId: requestedSystemEnumIdValue,
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
        String savedSystemEnumId = FacadeSupport.normalize(savedSchemaEntity?.systemEnumId)
        savedSchema = [
            jsonSchemaId: result.jsonSchemaId,
            schemaName: result.schemaName,
            filename: result.filename,
            systemEnumId: savedSystemEnumId,
            systemLabel: resolveSystemLabel(savedSystemEnumId, null, true),
        ]
        ec.message.addMessage("Saved refined schema ${result.schemaName}.")
    }
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
