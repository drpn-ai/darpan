package darpan.reconciliation.automation

import darpan.facade.common.SharedConfigAccessSupport
import darpan.facade.common.TenantScopedFinder

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.readField

/**
 * DAR-OPS-029 S06 (split out of AutomationExecutionSupport). Which connection (config id) an automation side reads through:
 * the source row's own id, its metadata's, the run's resolved default, else the tenant's single active config (owned or shared,
 * endpoint enabled), as the extract dispatch resolves it; and how a side's system is labelled.
 */
class AutomationSourceConfig {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(AutomationSourceConfig)

    static Map<String, Object> resolveSourceExtractorConfigDefaults(def ec, def automation, Collection sources) {
        String companyUserGroupId = normalize(readField(automation, "companyUserGroupId"))
        if (!companyUserGroupId) return [:]

        Map<String, Object> defaults = [:]
        (sources ?: []).each { source ->
            Map<String, Object> metadata = AutomationExecutionSupport.parseJsonMap(readField(source, "safeMetadataJson"))
            if (normalize(metadata.extractServiceName ?: metadata.serviceName)) return
            Map parameters = metadata.parameters instanceof Map ? (Map) metadata.parameters : [:]
            Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, normalize(readField(source, "systemEnumId")))
            String configParameterName = normalize(connector?.configParameterName)
            if (!configParameterName) return
            if (normalize(parameters[configParameterName]) || defaults.containsKey(configParameterName)) return
            // A connector whose config id lives on the source row (DATABASE -> databaseSourceQueryId) carries
            // its own id per source, so no shared tenant default is needed and the endpoint-enablement-filtered
            // findSingleActiveConfigId (which cannot match DatabaseSourceQuery) must not run. Null for the
            // API/SFTP connectors, whose id is not a row column, so their default resolution is unchanged.
            if (readSourceRowConfigId(source, configParameterName)) return
            // connector.systemEnumId (not the source's raw/possibly-aliased systemEnumId) is the canonical
            // id that SourceSystemConnector and SourceConfigEndpointAccess both key on.
            String configId = findSingleActiveConfigId(ec, companyUserGroupId,
                    normalize(connector.configEntityName), configParameterName, normalize(connector.systemEnumId))
            if (configId) defaults[configParameterName] = configId
        }
        return defaults
    }

    /**
     * DAR-BE-284: the config id each side reads through, resolved the same way the extract resolves it
     * (resolveSourceExtractorMetadata with the run's defaults), keyed FILE_1/FILE_2. A side with no connector config
     * (or an evaluate-mode audit's missing second side) is absent. Never throws: the ledger writes null instead.
     */
    static Map<String, String> sideConfigIds(def ec, def file1Source, def file2Source, Map<String, Object> configDefaults) {
        Map<String, String> out = [:]
        [FILE_1: file1Source, FILE_2: file2Source].each { String side, def source ->
            if (source == null) return
            try {
                Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, normalize(readField(source, "systemEnumId")))
                String parameterName = normalize(connector?.configParameterName)
                if (!parameterName) return
                Map parameters = resolveSourceExtractorMetadata(ec, source, configDefaults ?: [:]).parameters as Map
                String configId = normalize(parameters?.get(parameterName))
                if (configId) out[side] = configId
            } catch (Exception e) {
                logger.warn("Audit record connection for ${side} could not be resolved: ${e.class.simpleName}")
            }
        }
        return out
    }

    protected static Map<String, Object> resolveSourceExtractorMetadata(def ec, def source, Map<String, Object> configDefaults = [:]) {
        Map<String, Object> metadata = AutomationExecutionSupport.parseJsonMap(readField(source, "safeMetadataJson"))
        if (normalize(metadata.extractServiceName ?: metadata.serviceName)) return metadata

        String systemEnumId = normalize(readField(source, "systemEnumId"))
        Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, systemEnumId)
        // No connector, or a connector with no extract service (e.g. NETSUITE, which has no automation
        // extractor): leave the metadata service unset — callConfiguredSourceExtractor reports it as not
        // registered, exactly as the pre-registry switch did for unmatched systems.
        if (!connector || !normalize(connector.extractServiceName)) return metadata

        String companyUserGroupId = normalize(readField(source, "companyUserGroupId"))
        Map<String, Object> parameters = metadata.parameters instanceof Map ?
                new LinkedHashMap<>((Map<String, Object>) metadata.parameters) : [:]

        String configParameterName = normalize(connector.configParameterName)
        if (configParameterName) {
            // Preserve today's behavior exactly for the API/SFTP connectors (OMS/Shopify): those store the
            // config id in safeMetadataJson.parameters, never as a column on the source row, so the row read
            // below is null for them and the resolution falls through the SAME chain as before
            // (source param -> automation default -> the single active config for the tenant).
            //
            // AUT_SRC_DB regression fix: a connector whose configParameterName names a REAL column on the
            // automation source row (DATABASE -> databaseSourceQueryId) resolves that id DIRECTLY from the
            // admin-chosen row value. This bypasses findSingleActiveConfigId, whose endpoint-enablement
            // filter does not apply to DatabaseSourceQuery and whose "single active" pick would ignore the
            // admin's choice on a multi-query tenant. The id still flows into the extract service, which
            // re-checks tenant scope. Otherwise (no config id anywhere) leave the metadata service unset.
            String configId = readSourceRowConfigId(source, configParameterName) ?:
                    normalize(parameters[configParameterName]) ?:
                    normalize(configDefaults?[configParameterName]) ?:
                    findSingleActiveConfigId(ec, companyUserGroupId,
                            normalize(connector.configEntityName), configParameterName, normalize(connector.systemEnumId))
            if (!configId) return metadata
            parameters[configParameterName] = configId
        }

        metadata.parameters = parameters
        metadata.extractServiceName = connector.extractServiceName
        return metadata
    }

    /**
     * The admin-chosen config id stored DIRECTLY on the automation source row, when the connector's
     * configParameterName names a real column on that row (config over code: DATABASE ->
     * databaseSourceQueryId). Returns null for connectors whose config id is not a source-row column
     * (OMS/Shopify keep it in safeMetadataJson.parameters), so those dispatch paths stay byte-identical.
     *
     * A Moqui EntityValue is also a Map, but its containsKey only reflects fields actually loaded, so the
     * definition-backed isField check gates the read: reading an unknown field via EntityValue.get()
     * throws. A plain Map (unit-test source) has no isField, so key presence is the column-present signal.
     */
    protected static String readSourceRowConfigId(def source, String configParameterName) {
        if (source == null || !configParameterName) return null
        if (source.metaClass.respondsTo(source, "isField", String)) {
            return source.isField(configParameterName) ? normalize(readField(source, configParameterName)) : null
        }
        if (source instanceof Map) {
            return ((Map) source).containsKey(configParameterName) ?
                    normalize(((Map) source).get(configParameterName)) : null
        }
        return normalize(readField(source, configParameterName))
    }

    protected static String findSingleActiveConfigId(def ec, String companyUserGroupId, String configEntityName,
            String configIdField, String systemEnumId) {
        if (!companyUserGroupId || !configEntityName || !configIdField) return null
        try {
            // No .limit(2) here (unlike the pre-endpoint-enablement version of this query): the
            // endpoint-enablement post-filter below runs AFTER this fetch, so capping the fetch first
            // could truncate away the very owned row that would go on to survive that filter. Every
            // active owned candidate has to be visible to the filter before the list is narrowed.
            List rows = TenantScopedFinder.findGlobalUnscoped(ec, configEntityName,
                            "config lookup keyed by explicit companyUserGroupId from automation record")
                    .condition("companyUserGroupId", companyUserGroupId)
                    .condition("isActive", "Y")
                    .useCache(false)
                    .list() ?: []

            // DAR-BE-005: a config shared TO this tenant is equally usable by its automations.
            // Resolved by explicit PK so the cross-tenant read stays a point lookup.
            String configTypeEnumId = SharedConfigAccessSupport.CONFIG_TYPE_REGISTRY.find { k, v ->
                v.entityName == configEntityName
            }?.key
            if (configTypeEnumId) {
                Set<String> ownedIds = rows.collect { normalize(readField(it, configIdField)) }.findAll { it } as Set
                (SharedConfigAccessSupport.listSharedConfigIdsForTenant(ec, configTypeEnumId, companyUserGroupId) - ownedIds)
                        .each { String sharedId ->
                            def sharedRow = TenantScopedFinder.findGlobalUnscoped(ec, configEntityName,
                                            "shared config resolved by explicit PK for a tenant holding an " +
                                            "active ConfigTenantAccess grant (DAR-BE-005)")
                                    .condition(configIdField, sharedId)
                                    .condition("isActive", "Y")
                                    .useCache(false)
                                    .one()
                            if (sharedRow != null) rows = rows + [sharedRow]
                        }
            }

            // Endpoint enablement replaced canReadOrders. IMPORTANT: it cannot be a finder condition
            // above — absent means enabled (see SourceEndpointAccessSupport), so a WHERE clause would
            // exclude exactly the configs that have made no explicit decision, i.e. almost all of
            // them, silently inverting the default. Fetch every active candidate first (owned + shared,
            // above) and post-filter in Groovy through isEndpointEnabled instead.
            //
            // Converting this back into a finder condition is the obvious-looking optimisation that
            // breaks it — don't. Tell the next person too.
            if (configTypeEnumId) {
                rows = rows.findAll { row ->
                    SourceEndpointAccessSupport.isEndpointEnabled(ec, configTypeEnumId,
                            normalize(readField(row, configIdField)), systemEnumId)
                }
            }

            // Unchanged contract: auto-detect only when the choice is UNAMBIGUOUS. Sharing can push a
            // tenant from one candidate to two, in which case auto-detect correctly declines and the
            // automation must name its config explicitly.
            return rows.size() == 1 ? normalize(readField(rows.first(), configIdField)) : null
        } catch (Exception e) {
            logger.warn("Failed to detect single active source config for ${configEntityName}", e)
            return null
        }
    }

    protected static String sourceLabel(def ec, String systemEnumId, String fallback) {
        String enumId = normalize(systemEnumId)
        if (!enumId) return fallback
        try {
            def enumeration = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.basic.Enumeration",
                            "framework reference data: enumeration label lookup")
                    .condition("enumId", enumId)
                    .useCache(true)
                    .one()
            return normalize(readField(enumeration, "description")) ?: normalize(readField(enumeration, "enumCode")) ?: enumId
        } catch (Throwable ignored) {
            return enumId
        }
    }
}
