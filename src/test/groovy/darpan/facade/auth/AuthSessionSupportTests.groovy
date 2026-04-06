package darpan.facade.auth

import org.junit.jupiter.api.Test

import java.sql.Timestamp
import java.util.Locale

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class AuthSessionSupportTests {

    @Test
    void writePersistentLoginCookieSetsExpectedAttributes() {
        ResponseStub response = new ResponseStub()
        def ec = executionContext(
                request: new RequestStub(headers: ["X-Forwarded-Proto": "https"]),
                response: response,
                factory: new FactoryStub(expireHours: 2.5f)
        )

        AuthSessionSupport.writePersistentLoginCookie(ec, "issued-key")

        assertEquals(1, response.cookieHeaders.size())
        String header = response.cookieHeaders[0]
        assertTrue(header.startsWith("darpan_pilot_login_key=issued-key"))
        assertTrue(header.contains("; Max-Age=9000"))
        assertTrue(header.contains("; Path=/"))
        assertTrue(header.contains("; HttpOnly"))
        assertTrue(header.contains("; SameSite=Lax"))
        assertTrue(header.contains("; Secure"))
    }

    @Test
    void writePersistentLoginCookieUsesSameSiteNoneForCrossSiteSecureRequests() {
        ResponseStub response = new ResponseStub()
        def ec = executionContext(
                request: new RequestStub(headers: [
                        "Origin"           : "https://hotwax-darpan-dev.web.app",
                        "Host"             : "darpan-uat.hotwax.io",
                        "X-Forwarded-Proto": "https",
                ]),
                response: response
        )

        AuthSessionSupport.writePersistentLoginCookie(ec, "issued-key")

        assertEquals(1, response.cookieHeaders.size())
        String header = response.cookieHeaders[0]
        assertTrue(header.contains("; SameSite=None"))
        assertTrue(header.contains("; Secure"))
    }

    @Test
    void restoreAuthenticatedSessionClearsCookieWhenLoginKeyIsInvalid() {
        FinderStub keyFinder = new FinderStub(oneResult: null)
        EntityFacadeStub entity = new EntityFacadeStub(finders: ["moqui.security.UserLoginKey": keyFinder])
        ResponseStub response = new ResponseStub()
        def ec = executionContext(
                request: new RequestStub(cookies: [new CookieStub(name: AuthSessionSupport.PERSISTENT_LOGIN_COOKIE_NAME, value: "stale-key")]),
                response: response,
                entity: entity
        )

        assertFalse(AuthSessionSupport.restoreAuthenticatedSession(ec))

        assertEquals(1, response.cookieHeaders.size())
        String header = response.cookieHeaders[0]
        assertTrue(header.startsWith("darpan_pilot_login_key="))
        assertTrue(header.contains("; Max-Age=0"))
        assertTrue(header.contains("; Expires=Thu, 01 Jan 1970 00:00:00 GMT"))
    }

    @Test
    void restoreAuthenticatedSessionLogsUserInWhenCookieMatchesActiveKey() {
        FinderStub keyFinder = new FinderStub(oneResult: new EntityValueStub([
                userId  : "EX_USER",
                thruDate: new Timestamp(System.currentTimeMillis() + 60_000L),
        ]))
        FinderStub accountFinder = new FinderStub(oneResult: new EntityValueStub([
                username: "pilot.user"
        ]))
        EntityFacadeStub entity = new EntityFacadeStub(finders: [
                "moqui.security.UserLoginKey": keyFinder,
                "moqui.security.UserAccount" : accountFinder,
        ])
        UserStub user = new UserStub()
        def ec = executionContext(
                request: new RequestStub(cookies: [new CookieStub(name: AuthSessionSupport.PERSISTENT_LOGIN_COOKIE_NAME, value: "live-key")]),
                entity: entity,
                user: user
        )

        assertTrue(AuthSessionSupport.restoreAuthenticatedSession(ec))
        assertEquals(["pilot.user"], user.internalLoginUserCalls)
        assertEquals("EX_RESTORED", user.userId)
        assertTrue(((ResponseStub) ec.web.response).cookieHeaders.isEmpty())
    }

    @Test
    void revokePersistentLoginDeletesMatchingUserLoginKey() {
        FinderStub keyFinder = new FinderStub(deleteAllResult: 1)
        EntityFacadeStub entity = new EntityFacadeStub(finders: ["moqui.security.UserLoginKey": keyFinder])
        def ec = executionContext(
                request: new RequestStub(cookies: [new CookieStub(name: AuthSessionSupport.PERSISTENT_LOGIN_COOKIE_NAME, value: "logout-key")]),
                entity: entity
        )

        assertTrue(AuthSessionSupport.revokePersistentLogin(ec))
        assertEquals("hash:logout-key", keyFinder.conditions["loginKey"])
        assertEquals(1, keyFinder.deleteAllCalls)
    }

    @Test
    void buildSessionInfoIncludesCustomerScopeMetadata() {
        def ec = executionContext(user: new UserStub(userId: "CUST_100", username: "pilot.customer"))

        Map<String, Object> sessionInfo = AuthSessionSupport.buildSessionInfo(ec)

        assertEquals("CUST_100", sessionInfo.userId)
        assertEquals("pilot.customer", sessionInfo.username)
        assertEquals("CUSTOMER", sessionInfo.scopeType)
        assertEquals("CUST_100", sessionInfo.customerScopeId)
        assertFalse(sessionInfo.isSuperAdmin as boolean)
    }

    @Test
    void buildAuthContractMarksAuthenticatedSessionWithExplicitSource() {
        def ec = executionContext(user: new UserStub(userId: "CUST_100", username: "pilot.customer"))

        Map<String, Object> authContract = AuthSessionSupport.buildAuthContract(ec, AuthSessionSupport.AUTH_SOURCE_PERSISTENT_LOGIN)

        assertEquals("AUTHENTICATED", authContract.authState)
        assertEquals("PERSISTENT_LOGIN", authContract.authSource)
        assertEquals("CUST_100", authContract.sessionInfo.userId)
    }

    @Test
    void buildAuthContractMarksUnauthenticatedStateWithoutSessionInfo() {
        def ec = executionContext(user: new UserStub())

        Map<String, Object> authContract = AuthSessionSupport.buildAuthContract(ec, AuthSessionSupport.AUTH_SOURCE_PASSWORD_LOGIN)

        assertEquals("UNAUTHENTICATED", authContract.authState)
        assertEquals("NONE", authContract.authSource)
        assertEquals(null, authContract.sessionInfo)
    }

    @Test
    void buildSessionInfoContractKeepsActiveSessionSourceWhenUserIsAlreadyAuthenticated() {
        def ec = executionContext(user: new UserStub(userId: "EX_ACTIVE", username: "pilot.active"))

        Map<String, Object> authContract = AuthSessionSupport.buildSessionInfoContract(ec)

        assertEquals("AUTHENTICATED", authContract.authState)
        assertEquals("ACTIVE_SESSION", authContract.authSource)
        assertEquals(false, authContract.sessionRestored)
        assertEquals("EX_ACTIVE", authContract.sessionInfo.userId)
    }

    @Test
    void buildSessionInfoContractMarksPersistentLoginOnlyWhenCookieRestoreActuallyOccurs() {
        FinderStub keyFinder = new FinderStub(oneResult: new EntityValueStub([
                userId  : "EX_USER",
                thruDate: new Timestamp(System.currentTimeMillis() + 60_000L),
        ]))
        FinderStub accountFinder = new FinderStub(oneResult: new EntityValueStub([
                username: "pilot.user"
        ]))
        EntityFacadeStub entity = new EntityFacadeStub(finders: [
                "moqui.security.UserLoginKey": keyFinder,
                "moqui.security.UserAccount" : accountFinder,
        ])
        UserStub user = new UserStub()
        def ec = executionContext(
                request: new RequestStub(cookies: [new CookieStub(name: AuthSessionSupport.PERSISTENT_LOGIN_COOKIE_NAME, value: "live-key")]),
                entity: entity,
                user: user
        )

        Map<String, Object> authContract = AuthSessionSupport.buildSessionInfoContract(ec)

        assertEquals("AUTHENTICATED", authContract.authState)
        assertEquals("PERSISTENT_LOGIN", authContract.authSource)
        assertEquals(true, authContract.sessionRestored)
        assertEquals("EX_RESTORED", authContract.sessionInfo.userId)
    }

    private static Expando executionContext(Map overrides = [:]) {
        RequestStub request = overrides.request ?: new RequestStub()
        ResponseStub response = overrides.response ?: new ResponseStub()
        FactoryStub factory = overrides.factory ?: new FactoryStub()
        EntityFacadeStub entity = overrides.entity ?: new EntityFacadeStub()
        UserStub user = overrides.user ?: new UserStub()

        return new Expando(
                user: user,
                web: new Expando(request: request, response: response),
                factory: factory,
                entity: entity,
                l10n: new Expando(locale: Locale.forLanguageTag("en-US"), timeZone: "Asia/Kolkata")
        )
    }

    static class UserStub {
        String userId
        String username
        Expando userAccount = new Expando(timeZone: "Asia/Kolkata")
        List<String> internalLoginUserCalls = []
        boolean internalLoginUserResult = true

        boolean internalLoginUser(String loginUsername) {
            internalLoginUserCalls << loginUsername
            if (internalLoginUserResult) {
                userId = "EX_RESTORED"
                username = loginUsername
            }
            return internalLoginUserResult
        }
    }

    static class RequestStub {
        List<CookieStub> cookies = []
        Map<String, String> headers = [:]
        boolean secure = false

        CookieStub[] getCookies() {
            return cookies as CookieStub[]
        }

        String getHeader(String name) {
            return headers[name]
        }

        boolean isSecure() {
            return secure
        }
    }

    static class ResponseStub {
        List<String> cookieHeaders = []

        void addHeader(String name, String value) {
            if (name == "Set-Cookie") cookieHeaders << value
        }
    }

    static class CookieStub {
        String name
        String value
    }

    static class FactoryStub {
        float expireHours = 144.0f

        float getLoginKeyExpireHours() {
            return expireHours
        }

        String getLoginKeyHashType() {
            return "SHA-256"
        }

        String getSimpleHash(String input, String salt, Object hashType, boolean base64) {
            return "hash:${input}"
        }
    }

    static class EntityFacadeStub {
        Map<String, FinderStub> finders = [:]

        FinderStub find(String entityName) {
            FinderStub finder = finders[entityName]
            if (finder == null) {
                finder = new FinderStub()
                finders[entityName] = finder
            }
            return finder
        }
    }

    static class FinderStub {
        Map<String, Object> conditions = [:]
        Object oneResult
        int deleteAllResult = 0
        int deleteAllCalls = 0

        FinderStub condition(String field, Object value) {
            conditions[field] = value
            return this
        }

        FinderStub disableAuthz() {
            return this
        }

        Object one() {
            return oneResult
        }

        int deleteAll() {
            deleteAllCalls++
            return deleteAllResult
        }
    }

    static class EntityValueStub extends GroovyObjectSupport {
        private final Map<String, Object> values

        EntityValueStub(Map<String, Object> values) {
            this.values = values
        }

        @Override
        Object getProperty(String name) {
            return values[name]
        }

        Timestamp getTimestamp(String name) {
            return values[name] as Timestamp
        }

        String getString(String name) {
            return values[name]?.toString()
        }
    }
}
