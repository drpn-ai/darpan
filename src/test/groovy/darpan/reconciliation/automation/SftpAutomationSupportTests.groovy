package darpan.reconciliation.automation

import darpan.reconciliation.notification.TenantNotificationSupport
import org.junit.jupiter.api.Test

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

class SftpAutomationSupportTests {
    private static final Timestamp NOW = Timestamp.valueOf("2026-05-01 10:00:00")

    @Test
    void defaultOutputLocationUsesDataManagerRunFolder() {
        def ec = new Expando(resource: new Expando(properties: [:]))

        assertEquals(
                "runtime://datamanager/reconciliation-runs/OrderIdMap/20260430-010000000",
                SftpAutomationSupport.resolveDefaultOutputLocation(ec, "OrderIdMap", "20260430-010000000")
        )
    }

    @Test
    void runtimeOutputLocationMapsToRemoteDatamanagerPath() {
        assertEquals(
                "/datamanager/reconciliation-runs/OrderIdMap/20260430-010000000",
                SftpAutomationSupport.remotePathForRuntimeLocation(
                        "runtime://datamanager/reconciliation-runs/OrderIdMap/20260430-010000000"
                )
        )
        assertNull(SftpAutomationSupport.remotePathForRuntimeLocation("/incoming/results"))
    }

    @Test
    void sftpFileAutomationDelegatesConfiguredSourcesAndRecordsCompletion() {
        FakeEc ec = fakeEc()
        seedSftpAutomation(ec)
        ec.entity.add("moqui.security.UserGroup", [
                userGroupId    : "TENANT_A",
                groupTypeEnumId: "UgtDarpanCompany",
                description    : "Tenant A",
        ])
        String webhookUrl = "https://chat.googleapis.com/v1/spaces/TENANT_A_SPACE/messages?key=test-key&token=test-token"
        ec.entity.add("darpan.reconciliation.TenantNotificationSetting", [
                companyUserGroupId   : "TENANT_A",
                createdByUserId      : "tester",
                googleChatWebhookUrl : webhookUrl,
                isActive             : "Y",
                createdDate          : NOW,
                lastUpdatedDate      : NOW,
        ])
        ec.service.nextResult = [
                dataAvailable       : true,
                statusMessage       : "Complete",
                file1Source         : "sftp://source-a:22/incoming/shopify",
                file2Source         : "sftp://source-b:22/incoming/netsuite",
                file1SelectedName   : "shopify.csv",
                file2SelectedName   : "netsuite.csv",
                file1StagedLocation : "/tmp/shopify.csv",
                file2StagedLocation : "/tmp/netsuite.csv",
                reconciliationType  : "ORDER",
                diffLocation        : "reconciliation-runs/AUTO_SFTP/20260501/result.json",
                diffFileName        : "result.json",
                differenceCount     : 3,
                onlyInFile1Count    : 1,
                onlyInFile2Count    : 2,
                validationErrors    : [],
                processingWarnings  : [],
        ]
        List<Map<String, Object>> deliveries = []
        TenantNotificationSupport.setDeliveryHook { String deliveredWebhookUrl, Map<String, Object> payload ->
            deliveries << [webhookUrl: deliveredWebhookUrl, payload: payload]
            return [ok: true, statusCode: 200]
        }

        Map result
        try {
            result = SftpAutomationSupport.runSftpFileAutomation(ec, [automationId: "AUTO_SFTP"])
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }

        assertEquals("AUTO_EXEC_1", result.automationExecutionId)
        assertEquals(SftpAutomationSupport.AUTOMATION_STATUS_COMPLETED, result.statusEnumId)
        assertEquals("RUN_RESULT_1", result.reconciliationRunResultId)
        assertEquals("reconciliation-runs/AUTO_SFTP/20260501/result.json", result.resultDataManagerPath)
        assertEquals(1, deliveries.size())
        assertEquals(webhookUrl, deliveries[0].webhookUrl)
        String text = deliveries[0].payload.text as String
        assertTrue(text.contains("Run result: <https://hotwax-darpan-dev.web.app/reconciliation/run-result/RS_ORDER/reconciliation-runs%2FAUTO_SFTP%2F20260501%2Fresult.json?runName=SFTP+Automation&file1SystemLabel=SHOPIFY&file2SystemLabel=NETSUITE|Open run result>"))
        assertTrue(text.contains("Only in SHOPIFY: 1"))
        assertTrue(text.contains("Only in NETSUITE: 2"))
        assertFalse(text.contains("Only in file 1"))
        assertFalse(text.contains("Only in file 2"))

        FakeServiceCall call = ec.service.calls[0]
        assertEquals("reconciliation.ReconciliationAutomationServices.poll#SftpAndReconcile", call.serviceName)
        assertEquals("RS_ORDER", call.params.ruleSetId)
        assertEquals("SCOPE_ORDER", call.params.compareScopeId)
        assertEquals("SFTP_FILE_1", call.params.file1SftpServerId)
        assertEquals("SFTP_FILE_2", call.params.file2SftpServerId)
        assertEquals("/incoming/shopify", call.params.file1RemotePath)
        assertEquals("/incoming/netsuite", call.params.file2RemotePath)
        assertEquals("TENANT_A", call.params.runTenantUserGroupId)
        assertEquals(SftpAutomationSupport.SFTP_SCOPE_TENANT, call.params.sftpRunScopeEnumId)
        assertFalse(call.params.allowAdminSftp)
        assertEquals(10, call.params.pollIntervalMinutes)
        assertEquals(60, call.params.pollTimeoutMinutes)

        FakeValue execution = ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution")[0]
        assertEquals(SftpAutomationSupport.AUTOMATION_STATUS_COMPLETED, execution.statusEnumId)
        assertEquals("shopify.csv", execution.file1Name)
        assertEquals("netsuite.csv", execution.file2Name)
        assertEquals("result.json", execution.resultFileName)
        assertEquals("reconciliation-runs/AUTO_SFTP/20260501/result.json", execution.resultDataManagerPath)
        assertEquals(3, execution.differenceCount)
        assertEquals(1, execution.onlyInFile1Count)
        assertEquals(2, execution.onlyInFile2Count)
        assertEquals("RUN_RESULT_1", execution.reconciliationRunResultId)

        FakeValue runResult = ec.entity.createdValues("darpan.reconciliation.ReconciliationRunResult")[0]
        assertEquals("RS_ORDER", runResult.savedRunId)
        assertEquals("ruleset", runResult.savedRunType)
        assertEquals("RS_ORDER", runResult.ruleSetId)
        assertEquals("SCOPE_ORDER", runResult.compareScopeId)
        assertEquals("reconciliation-runs/AUTO_SFTP/20260501/result.json", runResult.resultDataManagerPath)
    }

