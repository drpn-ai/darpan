package darpan.facade.reconciliation

import darpan.common.DarpanEntityConstants
import darpan.facade.common.FacadeSupport
import darpan.facade.common.SharedConfigAccessSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.facade.settings.SettingsFacadeSupport
import darpan.reconciliation.automation.SftpAutomationSupport
import darpan.reconciliation.automation.SourceEndpointAccessSupport
import darpan.reconciliation.automation.SourceExtractOptionsSupport
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import groovy.json.JsonOutput

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.readField
import static darpan.common.ValueSupport.readString
import static darpan.facade.reconciliation.AutomationFacadeSupport.NETSUITE_SUITEQL_SYSTEM_ENUM_ID
import static darpan.facade.reconciliation.AutomationFacadeSupport.NETSUITE_SYSTEM_ENUM_ID
import static darpan.facade.reconciliation.AutomationFacadeSupport.SOURCE_CONFIG_TYPE_NETSUITE_AUTH
import static darpan.facade.reconciliation.AutomationFacadeSupport.SOURCE_CONFIG_TYPE_NETSUITE_SUITEQL
import static darpan.facade.reconciliation.AutomationFacadeSupport.enumLabel

/**
 * The automation wizard's and rules board's option lists (list#AutomationSourceOptions): systems and endpoints that can be
 * read, SFTP servers, source configs per (config x enabled endpoint), NetSuite auth/Restlet/SuiteQL choices, and each
 * endpoint's key and field pills. Registry-driven: a connector registered later needs only seed rows. Reads only.
 */
class AutomationSourceOptionsSupport {

