package darpan.facade.auth

import darpan.facade.common.FacadeSupport
import darpan.facade.common.PilotAccessSupport
import groovy.lang.Binding
import groovy.lang.GroovyShell
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class AuthFacadeScriptsTests {

    @Test
    void loginSessionIssuesLoginKeyTokenAndSessionInfo() {
        MessageFacadeStub message = new MessageFacadeStub()
        UserStub user = new UserStub(userId: "EX_USER", username: "pilot.user", loginUserResult: true, loginKey: "issued-token")
        EntityFacadeStub entity = new EntityFacadeStub()
        def ec = executionContext(message: message, user: user, entity: entity, factory: new FactoryStub(expireHours: 2.0f))

        Binding binding = runScript("src/main/groovy/darpan/facade/auth/loginSession.groovy", [
                ec      : ec,
                username: "pilot.user",
                password: "secret",
        ])

        assertTrue(binding.getVariable("authenticated") as boolean)
        assertEquals("issued-token", binding.getVariable("authToken"))
        assertEquals("LOGIN_KEY", binding.getVariable("authTokenType"))
        assertEquals("login_key", binding.getVariable("authTokenHeaderName"))
        assertEquals(7200, binding.getVariable("authTokenExpiresInSeconds"))

        Map<String, Object> sessionInfo = binding.getVariable("sessionInfo") as Map<String, Object>
        assertEquals("EX_USER", sessionInfo.userId)
        assertEquals("pilot.user", sessionInfo.username)
        assertEquals("CUSTOMER", sessionInfo.scopeType)
        assertEquals("EX_USER", sessionInfo.customerScopeId)
        assertFalse(sessionInfo.isSuperAdmin as boolean)
        assertTrue((binding.getVariable("ok") as boolean))
        assertTrue(((binding.getVariable("errors") as List<String>).isEmpty()))
    }

    @Test
    void getSessionInfoUsesCurrentMoquiUserState() {
        MessageFacadeStub message = new MessageFacadeStub()
        UserStub user = new UserStub(userId: "EX_USER", username: "pilot.user")
        EntityFacadeStub entity = new EntityFacadeStub()
        def ec = executionContext(message: message, user: user, entity: entity)

        boolean authenticated = FacadeSupport.normalize(ec?.user?.userId) != null
        Map<String, Object> sessionInfo = authenticated ? (PilotAccessSupport.buildSessionInfo(ec) as Map<String, Object>) : null
        Map<String, Object> envelope = FacadeSupport.envelope(ec)

        assertTrue(authenticated)
        assertEquals("EX_USER", sessionInfo.userId)
        assertEquals("pilot.user", sessionInfo.username)
        assertEquals("CUSTOMER", sessionInfo.scopeType)
        assertTrue(envelope.ok as boolean)
    }

    @Test
    void logoutSessionRevokesSuppliedLoginKeyAndLogsOutCurrentUser() {
        MessageFacadeStub message = new MessageFacadeStub()
        FinderStub keyFinder = new FinderStub(deleteAllResult: 1)
        EntityFacadeStub entity = new EntityFacadeStub(finders: ["moqui.security.UserLoginKey": keyFinder])
        RequestStub request = new RequestStub(headers: ["login_key": "header-token"])
        UserStub user = new UserStub(userId: "EX_USER", username: "pilot.user")
        def ec = executionContext(message: message, user: user, entity: entity, request: request)

        Binding binding = runScript("src/main/groovy/darpan/facade/auth/logoutSession.groovy", [
                ec: ec,
        ])

        assertTrue(binding.getVariable("authTokenRevoked") as boolean)
        assertFalse(binding.getVariable("authenticated") as boolean)
        assertTrue(user.loggedOut)
        assertEquals("hash:header-token", keyFinder.conditions["loginKey"])
        assertTrue(binding.getVariable("ok") as boolean)
    }

    private static Binding runScript(String relativePath, Map<String, Object> variables) {
        Path scriptPath = resolveComponentRoot().resolve(relativePath)
        Binding binding = new Binding(variables)
        new GroovyShell(AuthFacadeScriptsTests.class.classLoader, binding).evaluate(scriptPath.toFile())
        return binding
    }

    private static Path resolveComponentRoot() {
        Path cwd = Paths.get("").toAbsolutePath().normalize()
        List<Path> candidates = [
                cwd,
                cwd.resolve("runtime/component/darpan"),
                cwd.resolve("runtime/component/darpan-dar-101"),
                cwd.resolve("runtime/component/darpan-dar-101-moqui"),
                cwd.resolve("darpan-backend/runtime/component/darpan"),
                cwd.resolve("darpan-backend/runtime/component/darpan-dar-101"),
                cwd.resolve("darpan-backend/runtime/component/darpan-dar-101-moqui"),
        ]

        for (Path candidate : candidates) {
            if (Files.exists(candidate.resolve("src/main/groovy/darpan/facade/auth/loginSession.groovy"))) {
                return candidate
            }
        }

        throw new IllegalStateException("Unable to resolve Darpan component root from ${cwd}")
    }

    private static Expando executionContext(Map overrides = [:]) {
        RequestStub request = overrides.request ?: new RequestStub()
        MessageFacadeStub message = overrides.message ?: new MessageFacadeStub()
        FactoryStub factory = overrides.factory ?: new FactoryStub()
        EntityFacadeStub entity = overrides.entity ?: new EntityFacadeStub()
        UserStub user = overrides.user ?: new UserStub()

        return new Expando(
                message: message,
                user: user,
                web: new Expando(request: request, response: new Object()),
                factory: factory,
                entity: entity,
                l10n: new Expando(locale: Locale.forLanguageTag("en-US"), timeZone: "Asia/Kolkata")
        )
    }

    static class MessageFacadeStub {
        final List<String> messages = []
        final List<String> errors = []

        void addError(String message) {
            errors.add(message)
        }

        boolean hasError() {
            return !errors.isEmpty()
        }

        List<String> getMessages() {
            return messages
        }

        List<String> getErrors() {
            return errors
        }

        List<Object> getValidationErrors() {
            return []
        }
    }

    static class UserStub {
        String userId
        String username
        String loginKey
        boolean loginUserResult = false
        boolean loggedOut = false
        Expando userAccount = new Expando(timeZone: "Asia/Kolkata")

        boolean loginUser(String loginUsername, String password) {
            if (loginUserResult) {
                userId = userId ?: "EX_USER"
                username = username ?: loginUsername
            }
            return loginUserResult
        }

        String getLoginKey() {
            return loginKey
        }

        void logoutUser() {
            loggedOut = true
            userId = null
            username = null
        }
    }

    static class RequestStub {
        Map<String, String> headers = [:]
        Map<String, String> parameters = [:]
        SessionStub session = new SessionStub()

        String getHeader(String name) {
            return headers[name]
        }

        String getParameter(String name) {
            return parameters[name]
        }

        SessionStub getSession(boolean create) {
            return session
        }
    }

    static class SessionStub {
        boolean invalidated = false

        void invalidate() {
            invalidated = true
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
            return deleteAllResult
        }
    }
}
