package darpan.facade.auth

import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import org.apache.shiro.authc.SimpleAuthenticationInfo
import org.apache.shiro.authc.UsernamePasswordToken
import org.apache.shiro.lang.util.SimpleByteSource
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import static darpan.common.ValueSupport.normalize

class AuthFacadeSupport {
    private static final Logger logger = LoggerFactory.getLogger(AuthFacadeSupport.class)

    static Map<String, Object> loginSession(def ec, Object username, Object password) {
        String usernameValue = normalize(username)
        String passwordValue = password?.toString()

        if (!usernameValue) ec.message.addError("username is required")
        if (!passwordValue) ec.message.addError("password is required")

        boolean loggedIn = false
        String issuedAuthToken = null
        Integer authTokenExpiresInSecondsValue = null
        if (!ec.message.hasError()) {
            loggedIn = ec.user.loginUser(usernameValue, passwordValue)
            if (!loggedIn) ec.message.addError("Invalid username or password")
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
                authenticated: authenticated,
        ]
        if (authenticated) {
            result.sessionInfo = TenantAccessSupport.buildSessionInfo(ec)
            result.authToken = issuedAuthToken
            result.authTokenType = "LOGIN_KEY"
            result.authTokenHeaderName = "login_key"
            result.authTokenExpiresInSeconds = authTokenExpiresInSecondsValue
        }

        result.putAll(FacadeSupport.envelope(ec))
        return result
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
