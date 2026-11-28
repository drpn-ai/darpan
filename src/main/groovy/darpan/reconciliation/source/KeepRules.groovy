package darpan.reconciliation.source

/**
 * DAR-BE-318 (plan 3b-2b). The one Keep rule shape. Both front-ends (the tenant's source filters, SourceFilterSupport.toKeepRules,
 * and a connector's built-in Keep lines, KeepLineTranslator.declared) produce a List of these maps, in evaluation order; the first
 * rule whose test holds owns the record. This class only defines the shape's constants and checks it; it holds no system
 * name and no system logic.
 *
 * rule      = [id, sequenceNum, label, source: TENANT|BUILT_IN, tests: [[test: predicate, reason], ...]]
 * predicate = [kind: "compare", field, text: GROOVY|FACT, op, values, valueKind, fold]
 *           | [kind: "all"|"any", of: [predicate]] | [kind: "not", of: predicate] | [kind: "const", holds: boolean]
 */
class KeepRules {

    /** The record's 0-based ordinal among the object entries of the records array. */
    static final String ROW_COLUMN = "_darpanRow"
    /** "<rule id>|<reason>", or null for a kept record. */
    static final String REASON_COLUMN = "_darpanKeepReason"
    static final String INJECTED_PREFIX = "_darpanKeep_"

    static final Set<String> SOURCES = ["TENANT", "BUILT_IN"] as Set
    static final Set<String> TEXTS = ["GROOVY", "FACT"] as Set
    static final Set<String> OPS = ["IN", "NOT_IN", "STARTS_WITH_ANY", "EQ", "NE", "LT", "LE", "GT", "GE", "LIKE",
                                    "NOT_LIKE", "IS_NULL", "IS_NOT_NULL"] as Set
    static final Set<String> NO_VALUE_OPS = ["IS_NULL", "IS_NOT_NULL"] as Set
    static final Set<String> VALUE_KINDS = ["TEXT", "NUMBER", "BOOLEAN"] as Set
    static final Set<String> FOLDS = ["A_UPPER", "NORMALIZE", "LIKE_UPPER", "NONE"] as Set
    /**
     * The ops that hold for a record with no value (moqui-rules ValueChecks.fixedValue and Moqui's in-memory condition, where
     * Groovy orders null before every value). The one list: the Spark builder (KeepColumnSupport) decides by it, and the
     * landed-file check (KeepPlanSupport.landedFilteredAtRead) uses it to know when a missing flag says nothing.
     */
    static final Set<String> HOLDS_FOR_NO_VALUE = ["NE", "NOT_IN", "NOT_LIKE", "LT", "LE", "IS_NULL"] as Set

    /** A yes/no as "true" or "false", from true, false, y or n in any case, trimmed; anything else is null (no value). */
    static String yesNo(Object value) {
        switch (value?.toString()?.trim()?.toLowerCase(Locale.ROOT)) {
            case "true": case "y": return "true"
            case "false": case "n": return "false"
            default: return null
        }
    }

    /**
     * Throws IllegalArgumentException, naming the rule, for a rule with no tests or a test with an unknown kind or op, and for
     * two rules with one id: every count Keep reports is kept by rule id, so a shared id would give each rule the other's records.
     */
    static void validate(List<Map> rules) {
        Set<String> ids = new HashSet<>()
        for (Map rule : (rules ?: [])) {
            String name = "Keep rule ${rule?.get('id') ?: '(no id)'}"
            if (!(rule?.get("id") as String)) throw new IllegalArgumentException("${name} has no id.")
            if (!ids.add(rule.get("id") as String)) {
                throw new IllegalArgumentException("${name} appears twice; each rule's counts are kept by its id.")
            }
            if (!SOURCES.contains(rule.get("source"))) {
                throw new IllegalArgumentException("${name} has an unknown source: ${rule.get('source')}.")
            }
            List tests = rule.get("tests") as List
            if (!tests) throw new IllegalArgumentException("${name} has no tests.")
            for (Object raw : tests) {
                Map entry = raw instanceof Map ? (Map) raw : null
                if (entry == null || !(entry.get("reason") as String) || !(entry.get("test") instanceof Map)) {
                    throw new IllegalArgumentException("${name} has a test without a predicate and a reason.")
                }
                checkPredicate(name, (Map) entry.get("test"))
            }
        }
    }

    /** [column, field, text] for each distinct (field, text) the rules name, in first-seen order; columns number from 0. */
    static List<Map> injections(List<Map> rules) {
        Map<String, Map> seen = new LinkedHashMap<>()
        for (Map rule : (rules ?: [])) {
            for (Object entry : (rule.get("tests") as List ?: [])) walk((Map) ((Map) entry).get("test")) { Map compare ->
                String key = "${compare.get('field')}|${compare.get('text')}".toString()
                if (!seen.containsKey(key)) {
                    seen.put(key, [column: INJECTED_PREFIX + seen.size(), field: compare.get("field"), text: compare.get("text")] as Map)
                }
            }
        }
        return new ArrayList<Map>(seen.values())
    }

    /** Every field the rules name, in first-seen order. */
    static Set<String> fields(List<Map> rules) {
        Set<String> out = new LinkedHashSet<>()
        injections(rules).each { out.add(it.field as String) }
        return out
    }

    private static void walk(Map predicate, Closure onCompare) {
        switch (predicate?.get("kind")) {
            case "compare": onCompare.call(predicate); break
            case "all": case "any": ((List) predicate.get("of")).each { walk((Map) it, onCompare) }; break
            case "not": walk((Map) predicate.get("of"), onCompare); break
            default: break
        }
    }

    private static void checkPredicate(String name, Map predicate) {
        switch (predicate.get("kind")) {
            case "compare":
                if (!(predicate.get("field") as String)) throw new IllegalArgumentException("${name} compares no field.")
                if (!TEXTS.contains(predicate.get("text"))) {
                    throw new IllegalArgumentException("${name} has an unknown text kind: ${predicate.get('text')}.")
                }
                if (!OPS.contains(predicate.get("op"))) {
                    throw new IllegalArgumentException("${name} has an unknown op: ${predicate.get('op')}.")
                }
                if (!NO_VALUE_OPS.contains(predicate.get("op")) && !(predicate.get("values") instanceof List)) {
                    throw new IllegalArgumentException("${name} has an op ${predicate.get('op')} without values.")
                }
                if (!VALUE_KINDS.contains(predicate.get("valueKind"))) {
                    throw new IllegalArgumentException("${name} has an unknown valueKind: ${predicate.get('valueKind')}.")
                }
                if (!FOLDS.contains(predicate.get("fold"))) {
                    throw new IllegalArgumentException("${name} has an unknown fold: ${predicate.get('fold')}.")
                }
                break
            case "all": case "any":
                if (!(predicate.get("of") instanceof List) || !((List) predicate.get("of"))) {
                    throw new IllegalArgumentException("${name} has an empty ${predicate.get('kind')}.")
                }
                ((List) predicate.get("of")).each {
                    if (!(it instanceof Map)) throw new IllegalArgumentException("${name} has a malformed predicate.")
                    checkPredicate(name, (Map) it)
                }
                break
            case "not":
                if (!(predicate.get("of") instanceof Map)) throw new IllegalArgumentException("${name} has a not without a predicate.")
                checkPredicate(name, (Map) predicate.get("of"))
                break
            case "const":
                if (!(predicate.get("holds") instanceof Boolean)) throw new IllegalArgumentException("${name} has a const without holds.")
                break
            default:
                throw new IllegalArgumentException("${name} has an unknown predicate kind: ${predicate.get('kind')}.")
        }
    }
}
