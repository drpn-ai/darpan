package darpan.reconciliation.source

import groovy.json.JsonSlurper
import org.moqui.entity.EntityCondition.ComparisonOperator
import org.moqui.impl.entity.EntityConditionFactoryImpl
import org.moqui.rules.ValueChecks

/**
 * DAR-BE-318 (plan 3b-2b, front-end B). Turns moqui-rules "when" lines (RuleSetCondition maps, a tree by
 * parentConditionSeqId) into the one Keep rule shape (KeepRules), meaning exactly what prepare#CompareLine and the
 * all/any/not lines mean in moqui-rules 0.2.0. Lines Keep cannot run are refused with the reason. Built-in declarations
 * (SourceSystemConnector.keepLinesJson, Ruling 3b2b-12) go through the same translation on plain record fields.
 *
 * Fields. A decision's lines name pair fact fields: <sidePrefix>_<key> is a field of the side's record, param_<name> is a
 * check parameter (a const, judged here from params), ask_* comes from an ask, and anything else (the other side,
 * <sidePrefix>_presence, claim, compareId) is not a record field. A declared line names the record key itself.
 *
 * Values. Compare values and list elements are converted to the field's kind as convert#FactValue does; the predicate
 * carries them as text (a number as its plain text with its scale, yes/no as true or false), and the Spark builder folds
 * and casts both sides. A param line is judged here by moqui-rules' ValueChecks and Moqui's in-memory condition, never a
 * copy of them. This class holds no system name and no Spark dependency.
 */
class KeepLineTranslator {

    static final String DECISION_PREFIX = "B:"
    static final String BUILT_IN_PREFIX = "BUILT_IN:"
    static final String BUILT_IN_REASON = "BUILT_IN"
    static final String PARAM_PREFIX = "param_"
    static final String ASK_PREFIX = "ask_"

    static final Map<String, String> OPS = [ENTCO_EQUALS: "EQ", ENTCO_NOT_EQUALS: "NE", ENTCO_LESS: "LT", ENTCO_LESS_EQ: "LE",
            ENTCO_GREATER: "GT", ENTCO_GREATER_EQ: "GE", ENTCO_LIKE: "LIKE", ENTCO_NOT_LIKE: "NOT_LIKE",
            ENTCO_IS_NULL: "IS_NULL", ENTCO_IS_NOT_NULL: "IS_NOT_NULL", ENTCO_IN: "IN", ENTCO_NOT_IN: "NOT_IN"]
    static final Map<String, String> VALUE_KINDS = [FVT_TEXT: "TEXT", FVT_INTEGER: "NUMBER", FVT_DECIMAL: "NUMBER", FVT_BOOLEAN: "BOOLEAN"]
    /** Ruling 3b2b-17: Keep refuses date-time and list fields. The words name the kind in the refusal. */
    static final Map<String, String> REFUSED_KINDS = [FVT_DATE_TIME: "date and time", FVT_LIST: "list"]

    private static final Set<String> LIST_OPS = ["IN", "NOT_IN"] as Set
    private static final Set<String> LIKE_OPS = ["LIKE", "NOT_LIKE"] as Set
    private static final Set<String> NO_VALUE_OPS = KeepRules.NO_VALUE_OPS
    /** The operators normalizeText applies to (prepare#CompareLine valueCheckKinds). */
    private static final Set<String> NORMALIZABLE_OPS = ["EQ", "NE", "IN", "NOT_IN", "LIKE", "NOT_LIKE"] as Set
    private static final String NUMBER_TEXT = /[+-]?(\d+(\.\d*)?|\.\d+)/

