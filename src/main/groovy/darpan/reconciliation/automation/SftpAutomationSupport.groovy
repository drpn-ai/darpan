package darpan.reconciliation.automation

import darpan.common.DarpanEntityConstants
import darpan.facade.common.DataManagerSupport
import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.notification.TenantNotificationSupport

import org.moqui.sftp.SftpClient

import static darpan.common.ValueSupport.fileNameFromPath
import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeInt
import static darpan.common.ValueSupport.normalizeLong
import static darpan.common.ValueSupport.readField
import static darpan.reconciliation.automation.AutomationRuntimeSupport.currentUserId
import static darpan.reconciliation.automation.AutomationRuntimeSupport.loadAutomation
import static darpan.reconciliation.automation.AutomationRuntimeSupport.loadAutomationSources
import static darpan.reconciliation.automation.AutomationRuntimeSupport.normalizeDataManagerPath
import static darpan.reconciliation.automation.AutomationRuntimeSupport.nowTimestamp
import static darpan.reconciliation.automation.AutomationRuntimeSupport.requireNormalized
import static darpan.reconciliation.automation.AutomationRuntimeSupport.runInTransaction
import static darpan.reconciliation.automation.AutomationRuntimeSupport.safeMetadataJson
import static darpan.reconciliation.automation.AutomationRuntimeSupport.sanitizeErrorDetail
import static darpan.reconciliation.automation.AutomationRuntimeSupport.sanitizeErrorMessage
import static darpan.reconciliation.automation.AutomationRuntimeSupport.truncate
import static darpan.reconciliation.automation.AutomationRuntimeSupport.updateAutomationExecution

class SftpAutomationSupport {
    static final String AUTOMATION_INPUT_SFTP_FILES = "AUT_IN_SFTP_FILES"
    static final String AUTOMATION_SOURCE_SFTP = "AUT_SRC_SFTP"
    static final String AUTOMATION_STATUS_RUNNING = "AUT_STAT_RUNNING"
    static final String AUTOMATION_STATUS_COMPLETED = "AUT_STAT_SUCCESS"
    static final String AUTOMATION_STATUS_FAILED = "AUT_STAT_FAILED"
    static final String AUTOMATION_STATUS_NO_DATA = "AUT_STAT_NO_DATA"

    static final String FILE_SIDE_1 = "FILE_1"
    static final String FILE_SIDE_2 = "FILE_2"

    static final String SFTP_SCOPE_TENANT = "DARPAN_SFTP_TENANT"
    static final String SFTP_SCOPE_TENANT_GROUP = "DARPAN_SFTP_TENANT_GROUP"
    static final String SFTP_SCOPE_ADMIN = "DARPAN_SFTP_ADMIN"
    static final int DEFAULT_POLL_INTERVAL_MINUTES = 10
    static final int DEFAULT_POLL_TIMEOUT_MINUTES = 60

    private static final Closure DEFAULT_CLIENT_FACTORY = { String host, String user, Integer port ->
        new SftpClient(host, user, port)
    }
    private static final Closure DEFAULT_RETRY_SLEEPER = { long sleepMillis ->
        if (sleepMillis > 0L) Thread.sleep(sleepMillis)
    }

    private static Closure clientFactory = DEFAULT_CLIENT_FACTORY
    private static Closure retrySleeper = DEFAULT_RETRY_SLEEPER

    static Object createClient(String host, String user, Integer port) {
        return clientFactory.call(host, user, port)
    }

    static void setClientFactory(Closure factory) {
        clientFactory = factory ?: DEFAULT_CLIENT_FACTORY
    }

    static void resetClientFactory() {
        clientFactory = DEFAULT_CLIENT_FACTORY
    }

    static void setRetrySleeper(Closure sleeper) {
        retrySleeper = sleeper ?: DEFAULT_RETRY_SLEEPER
    }

    static void resetRetrySleeper() {
        retrySleeper = DEFAULT_RETRY_SLEEPER
    }

    static String resolveDefaultOutputLocation(def ec, Object runId, Object timestamp) {
        return DataManagerSupport.resolveReconciliationRunLocation(ec, runId, timestamp)
    }

