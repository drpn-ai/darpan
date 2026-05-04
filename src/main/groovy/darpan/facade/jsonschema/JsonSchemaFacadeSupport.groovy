package darpan.facade.jsonschema

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import com.saasquatch.jsonschemainferrer.JsonSchemaInferrer
import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import jsonschema.common.SchemaFlattener

class JsonSchemaFacadeSupport {
    private static final ObjectMapper mapper = new ObjectMapper()

    static Map<String, Object> inferJsonSchemaFromText(def ec, Object jsonText) {
        String jsonTextValue = FacadeSupport.normalize(jsonText)
        String jsonSchemaString = null
        List<Map> fieldList = null

        if (!jsonTextValue) {
            ec.message.addError("jsonText is required")
        }

        if (!ec.message.hasError()) {
            try {
                JsonNode sampleJson = mapper.readTree(jsonTextValue)
                def inferrer = JsonSchemaInferrer.newBuilder()
                        .setSpecVersion(com.saasquatch.jsonschemainferrer.SpecVersion.DRAFT_07)
                        .build()

                def jsonSchemaNode = inferrer.inferForSample(sampleJson)
                Map schemaMap = mapper.convertValue(jsonSchemaNode, Map.class)

                fieldList = SchemaFlattener.flatten(schemaMap)
                jsonSchemaString = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonSchemaNode)
            } catch (Exception e) {
                ec.message.addError("Error inferring schema: ${e.message}")
            }
        }

        Map<String, Object> envelope = FacadeSupport.envelope(ec)
        return envelope + [
                fieldList       : fieldList,
                jsonSchemaString: jsonSchemaString,
        ]
    }

    static Map<String, Object> validateJsonTextAgainstSchema(def ec, Object jsonText, Object jsonSchemaId, Object schemaName) {
        String jsonTextValue = FacadeSupport.normalize(jsonText)
        String schemaId = FacadeSupport.normalize(jsonSchemaId)
        String schemaNameValue = FacadeSupport.normalize(schemaName)
        Boolean valid = null
        Integer errorCount = null
        List<String> errorMessages = null

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

                TenantAccessSupport.requireTenantRecordAccess(
                        ec,
                        schemaRecord,
                        "Schema not found",
                        "Schema is not available in your active tenant."
                )
                if (!ec.message.hasError()) {
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

        Map<String, Object> envelope = FacadeSupport.envelope(ec)
        return envelope + [
                valid        : valid,
                errorCount   : errorCount,
                errorMessages: errorMessages,
        ]
    }
}
