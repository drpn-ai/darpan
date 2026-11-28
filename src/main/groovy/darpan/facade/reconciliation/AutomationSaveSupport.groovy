package darpan.facade.reconciliation

import darpan.common.DarpanEntityConstants
import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.SftpAutomationSupport
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import groovy.json.JsonOutput

import java.sql.Timestamp

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeLower
import static darpan.common.ValueSupport.readString
import static darpan.facade.reconciliation.AutomationFacadeSupport.FILE_SIDES
import static darpan.facade.reconciliation.AutomationFacadeSupport.FILE_SIDE_1
import static darpan.facade.reconciliation.AutomationFacadeSupport.FILE_SIDE_2
import static darpan.facade.reconciliation.AutomationFacadeSupport.INPUT_MODE_SFTP_FILES
import static darpan.facade.reconciliation.AutomationFacadeSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE
import static darpan.facade.reconciliation.AutomationFacadeSupport.SHOPIFY_ORDERS_REMOTE_ID
import static darpan.facade.reconciliation.AutomationFacadeSupport.SHOPIFY_SYSTEM_ENUM_ID
import static darpan.facade.reconciliation.AutomationFacadeSupport.SOURCE_TYPE_API
import static darpan.facade.reconciliation.AutomationFacadeSupport.SOURCE_TYPE_DB
import static darpan.facade.reconciliation.AutomationFacadeSupport.SOURCE_TYPE_SFTP
import static darpan.facade.reconciliation.AutomationFacadeSupport.nowTimestamp
import static darpan.facade.reconciliation.AutomationFacadeSupport.parseJsonMap
import static darpan.facade.reconciliation.AutomationFacadeSupport.toTimestamp

/**
 * save#Automation's half in Groovy: the saved run is resolved (or created), each source is validated against its run and
 * its connector (the extract-service allowlist is the save-side half of the DAR-297/298 gate) and enriched from the
 * connector registry, the source filters are captured or seeded from the run once, and the next fire is computed in the
 * zone being saved. An automation keeps its own copy of its run's setup; only a deliberate sync changes it (DAR-BE-338).
 */
class AutomationSaveSupport {

    /** DAR-BE-052: no connector reads a population by status, and the runner refuses such a row (S06 F-A4). */
    static final String STATE_WINDOW_RETIRED = "State-based windows are retired; choose a date window."

