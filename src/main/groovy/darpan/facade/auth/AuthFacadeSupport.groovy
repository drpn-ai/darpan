package darpan.facade.auth

import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import org.apache.shiro.authc.SimpleAuthenticationInfo
import org.apache.shiro.authc.UsernamePasswordToken
import org.apache.shiro.lang.util.SimpleByteSource
import org.moqui.entity.EntityCondition
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import static darpan.common.ValueSupport.normalize

class AuthFacadeSupport {
    private static final Logger logger = LoggerFactory.getLogger(AuthFacadeSupport.class)

    /** Reason codes darpan-ui switches on to decide whether a rejected login can be recovered by a
     *  password change. Values match the bracketed markers the framework embeds in its own messages. */
    static final String PASSWORD_CHANGE_REQUIRED_REASON = "PWDCHG"
    static final String PASSWORD_EXPIRED_REASON = "PWDTIM"

    /** MoquiShiroRealm.loginPostPassword raises PasswordChangeRequiredException with a "[PWDCHG]" marker
     *  when UserAccount.requirePasswordChange is Y, and ExpiredCredentialsException with "[PWDTIM]" when
     *  passwordSetDate is older than user-facade.password.@change-weeks. UserFacadeImpl.internalLoginToken
     *  catches both and adds the message text to the MessageFacade, which is the only place the distinction
     *  survives — loginUser() returns a bare false for every rejection reason alike. Matching the marker is
     *  therefore the supported signal; the session attributes the framework also sets are unusable here
     *  because darpan-ui authenticates statelessly with a login-key header and has no server session.
     *
     *  Both exceptions are thrown only AFTER the submitted password matched, so reporting either reason to
     *  an unauthenticated caller reveals nothing: it answers a caller who already holds valid credentials. */
    private static final String PASSWORD_CHANGE_REQUIRED_MARKER = "[PWDCHG]"
    private static final String PASSWORD_EXPIRED_MARKER = "[PWDTIM]"
    private static final String PASSWORD_CHANGE_REQUIRED_MESSAGE =
            "Your password must be changed before you can sign in."
    private static final String PASSWORD_EXPIRED_MESSAGE =
            "Your password has expired and must be changed before you can sign in."

    static Map<String, Object> loginSession(def ec, Object username, Object password) {
        String usernameValue = normalize(username)
        String passwordValue = password?.toString()

        if (!usernameValue) ec.message.addError("username is required")
        if (!passwordValue) ec.message.addError("password is required")

        boolean loggedIn = false
        String issuedAuthToken = null
        Integer authTokenExpiresInSecondsValue = null
        String passwordChangeReason = null
        if (!ec.message.hasError()) {
            loggedIn = ec.user.loginUser(usernameValue, passwordValue)
            if (!loggedIn) {
                passwordChangeReason = resolvePasswordChangeReason(ec)
                if (passwordChangeReason == null) ec.message.addError("Invalid username or password")
            }
        }

        String userId = normalize(ec?.user?.userId)
        boolean authenticated = loggedIn && userId != null

        if (authenticated) {
            issuedAuthToken = ec?.user?.getLoginKey()?.toString()?.trim()
            if (!issuedAuthToken) {
                ec.message.addError("Unable to issue auth token")
                logoutCurrentSessionOnly(ec)
                authenticated = false
            } else {
                authTokenExpiresInSecondsValue = resolveLoginKeyExpiresInSeconds(ec)
                // Audit W5 #39 — augment header response with a persistent-login cookie. SPA
                // contract (header `login_key`) is unchanged; the cookie is the side channel that
                // survives localStorage loss. SameSite is resolved per request inside the helper.
                AuthSessionSupport.writePersistentLoginCookie(ec, issuedAuthToken)
            }
        }

        Map<String, Object> result = [
                authenticated         : authenticated,
                passwordChangeRequired: passwordChangeReason != null,
        ]
        if (authenticated) {
            result.sessionInfo = TenantAccessSupport.buildSessionInfo(ec)
            result.authToken = issuedAuthToken
            result.authTokenType = "LOGIN_KEY"
            result.authTokenHeaderName = "login_key"
            result.authTokenExpiresInSeconds = authTokenExpiresInSecondsValue
        }

        result.putAll(FacadeSupport.envelope(ec))

        if (passwordChangeReason != null) {
            // Drop the framework text entirely: it names the account and quotes the internal code, and this
            // response goes to an unauthenticated caller. clearAll() rather than clearErrors(), because
            // clearErrors() MOVES errors into the messages list instead of discarding them.
            ec.message.clearAll()
            result.passwordChangeReason = passwordChangeReason
            // Reported as a COMPLETED call carrying a required next step, not as a failure — `authenticated`
            // stays false, which is the signal every caller actually reads. Two layers drop the payload of
            // anything shaped like a failure, and the reason code has to survive both: Moqui's JSON-RPC
            // dispatcher replaces the result with an error object when the MessageFacade ends with errors,
            // and darpan-ui's client throws on any envelope with ok:false or a non-empty errors list. The
            // user-facing text therefore travels in `messages`.
            result.ok = true
            result.errors = []
            result.messages = [passwordChangeReason == PASSWORD_EXPIRED_REASON
                    ? PASSWORD_EXPIRED_MESSAGE : PASSWORD_CHANGE_REQUIRED_MESSAGE]
        }

        return result
    }

