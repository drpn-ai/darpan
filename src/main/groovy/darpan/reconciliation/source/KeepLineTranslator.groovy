package darpan.reconciliation.source

import groovy.json.JsonSlurper
import org.moqui.rules.ValueChecks

/**
 * DAR-BE-318 (plan 3b-2b, Ruling 3b2b-12). A connector's built-in Keep lines (SourceSystemConnector.keepLinesJson) as the one
 * Keep rule shape (KeepRules). The lines are moqui-rules "when" lines (RuleSetCondition maps, a tree by parentConditionSeqId) on
 * plain record fields, and mean exactly what prepare#CompareLine and the all/any/not lines mean in moqui-rules 0.2.0. A line Keep
 * cannot run is a data error. (A judge Keep decision's lines are not read: DAR-OPS-029, Aditi 2026-11-28; plan 4 rebuilds that.)
 *
 * Values. Compare values and list elements are converted to the field's kind as convert#FactValue does; the predicate carries
 * them as text (a number as its plain text with its scale, yes/no as true or false), and the Spark builder folds and casts both
 * sides. This class holds no system name and no Spark dependency.
 */
class KeepLineTranslator {

    static final String BUILT_IN_PREFIX = "BUILT_IN:"
    static final String BUILT_IN_REASON = "BUILT_IN"

    static final Map<String, String> OPS = [ENTCO_EQUALS: "EQ", ENTCO_NOT_EQUALS: "NE", ENTCO_LESS: "LT", ENTCO_LESS_EQ: "LE",
            ENTCO_GREATER: "GT", ENTCO_GREATER_EQ: "GE", ENTCO_LIKE: "LIKE", ENTCO_NOT_LIKE: "NOT_LIKE",
            ENTCO_IS_NULL: "IS_NULL", ENTCO_IS_NOT_NULL: "IS_NOT_NULL", ENTCO_IN: "IN", ENTCO_NOT_IN: "NOT_IN"]
    static final Map<String, String> VALUE_KINDS = [FVT_TEXT: "TEXT", FVT_INTEGER: "NUMBER", FVT_DECIMAL: "NUMBER", FVT_BOOLEAN: "BOOLEAN"]
    /** Ruling 3b2b-17: Keep refuses date-time and list fields. The words name the kind in the refusal. */
    static final Map<String, String> REFUSED_KINDS = [FVT_DATE_TIME: "date and time", FVT_LIST: "list"]

    private static final Set<String> LIST_OPS = ["IN", "NOT_IN"] as Set
    private static final Set<String> LIKE_OPS = ["LIKE", "NOT_LIKE"] as Set
    /** The operators normalizeText applies to (prepare#CompareLine valueCheckKinds). */
    private static final Set<String> NORMALIZABLE_OPS = ["EQ", "NE", "IN", "NOT_IN", "LIKE", "NOT_LIKE"] as Set
    private static final String NUMBER_TEXT = /[+-]?(\d+(\.\d*)?|\.\d+)/

