package darpan.reconciliation.notification

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * The parts of the install flow that need no database. The token-selection rule below is the one
 * most worth pinning: picking the wrong field yields a token that passes every manual test, because
 * whoever installs the app is nearly always a workspace admin.
 */
class SlackOAuthSupportTests {

    private static final Map<String, Object> REAL_SHAPE = [
            ok            : true,
            access_token  : "xoxb-bot-token",
            token_type    : "bot",
            scope         : "chat:write,chat:write.public,channels:read,groups:read",
            bot_user_id   : "U0KRQLJ9H",
            app_id        : "A0KRD7HC3",
            team          : [id: "T9TK3CUKW", name: "Acme"],
            authed_user   : [id: "U1234", scope: "chat:write", access_token: "xoxp-user-token", token_type: "user"],
    ]

    @Test
    void takesTheTopLevelBotTokenAndNeverTheUserToken() {
        Map<String, Object> parsed = SlackOAuthSupport.readExchangeBody(REAL_SHAPE)

        assertTrue(parsed.ok as boolean)
        assertEquals("xoxb-bot-token", parsed.botAccessToken)
        // The failure this guards: authed_user.access_token posts as the installing human rather
        // than as the app, and is scoped to that person's access.
        assertFalse((parsed.botAccessToken as String).startsWith("xoxp-"))
        assertEquals("T9TK3CUKW", parsed.teamId)
        assertEquals("Acme", parsed.teamName)
        assertEquals("U0KRQLJ9H", parsed.botUserId)
        assertEquals("A0KRD7HC3", parsed.appId)
    }

    @Test
    void rejectsAResponseWhoseTokenIsNotABotToken() {
        Map<String, Object> userTokenOnly = new LinkedHashMap<>(REAL_SHAPE)
        userTokenOnly.put("access_token", "xoxp-user-token")
        userTokenOnly.put("token_type", "user")

        Map<String, Object> parsed = SlackOAuthSupport.readExchangeBody(userTokenOnly)
        assertFalse(parsed.ok as boolean)
        assertEquals("NOT_A_BOT_TOKEN", parsed.reason)
    }

    @Test
    void rejectsAnErrorResponseAndKeepsSlacksReason() {
        Map<String, Object> parsed = SlackOAuthSupport.readExchangeBody([ok: false, error: "invalid_code"])
        assertFalse(parsed.ok as boolean)
        assertEquals("invalid_code", parsed.reason)
    }

    @Test
    void rejectsAnEmptyOrMissingBody() {
        assertFalse(SlackOAuthSupport.readExchangeBody(null).ok as boolean)
        assertFalse(SlackOAuthSupport.readExchangeBody([:]).ok as boolean)
        assertEquals("NO_BOT_TOKEN", SlackOAuthSupport.readExchangeBody([ok: true, token_type: "bot"]).reason)
    }

    @Test
    void theAuthorizeUrlCarriesEveryParameterSlackNeeds() {
        String url = SlackOAuthSupport.buildAuthorizeUrl(
                "123.456", "https://api.example.com/apps/darpan/slackOauthCallback", "st4te-t0ken")

        assertTrue(url.startsWith(SlackOAuthSupport.AUTHORIZE_URL + "?"))
        assertTrue(url.contains("client_id=123.456"))
        assertTrue(url.contains("state=st4te-t0ken"))
        // The redirect must be percent-encoded or Slack reads the query string as its own.
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Fapi.example.com%2Fapps%2Fdarpan%2FslackOauthCallback"))
        assertTrue(url.contains("chat%3Awrite"))
    }

    @Test
    void requestsExactlyTheScopesTheProductUses() {
        // Every scope here is load-bearing; an unused one is a permission the tenant is asked to
        // grant for nothing, and Slack's consent screen shows all of them.
        List<String> scopes = SlackOAuthSupport.REQUESTED_SCOPES.split(",").toList()
        assertEquals(["chat:write", "chat:write.public", "channels:read", "groups:read"], scopes)
    }
}
