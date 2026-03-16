import darpan.facade.common.FacadeSupport

def userId = ec.user.userId

sessionInfo = [
    userId: userId,
    username: ec.user.username,
    locale: ec.l10n?.locale?.toLanguageTag(),
    timeZone: ec.user?.userAccount?.timeZone ?: ec.l10n?.timeZone,
]

authenticated = userId != null && userId.toString().trim().length() > 0

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