    @Test
    void sftpFileAutomationRecordsNoDataWithoutDateWindowConfig() {
        FakeEc ec = fakeEc()
        seedSftpAutomation(ec)
        ec.service.nextResult = [
                dataAvailable : false,
                statusMessage : "No file found at /incoming/shopify matching criteria.",
                validationErrors: [],
                processingWarnings: [],
        ]
        SftpAutomationSupport.setRetrySleeper { long ignored -> }

        Map result
        try {
            result = SftpAutomationSupport.runSftpFileAutomation(ec, [
                    automationId : "AUTO_SFTP",
                    scheduledDate: Timestamp.valueOf("2026-05-01 09:00:00"),
            ])
        } finally {
            SftpAutomationSupport.resetRetrySleeper()
        }

        assertEquals(SftpAutomationSupport.AUTOMATION_STATUS_NO_DATA, result.statusEnumId)
        assertEquals(7, result.pollAttemptCount)
        assertEquals(7, ec.service.calls.size())
        assertNull(result.reconciliationRunResultId)

        FakeValue execution = ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution")[0]
        assertEquals(SftpAutomationSupport.AUTOMATION_STATUS_NO_DATA, execution.statusEnumId)
        assertEquals(Timestamp.valueOf("2026-05-01 09:00:00"), execution.scheduledDate)
        assertNull(execution.reconciliationRunResultId)
        assertTrue(execution.safeMetadataJson.contains("No file found"))
        assertTrue(execution.safeMetadataJson.contains("\"pollAttemptCount\":7"))
    }

    @Test
    void sftpFileAutomationPassesAdminScopeOnlyWhenExplicitlyRequested() {
        FakeEc ec = fakeEc()
        seedSftpAutomation(ec)
        ec.service.nextResult = [
                dataAvailable     : false,
                statusMessage     : "No files found to reconcile",
                validationErrors  : [],
                processingWarnings: [],
        ]
        SftpAutomationSupport.setRetrySleeper { long ignored -> }

        try {
            SftpAutomationSupport.runSftpFileAutomation(ec, [
                    automationId       : "AUTO_SFTP",
                    sftpRunScopeEnumId : SftpAutomationSupport.SFTP_SCOPE_ADMIN,
                    allowAdminSftp     : true,
            ])
        } finally {
            SftpAutomationSupport.resetRetrySleeper()
        }

        FakeServiceCall call = ec.service.calls[0]
        assertEquals(SftpAutomationSupport.SFTP_SCOPE_ADMIN, call.params.sftpRunScopeEnumId)
        assertTrue(call.params.allowAdminSftp)
    }

