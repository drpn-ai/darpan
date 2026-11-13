package darpan.facade.settings

/**
 * DAR-BE-258. A NetSuite endpoint is sent its auth config's password, API token or access token, and the endpoint URL
 * allow-list admits every NetSuite account. So an endpoint stays on the auth config's own account: the account its
 * token URL names, or, without a token URL, the account its endpoints already use (the edited endpoint's stored URL
 * included, so the only endpoint cannot be walked to another account). The first endpoint of an auth config with
 * neither sets the account.
 */
class NsEndpointAccount {
    /** The NetSuite account a URL names: its host's first label ("1234567" of 1234567.suitetalk.api.netsuite.com).
     *  Sandbox ids read the same with an underscore or a hyphen (1234567_SB1, 1234567-sb1). Null when it has no host. */
    static String accountOf(String url) {
        // Read the host by pattern, not java.net.URI: URI gives no host at all when it holds an underscore.
        def m = (url ?: '').trim() =~ /^[A-Za-z][A-Za-z0-9+.-]*:\/\/(?:[^@\/?#]*@)?([^\/:?#]+)/
        if (!m.find()) return null
        String label = m.group(1).toLowerCase(Locale.ROOT).tokenize('.')[0]
        return label ? label.replace('_', '-') : null
    }

    /** Why this endpoint URL may not be saved under the auth config, or null when it may. */
    static String refusal(String endpointUrl, String tokenUrl, Collection<String> boundEndpointUrls) {
        Set<String> known = (tokenUrl?.trim() ? [accountOf(tokenUrl)] : (boundEndpointUrls ?: []).collect { accountOf(it) })
                .findAll { it } as Set<String>
        if (known.isEmpty()) return null
        String target = accountOf(endpointUrl)
        if (target && known.contains(target)) return null
        return "This endpoint must stay on NetSuite account ${known.sort().join(' or ')}, the account its auth config uses. Use an auth config for ${target ? 'account ' + target : 'that account'} instead.".toString()
    }
}
