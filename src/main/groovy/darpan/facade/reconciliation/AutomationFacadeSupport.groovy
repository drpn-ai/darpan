package darpan.facade.reconciliation

import darpan.common.DarpanEntityConstants
import darpan.facade.common.FacadeSupport
import darpan.facade.common.SharedConfigAccessSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.facade.settings.SettingsFacadeSupport
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.AutomationRuntimeSupport
import darpan.reconciliation.automation.SftpAutomationSupport
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import darpan.reconciliation.source.SourceFilterSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp
import java.time.Instant

import static darpan.common.ValueSupport.fileNameFromPath
import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeBool
import static darpan.common.ValueSupport.normalizeLower
import static darpan.common.ValueSupport.readField
import static darpan.common.ValueSupport.readString

class AutomationFacadeSupport {
    protected static final Logger logger = LoggerFactory.getLogger(AutomationFacadeSupport.class)

    static final String INPUT_MODE_API_RANGE = "AUT_IN_API_RANGE"
    static final String INPUT_MODE_SFTP_FILES = "AUT_IN_SFTP_FILES"
    static final String SOURCE_TYPE_SFTP = "AUT_SRC_SFTP"
    static final String OMS_SYSTEM_ENUM_ID = "OMS"
    static final String SHOPIFY_SYSTEM_ENUM_ID = "SHOPIFY"
    static final String NETSUITE_SYSTEM_ENUM_ID = "NETSUITE"
    // Constants below reference ReconciliationSavedRunSupport to avoid duplicate string literals.
    static final String SOURCE_TYPE_API = ReconciliationSavedRunSupport.SOURCE_TYPE_API
    static final String SOURCE_TYPE_DB = ReconciliationSavedRunSupport.SOURCE_TYPE_DB
    static final String FILE_SIDE_1 = ReconciliationSavedRunSupport.FILE_SIDE_1
    static final String FILE_SIDE_2 = ReconciliationSavedRunSupport.FILE_SIDE_2
    static final String SOURCE_CONFIG_TYPE_SHOPIFY_AUTH = ReconciliationSavedRunSupport.SOURCE_CONFIG_TYPE_SHOPIFY_AUTH
    static final String SOURCE_CONFIG_TYPE_HOTWAX_OMS_REST = ReconciliationSavedRunSupport.SOURCE_CONFIG_TYPE_HOTWAX_OMS_REST
    static final String SOURCE_CONFIG_TYPE_NETSUITE_AUTH = ReconciliationSavedRunSupport.SOURCE_CONFIG_TYPE_NETSUITE_AUTH
    static final String HOTWAX_ORDERS_REMOTE_ID = ReconciliationSavedRunSupport.HOTWAX_ORDERS_REMOTE_ID
    static final String HOTWAX_ORDERS_ENDPOINT_LABEL = ReconciliationSavedRunSupport.HOTWAX_ORDERS_ENDPOINT_LABEL
    static final String SHOPIFY_ORDERS_REMOTE_ID = ReconciliationSavedRunSupport.SHOPIFY_ORDERS_REMOTE_ID
    static final String SHOPIFY_ORDERS_ENDPOINT_LABEL = ReconciliationSavedRunSupport.SHOPIFY_ORDERS_ENDPOINT_LABEL
    static final String HOTWAX_OMS_ORDERS_EXTRACT_SERVICE = ReconciliationSavedRunSupport.HOTWAX_OMS_ORDERS_EXTRACT_SERVICE
    static final String OMS_RECON_SYSTEM_ENUM_ID = ReconciliationSavedRunSupport.SYSTEM_HOTWAX_OMS_RECON
    static final String OMS_RETURNS_SYSTEM_ENUM_ID = ReconciliationSavedRunSupport.SYSTEM_HOTWAX_OMS_RETURNS
    static final String HOTWAX_RECON_ORDERS_ENDPOINT_LABEL = ReconciliationSavedRunSupport.HOTWAX_RECON_ORDERS_ENDPOINT_LABEL
    static final String HOTWAX_OMS_RECON_ORDERS_EXTRACT_SERVICE = ReconciliationSavedRunSupport.HOTWAX_OMS_RECON_ORDERS_EXTRACT_SERVICE
    static final String SHOPIFY_ORDERS_EXTRACT_SERVICE = ReconciliationSavedRunSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE
    static final String SHOPIFY_GRAPHQL_EXECUTE_SERVICE = ReconciliationSavedRunSupport.SHOPIFY_GRAPHQL_EXECUTE_SERVICE
    static final String HOTWAX_OMS_WINDOW_START_PARAMETER = "windowStart"
    static final String HOTWAX_OMS_WINDOW_END_PARAMETER = "windowEnd"
    static final String SHOPIFY_WINDOW_START_PARAMETER = "windowStart"
    static final String SHOPIFY_WINDOW_END_PARAMETER = "windowEnd"
    static final List<String> FILE_SIDES = [FILE_SIDE_1, FILE_SIDE_2].asImmutable()
    static final List<String> SUPPORTED_INPUT_MODES = [INPUT_MODE_API_RANGE, INPUT_MODE_SFTP_FILES].asImmutable()
    static final List<Map<String, Object>> HOTWAX_OMS_ORDER_PRIMARY_ID_OPTIONS = [
            [fieldPath: "\$.records[*].orderId", label: "Order ID", type: "string"],
            [fieldPath: "\$.records[*].orderName", label: "Order name", type: "string"],
            [fieldPath: "\$.records[*].externalId", label: "External ID", type: "string"],
    ].asImmutable()
    static final List<Map<String, Object>> SHOPIFY_ORDER_PRIMARY_ID_OPTIONS = [
            [fieldPath: "\$.records[*].id", label: "Order ID", type: "ID"],
            [fieldPath: "\$.records[*].name", label: "Order name", type: "String"],
    ].asImmutable()

    /**
     * The fields the rules board offers as pills for an OMS source — a SUPERSET of
     * {@link #HOTWAX_OMS_ORDER_PRIMARY_ID_OPTIONS}, which stays deliberately narrow because primary-ID
     * selection wants key-like fields only.
     *
     * Contents = the OMS connector's {@code keepFieldsBase} (pinned against drift by
     * SourceSystemConnectorSupportSmokeTests) PLUS {@code salesChannelEnumId} and
     * {@code productStoreId}. Exclusion filters run BEFORE projection
     * (OmsRestSourceSupport.prepareOrdersPage), so ANY raw-record field is testable and this list is a
     * curated convenience, not a hard limit — those two are here precisely because they are the
     * shipping exclusion use cases and are NOT in keepFieldsBase.
     *
     * Every entry must name a real top-level key on the OMS order document
     * (org.apache.ofbiz.order.order.OrderHeader), because SourceFilterSupport matches rules against
     * top-level record keys only. A pill naming a field the record does not carry would persist a rule
     * that silently excludes nothing — see the guard tests in darpan-hotwax OmsRestSourceSupportTests.
     *
     * Safe for comparison rules too: resolveExtractKeepFields unions each rule's field paths into the
     * projection keep-set, so a rule drawn on a non-keepFieldsBase field still sees its value.
     */
    static final List<Map<String, Object>> HOTWAX_OMS_ORDER_FIELD_OPTIONS = [
            [fieldPath: "\$.records[*].orderId", label: "Order ID", type: "string"],
            [fieldPath: "\$.records[*].orderName", label: "Order name", type: "string"],
            [fieldPath: "\$.records[*].externalId", label: "External ID", type: "string"],
            [fieldPath: "\$.records[*].grandTotal", label: "Grand total", type: "string"],
            [fieldPath: "\$.records[*].orderDate", label: "Order date", type: "string"],
            [fieldPath: "\$.records[*].statusId", label: "Status", type: "string"],
            [fieldPath: "\$.records[*].salesChannelEnumId", label: "Sales channel", type: "string"],
            [fieldPath: "\$.records[*].productStoreId", label: "Product store", type: "string"],
    ].asImmutable()

