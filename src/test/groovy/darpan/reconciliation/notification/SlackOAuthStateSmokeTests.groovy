package darpan.reconciliation.notification

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * The Slack OAuth callback is a PUBLIC endpoint — Slack redirects the installing admin's browser to
 * it with no Darpan session attached. The state token is therefore the entire authorization, and
 * every property asserted here is load-bearing:
 *
 *  - unguessable, so it cannot be forged
 *  - single-use, so a replayed callback cannot re-attach a workspace
 *  - tenant-bound, so an attacker cannot attach their workspace to someone else's tenant
 *  - short-lived, so a leaked state is worthless by the time it is found
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SlackOAuthStateSmokeTests {
    private static final String TEN_A = "TEN_SLK_A"
    private static final String TEN_B = "TEN_SLK_B"

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "slack-oauth-state-smoke")
        ec.artifactExecution.disableAuthz()
        [TEN_A, TEN_B].each { String tenantId ->
            ec.entity.makeValue("moqui.security.UserGroup")
                    .setAll([userGroupId: tenantId, description: tenantId]).create()
        }
        ec.entity.makeValue("moqui.security.UserAccount")
                .setAll([userId: "USER_SLK", username: "slack.admin", currentPassword: ""]).create()
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @AfterEach
    void clearHook() { SlackOAuthSupport.resetExchangeHook() }

    @Test
    void aStateIsRedeemableExactlyOnce() {
        String stateToken = SlackOAuthSupport.mintState(ec, TEN_A, "USER_SLK")
        assertNotNull(stateToken)

        Map<String, Object> first = SlackOAuthSupport.consumeState(ec, stateToken)
        assertTrue(first.ok as boolean)
        assertEquals(TEN_A, first.companyUserGroupId)

        // A replay is either a double-clicked callback or an attack; both are refused identically.
        Map<String, Object> second = SlackOAuthSupport.consumeState(ec, stateToken)
        assertFalse(second.ok as boolean)
        assertEquals("STATE_ALREADY_USED", second.reason)
    }

    @Test
    void anUnknownStateIsRefused() {
        Map<String, Object> outcome = SlackOAuthSupport.consumeState(ec, "not-a-state-we-minted")
        assertFalse(outcome.ok as boolean)
        assertEquals("UNKNOWN_STATE", outcome.reason)
        assertNull(outcome.companyUserGroupId)
    }

    @Test
    void aBlankStateIsRefused() {
        assertEquals("MISSING_STATE", SlackOAuthSupport.consumeState(ec, null).reason)
        assertEquals("MISSING_STATE", SlackOAuthSupport.consumeState(ec, "   ").reason)
    }

    @Test
    void anExpiredStateIsRefusedAndStaysUnconsumed() {
        String stateToken = SlackOAuthSupport.mintState(ec, TEN_A, "USER_SLK")
        def stateRow = ec.entity.find("darpan.reconciliation.SlackOAuthState")
                .condition("stateToken", stateToken).disableAuthz().useCache(false).one()
        stateRow.set("expiresDate", new Timestamp(((Timestamp) ec.user.nowTimestamp).time - 60_000L))
        stateRow.update()

        Map<String, Object> outcome = SlackOAuthSupport.consumeState(ec, stateToken)
        assertFalse(outcome.ok as boolean)
        assertEquals("EXPIRED_STATE", outcome.reason)

        // Expiry must be checked BEFORE the claim: consuming an expired state would make the failure
        // indistinguishable from a replay on the next attempt.
        def afterRow = ec.entity.find("darpan.reconciliation.SlackOAuthState")
                .condition("stateToken", stateToken).disableAuthz().useCache(false).one()
        assertNull(afterRow.consumedDate)
    }

    @Test
    void eachMintedStateIsDistinct() {
        Set<String> seen = new HashSet<>()
        (1..25).each { seen.add(SlackOAuthSupport.mintState(ec, TEN_A, "USER_SLK")) }
        assertEquals(25, seen.size(), "state tokens must not repeat")
        seen.each { String token -> assertTrue(token.length() >= 40, "state token is too short to be unguessable: ${token}") }
    }

    @Test
    void aStateCarriesItsOwnTenantAndNotAnother() {
        String stateA = SlackOAuthSupport.mintState(ec, TEN_A, "USER_SLK")
        String stateB = SlackOAuthSupport.mintState(ec, TEN_B, "USER_SLK")
        assertNotEquals(stateA, stateB)
        assertEquals(TEN_A, SlackOAuthSupport.consumeState(ec, stateA).companyUserGroupId)
        assertEquals(TEN_B, SlackOAuthSupport.consumeState(ec, stateB).companyUserGroupId)
    }

    @Test
    void anInstallStoresTheBotTokenAndReInstallUpdatesRatherThanDuplicates() {
        Map<String, Object> exchange = [
                ok: true, teamId: "T_ACME", teamName: "Acme", appId: "A1",
                botUserId: "U_BOT", botAccessToken: "xoxb-first", grantedScopes: "chat:write",
        ]
        String installId = SlackOAuthSupport.upsertInstall(ec, TEN_A, "USER_SLK", exchange)
        assertNotNull(installId)
        assertEquals("xoxb-first", SlackOAuthSupport.resolveBotToken(ec, TEN_A, installId))

        // Slack issues a fresh token on every install and invalidates the old one, so a second row
        // would be a silently dead destination.
        Map<String, Object> reinstall = new LinkedHashMap<>(exchange)
        reinstall.put("botAccessToken", "xoxb-second")
        String secondId = SlackOAuthSupport.upsertInstall(ec, TEN_A, "USER_SLK", reinstall)
        assertEquals(installId, secondId)
        assertEquals("xoxb-second", SlackOAuthSupport.resolveBotToken(ec, TEN_A, installId))
        assertEquals(1, ec.entity.find("darpan.reconciliation.SlackWorkspaceInstall")
                .condition("companyUserGroupId", TEN_A).condition("teamId", "T_ACME")
                .disableAuthz().useCache(false).count())
    }

    @Test
    void aBotTokenIsNeverReadableFromAnotherTenant() {
        String installId = SlackOAuthSupport.upsertInstall(ec, TEN_A, "USER_SLK",
                [ok: true, teamId: "T_ISOLATED", teamName: "Isolated", botAccessToken: "xoxb-secret"])
        assertEquals("xoxb-secret", SlackOAuthSupport.resolveBotToken(ec, TEN_A, installId))
        // Same install id, wrong tenant. This is NOT a formality: expressing the pin as
        // .condition("companyUserGroupId", tenantId) alongside the complete primary key is silently
        // ignored by Moqui, so the naive version of resolveBotToken returned TEN_A's token here.
        assertNull(SlackOAuthSupport.resolveBotToken(ec, TEN_B, installId))
    }

    @Test
    void aDeactivatedInstallStopsResolvingItsToken() {
        String installId = SlackOAuthSupport.upsertInstall(ec, TEN_B, "USER_SLK",
                [ok: true, teamId: "T_OFF", teamName: "Off", botAccessToken: "xoxb-off"])
        def installRow = ec.entity.find("darpan.reconciliation.SlackWorkspaceInstall")
                .condition("slackInstallId", installId).disableAuthz().useCache(false).one()
        installRow.set("isActive", "N")
        installRow.update()
        assertNull(SlackOAuthSupport.resolveBotToken(ec, TEN_B, installId))
    }
}
