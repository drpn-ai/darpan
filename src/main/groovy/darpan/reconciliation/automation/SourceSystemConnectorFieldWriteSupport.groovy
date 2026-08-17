package darpan.reconciliation.automation

/**
 * Deletes {@code SourceSystemConnectorField} pill rows that were withdrawn from seed data after they
 * had already shipped.
 *
 * <p>Moqui seed loads are {@code createOrUpdate} and never delete, so removing a row from
 * {@code SourceSystemConnectorFieldSeedData.xml} has no effect on a database that already loaded the
 * earlier version of that file — the row survives, and the rules board keeps offering it. This is
 * exactly what happened to {@code SHOPIFY_RETURN_REFS} / {@code $.records[*].orderName}: Task 3 (Plan
 * 2) dropped it from the seed as an unevidenced primary-ID candidate, the evidence gate fired, and
 * browser verification then found the wizard still offering "Order name" — because the row was already
 * loaded and nothing had ever asked to delete it. {@link #RETIRED_FIELDS} is the actual retirement
 * mechanism: a deliberate, auditable declaration of exactly which (systemEnumId, fieldPath) rows to
 * delete.</p>
 *
 * <p>Deliberately does NOT compute "rows in the DB but not in the seed file" at runtime — a partially
 * loaded or mis-parsed seed would then delete live pills, turning a data-hygiene service into a
 * destructive one. {@link #deleteRetiredFields} deletes by exact PK only, one row per
 * {@code RETIRED_FIELDS} entry, never by {@code systemEnumId} alone (which would sweep every pill an
 * endpoint has, not just the retired one).</p>
 *
 * <p>Kept as a sibling to {@link SourceEndpointWriteSupport} rather than folded into it: that class
 * owns {@code SourceEndpointAccess} (per-config, per-credential endpoint enablement, an owner-writable
 * setting); this owns {@code SourceSystemConnectorField} (per-endpoint rules-board pills, a
 * platform-config catalog with no tenant dimension at all) — a different entity and a different owning
 * concern, so this got its own file rather than growing an already large one.</p>
 */
class SourceSystemConnectorFieldWriteSupport {
    static final String ENTITY_NAME = "darpan.reconciliation.SourceSystemConnectorField"

    /**
     * Pills withdrawn after they had already shipped in seed data. Seed loads are createOrUpdate, so
     * removing a row from the seed file leaves it in every database that already loaded it — this list
     * is how such a row is actually retired. Add an entry when you delete a pill from the seed; never
     * remove an entry, because environments load at different times.
     */
    static final List<Map<String, String>> RETIRED_FIELDS = [
            [systemEnumId: "SHOPIFY_RETURN_REFS", fieldPath: "\$.records[*].orderName",
             reason: "unevidenced primary-ID candidate: normalize(node.name) is nullable and no captured " +
                     "SHOPIFY_RETURN_REFS extract exists to prove it is reliably populated (Plan 2 Task 3)"],
    ].asImmutable()

    /**
     * Deletes each {@link #RETIRED_FIELDS} entry by its exact PK (systemEnumId + fieldPath). A row
     * that is already absent — never loaded, or already deleted by a prior run — is silently skipped,
     * so this is idempotent: a second run always deletes nothing and returns 0.
     *
     * <p>No entity-level authz-disable call here by design, matching
     * {@link SourceEndpointWriteSupport#migrateLegacyCanReadOrders}'s write path: the caller (the
     * {@code migrate#RetiredConnectorFields} service, called the same authz-disabled way its
     * {@code migrate#SourceConfigEndpointAccess} sibling already is) covers authz for the whole call,
     * so adding one here would only be a redundant bare call against
     * {@code DisableAuthzRatchetTest}'s tracked baseline.</p>
     *
     * @return the number of rows actually deleted.
     */
    static int deleteRetiredFields(def ec) {
        int rowsDeleted = 0
        RETIRED_FIELDS.each { Map<String, String> retired ->
            long deleted = ec.entity.find(ENTITY_NAME)
                    .condition("systemEnumId", retired.systemEnumId)
                    .condition("fieldPath", retired.fieldPath)
                    .deleteAll()
            rowsDeleted += (deleted ?: 0) as int
        }
        return rowsDeleted
    }
}
