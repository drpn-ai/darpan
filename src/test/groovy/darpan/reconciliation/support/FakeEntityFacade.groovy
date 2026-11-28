package darpan.reconciliation.support

/**
 * DAR-OPS-029 S99-200: an ec.entity stand-in over {@link FakeEntityFind}. Each entity is a table: its primary key and
 * its rows. find() returns a FRESH finder per call (one query's conditions never narrow the next one, as with Moqui's
 * EntityFacade), over the same row list, so updateAll/deleteAll are seen by later finds. Every issued finder is kept in
 * {@link #issued} so a test can check what the code under test did to it (disableAuthz, conditions).
 *
 * <p>Primary keys come from the entity XML ({@link EntityPrimaryKeys}). A table the test did not declare is an empty
 * table with its real key; a name no entity XML defines throws, and so does a declared key that differs from the XML.</p>
 */
class FakeEntityFacade {
    private final Map<String, List<String>> pkByEntity = [:]
    private final Map<String, List<Map>> rowsByEntity = [:]
    final Map<String, List<FakeEntityFind>> issued = [:]

    /** Declares a table, its key read from the entity XML; returns this for chaining. The row list is shared, not copied. */
    FakeEntityFacade table(String entityName, List<Map> rows = []) {
        return table(entityName, knownPk(entityName), rows)
    }

    /**
     * Declares a table with an explicit key: for an entity no XML defines (a test-only name), or to state the key in the
     * test. A key that contradicts the XML throws.
     */
    FakeEntityFacade table(String entityName, List<String> pkFields, List<Map> rows) {
        List<String> xmlPk = EntityPrimaryKeys.of(entityName)
        if (xmlPk != null && xmlPk != pkFields) {
            throw new IllegalArgumentException("${entityName}'s primary key is ${xmlPk} in its entity XML, not ${pkFields}")
        }
        pkByEntity.put(entityName, pkFields)
        rowsByEntity.put(entityName, rows)
        return this
    }

    /** The live row list of a declared table (what updateAll/deleteAll changed), or null when not declared. */
    List<Map> rows(String entityName) { rowsByEntity.get(entityName) }

    FakeEntityFind find(String entityName) {
        if (!pkByEntity.containsKey(entityName)) table(entityName, knownPk(entityName), new ArrayList<Map>())
        FakeEntityFind f = new FakeEntityFind(pkByEntity.get(entityName), rowsByEntity.get(entityName))
        issued.computeIfAbsent(entityName) { [] as List<FakeEntityFind> }.add(f)
        return f
    }

    /** The finders issued for one entity, in call order (empty when none). */
    List<FakeEntityFind> issuedFor(String entityName) { issued.get(entityName) ?: [] }

    private static List<String> knownPk(String entityName) {
        List<String> pk = EntityPrimaryKeys.of(entityName)
        if (pk == null) throw new IllegalStateException("FakeEntityFacade: no entity XML defines ${entityName}")
        return pk
    }
}
