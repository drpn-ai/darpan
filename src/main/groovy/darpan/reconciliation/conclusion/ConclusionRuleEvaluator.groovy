package darpan.reconciliation.conclusion

/**
 * DAR-UI-044. Decides a finding's conclusion from ordered rules. Pure: no Moqui, no I/O.
 *
 * Rules are tried in order and the FIRST whose conditions all hold wins; no match is UNEXPLAINED. The
 * checks returned are the winning rule's conditions paired with the value each one actually SAW, so
 * "How Darpan concluded" cannot claim anything the rule did not test.
 *
 * A side's presence is KEPT (in the extract), EXCLUDED (dropped by a source filter), ABSENT (neither)
 * or UNKNOWN (its sidecar was missing or truncated, so absence cannot be told from exclusion). UNKNOWN
 * satisfies no presence condition: "Not found" is a claim, and an unknown is not evidence for it.
 */
class ConclusionRuleEvaluator {

    static final String UNEXPLAINED = "UNEXPLAINED"
    static final String UNEXPLAINED_LABEL = "Unexplained"

    static Map evaluate(List<Map> rules, String bucket, Map sides) {
        for (Map rule : (rules ?: [])) {
            String applies = text(rule.get("appliesToBucket"))
            if (applies && applies != bucket) continue
            List<Map> checks = []
            boolean matched = true
            for (Map condition : ((List<Map>) (rule.get("conditions") ?: []))) {
                Map result = check(condition, (Map) (sides?.get(condition.get("subject")) ?: [:]))
                if (!result.passed) {
                    matched = false
                    break
                }
                checks.add([label: condition.get("checkLabel"), value: result.value, passed: true])
            }
            if (!matched) continue
            String questionText = text(rule.get("questionText"))
            return [code    : rule.get("conclusionEnumId"),
                    label   : rule.get("label"),
                    checks  : checks,
                    question: questionText ? [text: questionText, suggestedFilter: rule.get("suggestedFilter")] : null]
        }
        return [code: UNEXPLAINED, label: UNEXPLAINED_LABEL, checks: [], question: null]
    }

    /** @return [passed: boolean, value: String] — value is what the condition read. */
    private static Map check(Map condition, Map side) {
        boolean firstField = "FIRST_FIELD" == text(condition.get("keyScope"))
        String presence = text(firstField ? side.get("firstFieldPresence") : side.get("presence"))
        Map record = (Map) (firstField ? side.get("firstFieldRecord") : side.get("record"))
        // A side may hold several records under one key (the compare collapsed them to one row), and a
        // field condition must then hold for EVERY one: a mixed order is not a gift-card order.
        List<Map> records = firstField ? [record] : ((List<Map>) side.get("records") ?: [record])

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
        List<String> values = records.collect { Map r -> text(r?.get(field)) }
        boolean passed = values.every { String value -> holds(operator, value, wanted) }
        List<String> seen = values.findAll { it != null }.unique(false)
        return [passed: passed, value: seen ? seen.join(", ") : null]
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
