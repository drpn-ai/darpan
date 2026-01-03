import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import groovy.json.JsonSlurper
import org.slf4j.LoggerFactory

def logger = LoggerFactory.getLogger("darpan.jsonschema.Validate")

def cleanFileName = { String rawName ->
    if (!rawName) return null
    def name = rawName.trim()
    if (!name) return null
    if (name.contains("..") || name.contains("/") || name.contains("\\")) {
        throw new IllegalArgumentException("Invalid schema file name")
    }
    return name
}

if (!jsonFile) {
    throw new IllegalArgumentException("jsonFile is required")
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

def mapper = new ObjectMapper()
def schemaData = schemaFileRef.openStream().withCloseable { stream ->
    new JsonSlurper().parse(stream)
}
def jsonData = jsonFile.getInputStream().withCloseable { stream ->
    new JsonSlurper().parse(stream)
}
def schemaNode = mapper.valueToTree(schemaData)
def jsonNode = mapper.valueToTree(jsonData)

def schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
def schema = schemaFactory.getSchema(schemaNode)
def messages = schema.validate(jsonNode)

valid = messages.isEmpty()
errorCount = messages.size()
errorMessages = messages.collect { msg ->
    def path = msg.getPath()
    path ? "${path}: ${msg.getMessage()}" : msg.getMessage()
}

logger.info("JSON schema validation result: schema=${schemaFileSafeName} valid=${valid} errors=${errorCount}")
