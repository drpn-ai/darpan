package darpan.reconciliation.automation

import darpan.common.DarpanEntityConstants
import darpan.reconciliation.notification.TenantNotificationSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Terminal automation failures must reach the tenant's chat space.
 *
 * <p>UAT 2026-07-31: every scheduled run failed at {@code prepare#RuleSetCompareScope} and NOBODY was
 * told. The failure notification in {@code executeAutomation}'s catch was guarded by
 * {@code if (mintedRunResultId)} — only reachable when the run had already produced output and
 * persisted a {@code ReconciliationRunResult}. A run that dies during reconcile never mints that row,
 * so the whole notification path (and the run-history row) was skipped.</p>
 *
 * <p>Two terminal states were silent:</p>
 * <ul>
 *   <li>permanent failure before any output — the observed production case;</li>
 *   <li>{@code AUT_STAT_DEAD_LETTER}, i.e. retries exhausted and the automation has given up.</li>
 * </ul>
 *
 * <p>A failure that is going to be retried is NOT terminal and must stay quiet.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutomationFailureNotificationTests {

    private static final String AUTOMATION_ID = "AUTO_FAIL_NOTIFY"
    private static final String TENANT = "KREWE"
    private static final String CHAT_SPACE_ID = "CS_FAIL_NOTIFY"
    private static final String TEST_USER_ID = "TEST_CUSTOMER_USER"

    private ExecutionContext ec
    private List<Map<String, Object>> deliveries

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "automation-failure-notification")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompareScopeFixtures(ec)
        seedChatSpace()
        seedAutomation()
    }

    @AfterAll
    void cleanup() {
        AutomationExecutionSupport.resetExecutionHooks()
        TenantNotificationSupport.resetDeliveryHook()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void prepare() {
        ec.message.clearErrors()
        AutomationExecutionSupport.resetExecutionHooks()
        stubSourceExtractorWithData()
        deliveries = []
        TenantNotificationSupport.setDeliveryHook { String webhookUrl, Map<String, Object> payload ->
            deliveries << [webhookUrl: webhookUrl, payload: payload]
            return [ok: true, statusCode: 200]
        }
    }

    @AfterEach
    void reset() {
        AutomationExecutionSupport.resetExecutionHooks()
        TenantNotificationSupport.resetDeliveryHook()
    }

    // -----------------------------------------------------------------------
    // The production gap
    // -----------------------------------------------------------------------

    /**
     * The observed UAT failure shape: reconcile throws {@link IllegalArgumentException} (classified
     * permanent, so terminal FAILED with no retry) before any run-result row exists.
     */
    @Test
    void permanentFailureBeforeAnyOutputNotifiesTheTenant() {
        failReconcileWith(new IllegalArgumentException(
                "RuleSet DARPAN_TEST_COMPARE_RS was not found or is not accessible in your active tenant"))

        Map<String, Object> result = AutomationExecutionSupport.executeAutomation(ec, [
                automationId     : AUTOMATION_ID,
                scheduledFireTime: Timestamp.valueOf("2026-06-01 10:00:00"),
        ])

        assertEquals(1, result.failedCount, "Result: ${result}")
        assertEquals(1, deliveries.size(),
                "A terminal automation failure must notify the tenant even when no output was produced")
        String text = deliveries[0].payload.text as String
        assertTrue(text.toUpperCase().contains("FAIL"),
                "Notification must read as a failure. Got: ${text}")
    }

    /**
     * The standard payload prints a Details block ("Missing from …: 0 / Mismatches: 0"). For a run
     * that died before producing any result, those zeros read as a clean sync — worse than silence.
     * A no-output failure must not report counts it never computed, so it takes the service's
     * noOutput branch instead of the verdict renderer.
     */
    @Test
    void failureNotificationDoesNotReadLikeACleanRun() {
        failReconcileWith(new IllegalArgumentException("extract stage exploded"))

        AutomationExecutionSupport.executeAutomation(ec, [
                automationId     : AUTOMATION_ID,
                scheduledFireTime: Timestamp.valueOf("2026-06-06 10:00:00"),
        ])

        assertEquals(1, deliveries.size())
        String text = deliveries[0].payload.text as String
        assertFalse(text.contains("Differences:"),
                "A run that produced no result must not report a difference count. Got: ${text}")
        assertFalse(text.contains("run completed"),
                "A run that never completed must not say it completed. Got: ${text}")
    }

    /** The failed run must also be visible in run history, not just in the chat space. */
    @Test
    void permanentFailureIsRecordedAsAFailedRunResult() {
        failReconcileWith(new IllegalArgumentException("compare scope exploded"))

        AutomationExecutionSupport.executeAutomation(ec, [
                automationId     : AUTOMATION_ID,
                scheduledFireTime: Timestamp.valueOf("2026-06-02 10:00:00"),
        ])

        def runResult = findLatestRunResult()
        assertNotNull(runResult, "A terminal failure must leave a run-result row for run history")
        assertEquals(AutomationExecutionSupport.STATUS_FAILED, runResult.statusEnumId)
        assertTrue((runResult.errorMessage as String)?.contains("compare scope exploded"),
                "The run-result row must carry the failure reason. Got: ${runResult.errorMessage}")
    }

    /** Retries exhausted — the automation has given up, which is exactly when someone must be told. */
    @Test
    void deadLetteredExecutionNotifiesTheTenant() {
        Timestamp now = Timestamp.valueOf("2026-06-03 10:00:00")
        seedExhaustedRetryExecution(Timestamp.valueOf("2026-06-03 09:00:00"))

        AutomationExecutionSupport.reprocessDueRetries(ec, now, 100, [:])

        assertEquals(1, deliveries.size(),
                "Dead-lettering an automation execution must notify the tenant")
        String text = deliveries[0].payload.text as String
        assertTrue(text.toUpperCase().contains("FAIL"), "Got: ${text}")
    }

    // -----------------------------------------------------------------------
    // What must NOT change
    // -----------------------------------------------------------------------

    /** A failure queued for retry is not terminal — notifying would cry wolf on every blip. */
    @Test
    void retryableFailureDoesNotNotifyYet() {
        failReconcileWith(new RuntimeException("upstream timed out"))

        AutomationExecutionSupport.executeAutomation(ec, [
                automationId     : AUTOMATION_ID,
                scheduledFireTime: Timestamp.valueOf("2026-06-04 10:00:00"),
        ])

        def execution = findExecution(Timestamp.valueOf("2026-06-04 10:00:00"))
        assertEquals(AutomationExecutionSupport.STATUS_PENDING, execution.statusEnumId,
                "Precondition: a transient failure must be queued for retry")
        assertEquals(0, deliveries.size(),
                "A run that will be retried must not report itself as failed yet")
    }

    /** Notification is best-effort: it must never replace or mask the real failure. */
    @Test
    void notificationFailureDoesNotMaskTheRunFailure() {
        failReconcileWith(new IllegalArgumentException("original failure"))
        TenantNotificationSupport.setDeliveryHook { String webhookUrl, Map<String, Object> payload ->
            throw new IllegalStateException("chat webhook is down")
        }

        Map<String, Object> result = AutomationExecutionSupport.executeAutomation(ec, [
                automationId     : AUTOMATION_ID,
                scheduledFireTime: Timestamp.valueOf("2026-06-05 10:00:00"),
        ])

        assertEquals(1, result.failedCount,
                "A broken chat webhook must not change the outcome of the run. Result: ${result}")
        def execution = findExecution(Timestamp.valueOf("2026-06-05 10:00:00"))
        assertTrue((execution.errorMessage as String)?.contains("original failure"),
                "The recorded error must be the run's, not the notifier's. Got: ${execution.errorMessage}")
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void failReconcileWith(Throwable failure) {
        AutomationExecutionSupport.setReconcileRunner { Object ecArg, Object automation, Object file1Source,
                Object file2Source, Map<String, Object> file1Result, Map<String, Object> file2Result,
                Map<String, Object> window, Map<String, Object> params ->
            throw failure
        }
    }

    private void stubSourceExtractorWithData() {
        AutomationExecutionSupport.setSourceExtractor { def ignoredEc, def ignoredAutomation, def source,
                Map<String, Object> ignoredWindow, Map<String, Object> ignoredParams ->
            String fileSide = source.get("fileSide")
            return [
                    dataAvailable : true,
                    fileLocation  : fileSide == AutomationExecutionSupport.FILE_SIDE_1 ?
                            "component://darpan/data/test/test-orders-1.json" :
                            "component://darpan/data/test/test-orders-2.json",
                    fileName      : "${fileSide}.json".toString(),
                    fileTypeEnumId: "DftJson",
                    recordCount   : 3,
            ]
        }
    }

    private def findExecution(Timestamp scheduledDate) {
        return ec.entity.find(DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION)
                .condition("automationId", AUTOMATION_ID)
                .condition("scheduledDate", scheduledDate)
                .disableAuthz().useCache(false).one()
    }

    private def findLatestRunResult() {
        return ec.entity.find(DarpanEntityConstants.RECONCILIATION_RUN_RESULT)
                .condition("companyUserGroupId", TENANT)
                .orderBy("-reconciliationRunResultId")
                .disableAuthz().useCache(false).list()?.getAt(0)
    }

    private void seedExhaustedRetryExecution(Timestamp nextRetryDate) {
        ec.entity.makeValue(DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION)
                .setAll([
                        automationId      : AUTOMATION_ID,
                        companyUserGroupId: TENANT,
                        createdByUserId   : TEST_USER_ID,
                        statusEnumId      : AutomationExecutionSupport.STATUS_PENDING,
                        scheduledDate     : Timestamp.valueOf("2026-06-03 08:00:00"),
                        retryCount        : 3,
                        maxRetryCount     : 3,
                        nextRetryDate     : nextRetryDate,
                        errorMessage      : "upstream timed out",
                        createdDate       : ec.user.nowTimestamp,
                        lastUpdatedDate   : ec.user.nowTimestamp,
                ])
                .setSequencedIdPrimary()
                .create()
    }

    private void seedChatSpace() {
        upsertEntityValue(DarpanEntityConstants.TENANT_CHAT_SPACE, [chatSpaceId: CHAT_SPACE_ID], [
                chatSpaceId         : CHAT_SPACE_ID,
                companyUserGroupId  : TENANT,
                spaceName           : "Ops",
                googleChatWebhookUrl: "https://chat.googleapis.com/v1/spaces/AAA/messages?key=k&token=t",
                isActive            : "Y",
                createdByUserId     : TEST_USER_ID,
                createdDate         : ec.user.nowTimestamp,
                lastUpdatedDate     : ec.user.nowTimestamp,
        ])
    }

    private void seedAutomation() {
        upsertEntityValue("darpan.reconciliation.ReconciliationAutomation", [automationId: AUTOMATION_ID], [
                automationId            : AUTOMATION_ID,
                automationName          : "Failure Notification",
                companyUserGroupId      : TENANT,
                createdByUserId         : TEST_USER_ID,
                chatSpaceId             : CHAT_SPACE_ID,
                inputModeEnumId         : AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE,
                savedRunId              : "DARPAN_TEST_COMPARE_RS",
                savedRunType            : "ruleset",
                ruleSetId               : "DARPAN_TEST_COMPARE_RS",
                compareScopeId          : "DARPAN_TEST_ORDER_JSON_SCOPE",
                relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_PREVIOUS_DAY,
                relativeWindowCount     : 1,
                windowTimeZone          : "UTC",
                isActive                : "Y",
                createdDate             : ec.user.nowTimestamp,
                lastUpdatedDate         : ec.user.nowTimestamp,
        ])
        [AutomationExecutionSupport.FILE_SIDE_1, AutomationExecutionSupport.FILE_SIDE_2].each { String fileSide ->
            upsertEntityValue("darpan.reconciliation.ReconciliationAutomationSource",
                    [automationId: AUTOMATION_ID, fileSide: fileSide], [
                    automationId      : AUTOMATION_ID,
                    fileSide          : fileSide,
                    companyUserGroupId: TENANT,
                    createdByUserId   : TEST_USER_ID,
                    sourceTypeEnumId  : AutomationExecutionSupport.AUTOMATION_SOURCE_API,
                    systemEnumId      : fileSide == AutomationExecutionSupport.FILE_SIDE_1 ? "SHOPIFY" : "OMS",
                    fileTypeEnumId    : "DftJson",
                    createdDate       : ec.user.nowTimestamp,
                    lastUpdatedDate   : ec.user.nowTimestamp,
            ])
        }
    }

    private void upsertEntityValue(String entityName, Map<String, Object> pkFields, Map<String, Object> fields) {
        def existing = ec.entity.find(entityName).condition(pkFields).disableAuthz().useCache(false).one()
        if (existing != null) return
        ec.entity.makeValue(entityName).setAll(fields).create()
    }
}
