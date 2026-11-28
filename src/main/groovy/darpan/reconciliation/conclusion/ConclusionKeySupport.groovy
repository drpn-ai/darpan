package darpan.reconciliation.conclusion

import darpan.reconciliation.core.CompareIdExpressionSupport
import darpan.reconciliation.core.ReconciliationServices

/**
 * DAR-UI-044. The compare key, rebuilt in plain Groovy so the conclude pass can index a side's records
 * the way the Spark compare keyed them: each field trimmed as the compare trims it (every edge character up to U+0020, S01b
 * OC140), then normalized, joined with the composite
 * delimiter. The normalizer follows RuleSetCompareScopeAdapter: a composite key field carries its own
 * inline `field|NORMALIZER` (the caller passes no idNormalizer), a legacy key uses the source's
 * idValueNormalizer ?: its inline one, both through resolveIdNormalizer's aliases. A blank field has no
 * key, as it has none in the compare, which refuses composite rows with a blank field.
 */
class ConclusionKeySupport {

    static String compareKey(Map record, List<String> keyFields, String idNormalizer) {
        if (record == null || !keyFields) return null
        String configured = CompareIdExpressionSupport.resolveIdNormalizer(idNormalizer)
        List<String> parts = []
        for (String expression : keyFields) {
            Map split = CompareIdExpressionSupport.splitIdExpression(expression)
            String field = plainField(expression) ?: CompareIdExpressionSupport.topLevelRecordField(expression) ?: (String) split.idExpr
            String normalizer = configured ?: CompareIdExpressionSupport.resolveIdNormalizer((String) split.normalizer)
            Object raw = field == null ? null : record.get(field)
            String value = raw == null ? null : normalize(keyText(raw).trim(), normalizer)
            if (!value) return null
            parts.add(value)
        }
        return parts.join(ReconciliationServices.COMPOSITE_KEY_DELIMITER)
    }

    /**
     * The record field a key expression names when it is a plain top-level field of an extract record
     * (bare, or under $.records[*]); null for anything nested, which this pass cannot key the way the
     * compare did.
     */
    static String plainField(String expression) {
        String idExpr = (String) CompareIdExpressionSupport.splitIdExpression(expression).idExpr
        if (!idExpr) return null
        String path = idExpr.trim().replaceFirst(/^\$\.?/, "").replaceFirst(/^records\[\*\]\./, "")
        return path ==~ /[A-Za-z0-9_]+/ ? path : null
    }

    static String firstField(String compareKey) {
        if (compareKey == null) return null
        int at = compareKey.indexOf(ReconciliationServices.COMPOSITE_KEY_DELIMITER)
        return at < 0 ? compareKey : compareKey.substring(0, at)
    }

    /**
     * A key value's text as the Spark compare writes it (S01b cf60f0e8): a decimal (a BigDecimal from a JSON literal, a Double
     * or a Float) integral as a whole number, otherwise its shortest exact decimal (7.0 -> "7", 1.50 -> "1.5"); a string, an
     * Integer and a Long exactly as they are.
     */
    private static String keyText(Object raw) {
        if (!(raw instanceof BigDecimal || raw instanceof Double || raw instanceof Float)) return raw.toString()
        BigDecimal value
        try {
            value = new BigDecimal(raw.toString()).stripTrailingZeros()
        } catch (NumberFormatException ignored) {
            return raw.toString() // NaN or Infinity: no decimal to write
        }
        // Bounded: past 2^53 (16 digits) Spark writes scientific notation anyway, and plain text of an exponent such as
        // 1E999999999 would expand to a billion digits. Such a key keeps its own text. In long arithmetic: precision minus a
        // scale near Integer.MIN_VALUE overflows an int and would pass the check.
        long integerDigits = (long) value.precision() - (long) value.scale()
        if (integerDigits > 16L || value.scale() > 16 || value.scale() < -16) return raw.toString()
        return value.toPlainString()
    }

    private static String normalize(String value, String idNormalizer) {
        if (!value || !idNormalizer) return value
        if ("SHOPIFY_GID_TAIL" == idNormalizer) {
            def gid = (value =~ /gid:\/\/shopify\/[^\/]+\/(\d+)(?:\?.*)?$/)
            if (gid.find()) return gid.group(1)
            def digits = (value =~ /(\d+)$/)
            return digits.find() ? digits.group(1) : value
        }
        if ("CASE_FOLD" == idNormalizer) return value.toLowerCase(Locale.ROOT)
        throw new IllegalArgumentException("Unsupported ID normalizer '${idNormalizer}'")
    }
}
