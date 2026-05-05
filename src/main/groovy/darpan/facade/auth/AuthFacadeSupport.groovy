package darpan.facade.auth

import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import org.apache.shiro.authc.SimpleAuthenticationInfo
import org.apache.shiro.authc.UsernamePasswordToken
import org.apache.shiro.util.SimpleByteSource

class AuthFacadeSupport {

    static Map<String, Object> loginSession(def ec, Object username, Object password) {
        String usernameValue = ((username)?.toString()?.trim())
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

        String userId = ((ec?.user?.userId)?.toString()?.trim())
        boolean authenticated = loggedIn && userId != null

        if (authenticated) {
            issuedAuthToken = ec?.user?.getLoginKey()?.toString()?.trim()
            if (!issuedAuthToken) {
                ec.message.addError("Unable to issue auth token")
                ec.user.logoutUser()
                authenticated = false
            } else {
                authTokenExpiresInSecondsValue = resolveLoginKeyExpiresInSeconds(ec)
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

        boolean authenticated = ((ec?.user?.userId)?.toString()?.trim()) != null
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

            String usernameValue = ((userAccount?.username)?.toString()?.trim()) ?: ((ec?.user?.username)?.toString()?.trim())
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

    static Map<String, Object> logoutSession(def ec) {
        def request = ec?.web?.request
        String requestToken = requestAuthToken(request)

        boolean authTokenRevoked = false
        if (requestToken) {
            String hashedKey = ec?.factory?.getSimpleHash(requestToken, "", ec?.factory?.getLoginKeyHashType(), false)
            if (hashedKey) {
                def deleted = ec?.entity?.find("moqui.security.UserLoginKey")
                        ?.condition("loginKey", hashedKey)
                        ?.disableAuthz()
                        ?.deleteAll()
                authTokenRevoked = ((deleted ?: 0) as int) > 0
            }
        }

        if (((ec?.user?.userId)?.toString()?.trim()) != null) {
            ec.user.logoutUser()
        } else {
            def session = request?.getSession(false)
            if (session != null) {
                session.invalidate()
                request.getSession(true)
            }
        }

        Map<String, Object> result = [
                authenticated   : false,
                authTokenRevoked: authTokenRevoked,
        ]
        result.putAll(FacadeSupport.envelope(ec))
        return result
    }

    protected static Integer resolveLoginKeyExpiresInSeconds(def ec) {
        try {
            def configured = ec?.factory?.getLoginKeyExpireHours()
            float expireHours = configured instanceof Number ?
                    ((Number) configured).floatValue() :
                    ((configured ?: "144") as String).toFloat()
            return Math.max(1, Math.round(expireHours * 60.0f * 60.0f))
        } catch (Exception ignored) {
            return 518400
        }
    }

    protected static String requestAuthToken(def request) {
        String requestToken = normalizeTokenValue(request?.getHeader("login_key") ?: request?.getHeader("api_key"))
        if (!requestToken) {
            try {
                requestToken = normalizeTokenValue(request?.getParameter("login_key") ?: request?.getParameter("api_key"))
            } catch (Exception ignored) {
                requestToken = null
            }
        }
        return requestToken
    }

    protected static String normalizeTokenValue(Object value) {
        String normalized = ((value)?.toString()?.trim())
        if (!normalized || "null".equalsIgnoreCase(normalized) || "undefined".equalsIgnoreCase(normalized)) return null
        return normalized
    }
}
