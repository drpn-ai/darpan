package darpan.facade.auth

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.moqui.util.ContextStack

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-CLI-003 Task 2 — CliAuthSupport.isCliSession on a web request, with stubs (no Moqui boot).
 * The booted smoke class has no web request, so the header, cookie and memo paths are proved here.
 */
class CliAuthSupportTests {

    private static final String CLI_KEY = "cli-raw-key"
    private static final String APP_KEY = "app-raw-key"

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

    // ---- stubs ----

    private static def ec(RequestStub request, Map secureParameters = [:]) {
        def web = new Expando(request: request, secureRequestParameters: secureParameters)
        return new Expando(context: [:], web: web, factory: new FactoryStub(), entity: new EntityStub())
    }

    static class FactoryStub {
        String getLoginKeyHashType() { "SHA-256" }
        String getSimpleHash(String input, String salt, Object hashType, boolean base64) { "hash:${input}" }
    }

    static class EntityStub {
        int lookups = 0
        FinderStub find(String entityName) { lookups++; new FinderStub() }
    }

    static class FinderStub {
        Map<String, Object> conditions = [:]
        FinderStub disableAuthz() { this }
        FinderStub useCache(boolean ignored) { this }
        FinderStub condition(String field, Object value) { conditions[field] = value; this }
        long count() { conditions.loginKeyHash?.toString() == "hash:${CLI_KEY}" ? 1L : 0L }
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
        SessionStub getSession(boolean create) { session }
    }

    static class SessionStub {
        Map<String, Object> attributes = [:]
        Object getAttribute(String name) { attributes[name] }
        void setAttribute(String name, Object value) { attributes[name] = value }
    }

    static class CookieStub {
        String name
        String value
    }
}
