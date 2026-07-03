package reconciliation.rule

import groovy.json.JsonSlurper

/**
 * Audit 2026-06-11 #1 (durable fix). Server-side generator for FIELD_COMPARISON rule DRL.
 *
 * The UI builds the rule's DRL ({@code ruleLogic}) on the client from a structured {@code expression}
 * ({type:FIELD_COMPARISON, file1FieldPath, file2FieldPath, operator, preActions}) plus the run's
 * primary-ID expressions. Trusting that client-built DRL string as code is the root of the rule-engine
 * RCE/DoS exposure. This class regenerates the identical DRL SERVER-SIDE from the same structured
 * inputs, so for FIELD_COMPARISON rules no tenant-authored string ever reaches the Drools/MVEL/ECJ
 * compiler as code — only escaped string literals (field segments, operator, severity) do.
 *
 * It is a faithful port of darpan-ui `buildFieldComparisonRuleLogic` and its helpers; byte-for-byte
 * parity with the UI output is locked by FieldComparisonRuleLogicGeneratorTests against fixtures
 * captured from the real UI generator. Keep the two in lockstep.
 */
class FieldComparisonRuleLogicGenerator {

    static final String FIELD_COMPARISON_TYPE = "FIELD_COMPARISON"
    private static final Set<String> ORDERED_OPERATORS = [">", "<", ">=", "<="] as Set
    private static final Set<String> VALID_OPERATORS = ["=", "==", "!=", ">", "<", ">=", "<="] as Set
    private static final Set<String> VALID_PRE_ACTIONS = ["STRING_TO_INT", "STRING_TO_NUMBER"] as Set
    private static final JsonSlurper JSON = new JsonSlurper()

    /**
     * Returns the regenerated ruleLogic for a FIELD_COMPARISON rule, or null if the expression is not a
     * FIELD_COMPARISON or is malformed (caller keeps the existing validator-gated path in that case).
     */
    static String generate(String expressionJson, String file1PrimaryIdExpression, String file2PrimaryIdExpression,
                           String ruleId, String severity, int index) {
        Map expr = parseExpression(expressionJson)
        if (expr == null) return null
        if (normalize(expr.type) != FIELD_COMPARISON_TYPE) return null

        String file1FieldPath = normalize(expr.file1FieldPath)
        String file2FieldPath = normalize(expr.file2FieldPath)
        if (!file1FieldPath || !file2FieldPath) return null

        String operator = normalizeRuleOperator(normalize(expr.operator))
        List<Map> preActions = normalizePreActions(expr.preActions)

        String ruleName = normalize(ruleId) ?: "FIELD_COMPARISON_${index + 1}"
        String file1Path = fieldPathRelativeToPrimary(file1FieldPath, file1PrimaryIdExpression ?: "")
        String file2Path = fieldPathRelativeToPrimary(file2FieldPath, file2PrimaryIdExpression ?: "")
        String file1Raw = buildDrlMapAccess('$m.get("file1")', file1Path)
        String file2Raw = buildDrlMapAccess('$m.get("file2")', file2Path)
        String file1ValueExpression = buildPreActionValueExpression(file1Raw, preActionNamesForSide(preActions, "file1"))
        String file2ValueExpression = buildPreActionValueExpression(file2Raw, preActionNamesForSide(preActions, "file2"))
        String violationExpression = buildViolationExpression(file1ValueExpression, file2ValueExpression, operator, !preActions.isEmpty())
        String fieldLabel = "${formatFieldKey(file1FieldPath)} ${operator} ${formatFieldKey(file2FieldPath)}"
        String sev = normalize(severity) ?: "WARN"

        return 'rule "' + escapeDrlString(ruleName) + '"\n' +
                'when\n' +
                '    \$m : Map(this["file1"] != null, this["file2"] != null)\n' +
                '    eval(' + violationExpression + ')\n' +
                'then\n' +
                '    Object _file1Value = ' + file1ValueExpression + ';\n' +
                '    Object _file2Value = ' + file2ValueExpression + ';\n' +
                '    reconciliation.rule.RuleDiffSupport.addFieldMismatch(\n' +
                '        \$m,\n' +
                '        kcontext.getRule().getName(),\n' +
                '        "' + escapeDrlString(fieldLabel) + '",\n' +
                '        _file1Value,\n' +
                '        _file2Value,\n' +
                '        "' + escapeDrlString(sev) + '",\n' +
                '        "Field comparison failed: ' + escapeDrlString(fieldLabel) + '"\n' +
                '    );\n' +
                'end'
    }

    static Map parseExpression(String expressionJson) {
        String raw = normalize(expressionJson)
        if (!raw) return null
        try {
            Object parsed = JSON.parseText(raw)
            return (parsed instanceof Map) ? (Map) parsed : null
        } catch (Exception ignored) {
            return null
        }
    }

    private static String normalizeRuleOperator(String operator) {
        String op = operator?.trim()
        return (op && VALID_OPERATORS.contains(op)) ? op : "="
    }