    /** Null unless the failed login was rejected for a reason the user can clear themselves by setting a
     *  new password. See PASSWORD_CHANGE_REQUIRED_MARKER for why the message text is the signal. */
    private static String resolvePasswordChangeReason(def ec) {
        List<String> errors = FacadeSupport.getErrors(ec)
        if (errors.any { it?.contains(PASSWORD_CHANGE_REQUIRED_MARKER) }) return PASSWORD_CHANGE_REQUIRED_REASON
        if (errors.any { it?.contains(PASSWORD_EXPIRED_MARKER) }) return PASSWORD_EXPIRED_REASON
        return null
    }

    /** Change the password of an account that cannot sign in until it does so (PWDCHG or PWDTIM), without
     *  requiring a session — the whole point being that no session can exist until the password changes.
     *
     *  Three things this has to get right that the framework service underneath does not, because it was
     *  written for an authenticated caller:
     *
     *  1. update#Password reports "Could not find user with name X" and "Password incorrect for user X"
     *     through `<return public="true" type="danger"/>`, which calls addPublic — a plain message, not an
     *     error. Left alone those ride out on an ok:true envelope and make this a username-existence oracle.
     *  2. update#Password does not consult moqui.security.UserAccount.disabled, so an account the framework
     *     just locked after too many failed logins could reset its way straight past the lockout.
     *  3. update#Password does not count failed attempts, so an unguarded anonymous wrapper around it is an
     *     offline-speed password-guessing channel that never trips the max-failures lockout login enforces.
     *
     *  Password-policy complaints (too short, no digit, reused) are the one class of failure passed through
     *  verbatim: they are only reachable after the current password verified, so they tell an attacker
     *  nothing, and withholding them would leave the user guessing why their new password was refused. */
    static Map<String, Object> changeExpiredPassword(def ec, Object username, Object currentPassword,
                                                     Object newPassword, Object newPasswordVerify) {
        String usernameValue = normalize(username)
        String currentPasswordValue = currentPassword?.toString()
        String newPasswordValue = newPassword?.toString()
        String newPasswordVerifyValue = newPasswordVerify?.toString()

        if (!usernameValue) ec.message.addError("username is required")
        if (!currentPasswordValue) ec.message.addError("current password is required")
        if (!newPasswordValue) ec.message.addError("new password is required")
        if (!newPasswordVerifyValue) ec.message.addError("new password verification is required")

        boolean passwordUpdated = false
        if (!ec.message.hasError()) {
            def userAccount = findUserAccountForPasswordChange(ec, usernameValue)
            if (userAccount == null || "Y" == normalize(userAccount.disabled)) {
                rejectAnonymousPasswordChange(ec)
            } else {
                passwordUpdated = runFrameworkPasswordChange(ec, userAccount, currentPasswordValue,
                        newPasswordValue, newPasswordVerifyValue)
            }
        }

        Map<String, Object> result = [passwordUpdated: passwordUpdated]
        result.putAll(FacadeSupport.envelope(ec))
        return result
    }

