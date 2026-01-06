import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.LoggerFactory

def logger = LoggerFactory.getLogger("darpan.jsonschema.SaveSchema")

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

if (!schemaFile) {
    throw new IllegalArgumentException("schemaFile is required")
}

boolean overwriteMode = normalizeBool(overwrite, false)

def schemaData = schemaFile.getInputStream().withCloseable { stream ->
    new JsonSlurper().parse(stream)
}
String schemaJson = JsonOutput.prettyPrint(JsonOutput.toJson(schemaData))

String baseName = normalizeBaseName(schemaName?.toString())
if (!baseName) baseName = normalizeBaseName(schemaFile.getName())
if (!baseName) baseName = "schema"

// Externalize path
def schemaBaseLocation = ec.resource.properties['reconciliation.schema.location'] ?: "runtime://schemas"
def baseDirRef = ec.resource.getLocationReference(schemaBaseLocation)
if (baseDirRef == null) {
    throw new IllegalStateException("Unable to resolve schema base directory from ${schemaBaseLocation}")
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
schemaFileRef.putText(schemaJson)

schemaFileName = fileName
schemaLocation = schemaFileRef.getLocation()

logger.info("Saved JSON schema ${schemaFileName} at ${schemaLocation}")
