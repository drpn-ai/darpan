package darpan.facade.auth

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-261 — AuthSessionSupport.enforceWebSignInLifetime, the webroot before-request check that slides a web
 * sign-in's key one idle window on and ends the sign-in at the idle timeout or 24 hours after sign-in. Stubs, no boot:
 * the booted smoke harness has no web request.
 */
class WebSignInLifetimeTests {

    private static final long MINUTE = 60_000L
    private static final long HOUR = 60 * MINUTE
    private static final String APP_KEY = "app-raw-key"
    private static final String CLI_KEY = "cli-raw-key"
    private static final String USER = "WEB_USER"

    @Test
    @DisplayName("an active key slides one idle window on, never past sign-in + 24 hours")
    void anActiveKeySlides() {
        long now = System.currentTimeMillis()
        Timestamp signedIn = new Timestamp(now - 2 * HOUR)
        assertEquals(now + HOUR, AuthSessionSupport.slidThruDate(signedIn, now, 24 * HOUR).time)
        assertEquals(signedIn.time + 24 * HOUR, AuthSessionSupport.slidThruDate(signedIn, signedIn.time + 23 * HOUR + 30 * MINUTE, 24 * HOUR).time,
                "half an hour before the cap, the key ends at the cap, not an hour on")

        def entity = new EntityStub(keys: [key(APP_KEY, now - 2 * HOUR, now + 20 * MINUTE)])
        def session = new SessionStub(creationTime: now - 5 * MINUTE)
        def ec = ec(new RequestStub(headers: [login_key: APP_KEY], session: session), entity)

        assertFalse(AuthSessionSupport.enforceWebSignInLifetime(ec))
        long slid = (entity.keys[0].thruDate as Timestamp).time
        assertTrue(Math.abs(slid - (now + HOUR)) < MINUTE, "slid to an hour from now, got ${new Timestamp(slid)}")
        assertEquals(USER, ec.user.userId)
        assertFalse(session.invalidated)
    }

    @Test
    @DisplayName("a key slid within the last minute is not written again")
    void aFreshlySlidKeyIsNotRewritten() {
        long now = System.currentTimeMillis()
        def entity = new EntityStub(keys: [key(APP_KEY, now - HOUR, now + HOUR - 10_000L)])
        assertFalse(AuthSessionSupport.enforceWebSignInLifetime(ec(new RequestStub(headers: [login_key: APP_KEY]), entity)))
        assertEquals(0, entity.writes)
    }

    @Test
    @DisplayName("idle past its end: the session the framework restored without the key is signed out")
    void anIdleKeyEndsTheSignIn() {
        long now = System.currentTimeMillis()
        def entity = new EntityStub(keys: [key(APP_KEY, now - 3 * HOUR, now - MINUTE)])
        def session = new SessionStub(creationTime: now - 3 * HOUR)
        def ec = ec(new RequestStub(headers: [login_key: APP_KEY], session: session), entity)

        assertTrue(AuthSessionSupport.enforceWebSignInLifetime(ec))
        assertNull(ec.user.userId)
        assertTrue(session.invalidated)
        assertEquals(0, entity.writes, "an ended key is not slid back to life")
    }

    @Test
    @DisplayName("a key issued before the change (144 hours) is cut to sign-in + 24 hours and, past that, ends the sign-in")
    void aLegacyKeyIsCappedAtTwentyFourHours() {
        long now = System.currentTimeMillis()
        long signedIn = now - 30 * HOUR
        def entity = new EntityStub(keys: [key(APP_KEY, signedIn, signedIn + 144 * HOUR)])
        def ec = ec(new RequestStub(headers: [login_key: APP_KEY], session: new SessionStub(creationTime: now)), entity)

        assertTrue(AuthSessionSupport.enforceWebSignInLifetime(ec))
        assertEquals(signedIn + 24 * HOUR, (entity.keys[0].thruDate as Timestamp).time, "written as ended, so the framework refuses it next time")
        assertNull(ec.user.userId)
    }

    @Test
    @DisplayName("a web session older than 24 hours ends even with no key on the request")
    void anOldSessionEnds() {
        long now = System.currentTimeMillis()
        def session = new SessionStub(creationTime: now - 24 * HOUR - MINUTE)
        assertTrue(AuthSessionSupport.enforceWebSignInLifetime(ec(new RequestStub(session: session), new EntityStub())))
        assertTrue(session.invalidated)

        def young = new SessionStub(creationTime: now - 23 * HOUR)
        assertFalse(AuthSessionSupport.enforceWebSignInLifetime(ec(new RequestStub(session: young), new EntityStub())))
        assertFalse(young.invalidated)
    }

