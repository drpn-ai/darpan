import darpan.facade.common.FacadeSupport
import darpan.facade.common.PilotAccessSupport

authenticated = FacadeSupport.normalize(ec?.user?.userId) != null
if (authenticated) sessionInfo = PilotAccessSupport.buildSessionInfo(ec)

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
