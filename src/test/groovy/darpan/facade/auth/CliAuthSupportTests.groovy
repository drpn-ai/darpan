package darpan.facade.auth

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.moqui.util.ContextStack

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-CLI-003 Task 2 — CliAuthSupport.isCliSession on a web request, with stubs (no Moqui boot).
 * The booted smoke class has no web request, so the header, cookie and memo paths are proved here.
 */
class CliAuthSupportTests {

    private static final String CLI_KEY = "cli-raw-key"
    private static final String APP_KEY = "app-raw-key"

    @Test
    @DisplayName("final review R26: the host label loses bidi and other format characters, not only control characters")
    void hostLabelStripsFormatCharacters() {
        // U+202E right-to-left override, U+200B zero-width space, U+2066 left-to-right isolate, U+FEFF BOM.
        String label = CliAuthSupport.normalizeHostLabel("laptop\u202Ecod.exe\u200B\u2066x\uFEFF\u0007y")
        assertEquals("laptop cod.exe x y", label)
        assertFalse(label.codePoints().anyMatch { int cp -> Character.getType(cp) == Character.FORMAT })
        assertNull(CliAuthSupport.normalizeHostLabel("\u202E\u200B"))
    }

    @Test
    @DisplayName("a login_key header that is a CLI key is a CLI session")
    void loginKeyHeaderIsACliSession() {
        assertTrue(CliAuthSupport.isCliSession(ec(new RequestStub(headers: [login_key: " ${CLI_KEY} ".toString()]))))
        assertTrue(CliAuthSupport.isCliSession(ec(new RequestStub(headers: [api_key: CLI_KEY]))))
    }

    @Test
    @DisplayName("an ordinary app key is not a CLI session")
    void anOrdinaryKeyIsNotACliSession() {
        assertFalse(CliAuthSupport.isCliSession(ec(new RequestStub(headers: [login_key: APP_KEY]))))
        assertFalse(CliAuthSupport.isCliSession(ec(new RequestStub())))
    }

    @Test
    @DisplayName("a CLI key sent as a parameter or the persistent-login cookie is still a CLI session")
    void parameterOrCookieKeyIsACliSession() {
        assertTrue(CliAuthSupport.isCliSession(ec(new RequestStub(), [login_key: CLI_KEY])))
        assertTrue(CliAuthSupport.isCliSession(ec(new RequestStub(cookies: [new CookieStub(name: "darpan_login_key", value: CLI_KEY)]))))
    }

    @Test
    @DisplayName("a false placed in the context by a request cannot hide a CLI key")
    void aContextFalseCannotHideACliKey() {
        def stubEc = ec(new RequestStub(headers: [login_key: CLI_KEY]))
        stubEc.context.darpanCliSession = false
        assertTrue(CliAuthSupport.isCliSession(stubEc))
    }

    @Test
    @DisplayName("a true memo is honoured without a lookup")
    void aTrueMemoIsHonoured() {
        def stubEc = ec(new RequestStub())
        stubEc.context.darpanCliSession = true
        assertTrue(CliAuthSupport.isCliSession(stubEc))
        assertEquals(0, stubEc.entity.lookups)
    }

    @Test
    @DisplayName("a true memo on the shared map survives the isolated context a service call pushes")
    void aSharedMapMemoSurvivesPushContext() {
        ContextStack context = new ContextStack()
        context.getSharedMap().put(CliAuthSupport.CLI_SESSION_CONTEXT_KEY, true)
        context.pushContext()
        def stubEc = new Expando(context: context, web: null, factory: new FactoryStub(), entity: new EntityStub())
        assertTrue(CliAuthSupport.isCliSession(stubEc))
    }

    @Test
    @DisplayName("a false on the shared map cannot hide a CLI key on a web request")
    void aSharedMapFalseCannotHideACliKey() {
        ContextStack context = new ContextStack()
        context.getSharedMap().put(CliAuthSupport.CLI_SESSION_CONTEXT_KEY, false)
        def stubEc = ec(new RequestStub(headers: [login_key: CLI_KEY]))
        stubEc.context = context
        assertTrue(CliAuthSupport.isCliSession(stubEc))
        assertEquals(Boolean.TRUE, context.getSharedMap().get(CliAuthSupport.CLI_SESSION_CONTEXT_KEY))
    }

