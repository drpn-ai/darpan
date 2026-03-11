import groovy.json.JsonOutput
import jsonschema.common.JsonSchemaUtil
import org.slf4j.LoggerFactory

def logger = LoggerFactory.getLogger("darpan.jsonschema.SaveRefinedSchema")
Set<String> allowedTypes = ["string", "integer", "number", "boolean", "object", "array"] as Set<String>

def normalizeString = { Object value ->
    String text = value?.toString()?.trim()
    return text ? text : null
}

def normalizeBool = { Object value ->
    if (value == null) return false
    if (value instanceof Boolean) return (Boolean) value
    String text = value.toString().trim().toLowerCase()
    return ["true", "1", "y", "yes", "on"].contains(text)
}

def parsePathTokens = { String fieldPath ->
    if (!fieldPath) return null
    String remaining = fieldPath.trim()
    if (!remaining || remaining.startsWith(".") || remaining.endsWith(".") || remaining.contains("..")) return null

    List<String> tokens = []
    while (remaining) {
        if (remaining.startsWith("[")) {
            def matcher = remaining =~ /^\[(\d+)\](.*)$/
            if (!matcher.matches()) return null
            tokens.add("[${matcher[0][1]}]")
            remaining = matcher[0][2] as String
        } else {
            def matcher = remaining =~ /^([^\.\[\]]+)(.*)$/
            if (!matcher.matches()) return null
            tokens.add(matcher[0][1] as String)
            remaining = matcher[0][2] as String
        }

        if (remaining.startsWith(".")) remaining = remaining.substring(1)
    }

    return tokens ? tokens : null
}

def ensureType = { Map node, String expectedType, String fieldPath ->
    String currentType = node.type?.toString()
    if (!currentType) {
        node.type = expectedType
        return true
    }
    if (currentType != expectedType) {
        ec.message.addError("Conflicting types for '${fieldPath}': '${currentType}' vs '${expectedType}'")
        return false
    }
    return true
}

def ensureObjectNode = { Map node, String fieldPath ->
    if (!ensureType(node, "object", fieldPath)) return false
    if (!(node.properties instanceof Map)) node.properties = [:]
    return true
}

def ensureArrayNode = { Map node, String fieldPath ->
    if (!ensureType(node, "array", fieldPath)) return false
    if (!(node.items instanceof Map)) node.items = [:]
    return true
}

def markRequired = { Map objectNode, String propertyName ->
    if (!(objectNode.required instanceof List)) objectNode.required = []
    if (!objectNode.required.contains(propertyName)) objectNode.required.add(propertyName)
}

def normalizeNode
normalizeNode = { Map node ->
    String nodeType = node.type?.toString()
    if (nodeType == "object") {
        Map props = node.properties instanceof Map ? (Map) node.properties : [:]
        node.properties = props
        props.values().findAll { it instanceof Map }.each { Map child ->
            normalizeNode(child)
        }

        if (node.required instanceof Collection) {
            List requiredList = node.required.collect { it?.toString() }
                .findAll { it && props.containsKey(it) }
                .unique()
            if (requiredList) node.required = requiredList
            else node.remove("required")
        } else {
            node.remove("required")
        }

        node.remove("items")
    } else if (nodeType == "array") {
        Map itemsNode = node.items instanceof Map ? (Map) node.items : [:]
        node.items = itemsNode
        if (itemsNode) normalizeNode(itemsNode)
        node.remove("properties")
        node.remove("required")
    } else {
        node.remove("properties")
        node.remove("items")
        node.remove("required")
    }
}

if (!(fieldList instanceof Collection) || fieldList.isEmpty()) {
    ec.message.addError("fieldList is required and cannot be empty")
    return
}

List<Map> normalizedFields = []
fieldList.eachWithIndex { Object rawField, int index ->
    if (!(rawField instanceof Map)) {
        ec.message.addError("fieldList entry ${index + 1} must be an object")
        return
    }

    String fieldPath = normalizeString(rawField.fieldPath)
    if (!fieldPath) {
        ec.message.addError("fieldList entry ${index + 1} is missing fieldPath")
        return
    }

    String fieldType = normalizeString(rawField.type)?.toLowerCase()
    if (!fieldType || !allowedTypes.contains(fieldType)) {
        ec.message.addError("fieldList entry ${index + 1} has invalid type '${rawField.type}' for path '${fieldPath}'")
        return
    }

    List<String> pathTokens = parsePathTokens(fieldPath)
    if (!pathTokens) {
        ec.message.addError("fieldList entry ${index + 1} has invalid fieldPath '${fieldPath}'")
        return
    }

    normalizedFields.add([
        fieldPath: fieldPath,
        type: fieldType,
        required: normalizeBool(rawField.required),
        tokens: pathTokens
    ])
}

