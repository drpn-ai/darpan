/*
 * Service to infer a draft JSON Schema from a JSON file and return it as a flattened list of fields
 * for the UI Wizard "Refine" step.
 */

import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import org.slf4j.LoggerFactory
import com.saasquatch.jsonschemainferrer.JsonSchemaInferrer
import com.fasterxml.jackson.databind.ObjectMapper

def logger = LoggerFactory.getLogger("darpan.jsonschema.Infer")

// -----------------------------------------------------
// 1. HELPER FUNCTIONS
// -----------------------------------------------------

List<Map> flattenedFields = []
final int MAX_DEPTH = 50

// Helper to recursively flatten the Jackson/Saasquatch schema node for the UI
def flattenSchema
flattenSchema = { String name, String path, Map node, int depth, boolean isRequired, Map definitions ->
    
    if (depth > MAX_DEPTH) {
        logger.warn("Max depth reached at ${path}.${name}")
        return
    }

    // Handle $ref (JSON Schema Definition References)
    if (node.'$ref') {
        String ref = node.'$ref'
        // We only support local definitions for now, effectively expanding them
        if (ref.startsWith("#/definitions/")) {
            String defName = ref.substring("#/definitions/".length())
            Map defNode = definitions[defName] as Map
            if (defNode) {
                // Recursively flatten the definition in place of this node
                // We pass the same name and path, effectively "replacing" the reference with the actual content
                flattenSchema(name, path, defNode, depth, isRequired, definitions)
                return 
            } else {
                 logger.warn("Definition not found for ref: ${ref}")
            }
        }
    }

    String displayPath = path ? "${path}.${name}" : name
    if (name == "#ROOT") displayPath = "" 
    
    // Don't add #ROOT to the list, only its children
    if (name != "#ROOT") {
         String type = node.type
         // Handle array types which might be ["string", "null"] or just "string"
         if (type == null && node.anyOf) {
             type = "any" // Simplified for UI
         }
         
         flattenedFields.add([
             fieldPath: (displayPath ?: "") as String,
             fieldName: (name ?: "") as String,
             type: (type ?: "string"),
             required: (isRequired ? true : false),
             depth: (depth as int),
             indentLevel: (depth as int)
         ])
    }

    if (node.properties) {
        node.properties.each { k, v ->
            // In JSON Schema, 'required' is a list of keys on the object
            boolean childRequired = node.required?.contains(k)
            flattenSchema(k, displayPath, (Map)v, depth + 1, childRequired, definitions)
        }
    } else if (node.items) {
        // Handle array items
        if (node.items instanceof Map) {
             flattenSchema("[0]", displayPath, (Map)node.items, depth + 1, false, definitions)
        }
    }
}

// -----------------------------------------------------
// 3. MAIN EXECUTION
// -----------------------------------------------------

if (!jsonFile) {
    ec.message.addError("No JSON file uploaded")
    return
}

try {
    def jsonContent = jsonFile.getString("UTF-8")
    def mapper = new ObjectMapper()
    def jsonData = mapper.readTree(jsonContent)
    
    // 1. Generate full schema using Saasquatch Inferrer
    def inferrer = JsonSchemaInferrer.newBuilder()
                        .setSpecVersion(com.saasquatch.jsonschemainferrer.SpecVersion.DRAFT_07)
                        .build()
    
    def jsonSchemaNode = inferrer.inferForSample(jsonData)
    
    // Convert Jackson JsonNode back to Map for easy Groovy handling/flattening
    // (Or we could traverse JsonNode directly, but Map is easier with existing flatten logic)
    Map schemaMap = mapper.convertValue(jsonSchemaNode, Map.class)

    logger.info("Generated schema map using library. Flattening...")
    
    // 2. Flatten for UI
    Map definitions = schemaMap.definitions ?: [:]
    flattenSchema("#ROOT", "", schemaMap, 0, true, definitions)
    
    // DEBUG: StackOverflow workaround - print schema and skip flattening
    String schemaString = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonSchemaNode)
    logger.warn("**************** INFERRED SCHEMA (Stringified) ****************")
    logger.warn(schemaString)
    logger.warn("*************************************************************")

    // Safety check: ensure all values in the result are primitives
    flattenedFields.each { row ->
       row.each { k, v ->
           if (v != null && !(v instanceof String || v instanceof Number || v instanceof Boolean)) {
               row.put(k, v.toString())
           }
       }
    }

    logger.info("Flattened fields count: ${flattenedFields.size()}")
    
    // Return to context
    resultList = flattenedFields
    jsonSchemaString = schemaString
    
} catch (Exception e) {
    ec.message.addError("Error inferring schema: ${e.message}")
    logger.error("Error inferring schema", e)
}
