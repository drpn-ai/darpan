import com.fasterxml.jackson.databind.ObjectMapper
import jsonschema.common.JsonSchemaUtil
import org.slf4j.LoggerFactory

def logger = LoggerFactory.getLogger("darpan.jsonschema.ResolveJsonPath")

if (!key) throw new IllegalArgumentException("key is required")

String keyName = key.toString().trim()
if (!keyName) throw new IllegalArgumentException("key is required")

if (keyName.startsWith('$')) {
    resolvedJsonPath = keyName
    matchingPaths = [keyName]
    found = true
    return
}

String schemaJsonString = JsonSchemaUtil.loadSchemaText(ec, jsonSchemaId, (filename ?: schemaFileName))
if (!schemaJsonString) {
    throw new IllegalArgumentException("Schema not found for provided ID or Name")
}

ObjectMapper mapper = new ObjectMapper()
Object rootSchema = mapper.readValue(schemaJsonString, Object.class)

final int MAX_REF_DEPTH = 16

def resolveRef
resolveRef = { Object root, String ref, int depth ->
    if (depth > MAX_REF_DEPTH) return null
    if (!ref || !ref.startsWith("#/")) return null
    Object current = root
    for (String part : ref.substring(2).split("/")) {
        String decoded = part.replace("~1", "/").replace("~0", "~")
        if (!(current instanceof Map)) return null
        current = current[decoded]
    }
    if (current instanceof Map && current['$ref'] instanceof String) {
        return resolveRef(root, current['$ref'] as String, depth + 1)
    }
    return current
}

def appendProperty = { String basePath, String prop ->
    basePath == "\$" ? "\$.${prop}" : "${basePath}.${prop}"
}

List<String> matching = []
Set<Integer> visited = new HashSet<>()

def collectPaths
collectPaths = { Object node, String currentPath ->
    if (node == null) return
    Object actualNode = node
    if (node instanceof Map && node['$ref'] instanceof String) {
        Object resolved = resolveRef(rootSchema, node['$ref'] as String, 0)
        if (resolved != null) actualNode = resolved
    }

    int nodeId = System.identityHashCode(actualNode)
    if (!visited.add(nodeId)) return

    if (actualNode instanceof Map) {
        Object props = actualNode.properties instanceof Map ? actualNode.properties : null
        if (props) {
            props.each { propName, propSchema ->
                String propPath = appendProperty(currentPath, propName.toString())
                if (propName?.toString() == keyName && !matching.contains(propPath)) {
                    matching.add(propPath)
                }
                collectPaths(propSchema, propPath)
            }
        }

        Object itemsNode = actualNode.items
        if (itemsNode != null) {
            collectPaths(itemsNode, "${currentPath}[*]")
        }

        ['allOf', 'anyOf', 'oneOf'].each { altKey ->
            Object altList = actualNode[altKey]
            if (altList instanceof List) {
                altList.each { altNode -> collectPaths(altNode, currentPath) }
            }
        }
    } else if (actualNode instanceof List) {
        actualNode.each { altNode -> collectPaths(altNode, currentPath) }
    }
}

collectPaths(rootSchema, "\$")

matchingPaths = matching
found = !matching.isEmpty()
if (matching.size() == 1) resolvedJsonPath = matching[0]

String schemaReference = jsonSchemaId ?: (filename ?: schemaFileName)
logger.info("Resolved JSONPath for key ${keyName} in schema ${schemaReference}: ${matching}")
