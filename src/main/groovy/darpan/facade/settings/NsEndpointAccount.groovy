package darpan.facade.settings

/**
 * DAR-BE-258. A NetSuite endpoint is sent its auth config's password, API token or access token, and the endpoint URL
 * allow-list admits every NetSuite account. So an endpoint stays on the auth config's own account: the one its token URL
 * names, else the one stored on the auth config (NsAuthConfig.netsuiteAccount), set once by its first endpoint. The
 * account is kept on the row and never inferred from the endpoints bound now, so detaching or deleting endpoints
 * cannot reset it. Until the NS_AUTH_ACCOUNTS migration has filled the field, a row without one takes the account its
 * endpoints already use.
 */
class NsEndpointAccount {
    /** The NetSuite account a URL names: its host's first label ("1234567" of 1234567.suitetalk.api.netsuite.com),
     *  lower case, a sandbox's _ read as - (1234567_SB1 = 1234567-sb1). Null when it has no host. */
    static String accountOf(String url) {
        // Read the host by pattern, not java.net.URI: URI gives no host at all when it holds an underscore.
        def m = (url ?: '').trim() =~ /^[A-Za-z][A-Za-z0-9+.-]*:\/\/(?:[^@\/?#]*@)?([^\/:?#]+)/
        if (!m.find()) return null
        return normalize(m.group(1).tokenize('.')[0])
    }

    static String normalize(String account) {
        String a = account?.trim()?.toLowerCase(Locale.ROOT)?.replace('_', '-')
        return a ?: null
    }

    /**
     * May this endpoint URL be saved under the auth config? [error: text] when not; otherwise [store: account] when the
     * auth config has no account yet and this endpoint sets it, or [:] when nothing is to be stored.
     */
    static Map decide(String endpointUrl, String tokenUrl, String storedAccount, Collection<String> boundEndpointUrls) {
        String target = accountOf(endpointUrl)
        if (!target) return [error: 'The endpoint URL names no NetSuite account.']
        if (tokenUrl?.trim()) {
            String account = accountOf(tokenUrl)
            if (!account) return [error: "The auth config's token URL names no NetSuite account. Fix its token URL first."]
            return account == target ? [:] : [error: refusal(account, target)]
        }
        String stored = normalize(storedAccount)
        if (stored) return stored == target ? [:] : [error: refusal(stored, target)]
        Set<String> used = (boundEndpointUrls ?: []).collect { accountOf(it) }.findAll { it } as Set<String>
        if (used && !used.contains(target)) return [error: refusal(used.sort().join(' or '), target)]
        return [store: target]
    }

    private static String refusal(String account, String target) {
        return "This endpoint must stay on NetSuite account ${account}, the account its auth config uses. Use an auth config for account ${target} instead.".toString()
    }
}
