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
