package reconciliation.rule

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Parses simplified human-readable conditions into DRL map constraints.
 */
class RuleConditionParser {

    private static final Map<String, String> OPERATOR_MAP = [
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
    ]

    static String parseCondition(String condition) {
        String raw = condition?.trim()
        if (!raw) return ""

        String containsExpr = parseContains(raw)
        if (containsExpr) return containsExpr

        List<String> sortedOps = OPERATOR_MAP.keySet().sort { -it.length() }
        for (String op : sortedOps) {
            String regexOp = op.matches("[a-zA-Z\\s]+") ? "\\b${op}\\b" : Pattern.quote(op)
            Pattern pattern = Pattern.compile("^\\s*(.+?)\\s+" + regexOp + "\\s+(.+)\\s*\$", Pattern.CASE_INSENSITIVE)
            Matcher matcher = pattern.matcher(raw)
            if (!matcher.find()) continue

            String field = normalizeField(matcher.group(1))
            String value = matcher.group(2)?.trim()
            if (!field || value == null) return ""
            return "this[\"${escape(field)}\"] ${OPERATOR_MAP[op]} ${normalizeValue(value)}"
        }

        return ""
    }

    private static String parseContains(String condition) {
        Pattern containsPattern = Pattern.compile("^\\s*(.+?)\\s+contains\\s+(.+)\\s*\$", Pattern.CASE_INSENSITIVE)
        Matcher containsMatcher = containsPattern.matcher(condition)
        if (containsMatcher.find()) {
            String field = normalizeField(containsMatcher.group(1))
            String value = containsMatcher.group(2)?.trim()
            if (!field || value == null) return ""
            return "this[\"${escape(field)}\"] != null && this[\"${escape(field)}\"].toString().contains(${normalizeValue(value)})"
        }

        Pattern notContainsPattern = Pattern.compile("^\\s*(.+?)\\s+(does not contain|not contains)\\s+(.+)\\s*\$", Pattern.CASE_INSENSITIVE)
        Matcher notContainsMatcher = notContainsPattern.matcher(condition)
        if (notContainsMatcher.find()) {
            String field = normalizeField(notContainsMatcher.group(1))
            String value = notContainsMatcher.group(3)?.trim()
            if (!field || value == null) return ""
            return "this[\"${escape(field)}\"] == null || !this[\"${escape(field)}\"].toString().contains(${normalizeValue(value)})"
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

        if ((cleaned.startsWith("\"") && cleaned.endsWith("\"")) || (cleaned.startsWith("'") && cleaned.endsWith("'"))) {
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
