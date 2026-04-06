package darpan.facade.auth

import darpan.facade.common.PilotAccessSupport

import java.sql.Timestamp

class AuthSessionSupport {
    static final String PERSISTENT_LOGIN_COOKIE_NAME = "darpan_pilot_login_key"
    static final String COOKIE_PATH = "/"
    static final String COOKIE_SAME_SITE_LAX = "Lax"
    static final String COOKIE_SAME_SITE_NONE = "None"
    static final String EXPIRED_COOKIE_DATE = "Thu, 01 Jan 1970 00:00:00 GMT"
    static final String AUTH_STATE_AUTHENTICATED = "AUTHENTICATED"
    static final String AUTH_STATE_UNAUTHENTICATED = "UNAUTHENTICATED"
    static final String AUTH_SOURCE_PASSWORD_LOGIN = "PASSWORD_LOGIN"
    static final String AUTH_SOURCE_ACTIVE_SESSION = "ACTIVE_SESSION"
    static final String AUTH_SOURCE_PERSISTENT_LOGIN = "PERSISTENT_LOGIN"
    static final String AUTH_SOURCE_NONE = "NONE"

    static boolean isAuthenticated(def ec) {
        String userId = ec?.user?.userId?.toString()?.trim()
        return userId != null && !userId.isEmpty()
    }

    static Map<String, Object> buildSessionInfo(def ec) {
        Map<String, Object> sessionInfo = [
            userId: ec?.user?.userId,
            username: ec?.user?.username,
            locale: ec?.l10n?.locale?.toLanguageTag(),
            timeZone: ec?.user?.userAccount?.timeZone ?: ec?.l10n?.timeZone,
        ]
        PilotAccessSupport.applyScopeToSessionInfo(ec, sessionInfo)
        return sessionInfo
    }

    static Map<String, Object> buildAuthContract(def ec, String authenticatedSource = AUTH_SOURCE_ACTIVE_SESSION) {
        boolean authenticated = isAuthenticated(ec)
        Map<String, Object> contract = [
                authState    : authenticated ? AUTH_STATE_AUTHENTICATED : AUTH_STATE_UNAUTHENTICATED,
                authSource   : authenticated ? authenticatedSource : AUTH_SOURCE_NONE,
        ]
        if (authenticated) {
            contract.sessionInfo = buildSessionInfo(ec)
        }
        return contract
    }

    static Map<String, Object> buildSessionInfoContract(def ec) {
        boolean alreadyAuthenticated = isAuthenticated(ec)
        boolean sessionRestored = !alreadyAuthenticated && restoreAuthenticatedSession(ec)
        Map<String, Object> contract = buildAuthContract(
                ec,
                sessionRestored ? AUTH_SOURCE_PERSISTENT_LOGIN : AUTH_SOURCE_ACTIVE_SESSION
        )
        contract.sessionRestored = sessionRestored
        return contract
    }

    static String issuePersistentLogin(def ec) {
        String loginKey = ec?.user?.getLoginKey()?.toString()?.trim()
        if (!loginKey) return null
        writePersistentLoginCookie(ec, loginKey)
        return loginKey
    }

    static boolean revokePersistentLogin(def ec) {
        String loginKey = readPersistentLoginCookie(ec)
        if (!loginKey) return false

        String hashedKey = ec?.factory?.getSimpleHash(loginKey, "", ec?.factory?.getLoginKeyHashType(), false)
        if (!hashedKey) return false

        def deleted = ec?.entity?.find("moqui.security.UserLoginKey")
                ?.condition("loginKey", hashedKey)
                ?.disableAuthz()
                ?.deleteAll()
        return ((deleted ?: 0) as int) > 0
    }

    static boolean restoreAuthenticatedSession(def ec) {
        if (isAuthenticated(ec)) return true

        String loginKey = readPersistentLoginCookie(ec)
        if (!loginKey) return false

        def userLoginKey = findActiveUserLoginKey(ec, loginKey)
        if (userLoginKey == null) {
            clearPersistentLoginCookie(ec)
            return false
        }

        def userAccount = ec?.entity?.find("moqui.security.UserAccount")
                ?.condition("userId", userLoginKey.userId)
                ?.disableAuthz()
                ?.one()
        String username = userAccount?.username?.toString()?.trim()
        if (!username) {
            clearPersistentLoginCookie(ec)
            return false
        }

        boolean restored = ec?.user?.internalLoginUser(username) as boolean
        if (!restored) clearPersistentLoginCookie(ec)
        return restored
    }

    static void writePersistentLoginCookie(def ec, String loginKey) {
        if (!loginKey?.trim()) return
        addCookieHeader(ec, loginKey.trim(), resolveCookieMaxAgeSeconds(ec), null)
    }

    static void clearPersistentLoginCookie(def ec) {
        addCookieHeader(ec, "", 0, EXPIRED_COOKIE_DATE)
    }

