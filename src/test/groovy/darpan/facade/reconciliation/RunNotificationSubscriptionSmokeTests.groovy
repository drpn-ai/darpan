package darpan.facade.reconciliation

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

    private String seedRun(String statusEnumId) {
        String runId = "RUNRES_${runSequence.incrementAndGet()}"
        ec.entity.makeValue("darpan.reconciliation.ReconciliationRunResult")
                .setAll([reconciliationRunResultId: runId, companyUserGroupId: TENANT_ID, statusEnumId: statusEnumId])
                .create()
        return runId
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
