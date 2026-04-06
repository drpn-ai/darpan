package darpan.facade.auth

import darpan.facade.common.PilotAccessSupport

import java.sql.Timestamp

class AuthSessionSupport {
    static final String PERSISTENT_LOGIN_COOKIE_NAME = "darpan_pilot_login_key"
    static final String COOKIE_PATH = "/"
    static final String COOKIE_SAME_SITE = "Lax"
    static final String EXPIRED_COOKIE_DATE = "Thu, 01 Jan 1970 00:00:00 GMT"

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

        StringBuilder cookieHeader = new StringBuilder()
        cookieHeader.append(PERSISTENT_LOGIN_COOKIE_NAME).append("=").append(value ?: "")
        cookieHeader.append("; Max-Age=").append(Math.max(maxAgeSeconds ?: 0, 0))
        if (expiresAt) cookieHeader.append("; Expires=").append(expiresAt)
        cookieHeader.append("; Path=").append(COOKIE_PATH)
        cookieHeader.append("; HttpOnly")
        cookieHeader.append("; SameSite=").append(COOKIE_SAME_SITE)
        if (isSecureRequest(ec)) cookieHeader.append("; Secure")

        response.addHeader("Set-Cookie", cookieHeader.toString())
    }
}
