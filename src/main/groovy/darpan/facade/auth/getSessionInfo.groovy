import darpan.facade.auth.AuthSessionSupport
import darpan.facade.common.FacadeSupport

Map authContract = AuthSessionSupport.buildSessionInfoContract(ec)
authState = authContract.authState
authSource = authContract.authSource
sessionRestored = authContract.sessionRestored
if (authContract.sessionInfo) sessionInfo = authContract.sessionInfo

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
