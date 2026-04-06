import com.fasterxml.jackson.databind.ObjectMapper
import darpan.facade.common.FacadeSupport
import darpan.facade.common.PilotAccessSupport
import jsonschema.common.JsonSchemaUtil

ObjectMapper mapper = new ObjectMapper()

String schemaNameValue = FacadeSupport.normalize(schemaName)
String schemaTextValue = FacadeSupport.normalize(schemaText)
String descriptionValue = FacadeSupport.normalize(description)
String schemaIdValue = FacadeSupport.normalize(jsonSchemaId)
boolean overwriteMode = FacadeSupport.normalizeBool(overwrite, false)

if (!schemaTextValue) ec.message.addError("schemaText is required")
if (!schemaIdValue && !schemaNameValue) ec.message.addError("schemaName is required when jsonSchemaId is not provided")

if (!ec.message.hasError()) {
    try {
        mapper.readTree(schemaTextValue)
    } catch (Exception e) {
        ec.message.addError("schemaText is invalid JSON: ${e.message}")
    }
}

if (!ec.message.hasError()) {
    def existingSchema = null
    if (schemaIdValue) {
        existingSchema = ec.entity.find("darpan.reconciliation.JsonSchema")
            .condition("jsonSchemaId", schemaIdValue)
            .useCache(false)
            .one()
        PilotAccessSupport.requireOwnedRecordAccess(ec, existingSchema,
            "Schema not found for ID '${schemaIdValue}'",
            "Schema is not available in your customer scope.")
    }

    if (!ec.message.hasError()) {
        String resolvedName
        if (existingSchema) {
            resolvedName = schemaNameValue ?: existingSchema.schemaName
            if (resolvedName != existingSchema.schemaName) {
                def duplicate = ec.entity.find("darpan.reconciliation.JsonSchema")
                    .condition("schemaName", resolvedName)
                    .useCache(false)
                    .one()
                if (duplicate && duplicate.jsonSchemaId != existingSchema.jsonSchemaId) {
                    ec.message.addError("Schema name '${resolvedName}' already exists")
                }
            }
        } else {
            resolvedName = JsonSchemaUtil.generateUniqueSchemaName(ec, schemaNameValue, overwriteMode)
        }

        if (!ec.message.hasError()) {
            if (existingSchema) {
                existingSchema.schemaName = resolvedName
                existingSchema.schemaText = schemaTextValue
                if (description != null) existingSchema.description = descriptionValue
                if (!existingSchema.statusId) existingSchema.statusId = "Active"
                existingSchema.lastUpdatedStamp = ec.user.nowTimestamp
                existingSchema.update()

                savedSchema = [
                    jsonSchemaId: existingSchema.jsonSchemaId,
                    schemaName: existingSchema.schemaName,
                    description: existingSchema.description,
                    ownerUserId: existingSchema.ownerUserId,
                    statusId: existingSchema.statusId,
                ]
            } else {
                def newSchema = ec.entity.makeValue("darpan.reconciliation.JsonSchema")
                newSchema.schemaName = resolvedName
                newSchema.schemaText = schemaTextValue
                newSchema.description = descriptionValue
                newSchema.statusId = "Active"
                newSchema.createdDate = ec.user.nowTimestamp
                PilotAccessSupport.assignOwnerOnCreate(newSchema, ec)
                newSchema.setSequencedIdPrimary()
                newSchema.create()

                savedSchema = [
                    jsonSchemaId: newSchema.jsonSchemaId,
                    schemaName: newSchema.schemaName,
                    description: newSchema.description,
                    ownerUserId: newSchema.ownerUserId,
                    statusId: newSchema.statusId,
                ]
            }

            ec.message.addMessage("Saved schema ${savedSchema.schemaName}.")
        }
    }
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
