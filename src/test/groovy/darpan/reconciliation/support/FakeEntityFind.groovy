package darpan.reconciliation.support

import java.sql.Timestamp

/**
 * DAR-OPS-029 S39 (from S26a R2): a test finder that answers like Moqui's EntityFind, not like a filter.
 *
 * Hand-rolled finder stubs applied EVERY condition in one(), so a tenant condition placed beside a full primary key
 * looked like it scoped the read. Moqui does not do that: EntityFindBase.oneInternal keeps only the primary key when the
 * equality conditions hold a full, non-empty PK plus anything else, so the extra condition is silently dropped
 * (DAR-OPS-029 OC66 hid behind such stubs). This fake follows Moqui:
 * <ul>
 *   <li>one() with a full PK and other equality conditions: matches on the PK alone. Non-equality conditions
 *       (conditionDate, IN, comparisons) still apply, because Moqui keeps them in the where condition;</li>
 *   <li>one() with a PK field given but empty: null;</li>
 *   <li>one() with no condition at all: null;</li>
 *   <li>one() otherwise: every condition; with several matches, the first row (Moqui's EntityFindImpl reads the first
 *       row and only trace-logs the rest);</li>
 *   <li>list() and count(): every condition; list() also sorts (orderBy) and pages (offset, limit);</li>
 *   <li>updateAll() and deleteAll(): change or remove the rows every condition matches, in the shared row list.</li>
 * </ul>
 * Rows are plain maps. conditionDate keeps rows whose from field is null or not after the moment and whose thru field is
 * null or after it (Moqui's DateCondition). A 3-argument condition takes Moqui's operator names ("in", "not-equals",
 * "greater-equals"…) or an EntityCondition.ComparisonOperator; an operator the fake does not know throws, so a test
 * never passes on a condition it silently ignored. The fake records disableAuthz() and useCache() for tests that assert
 * them; selectField, forUpdate and distinct are no-ops.
 */
class FakeEntityFind {
    final List<String> pkFields
    final List<Map> rows
    /** Equality conditions: Moqui's simpleAndMap, the only conditions an over-constrained one() drops. */
    final Map<String, Object> conditions = new LinkedHashMap<>()
    /** Non-equality conditions (conditionDate, IN, comparisons): Moqui's where condition, applied by one() too. */
    final List<Closure<Boolean>> whereConditions = []
    final List<String> orderByFields = []
    Integer offsetCount
    Integer limitCount
    boolean authzDisabled = false
    Boolean cacheUsed

    FakeEntityFind(List<String> pkFields, List<Map> rows = []) {
        if (!pkFields) throw new IllegalArgumentException("an entity has at least one primary key field")
        this.pkFields = pkFields.asImmutable()
        this.rows = rows
    }

    FakeEntityFind condition(String field, Object value) { conditions.put(field, value); this }

    FakeEntityFind condition(Map<String, Object> fields) { conditions.putAll(fields); this }

    FakeEntityFind condition(String field, Object operator, Object value) {
        String op = operatorName(operator)
        if (op == "equals") return condition(field, value)
        Closure<Boolean> test = comparison(op, value)
        whereConditions.add({ Map row -> test.call(row.get(field)) } as Closure<Boolean>)
        return this
    }

    FakeEntityFind conditionDate(String fromField, String thruField, Object moment) {
        String from = fromField ?: "fromDate"
        String thru = thruField ?: "thruDate"
        Comparable at = (Comparable) (moment ?: new Timestamp(System.currentTimeMillis()))
        whereConditions.add({ Map row ->
            Object f = row.get(from)
            Object t = row.get(thru)
            (f == null || ((Comparable) f) <= at) && (t == null || ((Comparable) t) > at)
        } as Closure<Boolean>)
        return this
    }

    FakeEntityFind useCache(boolean use) { cacheUsed = use; this }

    FakeEntityFind disableAuthz() { authzDisabled = true; this }

    FakeEntityFind forUpdate(boolean ignored) { this }

    FakeEntityFind selectField(String ignored) { this }

    FakeEntityFind distinct(boolean ignored) { this }

    /** Moqui's orderBy(String): one field or a comma-separated list; "-field" or "field DESC" sorts descending. */
    FakeEntityFind orderBy(String fields) {
        if (fields) fields.split(",").each { String f -> if (f.trim()) orderByFields.add(f.trim()) }
        return this
    }

    FakeEntityFind orderBy(List<String> fields) { fields?.each { orderBy(it) }; this }

    FakeEntityFind offset(Integer count) { offsetCount = count; this }

    FakeEntityFind limit(Integer count) { limitCount = count; this }

