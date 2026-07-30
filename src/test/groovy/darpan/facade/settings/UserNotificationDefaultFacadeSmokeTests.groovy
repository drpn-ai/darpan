package darpan.facade.settings

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserNotificationDefaultFacadeSmokeTests {
    static final String WEBHOOK = "https://chat.googleapis.com/v1/spaces/AAAA1234ZZZZ/messages?key=k&token=t"

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "user-notification-default-facade-smoke")
        // ReconciliationAutomation.inputModeEnumId FKs to moqui.basic.Enumeration; pull the seeded
        // AutomationInputMode enums (same convention as AutomationFacadeSmokeTests).
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
    void saveAndGetDefaultRoundTrip() {
        def save = callFacade("facade.SettingsFacadeServices.save#TenantChatSpace",
                [spaceName: "My space", googleChatWebhookUrl: WEBHOOK, isActive: true])
        assertTrue(save.ok as boolean)
        String chatSpaceId = save.chatSpace.chatSpaceId

        def saveDefault = callFacade("facade.SettingsFacadeServices.save#UserNotificationDefault",
                [chatSpaceId: chatSpaceId])
        assertTrue(saveDefault.ok as boolean)
        assertEquals(chatSpaceId, saveDefault.userNotificationDefault.chatSpaceId)
        assertEquals("My space", saveDefault.userNotificationDefault.spaceName)

        def get = callFacade("facade.SettingsFacadeServices.get#UserNotificationDefault", [:])
        assertTrue(get.ok as boolean)
        assertEquals(chatSpaceId, get.userNotificationDefault.chatSpaceId)
        assertEquals("My space", get.userNotificationDefault.spaceName)
    }

    @Test
    void clearingAndStaleDefaults() {
        def save = callFacade("facade.SettingsFacadeServices.save#TenantChatSpace",
                [spaceName: "Clearable space", googleChatWebhookUrl: WEBHOOK, isActive: true])
        assertTrue(save.ok as boolean)
        String chatSpaceId = save.chatSpace.chatSpaceId

        def saveDefault = callFacade("facade.SettingsFacadeServices.save#UserNotificationDefault",
                [chatSpaceId: chatSpaceId])
        assertTrue(saveDefault.ok as boolean)

        def clearDefault = callFacade("facade.SettingsFacadeServices.save#UserNotificationDefault",
                [chatSpaceId: ""])
        assertTrue(clearDefault.ok as boolean)
        assertNull(clearDefault.userNotificationDefault)

        def getAfterClear = callFacade("facade.SettingsFacadeServices.get#UserNotificationDefault", [:])
        assertTrue(getAfterClear.ok as boolean)
        assertNull(getAfterClear.userNotificationDefault)

        // Re-set the default, then deactivate the underlying space via save#TenantChatSpace so the
        // stored preference becomes a stale/inactive pointer.
        def resaveDefault = callFacade("facade.SettingsFacadeServices.save#UserNotificationDefault",
                [chatSpaceId: chatSpaceId])
        assertTrue(resaveDefault.ok as boolean)

        def deactivate = callFacade("facade.SettingsFacadeServices.save#TenantChatSpace",
                [chatSpaceId: chatSpaceId, spaceName: "Clearable space", isActive: false])
        assertTrue(deactivate.ok as boolean)

        def getAfterDeactivate = callFacade("facade.SettingsFacadeServices.get#UserNotificationDefault", [:])
        assertTrue(getAfterDeactivate.ok as boolean)
        assertNull(getAfterDeactivate.userNotificationDefault)
    }

    @Test
    void rejectsSpaceFromOtherTenant() {
        def saveDefault = callFacade("facade.SettingsFacadeServices.save#UserNotificationDefault",
                [chatSpaceId: "NOT_A_REAL_CHAT_SPACE"])
        assertFalse(saveDefault.ok as boolean)
        assertTrue((saveDefault.errors ?: []).join(" ").contains("not found"))

        // ServiceCallSyncImpl.ignorePreviousError defaults to false, so a prior-call error blocks the
        // next service invocation outright unless cleared (same convention as
        // TenantChatSpaceFacadeSmokeTests, which clears errors after every intentionally-failed call).
        ec.message.clearErrors()

        def get = callFacade("facade.SettingsFacadeServices.get#UserNotificationDefault", [:])
        assertTrue(get.ok as boolean)
        assertNull(get.userNotificationDefault)
    }

    private Map<String, Object> callFacade(String serviceName, Map<String, Object> parameters) {
        return (Map<String, Object>) ec.service.sync()
                .name(serviceName)
                .parameters(parameters)
                .disableAuthz()
                .call()
    }
}