    /**
     * The subset of the OMS pills that the /rest/s1/oms/reconciliationOrders connector may offer.
     *
     * The wider two pills exist because on the LEGACY endpoint exclusions run against the full order
     * document before projection, so any raw field is testable. The recon endpoint projects
     * SERVER-side to a fixed set, so salesChannelEnumId and productStoreId never arrive — a rule
     * drawn on either would validate, persist, and then exclude nothing. Offering only what the
     * endpoint actually returns keeps that failure impossible rather than silent.
     *
     * Derived from the list above rather than re-typed, so the two cannot drift apart.
     */
    static final List<Map<String, Object>> HOTWAX_OMS_RECON_ORDER_FIELD_OPTIONS =
            HOTWAX_OMS_ORDER_FIELD_OPTIONS.findAll { Map<String, Object> option ->
                !((String) option.fieldPath).endsWith("salesChannelEnumId") &&
                        !((String) option.fieldPath).endsWith("productStoreId")
            }.asImmutable()

    /**
     * Pills for the OMS returns connector (DAR-BE-018, design §5).
     *
     * Every entry must be a real TOP-LEVEL key on the OMS return header, because SourceFilterSupport
     * reduces a stored expression via CompareIdExpressionSupport.topLevelRecordField and matches
     * against top-level record keys only — a nested or non-existent path persists a rule that
     * validates, excludes nothing, and reports no error.
     *
     * returnChannelEnumId IS offered here, unlike its orders analog salesChannelEnumId on the recon
     * connector: the returns endpoint's server-side projection includes the channel field, so a rule
     * drawn on it actually sees a value. If the OMS ever narrows that projection, or implements the
     * RQ-24 server-side channel filter, remove this pill — see the design's §5 tension note.
     *
     * items[] is deliberately absent: it is a nested array, not a top-level scalar key.
     */
    static final List<Map<String, Object>> HOTWAX_OMS_RETURN_FIELD_OPTIONS = [
            [fieldPath: "\$.records[*].returnId", label: "Return ID", type: "string"],
            [fieldPath: "\$.records[*].externalId", label: "Shopify reference", type: "string"],
            [fieldPath: "\$.records[*].orderExternalId", label: "Order external ID", type: "string"],
            [fieldPath: "\$.records[*].statusId", label: "Status", type: "string"],
            [fieldPath: "\$.records[*].entryDate", label: "Entry date", type: "string"],
            [fieldPath: "\$.records[*].returnTotal", label: "Return total", type: "string"],
            [fieldPath: "\$.records[*].currencyUomId", label: "Currency", type: "string"],
            [fieldPath: "\$.records[*].returnChannelEnumId", label: "Return channel", type: "string"],
    ].asImmutable()

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
        if (!ec.message.hasError()) {
            validateStateModeSources(ec, input, sourceEntries).each { String error -> ec.message.addError(error) }
        }

        String chatSpaceId = normalize(input.chatSpaceId) ?: null
        if (!ec.message.hasError() && chatSpaceId) {
            validateChatSpace(ec, chatSpaceId)
        }

