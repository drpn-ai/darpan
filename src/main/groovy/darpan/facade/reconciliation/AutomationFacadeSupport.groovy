package darpan.facade.reconciliation

import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.settings.SettingsFacadeSupport
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.SftpAutomationSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.sql.Timestamp
import java.time.Instant

class AutomationFacadeSupport {
    static final String INPUT_MODE_API_RANGE = "AUT_IN_API_RANGE"
    static final String INPUT_MODE_SFTP_FILES = "AUT_IN_SFTP_FILES"
    static final String SOURCE_TYPE_API = "AUT_SRC_API"
    static final String SOURCE_TYPE_SFTP = "AUT_SRC_SFTP"
    static final String FILE_SIDE_1 = "FILE_1"
    static final String FILE_SIDE_2 = "FILE_2"
    static final String OMS_SYSTEM_ENUM_ID = "OMS"
    static final String SHOPIFY_SYSTEM_ENUM_ID = "SHOPIFY"
    static final String NETSUITE_SYSTEM_ENUM_ID = "NETSUITE"
    static final String SOURCE_CONFIG_TYPE_SHOPIFY_AUTH = "SHOPIFY_AUTH"
    static final String SOURCE_CONFIG_TYPE_HOTWAX_OMS_REST = "HOTWAX_OMS_REST"
    static final String SOURCE_CONFIG_TYPE_NETSUITE_AUTH = "NETSUITE_AUTH"
    static final String HOTWAX_ORDERS_REMOTE_ID = "HOTWAX_ORDERS_API"
    static final String HOTWAX_ORDERS_ENDPOINT_LABEL = "Orders API"
    static final String SHOPIFY_ORDERS_REMOTE_ID = "SHOPIFY_REMOTE"
    static final String SHOPIFY_ORDERS_ENDPOINT_LABEL = "Admin GraphQL Orders"
    static final String HOTWAX_OMS_ORDERS_EXTRACT_SERVICE = "reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders"
    static final String SHOPIFY_ORDERS_EXTRACT_SERVICE = "reconciliation.ShopifyOrderExtractionServices.extract#ShopifyOrders"
    static final String SHOPIFY_GRAPHQL_EXECUTE_SERVICE = "facade.ShopifyFacadeServices.execute#ShopifyGraphql"
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

    static Map<String, Object> listAutomations(def ec, Map params = [:]) {
        Map input = params ?: [:]
        int page = Math.max(0, FacadeSupport.normalizeInt(input.pageIndex, 0))
        int size = Math.max(1, Math.min(200, FacadeSupport.normalizeInt(input.pageSize, 20)))
        String search = FacadeSupport.normalize(input.query)?.toLowerCase()

        List<Map<String, Object>> rows = collectAutomationRows(ec)
        if (search) {
            rows = rows.findAll { Map<String, Object> row ->
                [
                        row.automationId,
                        row.automationName,
                        row.description,
                        row.savedRunId,
                        row.savedRunName,
                        row.inputModeLabel,
                        row.sourceSummary,
                        row.scheduleSummary,
                        row.timezone,
                        row.lastExecution?.statusLabel,
                ].any { Object value -> value?.toString()?.toLowerCase()?.contains(search) }
            }
        }

        int totalCount = rows.size()
        int fromIndex = Math.min(page * size, totalCount)
        int toIndex = Math.min(fromIndex + size, totalCount)
        return FacadeSupport.envelope(ec) + [
                automations: rows.subList(fromIndex, toIndex),
                pagination : [
                        pageIndex : page,
                        pageSize  : size,
                        totalCount: totalCount,
                        pageCount : Math.max(1, Math.ceil(totalCount / (double) size) as int),
                ],
        ]
    }

    static Map<String, Object> getAutomation(def ec, Map params = [:]) {
        String automationId = FacadeSupport.normalize(params?.automationId)
        if (!automationId) ec.message.addError("automationId is required")

        Map<String, Object> automation = null
        if (!ec.message.hasError()) {
            def record = findAutomation(ec, automationId)
            if (!record) {
                ec.message.addError("Automation '${automationId}' was not found.")
            } else {
                automation = buildAutomationRow(ec, record, true)
            }
        }

        return FacadeSupport.envelope(ec) + [automation: automation]
    }