    // Mirror of darpan-ui normalizePreActions for the object form the expression JSON stores:
    // a list of {fieldSide, action}. (The legacy string form is not produced by the UI generator.)
    private static List<Map> normalizePreActions(Object value) {
        if (!(value instanceof List)) return []
        List<Map> result = []
        ((List) value).each { Object entry ->
            if (entry instanceof Map) {
                String side = normalize(((Map) entry).fieldSide)
                String action = normalize(((Map) entry).action)
                if ((side == "file1" || side == "file2") && VALID_PRE_ACTIONS.contains(action)) {
                    result.add([fieldSide: side, action: action])
                }
            }
        }
        return result
    }

    private static List<String> preActionNamesForSide(List<Map> preActions, String fieldSide) {
        return preActions.findAll { it.fieldSide == fieldSide }.collect { (String) it.action }
    }

    private static String buildPreActionValueExpression(String valueExpression, List<String> preActions) {
        if (!preActions) return valueExpression
        String drlPreActions = preActions.collect { '"' + escapeDrlString(it) + '"' }.join(", ")
        return "reconciliation.rule.RuleDiffSupport.applyPreActions(${valueExpression}, java.util.Arrays.asList(${drlPreActions}))"
    }

    private static String buildDrlMapAccess(String rootExpression, String fieldPath) {
        String expression = rootExpression
        pathSegments(fieldPath).each { String segment ->
            expression = '((Map) ' + expression + ').get("' + escapeDrlString(segment) + '")'
        }
        return expression
    }

    private static List<String> pathSegments(String fieldPath) {
        String stripped = stripRootPrefix(fieldPath)
                .replaceAll(/\[['"]?([A-Za-z_$][\w$-]*)['"]?\]/, '.$1')
                .replaceAll(/\[(\d+)\]/, '.$1')
                .replaceAll(/\[\*\]/, '')
        return stripped.split(/\./, -1).collect { it.trim() }.findAll { it }
    }

    private static String buildViolationExpression(String file1ValueExpression, String file2ValueExpression,
                                                   String operator, boolean useRuleDiffSupport) {
        if (useRuleDiffSupport || ORDERED_OPERATORS.contains(operator)) {
            return "reconciliation.rule.RuleDiffSupport.violatesOperator(${file1ValueExpression}, ${file2ValueExpression}, \"${escapeDrlString(operator)}\")"
        }
        if (operator == "!=") {
            return "java.util.Objects.equals(${file1ValueExpression}, ${file2ValueExpression})"
        }
        // "=", "==", and any default
        return "!java.util.Objects.equals(${file1ValueExpression}, ${file2ValueExpression})"
    }

    // Mirror of darpan-ui normalizeReconciliationFieldPath.
    static String normalizeReconciliationFieldPath(String fieldPath) {
        String trimmed = fieldPath?.trim()
        if (!trimmed) return ""
        String expression = trimmed.split(/\|/, 2)[0] ?: ""
        return expression.trim()
                .replaceAll(/\[(\d+)\]/, '[*]')
                .replaceFirst(/\.\[\*\]/, '[*]')
    }

    private static String fieldPathRelativeToPrimary(String fieldPath, String primaryExpression) {
        String normalizedField = normalizeReconciliationFieldPath(fieldPath)
        String normalizedPrimary = normalizeReconciliationFieldPath(primaryExpression)
        if (!normalizedField) return ""
        int starIndex = normalizedPrimary.indexOf("[*]")
        if (starIndex >= 0) {
            String recordPrefix = normalizedPrimary.substring(0, starIndex + 3)
            String prefixWithDot = recordPrefix + "."
            if (normalizedField.startsWith(prefixWithDot)) {
                return stripRootPrefix(normalizedField.substring(prefixWithDot.length()))
            }
        }
        return stripRootPrefix(normalizedField)
    }

    private static String stripRootPrefix(String fieldPath) {
        return fieldPath
                .replaceFirst(/^\$\[\*\]\.?/, '')
                .replaceFirst(/^\$\./, '')
                .replaceFirst(/^\$/, '')
                .replaceFirst(/^\./, '')
    }

    // Mirror of darpan-ui formatReconciliationFieldKey: returns the last meaningful path segment.
    static String formatFieldKey(String fieldPath) {
        String normalized = normalizeReconciliationFieldPath(fieldPath)
        if (!normalized) return "Field pending"
        List<String> segments = stripRootPrefix(normalized)
                .replaceAll(/\[['"]?([A-Za-z_$][\w$-]*)['"]?\]/, '.$1')
                .replaceAll(/\[\*\]/, '')
                .split(/\./, -1)
                .collect { it.trim() }
                .findAll { it && it != '$' }
        return segments ? segments[-1] : normalized
    }

    private static String escapeDrlString(String value) {
        if (value == null) return ""
        return value.replace("\\", "\\\\")
                .replace('"', '\\"')
                .replaceAll(/\r?\n/, ' ')
    }

    private static String normalize(Object value) {
        String s = value?.toString()?.trim()
        return s ?: null
    }
}