    /**
     * A connector's built-in Keep lines (keepLinesJson: a JSON text or its parsed list) as Keep rules, in sequenceNum order:
     * [{sequenceNum, label, appliesUnless: {option: value}, kinds: {field: FVT_*}, lines: [RuleSetCondition maps]}].
     * Lines name plain record fields. An entry is skipped when one of its appliesUnless options has that value in
     * extractOptions (compared as text). Null or blank text is no entries; written text that is not a JSON list (such as
     * "null"), an entry Keep cannot run, and an entry with no line at the top are data errors and throw, naming the entry:
     * read as "no built-ins" or "holds for every record" they would change which records are compared without a word.
     */
    static List<Map> declared(Object keepLinesJson, Map extractOptions) {
        Object parsed = keepLinesJson
        if (keepLinesJson instanceof CharSequence) {
            String text = keepLinesJson.toString().trim()
            if (!text) return []
            parsed = new JsonSlurper().parseText(text)
            if (!(parsed instanceof List)) throw new IllegalArgumentException("Built-in Keep lines must be a JSON list; got ${text.take(40)}.".toString())
        }
        if (parsed == null) return []
        if (!(parsed instanceof List)) throw new IllegalArgumentException("Built-in Keep lines must be a JSON list.")
        List<Map> entries = ((List) parsed).collect { Object entry ->
            if (!(entry instanceof Map)) throw new IllegalArgumentException("A built-in Keep entry must be a JSON object.")
            return (Map) entry
        }
        List<Map> rules = []
        for (Map entry : entries.sort(false) { ((it.sequenceNum ?: 0) as Integer) }) {
            Map appliesUnless = (entry.appliesUnless ?: [:]) as Map
            boolean skipped = appliesUnless.any { Object option, Object value ->
                Object given = extractOptions?.get(option as String)
                return Objects.equals(given?.toString(), value?.toString())
            }
            if (skipped) continue
            Integer sequenceNum = (entry.sequenceNum ?: 0) as Integer
            String name = "Built-in Keep entry ${sequenceNum} (${entry.label})"
            Map<String, String> fieldKinds = (entry.kinds ?: [:]) as Map<String, String>
            List<Map> lines = (entry.lines ?: []) as List<Map>
            if (!lines) throw new IllegalArgumentException("${name} has no lines.".toString())
            List<String> refusals = refusalsIn(lines, fieldKinds)
            if (refusals) throw new IllegalArgumentException("${name}: ${refusals.join('; ')}".toString())
            if (!tree(lines).get(null)) {
                throw new IllegalArgumentException("${name} has no line at the top: every line sits under another.".toString())
            }
            rules.add([id: BUILT_IN_PREFIX + sequenceNum, sequenceNum: sequenceNum, label: entry.label as String, source: "BUILT_IN",
                       tests: [[test: predicateOf(lines, fieldKinds), reason: BUILT_IN_REASON]]] as Map)
        }
        KeepRules.validate(rules)
        return rules
    }

    // ---------------------------------------------------------------------------------------------------------- refusals

    private static String kindOf(Map<String, String> fieldKinds, String field) {
        return (fieldKinds.get(field) ?: "FVT_TEXT") as String
    }

    /** One refusal per line Keep cannot run, in line order from the top lines down; empty when Keep can run every line. */
    private static List<String> refusalsIn(List<Map> lines, Map<String, String> fieldKinds) {
        List<String> out = []
        Map<Object, List<Map>> byParent = tree(lines)
        Closure visit
        visit = { Map row ->
            String seq = row.conditionSeqId as String
            switch (row.conditionTypeId) {
                case "RCT_EXISTS": out.add("Keep line ${seq}: an exists line reads a list, and Keep sets aside whole records only".toString()); return
                case "RCT_PIECE": out.add("Keep line ${seq}: a piece cannot be used in Keep; write its lines out".toString()); return
                case "RCT_COMPARE_FIELD": out.add("Keep line ${seq}: Keep compares one field with fixed values, not with another field".toString()); return
                case "RCT_COMPARE":
                    String field = row.fieldName as String ?: ""
                    String kindWords = REFUSED_KINDS.get(kindOf(fieldKinds, field))
                    if (kindWords) out.add("Keep line ${seq}: ${field} is a ${kindWords} field; Keep compares text, numbers and yes/no only".toString())
                    return
                default: (byParent.get(row.conditionSeqId) ?: []).each { visit.call(it) }
            }
        }
        (byParent.get(null) ?: []).each { visit.call(it) }
        return out
    }

    // ---------------------------------------------------------------------------------------------------------- predicates

