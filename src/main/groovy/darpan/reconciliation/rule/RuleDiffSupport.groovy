package reconciliation.rule

import java.util.Objects

class RuleDiffSupport {
    static String normalize(Object value) {
        return value?.toString()?.trim()
    }

    static String stringify(Object value) {
        return value == null ? null : value.toString()
    }

    static boolean valuesDiffer(Object file1Value, Object file2Value) {
        return !Objects.equals(file1Value, file2Value)
    }

    static Object valueAtPath(Object source, String path) {
        if (source == null) return null
        String normalizedPath = normalize(path)
        if (!normalizedPath || normalizedPath == "\$") return source

        String cleanedPath = normalizedPath
                .replaceFirst(/^\$\./, "")
                .replaceFirst(/^\$/, "")
                .replaceAll(/\[['"]?([^'"\]]+)['"]?\]/, '.$1')
                .replaceAll(/\[(\d+)\]/, '.$1')
                .replaceAll(/\[\*\]/, "")
        if (cleanedPath.startsWith(".")) cleanedPath = cleanedPath.substring(1)
        if (!cleanedPath) return source

        Object current = source
        for (String segment : cleanedPath.split(/\./).findAll { it }) {
            if (current == null) return null
            if (current instanceof Map) {
                current = ((Map) current).get(segment)
            } else if (current instanceof List && segment ==~ /\d+/) {
                int index = Integer.parseInt(segment)
                List list = (List) current
                current = index >= 0 && index < list.size() ? list[index] : null
            } else {
                return null
            }
        }
        return current
    }

    static boolean matchesOperator(Object file1Value, Object file2Value, String operator) {
        String normalizedOperator = normalize(operator) ?: "="
        switch (normalizedOperator) {
            case "=":
            case "==":
                return equalValues(file1Value, file2Value)
            case "!=":
                return !equalValues(file1Value, file2Value)
            case ">":
                return compareValues(file1Value, file2Value) > 0
            case "<":
                return compareValues(file1Value, file2Value) < 0
            case ">=":
                return compareValues(file1Value, file2Value) >= 0
            case "<=":
                return compareValues(file1Value, file2Value) <= 0
            default:
                return equalValues(file1Value, file2Value)
        }
    }

    static boolean violatesOperator(Object file1Value, Object file2Value, String operator) {
        return !matchesOperator(file1Value, file2Value, operator)
    }

    static Object applyPreActions(Object value, Object rawPreActions) {
        Object current = value
        normalizePreActions(rawPreActions).each { String preAction ->
            current = applyPreAction(current, preAction)
        }
        return current
    }

    static List<String> normalizePreActions(Object rawPreActions) {
        if (rawPreActions == null) return []
        Collection values = rawPreActions instanceof Collection ? (Collection) rawPreActions : [rawPreActions]

        return values.collect { Object rawValue ->
            normalize(rawValue)?.toUpperCase()
        }.findAll { String value ->
            value in ["STRING_TO_INT", "STRING_TO_INTEGER", "TO_INT", "TO_INTEGER", "STRING_TO_NUMBER", "TO_NUMBER"]
        }.collect { String value ->
            value in ["STRING_TO_INTEGER", "TO_INT", "TO_INTEGER"] ? "STRING_TO_INT" :
                    (value == "TO_NUMBER" ? "STRING_TO_NUMBER" : value)
        }
    }

    static Object applyPreAction(Object value, String preAction) {
        switch (normalize(preAction)?.toUpperCase()) {
            case "STRING_TO_INT":
                return toLong(value)
            case "STRING_TO_NUMBER":
                return toBigDecimal(value)
            default:
                return value
        }
    }

    static boolean equalValues(Object left, Object right) {
        if (left == null || right == null) return Objects.equals(left, right)

        BigDecimal leftNumber = toBigDecimal(left)
        BigDecimal rightNumber = toBigDecimal(right)
        if (leftNumber != null && rightNumber != null) return leftNumber.compareTo(rightNumber) == 0

        return Objects.equals(left, right) || stringify(left) == stringify(right)
    }