    static String readPersistentLoginCookie(def ec) {
        def cookies = ec?.web?.request?.getCookies()
        if (cookies == null) return null

        for (def cookie : cookies) {
            if (cookie?.name == PERSISTENT_LOGIN_COOKIE_NAME) {
                String value = cookie.value?.toString()?.trim()
                if (value) return value
            }
        }
        return null
    }

    static Integer resolveCookieMaxAgeSeconds(def ec) {
        float expireHours = 144.0f
        try {
            def configured = ec?.factory?.getLoginKeyExpireHours()
            if (configured instanceof Number) {
                expireHours = ((Number) configured).floatValue()
            } else if (configured != null) {
                expireHours = Float.parseFloat(configured.toString())
            }
        } catch (Exception ignored) {
        }

        return Math.max(1, Math.round(expireHours * 60.0f * 60.0f))
    }

    static boolean isSecureRequest(def ec) {
        def request = ec?.web?.request
        if (request == null) return false

        String forwardedProto = request.getHeader("X-Forwarded-Proto")?.toString()?.trim()
        String forwardedSsl = request.getHeader("X-Forwarded-Ssl")?.toString()?.trim()
        String frontEndHttps = request.getHeader("Front-End-Https")?.toString()?.trim()

        return request.isSecure() ||
                "https".equalsIgnoreCase(forwardedProto) ||
                "on".equalsIgnoreCase(forwardedSsl) ||
                "on".equalsIgnoreCase(frontEndHttps)
    }

    static String resolveCookieSameSite(def ec) {
        return isCrossSiteRequest(ec) && isSecureRequest(ec) ? COOKIE_SAME_SITE_NONE : COOKIE_SAME_SITE_LAX
    }

    static boolean isCrossSiteRequest(def ec) {
        def request = ec?.web?.request
        if (request == null) return false

        String originHeader = request.getHeader("Origin")?.toString()?.trim()
        if (!originHeader) return false

        String originHost = extractHost(originHeader)
        String requestHost = normalizeHost(
                request.getHeader("X-Forwarded-Host")
                        ?: request.getHeader("Host")
                        ?: request.getServerName()
        )
        return originHost != null && requestHost != null && originHost != requestHost
    }

    protected static String extractHost(String originHeader) {
        String normalizedOrigin = originHeader?.toString()?.trim()
        if (!normalizedOrigin) return null

        try {
            return normalizeHost(new URI(normalizedOrigin).host)
        } catch (Exception ignored) {
            int schemeIdx = normalizedOrigin.indexOf("://")
            String withoutScheme = schemeIdx >= 0 ? normalizedOrigin.substring(schemeIdx + 3) : normalizedOrigin
            int slashIdx = withoutScheme.indexOf("/")
            return normalizeHost(slashIdx >= 0 ? withoutScheme.substring(0, slashIdx) : withoutScheme)
        }
    }

    protected static String normalizeHost(Object value) {
        String normalized = value?.toString()?.trim()?.toLowerCase()
        if (!normalized) return null

        int commaIdx = normalized.indexOf(",")
        if (commaIdx >= 0) normalized = normalized.substring(0, commaIdx).trim()

        int colonIdx = normalized.indexOf(":")
        if (colonIdx >= 0) normalized = normalized.substring(0, colonIdx).trim()

        return normalized ?: null
    }

    protected static def findActiveUserLoginKey(def ec, String loginKey) {
        String normalizedKey = loginKey?.toString()?.trim()
        if (!normalizedKey) return null

        String hashedKey = ec?.factory?.getSimpleHash(normalizedKey, "", ec?.factory?.getLoginKeyHashType(), false)
        if (!hashedKey) return null

        def userLoginKey = ec?.entity?.find("moqui.security.UserLoginKey")
                ?.condition("loginKey", hashedKey)
                ?.disableAuthz()
                ?.one()
        if (userLoginKey == null) return null

        Timestamp thruDate = userLoginKey.getTimestamp("thruDate")
        Timestamp now = new Timestamp(System.currentTimeMillis())
        if (thruDate != null && now.after(thruDate)) return null

        return userLoginKey
    }

    protected static void addCookieHeader(def ec, String value, Integer maxAgeSeconds, String expiresAt) {
        def response = ec?.web?.response
        if (response == null) return

        String sameSite = resolveCookieSameSite(ec)
        StringBuilder cookieHeader = new StringBuilder()
        cookieHeader.append(PERSISTENT_LOGIN_COOKIE_NAME).append("=").append(value ?: "")
        cookieHeader.append("; Max-Age=").append(Math.max(maxAgeSeconds ?: 0, 0))
        if (expiresAt) cookieHeader.append("; Expires=").append(expiresAt)
        cookieHeader.append("; Path=").append(COOKIE_PATH)
        cookieHeader.append("; HttpOnly")
        cookieHeader.append("; SameSite=").append(sameSite)
        if (isSecureRequest(ec)) cookieHeader.append("; Secure")

        response.addHeader("Set-Cookie", cookieHeader.toString())
    }
}
