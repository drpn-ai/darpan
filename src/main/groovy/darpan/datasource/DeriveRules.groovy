package darpan.datasource

import java.time.Instant

/**
 * DAR-BE-176. The rows of a derive step, as data: each `{set, when, value}` writes one field of a record. DeriveEngine
 * compiles the rows through one fixed Drools template (rule number and salience only, no definition text in the DRL)
 * and fires them; this class is what each rule calls.
 *
 *   set    the field written (one word)
 *   when   RecordConditions, read on the record as it entered the step; absent = always
 *   value  {literal}, {copy: path}, {map: {..}, from: path, default}, or {fn, args: [values], sep}; a copy path through a
 *          list (`opps[*].method`) gives the list of every value it reaches
 *          fn: text (trimmed text, blank = null), decimal (text that parses as a number becomes one; anything else is
 *          kept), decimalPlain (a number with no trailing zeros; blank = null), epochMillis (an ISO instant becomes epoch
 *          millis; anything else, numbers included, is kept), join (non-blank args joined by sep), firstPresent, units
 *          (a quantity as whole units: parse STRICT_INT|NUMERIC, nullAs, as the repeat step reads it), size (how many in a
 *          list or object; null is 0, any other value 1), distinctSorted (the args' non-blank values, lists flattened,
 *          trimmed, once each, sorted, joined by sep (default ","); none gives empty text), first (a list's first value,
 *          null when empty; any other value itself), atLeast (true when the first arg is a number at least the second),
 *          firstWhere {list, when, field} (field of the list's first element matching when, else null), multiply and sum
 *          (numbers; blank counts as 0; sum flattens lists), daysBetween (whole days from the second epoch-millis arg to
 *          the first, floored; null unless both are numbers),
 *          without (the first arg's list less every value equal to a later arg); join flattens list args
 *
 * Rows fire in order, so a later row's value may read a field an earlier row set, and a later row for the same field
 * overrides an earlier one (a default first, the cases after). Pure.
 */
class DeriveRules {

    static final List<String> FUNCTIONS = ["text", "decimal", "decimalPlain", "epochMillis", "join", "firstPresent", "units",
                                           "size", "distinctSorted", "first", "atLeast", "without", "firstWhere", "multiply",
                                           "sum", "daysBetween"].asImmutable()
    private static final String NAME = /[A-Za-z][A-Za-z0-9_]{0,59}/
    private static final String PATH = RecordConditions.FIELD

    static boolean matches(Map record, Map rule) {
        return RecordConditions.matches(record, rule.when)
    }

    static void apply(Map record, Map rule) {
        record.put((String) rule.set, valueOf(record, (Map) rule.value))
    }

    static Object valueOf(Map record, Map v) {
        if (v.containsKey("literal")) return v.literal
        if (v.copy != null) {
            String path = (String) v.copy
            return path.contains("[*]") ? JsonPathValues.values(record, path) : JsonPathValues.value(record, path)
        }
        if (v.map instanceof Map) {
            Object key = JsonPathValues.value(record, (String) v.from)
            Map m = (Map) v.map
            return key != null && m.containsKey(key.toString()) ? m.get(key.toString()) : v.get("default")
        }
        List<Object> args = ((v.args ?: []) as List).collect { Object a -> a instanceof Map ? valueOf(record, (Map) a) : a }
        Object first = args ? args[0] : null
        switch (v.fn) {
            case "text":
                String s = first?.toString()?.trim()
                return s ? s : null
            case "decimal":
                if (!(first instanceof CharSequence)) return first
                BigDecimal d = parseDecimal(first)
                return d != null ? d : first
            case "decimalPlain":
                String text = first?.toString()?.trim()
                if (!text) return null
                BigDecimal p = parseDecimal(text)
                return p != null ? new BigDecimal(p.stripTrailingZeros().toPlainString()) : first
            case "epochMillis":
                return epochMillis(first)
            case "join":
                List<String> parts = args.collectMany { it instanceof Collection ? ((Collection) it).toList() : [it] }
                        .collect { it?.toString()?.trim() }.findAll { it }
                return parts ? parts.join((v.sep ?: " ") as String) : null
            case "firstPresent":
                return args.find { it != null && it.toString().trim() }
            case "units":
                return DataSourceRunner.units(first, (v.parse ?: "STRICT_INT") as String, ((v.nullAs != null ? v.nullAs : 1) as Number).intValue())
            case "size":
                if (first == null) return 0
                if (first instanceof Collection) return ((Collection) first).size()
                if (first instanceof Map) return ((Map) first).size()
                return 1
            case "distinctSorted":
                List<String> values = args.collectMany { it instanceof Collection ? ((Collection) it).toList() : [it] }
                        .collect { it?.toString()?.trim() }.findAll { it }.unique().sort()
                return values.join((v.sep ?: ",") as String)
            case "first":
                if (first instanceof Collection) return ((Collection) first).isEmpty() ? null : ((Collection) first).iterator().next()
                return first
            case "without":
                List<Object> list = first instanceof Collection ? ((Collection) first).toList() : (first == null ? [] : [first])
                List<Object> drop = args.drop(1)
                return list.findAll { Object x -> !drop.any { RecordConditions.same(x, it) } }
            case "firstWhere":
                // A path to a list gives the list as one value: its elements are what is searched.
                Object found = JsonPathValues.values(record, (String) v.list).collectMany { it instanceof Collection ? ((Collection) it).toList() : [it] }
                        .find { it instanceof Map && RecordConditions.matches((Map) it, v.when) }
                return found == null ? null : JsonPathValues.value(found, (String) v.field)
            case "multiply":
                return args.inject(BigDecimal.ONE) { BigDecimal p, Object a -> p * zeroIfBlank(a) }
            case "sum":
                return args.collectMany { it instanceof Collection ? ((Collection) it).toList() : [it] }
                        .inject(BigDecimal.ZERO) { BigDecimal t, Object a -> t + zeroIfBlank(a) }
            case "daysBetween":
                if (!(first instanceof Number) || !(args.size() > 1 && args[1] instanceof Number)) return null
                return Math.floorDiv(((Number) first).longValue() - ((Number) args[1]).longValue(), 86_400_000L)
            case "atLeast":
                BigDecimal a = RecordConditions.number(first), b = RecordConditions.number(args.size() > 1 ? args[1] : null)
                return a != null && b != null && a >= b
            default:
                throw new IllegalArgumentException("A derive value uses ${FUNCTIONS.join(', ')}, not '${v.fn}'.")
        }
    }

