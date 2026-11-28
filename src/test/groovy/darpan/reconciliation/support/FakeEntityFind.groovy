package darpan.reconciliation.support

/**
 * DAR-OPS-029 S39 (from S26a R2): a test finder that answers like Moqui's EntityFind, not like a filter.
 *
 * Hand-rolled finder stubs applied EVERY condition in one(), so a tenant condition placed beside a full primary key
 * looked like it scoped the read. Moqui does not do that: EntityFindBase.oneInternal keeps only the primary key when the
 * equality conditions hold a full, non-empty PK plus anything else, so the extra condition is silently dropped
 * (DAR-OPS-029 OC66 hid behind such stubs). This fake follows Moqui:
 * <ul>
 *   <li>one() with a full PK and other conditions: matches on the PK alone;</li>
 *   <li>one() with a PK field given but empty: null;</li>
 *   <li>one() otherwise: every condition, and more than one match throws, as Moqui does;</li>
 *   <li>list() and count(): every condition.</li>
 * </ul>
 * Rows are plain maps. The chainable no-ops (useCache, disableAuthz, forUpdate, selectField, orderBy) return this.
 */
class FakeEntityFind {
    final List<String> pkFields
    final List<Map> rows
    final Map<String, Object> conditions = new LinkedHashMap<>()

    FakeEntityFind(List<String> pkFields, List<Map> rows = []) {
        if (!pkFields) throw new IllegalArgumentException("an entity has at least one primary key field")
        this.pkFields = pkFields.asImmutable()
        this.rows = rows
    }

    FakeEntityFind condition(String field, Object value) { conditions.put(field, value); this }

    FakeEntityFind condition(Map<String, Object> fields) { conditions.putAll(fields); this }

    FakeEntityFind useCache(boolean ignored) { this }

    FakeEntityFind disableAuthz() { this }

    FakeEntityFind forUpdate(boolean ignored) { this }

    FakeEntityFind selectField(String ignored) { this }

    FakeEntityFind orderBy(String ignored) { this }

    /** The conditions one() actually applies: the PK alone when a full PK is over-constrained. */
    Map<String, Object> oneConditions() {
        boolean fullPk = pkFields.every { conditions.containsKey(it) && !isEmpty(conditions.get(it)) }
        if (!fullPk || conditions.size() <= pkFields.size()) return new LinkedHashMap<>(conditions)
        Map<String, Object> pk = new LinkedHashMap<>()
        pkFields.each { pk.put(it, conditions.get(it)) }
        return pk
    }

    Map one() {
        if (pkFields.any { conditions.containsKey(it) && isEmpty(conditions.get(it)) }) return null
        List<Map> found = matching(oneConditions())
        if (found.size() > 1) throw new IllegalStateException("one() found ${found.size()} rows for ${conditions}")
        return found ? found[0] : null
    }

    List<Map> list() { matching(conditions) }

    long count() { (long) list().size() }

    private List<Map> matching(Map<String, Object> applied) {
        return rows.findAll { Map row -> applied.every { String k, Object v -> row.get(k) == v } }
    }

    private static boolean isEmpty(Object value) {
        return value == null || (value instanceof CharSequence && value.length() == 0)
    }
}