        Map<String, Object> automationFields = null
        if (!ec.message.hasError()) {
            Timestamp now = nowTimestamp(ec)
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
                            resolveNextFireTime(input, now),
                    lastScheduledFireTime : toTimestamp(input.lastScheduledFireTime),
                    relativeWindowTypeEnumId: input.relativeWindowTypeEnumId,
                    relativeWindowCount   : input.relativeWindowCount,
                    customWindowStartDate : toTimestamp(input.customWindowStartDate),
                    customWindowEndDate   : toTimestamp(input.customWindowEndDate),
                    maxWindowDays         : input.maxWindowDays,
                    splitWindowDays       : input.splitWindowDays,
                    windowTimeZone        : input.windowTimeZone ?: "UTC",
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

    static Map<String, Object> buildAutomationRow(def ec, def automation, boolean includeSources, List sources, List executions) {
        String automationId = readString(automation, "automationId")
        List sourceRows = sources ?: []
        List executionRows = executions ?: []
        Map<String, Object> savedRun = resolveSavedRunSummary(ec, automation)
        String inputModeEnumId = readString(automation, "inputModeEnumId")
        String isActive = readString(automation, "isActive") ?: "Y"
        Map<String, Object> chatSpaceSummary = resolveChatSpaceSummary(ec, automation)
        Map<String, Object> row = [
                automationId          : automationId,
                automationName        : readString(automation, "automationName"),
                description           : readString(automation, "description"),
                companyUserGroupId    : readString(automation, "companyUserGroupId"),
                companyLabel          : TenantAccessSupport.resolveTenantLabelForUserGroupId(ec, readString(automation, "companyUserGroupId")),
                savedRunId            : readString(automation, "savedRunId"),
                savedRunName          : savedRun?.runName,
                savedRunType          : readString(automation, "savedRunType") ?: savedRun?.runType ?: "ruleset",
                ruleSetId             : readString(automation, "ruleSetId"),
                compareScopeId        : readString(automation, "compareScopeId"),
                reconciliationMappingId: readString(automation, "reconciliationMappingId"),
                inputModeEnumId       : inputModeEnumId,
                inputModeLabel        : enumLabel(ec, inputModeEnumId),
                inputModeCode         : enumCode(ec, inputModeEnumId),
                sourceSummary         : buildSourceSummary(ec, inputModeEnumId, sourceRows),
                scheduleExpr          : readString(automation, "scheduleExpr"),
                scheduleSummary       : buildScheduleSummary(automation),
                timezone              : readString(automation, "windowTimeZone") ?: "UTC",
                nextScheduledFireTime : readField(automation, "nextScheduledFireTime"),
                lastScheduledFireTime : readField(automation, "lastScheduledFireTime"),
                relativeWindowTypeEnumId: readString(automation, "relativeWindowTypeEnumId"),
                relativeWindowLabel   : enumLabel(ec, readString(automation, "relativeWindowTypeEnumId")),
                relativeWindowCount   : readField(automation, "relativeWindowCount"),
                customWindowStartDate : readField(automation, "customWindowStartDate"),
                customWindowEndDate   : readField(automation, "customWindowEndDate"),
                maxWindowDays         : readField(automation, "maxWindowDays"),
                splitWindowDays       : readField(automation, "splitWindowDays"),
                isActive              : isActive,
                active                : isActive != "N",
                chatSpaceId           : chatSpaceSummary.chatSpaceId,
                chatSpaceName         : chatSpaceSummary.chatSpaceName,
                chatSpaceActive       : chatSpaceSummary.chatSpaceActive,
                lastExecution         : executionRows ? buildExecutionRow(ec, executionRows.first()) : null,
                executionCount        : executionRows.size(),
                permissions           : buildPermissions(ec, isActive),
                createdDate           : readField(automation, "createdDate"),
                lastUpdatedDate       : readField(automation, "lastUpdatedDate"),
        ].findAll { it.value != null } as Map<String, Object>
        if (includeSources) {
            // Reused whole-automation lookup rather than one query per source: cheap for the
            // single-record get/save/pause/resume paths this runs on (list#Automations passes
            // includeSources=false and never reaches here).
            Map<String, List<Map<String, Object>>> filtersByFileSide = captureSourceFiltersByFileSide(ec, automationId)
            row.sources = sourceRows.collect { source -> buildSourceRow(ec, source, filtersByFileSide) }
            row.savedRun = savedRun
        }
        return row
    }

    protected static Map<String, Object> buildPermissions(def ec, String isActive) {
        boolean canEdit = TenantAccessSupport.hasActiveTenantWriteAccess(ec)
        boolean canRun = TenantAccessSupport.hasActiveTenantRunAccess(ec)
        boolean active = (isActive ?: "Y") != "N"
        return [
                canViewHistory: true,
                canEdit       : canEdit,
                canDelete     : canEdit,
                canPause      : canEdit && active,
                canResume     : canEdit && !active,
                canRunNow     : canRun,
        ]
    }

    static Map<String, Object> buildExecutionRow(def ec, def execution) {
        String statusEnumId = readString(execution, "statusEnumId")
        Map<String, String> resultFields = executionResultFields(ec, execution)
        return [
                automationExecutionId    : readString(execution, "automationExecutionId"),
                automationId             : readString(execution, "automationId"),
                companyUserGroupId       : readString(execution, "companyUserGroupId"),
                statusEnumId             : statusEnumId,
                statusLabel              : enumLabel(ec, statusEnumId),
                scheduledDate            : readField(execution, "scheduledDate"),
                startedDate              : readField(execution, "startedDate"),
                completedDate            : readField(execution, "completedDate"),
                parentAutomationExecutionId: readString(execution, "parentAutomationExecutionId"),
                childWindowSequenceNum   : readField(execution, "childWindowSequenceNum"),
                windowStartDate          : readField(execution, "windowStartDate"),
                windowEndDate            : readField(execution, "windowEndDate"),
                childWindowStartDate     : readField(execution, "childWindowStartDate"),
                childWindowEndDate       : readField(execution, "childWindowEndDate"),
                file1Name                : readString(execution, "file1Name"),
                file2Name                : readString(execution, "file2Name"),
                resultFileName           : resultFields.resultFileName,
                resultDataManagerPath    : resultFields.resultDataManagerPath,
                reconciliationRunResultId: resultFields.reconciliationRunResultId,
                file1RecordCount         : readField(execution, "file1RecordCount"),
                file2RecordCount         : readField(execution, "file2RecordCount"),
                differenceCount          : readField(execution, "differenceCount"),
                onlyInFile1Count         : readField(execution, "onlyInFile1Count"),
                onlyInFile2Count         : readField(execution, "onlyInFile2Count"),
                errorMessage             : readString(execution, "errorMessage"),
                createdDate              : readField(execution, "createdDate"),
                lastUpdatedDate          : readField(execution, "lastUpdatedDate"),
        ].findAll { it.value != null } as Map<String, Object>
    }

    protected static Map<String, String> executionResultFields(def ec, def execution) {
        String reconciliationRunResultId = readString(execution, "reconciliationRunResultId")
        String resultDataManagerPath = readString(execution, "resultDataManagerPath")
        String resultFileName = readString(execution, "resultFileName")

        if (reconciliationRunResultId && (!resultDataManagerPath || !resultFileName)) {
            // P0 #4 step 4b: route through finder for uniform tenant scoping (null on foreign tenant — caller handles null).
            def runResult = TenantScopedFinder.findTenantScopedByIdQuiet(
                    ec, DarpanEntityConstants.RECONCILIATION_RUN_RESULT,
                    "reconciliationRunResultId", reconciliationRunResultId)
            String runResultPath = readString(runResult, "resultDataManagerPath")
            if (!resultDataManagerPath) resultDataManagerPath = runResultPath
            if (!resultFileName) resultFileName = fileNameFromPath(runResultPath)
        }

        return [
                reconciliationRunResultId: reconciliationRunResultId,
                resultDataManagerPath    : resultDataManagerPath,
                resultFileName           : resultFileName,
        ]
    }

    protected static Map<String, Object> buildSourceRow(def ec, def source, Map<String, List<Map<String, Object>>> filtersByFileSide = [:]) {
        String sourceTypeEnumId = readString(source, "sourceTypeEnumId")
        String systemEnumId = readString(source, "systemEnumId")
        String fileSide = readString(source, "fileSide")
        return [
                automationId          : readString(source, "automationId"),
                fileSide              : fileSide,
                companyUserGroupId    : readString(source, "companyUserGroupId"),
                sourceTypeEnumId      : sourceTypeEnumId,
                sourceTypeLabel       : enumLabel(ec, sourceTypeEnumId),
                systemEnumId          : systemEnumId,
                systemLabel           : enumLabel(ec, systemEnumId),
                fileTypeEnumId        : readString(source, "fileTypeEnumId"),
                fileTypeLabel         : enumLabel(ec, readString(source, "fileTypeEnumId")),
                schemaFileName        : readString(source, "schemaFileName"),
                recordRootExpression  : readString(source, "recordRootExpression"),
                primaryIdExpression   : readString(source, "primaryIdExpression"),
                idValueNormalizer     : readString(source, "idValueNormalizer"),
                systemMessageRemoteId : readString(source, "systemMessageRemoteId"),
                nsRestletConfigId     : readString(source, "nsRestletConfigId"),
                sftpServerId          : readString(source, "sftpServerId"),
                sftpServerLabel       : sftpServerLabel(ec, readString(source, "sftpServerId")),
                remotePathTemplate    : readString(source, "remotePathTemplate"),
                fileNamePattern       : readString(source, "fileNamePattern"),
                apiRequestTemplateJson: readString(source, "apiRequestTemplateJson"),
                apiResponsePathExpression: readString(source, "apiResponsePathExpression"),
                dateFromParameterName : readString(source, "dateFromParameterName"),
                dateToParameterName   : readString(source, "dateToParameterName"),
                safeMetadataJson      : readString(source, "safeMetadataJson"),
                excludeFilters        : buildSourceExcludeFilterResponse(filtersByFileSide?.get(fileSide)),
                createdDate           : readField(source, "createdDate"),
                lastUpdatedDate       : readField(source, "lastUpdatedDate"),
        ].findAll { it.value != null } as Map<String, Object>
    }

    /** Captured filter rows (stored shape) in wire shape: values as a List, not the stored CSV string. */
    protected static List<Map<String, Object>> buildSourceExcludeFilterResponse(List<Map<String, Object>> capturedRows) {
        if (!capturedRows) return []
        return capturedRows.collect { Map<String, Object> row ->
            [
                    fieldExpression: row.fieldExpression,
                    operator       : row.operator,
                    values         : SourceFilterSupport.splitValues(row.filterValues as String),
            ] as Map<String, Object>
        }
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

    /** Rule set rows for this automation's compare scope and side; empty when it has no compareScopeId. */
    protected static List<Map<String, Object>> seedFromRuleSet(def ec, String rawCompareScopeId, String fileSide) {
        String compareScopeId = normalize(rawCompareScopeId)
        if (!compareScopeId || !fileSide) return []
        // Same entity, same directly-tenant-owned read pattern as
        // ReconciliationSavedRunSupport.resolveExtractExcludeFilters (P0 #4 — bare disableAuthz is
        // CI-banned by DisableAuthzRatchetTest). Session-scoped by design: this is the save#Automation
        // path, where the caller's active tenant is exactly the tenant the compareScopeId belongs to.
        // Do NOT reuse this for a cross-tenant sweep — see seedFromRuleSetGlobal.
        List rows = TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSetCompareSourceFilter")
                .condition("compareScopeId", compareScopeId)
                .condition("fileSide", fileSide)
                .orderBy("sequenceNum")
                .useCache(false).list() ?: []
        return mapRuleSetFilterRows(rows)
    }

    /**
     * Same rule-set rows as {@link #seedFromRuleSet}, but read without session-tenant scoping.
     *
     * Task 13 fix round 1, Critical 1: {@link #backfillAutomationExcludeFilters} sweeps every
     * tenant's automations from a single call with no active tenant of its own. Routing that sweep's
     * rule-set read through {@code seedFromRuleSet} — which pre-applies
     * {@code TenantScopedFinder.findTenantScoped}'s SESSION tenant — silently returned {@code []} for
     * every automation whose tenant differed from the caller's active tenant, and returned {@code []}
     * for everything when there was no active tenant at all, both indistinguishable from "no
     * exclusions configured". The automation's own {@code compareScopeId} already pins its tenant, so
     * this reads by {@code compareScopeId} + {@code fileSide} alone, the same unscoped pattern
     * {@code AutomationRuntimeSupport.loadAutomationSourceFilters} already uses for the equivalent
     * no-active-tenant case on the automation-runner side.
     *
     * Do not use this from any session-scoped, user-entry-point code path — it deliberately skips the
     * tenant gate {@link #seedFromRuleSet} applies for its normal (save#Automation) caller.
     */
    protected static List<Map<String, Object>> seedFromRuleSetGlobal(def ec, String rawCompareScopeId, String fileSide) {
        String compareScopeId = normalize(rawCompareScopeId)
        if (!compareScopeId || !fileSide) return []
        List rows = TenantScopedFinder.findGlobalUnscoped(
                ec, "darpan.rule.RuleSetCompareSourceFilter",
                "one-time cross-tenant backfill reads rule-set filter rows for an automation whose tenant is pinned by its own compareScopeId, not by the (absent or mismatched) session tenant")
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

    /**
     * One-time backfill for automations created before exclusion filters existed. The create-time
     * seed only fires for a fileSide with no ReconciliationAutomationSource row, and every existing
     * automation has both — so without this there is no write path at all for their filter rows and
     * every pre-existing schedule silently disagrees with its interactive run.
     *
     * Cross-tenant by design: this sweeps every tenant's automations from a single admin-triggered
     * call with no active tenant of its own, so every read/write inside it resolves tenant from the
     * automation/compareScopeId being processed, never from ec.user's session tenant — see
     * {@link #seedFromRuleSetGlobal}.
     *
     * Idempotent and non-destructive: a side that already has filter rows is left completely alone,
     * so a snapshot deliberately frozen at an older rule-set state is never refreshed.
     *
     * Transaction semantics: each automation's read-then-write is chunked into its own transaction via
     * {@code AutomationRuntimeSupport.runInNewTransaction} — {@code runRequireNew}, not
     * {@code runUseOrBegin} — so the guarantee holds unconditionally, even if this is ever invoked
     * from inside an already-open ambient transaction (a bare {@code runUseOrBegin}-based chunk would
     * silently join that transaction instead of starting its own, restoring the all-or-nothing
     * exposure this exists to remove — Task 13 fix round 2, New Important 3). It addresses the same
     * CLASS of bug as the request-transaction-timeout fix on the saved-run path (see the "UAT run
     * tx-timeout root cause" fix) but by a different mechanism: that fix suspends the caller's
     * transaction outright, while {@code runRequireNew} runs the work on a fresh thread with an empty
     * transaction stack (TransactionFacadeImpl.requireNewThread is a {@code final static true}).
     * A large instance's full automation set is not made to fit inside the ~60s default request
     * transaction as one all-or-nothing unit, and a failure partway through one automation rolls back only that
     * automation's own writes, not every automation already committed before it. A failure on one
     * automation is caught, recorded as an {@code ec.message} error (surfaced via the
     * {@code ok}/{@code errors} out-parameters), and does not stop the sweep from continuing to the
     * rest. Re-running after a partial failure is safe: already-seeded sides are skipped (idempotent),
     * so re-running only retries the automations that did not get a row.
     *
     * Known limitation: a side with zero rows because an operator deliberately cleared it is
     * indistinguishable from one that was never seeded, and will be re-seeded. That is acceptable
     * today because no UI path can clear an automation's exclusions — the automation wizard never
     * submits them — so zero rows means never-seeded in practice.
     */
    static Map<String, Object> backfillAutomationExcludeFilters(def ec) {
        int automationsScanned = 0
        int automationsSkipped = 0
        int sidesSeeded = 0
        int rowsCreated = 0
        // Collected here, not added to ec.message inside the loop: Moqui's ServiceCallSyncImpl
        // refuses to run ANY service — including the next automation's own create# calls — while
        // ec.message.hasError() is true (checked at the top of every single service call, framework-
        // wide, before its own actions ever run). Adding an error via ec.message.addError inside the
        // loop would silently no-op every automation processed after the first failure: their
        // create# calls would return null without adding a NEW error, the per-create# delta check
        // would see nothing to react to, and rowsCreated would increment for rows that were never
        // actually written (Task 13 fix round 2 — discovered while closing the "ok scoped to this
        // call's own errors" minor finding). Errors are batched into ec.message once, after the loop,
        // instead.
        List<String> failureMessages = []

        List automations = TenantScopedFinder.findGlobalUnscoped(
                ec, "darpan.reconciliation.ReconciliationAutomation",
                "one-time backfill sweeps every tenant's automations by design; no active tenant, no user entry point")
                .orderBy("automationId")
                .useCache(false).list() ?: []

        for (def automation : automations) {
            automationsScanned++
            String automationId = normalize(automation.automationId)
            String compareScopeId = normalize(automation.compareScopeId)
            if (!compareScopeId) { automationsSkipped++; continue }

            String tenantUserGroupId = normalize(automation.companyUserGroupId)
            String createdByUserId = normalize(automation.createdByUserId)

            try {
                Map<String, Integer> automationCounts = AutomationRuntimeSupport.runInNewTransaction(ec,
                        "One-time backfill: seed exclusion filters for automation ${automationId}".toString(), {
                    return backfillOneAutomation(ec, automationId, compareScopeId, tenantUserGroupId, createdByUserId)
                }) as Map<String, Integer>
                sidesSeeded += automationCounts.sidesSeeded
                rowsCreated += automationCounts.rowsCreated
            } catch (Exception e) {
                logger.warn("Exclusion-filter backfill failed for automation ${automationId}; skipping — safe to re-run", e)
                failureMessages << "Exclusion-filter backfill failed for automation ${automationId}: ${e.message}".toString()
            } finally {
                // See the comment above failureMessages: clear whatever this automation's failure (or
                // seedFromRuleSetGlobal / the existing-rows read) left on ec.message so the NEXT
                // automation's create# calls are not silently blocked by it.
                if (ec.message.hasError()) ec.message.clearErrors()
            }
        }
        failureMessages.each { ec.message.addError(it) }

        return [
                automationsScanned: automationsScanned,
                automationsSkipped: automationsSkipped,
                sidesSeeded       : sidesSeeded,
                rowsCreated       : rowsCreated,
        ] as Map<String, Object>
    }

    /**
     * One automation's worth of backfill work — the unit {@code AutomationRuntimeSupport.runInNewTransaction}
     * chunks a transaction around, so either both sides of one automation commit or neither does.
     */
    private static Map<String, Integer> backfillOneAutomation(def ec, String automationId, String compareScopeId,
            String tenantUserGroupId, String createdByUserId) {
        int sidesSeeded = 0
        int rowsCreated = 0
        Timestamp nowTs = ec.user.nowTimestamp

        for (String fileSide : FILE_SIDES) {
            // Same unscoped-read pattern as AutomationRuntimeSupport.loadAutomationSourceFilters:
            // the automation was already resolved via the unscoped sweep above, so this is a
            // continuation of the same tenant-neutral migration read, not a fresh access decision
            // (P0 #4 — bare disableAuthz is CI-banned by DisableAuthzRatchetTest).
            List existing = TenantScopedFinder.findGlobalUnscoped(
                    ec, "darpan.reconciliation.ReconciliationAutomationSourceFilter",
                    "one-time backfill checks whether this automation/fileSide already has filter rows before seeding")
                    .condition("automationId", automationId)
                    .condition("fileSide", fileSide)
                    .useCache(false).list() ?: []
            if (existing) continue

            // Global (tenant-unscoped) read, not seedFromRuleSet's session-scoped one: this
            // automation's own compareScopeId already pins its tenant, and the sweep may be running
            // under a different (or no) session tenant (Task 13 fix round 1, Critical 1).
            List<Map<String, Object>> seeded = seedFromRuleSetGlobal(ec, compareScopeId, fileSide)
            if (!seeded) continue

            sidesSeeded++
            int sequenceNum = 0
            for (Map<String, Object> row : seeded) {
                sequenceNum++
                // FacadeSupport.getErrors (errorList + validationErrorList), not the narrower bare
                // ec.message.getErrors (errorList only): a create# that fails Moqui PARAMETER
                // validation (e.g. a value that cannot convert to the target field's type) adds a
                // ValidationError, not an Error — invisible to ec.message.getErrors() alone, which
                // would let rowsCreated increment for a row that was never actually written (Task 13
                // fix round 2, New Important 2).
                int errorCountBefore = FacadeSupport.getErrors(ec).size()
                // Write, not a read — TenantScopedFinder has no create-side equivalent, so this
                // stays a bare disableAuthz service call, same category as the other cross-tenant
                // migration writes already allowlisted in DisableAuthzRatchetTest (KEPT BARE).
                ec.service.sync().name("create#darpan.reconciliation.ReconciliationAutomationSourceFilter")
                        .parameters([
                                automationId      : automationId,
                                fileSide          : fileSide,
                                sequenceNum       : sequenceNum,
                                fieldExpression   : row.fieldExpression,
                                operator          : row.operator,
                                filterValues      : row.filterValues,
                                // Stamped from the automation, not the session: the sweep runs
                                // across every tenant with no active tenant of its own, and the
                                // tenant-scoped capture on the save path cannot see an unstamped row.
                                companyUserGroupId: tenantUserGroupId,
                                createdByUserId   : createdByUserId,
                                createdDate       : nowTs,
                                lastUpdatedDate   : nowTs,
                        ]).disableAuthz().call()
                // Checked as a size delta against errors already present before this specific call,
                // not a bare ec.message.hasError(): this sweep processes many automations against the
                // same ec, and a bare hasError() would stay tripped by an earlier automation's failure
                // for the rest of the run, mis-flagging every later create as failed too (Task 13 fix
                // round 1, Important 3).
                List<String> newErrors = FacadeSupport.getErrors(ec).drop(errorCountBefore)
                if (newErrors) {
                    throw new IllegalStateException(
                            "create#ReconciliationAutomationSourceFilter failed for automation ${automationId} " +
                            "(${fileSide}, sequenceNum ${sequenceNum}): ${newErrors.join('; ')}".toString())
                }
                rowsCreated++
            }
        }
        return [sidesSeeded: sidesSeeded, rowsCreated: rowsCreated]
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
        if (sources.size() != 2) {
            ec.message.addError("sources must include exactly FILE_1 and FILE_2")
            return
        }
        Set<String> fileSides = sources.collect { it.fileSide as String }.toSet()
        if (fileSides != FILE_SIDES.toSet()) {
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
            validateSourceMatchesSavedRun(ec, source, savedRun)
            if (source.sourceTypeEnumId == SOURCE_TYPE_SFTP) validateSftpSource(ec, source)
            if (source.sourceTypeEnumId == SOURCE_TYPE_API) validateApiSource(ec, source)
            if (source.sourceTypeEnumId == SOURCE_TYPE_DB) validateDatabaseSource(ec, source)
        }
    }

    /**
     * A state-based automation defines its population by status on BOTH sides. If one side still
     * receives a date window while the other does not, the two extracts describe different populations
     * and every non-overlapping record reads as a genuine discrepancy. Caught on save, because at run
     * time the result looks like a real finding rather than a configuration error.
     */
    static List<String> validateStateModeSources(def ec, def automation, Collection sources) {
        if (!AutomationExecutionSupport.isStateWindowMode(automation)) return []

        List<String> errors = []
        (sources ?: []).each { Object source ->
            String systemEnumId = normalize(readField(source, "systemEnumId"))
            String fileSide = normalize(readField(source, "fileSide"))
            Map<String, Object> connector = SourceSystemConnectorSupport.resolveBySystemEnumId(ec, systemEnumId)
            if (!connector?.supportsStateExtract) {
                errors.add(("Source ${fileSide} (${systemEnumId}) cannot run without a date window. " +
                        "Choose a date-based schedule window, or a source system that supports " +
                        "status-defined extraction.").toString())
            }
        }
        return errors
    }

    protected static void validateChatSpace(def ec, String chatSpaceId) {
        // P0 #4: tenant condition pre-applied by TenantScopedFinder (no bare disableAuthz — see
        // DisableAuthzRatchetTest); equivalent to the old explicit companyUserGroupId condition
        // since findTenantScoped resolves the same currentActiveTenantUserGroupId internally.
        def chatSpaceRow = TenantScopedFinder.findTenantScoped(ec, DarpanEntityConstants.TENANT_CHAT_SPACE)
                .condition("chatSpaceId", chatSpaceId)
                .useCache(false).one()
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
            SftpAutomationSupport.requireSftpServerAccess(ec, server, [
                    runScopeEnumId      : SftpAutomationSupport.SFTP_SCOPE_TENANT,
                    runTenantUserGroupId: TenantAccessSupport.currentActiveTenantUserGroupId(ec),
                    allowAdminSftp      : false,
            ], "${source.fileSide} source")
        } catch (IllegalArgumentException e) {
            ec.message.addError(e.message)
        }
    }

    protected static void validateApiSource(def ec, Map<String, Object> source) {
        if (!source.systemMessageRemoteId && !source.nsRestletConfigId) {
            ec.message.addError("${source.fileSide} API source requires systemMessageRemoteId or nsRestletConfigId")
        }
        if (source.systemMessageRemoteId) {
            def remote = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.service.message.SystemMessageRemote",
                            "framework remote config — SystemMessageRemote is not tenant-owned")
                    .condition("systemMessageRemoteId", source.systemMessageRemoteId)
                    .useCache(false)
                    .one()
            if (!remote && isVirtualApiOrdersRemote(source)) {
                remote = ReconciliationSavedRunSupport.ensureVirtualApiOrdersRemote(
                        ec, source.systemEnumId, source.systemMessageRemoteId, source.sourceConfigType)
            }
            if (!remote) {
                ec.message.addError("SystemMessageRemote ${source.systemMessageRemoteId} was not found for ${source.fileSide}.")
            }
        }
        if (source.nsRestletConfigId) {
            // P0 #4 step 4b: unified tenant-scoped load; sets not-found/forbidden error on ec.message if denied.
            def restlet = TenantScopedFinder.findTenantScopedById(
                    ec, DarpanEntityConstants.NS_RESTLET_CONFIG,
                    "nsRestletConfigId", source.nsRestletConfigId)
            if (restlet && (readString(restlet, "isActive") ?: "Y").equalsIgnoreCase("N")) {
                ec.message.addError("NsRestletConfig ${source.nsRestletConfigId} is inactive.")
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
        // Both OMS orders connectors read their credentials from the same parameter, so both must be
        // held to the same save-side requirement — keying on one service name alone would let a
        // recon-orders source save with no config id and fail later inside a scheduled run.
        if (serviceName == HOTWAX_OMS_ORDERS_EXTRACT_SERVICE || serviceName == HOTWAX_OMS_RECON_ORDERS_EXTRACT_SERVICE) {
            Map parameters = metadata.parameters instanceof Map ? (Map) metadata.parameters : [:]
            if (!normalize(parameters.omsRestSourceConfigId)) {
                ec.message.addError("${source.fileSide} OMS API source requires safeMetadataJson.parameters.omsRestSourceConfigId.")
            }
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
                String configId = normalize(parameters[configParam]) ?:
                        normalize(enriched[configParam]) ?:
                        normalize(enriched.sourceConfigId) ?:
                        normalize(enriched.optionKey) ?:
                        AutomationExecutionSupport.findSingleActiveConfigId(ec,
                                TenantAccessSupport.currentActiveTenantUserGroupId(ec),
                                normalize(connector.configEntityName), configParam)
                if (configId) parameters[configParam] = configId

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

    static List collapseExecutionHistory(List executions) {
        List visibleExecutions = []
        Set<String> seenWindowKeys = [] as Set
        (executions ?: []).each { execution ->
            String key = logicalExecutionWindowKey(execution)
            if (key && !seenWindowKeys.add(key)) return
            visibleExecutions << execution
        }
        return visibleExecutions
    }

    protected static String logicalExecutionWindowKey(def execution) {
        Object childStart = readField(execution, "childWindowStartDate")
        Object childEnd = readField(execution, "childWindowEndDate")
        if (childStart == null && childEnd == null) return null
        return [
                readString(execution, "automationId"),
                timestampKey(childStart),
                timestampKey(childEnd),
        ].join("|")
    }

    protected static String timestampKey(Object value) {
        if (value == null) return ""
        if (value instanceof Date) return Long.toString(((Date) value).time)
        return value.toString()
    }

    protected static Map<String, Object> resolveSavedRunSummary(def ec, def automation) {
        String savedRunId = readString(automation, "savedRunId")
        if (!savedRunId) return null
        return ReconciliationSavedRunSupport.findSavedRunById(ec, savedRunId)
    }

    protected static Map<String, Object> resolveChatSpaceSummary(def ec, def automation) {
        String chatSpaceId = readString(automation, "chatSpaceId")
        // P0 #4: tenant-scoped read (no bare disableAuthz — see DisableAuthzRatchetTest); the
        // automation's chatSpaceId was already validated against the active tenant at save time.
        def chatSpaceRow = chatSpaceId ? TenantScopedFinder.findTenantScoped(ec, DarpanEntityConstants.TENANT_CHAT_SPACE)
                .condition("chatSpaceId", chatSpaceId).useCache(true).one() : null
        return [
                chatSpaceId    : chatSpaceId,
                chatSpaceName  : chatSpaceRow?.spaceName,
                chatSpaceActive: chatSpaceRow != null && ((chatSpaceRow.isActive)?.toString()?.trim()) != "N",
        ]
    }

    protected static String buildSourceSummary(def ec, String inputModeEnumId, List sources) {
        String mode = inputModeEnumId == INPUT_MODE_SFTP_FILES ? "SFTP" : "API"
        List<String> parts = sources.collect { source ->
            String systemLabel = enumLabel(ec, readString(source, "systemEnumId")) ?: readString(source, "systemEnumId")
            String target = readString(source, "sftpServerId") ?:
                    readString(source, "nsRestletConfigId") ?:
                    readString(source, "systemMessageRemoteId")
            return target ? "${systemLabel} via ${target}".toString() : systemLabel
        }.findAll { it } as List<String>
        return parts ? "${mode}: ${parts.join(' -> ')}".toString() : mode
    }

    protected static String buildScheduleSummary(def automation) {
        String scheduleExpr = readString(automation, "scheduleExpr")
        if (!scheduleExpr) return "Not scheduled"
        if (scheduleExpr.startsWith("P")) return scheduleExpr
        return "Cron: ${scheduleExpr}".toString()
    }

    protected static Timestamp resolveNextFireTime(Map input, Timestamp now) {
        String scheduleExpr = normalize(input.scheduleExpr)
        if (!scheduleExpr) return null
        // Audit H15.3 — previously defaulted to 'UTC' when input.windowTimeZone was unset, so a UI
        // preview of an automation persisted with windowTimeZone='America/Los_Angeles' (but called
        // here without explicitly passing it through) would show next-fire times in UTC, diverging
        // from what the scanner actually fires (the scanner reads the entity's windowTimeZone).
        // Pull from input.windowTimeZone first; if absent, look at the embedded automation map; only
        // then fall back to UTC.
        String zone = normalize(input.windowTimeZone)
        if (!zone && input.automation instanceof Map) zone = normalize(((Map) input.automation).windowTimeZone)
        Map automationMap = [
                scheduleExpr          : scheduleExpr,
                windowTimeZone        : zone ?: "UTC",
                lastScheduledFireTime : toTimestamp(input.lastScheduledFireTime),
        ]
        return AutomationExecutionSupport.resolveNextScheduledFireTime(automationMap, automationMap.lastScheduledFireTime as Timestamp, now)
    }

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
                    // Only DarpanSystemSource rows populate this (endpoint rows like OMS_RETURNS
                    // point at their parent system, e.g. OMS) — every other enum type's rows have
                    // no parentEnumId value, so findAll below strips the key out entirely rather
                    // than emitting parentEnumId:null, leaving their contract shape unchanged.
                    parentEnumId: readString(item, "parentEnumId"),
            ].findAll { it.value != null }
        } as List<Map<String, Object>>
        return SettingsFacadeSupport.deduplicateEnumOptions(enumTypeId, mappedOptions)
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
        return listOmsRestSourceConfigOptions(ec) + listShopifyAuthConfigOptions(ec) + listNsAuthConfigOptions(ec)
    }

    protected static List<Map<String, Object>> listOmsRestSourceConfigOptions(def ec) {
        String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!activeTenantUserGroupId) return []
        try {
            // DAR-BE-005: owned rows plus rows shared to this tenant via ConfigTenantAccess.
            // listAccessibleConfigRows applies no status filter, so isActive/canReadOrders are
            // preserved here — the source picker must not offer inactive or non-order-reading configs.
            List rows = SharedConfigAccessSupport
                    .listAccessibleConfigRows(ec, SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS)
                    .findAll { normalize(readString(it, "isActive")) != "N" && normalizeBool(readField(it, "canReadOrders")) }
            return rows.collect { item ->
                String configId = readString(item, "omsRestSourceConfigId")
                String label = readString(item, "description") ?: configId
                [
                        sourceConfigId      : configId,
                        sourceConfigType    : SOURCE_CONFIG_TYPE_HOTWAX_OMS_REST,
                        omsRestSourceConfigId: configId,
                        description         : readString(item, "description"),
                        systemEnumId        : OMS_SYSTEM_ENUM_ID,
                        systemLabel         : enumLabel(ec, OMS_SYSTEM_ENUM_ID),
                        label               : label,
                ].findAll { it.value != null } as Map<String, Object>
            } as List<Map<String, Object>>
        } catch (Exception e) {
            logger.warn("Failed to load source config options", e)
            return []
        }
    }

    protected static List<Map<String, Object>> listShopifyAuthConfigOptions(def ec) {
        String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!activeTenantUserGroupId) return []
        try {
            // DAR-BE-005: owned rows plus rows shared to this tenant via ConfigTenantAccess.
            // listAccessibleConfigRows applies no status filter, so isActive/canReadOrders are
            // preserved here — the source picker must not offer inactive or non-order-reading configs.
            List rows = SharedConfigAccessSupport
                    .listAccessibleConfigRows(ec, SharedConfigAccessSupport.CONFIG_TYPE_SHOPIFY_AUTH)
                    .findAll { normalize(readString(it, "isActive")) != "N" && normalizeBool(readField(it, "canReadOrders")) }
            return rows.collect { item ->
                String configId = readString(item, "shopifyAuthConfigId")
                String label = readString(item, "description") ?: configId
                [
                        sourceConfigId     : configId,
                        sourceConfigType   : SOURCE_CONFIG_TYPE_SHOPIFY_AUTH,
                        shopifyAuthConfigId: configId,
                        description        : readString(item, "description"),
                        systemEnumId       : SHOPIFY_SYSTEM_ENUM_ID,
                        systemLabel        : enumLabel(ec, SHOPIFY_SYSTEM_ENUM_ID),
                        dateFromParameterName: SHOPIFY_WINDOW_START_PARAMETER,
                        dateToParameterName  : SHOPIFY_WINDOW_END_PARAMETER,
                        safeMetadataJson   : JsonOutput.toJson([
                                extractServiceName: SHOPIFY_ORDERS_EXTRACT_SERVICE,
                                parameters        : [shopifyAuthConfigId: configId],
                        ]),
                        label              : label,
                ].findAll { it.value != null } as Map<String, Object>
            } as List<Map<String, Object>>
        } catch (Exception e) {
            logger.warn("Failed to load source config options", e)
            return []
        }
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

    static List<Map<String, Object>> listNsRestletOptions(def ec) {
        String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!activeTenantUserGroupId) return []
        // DAR-BE-005 B5: owned rows plus rows shared to this tenant via ConfigTenantAccess — was
        // owner-only findTenantScoped, so a shared NetSuite Restlet config appeared in Settings
        // (editable) but never in this automation source picker. listAccessibleConfigRows applies
        // no status filter; this builder never filtered by isActive either (isActive is surfaced
        // as a response field, not filtered out), so there is nothing to preserve beyond the
        // widened row set — unlike listNsAuthConfigOptions above.
        List rows = SharedConfigAccessSupport
                .listAccessibleConfigRows(ec, SharedConfigAccessSupport.CONFIG_TYPE_NS_RESTLET)
        return rows.collect { item ->
            String label = readString(item, "description") ?: readString(item, "nsRestletConfigId")
            String systemEnumId = inferSystemEnumId(readString(item, "nsRestletConfigId"), label)
            [
                    nsRestletConfigId: readString(item, "nsRestletConfigId"),
                    description      : readString(item, "description"),
                    endpointUrl      : readString(item, "endpointUrl"),
                    httpMethod       : readString(item, "httpMethod") ?: "POST",
                    nsAuthConfigId   : readString(item, "nsAuthConfigId"),
                    sourceConfigId   : readString(item, "nsAuthConfigId"),
                    sourceConfigType : SOURCE_CONFIG_TYPE_NETSUITE_AUTH,
                    isActive         : readString(item, "isActive") ?: "Y",
                    systemEnumId     : systemEnumId,
                    systemLabel      : enumLabel(ec, systemEnumId),
                    label            : label,
            ].findAll { it.value != null }
        } as List<Map<String, Object>>
    }

