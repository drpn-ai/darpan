package darpan.facade.auth

import org.junit.jupiter.api.Test

import java.util.Locale

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

class AuthSessionSupportTests {

    @Test
    void issueAuthTokenReturnsExplicitLoginKey() {
        UserStub user = new UserStub(userId: "EX_USER", username: "pilot.user", loginKey: "issued-token")
        def ec = executionContext(user: user)

        assertEquals("issued-token", AuthSessionSupport.issueAuthToken(ec))
    }

    @Test
    void buildAuthTokenContractReturnsHeaderMetadataAndExpiry() {
        def ec = executionContext(factory: new FactoryStub(expireHours: 2.0f))

        Map<String, Object> contract = AuthSessionSupport.buildAuthTokenContract(ec, "issued-token")

        assertEquals("issued-token", contract.authToken)
        assertEquals("LOGIN_KEY", contract.authTokenType)
        assertEquals("login_key", contract.authTokenHeaderName)
        assertEquals(7200, contract.authTokenExpiresInSeconds)
    }

    @Test
    void buildAuthTokenContractSkipsBlankTokens() {
        Map<String, Object> contract = AuthSessionSupport.buildAuthTokenContract(executionContext(), "   ")

        assertTrue(contract.isEmpty())
    }

    @Test
    void readRequestAuthTokenUsesLoginKeyHeader() {
        def ec = executionContext(request: new RequestStub(headers: ["login_key": "header-token"]))

        assertEquals("header-token", AuthSessionSupport.readRequestAuthToken(ec))
    }

    @Test
    void readRequestAuthTokenFallsBackToApiKeyParameter() {
        def ec = executionContext(request: new RequestStub(parameters: ["api_key": "query-token"]))

        assertEquals("query-token", AuthSessionSupport.readRequestAuthToken(ec))
    }

    @Test
    void revokeAuthTokenDeletesMatchingUserLoginKeyFromRequestHeader() {
        FinderStub keyFinder = new FinderStub(deleteAllResult: 1)
        EntityFacadeStub entity = new EntityFacadeStub(finders: ["moqui.security.UserLoginKey": keyFinder])
        def ec = executionContext(
                request: new RequestStub(headers: ["login_key": "header-token"]),
                entity: entity
        )

        assertTrue(AuthSessionSupport.revokeAuthToken(ec))
        assertEquals("hash:header-token", keyFinder.conditions["loginKey"])
        assertEquals(1, keyFinder.deleteAllCalls)
    }

    @Test
    void revokeAuthTokenReturnsFalseWhenRequestDoesNotIncludeAToken() {
        FinderStub keyFinder = new FinderStub(deleteAllResult: 1)
        EntityFacadeStub entity = new EntityFacadeStub(finders: ["moqui.security.UserLoginKey": keyFinder])
        def ec = executionContext(entity: entity)

        assertFalse(AuthSessionSupport.revokeAuthToken(ec))
        assertTrue(keyFinder.conditions.isEmpty())
        assertEquals(0, keyFinder.deleteAllCalls)
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
    void readRequestAuthTokenNormalizesNullishHeaderValues() {
        def ec = executionContext(request: new RequestStub(headers: ["login_key": "undefined"]))

        assertNull(AuthSessionSupport.readRequestAuthToken(ec))
    }

    private static Expando executionContext(Map overrides = [:]) {
        RequestStub request = overrides.request ?: new RequestStub()
        FactoryStub factory = overrides.factory ?: new FactoryStub()
        EntityFacadeStub entity = overrides.entity ?: new EntityFacadeStub()
        UserStub user = overrides.user ?: new UserStub()

        return new Expando(
                user: user,
                web: new Expando(request: request, response: new Object()),
                factory: factory,
                entity: entity,
                l10n: new Expando(locale: Locale.forLanguageTag("en-US"), timeZone: "Asia/Kolkata")
        )
    }

    static class UserStub {
        String userId
        String username
        String loginKey
        Expando userAccount = new Expando(timeZone: "Asia/Kolkata")

        String getLoginKey() {
            return loginKey
        }
    }

    static class RequestStub {
        Map<String, String> headers = [:]
        Map<String, String> parameters = [:]

        String getHeader(String name) {
            return headers[name]
        }

        String getParameter(String name) {
            return parameters[name]
        }
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
}
