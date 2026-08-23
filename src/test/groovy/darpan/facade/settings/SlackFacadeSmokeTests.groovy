package darpan.facade.settings

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.notification.SlackApiClient
import darpan.reconciliation.notification.SlackOAuthSupport
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
 * The Slack facade. Two properties matter most and neither is visible by reading the happy path:
 * the bot token must never leave the backend, and one tenant must not be able to drive another
 * tenant's workspace by passing its install id.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SlackFacadeSmokeTests {
    private static final String TEST_USER_ID = "TEST_CUSTOMER_USER"
    private static final String OWNER = "KREWE"
    private static final String OTHER = "GORJANA"
    private static final String SECRET_TOKEN = "xoxb-do-not-leak-this"
    private static final Timestamp TEST_FROM_DATE = Timestamp.valueOf("2026-04-23 00:00:00")

    private ExecutionContext ec
    private String ownerInstallId

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "slack-facade-smoke")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        seedTenant(OTHER, "Gorjana")
        grantEditor(OWNER)
        ec.artifactExecution.disableAuthz()
        // TenantChatSpace.chatProviderEnumId FKs moqui.basic.Enumeration, so a Slack-provider space
        // cannot be written until the DarpanChatProvider catalog exists — the same prerequisite the
        // 1.6.0 upgrade data carries for a real deployment.
        ec.entity.makeValue("moqui.basic.EnumerationType")
                .setAll([enumTypeId: "DarpanChatProvider", description: "Chat providers"]).createOrUpdate()
        ["CHAT_PROV_GOOGLE": "Google Chat", "CHAT_PROV_SLACK": "Slack"].each { String id, String label ->
            ec.entity.makeValue("moqui.basic.Enumeration")
                    .setAll([enumId: id, enumTypeId: "DarpanChatProvider", description: label]).createOrUpdate()
        }
        ownerInstallId = SlackOAuthSupport.upsertInstall(ec, OWNER, TEST_USER_ID, [
                ok: true, teamId: "T_OWNER", teamName: "Owner Workspace", appId: "A1",
                botUserId: "U_BOT", botAccessToken: SECRET_TOKEN, grantedScopes: "chat:write",
        ])
        assertNotNull(ownerInstallId)
    }

    @AfterAll
    void cleanup() {
        System.clearProperty(SlackOAuthSupport.CLIENT_ID_PROPERTY)
        System.clearProperty(SlackOAuthSupport.REDIRECT_URI_PROPERTY)
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void asOwner() {
        ec.message.clearErrors()
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, OWNER)
    }

    @AfterEach
    void clearHooks() { SlackApiClient.resetTransportHook() }

    @Test
    void beginInstallRefusesWhenTheSlackAppIsNotConfigured() {
        System.clearProperty(SlackOAuthSupport.CLIENT_ID_PROPERTY)
        Map<String, Object> result = call("facade.SlackFacadeServices.begin#SlackInstall", [:])
        assertFalse(result.ok as boolean)
        // Refusing HERE rather than at Slack's own error page is the point: Slack's rejection of an
        // unregistered redirect_uri says nothing about Darpan being misconfigured.
        assertTrue((result.errors as List).join(" ").contains("not configured"))
    }

    @Test
    void beginInstallReturnsAnAuthorizeUrlCarryingAFreshState() {
        System.setProperty(SlackOAuthSupport.CLIENT_ID_PROPERTY, "123.456")
        System.setProperty(SlackOAuthSupport.REDIRECT_URI_PROPERTY,
                "https://api.example.com/apps/darpan/slackOauthCallback")

        Map<String, Object> first = call("facade.SlackFacadeServices.begin#SlackInstall", [:])
        assertTrue(first.ok as boolean, "${first.errors}")
        String urlOne = first.authorizeUrl as String
        assertTrue(urlOne.startsWith("https://slack.com/oauth/v2/authorize?"))
        assertTrue(urlOne.contains("client_id=123.456"))
        assertTrue(urlOne.contains("chat%3Awrite"))

        ec.message.clearErrors()
        String urlTwo = call("facade.SlackFacadeServices.begin#SlackInstall", [:]).authorizeUrl as String
        // A reused state would make the single-use guard meaningless.
        assertFalse(urlOne == urlTwo, "each install attempt must mint its own state")
    }

    @Test
    void getInstallNeverReturnsTheBotToken() {
        Map<String, Object> result = call("facade.SlackFacadeServices.get#SlackInstall", [:])
        assertTrue(result.ok as boolean)
        List<Map<String, Object>> installs = (List<Map<String, Object>>) result.installs
        assertEquals(1, installs.size())
        assertEquals("Owner Workspace", installs.first().teamName)
        assertFalse(result.toString().contains(SECRET_TOKEN),
                "the bot token leaked through get#SlackInstall")
    }

    @Test
    void anotherTenantCannotSeeThisTenantsInstall() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, OTHER)
        Map<String, Object> result = call("facade.SlackFacadeServices.get#SlackInstall", [:])
        assertEquals(0, ((List) result.installs).size())
    }

    @Test
    void anotherTenantCannotDriveThisTenantsWorkspace() {
        // The install id comes from the client, so ownership must be verified rather than assumed —
        // otherwise a tenant could list channels in, and post into, someone else's Slack.
        SlackApiClient.setTransportHook { String method, String token, Map params ->
            throw new IllegalStateException("Slack must never be contacted for a foreign install")
        }
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, OTHER)
        Map<String, Object> result = call("facade.SlackFacadeServices.list#SlackChannels",
                [slackInstallId: ownerInstallId])
        assertFalse(result.ok as boolean)
        assertTrue((result.errors as List).join(" ").contains("not connected"))
    }

    @Test
    void listChannelsFlagsPrivateChannelsNeedingAnInvite() {
        SlackApiClient.setTransportHook { String method, String token, Map params ->
            assertEquals(SECRET_TOKEN, token)
            return [statusCode: 200, body: '''{"ok":true,"channels":[
                {"id":"C1","name":"ops","is_private":false,"is_member":false},
                {"id":"G2","name":"secret","is_private":true,"is_member":false}],
                "response_metadata":{"next_cursor":""}}''']
        }
        Map<String, Object> result = call("facade.SlackFacadeServices.list#SlackChannels",
                [slackInstallId: ownerInstallId])
        assertTrue(result.ok as boolean, "${result.errors}")
        List<Map<String, Object>> channels = (List<Map<String, Object>>) result.channels
        assertEquals(2, channels.size())
        assertEquals(false, channels[1].isMember)
        assertEquals("U_BOT", result.botUserId)
    }

    @Test
    void aSlackFailureDuringChannelListingBecomesAnOperatorSentence() {
        SlackApiClient.setTransportHook { String method, String token, Map params ->
            return [statusCode: 200, body: '{"ok":false,"error":"token_revoked"}']
        }
        Map<String, Object> result = call("facade.SlackFacadeServices.list#SlackChannels",
                [slackInstallId: ownerInstallId])
        assertFalse(result.ok as boolean)
        assertTrue((result.errors as List).join(" ").contains("Reconnect"))
    }

    @Test
    void disconnectIsRefusedWhileAChatSpaceStillPostsThroughIt() {
        ec.entity.makeValue("darpan.reconciliation.TenantChatSpace").setAll([
                chatSpaceId       : "SPACE_USING_SLACK",
                companyUserGroupId: OWNER,
                spaceName         : "Ops Slack",
                chatProviderEnumId: "CHAT_PROV_SLACK",
                slackInstallId    : ownerInstallId,
                slackChannelId    : "C1",
                isActive          : "Y",
        ]).createOrUpdate()

        Map<String, Object> result = call("facade.SlackFacadeServices.disconnect#SlackWorkspace",
                [slackInstallId: ownerInstallId])
        assertFalse(result.ok as boolean)
        // Naming the spaces matters: a silent disconnect leaves them resolving to nothing and the
        // tenant finds out from missing notifications.
        assertTrue((result.errors as List).join(" ").contains("Ops Slack"))

        ec.entity.find("darpan.reconciliation.TenantChatSpace")
                .condition("chatSpaceId", "SPACE_USING_SLACK").disableAuthz().deleteAll()
    }

    @Test
    void aPastedBotTokenIsVerifiedWithSlackBeforeItIsStored() {
        SlackApiClient.setTransportHook { String method, String token, Map params ->
            assertEquals("auth.test", method)
            assertEquals("xoxb-pasted-token", token)
            return [statusCode: 200, scopes: "chat:write,chat:write.public,channels:read,groups:read",
                    body: '{"ok":true,"team":"Pasted Workspace","team_id":"T_PASTE","user":"darpan","user_id":"U_PASTE"}']
        }
        Map<String, Object> result = call("facade.SlackFacadeServices.save#SlackBotToken",
                [botAccessToken: "xoxb-pasted-token"])

        assertTrue(result.ok as boolean, "${result.errors}")
        Map<String, Object> install = (Map<String, Object>) result.install
        // Identity comes from Slack's answer, never from the caller.
        assertEquals("T_PASTE", install.teamId)
        assertEquals("Pasted Workspace", install.teamName)
        assertEquals("U_PASTE", install.botUserId)
        assertEquals(0, ((List) result.missingScopes).size())

        // Stored through the entity facade, so the encrypted column round-trips.
        assertEquals("xoxb-pasted-token",
                SlackOAuthSupport.resolveBotToken(ec, OWNER, install.slackInstallId))
    }

    @Test
    void aTokenSlackRejectsIsNeverStored() {
        // Without this check Darpan would accept any string and the tenant would find out only when
        // a run failed to notify — by which time the run is over and the alert is gone.
        SlackApiClient.setTransportHook { String method, String token, Map params ->
            return [statusCode: 200, scopes: "", body: '{"ok":false,"error":"invalid_auth"}']
        }
        Map<String, Object> result = call("facade.SlackFacadeServices.save#SlackBotToken",
                [botAccessToken: "xoxb-not-a-real-token"])
        assertFalse(result.ok as boolean)
        assertTrue((result.errors as List).join(" ").contains("Reconnect")
                || (result.errors as List).join(" ").contains("rejected"))
    }

    @Test
    void aUserTokenIsRefusedBeforeItReachesSlack() {
        // xoxp- authenticates perfectly well and then posts as the human who created it. It is the
        // likeliest paste mistake, both tokens sit on the same Slack settings page, and it is caught
        // without a network call.
        SlackApiClient.setTransportHook { String method, String token, Map params ->
            throw new IllegalStateException("a user token must be refused before contacting Slack")
        }
        Map<String, Object> result = call("facade.SlackFacadeServices.save#SlackBotToken",
                [botAccessToken: "xoxp-user-token-pasted-by-mistake"])
        assertFalse(result.ok as boolean)
        assertTrue((result.errors as List).join(" ").contains("xoxb-"))
    }

    @Test
    void aPartiallyScopedTokenIsAcceptedButNamesWhatWillNotWork() {
        // chat:write alone still delivers notifications, which is the point of the feature; refusing
        // it outright would block a working setup over a channel picker.
        SlackApiClient.setTransportHook { String method, String token, Map params ->
            return [statusCode: 200, scopes: "chat:write",
                    body: '{"ok":true,"team":"Thin","team_id":"T_THIN","user":"darpan","user_id":"U_THIN"}']
        }
        Map<String, Object> result = call("facade.SlackFacadeServices.save#SlackBotToken",
                [botAccessToken: "xoxb-thin-scopes"])
        assertTrue(result.ok as boolean, "${result.errors}")
        List<String> missing = (List<String>) result.missingScopes
        assertTrue(missing.contains("channels:read"))
        assertTrue(missing.contains("chat:write.public"))
        assertTrue((result.messages as List).join(" ").contains("Missing scope"))
    }

    @Test
    void oauthIsOnlyAdvertisedWhenTheDeploymentCanActuallyDoIt() {
        System.clearProperty(SlackOAuthSupport.CLIENT_ID_PROPERTY)
        Map<String, Object> without = call("facade.SlackFacadeServices.get#SlackInstall", [:])
        // Slack itself stays available — the token path needs no client id. Only the one-click
        // install is withheld. Conflating these once hid Slack entirely on this deployment.
        assertEquals(true, without.slackConfigured)
        assertEquals(false, without.oauthAvailable)

        ec.message.clearErrors()
        System.setProperty(SlackOAuthSupport.CLIENT_ID_PROPERTY, "123.456")
        System.setProperty(SlackOAuthSupport.REDIRECT_URI_PROPERTY, "https://api.example.com/apps/darpan/slackOauthCallback")
        Map<String, Object> with = call("facade.SlackFacadeServices.get#SlackInstall", [:])
        assertEquals(true, with.oauthAvailable)
    }

    private Map<String, Object> call(String serviceName, Map<String, Object> params) {
        return (Map<String, Object>) ec.service.sync().name(serviceName)
                .parameters(params).disableAuthz().call()
    }

    private void seedTenant(String tenantId, String label) {
        ec.entity.makeValue("moqui.security.UserGroup").setAll([
                userGroupId    : tenantId, description: label,
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID,
        ]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserGroupMember").setAll([
                userGroupId: tenantId, userId: TEST_USER_ID, fromDate: TEST_FROM_DATE,
        ]).createOrUpdate()
        grantEditor(tenantId)
    }

    private void grantEditor(String tenantId) {
        ec.entity.makeValue(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME).setAll([
                tenantUserGroupId    : tenantId,
                userId               : TEST_USER_ID,
                permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID,
                fromDate             : TEST_FROM_DATE,
        ]).createOrUpdate()
    }
}
