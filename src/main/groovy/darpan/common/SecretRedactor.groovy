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
    private static final Pattern URL_USERINFO = ~/(?i)(\b[a-z][a-z0-9+.-]*:\/\/[^\/\s:@]+:)[^@\s\/]+@/
    private static final Pattern URL_SECRET_PARAM = ~/(?i)([?&](?:key|token|sig|signature|access_token)=)[^&\s"'#]+/
    private static final Pattern SECRET_VALUE =
            ~/(?i)(\\?["']?)([A-Za-z0-9_-]*?)(password|privateKey|apiToken|api[_-]?key|clientSecret|secret|token|webhookUrl|webhook)\1([ \t]*(?:=>|[:=])[ \t]*)("(?:\\.|[^"\\\r\n]){0,512}"|'(?:\\.|[^'\\\r\n]){0,512}'|["'][^\r\n]*|\S+)/
    private static final Pattern BEARER_VALUE = ~/(?i)\b(Bearer)\s+[A-Za-z0-9._~+\/=-]+/

    private SecretRedactor() {}

    /** The text with every secret it carries masked; null and empty come back as given. */
    static String redact(String text) {
        if (text == null || text.isEmpty()) return text
        String out = PEM_BLOCK.matcher(text).replaceAll('-----BEGIN $1-----***-----END $1-----')
        out = URL_USERINFO.matcher(out).replaceAll('$1***@')
        out = URL_SECRET_PARAM.matcher(out).replaceAll('$1***')
        Matcher keyed = SECRET_VALUE.matcher(out)
        StringBuffer masked = new StringBuffer(out.length() + 16)
        while (keyed.find()) {
            String quote = keyed.group(1)
            String secret = keyed.group(5)
            String valueQuote = secret.startsWith('"') || secret.startsWith("'") ? secret.substring(0, 1) : ""
            keyed.appendReplacement(masked, Matcher.quoteReplacement(
                    quote + keyed.group(2) + keyed.group(3) + quote + keyed.group(4) + valueQuote + MASK + valueQuote))
        }
        keyed.appendTail(masked)
        return BEARER_VALUE.matcher(masked.toString()).replaceAll('$1 ***')
    }
}