    static List<Map<String, Object>> listEnumOptions(def ec, String enumTypeId) {
        List options = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.basic.Enumeration",
                        "framework reference data: enumeration list by type")
                .condition("enumTypeId", enumTypeId)
                .orderBy("sequenceNum,description,enumId")
                .useCache(true)
                .list() ?: []
        List<Map<String, Object>> mappedOptions = options.collect { item ->
            [
                    enumId      : readString(item, "enumId"),
                    enumCode    : readString(item, "enumCode"),
                    description : readString(item, "description"),
                    sequenceNum : readField(item, "sequenceNum"),
                    label       : FacadeSupport.enumLabel(item),
                    // Only DarpanSystemSource rows populate this (endpoint rows like OMS_RETURNS_GQL
                    // point at their parent system, e.g. OMS) — every other enum type's rows have
                    // no parentEnumId value, so findAll below strips the key out entirely rather
                    // than emitting parentEnumId:null, leaving their contract shape unchanged.
                    parentEnumId: readString(item, "parentEnumId"),
            ].findAll { it.value != null }
        } as List<Map<String, Object>>
        List<Map<String, Object>> deduplicated = SettingsFacadeSupport.deduplicateEnumOptions(enumTypeId, mappedOptions)
        return enumTypeId == SettingsFacadeSupport.DARPAN_SYSTEM_SOURCE_ENUM_TYPE_ID
                ? extractableSystemOptions(deduplicated, { String id -> SourceSystemConnectorSupport.resolveBySystemEnumId(ec, id) != null })
                : deduplicated
    }

    /**
     * DAR-BE-052: the picker offers only what can be read. An endpoint row (one with a parentEnumId) whose connector
     * is gone or disabled is dropped; its enum row stays for the history that references it. A parent with no
     * connector of its own but with readable endpoints stays as their group, marked extractable:false so it is not
     * offered as an endpoint itself. Top-level rows with no endpoints are unchanged.
     */
    static List<Map<String, Object>> extractableSystemOptions(List<Map<String, Object>> options, Closure<Boolean> hasConnector) {
        List<Map<String, Object>> endpoints = options.findAll { it.parentEnumId && hasConnector.call((String) it.enumId) }
        Set<String> parentsWithEndpoints = endpoints.collect { (String) it.parentEnumId } as Set<String>
        return options.findResults { Map<String, Object> option ->
            if (option.parentEnumId) return option in endpoints ? option : null
            if (option.enumId in parentsWithEndpoints && !hasConnector.call((String) option.enumId)) {
                return option + [extractable: false]
            }
            return option
        } as List<Map<String, Object>>
    }

    static List<Map<String, Object>> listSftpServerOptions(def ec) {
        String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!activeTenantUserGroupId) return []
        List servers = TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.SFTP_SERVER,
                        "sftp server listing — filtered post-load by isSftpServerOptionVisible scope check")
                .orderBy("description,sftpServerId")
                .useCache(true)
                .list() ?: []
        Set<String> tenantGroupServerIds = accessibleTenantGroupSftpServerIds(ec, activeTenantUserGroupId)
        return servers.findAll { server ->
            isSftpServerOptionVisible(server, activeTenantUserGroupId, tenantGroupServerIds)
        }.collect { server ->
            String sftpServerId = readString(server, "sftpServerId")
            [
                    sftpServerId      : sftpServerId,
                    description       : readString(server, "description"),
                    companyUserGroupId: readString(server, "companyUserGroupId"),
                    scopeEnumId       : readString(server, "scopeEnumId"),
                    host              : readString(server, "host"),
                    port              : readField(server, "port"),
                    username          : readString(server, "username"),
                    label             : readString(server, "description") ?: sftpServerId,
            ].findAll { it.value != null } as Map<String, Object>
        } as List<Map<String, Object>>
    }

    protected static Set<String> accessibleTenantGroupSftpServerIds(def ec, String activeTenantUserGroupId) {
        if (!activeTenantUserGroupId) return [] as Set
        List rows = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.reconciliation.SftpServerTenantAccess",
                        "sftp tenant-group access join table — keyed by active tenantUserGroupId, not directly tenant-owned")
                .condition("tenantUserGroupId", activeTenantUserGroupId)
                .useCache(true)
                .list() ?: []
        return rows.collect { readString(it, "sftpServerId") }.findAll { it } as Set
    }

    protected static boolean isSftpServerOptionVisible(def server, String activeTenantUserGroupId, Set<String> tenantGroupServerIds) {
        String scopeEnumId = SftpAutomationSupport.resolveServerScopeEnumId(server)
        if (scopeEnumId == SftpAutomationSupport.SFTP_SCOPE_TENANT) {
            return readString(server, "companyUserGroupId") == activeTenantUserGroupId
        }
        if (scopeEnumId == SftpAutomationSupport.SFTP_SCOPE_TENANT_GROUP) {
            return tenantGroupServerIds.contains(readString(server, "sftpServerId"))
        }
        return false
    }

    static List<Map<String, Object>> listSourceConfigOptions(def ec) {
        return listRegistrySourceConfigOptions(ec) + listNsAuthConfigOptions(ec) +
                listNsSuiteQlQueryOptions(ec)
    }

    /**
     * One option row per (accessible, active config row) x (enabled endpoint), across every
     * {@code SharedConfigAccessSupport.CONFIG_TYPE_REGISTRY} type that has {@code SourceSystemConnector}
     * rows keyed to its {@code configEntityName} — today HOTWAX_OMS and SHOPIFY_AUTH (NS_AUTH has
     * none; it keeps its own non-endpoint-aware builder below, unchanged).
     *
     * <p>THE FIX: before this, every row for a config was stamped with the parent system's canonical
     * id (OMS / SHOPIFY) regardless of which endpoint it represented. The wizard filters this list by
     * the selected systemEnumId, so picking a non-canonical endpoint (e.g. OMS_RETURNS) matched zero
     * rows and rendered "No API configs are available". Each row here instead carries THAT endpoint's
     * own connector fields, so selecting OMS_RETURNS surfaces a row stamped OMS_RETURNS.</p>
     */
    protected static List<Map<String, Object>> listRegistrySourceConfigOptions(def ec) {
        List<Map<String, Object>> options = []
        SharedConfigAccessSupport.CONFIG_TYPE_REGISTRY.keySet().each { String configTypeEnumId ->
            options.addAll(sourceConfigOptionsForType(ec, configTypeEnumId))
        }
        return options
    }

    protected static List<Map<String, Object>> sourceConfigOptionsForType(def ec, String configTypeEnumId) {
        return listEnabledConfigEndpoints(ec, configTypeEnumId).collect { Map<String, Object> pair ->
            String label = pair.configLabel as String
            (endpointOptionFields(ec, pair) + [
                    description   : label,
                    label         : label,
                    // DAR-UI-064: the extract options a run side over this endpoint may set; absent when it takes none.
                    extractOptions: SourceExtractOptionsSupport.declaredOptions(pair.connector as Map<String, Object>) ?: null,
            ]).findAll { it.value != null } as Map<String, Object>
        } as List<Map<String, Object>>
    }

    /** The fields every (config x endpoint) option row carries, in both the sourceConfigs and systemRemotes lists. */
    private static Map<String, Object> endpointOptionFields(def ec, Map<String, Object> pair) {
        Map<String, Object> connector = pair.connector as Map<String, Object>
        String configId = pair.configId as String
        String pkField = pair.pkField as String
        String systemEnumId = connector.systemEnumId as String
        return [
                sourceConfigId       : configId,
                sourceConfigType     : connector.expectedSourceConfigType,
                (pkField)            : configId,
                systemEnumId         : systemEnumId,
                systemLabel          : enumLabel(ec, systemEnumId),
                dateFromParameterName: connector.dateFromParameterName,
                dateToParameterName  : connector.dateToParameterName,
                safeMetadataJson     : safeMetadataJsonForConnector(connector, pkField, configId),
        ] as Map<String, Object>
    }

    protected static List<Map<String, Object>> listNsAuthConfigOptions(def ec) {
        String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!activeTenantUserGroupId) return []
        // DAR-BE-005 B5: owned rows plus rows shared to this tenant via ConfigTenantAccess — was
        // owner-only findTenantScoped, so a shared NetSuite auth config appeared in Settings
        // (editable) but never in this automation source picker. listAccessibleConfigRows applies
        // no status filter, so isActive is preserved here via findAll — same precedent as the
        // OMS/Shopify builders above (Task 5).
        List rows = SharedConfigAccessSupport
                .listAccessibleConfigRows(ec, SharedConfigAccessSupport.CONFIG_TYPE_NS_AUTH)
                .findAll { normalize(readString(it, "isActive")) != "N" }
        return rows.collect { item ->
            String configId = readString(item, "nsAuthConfigId")
            String label = readString(item, "description") ?: configId
            [
                    sourceConfigId  : configId,
                    sourceConfigType: SOURCE_CONFIG_TYPE_NETSUITE_AUTH,
                    nsAuthConfigId  : configId,
                    description     : readString(item, "description"),
                    systemEnumId    : NETSUITE_SYSTEM_ENUM_ID,
                    systemLabel     : enumLabel(ec, NETSUITE_SYSTEM_ENUM_ID),
                    label           : label,
            ].findAll { it.value != null } as Map<String, Object>
        } as List<Map<String, Object>>
    }

    /**
     * DAR-BE-058: NetSuite SuiteQL check definitions, so a run can be built over one from the
     * wizard instead of only from a data load.
     *
     * <p>A dedicated builder rather than a CONFIG_TYPE_REGISTRY entry, following the NS_AUTH
     * precedent above. Registering the type would enrol these rows in cross-tenant
     * config SHARING, which needs a DarpanSharedConfigType enumeration row and implies a
     * borrowing model these do not have: a check is a question about one tenant's data, cheap to
     * recreate, and it already points at an NsAuthConfig that IS shareable. The credential is the
     * thing worth sharing; the question is not.</p>
     *
     * <p>Date parameter names come off the connector rather than being repeated here, so a run
     * built through the wizard gets the same windowing as one built by a data load — including
     * windowDateOnly, without which a custom date range runs the previous day (DAR-BE-057).</p>
     */
    static List<Map<String, Object>> listNsSuiteQlQueryOptions(def ec) {
        String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!activeTenantUserGroupId) return []
        Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, NETSUITE_SUITEQL_SYSTEM_ENUM_ID)
        if (connector == null) return []

        List rows = TenantScopedFinder.findTenantScoped(ec, "darpan.reconciliation.NsSuiteQlSourceQuery")
                .useCache(false).list()
                .findAll { normalize(readString(it, "isActive")) != "N" }
        return rows.collect { item ->
            String configId = readString(item, "nsSuiteQlSourceQueryId")
            String label = readString(item, "description") ?: configId
            [
                    sourceConfigId       : configId,
                    sourceConfigType     : SOURCE_CONFIG_TYPE_NETSUITE_SUITEQL,
                    nsSuiteQlSourceQueryId: configId,
                    description          : readString(item, "description"),
                    systemEnumId         : NETSUITE_SUITEQL_SYSTEM_ENUM_ID,
                    systemLabel          : enumLabel(ec, NETSUITE_SUITEQL_SYSTEM_ENUM_ID),
                    dateFromParameterName: connector.dateFromParameterName,
                    dateToParameterName  : connector.dateToParameterName,
                    label                : label,
                    // DAR-UI-064: a query's own projection is its field list, for the key card and the rules board
                    // (the shape SourceSystemConnectorField pills take for connector endpoints).
                    primaryIdOptions     : suiteQlQueryFields(ec, configId, readString(item, "queryTemplate")),
                    fieldOptions         : suiteQlQueryFields(ec, configId, readString(item, "queryTemplate"), readString(item, "originFieldName")),
                    supportsExcludeFilters: supportsExcludeFiltersForSystem(ec, NETSUITE_SUITEQL_SYSTEM_ENUM_ID),
            ].findAll { it.value != null } as Map<String, Object>
        } as List<Map<String, Object>>
    }

    /**
     * The record fields a SuiteQL template adds itself, beside the operator's projection (netsuite-darpan
     * NsSuiteQlOrderSupport.TEMPLATE_RECORD_COLUMNS; darpan cannot import it, so NsSuiteQlTemplateFieldsSyncTests there
     * keeps the two equal). SALES_LINES makes one record per item line; nsUnkeyedLine ("Y" on a line with no HotWax line id,
     * which its declared Keep line sets aside) is shown like any other field (DAR-BE-318).
     */
    static final Map<String, List<String>> SUITEQL_TEMPLATE_FIELDS = [
            SALES_LINES        : ["orderLineId", "lineId", "lineState", "lineLocation", "itemType", "quantityShipped",
                                  "quantityBilled", "quantityBackordered", "lineClosed", "itemName", "lineAmount", "nsUnkeyedLine"],
            STATE_CONTRADICTION: ["contradiction", "contradictionBrief"],
    ].asImmutable()

    /**
     * A SuiteQL query's record fields as pills ($.records[*].<field>): its projection, what its template adds, and its
     * origin field (HOTWAX or NETSUITE_NATIVE, stamped from the join key) — the field a "only HotWax's records" filter
     * reads, as the lifecycle checks do (gorjana: an invoice with no HC Order ID is not ours).
     */
    protected static List<Map<String, Object>> suiteQlQueryFields(def ec, String queryId, String queryTemplate = null,
                                                                  String originFieldName = null) {
        // The projection has no tenant column; it is reached only through a query the caller already scoped.
        List<String> names = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.reconciliation.NsSuiteQlSourceQueryField",
                "projection of a tenant-scoped NsSuiteQlSourceQuery")
                .condition("nsSuiteQlSourceQueryId", queryId).orderBy("sequenceNum").useCache(false).list()
                .collect { readString(it, "recordFieldName") }
        names.addAll((SUITEQL_TEMPLATE_FIELDS.get(normalize(queryTemplate)) ?: []).findAll { !names.contains(it) })
        String origin = normalize(originFieldName)
        if (origin && !names.contains(origin)) names.add(origin)
        return names.collect { String name ->
            [fieldPath: "\$.records[*].${name}".toString(), label: name, type: "string"] as Map<String, Object>
        } as List<Map<String, Object>>
    }

    /** DAR-OPS-029 S99-314: HotWax OMS endpoints (GraphQL; the REST path is gone), one row per connection and endpoint. */
    static List<Map<String, Object>> listHotWaxOmsSourceRemoteOptions(def ec) {
        return remoteOptionsForType(ec, SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS)
    }

    static List<Map<String, Object>> listShopifySourceRemoteOptions(def ec) {
        return remoteOptionsForType(ec, SharedConfigAccessSupport.CONFIG_TYPE_SHOPIFY_AUTH)
    }

    /**
     * Registry-driven replacement for the old per-system remote-option builders. One row per
     * (accessible, active config row) x (enabled endpoint) — see {@link #listRegistrySourceConfigOptions}
     * for why cardinality changed. Every field comes off the connector row for THAT specific endpoint,
     * never a shared canonical remote lookup — proven independent of the SystemMessageRemote seed by
     * {@code AutomationFacadeSmokeTests.hotWaxEndpointOptionsDoNotDependOnSystemRemoteSeed} /
     * {@code shopifyEndpointOptionsDoNotDependOnSystemRemoteSeed}.
     */
    protected static List<Map<String, Object>> remoteOptionsForType(def ec, String configTypeEnumId) {
        return listEnabledConfigEndpoints(ec, configTypeEnumId).collect { Map<String, Object> pair ->
            Map<String, Object> connector = pair.connector as Map<String, Object>
            String systemEnumId = connector.systemEnumId as String
            String endpointLabel = normalize(connector.endpointLabel as String) ?: systemEnumId
            (endpointOptionFields(ec, pair) + [
                    systemMessageRemoteId : connector.remoteId,
                    optionKey             : pair.configId,
                    sourceConfigLabel     : pair.configLabel,
                    description           : endpointLabel,
                    primaryIdOptions      : primaryIdOptionsForSystem(ec, systemEnumId),
                    fieldOptions          : fieldOptionsForSystem(ec, systemEnumId),
                    supportsExcludeFilters: supportsExcludeFiltersForSystem(ec, systemEnumId),
                    label                 : endpointLabel,
            ]).findAll { it.value != null } as Map<String, Object>
        } as List<Map<String, Object>>
    }

    /**
     * The core product both option shapes above are built from: every (accessible, active config row)
     * paired with every ENABLED endpoint connector for that config type — "enabled" meaning both the
     * registry gate ({@code SourceSystemConnector.enabled='Y'}) and the per-config decision
     * ({@link SourceEndpointAccessSupport#isEndpointEnabled}, where an absent decision means enabled).
     *
     * <p>Returns {@code []} for a config type with no registered endpoints (NS_AUTH today —
     * {@code listNsAuthConfigOptions} is a separate builder) or with no active tenant.</p>
     */
    protected static List<Map<String, Object>> listEnabledConfigEndpoints(def ec, String configTypeEnumId) {
        Map<String, String> type = SharedConfigAccessSupport.configType(configTypeEnumId)
        if (type == null) return []
        String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!activeTenantUserGroupId) return []

        List<Map<String, Object>> connectors = connectorRowsForConfigEntity(ec, type.entityName)
        if (!connectors) return []

        // DAR-BE-005: owned rows plus rows shared to this tenant via ConfigTenantAccess.
        // listAccessibleConfigRows applies no status filter, so isActive is preserved here — the
        // source picker must not offer an inactive config. The retired per-config orders flag is not
        // read: per-endpoint enablement (isEndpointEnabled below) replaced it.
        // No catch: a failed read must fail the options call, not show "No API configs are available".
        List rows = SharedConfigAccessSupport.listAccessibleConfigRows(ec, configTypeEnumId)
                .findAll { normalize(readString(it, "isActive")) != "N" }
        List<Map<String, Object>> pairs = []
        rows.each { item ->
            String configId = readString(item, type.pkField)
            String configLabel = readString(item, "description") ?: configId
            connectors.each { Map<String, Object> connector ->
                String systemEnumId = connector.systemEnumId as String
                // Absent SourceConfigEndpointAccess row means enabled — never expressed as a
                // finder condition; isEndpointEnabled already applies that contract.
                if (SourceEndpointAccessSupport.isEndpointEnabled(ec, configTypeEnumId, configId, systemEnumId)) {
                    pairs << ([configId: configId, pkField: type.pkField, configLabel: configLabel,
                               connector: connector] as Map<String, Object>)
                }
            }
        }
        return pairs
    }

    /** Enabled SourceSystemConnector rows for one config entity, projected to just the fields these builders need. */
    static List<Map<String, Object>> connectorRowsForConfigEntity(def ec, String configEntityName) {
        if (!configEntityName) return []
        return (ec.entity.find(SourceSystemConnectorSupport.ENTITY_NAME)
                .condition("configEntityName", configEntityName)
                .orderBy("systemEnumId")
                .useCache(true)
                .list() ?: [])
                .findAll { row -> (readString(row, "enabled") ?: "Y").equalsIgnoreCase("Y") }
                .collect { row ->
                    ([
                            systemEnumId            : readString(row, "systemEnumId"),
                            expectedSourceConfigType: readString(row, "expectedSourceConfigType"),
                            extractServiceName      : readString(row, "extractServiceName"),
                            endpointLabel           : readString(row, "endpointLabel"),
                            dateFromParameterName   : readString(row, "dateFromParameterName"),
                            dateToParameterName     : readString(row, "dateToParameterName"),
                            remoteId                : readString(row, "remoteId"),
                            configParameterName     : readString(row, "configParameterName"),
                            // DAR-UI-064: carried so each option row can say which extract options its side may set.
                            extractOptionsJson      : readString(row, "extractOptionsJson"),
                    ] as Map<String, Object>)
                } as List<Map<String, Object>>
    }

    /** safeMetadataJson for a connector-driven option row: its OWN extractServiceName + config-id parameter. */
    private static String safeMetadataJsonForConnector(Map<String, Object> connector, String pkField, String configId) {
        String paramName = normalize(connector.configParameterName as String) ?: pkField
        return JsonOutput.toJson([
                extractServiceName: connector.extractServiceName,
                parameters        : [(paramName): configId],
        ])
    }


    /**
     * Join-key candidates for an endpoint: the deliberately narrow, key-like subset.
     * Registry-driven — a connector shipped later needs only seed rows.
     */
    static List<Map<String, Object>> primaryIdOptionsForSystem(def ec, String systemEnumId) {
        return pillsForSystem(ec, systemEnumId, true)
    }

    /**
     * The wider pill list the rules board offers. Endpoints with no curated superset simply have
     * fewer rows — Shopify has exactly its two primary-ID candidates, which is byte-identical to the
     * old "return null, board falls back to primaryIdOptions" behaviour.
     */
    static List<Map<String, Object>> fieldOptionsForSystem(def ec, String systemEnumId) {
        return pillsForSystem(ec, systemEnumId, false)
    }

    private static List<Map<String, Object>> pillsForSystem(def ec, String systemEnumId, boolean primaryIdOnly) {
        String target = normalize(systemEnumId)
        if (!target) return []

        def finder = ec.entity.find("darpan.reconciliation.SourceSystemConnectorField")
                .condition("systemEnumId", target)
                .orderBy("sequenceNum,fieldPath")
                .useCache(true)
        if (primaryIdOnly) finder = finder.condition("isPrimaryIdCandidate", "Y")

        return (finder.list() ?: []).collect { row ->
            [
                    fieldPath: readString(row, "fieldPath"),
                    label    : readString(row, "label"),
                    type     : readString(row, "fieldType") ?: "string",
            ] as Map<String, Object>
        } as List<Map<String, Object>>
    }

    /**
     * Whether a source on this system can actually carry exclusion filters — i.e. whether its
     * connector declares the {@code filterParameterName} that runSavedRunDiff / AutomationExecutionSupport
     * key exclusion dispatch on. The UI gates the exclusion mark on this so the board never offers a
     * control that would validate, persist and then exclude nothing (design: fail closed and loudly).
     * Registry-driven on purpose: a connector opting in later needs no UI change.
     */
    static boolean supportsExcludeFiltersForSystem(def ec, String systemEnumId) {
        Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, normalize(systemEnumId))
        // Explicit truthiness, NOT `as boolean`: String.asType(Boolean) routes through
        // Boolean.valueOf, which would turn "sourceFilters" into false.
        return normalize(connector?.get("filterParameterName")) ? true : false
    }
}