    /**
     * decisionRules: the Keep decision's rules, each [ruleId, ruleName, priority, outcomeId (or actions with an outcomeId),
     * conditions: RuleSetCondition maps]. fieldKinds: fact field name to FVT_* (a field not named is text, as the pair fact
     * type generates it). params: the check's parameters by name. recordKeys (required): this side's fact field name to the
     * record key it was named from (generate#PairFactType keyNames, inverted). A field's key is never derived from its name:
     * nameKeys rewrites characters, cuts at 40 and adds _2 on a case clash, so a record field missing from recordKeys throws.
     *
     * Rules come out in moqui-rules' firing order (highest priority first), id "B:<ruleId>", sequenceNum the priority,
     * label the rule name, reason the outcome. A rule with a refused line is left out and its refusals listed: the caller
     * treats any refusal as an error, since leaving a rule out changes which rule owns a record.
     */
    static Map translate(List<Map> decisionRules, String sidePrefix, Map<String, String> fieldKinds, Map<String, Object> params,
                         Map<String, String> recordKeys) {
        if (!sidePrefix) throw new IllegalArgumentException("Keep lines need the side's prefix.")
        if (recordKeys == null) throw new IllegalArgumentException("Keep lines need the record key of each ${sidePrefix} fact field.".toString())
        Scope scope = new Scope(sidePrefix: sidePrefix, fieldKinds: fieldKinds ?: [:], params: params ?: [:], recordKeys: recordKeys)
        List<Map> ordered = (decisionRules ?: []).sort(false) { Map a, Map b ->
            ((b.priority ?: 0) as Integer) <=> ((a.priority ?: 0) as Integer) ?: (a.ruleName as String) <=> (b.ruleName as String) }
        List<Map> rules = []
        List<String> refusals = []
        for (Map decisionRule : ordered) {
            List<Map> lines = (decisionRule.conditions ?: []) as List<Map>
            List<String> ruleRefusals = refusalsIn(lines, scope)
            if (ruleRefusals) { refusals.addAll(ruleRefusals); continue }
            String ruleName = decisionRule.ruleName as String
            rules.add([id: DECISION_PREFIX + decisionRule.ruleId, sequenceNum: (decisionRule.priority ?: 0) as Integer, label: ruleName,
                       source: "DECISION", tests: [[test: predicateOf(lines, scope), reason: outcomeOf(decisionRule) ?: ruleName]]] as Map)
        }
        KeepRules.validate(rules)
        return [rules: rules, refusals: refusals]
    }

    /** One refusal per line Keep cannot run, in line order; empty when Keep can run every line. Used at save (Task 8). */
    static List<String> refusals(List<Map> lines, String sidePrefix, Map<String, String> fieldKinds) {
        if (!sidePrefix) throw new IllegalArgumentException("Keep lines need the side's prefix.")
        return refusalsIn(lines ?: [], new Scope(sidePrefix: sidePrefix, fieldKinds: fieldKinds ?: [:], params: [:]))
    }