    @Test
    @DisplayName("the answer is memoised on the request")
    void theAnswerIsMemoisedOnTheRequest() {
        def request = new RequestStub(headers: [login_key: CLI_KEY])
        def stubEc = ec(request)
        assertTrue(CliAuthSupport.isCliSession(stubEc))
        assertTrue(CliAuthSupport.isCliSession(stubEc))
        assertEquals(1, stubEc.entity.lookups)
        assertEquals(Boolean.TRUE, request.attributes[CliAuthSupport.CLI_SESSION_ATTRIBUTE])
    }

    @Test
    @DisplayName("a web session a CLI key signed in stays a CLI session without the header")
    void aWebSessionACliKeySignedInStaysACliSession() {
        def session = new SessionStub()
        assertTrue(CliAuthSupport.isCliSession(ec(new RequestStub(headers: [login_key: CLI_KEY], session: session))))
        assertTrue(CliAuthSupport.isCliSession(ec(new RequestStub(session: session))))
    }

    @Test
    @DisplayName("no web request and no memo is not a CLI session")
    void noWebRequestIsNotACliSession() {
        def stubEc = new Expando(context: [:], web: null, factory: new FactoryStub(), entity: new EntityStub())
        assertFalse(CliAuthSupport.isCliSession(stubEc))
        stubEc.context.darpanCliSession = false
        assertFalse(CliAuthSupport.isCliSession(stubEc))
    }

    // ---- revalidateCliWebSession (R9: revoke and disable end a CLI key's web session at once) ----

    @Test
    @DisplayName("revalidate: a live CLI key's session passes")
    void revalidateLiveSessionPasses() {
        def session = new SessionStub()
        def first = ec(new RequestStub(headers: [login_key: CLI_KEY], session: session))
        assertNull(CliAuthSupport.revalidateCliWebSession(first))
        assertEquals("hash:${CLI_KEY}".toString(), session.attributes[CliAuthSupport.CLI_SESSION_MARK_ATTRIBUTE],
                "the mark carries the key's hash, not a bare TRUE")
        def later = ec(new RequestStub(session: session), [:], first.entity)
        assertNull(CliAuthSupport.revalidateCliWebSession(later))
        assertFalse(session.invalidated)
        assertFalse(later.user.loggedOutLocal)
    }

    @Test
    @DisplayName("revalidate: a revoked key's marked session is refused and ended")
    void revalidateRevokedKeysSessionIsRefused() {
        def session = new SessionStub()
        def first = ec(new RequestStub(headers: [login_key: CLI_KEY], session: session))
        assertNull(CliAuthSupport.revalidateCliWebSession(first))

        first.entity.rows["darpan.auth.CliSession"][0].revokedDate = new Timestamp(System.currentTimeMillis() - 1000L)
        def later = ec(new RequestStub(session: session), [:], first.entity)   // JSESSIONID only, no key
        assertNotNull(CliAuthSupport.revalidateCliWebSession(later))
        assertTrue(session.invalidated, "the web session must be invalidated")
        assertTrue(later.user.loggedOutLocal, "the user must be logged out of this request")
    }

    @Test
    @DisplayName("revalidate: an expired key's marked session is refused")
    void revalidateExpiredKeyIsRefused() {
        def entity = new EntityStub()
        entity.rows["moqui.security.UserLoginKey"][0].thruDate = new Timestamp(System.currentTimeMillis() - 1000L)
        def session = new SessionStub(attributes: [(CliAuthSupport.CLI_SESSION_MARK_ATTRIBUTE): "hash:${CLI_KEY}".toString()])
        def stubEc = ec(new RequestStub(session: session), [:], entity)
        assertNotNull(CliAuthSupport.revalidateCliWebSession(stubEc))
        assertTrue(session.invalidated)
    }