    static Map<String, Object> saveAutomation(def ec, Map params = [:]) {
        Map input = params ?: [:]
        String automationId = FacadeSupport.normalize(input.automationId)
        def existing = automationId ? findAutomationRaw(ec, automationId) : null
        if (existing) {
            TenantAccessSupport.requireTenantRecordAccess(ec, existing,
                    "Automation '${automationId}' was not found.",
                    "Automation '${automationId}' is not available in your active tenant.")
        }
        if (!ec.message.hasError()) {
            TenantAccessSupport.requireActiveTenantWriteAccess(ec,
                    "Your active tenant only has view access for automation changes.")
        }

        String automationName = FacadeSupport.normalize(input.automationName)
        String inputModeEnumId = FacadeSupport.normalize(input.inputModeEnumId)
        String scheduleExpr = FacadeSupport.normalize(input.scheduleExpr)
        if (!automationName) ec.message.addError("automationName is required")
        if (!(inputModeEnumId in [INPUT_MODE_API_RANGE, INPUT_MODE_SFTP_FILES])) {
            ec.message.addError("inputModeEnumId must be ${INPUT_MODE_API_RANGE} or ${INPUT_MODE_SFTP_FILES}")
        }
        if (!scheduleExpr) ec.message.addError("scheduleExpr is required")

        Map<String, Object> savedRunResolution = !ec.message.hasError() ? resolveSavedRunForSave(ec, input) : [:]
        String safeConfigJsonValue = null
        List<Map<String, Object>> sourceEntries = []
        if (!ec.message.hasError()) {
            try {
                safeConfigJsonValue = normalizeJsonText(input.safeConfigJson, "safeConfigJson")
                sourceEntries = normalizeSourceEntries(input.sources)
                sourceEntries = applyApiSourceMetadataDefaults(ec, sourceEntries)
            } catch (IllegalArgumentException e) {
                ec.message.addError(e.message)
            }
        }
        if (!ec.message.hasError()) {
            validateSources(ec, inputModeEnumId, sourceEntries, savedRunResolution.savedRun as Map)
        }

        def automationValue = null
        if (!ec.message.hasError()) {
            Timestamp now = nowTimestamp(ec)
            automationValue = existing ?: ec.entity.makeValue("darpan.reconciliation.ReconciliationAutomation")
            if (automationId) automationValue.set("automationId", automationId)
            automationValue.set("automationName", automationName)
            automationValue.set("description", FacadeSupport.normalize(input.description))
            automationValue.set("inputModeEnumId", inputModeEnumId)
            automationValue.set("savedRunId", savedRunResolution.savedRunId)
            automationValue.set("savedRunType", savedRunResolution.savedRunType)
            automationValue.set("reconciliationRunId", FacadeSupport.normalize(input.reconciliationRunId))
            automationValue.set("reconciliationMappingId", savedRunResolution.reconciliationMappingId)
            automationValue.set("ruleSetId", savedRunResolution.ruleSetId)
            automationValue.set("compareScopeId", savedRunResolution.compareScopeId)
            automationValue.set("scheduleExpr", scheduleExpr)
            automationValue.set("nextScheduledFireTime", toTimestamp(input.nextScheduledFireTime) ?:
                    resolveNextFireTime(input, now))
            automationValue.set("lastScheduledFireTime", toTimestamp(input.lastScheduledFireTime))
            automationValue.set("relativeWindowTypeEnumId", FacadeSupport.normalize(input.relativeWindowTypeEnumId))
            automationValue.set("relativeWindowCount", FacadeSupport.normalizeInt(input.relativeWindowCount, null))
            automationValue.set("customWindowStartDate", toTimestamp(input.customWindowStartDate))
            automationValue.set("customWindowEndDate", toTimestamp(input.customWindowEndDate))
            automationValue.set("maxWindowDays", FacadeSupport.normalizeInt(input.maxWindowDays, 28))
            automationValue.set("splitWindowDays", FacadeSupport.normalizeInt(input.splitWindowDays, 28))
            automationValue.set("windowTimeZone", FacadeSupport.normalize(input.windowTimeZone) ?: "UTC")
            automationValue.set("safeConfigJson", safeConfigJsonValue)
            automationValue.set("isActive", FacadeSupport.normalizeBool(input.isActive, true) ? "Y" : "N")
            automationValue.set("lastUpdatedDate", now)
            if (!existing) {
                TenantAccessSupport.assignTenantOwnershipOnCreate(automationValue, ec)
                automationValue.set("createdDate", now)
                if (!automationId) automationValue.setSequencedIdPrimary()
                if (!ec.message.hasError()) automationValue.create()
            } else {
                automationValue.update()
            }
        }

        if (!ec.message.hasError() && automationValue) {
            String savedAutomationId = readString(automationValue, "automationId")
            replaceSources(ec, savedAutomationId, sourceEntries)
            automationValue = findAutomation(ec, savedAutomationId)
            ec.message.addMessage("Saved automation ${readString(automationValue, "automationName")}.")
        }

        return FacadeSupport.envelope(ec) + [
                automation: !ec.message.hasError() && automationValue ? buildAutomationRow(ec, automationValue, true) : null,
        ]
    }

    static Map<String, Object> deleteAutomation(def ec, Map params = [:]) {
        String automationId = FacadeSupport.normalize(params?.automationId)
        if (!automationId) ec.message.addError("automationId is required")
        if (!ec.message.hasError()) {
            TenantAccessSupport.requireActiveTenantWriteAccess(ec,
                    "Your active tenant only has view access for automation changes.")
        }

        int deletedSourceCount = 0
        int deletedExecutionCount = 0
        boolean deleted = false
        if (!ec.message.hasError()) {
            def automation = findAutomation(ec, automationId)
            if (!automation) {
                ec.message.addError("Automation '${automationId}' was not found.")
            } else {
                deletedSourceCount = ec.entity.find("darpan.reconciliation.ReconciliationAutomationSource")
                        .condition("automationId", automationId)
                        .disableAuthz()
                        .useCache(false)
                        .deleteAll()
                deletedExecutionCount = ec.entity.find("darpan.reconciliation.ReconciliationAutomationExecution")
                        .condition("automationId", automationId)
                        .disableAuthz()
                        .useCache(false)
                        .deleteAll()
                automation.delete()
                deleted = true
                ec.message.addMessage("Deleted automation ${automationId}.")
            }
        }

        return FacadeSupport.envelope(ec) + [
                deleted              : deleted,
                deletedAutomationId  : deleted ? automationId : null,
                deletedSourceCount   : deletedSourceCount,
                deletedExecutionCount: deletedExecutionCount,
        ]
    }

    static Map<String, Object> setAutomationActive(def ec, Map params = [:], boolean active) {
        String automationId = FacadeSupport.normalize(params?.automationId)
        if (!automationId) ec.message.addError("automationId is required")
        if (!ec.message.hasError()) {
            TenantAccessSupport.requireActiveTenantWriteAccess(ec,
                    "Your active tenant only has view access for automation changes.")
        }

        def automation = null
        if (!ec.message.hasError()) {
            automation = findAutomation(ec, automationId)
            if (!automation) {
                ec.message.addError("Automation '${automationId}' was not found.")
            } else {
                automation.set("isActive", active ? "Y" : "N")
                automation.set("lastUpdatedDate", nowTimestamp(ec))
                automation.update()
                ec.message.addMessage("${active ? "Resumed" : "Paused"} automation ${automationId}.")
            }
        }

        return FacadeSupport.envelope(ec) + [
                automation: !ec.message.hasError() && automation ? buildAutomationRow(ec, automation, true) : null,
        ]
    }