    static Map<String, Object> runSftpFileAutomation(def ec, Map params) {
        Map input = params ?: [:]
        String automationId = requireNormalized(input.automationId, "automationId is required")
        def automation = loadAutomation(ec, automationId)
        String inputModeEnumId = normalize(readField(automation, "inputModeEnumId"))
        if (inputModeEnumId != AUTOMATION_INPUT_SFTP_FILES) {
            throw new IllegalArgumentException("Automation ${automationId} must use ${AUTOMATION_INPUT_SFTP_FILES} input mode")
        }

        def execution = createAutomationExecution(ec, automation, input)
        String automationExecutionId = normalize(readField(execution, "automationExecutionId"))

        try {
            Map<String, Object> sourcesBySide = loadAutomationSources(ec, automationId)
            def file1Source = requireSftpSource(automation, sourcesBySide[FILE_SIDE_1], FILE_SIDE_1)
            def file2Source = requireSftpSource(automation, sourcesBySide[FILE_SIDE_2], FILE_SIDE_2)
            Map<String, Object> pollParams = buildSftpPollParameters(automation, file1Source, file2Source, input)

            boolean hadMessageErrors = hasMessageErrors(ec)
            Map<String, Object> pollResult = pollSftpUntilAvailable(ec, pollParams)
            boolean dataAvailable = pollResult.dataAvailable == true
            boolean serviceReportedError = !hadMessageErrors && hasMessageErrors(ec)
            String statusEnumId = serviceReportedError ? AUTOMATION_STATUS_FAILED :
                    (dataAvailable ? AUTOMATION_STATUS_COMPLETED : AUTOMATION_STATUS_NO_DATA)

            String resultDataManagerPath = statusEnumId == AUTOMATION_STATUS_COMPLETED ?
                    normalizeDataManagerPath(ec, pollResult.diffLocation ?: pollResult.diffFileName) : null
            String reconciliationRunResultId = statusEnumId == AUTOMATION_STATUS_COMPLETED ?
                    persistAutomationRunResult(ec, automation, pollResult, resultDataManagerPath) : null

            def completedTimestamp = nowTimestamp(ec)
            Map<String, Object> updateFields = [
                    statusEnumId              : statusEnumId,
                    completedDate             : completedTimestamp,
                    file1Name                 : normalize(pollResult.file1SelectedName),
                    file2Name                 : normalize(pollResult.file2SelectedName),
                    resultFileName            : normalize(pollResult.diffFileName) ?: fileNameFromPath(resultDataManagerPath),
                    resultDataManagerPath     : resultDataManagerPath,
                    reconciliationRunResultId : reconciliationRunResultId,
                    differenceCount           : normalizeInt(pollResult.differenceCount),
                    onlyInFile1Count          : normalizeInt(pollResult.onlyInFile1Count),
                    onlyInFile2Count          : normalizeInt(pollResult.onlyInFile2Count),
                    safeMetadataJson          : safeMetadataJson([
                            mode                 : "SFTP_FILES",
                            dataAvailable        : dataAvailable,
                            statusMessage        : pollResult.statusMessage,
                            sftpRunScopeEnumId   : pollParams.sftpRunScopeEnumId,
                            runTenantUserGroupId : pollParams.runTenantUserGroupId,
                            archiveSubdir        : pollParams.archiveSubdir,
                            file1Source          : pollResult.file1Source,
                            file2Source          : pollResult.file2Source,
                            file1StagedLocation  : pollResult.file1StagedLocation,
                            file2StagedLocation  : pollResult.file2StagedLocation,
                            file1RemotePath      : pollParams.file1RemotePath,
                            file2RemotePath      : pollParams.file2RemotePath,
                            file1NamePattern     : readField(file1Source, "fileNamePattern"),
                            file2NamePattern     : readField(file2Source, "fileNamePattern"),
                            pollIntervalMinutes  : pollParams.pollIntervalMinutes,
                            pollTimeoutMinutes   : pollParams.pollTimeoutMinutes,
                            pollAttemptCount     : pollResult.pollAttemptCount,
                            validationErrors     : pollResult.validationErrors ?: [],
                            processingWarnings   : pollResult.processingWarnings ?: [],
                    ]),
                    lastUpdatedDate           : completedTimestamp,
            ]
            if (statusEnumId == AUTOMATION_STATUS_FAILED) {
                updateFields.errorMessage = normalize(pollResult.statusMessage) ?: "SFTP automation run failed"
            }
            updateAutomationExecution(ec, execution, updateFields)
            if (statusEnumId == AUTOMATION_STATUS_COMPLETED) {
                TenantNotificationSupport.notifyRunCompleted(ec, [
                        reconciliationRunResultId: reconciliationRunResultId,
                        runName                  : normalize(readField(automation, "automationName")),
                        savedRunId               : normalize(readField(automation, "savedRunId")),
                        reconciliationRunId      : normalize(readField(automation, "reconciliationRunId")),
                        companyUserGroupId       : normalize(readField(automation, "companyUserGroupId")),
                        resultDataManagerPath    : resultDataManagerPath,
                        file1SystemEnumId        : normalize(readField(file1Source, "systemEnumId")),
                        file2SystemEnumId        : normalize(readField(file2Source, "systemEnumId")),
                        file1SystemLabel         : normalize(pollResult.file1Label),
                        file2SystemLabel         : normalize(pollResult.file2Label),
                        differenceCount          : updateFields.differenceCount,
                        onlyInFile1Count         : updateFields.onlyInFile1Count,
                        onlyInFile2Count         : updateFields.onlyInFile2Count,
                ])
            }

            Map<String, Object> result = [:]
            result.putAll(pollResult)
            result.automationExecutionId = automationExecutionId
            result.statusEnumId = statusEnumId
            result.reconciliationRunResultId = reconciliationRunResultId
            result.resultDataManagerPath = resultDataManagerPath
            return result
        } catch (Throwable t) {
            try {
                def completedTimestamp = nowTimestamp(ec)
                updateAutomationExecution(ec, execution, [
                        statusEnumId    : AUTOMATION_STATUS_FAILED,
                        completedDate   : completedTimestamp,
                        errorMessage    : truncate(sanitizeErrorMessage(t), 3900),
                        errorDetail     : truncate(sanitizeErrorDetail(t), 12000),
                        safeMetadataJson: safeMetadataJson([
                                mode        : "SFTP_FILES",
                                errorMessage: sanitizeErrorMessage(t),
                        ]),
                        lastUpdatedDate : completedTimestamp,
                ])
            } catch (Throwable ignored) {
            }
            throw t
        }
    }

