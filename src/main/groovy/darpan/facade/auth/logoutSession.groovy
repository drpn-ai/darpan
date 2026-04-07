import darpan.facade.common.FacadeSupport

def request = ec?.web?.request
def normalizeTokenValue = { Object value ->
    String normalized = FacadeSupport.normalize(value)
    if (!normalized || "null".equalsIgnoreCase(normalized) || "undefined".equalsIgnoreCase(normalized)) return null
    return normalized
}

String requestToken = normalizeTokenValue(request?.getHeader("login_key") ?: request?.getHeader("api_key"))
if (!requestToken) {
    try {
        requestToken = normalizeTokenValue(request?.getParameter("login_key") ?: request?.getParameter("api_key"))
    } catch (Exception ignored) {
        requestToken = null
    }
}

authTokenRevoked = false
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

if (FacadeSupport.normalize(ec?.user?.userId) != null) {
    ec.user.logoutUser()
} else {
    def session = request?.getSession(false)
    if (session != null) {
        session.invalidate()
        request.getSession(true)
    }
}

authenticated = false

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
