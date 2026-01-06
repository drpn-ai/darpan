import org.slf4j.LoggerFactory
import com.fasterxml.jackson.databind.ObjectMapper

def logger = LoggerFactory.getLogger("darpan.jsonschema.UpdateText")

if (!schemaName) {
    throw new IllegalArgumentException("schemaName is required")
}
if (!jsonText) {
    throw new IllegalArgumentException("jsonText is required")
}

// Validate JSON
def mapper = new ObjectMapper()
try {
    mapper.readTree(jsonText)
} catch (Exception e) {
    throw new IllegalArgumentException("Invalid JSON content: ${e.message}")
}

// Externalize path
def schemaBaseLocation = ec.resource.properties['reconciliation.schema.location'] ?: "runtime://schemas"
def baseDirRef = ec.resource.getLocationReference(schemaBaseLocation)
if (baseDirRef == null) {
    throw new IllegalStateException("Unable to resolve schema base directory from ${schemaBaseLocation}")
}
def schemaDirRef = baseDirRef.makeDirectory("json")

String fileName = schemaName.trim()
if (!fileName.endsWith(".json")) fileName += ".json"

def schemaFileRef = schemaDirRef.getChild(fileName)
if (!schemaFileRef.getExists()) {
    throw new IllegalArgumentException("Schema file ${fileName} does not exist. Use Upload or Generate to create new schemas.")
}

schemaFileRef.putText(jsonText)

schemaFileName = fileName
schemaLocation = schemaFileRef.getLocation()

logger.info("Updated JSON schema ${schemaFileName} from text editor")
