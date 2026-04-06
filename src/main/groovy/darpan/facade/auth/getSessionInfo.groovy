import darpan.facade.auth.AuthSessionSupport
import darpan.facade.common.FacadeSupport

AuthSessionSupport.restoreAuthenticatedSession(ec)
authenticated = AuthSessionSupport.isAuthenticated(ec)
if (authenticated) sessionInfo = AuthSessionSupport.buildSessionInfo(ec)

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
