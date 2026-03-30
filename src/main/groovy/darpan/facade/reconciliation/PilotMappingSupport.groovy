package darpan.facade.reconciliation

import groovy.json.JsonSlurper
import jsonschema.common.JsonSchemaUtil
import org.moqui.context.ExecutionContext

class PilotMappingSupport {

    static List<String> collectPilotReadinessIssues(ExecutionContext ec, List mappingMembers) {
        List members = mappingMembers ?: []
        if (members.isEmpty()) return ["The mapping does not contain any system members."]
        return members.collectMany { member -> collectMemberIssues(ec, member) }.unique()
    }

    static List<String> collectMemberIssues(ExecutionContext ec, Object member) {
        String systemLabel = resolveSystemLabel(ec, normalize(member?.systemEnumId))
        String fileTypeCode = resolveFileTypeCode(ec, normalize(member?.fileTypeEnumId))
        String schemaFileName = normalize(member?.schemaFileName)
        String idFieldExpression = normalize(member?.idFieldExpression ?: member?.systemFieldName)
        boolean jsonConfigured = "JSON".equalsIgnoreCase(fileTypeCode) || !!schemaFileName

        List<String> issues = []
        if (!idFieldExpression) {
            issues.add("${systemLabel} is missing an id field expression.")
        }

        if (!jsonConfigured) return issues
        if (!schemaFileName) {
            issues.add("${systemLabel} is missing a schema for JSON pilot runs.")
            return issues
        }

        String schemaText = JsonSchemaUtil.loadSchemaText(ec, null, schemaFileName)
        if (!schemaText) {
            issues.add("${systemLabel} schema '${schemaFileName}' is not available to the pilot flow.")
            return issues
        }

        String propertyName = extractJsonPropertyName(idFieldExpression)
        if (!propertyName || !isResolvableJsonIdExpression(idFieldExpression, schemaText)) {
            issues.add("${systemLabel} id field '${normalizeBaseIdFieldExpression(idFieldExpression)}' is not present in schema '${schemaFileName}'.")
        }

        return issues
    }

    static boolean isResolvableJsonIdExpression(String idFieldExpression, String schemaText) {
        String propertyName = extractJsonPropertyName(idFieldExpression)
        if (!propertyName || !schemaText?.trim()) return false

        def schemaDocument = new JsonSlurper().parseText(schemaText)
        return schemaContainsProperty(schemaDocument, propertyName, [] as Set<Integer>)
    }

    static String normalizeBaseIdFieldExpression(String rawExpression) {
        String normalized = normalize(rawExpression)
        if (!normalized) return null
        int separatorIndex = normalized.indexOf("|")
        return separatorIndex >= 0 ? normalize(normalized.substring(0, separatorIndex)) : normalized
    }

    protected static String extractJsonPropertyName(String rawExpression) {
        String normalized = normalizeBaseIdFieldExpression(rawExpression)
        if (!normalized) return null
        if (!normalized.startsWith('$')) return normalized

        String withoutArrayHints = normalized.replace("[*]", "")
        List<String> segments = withoutArrayHints.tokenize(".")
                .collect { segment -> segment?.trim() }
                .findAll { segment -> segment && segment != '$' }
                .collect { segment -> segment.startsWith('$') ? segment.substring(1) : segment }
                .findAll { segment -> segment }
        return segments ? segments.last() : null
    }

    protected static boolean schemaContainsProperty(Object node, String propertyName, Set<Integer> visited) {
        if (node == null || !propertyName) return false

        int nodeIdentity = System.identityHashCode(node)
        if (!visited.add(nodeIdentity)) return false

        if (node instanceof Map) {
            Map nodeMap = (Map) node
            Object properties = nodeMap.get("properties")
            if (properties instanceof Map) {
                Map propertyMap = (Map) properties
                if (propertyMap.containsKey(propertyName)) return true
                if (propertyMap.values().any { child -> schemaContainsProperty(child, propertyName, visited) }) return true
            }

            Object items = nodeMap.get("items")
            if (items != null && schemaContainsProperty(items, propertyName, visited)) return true

            for (String compositeKey in ["allOf", "anyOf", "oneOf"]) {
                Object variants = nodeMap.get(compositeKey)
                if (variants instanceof List && ((List) variants).any { child -> schemaContainsProperty(child, propertyName, visited) }) {
                    return true
                }
            }
        } else if (node instanceof List) {
            return ((List) node).any { child -> schemaContainsProperty(child, propertyName, visited) }
        }

        return false
    }

    protected static String resolveFileTypeCode(ExecutionContext ec, String enumId) {
        if (!enumId) return null
        def enumValue = ec.entity.find("moqui.basic.Enumeration")
                .condition("enumId", enumId)
                .useCache(true)
                .one()
        return normalize(enumValue?.enumCode)
    }

    protected static String resolveSystemLabel(ExecutionContext ec, String enumId) {
        if (!enumId) return "A configured system"
        def enumValue = ec.entity.find("moqui.basic.Enumeration")
                .condition("enumId", enumId)
                .useCache(true)
                .one()
        return normalize(enumValue?.enumCode) ?: normalize(enumValue?.description) ?: enumId
    }

    protected static String normalize(Object value) {
        return value?.toString()?.trim()
    }
}
