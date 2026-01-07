import org.moqui.context.ExecutionContext
import org.moqui.resource.ResourceReference
import groovy.json.JsonSlurper
import jsonschema.common.SchemaFlattener

ExecutionContext ec = context.ec
ec.logger.info("flattenJsonSchema called with: ${schemaFileName}")

fieldList = []
jsonSchemaString = ""

// 1. Resolve and read schema content
if (schemaFile != null && schemaFile.getSize() > 0) {
    jsonSchemaString = schemaFile.getString("UTF-8")
} else if (jsonSchemaId) {
    def schema = ec.entity.find("darpan.reconciliation.JsonSchema")
        .condition("jsonSchemaId", jsonSchemaId)
        .one()
    if (!schema) {
         ec.message.addError("Schema not found for ID: ${jsonSchemaId}")
         return
    }
    jsonSchemaString = schema.schemaText
} else if (filename || schemaFileName || schemaName) {
    String fName = schemaName ?: (filename ?: schemaFileName)
    def schema = ec.entity.find("darpan.reconciliation.JsonSchema")
        .condition("schemaName", fName)
        .one()
    if (!schema) {
         ec.message.addError("Schema not found for Name: ${fName}")
         return
    }
    jsonSchemaString = schema.schemaText
    ec.logger.info("Found schema by Name '${fName}': valid? ${schema != null}, text length: ${jsonSchemaString?.length()}")
} else {
    ec.logger.warn("No jsonSchemaId, schemaName, filename, or schemaFile provided")
    return
}

if (!jsonSchemaString || jsonSchemaString.trim().isEmpty()) {
    ec.logger.error("jsonSchemaString is empty or null for inputs: ID=${jsonSchemaId}, Name=${schemaName}, File=${filename}")
    ec.message.addError("Schema content is empty")
    return
}

try {
    def schemaMap = new JsonSlurper().parseText(jsonSchemaString)
    ec.logger.info("Schema parsed, keys: ${schemaMap.keySet()}")
    
    // 2. Flatten logic using centralized utility
    // SchemaFlattener handles recursion, $ref resolution, and complex structures (nested objects, arrays).
    // It standardizes array path notation to '[0]' which matches the Editor's expectation.
    fieldList = SchemaFlattener.flatten(schemaMap)

    ec.logger.info("Flattened fieldList size: ${fieldList.size()}")

} catch (Exception e) {
    ec.message.addError("Error parsing schema: ${e.message}")
    ec.logger.error("Error parsing schema", e)
}
