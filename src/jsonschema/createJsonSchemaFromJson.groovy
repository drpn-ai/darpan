import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.LoggerFactory

def logger = LoggerFactory.getLogger("darpan.jsonschema.CreateFromJson")

def normalizeBool = { val, boolean defaultValue ->
    if (val == null) return defaultValue
    return val.toString().equalsIgnoreCase("true")
}

def normalizeBaseName = { String rawName ->
    if (!rawName) return null
    def name = rawName.trim()
    if (!name) return null
    name = name.replaceAll("[^A-Za-z0-9._-]", "_")
    name = name.replaceAll("(?i)\\.schema\\.json\$", "")
    name = name.replaceAll("(?i)\\.json\$", "")
    name = name.replaceAll("^\\.+", "")
    return name ?: null
}

def buildSchema
buildSchema = { Object value, boolean strictMode ->
    if (value == null) return [type: "null"]
    if (value instanceof Map) {
        Map properties = [:]
        value.each { k, v ->
            if (k == null) return
            properties[k.toString()] = buildSchema(v, strictMode)
        }
        Map schema = [type: "object", properties: properties]
        if (strictMode && properties) {
            schema.required = properties.keySet().toList()
            schema.additionalProperties = false
        }
        return schema
    }
    if (value instanceof Collection) {
        List itemSchemas = value.collect { buildSchema(it, strictMode) }
        itemSchemas = itemSchemas.findAll { it != null }
        if (itemSchemas.isEmpty()) {
            return [type: "array", items: [:]]
        }
        List uniqueSchemas = itemSchemas.unique()
        Map itemsSchema = uniqueSchemas.size() == 1 ? uniqueSchemas[0] : [anyOf: uniqueSchemas]
        return [type: "array", items: itemsSchema]
    }
    if (value instanceof Boolean) return [type: "boolean"]
    if (value instanceof Integer || value instanceof Long || value instanceof BigInteger || value instanceof Short) {
        return [type: "integer"]
    }
    if (value instanceof Number) return [type: "number"]
    return [type: "string"]
}

if (!jsonFile) {
    throw new IllegalArgumentException("jsonFile is required")
}

boolean overwriteMode = normalizeBool(overwrite, false)
boolean strictMode = normalizeBool(strict, false)

def jsonData = jsonFile.getInputStream().withCloseable { stream ->
    new JsonSlurper().parse(stream)
}

Map schemaMap = buildSchema(jsonData, strictMode)
schemaMap["\$schema"] = "http://json-schema.org/draft-07/schema#"
if (schemaName?.toString()?.trim()) schemaMap.title = schemaName.toString().trim()

String baseName = normalizeBaseName(schemaName?.toString())
if (!baseName) baseName = normalizeBaseName(jsonFile.getName())
if (!baseName) baseName = "schema"

def baseDirRef = ec.resource.getLocationReference("runtime://schemas")
if (baseDirRef == null) {
    throw new IllegalStateException("Unable to resolve schema base directory")
}
def schemaDirRef = baseDirRef.makeDirectory("json")

String fileName = "${baseName}.schema.json"
def candidateRef = schemaDirRef.getChild(fileName)
if (!overwriteMode) {
    String nameRoot = baseName
    int suffix = 1
    while (candidateRef != null && candidateRef.getExists()) {
        fileName = "${nameRoot}-${suffix}.schema.json"
        candidateRef = schemaDirRef.getChild(fileName)
        suffix++
    }
}

def schemaFileRef = schemaDirRef.makeFile(fileName)
String schemaJson = JsonOutput.prettyPrint(JsonOutput.toJson(schemaMap))
schemaFileRef.putText(schemaJson)

schemaFileName = fileName
schemaLocation = schemaFileRef.getLocation()

logger.info("Saved JSON schema ${schemaFileName} at ${schemaLocation}")
