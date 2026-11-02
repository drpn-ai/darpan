package darpan.datasource

/**
 * DAR-BE-176. The conditions a data-defined step reads a record with: {field, op, values} with op IN, NOT_IN, BLANK or
 * NOT_BLANK, and {any: [...]} / {all: [...]} to combine them. A field is a path (`item.externalId`). Values compare as
 * numbers when both sides are numbers (1, "1" and 1.0 are one value), else as text. A list of conditions means all of
 * them. Pure.
 */
class RecordConditions {

    static final List<String> OPS = ["IN", "NOT_IN", "BLANK", "NOT_BLANK"].asImmutable()

    static boolean matches(Map record, Object conditions) {
        if (conditions == null) return true
        if (conditions instanceof List) return ((List) conditions).every { matches(record, it) }
        Map c = (Map) conditions
        if (c.any instanceof List) return ((List) c.any).any { matches(record, it) }
        if (c.all instanceof List) return ((List) c.all).every { matches(record, it) }
        Object value = JsonPathValues.value(record, (String) c.field)
        boolean blank = value == null || value.toString().trim().isEmpty()
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
        if (!(c.field ==~ /[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*){0,7}/)) errors << "${where}: a condition needs a field.".toString()
        if (!(c.op in OPS)) errors << "${where}: a condition is one of ${OPS.join(', ')}, not '${c.op}'.".toString()
        if (c.op in ["IN", "NOT_IN"] && !((c.values instanceof List) && !((List) c.values).isEmpty())) {
            errors << "${where}: ${c.op} needs values.".toString()
        }
        return errors
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