    static int compareValues(Object left, Object right) {
        if (left == null || right == null) return left == right ? 0 : (left == null ? -1 : 1)

        BigDecimal leftNumber = toBigDecimal(left)
        BigDecimal rightNumber = toBigDecimal(right)
        if (leftNumber != null && rightNumber != null) return leftNumber.compareTo(rightNumber)

        return stringify(left) <=> stringify(right)
    }

    static Long toLong(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue()
        }

        String normalized = normalize(value)
        if (!normalized || !(normalized ==~ /-?\d+/)) return null
        try {
            return Long.valueOf(normalized)
        } catch (Exception ignored) {
            return null
        }
    }

    static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal) return (BigDecimal) value
        if (value instanceof Number) return new BigDecimal(value.toString())

        String normalized = normalize(value)
        if (!normalized || !(normalized ==~ /-?\d+(\.\d+)?/)) return null
        try {
            return new BigDecimal(normalized)
        } catch (Exception ignored) {
            return null
        }
    }

    static void addFieldMismatch(Map fact, String ruleId, String field, Object file1Value, Object file2Value,
                                 String severity = null, String message = null) {
        String fieldName = normalize(field)
        addDiff(fact, [
                diffType  : "FIELD_MISMATCH",
                field     : fieldName,
                file1Value: stringify(file1Value),
                file2Value: stringify(file2Value),
                ruleId    : normalize(ruleId),
                severity  : normalize(severity),
                message   : normalize(message) ?: (fieldName ? "${fieldName} differs between file 1 and file 2" : "Field values differ between file 1 and file 2")
        ])
    }

    static void addBusinessRuleDiff(Map fact, String ruleId, String diffType, String message, String severity = null) {
        addDiff(fact, [
                diffType: normalize(diffType) ?: "BUSINESS_RULE_DIFF",
                ruleId  : normalize(ruleId),
                severity: normalize(severity),
                message : normalize(message)
        ])
    }

    static void addDiff(Map fact, Map rawDiff) {
        if (fact == null) return
        Map diff = rawDiff instanceof Map ? new LinkedHashMap(rawDiff) : [:]
        List<Map> generatedDiffs = ensureGeneratedDiffList(fact)
        generatedDiffs.add(normalizeDiff(fact, diff))
    }

    static List<Map> extractNormalizedDiffs(Map fact) {
        if (fact == null) return []
        List<Map> generatedDiffs = ensureGeneratedDiffList(fact)
        return generatedDiffs.collect { Map diff -> normalizeDiff(fact, diff) }
    }

    static List<Map> ensureGeneratedDiffList(Map fact) {
        Object existing = fact.get("_generatedDiffs")
        if (existing instanceof List) {
            return (List<Map>) existing
        }
        List<Map> generatedDiffs = []
        fact.put("_generatedDiffs", generatedDiffs)
        return generatedDiffs
    }

    static Map normalizeDiff(Map fact, Map rawDiff) {
        Map diff = rawDiff instanceof Map ? rawDiff : [:]
        return [
                diffType      : normalize(diff.diffType) ?: "BUSINESS_RULE_DIFF",
                compareScopeId: normalize(diff.compareScopeId) ?: normalize(fact.compareScopeId),
                objectType    : normalize(diff.objectType) ?: normalize(fact.objectType),
                primaryId     : normalize(diff.primaryId) ?: normalize(fact.primaryId),
                field         : normalize(diff.field),
                file1Value    : stringify(diff.file1Value),
                file2Value    : stringify(diff.file2Value),
                presentIn     : normalize(diff.presentIn),
                missingIn     : normalize(diff.missingIn),
                data          : stringify(diff.data),
                ruleId        : normalize(diff.ruleId),
                severity      : normalize(diff.severity),
                message       : normalize(diff.message)
        ]
    }
}