    static Map<String, Object> prepareAutomationSave(def ec, Map params = [:]) {
        Map input = params ?: [:]
        String automationName = input.automationName as String
        String inputModeEnumId = input.inputModeEnumId as String
        String scheduleExpr = input.scheduleExpr as String

        Map<String, Object> savedRunResolution = !ec.message.hasError() ? resolveSavedRunForSave(ec, input) : [:]
        List<Map<String, Object>> sourceEntries = (input.sourceEntries instanceof Collection ?
                ((Collection<Map<String, Object>>) input.sourceEntries) : []) as List<Map<String, Object>>
        if (!ec.message.hasError()) sourceEntries = applyApiSourceMetadataDefaults(ec, sourceEntries)
        if (!ec.message.hasError()) {
            validateSources(ec, inputModeEnumId, sourceEntries, savedRunResolution.savedRun as Map)
        }
        if (!ec.message.hasError() && AutomationExecutionSupport.isStateWindowMode(input)) {
            ec.message.addError(STATE_WINDOW_RETIRED)
        }

        String chatSpaceId = normalize(input.chatSpaceId) ?: null
        if (!ec.message.hasError() && chatSpaceId) {
            validateChatSpace(ec, chatSpaceId)
        }

        Map<String, Object> automationFields = null
        if (!ec.message.hasError()) {
            Timestamp now = nowTimestamp(ec)
            // OC44: the automation's zone is the tenant's. save#Automation sends the zone it resolved (the one sent, an
            // edit's own, else the tenant's); a caller that sends none gets the tenant's here too, never UTC by default.
            String windowTimeZone = normalize(input.windowTimeZone) ?: TenantAccessSupport.resolveActiveTenantTimeZone(ec)
            automationFields = [
                    automationName        : automationName,
                    description           : input.description,
                    inputModeEnumId       : inputModeEnumId,
                    savedRunId            : savedRunResolution.savedRunId,
                    savedRunType          : savedRunResolution.savedRunType,
                    reconciliationRunId   : input.reconciliationRunId,
                    reconciliationMappingId: savedRunResolution.reconciliationMappingId,
                    ruleSetId             : savedRunResolution.ruleSetId,
                    compareScopeId        : savedRunResolution.compareScopeId,
                    scheduleExpr          : scheduleExpr,
                    nextScheduledFireTime : toTimestamp(input.nextScheduledFireTime) ?:
                            resolveNextFireTime(input + [windowTimeZone: windowTimeZone], now),
                    lastScheduledFireTime : toTimestamp(input.lastScheduledFireTime),
                    relativeWindowTypeEnumId: input.relativeWindowTypeEnumId,
                    relativeWindowCount   : input.relativeWindowCount,
                    customWindowStartDate : toTimestamp(input.customWindowStartDate),
                    customWindowEndDate   : toTimestamp(input.customWindowEndDate),
                    maxWindowDays         : input.maxWindowDays,
                    splitWindowDays       : input.splitWindowDays,
                    windowTimeZone        : windowTimeZone,
                    safeConfigJson        : input.safeConfigJson,
                    chatSpaceId           : chatSpaceId,
                    isActive              : input.isActive == false ? "N" : "Y",
                    lastUpdatedDate       : now,
            ] as Map<String, Object>
        }

        return FacadeSupport.envelope(ec) + [
                automationFields: !ec.message.hasError() ? automationFields : null,
                sourceEntries   : !ec.message.hasError() ? sourceEntries : [],
        ]
    }

    /**
     * Existing automation source-exclusion-filter rows grouped by fileSide, in stored shape
     * (filterValues as the comma-joined string), ordered by sequenceNum.
     *
     * A fileSide that currently has a ReconciliationAutomationSource row is always represented as a
     * key here — with an empty list when it has no filter rows — never simply omitted. The automation
     * save deletes and recreates every source row on every save (see save#Automation), so a source row
     * existing is exactly the signal that this side has been saved before. Without that empty-list
     * entry, a side with zero filter rows (e.g. explicitly cleared on a prior save) would be
     * indistinguishable from a side that has never been configured, and the very next omitted-key
     * resave would silently re-seed it from the rule set (see resolveSourceFilters/seedFromRuleSet)
     * instead of staying cleared — the scheduled automation would then quietly diverge from what the
     * operator intentionally cleared.
     */
    static Map<String, List<Map<String, Object>>> captureSourceFiltersByFileSide(def ec, String automationId) {
        Map<String, List<Map<String, Object>>> byFileSide = [:]
        if (!automationId) return byFileSide
        // Both entities carry their own companyUserGroupId (directly tenant-owned, not transitively via
        // a parent), so this routes through TenantScopedFinder rather than a bare disableAuthz (P0 #4 —
        // bare disableAuthz is CI-banned by DisableAuthzRatchetTest). Callers of this function (the
        // save#Automation capture-before-delete step and buildAutomationRow's load path) have already
        // established an active tenant matching the automation before reaching here.
        List existingSources = TenantScopedFinder.findTenantScoped(ec, "darpan.reconciliation.ReconciliationAutomationSource")
                .condition("automationId", automationId)
                .useCache(false).list() ?: []
        existingSources.each { def existingSource ->
            String existingFileSide = normalize(existingSource.fileSide)
            if (existingFileSide) byFileSide.putIfAbsent(existingFileSide, [])
        }
        List rows = TenantScopedFinder.findTenantScoped(ec, "darpan.reconciliation.ReconciliationAutomationSourceFilter")
                .condition("automationId", automationId)
                .orderBy("fileSide,sequenceNum")
                .useCache(false).list() ?: []
        rows.each { def row ->
            String fileSide = normalize(row.fileSide)
            byFileSide.computeIfAbsent(fileSide, { ignored -> [] }).add([
                    fieldExpression: normalize(row.fieldExpression),
                    operator       : normalize(row.operator),
                    filterValues   : normalize(row.filterValues),
            ] as Map<String, Object>)
        }
        return byFileSide
    }

