package darpan.reconciliation.notification

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull

/**
 * v1.2.0 upgrade migration: reconciliation.ReconciliationNotificationServices.migrate#TenantNotificationSettings
 * copies each tenant's retired TenantNotificationSetting webhook into a TenantChatSpace named
 * 'Default space' and links that tenant's un-linked automations to it. Internal-only
 * (authenticate="false", not allow-remote) — invoked once during the v1.2.0 upgrade, never from the SPA.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantNotificationMigrationSmokeTests {
    private static final String TEN_A = "TEN_A"
    private static final String TEN_B = "TEN_B"
    private static final String MIGRATE_SERVICE = "reconciliation.ReconciliationNotificationServices.migrate#TenantNotificationSettings"

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "tenant-notification-migration-smoke")
        // ReconciliationAutomation.inputModeEnumId FKs to moqui.basic.Enumeration; pull the seeded
        // AutomationInputMode enums (same convention as TenantChatSpaceFacadeSmokeTests).
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @Test
    void migratesWebhookAndLinksAutomationsIdempotently() {
        ec.artifactExecution.disableAuthz()

        // TEN_A: legacy TenantNotificationSetting webhook + two automations with no chat space yet.
        // The migration should create one 'Default space' for TEN_A and link both automations to it.
        ec.entity.makeValue("moqui.security.UserGroup")
                .setAll([userGroupId: TEN_A, description: "Tenant A"])
                .create()
        ec.entity.makeValue("moqui.security.UserAccount")
                .setAll([userId: "USER_A", username: "user.a", currentPassword: ""])
                .create()
        createNotificationSetting(TEN_A, "USER_A",
                "https://chat.googleapis.com/v1/spaces/AAA111/messages?key=ka&token=ta")
        String automationA1 = createAutomation(TEN_A, "TEN_A automation 1", null)
        String automationA2 = createAutomation(TEN_A, "TEN_A automation 2", null)

        // TEN_B: already has its own chat space and an automation linked to it, but ALSO still has a
        // legacy TenantNotificationSetting row. The idempotency guard (tenants with ANY existing chat
        // space are skipped entirely) must leave TEN_B completely untouched.
        ec.entity.makeValue("moqui.security.UserGroup")
                .setAll([userGroupId: TEN_B, description: "Tenant B"])
                .create()
        ec.entity.makeValue("moqui.security.UserAccount")
                .setAll([userId: "USER_B", username: "user.b", currentPassword: ""])
                .create()
        createNotificationSetting(TEN_B, "USER_B",
                "https://chat.googleapis.com/v1/spaces/BBB222/messages?key=kb&token=tb")
        Map createdBSpace = (Map) ec.service.sync().name("create#darpan.reconciliation.TenantChatSpace")
                .parameters([companyUserGroupId: TEN_B, spaceName: "Existing space",
                             googleChatWebhookUrl: "https://chat.googleapis.com/v1/spaces/BBB999/messages?key=kb2&token=tb2",
                             isActive: "Y"])
                .disableAuthz().call()
        String tenBSpaceId = createdBSpace.chatSpaceId as String
        assertNotNull(tenBSpaceId)
        String automationB1 = createAutomation(TEN_B, "TEN_B automation 1", tenBSpaceId)

        // First run: only TEN_A qualifies (no existing chat space).
        Map firstRun = (Map) ec.service.sync().name(MIGRATE_SERVICE).parameters([:]).call()
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals(1, firstRun.migratedTenantCount as Integer)
        assertEquals(2, firstRun.linkedAutomationCount as Integer)

        List<Map> tenASpaces = ec.entity.find("darpan.reconciliation.TenantChatSpace")
                .condition("companyUserGroupId", TEN_A).disableAuthz().useCache(false).list()
        assertEquals(1, tenASpaces.size())
        Map tenASpace = tenASpaces.first()
        assertEquals("Default space", tenASpace.spaceName)
        assertEquals("https://chat.googleapis.com/v1/spaces/AAA111/messages?key=ka&token=ta", tenASpace.googleChatWebhookUrl)
        assertEquals("Y", tenASpace.isActive)
        assertEquals("USER_A", tenASpace.createdByUserId)

        assertEquals(tenASpace.chatSpaceId, findAutomation(automationA1).chatSpaceId)
        assertEquals(tenASpace.chatSpaceId, findAutomation(automationA2).chatSpaceId)

        // TEN_B untouched: still exactly its one pre-existing chat space, automation link unchanged.
        List<Map> tenBSpaces = ec.entity.find("darpan.reconciliation.TenantChatSpace")
                .condition("companyUserGroupId", TEN_B).disableAuthz().useCache(false).list()
        assertEquals(1, tenBSpaces.size())
        assertEquals("Existing space", tenBSpaces.first().spaceName)
        assertEquals(tenBSpaceId, findAutomation(automationB1).chatSpaceId)

        // Second run: fully idempotent — every tenant with a TenantNotificationSetting row already
        // has a chat space, so nothing migrates and nothing re-links.
        Map secondRun = (Map) ec.service.sync().name(MIGRATE_SERVICE).parameters([:]).call()
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals(0, secondRun.migratedTenantCount as Integer)
        assertEquals(0, secondRun.linkedAutomationCount as Integer)

        List<Map> tenASpacesAfter = ec.entity.find("darpan.reconciliation.TenantChatSpace")
                .condition("companyUserGroupId", TEN_A).disableAuthz().useCache(false).list()
        assertEquals(1, tenASpacesAfter.size())
    }

    private void createNotificationSetting(String tenantId, String createdByUserId, String webhookUrl) {
        ec.service.sync().name("create#darpan.reconciliation.TenantNotificationSetting")
                .parameters([companyUserGroupId: tenantId, createdByUserId: createdByUserId,
                             googleChatWebhookUrl: webhookUrl, isActive: "Y",
                             createdDate: ec.user.nowTimestamp, lastUpdatedDate: ec.user.nowTimestamp])
                .disableAuthz().call()
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
    }

    private String createAutomation(String tenantId, String name, String chatSpaceId) {
        Map<String, Object> params = [
                automationName    : name,
                companyUserGroupId: tenantId,
                inputModeEnumId   : "AUT_IN_API_RANGE",
                savedRunId        : "RS_TEST",
        ]
        if (chatSpaceId) params.chatSpaceId = chatSpaceId
        Map created = (Map) ec.service.sync()
                .name("create#darpan.reconciliation.ReconciliationAutomation")
                .parameters(params)
                .disableAuthz()
                .call()
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        return created.automationId as String
    }

    private Map findAutomation(String automationId) {
        return ec.entity.find("darpan.reconciliation.ReconciliationAutomation")
                .condition("automationId", automationId)
                .disableAuthz().useCache(false).one()
    }
}