    static Map<String, Object> resolveRunScope(def ec, Object rawRunScopeEnumId, Object rawRunTenantUserGroupId,
            Object rawAllowAdminSftp) {
        String runScopeEnumId = normalize(rawRunScopeEnumId) ?: SFTP_SCOPE_TENANT
        if (![SFTP_SCOPE_TENANT, SFTP_SCOPE_ADMIN].contains(runScopeEnumId)) {
            throw new IllegalArgumentException("sftpRunScopeEnumId must be ${SFTP_SCOPE_TENANT} or ${SFTP_SCOPE_ADMIN}")
        }

        String runTenantUserGroupId = normalize(rawRunTenantUserGroupId)
        if (!runTenantUserGroupId && runScopeEnumId == SFTP_SCOPE_TENANT) {
            runTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        }

        if (runScopeEnumId == SFTP_SCOPE_TENANT && !runTenantUserGroupId) {
            throw new IllegalArgumentException("runTenantUserGroupId is required for tenant-scoped SFTP automation")
        }

        boolean allowAdminSftp = rawAllowAdminSftp == Boolean.TRUE ||
                ["Y", "true", "TRUE", "yes", "YES"].contains(normalize(rawAllowAdminSftp))
        return [
                runScopeEnumId      : runScopeEnumId,
                runTenantUserGroupId: runTenantUserGroupId,
                allowAdminSftp      : allowAdminSftp,
        ]
    }

    static Object loadSftpServerForRun(def ec, Object rawSftpServerId, Map<String, Object> runScope, String label) {
        String sftpServerId = normalize(rawSftpServerId)
        if (!sftpServerId) throw new IllegalArgumentException("SFTP server is required for ${label}")

        def server = ec?.entity?.find(DarpanEntityConstants.SFTP_SERVER)
                ?.condition("sftpServerId", sftpServerId)
                ?.disableAuthz()
                ?.useCache(true)
                ?.one()
        if (!server) throw new IllegalArgumentException("SFTP Server ${sftpServerId} not found for ${label}")

        requireSftpServerAccess(ec, server, runScope, label)
        return server
    }