    /** A number for arithmetic: blank (or not a number) counts as 0, as a missing price or quantity does. */
    private static BigDecimal zeroIfBlank(Object v) {
        BigDecimal n = RecordConditions.isBlank(v) ? null : parseDecimal(v)
        return n ?: BigDecimal.ZERO
    }

    static Object epochMillis(Object v) {
        if (!(v instanceof CharSequence)) return v
        try { return Instant.parse(v.toString()).toEpochMilli() } catch (Exception ignored) { return v }
    }

    private static BigDecimal parseDecimal(Object v) {
        try { return new BigDecimal(v.toString().trim()) } catch (Exception ignored) { return null }
    }

    /** Words for each problem in a derive step's rows. */
    static List<String> check(String where, Object rules) {
        if (!(rules instanceof List) || ((List) rules).isEmpty()) return ["${where}: a derive step needs rules.".toString()]
        List<String> errors = []
        ((List) rules).eachWithIndex { Object o, int i ->
            String at = "${where}, rule ${i + 1}"
            if (!(o instanceof Map)) { errors << "${at} must be an object.".toString(); return }
            Map r = (Map) o
            if (!(r.set ==~ NAME)) errors << "${at} needs set, the field it writes.".toString()
            errors.addAll(RecordConditions.check(at, r.when))
            errors.addAll(checkValue(at, r.value, 0))
        }
        return errors
    }

    /** Words for each problem in one value spec, for steps that compute values outside derive rows (a drop's sidecar). */
    static List<String> checkValueSpec(String at, Object raw) {
        return checkValue(at, raw, 0)
    }

    private static List<String> checkValue(String at, Object raw, int depth) {
        if (!(raw instanceof Map)) return ["${at}: value is {literal}, {copy}, {map, from} or {fn, args}.".toString()]
        if (depth > 4) return ["${at}: values nest at most 4 deep.".toString()]
        Map v = (Map) raw
        int kinds = [v.containsKey("literal"), v.copy != null, v.map != null, v.fn != null].count { it }
        if (kinds != 1) return ["${at}: value is exactly one of literal, copy, map or fn.".toString()]
        if (v.copy != null && !(v.copy ==~ PATH)) return ["${at}: copy is a field path.".toString()]
        if (v.map != null) {
            if (!(v.map instanceof Map) || !(v.from ==~ PATH)) return ["${at}: map needs an object of values and from, a field path.".toString()]
            return []
        }
        if (v.fn != null) {
            if (!(v.fn in FUNCTIONS)) return ["${at}: fn is one of ${FUNCTIONS.join(', ')}, not '${v.fn}'.".toString()]
            if (v.fn == "firstWhere") {
                if (!(v.list ==~ PATH) || !(v.field ==~ PATH) || v.when == null) {
                    return ["${at}: firstWhere needs list (a path to a list), when (conditions on each element) and field.".toString()]
                }
                return RecordConditions.check(at, v.when)
            }
            if (!(v.args instanceof List) || ((List) v.args).isEmpty()) return ["${at}: ${v.fn} needs args.".toString()]
            if (v.fn == "atLeast" && ((List) v.args).size() != 2) return ["${at}: atLeast compares two values.".toString()]
            if (v.fn == "daysBetween" && ((List) v.args).size() != 2) return ["${at}: daysBetween takes the later and the earlier instant.".toString()]
            if (v.fn == "units" && v.parse != null && !(v.parse in DataSourceDefinitionSupport.REPEAT_PARSES)) {
                return ["${at}: units parse is ${DataSourceDefinitionSupport.REPEAT_PARSES.join(' or ')}.".toString()]
            }
            return ((List) v.args).collectMany { Object a -> a instanceof Map ? checkValue(at, a, depth + 1) : [] }
        }
        return []
    }
}
