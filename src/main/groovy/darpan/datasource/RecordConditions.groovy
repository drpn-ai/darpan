package darpan.datasource

/**
 * DAR-BE-176. The conditions a data-defined step reads a record with: {field, op, values} with op IN, NOT_IN, BLANK or
 * NOT_BLANK, and {any: [...]} / {all: [...]} to combine them. A field is a path (`item.externalId`). Values compare as
 * numbers when both sides are numbers (1, "1" and 1.0 are one value), else as text. A list of conditions means all of
 * them. Blank is null, blank text, or an empty list or object.
 *
 * A path through a list (`lines[*].status`) reads every value it reaches: IN and NOT_BLANK hold when any value does,
 * NOT_IN and BLANK when none does (a path that reaches nothing is BLANK and NOT_IN anything). Pure.
 */
class RecordConditions {

    static final List<String> OPS = ["IN", "NOT_IN", "BLANK", "NOT_BLANK"].asImmutable()
    static final String FIELD = /[A-Za-z_][A-Za-z0-9_]*(\[\*\])?(\.[A-Za-z_][A-Za-z0-9_]*(\[\*\])?){0,7}/

    static boolean matches(Map record, Object conditions) {
        if (conditions == null) return true
        if (conditions instanceof List) return ((List) conditions).every { matches(record, it) }
        Map c = (Map) conditions
        if (c.any instanceof List) return ((List) c.any).any { matches(record, it) }
        if (c.all instanceof List) return ((List) c.all).every { matches(record, it) }
        String field = (String) c.field
        if (field.contains("[*]")) {
            List<Object> found = JsonPathValues.values(record, field)
            Closure<Boolean> listed = { Object v -> !isBlank(v) && ((c.values ?: []) as List).any { same(v, it) } }
            switch (c.op) {
                case "BLANK": return found.every { isBlank(it) }
                case "NOT_BLANK": return found.any { !isBlank(it) }
                case "IN": return found.any(listed)
                case "NOT_IN": return !found.any(listed)
                default: throw new IllegalArgumentException("A condition is one of ${OPS.join(', ')}, not '${c.op}'.")
            }
        }
        Object value = JsonPathValues.value(record, field)
        boolean blank = isBlank(value)
        switch (c.op) {
            case "BLANK": return blank
            case "NOT_BLANK": return !blank
            case "IN": return !blank && ((c.values ?: []) as List).any { same(value, it) }
            case "NOT_IN": return blank || !((c.values ?: []) as List).any { same(value, it) }
            default: throw new IllegalArgumentException("A condition is one of ${OPS.join(', ')}, not '${c.op}'.")
        }
    }

    /** Words for each problem in a condition list, for the definition check. */
    static List<String> check(String where, Object conditions) {
        List<String> errors = []
        if (conditions == null) return errors
        if (conditions instanceof List) { ((List) conditions).each { errors.addAll(check(where, it)) }; return errors }
        if (!(conditions instanceof Map)) return ["${where}: a condition must be an object.".toString()]
        Map c = (Map) conditions
        if (c.any != null || c.all != null) {
            Object inner = c.any != null ? c.any : c.all
            if (!(inner instanceof List) || ((List) inner).isEmpty()) errors << "${where}: any/all holds a list of conditions.".toString()
            else errors.addAll(check(where, inner))
            return errors
        }
        if (!(c.field ==~ FIELD)) errors << "${where}: a condition needs a field.".toString()
        if (!(c.op in OPS)) errors << "${where}: a condition is one of ${OPS.join(', ')}, not '${c.op}'.".toString()
        if (c.op in ["IN", "NOT_IN"] && !((c.values instanceof List) && !((List) c.values).isEmpty())) {
            errors << "${where}: ${c.op} needs values.".toString()
        }
        return errors
    }

    static boolean isBlank(Object v) {
        if (v == null) return true
        if (v instanceof Collection) return ((Collection) v).isEmpty()
        if (v instanceof Map) return ((Map) v).isEmpty()
        return v.toString().trim().isEmpty()
    }

    static boolean same(Object a, Object b) {
        if (a == null || b == null) return a == b
        BigDecimal x = number(a), y = number(b)
        if (x != null && y != null) return x.compareTo(y) == 0
        return a.toString() == b.toString()
    }

    static BigDecimal number(Object v) {
        if (v instanceof Number) return new BigDecimal(v.toString())
        String s = v?.toString()?.trim()
        if (!s || !(s ==~ /-?\d+(\.\d+)?/)) return null
        return new BigDecimal(s)
    }
}
