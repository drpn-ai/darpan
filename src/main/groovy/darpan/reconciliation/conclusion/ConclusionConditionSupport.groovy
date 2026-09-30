package darpan.reconciliation.conclusion

/**
 * DAR-BE-063. One conclusion condition against one subject. Pure: no Moqui, no I/O, no system names.
 *
 * Lifted verbatim from DAR-UI-044's ConclusionRuleEvaluator.check/holds, plus two additions that
 * default to the old behaviour:
 *  - quantifier ALL (default) | ANY: every value, or at least one, across every record under the key.
 *  - a DOTTED fieldExpression walks nested maps and fans out across lists; a path yielding nothing
 *    reads as one blank value. An undotted field is read exactly as before (record.get(field)).
 *
 * UNKNOWN presence satisfies no presence condition: "Not found" is a claim, and an unknown is not
 * evidence for it.
 */
class ConclusionConditionSupport {

    /** @return [passed: boolean, value: String] — value is what the condition read. */
    static Map check(Map condition, Map side) {
        boolean firstField = "FIRST_FIELD" == text(condition.get("keyScope"))
        String presence = text(firstField ? side?.get("firstFieldPresence") : side?.get("presence"))
        Map record = (Map) (firstField ? side?.get("firstFieldRecord") : side?.get("record"))
        // A side may hold several records under one key (the compare collapsed them to one row).
        List<Map> records = firstField ? [record] : ((List<Map>) side?.get("records") ?: [record])

        String wantedPresence = text(condition.get("presence")) ?: "ANY"
        if (wantedPresence != "ANY" && (presence == null || presence == "UNKNOWN" || presence != wantedPresence)) {
            return [passed: false, value: null]
        }

        String field = text(condition.get("fieldExpression"))
        String operator = text(condition.get("operator"))
        if (!field || !operator) {
            return [passed: true, value: presence?.toLowerCase()]
        }
        List<String> wanted = ((List) (condition.get("conditionValues") ?: [])).collect { it?.toString()?.trim()?.toUpperCase() }
        List<String> values = records.collectMany { Map r -> valuesAt(r, field) }
        boolean any = "ANY" == text(condition.get("quantifier"))
        boolean passed = any ? values.any { String v -> holds(operator, v, wanted) }
                             : values.every { String v -> holds(operator, v, wanted) }
        List<String> seen = values.findAll { it != null }.unique(false)
        return [passed: passed, value: seen ? seen.join(", ") : null]
    }

    /** The values a field expression yields on one record. Never empty: nothing found is [null]. */
    static List<String> valuesAt(Map record, String path) {
        if (!path.contains(".")) return [text(record?.get(path))]
        List<Object> current = [record]
        String[] segments = path.split("\\.")
        for (int i = 0; i < segments.length; i++) {
            boolean leaf = i == segments.length - 1
            List<Object> next = []
            for (Object node : current) {
                if (!(node instanceof Map)) continue
                Object value = ((Map) node).get(segments[i])
                if (value instanceof Collection) next.addAll((Collection) value)
                // A leaf keeps its null: an element that exists but lacks the field is a blank value
                // (a payment preference with no method), not an absent one.
                else if (value != null || leaf) next.add(value)
            }
            current = next
        }
        List<String> values = current.collect { Object v -> text(v) }
        return values ? values : [null]
    }

    private static boolean holds(String operator, String value, List<String> wanted) {
        switch (operator) {
            case "IN": return value != null && wanted.contains(value.toUpperCase())
            case "NOT_IN": return value == null || !wanted.contains(value.toUpperCase())
            case "GT_ZERO": return isPositive(value)
            case "BLANK": return !value
            case "NOT_BLANK": return value as boolean
            default: throw new IllegalArgumentException("Unsupported conclusion operator '${operator}'")
        }
    }

    private static boolean isPositive(String value) {
        if (!value) return false
        try {
            return new BigDecimal(value) > BigDecimal.ZERO
        } catch (NumberFormatException ignored) {
            return false
        }
    }

    private static String text(Object value) {
        String s = value?.toString()?.trim()
        return s ? s : null
    }
}
