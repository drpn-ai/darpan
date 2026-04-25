import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport

String usernameValue = FacadeSupport.normalize(username)
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

String userId = FacadeSupport.normalize(ec?.user?.userId)
authenticated = loggedIn && userId != null

if (authenticated) {
    issuedAuthToken = FacadeSupport.normalize(ec?.user?.getLoginKey())
    if (!issuedAuthToken) {
        ec.message.addError("Unable to issue auth token")
        ec.user.logoutUser()
        authenticated = false
    } else {
        try {
            def configured = ec?.factory?.getLoginKeyExpireHours()
            float expireHours = configured instanceof Number ? ((Number) configured).floatValue() : ((configured ?: "144") as String).toFloat()
            authTokenExpiresInSecondsValue = Math.max(1, Math.round(expireHours * 60.0f * 60.0f))
        } catch (Exception ignored) {
            authTokenExpiresInSecondsValue = 518400
        }
    }
}

if (authenticated) {
    sessionInfo = TenantAccessSupport.buildSessionInfo(ec)
    authToken = issuedAuthToken
    authTokenType = "LOGIN_KEY"
    authTokenHeaderName = "login_key"
    authTokenExpiresInSeconds = authTokenExpiresInSecondsValue
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