    @Test
    void sftpFileAutomationFailsExecutionBeforePollingWhenSourceIsNotSftp() {
        FakeEc ec = fakeEc()
        seedSftpAutomation(ec)
        ec.entity.rows["darpan.reconciliation.ReconciliationAutomationSource"][0]["sourceTypeEnumId"] = "AUT_SRC_API"

        IllegalArgumentException exception = assertThrows(IllegalArgumentException) {
            SftpAutomationSupport.runSftpFileAutomation(ec, [automationId: "AUTO_SFTP"])
        }

        assertTrue(exception.message.contains("AUT_SRC_SFTP"))
        assertTrue(ec.service.calls.isEmpty())

        FakeValue execution = ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution")[0]
        assertEquals(SftpAutomationSupport.AUTOMATION_STATUS_FAILED, execution.statusEnumId)
        assertTrue(execution.errorMessage.contains("AUT_SRC_SFTP"))
    }

    private static FakeEc fakeEc() {
        FakeEc ec = new FakeEc(
                entity: new FakeEntityFacade(),
                service: new FakeServiceFacade(),
                transaction: new FakeTransactionFacade(),
                message: new FakeMessageFacade(),
                user: new Expando(nowTimestamp: NOW, userId: "tester"),
                resource: new Expando(properties: [:]),
        )
        ec.service.ec = ec
        return ec
    }

    private static void seedSftpAutomation(FakeEc ec) {
        ec.entity.add("darpan.reconciliation.ReconciliationAutomation", [
                automationId      : "AUTO_SFTP",
                automationName    : "SFTP Automation",
                companyUserGroupId: "TENANT_A",
                createdByUserId   : "tester",
                inputModeEnumId   : SftpAutomationSupport.AUTOMATION_INPUT_SFTP_FILES,
                savedRunId        : "RS_ORDER",
                savedRunType      : "ruleset",
                ruleSetId         : "RS_ORDER",
                compareScopeId    : "SCOPE_ORDER",
        ])
        ec.entity.add("darpan.reconciliation.ReconciliationAutomationSource", [
                automationId      : "AUTO_SFTP",
                fileSide          : "FILE_1",
                companyUserGroupId: "TENANT_A",
                sourceTypeEnumId  : SftpAutomationSupport.AUTOMATION_SOURCE_SFTP,
                systemEnumId      : "SHOPIFY",
                fileTypeEnumId    : "DftCsv",
                sftpServerId      : "SFTP_FILE_1",
                remotePathTemplate: "/incoming/shopify",
                fileNamePattern   : "*.csv",
        ])
        ec.entity.add("darpan.reconciliation.ReconciliationAutomationSource", [
                automationId      : "AUTO_SFTP",
                fileSide          : "FILE_2",
                companyUserGroupId: "TENANT_A",
                sourceTypeEnumId  : SftpAutomationSupport.AUTOMATION_SOURCE_SFTP,
                systemEnumId      : "NETSUITE",
                fileTypeEnumId    : "DftCsv",
                sftpServerId      : "SFTP_FILE_2",
                remotePathTemplate: "/incoming/netsuite",
                fileNamePattern   : "*.csv",
        ])
    }

