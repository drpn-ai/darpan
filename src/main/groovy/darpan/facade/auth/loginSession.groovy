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

def userId = ec.user.userId
authenticated = loggedIn && userId != null && userId.toString().trim().length() > 0

if (authenticated) {
    AuthSessionSupport.issuePersistentLogin(ec)
    sessionInfo = AuthSessionSupport.buildSessionInfo(ec)
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