    /** Lines by parent (null = top), each list in sequenceNum order, as prepare#RuleLines walks them. */
    private static Map<Object, List<Map>> tree(List<Map> lines) {
        List<Map> rows = (lines ?: []).sort(false) { it.sequenceNum }
        rows.each { if (!it.conditionSeqId) throw new IllegalArgumentException("Each Keep line needs a conditionSeqId.") }
        Set<Object> ids = rows.collect { it.conditionSeqId } as Set
        rows.each { Map row ->
            if (row.parentConditionSeqId != null && !ids.contains(row.parentConditionSeqId)) {
                throw new IllegalArgumentException("Keep line ${row.conditionSeqId}: its parent ${row.parentConditionSeqId} is not a line of this rule".toString())
            }
        }
        return rows.groupBy { it.parentConditionSeqId } as Map<Object, List<Map>>
    }

    /** The top lines all hold (declared has refused an entry with none). */
    private static Map predicateOf(List<Map> lines, Map<String, String> fieldKinds) {
        Map<Object, List<Map>> byParent = tree(lines)
        List<Map> nodes = byParent.get(null).collect { node(it, byParent, fieldKinds) }
        return nodes.size() == 1 ? nodes[0] : [kind: "all", of: nodes]
    }

    private static Map node(Map row, Map<Object, List<Map>> byParent, Map<String, String> fieldKinds) {
        List<Map> children = byParent.get(row.conditionSeqId) ?: []
        String seq = row.conditionSeqId as String
        switch (row.conditionTypeId) {
            case "RCT_COMPARE":
                if (children) throw new IllegalArgumentException("Keep line ${seq}: a compare line takes no lines under it".toString())
                return compare(row, fieldKinds)
            case "RCT_ALL": case "RCT_ANY": case "RCT_NOT":
                if (!children) throw new IllegalArgumentException("Keep line ${seq}: a ${row.conditionTypeId} line needs at least one line under it".toString())
                List<Map> of = children.collect { node(it, byParent, fieldKinds) }
                if (row.conditionTypeId == "RCT_ALL") return [kind: "all", of: of]
                if (row.conditionTypeId == "RCT_ANY") return [kind: "any", of: of]
                // a not line holds when no line under it holds (prepare#NotLine: the AND of each line turned around)
                return [kind: "not", of: of.size() == 1 ? of[0] : [kind: "any", of: of]]
            default:
                throw new IllegalArgumentException("Keep line ${seq}: condition type ${row.conditionTypeId} is not known".toString())
        }
    }

    private static Map compare(Map row, Map<String, String> fieldKinds) {
        String seq = row.conditionSeqId as String
        String field = row.fieldName as String
        String kindId = kindOf(fieldKinds, field)
        String valueKind = VALUE_KINDS.get(kindId)
        if (valueKind == null) throw new IllegalArgumentException("Keep line ${seq}: ${field} has a kind Keep does not know: ${kindId}".toString())
        String op = OPS.get(row.operatorEnumId as String)
        if (op == null) throw new IllegalArgumentException("Keep line ${seq}: operator ${row.operatorEnumId} is not allowed".toString())
        if (LIKE_OPS.contains(op) && valueKind != "TEXT") {
            throw new IllegalArgumentException("Keep line ${seq}: operator ${row.operatorEnumId} works on text fields only".toString())
        }
        Object flag = row.normalizeText
        if (flag != null && !(flag in ["Y", "N", ""])) {
            throw new IllegalArgumentException("Keep line ${seq}: normalizeText ${flag} on field ${field} is not Y, N or empty".toString())
        }
        boolean normalize = flag == "Y"
        if (normalize && kindId != "FVT_TEXT") {
            throw new IllegalArgumentException("Keep line ${seq}: field ${field} is not text, so it cannot ignore case and surrounding spaces".toString())
        }
        if (normalize && !NORMALIZABLE_OPS.contains(op)) {
            throw new IllegalArgumentException("Keep line ${seq}: operator ${row.operatorEnumId} on field ${field} cannot ignore case and surrounding spaces".toString())
        }
        List<Object> values = compareValues(seq, field, kindId, op, normalize, row.compareValue)
        String fold = normalize ? "NORMALIZE" : (LIKE_OPS.contains(op) ? "LIKE_UPPER" : "NONE")
        return [kind: "compare", field: field, text: "FACT", op: op,
                values: values == null ? null : values.collect { valueText(it) }, valueKind: valueKind, fold: fold]
    }

