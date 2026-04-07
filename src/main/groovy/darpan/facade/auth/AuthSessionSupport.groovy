package darpan.facade.auth

import darpan.facade.common.PilotAccessSupport

class AuthSessionSupport {
    static final String AUTH_TOKEN_HEADER_NAME = "login_key"
    static final String AUTH_TOKEN_TYPE_LOGIN_KEY = "LOGIN_KEY"

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

    static String issueAuthToken(def ec) {
        return normalizeTokenValue(ec?.user?.getLoginKey())
    }

    static Map<String, Object> buildAuthTokenContract(def ec, String authToken) {
        String normalizedToken = normalizeTokenValue(authToken)
        if (!normalizedToken) return [:]

        return [
                authToken                : normalizedToken,
                authTokenType            : AUTH_TOKEN_TYPE_LOGIN_KEY,
                authTokenHeaderName      : AUTH_TOKEN_HEADER_NAME,
                authTokenExpiresInSeconds: resolveAuthTokenExpiresInSeconds(ec),
        ]
    }

    static String readRequestAuthToken(def ec) {
        def request = ec?.web?.request
        if (request == null) return null

        String headerToken = normalizeTokenValue(request.getHeader(AUTH_TOKEN_HEADER_NAME) ?: request.getHeader("api_key"))
        if (headerToken) return headerToken

        try {
            return normalizeTokenValue(request.getParameter(AUTH_TOKEN_HEADER_NAME) ?: request.getParameter("api_key"))
        } catch (Exception ignored) {
            return null
        }
    }

    static boolean revokeAuthToken(def ec, String authToken = null) {
        String normalizedToken = normalizeTokenValue(authToken) ?: readRequestAuthToken(ec)
        if (!normalizedToken) return false

        String hashedKey = ec?.factory?.getSimpleHash(normalizedToken, "", ec?.factory?.getLoginKeyHashType(), false)
        if (!hashedKey) return false

        def deleted = ec?.entity?.find("moqui.security.UserLoginKey")
                ?.condition("loginKey", hashedKey)
                ?.disableAuthz()
                ?.deleteAll()
        return ((deleted ?: 0) as int) > 0
    }

    static Integer resolveAuthTokenExpiresInSeconds(def ec) {
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

    protected static String normalizeTokenValue(Object value) {
        String normalized = value?.toString()?.trim()
        if (!normalized || "null".equalsIgnoreCase(normalized) || "undefined".equalsIgnoreCase(normalized)) {
            return null
        }
        return normalized
    }
}
