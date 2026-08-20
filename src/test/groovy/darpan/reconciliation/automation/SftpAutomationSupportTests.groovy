package darpan.reconciliation.automation

import darpan.facade.reconciliation.RunObservability
import darpan.reconciliation.notification.TenantNotificationSupport
import org.junit.jupiter.api.Test

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

class SftpAutomationSupportTests {
    // Audit H11.2 removed the hardcoded dev fallback (https://hotwax-darpan-dev.web.app) for the app
    // base URL; tests must set their own so the run-result link assertion below has something to match.
    static {
        System.setProperty("darpan.app.baseUrl", "https://hotwax-darpan-dev.web.app")
    }

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
        ec.entity.add("darpan.reconciliation.TenantChatSpace", [
                chatSpaceId         : "CS_OPS",
                companyUserGroupId  : "TENANT_A",
                spaceName           : "Ops",
                googleChatWebhookUrl: webhookUrl,
                isActive            : "Y",
        ])
        FakeValue notifyAutomation = ec.entity.rows["darpan.reconciliation.ReconciliationAutomation"].find {
            it.automationId == "AUTO_SFTP"
        }
        notifyAutomation.put("chatSpaceId", "CS_OPS")
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
        assertTrue(text.contains("Run result: <https://hotwax-darpan-dev.web.app/reconciliation/run-result/RS_ORDER/reconciliation-runs%2FAUTO_SFTP%2F20260501%2Fresult.json?runName=SFTP+Automation&file1SystemLabel=SHOPIFY&file2SystemLabel=NETSUITE&tenantId=TENANT_A|Open run result>"))
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
        // Self-review-2 #4: the run-result row now carries an explicit status (not the entity default),
        // so a rule-failed run would persist FAILED here instead of a misleading "Succeeded".
        assertEquals(SftpAutomationSupport.AUTOMATION_STATUS_COMPLETED, runResult.statusEnumId)
    }

    @Test
    void sftpFailureWithRunResultNotifies() {
        // Task 7: a poll that produced data but whose rule execution failed still mints a run-result row
        // (status FAILED, since outputProduced only depends on dataAvailable/serviceReportedError) — the
        // guard (reconciliationRunResultId present && statusEnumId != NO_DATA) must still notify once,
        // unlike the true NO_DATA / unminted-row case in sftpFileAutomationRecordsNoDataWithoutDateWindowConfig.
        FakeEc ec = fakeEc()
        seedSftpAutomation(ec)
        ec.entity.add("moqui.security.UserGroup", [
                userGroupId    : "TENANT_A",
                groupTypeEnumId: "UgtDarpanCompany",
                description    : "Tenant A",
        ])
        String webhookUrl = "https://chat.googleapis.com/v1/spaces/TENANT_A_SPACE/messages?key=test-key&token=test-token"
        ec.entity.add("darpan.reconciliation.TenantChatSpace", [
                chatSpaceId         : "CS_OPS",
                companyUserGroupId  : "TENANT_A",
                spaceName           : "Ops",
                googleChatWebhookUrl: webhookUrl,
                isActive            : "Y",
        ])
        FakeValue notifyAutomation = ec.entity.rows["darpan.reconciliation.ReconciliationAutomation"].find {
            it.automationId == "AUTO_SFTP"
        }
        notifyAutomation.put("chatSpaceId", "CS_OPS")
        ec.service.nextResult = [
                dataAvailable       : true,
                ruleExecutionFailed : true,
                statusMessage       : "Ruleset did not fully evaluate",
                file1Source         : "sftp://source-a:22/incoming/shopify",
                file2Source         : "sftp://source-b:22/incoming/netsuite",
                file1SelectedName   : "shopify.csv",
                file2SelectedName   : "netsuite.csv",
                file1StagedLocation : "/tmp/shopify.csv",
                file2StagedLocation : "/tmp/netsuite.csv",
                reconciliationType  : "ORDER",
                diffLocation        : "reconciliation-runs/AUTO_SFTP/20260501/result.json",
                diffFileName        : "result.json",
                differenceCount     : 0,
                onlyInFile1Count    : 0,
                onlyInFile2Count    : 0,
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

        assertEquals(SftpAutomationSupport.AUTOMATION_STATUS_FAILED, result.statusEnumId)
        assertEquals("RUN_RESULT_1", result.reconciliationRunResultId)
        FakeValue execution = ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution")[0]
        assertEquals(SftpAutomationSupport.AUTOMATION_STATUS_FAILED, execution.statusEnumId)
        assertEquals(1, ec.entity.createdValues("darpan.reconciliation.ReconciliationRunResult").size())
        assertEquals(1, deliveries.size())
        assertEquals(webhookUrl, deliveries[0].webhookUrl)
    }

    @Test
    void sftpNotifyFailureDoesNotOverwriteTerminalStatus() {
        // Fix round 1 (review finding 2, SFTP regression guard for finding 1): before finding 1's fix,
        // this unguarded throw — from the payload-build call, which (unlike the delivery loop) has no
        // internal try/catch — would propagate out of the unwrapped notify call, be caught by the
        // method's outer catch(Throwable), unconditionally overwrite statusEnumId to
        // AUTOMATION_STATUS_FAILED, and re-throw — corrupting an otherwise-successful run and its
        // already-correct terminal status. With the fix, the notify failure is absorbed locally: the
        // terminal status set moments earlier by updateAutomationExecution stands, and nothing propagates.
        FakeEc ec = fakeEc()
        seedSftpAutomation(ec)
        // A destination MUST resolve, or notifyRunCompleted short-circuits before ever reaching the
        // payload-build call (NO_DESTINATIONS), which would make explodeOnBuildPayload below a no-op.
        ec.entity.add("darpan.reconciliation.TenantChatSpace", [
                chatSpaceId         : "CS_OPS",
                companyUserGroupId  : "TENANT_A",
                spaceName           : "Ops",
                googleChatWebhookUrl: "https://chat.googleapis.com/v1/spaces/AAA/messages?key=test-key&token=test-token",
                isActive            : "Y",
        ])
        FakeValue notifyAutomation = ec.entity.rows["darpan.reconciliation.ReconciliationAutomation"].find {
            it.automationId == "AUTO_SFTP"
        }
        notifyAutomation.put("chatSpaceId", "CS_OPS")
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
        ec.service.explodeOnBuildPayload = true

        // No delivery hook is set/needed: the payload-build call throws before deliverGoogleChat is ever
        // reached. If this regresses (the best-effort wrap removed), runSftpFileAutomation itself throws.
        Map result = SftpAutomationSupport.runSftpFileAutomation(ec, [automationId: "AUTO_SFTP"])

        assertEquals(SftpAutomationSupport.AUTOMATION_STATUS_COMPLETED, result.statusEnumId)
        FakeValue execution = ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution")[0]
        assertEquals(SftpAutomationSupport.AUTOMATION_STATUS_COMPLETED, execution.statusEnumId)
        assertEquals(1, ec.entity.createdValues("darpan.reconciliation.ReconciliationRunResult").size())
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
        // Task 2d: this used to assert "no run-result row at all", which it can no longer prove — the row
        // is minted at RUNNING so a NO_DATA window has one. The stronger, still-true statement is that the
        // one row it has ended NO_DATA rather than being left stranded RUNNING, and that the execution row
        // names it, which is what makes the window followable while it is still polling.
        assertEquals("RUN_RESULT_1", result.reconciliationRunResultId)
        List<FakeValue> runResults = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"]
        assertEquals(1, runResults.size(), "a NO_DATA window owns exactly one run-result row")
        assertEquals(SftpAutomationSupport.AUTOMATION_STATUS_NO_DATA, runResults[0].statusEnumId)

        FakeValue execution = ec.entity.createdValues("darpan.reconciliation.ReconciliationAutomationExecution")[0]
        assertEquals(SftpAutomationSupport.AUTOMATION_STATUS_NO_DATA, execution.statusEnumId)
        assertEquals(Timestamp.valueOf("2026-05-01 09:00:00"), execution.scheduledDate)
        assertEquals("RUN_RESULT_1", execution.reconciliationRunResultId)
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

    // ==================================================================================================
    // Task 2d — the run-result row is minted at RUNNING on the SFTP path too, so "Run now" on an SFTP
    // automation has a live run to redirect to. Mirrors AutomationExecutionSupportTests' Task 2b set.
    // ==================================================================================================

    @Test
    void activeSftpExecutionCarriesARunResultIdWhileThePollIsStillGoing() {
        // The only test that asserts the IN-FLIGHT triple (execution RUNNING, execution names a run-result
        // row, that row is RUNNING). It reads the live rows from inside the poll service call, which is
        // exactly where the "Run now" UI poll looks. Every other test here could pass with the id
        // appearing only at the end — which is precisely the production bug. It also pins
        // companyUserGroupId on the row, without which the tenant-gated get#ReconciliationRunStatus read
        // would deny the operator who started the run.
        FakeEc ec = fakeEc()
        seedSftpAutomation(ec)
        ec.service.nextResult = successfulPollResult()
        Map<String, Object> liveSnapshot = [:]
        ec.service.onPollCall = { liveSnapshot.putAll(snapshotLiveRun(ec)) }

        SftpAutomationSupport.runSftpFileAutomation(ec, [automationId: "AUTO_SFTP"])

        assertEquals(SftpAutomationSupport.AUTOMATION_STATUS_RUNNING, liveSnapshot.executionStatus)
        assertNotNull(liveSnapshot.executionRunResultId,
                "an in-flight SFTP execution must already name its run-result row")
        assertEquals(liveSnapshot.runResultId, liveSnapshot.executionRunResultId,
                "the id on the execution row must be the row that actually exists")
        assertEquals(SftpAutomationSupport.AUTOMATION_STATUS_RUNNING, liveSnapshot.runResultStatus)
        assertEquals("TENANT_A", liveSnapshot.runResultTenant,
                "the live row must be readable by the tenant that started the run")
        assertEquals(NOW, liveSnapshot.runResultStartedDate)
        assertNull(liveSnapshot.runResultCompletedDate, "a live run has not completed")
    }

    @Test
    void aSuccessfulSftpRunOwnsExactlyOneRunResultRowFromStartToFinish() {
        // The only test that catches the specific regression early minting invites: mint at RUNNING AND
        // create a second row at terminal. A count-only assertion would have passed against the old code
        // too (it also produced exactly one row), so this asserts count == 1 AND that the single row
        // carries both halves — startedDate, written only by the mint, and resultDataManagerPath, written
        // only by the terminal update.
        FakeEc ec = fakeEc()
        seedSftpAutomation(ec)
        ec.service.nextResult = successfulPollResult()

        SftpAutomationSupport.runSftpFileAutomation(ec, [automationId: "AUTO_SFTP"])

        List<FakeValue> runResults = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"]
        assertEquals(1, runResults.size(), "one SFTP execution owns exactly one run-result row")
        assertEquals(1, ec.entity.createdValues("darpan.reconciliation.ReconciliationRunResult").size())
        FakeValue runResult = runResults[0]
        assertEquals(NOW, runResult.startedDate, "startedDate is written only by the mint at RUNNING")
        assertEquals("reconciliation-runs/AUTO_SFTP/20260501/result.json", runResult.resultDataManagerPath,
                "resultDataManagerPath is written only by the terminal update")
        assertEquals(SftpAutomationSupport.AUTOMATION_STATUS_COMPLETED, runResult.statusEnumId)
        assertEquals(NOW, runResult.completedDate)
        assertTrue(runResult.@updated, "the minted row must be UPDATED at terminal, not replaced")
    }

    @Test
    void theSftpRunResultIdSeenAtRunningIsTheSameIdSeenAtCompleted() {
        // Catches a mint whose id is later REPLACED rather than reused. The previous test counts rows;
        // this one follows identity — the id a redirected browser is holding must still resolve, at the
        // end, to the execution's id, the service out-parameter, and a row that is COMPLETED.
        FakeEc ec = fakeEc()
        seedSftpAutomation(ec)
        ec.service.nextResult = successfulPollResult()
        List<String> liveIds = []
        ec.service.onPollCall = { liveIds << (snapshotLiveRun(ec).executionRunResultId as String) }

        Map result = SftpAutomationSupport.runSftpFileAutomation(ec, [automationId: "AUTO_SFTP"])

        String runningId = liveIds[0]
        assertNotNull(runningId)
        FakeValue execution = ec.entity.rows["darpan.reconciliation.ReconciliationAutomationExecution"][0]
        assertEquals(runningId, execution.reconciliationRunResultId,
                "the id present at RUNNING must be the id present at terminal")
        assertEquals(runningId, result.reconciliationRunResultId)
        FakeValue runResult = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"]
                .find { it.reconciliationRunResultId == runningId }
        assertNotNull(runResult, "the id handed out at RUNNING must still resolve to a row")
        assertEquals(SftpAutomationSupport.AUTOMATION_STATUS_COMPLETED, runResult.statusEnumId)
    }

    @Test
    void aFailedSftpRunEndsItsMintedRowTerminalAndAlertsFailedOnce() {
        // The failure-path counterpart, and the only test that proves the throw exit closes the row it
        // minted rather than abandoning it RUNNING (where the stuck-run reaper would flip it FAILED two
        // hours later AND alert on it). Fix round 1: it now also pins the notification-parity decision —
        // a terminal SFTP failure alerts FAILED exactly like the API path does, exactly once, with the
        // FAILURE payload (not a success one), and claims the notifiedDate CAS on the same single row.
        FakeEc ec = fakeEc()
        seedSftpAutomation(ec)
        String webhookUrl = seedNotifyChatSpace(ec)
        ec.entity.rows["darpan.reconciliation.ReconciliationAutomationSource"][0]["sourceTypeEnumId"] = "AUT_SRC_API"
        List<Map<String, Object>> deliveries = []
        TenantNotificationSupport.setDeliveryHook { String deliveredWebhookUrl, Map<String, Object> payload ->
            deliveries << [webhookUrl: deliveredWebhookUrl, payload: payload]
            return [ok: true, statusCode: 200]
        }

        try {
            assertThrows(IllegalArgumentException) {
                SftpAutomationSupport.runSftpFileAutomation(ec, [automationId: "AUTO_SFTP"])
            }
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }

        List<FakeValue> runResults = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"]
        assertEquals(1, runResults.size(), "a failed run reuses its minted row instead of adding a second")
        FakeValue runResult = runResults[0]
        assertEquals(SftpAutomationSupport.AUTOMATION_STATUS_FAILED, runResult.statusEnumId,
                "the minted row must not be left stranded RUNNING by a failure")
        assertEquals(NOW, runResult.completedDate)
        assertTrue((runResult.errorMessage as String).contains("AUT_SRC_SFTP"))
        FakeValue execution = ec.entity.rows["darpan.reconciliation.ReconciliationAutomationExecution"][0]
        assertEquals(SftpAutomationSupport.AUTOMATION_STATUS_FAILED, execution.statusEnumId)
        assertEquals(runResult.reconciliationRunResultId, execution.reconciliationRunResultId)
        assertEquals(1, deliveries.size(), "a terminal SFTP failure must alert exactly once, like the API path")
        assertEquals(webhookUrl, deliveries[0].webhookUrl)
        String text = deliveries[0].payload.text as String
        assertTrue(text.contains("Darpan run completed WITH ISSUES"), "the delivered payload must be the FAILURE payload")
        assertTrue(text.contains("AUT_SRC_SFTP"), "the alert must carry the reason the run died")
        assertNotNull(runResult.notifiedDate, "the alert must claim the CAS on the one row this run owns")
    }

    @Test
    void aFailedExecutionRowCreateStillClosesTheRunResultItAlreadyMinted() {
        // The only proof of "no path may leave a minted row stranded RUNNING" for the one exit that
        // happens BEFORE the main try block. Delete the guard around createAutomationExecution and every
        // other test here still passes, while every DB failure at execution insert leaves a RUNNING row
        // that StuckRunReaper flips to FAILED — and alerts on — 120 minutes later.
        FakeEc ec = fakeEc()
        seedSftpAutomation(ec)
        seedNotifyChatSpace(ec)
        ec.entity.createHook = { FakeValue value ->
            if (value.entityName == "darpan.reconciliation.ReconciliationAutomationExecution") {
                throw new IllegalStateException("execution insert failed")
            }
        }
        List<Map<String, Object>> deliveries = []
        TenantNotificationSupport.setDeliveryHook { String webhookUrl, Map<String, Object> payload ->
            deliveries << [webhookUrl: webhookUrl, payload: payload]
            return [ok: true, statusCode: 200]
        }

        try {
            IllegalStateException thrown = assertThrows(IllegalStateException) {
                SftpAutomationSupport.runSftpFileAutomation(ec, [automationId: "AUTO_SFTP"])
            }
            assertEquals("execution insert failed", thrown.message,
                    "the original failure must reach the caller, not one raised by the cleanup")
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }

        assertTrue(ec.entity.rows["darpan.reconciliation.ReconciliationAutomationExecution"].isEmpty())
        List<FakeValue> runResults = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"]
        assertEquals(1, runResults.size(), "the row was already minted before the execution row was created")
        assertEquals(SftpAutomationSupport.AUTOMATION_STATUS_FAILED, runResults[0].statusEnumId,
                "with no execution row, nothing downstream will ever close this row — so this exit must")
        assertTrue((runResults[0].errorMessage as String).contains("execution insert failed"))
        assertTrue(deliveries.isEmpty(),
                "a pre-run infrastructure failure stays silent, like the API path's findOrCreateExecution throw")
        assertTrue(ec.entity.rows["darpan.reconciliation.ReconciliationRunNotifySubscription"].isEmpty())
    }

    @Test
    void aRunWithOutputButNoArtifactPathStillAlertsItsSubscriberInsteadOfDeletingThemSilently() {
        // The terminal exit nothing else covers: dataAvailable is true but the poll returned no
        // diffLocation/diffFileName, so resultDataManagerPath is blank. Before fix round 1 this branch
        // closed the row and PURGED — the subscriber who clicked "Notify me" mid-run was deleted without
        // ever being told the run ended. The automation has NO chatSpaceId of its own here, so the
        // subscription is the only route an alert can take: one delivery proves the subscriber path
        // specifically rather than incidentally.
        FakeEc ec = fakeEc()
        seedSftpAutomation(ec)
        String subscriberWebhookUrl = seedSubscriberChatSpace(ec)
        Map<String, Object> noArtifactPoll = successfulPollResult()
        noArtifactPoll.remove("diffLocation")
        noArtifactPoll.remove("diffFileName")
        ec.service.nextResult = noArtifactPoll
        ec.service.onPollCall = { subscribeMidRun(ec, "USER_A", "CS_ME") }
        List<Map<String, Object>> deliveries = []
        TenantNotificationSupport.setDeliveryHook { String webhookUrl, Map<String, Object> payload ->
            deliveries << [webhookUrl: webhookUrl, payload: payload]
            return [ok: true, statusCode: 200]
        }

        Map result
        try {
            result = SftpAutomationSupport.runSftpFileAutomation(ec, [automationId: "AUTO_SFTP"])
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }

        assertNull(result.resultDataManagerPath, "this is the blank-artifact-path branch")
        List<FakeValue> runResults = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"]
        assertEquals(1, runResults.size())
        assertEquals(SftpAutomationSupport.AUTOMATION_STATUS_COMPLETED, runResults[0].statusEnumId,
                "the row must still end terminal even though nothing was written to it")
        assertEquals(1, deliveries.size(), "the subscriber must be told how the run ended")
        assertEquals(subscriberWebhookUrl, deliveries[0].webhookUrl)
        assertNotNull(runResults[0].notifiedDate)
        assertTrue(ec.entity.rows["darpan.reconciliation.ReconciliationRunNotifySubscription"].isEmpty(),
                "and the subscription must not outlive the run it was watching")
    }

    @Test
    void aPollServiceErrorAlertsTheSubscriberItWouldOtherwiseHaveDeletedSilently() {
        // The other no-output terminal exit: the poll service raised a message error, so the run is
        // FAILED with nothing persisted. Distinct code path from the throw (this is the else-if inside
        // the try body, not the catch). Again the automation has no chatSpaceId, so the single delivery
        // is proof the subscriber themselves was reached.
        FakeEc ec = fakeEc()
        seedSftpAutomation(ec)
        String subscriberWebhookUrl = seedSubscriberChatSpace(ec)
        ec.service.nextResult = successfulPollResult()
        ec.service.onPollCall = {
            subscribeMidRun(ec, "USER_A", "CS_ME")
            ec.message.addError("SFTP poll service failed")
        }
        List<Map<String, Object>> deliveries = []
        TenantNotificationSupport.setDeliveryHook { String webhookUrl, Map<String, Object> payload ->
            deliveries << [webhookUrl: webhookUrl, payload: payload]
            return [ok: true, statusCode: 200]
        }

        Map result
        try {
            result = SftpAutomationSupport.runSftpFileAutomation(ec, [automationId: "AUTO_SFTP"])
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }

        assertEquals(SftpAutomationSupport.AUTOMATION_STATUS_FAILED, result.statusEnumId)
        assertNull(result.resultDataManagerPath, "a service-error run persists no output")
        List<FakeValue> runResults = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"]
        assertEquals(1, runResults.size())
        assertEquals(SftpAutomationSupport.AUTOMATION_STATUS_FAILED, runResults[0].statusEnumId)
        assertEquals(1, deliveries.size(), "a no-output SFTP failure must alert, like the API path")
        assertEquals(subscriberWebhookUrl, deliveries[0].webhookUrl)
        assertTrue((deliveries[0].payload.text as String).contains("Darpan run completed WITH ISSUES"))
        assertNotNull(runResults[0].notifiedDate)
        assertTrue(ec.entity.rows["darpan.reconciliation.ReconciliationRunNotifySubscription"].isEmpty(),
                "the alert's won claim is what purges the subscription — never a purge ahead of it")
    }

    @Test
    void aReDrivenSftpRunMintsAFreshRowSoItsCompletionStillNotifies() {
        // Guards the invariant the whole mint-per-attempt design rests on: a re-drive must never adopt an
        // already-notified row. Adopting one would let notifiedDate's claim-then-deliver CAS swallow the
        // re-drive's completion alert (ALREADY_NOTIFIED) — a silent regression no other test here would
        // catch, because every other test stops after one run.
        FakeEc ec = fakeEc()
        seedSftpAutomation(ec)
        String webhookUrl = seedNotifyChatSpace(ec)
        ec.service.nextResult = successfulPollResult()
        List<Map<String, Object>> deliveries = []
        TenantNotificationSupport.setDeliveryHook { String deliveredWebhookUrl, Map<String, Object> payload ->
            deliveries << [webhookUrl: deliveredWebhookUrl, payload: payload]
            return [ok: true, statusCode: 200]
        }

        Map firstResult
        Map secondResult
        try {
            firstResult = SftpAutomationSupport.runSftpFileAutomation(ec, [automationId: "AUTO_SFTP"])
            secondResult = SftpAutomationSupport.runSftpFileAutomation(ec, [automationId: "AUTO_SFTP"])
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }

        assertNotEquals(firstResult.reconciliationRunResultId, secondResult.reconciliationRunResultId,
                "the re-drive must mint its OWN row — adopting the first would inherit its spent notifiedDate")
        List<FakeValue> runResults = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"]
        assertEquals(2, runResults.size())
        assertTrue(runResults.every { it.notifiedDate != null },
                "each attempt's row carries its own, unspent notify claim")
        assertEquals(2, deliveries.size(), "the re-drive's completion alert must not be swallowed")
        assertEquals(webhookUrl, deliveries[1].webhookUrl)
        List<FakeValue> executions = ec.entity.rows["darpan.reconciliation.ReconciliationAutomationExecution"]
        assertEquals(2, executions.size())
        assertEquals(secondResult.reconciliationRunResultId, executions[1].reconciliationRunResultId)
    }

    @Test
    void aNoDataSftpRunPurgesTheNotifyMeSubscriptionItCanNowCollect() {
        // What else keys on the run-result row: subscribe#RunNotification accepts any run whose row is
        // PENDING/RUNNING, so minting at RUNNING silently makes "Notify me" reachable for SFTP runs. The
        // NO_DATA close never notifies, and purgeRunSubscriptions only runs off a won notification claim,
        // so without an explicit purge the subscription would survive forever — never firing, and
        // permanently counting as chat-space usage so settings refuses to delete that space. This is the
        // only test that exercises the subscription lifecycle on the SFTP path.
        FakeEc ec = fakeEc()
        seedSftpAutomation(ec)
        ec.service.nextResult = [
                dataAvailable     : false,
                statusMessage     : "No file found at /incoming/shopify matching criteria.",
                validationErrors  : [],
                processingWarnings: [],
        ]
        ec.service.onPollCall = { subscribeMidRun(ec, "USER_A", "CS_ME") }
        List<Map<String, Object>> deliveries = []
        TenantNotificationSupport.setDeliveryHook { String webhookUrl, Map<String, Object> payload ->
            deliveries << [webhookUrl: webhookUrl, payload: payload]
            return [ok: true, statusCode: 200]
        }
        SftpAutomationSupport.setRetrySleeper { long ignored -> }

        try {
            SftpAutomationSupport.runSftpFileAutomation(ec, [automationId: "AUTO_SFTP"])
        } finally {
            SftpAutomationSupport.resetRetrySleeper()
            TenantNotificationSupport.resetDeliveryHook()
        }

        FakeValue runResult = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"][0]
        assertEquals(SftpAutomationSupport.AUTOMATION_STATUS_NO_DATA, runResult.statusEnumId)
        assertNull(runResult.notifiedDate, "purging must not be achieved by sending a notification")
        assertTrue(deliveries.isEmpty(), "NO_DATA stayed silent before Task 2d and still must")
        assertTrue(ec.entity.rows["darpan.reconciliation.ReconciliationRunNotifySubscription"].isEmpty(),
                "a terminal run that never notifies must still purge its own subscriptions")
    }

    @Test
    void sftpHeartbeatsFireAfterEveryPollAttemptWithoutChangingStatusOrNotifiedDate() {
        // Task 2c parity. The SFTP poll loop is the longest unprotected stretch anywhere in the runner —
        // pollTimeoutMinutes of attempt/sleep cycles (60 by default, operator-configurable) during which
        // the newly-minted row is exposed to StuckRunReaper's 120-minute lastUpdatedStamp sweep. This is
        // the only test that pins the COUNT and ORDER of heartbeats against the poll attempts, so
        // dropping the heartbeat call site fails here even though the run itself still ends NO_DATA; and
        // the mid-run snapshot is the only check that a heartbeat writes lastHeartbeatDate WITHOUT
        // touching statusEnumId or notifiedDate.
        Timestamp heartbeatAt = Timestamp.valueOf("2026-05-01 10:05:00")
        FakeEc ec = fakeEc()
        seedSftpAutomation(ec)
        ec.service.nextResult = [
                dataAvailable     : false,
                statusMessage     : "No file found at /incoming/shopify matching criteria.",
                validationErrors  : [],
                processingWarnings: [],
        ]
        List<String> sequence = []
        Map<String, Object> lastLiveSnapshot = [:]
        ec.entity.updateHook = { FakeValue value ->
            // A run-result write that still reads RUNNING at .update() time is a heartbeat: every terminal
            // close sets a terminal statusEnumId in the SAME .set() sequence this hook inspects, so it
            // cannot mistake a terminal write for a heartbeat.
            if (value.entityName == "darpan.reconciliation.ReconciliationRunResult" &&
                    value.statusEnumId == SftpAutomationSupport.AUTOMATION_STATUS_RUNNING) {
                sequence << "heartbeat"
            }
        }
        ec.service.onPollCall = {
            sequence << "poll"
            ec.user.nowTimestamp = heartbeatAt
            lastLiveSnapshot.clear()
            lastLiveSnapshot.putAll(snapshotLiveRun(ec))
        }
        SftpAutomationSupport.setRetrySleeper { long ignored -> }

        try {
            SftpAutomationSupport.runSftpFileAutomation(ec, [automationId: "AUTO_SFTP"])
        } finally {
            SftpAutomationSupport.resetRetrySleeper()
        }

        assertEquals(["poll", "heartbeat"] * 7, sequence,
                "exactly one heartbeat must follow each poll attempt")
        // Everything below reads the snapshot taken by the LAST poll call — after six heartbeats and
        // before any terminal write. Asserting on the end state instead would prove nothing: the terminal
        // close writes lastHeartbeatDate too, so it lands on the same value with or without heartbeats.
        assertEquals(heartbeatAt, lastLiveSnapshot.runResultHeartbeatDate,
                "the heartbeat must move lastHeartbeatDate forward while the run is still going")
        assertEquals(SftpAutomationSupport.AUTOMATION_STATUS_RUNNING, lastLiveSnapshot.runResultStatus)
        assertNull(lastLiveSnapshot.runResultNotifiedDate)
        assertEquals(NOW, lastLiveSnapshot.runResultStartedDate, "a heartbeat must not rewrite startedDate")
    }

    // ==================================================================================================
    // Task 6 Part A — the SFTP half. Task 2d made an SFTP run followable on the live progress view, which
    // offers "Cancel run"; nothing on this path read the flag that button sets, so a poll kept polling for
    // up to its full timeout and the run reported its own outcome to an operator who had stopped it.
    // ==================================================================================================

    @Test
    void aCancelRequestedMidPollStopsTheSftpRunAndEndsBothRowsCancelled() {
        // The poll is this path's long phase: with no files present it retries up to pollTimeoutMinutes /
        // pollIntervalMinutes times (7 by default — see the heartbeat test above). The attempt count is
        // the load-bearing assertion: a run that ignored the cancel and was merely relabelled at the end
        // would still show CANCELLED, but it would have polled seven times.
        FakeEc ec = fakeEc()
        seedSftpAutomation(ec)
        ec.service.nextResult = [dataAvailable: false, statusMessage: "No SFTP files found"] as Map<String, Object>
        int[] pollCount = [0]
        ec.service.onPollCall = {
            pollCount[0]++
            if (pollCount[0] == 1) requestCancelMidRun(ec)
        }
        SftpAutomationSupport.setRetrySleeper { long ignored -> }

        Map result
        try {
            result = SftpAutomationSupport.runSftpFileAutomation(ec, [automationId: "AUTO_SFTP"])
        } finally {
            SftpAutomationSupport.resetRetrySleeper()
        }

        assertEquals(1, pollCount[0],
                "the cancel checkpoint must break the poll loop at the first attempt, not let it run to timeout")
        assertEquals(AutomationExecutionSupport.STATUS_CANCELLED, result.statusEnumId,
                "the service must report the cancellation as a normal outcome, not raise it as a crash")
        List<FakeValue> runResults = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"]
        assertEquals(1, runResults.size(), "cancelling must reuse the row minted at RUNNING")
        FakeValue runResult = runResults[0]
        assertEquals(AutomationExecutionSupport.STATUS_CANCELLED, runResult.statusEnumId,
                "the watched row must end CANCELLED — never left RUNNING for the reaper to flip to FAILED")
        assertNotNull(runResult.completedDate)
        assertEquals("Run cancelled by an operator.", runResult.errorMessage)
        assertEquals(runResult.reconciliationRunResultId, result.reconciliationRunResultId)
        FakeValue execution = ec.entity.rows["darpan.reconciliation.ReconciliationAutomationExecution"][0]
        assertEquals(AutomationExecutionSupport.STATUS_CANCELLED, execution.statusEnumId,
                "the execution row must be CANCELLED too, not FAILED")
        assertNotNull(execution.completedDate)
    }

    @Test
    void aCancelSurfacingAsAThrownPollFailureIsStillReportedCancelledNotFailed() {
        // Requirement 3 for the throw route. The poll/reconcile service can catch the cancel raised by its
        // own progress checkpoint and re-raise its own exception, so the runner never sees a
        // RunCancelledException. Without the outranking check this catch records FAILED, ALERTS the
        // operator that their own cancellation was a run failure, and re-throws so the caller reports a
        // crash. With it: CANCELLED, silent, and a normal return.
        FakeEc ec = fakeEc()
        seedSftpAutomation(ec)
        seedNotifyChatSpace(ec)
        ec.service.nextResult = [dataAvailable: false] as Map<String, Object>
        ec.service.onPollCall = {
            requestCancelMidRun(ec)
            throw new IllegalStateException("SFTP transport aborted")
        }
        List<Map<String, Object>> deliveries = []
        TenantNotificationSupport.setDeliveryHook { String deliveredWebhookUrl, Map<String, Object> payload ->
            deliveries << [webhookUrl: deliveredWebhookUrl, payload: payload]
            return [ok: true, statusCode: 200]
        }

        try {
            Map result = SftpAutomationSupport.runSftpFileAutomation(ec, [automationId: "AUTO_SFTP"])

            assertEquals(AutomationExecutionSupport.STATUS_CANCELLED, result.statusEnumId)
            FakeValue runResult = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"][0]
            assertEquals(AutomationExecutionSupport.STATUS_CANCELLED, runResult.statusEnumId)
            FakeValue execution = ec.entity.rows["darpan.reconciliation.ReconciliationAutomationExecution"][0]
            assertEquals(AutomationExecutionSupport.STATUS_CANCELLED, execution.statusEnumId)
            assertTrue(deliveries.isEmpty(),
                    "a cancelled run must not send a failure alert about the operator's own cancellation")
            assertNull(runResult.notifiedDate)
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }
    }

    @Test
    void aCancelLandingAsThePollGivesUpWithAnErrorIsStillReportedCancelledNotFailed() {
        // Requirement 3 for the route that never throws, which is the one runSavedRunDiff.groovy:1017
        // itself guards: a message-level error from the poll/reconcile chain is only a FLAG here, so the
        // catch above can never see it. The cancel therefore has to be outranked in the terminal decision
        // itself, or a stop that arrived while the poll was recording an error is written FAILED and
        // alerted as a run failure.
        //
        // Landing the click in that window needs the message facade's own read as the seam: the loop asks
        // "should I stop?" only AFTER the attempt's cancel checkpoint has already run and passed. The
        // sequence assertion below is what keeps this test honest — without it, a cancel stamped one step
        // earlier would be caught by the checkpoint and the test would pass while proving the throw path.
        FakeEc ec = fakeEc()
        seedSftpAutomation(ec)
        seedNotifyChatSpace(ec)
        ec.service.nextResult = [dataAvailable: false, statusMessage: "SFTP connection lost"] as Map<String, Object>
        List<String> sequence = []
        ec.entity.updateHook = { FakeValue value ->
            if (value.entityName == "darpan.reconciliation.ReconciliationRunResult" &&
                    value.statusEnumId == SftpAutomationSupport.AUTOMATION_STATUS_RUNNING) {
                sequence << "heartbeat"
            }
        }
        ec.service.onPollCall = {
            sequence << "poll"
            ec.message.addError("SFTP connection lost")
        }
        ec.message.onHasError = {
            if (sequence.contains("heartbeat") && !sequence.contains("cancel")) {
                sequence << "cancel"
                requestCancelMidRun(ec)
            }
        }
        List<Map<String, Object>> deliveries = []
        TenantNotificationSupport.setDeliveryHook { String deliveredWebhookUrl, Map<String, Object> payload ->
            deliveries << [webhookUrl: deliveredWebhookUrl, payload: payload]
            return [ok: true, statusCode: 200]
        }

        try {
            Map result = SftpAutomationSupport.runSftpFileAutomation(ec, [automationId: "AUTO_SFTP"])

            assertEquals(["poll", "heartbeat", "cancel"], sequence.take(3),
                    "the cancel must land AFTER the attempt's checkpoint, or this proves the throw path instead")
            assertEquals(AutomationExecutionSupport.STATUS_CANCELLED, result.statusEnumId)
            FakeValue runResult = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"][0]
            assertEquals(AutomationExecutionSupport.STATUS_CANCELLED, runResult.statusEnumId,
                    "a poll that gave up with an error under a pending cancel is a cancellation, not a failure")
            FakeValue execution = ec.entity.rows["darpan.reconciliation.ReconciliationAutomationExecution"][0]
            assertEquals(AutomationExecutionSupport.STATUS_CANCELLED, execution.statusEnumId)
            assertTrue(deliveries.isEmpty())
            assertNull(runResult.notifiedDate)
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }
    }

    @Test
    void aCancelledSftpRunPurgesTheNotifyMeSubscriptionItLeavesBehind() {
        // Cancellation notifies nobody, so nothing else will ever clean up a subscription taken while the
        // run was RUNNING — purgeRunSubscriptions only fires off a won notification claim, and this runner
        // never requeues, so there is no successor attempt to carry it to either. An orphan can never fire
        // AND pins its chat space against deletion forever. The subscriber's space is the ONLY destination
        // seeded here, so an empty delivery list means the silence is real rather than incidental.
        FakeEc ec = fakeEc()
        seedSftpAutomation(ec)
        seedSubscriberChatSpace(ec)
        ec.service.nextResult = [dataAvailable: false, statusMessage: "No SFTP files found"] as Map<String, Object>
        ec.service.onPollCall = {
            subscribeMidRun(ec, "USER_A", "CS_ME")
            requestCancelMidRun(ec)
        }
        SftpAutomationSupport.setRetrySleeper { long ignored -> }
        List<Map<String, Object>> deliveries = []
        TenantNotificationSupport.setDeliveryHook { String deliveredWebhookUrl, Map<String, Object> payload ->
            deliveries << [webhookUrl: deliveredWebhookUrl, payload: payload]
            return [ok: true, statusCode: 200]
        }

        try {
            SftpAutomationSupport.runSftpFileAutomation(ec, [automationId: "AUTO_SFTP"])

            FakeValue runResult = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"][0]
            assertEquals(AutomationExecutionSupport.STATUS_CANCELLED, runResult.statusEnumId)
            assertTrue(deliveries.isEmpty(), "a cancelled run stays silent — the operator already knows")
            assertNull(runResult.notifiedDate)
            assertTrue(ec.entity.rows["darpan.reconciliation.ReconciliationRunNotifySubscription"].isEmpty(),
                    "the subscription must not outlive the chain a cancel ends")
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
            SftpAutomationSupport.resetRetrySleeper()
        }
    }

    /**
     * Task 6: what pressing "Cancel run" on the live progress view does — cancel#ReconciliationRun calls
     * RunObservability.requestCancel, which stamps cancelRequestedDate on the row and returns true while
     * the run is still active. Driven through the real production entry point rather than by setting the
     * field directly, so the fixture cannot drift from what the button actually writes.
     */
    private static String requestCancelMidRun(FakeEc ec) {
        FakeValue liveRunResult = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"]
                .find { it.statusEnumId == SftpAutomationSupport.AUTOMATION_STATUS_RUNNING }
        if (liveRunResult == null) return null
        String runResultId = liveRunResult.reconciliationRunResultId
        assertTrue(RunObservability.requestCancel(ec, runResultId, "USER_A"),
                "the fixture must actually record a cancel request against the live run")
        return runResultId
    }

    /**
     * What a poller would see MID-RUN: called from inside the poll service call, i.e. after the execution
     * went RUNNING and long before any terminal write, so assertions can be about the in-flight state
     * rather than the end state (which the old design got right too).
     */
    private static Map<String, Object> snapshotLiveRun(FakeEc ec) {
        FakeValue execution = ec.entity.rows["darpan.reconciliation.ReconciliationAutomationExecution"]
                .find { it.statusEnumId == SftpAutomationSupport.AUTOMATION_STATUS_RUNNING }
        FakeValue runResult = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"]
                .find { it.statusEnumId == SftpAutomationSupport.AUTOMATION_STATUS_RUNNING }
        return [
                executionStatus       : execution?.statusEnumId,
                executionRunResultId  : execution?.reconciliationRunResultId,
                runResultId           : runResult?.reconciliationRunResultId,
                runResultStatus       : runResult?.statusEnumId,
                runResultStartedDate  : runResult?.startedDate,
                runResultHeartbeatDate: runResult?.lastHeartbeatDate,
                runResultCompletedDate: runResult?.completedDate,
                runResultNotifiedDate : runResult?.notifiedDate,
                runResultTenant       : runResult?.companyUserGroupId,
        ] as Map<String, Object>
    }

    /** What "Notify me" does while the live view is open: a subscription against the RUNNING row. */
    private static void subscribeMidRun(FakeEc ec, String userId, String chatSpaceId) {
        FakeValue liveRunResult = ec.entity.rows["darpan.reconciliation.ReconciliationRunResult"]
                .find { it.statusEnumId == SftpAutomationSupport.AUTOMATION_STATUS_RUNNING }
        if (liveRunResult == null) return
        String runResultId = liveRunResult.reconciliationRunResultId
        boolean already = ec.entity.rows["darpan.reconciliation.ReconciliationRunNotifySubscription"]
                .any { it.reconciliationRunResultId == runResultId && it.userId == userId }
        if (already) return
        ec.entity.add("darpan.reconciliation.ReconciliationRunNotifySubscription", [
                reconciliationRunResultId: runResultId,
                userId                   : userId,
                chatSpaceId              : chatSpaceId,
        ])
    }

    private static Map<String, Object> successfulPollResult() {
        return [
                dataAvailable      : true,
                statusMessage      : "Complete",
                file1Source        : "sftp://source-a:22/incoming/shopify",
                file2Source        : "sftp://source-b:22/incoming/netsuite",
                file1SelectedName  : "shopify.csv",
                file2SelectedName  : "netsuite.csv",
                file1StagedLocation: "/tmp/shopify.csv",
                file2StagedLocation: "/tmp/netsuite.csv",
                reconciliationType : "ORDER",
                diffLocation       : "reconciliation-runs/AUTO_SFTP/20260501/result.json",
                diffFileName       : "result.json",
                differenceCount    : 3,
                onlyInFile1Count   : 1,
                onlyInFile2Count   : 2,
                validationErrors   : [],
                processingWarnings : [],
        ] as Map<String, Object>
    }

    /**
     * A chat space for a notify-me SUBSCRIBER, deliberately without giving the automation a chatSpaceId
     * of its own — so the subscription is the only route an alert can take and a delivery proves the
     * subscriber path specifically rather than incidentally.
     */
    private static String seedSubscriberChatSpace(FakeEc ec) {
        String webhookUrl = "https://chat.googleapis.com/v1/spaces/SUBSCRIBER_SPACE/messages?key=test-key&token=test-token"
        ec.entity.add("darpan.reconciliation.TenantChatSpace", [
                chatSpaceId         : "CS_ME",
                companyUserGroupId  : "TENANT_A",
                spaceName           : "Mine",
                googleChatWebhookUrl: webhookUrl,
                isActive            : "Y",
        ])
        return webhookUrl
    }

    /** Gives the automation a destination so "did this run notify?" is an observable fact. */
    private static String seedNotifyChatSpace(FakeEc ec) {
        String webhookUrl = "https://chat.googleapis.com/v1/spaces/TENANT_A_SPACE/messages?key=test-key&token=test-token"
        ec.entity.add("darpan.reconciliation.TenantChatSpace", [
                chatSpaceId         : "CS_OPS",
                companyUserGroupId  : "TENANT_A",
                spaceName           : "Ops",
                googleChatWebhookUrl: webhookUrl,
                isActive            : "Y",
        ])
        ec.entity.rows["darpan.reconciliation.ReconciliationAutomation"]
                .find { it.automationId == "AUTO_SFTP" }.put("chatSpaceId", "CS_OPS")
        return webhookUrl
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
        // System-write tenant assertion (MACH P0): execution/run-result writes validate the
        // automation's companyUserGroupId against moqui.security.UserGroup before stamping rows.
        ec.entity.add("moqui.security.UserGroup", [
                userGroupId    : "TENANT_A",
                groupTypeEnumId: "UgtDarpanCompany",
                description    : "Tenant A",
        ])
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
        // Task 2d fix round 1: mirror the real build#RunCompletedPayload template's FAILURE rendering
        // (see ReconciliationNotificationServices.xml, and the identical harness in
        // AutomationExecutionSupportTests) so the new SFTP failure-alert tests can assert that what got
        // delivered is genuinely the failure payload — carrying the reason — and not a success one.
        boolean runFailed = ((params.statusEnumId)?.toString()?.trim()) == SftpAutomationSupport.AUTOMATION_STATUS_FAILED
        String headerPrefix = runFailed ? "Darpan run completed WITH ISSUES: " : "Darpan run completed: "
        List<String> lines = ["${headerPrefix}${runName}".toString()]
        if (runFailed) lines << "⚠ Status: FAILED — the ruleset did not fully evaluate; results may be incomplete.".toString()
        String terminationReasonValue = ((params.terminationReason)?.toString()?.trim())
        if (terminationReasonValue) lines << "⚠ ${terminationReasonValue}".toString()
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
        // Task 2d: observation point for the heartbeat/phase-boundary tests — fired on every .update(),
        // mirroring AutomationExecutionSupportTests' harness so the two paths are tested the same way.
        Closure updateHook = null
        // Task 2d fix round 1: injection point for a row insert that fails, so the guarded
        // execution-row create can be exercised without a real database.
        Closure createHook = null

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
                    // A null condition value means IS NULL (mirrors Moqui EntityFind semantics) — a
                    // missing/absent key on the row map already reads as null via Groovy Map.get(), so
                    // the same equality check above is IS-NULL-compatible with no special-casing.
                    value[fieldName] == expected
                }
            }
        }

        // Atomic claim-then-deliver support (Task 6 fix round 1): bulk-update every row matching the
        // accumulated conditions and report how many rows were touched, mirroring Moqui's
        // EntityFind.updateAll(Map) contract (long row count).
        long updateAll(Map<String, Object> fieldsToSet) {
            List<FakeValue> matchedRows = list()
            matchedRows.each { FakeValue row ->
                fieldsToSet.each { fieldName, value -> row.set(fieldName, value) }
                row.updated = true
            }
            return matchedRows.size() as long
        }
    }

    private static class FakeValue extends LinkedHashMap<String, Object> {
        String entityName
        FakeEntityFacade entity
        boolean created
        boolean updated
        boolean deleted

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
            entity?.createHook?.call(this)
            created = true
            entity.rows[entityName] << this
            return this
        }

        FakeValue update() {
            entity?.updateHook?.call(this)
            updated = true
            return this
        }

        // TenantNotificationSupport.purgeRunSubscriptions calls .delete() on rows it already loaded;
        // mirror real Moqui EntityValue.delete() semantics (self-removes from the backing store) so the
        // Task 2d subscription-cleanup tests can assert on it.
        FakeValue delete() {
            entity?.rows?.get(entityName)?.remove(this)
            deleted = true
            return this
        }
    }

    private static class FakeServiceFacade {
        Map<String, Object> nextResult = [:]
        List<FakeServiceCall> calls = []
        FakeEc ec
        // Task 2d: fired on every poll#SftpAndReconcile call, i.e. from INSIDE the run, so a test can
        // observe the live rows mid-flight (which is where the "Run now" UI poll looks) instead of only
        // the end state, and can drive "click Notify me while it is running".
        Closure onPollCall = null
        // Fix round 1 (review finding 2): forces the build#RunCompletedPayload call itself to throw —
        // that call has no internal try/catch (unlike the delivery loop), so this is the genuine
        // unguarded-escape mechanism sftpNotifyFailureDoesNotOverwriteTerminalStatus needs to regression
        // guard finding 1's best-effort wrap.
        boolean explodeOnBuildPayload = false

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
                if (service.explodeOnBuildPayload) throw new RuntimeException("payload build failed")
                return buildNotificationPayload(service.ec, params)
            }
            if (serviceName == "reconciliation.ReconciliationAutomationServices.poll#SftpAndReconcile") {
                service.onPollCall?.call()
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
        /**
         * Task 6: fired on every hasError() read. pollSftpUntilAvailable's loop condition asks this
         * question only AFTER the attempt's cancel checkpoint has already run and passed, so it is the
         * one seam a test can use to land an operator's click inside the narrow window the non-throwing
         * outrank guard exists for — a cancel stamped after the final checkpoint, on a run already
         * heading for a FAILED terminal it never threw for.
         */
        Closure onHasError = null

        boolean hasError() {
            onHasError?.call()
            return error
        }

        void addError(String ignored) {
            error = true
        }
    }
}