    /** The equality conditions one() actually applies: the PK alone when a full PK is over-constrained. */
    Map<String, Object> oneConditions() {
        boolean fullPk = pkFields.every { conditions.containsKey(it) && !isEmpty(conditions.get(it)) }
        if (!fullPk || conditions.size() <= pkFields.size()) return new LinkedHashMap<>(conditions)
        Map<String, Object> pk = new LinkedHashMap<>()
        pkFields.each { pk.put(it, conditions.get(it)) }
        return pk
    }

    Map one() {
        if (pkFields.any { conditions.containsKey(it) && isEmpty(conditions.get(it)) }) return null
        if (conditions.isEmpty() && whereConditions.isEmpty()) return null
        List<Map> found = sorted(matching(oneConditions()))
        return found ? found[0] : null
    }

    List<Map> list() {
        List<Map> found = sorted(matching(conditions))
        int from = Math.min(offsetCount ?: 0, found.size())
        int to = limitCount != null ? Math.min(from + limitCount, found.size()) : found.size()
        return found.subList(from, to)
    }

    long count() { (long) matching(conditions).size() }

    long updateAll(Map<String, Object> fieldsToSet) {
        List<Map> found = matching(conditions)
        found.each { Map row -> row.putAll(fieldsToSet) }
        return (long) found.size()
    }

    long deleteAll() {
        List<Map> found = matching(conditions)
        found.each { Map row -> rows.removeIf { it.is(row) } }
        return (long) found.size()
    }

    private List<Map> matching(Map<String, Object> applied) {
        return rows.findAll { Map row ->
            applied.every { String k, Object v -> row.get(k) == v } && whereConditions.every { it.call(row) }
        }
    }

    private List<Map> sorted(List<Map> found) {
        if (orderByFields.isEmpty()) return found
        List<Map> copy = new ArrayList<>(found)
        copy.sort { Map a, Map b ->
            for (String spec in orderByFields) {
                boolean desc = spec.startsWith("-") || spec.toUpperCase().endsWith(" DESC")
                String field = spec.replaceFirst(/^[-+^]+/, "").replaceFirst(/(?i)\s+(DESC|ASC)$/, "").trim()
                int c = compareNullsLow(a.get(field), b.get(field))
                if (c != 0) return desc ? -c : c
            }
            return 0
        }
        return copy
    }

    // NULL sorts lowest, as in H2 and MySQL.
    private static int compareNullsLow(Object a, Object b) {
        if (a == null) return b == null ? 0 : -1
        if (b == null) return 1
        return ((Comparable) a) <=> b
    }

    private static String operatorName(Object operator) {
        String name = operator instanceof Enum ? ((Enum) operator).name() : String.valueOf(operator)
        switch (name) {
            case ["equals", "EQUALS", "="]: return "equals"
            case ["not-equals", "NOT_EQUAL", "!=", "<>"]: return "not-equals"
            case ["less", "LESS_THAN", "<"]: return "less"
            case ["greater", "GREATER_THAN", ">"]: return "greater"
            case ["less-equals", "LESS_THAN_EQUAL_TO", "<="]: return "less-equals"
            case ["greater-equals", "GREATER_THAN_EQUAL_TO", ">="]: return "greater-equals"
            case ["in", "IN"]: return "in"
            case ["not-in", "NOT_IN"]: return "not-in"
            case ["is-null", "IS_NULL"]: return "is-null"
            case ["is-not-null", "IS_NOT_NULL"]: return "is-not-null"
            default: throw new UnsupportedOperationException("FakeEntityFind does not model the operator ${operator}")
        }
    }

    // SQL semantics: a comparison against a NULL column is never true (only is-null matches it).
    private static Closure<Boolean> comparison(String op, Object value) {
        switch (op) {
            case "is-null": return { Object v -> v == null }
            case "is-not-null": return { Object v -> v != null }
            case "not-equals": return { Object v -> value == null ? v != null : (v != null && v != value) }
            case ["in", "not-in"]:
                if (!(value instanceof Collection)) {
                    throw new UnsupportedOperationException("FakeEntityFind models ${op} with a Collection, got ${value?.getClass()}")
                }
                Collection values = (Collection) value
                return op == "in" ? { Object v -> v != null && values.contains(v) } : { Object v -> v != null && !values.contains(v) }
            case "less": return { Object v -> v != null && ((Comparable) v) < value }
            case "greater": return { Object v -> v != null && ((Comparable) v) > value }
            case "less-equals": return { Object v -> v != null && ((Comparable) v) <= value }
            case "greater-equals": return { Object v -> v != null && ((Comparable) v) >= value }
            default: throw new UnsupportedOperationException("FakeEntityFind does not model the operator ${op}")
        }
    }

    private static boolean isEmpty(Object value) {
        return value == null || (value instanceof CharSequence && value.length() == 0)
    }
}