    static Map<String, Object> runAutomationNow(def ec, Map params = [:]) {
        String automationId = FacadeSupport.normalize(params?.automationId)
        if (!automationId) ec.message.addError("automationId is required")
        if (!ec.message.hasError()) {
            TenantAccessSupport.requireActiveTenantRunAccess(ec,
                    "Your active tenant only has view access for automation runs.")
        }

        Map<String, Object> runResult = null
        Map<String, Object> automationRow = null
        if (!ec.message.hasError()) {
            def automation = findAutomation(ec, automationId)
            if (!automation) {
                ec.message.addError("Automation '${automationId}' was not found.")
            } else {
                Map<String, Object> executeParams = [
                        automationId      : automationId,
                        scheduledFireTime : toTimestamp(params?.scheduledFireTime) ?: nowTimestamp(ec),
                        windowStartDate   : toTimestamp(params?.windowStartDate),
                        windowEndDate     : toTimestamp(params?.windowEndDate),
                        hasHeader         : params?.containsKey("hasHeader") ? params.hasHeader : Boolean.TRUE,
                        outputLocation    : FacadeSupport.normalize(params?.outputLocation),
                        sparkMaster       : FacadeSupport.normalize(params?.sparkMaster),
                        sparkAppName      : FacadeSupport.normalize(params?.sparkAppName) ?: "AutomationFacadeRunNow",
                ].findAll { it.value != null } as Map<String, Object>
                runResult = callExecuteAutomation(ec, executeParams)
                automationRow = buildAutomationRow(ec, findAutomation(ec, automationId), true)
            }
        }

        return FacadeSupport.envelope(ec) + [
                automation: automationRow,
                runResult : runResult,
        ]
    }

    static Map<String, Object> listAutomationExecutions(def ec, Map params = [:]) {
        Map input = params ?: [:]
        int page = Math.max(0, FacadeSupport.normalizeInt(input.pageIndex, 0))
        int size = Math.max(1, Math.min(200, FacadeSupport.normalizeInt(input.pageSize, 20)))
        String automationId = FacadeSupport.normalize(input.automationId)
        if (automationId && !findAutomation(ec, automationId)) {
            ec.message.addError("Automation '${automationId}' was not found.")
        }

        List<Map<String, Object>> rows = []
        if (!ec.message.hasError()) {
            String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
            def finder = ec.entity.find("darpan.reconciliation.ReconciliationAutomationExecution")
                    .orderBy("-createdDate,-scheduledDate,-automationExecutionId")
                    .disableAuthz()
                    .useCache(false)
            if (activeTenantUserGroupId) finder.condition("companyUserGroupId", activeTenantUserGroupId)
            if (automationId) finder.condition("automationId", automationId)
            rows = collapseExecutionHistory(finder.list() ?: [])
                    .collect { execution -> buildExecutionRow(ec, execution) }
        }

        int totalCount = rows.size()
        int fromIndex = Math.min(page * size, totalCount)
        int toIndex = Math.min(fromIndex + size, totalCount)
        return FacadeSupport.envelope(ec) + [
                executions: rows.subList(fromIndex, toIndex),
                pagination: [
                        pageIndex : page,
                        pageSize  : size,
                        totalCount: totalCount,
                        pageCount : Math.max(1, Math.ceil(totalCount / (double) size) as int),
                ],
        ]
    }

    static Map<String, Object> listAutomationSourceOptions(def ec, Map params = [:]) {
        return FacadeSupport.envelope(ec) + [
                inputModes       : listEnumOptions(ec, "AutomationInputMode").findAll { it.enumId in SUPPORTED_INPUT_MODES },
                sourceTypes      : listEnumOptions(ec, "AutomationSourceType"),
                relativeWindows  : listEnumOptions(ec, "AutomationRelWindow"),
                fileTypes        : listEnumOptions(ec, "DarpanFileType"),
                systems          : listEnumOptions(ec, "DarpanSystemSource"),
                savedRuns        : ReconciliationSavedRunSupport.collectSavedRunRows(ec),
                sftpServers      : listSftpServerOptions(ec),
                sourceConfigs    : listSourceConfigOptions(ec),
                nsRestletConfigs : listNsRestletOptions(ec),
                systemRemotes    : listOmsRestSourceRemoteOptions(ec) + listShopifySourceRemoteOptions(ec) + listSystemRemoteOptions(ec),
        ]
    }

