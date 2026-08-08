package darpan.facade.auth

import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import org.junit.jupiter.api.Test

import java.sql.Timestamp
import java.util.Locale

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
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

        boolean authenticated = ((ec?.user?.userId)?.toString()?.trim()) != null
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

        boolean authenticated = ((ec?.user?.userId)?.toString()?.trim()) != null
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
        assertTrue(user.loggedOutLocally)
        assertEquals("hash:header-token", keyFinder.conditions["loginKey"])
        assertTrue(result.ok as boolean)
    }

    @Test
    void logoutSessionDoesNotBroadcastLogoutToOtherSessionsOnSharedAccount() {
        MessageFacadeStub message = new MessageFacadeStub()
        EntityFacadeStub entity = new EntityFacadeStub(finders: ["moqui.security.UserLoginKey": new FinderStub(deleteAllResult: 1)])
        RequestStub request = new RequestStub(headers: ["login_key": "header-token"])
        UserStub user = new UserStub(userId: "EX_USER", username: "hotwax.user")
        def ec = executionContext(message: message, user: user, entity: entity, request: request)

        Map<String, Object> result = AuthFacadeSupport.logoutSession(ec)

        // Framework logoutUser() writes hasLoggedOut=Y on the shared UserAccount row, which
        // force-logs-out every other session on the same account — logout#Session must not use it.
        assertFalse(user.loggedOut)
        assertTrue(user.loggedOutLocally)
        assertTrue(request.session.invalidated)
        assertFalse(result.authenticated as boolean)
        assertTrue(result.ok as boolean)
    }

    @Test
    void loginSessionTokenIssuanceFailureEndsOnlyCallersSession() {
        MessageFacadeStub message = new MessageFacadeStub()
        UserStub user = new UserStub(userId: "EX_USER", username: "hotwax.user", loginUserResult: true, loginKey: null)
        def ec = executionContext(message: message, user: user)

        Map<String, Object> result = AuthFacadeSupport.loginSession(ec, "hotwax.user", "secret")

        assertFalse(result.authenticated as boolean)
        assertTrue((result.errors as List<String>).contains("Unable to issue auth token"))
        assertFalse(user.loggedOut)
        assertTrue(user.loggedOutLocally)
    }

    // ----- Audit W5 #39 — AuthSessionSupport cookie writer + SameSite escalation -----

    @Test
    void loginSessionEmitsPersistentCookieWithLaxSameOriginDefault() {
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
        // Same-origin request (no Origin header) → expect SameSite=Lax, no Secure (request not TLS).
        RequestStub request = new RequestStub(headers: [Host: "darpan.example.com"])
        ResponseStub response = new ResponseStub()
        def ec = executionContext(message: message, user: user, entity: entity, request: request, response: response, factory: new FactoryStub(expireHours: 2.0f))

        AuthFacadeSupport.loginSession(ec, "test.user", "secret")

        List<String> setCookies = response.setCookieHeaders()
        assertEquals(1, setCookies.size())
        String header = setCookies[0]
        assertTrue(header.startsWith("darpan_login_key=issued-token;"))
        assertTrue(header.contains("Max-Age=7200"))
        assertTrue(header.contains("Path=/"))
        assertTrue(header.contains("HttpOnly"))
        assertTrue(header.contains("SameSite=Lax"))
        assertFalse(header.contains("Secure"))
    }

    @Test
    void loginSessionEmitsCookieWithSameSiteNoneAndSecureOnCrossOriginTls() {
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
        // SPA at darpan.hotwax.io → api.darpan.hotwax.io, TLS-terminated upstream (X-Forwarded-Proto).
        RequestStub request = new RequestStub(headers: [
                Host                 : "api.darpan.hotwax.io",
                Origin               : "https://darpan.hotwax.io",
                "X-Forwarded-Proto"  : "https",
        ])
        ResponseStub response = new ResponseStub()
        def ec = executionContext(message: message, user: user, entity: entity, request: request, response: response)

        AuthFacadeSupport.loginSession(ec, "test.user", "secret")

        String header = response.setCookieHeaders()[0]
        assertTrue(header.contains("SameSite=None"))
        assertTrue(header.contains("Secure"))
    }

    @Test
    void logoutSessionEmitsExpiredCookieEvenWhenRequestHadNoCookieToRevoke() {
        MessageFacadeStub message = new MessageFacadeStub()
        EntityFacadeStub entity = new EntityFacadeStub(finders: ["moqui.security.UserLoginKey": new FinderStub(deleteAllResult: 0)])
        RequestStub request = new RequestStub(headers: ["login_key": "header-token"])
        ResponseStub response = new ResponseStub()
        UserStub user = new UserStub(userId: "EX_USER", username: "test.user")
        def ec = executionContext(message: message, user: user, entity: entity, request: request, response: response)

        AuthFacadeSupport.logoutSession(ec)

        // Logout must clear the browser-side cookie even when nothing was deleted server-side.
        String header = response.setCookieHeaders()[0]
        assertTrue(header.startsWith("darpan_login_key=;"))
        assertTrue(header.contains("Max-Age=0"))
        assertTrue(header.contains("Expires=Thu, 01 Jan 1970 00:00:00 GMT"))
    }

    @Test
    void logoutSessionAlsoRevokesCookieSidedLoginKeyRowWhenPresent() {
        MessageFacadeStub message = new MessageFacadeStub()
        FinderStub keyFinder = new FinderStub(deleteAllResult: 1)
        EntityFacadeStub entity = new EntityFacadeStub(finders: ["moqui.security.UserLoginKey": keyFinder])
        // No request header, only a persistent-login cookie. Cookie path must still revoke + clear.
        RequestStub request = new RequestStub(cookies: [new CookieStub(name: "darpan_login_key", value: "cookie-token")])
        ResponseStub response = new ResponseStub()
        UserStub user = new UserStub(userId: "EX_USER", username: "test.user")
        def ec = executionContext(message: message, user: user, entity: entity, request: request, response: response)

        Map<String, Object> result = AuthFacadeSupport.logoutSession(ec)

        assertTrue(result.authTokenRevoked as boolean)
        assertEquals("hash:cookie-token", keyFinder.conditions["loginKey"])
        assertTrue(response.setCookieHeaders()[0].contains("Max-Age=0"))
    }

    // ─── Forced / expired password change ────────────────────────────────────────────────────────────
    //
    // Framework text, reproduced verbatim from MoquiShiroRealm.loginPostPassword. These strings are the
    // contract: loginUser() returns a bare false for every rejection alike, so the bracketed marker in the
    // message is the only thing that distinguishes "wrong password" from "correct password, must change it".
    private static final String FRAMEWORK_PWDCHG_ERROR =
            "Authenticate failed for user [avnindra.sharma] because account requires password change [PWDCHG]."
    private static final String FRAMEWORK_PWDTIM_ERROR =
            "Authenticate failed for user avnindra.sharma because password was changed 15 weeks ago and must be changed every 12 weeks [PWDTIM]."

    @Test
    void loginSessionReportsPasswordChangeRequiredInsteadOfGenericFailure() {
        MessageFacadeStub message = new MessageFacadeStub()
        UserStub user = new UserStub(loginUserResult: false, loginFailureError: FRAMEWORK_PWDCHG_ERROR)
        def ec = executionContext(message: message, user: user)

        Map<String, Object> result = AuthFacadeSupport.loginSession(ec, "avnindra.sharma", "temp-pass")

        assertFalse(result.authenticated as boolean)
        assertTrue(result.passwordChangeRequired as boolean)
        assertEquals(AuthFacadeSupport.PASSWORD_CHANGE_REQUIRED_REASON, result.passwordChangeReason)
        // Reported as a completed call, not a failure: both the JSON-RPC dispatcher and darpan-ui's client
        // discard the payload of anything shaped like a failure, and the reason code has to survive.
        assertTrue(result.ok as boolean)
        assertEquals([], result.errors)
    }

    @Test
    void loginSessionRecognisesExpiredPasswordAsRecoverable() {
        MessageFacadeStub message = new MessageFacadeStub()
        UserStub user = new UserStub(loginUserResult: false, loginFailureError: FRAMEWORK_PWDTIM_ERROR)
        def ec = executionContext(message: message, user: user)

        Map<String, Object> result = AuthFacadeSupport.loginSession(ec, "avnindra.sharma", "stale-pass")

        assertTrue(result.passwordChangeRequired as boolean)
        assertEquals(AuthFacadeSupport.PASSWORD_EXPIRED_REASON, result.passwordChangeReason)
    }

    /** The framework message names the account and quotes the internal code, and this response goes to a
     *  caller with no session. Nothing of it may reach darpan-ui — including via the messages list, which is
     *  where clearErrors() would have deposited it. */
    @Test
    void loginSessionStripsFrameworkTextFromPasswordChangeResponse() {
        MessageFacadeStub message = new MessageFacadeStub()
        UserStub user = new UserStub(loginUserResult: false, loginFailureError: FRAMEWORK_PWDCHG_ERROR)
        def ec = executionContext(message: message, user: user)

        Map<String, Object> result = AuthFacadeSupport.loginSession(ec, "avnindra.sharma", "temp-pass")

        String rendered = "${result.errors}${result.messages}"
        assertFalse(rendered.contains("avnindra.sharma"), "leaked the account name: ${rendered}")
        assertFalse(rendered.contains("PWDCHG"), "leaked the internal code: ${rendered}")
        assertEquals(["Your password must be changed before you can sign in."], result.messages)
        assertEquals([], result.errors)
    }

    /** Leaving errors on the MessageFacade turns the reply into a JSON-RPC error object with no result
     *  payload (ServiceJsonRpcDispatcher), which would strand the reason code the UI needs to offer the
     *  change form. Proven the hard way: the first cut of this returned ok:false with the text in `errors`,
     *  and darpan-ui's client threw on the envelope before the store could read the reason. */
    @Test
    void loginSessionLeavesNoFacadeErrorSoTheReasonCodeSurvivesTheResponse() {
        MessageFacadeStub message = new MessageFacadeStub()
        UserStub user = new UserStub(loginUserResult: false, loginFailureError: FRAMEWORK_PWDCHG_ERROR)
        def ec = executionContext(message: message, user: user)

        AuthFacadeSupport.loginSession(ec, "avnindra.sharma", "temp-pass")

        assertFalse(message.hasError())
    }

    @Test
    void loginSessionStillReportsAnOrdinaryBadPasswordGenerically() {
        MessageFacadeStub message = new MessageFacadeStub()
        UserStub user = new UserStub(loginUserResult: false)
        def ec = executionContext(message: message, user: user)

        Map<String, Object> result = AuthFacadeSupport.loginSession(ec, "avnindra.sharma", "wrong")

        assertFalse(result.passwordChangeRequired as boolean)
        assertEquals(null, result.passwordChangeReason)
        assertEquals(["Invalid username or password"], result.errors)
    }

    @Test
    void changeExpiredPasswordUpdatesPasswordAndRevokesEveryOtherSession() {
        MessageFacadeStub message = new MessageFacadeStub()
        ServiceFacadeStub service = new ServiceFacadeStub(message: message, resultsByName: [
                "org.moqui.impl.UserServices.update#Password": [updateSuccessful: true, passwordIssues: false],
        ])
        FinderStub loginKeys = new FinderStub(deleteAllResult: 3)
        EntityFacadeStub entity = new EntityFacadeStub(finders: [
                "moqui.security.UserAccount": new FinderStub(oneResult: [userId: "EX_USER", username: "avnindra.sharma", disabled: "N"]),
                "moqui.security.UserLoginKey": loginKeys,
        ])
        def ec = executionContext(message: message, entity: entity, service: service)

        Map<String, Object> result = AuthFacadeSupport.changeExpiredPassword(
                ec, "avnindra.sharma", "temp-pass", "N3w-password!", "N3w-password!")

        assertTrue(result.passwordUpdated as boolean)
        assertTrue(result.ok as boolean)
        assertEquals([], result.errors)
        assertEquals("EX_USER", service.callTo("org.moqui.impl.UserServices.update#Password").parametersMap.userId)
        assertEquals("temp-pass", service.callTo("org.moqui.impl.UserServices.update#Password").parametersMap.oldPassword)
        assertEquals(3, loginKeys.deleteAllResult)
    }

    /** An unknown username and a wrong current password must be indistinguishable, or this anonymous endpoint
     *  answers "does this account exist?" for anyone who asks. */
    @Test
    void changeExpiredPasswordGivesUnknownAndWrongTheSameAnswer() {
        MessageFacadeStub unknownMessage = new MessageFacadeStub()
        EntityFacadeStub noAccount = new EntityFacadeStub(finders: [
                "moqui.security.UserAccount": new FinderStub(oneResult: null),
        ])
        Map<String, Object> unknownResult = AuthFacadeSupport.changeExpiredPassword(
                executionContext(message: unknownMessage, entity: noAccount),
                "no.such.user", "whatever", "N3w-password!", "N3w-password!")

        MessageFacadeStub wrongMessage = new MessageFacadeStub()
        ServiceFacadeStub service = new ServiceFacadeStub(message: wrongMessage, resultsByName: [
                "org.moqui.impl.UserServices.update#Password": [updateSuccessful: false, passwordIssues: false],
        ], publicMessagesByName: [
                "org.moqui.impl.UserServices.update#Password": "Password incorrect for user avnindra.sharma",
        ])
        EntityFacadeStub entity = new EntityFacadeStub(finders: [
                "moqui.security.UserAccount": new FinderStub(oneResult: [userId: "EX_USER", username: "avnindra.sharma", disabled: "N"]),
        ])
        Map<String, Object> wrongResult = AuthFacadeSupport.changeExpiredPassword(
                executionContext(message: wrongMessage, entity: entity, service: service),
                "avnindra.sharma", "wrong-pass", "N3w-password!", "N3w-password!")

        assertFalse(unknownResult.passwordUpdated as boolean)
        assertFalse(wrongResult.passwordUpdated as boolean)
        assertEquals(unknownResult.errors, wrongResult.errors)
        assertEquals(unknownResult.messages, wrongResult.messages)
        assertFalse("${wrongResult.errors}${wrongResult.messages}".contains("avnindra.sharma"))
    }

    /** update#Password never counts a failed attempt, so without this the endpoint is a password-guessing
     *  channel that never trips the max-failures lockout login enforces. */
    @Test
    void changeExpiredPasswordCountsAWrongCurrentPasswordAgainstTheLockout() {
        MessageFacadeStub message = new MessageFacadeStub()
        ServiceFacadeStub service = new ServiceFacadeStub(message: message, resultsByName: [
                "org.moqui.impl.UserServices.update#Password": [updateSuccessful: false, passwordIssues: false],
        ], publicMessagesByName: [
                "org.moqui.impl.UserServices.update#Password": "Password incorrect for user avnindra.sharma",
        ])
        EntityFacadeStub entity = new EntityFacadeStub(finders: [
                "moqui.security.UserAccount": new FinderStub(oneResult: [userId: "EX_USER", username: "avnindra.sharma", disabled: "N"]),
        ])
        def ec = executionContext(message: message, entity: entity, service: service)

        AuthFacadeSupport.changeExpiredPassword(ec, "avnindra.sharma", "wrong-pass", "N3w-password!", "N3w-password!")

        ServiceCallStub increment = service.callTo("org.moqui.impl.UserServices.increment#UserAccountFailedLogins")
        assertNotNull(increment, "a wrong current password must count against the account lockout")
        assertEquals("EX_USER", increment.parametersMap.userId)
        assertTrue(increment.newTransactionRequired, "the counter must outlive the rollback of this attempt")
    }

    /** A new password the user typed badly is not a credential guess; counting it would let a user lock
     *  themselves out by failing the password policy three times. */
    @Test
    void changeExpiredPasswordDoesNotCountAPolicyFailureAgainstTheLockout() {
        MessageFacadeStub message = new MessageFacadeStub()
        // Faithful to update#PasswordInternal: the actionable reason goes to the messages list, while the
        // error it fails with is the contentless summary.
        ServiceFacadeStub service = new ServiceFacadeStub(message: message, resultsByName: [
                "org.moqui.impl.UserServices.update#Password": [updateSuccessful: false, passwordIssues: true],
        ], errorsByName: [
                "org.moqui.impl.UserServices.update#Password": "Found issues with password so not updating",
        ], publicMessagesByName: [
                "org.moqui.impl.UserServices.update#Password": "Password shorter than 8 characters",
        ])
        EntityFacadeStub entity = new EntityFacadeStub(finders: [
                "moqui.security.UserAccount": new FinderStub(oneResult: [userId: "EX_USER", username: "avnindra.sharma", disabled: "N"]),
        ])
        def ec = executionContext(message: message, entity: entity, service: service)

        Map<String, Object> result = AuthFacadeSupport.changeExpiredPassword(
                ec, "avnindra.sharma", "temp-pass", "short", "short")

        assertFalse(result.passwordUpdated as boolean)
        assertEquals(null, service.callTo("org.moqui.impl.UserServices.increment#UserAccountFailedLogins"))
        // Policy complaints are only reachable after the current password verified, so they are safe to show
        // — and withholding them would leave the user guessing why the new password was refused. They must
        // arrive as errors, because that is the only field a JSON-RPC error response carries.
        assertEquals(["Password shorter than 8 characters"], result.errors)
        assertFalse(result.errors.contains("Found issues with password so not updating"))
    }

    /** The framework disables an account after too many failed logins. update#Password does not look at that
     *  flag, so without this gate the account could reset its way straight past its own lockout. */
    @Test
    void changeExpiredPasswordRefusesADisabledAccount() {
        MessageFacadeStub message = new MessageFacadeStub()
        ServiceFacadeStub service = new ServiceFacadeStub(message: message)
        EntityFacadeStub entity = new EntityFacadeStub(finders: [
                "moqui.security.UserAccount": new FinderStub(oneResult: [userId: "EX_USER", username: "avnindra.sharma", disabled: "Y"]),
        ])
        def ec = executionContext(message: message, entity: entity, service: service)

        Map<String, Object> result = AuthFacadeSupport.changeExpiredPassword(
                ec, "avnindra.sharma", "temp-pass", "N3w-password!", "N3w-password!")

        assertFalse(result.passwordUpdated as boolean)
        assertEquals(null, service.callTo("org.moqui.impl.UserServices.update#Password"),
                "a disabled account must not reach the framework password change at all")
    }

    /** Login resolves an account case-insensitively, so a user who signed in as "Avnindra.Sharma" and was
     *  told to change their password must not hit a dead end on the very next step. */
    @Test
    void changeExpiredPasswordResolvesTheAccountTheSameWayLoginDoes() {
        MessageFacadeStub message = new MessageFacadeStub()
        ServiceFacadeStub service = new ServiceFacadeStub(message: message, resultsByName: [
                "org.moqui.impl.UserServices.update#Password": [updateSuccessful: true, passwordIssues: false],
        ])
        EntityFacadeStub entity = new EntityFacadeStub(finders: [
                "moqui.security.UserAccount": new FinderStub(oneResult: [userId: "EX_USER", username: "avnindra.sharma", disabled: "N"]),
        ])
        def ec = executionContext(message: message, entity: entity, service: service)

        Map<String, Object> result = AuthFacadeSupport.changeExpiredPassword(
                ec, "Avnindra.Sharma", "temp-pass", "N3w-password!", "N3w-password!")

        assertTrue(result.passwordUpdated as boolean)
    }

    @Test
    void changeExpiredPasswordRequiresEveryField() {
        MessageFacadeStub message = new MessageFacadeStub()
        ServiceFacadeStub service = new ServiceFacadeStub(message: message)
        def ec = executionContext(message: message, service: service)

        Map<String, Object> result = AuthFacadeSupport.changeExpiredPassword(ec, "avnindra.sharma", "", "", "")

        assertFalse(result.ok as boolean)
        assertFalse(result.passwordUpdated as boolean)
        assertEquals(null, service.callTo("org.moqui.impl.UserServices.update#Password"))
    }

    private static Expando executionContext(Map overrides = [:]) {
        RequestStub request = overrides.request ?: new RequestStub()
        MessageFacadeStub message = overrides.message ?: new MessageFacadeStub()
        FactoryStub factory = overrides.factory ?: new FactoryStub()
        EntityFacadeStub entity = overrides.entity ?: new EntityFacadeStub()
        UserStub user = overrides.user ?: new UserStub()
        ResponseStub response = overrides.response ?: new ResponseStub()

        user.message = user.message ?: message

        return new Expando(
                message: message,
                user: user,
                web: new Expando(request: request, response: response),
                factory: factory,
                ecfi: overrides.ecfi ?: new EcfiStub(credentialsMatch: true),
                entity: entity,
                service: overrides.service ?: new ServiceFacadeStub(message: message),
                l10n: new Expando(locale: Locale.forLanguageTag("en-US"), timeZone: "Asia/Kolkata")
        )
    }

    static class MessageFacadeStub {
        final List<String> messages = []
        final List<String> errors = []

        void addError(String message) {
            errors.add(message)
        }

        void addMessage(String message) {
            messages.add(message)
        }

        /** Mirrors MessageFacadeImpl.addPublic: a plain message, NOT an error. update#Password reports
         *  "user not found" and "password incorrect" this way, which is why they survive an ok:true envelope
         *  unless the facade sanitizes them. */
        void addPublic(String message, String type) {
            messages.add(message)
        }

        /** Mirrors MessageFacadeImpl.clearAll: genuinely discards. Deliberately NOT clearErrors(), which
         *  moves errors into the messages list instead of dropping them. */
        void clearAll() {
            messages.clear()
            errors.clear()
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
        MessageFacadeStub message
        /** Text MoquiShiroRealm would have left in the MessageFacade when loginUser rejects the attempt.
         *  loginUser itself returns a bare false for every rejection reason, so this is the only channel
         *  that carries which one. */
        String loginFailureError
        boolean loginUserResult = false
        boolean loggedOut = false
        boolean loggedOutLocally = false
        Map<String, Object> preferences = [:]
        Map<String, Object> context = [:]
        Timestamp nowTimestamp = new Timestamp(System.currentTimeMillis())
        Object userAccount = new Expando(timeZone: "Asia/Kolkata")

        boolean loginUser(String loginUsername, String password) {
            if (loginUserResult) {
                userId = userId ?: "EX_USER"
                username = username ?: loginUsername
            } else if (loginFailureError) {
                message?.addError(loginFailureError)
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

        void logoutLocal() {
            loggedOutLocally = true
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
        // Audit W5 #39 — AuthSessionSupport reads request.getCookies() for persistent-login restore.
        List<CookieStub> cookies = []
        boolean secure = false
        String serverName = "darpan.example.com"

        String getHeader(String name) {
            return headers[name]
        }

        String getParameter(String name) {
            return parameters[name]
        }

        SessionStub getSession(boolean create) {
            return session
        }

        CookieStub[] getCookies() {
            return cookies ? cookies.toArray(new CookieStub[0]) : null
        }

        boolean isSecure() {
            return secure
        }

        String getServerName() {
            return serverName
        }
    }

    // Audit W5 #39 — captures Set-Cookie headers so tests can assert SameSite/Secure/Max-Age.
    static class ResponseStub {
        List<Map<String, String>> headers = []

        void addHeader(String name, String value) {
            headers.add([name: name, value: value])
        }

        List<String> setCookieHeaders() {
            return headers.findAll { it.name == "Set-Cookie" }*.value
        }
    }

    static class CookieStub {
        String name
        String value
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
        ConditionFactoryStub conditionFactory = new ConditionFactoryStub()

        FinderStub find(String entityName) {
            FinderStub finder = finders[entityName]
            if (finder == null) {
                finder = new FinderStub()
                finders[entityName] = finder
            }
            return finder
        }

        ConditionFactoryStub getConditionFactory() {
            return conditionFactory
        }
    }

    static class ConditionFactoryStub {
        ConditionStub makeCondition(String field, Object operator, Object value) {
            return new ConditionStub(field: field, value: value)
        }
    }

    static class ConditionStub {
        String field
        Object value
        boolean ignoreCaseApplied = false

        ConditionStub ignoreCase() {
            ignoreCaseApplied = true
            return this
        }
    }

    /** Stands in for the framework ServiceFacade so the anonymous password-change path can be driven without
     *  a running Moqui. Mirrors AdminUserSupportTests' stub, plus requireNewTransaction for the failed-login
     *  counter, which must survive the rollback of the enclosing transaction. */
    static class ServiceFacadeStub {
        List<ServiceCallStub> calls = []
        Map<String, Map> resultsByName = [:]
        Map<String, String> errorsByName = [:]
        MessageFacadeStub message
        /** Messages a stubbed service adds via addPublic — how update#Password reports identity failures. */
        Map<String, String> publicMessagesByName = [:]

        ServiceCallStub sync() {
            ServiceCallStub call = new ServiceCallStub(facade: this)
            calls << call
            return call
        }

        ServiceCallStub callTo(String serviceName) {
            return calls.find { it.serviceName == serviceName }
        }
    }

    static class ServiceCallStub {
        ServiceFacadeStub facade
        String serviceName
        Map<String, Object> parametersMap = [:]
        boolean newTransactionRequired = false

        ServiceCallStub name(String serviceName) {
            this.serviceName = serviceName
            return this
        }

        ServiceCallStub parameters(Map<String, Object> parametersMap) {
            this.parametersMap = parametersMap
            return this
        }

        ServiceCallStub requireNewTransaction(boolean requireNewTransaction) {
            this.newTransactionRequired = requireNewTransaction
            return this
        }

        Map<String, Object> call() {
            String error = facade?.errorsByName?.get(serviceName)
            if (error) facade?.message?.addError(error)
            String publicMessage = facade?.publicMessagesByName?.get(serviceName)
            if (publicMessage) facade?.message?.addPublic(publicMessage, "danger")
            return facade?.resultsByName?.get(serviceName) ?: [:]
        }
    }

    static class FinderStub {
        Map<String, Object> conditions = [:]
        ConditionStub appliedCondition
        Object oneResult
        List listResult = []
        int deleteAllResult = 0

        FinderStub condition(String field, Object value) {
            conditions[field] = value
            return this
        }

        FinderStub condition(ConditionStub condition) {
            appliedCondition = condition
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
            if (appliedCondition != null) {
                if (!(oneResult instanceof Map)) return oneResult
                Object actual = oneResult[appliedCondition.field]
                return actual?.toString()?.equalsIgnoreCase(appliedCondition.value?.toString()) ? oneResult : null
            }
            if (oneResult instanceof Map && !conditions.every { String field, Object value -> oneResult[field] == value }) {
                return null
            }
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