    /** Exact username first, then case-insensitive, mirroring MoquiShiroRealm.loginPrePassword. Login accepts
     *  either casing, so resolving the account any more strictly here would strand a user who signed in
     *  successfully and then could not complete the change they were just told to make. */
    private static def findUserAccountForPasswordChange(def ec, String usernameValue) {
        def finder = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.security.UserAccount",
                "self-scoped auth: resolve account for anonymous forced password change")
        def account = finder.condition("username", usernameValue).one()
        if (account != null) return account

        def caseInsensitiveFinder = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.security.UserAccount",
                "self-scoped auth: case-insensitive account lookup for anonymous forced password change")
        def conditionFactory = ec.entity?.conditionFactory
        if (conditionFactory == null) return null
        return caseInsensitiveFinder.condition(conditionFactory
                .makeCondition("username", EntityCondition.ComparisonOperator.EQUALS, usernameValue)
                .ignoreCase()).one()
    }

    private static boolean runFrameworkPasswordChange(def ec, def userAccount, String currentPasswordValue,
                                                      String newPasswordValue, String newPasswordVerifyValue) {
        Map updateOut = ec.service.sync().name("org.moqui.impl.UserServices.update#Password").parameters([
                userId           : userAccount.userId,
                oldPassword      : currentPasswordValue,
                newPassword      : newPasswordValue,
                newPasswordVerify: newPasswordVerifyValue,
        ]).call()

        if (updateOut?.updateSuccessful == true && !ec.message.hasError()) {
            // The framework success message names the account; darpan-ui renders its own confirmation copy.
            ec.message.clearAll()
            // Match change#OwnPassword: a password change invalidates every other device immediately.
            deleteAllLoginKeysForUser(ec, normalize(userAccount.userId))
            return true
        }

        if (updateOut?.passwordIssues == true) {
            promotePasswordPolicyMessagesToErrors(ec)
            return false
        }

        if (!ec.message.hasError()) {
            // No error and no policy complaint means update#Password bailed through one of its addPublic
            // returns: the account was not found or the current password did not match. Only this branch is
            // a credential guess, so only this branch feeds the framework's max-failures lockout. Its own
            // transaction, because the enclosing one is about to roll back.
            incrementFailedLogins(ec, normalize(userAccount.userId))
        }
        rejectAnonymousPasswordChange(ec)
        return false
    }

    /** update#PasswordInternal splits a policy rejection in two: the reasons ("Password shorter than 8
     *  characters", "Password was used in last 5 passwords") go to the messages list, while the error it
     *  actually fails with is the contentless "Found issues with password so not updating". A service that
     *  ends with errors is returned over JSON-RPC as an error object built from the error strings alone, so
     *  the messages are dropped and the user is told only that something was wrong. Swapping the reasons
     *  into the error position keeps the failure a failure while making it one the user can act on. */
    private static void promotePasswordPolicyMessagesToErrors(def ec) {
        List<String> reasons = FacadeSupport.getMessages(ec).findAll { it }
        ec.message.clearAll()
        if (reasons) {
            reasons.each { ec.message.addError(it) }
        } else {
            ec.message.addError("That new password was refused. Choose a different one.")
        }
    }

    /** One indistinguishable answer for "no such account", "wrong current password", "locked out", and any
     *  other non-policy refusal, so the response cannot be mined for which of those is true. */
    private static void rejectAnonymousPasswordChange(def ec) {
        // clearAll(), not clearErrors(): clearErrors MOVES errors into the messages list rather than
        // discarding them, which would put the framework text back in the response under another key.
        ec.message.clearAll()
        ec.message.addError("Unable to change the password. Check the username and current password.")
    }

    private static void incrementFailedLogins(def ec, String userId) {
        if (!userId) return
        try {
            ec.service.sync().name("org.moqui.impl.UserServices.increment#UserAccountFailedLogins")
                    .parameters([userId: userId]).requireNewTransaction(true).call()
        } catch (Exception e) {
            logger.warn("incrementFailedLogins failed for userId=${userId}: ${e.message}")
        }
    }

    static Map<String, Object> verifyOwnPassword(def ec, Object currentPassword) {
        String currentPasswordValue = currentPassword?.toString()

        boolean authenticated = normalize(ec?.user?.userId) != null
        boolean passwordVerified = false

        if (!authenticated) {
            ec.message.addError("Authentication required to verify password.")
        } else if (!currentPasswordValue) {
            ec.message.addError("Current password is required.")
        } else {
            def userAccount = ec?.user?.userAccount
            if (!userAccount) {
                userAccount = ec.entity.find("moqui.security.UserAccount")
                        .condition("userId", ec.user.userId)
                        .one()
            }

            String usernameValue = normalize(userAccount?.username) ?: normalize(ec?.user?.username)
            if (usernameValue && userAccount?.currentPassword) {
                def token = new UsernamePasswordToken(usernameValue, currentPasswordValue)
                def salt = userAccount?.passwordSalt ? new SimpleByteSource((String) userAccount.passwordSalt) : null
                def info = new SimpleAuthenticationInfo(usernameValue, userAccount.currentPassword, salt, "moquiRealm")
                passwordVerified = ec.ecfi
                        .getCredentialsMatcher(userAccount.passwordHashType, "Y".equals(userAccount.passwordBase64?.toString()))
                        .doCredentialsMatch(token, info)
            }
        }

        Map<String, Object> result = [
                authenticated   : authenticated,
                passwordVerified: passwordVerified,
        ]
        if (authenticated) result.sessionInfo = TenantAccessSupport.buildSessionInfo(ec)

        result.putAll(FacadeSupport.envelope(ec))
        return result
    }

    /** Delete every UserLoginKey row for a given userId; returns the count removed. Used by
     *  logoutAllSessions() and by change#OwnPassword to rotate every device after a password change.
     *  Audit M1.2 / M1.3 — previously logout only removed the current key and password changes
     *  did NOT invalidate other devices, so a 6-day token on a compromised laptop kept working
     *  after the user changed their password. */
    static int deleteAllLoginKeysForUser(def ec, String userId) {
        if (!userId) return 0
        try {
            def deleted = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.security.UserLoginKey",
                            "self-scoped auth: delete all login keys for userId (logout-all path)")
                    .condition("userId", userId)
                    .deleteAll()
            return ((deleted ?: 0) as int)
        } catch (Exception e) {
            logger.warn("deleteAllLoginKeysForUser failed for userId=${userId}: ${e.message}")
            return 0
        }
    }

    static Map<String, Object> logoutAllSessions(def ec) {
        String userId = normalize(ec?.user?.userId)
        Map<String, Object> result = [authenticated: false, authTokenRevoked: false, allSessionsRevoked: 0]
        if (!userId) {
            ec?.message?.addError("Authentication required to revoke sessions.")
            result.putAll(FacadeSupport.envelope(ec))
            return result
        }
        int removed = deleteAllLoginKeysForUser(ec, userId)
        result.allSessionsRevoked = removed
        result.authTokenRevoked = removed > 0

        // Audit W5 #39 — `deleteAllLoginKeysForUser` already removed the row this cookie pointed
        // at, but the browser still holds the cookie value. Emit an expiring Set-Cookie so the
        // next request from this browser does not carry a usable-looking token.
        AuthSessionSupport.clearPersistentLoginCookie(ec)

        try {
            // logoutUser() (not logoutCurrentSessionOnly) is intentional here: the hasLoggedOut=Y
            // broadcast is what evicts other browsers' session-authenticated requests on this account.
            ec.user.logoutUser()
        } catch (Exception ignored) {
            def session = ec?.web?.request?.getSession(false)
            session?.invalidate()
        }

        result.putAll(FacadeSupport.envelope(ec))
        return result
    }

    static Map<String, Object> logoutSession(def ec) {
        def request = ec?.web?.request
        String requestToken = requestAuthToken(request)

        boolean authTokenRevoked = false
        if (requestToken) {
            String hashedKey = ec?.factory?.getSimpleHash(requestToken, "", ec?.factory?.getLoginKeyHashType(), false)
            if (hashedKey) {
                def deleted = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.security.UserLoginKey",
                                "self-scoped auth: logout by token hash — caller verified header token")
                        .condition("loginKey", hashedKey)
                        .deleteAll()
                authTokenRevoked = ((deleted ?: 0) as int) > 0
            }
        }

        // Audit W5 #39 — also revoke the cookie-side UserLoginKey row (the cookie can hold a
        // different token than the request header during the issuance window between login and
        // the SPA picking up the new header value) and emit an expiring Set-Cookie.
        if (AuthSessionSupport.revokePersistentLogin(ec)) {
            authTokenRevoked = true
        }
        AuthSessionSupport.clearPersistentLoginCookie(ec)

        logoutCurrentSessionOnly(ec)

        Map<String, Object> result = [
                authenticated   : false,
                authTokenRevoked: authTokenRevoked,
        ]
        result.putAll(FacadeSupport.envelope(ec))
        return result
    }

    /** End only the caller's session: local Shiro/user-stack logout plus servlet session
     *  invalidation. Deliberately avoids UserFacade.logoutUser(), which broadcasts
     *  hasLoggedOut=Y on the UserAccount row — with a shared account (UAT hotwax.user) that
     *  flag force-logs-out every other session at its next request, and the reset update it
     *  forces on the next auth serializes all logins on the single row (observed as 50s+
     *  lock-wait login failures). logout#AllSessions remains the revoke-everywhere path. */
    static void logoutCurrentSessionOnly(def ec) {
        try {
            ec.user.logoutLocal()
        } catch (Exception e) {
            logger.warn("logoutLocal failed during per-session logout, falling back to session invalidation only: ${e.message}")
        }
        def request = ec?.web?.request
        def session = request?.getSession(false)
        if (session != null) {
            session.invalidate()
            request.getSession(true)
        }
    }

    protected static Integer resolveLoginKeyExpiresInSeconds(def ec) {
        try {
            def configured = ec?.factory?.getLoginKeyExpireHours()
            float expireHours = configured instanceof Number ?
                    ((Number) configured).floatValue() :
                    ((configured ?: "144") as String).toFloat()
            return Math.max(1, Math.round(expireHours * 60.0f * 60.0f))
        } catch (Exception e) {
            logger.warn("Could not resolve loginKeyExpireHours; defaulting to 144 hours", e)
            return 518400
        }
    }

    protected static String requestAuthToken(def request) {
        // Header-only. We deliberately do NOT fall back to request.getParameter("login_key" / "api_key"):
        // tokens in a URL query parameter land in webserver access logs, browser history, Referer headers
        // on cross-origin link clicks, and CDN logs — a passive leak surface that any operator with log
        // access can harvest. SameSite=None + Secure cookies (or the explicit header path) are the
        // intended carriers for the session token.
        return normalizeTokenValue(request?.getHeader("login_key") ?: request?.getHeader("api_key"))
    }

    protected static String normalizeTokenValue(Object value) {
        String normalized = normalize(value)
        if (!normalized || "null".equalsIgnoreCase(normalized) || "undefined".equalsIgnoreCase(normalized)) return null
        return normalized
    }
}
