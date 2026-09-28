package darpan.reconciliation.source

import darpan.reconciliation.core.CompareIdExpressionSupport

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
 *
 * Include rules invert only the value test, never the field-name or case rules: under INCLUDE_IN a
 * record with no usable value for the field is REJECTED, because "only these values" cannot be
 * satisfied by silence.
 */
class SourceFilterSupport {

    static final String OPERATOR_EXCLUDE_IN = "EXCLUDE_IN"
    static final String OPERATOR_INCLUDE_IN = "INCLUDE_IN"

    /** Rejected because the record carried a value this rule does not allow. */
    static final String REASON_VALUE = "VALUE"
    /**
     * Rejected because the record carried no usable value for the field — the key is missing, or
     * present and blank. INCLUDE_IN only: "only these values" cannot be satisfied by no value.
     * Counted separately from REASON_VALUE so an include rule that silently removes a whole row
     * class (Darpan has row-class-scoped keys, e.g. refundLineEverFulfilled on REFUND rows only)
     * is visible in extract metadata instead of hiding inside the rule's excluded count.
     */
    static final String REASON_FIELD_ABSENT = "FIELD_ABSENT"

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
            throw new IllegalArgumentException("Source filters must be a list.")
        }
        Collection rawList = (Collection) rawRules
        if (rawList.isEmpty()) return Collections.emptyList()
        if (rawList.size() > MAX_RULES_PER_SOURCE) {
            throw new IllegalArgumentException(
                    "A source may define at most ${MAX_RULES_PER_SOURCE} filters; got ${rawList.size()}.".toString())
        }

        List<Map<String, Object>> parsed = new ArrayList<>(rawList.size())
        // One field may carry several rules, but they must agree on direction: "only A" together
        // with "not B" on one field is either a contradiction or an intersection nobody typed on
        // purpose. The board cannot produce this state (one filter per field), so this guards
        // direct API callers and rows written before the guard existed.
        Map<String, String> operatorByField = new LinkedHashMap<>()
        int position = 0
        for (Object raw : rawList) {
            position++
            if (!(raw instanceof Map)) {
                throw new IllegalArgumentException("Source filter ${position} is not a rule object.".toString())
            }
            Map row = (Map) raw
            Integer sequenceNum = parseSequenceNum(row.get("sequenceNum"), position)
            String fieldExpression = normalize(row.get("fieldExpression"))
            if (!fieldExpression) {
                throw new IllegalArgumentException("Source filter ${sequenceNum} has no field to test.".toString())
            }
            String operator = (normalize(row.get("operator")) ?: OPERATOR_EXCLUDE_IN).toUpperCase(Locale.ROOT)
            if (operator != OPERATOR_EXCLUDE_IN && operator != OPERATOR_INCLUDE_IN) {
                throw new IllegalArgumentException(
                        "Source filter ${sequenceNum} uses unsupported operator '${operator}'.".toString())
            }
            String priorOperator = operatorByField.put(fieldExpression, operator)
            if (priorOperator != null && priorOperator != operator) {
                throw new IllegalArgumentException(
                        ("Source filter ${sequenceNum} tests field '${fieldExpression}' with ${operator}, " +
                                "but another filter already tests it with ${priorOperator}.").toString())
            }
            List<String> values = splitValues(row.containsKey("filterValues") ? row.get("filterValues") : row.get("values"))
            if (!values) {
                throw new IllegalArgumentException("Source filter ${sequenceNum} has no values.".toString())
            }
            if (values.size() > MAX_VALUES_PER_RULE) {
                throw new IllegalArgumentException(
                        "Source filter ${sequenceNum} lists ${values.size()} values; the maximum is ${MAX_VALUES_PER_RULE}.".toString())
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

    /**
     * Test one record against every rule in order and report the first rejection.
     *
     * Returns null to KEEP the record, or [rule: <rule>, reason: REASON_*] naming the rule that
     * rejected it. The first rejecting rule wins and owns the count, exactly as the exclude-only
     * predecessor did, so adding an include rule never re-attributes an existing rule's tally.
     *
     * Direction, by operator:
     *   EXCLUDE_IN — reject when the record's value is listed. No value, no rejection.
     *   INCLUDE_IN — reject when the record's value is NOT listed, and reject when there is no
     *                usable value at all. "Only these values" is not satisfied by silence.
     */
    static Map<String, Object> evaluate(Object record, List<Map<String, Object>> rules) {
        if (!rules || !(record instanceof Map)) return null
        Map row = (Map) record
        for (Map<String, Object> rule : rules) {
            String fieldExpression = (String) rule.get("fieldExpression")
            // Same top-level, trimmed, case-sensitive key scan as OmsRestSourceSupport.isSalesOrder.
            Object rawValue = row.find { key, ignored -> normalize(key) == fieldExpression }?.value
            String candidate = normalize(rawValue)
            boolean includeMode = OPERATOR_INCLUDE_IN == rule.get("operator")
            if (!candidate) {
                // normalize() answers null for a missing key and "" for a blank one; both are falsy
                // here, and that conflation is right in both modes. No usable value cannot be in an
                // allowlist, and cannot match a denylist entry either.
                if (includeMode) return [rule: rule, reason: REASON_FIELD_ABSENT]
                continue
            }
            boolean listed = ((Set<String>) rule.get("matchValues")).contains(candidate.toUpperCase(Locale.ROOT))
            if (includeMode ? !listed : listed) return [rule: rule, reason: REASON_VALUE]
        }
        return null
    }

    /**
     * Translate stored rules into the shape {@link #evaluate} actually tests.
     *
     * {@code fieldExpression} is STORED as the operator-facing expression the rules board writes — a
     * JSONPath such as {@code $.records[*].salesChannelEnumId} — exactly like its sibling
     * {@code RuleSetCompareSourceKeyField.fieldExpression}. {@link #evaluate} scans TOP-LEVEL
     * RECORD KEYS, so handing it the stored form matches nothing at all: no rows dropped,
     * {@code excludedCount} 0, and no error — the silent no-op this feature exists to remove. Every
     * path that feeds rules to a getter must run them through here first.
     *
     * Normalizing at the READ boundary (rather than at save) is deliberate: storage keeps the
     * operator-facing path so a saved run round-trips straight back onto the board with the pill it
     * was configured from, and the key-field sibling already establishes exactly this convention
     * (stored as an expression, reduced via topLevelRecordField at the point of use).
     *
     * Fails closed and loudly: an expression that reduces to no usable record field throws rather
     * than being dropped or passed through to match nothing. The save path rejects the same shape, so
     * this is a defense-in-depth check for rows written before that gate existed.
     */
    static List<Map<String, Object>> toRecordFieldRules(List<Map<String, Object>> rows) {
        if (!rows) return Collections.emptyList()
        return rows.collect { Map<String, Object> row ->
            String stored = normalize(row.get("fieldExpression"))
            String fieldName = CompareIdExpressionSupport.topLevelRecordField(stored)
            if (!fieldName) {
                throw new IllegalArgumentException(
                        ("Source filter ${row.get('sequenceNum')} names field expression " +
                                "'${stored}', which does not resolve to a record field.").toString())
            }
            // A plain mutable copy, matching the shape these loaders returned before: the rows travel
            // on into Moqui service parameters, which is no place to hand out an unmodifiable Map.
            Map<String, Object> mapped = new LinkedHashMap<String, Object>(row)
            mapped.put("fieldExpression", fieldName)
            return mapped
        } as List<Map<String, Object>>
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
            throw new IllegalArgumentException("Source filter ${position} has a non-numeric sequence number.".toString())
        }
    }
}
