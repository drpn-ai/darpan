package darpan.common

import groovy.transform.CompileStatic

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * DAR-BE-408 (DAR-OPS-029 S07a). The one secret mask for text Darpan stores or posts: run errors (execution errorMessage and
 * errorDetail, Darpan's error card, the daily email), the audit ledger's read reasons, the migration ledger, and the error
 * digest's sample messages and stacks. Pure, no Moqui, so a log appender can call it. Four maskers with four rule sets used to
 * do this, and a secret one of them caught leaked through another.
 *
 * <p>What it masks, keeping each key, separator and quote so the text stays readable:</p>
 * <ul>
 *   <li>a PEM block (a private key or certificate spans lines) whole, or to the end of the text when it was cut before END;</li>
 *   <li>a URL's password ({@code https://user:***@host}) and its key, token, sig, signature or access_token query values;</li>
 *   <li>the value of a password, privateKey, apiToken, apiKey/api_key/api-key, clientSecret, secret, token, webhook or webhookUrl
 *       key, bare, quoted or backslash-quoted (JSON inside a string), with any prefix (access_token, client_secret, x-api-key),
 *       after =, : or => on the same line: a quoted value up to its closing quote on that line (escapes allowed, at most 512
 *       characters), a quote that does not close on its line up to the line's end, a bare value up to whitespace (a comma or
 *       ")" in a secret stays masked; the old migration-ledger rule, kept so no path masks less);</li>
 *   <li>an Authorization or Proxy-Authorization value of any scheme, and a Cookie or Set-Cookie header, whole: quoted up to its
 *       closing quote, else to the line's end (OC83, OC218);</li>
 *   <li>OC218: a login key, passphrase, pwd, credentials, an AWS secret access key and any oauth_* value, as keyed values; a URL's
 *       password with a '/' in it or no user before it; and its code, X-Amz-Signature, X-Amz-Credential, X-Amz-Security-Token
 *       and oauth_* query values;</li>
 *   <li>a Bearer token.</li>
 * </ul>
 * <p>Never past a line end (but for PEM), so a stack trace's other lines stay readable. A key that only ends in a secret word
 * (nextPageToken) is masked too: over-masking is the safe side. Masking can lengthen text ({@code token=a} reads
 * {@code token=***}), so a caller that fits a column caps after redacting.</p>
 */
@CompileStatic
final class SecretRedactor {

    static final String MASK = "***"

    private static final Pattern PEM_BLOCK = ~/-----BEGIN ([A-Z0-9 ]+)-----[\s\S]*?(?:-----END \1-----|\z)/
    /**
     * A URL's userinfo password. DAR-OPS-029 S99-230: tried once per run of scheme characters, from the run's start, not at
     * every word start in it (a dotted run "a.a.a…" cost one scan per dot). A scheme always runs to the end of its run (':'
     * is not a scheme character), and the replacement keeps the scheme text, so starting at the run's beginning masks the
     * same text; the lookahead keeps the old rule that the scheme itself starts at a word boundary with a letter.
     */
    private static final Pattern URL_USERINFO = ~/(?i)(?<![a-z0-9+.-])((?=[a-z0-9+.-]*?\b[a-z])[a-z0-9+.-]*+:\/\/[^\/\s:@]*:)[^@\s]+@/
    private static final Pattern URL_SECRET_PARAM =
            ~/(?i)([?&](?:key|token|sig|signature|access_token|code|x-amz-signature|x-amz-credential|x-amz-security-token|oauth_[a-z_]+)=)[^&\s"'#]+/
    /**
     * DAR-OPS-029 S99-230: a key's prefix starts only where a word does, so a long run of letters costs one try, not one per
     * letter (a 20000-character run took seconds). The matches are the same: a prefix starting inside a word could always
     * start at the word's first letter instead, and a match is found from its leftmost start.
     */
    private static final Pattern SECRET_VALUE =
            ~/(?i)(\\?["']?)(?<![A-Za-z0-9_-])([A-Za-z0-9_-]*?)(password|passphrase|pwd|credentials?|login[_-]?key|privateKey|apiToken|api[_-]?key|clientSecret|secret[_-]?access[_-]?key|secret|oauth_[a-z_]+|token|webhookUrl|webhook)\1([ \t]*(?:=>|[:=])[ \t]*)("(?:\\.|[^"\\\r\n]){0,512}"|'(?:\\.|[^'\\\r\n]){0,512}'|["'][^\r\n]*|\S+)/
    /**
     * OC83 (DAR-OPS-029 S29). An Authorization header's value, any scheme: quoted up to its closing quote on the line, else to
     * the line's end (a Digest value holds spaces, commas and quotes, and a scheme cannot be told from a credential).
     * NetSuite's record type "Return Authorization" (a status reads "Return Authorization : Pending Refund") is no header.
     * S99-230: the prefix starts where a word does (as in SECRET_VALUE), or one letter in when the word starts right after
     * "return ", which is where the guard alone used to move the match: "Return Authorization" stays unmasked and
     * "return xAuthorization: v" stays masked.
     */
    private static final Pattern AUTHORIZATION_VALUE =
            ~/(?i)(?<!return[ \t])(\\?["']?)(?:(?<![A-Za-z0-9_-])|(?<=return[ \t][A-Za-z0-9_-]))([A-Za-z0-9_-]*?)(authorization|cookie)\1([ \t]*(?:=>|[:=])[ \t]*)("(?:\\.|[^"\\\r\n]){0,512}"|'(?:\\.|[^'\\\r\n]){0,512}'|[^\r\n]+)/
    private static final Pattern BEARER_VALUE = ~/(?i)\b(Bearer)\s+[A-Za-z0-9._~+\/=-]+/

    private SecretRedactor() {}

    /** The text with every secret it carries masked; null and empty come back as given. */
    static String redact(String text) {
        if (text == null || text.isEmpty()) return text
        String out = PEM_BLOCK.matcher(text).replaceAll('-----BEGIN $1-----***-----END $1-----')
        out = URL_USERINFO.matcher(out).replaceAll('$1***@')
        out = URL_SECRET_PARAM.matcher(out).replaceAll('$1***')
        out = maskKeyed(AUTHORIZATION_VALUE, out)
        out = maskKeyed(SECRET_VALUE, out)
        return BEARER_VALUE.matcher(out).replaceAll('$1 ***')
    }

    /** Each keyed value masked, keeping its key, quotes and separator: groups are quote, key prefix, key word, separator, value. */
    private static String maskKeyed(Pattern pattern, String text) {
        Matcher keyed = pattern.matcher(text)
        StringBuffer masked = new StringBuffer(text.length() + 16)
        while (keyed.find()) {
            String quote = keyed.group(1)
            String secret = keyed.group(5)
            String valueQuote = secret.startsWith('"') || secret.startsWith("'") ? secret.substring(0, 1) : ""
            keyed.appendReplacement(masked, Matcher.quoteReplacement(
                    quote + keyed.group(2) + keyed.group(3) + quote + keyed.group(4) + valueQuote + MASK + valueQuote))
        }
        keyed.appendTail(masked)
        return masked.toString()
    }
}