    static void requireSftpServerAccess(def ec, def server, Map<String, Object> runScope, String label) {
        String sftpServerId = normalize(readField(server, "sftpServerId"))
        String serverScopeEnumId = resolveServerScopeEnumId(server)
        String runScopeEnumId = normalize(runScope?.runScopeEnumId) ?: SFTP_SCOPE_TENANT
        String runTenantUserGroupId = normalize(runScope?.runTenantUserGroupId)

        if (runScopeEnumId == SFTP_SCOPE_ADMIN) {
            if (serverScopeEnumId != SFTP_SCOPE_ADMIN) {
                throw new IllegalArgumentException("SFTP Server ${sftpServerId} is not an admin SFTP server for ${label}")
            }
            if (runScope?.allowAdminSftp != true) {
                throw new IllegalArgumentException("allowAdminSftp must be true to use admin SFTP Server ${sftpServerId} for ${label}")
            }
            return
        }

        if (serverScopeEnumId == SFTP_SCOPE_ADMIN) {
            throw new IllegalArgumentException("Admin SFTP Server ${sftpServerId} is not available to tenant-scoped automation for ${label}")
        }

        if (!runTenantUserGroupId) {
            throw new IllegalArgumentException("runTenantUserGroupId is required to use SFTP Server ${sftpServerId} for ${label}")
        }

        if (serverScopeEnumId == SFTP_SCOPE_TENANT) {
            String ownerTenantUserGroupId = normalize(readField(server, "companyUserGroupId"))
            if (ownerTenantUserGroupId == runTenantUserGroupId) return
            throw new IllegalArgumentException("SFTP Server ${sftpServerId} is not available to tenant ${runTenantUserGroupId} for ${label}")
        }

        if (serverScopeEnumId == SFTP_SCOPE_TENANT_GROUP && tenantGroupServerAllowsTenant(ec, sftpServerId, runTenantUserGroupId)) {
            return
        }

        throw new IllegalArgumentException("SFTP Server ${sftpServerId} is not available to tenant ${runTenantUserGroupId} for ${label}")
    }

    static String resolveServerScopeEnumId(def server) {
        String explicitScope = normalize(readField(server, "scopeEnumId"))
        if (explicitScope) return explicitScope
        return normalize(readField(server, "companyUserGroupId")) ? SFTP_SCOPE_TENANT : SFTP_SCOPE_ADMIN
    }

    static String remotePathForRuntimeLocation(Object location) {
        String normalized = location?.toString()?.trim()
        if (!normalized?.startsWith("runtime://")) return null

        String path = normalized.substring("runtime://".length())
                .replaceAll(/\\+/, "/")
                .replaceAll(/\/+$/, "")
        if (!path) return null
        return path.startsWith("/") ? path : "/${path}"
    }

    protected static boolean tenantGroupServerAllowsTenant(def ec, String sftpServerId, String tenantUserGroupId) {
        if (!sftpServerId || !tenantUserGroupId) return false
        def access = ec?.entity?.find("darpan.reconciliation.SftpServerTenantAccess")
                ?.condition("sftpServerId", sftpServerId)
                ?.condition("tenantUserGroupId", tenantUserGroupId)
                ?.disableAuthz()
                ?.useCache(true)
                ?.one()
        return access != null
    }

    protected static def requireSftpSource(def automation, def source, String fileSide) {
        String automationId = normalize(readField(automation, "automationId"))
        if (!source) throw new IllegalArgumentException("Automation ${automationId} is missing ${fileSide} source")

        String automationTenant = normalize(readField(automation, "companyUserGroupId"))
        String sourceTenant = normalize(readField(source, "companyUserGroupId"))
        if (automationTenant && sourceTenant && automationTenant != sourceTenant) {
            throw new IllegalArgumentException("Automation ${automationId} ${fileSide} source belongs to tenant ${sourceTenant}, not ${automationTenant}")
        }

        String sourceTypeEnumId = normalize(readField(source, "sourceTypeEnumId"))
        if (sourceTypeEnumId != AUTOMATION_SOURCE_SFTP) {
            throw new IllegalArgumentException("Automation ${automationId} ${fileSide} source must use ${AUTOMATION_SOURCE_SFTP}")
        }

        if (!normalize(readField(source, "sftpServerId"))) {
            throw new IllegalArgumentException("Automation ${automationId} ${fileSide} source requires sftpServerId")
        }
        if (!normalize(readField(source, "remotePathTemplate"))) {
            throw new IllegalArgumentException("Automation ${automationId} ${fileSide} source requires remotePathTemplate")
        }
        return source
    }

