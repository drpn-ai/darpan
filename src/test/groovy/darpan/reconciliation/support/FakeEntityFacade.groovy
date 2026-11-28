package darpan.reconciliation.support

/**
 * DAR-OPS-029 S99-200: an ec.entity stand-in over {@link FakeEntityFind}. Each entity is a table: its primary key and
 * its rows. find() returns a FRESH finder per call (one query's conditions never narrow the next one, as with Moqui's
 * EntityFacade), over the same row list, so updateAll/deleteAll are seen by later finds. Every issued finder is kept in
 * {@link #issued} so a test can check what the code under test did to it (disableAuthz, conditions).
 * A find on an entity the test did not declare throws: a test should say which tables its code reads.
 */
class FakeEntityFacade {
    private final Map<String, List<String>> pkByEntity = [:]
    private final Map<String, List<Map>> rowsByEntity = [:]
    final Map<String, List<FakeEntityFind>> issued = [:]

    /** Declares a table; returns this for chaining. The row list is shared, not copied. */
    FakeEntityFacade table(String entityName, List<String> pkFields, List<Map> rows = []) {
        pkByEntity.put(entityName, pkFields)
        rowsByEntity.put(entityName, rows)
        return this
    }

    /** The live row list of a declared table (what updateAll/deleteAll changed). */
    List<Map> rows(String entityName) { rowsByEntity.get(entityName) }

    FakeEntityFind find(String entityName) {
        if (!pkByEntity.containsKey(entityName)) {
            throw new IllegalStateException("FakeEntityFacade: no table declared for ${entityName}")
        }
        FakeEntityFind f = new FakeEntityFind(pkByEntity.get(entityName), rowsByEntity.get(entityName))
        issued.computeIfAbsent(entityName) { [] as List<FakeEntityFind> }.add(f)
        return f
    }

    /** The finders issued for one entity, in call order (empty when none). */
    List<FakeEntityFind> issuedFor(String entityName) { issued.get(entityName) ?: [] }
}
