import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import jsonschema.common.JsonSchemaConstants
import jsonschema.common.JsonSchemaInferenceUtil
import jsonschema.common.JsonSchemaUtil
import org.slf4j.LoggerFactory

import static darpan.common.ValueSupport.normalizeBlankToNull
import static darpan.common.ValueSupport.normalizeBool

def logger = LoggerFactory.getLogger("darpan.jsonschema.CreateFromJson")

if (!jsonFile) {
    logger.error("createJsonSchemaFromJson: jsonFile is missing!")
    throw new IllegalArgumentException("jsonFile is required")
}
logger.info("createJsonSchemaFromJson called. File: ${jsonFile.getName()}, Size: ${jsonFile.getSize()}")

boolean overwriteMode = normalizeBool(overwrite, false)
boolean strictMode = normalizeBool(strict, false)

ObjectMapper mapper = new ObjectMapper()
mapper.configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
JsonNode jsonData = jsonFile.getInputStream().withCloseable { stream ->
    mapper.readTree(stream)
}
if (jsonData == null) {
    throw new IllegalArgumentException("jsonFile is empty")
}

Map schemaMap = JsonSchemaInferenceUtil.buildSchema(jsonData, strictMode)
schemaMap[JsonSchemaConstants.SCHEMA_KEYWORD] = JsonSchemaConstants.DRAFT_07_URL
String schemaTitle = normalizeBlankToNull(schemaName)
if (schemaTitle) schemaMap.title = schemaTitle

String schemaJson = mapper.writeValueAsString(schemaMap)

String nameToSave = schemaTitle ?: jsonFile.getName()
String descriptionToSave = normalizeBlankToNull(description) ?: schemaTitle ?: nameToSave

String finalName = JsonSchemaUtil.generateUniqueSchemaName(ec, nameToSave, overwriteMode)
nameToSave = finalName

def existingSchema = ec.entity.find(JsonSchemaConstants.JSON_SCHEMA_ENTITY_NAME)
        .condition("schemaName", finalName)
        .one()

if (existingSchema) {
    existingSchema.schemaText = schemaJson
    if (description) existingSchema.description = description
    existingSchema.lastUpdatedStamp = ec.user.nowTimestamp
    existingSchema.update()
    jsonSchemaId = existingSchema.jsonSchemaId
} else {
    def newSchema = ec.entity.makeValue(JsonSchemaConstants.JSON_SCHEMA_ENTITY_NAME)
    newSchema.schemaName = nameToSave
    newSchema.schemaText = schemaJson
    newSchema.description = descriptionToSave
    newSchema.statusId = "Active"
    newSchema.createdDate = ec.user.nowTimestamp
    newSchema.lastUpdatedStamp = ec.user.nowTimestamp
    newSchema.create()
    jsonSchemaId = newSchema.jsonSchemaId
}

filename = nameToSave
schemaName = nameToSave
schemaDataManagerLocation = JsonSchemaUtil.persistSchemaText(ec, nameToSave, schemaJson)

logger.info("Saved generated JSON schema ${filename} to DB with ID ${jsonSchemaId}")