    protected static Map<String, Object> buildSftpPollParameters(def automation, def file1Source, def file2Source, Map input) {
        String savedRunType = normalize(readField(automation, "savedRunType"))?.toLowerCase(Locale.ROOT) ?: "ruleset"
        String savedRunId = normalize(readField(automation, "savedRunId"))
        String ruleSetId = normalize(readField(automation, "ruleSetId"))
        String mappingId = normalize(readField(automation, "reconciliationMappingId"))
        if (!ruleSetId && savedRunType == "ruleset") ruleSetId = savedRunId
        if (!mappingId && savedRunType == "mapping") mappingId = savedRunId

        String automationId = normalize(readField(automation, "automationId"))
        String runTenantUserGroupId = normalize(input.runTenantUserGroupId) ?: normalize(readField(automation, "companyUserGroupId"))
        String sparkAppName = normalize(input.sparkAppName) ?: "SftpFileAutomation-${automationId}"

        return [
                ruleSetId                : ruleSetId,
                compareScopeId           : normalize(readField(automation, "compareScopeId")),
                reconciliationMappingId  : mappingId,
                reconciliationRunId      : normalize(readField(automation, "reconciliationRunId")),
                file1SystemEnumId        : normalize(readField(file1Source, "systemEnumId")),
                file2SystemEnumId        : normalize(readField(file2Source, "systemEnumId")),
                file1SftpServerId        : normalize(readField(file1Source, "sftpServerId")),
                file2SftpServerId        : normalize(readField(file2Source, "sftpServerId")),
                file1FileTypeEnumId      : normalize(readField(file1Source, "fileTypeEnumId")),
                file2FileTypeEnumId      : normalize(readField(file2Source, "fileTypeEnumId")),
                file1SchemaFileName      : normalize(readField(file1Source, "schemaFileName")),
                file2SchemaFileName      : normalize(readField(file2Source, "schemaFileName")),
                file1RemotePath          : normalize(readField(file1Source, "remotePathTemplate")),
                file2RemotePath          : normalize(readField(file2Source, "remotePathTemplate")),
                sftpRunScopeEnumId       : normalize(input.sftpRunScopeEnumId) ?: SFTP_SCOPE_TENANT,
                runTenantUserGroupId     : runTenantUserGroupId,
                allowAdminSftp           : toBoolean(input.allowAdminSftp),
                archiveSubdir            : normalize(input.archiveSubdir) ?: "archive",
                hasHeader                : input.containsKey("hasHeader") ? input.hasHeader : Boolean.TRUE,
                stageLocation            : normalize(input.stageLocation) ?: "runtime://tmp/reconciliation/automation/input",
                outputLocation           : normalize(input.outputLocation),
                pollIntervalMinutes      : positiveInteger(input.pollIntervalMinutes) ?: DEFAULT_POLL_INTERVAL_MINUTES,
                pollTimeoutMinutes       : positiveInteger(input.pollTimeoutMinutes) ?: DEFAULT_POLL_TIMEOUT_MINUTES,
                pollSleepMillis          : positiveLong(input.pollSleepMillis),
                sparkMaster              : normalize(input.sparkMaster),
                sparkAppName             : sparkAppName,
        ].findAll { it.value != null } as Map<String, Object>
    }

    protected static Map<String, Object> pollSftpUntilAvailable(def ec, Map<String, Object> pollParams) {
        int intervalMinutes = positiveInteger(pollParams.pollIntervalMinutes) ?: DEFAULT_POLL_INTERVAL_MINUTES
        int timeoutMinutes = positiveInteger(pollParams.pollTimeoutMinutes) ?: DEFAULT_POLL_TIMEOUT_MINUTES
        long sleepMillis = positiveLong(pollParams.pollSleepMillis) ?: intervalMinutes * 60_000L
        int attemptCount = 1

        Map<String, Object> result = callSftpPollService(ec, pollParams)
        while (result.dataAvailable != true && !hasMessageErrors(ec) && attemptCount * intervalMinutes <= timeoutMinutes) {
            retrySleeper.call(sleepMillis)
            attemptCount++
            result = callSftpPollService(ec, pollParams)
        }

        Map<String, Object> finalResult = [:]
        finalResult.putAll(result)
        finalResult.pollAttemptCount = attemptCount
        if (finalResult.dataAvailable != true && timeoutMinutes > 0) {
            String status = normalize(finalResult.statusMessage) ?: "No SFTP files found"
            finalResult.statusMessage = "${status}. Checked every ${intervalMinutes} minutes for up to ${timeoutMinutes} minutes.".toString()
        }
        return finalResult
    }