    static List<Map<String, Object>> listOmsRestSourceRemoteOptions(def ec) {
        String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!activeTenantUserGroupId) return []

        def omsRemote = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.service.message.SystemMessageRemote",
                        "framework remote config — SystemMessageRemote is not tenant-owned")
                .condition("systemMessageRemoteId", HOTWAX_ORDERS_REMOTE_ID)
                .useCache(false)
                .one()
        String endpointLabel = endpointLabelForSystem(OMS_SYSTEM_ENUM_ID, HOTWAX_ORDERS_REMOTE_ID,
                readString(omsRemote, "description") ?: HOTWAX_ORDERS_ENDPOINT_LABEL)

        // Resolved once, outside the row loop: it is the same registry row for every source config.
        boolean omsSupportsExcludeFilters = supportsExcludeFiltersForSystem(ec, OMS_SYSTEM_ENUM_ID)

        try {
            // DAR-BE-005: owned rows plus rows shared to this tenant via ConfigTenantAccess.
            // listAccessibleConfigRows applies no status filter, so isActive/canReadOrders are
            // preserved here — the source picker must not offer inactive or non-order-reading configs.
            List rows = SharedConfigAccessSupport
                    .listAccessibleConfigRows(ec, SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS)
                    .findAll { normalize(readString(it, "isActive")) != "N" && normalizeBool(readField(it, "canReadOrders")) }
            return rows.collect { item ->
                String configId = readString(item, "omsRestSourceConfigId")
                String label = readString(item, "description") ?: configId
                [
                        systemMessageRemoteId: HOTWAX_ORDERS_REMOTE_ID,
                        optionKey            : configId,
                        sourceConfigId       : configId,
                        sourceConfigType     : SOURCE_CONFIG_TYPE_HOTWAX_OMS_REST,
                        omsRestSourceConfigId: configId,
                        sourceConfigLabel    : label,
                        description          : endpointLabel,
                        systemEnumId         : OMS_SYSTEM_ENUM_ID,
                        systemLabel          : enumLabel(ec, OMS_SYSTEM_ENUM_ID),
                        dateFromParameterName : HOTWAX_OMS_WINDOW_START_PARAMETER,
                        dateToParameterName   : HOTWAX_OMS_WINDOW_END_PARAMETER,
                        primaryIdOptions      : HOTWAX_OMS_ORDER_PRIMARY_ID_OPTIONS,
                        fieldOptions          : fieldOptionsForSystem(OMS_SYSTEM_ENUM_ID),
                        supportsExcludeFilters: omsSupportsExcludeFilters,
                        safeMetadataJson     : JsonOutput.toJson([
                                extractServiceName: HOTWAX_OMS_ORDERS_EXTRACT_SERVICE,
                                parameters        : [omsRestSourceConfigId: configId],
                        ]),
                        label                : endpointLabel,
                ].findAll { it.value != null } as Map<String, Object>
            } as List<Map<String, Object>>
        } catch (Exception e) {
            logger.warn("Failed to load source config options", e)
            return []
        }
    }

    static List<Map<String, Object>> listShopifySourceRemoteOptions(def ec) {
        def shopifyRemote = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.service.message.SystemMessageRemote",
                        "framework remote config — SystemMessageRemote is not tenant-owned")
                .condition("systemMessageRemoteId", SHOPIFY_ORDERS_REMOTE_ID)
                .useCache(false)
                .one()
        String endpointLabel = endpointLabelForSystem(SHOPIFY_SYSTEM_ENUM_ID, SHOPIFY_ORDERS_REMOTE_ID,
                readString(shopifyRemote, "description") ?: SHOPIFY_ORDERS_ENDPOINT_LABEL)

        boolean shopifySupportsExcludeFilters = supportsExcludeFiltersForSystem(ec, SHOPIFY_SYSTEM_ENUM_ID)
        return listShopifyAuthConfigOptions(ec).collect { Map<String, Object> sourceConfig ->
            String sourceConfigId = readString(sourceConfig, "sourceConfigId")
            [
                    systemMessageRemoteId: SHOPIFY_ORDERS_REMOTE_ID,
                    optionKey            : sourceConfigId,
                    sourceConfigId       : sourceConfigId,
                    sourceConfigType     : SOURCE_CONFIG_TYPE_SHOPIFY_AUTH,
                    shopifyAuthConfigId  : sourceConfigId,
                    sourceConfigLabel    : readString(sourceConfig, "label"),
                    description          : endpointLabel,
                    sendUrl              : maskUrl(readString(shopifyRemote, "sendUrl")),
                    sendServiceName      : SHOPIFY_GRAPHQL_EXECUTE_SERVICE,
                    systemEnumId         : SHOPIFY_SYSTEM_ENUM_ID,
                    systemLabel          : enumLabel(ec, SHOPIFY_SYSTEM_ENUM_ID),
                    dateFromParameterName: SHOPIFY_WINDOW_START_PARAMETER,
                    dateToParameterName  : SHOPIFY_WINDOW_END_PARAMETER,
                    primaryIdOptions     : primaryIdOptionsForSystem(SHOPIFY_SYSTEM_ENUM_ID),
                    fieldOptions         : fieldOptionsForSystem(SHOPIFY_SYSTEM_ENUM_ID),
                    supportsExcludeFilters: shopifySupportsExcludeFilters,
                    safeMetadataJson     : JsonOutput.toJson([
                            extractServiceName: SHOPIFY_ORDERS_EXTRACT_SERVICE,
                            parameters        : [shopifyAuthConfigId: sourceConfigId],
                    ]),
                    label                : endpointLabel,
            ].findAll { it.value != null } as Map<String, Object>
        } as List<Map<String, Object>>
    }

    static List<Map<String, Object>> listSystemRemoteOptions(def ec) {
        List rows = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.service.message.SystemMessageRemote",
                        "framework remote config list — SystemMessageRemote is not tenant-owned")
                .orderBy("description,systemMessageRemoteId")
                .useCache(false)
                .list() ?: []
        return rows.collectMany { item ->
            String remoteId = readString(item, "systemMessageRemoteId")
            String label = readString(item, "description") ?: remoteId
            String systemEnumId = inferSystemEnumId(remoteId, label)
            if (!systemEnumId) return []
            String sendServiceName = readString(item, "sendServiceName")
            if (!isDirectApiSourceRemote(systemEnumId, sendServiceName)) return []
            if (systemEnumId == SHOPIFY_SYSTEM_ENUM_ID) return []
            String endpointLabel = endpointLabelForSystem(systemEnumId, remoteId, label)
            return [[
                    systemMessageRemoteId: remoteId,
                    description          : readString(item, "description"),
                    sendUrl              : maskUrl(readString(item, "sendUrl")),
                    sendServiceName      : sendServiceName,
                    systemEnumId         : systemEnumId,
                    systemLabel          : enumLabel(ec, systemEnumId),
                    primaryIdOptions     : primaryIdOptionsForSystem(systemEnumId),
                    fieldOptions         : fieldOptionsForSystem(systemEnumId),
                    supportsExcludeFilters: supportsExcludeFiltersForSystem(ec, systemEnumId),
                    label                : endpointLabel,
            ].findAll { it.value != null } as Map<String, Object>]
        } as List<Map<String, Object>>
    }

    protected static String endpointLabelForSystem(String systemEnumId, String remoteId, String fallbackLabel) {
        // Checked before the OMS branch: both connectors share HOTWAX_ORDERS_REMOTE_ID (same API
        // family, same credentials), so the system id is the only thing that separates them here.
        if (normalize(systemEnumId) == OMS_RECON_SYSTEM_ENUM_ID) {
            return HOTWAX_RECON_ORDERS_ENDPOINT_LABEL
        }
        if (normalize(systemEnumId) == OMS_SYSTEM_ENUM_ID &&
                normalize(remoteId) == HOTWAX_ORDERS_REMOTE_ID) {
            return HOTWAX_ORDERS_ENDPOINT_LABEL
        }
        if (normalize(systemEnumId) == SHOPIFY_SYSTEM_ENUM_ID &&
                normalize(remoteId) == SHOPIFY_ORDERS_REMOTE_ID) {
            return SHOPIFY_ORDERS_ENDPOINT_LABEL
        }
        return normalize(fallbackLabel) ?: normalize(remoteId)
    }

    protected static boolean isVirtualApiOrdersRemote(Map<String, Object> source) {
        return isVirtualHotWaxOrdersRemote(source) || isVirtualShopifyOrdersRemote(source)
    }

    protected static boolean isVirtualHotWaxOrdersRemote(Map<String, Object> source) {
        if (normalize(source?.systemEnumId) != OMS_SYSTEM_ENUM_ID) return false
        if (normalize(source?.systemMessageRemoteId) != HOTWAX_ORDERS_REMOTE_ID) return false
        Map<String, Object> metadata = parseJsonMap(source?.safeMetadataJson)
        return normalize(metadata.extractServiceName ?: metadata.serviceName) == HOTWAX_OMS_ORDERS_EXTRACT_SERVICE
    }

    protected static boolean isVirtualShopifyOrdersRemote(Map<String, Object> source) {
        if (normalize(source?.systemEnumId) != SHOPIFY_SYSTEM_ENUM_ID) return false
        if (normalize(source?.systemMessageRemoteId) != SHOPIFY_ORDERS_REMOTE_ID) return false
        Map<String, Object> metadata = parseJsonMap(source?.safeMetadataJson)
        return normalize(metadata.extractServiceName ?: metadata.serviceName) == SHOPIFY_ORDERS_EXTRACT_SERVICE
    }

    protected static boolean isDirectApiSourceRemote(String systemEnumId, String sendServiceName) {
        String normalizedSystemEnumId = normalize(systemEnumId)
        String normalizedServiceName = normalize(sendServiceName)
        if (normalizedSystemEnumId == SHOPIFY_SYSTEM_ENUM_ID) {
            return normalizedServiceName == SHOPIFY_GRAPHQL_EXECUTE_SERVICE
        }
        return false
    }

    protected static List<Map<String, Object>> primaryIdOptionsForSystem(String systemEnumId) {
        switch (normalize(systemEnumId)) {
            // Both OMS orders connectors read the same order documents and project the same six
            // fields, so they offer the same choices; only the endpoint serving them differs.
            case OMS_RECON_SYSTEM_ENUM_ID:
            case OMS_SYSTEM_ENUM_ID:
                return HOTWAX_OMS_ORDER_PRIMARY_ID_OPTIONS
            case SHOPIFY_SYSTEM_ENUM_ID:
                return SHOPIFY_ORDER_PRIMARY_ID_OPTIONS
            default:
                return []
        }
    }

    /**
     * The wider pill list the rules board uses, or null when this system has no curated superset —
     * the board then falls back to {@code primaryIdOptions}, which is exactly today's behavior.
     *
     * OMS (both order connectors) and OMS_RETURNS have one. Shopify deliberately does NOT: its
     * connector declares no {@code keepFieldsBase} and its record shape comes from a per-tenant
     * GraphQL request template, so any wider list would be guessed rather than derived — and it
     * declares no {@code filterParameterName} either, so it can carry no exclusions for the wider
     * list to serve.
     */
    protected static List<Map<String, Object>> fieldOptionsForSystem(String systemEnumId) {
        String normalized = normalize(systemEnumId)
        if (normalized == OMS_SYSTEM_ENUM_ID) return HOTWAX_OMS_ORDER_FIELD_OPTIONS
        // Narrower on purpose — the recon endpoint projects server-side, so the two extra pills
        // would name fields that never arrive. See HOTWAX_OMS_RECON_ORDER_FIELD_OPTIONS.
        if (normalized == OMS_RECON_SYSTEM_ENUM_ID) return HOTWAX_OMS_RECON_ORDER_FIELD_OPTIONS
        if (normalized == OMS_RETURNS_SYSTEM_ENUM_ID) return HOTWAX_OMS_RETURN_FIELD_OPTIONS
        return null
    }

    /**
     * Whether a source on this system can actually carry exclusion filters — i.e. whether its
     * connector declares the {@code filterParameterName} that runSavedRunDiff / AutomationExecutionSupport
     * key exclusion dispatch on. The UI gates the exclusion mark on this so the board never offers a
     * control that would validate, persist and then exclude nothing (design: fail closed and loudly).
     * Registry-driven on purpose: a connector opting in later needs no UI change.
     */
    protected static boolean supportsExcludeFiltersForSystem(def ec, String systemEnumId) {
        try {
            Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, normalize(systemEnumId))
            // Explicit truthiness, NOT `as boolean`: String.asType(Boolean) routes through
            // Boolean.valueOf, which would turn "sourceFilters" into false.
            return normalize(connector?.get("filterParameterName")) ? true : false
        } catch (Exception e) {
            logger.warn("Could not resolve exclusion-filter support for system ${systemEnumId}", e)
            return false
        }
    }

    protected static String inferSystemEnumId(String id, String label) {
        String text = "${id ?: ""} ${label ?: ""}".toUpperCase(Locale.ROOT)
        if (text.contains("SHOPIFY")) return "SHOPIFY"
        if (text.contains("NETSUITE") || text.contains("NET_SUITE")) return "NETSUITE"
        if (text.contains("HOTWAX") || text.contains("OMS")) return "OMS"
        return null
    }

    protected static String sftpServerLabel(def ec, String sftpServerId) {
        if (!sftpServerId) return null
        def server = TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.SFTP_SERVER,
                        "sftp server label lookup for display — access enforced at execution time")
                .condition("sftpServerId", sftpServerId)
                .useCache(true)
                .one()
        if (!server) return sftpServerId
        return readString(server, "description") ?: readString(server, "sftpServerId")
    }

    protected static String enumLabel(def ec, String enumId) {
        def enumValue = FacadeSupport.findEnum(ec, enumId)
        return enumValue ? FacadeSupport.enumLabel(enumValue) : enumId
    }

    protected static String enumCode(def ec, String enumId) {
        return readString(FacadeSupport.findEnum(ec, enumId), "enumCode")
    }

    static String canonicalJsonText(def ec, Object rawValue, String label) {
        String value = normalize(rawValue)
        if (!value) return null
        try {
            Object parsed = new JsonSlurper().parseText(value)
            return JsonOutput.toJson(parsed)
        } catch (Exception e) {
            ec.message.addError("${label} must be valid JSON")
            return null
        }
    }

    protected static Map<String, Object> parseJsonMap(Object rawValue) {
        String value = normalize(rawValue)
        if (!value) return [:]
        try {
            Object parsed = new JsonSlurper().parseText(value)
            return parsed instanceof Map ? new LinkedHashMap<>((Map<String, Object>) parsed) : [:]
        } catch (Exception e) {
            logger.warn("Invalid JSON in automation metadata field", e)
            return [:]
        }
    }

    protected static String maskUrl(String value) {
        return value?.replaceAll(/(?i)(password=)[^&;]+/, "\$1***")
    }

    protected static Timestamp toTimestamp(Object rawValue) {
        if (rawValue == null) return null
        if (rawValue instanceof Timestamp) return (Timestamp) rawValue
        if (rawValue instanceof Date) return new Timestamp(((Date) rawValue).time)
        String value = normalize(rawValue)
        if (!value) return null
        try {
            return Timestamp.from(Instant.parse(value))
        } catch (Exception ignored) {
            return Timestamp.valueOf(value)
        }
    }

    protected static Timestamp nowTimestamp(def ec) {
        return ec?.user?.nowTimestamp ?: new Timestamp(System.currentTimeMillis())
    }

}
