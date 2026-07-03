package reconciliation.rule

import java.util.Collections
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Parses simplified human-readable conditions into DRL map constraints.
 */
class RuleConditionParser {

    private static final Map<String, String> OPERATOR_MAP = Collections.unmodifiableMap([
            "greater than or equals": ">=",
            "less than or equals"   : "<=",
            "is not"                : "!=",
            "not equals"            : "!=",
            "greater than"          : ">",
            "less than"             : "<",
            "equals"                : "==",
            "is"                    : "==",
            ">="                    : ">=",
            "<="                    : "<=",
            ">"                     : ">",
            "<"                     : "<"
    ])
    private static final List<String> SORTED_OPERATORS = Collections.unmodifiableList(OPERATOR_MAP.keySet().toList().sort { String op -> -op.length() })
    // Patterns precompiled once at class-load time, keyed in longest-first operator order.
    // Avoids recompiling the same Pattern objects on every parseCondition call.
    private static final List<List> COMPILED_OPERATORS = Collections.unmodifiableList(
            SORTED_OPERATORS.collect { String op ->
                String regexOp = op.matches("[a-zA-Z\\s]+") ? "\\b${op}\\b" : Pattern.quote(op)
                [op, Pattern.compile("^\\s*(.+?)\\s+" + regexOp + "\\s+(.+)\\s*\\z", Pattern.CASE_INSENSITIVE)]
            }
    )
    private static final Pattern CONTAINS_PATTERN = Pattern.compile("^\\s*(.+?)\\s+contains\\s+(.+)\\s*\\z", Pattern.CASE_INSENSITIVE)
    private static final Pattern NOT_CONTAINS_PATTERN = Pattern.compile("^\\s*(.+?)\\s+(does not contain|not contains)\\s+(.+)\\s*\\z", Pattern.CASE_INSENSITIVE)

    static String parseCondition(String condition) {
        String raw = condition?.trim()
        if (!raw) return ""

        String containsExpr = parseContains(raw)
        if (containsExpr) return containsExpr

        for (List entry : COMPILED_OPERATORS) {
            String op = (String) entry[0]
            Matcher matcher = ((Pattern) entry[1]).matcher(raw)
            if (!matcher.find()) continue

            String field = normalizeField(matcher.group(1))
            String value = matcher.group(2)?.trim()
            if (!field || value == null) return ""
            return "this[\"${escape(field)}\"] ${OPERATOR_MAP[op]} ${normalizeValue(value)}"
        }

        return ""
    }

    private static String parseContains(String condition) {
        Matcher notContainsMatcher = NOT_CONTAINS_PATTERN.matcher(condition)
        if (notContainsMatcher.find()) {
            String field = normalizeField(notContainsMatcher.group(1))
            String value = notContainsMatcher.group(3)?.trim()
            if (!field || value == null) return ""
            return "this[\"${escape(field)}\"] == null || !this[\"${escape(field)}\"].toString().contains(${normalizeValue(value)})"
        }

        Matcher containsMatcher = CONTAINS_PATTERN.matcher(condition)
        if (containsMatcher.find()) {
            String field = normalizeField(containsMatcher.group(1))
            String value = containsMatcher.group(2)?.trim()
            if (!field || value == null) return ""
            return "this[\"${escape(field)}\"] != null && this[\"${escape(field)}\"].toString().contains(${normalizeValue(value)})"
        }

        return ""
    }

    private static String normalizeField(String field) {
        String cleaned = field?.trim()
        if (!cleaned) return ""
        return cleaned.replaceAll(/^['"`]+|['"`]+$/, "")
    }

    private static String normalizeValue(String value) {
        String cleaned = value?.trim()
        if (!cleaned) return "\"\""

        if (cleaned.length() >= 2 && ((cleaned.startsWith("\"") && cleaned.endsWith("\"")) || (cleaned.startsWith("'") && cleaned.endsWith("'")))) {
            String unquoted = cleaned.substring(1, cleaned.length() - 1)
            return "\"${escape(unquoted)}\""
        }

        if (cleaned ==~ /-?\d+(\.\d+)?/) return cleaned
        String lowered = cleaned.toLowerCase()
        if (["true", "false", "null"].contains(lowered)) return lowered

        return "\"${escape(cleaned)}\""
    }

    private static String escape(String raw) {
        return raw?.replace("\\", "\\\\")?.replace("\"", "\\\"") ?: ""
    }
}
