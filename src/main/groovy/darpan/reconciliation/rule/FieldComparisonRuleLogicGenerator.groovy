package reconciliation.rule

import groovy.json.JsonSlurper

import java.util.regex.Pattern

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
    // Operators whose meaning lives in RuleDiffSupport.matchesOperator (DAR-BE-126 adds the one-way presence pair,
    // DAR-BE-153 the list pair).
    private static final Set<String> RULE_DIFF_OPERATORS = [">", "<", ">=", "<=", "requires", "required by", "all in", "contains all"] as Set
    private static final Set<String> VALID_OPERATORS = ["=", "==", "!="] as Set + RULE_DIFF_OPERATORS
    private static final Set<String> VALID_PRE_ACTIONS = ["STRING_TO_INT", "STRING_TO_NUMBER"] as Set
    private static final JsonSlurper JSON = new JsonSlurper()

    // DAR-OPS-029 S02: the shape generate() emits, with every string literal collapsed to "S", every map access to @A
    // and every side's value expression to @V. Only string literals vary between two generated rules.
    private static final Pattern DRL_STRING_LITERAL = ~/"(?:[^"\\\r\n]|\\.)*"/
    private static final Pattern PRE_ACTION_CALL = Pattern.compile(
            Pattern.quote('reconciliation.rule.RuleDiffSupport.applyPreActions(@A, java.util.Arrays.asList(') +
                    '"S"(?:, "S")*' + Pattern.quote('))'))
    private static final String VIOLATION_SHAPE =
            '(?:' + Pattern.quote('reconciliation.rule.RuleDiffSupport.differsByAtLeast(@V, @V, "S")') +
            '|' + Pattern.quote('reconciliation.rule.RuleDiffSupport.violatesOperator(@V, @V, "S")') +
            '|!?' + Pattern.quote('java.util.Objects.equals(@V, @V)') + ')'
    private static final Pattern GENERATED_SHAPE = Pattern.compile(
            Pattern.quote('rule "S"\nwhen\n    $m : Map(this["S"] != null, this["S"] != null)\n    eval(') +
                    VIOLATION_SHAPE +
                    Pattern.quote(')\nthen\n    Object _file1Value = @V;\n    Object _file2Value = @V;\n' +
                            '    reconciliation.rule.RuleDiffSupport.addFieldMismatch(\n        $m,\n' +
                            '        kcontext.getRule().getName(),\n        "S",\n        _file1Value,\n        _file2Value,\n' +
                            '        "S",\n        "S"\n    );\nend'))

    /**
     * DAR-OPS-029 S02. True only when {@code ruleLogic} is exactly what {@link #generate} emits for some expression: the
     * fixed template, where everything that varies sits inside a string literal and so cannot run. The compile gate
     * trusts a tenant's stored ruleLogic only on this proof, never on its ruleType or on an expression stored beside it.
     * A Unicode escape anywhere, or an '@' outside a literal, fails it: either could change what the compiler reads.
     */
    static boolean isGeneratedRuleLogic(String ruleLogic) {
        String text = ruleLogic?.replace("\r\n", "\n")?.trim()
        if (!text || text.contains('\\u')) return false
        if (!literalSlotsAreGenerated(text)) return false
        String shape = DRL_STRING_LITERAL.matcher(text).replaceAll('"S"')
        if (shape.contains('@')) return false
        shape = shape.replace('$m.get("S")', '@A')
        String previous = null
        while (shape != previous) {
            previous = shape
            shape = shape.replace('((Map) @A).get("S")', '@A')
        }
        shape = PRE_ACTION_CALL.matcher(shape).replaceAll('@V').replace('@A', '@V')
        return GENERATED_SHAPE.matcher(shape).matches()
    }

    private static final String FACT_GUARD_LINE = '    $m : Map(this["file1"] != null, this["file2"] != null)'
    private static final Pattern LAST_LITERAL = ~/"([^"\\]*)"\)\)\s*$/

    /**
     * DAR-OPS-029 S02 (QA refute): the literals whose value carries meaning hold only what generate() can put there.
     * The shape alone let any literal stand in any slot, so a stored differsByAtLeast(a, b, "0") flagged every pair.
     * The fact guard names file1 and file2; the eval line's last literal is a tolerance generate() accepts
     * (differsByAtLeast) or one of its operators (violatesOperator). Severity, labels and path segments stay free text.
     */
    private static boolean literalSlotsAreGenerated(String text) {
        List<String> lines = text.split("\n", -1) as List<String>
        if (lines.size() < 4 || lines[2] != FACT_GUARD_LINE) return false
        String evalLine = lines[3]
        boolean tolerance = evalLine.startsWith('    eval(reconciliation.rule.RuleDiffSupport.differsByAtLeast(')
        boolean operator = evalLine.startsWith('    eval(reconciliation.rule.RuleDiffSupport.violatesOperator(')
        if (!tolerance && !operator) return true
        java.util.regex.Matcher last = LAST_LITERAL.matcher(evalLine)
        if (!last.find()) return false
        String value = last.group(1)
        return tolerance ? normalizeTolerance(value) == value : VALID_OPERATORS.contains(value)
    }

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
        // DAR-BE-192: an equality may tolerate a difference below an amount (gorjana R9 ignores memo/credit cents under 1.00).
        // Server-only: the UI never sends it, so UI parity is unchanged when it is absent.
        String tolerance = operator in ["=", "=="] ? normalizeTolerance(expr.tolerance) : null
        String violationExpression = tolerance ?
                "reconciliation.rule.RuleDiffSupport.differsByAtLeast(${file1ValueExpression}, ${file2ValueExpression}, \"${tolerance}\")" :
                buildViolationExpression(file1ValueExpression, file2ValueExpression, operator, !preActions.isEmpty())
        String fieldLabel = "${formatFieldKey(file1FieldPath)} ${operator} ${formatFieldKey(file2FieldPath)}" +
                (tolerance ? " (differences under ${tolerance} ignored)" : "")
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

    private static Map parseExpression(String expressionJson) {
        String raw = normalize(expressionJson)
        if (!raw) return null
        try {
            Object parsed = JSON.parseText(raw)
            return (parsed instanceof Map) ? (Map) parsed : null
        } catch (Exception ignored) {
            return null
        }
    }

    /** DAR-BE-192. A tolerance is a plain non-negative decimal (at most 9 digits, 6 after the point), else none. */
    private static String normalizeTolerance(Object raw) {
        String t = normalize(raw?.toString())
        if (!t || !(t ==~ /\d{1,9}(\.\d{1,6})?/)) return null
        return new BigDecimal(t).signum() > 0 ? t : null
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
        if (useRuleDiffSupport || RULE_DIFF_OPERATORS.contains(operator)) {
            return "reconciliation.rule.RuleDiffSupport.violatesOperator(${file1ValueExpression}, ${file2ValueExpression}, \"${escapeDrlString(operator)}\")"
        }
        if (operator == "!=") {
            return "java.util.Objects.equals(${file1ValueExpression}, ${file2ValueExpression})"
        }
        // "=", "==", and any default
        return "!java.util.Objects.equals(${file1ValueExpression}, ${file2ValueExpression})"
    }

    // Mirror of darpan-ui normalizeReconciliationFieldPath.
    private static String normalizeReconciliationFieldPath(String fieldPath) {
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
    private static String formatFieldKey(String fieldPath) {
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
