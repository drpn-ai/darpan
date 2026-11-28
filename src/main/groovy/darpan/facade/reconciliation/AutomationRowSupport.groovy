package darpan.facade.reconciliation

import darpan.audit.AuditSettingsSupport
import darpan.common.DarpanEntityConstants
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.source.SourceFilterSupport

import static darpan.common.ValueSupport.fileNameFromPath
import static darpan.common.ValueSupport.readField
import static darpan.common.ValueSupport.readString
import static darpan.facade.reconciliation.AutomationFacadeSupport.INPUT_MODE_SFTP_FILES
import static darpan.facade.reconciliation.AutomationSaveSupport.captureSourceFiltersByFileSide
import static darpan.facade.reconciliation.AutomationFacadeSupport.enumCode
import static darpan.facade.reconciliation.AutomationFacadeSupport.enumLabel
import static darpan.facade.reconciliation.AutomationFacadeSupport.sftpServerLabel

/**
 * The automation read model: the rows list/get/save/pause/resume#Automation and list#AutomationExecutions return, with
 * their labels, permissions, summaries and the execution-history collapse. Reads only; nothing here writes.
 */
class AutomationRowSupport {

    static Map<String, Object> buildAutomationRow(def ec, def automation, boolean includeSources, List sources, List executions,
                                                  Map<String, Object> peakTick = null) {
        String automationId = readString(automation, "automationId")
        List sourceRows = sources ?: []
        List executionRows = executions ?: []
        Map<String, Object> savedRun = resolveSavedRunSummary(ec, automation)
        String inputModeEnumId = readString(automation, "inputModeEnumId")
        String isActive = readString(automation, "isActive") ?: "Y"
        Map<String, Object> chatSpaceSummary = resolveChatSpaceSummary(ec, automation)
        // DAR-BE-199: an audit's automation runs on the audit's schedule, never paused or resumed by itself.
        Map audit = AuditSettingsSupport.auditOfAutomation(ec, readString(automation, "companyUserGroupId"), automationId)
        Map<String, Object> peak = AutomationExecutionSupport.peakNextRun(ec,
                peakTick ?: AutomationExecutionSupport.newPeakTick(ec.user.nowTimestamp), automation)
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
                // DAR-BE-328: where the next run really starts (a peak-period move or hold), and until when peak hours last now.
                effectiveNextStart    : peak.effectiveNextStart,
                peakPeriodName        : peak.peakPeriodName,
                peakHoursUntil        : peak.peakHoursUntil,
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
                audit                 : audit ? [code: audit.auditCode, name: audit.auditName] : null,
                permissions           : buildPermissions(ec, isActive, audit != null),
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

    protected static Map<String, Object> buildPermissions(def ec, String isActive, boolean audits = false) {
        boolean canEdit = TenantAccessSupport.hasActiveTenantWriteAccess(ec)
        boolean canRun = TenantAccessSupport.hasActiveTenantRunAccess(ec)
        boolean active = (isActive ?: "Y") != "N"
        return [
                canViewHistory: true,
                canEdit       : canEdit,
                canDelete     : canEdit,
                canPause      : canEdit && active && !audits,
                canResume     : canEdit && !active && !audits,
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
        // DAR-BE-077: owner re-checked on the row; a tenant condition beside the full PK is dropped by
        // one(), and an automation saved before the fix may still name another tenant's space.
        def chatSpaceRow = chatSpaceId ? TenantScopedFinder.findOneOwnedByTenant(ec, DarpanEntityConstants.TENANT_CHAT_SPACE,
                "chatSpaceId", chatSpaceId, TenantAccessSupport.currentActiveTenantUserGroupId(ec),
                "automation chat-space summary — owner re-checked on the row (PK-only one())") : null
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
}