    @Test
    @DisplayName("only the key presented first decides: a stale cookie beside a live header key is not a sign-out")
    void aStaleCookieBesideALiveHeaderIsNoSignOut() {
        long now = System.currentTimeMillis()
        def entity = new EntityStub(keys: [key(APP_KEY, now - HOUR, now + 30 * MINUTE), key("old-cookie-key", now - 3 * HOUR, now - HOUR)])
        def request = new RequestStub(headers: [login_key: APP_KEY], cookies: [new CookieStub(name: "darpan_login_key", value: "old-cookie-key")])
        def ec = ec(request, entity)
        assertFalse(AuthSessionSupport.enforceWebSignInLifetime(ec))
        assertEquals(USER, ec.user.userId)
    }

    @Test
    @DisplayName("a CLI key keeps its own lifetime; another user's key and an anonymous request are left alone")
    void cliKeysOtherUsersAndAnonymousAreLeftAlone() {
        long now = System.currentTimeMillis()
        Timestamp cliEnd = new Timestamp(now + 6 * 24 * HOUR)
        def cliEntity = new EntityStub(keys: [key(CLI_KEY, now - 24 * HOUR - MINUTE, cliEnd.time)],
                cliSessions: [[loginKeyHash: "hash:${CLI_KEY}".toString(), userId: USER]])
        def cliEc = ec(new RequestStub(headers: [login_key: CLI_KEY], session: new SessionStub(creationTime: now - 25 * HOUR)), cliEntity)
        assertFalse(AuthSessionSupport.enforceWebSignInLifetime(cliEc))
        assertEquals(cliEnd, cliEntity.keys[0].thruDate)
        assertEquals(USER, cliEc.user.userId)

        def othersEntity = new EntityStub(keys: [key(APP_KEY, now - 30 * HOUR, now + HOUR, "SOMEONE_ELSE")])
        assertFalse(AuthSessionSupport.enforceWebSignInLifetime(ec(new RequestStub(headers: [login_key: APP_KEY]), othersEntity)))
        assertEquals(0, othersEntity.writes)

        def anonEc = ec(new RequestStub(headers: [login_key: APP_KEY]), new EntityStub(keys: [key(APP_KEY, now - HOUR, now + HOUR)]))
        anonEc.user.userId = null
        assertFalse(AuthSessionSupport.enforceWebSignInLifetime(anonEc))
    }

    @Test
    @DisplayName("a new key starts with the 60-minute idle window, or the absolute lifetime when that is shorter")
    void theFirstKeyIsTheIdleWindow() {
        assertEquals(1.0f, AuthSessionSupport.initialLoginKeyHours(new Expando(factory: new FactoryStub(expireHours: 24.0f))))
        assertEquals(0.5f, AuthSessionSupport.initialLoginKeyHours(new Expando(factory: new FactoryStub(expireHours: 0.5f))))
    }

    // ---- stubs ----

    private static Map key(String raw, long fromMillis, long thruMillis, String userId = USER) {
        return [loginKey: "hash:${raw}".toString(), userId: userId, fromDate: new Timestamp(fromMillis), thruDate: new Timestamp(thruMillis)]
    }

    private static def ec(RequestStub request, EntityStub entity) {
        def web = new Expando(request: request, secureRequestParameters: [:])
        def transaction = new Expando()
        transaction.runUseOrBegin = { Integer timeout, String message, Closure work -> work.call() }
        return new Expando(context: [:], web: web, factory: new FactoryStub(), entity: entity, user: new UserStub(),
                transaction: transaction)
    }

    static class FactoryStub {
        float expireHours = 24.0f
        float getLoginKeyExpireHours() { expireHours }
        String getLoginKeyHashType() { "SHA-256" }
        String getSimpleHash(String input, String salt, Object hashType, boolean base64) { "hash:${input}" }
    }

    static class UserStub {
        String userId = USER
        void logoutLocal() { userId = null }
    }

    static class EntityStub {
        List<Map> keys = []
        List<Map> cliSessions = []
        int writes = 0
        FinderStub find(String entityName) {
            if (entityName == "moqui.security.UserLoginKey") return new FinderStub(rows: keys, owner: this)
            if (entityName == "darpan.auth.CliSession") return new FinderStub(rows: cliSessions, owner: this)
            return new FinderStub(owner: this)
        }
    }

    static class FinderStub {
        List<Map> rows = []
        EntityStub owner
        Map<String, Object> conditions = [:]
        FinderStub disableAuthz() { this }
        FinderStub useCache(boolean ignored) { this }
        FinderStub condition(String field, Object value) { conditions[field] = value; this }
        private List<Map> matches() { rows.findAll { Map row -> conditions.every { k, v -> row[k]?.toString() == v?.toString() } } }
        long count() { matches().size() }
        Map one() { matches() ? matches()[0] : null }
        long updateAll(Map fields) {
            List<Map> hit = matches()
            hit.each { it.putAll(fields) }
            owner.writes += hit.size()
            return hit.size()
        }
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
        long creationTime = System.currentTimeMillis()
        Map<String, Object> attributes = [:]
        boolean invalidated = false
        Object getAttribute(String name) { attributes[name] }
        void setAttribute(String name, Object value) { attributes[name] = value }
        void invalidate() { invalidated = true }
    }

    static class CookieStub {
        String name
        String value
    }
}