    protected static List<Map<String, Object>> collectAutomationRows(def ec) {
        String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!activeTenantUserGroupId) return []
        List automations = ec.entity.find("darpan.reconciliation.ReconciliationAutomation")
                .condition("companyUserGroupId", activeTenantUserGroupId)
                .orderBy("automationName,automationId")
                .disableAuthz()
                .useCache(false)
                .list() ?: []
        return automations.collect { automation -> buildAutomationRow(ec, automation, false) } as List<Map<String, Object>>
    }

    protected static Map<String, Object> buildAutomationRow(def ec, def automation, boolean includeSources) {
        String automationId = readString(automation, "automationId")
        List sources = loadSources(ec, automationId)
        List executions = loadExecutions(ec, automationId)
        Map<String, Object> savedRun = resolveSavedRunSummary(ec, automation)
        String inputModeEnumId = readString(automation, "inputModeEnumId")
        String isActive = readString(automation, "isActive") ?: "Y"
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
                sourceSummary         : buildSourceSummary(ec, inputModeEnumId, sources),
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
                lastExecution         : executions ? buildExecutionRow(ec, executions.first()) : null,
                executionCount        : executions.size(),
                permissions           : buildPermissions(ec, isActive),
                createdDate           : readField(automation, "createdDate"),
                lastUpdatedDate       : readField(automation, "lastUpdatedDate"),
        ].findAll { it.value != null } as Map<String, Object>
        if (includeSources) {
            row.sources = sources.collect { source -> buildSourceRow(ec, source) }
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

    protected static Map<String, Object> buildExecutionRow(def ec, def execution) {
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
            def runResult = ec?.entity?.find("darpan.reconciliation.ReconciliationRunResult")
                    ?.condition("reconciliationRunResultId", reconciliationRunResultId)
                    ?.disableAuthz()
                    ?.useCache(false)
                    ?.one()
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

    protected static String fileNameFromPath(Object rawPath) {
        String normalized = FacadeSupport.normalize(rawPath)
        if (!normalized) return null
        return normalized.tokenize("/\\").last()
    }

    protected static Map<String, Object> buildSourceRow(def ec, def source) {
        String sourceTypeEnumId = readString(source, "sourceTypeEnumId")
        String systemEnumId = readString(source, "systemEnumId")
        return [
                automationId          : readString(source, "automationId"),
                fileSide              : readString(source, "fileSide"),
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
                createdDate           : readField(source, "createdDate"),
                lastUpdatedDate       : readField(source, "lastUpdatedDate"),
        ].findAll { it.value != null } as Map<String, Object>
    }

    protected static Map<String, Object> resolveSavedRunForSave(def ec, Map input) {
        Map savedRunPayload = (input.savedRun instanceof Map ? input.savedRun :
                input.newSavedRun instanceof Map ? input.newSavedRun : null) as Map
        if (!FacadeSupport.normalize(input.savedRunId) && savedRunPayload) {
            String createMode = FacadeSupport.normalize(savedRunPayload.createMode)?.toLowerCase()
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

        String savedRunId = FacadeSupport.normalize(input.savedRunId)
        String savedRunType = FacadeSupport.normalize(input.savedRunType)?.toLowerCase() ?: "ruleset"
        if (!savedRunId) ec.message.addError("savedRunId or savedRun is required")
        if (!(savedRunType in ["ruleset", "mapping"])) ec.message.addError("savedRunType must be ruleset or mapping")
        if (ec.message.hasError()) return [:]

        if (savedRunType == "mapping") {
            def mapping = ec.entity.find("darpan.mapping.ReconciliationMapping")
                    .condition("reconciliationMappingId", savedRunId)
                    .disableAuthz()
                    .useCache(false)
                    .one()
            TenantAccessSupport.requireTenantRecordAccess(ec, mapping,
                    "Saved run '${savedRunId}' was not found.",
                    "Saved run '${savedRunId}' is not available in your active tenant.")
            return [
                    savedRunId             : savedRunId,
                    savedRunType           : "mapping",
                    reconciliationMappingId: savedRunId,
                    ruleSetId              : null,
                    compareScopeId         : null,
                    savedRun               : ReconciliationSavedRunSupport.collectSavedRunRows(ec).find { it.savedRunId == savedRunId },
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
            String expectedSourceType = inputModeEnumId == INPUT_MODE_SFTP_FILES ? SOURCE_TYPE_SFTP : SOURCE_TYPE_API
            if (source.sourceTypeEnumId != expectedSourceType) {
                ec.message.addError("${fileSide} sourceTypeEnumId must be ${expectedSourceType}")
            }
            if (!source.systemEnumId) ec.message.addError("${fileSide} systemEnumId is required")
            validateSourceMatchesSavedRun(ec, source, savedRun)
            if (expectedSourceType == SOURCE_TYPE_SFTP) validateSftpSource(ec, source)
            if (expectedSourceType == SOURCE_TYPE_API) validateApiSource(ec, source)
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
        String expectedSystemEnumId = FacadeSupport.normalize(matchingOption?.enumId)
        if (!expectedSystemEnumId && fileSide == FILE_SIDE_1) expectedSystemEnumId = FacadeSupport.normalize(savedRun.defaultFile1SystemEnumId)
        if (!expectedSystemEnumId && fileSide == FILE_SIDE_2) expectedSystemEnumId = FacadeSupport.normalize(savedRun.defaultFile2SystemEnumId)
        if (expectedSystemEnumId && source.systemEnumId && source.systemEnumId != expectedSystemEnumId) {
            ec.message.addError("${fileSide} systemEnumId ${source.systemEnumId} does not match saved run system ${expectedSystemEnumId}")
        }
    }

    protected static void validateSftpSource(def ec, Map<String, Object> source) {
        if (!source.sftpServerId) ec.message.addError("${source.fileSide} sftpServerId is required")
        if (!source.remotePathTemplate) ec.message.addError("${source.fileSide} remotePathTemplate is required")
        if (ec.message.hasError() || !source.sftpServerId) return

        def server = ec.entity.find("darpan.reconciliation.SftpServer")
                .condition("sftpServerId", source.sftpServerId)
                .disableAuthz()
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
            def remote = ec.entity.find("moqui.service.message.SystemMessageRemote")
                    .condition("systemMessageRemoteId", source.systemMessageRemoteId)
                    .disableAuthz()
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
            def restlet = ec.entity.find("darpan.reconciliation.NsRestletConfig")
                    .condition("nsRestletConfigId", source.nsRestletConfigId)
                    .disableAuthz()
                    .useCache(false)
                    .one()
            TenantAccessSupport.requireTenantRecordAccess(ec, restlet,
                    "NsRestletConfig ${source.nsRestletConfigId} was not found.",
                    "NsRestletConfig ${source.nsRestletConfigId} is not available in your active tenant.")
            if (restlet && (readString(restlet, "isActive") ?: "Y").equalsIgnoreCase("N")) {
                ec.message.addError("NsRestletConfig ${source.nsRestletConfigId} is inactive.")
            }
        }
        validateApiSourceMetadata(ec, source)
    }

    protected static void validateApiSourceMetadata(def ec, Map<String, Object> source) {
        Map<String, Object> metadata = parseJsonMap(source.safeMetadataJson)
        String serviceName = FacadeSupport.normalize(metadata.extractServiceName ?: metadata.serviceName)
        if (!serviceName) {
            ec.message.addError("${source.fileSide} API source requires safeMetadataJson.extractServiceName.")
            return
        }
        if (serviceName == HOTWAX_OMS_ORDERS_EXTRACT_SERVICE) {
            Map parameters = metadata.parameters instanceof Map ? (Map) metadata.parameters : [:]
            if (!FacadeSupport.normalize(parameters.omsRestSourceConfigId)) {
                ec.message.addError("${source.fileSide} OMS API source requires safeMetadataJson.parameters.omsRestSourceConfigId.")
            }
        }
        if (serviceName == SHOPIFY_ORDERS_EXTRACT_SERVICE) {
            Map parameters = metadata.parameters instanceof Map ? (Map) metadata.parameters : [:]
            if (!FacadeSupport.normalize(parameters.shopifyAuthConfigId)) {
                ec.message.addError("${source.fileSide} Shopify API source requires safeMetadataJson.parameters.shopifyAuthConfigId.")
            }
        }
    }

    protected static List<Map<String, Object>> normalizeSourceEntries(Object rawSources) {
        Collection rawList = rawSources instanceof Collection ? (Collection) rawSources :
                rawSources instanceof Map ? ((Map) rawSources).values() : []
        return rawList.collect { Object raw ->
            Map item = raw instanceof Map ? (Map) raw : [:]
            [
                    fileSide                 : FacadeSupport.normalize(item.fileSide)?.toUpperCase(),
                    sourceTypeEnumId         : FacadeSupport.normalize(item.sourceTypeEnumId),
                    systemEnumId             : FacadeSupport.normalize(item.systemEnumId),
                    fileTypeEnumId           : FacadeSupport.normalize(item.fileTypeEnumId),
                    schemaFileName           : FacadeSupport.normalize(item.schemaFileName),
                    recordRootExpression     : FacadeSupport.normalize(item.recordRootExpression),
                    primaryIdExpression      : FacadeSupport.normalize(item.primaryIdExpression),
                    idValueNormalizer        : FacadeSupport.normalize(item.idValueNormalizer),
                    systemMessageRemoteId    : FacadeSupport.normalize(item.systemMessageRemoteId),
                    nsRestletConfigId        : FacadeSupport.normalize(item.nsRestletConfigId),
                    sftpServerId             : FacadeSupport.normalize(item.sftpServerId),
                    remotePathTemplate       : FacadeSupport.normalize(item.remotePathTemplate),
                    fileNamePattern          : FacadeSupport.normalize(item.fileNamePattern),
                    apiRequestTemplateJson   : normalizeJsonText(item.apiRequestTemplateJson, "apiRequestTemplateJson"),
                    apiResponsePathExpression: FacadeSupport.normalize(item.apiResponsePathExpression),
                    dateFromParameterName    : FacadeSupport.normalize(item.dateFromParameterName),
                    dateToParameterName      : FacadeSupport.normalize(item.dateToParameterName),
                    safeMetadataJson         : normalizeJsonText(item.safeMetadataJson, "safeMetadataJson"),
                    optionKey                : FacadeSupport.normalize(item.optionKey),
                    sourceConfigId           : FacadeSupport.normalize(item.sourceConfigId),
                    shopifyAuthConfigId      : FacadeSupport.normalize(item.shopifyAuthConfigId),
                    omsRestSourceConfigId    : FacadeSupport.normalize(item.omsRestSourceConfigId),
            ].findAll { it.value != null } as Map<String, Object>
        } as List<Map<String, Object>>
    }

    protected static List<Map<String, Object>> applyApiSourceMetadataDefaults(def ec, List<Map<String, Object>> sources) {
        return sources.collect { Map<String, Object> source ->
            if (source.sourceTypeEnumId != SOURCE_TYPE_API) return source

            Map<String, Object> enriched = new LinkedHashMap<>(source)
            Map<String, Object> metadata = parseJsonMap(enriched.safeMetadataJson)
            Map<String, Object> parameters = metadata.parameters instanceof Map ?
                    new LinkedHashMap<>((Map<String, Object>) metadata.parameters) : [:]

            if (enriched.systemEnumId == OMS_SYSTEM_ENUM_ID) {
                String configId = FacadeSupport.normalize(parameters.omsRestSourceConfigId) ?:
                        FacadeSupport.normalize(enriched.omsRestSourceConfigId) ?:
                        FacadeSupport.normalize(enriched.sourceConfigId) ?:
                        FacadeSupport.normalize(enriched.optionKey) ?:
                        findSingleActiveOmsRestSourceConfigId(ec)
                if (configId) parameters.omsRestSourceConfigId = configId

                String serviceName = FacadeSupport.normalize(metadata.extractServiceName ?: metadata.serviceName)
                if (!metadata.extractServiceName && configId) {
                    metadata.extractServiceName = serviceName ?: HOTWAX_OMS_ORDERS_EXTRACT_SERVICE
                }
            }

            if (enriched.systemEnumId == SHOPIFY_SYSTEM_ENUM_ID) {
                String configId = FacadeSupport.normalize(parameters.shopifyAuthConfigId) ?:
                        FacadeSupport.normalize(enriched.shopifyAuthConfigId) ?:
                        FacadeSupport.normalize(enriched.sourceConfigId) ?:
                        FacadeSupport.normalize(enriched.optionKey) ?:
                        findSingleActiveShopifyAuthConfigId(ec)
                if (configId) parameters.shopifyAuthConfigId = configId

                String serviceName = FacadeSupport.normalize(metadata.extractServiceName ?: metadata.serviceName)
                if (!metadata.extractServiceName && configId) {
                    metadata.extractServiceName = serviceName ?: SHOPIFY_ORDERS_EXTRACT_SERVICE
                }
            }

            if (parameters) metadata.parameters = parameters

            String resolvedServiceName = FacadeSupport.normalize(metadata.extractServiceName ?: metadata.serviceName)
            switch (resolvedServiceName) {
                case HOTWAX_OMS_ORDERS_EXTRACT_SERVICE:
                    enriched.dateFromParameterName = HOTWAX_OMS_WINDOW_START_PARAMETER
                    enriched.dateToParameterName = HOTWAX_OMS_WINDOW_END_PARAMETER
                    break
                case SHOPIFY_ORDERS_EXTRACT_SERVICE:
                    enriched.dateFromParameterName = SHOPIFY_WINDOW_START_PARAMETER
                    enriched.dateToParameterName = SHOPIFY_WINDOW_END_PARAMETER
                    break
            }
            if (metadata) enriched.safeMetadataJson = JsonOutput.toJson(metadata)
            return enriched
        } as List<Map<String, Object>>
    }

    protected static void replaceSources(def ec, String automationId, List<Map<String, Object>> sources) {
        ec.entity.find("darpan.reconciliation.ReconciliationAutomationSource")
                .condition("automationId", automationId)
                .disableAuthz()
                .useCache(false)
                .deleteAll()
        Timestamp now = nowTimestamp(ec)
        sources.each { Map<String, Object> source ->
            def sourceValue = ec.entity.makeValue("darpan.reconciliation.ReconciliationAutomationSource")
            sourceValue.set("automationId", automationId)
            sourceValue.set("fileSide", source.fileSide)
            sourceValue.set("companyUserGroupId", TenantAccessSupport.currentActiveTenantUserGroupId(ec))
            sourceValue.set("createdByUserId", TenantAccessSupport.currentUserId(ec))
            sourceValue.set("sourceTypeEnumId", source.sourceTypeEnumId)
            sourceValue.set("systemEnumId", source.systemEnumId)
            sourceValue.set("fileTypeEnumId", source.fileTypeEnumId)
            sourceValue.set("schemaFileName", source.schemaFileName)
            sourceValue.set("recordRootExpression", source.recordRootExpression)
            sourceValue.set("primaryIdExpression", source.primaryIdExpression)
            sourceValue.set("idValueNormalizer", source.idValueNormalizer)
            sourceValue.set("systemMessageRemoteId", source.systemMessageRemoteId)
            sourceValue.set("nsRestletConfigId", source.nsRestletConfigId)
            sourceValue.set("sftpServerId", source.sftpServerId)
            sourceValue.set("remotePathTemplate", source.remotePathTemplate)
            sourceValue.set("fileNamePattern", source.fileNamePattern)
            sourceValue.set("apiRequestTemplateJson", source.apiRequestTemplateJson)
            sourceValue.set("apiResponsePathExpression", source.apiResponsePathExpression)
            sourceValue.set("dateFromParameterName", source.dateFromParameterName)
            sourceValue.set("dateToParameterName", source.dateToParameterName)
            sourceValue.set("safeMetadataJson", source.safeMetadataJson)
            sourceValue.set("createdDate", now)
            sourceValue.set("lastUpdatedDate", now)
            sourceValue.create()
        }
    }

    protected static Map<String, Object> callExecuteAutomation(def ec, Map<String, Object> executeParams) {
        return (ec.service.sync()
                .name("reconciliation.ReconciliationAutomationServices.execute#Automation")
                .parameters(executeParams)
                .disableAuthz()
                .call() ?: [:]) as Map<String, Object>
    }

    protected static List loadSources(def ec, String automationId) {
        if (!automationId) return []
        return ec.entity.find("darpan.reconciliation.ReconciliationAutomationSource")
                .condition("automationId", automationId)
                .orderBy("fileSide")
                .disableAuthz()
                .useCache(false)
                .list() ?: []
    }

    protected static List loadExecutions(def ec, String automationId) {
        if (!automationId) return []
        List executions = ec.entity.find("darpan.reconciliation.ReconciliationAutomationExecution")
                .condition("automationId", automationId)
                .orderBy("-completedDate,-startedDate,-scheduledDate,-createdDate,-automationExecutionId")
                .disableAuthz()
                .useCache(false)
                .list() ?: []
        return collapseExecutionHistory(executions)
    }

    protected static List collapseExecutionHistory(List executions) {
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

    protected static def findAutomation(def ec, String automationId) {
        def automation = findAutomationRaw(ec, automationId)
        return TenantAccessSupport.canAccessTenantRecord(ec, automation) ? automation : null
    }

    protected static def findAutomationRaw(def ec, String automationId) {
        if (!automationId) return null
        return ec.entity.find("darpan.reconciliation.ReconciliationAutomation")
                .condition("automationId", automationId)
                .disableAuthz()
                .useCache(false)
                .one()
    }

    protected static Map<String, Object> resolveSavedRunSummary(def ec, def automation) {
        String savedRunId = readString(automation, "savedRunId")
        if (!savedRunId) return null
        return ReconciliationSavedRunSupport.collectSavedRunRows(ec).find { it.savedRunId == savedRunId }
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
        String scheduleExpr = FacadeSupport.normalize(input.scheduleExpr)
        if (!scheduleExpr) return null
        Map automationMap = [
                scheduleExpr          : scheduleExpr,
                windowTimeZone        : FacadeSupport.normalize(input.windowTimeZone) ?: "UTC",
                lastScheduledFireTime : toTimestamp(input.lastScheduledFireTime),
        ]
        return AutomationExecutionSupport.resolveNextScheduledFireTime(automationMap, automationMap.lastScheduledFireTime as Timestamp, now)
    }

    protected static List<Map<String, Object>> listEnumOptions(def ec, String enumTypeId) {
        List options = ec.entity.find("moqui.basic.Enumeration")
                .condition("enumTypeId", enumTypeId)
                .orderBy("sequenceNum,description,enumId")
                .disableAuthz()
                .useCache(true)
                .list() ?: []
        List<Map<String, Object>> mappedOptions = options.collect { item ->
            [
                    enumId     : readString(item, "enumId"),
                    enumCode   : readString(item, "enumCode"),
                    description: readString(item, "description"),
                    sequenceNum: readField(item, "sequenceNum"),
                    label      : FacadeSupport.enumLabel(item),
            ].findAll { it.value != null }
        } as List<Map<String, Object>>
        return SettingsFacadeSupport.deduplicateEnumOptions(enumTypeId, mappedOptions)
    }

    protected static List<Map<String, Object>> listSftpServerOptions(def ec) {
        String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!activeTenantUserGroupId) return []
        List servers = ec.entity.find("darpan.reconciliation.SftpServer")
                .orderBy("description,sftpServerId")
                .disableAuthz()
                .useCache(false)
                .list() ?: []
        return servers.collectMany { server ->
            try {
                SftpAutomationSupport.requireSftpServerAccess(ec, server, [
                        runScopeEnumId      : SftpAutomationSupport.SFTP_SCOPE_TENANT,
                        runTenantUserGroupId: activeTenantUserGroupId,
                        allowAdminSftp      : false,
                ], "automation source option")
                return [[
                        sftpServerId      : readString(server, "sftpServerId"),
                        description       : readString(server, "description"),
                        companyUserGroupId: readString(server, "companyUserGroupId"),
                        scopeEnumId       : readString(server, "scopeEnumId"),
                        host              : readString(server, "host"),
                        port              : readField(server, "port"),
                        username          : readString(server, "username"),
                        label             : sftpServerLabel(ec, readString(server, "sftpServerId")),
                ].findAll { it.value != null } as Map<String, Object>]
            } catch (IllegalArgumentException ignored) {
                return []
            }
        } as List<Map<String, Object>>
    }

    protected static List<Map<String, Object>> listSourceConfigOptions(def ec) {
        return listOmsRestSourceConfigOptions(ec) + listShopifyAuthConfigOptions(ec) + listNsAuthConfigOptions(ec)
    }

    protected static List<Map<String, Object>> listOmsRestSourceConfigOptions(def ec) {
        String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!activeTenantUserGroupId) return []
        try {
            List rows = ec.entity.find("darpan.hotwax.HotWaxOmsRestSourceConfig")
                    .condition("companyUserGroupId", activeTenantUserGroupId)
                    .condition("isActive", "Y")
                    .condition("canReadOrders", "Y")
                    .orderBy("description,omsRestSourceConfigId")
                    .disableAuthz()
                    .useCache(false)
                    .list() ?: []
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
        } catch (Throwable ignored) {
            return []
        }
    }

    protected static List<Map<String, Object>> listShopifyAuthConfigOptions(def ec) {
        String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!activeTenantUserGroupId) return []
        try {
            List rows = ec.entity.find("darpan.shopify.ShopifyAuthConfig")
                    .condition("companyUserGroupId", activeTenantUserGroupId)
                    .condition("isActive", "Y")
                    .condition("canReadOrders", "Y")
                    .orderBy("description,shopifyAuthConfigId")
                    .disableAuthz()
                    .useCache(false)
                    .list() ?: []
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
        } catch (Throwable ignored) {
            return []
        }
    }

    protected static List<Map<String, Object>> listNsAuthConfigOptions(def ec) {
        String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!activeTenantUserGroupId) return []
        List rows = ec.entity.find("darpan.reconciliation.NsAuthConfig")
                .condition("companyUserGroupId", activeTenantUserGroupId)
                .condition("isActive", "Y")
                .orderBy("description,nsAuthConfigId")
                .disableAuthz()
                .useCache(false)
                .list() ?: []
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

    protected static List<Map<String, Object>> listNsRestletOptions(def ec) {
        String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!activeTenantUserGroupId) return []
        List rows = ec.entity.find("darpan.reconciliation.NsRestletConfig")
                .condition("companyUserGroupId", activeTenantUserGroupId)
                .orderBy("description,nsRestletConfigId")
                .disableAuthz()
                .useCache(false)
                .list() ?: []
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

    protected static List<Map<String, Object>> listOmsRestSourceRemoteOptions(def ec) {
        String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!activeTenantUserGroupId) return []

        def omsRemote = ec.entity.find("moqui.service.message.SystemMessageRemote")
                .condition("systemMessageRemoteId", HOTWAX_ORDERS_REMOTE_ID)
                .disableAuthz()
                .useCache(false)
                .one()
        String endpointLabel = endpointLabelForSystem(OMS_SYSTEM_ENUM_ID, HOTWAX_ORDERS_REMOTE_ID,
                readString(omsRemote, "description") ?: HOTWAX_ORDERS_ENDPOINT_LABEL)

        try {
            List rows = ec.entity.find("darpan.hotwax.HotWaxOmsRestSourceConfig")
                    .condition("companyUserGroupId", activeTenantUserGroupId)
                    .condition("isActive", "Y")
                    .condition("canReadOrders", "Y")
                    .orderBy("description,omsRestSourceConfigId")
                    .disableAuthz()
                    .useCache(false)
                    .list() ?: []
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
                        safeMetadataJson     : JsonOutput.toJson([
                                extractServiceName: HOTWAX_OMS_ORDERS_EXTRACT_SERVICE,
                                parameters        : [omsRestSourceConfigId: configId],
                        ]),
                        label                : endpointLabel,
                ].findAll { it.value != null } as Map<String, Object>
            } as List<Map<String, Object>>
        } catch (Throwable ignored) {
            return []
        }
    }

    protected static List<Map<String, Object>> listShopifySourceRemoteOptions(def ec) {
        def shopifyRemote = ec.entity.find("moqui.service.message.SystemMessageRemote")
                .condition("systemMessageRemoteId", SHOPIFY_ORDERS_REMOTE_ID)
                .disableAuthz()
                .useCache(false)
                .one()
        String endpointLabel = endpointLabelForSystem(SHOPIFY_SYSTEM_ENUM_ID, SHOPIFY_ORDERS_REMOTE_ID,
                readString(shopifyRemote, "description") ?: SHOPIFY_ORDERS_ENDPOINT_LABEL)

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
                    safeMetadataJson     : JsonOutput.toJson([
                            extractServiceName: SHOPIFY_ORDERS_EXTRACT_SERVICE,
                            parameters        : [shopifyAuthConfigId: sourceConfigId],
                    ]),
                    label                : endpointLabel,
            ].findAll { it.value != null } as Map<String, Object>
        } as List<Map<String, Object>>
    }

    protected static List<Map<String, Object>> listSystemRemoteOptions(def ec) {
        List rows = ec.entity.find("moqui.service.message.SystemMessageRemote")
                .orderBy("description,systemMessageRemoteId")
                .disableAuthz()
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
                    label                : endpointLabel,
            ].findAll { it.value != null } as Map<String, Object>]
        } as List<Map<String, Object>>
    }

    protected static String endpointLabelForSystem(String systemEnumId, String remoteId, String fallbackLabel) {
        if (FacadeSupport.normalize(systemEnumId) == OMS_SYSTEM_ENUM_ID &&
                FacadeSupport.normalize(remoteId) == HOTWAX_ORDERS_REMOTE_ID) {
            return HOTWAX_ORDERS_ENDPOINT_LABEL
        }
        if (FacadeSupport.normalize(systemEnumId) == SHOPIFY_SYSTEM_ENUM_ID &&
                FacadeSupport.normalize(remoteId) == SHOPIFY_ORDERS_REMOTE_ID) {
            return SHOPIFY_ORDERS_ENDPOINT_LABEL
        }
        return FacadeSupport.normalize(fallbackLabel) ?: FacadeSupport.normalize(remoteId)
    }

    protected static boolean isVirtualApiOrdersRemote(Map<String, Object> source) {
        return isVirtualHotWaxOrdersRemote(source) || isVirtualShopifyOrdersRemote(source)
    }

    protected static boolean isVirtualHotWaxOrdersRemote(Map<String, Object> source) {
        if (FacadeSupport.normalize(source?.systemEnumId) != OMS_SYSTEM_ENUM_ID) return false
        if (FacadeSupport.normalize(source?.systemMessageRemoteId) != HOTWAX_ORDERS_REMOTE_ID) return false
        Map<String, Object> metadata = parseJsonMap(source?.safeMetadataJson)
        return FacadeSupport.normalize(metadata.extractServiceName ?: metadata.serviceName) == HOTWAX_OMS_ORDERS_EXTRACT_SERVICE
    }

    protected static boolean isVirtualShopifyOrdersRemote(Map<String, Object> source) {
        if (FacadeSupport.normalize(source?.systemEnumId) != SHOPIFY_SYSTEM_ENUM_ID) return false
        if (FacadeSupport.normalize(source?.systemMessageRemoteId) != SHOPIFY_ORDERS_REMOTE_ID) return false
        Map<String, Object> metadata = parseJsonMap(source?.safeMetadataJson)
        return FacadeSupport.normalize(metadata.extractServiceName ?: metadata.serviceName) == SHOPIFY_ORDERS_EXTRACT_SERVICE
    }

    protected static boolean isDirectApiSourceRemote(String systemEnumId, String sendServiceName) {
        String normalizedSystemEnumId = FacadeSupport.normalize(systemEnumId)
        String normalizedServiceName = FacadeSupport.normalize(sendServiceName)
        if (normalizedSystemEnumId == SHOPIFY_SYSTEM_ENUM_ID) {
            return normalizedServiceName == SHOPIFY_GRAPHQL_EXECUTE_SERVICE
        }
        return false
    }

    protected static List<Map<String, Object>> primaryIdOptionsForSystem(String systemEnumId) {
        switch (FacadeSupport.normalize(systemEnumId)) {
            case OMS_SYSTEM_ENUM_ID:
                return HOTWAX_OMS_ORDER_PRIMARY_ID_OPTIONS
            case SHOPIFY_SYSTEM_ENUM_ID:
                return SHOPIFY_ORDER_PRIMARY_ID_OPTIONS
            default:
                return []
        }
    }

    protected static String findSystemRemoteIdForSystem(def ec, String systemEnumId) {
        return readString(findSystemRemoteForSystem(ec, systemEnumId), "systemMessageRemoteId")
    }

    protected static def findSystemRemoteForSystem(def ec, String systemEnumId) {
        List rows = ec.entity.find("moqui.service.message.SystemMessageRemote")
                .orderBy("description,systemMessageRemoteId")
                .disableAuthz()
                .useCache(false)
                .list() ?: []
        String normalizedSystemEnumId = FacadeSupport.normalize(systemEnumId)
        if (normalizedSystemEnumId == OMS_SYSTEM_ENUM_ID) {
            def hotwaxOrdersRemote = rows.find { item ->
                readString(item, "systemMessageRemoteId") == HOTWAX_ORDERS_REMOTE_ID
            }
            if (hotwaxOrdersRemote) return hotwaxOrdersRemote
        }
        def match = rows.find { item ->
            inferSystemEnumId(readString(item, "systemMessageRemoteId"), readString(item, "description")) == normalizedSystemEnumId
        }
        return match
    }

    protected static String findSingleActiveOmsRestSourceConfigId(def ec) {
        String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!activeTenantUserGroupId) return null
        try {
            List rows = ec.entity.find("darpan.hotwax.HotWaxOmsRestSourceConfig")
                    .condition("companyUserGroupId", activeTenantUserGroupId)
                    .condition("isActive", "Y")
                    .condition("canReadOrders", "Y")
                    .orderBy("description,omsRestSourceConfigId")
                    .disableAuthz()
                    .useCache(false)
                    .list() ?: []
            return rows.size() == 1 ? readString(rows.first(), "omsRestSourceConfigId") : null
        } catch (Throwable ignored) {
            return null
        }
    }

    protected static String findSingleActiveShopifyAuthConfigId(def ec) {
        String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!activeTenantUserGroupId) return null
        try {
            List rows = ec.entity.find("darpan.shopify.ShopifyAuthConfig")
                    .condition("companyUserGroupId", activeTenantUserGroupId)
                    .condition("isActive", "Y")
                    .condition("canReadOrders", "Y")
                    .orderBy("description,shopifyAuthConfigId")
                    .disableAuthz()
                    .useCache(false)
                    .list() ?: []
            return rows.size() == 1 ? readString(rows.first(), "shopifyAuthConfigId") : null
        } catch (Throwable ignored) {
            return null
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
        def server = ec.entity.find("darpan.reconciliation.SftpServer")
                .condition("sftpServerId", sftpServerId)
                .disableAuthz()
                .useCache(false)
                .one()
        if (!server) return sftpServerId
        return readString(server, "description") ?: readString(server, "sftpServerId")
    }

    protected static String enumLabel(def ec, String enumId) {
        def enumValue = findEnum(ec, enumId)
        return enumValue ? FacadeSupport.enumLabel(enumValue) : enumId
    }

    protected static String enumCode(def ec, String enumId) {
        return readString(findEnum(ec, enumId), "enumCode")
    }

    protected static def findEnum(def ec, String enumId) {
        if (!enumId) return null
        return ec.entity.find("moqui.basic.Enumeration")
                .condition("enumId", enumId)
                .disableAuthz()
                .useCache(true)
                .one()
    }

    protected static String normalizeJsonText(Object rawValue, String label) {
        String value = FacadeSupport.normalize(rawValue)
        if (!value) return null
        try {
            Object parsed = new JsonSlurper().parseText(value)
            return JsonOutput.toJson(parsed)
        } catch (Exception e) {
            throw new IllegalArgumentException("${label} must be valid JSON")
        }
    }

    protected static Map<String, Object> parseJsonMap(Object rawValue) {
        String value = FacadeSupport.normalize(rawValue)
        if (!value) return [:]
        Object parsed = new JsonSlurper().parseText(value)
        return parsed instanceof Map ? new LinkedHashMap<>((Map<String, Object>) parsed) : [:]
    }

    protected static String maskUrl(String value) {
        return value?.replaceAll(/(?i)(password=)[^&;]+/, "\$1***")
    }

    protected static Timestamp toTimestamp(Object rawValue) {
        if (rawValue == null) return null
        if (rawValue instanceof Timestamp) return (Timestamp) rawValue
        if (rawValue instanceof Date) return new Timestamp(((Date) rawValue).time)
        String value = FacadeSupport.normalize(rawValue)
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

    protected static String readString(def record, String fieldName) {
        return FacadeSupport.normalize(readField(record, fieldName))
    }

    protected static Object readField(def record, String fieldName) {
        if (record == null || !fieldName) return null
        if (record instanceof Map) return record[fieldName]
        if (record.metaClass.respondsTo(record, "get", String)) return record.get(fieldName)
        return record."${fieldName}"
    }
}
