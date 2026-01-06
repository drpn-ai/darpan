import org.moqui.context.ExecutionContext
import org.moqui.resource.ResourceReference
import groovy.json.JsonSlurper

ExecutionContext ec = context.ec
ec.logger.info("flattenJsonSchema called with: ${schemaFileName}")

fieldList = []
jsonSchemaString = ""

// 1. Resolve and read file
if (schemaFile != null && schemaFile.getSize() > 0) {
    jsonSchemaString = schemaFile.getString("UTF-8")
    // Note: We don't need a fileRef if we have the content
} else if (schemaFileName) {
    ResourceReference schemaBaseDirRef = ec.resource.getLocationReference("runtime://schemas/json")
    ResourceReference fileRef = schemaBaseDirRef.getChild(schemaFileName)

    if (fileRef == null || !fileRef.getExists()) {
        ec.message.addError("Schema file not found: ${schemaFileName}")
        ec.logger.error("Schema file not found: ${schemaFileName}")
        return
    }
    jsonSchemaString = fileRef.getText()
} else {
    ec.logger.warn("No schemaFileName or schemaFile provided")
    return
}

try {
    def schemaMap = new JsonSlurper().parseText(jsonSchemaString)
    ec.logger.info("Schema parsed, keys: ${schemaMap.keySet()}")
    
    // 2. Flatten logic
    // Recursively parse 'properties' to build dotted paths
    
    def parseProperties
    parseProperties = { Map properties, String parentPath ->
        properties.each { key, value ->
            String currentPath = parentPath ? "${parentPath}.${key}" : key
            def typeVal = value.type
            String type = typeVal instanceof List ? typeVal[0] : typeVal
            
            // Log deep structures occasionally? No, too verbose.
            
            fieldList.add([fieldPath: currentPath, type: type, required: false])
            
            if (type == 'object' && value.properties) {
                parseProperties(value.properties, currentPath)
            } else if (type == 'array' && value.items && value.items.properties) {
                parseProperties(value.items.properties, "${currentPath}[*]")
            }
        }
    }

    // Initial call
    if (schemaMap.properties) {
        ec.logger.info("Found root properties")
        parseProperties(schemaMap.properties, "")
    } else if (schemaMap.type == 'array' && schemaMap.items) {
         ec.logger.info("Found root array. items class: ${schemaMap.items.getClass().getName()}")
         if (schemaMap.items instanceof Map) {
             ec.logger.info("items is a Map")
             if (schemaMap.items.properties) {
                 ec.logger.info("Found properties in root array items")
                 parseProperties(schemaMap.items.properties, "[*]")
             } else if (schemaMap.items.anyOf) {
                 ec.logger.info("Found anyOf in root array items")
                 // Best effort: take the first one with properties
                 def validSchema = schemaMap.items.anyOf.find { it.properties }
                 if (validSchema) {
                      ec.logger.info("Found properties in anyOf schema option")
                      parseProperties(validSchema.properties, "[*]")
                 } else {
                      ec.logger.warn("No properties found in anyOf options")
                 }
             } else if (schemaMap.items.oneOf) {
                 ec.logger.info("Found oneOf in root array items")
                 def validSchema = schemaMap.items.oneOf.find { it.properties }
                 if (validSchema) {
                      ec.logger.info("Found properties in oneOf schema option")
                      parseProperties(validSchema.properties, "[*]")
                 } else {
                      ec.logger.warn("No properties found in oneOf options")
                 }
             } else if (schemaMap.items.allOf) {
                 ec.logger.info("Found allOf in root array items")
                 // allOf usually means "merge all these". 
                 // For flattening, we might want to merge them?
                 // Simple approach: parse all of them that have properties
                 schemaMap.items.allOf.each { schemaOption ->
                     if (schemaOption.properties) {
                         parseProperties(schemaOption.properties, "[*]")
                     }
                 }
             } else {
                 ec.logger.warn("root array items is Map but has no recognized structure (properties, anyOf, oneOf, allOf)")
             }
         } else if (schemaMap.items instanceof List) {
             ec.logger.info("Found list of items in root array (size: ${schemaMap.items.size()})")
             if (schemaMap.items.size() > 0) {
                 def firstItem = schemaMap.items[0]
                 if (firstItem instanceof Map && firstItem.properties) {
                      ec.logger.info("Parsing properties from first item in array")
                      parseProperties(firstItem.properties, "[*]")
                 } else {
                      ec.logger.warn("First item in items list does not have properties")
                 }
             }
         } else {
             ec.logger.warn("Unknown type for schemaMap.items: ${schemaMap.items.getClass()}")
         }
    } else if (schemaMap.items && schemaMap.items.properties) {
         // Fallback for missing 'type': 'array'
         ec.logger.info("Found root array items properties (inferred type array)")
         parseProperties(schemaMap.items.properties, "[*]")
    } else {
        ec.logger.warn("No properties found in schema root. Keys: ${schemaMap.keySet()}")
    }

    ec.logger.info("Flattened fieldList size: ${fieldList.size()}")

} catch (Exception e) {
    ec.message.addError("Error parsing schema: ${e.message}")
    ec.logger.error("Error parsing schema", e)
}
