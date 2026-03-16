import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.saasquatch.jsonschemainferrer.JsonSchemaInferrer
import darpan.facade.common.FacadeSupport
import jsonschema.common.SchemaFlattener

ObjectMapper mapper = new ObjectMapper()
String jsonTextValue = FacadeSupport.normalize(jsonText)

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

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