    /**
     * The filter rows to write for one source entry. An entry that omits excludeFilters keeps the
     * rules captured before the source delete; an explicit list (including an empty one) replaces them.
     *
     * On a FIRST save there is nothing captured and the automation UI submits nothing (exclusions are
     * only editable on the rules board, which belongs to the rule set — the automation wizard never
     * submits them), so without the seed below an automation would run with no exclusions while its
     * rule set has them — the scheduled job silently disagreeing with the interactive run. The seed
     * copies the rule set's rows once, at creation; it is frozen thereafter (later rule-set edits do
     * not reach an existing automation), which is the snapshot property the mirrored tables exist for.
     */
    static List<Map<String, Object>> resolveSourceFilters(def ec, String compareScopeId, Object sourceEntry,
                                                          Map<String, List<Map<String, Object>>> capturedByFileSide) {
        Map entry = (sourceEntry instanceof Map) ? (Map) sourceEntry : [:]
        String fileSide = normalize(entry.get("fileSide"))
        if (entry.containsKey("excludeFilters") && entry.get("excludeFilters") != null) {
            return ReconciliationSavedRunSupport.validateExcludeFilterPayload(
                    entry.get("excludeFilters"), "Automation ${fileSide ?: 'source'}".toString())
        }
        List<Map<String, Object>> captured = capturedByFileSide?.get(fileSide)
        if (captured != null) return captured
        return seedFromRuleSet(ec, compareScopeId, fileSide)
    }

    /** Rule set rows for this compare scope and side, for the session's active tenant (the save and sync paths). */
    protected static List<Map<String, Object>> seedFromRuleSet(def ec, String rawCompareScopeId, String fileSide) {
        return seedFromRuleSet(ec, TenantAccessSupport.currentActiveTenantUserGroupId(ec), rawCompareScopeId, fileSide)
    }

