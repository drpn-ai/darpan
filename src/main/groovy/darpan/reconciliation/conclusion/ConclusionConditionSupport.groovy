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
            // DAR-BE-345: a name prefix (a HotWax exchange order's lines carry only its name, EXC-...); case ignored as IN.
            case "STARTS_WITH_ANY": return startsWithAny(value, wanted)
            case "NOT_STARTS_WITH_ANY": return !startsWithAny(value, wanted)
            case "GT_ZERO": return isPositive(value)
            // DAR-BE-094 R4: more than the condition's one number ("more than $5 unapplied"); a blank or non-number is not.
            case "GT": return isAbove(value, wanted ? wanted[0] : null)
            // DAR-BE-327 (Aditi 2026-11-20): less than the one number, e.g. a return date (epoch millis) before go-live.
            case "LT": return isBelow(value, wanted ? wanted[0] : null)
            // Aditi 2026-10-24: a time within the last N hours (a fulfilled item NetSuite bills within 24 hours).
            case "WITHIN_HOURS": return isWithinHours(value, wanted ? wanted[0] : null)
            case "BLANK": return !value
            case "NOT_BLANK": return value as boolean
            default: throw new IllegalArgumentException("Unsupported conclusion operator '${operator}'")
        }
    }

    private static boolean startsWithAny(String value, List<String> prefixes) {
        if (!value) return false
        String upper = value.toUpperCase()
        return prefixes.any { String p -> p && upper.startsWith(p) }
    }

    private static boolean isAbove(String value, String floor) {
        return compared(value, floor) > 0
    }

    private static boolean isBelow(String value, String ceiling) {
        return compared(value, ceiling) < 0
    }

    /** value compared with bound as numbers; 0 (neither above nor below) when either is blank or not a number. */
    private static int compared(String value, String bound) {
        if (!value || !bound) return 0
        try {
            return new BigDecimal(value) <=> new BigDecimal(bound)
        } catch (NumberFormatException ignored) {
            return 0
        }
    }

    /** An ISO instant or epoch millis no more than `hours` before now (and not after it). */
    private static boolean isWithinHours(String value, String hours) {
        if (!value || !hours) return false
        try {
            long at = value ==~ /-?\d{10,}/ ? Long.parseLong(value) : java.time.Instant.parse(value).toEpochMilli()
            long age = System.currentTimeMillis() - at
            return age >= -60_000L && age <= (long) (new BigDecimal(hours) * 3_600_000L)
        } catch (Exception ignored) {
            return false
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