    @Test
    @DisplayName("revalidate: a disabled user's marked session is refused")
    void revalidateDisabledUserIsRefused() {
        def entity = new EntityStub()
        entity.rows["moqui.security.UserAccount"][0].disabled = "Y"
        def session = new SessionStub(attributes: [(CliAuthSupport.CLI_SESSION_MARK_ATTRIBUTE): "hash:${CLI_KEY}".toString()])
        def stubEc = ec(new RequestStub(session: session), [:], entity)
        assertNotNull(CliAuthSupport.revalidateCliWebSession(stubEc))
        assertTrue(session.invalidated)
    }

    @Test
    @DisplayName("revalidate: a session with no mark is left alone")
    void revalidateUnmarkedSessionPasses() {
        def session = new SessionStub()
        def stubEc = ec(new RequestStub(headers: [login_key: APP_KEY], session: session))
        assertNull(CliAuthSupport.revalidateCliWebSession(stubEc))
        assertNull(session.attributes[CliAuthSupport.CLI_SESSION_MARK_ATTRIBUTE])
        assertFalse(session.invalidated)
        assertNull(CliAuthSupport.revalidateCliWebSession(new Expando(context: [:], web: null, entity: new EntityStub())))
    }

    // ---- stubs ----

    private static def ec(RequestStub request, Map secureParameters = [:], EntityStub entity = new EntityStub()) {
        def web = new Expando(request: request, secureRequestParameters: secureParameters)
        return new Expando(context: [:], web: web, factory: new FactoryStub(), entity: entity, user: new UserStub())
    }

    static class FactoryStub {
        String getLoginKeyHashType() { "SHA-256" }
        String getSimpleHash(String input, String salt, Object hashType, boolean base64) { "hash:${input}" }
    }

    static class UserStub {
        String userId = "CLI_USER"
        boolean loggedOutLocal = false
        void logoutLocal() { loggedOutLocal = true; userId = null }
    }

    /** One live CLI session for CLI_KEY: unrevoked, key valid for a day, account enabled. */
    static class EntityStub {
        int lookups = 0
        Map<String, List<Map>> rows = [
                "darpan.auth.CliSession"     : [[loginKeyHash: "hash:${CLI_KEY}".toString(), userId: "CLI_USER", revokedDate: null]],
                "moqui.security.UserLoginKey": [[loginKey: "hash:${CLI_KEY}".toString(), userId: "CLI_USER",
                                                 thruDate: new Timestamp(System.currentTimeMillis() + 86_400_000L)]],
                "moqui.security.UserAccount" : [[userId: "CLI_USER", username: "cli.user", disabled: "N"]],
        ]
        FinderStub find(String entityName) { lookups++; new FinderStub(rows: rows[entityName] ?: []) }
    }

    static class FinderStub {
        List<Map> rows = []
        Map<String, Object> conditions = [:]
        FinderStub disableAuthz() { this }
        FinderStub useCache(boolean ignored) { this }
        FinderStub condition(String field, Object value) { conditions[field] = value; this }
        private List<Map> matches() { rows.findAll { Map row -> conditions.every { k, v -> row[k]?.toString() == v?.toString() } } }
        long count() { matches().size() }
        Map one() { matches() ? matches()[0] : null }
    }

    static class RequestStub {
        Map<String, String> headers = [:]
        List<CookieStub> cookies = []
        Map<String, Object> attributes = [:]
        SessionStub session
        String getHeader(String name) { headers[name] }
        CookieStub[] getCookies() { cookies ? cookies.toArray(new CookieStub[0]) : null }
        Object getAttribute(String name) { attributes[name] }
        void setAttribute(String name, Object value) { attributes[name] = value }
        SessionStub getSession(boolean create) { session?.invalidated ? null : session }
    }

    static class SessionStub {
        Map<String, Object> attributes = [:]
        boolean invalidated = false
        Object getAttribute(String name) { attributes[name] }
        void setAttribute(String name, Object value) { attributes[name] = value }
        void invalidate() { invalidated = true; attributes.clear() }
    }

    static class CookieStub {
        String name
        String value
    }
}
