package darpan.reconciliation.automation

import darpan.facade.common.SharedConfigAccessSupport
import darpan.facade.common.TenantScopedFinder

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.readString

/**
 * Owner-controlled write path for {@link SourceEndpointAccessSupport}'s per-endpoint enablement.
 *
 * <p>Kept separate from {@link SourceEndpointAccessSupport}, which is read-only by design (see its
 * class Javadoc) — the same read/write split {@code SourceSystemConnectorSupport} already follows.</p>
 *
 * <p>Endpoint enablement is a property of the CREDENTIAL, so it is owner-controlled and global to the
 * config row: a tenant holding only {@code ConfigTenantAccess} can read the list (via
 * {@link SourceEndpointAccessSupport#listEndpointsForAuthorizedConfig}) but not change it. This
 * matches {@code canReadOrders}, which was one column on the owner's row.</p>
 */
class SourceEndpointWriteSupport {

    /**
     * Replaces this config's endpoint enablement. Endpoints omitted from {@code enabledSystemEnumIds}
     * are explicitly disabled; endpoints included have any prior 'N' row flipped back to 'Y'. Writes
     * one row per catalog endpoint rather than deleting rows for re-enabled ones, so a previously
     * disabled endpoint visibly flips back; absent still means enabled for endpoints that ship later
     * and were never in this catalog (see {@link SourceEndpointAccessSupport} class Javadoc).
     *
     * <p>Authorization happens BEFORE any write: {@link TenantScopedFinder#findTenantScopedByIdQuiet}
     * is owner-only (unlike the read path's owner-or-shared check), so a tenant that only holds
     * {@code ConfigTenantAccess} to a shared config is rejected here exactly like a config it has no
     * relationship to at all — reading is shared, writing is not.</p>
     *
     * @return true on a successful write; false (with an {@code ec.message} error added, and no rows
     *         touched) otherwise.
     */
    static boolean storeAccess(def ec, String configTypeEnumId, String configId, List enabledSystemEnumIds) {
        Map<String, String> typeRow = SharedConfigAccessSupport.configType(configTypeEnumId)
        if (typeRow == null) {
            ec.message.addError("Unknown config type '${configTypeEnumId}'.".toString())
            return false
        }

        def parent = TenantScopedFinder.findTenantScopedByIdQuiet(ec, typeRow.entityName, typeRow.pkField, configId)
        if (parent == null) {
            ec.message.addError("${typeRow.label} ${configId} not found.".toString())
            return false
        }

        String ownerUserGroupId = readString(parent, "companyUserGroupId")

        List<Map<String, Object>> catalog =
                SourceEndpointAccessSupport.listEndpointsForConfig(ec, configTypeEnumId, configId)
        if (catalog.isEmpty()) {
            ec.message.addError("No source endpoints are registered for ${typeRow.label}. Load the component's seed data.".toString())
            return false
        }

        Set<String> requested = ((enabledSystemEnumIds ?: []) as List)
                .collect { normalize(it) }.findAll { it } as Set<String>

        Set<String> known = catalog.collect { it.systemEnumId as String } as Set<String>
        Set<String> unknown = requested - known
        if (unknown) {
            // Fail loudly rather than silently ignoring: a caller naming an unregistered endpoint has a bug,
            // and swallowing it would look like a successful save that changed nothing.
            ec.message.addError("Not registered for ${typeRow.label}: ${unknown.sort().join(', ')}.".toString())
            return false
        }

        // Write one row per catalog endpoint. Rows for enabled endpoints are stored as 'Y' rather than
        // deleted so a previously disabled endpoint visibly flips back; absent still means enabled for
        // endpoints that ship later and were never in this catalog.
        catalog.each { Map<String, Object> endpoint ->
            String systemEnumId = endpoint.systemEnumId as String
            ec.entity.makeValue(SourceEndpointAccessSupport.ENTITY_NAME)
                    .setAll([configTypeEnumId  : configTypeEnumId,
                             configId          : configId,
                             systemEnumId      : systemEnumId,
                             companyUserGroupId: ownerUserGroupId,
                             isEnabled         : requested.contains(systemEnumId) ? "Y" : "N"])
                    .createOrUpdate()
        }

        return true
    }
}