if (ec.message.hasError()) return

def existingSchema = null
if (jsonSchemaId) {
    existingSchema = ec.entity.find("darpan.reconciliation.JsonSchema")
        .condition("jsonSchemaId", jsonSchemaId)
        .one()
    if (!existingSchema) {
        ec.message.addError("Schema not found for ID '${jsonSchemaId}'")
        return
    }
}

String requestedSchemaName = normalizeString(schemaName)
if (!existingSchema && !requestedSchemaName) {
    ec.message.addError("schemaName is required when creating a schema")
    return
}

String finalSchemaName = existingSchema ?
    (requestedSchemaName ?: existingSchema.schemaName) :
    JsonSchemaUtil.generateUniqueSchemaName(ec, requestedSchemaName, false)

if (!finalSchemaName) {
    ec.message.addError("Unable to resolve schemaName")
    return
}

if (existingSchema && finalSchemaName != existingSchema.schemaName) {
    def duplicate = ec.entity.find("darpan.reconciliation.JsonSchema")
        .condition("schemaName", finalSchemaName)
        .one()
    if (duplicate && duplicate.jsonSchemaId != existingSchema.jsonSchemaId) {
        ec.message.addError("Schema name '${finalSchemaName}' already exists")
        return
    }
}

Map<String, Object> schemaMap = [:]

normalizedFields.each { Map row ->
    List<String> tokens = row.tokens as List<String>
    Map currentNode = schemaMap

    for (int i = 0; i < tokens.size(); i++) {
        String token = tokens[i]
        boolean isLast = i == tokens.size() - 1

        if (token.startsWith("[")) {
            if (!ensureArrayNode(currentNode, row.fieldPath as String)) return
            Map itemNode = currentNode.items as Map

            if (isLast) {
                if (!ensureType(itemNode, row.type as String, row.fieldPath as String)) return
                if ((row.type as String) == "object" && !(itemNode.properties instanceof Map)) itemNode.properties = [:]
                if ((row.type as String) == "array" && !(itemNode.items instanceof Map)) itemNode.items = [:]
            } else {
                currentNode = itemNode
            }
            continue
        }

        if (!ensureObjectNode(currentNode, row.fieldPath as String)) return
        Map properties = currentNode.properties as Map
        Map childNode = properties[token] instanceof Map ? (Map) properties[token] : [:]
        properties[token] = childNode

        if (isLast) {
            if (!ensureType(childNode, row.type as String, row.fieldPath as String)) return
            if ((row.type as String) == "object" && !(childNode.properties instanceof Map)) childNode.properties = [:]
            if ((row.type as String) == "array" && !(childNode.items instanceof Map)) childNode.items = [:]
            if (row.required) markRequired(currentNode, token)
        } else {
            currentNode = childNode
        }
    }
}

if (ec.message.hasError()) return

if (!schemaMap.type) {
    schemaMap.type = "object"
    schemaMap.properties = [:]
}
normalizeNode(schemaMap)

schemaMap['$schema'] = "http://json-schema.org/draft-07/schema#"
schemaMap.title = finalSchemaName

String schemaJson = JsonOutput.toJson(schemaMap)
String descriptionText = normalizeString(description)
boolean hasDescriptionInput = description != null

if (existingSchema) {
    existingSchema.schemaName = finalSchemaName
    existingSchema.schemaText = schemaJson
    if (hasDescriptionInput) existingSchema.description = descriptionText
    existingSchema.lastUpdatedStamp = ec.user.nowTimestamp
    existingSchema.update()
    jsonSchemaId = existingSchema.jsonSchemaId
} else {
    def newSchema = ec.entity.makeValue("darpan.reconciliation.JsonSchema")
    newSchema.schemaName = finalSchemaName
    newSchema.schemaText = schemaJson
    newSchema.description = descriptionText
    newSchema.statusId = "Active"
    newSchema.createdDate = ec.user.nowTimestamp
    newSchema.setSequencedIdPrimary()
    newSchema.create()
    jsonSchemaId = newSchema.jsonSchemaId
}

schemaName = finalSchemaName
filename = finalSchemaName

logger.info("Saved refined schema '{}' (ID: {}) with {} fields", schemaName, jsonSchemaId, normalizedFields.size())