    /** The compare values converted to the field's kind, as prepare#CompareLine converts them; null for IS_NULL/IS_NOT_NULL. */
    private static List<Object> compareValues(String seq, String field, String kindId, String op, boolean normalize, Object compareValue) {
        if (KeepRules.NO_VALUE_OPS.contains(op)) return null
        List<Object> out = []
        if (LIST_OPS.contains(op)) {
            Object parsed = compareValue
            if (!(parsed instanceof List)) {
                try { parsed = new JsonSlurper().parseText(compareValue?.toString() ?: "") } catch (Exception ignored) { parsed = null }
            }
            if (!(parsed instanceof List)) throw new IllegalArgumentException("Keep line ${seq}: value ${compareValue} for field ${field} is not a JSON list".toString())
            if (((List) parsed).isEmpty()) throw new IllegalArgumentException("Keep line ${seq}: the list for field ${field} is empty".toString())
            for (Object element : (List) parsed) {
                if (!(element instanceof CharSequence || element instanceof Number || element instanceof Boolean)) {
                    throw new IllegalArgumentException("Keep line ${seq}: the list for field ${field} holds ${element} which is not a text, a number, true or false".toString())
                }
                // a JSON number is its plain text: 1e2 is 100 on a text field and a whole number on a number field
                Map converted = convert(kindId, element instanceof BigDecimal ? ((BigDecimal) element).toPlainString() : element)
                if (converted.problem) throw new IllegalArgumentException("Keep line ${seq}: value ${element} in the list does not fit field ${field}".toString())
                if (normalize && ValueChecks.normalizedText(converted.value) == null) {
                    throw new IllegalArgumentException("Keep line ${seq}: value \"${element}\" for field ${field} is no value once case and surrounding spaces are ignored".toString())
                }
                out.add(converted.value)
            }
            return out
        }
        Object single = compareValue instanceof BigDecimal ? ((BigDecimal) compareValue).toPlainString() : compareValue
        Map converted = convert(kindId, single)
        if (single == null || converted.problem) throw new IllegalArgumentException("Keep line ${seq}: value ${compareValue} does not fit field ${field}".toString())
        if (normalize && ValueChecks.normalizedText(converted.value) == null) {
            throw new IllegalArgumentException("Keep line ${seq}: value \"${compareValue}\" for field ${field} is no value once case and surrounding spaces are ignored".toString())
        }
        out.add(converted.value)
        return out
    }

    /** moqui-rules convert#FactValue for the kinds Keep compares: [value] or [problem]. */
    // A known copy (no ec here to call the service): it must track moqui-rules convert#FactValue (number text, whole-number bounds, yes/no words).
    private static Map convert(String kindId, Object value) {
        if (value == null) return [value: null]
        String text = value.toString().trim()
        BigDecimal number = value instanceof Number ? new BigDecimal(value.toString()) : (text ==~ NUMBER_TEXT ? new BigDecimal(text) : null)
        switch (kindId) {
            case "FVT_TEXT": return [value: value.toString()]
            case "FVT_INTEGER":
                if (number == null || number.stripTrailingZeros().scale() > 0 || number.abs() > Long.MAX_VALUE) return [problem: "${value} is not a whole number"]
                return [value: number.longValue()]
            case "FVT_DECIMAL": return number == null ? [problem: "${value} is not a number"] : [value: number]
            case "FVT_BOOLEAN":
                String yesNo = value instanceof Boolean ? value.toString() : KeepRules.yesNo(text)
                return yesNo == null ? [problem: "${value} is not yes or no"] : [value: yesNo == "true"]
            default: return [problem: "value kind ${kindId} is not one Keep compares"]
        }
    }

    private static String valueText(Object value) {
        return value instanceof BigDecimal ? ((BigDecimal) value).toPlainString() : value.toString()
    }
}