    /**
     * Rule set rows for this compare scope and side, read for the tenant NAMED, whatever the session's active tenant. An
     * audit check's automation is built under the agent pipeline's tenant (S12 A1): a session-scoped read there found no
     * rows and the automation ran unfiltered, with no error. Empty without a tenant, a scope or a side.
     */
    static List<Map<String, Object>> seedFromRuleSet(def ec, String tenantId, String rawCompareScopeId, String fileSide) {
        String tenant = normalize(tenantId)
        String compareScopeId = normalize(rawCompareScopeId)
        if (!tenant || !compareScopeId || !fileSide) return []
        List rows = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.rule.RuleSetCompareSourceFilter",
                        "rule-set source filters for an automation: explicit companyUserGroupId condition applied")
                .condition("companyUserGroupId", tenant)
                .condition("compareScopeId", compareScopeId)
                .condition("fileSide", fileSide)
                .orderBy("sequenceNum")
                .useCache(false).list() ?: []
        return mapRuleSetFilterRows(rows)
    }

    private static List<Map<String, Object>> mapRuleSetFilterRows(List rows) {
        return rows.collect { def row ->
            [
                    fieldExpression: normalize(row.fieldExpression),
                    operator       : normalize(row.operator),
                    filterValues   : normalize(row.filterValues),
            ] as Map<String, Object>
        }
    }

    protected static Map<String, Object> resolveSavedRunForSave(def ec, Map input) {
        Map savedRunPayload = (input.savedRun instanceof Map ? input.savedRun :
                input.newSavedRun instanceof Map ? input.newSavedRun : null) as Map
        if (!normalize(input.savedRunId) && savedRunPayload) {
            String createMode = normalizeLower(savedRunPayload.createMode)
            String serviceName = createMode == "csv" ?
                    "facade.ReconciliationFacadeServices.create#CsvRun" :
                    "facade.ReconciliationFacadeServices.create#RuleSetRun"
            Map createParams = new LinkedHashMap(savedRunPayload)
            createParams.remove("createMode")
            Map createResult = ec.service.sync()
                    .name(serviceName)
                    .parameters(createParams)
                    .disableAuthz()
                    .call() ?: [:]
            if (ec.message.hasError()) return [:]
            input.savedRunId = ((Map) createResult.savedRun)?.savedRunId
            input.savedRunType = ((Map) createResult.savedRun)?.runType ?: "ruleset"
        }

        String savedRunId = normalize(input.savedRunId)
        String savedRunType = normalizeLower(input.savedRunType) ?: "ruleset"
        if (!savedRunId) ec.message.addError("savedRunId or savedRun is required")
        if (!(savedRunType in ["ruleset", "mapping"])) ec.message.addError("savedRunType must be ruleset or mapping")
        if (ec.message.hasError()) return [:]

        if (savedRunType == "mapping") {
            // P0 #4 step 4b: unified tenant-scoped load; sets not-found/forbidden error on ec.message if denied.
            def mapping = TenantScopedFinder.findTenantScopedById(
                    ec, DarpanEntityConstants.RECONCILIATION_MAPPING,
                    "reconciliationMappingId", savedRunId)
            return [
                    savedRunId             : savedRunId,
                    savedRunType           : "mapping",
                    reconciliationMappingId: savedRunId,
                    ruleSetId              : null,
                    compareScopeId         : null,
                    savedRun               : ReconciliationSavedRunSupport.findSavedRunById(ec, savedRunId),
            ]
        }

        Map<String, Object> resolved = ReconciliationSavedRunSupport.resolveRuleSetRun(ec, savedRunId)
        if (resolved.error) ec.message.addError(resolved.error as String)
        if (!ec.message.hasError() && !resolved.savedRun) ec.message.addError("Saved run '${savedRunId}' was not found.")
        return [
                savedRunId             : savedRunId,
                savedRunType           : "ruleset",
                reconciliationMappingId: null,
                ruleSetId              : resolved.savedRun?.ruleSetId ?: savedRunId,
                compareScopeId         : resolved.savedRun?.compareScopeId,
                savedRun               : resolved.savedRun,
        ]
    }

    protected static void validateSources(def ec, String inputModeEnumId, List<Map<String, Object>> sources,
            Map<String, Object> savedRun) {
        // DAR-UI-064: a one-system (EVALUATE) run has one side, FILE_1 (DAR-BE-049). Requiring two made every automation
        // over such a run unsavable, and Sync failed on each audit check that reads one system (R2, R4, R7, R9, R12).
        boolean oneSystem = "EVALUATE".equalsIgnoreCase(normalize(savedRun?.get("scopeMode")))
        if (oneSystem) {
            if (sources.size() != 1 || (sources[0].fileSide as String) != "FILE_1") {
                ec.message.addError("A run that reads one system takes exactly one source, FILE_1.")
                return
            }
        } else if (sources.size() != 2) {
            ec.message.addError("sources must include exactly FILE_1 and FILE_2")
            return
        }
        Set<String> fileSides = sources.collect { it.fileSide as String }.toSet()
        if (!oneSystem && fileSides != FILE_SIDES.toSet()) {
            ec.message.addError("sources must include FILE_1 and FILE_2")
            return
        }

        sources.each { Map<String, Object> source ->
            String fileSide = source.fileSide as String
            // Per-side allowed set (not a single expected type) so a DB source can pair with an API
            // source on the other file side of the same automation (MACH database-source epic Task 2).
            Set<String> allowedSourceTypes = inputModeEnumId == INPUT_MODE_SFTP_FILES ?
                    [SOURCE_TYPE_SFTP] as Set : [SOURCE_TYPE_API, SOURCE_TYPE_DB] as Set
            if (!allowedSourceTypes.contains(source.sourceTypeEnumId)) {
                ec.message.addError("${fileSide} sourceTypeEnumId must be one of ${allowedSourceTypes.join(', ')}")
            }
            if (!source.systemEnumId) ec.message.addError("${fileSide} systemEnumId is required")
            if (source.nsRestletConfigId) {
                ec.message.addError("${fileSide}: NetSuite RESTlet endpoints were removed (DAR-BE-424): read NetSuite through a NetSuite connection instead.")
            }
            validateSourceMatchesSavedRun(ec, source, savedRun)
            if (source.sourceTypeEnumId == SOURCE_TYPE_SFTP) validateSftpSource(ec, source)
            if (source.sourceTypeEnumId == SOURCE_TYPE_API) validateApiSource(ec, source)
            if (source.sourceTypeEnumId == SOURCE_TYPE_DB) validateDatabaseSource(ec, source)
        }
    }

    protected static void validateChatSpace(def ec, String chatSpaceId) {
        // DAR-BE-077: chatSpaceId is the whole PK, and one() keeps only the PK when it has one, so a
        // tenant condition beside it was silently dropped and another tenant's space passed. The
        // tenant is checked on the row after the read instead.
        def chatSpaceRow = TenantScopedFinder.findOneOwnedByTenant(ec, DarpanEntityConstants.TENANT_CHAT_SPACE,
                "chatSpaceId", chatSpaceId, TenantAccessSupport.currentActiveTenantUserGroupId(ec),
                "automation chat-space check — owner re-checked on the row (PK-only one())")
        if (chatSpaceRow == null || ((chatSpaceRow.isActive)?.toString()?.trim()) == "N") {
            ec.message.addError("Chat space '${chatSpaceId}' was not found or is inactive.")
        }
    }

    protected static void validateSourceMatchesSavedRun(def ec, Map<String, Object> source, Map<String, Object> savedRun) {
        if (!savedRun) return
        String fileSide = source.fileSide as String
        Map<String, Object> matchingOption = null
        if (savedRun.systemOptions instanceof Collection) {
            matchingOption = ((Collection<Map<String, Object>>) savedRun.systemOptions).find {
                it.fileSide == fileSide
            }
        }
        String expectedSystemEnumId = normalize(matchingOption?.enumId)
        if (!expectedSystemEnumId && fileSide == FILE_SIDE_1) expectedSystemEnumId = normalize(savedRun.defaultFile1SystemEnumId)
        if (!expectedSystemEnumId && fileSide == FILE_SIDE_2) expectedSystemEnumId = normalize(savedRun.defaultFile2SystemEnumId)
        if (expectedSystemEnumId && source.systemEnumId && source.systemEnumId != expectedSystemEnumId) {
            ec.message.addError("${fileSide} systemEnumId ${source.systemEnumId} does not match saved run system ${expectedSystemEnumId}")
        }
    }

    protected static void validateSftpSource(def ec, Map<String, Object> source) {
        if (!source.sftpServerId) ec.message.addError("${source.fileSide} sftpServerId is required")
        if (!source.remotePathTemplate) ec.message.addError("${source.fileSide} remotePathTemplate is required")
        if (ec.message.hasError() || !source.sftpServerId) return

        def server = TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.SFTP_SERVER,
                        "sftp server access validated by requireSftpServerAccess after load")
                .condition("sftpServerId", source.sftpServerId)
                .useCache(false)
                .one()
        if (!server) {
            ec.message.addError("SFTP server '${source.sftpServerId}' was not found.")
            return
        }
        try {
            SftpAutomationSupport.requireSftpServerAccess(ec, server,
                    [runTenantUserGroupId: TenantAccessSupport.currentActiveTenantUserGroupId(ec)], "${source.fileSide} source")
        } catch (IllegalArgumentException e) {
            ec.message.addError(e.message)
        }
    }

    protected static void validateApiSource(def ec, Map<String, Object> source) {
        // DAR-UI-064: a side the connector registry knows (an extract service and a config parameter: NetSuite SuiteQL,
        // the OMS GraphQL and order-line endpoints) is identified by its config id, not by a remote. Holding
        // it to the remote rule made every such automation unsavable, so Sync failed on each one the audit made.
        Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, normalize(source.systemEnumId))
        String configParam = normalize(connector?.configParameterName)
        boolean registryDriven = normalize(connector?.extractServiceName) && configParam
        if (!source.systemMessageRemoteId) {
            if (!registryDriven) {
                ec.message.addError("${source.fileSide} API source requires systemMessageRemoteId")
            } else {
                Map metadata = parseJsonMap(source.safeMetadataJson)
                Map parameters = metadata.parameters instanceof Map ? (Map) metadata.parameters : [:]
                if (!normalize(parameters.get(configParam))) {
                    ec.message.addError("${source.fileSide} ${connector.endpointLabel ?: source.systemEnumId} source requires its ${configParam}.")
                }
            }
        }
        if (source.systemMessageRemoteId) {
            def remote = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.service.message.SystemMessageRemote",
                            "framework remote config — SystemMessageRemote is not tenant-owned")
                    .condition("systemMessageRemoteId", source.systemMessageRemoteId)
                    .useCache(false)
                    .one()
            if (!remote && isVirtualShopifyOrdersRemote(source)) {
                remote = ReconciliationSavedRunSupport.ensureVirtualApiOrdersRemote(
                        ec, source.systemEnumId, source.systemMessageRemoteId, source.sourceConfigType)
            }
            if (!remote) {
                ec.message.addError("SystemMessageRemote ${source.systemMessageRemoteId} was not found for ${source.fileSide}.")
            }
        }
        validateApiSourceMetadata(ec, source)
    }

    protected static void validateDatabaseSource(def ec, Map<String, Object> source) {
        String queryId = source.databaseSourceQueryId?.toString()?.trim()
        if (!queryId) ec.message.addError("Database source (${source.fileSide}) requires databaseSourceQueryId.")
    }

    protected static void validateApiSourceMetadata(def ec, Map<String, Object> source) {
        Map<String, Object> metadata = parseJsonMap(source.safeMetadataJson)
        String serviceName = normalize(metadata.extractServiceName ?: metadata.serviceName)
        if (!serviceName) {
            ec.message.addError("${source.fileSide} API source requires safeMetadataJson.extractServiceName.")
            return
        }
        if (serviceName == SHOPIFY_ORDERS_EXTRACT_SERVICE) {
            Map parameters = metadata.parameters instanceof Map ? (Map) metadata.parameters : [:]
            if (!normalize(parameters.shopifyAuthConfigId)) {
                ec.message.addError("${source.fileSide} Shopify API source requires safeMetadataJson.parameters.shopifyAuthConfigId.")
            }
        }
        // Security (HIGH gap 6): the stored extractServiceName is later invoked verbatim via a sync
        // service call with authz disabled on every scheduled run. Without an allowlist a caller could
        // persist any reachable Moqui service name and have it executed. Reject anything outside the
        // registry-derived allow-list so this save-side check stays in lockstep with the dispatch sink
        // (AutomationExecutionSupport.callConfiguredSourceExtractor), which re-checks the same set + a
        // naming guard (DAR-297/DAR-298). For OMS/SHOPIFY this is the same known set as before.
        if (!SourceSystemConnectorSupport.allowedServiceNames(ec).contains(serviceName)) {
            ec.message.addError("${source.fileSide} extractServiceName '${serviceName}' is not in the allowed service list.")
        }
    }

    protected static List<Map<String, Object>> applyApiSourceMetadataDefaults(def ec, List<Map<String, Object>> sources) {
        return sources.collect { Map<String, Object> source ->
            if (source.sourceTypeEnumId != SOURCE_TYPE_API) return source

            Map<String, Object> enriched = new LinkedHashMap<>(source)
            Map<String, Object> metadata = parseJsonMap(enriched.safeMetadataJson)
            Map<String, Object> parameters = metadata.parameters instanceof Map ?
                    new LinkedHashMap<>((Map<String, Object>) metadata.parameters) : [:]

            // Registry-driven defaults (MACH P1): the config-id fallback chain, the default extract
            // service, and the window parameter names all come from the SourceSystemConnector row,
            // so a newly registered source gets the same enrichment with no edit here.
            Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, normalize(enriched.systemEnumId))
            String configParam = connector ? normalize(connector.configParameterName) : null
            if (configParam) {
                String configId = normalize(parameters.get(configParam)) ?:
                        normalize(enriched.get(configParam)) ?:
                        normalize(enriched.sourceConfigId) ?:
                        normalize(enriched.optionKey) ?:
                        AutomationExecutionSupport.findSingleActiveConfigId(ec,
                                TenantAccessSupport.currentActiveTenantUserGroupId(ec),
                                normalize(connector.configEntityName), configParam, normalize(connector.systemEnumId))
                if (configId) parameters.put(configParam, configId)

                String serviceName = normalize(metadata.extractServiceName ?: metadata.serviceName)
                if (!metadata.extractServiceName && configId) {
                    metadata.extractServiceName = serviceName ?: normalize(connector.extractServiceName)
                }
            }

            if (parameters) metadata.parameters = parameters

            String resolvedServiceName = normalize(metadata.extractServiceName ?: metadata.serviceName)
            Map<String, Object> serviceConnector = resolvedServiceName ?
                    SourceSystemConnectorSupport.resolveByExtractServiceName(ec, resolvedServiceName) : null
            if (serviceConnector) {
                enriched.dateFromParameterName = normalize(serviceConnector.dateFromParameterName) ?: enriched.dateFromParameterName
                enriched.dateToParameterName = normalize(serviceConnector.dateToParameterName) ?: enriched.dateToParameterName
            }
            if (metadata) enriched.safeMetadataJson = JsonOutput.toJson(metadata)
            return enriched
        } as List<Map<String, Object>>
    }

    protected static Timestamp resolveNextFireTime(Map input, Timestamp now) {
        String scheduleExpr = normalize(input.scheduleExpr)
        if (!scheduleExpr) return null
        // Audit H15.3: the next fire is computed in the zone being saved (prepareAutomationSave passes the resolved
        // one), through the scanner's own resolver, so save and scanner agree by construction.
        Map automationMap = [
                scheduleExpr          : scheduleExpr,
                windowTimeZone        : normalize(input.windowTimeZone),
                lastScheduledFireTime : toTimestamp(input.lastScheduledFireTime),
        ]
        return AutomationExecutionSupport.resolveNextScheduledFireTime(automationMap, automationMap.lastScheduledFireTime as Timestamp, now)
    }

    protected static boolean isVirtualShopifyOrdersRemote(Map<String, Object> source) {
        if (normalize(source?.systemEnumId) != SHOPIFY_SYSTEM_ENUM_ID) return false
        if (normalize(source?.systemMessageRemoteId) != SHOPIFY_ORDERS_REMOTE_ID) return false
        Map<String, Object> metadata = parseJsonMap(source?.safeMetadataJson)
        return normalize(metadata.extractServiceName ?: metadata.serviceName) == SHOPIFY_ORDERS_EXTRACT_SERVICE
    }
}
