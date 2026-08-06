package darpan.facade.reconciliation

import darpan.reconciliation.notification.TenantNotificationSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
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
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
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
    }

    @AfterAll
    void cleanup() {
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

    private String seedRun(String statusEnumId) {
        String runId = "RUNRES_${runSequence.incrementAndGet()}"
        ec.entity.makeValue("darpan.reconciliation.ReconciliationRunResult")
                .setAll([reconciliationRunResultId: runId, companyUserGroupId: TENANT_ID, statusEnumId: statusEnumId])
                .create()
        return runId
    }

    /** Saves a chat space and makes it the caller's notification default, so subscribe# can succeed. */
    private void seedDefaultChatSpace(String spaceName) {
        def save = callFacade("facade.SettingsFacadeServices.save#TenantChatSpace",
                [spaceName: spaceName, googleChatWebhookUrl: WEBHOOK, isActive: true])
        assertTrue(save.ok as boolean)
        def saveDefault = callFacade("facade.SettingsFacadeServices.save#UserNotificationDefault",
                [chatSpaceId: save.chatSpace.chatSpaceId])
        assertTrue(saveDefault.ok as boolean)
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
