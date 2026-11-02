package darpan.datasource

import java.time.Instant

/**
 * DAR-BE-176. The rows of a derive step, as data: each `{set, when, value}` writes one field of a record. DeriveEngine
 * compiles the rows through one fixed Drools template (rule number and salience only, no definition text in the DRL)
 * and fires them; this class is what each rule calls.
 *
 *   set    the field written (one word)
 *   when   RecordConditions, read on the record as it entered the step; absent = always
 *   value  {literal}, {copy: path}, {map: {..}, from: path, default}, or {fn, args: [values], sep}
 *          fn: text (trimmed text, blank = null), decimal (text that parses as a number becomes one; anything else is
 *          kept), decimalPlain (a number with no trailing zeros; blank = null), epochMillis (an ISO instant becomes epoch
 *          millis; anything else, numbers included, is kept), join (non-blank args joined by sep), firstPresent, units
 *          (a quantity as whole units: parse STRICT_INT|NUMERIC, nullAs, as the repeat step reads it)
 *
 * Rows fire in order, so a later row's value may read a field an earlier row set, and a later row for the same field
 * overrides an earlier one (a default first, the cases after). Pure.
 */
class DeriveRules {

    static final List<String> FUNCTIONS = ["text", "decimal", "decimalPlain", "epochMillis", "join", "firstPresent", "units"].asImmutable()
    private static final String NAME = /[A-Za-z][A-Za-z0-9_]{0,59}/
    private static final String PATH = /[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*){0,7}/

    static boolean matches(Map record, Map rule) {
        return RecordConditions.matches(record, rule.when)
    }

    static void apply(Map record, Map rule) {
        record.put((String) rule.set, valueOf(record, (Map) rule.value))
    }

    static Object valueOf(Map record, Map v) {
        if (v.containsKey("literal")) return v.literal
        if (v.copy != null) return JsonPathValues.value(record, (String) v.copy)
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
                List<String> parts = args.collect { it?.toString()?.trim() }.findAll { it }
                return parts ? parts.join((v.sep ?: " ") as String) : null
            case "firstPresent":
                return args.find { it != null && it.toString().trim() }
            case "units":
                return DataSourceRunner.units(first, (v.parse ?: "STRICT_INT") as String, ((v.nullAs != null ? v.nullAs : 1) as Number).intValue())
            default:
                throw new IllegalArgumentException("A derive value uses ${FUNCTIONS.join(', ')}, not '${v.fn}'.")
        }
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
            if (!(v.args instanceof List) || ((List) v.args).isEmpty()) return ["${at}: ${v.fn} needs args.".toString()]
            if (v.fn == "units" && v.parse != null && !(v.parse in DataSourceDefinitionSupport.REPEAT_PARSES)) {
                return ["${at}: units parse is ${DataSourceDefinitionSupport.REPEAT_PARSES.join(' or ')}.".toString()]
            }
            return ((List) v.args).collectMany { Object a -> a instanceof Map ? checkValue(at, a, depth + 1) : [] }
        }
        return []
    }
}
