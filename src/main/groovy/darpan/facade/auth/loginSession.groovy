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
    sessionInfo = [
        userId: userId,
        username: ec.user.username,
        locale: ec.l10n?.locale?.toLanguageTag(),
        timeZone: ec.user?.userAccount?.timeZone ?: ec.l10n?.timeZone,
    ]
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