    /**
     * A connector's built-in Keep lines (keepLinesJson: a JSON text or its parsed list) as Keep rules, in sequenceNum order:
     * [{sequenceNum, label, appliesUnless: {option: value}, kinds: {field: FVT_*}, lines: [RuleSetCondition maps]}].
     * Lines name plain record fields. An entry is skipped when one of its appliesUnless options has that value in
     * extractOptions (compared as text). A refused line is a data error and throws, naming the entry.
     */
    static List<Map> declared(Object keepLinesJson, Map extractOptions) {
        Object parsed = keepLinesJson instanceof CharSequence ?
                (keepLinesJson.toString().trim() ? new JsonSlurper().parseText(keepLinesJson.toString()) : null) : keepLinesJson
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
            Scope scope = new Scope(sidePrefix: null, fieldKinds: ((entry.kinds ?: [:]) as Map<String, String>), params: [:])
            List<Map> lines = (entry.lines ?: []) as List<Map>
            List<String> entryRefusals = refusalsIn(lines, scope)
            if (entryRefusals) {
                throw new IllegalArgumentException("Built-in Keep entry ${sequenceNum} (${entry.label}): ${entryRefusals.join('; ')}".toString())
            }
            if (!lines) throw new IllegalArgumentException("Built-in Keep entry ${sequenceNum} (${entry.label}) has no lines.".toString())
            rules.add([id: BUILT_IN_PREFIX + sequenceNum, sequenceNum: sequenceNum, label: entry.label as String, source: "BUILT_IN",
                       tests: [[test: predicateOf(lines, scope), reason: BUILT_IN_REASON]]] as Map)
        }
        KeepRules.validate(rules)
        return rules
    }

    // ---------------------------------------------------------------------------------------------------------- fields

    /** sidePrefix null = a declared entry: every field is a plain record field, named by its record key. */
    private static class Scope {
        String sidePrefix
        Map<String, String> fieldKinds
        Map<String, Object> params
        Map<String, String> recordKeys

        String kindOf(String field) { return (fieldKinds.get(field) ?: "FVT_TEXT") as String }

        /** RECORD, PARAM, ASK or OTHER. */
        String roleOf(String field) {
            if (sidePrefix == null) return "RECORD"
            if (field.startsWith(PARAM_PREFIX)) return "PARAM"
            if (field.startsWith(ASK_PREFIX)) return "ASK"
            if (field == sidePrefix + "_presence") return "OTHER"
            if (field.startsWith(sidePrefix + "_") && field.length() > sidePrefix.length() + 1) return "RECORD"
            return "OTHER"
        }

        /** A declared field is its record key; a decision's field maps only through recordKeys. */
        String recordKeyOf(String seq, String field) {
            if (sidePrefix == null) return field
            String key = recordKeys?.get(field)
            if (!key) throw new IllegalArgumentException("Keep line ${seq}: fact field ${field} has no record key on the ${sidePrefix} side".toString())
            return key
        }
    }

    // ---------------------------------------------------------------------------------------------------------- refusals

    private static List<String> refusalsIn(List<Map> lines, Scope scope) {
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
                    String refusal = fieldRefusal(seq, row.fieldName as String, scope)
                    if (refusal) out.add(refusal)
                    return
                default: (byParent.get(row.conditionSeqId) ?: []).each { visit.call(it) }
            }
        }
        (byParent.get(null) ?: []).each { visit.call(it) }
        return out
    }

    private static String fieldRefusal(String seq, String field, Scope scope) {
        String name = field ?: ""
        switch (scope.roleOf(name)) {
            case "ASK": return "Keep line ${seq}: ${name} comes from an ask, which runs after Keep".toString()
            case "OTHER": return "Keep line ${seq}: ${name} is not a ${scope.sidePrefix} record field".toString()
            default:
                String kindWords = REFUSED_KINDS.get(scope.kindOf(name))
                return kindWords ? "Keep line ${seq}: ${name} is a ${kindWords} field; Keep compares text, numbers and yes/no only".toString() : null
        }
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

    /** Top lines all hold; a rule with no lines holds for every record (moqui-rules: it fires once per fact). */
    private static Map predicateOf(List<Map> lines, Scope scope) {
        Map<Object, List<Map>> byParent = tree(lines)
        List<Map> top = byParent.get(null) ?: []
        if (!top) return [kind: "const", holds: true]
        List<Map> nodes = top.collect { node(it, byParent, scope) }
        return nodes.size() == 1 ? nodes[0] : [kind: "all", of: nodes]
    }

    private static Map node(Map row, Map<Object, List<Map>> byParent, Scope scope) {
        List<Map> children = byParent.get(row.conditionSeqId) ?: []
        String seq = row.conditionSeqId as String
        switch (row.conditionTypeId) {
            case "RCT_COMPARE":
                if (children) throw new IllegalArgumentException("Keep line ${seq}: a compare line takes no lines under it".toString())
                return compare(row, scope)
            case "RCT_ALL": case "RCT_ANY": case "RCT_NOT":
                if (!children) throw new IllegalArgumentException("Keep line ${seq}: a ${row.conditionTypeId} line needs at least one line under it".toString())
                List<Map> of = children.collect { node(it, byParent, scope) }
                if (row.conditionTypeId == "RCT_ALL") return [kind: "all", of: of]
                if (row.conditionTypeId == "RCT_ANY") return [kind: "any", of: of]
                // a not line holds when no line under it holds (prepare#NotLine: the AND of each line turned around)
                return [kind: "not", of: of.size() == 1 ? of[0] : [kind: "any", of: of]]
            default:
                throw new IllegalArgumentException("Keep line ${seq}: condition type ${row.conditionTypeId} is not known".toString())
        }
    }

    private static Map compare(Map row, Scope scope) {
        String seq = row.conditionSeqId as String
        String field = row.fieldName as String
        String kindId = scope.kindOf(field)
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

        if (scope.roleOf(field) == "PARAM") {
            return [kind: "const", holds: paramHolds(seq, field, kindId, op, normalize, values, scope.params.get(field.substring(PARAM_PREFIX.length())))]
        }
        return [kind: "compare", field: scope.recordKeyOf(seq, field), text: "FACT", op: op,
                values: values == null ? null : values.collect { valueText(it) }, valueKind: valueKind, fold: fold]
    }

    /** The compare values converted to the field's kind, as prepare#CompareLine converts them; null for IS_NULL/IS_NOT_NULL. */
    private static List<Object> compareValues(String seq, String field, String kindId, String op, boolean normalize, Object compareValue) {
        if (NO_VALUE_OPS.contains(op)) return null
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
                Object yesNo = value instanceof Boolean ? value : ["true": true, "y": true, "false": false, "n": false].get(text.toLowerCase())
                return yesNo == null ? [problem: "${value} is not yes or no"] : [value: yesNo]
            default: return [problem: "value kind ${kindId} is not one Keep compares"]
        }
    }

    private static String valueText(Object value) {
        return value instanceof BigDecimal ? ((BigDecimal) value).toPlainString() : value.toString()
    }

    private static String outcomeOf(Map decisionRule) {
        if (decisionRule.outcomeId) return decisionRule.outcomeId as String
        Map action = ((decisionRule.actions ?: []) as List<Map>).find { it?.outcomeId }
        return action?.outcomeId as String
    }

    // ---------------------------------------------------------------------------------------------------------- params

    /** The value check each operator is when the line normalizes or is a like (prepare#CompareLine valueCheckKinds). */
    private static final Map<String, String> VALUE_CHECK_KINDS = [EQ: "=", NE: "!=", IN: "in", NOT_IN: "not in", LIKE: "like", NOT_LIKE: "not like"]
    /** The Moqui condition every other line is (prepare#CompareLine, comparisonOperatorFromEnumId). */
    private static final Map<String, ComparisonOperator> CONDITION_OPS = [EQ: ComparisonOperator.EQUALS, NE: ComparisonOperator.NOT_EQUAL,
            LT: ComparisonOperator.LESS_THAN, LE: ComparisonOperator.LESS_THAN_EQUAL_TO, GT: ComparisonOperator.GREATER_THAN,
            GE: ComparisonOperator.GREATER_THAN_EQUAL_TO, IS_NULL: ComparisonOperator.IS_NULL, IS_NOT_NULL: ComparisonOperator.IS_NOT_NULL]

    /**
     * A param line judged now: the param converted to the field's kind (a missing param is no value), then the line as
     * moqui-rules judges it, by moqui-rules' and Moqui's own code. A normalized line and every like is a value check
     * (ValueChecks.fixedValue). Any other line is a Moqui condition judged in memory (EntityConditionFactoryImpl.compareByOperator):
     * in is one equals per value, not in one not-equal per value, as prepare#CompareLine builds them.
     */
    private static boolean paramHolds(String seq, String field, String kindId, String op, boolean normalize, List<Object> values, Object raw) {
        Map converted = convert(kindId, raw)
        if (converted.problem) throw new IllegalArgumentException("Keep line ${seq}: parameter value ${raw} does not fit field ${field}".toString())
        Object fact = converted.value
        if (normalize || LIKE_OPS.contains(op)) return ValueChecks.fixedValue(VALUE_CHECK_KINDS.get(op), fact, values, normalize)
        if (op == "IN") return values.any { EntityConditionFactoryImpl.compareByOperator(fact, ComparisonOperator.EQUALS, it) }
        if (op == "NOT_IN") return values.every { EntityConditionFactoryImpl.compareByOperator(fact, ComparisonOperator.NOT_EQUAL, it) }
        return EntityConditionFactoryImpl.compareByOperator(fact, CONDITION_OPS.get(op), values ? values[0] : null)
    }
}
