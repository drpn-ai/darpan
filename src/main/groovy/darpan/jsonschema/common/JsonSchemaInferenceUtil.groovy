package jsonschema.common

import com.fasterxml.jackson.databind.JsonNode

class JsonSchemaInferenceUtil {

    private static final List<String> PRIMITIVE_TYPES = ["string", "integer", "number", "boolean", "null"]

    /**
     * Infer a draft-07 style schema Map from a parsed JSON tree.
     * @param node     Root Jackson node to inspect.
     * @param strict   When true, emit required + additionalProperties:false for every object.
     */
    static Map<String, Object> buildSchema(JsonNode node, boolean strict) {
        return buildSchemaInternal(node, strict, 0)
    }

    private static Map<String, Object> buildSchemaInternal(JsonNode node, boolean strict, int depth) {
        if (depth > JsonSchemaConstants.MAX_SCHEMA_DEPTH) {
            return [type: "object",
                    description: "Max depth of ${JsonSchemaConstants.MAX_SCHEMA_DEPTH} reached, schema truncated"]
        }

        if (node == null || node.isNull()) return [type: "null"]
        if (node.isObject()) return objectSchema(node, strict, depth)
        if (node.isArray()) return arraySchema(node, strict, depth)
        if (node.isBoolean()) return [type: "boolean"]
        if (node.isIntegralNumber()) return [type: "integer"]
        if (node.isNumber()) return [type: "number"]
        return [type: "string"]
    }

    private static Map<String, Object> objectSchema(JsonNode node, boolean strict, int depth) {
        Map<String, Object> properties = [:]
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields()
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next()
            properties[entry.getKey()] = buildSchemaInternal(entry.getValue(), strict, depth + 1)
        }
        Map<String, Object> schema = [type: "object", properties: properties]
        if (strict && properties) {
            schema.required = properties.keySet().toList()
            schema.additionalProperties = false
        }
        return schema
    }

    private static Map<String, Object> arraySchema(JsonNode node, boolean strict, int depth) {
        List<Map<String, Object>> itemSchemas = []
        Iterator<JsonNode> elements = node.elements()
        while (elements.hasNext()) {
            itemSchemas << buildSchemaInternal(elements.next(), strict, depth + 1)
        }
        itemSchemas = itemSchemas.findAll { it != null }
        if (itemSchemas.isEmpty()) return [type: "array", items: [:]]

        if (!strict) {
            Map<String, Object> mergedItems = mergeSchemaList(itemSchemas)
            if (mergedItems != null) return [type: "array", items: mergedItems]
        }

        List<Map<String, Object>> uniqueSchemas = itemSchemas.unique()
        Map<String, Object> itemsSchema = uniqueSchemas.size() == 1 ?
                uniqueSchemas[0] :
                [anyOf: uniqueSchemas]
        return [type: "array", items: itemsSchema]
    }

    /**
     * Merge a list of inferred schemas into a single schema, attempting to keep nested object
     * properties together and collapsing arrays. Returns null when there is nothing to merge.
     */
    static Map<String, Object> mergeSchemaList(List schemas) {
        List flattened = []
        schemas?.findAll { it != null }?.each { schema ->
            if (schema instanceof Map && schema.anyOf instanceof List) {
                flattened.addAll(schema.anyOf)
            } else {
                flattened.add(schema)
            }
        }
        if (!flattened) return null

        if (flattened.every { it instanceof Map && isObjectOnly((Map) it) }) {
            return mergeObjectSchemas(flattened)
        }
        if (flattened.every { it instanceof Map && isArrayOnly((Map) it) }) {
            return mergeArraySchemas(flattened)
        }
        if (flattened.every { it instanceof Map && isPrimitiveOnly((Map) it) }) {
            return mergePrimitiveSchemas(flattened)
        }

        List uniqueSchemas = flattened.unique()
        if (uniqueSchemas.size() == 1) return (Map<String, Object>) uniqueSchemas[0]
        return [anyOf: uniqueSchemas]
    }

    private static Map<String, Object> mergeObjectSchemas(List flattened) {
        Map<String, Object> mergedProps = [:]
        flattened.each { schema ->
            Map props = schema.properties instanceof Map ? (Map) schema.properties : [:]
            props.each { k, v ->
                if (k == null || v == null) return
                String key = k.toString()
                Map<String, Object> merged = mergeSchemaList([mergedProps[key], v].findAll { it != null })
                mergedProps[key] = merged ?: v
            }
        }
        List<String> mergedTypes = mergeTypeLists(flattened.collectMany { schemaTypeList((Map) it) } + ["object"])
        Object typeVal = mergedTypes.size() == 1 ? mergedTypes[0] : mergedTypes
        return [type: typeVal, properties: mergedProps]
    }

    private static Map<String, Object> mergeArraySchemas(List flattened) {
        List itemSchemas = flattened.collect { it.items }.findAll { it != null }
        Map<String, Object> mergedItems = mergeSchemaList(itemSchemas)
        List<String> mergedTypes = mergeTypeLists(flattened.collectMany { schemaTypeList((Map) it) } + ["array"])
        Object typeVal = mergedTypes.size() == 1 ? mergedTypes[0] : mergedTypes
        return [type: typeVal, items: mergedItems ?: [:]]
    }

    private static Map<String, Object> mergePrimitiveSchemas(List flattened) {
        List<String> mergedTypes = mergeTypeLists(flattened.collectMany { schemaTypeList((Map) it) })
        Object typeVal = mergedTypes.size() == 1 ? mergedTypes[0] : mergedTypes
        return [type: typeVal]
    }

    static List<String> schemaTypeList(Map schema) {
        if (schema == null) return []
        Object typeVal = schema.type
        List<String> types = []
        if (typeVal instanceof Collection) {
            types = typeVal.collect { it?.toString() }.findAll { it } as List<String>
        } else if (typeVal != null) {
            types = [typeVal.toString()]
        }
        if (!types) {
            if (schema.properties instanceof Map) types = ["object"]
            else if (schema.items != null) types = ["array"]
        }
        return types
    }

    static List<String> mergeTypeLists(List types) {
        List<String> normalized = (types?.collect { it?.toString() }?.findAll { it } as List<String>) ?: []
        LinkedHashSet<String> ordered = new LinkedHashSet<>(normalized)
        if (ordered.contains("number") && ordered.contains("integer")) {
            ordered.remove("integer")
        }
        return ordered as List<String>
    }

    static boolean isObjectOnly(Map schema) {
        List<String> types = schemaTypeList(schema)
        if (types) return types.every { ["object", "null"].contains(it) }
        return schema?.properties instanceof Map
    }

    static boolean isArrayOnly(Map schema) {
        List<String> types = schemaTypeList(schema)
        if (types) return types.every { ["array", "null"].contains(it) }
        return schema?.items != null
    }

    static boolean isPrimitiveOnly(Map schema) {
        List<String> types = schemaTypeList(schema)
        if (!types) return false
        return types.every { PRIMITIVE_TYPES.contains(it) }
    }
}