    private static Map<String, Object> buildNotificationPayload(FakeEc ec, Map<String, Object> params) {
        String tenantLabel = ((params.companyLabel)?.toString()?.trim()) ?:
                darpan.facade.common.TenantAccessSupport.resolveTenantLabelForUserGroupId(ec, params.companyUserGroupId)
        String runName = ((params.runName)?.toString()?.trim()) ?:
                ((params.savedRunId)?.toString()?.trim()) ?:
                ((params.reconciliationRunId)?.toString()?.trim()) ?:
                "reconciliation run"
        String resultId = ((params.reconciliationRunResultId)?.toString()?.trim())
        String resultUrl = TenantNotificationSupport.buildRunResultUrl(ec, params)
        String file1SystemLabel = TenantNotificationSupport.resolveFileSystemLabel(ec, params, "file1", null)
        String file2SystemLabel = TenantNotificationSupport.resolveFileSystemLabel(ec, params, "file2", null)
        Closure<String> displayCount = { Object value ->
            value == null ? "0" :
                    value instanceof Number ? ((Number) value).intValue().toString() :
                            (((value)?.toString()?.trim()) ?: "0")
        }
        List<String> lines = ["Darpan run completed: ${runName}".toString()]
        if (tenantLabel) lines << "Tenant: ${tenantLabel}".toString()
        if (resultId) lines << "Result ID: ${resultId}".toString()
        if (resultUrl) lines << "Run result: <${resultUrl}|Open run result>".toString()
        lines << "Differences: ${displayCount(params.differenceCount)}".toString()
        lines << "Only in ${file1SystemLabel ?: "File 1"}: ${displayCount(params.onlyInFile1Count)}".toString()
        lines << "Only in ${file2SystemLabel ?: "File 2"}: ${displayCount(params.onlyInFile2Count)}".toString()
        return [payload: [text: lines.join("\n")]]
    }

    private static class FakeEc {
        FakeEntityFacade entity
        FakeServiceFacade service
        FakeTransactionFacade transaction
        FakeMessageFacade message
        Object user
        Object resource
    }

    private static class FakeEntityFacade {
        Map<String, List<FakeValue>> rows = [:].withDefault { [] }
        int automationExecutionSeq = 1
        int runResultSeq = 1

        FakeFind find(String entityName) {
            return new FakeFind(entity: this, entityName: entityName)
        }

        FakeValue makeValue(String entityName) {
            return new FakeValue([:], entityName, this)
        }

        void add(String entityName, Map fields) {
            rows[entityName] << new FakeValue(fields, entityName, this)
        }

        List<FakeValue> createdValues(String entityName) {
            return rows[entityName].findAll { it.@created }
        }
    }

    private static class FakeFind {
        FakeEntityFacade entity
        String entityName
        Map<String, Object> conditions = [:]

        FakeFind condition(String fieldName, Object value) {
            conditions[fieldName] = value
            return this
        }

        FakeFind disableAuthz() { return this }

        FakeFind useCache(boolean ignored) { return this }

        FakeValue one() {
            return list().find()
        }

        List<FakeValue> list() {
            return entity.rows[entityName].findAll { value ->
                conditions.every { fieldName, expected ->
                    value[fieldName] == expected
                }
            }
        }
    }

    private static class FakeValue extends LinkedHashMap<String, Object> {
        String entityName
        FakeEntityFacade entity
        boolean created
        boolean updated

        FakeValue(Map fields = [:], String entityName = null, FakeEntityFacade entity = null) {
            super(fields)
            this.entityName = entityName
            this.entity = entity
        }

        FakeValue set(String fieldName, Object value) {
            put(fieldName, value)
            return this
        }

        FakeValue setSequencedIdPrimary() {
            if (entityName == "darpan.reconciliation.ReconciliationAutomationExecution") {
                put("automationExecutionId", "AUTO_EXEC_${entity.automationExecutionSeq++}".toString())
            } else if (entityName == "darpan.reconciliation.ReconciliationRunResult") {
                put("reconciliationRunResultId", "RUN_RESULT_${entity.runResultSeq++}".toString())
            }
            return this
        }

        FakeValue create() {
            created = true
            entity.rows[entityName] << this
            return this
        }

        FakeValue update() {
            updated = true
            return this
        }
    }

    private static class FakeServiceFacade {
        Map<String, Object> nextResult = [:]
        List<FakeServiceCall> calls = []
        FakeEc ec

        FakeServiceCall sync() {
            return new FakeServiceCall(service: this)
        }
    }

    private static class FakeServiceCall {
        FakeServiceFacade service
        String serviceName
        Map<String, Object> params = [:]

        FakeServiceCall name(String serviceName) {
            this.serviceName = serviceName
            return this
        }

        FakeServiceCall parameters(Map<String, Object> params) {
            this.params = params
            return this
        }

        FakeServiceCall disableAuthz() { return this }

        Map<String, Object> call() {
            service.calls << this
            if (serviceName == "reconciliation.ReconciliationNotificationServices.build#RunCompletedPayload") {
                return buildNotificationPayload(service.ec, params)
            }
            return service.nextResult
        }
    }

    private static class FakeTransactionFacade {
        Object runUseOrBegin(Integer timeout, String message, Closure work) {
            return work.call()
        }
    }

    private static class FakeMessageFacade {
        boolean error

        boolean hasError() { return error }

        void addError(String ignored) {
            error = true
        }
    }
}
