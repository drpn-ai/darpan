import darpan.facade.auth.AuthSessionSupport
import darpan.facade.common.FacadeSupport

authTokenRevoked = AuthSessionSupport.revokeAuthToken(ec)

if (AuthSessionSupport.isAuthenticated(ec)) {
    ec.user.logoutUser()
} else {
    def session = ec.web?.request?.getSession(false)
    if (session != null) {
        session.invalidate()
        ec.web.request.getSession(true)
    }
}

authenticated = false

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
