import darpan.facade.auth.AuthSessionSupport
import darpan.facade.common.FacadeSupport

String usernameValue = FacadeSupport.normalize(username)
String passwordValue = password?.toString()

if (!usernameValue) ec.message.addError("username is required")
if (!passwordValue) ec.message.addError("password is required")

boolean loggedIn = false
if (!ec.message.hasError()) {
    loggedIn = ec.user.loginUser(usernameValue, passwordValue)
    if (!loggedIn) ec.message.addError("Invalid username or password")
}

String loginKey = null
if (loggedIn && AuthSessionSupport.isAuthenticated(ec)) {
    loginKey = AuthSessionSupport.issuePersistentLogin(ec)
}

Map authContract = AuthSessionSupport.buildAuthContract(ec, AuthSessionSupport.AUTH_SOURCE_PASSWORD_LOGIN)
authState = authContract.authState
authSource = authContract.authSource
persistentLoginIssued = loginKey != null
if (authContract.sessionInfo) sessionInfo = authContract.sessionInfo

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
