import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import org.slf4j.LoggerFactory

def logger = LoggerFactory.getLogger("darpan.jsonschema.ValidateLocation")

def cleanFileName = { String rawName ->
    if (!rawName) return null
    def name = rawName.trim()
    if (!name) return null
    if (name.contains("..") || name.contains("/") || name.contains("\\")) {
        throw new IllegalArgumentException("Invalid schema file name")
    }
    return name
}

def resolvePath = { String location ->
    def rr = ec.resource.getLocationReference(location)
    if (rr != null && rr.supportsUrl()) {
        def url = rr.getUrl()
        if (url != null) {
            if ("file".equalsIgnoreCase(url.protocol)) {
                try {
                    return new File(url.toURI()).getAbsolutePath()
                } catch (Exception e) {
                    return url.getPath()
                }
            }
            return url.toString()
        }
    }
    return location
}

if (!jsonLocation) {
    throw new IllegalArgumentException("jsonLocation is required")
}
String schemaFileSafeName = cleanFileName(schemaFileName?.toString())
if (!schemaFileSafeName) { 
    throw new IllegalArgumentException("schemaFileName is required")
}

def baseDirRef = ec.resource.getLocationReference("runtime://schemas")
if (baseDirRef == null) {
    throw new IllegalStateException("Unable to resolve schema base directory")
}
def schemaDirRef = baseDirRef.makeDirectory("json")
def schemaFileRef = schemaDirRef.getChild(schemaFileSafeName)
if (schemaFileRef == null || !schemaFileRef.getExists()) {
    throw new IllegalArgumentException("Schema file not found: ${schemaFileSafeName}")
}

String jsonPath = resolvePath(jsonLocation)
if (!jsonPath) {
    throw new IllegalArgumentException("Unable to resolve jsonLocation: ${jsonLocation}")
}
File jsonFile = new File(jsonPath)
if (!jsonFile.exists()) {
    throw new IllegalArgumentException("JSON file not found: ${jsonPath}")
}

def mapper = new ObjectMapper()
def schemaNode = schemaFileRef.openStream().withCloseable { stream ->
    mapper.readTree(stream)
}

long maxFullArrayValidationBytes = 5L * 1024L * 1024L
def isSchemaArray = {
    def typeNode = schemaNode?.get("type")
    if (typeNode?.isTextual() && typeNode.asText() == "array") return true
    if (typeNode?.isArray()) {
        return typeNode.elements().any { it?.isTextual() && it.asText() == "array" }
    }
    return schemaNode?.has("items")
}

def jsonNode = jsonFile.withInputStream { stream ->
    def parser = mapper.getFactory().createParser(stream)
    try {
        def token = parser.nextToken()
        if (token == null) return null
        if (token == JsonToken.START_ARRAY) {
            if (isSchemaArray() && jsonFile.length() <= maxFullArrayValidationBytes) {
                logger.info("Validating full JSON array (size=${jsonFile.length()} bytes)")
                return mapper.readTree(parser)
            }
            def nextToken = parser.nextToken()
            def arrayNode = mapper.createArrayNode()
            if (nextToken != null && nextToken != JsonToken.END_ARRAY) {
                arrayNode.add(mapper.readTree(parser))
            }
            if (isSchemaArray()) {
                logger.info("Validating JSON sample: array root detected, using first element only (size=${jsonFile.length()} bytes)")
            }
            return arrayNode
        }
        if (token == JsonToken.START_OBJECT) {
            return mapper.readTree(parser)
        }
        return mapper.readTree(parser)
    } finally {
        parser.close()
    }
}

def schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
def schema = schemaFactory.getSchema(schemaNode)
if (jsonNode == null) {
    valid = false
    errorCount = 1
    errorMessages = ["JSON file is empty"]
    logger.info("JSON schema validation result: location=${jsonPath} schema=${schemaFileSafeName} valid=${valid} errors=${errorCount}")
    return
}
def messages = schema.validate(jsonNode)

valid = messages.isEmpty()
errorCount = messages.size()
errorMessages = messages.collect { msg ->
    def path = msg.getPath()
    path ? "${path}: ${msg.getMessage()}" : msg.getMessage()
}

logger.info("JSON schema validation result: location=${jsonPath} schema=${schemaFileSafeName} valid=${valid} errors=${errorCount}")
