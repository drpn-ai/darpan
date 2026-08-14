package darpan.reconciliation.automation

import darpan.facade.common.SharedConfigAccessSupport

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
 * finds. Callers reach this only after authorizing the parent config through its own tenant-scoped
 * path, so no {@code disableAuthz} is introduced here.</p>
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
