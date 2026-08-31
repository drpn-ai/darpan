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
import static org.junit.jupiter.api.Assertions.assertNull

/**
 * Slack-support upgrade migration:
 * reconciliation.ReconciliationNotificationServices.migrate#ChatSpaceWebhookUrls copies each chat
 * space's legacy googleChatWebhookUrl into the provider-agnostic webhookUrl column and stamps the
 * space as CHAT_PROV_GOOGLE. Internal-only (authenticate="false", not allow-remote).
 *
 * The migration is deliberately OPTIONAL for delivery — resolveWebhookUrl falls back to the legacy
 * column — so the property that actually matters is that it never damages a row: it must not touch
 * an already-migrated space, must not overwrite a Slack space's provider, and must be safe to run
 * twice.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatSpaceWebhookMigrationSmokeTests {
    private static final String TENANT_ID = "TEN_MIG"
    private static final String MIGRATE_SERVICE =
            "reconciliation.ReconciliationNotificationServices.migrate#ChatSpaceWebhookUrls"
    private static final String LEGACY_URL =
            "https://chat.googleapis.com/v1/spaces/LEGACY/messages?key=k&token=t"
    private static final String SLACK_URL =
            "https://hooks.slack.com/services/T-EXAMPLE/B-EXAMPLE/placeholder-not-a-real-secret"
    private static final String CLEAR_SERVICE =
            "reconciliation.ReconciliationNotificationServices.clear#UndecryptableChatSpaceWebhooks"
    // Shaped like what Moqui hands back for a row encrypted under the old entity_ds_crypt_pass once
    // encrypt="true" is gone: base64-ish, and emphatically not a URL.
    private static final String CIPHERTEXT = "j8Fq1nZk3xQ==ciphertext-not-a-url"

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "chat-space-webhook-migration-smoke")
        // TenantChatSpace.chatProviderEnumId FKs moqui.basic.Enumeration (TCSPACE_PROVIDER), so a
        // Slack-provider row cannot be written until the DarpanChatProvider catalog exists — the
        // same prerequisite the 1.6.0 upgrade data carries for a real deployment. Seeded here
        // directly rather than loading SecuritySeedData.xml, which would also pull in this
        // component's whole authorization graph for a two-row dependency.
        ec.artifactExecution.disableAuthz()
        ec.entity.makeValue("moqui.basic.EnumerationType")
                .setAll([enumTypeId: "DarpanChatProvider", description: "Chat providers"]).create()
        ec.entity.makeValue("moqui.basic.Enumeration")
                .setAll([enumId: "CHAT_PROV_GOOGLE", enumTypeId: "DarpanChatProvider", description: "Google Chat"]).create()
        ec.entity.makeValue("moqui.basic.Enumeration")
                .setAll([enumId: "CHAT_PROV_SLACK", enumTypeId: "DarpanChatProvider", description: "Slack"]).create()
        // TenantChatSpace.companyUserGroupId FKs UserGroup. Created ONCE here rather than inside a
        // test: both tests need it, JUnit does not fix their order, and an unconditional create in
        // one of them fails with "record already exists" whenever the other ran first.
        ec.entity.makeValue("moqui.security.UserGroup")
                .setAll([userGroupId: TENANT_ID, description: "Migration tenant"]).create()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @Test
    void carriesLegacyWebhooksForwardWithoutTouchingMigratedOrSlackRows() {
        // A pre-Slack row: legacy column only, no provider. This is what every existing space in
        // production looks like.
        String legacyId = createSpace("Legacy space", [googleChatWebhookUrl: LEGACY_URL])
        // A row already carrying the new column — the migration must leave it exactly as-is rather
        // than overwriting webhookUrl from the stale legacy value.
        String migratedId = createSpace("Already migrated", [
                webhookUrl          : SLACK_URL,
                googleChatWebhookUrl: LEGACY_URL,
                chatProviderEnumId  : "CHAT_PROV_SLACK",
        ])
        // A row with nothing to carry forward must not be counted as migrated.
        String emptyId = createSpace("No webhook", [:])

        Map firstRun = (Map) ec.service.sync().name(MIGRATE_SERVICE).parameters([:]).call()
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals(1, firstRun.migratedSpaceCount as Integer)
        assertEquals(2, firstRun.skippedSpaceCount as Integer)

        Map legacy = findSpace(legacyId)
        assertEquals(LEGACY_URL, legacy.webhookUrl)
        // The legacy column is deliberately left populated: dropping it here would break a rollback
        // to the release before Slack support.
        assertEquals(LEGACY_URL, legacy.googleChatWebhookUrl)
        assertEquals("CHAT_PROV_GOOGLE", legacy.chatProviderEnumId)

        // The Slack row keeps its own URL and provider — a migration that read the legacy column
        // unconditionally would have re-pointed it at Google Chat.
        Map migrated = findSpace(migratedId)
        assertEquals(SLACK_URL, migrated.webhookUrl)
        assertEquals("CHAT_PROV_SLACK", migrated.chatProviderEnumId)

        Map empty = findSpace(emptyId)
        assertNull(empty.webhookUrl)
        assertNull(empty.chatProviderEnumId)

        // Idempotent: the carried-forward row now has webhookUrl, so a second run skips everything.
        Map secondRun = (Map) ec.service.sync().name(MIGRATE_SERVICE).parameters([:]).call()
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals(0, secondRun.migratedSpaceCount as Integer)
        assertEquals(3, secondRun.skippedSpaceCount as Integer)
        assertEquals(LEGACY_URL, findSpace(legacyId).webhookUrl)
        assertEquals(SLACK_URL, findSpace(migratedId).webhookUrl)
    }

    // clear#UndecryptableChatSpaceWebhooks only ever validated googleChatWebhookUrl. But
    // migrate#ChatSpaceWebhookUrls copies that column into webhookUrl WITHOUT validating it, and
    // resolveWebhookUrl reads webhookUrl FIRST. So on any deployment that ran the migration before
    // the cleanup — nothing enforces an order between two one-time services — the ciphertext moved
    // into the new column, where no service could reach it. The space still rendered as configured
    // and the garbage went to URI.create() on every send. Reported live 2026-08-29.
    //
    // The Slack row is the other half of the requirement, and the reason this cannot simply reuse
    // validateGoogleChatWebhookUrl: webhookUrl is provider-agnostic, so validating it against the
    // Google Chat host pin would null every working Slack webhook and turn a cleanup into an outage.
    @Test
    void clearsCiphertextCarriedIntoTheNewColumnAndSparesSlackWebhooks() {
        String cipherId = createSpace("Ciphertext carried forward", [
                webhookUrl        : CIPHERTEXT,
                chatProviderEnumId: "CHAT_PROV_GOOGLE",
        ])
        String slackId = createSpace("Slack space", [
                webhookUrl        : SLACK_URL,
                chatProviderEnumId: "CHAT_PROV_SLACK",
        ])

        try {
            ec.service.sync().name(CLEAR_SERVICE).parameters([:]).call()
            assertFalse(ec.message.hasError(), ec.message.errors?.toString())

            assertNull(findSpace(cipherId).webhookUrl,
                    "ciphertext carried into webhookUrl must be cleared, or nothing can reach it")
            assertEquals(SLACK_URL, findSpace(slackId).webhookUrl,
                    "a valid Slack webhook must survive: webhookUrl is provider-agnostic")
        } finally {
            // The sibling test asserts ABSOLUTE migrated/skipped counts over every space in the
            // database, and this class shares one Moqui instance, so these rows must not outlive
            // the test that made them whatever its outcome.
            [cipherId, slackId].each { String id ->
                def row = findSpace(id)
                if (row != null) row.delete()
            }
            ec.message.clearErrors()
        }
    }

    private String createSpace(String spaceName, Map<String, Object> extraFields) {
        Map<String, Object> params = [companyUserGroupId: TENANT_ID, spaceName: spaceName, isActive: "Y"]
        params.putAll(extraFields)
        Map created = (Map) ec.service.sync().name("create#darpan.reconciliation.TenantChatSpace")
                .parameters(params).disableAuthz().call()
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        String chatSpaceId = created.chatSpaceId as String
        assertNotNull(chatSpaceId)
        return chatSpaceId
    }

    private Map findSpace(String chatSpaceId) {
        return ec.entity.find("darpan.reconciliation.TenantChatSpace")
                .condition("chatSpaceId", chatSpaceId)
                .disableAuthz().useCache(false).one()
    }
}
