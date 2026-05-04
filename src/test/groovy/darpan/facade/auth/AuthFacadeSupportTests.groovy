package darpan.facade.auth

import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import org.junit.jupiter.api.Test

import java.sql.Timestamp
import java.util.Locale

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class AuthFacadeSupportTests {

    @Test
    void loginSessionIssuesLoginKeyTokenAndSessionInfo() {
        MessageFacadeStub message = new MessageFacadeStub()
        UserStub user = new UserStub(userId: "EX_USER", username: "test.user", loginUserResult: true, loginKey: "issued-token")
        EntityFacadeStub entity = new EntityFacadeStub(finders: [
                "moqui.security.UserGroupAndMember": new FinderStub(listResult: [
                        [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
                ]),
                (TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME): new FinderStub(listResult: [
                        [tenantUserGroupId: "KREWE", userId: "EX_USER", permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID],
                ]),
        ])
        def ec = executionContext(message: message, user: user, entity: entity, factory: new FactoryStub(expireHours: 2.0f))

        Map<String, Object> result = AuthFacadeSupport.loginSession(ec, "test.user", "secret")

        assertTrue(result.authenticated as boolean)
        assertEquals("issued-token", result.authToken)
        assertEquals("LOGIN_KEY", result.authTokenType)
        assertEquals("login_key", result.authTokenHeaderName)
        assertEquals(7200, result.authTokenExpiresInSeconds)

        Map<String, Object> sessionInfo = result.sessionInfo as Map<String, Object>
        assertEquals("EX_USER", sessionInfo.userId)
        assertEquals("test.user", sessionInfo.username)
        assertEquals("test.user", sessionInfo.displayName)
        assertEquals("TENANT", sessionInfo.scopeType)
        assertEquals("KREWE", sessionInfo.customerScopeId)
        assertEquals("KREWE", sessionInfo.activeTenantUserGroupId)
        assertEquals("Krewe", sessionInfo.activeTenantLabel)
        assertEquals([[userGroupId: "KREWE", label: "Krewe"]], sessionInfo.availableTenants)
        assertEquals([TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID], sessionInfo.activeTenantPermissionGroupIds)
        assertTrue(sessionInfo.canViewActiveTenantData as boolean)
        assertTrue(sessionInfo.canRunActiveTenantReconciliation as boolean)
        assertTrue(sessionInfo.canEditActiveTenantData as boolean)
        assertFalse(sessionInfo.canManageDarpanCore as boolean)
        assertFalse(sessionInfo.isSuperAdmin as boolean)
        assertEquals("KREWE", user.context.activeTenantUserGroupId)
        assertTrue(result.ok as boolean)
        assertTrue((result.errors as List<String>).isEmpty())
    }

    @Test
    void getSessionInfoUsesCurrentMoquiUserState() {
        MessageFacadeStub message = new MessageFacadeStub()
        UserStub user = new UserStub(userId: "EX_USER", username: "test.user", preferences: [
                (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
                (TenantAccessSupport.DISPLAY_NAME_PREFERENCE_KEY): "Aditi",
        ])
        Timestamp lastLoginDate = Timestamp.valueOf("2026-04-30 14:14:00")
        user.userAccount = new Expando(timeZone: "Asia/Kolkata", userFullName: "Fallback User", lastLoginDate: lastLoginDate)
        Timestamp lastRunDate = Timestamp.valueOf("2026-04-30 14:44:00")
        EntityFacadeStub entity = new EntityFacadeStub(finders: [
                "moqui.security.UserGroupAndMember": new FinderStub(listResult: [
                        [userGroupId: "GORJANA", userId: "EX_USER", description: "Gorjana", groupTypeEnumId: "UgtDarpanCompany"],
                        [userGroupId: "KREWE", userId: "EX_USER", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
                ]),
                (TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME): new FinderStub(listResult: [
                        [tenantUserGroupId: "GORJANA", userId: "EX_USER", permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID],
                        [tenantUserGroupId: "KREWE", userId: "EX_USER", permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID],
                ]),
                "moqui.security.UserLoginHistory": new FinderStub(oneResult: [
                        userId         : "EX_USER",
                        successfulLogin: "Y",
                        fromDate       : lastLoginDate,
                ]),
                "darpan.reconciliation.ReconciliationRunResult": new FinderStub(oneResult: [
                        reconciliationRunResultId: "RUN_RESULT_1",
                        savedRunId              : "ORDER_SYNC",
                        savedRunType            : "ruleset",
                        reconciliationRunId     : "RUN_1",
                        createdByUserId         : "EX_USER",
                        companyUserGroupId      : "KREWE",
                        createdDate             : lastRunDate,
                ]),
        ])
        def ec = executionContext(message: message, user: user, entity: entity)

        boolean authenticated = FacadeSupport.normalize(ec?.user?.userId) != null
        Map<String, Object> sessionInfo = authenticated ? (TenantAccessSupport.buildSessionInfo(ec) as Map<String, Object>) : null
        Map<String, Object> envelope = FacadeSupport.envelope(ec)

        assertTrue(authenticated)
        assertEquals("EX_USER", sessionInfo.userId)
        assertEquals("test.user", sessionInfo.username)
        assertEquals("Aditi", sessionInfo.displayName)
        assertEquals(lastLoginDate, sessionInfo.lastLoginDate)
        assertEquals("TENANT", sessionInfo.scopeType)
        assertEquals("KREWE", sessionInfo.activeTenantUserGroupId)
        assertEquals("Krewe", sessionInfo.activeTenantLabel)
        assertEquals("ORDER_SYNC", ((Map) sessionInfo.lastRun).savedRunId)
        assertEquals(lastRunDate, ((Map) sessionInfo.lastRun).createdDate)
        assertEquals([TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID], sessionInfo.activeTenantPermissionGroupIds)
        assertTrue(sessionInfo.canViewActiveTenantData as boolean)
        assertFalse(sessionInfo.canRunActiveTenantReconciliation as boolean)
        assertFalse(sessionInfo.canEditActiveTenantData as boolean)
        assertFalse(sessionInfo.canManageDarpanCore as boolean)
        assertTrue(envelope.ok as boolean)
    }

    @Test
    void saveUserSettingsPersistsDisplayNameWithoutChangingTimezone() {
        MessageFacadeStub message = new MessageFacadeStub()
        UserStub user = new UserStub(userId: "EX_USER", username: "test.user")
        user.userAccount.timeZone = "Asia/Kolkata"
        def ec = executionContext(message: message, user: user)

        assertTrue(TenantAccessSupport.saveUserSettings(ec, "Aditi"))

        assertEquals("Aditi", user.preferences[TenantAccessSupport.DISPLAY_NAME_PREFERENCE_KEY])
        assertEquals("Asia/Kolkata", user.userAccount.timeZone)
        assertTrue(FacadeSupport.envelope(ec).ok as boolean)
    }

    @Test
    void saveUserSettingsDoesNotRequireUserAccountFieldAccess() {
        MessageFacadeStub message = new MessageFacadeStub()
        EntityValueLikeUserAccountStub userAccount = new EntityValueLikeUserAccountStub(timeZone: "Asia/Kolkata")
        UserStub user = new UserStub(userId: "EX_USER", username: "test.user", userAccount: userAccount)
        def ec = executionContext(message: message, user: user)

        assertTrue(TenantAccessSupport.saveUserSettings(ec, "Aditi"))

        assertEquals("Asia/Kolkata", userAccount.timeZone)
        assertFalse(userAccount.updated)
        assertTrue(message.errors.isEmpty())
    }

    @Test
    void verifyOwnPasswordAcceptsMatchingCurrentPasswordWithoutChangingSession() {
        MessageFacadeStub message = new MessageFacadeStub()
        EcfiStub ecfi = new EcfiStub(credentialsMatch: true)
        UserStub user = new UserStub(userId: "EX_USER", username: "test.user")
        user.userAccount = new Expando(
                username: "test.user",
                currentPassword: "hashed-password",
                passwordSalt: "salt",
                passwordHashType: "SHA-256",
                passwordBase64: "N",
                timeZone: "Asia/Kolkata"
        )
        def ec = executionContext(message: message, user: user, ecfi: ecfi)

        Map<String, Object> result = AuthFacadeSupport.verifyOwnPassword(ec, "old-password")

        assertTrue(result.authenticated as boolean)
        assertTrue(result.passwordVerified as boolean)
        assertTrue(result.ok as boolean)
        assertEquals("SHA-256", ecfi.requestedHashType)
        assertFalse(ecfi.requestedBase64)
        assertTrue(message.errors.isEmpty())
    }

    @Test
    void verifyOwnPasswordReturnsFalseForIncorrectCurrentPasswordWithoutServiceError() {
        MessageFacadeStub message = new MessageFacadeStub()
        UserStub user = new UserStub(userId: "EX_USER", username: "test.user")
        user.userAccount = new Expando(
                username: "test.user",
                currentPassword: "hashed-password",
                passwordSalt: "salt",
                passwordHashType: "SHA-256",
                passwordBase64: "N",
                timeZone: "Asia/Kolkata"
        )
        def ec = executionContext(message: message, user: user, ecfi: new EcfiStub(credentialsMatch: false))

        Map<String, Object> result = AuthFacadeSupport.verifyOwnPassword(ec, "wrong-password")

        assertTrue(result.authenticated as boolean)
        assertFalse(result.passwordVerified as boolean)
        assertTrue(result.ok as boolean)
        assertEquals([], result.errors)
    }

    @Test
    void getSessionInfoIncludesActiveTenantForAdminMemberships() {
        MessageFacadeStub message = new MessageFacadeStub()
        UserStub user = new UserStub(userId: "EX_ADMIN", username: "john.doe", preferences: [
                (TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): "KREWE",
        ])
        EntityFacadeStub entity = new EntityFacadeStub(finders: [
                "moqui.security.UserGroupMember": new FinderStub(oneResult: [userGroupId: "ADMIN", userId: "EX_ADMIN"]),
                "moqui.security.UserGroup"      : new FinderStub(listResult: [
                        [userGroupId: "GORJANA", description: "Gorjana", groupTypeEnumId: "UgtDarpanCompany"],
                        [userGroupId: "KREWE", description: "Krewe", groupTypeEnumId: "UgtDarpanCompany"],
                ]),
        ])
        def ec = executionContext(message: message, user: user, entity: entity)

        boolean authenticated = FacadeSupport.normalize(ec?.user?.userId) != null
        Map<String, Object> sessionInfo = authenticated ? (TenantAccessSupport.buildSessionInfo(ec) as Map<String, Object>) : null
        Map<String, Object> envelope = FacadeSupport.envelope(ec)

        assertTrue(authenticated)
        assertTrue(sessionInfo.isSuperAdmin as boolean)
        assertEquals("TENANT", sessionInfo.scopeType)
        assertEquals("KREWE", sessionInfo.activeTenantUserGroupId)
        assertEquals("Krewe", sessionInfo.activeTenantLabel)
        assertEquals([
                [userGroupId: "GORJANA", label: "Gorjana"],
                [userGroupId: "KREWE", label: "Krewe"],
        ], sessionInfo.availableTenants)
        assertEquals([
                TenantAccessSupport.DARPAN_SUPER_ADMIN_GROUP_ID,
                TenantAccessSupport.DARPAN_TENANT_ADMIN_GROUP_ID,
                TenantAccessSupport.DARPAN_TENANT_USER_GROUP_ID,
        ], sessionInfo.activeTenantPermissionGroupIds)
        assertTrue(sessionInfo.canViewActiveTenantData as boolean)
        assertTrue(sessionInfo.canRunActiveTenantReconciliation as boolean)
        assertTrue(sessionInfo.canEditActiveTenantData as boolean)
        assertTrue(sessionInfo.canManageDarpanCore as boolean)
        assertTrue(envelope.ok as boolean)
    }

    @Test
    void logoutSessionRevokesSuppliedLoginKeyAndLogsOutCurrentUser() {
        MessageFacadeStub message = new MessageFacadeStub()
        FinderStub keyFinder = new FinderStub(deleteAllResult: 1)
        EntityFacadeStub entity = new EntityFacadeStub(finders: ["moqui.security.UserLoginKey": keyFinder])
        RequestStub request = new RequestStub(headers: ["login_key": "header-token"])
        UserStub user = new UserStub(userId: "EX_USER", username: "test.user")
        def ec = executionContext(message: message, user: user, entity: entity, request: request)

        Map<String, Object> result = AuthFacadeSupport.logoutSession(ec)

        assertTrue(result.authTokenRevoked as boolean)
        assertFalse(result.authenticated as boolean)
        assertTrue(user.loggedOut)
        assertEquals("hash:header-token", keyFinder.conditions["loginKey"])
        assertTrue(result.ok as boolean)
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
                ecfi: overrides.ecfi ?: new EcfiStub(credentialsMatch: true),
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
        Map<String, Object> preferences = [:]
        Map<String, Object> context = [:]
        Timestamp nowTimestamp = new Timestamp(System.currentTimeMillis())
        Object userAccount = new Expando(timeZone: "Asia/Kolkata")

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

        Object getPreference(String preferenceKey) {
            return preferences[preferenceKey]
        }

        void setPreference(String preferenceKey, Object preferenceValue) {
            preferences[preferenceKey] = preferenceValue
        }

        void logoutUser() {
            loggedOut = true
            userId = null
            username = null
        }
    }

    static class EcfiStub {
        boolean credentialsMatch
        Object requestedHashType
        boolean requestedBase64

        Object getCredentialsMatcher(Object hashType, boolean base64) {
            requestedHashType = hashType
            requestedBase64 = base64
            return new CredentialsMatcherStub(credentialsMatch: credentialsMatch)
        }
    }

    static class CredentialsMatcherStub {
        boolean credentialsMatch

        boolean doCredentialsMatch(Object token, Object info) {
            return credentialsMatch
        }
    }

    static class EntityValueLikeUserAccountStub extends GroovyObjectSupport {
        String timeZone
        boolean updated = false

        @Override
        Object getProperty(String propertyName) {
            if (propertyName == "metaClass") {
                throw new IllegalArgumentException("The name [metaClass] is not a valid field name or relationship name for entity moqui.security.UserAccount")
            }
            if (propertyName == "timeZone") return timeZone
            if (propertyName == "updated") return updated
            return super.getProperty(propertyName)
        }

        @Override
        void setProperty(String propertyName, Object newValue) {
            if (propertyName == "timeZone") {
                timeZone = newValue?.toString()
                return
            }
            if (propertyName == "updated") {
                updated = newValue == true
                return
            }
            super.setProperty(propertyName, newValue)
        }

        void update() {
            updated = true
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
        List listResult = []
        int deleteAllResult = 0

        FinderStub condition(String field, Object value) {
            conditions[field] = value
            return this
        }

        FinderStub disableAuthz() {
            return this
        }

        FinderStub conditionDate(String fromField, String thruField, Object moment) {
            return this
        }

        FinderStub useCache(boolean useCache) {
            return this
        }

        FinderStub orderBy(String orderBy) {
            return this
        }

        Object one() {
            return oneResult
        }

        List list() {
            return listResult.findAll { Object row ->
                if (!(row instanceof Map)) return true
                conditions.every { String field, Object value -> row[field] == value }
            }
        }

        int deleteAll() {
            return deleteAllResult
        }
    }
}
