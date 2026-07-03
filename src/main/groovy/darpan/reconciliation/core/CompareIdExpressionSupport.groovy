package darpan.reconciliation.core

import com.jayway.jsonpath.InvalidPathException
import com.jayway.jsonpath.JsonPath

import static darpan.common.ValueSupport.normalize

/**
 * Pure compare-id expression and normalizer parsing extracted from ReconciliationServices
 * (decomposition 2026-07-02, MACH P1). No Spark types appear in any signature here.
 * Shared constants intentionally stay on ReconciliationServices and are referenced from here.
 */
class CompareIdExpressionSupport {

    static Map parseIdSpec(String expr, boolean isCsv) {
        Map split = splitIdExpression(expr)
        String baseExpr = (String) split.idExpr
        String rawNormalizer = (String) split.normalizer
        String normalizedExpr = isCsv ? normalizeCsvId(baseExpr) : normalizeJsonIdExpr(baseExpr)
        String normalizedIdNormalizer = resolveIdNormalizer(rawNormalizer)
        return [idExpr: normalizedExpr, idNormalizer: normalizedIdNormalizer]
    }

    static Map splitIdExpression(String expr) {
        String raw = normalize(expr)
        if (!raw) return [idExpr: null, normalizer: null]
        int separatorIndex = raw.indexOf("|")
        if (separatorIndex < 0) return [idExpr: raw, normalizer: null]
        String idExpr = raw.substring(0, separatorIndex)?.trim()
        String normalizer = raw.substring(separatorIndex + 1)?.trim()
        return [idExpr: idExpr, normalizer: normalizer]
    }

    static String resolveIdNormalizer(String rawNormalizer) {
        String code = normalize(rawNormalizer)
        if (!code) return null
        String normalized = code.replace("-", "_").replace(" ", "_").toUpperCase()
        if (normalized == "SHOPIFY_GID_TAIL") return "SHOPIFY_GID_TAIL"
        // Audit 2026-06-11 #17: opt-in case-insensitive compare_id matching. Cross-system IDs that
        // differ only by letter case (e.g. an OMS 'GID-ABC' vs a Shopify 'gid-abc') were treated as
        // distinct and surfaced as false 'missing' alerts. Set a side's normalizer to CASE_FOLD to
        // lower-case the key before the join. Existing configs are unaffected (no default change).
        if (normalized == "CASE_FOLD" || normalized == "CASE_INSENSITIVE" || normalized == "LOWER") return "CASE_FOLD"
        throw new IllegalArgumentException("Unsupported ID normalizer '${rawNormalizer}'. Supported values: SHOPIFY_GID_TAIL, CASE_FOLD")
    }

    // JSON & CSV Helpers
    static String normalizeCsvId(String expr) {
        def raw = normalize(expr)
        if (!raw) return "id"
        if (raw.startsWith("\$")) {
            def parts = raw.tokenize(".")
            return parts ? parts[-1].replaceAll(/\[.*\]/, "") : "id"
        }
        return raw
    }
    static String normalizeJsonIdExpr(String expr) {
        def raw = normalize(expr)
        if (!raw) return validateJsonPath("\$.id")
        String normalized = raw
                .replaceAll(/\[(\d+)\]/, "[*]")
                .replace(".[*]", "[*]")
        String jsonPath = toJsonPath(normalized)
        return validateJsonPath(jsonPath)
    }

    private static String toJsonPath(String expression) {
        if (expression.startsWith("\$")) return expression
        if (expression.startsWith("[")) return '$' + expression
        if (expression.startsWith(".")) return '$' + expression
        if (ReconciliationServices.SIMPLE_JSON_FIELD_NAME.matcher(expression).matches()) return "\$[*].${expression}"
        return '$.' + expression
    }

    private static String validateJsonPath(String expression) {
        try {
            JsonPath.compile(expression)
            return expression
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid JSONPath '${expression}': ${e.message}", e)
        }
    }

    static Map convertJsonPathToSpark(String jsonPath) {
        def path = normalizeSparkPath(jsonPath)
        if (!path) throw new IllegalArgumentException("JSONPath ${jsonPath} resolves to an empty field path.")
        if (path.contains("[*]")) {
            def parts = path.split(/\[\*\]/)
            if (parts.length == 2) {
                return [needsExplode: true, arrayPath: parts[0], fieldPath: parts[1].replaceFirst(/^\./, "")]
            }
        }
        return [needsExplode: false, path: path]
    }
    static String normalizeSparkPath(String jsonPath) {
        if (!jsonPath) return jsonPath
        def path = jsonPath.toString().trim()
        path = path.replaceFirst(/^\$\[\*\]/, "")
        path = path.replaceFirst(/^\$\./, "")
        if (path.startsWith(".")) path = path.substring(1)
        return path
    }

    // Spark SQL injection defense for selectExpr arguments built from tenant-controlled JSON path strings.
    // normalizeSparkPath only strips the `$.`/`$[*]` prefix — it does not constrain what comes after.
    // A tenant whose file1IdExpression resolves to a path like `x as compare_id, reflect("…") as inj`
    // would otherwise inject arbitrary Spark SQL into selectExpr. Validate each dot-separated segment
    // strictly (ReconciliationServices.SAFE_PATH_SEGMENT) and backtick-quote per segment so the result
    // is unambiguous as a column reference.
    static String safeSparkColumnPath(String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("Spark column path is empty.")
        }
        StringBuilder out = new StringBuilder()
        boolean first = true
        for (String seg : path.split("\\.", -1)) {
            String trimmed = seg.trim()
            if (!ReconciliationServices.SAFE_PATH_SEGMENT.matcher(trimmed).matches()) {
                throw new IllegalArgumentException("Spark column path segment is not a valid identifier: '" + trimmed + "' (from full path '" + path + "')")
            }
            if (!first) out.append('.')
            out.append('`').append(trimmed.replace("`", "``")).append('`')
            first = false
        }
        return out.toString()
    }

    static String compareScopeDisplayName(Object compareScopeId, Object compareScopeDescription) {
        String description = normalize(compareScopeDescription)
        if (description) return description
        return normalize(compareScopeId)
    }

    static String determineReconciliationType(String file1Type, String file2Type) {
        String left = normalize(file1Type)?.toUpperCase()
        String right = normalize(file2Type)?.toUpperCase()
        if (left == "CSV" && right == "CSV") return "CSV"
        if (left == "JSON" && right == "JSON") return "JSON"
        return "MIXED"
    }
}
