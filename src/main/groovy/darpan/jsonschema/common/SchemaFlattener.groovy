package jsonschema.common

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Flattens a JSON Schema structure into a list of fields suitable for tabular UI display.
 */
class SchemaFlattener {
    private static final Logger logger = LoggerFactory.getLogger(SchemaFlattener.class)
    private static final String ROOT_MARKER = "#ROOT"

    /**
     * Flattens a JSON Schema Map (Jackson converted or native) into a list of Map representations.
     * @param schemaMap The root schema map.
     * @return List of Maps representing fields.
     */
    static List<Map<String, Object>> flatten(Map<String, Object> schemaMap) {
        List<Map<String, Object>> result = []
        Map<String, Object> definitions = (schemaMap?.definitions instanceof Map) ?
                (Map<String, Object>) schemaMap.definitions :
                [:]

        flattenRecursive(result, ROOT_MARKER, "", schemaMap, 0, true, definitions)
        return result
    }

    private static void flattenRecursive(List<Map<String, Object>> result, String name, String path,
                                         Map<String, Object> node, int depth, boolean isRequired,
                                         Map<String, Object> definitions) {
        if (depth > JsonSchemaConstants.MAX_SCHEMA_DEPTH) {
            logger.warn("Max schema depth reached at ${path}.${name}")
            return
        }
        if (node == null) return

        Object refValue = node.get('$ref')
        if (refValue instanceof String && refValue.startsWith("#/definitions/")) {
            String defName = refValue.substring("#/definitions/".length())
            Map<String, Object> defNode = definitions[defName] as Map<String, Object>
            if (defNode != null) {
                flattenRecursive(result, name, path, defNode, depth, isRequired, definitions)
                return
            }
        }

        String displayPath = path ? "${path}.${name}" : name
        if (name == ROOT_MARKER) displayPath = ""

        if (name != ROOT_MARKER) {
            String type = node.type
            if (type == null && node.anyOf) type = "any"
            result.add([
                    fieldPath: displayPath,
                    fieldName: name,
                    type: type ?: "string",
                    required: isRequired,
                    depth: depth,
                    indentLevel: depth
            ])
        }

        Map<String, Object> propertiesToParse = pickProperties(node)
        if (propertiesToParse != null) {
            propertiesToParse.each { k, v ->
                boolean childRequired = node.required instanceof List && node.required.contains(k)
                if (v instanceof Map) {
                    flattenRecursive(result, k as String, displayPath, (Map<String, Object>) v,
                            depth + 1, childRequired, definitions)
                }
            }
        }

        Object items = node.items
        if (items instanceof Map) {
            flattenRecursive(result, "[0]", displayPath, (Map<String, Object>) items,
                    depth + 1, false, definitions)
        } else if (items instanceof List && !items.isEmpty()) {
            Object first = items[0]
            if (first instanceof Map) {
                flattenRecursive(result, "[0]", displayPath, (Map<String, Object>) first,
                        depth + 1, false, definitions)
            }
        }
    }

    private static Map<String, Object> pickProperties(Map<String, Object> node) {
        if (node.properties instanceof Map) return (Map<String, Object>) node.properties

        String combinator = ['anyOf', 'oneOf', 'allOf'].find { node[it] instanceof List }
        if (combinator == null) return null

        List options = (List) node[combinator]
        Object validOption = options.find { it instanceof Map && it.properties instanceof Map }
        return validOption ? (Map<String, Object>) ((Map) validOption).properties : null
    }
}
