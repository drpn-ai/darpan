package darpan.facade.auth

import org.junit.jupiter.api.Test

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Unit tests for {@link AuthSessionSupport#restoreAuthenticatedSession} and the logic
 * delegated by the {@code csrfToken} GET screen transition on {@code darpan.xml}.
 *
 * <h2>CSRF-exemption guarantee (structural, not testable in unit test)</h2>
 * The transition is mounted as a GET on the darpan root screen. Moqui's
 * {@code ScreenRenderImpl.groovy} ~L429 skips the CSRF check when
 * {@code request.getMethod().toLowerCase() == "get"}, so no {@code X-CSRF-Token}
 * header is required to reach {@code GET /apps/darpan/csrfToken}. This guarantee
 * is verified structurally (GET transitions are unconditionally exempt), and is
 * covered by integration/live-stack verification rather than this unit test.
 */
class AuthSessionSupportTests {

    // -----------------------------------------------------------------------
    // Happy path: valid persistent-login cookie → session restored, both fields
    // -----------------------------------------------------------------------

    @Test
    void csrfTokenEndpointRestoresSessionFromCookieAndReturnsBothFields() {
        // Arrange: unauthenticated request carrying a valid darpan_login_key cookie.
        // Mirrors a new browser tab where the SPA cannot obtain the CSRF token via
        // a POST (CSRF-rejected) but still has the HttpOnly cookie from a prior login.
        def message = new MessageFacadeStub()
        def user = new UserStub(userId: null, loginUserResult: true)

        Timestamp futureThruDate = new Timestamp(System.currentTimeMillis() + 86_400_000L)
        // Moqui EntityValue-like stub: must expose getTimestamp(String) for thruDate check
        // and property access for userId / loginKey.
        def loginKeyEntity = new Expando()
        loginKeyEntity.loginKey = "hash:cookie-session-key"   // FactoryStub.getSimpleHash returns "hash:<input>"
        loginKeyEntity.userId   = "EX_USER"
        loginKeyEntity.getTimestamp = { String field -> field == "thruDate" ? futureThruDate : null }

        def userAccountEntity = new Expando(userId: "EX_USER", username: "test.user")

        def entity = new EntityFacadeStub(finders: [
            "moqui.security.UserLoginKey": new FinderStub(oneResult: loginKeyEntity),
            "moqui.security.UserAccount" : new FinderStub(oneResult: userAccountEntity),
        ])
        def request  = new RequestStub(cookies: [new CookieStub(name: "darpan_login_key", value: "cookie-session-key")])
        def response = new ResponseStub()

        // Web stub: getSessionToken() simulates the HTTP session CSRF token that the
        // Moqui framework issues and exposes on the response.
        def webStub = new Expando(request: request, response: response)
        webStub.getSessionToken = { -> "csrf-test-token-abc" }

        def ec = buildEc(message: message, user: user, web: webStub, entity: entity)

        // Act: the csrfToken transition runs exactly these three lines.
        boolean restored      = AuthSessionSupport.restoreAuthenticatedSession(ec)
        boolean authenticated = ((ec?.user?.userId)?.toString()?.trim()) != null
        String  token         = ec?.web?.getSessionToken()

        // Assert
        assertTrue(restored,       "restoreAuthenticatedSession must return true for a valid persistent-login cookie")
        assertTrue(authenticated,  "authenticated must be true after cookie-based session restore")
        assertNotNull(token,       "CSRF session token must not be null for SPA bootstrap")
        assertEquals("csrf-test-token-abc", token)
        assertEquals("EX_USER", user.userId, "internalLoginUser must have populated userId on the UserStub")
    }

    // -----------------------------------------------------------------------
    // No cookie: not restored, not authenticated; but token still present
    // (Moqui issues session tokens to anonymous sessions too, so the SPA can
    //  cache the token before the user logs in.)
    // -----------------------------------------------------------------------

    @Test
    void csrfTokenEndpointReturnsUnauthenticatedWhenNoCookiePresent() {
        def message = new MessageFacadeStub()
        def user    = new UserStub(userId: null, loginUserResult: false)
        def request = new RequestStub(cookies: [])   // no cookie
        def webStub = new Expando(request: request, response: new ResponseStub())
        webStub.getSessionToken = { -> "anon-session-token" }

        def ec = buildEc(message: message, user: user, web: webStub)

        boolean restored      = AuthSessionSupport.restoreAuthenticatedSession(ec)
        boolean authenticated = ((ec?.user?.userId)?.toString()?.trim()) != null
        String  token         = ec?.web?.getSessionToken()

        assertFalse(restored,     "No cookie present — restore must return false")
        assertFalse(authenticated,"No cookie — must not be authenticated")
        // Token is still available: anonymous sessions have a CSRF token which the SPA can
        // hold onto before it logs in and starts issuing CSRF-protected POSTs.
        assertNotNull(token, "Session token must be non-null even for unauthenticated responses")
    }

    // -----------------------------------------------------------------------
    // Already-authenticated session: short-circuits without touching cookie
    // -----------------------------------------------------------------------

    @Test
    void csrfTokenEndpointReturnsAuthenticatedTrueWhenSessionAlreadyActive() {
        def message = new MessageFacadeStub()
        def user    = new UserStub(userId: "EXISTING_USER")   // already logged in
        def webStub = new Expando(request: new RequestStub(), response: new ResponseStub())
        webStub.getSessionToken = { -> "already-active-csrf-token" }

        def ec = buildEc(message: message, user: user, web: webStub)

        boolean restored      = AuthSessionSupport.restoreAuthenticatedSession(ec)
        boolean authenticated = ((ec?.user?.userId)?.toString()?.trim()) != null
        String  token         = ec?.web?.getSessionToken()

        // restoreAuthenticatedSession short-circuits and returns true when userId is already set.
        assertTrue(restored,     "Already authenticated — restore must return true (no-op)")
        assertTrue(authenticated,"Already authenticated — must report authenticated=true")
        assertEquals("already-active-csrf-token", token)
    }

    // -----------------------------------------------------------------------
    // Expired key: thruDate in the past → false returned, cookie cleared
    // -----------------------------------------------------------------------

    @Test
    void csrfTokenEndpointReturnsFalseAndClearsCookieWhenLoginKeyIsExpired() {
        // Arrange: unauthenticated request carrying a cookie whose corresponding
        // UserLoginKey.thruDate is in the past. The session must NOT be restored
        // and the stale cookie must be proactively cleared (Max-Age=0).
        def message = new MessageFacadeStub()
        def user    = new UserStub(userId: null, loginUserResult: false)

        Timestamp pastThruDate = new Timestamp(System.currentTimeMillis() - 86_400_000L)
        def loginKeyEntity = new Expando()
        loginKeyEntity.loginKey = "hash:expired-cookie-key"  // FactoryStub.getSimpleHash returns "hash:<input>"
        loginKeyEntity.userId   = "EX_USER"
        loginKeyEntity.getTimestamp = { String field -> field == "thruDate" ? pastThruDate : null }

        def entity   = new EntityFacadeStub(finders: [
            "moqui.security.UserLoginKey": new FinderStub(oneResult: loginKeyEntity),
        ])
        def request  = new RequestStub(cookies: [new CookieStub(name: "darpan_login_key", value: "expired-cookie-key")])
        def response = new ResponseStub()
        def webStub  = new Expando(request: request, response: response)
        webStub.getSessionToken = { -> "anon-csrf-token" }

        def ec = buildEc(message: message, user: user, web: webStub, entity: entity)

        // Act
        boolean restored      = AuthSessionSupport.restoreAuthenticatedSession(ec)
        boolean authenticated = ((ec?.user?.userId)?.toString()?.trim()) != null

        // Assert: expired key must not restore a session
        assertFalse(restored,      "Expired login key must not restore the session")
        assertFalse(authenticated, "Expired login key must not result in an authenticated session")

        // Assert: the clear-cookie header must have been written (Max-Age=0 signals browser deletion)
        boolean cookieCleared = response.headers.any { h ->
            h.name == "Set-Cookie" &&
            h.value?.contains("${AuthSessionSupport.PERSISTENT_LOGIN_COOKIE_NAME}=") &&
            h.value?.contains("Max-Age=0")
        }
        assertTrue(cookieCleared, "Expired key must trigger a Set-Cookie: Max-Age=0 clear header")
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static def buildEc(Map overrides = [:]) {
        return new Expando(
            message: overrides.message ?: new MessageFacadeStub(),
            user:    overrides.user    ?: new UserStub(),
            web:     overrides.web     ?: { def w = new Expando(request: new RequestStub(), response: new ResponseStub()); w.getSessionToken = { -> null }; w }(),
            factory: overrides.factory ?: new FactoryStub(),
            entity:  overrides.entity  ?: new EntityFacadeStub(),
            l10n:    new Expando(locale: Locale.forLanguageTag("en-US"), timeZone: "Asia/Kolkata")
        )
    }

    // -----------------------------------------------------------------------
    // Stubs
    // -----------------------------------------------------------------------

    static class MessageFacadeStub {
        final List<String> errors = []
        void addError(String m) { errors.add(m) }
        boolean hasError()      { !errors.isEmpty() }
    }

    static class UserStub {
        String  userId
        String  username
        boolean loginUserResult = false

        /** Called by AuthSessionSupport.restoreAuthenticatedSession when rebuilding the session. */
        boolean internalLoginUser(String uname) {
            if (loginUserResult) {
                userId   = "EX_USER"
                username = uname
            }
            return loginUserResult
        }
    }

    static class RequestStub {
        Map<String, String> headers = [:]
        List<CookieStub>    cookies = []
        boolean secure              = false
        String  serverName          = "darpan.example.com"

        String getHeader(String name)       { headers[name] }
        CookieStub[] getCookies()           { cookies ? cookies.toArray(new CookieStub[0]) : null }
        boolean isSecure()                  { secure }
        String  getServerName()             { serverName }
    }

    static class ResponseStub {
        List<Map<String, String>> headers = []
        void addHeader(String name, String value) { headers.add([name: name, value: value]) }
    }

    static class CookieStub {
        String name
        String value
    }

    static class FactoryStub {
        String getLoginKeyHashType() { "SHA-256" }
        String getSimpleHash(String input, String salt, Object hashType, boolean base64) { "hash:${input}" }
    }

    static class EntityFacadeStub {
        Map<String, FinderStub> finders = [:]

        FinderStub find(String entityName) {
            finders[entityName] ?: new FinderStub()
        }
    }

    static class FinderStub {
        Map<String, Object> conditions = [:]
        Object oneResult

        FinderStub condition(String field, Object value) { conditions[field] = value; this }
        FinderStub disableAuthz()                        { this }

        Object one() {
            // When oneResult is a Map, filter by recorded conditions.
            // Expando and other non-Map objects are returned as-is (condition check not applicable).
            if (oneResult instanceof Map && !conditions.every { k, v -> oneResult[k] == v }) return null
            return oneResult
        }
    }
}