    protected static Map<String, Object> callSftpPollService(def ec, Map<String, Object> pollParams) {
        def call = ec.service.sync()
                .name("reconciliation.ReconciliationAutomationServices.poll#SftpAndReconcile")
                .parameters(pollParams)
        if (call?.metaClass?.respondsTo(call, "disableAuthz")) call = call.disableAuthz()
        return (call.call() ?: [:]) as Map<String, Object>
    }

    protected static def createAutomationExecution(def ec, def automation, Map input) {
        return runInTransaction(ec, "Error creating reconciliation automation execution", {
            def createdTimestamp = nowTimestamp(ec)
            def execution = ec.entity.makeValue(DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION)
            execution.set("automationId", normalize(readField(automation, "automationId")))
            execution.set("companyUserGroupId", normalize(readField(automation, "companyUserGroupId")))
            execution.set("createdByUserId", normalize(readField(automation, "createdByUserId")) ?: currentUserId(ec))
            execution.set("statusEnumId", AUTOMATION_STATUS_RUNNING)
            execution.set("scheduledDate", input.scheduledDate ?: createdTimestamp)
            execution.set("startedDate", createdTimestamp)
            execution.set("createdDate", createdTimestamp)
            execution.set("lastUpdatedDate", createdTimestamp)
            execution.setSequencedIdPrimary()
            execution.create()
            return execution
        })
    }

    protected static String persistAutomationRunResult(def ec, def automation, Map<String, Object> pollResult,
            String resultDataManagerPath) {
        if (!resultDataManagerPath) return null

        return runInTransaction(ec, "Error saving reconciliation automation run result", {
            def runResultValue = ec.entity.makeValue(DarpanEntityConstants.RECONCILIATION_RUN_RESULT)
            runResultValue.set("savedRunId", normalize(readField(automation, "savedRunId")))
            runResultValue.set("savedRunType", normalize(readField(automation, "savedRunType")) ?: "ruleset")
            runResultValue.set("reconciliationRunId", normalize(readField(automation, "reconciliationRunId")))
            runResultValue.set("reconciliationMappingId", normalize(readField(automation, "reconciliationMappingId")))
            runResultValue.set("ruleSetId", normalize(readField(automation, "ruleSetId")))
            runResultValue.set("compareScopeId", normalize(readField(automation, "compareScopeId")))
            runResultValue.set("companyUserGroupId", normalize(readField(automation, "companyUserGroupId")))
            runResultValue.set("createdByUserId", normalize(readField(automation, "createdByUserId")) ?: currentUserId(ec))
            runResultValue.set("file1Name", normalize(pollResult.file1SelectedName))
            runResultValue.set("file2Name", normalize(pollResult.file2SelectedName))
            runResultValue.set("resultDataManagerPath", resultDataManagerPath)
            runResultValue.set("reconciliationType", normalize(pollResult.reconciliationType))
            runResultValue.set("differenceCount", normalizeInt(pollResult.differenceCount))
            runResultValue.set("onlyInFile1Count", normalizeInt(pollResult.onlyInFile1Count))
            runResultValue.set("onlyInFile2Count", normalizeInt(pollResult.onlyInFile2Count))
            runResultValue.set("createdDate", nowTimestamp(ec))
            runResultValue.setSequencedIdPrimary()
            runResultValue.create()
            return normalize(readField(runResultValue, "reconciliationRunResultId"))
        }) as String
    }

    protected static boolean hasMessageErrors(def ec) {
        try {
            return ec?.message?.hasError() == true
        } catch (Exception ignored) {
            return false
        }
    }

    protected static Integer positiveInteger(Object rawValue) {
        Integer value = normalizeInt(rawValue)
        return value != null && value > 0 ? value : null
    }

    protected static Long positiveLong(Object rawValue) {
        Long value = normalizeLong(rawValue)
        return value != null && value > 0L ? value : null
    }

    protected static boolean toBoolean(Object rawValue) {
        return rawValue == Boolean.TRUE || ["Y", "true", "TRUE", "yes", "YES"].contains(normalize(rawValue))
    }

}
