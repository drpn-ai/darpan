package darpan.facade.settings

import darpan.reconciliation.support.SharedMoqui
import darpan.facade.common.TenantAccessSupport
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
@SharedMoqui
class UserNotificationDefaultFacadeSmokeTests {
    static final String WEBHOOK = "https://chat.googleapis.com/v1/spaces/AAAA1234ZZZZ/messages?key=k&token=t"
    // seedCompanyScope makes KREWE the active tenant; the other tenant owns a real, active space.
    static final String TENANT = "KREWE"
    static final String OTHER_TENANT = "GORJANA"
    static final String OTHER_SPACE = "OTHER_TENANT_DEFAULT_SPACE"

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
        ec.message.clearErrors()
        // The default is a user preference, not tenant data: clear it so no test reads another's default.
        ec.user.setPreference(UserNotificationPreferenceSupport.PREFERENCE_KEY, null)
        ec.entity.makeValue("moqui.security.UserGroup").setAll([
                userGroupId    : OTHER_TENANT, description: "Gorjana",
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID,
        ]).createOrUpdate()
        ec.entity.makeValue("darpan.reconciliation.TenantChatSpace").setAll([
                chatSpaceId       : OTHER_SPACE,
                companyUserGroupId: OTHER_TENANT,
                spaceName         : "Other tenant space",
                webhookUrl        : WEBHOOK,
                isActive          : "Y",
        ]).createOrUpdate()
    }

    @Test
    void saveAndGetDefaultRoundTrip() {
        def save = callFacade("facade.SettingsFacadeServices.save#TenantChatSpace",
                [spaceName: "My space", webhookUrl: WEBHOOK, isActive: true])
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
                [spaceName: "Clearable space", webhookUrl: WEBHOOK, isActive: true])
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
        // Positive control: the caller can set their OWN space, so the refusal below is containment,
        // not a setup problem refusing everything.
        def own = callFacade("facade.SettingsFacadeServices.save#TenantChatSpace",
                [spaceName: "Own space ${System.nanoTime()}".toString(), webhookUrl: WEBHOOK, isActive: true])
        assertTrue(own.ok as boolean, own.errors?.toString())
        String ownId = own.chatSpace.chatSpaceId as String
        assertTrue(callFacade("facade.SettingsFacadeServices.save#UserNotificationDefault", [chatSpaceId: ownId]).ok as boolean)

        def saveDefault = callFacade("facade.SettingsFacadeServices.save#UserNotificationDefault",
                [chatSpaceId: OTHER_SPACE])
        assertFalse(saveDefault.ok as boolean)
        assertTrue((saveDefault.errors ?: []).join(" ").contains("Chat space '${OTHER_SPACE}' was not found or is inactive.".toString()),
                saveDefault.errors?.toString())
        assertNull(saveDefault.userNotificationDefault)
        assertEquals(ownId, UserNotificationPreferenceSupport.getDefaultChatSpaceId(ec, TENANT),
                "a refused save must leave the stored default untouched")

        // ServiceCallSyncImpl.ignorePreviousError defaults to false, so a prior-call error blocks the
        // next service invocation outright unless cleared (same convention as
        // TenantChatSpaceFacadeSmokeTests, which clears errors after every intentionally-failed call).
        ec.message.clearErrors()

        def get = callFacade("facade.SettingsFacadeServices.get#UserNotificationDefault", [:])
        assertTrue(get.ok as boolean)
        assertEquals(ownId, get.userNotificationDefault?.chatSpaceId)
    }

    private Map<String, Object> callFacade(String serviceName, Map<String, Object> parameters) {
        return (Map<String, Object>) ec.service.sync()
                .name(serviceName)
                .parameters(parameters)
                .disableAuthz()
                .call()
    }
}
