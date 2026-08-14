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
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantChatSpaceFacadeSmokeTests {
    private static final String TENANT_ID = "KREWE"
    static final String WEBHOOK = "https://chat.googleapis.com/v1/spaces/AAAA1234ZZZZ/messages?key=k&token=t"

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "tenant-chat-space-facade-smoke")
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
    void saveListDeleteLifecycle() {
        def save = callFacade("facade.SettingsFacadeServices.save#TenantChatSpace",
                [spaceName: "Finance space", googleChatWebhookUrl: WEBHOOK, isActive: true])
        assertTrue(save.ok as boolean)
        String chatSpaceId = save.chatSpace.chatSpaceId
        assertEquals("Finance space", save.chatSpace.spaceName)
        assertTrue(save.chatSpace.googleChatConfigured as boolean)
        // Inverted 2026-08-14: masking removed, so save echoes the webhook URL back verbatim.
        assertEquals(WEBHOOK, save.chatSpace.googleChatWebhookUrl as String)

        def dup = callFacade("facade.SettingsFacadeServices.save#TenantChatSpace",
                [spaceName: "finance SPACE", googleChatWebhookUrl: WEBHOOK])
        assertFalse(dup.ok as boolean)   // case-insensitive per-tenant name uniqueness

        // ServiceCallSyncImpl.ignorePreviousError defaults to false, so a prior-call error blocks the
        // next service invocation outright unless cleared (same convention as
        // AutomationFacadeSmokeTests, which clears errors after every intentionally-failed call).
        ec.message.clearErrors()

        def list = callFacade("facade.SettingsFacadeServices.list#TenantChatSpaces", [:])
        assertTrue(list.chatSpaces.any { it.chatSpaceId == chatSpaceId && it.inUse == false })

        def del = callFacade("facade.SettingsFacadeServices.delete#TenantChatSpace", [chatSpaceId: chatSpaceId])
        assertTrue(del.ok as boolean)
        def listAfter = callFacade("facade.SettingsFacadeServices.list#TenantChatSpaces", [:])
        assertFalse(listAfter.chatSpaces.any { it.chatSpaceId == chatSpaceId })
    }

    @Test
    void deleteBlockedWhenReferencedByAutomation() {
        def save = callFacade("facade.SettingsFacadeServices.save#TenantChatSpace",
                [spaceName: "Ops space", googleChatWebhookUrl: WEBHOOK, isActive: true])
        assertTrue(save.ok as boolean)
        String chatSpaceId = save.chatSpace.chatSpaceId

        // FK prerequisite: ReconciliationAutomation.chatSpaceId -> TenantChatSpace (RECAUT_CHAT_SPACE).
        // Minimal not-null fields: automationName, companyUserGroupId, inputModeEnumId, savedRunId.
        ec.service.sync()
                .name("create#darpan.reconciliation.ReconciliationAutomation")
                .parameters([
                        automationName    : "T",
                        companyUserGroupId: TENANT_ID,
                        inputModeEnumId   : "AUT_IN_API_RANGE",
                        savedRunId        : "RS_TEST",
                        chatSpaceId       : chatSpaceId,
                ])
                .disableAuthz()
                .call()
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())

        def del = callFacade("facade.SettingsFacadeServices.delete#TenantChatSpace", [chatSpaceId: chatSpaceId])
        assertFalse(del.ok as boolean)
        assertTrue((del.errors ?: []).join(" ").contains("in use"))

        def row = ec.entity.find("darpan.reconciliation.TenantChatSpace")
                .condition("chatSpaceId", chatSpaceId)
                .disableAuthz()
                .useCache(false)
                .one()
        assertNotNull(row)

        // The blocked delete above is an intentional error-producing call; ServiceCallSyncImpl
        // refuses to run a new top-level service while a prior error is still set
        // (ignorePreviousError defaults to false), so clear it before the next call.
        ec.message.clearErrors()

        def resave = callFacade("facade.SettingsFacadeServices.save#TenantChatSpace",
                [chatSpaceId: chatSpaceId, spaceName: "Ops space renamed", googleChatWebhookUrl: WEBHOOK, isActive: true])
        assertTrue(resave.ok as boolean)
        assertTrue(resave.chatSpace.inUse as boolean)
    }

    // clear#UndecryptableChatSpaceWebhooks exists for the 2026-08-14 removal of encrypt="true": rows
    // written while the field was encrypted now read back as ciphertext, and deliverGoogleChat feeds
    // the value straight into URI.create(). A ciphertext-shaped value is simulated here by writing a
    // non-URL directly to the entity, bypassing save#TenantChatSpace's validation.
    @Test
    void clearUndecryptableWebhooksNullsUnreadableValuesAndKeepsValidOnes() {
        def good = callFacade("facade.SettingsFacadeServices.save#TenantChatSpace",
                [spaceName: "Readable space", googleChatWebhookUrl: WEBHOOK, isActive: true])
        assertTrue(good.ok as boolean)
        String goodId = good.chatSpace.chatSpaceId

        String badId = "CIPHERTEXT_SPACE"
        ReconciliationSmokeTestSupport.insertEntityDirect(ec, "darpan.reconciliation.TenantChatSpace",
                [chatSpaceId: badId, companyUserGroupId: TENANT_ID, spaceName: "Ciphertext space",
                 googleChatWebhookUrl: "j8Fq1nZk3xQ==ciphertext-not-a-url", isActive: "Y"])

        def result = callFacade("reconciliation.ReconciliationNotificationServices.clear#UndecryptableChatSpaceWebhooks", [:])
        assertEquals(1, result.clearedSpaceCount as Integer)
        assertEquals(1, result.keptSpaceCount as Integer)

        assertNull(ec.entity.find("darpan.reconciliation.TenantChatSpace")
                .condition("chatSpaceId", badId).disableAuthz().useCache(false).one().get("googleChatWebhookUrl"))
        assertEquals(WEBHOOK, ec.entity.find("darpan.reconciliation.TenantChatSpace")
                .condition("chatSpaceId", goodId).disableAuthz().useCache(false).one().get("googleChatWebhookUrl"))

        // Idempotent: the cleared row no longer has a value, so a second pass clears nothing.
        def again = callFacade("reconciliation.ReconciliationNotificationServices.clear#UndecryptableChatSpaceWebhooks", [:])
        assertEquals(0, again.clearedSpaceCount as Integer)
        assertEquals(1, again.keptSpaceCount as Integer)
    }

    private Map<String, Object> callFacade(String serviceName, Map<String, Object> parameters) {
        return (Map<String, Object>) ec.service.sync()
                .name(serviceName)
                .parameters(parameters)
                .disableAuthz()
                .call()
    }
}
