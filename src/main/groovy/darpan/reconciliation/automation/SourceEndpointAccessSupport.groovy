package darpan.reconciliation.automation

import darpan.facade.common.SharedConfigAccessSupport
import darpan.facade.common.TenantScopedFinder

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.readString

/**
 * Per-endpoint enablement for one API source config row.
 *
 * <p><strong>The catalog is the registry.</strong> Which endpoints exist for a config type comes
 * from enabled {@code SourceSystemConnector} rows filtered by {@code configEntityName}. Access rows
 * supply only {@code isEnabled}. A row here can therefore turn an endpoint OFF but can never add
 * one — a registry row with {@code enabled='N'} stays unreachable regardless of tenant data.</p>
 *
 * <p><strong>Absent means enabled.</strong> The access table stores explicit decisions only, so an
 * endpoint shipped in a later release is usable the moment its seed row loads, with no per-tenant
 * backfill. Every reader must default to enabled.</p>
 *
 * <p>Both entities are {@code use="configuration"} platform/tenant config, read as plain cached
 * finds. {@link #listEndpointsForConfig} and {@link #isEndpointEnabled} carry no authorization of
 * their own — callers reach them only after authorizing the parent config through its own
 * tenant-scoped path, so no {@code disableAuthz} is introduced here.
 * {@link #listEndpointsForAuthorizedConfig} is the one exception: it IS the authorization boundary
 * for the {@code list#SourceConfigEndpoints} facade service, which has no other gate (see that
 * service's FENCE WARNING comment). Still read-only — no entity writes. The write path lives in the
 * separate {@link SourceEndpointWriteSupport}, matching the read/write split
 * {@code SourceSystemConnectorSupport} already follows.</p>
 */
class SourceEndpointAccessSupport {
    static final String ENTITY_NAME = "darpan.reconciliation.SourceConfigEndpointAccess"

    /** Reverse of CONFIG_TYPE_REGISTRY: connector rows carry configEntityName, this table carries configTypeEnumId. */
    static String configTypeForEntityName(String entityName) {
        String target = normalize(entityName)
        if (!target) return null
        return SharedConfigAccessSupport.CONFIG_TYPE_REGISTRY.find { String _k, Map<String, String> row ->
            row.entityName == target
        }?.key
    }

    private static String entityNameForConfigType(String configTypeEnumId) {
        return SharedConfigAccessSupport.configType(configTypeEnumId)?.entityName
    }

    /**
     * Every endpoint registered for this config type, each with its current enablement.
     * Ordered by systemEnumId so callers and tests get a stable list.
     */
    static List<Map<String, Object>> listEndpointsForConfig(def ec, String configTypeEnumId, String configId) {
        String entityName = entityNameForConfigType(configTypeEnumId)
        if (!entityName) return []

        List connectors = ec.entity.find(SourceSystemConnectorSupport.ENTITY_NAME)
                .condition("configEntityName", entityName)
                .useCache(true)
                .list() ?: []

        Map<String, String> decisions = decisionsFor(ec, configTypeEnumId, configId)

        return connectors.findAll { row ->
            (readString(row, "enabled") ?: "Y").equalsIgnoreCase("Y")
        }.collect { row ->
            String systemEnumId = readString(row, "systemEnumId")
            [
                    systemEnumId : systemEnumId,
                    endpointLabel: readString(row, "endpointLabel") ?: systemEnumId,
                    isEnabled    : decisions.get(systemEnumId) != "N",
            ] as Map<String, Object>
        }.sort { it.systemEnumId } as List<Map<String, Object>>
    }

    /**
     * Authorization-checked entry point for {@code list#SourceConfigEndpoints}: the catalog from
     * {@link #listEndpointsForConfig}, but only after confirming the ACTIVE tenant may use the parent
     * config — owner OR shared peer (via {@code SharedConfigAccessSupport.canActiveTenantUseConfig}).
     * A tenant with neither relationship gets an {@code ec.message} error and an empty list, never the
     * catalog. Read access is intentionally wider than write access: a shared peer may read the
     * enablement list (the settings page must render for them) even though only the owner may change
     * it — see {@link SourceEndpointWriteSupport#storeAccess}.
     */
    static List<Map<String, Object>> listEndpointsForAuthorizedConfig(def ec, String configTypeEnumId, String configId) {
        Map<String, String> typeRow = SharedConfigAccessSupport.configType(configTypeEnumId)
        if (typeRow == null) {
            ec.message.addError("Unknown config type '${configTypeEnumId}'.".toString())
            return []
        }

        // Fast path: the active tenant owns the row. findTenantScopedByIdQuiet is owner-only (it
        // gates on companyUserGroupId == active tenant), so a null here does NOT mean "deny" — it
        // only rules out ownership, and a shared peer is checked next.
        def parent = TenantScopedFinder.findTenantScopedByIdQuiet(ec, typeRow.entityName, typeRow.pkField, configId)
        if (parent == null) {
            // Not owned by the active tenant (or it doesn't exist). It may still be shared TO this
            // tenant via ConfigTenantAccess — the owner-only finder above can't see that, so resolve
            // the row unscoped (same point-lookup shape SharedConfigAccessSupport.listAccessibleConfigRows
            // already uses for shared rows) and let canActiveTenantUseConfig make the real, wider
            // owner-or-shared decision.
            parent = TenantScopedFinder.findGlobalUnscoped(ec, typeRow.entityName,
                    "shared-config peer read for list#SourceConfigEndpoints — endpoint enablement must " +
                    "stay readable by every peer in the config's ConfigTenantAccess group, not just its owner")
                    .condition(typeRow.pkField, configId)
                    .useCache(false)
                    .one()
        }

        if (parent == null || !SharedConfigAccessSupport.canActiveTenantUseConfig(ec, configTypeEnumId, parent)) {
            // Same not-found wording regardless of cause (missing vs. foreign vs. unshared) — this
            // path must not let a caller distinguish "doesn't exist" from "exists but isn't yours".
            ec.message.addError("${typeRow.label} ${configId} not found.".toString())
            return []
        }

        return listEndpointsForConfig(ec, configTypeEnumId, configId)
    }

    /** True when this config may extract from this endpoint. Absent decision means enabled. */
    static boolean isEndpointEnabled(def ec, String configTypeEnumId, String configId, String systemEnumId) {
        String target = normalize(systemEnumId)
        if (!target) return false
        return listEndpointsForConfig(ec, configTypeEnumId, configId)
                .any { it.systemEnumId == target && it.isEnabled }
    }

    /** systemEnumId -> 'Y'/'N' for the rows that exist. Missing keys are the absent-means-enabled case. */
    private static Map<String, String> decisionsFor(def ec, String configTypeEnumId, String configId) {
        String type = normalize(configTypeEnumId)
        String id = normalize(configId)
        if (!type || !id) return [:]

        List rows = ec.entity.find(ENTITY_NAME)
                .condition("configTypeEnumId", type)
                .condition("configId", id)
                .useCache(true)
                .list() ?: []

        Map<String, String> decisions = [:]
        rows.each { row ->
            decisions.put(readString(row, "systemEnumId"),
                    (readString(row, "isEnabled") ?: "Y").toUpperCase())
        }
        return decisions
    }
}
