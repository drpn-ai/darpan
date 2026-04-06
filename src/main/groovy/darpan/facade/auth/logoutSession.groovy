import darpan.facade.auth.AuthSessionSupport
import darpan.facade.common.FacadeSupport

boolean revokedPersistentLogin = AuthSessionSupport.revokePersistentLogin(ec)
AuthSessionSupport.clearPersistentLoginCookie(ec)

if (AuthSessionSupport.isAuthenticated(ec)) {
    ec.user.logoutUser()
} else {
    def session = ec.web?.request?.getSession(false)
    if (session != null) {
        session.invalidate()
        ec.web.request.getSession(true)
    }
}

authState = AuthSessionSupport.AUTH_STATE_UNAUTHENTICATED
authSource = AuthSessionSupport.AUTH_SOURCE_NONE
persistentLoginRevoked = revokedPersistentLogin

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
