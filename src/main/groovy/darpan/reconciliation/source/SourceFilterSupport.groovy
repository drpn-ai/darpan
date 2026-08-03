package darpan.reconciliation.source

import java.util.Locale

import static darpan.common.ValueSupport.normalize

/**
 * Connector-agnostic record exclusion rules for reconciliation getters.
 *
 * Rules are parsed and validated ONCE, before extraction starts, and the result is immutable:
 * getters hand the parsed list to page-preparation code running on fetch-pool worker threads, so
 * per-page parsing would both repeat the work on every thread and turn one malformed rule into N
 * identical mid-flight failures instead of a single clean pre-flight error.
 *
 * Matching semantics deliberately copy the hardcoded OMS filters (OmsRestSourceSupport.isSalesOrder
 * and containsExchangeOrderAssociation) so configured and built-in exclusions behave identically:
 * field names are trimmed and case-SENSITIVE, values are trimmed and case-INSENSITIVE, and a record
 * that lacks the field is kept — "exclude these values" cannot match an absent value.
 */
class SourceFilterSupport {

    static final String OPERATOR_EXCLUDE_IN = "EXCLUDE_IN"
    static final int MAX_RULES_PER_SOURCE = 20
    static final int MAX_VALUES_PER_RULE = 200

    private static final String VALUE_DELIMITER = ","

    /**
     * Normalize raw filter rows (entity records, service Maps, or plain Maps) into validated,
     * immutable rules. Invalid input throws rather than silently dropping a rule: an exclusion the
     * operator configured but Darpan quietly ignored produces exactly the confusion this removes.
     */
    static List<Map<String, Object>> parseRules(Object rawRules) {
        if (rawRules == null) return Collections.emptyList()
        if (!(rawRules instanceof Collection)) {
            throw new IllegalArgumentException("Source exclusion filters must be a list.")
        }
        Collection rawList = (Collection) rawRules
        if (rawList.isEmpty()) return Collections.emptyList()
        if (rawList.size() > MAX_RULES_PER_SOURCE) {
            throw new IllegalArgumentException(
                    "A source may define at most ${MAX_RULES_PER_SOURCE} exclusion filters; got ${rawList.size()}.".toString())
        }

        List<Map<String, Object>> parsed = new ArrayList<>(rawList.size())
        int position = 0
        for (Object raw : rawList) {
            position++
            if (!(raw instanceof Map)) {
                throw new IllegalArgumentException("Source exclusion filter ${position} is not a rule object.".toString())
            }
            Map row = (Map) raw
            Integer sequenceNum = parseSequenceNum(row.get("sequenceNum"), position)
            String fieldExpression = normalize(row.get("fieldExpression"))
            if (!fieldExpression) {
                throw new IllegalArgumentException("Source exclusion filter ${sequenceNum} has no field to test.".toString())
            }
            String operator = (normalize(row.get("operator")) ?: OPERATOR_EXCLUDE_IN).toUpperCase(Locale.ROOT)
            if (operator != OPERATOR_EXCLUDE_IN) {
                throw new IllegalArgumentException(
                        "Source exclusion filter ${sequenceNum} uses unsupported operator '${operator}'.".toString())
            }
            List<String> values = splitValues(row.containsKey("filterValues") ? row.get("filterValues") : row.get("values"))
            if (!values) {
                throw new IllegalArgumentException("Source exclusion filter ${sequenceNum} has no values to exclude.".toString())
            }
            if (values.size() > MAX_VALUES_PER_RULE) {
                throw new IllegalArgumentException(
                        "Source exclusion filter ${sequenceNum} lists ${values.size()} values; the maximum is ${MAX_VALUES_PER_RULE}.".toString())
            }
            Set<String> matchValues = new LinkedHashSet<>()
            values.each { String value -> matchValues.add(value.toUpperCase(Locale.ROOT)) }

            Map<String, Object> rule = [
                    sequenceNum    : sequenceNum,
                    fieldExpression: fieldExpression,
                    operator       : operator,
                    values         : Collections.unmodifiableList(values),
                    matchValues    : Collections.unmodifiableSet(matchValues),
            ]
            parsed.add(Collections.unmodifiableMap(rule))
        }
        return Collections.unmodifiableList(parsed)
    }

    /** The first rule that excludes this record, or null when the record should be kept. */
    static Map<String, Object> firstMatchingRule(Object record, List<Map<String, Object>> rules) {
        if (!rules || !(record instanceof Map)) return null
        Map row = (Map) record
        for (Map<String, Object> rule : rules) {
            String fieldExpression = (String) rule.get("fieldExpression")
            // Same top-level, trimmed, case-sensitive key scan as OmsRestSourceSupport.isSalesOrder.
            Object rawValue = row.find { key, ignored -> normalize(key) == fieldExpression }?.value
            String candidate = normalize(rawValue)
            if (!candidate) continue
            if (((Set<String>) rule.get("matchValues")).contains(candidate.toUpperCase(Locale.ROOT))) return rule
        }
        return null
    }

    /**
     * Accepts a List of values or a comma-separated String; trims each and drops blanks. Public
     * (not just used internally by parseRules): the facade save path (ReconciliationSavedRunSupport)
     * reuses this to flatten a submitted values list into storage form and to re-expand the stored
     * comma string back into a List for the wire-shape load response, so both directions share one
     * definition of "how a value list is split" instead of drifting apart.
     */
    static List<String> splitValues(Object rawValues) {
        List<String> values = []
        if (rawValues instanceof Collection) {
            ((Collection) rawValues).each { Object value ->
                String trimmed = normalize(value)
                if (trimmed) values.add(trimmed)
            }
            return values
        }
        String text = normalize(rawValues)
        if (!text) return values
        text.split(VALUE_DELIMITER, -1).each { String value ->
            String trimmed = normalize(value)
            if (trimmed) values.add(trimmed)
        }
        return values
    }

    protected static Integer parseSequenceNum(Object rawSequenceNum, int position) {
        if (rawSequenceNum == null) return position
        if (rawSequenceNum instanceof Number) return ((Number) rawSequenceNum).intValue()
        String text = normalize(rawSequenceNum)
        if (!text) return position
        try {
            return Integer.parseInt(text)
        } catch (NumberFormatException ignored) {
            throw new IllegalArgumentException("Source exclusion filter ${position} has a non-numeric sequence number.".toString())
        }
    }
}
