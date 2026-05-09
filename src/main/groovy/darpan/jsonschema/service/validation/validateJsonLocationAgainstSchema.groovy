import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import jsonschema.common.JsonSchemaUtil
import org.slf4j.LoggerFactory

def logger = LoggerFactory.getLogger("darpan.jsonschema.ValidateLocation")

if (!jsonLocation) throw new IllegalArgumentException("jsonLocation is required")
if (!schemaFileName) throw new IllegalArgumentException("schemaFileName is required")

// --------------------------------------------------------------------------------
// Helper: stream validation logic kept local to avoid loading large JSON payloads at once.
// --------------------------------------------------------------------------------
ObjectMapper mapper = new ObjectMapper()

def isSchemaArray = { JsonNode sNode ->
    JsonNode typeNode = sNode.get("type")
    if (typeNode != null) {
        if (typeNode.isTextual() && typeNode.asText() == "array") return true
        if (typeNode.isArray()) {
                for (JsonNode t : typeNode) {
                    if (t.asText() == "array") return true
                }
        }
    }
    // Implicit array if "items" keyword exists
    return sNode.has("items")
}

def validateStream = { InputStream inputStream, JsonNode sNode, long maxErrors ->
    JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
    JsonSchema rootSchema = factory.getSchema(sNode)
    
    List<String> errors = []
    boolean isValid = true
    
    try {
        JsonFactory jsonFactory = mapper.getFactory()
        JsonParser parser = jsonFactory.createParser(inputStream)
        
        JsonToken token = parser.nextToken()
        if (token == null) {
            return [valid: false, errors: ["Empty JSON content"]]
        }
        
        if (token == JsonToken.START_ARRAY && isSchemaArray(sNode)) {
            logger.info("Validating array stream...")
            JsonNode itemsNode = sNode.get("items")
            JsonSchema itemSchema = null
            
            if (itemsNode != null && itemsNode.isObject()) {
                itemSchema = factory.getSchema(itemsNode)
            } else {
                logger.warn("Schema is array but 'items' is complex or missing.")
            }
            
            int index = 0
            while (parser.nextToken() != JsonToken.END_ARRAY && errors.size() < maxErrors) {
                JsonNode itemNode = mapper.readTree(parser)
                if (itemSchema) {
                    Set<ValidationMessage> messages = itemSchema.validate(itemNode)
                    messages.each { msg ->
                        errors.add("Item ${index}: ${msg.getMessage()}")
                    }
                    if (!messages.isEmpty()) isValid = false
                }
                index++
            }
        } else {
            // Object or non-array root
            logger.info("Validating full object...")
            JsonNode node = mapper.readTree(parser)
            Set<ValidationMessage> messages = rootSchema.validate(node)
            messages.each { msg ->
                errors.add(msg.getMessage())
            }
            if (!messages.isEmpty()) isValid = false
        }
        
        parser.close()
        
    } catch (Exception e) {
        logger.error("Validation error", e)
        errors.add("System Error: ${e.message}")
        isValid = false
    }
    
    return [valid: isValid, errors: errors, count: errors.size()]
}

// --------------------------------------------------------------------------------
// Main Execution Flow
// --------------------------------------------------------------------------------

// 1. Resolve JSON File
String jsonPath = JsonSchemaUtil.resolveFilePath(ec, jsonLocation)
File jsonFile = new File(jsonPath)
if (!jsonFile.exists()) {
    throw new IllegalArgumentException("JSON file not found: ${jsonPath}")
}

// 2. Load Schema Node
String schemaText = JsonSchemaUtil.loadSchemaText(ec, null, schemaFileName)
if (!schemaText) {
     throw new IllegalArgumentException("Schema not found: ${schemaFileName}")
}
JsonNode schemaNode = mapper.readTree(schemaText)

// 3. Validate
logger.info("Starting validation for ${jsonPath} against ${schemaFileName}")
Map result = [:]

jsonFile.withInputStream { stream ->
    result = validateStream(stream, schemaNode, 100)
}

// 4. Return results
valid = result.valid
errorCount = result.count
errorMessages = result.errors

logger.info("Validation complete: valid=${valid}, errors=${errorCount}")
