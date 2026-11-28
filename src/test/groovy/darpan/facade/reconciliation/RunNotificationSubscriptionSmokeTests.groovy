package darpan.facade.reconciliation

import darpan.config.DarpanSettings
import darpan.ops.ErrorChatSupport
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.notification.TenantNotificationSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SharedMoqui
class RunNotificationSubscriptionSmokeTests {
    private static final String TENANT_ID = "KREWE"
    static final String WEBHOOK = "https://chat.googleapis.com/v1/spaces/AAAA1234ZZZZ/messages?key=k&token=t"

    private ExecutionContext ec
    private final AtomicInteger runSequence = new AtomicInteger()

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "run-notification-subscription-facade-smoke")
        // ReconciliationRunResult.statusEnumId FKs to moqui.basic.Enumeration; pull the seeded
        // AutomationStatus enums (same convention as AutomationFacadeSmokeTests).
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
        // DAR-BE-362: the run-failed line is read from its copy block; create#CsvRun needs the system and file-type enums.
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/OpsNoticeSeedData.xml")
        ReconciliationSmokeTestSupport.seedBaseCompareRuleSet(ec)
    }

    @AfterAll
    void cleanup() {
        AutomationExecutionSupport.resetForTests()
        TenantNotificationSupport.resetDeliveryHook()
        ErrorChatSupport.resetForTests()
        DarpanSettings.resetForTests()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void resetTenantScope() {
        // seedCompanyScope logs in as the KREWE-tenant test user, seeds the KREWE UserGroup with
        // editor permission, sets it as the active tenant, and clears any prior message state.
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        // @TestInstance(PER_CLASS) shares this ec/session (and the underlying user preference row)
        // across every test method, and JUnit 5's default method ordering is
        // deterministic-but-unspecified. subscribeWithoutDefaultSetsNeedsFlag's "no default set yet"
        // premise only holds if a prior test (subscribeUnsubscribeRoundTripAndStatusFlag) hasn't
        // already saved a default for this user+tenant, so clear it unconditionally before every
        // test rather than relying on run order.
        callFacade("facade.SettingsFacadeServices.save#UserNotificationDefault", [chatSpaceId: ""])
    }

    @Test
    void subscribeWithoutDefaultSetsNeedsFlag() {
        String runId = seedRun("AUT_STAT_RUNNING")

        def subscribe = callFacade("facade.ReconciliationFacadeServices.subscribe#RunNotification",
                [reconciliationRunResultId: runId])
        assertFalse(subscribe.ok as boolean)
        assertTrue(subscribe.needsDefaultChatSpace as boolean)
        assertFalse(subscribe.subscribed as boolean)
        assertNull(findSubscription(runId))

        // ServiceCallSyncImpl.ignorePreviousError defaults to false, so a prior-call error blocks the
        // next service invocation outright unless cleared (same convention as
        // TenantChatSpaceFacadeSmokeTests, which clears errors after every intentionally-failed call).
        ec.message.clearErrors()
    }

    @Test
    void subscribeUnsubscribeRoundTripAndStatusFlag() {
        String runId = seedRun("AUT_STAT_RUNNING")

        def save = callFacade("facade.SettingsFacadeServices.save#TenantChatSpace",
                [spaceName: "Notify space", googleChatWebhookUrl: WEBHOOK, isActive: true])
        assertTrue(save.ok as boolean)
        String chatSpaceId = save.chatSpace.chatSpaceId

        def saveDefault = callFacade("facade.SettingsFacadeServices.save#UserNotificationDefault",
                [chatSpaceId: chatSpaceId])
        assertTrue(saveDefault.ok as boolean)

        def subscribe = callFacade("facade.ReconciliationFacadeServices.subscribe#RunNotification",
                [reconciliationRunResultId: runId])
        assertTrue(subscribe.ok as boolean)
        assertTrue(subscribe.subscribed as boolean)
        assertFalse(subscribe.needsDefaultChatSpace as boolean)
        assertEquals("Notify space", subscribe.chatSpaceName)

        def status = callFacade("facade.ReconciliationFacadeServices.get#ReconciliationRunStatus",
                [reconciliationRunResultId: runId])
        assertTrue(status.ok as boolean)
        assertTrue(status.mySubscription as boolean)
        assertEquals("Notify space", status.mySubscriptionSpaceName)

        // Re-subscribing is a store# upsert on the (reconciliationRunResultId, userId) PK — still one row.
        def resubscribe = callFacade("facade.ReconciliationFacadeServices.subscribe#RunNotification",
                [reconciliationRunResultId: runId])
        assertTrue(resubscribe.ok as boolean)
        assertTrue(resubscribe.subscribed as boolean)
        assertEquals(1L, countSubscriptions(runId))

        def unsubscribe = callFacade("facade.ReconciliationFacadeServices.unsubscribe#RunNotification",
                [reconciliationRunResultId: runId])
        assertTrue(unsubscribe.ok as boolean)
        assertFalse(unsubscribe.subscribed as boolean)
        assertNull(findSubscription(runId))

        def statusAfter = callFacade("facade.ReconciliationFacadeServices.get#ReconciliationRunStatus",
                [reconciliationRunResultId: runId])
        assertTrue(statusAfter.ok as boolean)
        assertFalse(statusAfter.mySubscription as boolean)
    }

    @Test
    void subscribeRejectedOnCompletedRun() {
        String runId = seedRun("AUT_STAT_SUCCESS")

        def subscribe = callFacade("facade.ReconciliationFacadeServices.subscribe#RunNotification",
                [reconciliationRunResultId: runId])
        assertFalse(subscribe.ok as boolean)
        assertTrue((subscribe.errors ?: []).join(" ").contains("already completed"))

        // ServiceCallSyncImpl.ignorePreviousError defaults to false, so a prior-call error blocks the
        // next service invocation outright unless cleared (same convention as
        // TenantChatSpaceFacadeSmokeTests, which clears errors after every intentionally-failed call).
        ec.message.clearErrors()
    }

    @Test
    void unsubscribingWithNothingToRemoveReportsAnErrorInsteadOfSuccess() {
        // Task 6 Part B. The miss branch used to silently do nothing, set subscribed=false and return
        // ok=true with no message — indistinguishable, to any caller, from having actually removed a
        // subscription. That is only harmless if a miss always means "already unsubscribed", and since
        // retry carry-forward it does not (see the next test).
        String runId = seedRun("AUT_STAT_RUNNING")

        def unsubscribe = callFacade("facade.ReconciliationFacadeServices.unsubscribe#RunNotification",
                [reconciliationRunResultId: runId])

        assertFalse(unsubscribe.ok as boolean, "nothing was removed, so this must not report success")
        assertTrue((unsubscribe.errors ?: []).join(" ").contains("nothing was cancelled"))

        // ServiceCallSyncImpl.ignorePreviousError defaults to false, so a prior-call error blocks the
        // next service invocation outright unless cleared.
        ec.message.clearErrors()
    }

    @Test
    void unsubscribingFromAnAttemptWhoseSubscriptionMovedToARetryDoesNotClaimSuccess() {
        // The exact bug: TenantNotificationSupport.reassignRunSubscriptions carries a notify-me
        // subscription forward onto the retry's run-result row (create-then-delete), so an operator whose
        // page is still holding the OLD attempt's id clicks "stop notifying me" and hits the miss branch.
        // It used to answer ok=true while the moved subscription went on to fire at the retry's terminal
        // outcome.
        String attemptOneRunId = seedRun("AUT_STAT_RUNNING")
        seedDefaultChatSpace("Retry space")

        def subscribe = callFacade("facade.ReconciliationFacadeServices.subscribe#RunNotification",
                [reconciliationRunResultId: attemptOneRunId])
        assertTrue(subscribe.ok as boolean)
        assertEquals(1L, countSubscriptions(attemptOneRunId))

        // The attempt fails transiently — its row goes FAILED without ever notifying — and the re-drive
        // mints a fresh row and carries the subscription onto it, exactly as the automation runner does.
        setRunStatus(attemptOneRunId, "AUT_STAT_FAILED")
        String attemptTwoRunId = seedRun("AUT_STAT_RUNNING")
        assertEquals(1, TenantNotificationSupport.reassignRunSubscriptions(ec, attemptOneRunId, attemptTwoRunId))
        assertEquals(0L, countSubscriptions(attemptOneRunId))
        assertEquals(1L, countSubscriptions(attemptTwoRunId))

        def unsubscribe = callFacade("facade.ReconciliationFacadeServices.unsubscribe#RunNotification",
                [reconciliationRunResultId: attemptOneRunId])

        assertFalse(unsubscribe.ok as boolean,
                "the subscription is still live on the successor row — reporting success here is the lie")
        assertTrue((unsubscribe.errors ?: []).join(" ").contains("retried"))
        // Pinned deliberately: the subscription IS still live and WILL still fire. Nothing stored links
        // attempt 1's run-result id to attempt 2's — reassignRunSubscriptions leaves no trail and the
        // execution row has already been repointed — so this service cannot follow the chain; it can only
        // stop claiming to have done something it did not do. Closing the gap needs new state; when that
        // lands, this is the assertion that must change.
        assertEquals(1L, countSubscriptions(attemptTwoRunId))

        ec.message.clearErrors()
    }

    @Test
    void notifyRunCompletedPurgesSubscriptionAndClearsStatusFlag() {
        // Final-review fix, finding 1: exercised through the real Moqui boot this class already pays
        // for — subscribe while RUNNING, flip to a terminal status the way a real run completion
        // would, then call the same TenantNotificationSupport.notifyRunCompleted the automation/SFTP/
        // reaper call sites use. mySubscription must flip back to false and the subscription row must
        // be gone — not just left around forever pointing at a run that already notified.
        String runId = seedRun("AUT_STAT_RUNNING")

        def save = callFacade("facade.SettingsFacadeServices.save#TenantChatSpace",
                [spaceName: "Completion space", googleChatWebhookUrl: WEBHOOK, isActive: true])
        assertTrue(save.ok as boolean)
        String chatSpaceId = save.chatSpace.chatSpaceId

        def saveDefault = callFacade("facade.SettingsFacadeServices.save#UserNotificationDefault",
                [chatSpaceId: chatSpaceId])
        assertTrue(saveDefault.ok as boolean)

        def subscribe = callFacade("facade.ReconciliationFacadeServices.subscribe#RunNotification",
                [reconciliationRunResultId: runId])
        assertTrue(subscribe.ok as boolean)
        assertEquals(1L, countSubscriptions(runId))

        def statusWhileRunning = callFacade("facade.ReconciliationFacadeServices.get#ReconciliationRunStatus",
                [reconciliationRunResultId: runId])
        assertTrue(statusWhileRunning.mySubscription as boolean)

        ec.entity.find("darpan.reconciliation.ReconciliationRunResult")
                .condition("reconciliationRunResultId", runId)
                .disableAuthz()
                .one()
                .set("statusEnumId", "AUT_STAT_SUCCESS")
                .update()

        TenantNotificationSupport.setDeliveryHook { String url, Map payload -> [ok: true, statusCode: 200] }
        try {
            Map<String, Object> notifyResult = TenantNotificationSupport.notifyRunCompleted(ec, [
                    reconciliationRunResultId: runId,
                    companyUserGroupId       : TENANT_ID,
                    statusEnumId             : "AUT_STAT_SUCCESS",
            ])
            assertTrue((boolean) notifyResult.attempted)
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }

        assertEquals(0L, countSubscriptions(runId))
        assertNull(findSubscription(runId))

        def statusAfterNotify = callFacade("facade.ReconciliationFacadeServices.get#ReconciliationRunStatus",
                [reconciliationRunResultId: runId])
        assertTrue(statusAfterNotify.ok as boolean)
        assertFalse(statusAfterNotify.mySubscription as boolean)
    }

    @Test
    void failedInteractiveRunPostsOneCardToDarpanAndNoErrorToClients() {
        // DAR-BE-362, end to end through the facade: a saved run that fails after its run row exists (here: the saved run
        // is missing) posts Darpan one FAILED card carrying the error; its subscriber gets the neutral line and never the error.
        String chatSpaceId = seedDefaultChatSpace("Missing run space")
        Map outcome = runAndWatch("BE362_MISSING_RUN", chatSpaceId, [
                file1Name: "orders-1.csv", file1Text: "order_id\nA100\n", file2Name: "orders-2.csv", file2Text: "order_id\nA200\n"])
        String rawError = outcome.rawError
        assertNotNull(rawError, "the run must report its error to the person who ran it")

        assertEquals(1, outcome.cards.size(), "one card for Darpan")
        Map card = outcome.cards[0]
        assertEquals("FAILED", card.kind)
        assertEquals("manual", card.trigger)
        assertEquals(false, card.scheduled)
        assertEquals(TENANT_ID, card.tenantId)
        assertTrue(card.errorFirstLine.toString().contains(rawError), "the card carries the error: ${card.errorFirstLine}")
        assertEquals("savedRun:BE362_MISSING_RUN", card.subjectKey)
        // no saved run exists to name, so the line names its id, as before
        assertEquals([[text: "BE362_MISSING_RUN did not finish. Darpan has been alerted and is working on a fix."]], outcome.deliveries)
        assertFalse(outcome.deliveries.toString().contains(rawError), "the subscriber never sees the error")

        def run = outcome.run
        assertEquals("AUT_STAT_FAILED", run.statusEnumId)
        assertNotNull(run.notifiedDate, "the failed run is claimed, so nothing notifies it twice")
        assertEquals(0L, countSubscriptions(run.reconciliationRunResultId as String))
    }

    @Test
    void aRunThatFailsAfterItResolvesIsNamedByItsSavedRun() {
        // DAR-BE-362: runResult is still null when a run fails before its compare (here: no second file), and the card and the
        // subscriber's line name the saved run, not its id.
        String chatSpaceId = seedDefaultChatSpace("Named run space")
        String savedRunId = createCsvRun("BE362 named run")
        Map outcome = runAndWatch(savedRunId, chatSpaceId, [file1Name: "orders-1.csv", file1Text: "order_id\nA100\n"])
        assertNotNull(outcome.rawError)
        assertEquals(1, outcome.cards.size())
        assertEquals("BE362 named run", outcome.cards[0].automationName)
        assertEquals([[text: "BE362 named run did not finish. Darpan has been alerted and is working on a fix."]], outcome.deliveries)
    }

    @Test
    void aRunThatThrowsIsToldLikeAnyFailure() {
        // DAR-BE-362: an exception out of the run (here the run row refuses a 400-character file name) ends in the outer catch:
        // the run still ends FAILED, Darpan gets the card, the subscriber the neutral line, and the subscription goes.
        String chatSpaceId = seedDefaultChatSpace("Thrown run space")
        String savedRunId = createCsvRun("BE362 thrown run")
        Map outcome = runAndWatch(savedRunId, chatSpaceId, [
                file1Name: ("x" * 400) + ".csv", file1Text: "order_id\nA100\n", file2Name: "orders-2.csv", file2Text: "order_id\nA200\n"])
        assertNotNull(outcome.thrown ?: outcome.rawError, "the run fails")
        assertEquals(1, outcome.cards.size(), "one card for Darpan")
        Map card = outcome.cards[0]
        assertEquals("FAILED", card.kind)
        assertEquals("BE362 thrown run", card.automationName)
        String reason = card.errorFirstLine.toString()
        assertTrue(reason.length() > 0)
        assertEquals([[text: "BE362 thrown run did not finish. Darpan has been alerted and is working on a fix."]], outcome.deliveries)
        assertFalse(outcome.deliveries.toString().contains("xxxxxxxxxx"), "the subscriber never sees the error")
        assertEquals("AUT_STAT_FAILED", outcome.run.statusEnumId)
        assertNotNull(outcome.run.notifiedDate, "the thrown run is claimed, so nothing notifies it twice")
        assertEquals(0L, countSubscriptions(outcome.run.reconciliationRunResultId as String))
    }

    @Test
    void aRunWhoseRulesFailedTellsSubscribersOnlyTheNeutralLine() {
        // DAR-BE-362: rules that did not fully evaluate leave ec.message clean, so the run reaches its notify stage; that stage
        // sends the error path (card + neutral line), never the completion message with the raw rule warning.
        String runId = seedRun("AUT_STAT_RUNNING")
        seedDefaultChatSpace("Rules failed space")
        assertTrue(callFacade("facade.ReconciliationFacadeServices.subscribe#RunNotification",
                [reconciliationRunResultId: runId]).ok as boolean)
        setRunStatus(runId, "AUT_STAT_FAILED")
        String rawWarning = "RuleSet RS_1 rule stage failed on batch 3: [Error: unable to resolve method using strict-mode: order.getFoo()]"
        List<Map> cards = []
        List<Map> deliveries = []
        AutomationExecutionSupport.errorCardPoster = { def postEc, Map event -> cards << event; true }
        TenantNotificationSupport.setDeliveryHook { String url, Map payload -> deliveries << payload; [ok: true, statusCode: 200] }
        try {
            AutomationExecutionSupport.notifySavedRunCompleted(ec, [reconciliationRunResultId: runId, companyUserGroupId: TENANT_ID,
                    savedRunId: "BE362_RULES", runName: "Rules run", statusEnumId: "AUT_STAT_FAILED", processingWarnings: [rawWarning]], false)
        } finally {
            AutomationExecutionSupport.errorCardPoster = null
            TenantNotificationSupport.resetDeliveryHook()
        }
        assertEquals([[text: "Rules run did not finish. Darpan has been alerted and is working on a fix."]], deliveries)
        assertFalse(deliveries.toString().contains("RuleSet"), "the subscriber never sees the rule warning")
        assertEquals(1, cards.size())
        assertEquals("FAILED", cards[0].kind)
        assertEquals(rawWarning, cards[0].errorFirstLine)
        assertEquals(0L, countSubscriptions(runId))
    }

    @Test
    void aRunWhoseRulesFailedAfterACancelIsNotToldAsAFailure() {
        // DAR-BE-362 re-review: the last cancel checkpoint is before the rule stage, so a cancel can land while the rules fail;
        // the run then ends CANCELLED. Like the automation path, a cancelled run posts no card and tells no one (the person who
        // pressed Cancel knows), and its subscriptions still go.
        String runId = seedRun("AUT_STAT_RUNNING")
        seedDefaultChatSpace("Cancelled run space")
        assertTrue(callFacade("facade.ReconciliationFacadeServices.subscribe#RunNotification",
                [reconciliationRunResultId: runId]).ok as boolean)
        ec.entity.find("darpan.reconciliation.ReconciliationRunResult").condition("reconciliationRunResultId", runId)
                .disableAuthz().one().set("cancelRequestedDate", ec.user.nowTimestamp).update()
        List<Map> cards = []
        List<Map> deliveries = []
        AutomationExecutionSupport.errorCardPoster = { def postEc, Map event -> cards << event; true }
        TenantNotificationSupport.setDeliveryHook { String url, Map payload -> deliveries << payload; [ok: true, statusCode: 200] }
        try {
            AutomationExecutionSupport.notifySavedRunCompleted(ec, [reconciliationRunResultId: runId, companyUserGroupId: TENANT_ID,
                    savedRunId: "BE362_CANCEL", runName: "Cancelled run", processingWarnings: ["RuleSet RS_1 rule stage failed"]], true)
        } finally {
            AutomationExecutionSupport.errorCardPoster = null
            TenantNotificationSupport.resetDeliveryHook()
        }
        assertEquals(0, cards.size(), "no card for a cancelled run")
        assertEquals(0, deliveries.size(), "no subscriber line for a cancelled run: ${deliveries}")
        assertEquals(0L, countSubscriptions(runId), "its subscriptions still go")
    }

    @Test
    void aCleanRunStillGetsItsCompletionMessageAndNoCard() {
        // DAR-BE-362: the success path is unchanged: notifySavedRunCompleted sends the usual completion message, no card.
        String runId = seedRun("AUT_STAT_RUNNING")
        seedDefaultChatSpace("Clean run space")
        assertTrue(callFacade("facade.ReconciliationFacadeServices.subscribe#RunNotification",
                [reconciliationRunResultId: runId]).ok as boolean)
        setRunStatus(runId, "AUT_STAT_SUCCESS")
        List<Map> cards = []
        List<Map> deliveries = []
        AutomationExecutionSupport.errorCardPoster = { def postEc, Map event -> cards << event; true }
        TenantNotificationSupport.setDeliveryHook { String url, Map payload -> deliveries << payload; [ok: true, statusCode: 200] }
        try {
            AutomationExecutionSupport.notifySavedRunCompleted(ec, [reconciliationRunResultId: runId, companyUserGroupId: TENANT_ID,
                    savedRunId: "BE362_CLEAN", runName: "Clean run", statusEnumId: "AUT_STAT_SUCCESS", processingWarnings: []], false)
        } finally {
            AutomationExecutionSupport.errorCardPoster = null
            TenantNotificationSupport.resetDeliveryHook()
        }
        assertEquals(0, cards.size())
        assertEquals(1, deliveries.size())
        assertFalse(deliveries[0].toString().contains("did not finish"), "a clean run is not told it failed: ${deliveries}")
        assertEquals(0L, countSubscriptions(runId))
    }

    @Test
    void failedInteractiveRunTellsSubscribersOnlyTheNeutralLine() {
        // DAR-BE-362: a notify-me subscriber of a failed interactive run gets the neutral line from the copy block and never
        // the raw error; Darpan's card gets the error's first line. The run is claimed and its subscription purged, so a
        // later notify for the same run sends nothing.
        String runId = seedRun("AUT_STAT_RUNNING")
        seedDefaultChatSpace("Failed run space")
        assertTrue(callFacade("facade.ReconciliationFacadeServices.subscribe#RunNotification",
                [reconciliationRunResultId: runId]).ok as boolean)
        setRunStatus(runId, "AUT_STAT_FAILED")
        String rawError = "OMS_GQL extract failed: HTTP 401 from oms.example.com (token expired)\n\tat darpan.Extractor.read(Extractor.groovy:12)"

        List<Map> cards = []
        List<Map> deliveries = []
        AutomationExecutionSupport.errorCardPoster = { def postEc, Map event -> cards << event; true }
        TenantNotificationSupport.setDeliveryHook { String url, Map payload -> deliveries << payload; [ok: true, statusCode: 200] }
        try {
            AutomationExecutionSupport.notifySavedRunError(ec, [reconciliationRunResultId: runId, companyUserGroupId: TENANT_ID,
                    runName: "Nightly orders", savedRunId: "BE362_SR"], rawError)
            Map again = TenantNotificationSupport.notifyRunCompleted(ec, [reconciliationRunResultId: runId,
                    companyUserGroupId: TENANT_ID, statusEnumId: "AUT_STAT_FAILED", processingWarnings: [rawError]])
            assertEquals("ALREADY_NOTIFIED", again.skippedReason, "no second notification for the same run")
        } finally {
            AutomationExecutionSupport.errorCardPoster = null
            TenantNotificationSupport.resetDeliveryHook()
        }

        assertEquals(1, deliveries.size(), "the subscriber is told once: ${deliveries}")
        assertEquals([text: "Nightly orders did not finish. Darpan has been alerted and is working on a fix."], deliveries[0])
        ["401", "OMS", "oms.example.com", "token", "Extractor"].each { String leak ->
            assertFalse(deliveries[0].text.toString().contains(leak), "the client line leaks '${leak}'")
        }
        assertEquals(0L, countSubscriptions(runId), "the subscription goes with the claim")

        assertEquals(1, cards.size())
        Map card = cards[0]
        assertEquals("FAILED", card.kind)
        assertEquals("manual", card.trigger)
        assertEquals("Nightly orders", card.automationName)
        assertEquals("savedRun:BE362_SR", card.subjectKey)
        String cardText = darpan.ops.ErrorChatSupport.cardText(card, 1)
        assertTrue(cardText.contains("OMS_GQL extract failed: HTTP 401 from oms.example.com (token expired)"), cardText)
        assertFalse(cardText.contains("Extractor.groovy"), "first line only: ${cardText}")
        assertTrue(cardText.contains("FAILED · manual"), cardText)
    }

    /**
     * OC48: a run's warnings are counted in its client message and never quoted there; their words go to Darpan's alerts
     * room, once per run (the notification claim), so a warning still reaches someone who can act on it. A run with no
     * warnings sends the alerts room nothing.
     */
    @Test
    void aRunsWarningsReachDarpansAlertsRoomOnceAndClientsOnlyAsACount() {
        String chatSpaceId = seedDefaultChatSpace("Warned space")
        String warnedRun = seedRun("AUT_STAT_SUCCESS")
        String quietRun = seedRun("AUT_STAT_SUCCESS")
        List<String> alerts = []
        List<String> deliveries = []
        DarpanSettings.rowLoader = { -> [(ErrorChatSupport.WEBHOOK_SETTING): [settingValue: WEBHOOK]] }
        DarpanSettings.expire()
        ErrorChatSupport.postHook = { String url, Map payload -> alerts << (payload.text as String); [ok: true] }
        TenantNotificationSupport.setDeliveryHook { String url, Map payload -> deliveries << (payload.text as String); [ok: true, statusCode: 200] }
        try {
            Map<String, Object> warned = [reconciliationRunResultId: warnedRun, companyUserGroupId: TENANT_ID, chatSpaceId: chatSpaceId,
                                          runName: "API Order Sync", savedRunId: "RS_WARNED", differenceCount: 0, onlyInFile1Count: 0,
                                          onlyInFile2Count: 0,
                                          processingWarnings: ["Shopify exchange sweep failed: java.net.SocketException: Connection reset"]]
            TenantNotificationSupport.notifyRunCompleted(ec, warned)
            assertEquals("ALREADY_NOTIFIED", TenantNotificationSupport.notifyRunCompleted(ec, warned).skippedReason)
            TenantNotificationSupport.notifyRunCompleted(ec, [reconciliationRunResultId: quietRun, companyUserGroupId: TENANT_ID,
                                                              chatSpaceId: chatSpaceId, runName: "Quiet Sync", differenceCount: 0,
                                                              onlyInFile1Count: 0, onlyInFile2Count: 0, processingWarnings: []])
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
            ErrorChatSupport.resetForTests()
            DarpanSettings.resetForTests()
        }

        assertEquals(2, deliveries.size(), "each run told its space once: ${deliveries}")
        String client = deliveries.first()
        assertTrue(client.contains("Warnings (1): details on the run result."), client)
        assertFalse(client.contains("SocketException"), client)
        assertEquals(1, alerts.size(), "one alert, for the warned run only: ${alerts}")
        assertTrue(alerts.first().contains("API Order Sync"), alerts.first())
        assertTrue(alerts.first().contains("java.net.SocketException: Connection reset"), alerts.first())
    }

    private String seedRun(String statusEnumId) {
        String runId = "RUNRES_${runSequence.incrementAndGet()}"
        ec.entity.makeValue("darpan.reconciliation.ReconciliationRunResult")
                .setAll([reconciliationRunResultId: runId, companyUserGroupId: TENANT_ID, statusEnumId: statusEnumId])
                .create()
        return runId
    }

    /** Saves a chat space and makes it the caller's notification default, so subscribe# can succeed. Returns its id. */
    private String seedDefaultChatSpace(String spaceName) {
        def save = callFacade("facade.SettingsFacadeServices.save#TenantChatSpace",
                [spaceName: spaceName, googleChatWebhookUrl: WEBHOOK, isActive: true])
        assertTrue(save.ok as boolean)
        def saveDefault = callFacade("facade.SettingsFacadeServices.save#UserNotificationDefault",
                [chatSpaceId: save.chatSpace.chatSpaceId])
        assertTrue(saveDefault.ok as boolean)
        return save.chatSpace.chatSpaceId as String
    }

    private String createCsvRun(String runName) {
        def created = callFacade("facade.ReconciliationFacadeServices.create#CsvRun", [
                runName: runName, file1SystemEnumId: "OMS", file2SystemEnumId: "SHOPIFY",
                file1CompareColumn: "order_id", file2CompareColumn: "order_id"])
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        return created.savedRun.savedRunId as String
    }

    /**
     * DAR-BE-362: runs a saved run through run#SavedRunDiff, capturing Darpan's cards and every client delivery. A person
     * subscribes while it runs: the card is posted before the client line, so the poster subscribes the caller to the run
     * then, as the run page's bell would mid-run. Returns cards, deliveries, rawError (the run's first error), thrown, run.
     */
    private Map runAndWatch(String savedRunId, String chatSpaceId, Map files) {
        List<Map> cards = []
        List<Map> deliveries = []
        AutomationExecutionSupport.errorCardPoster = { def postEc, Map event ->
            cards << event
            def run = findRunOf(savedRunId)
            ec.transaction.runUseOrBegin(30, "test subscription", {
                ec.entity.makeValue("darpan.reconciliation.ReconciliationRunNotifySubscription").setAll([
                        reconciliationRunResultId: run.reconciliationRunResultId, userId: ec.user.userId,
                        chatSpaceId: chatSpaceId, subscribedDate: ec.user.nowTimestamp]).create()
            })
            true
        }
        TenantNotificationSupport.setDeliveryHook { String url, Map payload -> deliveries << payload; [ok: true, statusCode: 200] }
        Map<String, Object> result = null
        Throwable thrown = null
        try {
            result = callFacade("facade.ReconciliationFacadeServices.run#SavedRunDiff", [savedRunId: savedRunId, hasHeader: true] + files)
        } catch (Throwable t) {
            thrown = t
        } finally {
            AutomationExecutionSupport.errorCardPoster = null
            TenantNotificationSupport.resetDeliveryHook()
        }
        String rawError = ((result?.errors ?: ec.message.errors ?: []) as List).find { it }?.toString()
        ec.message.clearErrors()
        return [cards: cards, deliveries: deliveries, rawError: rawError, thrown: thrown, run: findRunOf(savedRunId)]
    }

    private def findRunOf(String savedRunId) {
        return ec.entity.find("darpan.reconciliation.ReconciliationRunResult").condition("savedRunId", savedRunId)
                .disableAuthz().useCache(false).one()
    }

    /** Moves a seeded run to another status, the way a real terminal close would. */
    private void setRunStatus(String runId, String statusEnumId) {
        ec.entity.find("darpan.reconciliation.ReconciliationRunResult")
                .condition("reconciliationRunResultId", runId)
                .disableAuthz()
                .one()
                .set("statusEnumId", statusEnumId)
                .update()
    }

    private def findSubscription(String runId) {
        return ec.entity.find("darpan.reconciliation.ReconciliationRunNotifySubscription")
                .condition([reconciliationRunResultId: runId, userId: ec.user.userId])
                .disableAuthz()
                .useCache(false)
                .one()
    }

    private long countSubscriptions(String runId) {
        return ec.entity.find("darpan.reconciliation.ReconciliationRunNotifySubscription")
                .condition("reconciliationRunResultId", runId)
                .disableAuthz()
                .useCache(false)
                .count()
    }

    private Map<String, Object> callFacade(String serviceName, Map<String, Object> parameters) {
        return (Map<String, Object>) ec.service.sync()
                .name(serviceName)
                .parameters(parameters)
                .disableAuthz()
                .call()
    }
}
