package darpan.common

import darpan.reconciliation.automation.AutomationRuntimeSupport
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull

/**
 * DAR-BE-408 (DAR-OPS-029 S07a). The one secret mask: run errors, cards and the daily email (AutomationRuntimeSupport.sanitizeText),
 * the migration ledger and the error digest all go through it, so a case here holds on every one of those paths. No Moqui.
 */
class SecretRedactorTests {

    private static String masked(String text) { return SecretRedactor.redact(text) }

    @Test
    void aKeyValueSecretKeepsItsShape() {
        assertEquals("NetSuite 401 for apiToken=*** retry", masked("NetSuite 401 for apiToken=sk_live_secret, retry"),
                "a bare value runs to whitespace: the comma after it goes too, over-masking is the safe side")
        assertEquals("password=*** token=***", masked("password=hunter2 token=abc.def"))
        assertEquals("access_token=***", masked("access_token=abc&x=1"))
        assertEquals("password=*** (retry)", masked("password=p&ss}w0rd (retry)"))
        assertEquals("password=*** next", masked("password=ab,cd next"), "a comma in a secret stays masked (the migration ledger's old rule)")
        assertEquals("secret=***", masked("secret=x)y"))
    }

    @Test
    void aQuotedSecretIsMaskedUpToItsClosingQuote() {
        assertEquals('{"secret":"***","next":1}', masked('{"secret":"a b, c&d}","next":1}'))
        assertEquals('{"password":"***"}', masked('{"password":"x\\"y z"}'), "an escaped quote does not end the value")
        assertEquals("token='***' ok", masked("token='a b' ok"))
    }

    @Test
    void aQuoteThatDoesNotCloseOnItsLineIsMaskedToTheLineEndAndNoFurther() {
        String detail = 'IllegalStateException: password="ab cd ef\n    at darpan.X.read(X.groovy:12)\nCaused by: "y"'
        assertEquals('IllegalStateException: password="***"\n    at darpan.X.read(X.groovy:12)\nCaused by: "y"', masked(detail))
        String overCap = '"secret":"' + ("k" * 600) + '" tail'
        assertFalse(masked(overCap).contains("kkk"), "a quoted value past the cap is still masked, to the line end")
    }

    @Test
    void aJsonQuotedSecretIsMasked() {
        String body = '{"error":"unauthorized","apiToken":"sk_live_1","secret": "s3cr3t","apiKey":"AK-9","clientSecret":"cs"}'
        String out = masked(body)
        ["sk_live_1", "s3cr3t", "AK-9", '"cs"'].each { assertFalse(out.contains(it), "${it} leaked: ${out}") }
        assertEquals('{"error":"unauthorized","apiToken":"***","secret": "***","apiKey":"***","clientSecret":"***"}', out)
    }

    @Test
    void secretsUnderOtherNamesAndShapesAreMasked() {
        assertEquals('{"access_token":"***","refresh_token":"***","client_secret":"***"}',
                masked('{"access_token":"shpat_x","refresh_token":"r1","client_secret":"cs"}'))
        assertEquals('body: {\\"apiToken\\":***', masked('body: {\\"apiToken\\":\\"sk_live\\",\\"x\\":1}'),
                "JSON quoted inside a string")
        assertEquals("password => ***", masked("password => hunter2"))
        assertEquals("x-api-key: ***", masked("x-api-key: k1"))
        assertEquals("token:\n    at darpan.X", masked("token:\n    at darpan.X"), "a separator never reaches the next line")
    }

    /** A private key spans lines, so the line-bound rule alone would mask only its first line. */
    @Test
    void aPemBlockIsMaskedWholeEvenWhenItWasCutBeforeItsEnd() {
        String detail = 'privateKey="-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA1\nq2w3e4r5\n-----END RSA PRIVATE KEY-----"\n    at darpan.X'
        assertEquals('privateKey="***"\n    at darpan.X', masked(detail))
        String cut = masked("key: -----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBg\nkqhkiG9w0BAQEF")
        assertFalse(cut.contains("MIIEvQ") || cut.contains("kqhkiG9"), "a block a cap cut before END is masked to the end: ${cut}")
    }

    /** What the migration ledger and the error digest masked before, and the webhook URLs a failed chat post logs. */
    @Test
    void urlsAndWebhooksAreMasked() {
        assertEquals("POST https://chat.googleapis.com/v1/spaces/AAA/messages?key=***&token=*** failed",
                masked("POST https://chat.googleapis.com/v1/spaces/AAA/messages?key=AIzaK1&token=T0k3n failed"))
        assertEquals("jdbc:mysql://darpan:***@db:3306/darpan", masked("jdbc:mysql://darpan:s3cret@db:3306/darpan"))
        assertEquals("googleChatWebhookUrl=*** rejected", masked("googleChatWebhookUrl=https://chat.googleapis.com/v1/spaces/A/messages rejected"))
        assertEquals("migration failed: webhook=*** (400)", masked("migration failed: webhook=https://hooks.slack.com/services/T/B/x (400)"))
    }

    // DAR-OPS-029 OC218 (Session 010's re-verify): secrets the rule set let through.
    @Test
    void loginKeysCookiesAwsSecretsAndPassphrasesAreMasked() {
        assertEquals("login_key=*** loginKey: ***", masked("login_key=lk-1 loginKey: lk-2"))
        assertEquals("Cookie: ***", masked("Cookie: JSESSIONID=abc123; moqui_visitor=xyz"))
        assertEquals("Set-Cookie: ***", masked("Set-Cookie: JSESSIONID=abc123; Path=/; HttpOnly"))
        assertEquals("AWS_SECRET_ACCESS_KEY=***", masked("AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG"))
        assertEquals("passphrase=*** pwd=*** credentials: ***", masked("passphrase=open-sesame pwd=p1 credentials: c1"))
    }

    @Test
    void aUrlsUserinfoAndSignedQueryValuesAreMasked() {
        assertEquals("sftp://svc:***@host/in", masked("sftp://svc:pa/ss@host/in"), "a slash in the password")
        assertEquals("https://:***@host/x", masked("https://:tok3n@host/x"), "an empty user")
        assertEquals("GET /oauth/callback?code=***&state=s", masked("GET /oauth/callback?code=4%2F0AX&state=s"))
        // A bare keyed value is masked to the next whitespace (the documented safe side), so the signature after it goes too.
        assertEquals("https://b.s3.amazonaws.com/f?X-Amz-Credential=***",
                masked("https://b.s3.amazonaws.com/f?X-Amz-Credential=AKIA%2F20261128&X-Amz-Signature=9f8e7d"))
        assertEquals("https://b.s3.amazonaws.com/f?X-Amz-Signature=***&X-Amz-Date=20261128",
                masked("https://b.s3.amazonaws.com/f?X-Amz-Signature=9f8e7d&X-Amz-Date=20261128"), "the signature alone, as a query value")
    }

    @Test
    void oauthParametersAreMaskedOutsideAnAuthorizationHeaderToo() {
        assertEquals('oauth_signature="***", oauth_nonce="***"', masked('oauth_signature="a%2Bb", oauth_nonce="n1"'))
        assertEquals("https://x/api?oauth_verifier=***", masked("https://x/api?oauth_verifier=v1&page=2"), "to the next whitespace")
        assertEquals("https://x/api?oauth_token=***", masked("https://x/api?oauth_token=t1&oauth_verifier=v1"),
                "a bare keyed value is masked to the next whitespace")
    }

    @Test
    void aBearerHeaderIsMasked() {
        assertEquals("Authorization: ***", masked("Authorization: Bearer eyJhbGciOi.payload.sig"))
        assertEquals("sent Bearer *** twice", masked("sent Bearer eyJhbGciOi.payload.sig twice"), "a bare bearer token too")
    }

    /**
     * OC83 (DAR-OPS-029 S29). An Authorization or Proxy-Authorization value of any scheme (Basic, Digest, NTLM, a token), in a
     * header line, a header map and JSON (a connection's headersJson): masked whole, scheme and all, and never past its line.
     */
    @Test
    void anAuthorizationValueOfAnySchemeIsMasked() {
        assertEquals("Authorization: ***", masked("Authorization: Basic dXNlcjpwYXNz"))
        assertEquals("Proxy-Authorization: ***", masked('Proxy-Authorization: Digest username="svc", realm="ns", response="6629fae4"'))
        assertEquals('{"Authorization":"***","Accept":"application/json"}',
                masked('{"Authorization":"Basic dXNlcjpwYXNz","Accept":"application/json"}'))
        assertEquals("headers [authorization:***", masked("headers [authorization:Token t0k3n-v4lue, accept:json]"),
                "a header map's value runs to its line end: over-masking is the safe side")
        assertEquals("X-Authorization = ***\n    at darpan.X", masked("X-Authorization = NTLM TlRMTVNTUAAB\n    at darpan.X"))
        assertEquals("authorization failed for svc", masked("authorization failed for svc"), "the word alone is no header")
        assertEquals("RMA 7 is Return Authorization : Pending Refund, expected Closed",
                masked("RMA 7 is Return Authorization : Pending Refund, expected Closed"), "NetSuite's record type is no header")
    }

    @Test
    void nothingButASecretIsMasked() {
        String plain = 'orderId=123, "status":"OPEN", tokenCount=5, secretary=Ann, "keyField":"orderName", primaryKey=ID'
        assertEquals(plain, masked(plain))
        assertNull(masked(null))
        assertEquals("", masked(""))
    }

    /**
     * DAR-OPS-029 S99-230. Text from an external system (a 401 body, a long token) can hold a 20000-character run: the keyed
     * and Authorization patterns read it in one pass, not one pass per character (seconds per redact, on every stored error).
     */
    @Test
    void aLongRunIsRedactedQuickly() {
        String run = "u7" * 10_000
        ["": "", "token=abc": "token=***", "authorization: x": "authorization: ***", " cookie=c": " cookie=***"].each { String tail, String out ->
            long t0 = System.nanoTime()
            assertEquals(run + out, masked(run + tail))
            long ms = (long) ((System.nanoTime() - t0) / 1_000_000)
            org.junit.jupiter.api.Assertions.assertTrue(ms < 500, "'${tail}' after a 20000-character run took ${ms} ms")
        }
        long t1 = System.nanoTime()
        // the guard skips only the word that starts right after "return ": a longer word is still a header key
        assertEquals("return " + run + "authorization: ***", masked("return " + run + "authorization: x"))
        org.junit.jupiter.api.Assertions.assertTrue((System.nanoTime() - t1) / 1_000_000 < 500, "the Return guard over a long run")
    }

    /**
     * S99-230 follow-up: a dotted run ("a.a.a…", a host or package name gone long) is read in one pass by the URL userinfo
     * rule, which used to try a scheme at every dot.
     */
    @Test
    void aLongDottedRunIsRedactedQuickly() {
        String dotted = "a." * 10_000
        ["": "", "x://u:p@h": "x://u:***@h", " https://u:p@h": " https://u:***@h"].each { String tail, String out ->
            long t0 = System.nanoTime()
            assertEquals(dotted + out, masked(dotted + tail))
            long ms = (long) ((System.nanoTime() - t0) / 1_000_000)
            org.junit.jupiter.api.Assertions.assertTrue(ms < 500, "'${tail}' after a 20000-character dotted run took ${ms} ms")
        }
    }

    /**
     * S99-230: the linear patterns mask exactly what the old ones did, over every input in this class, the edges of the
     * "Return " guard and word starts, and 4000 seeded random texts built from the patterns' own pieces. OLD is the rule set
     * as it was (b1a0c4 era), kept here as the oracle.
     */
    @Test
    void theLinearPatternsMaskExactlyWhatTheOldOnesDid() {
        List<String> inputs = [
                "NetSuite 401 for apiToken=sk_live_secret, retry", "password=hunter2 token=abc.def", "access_token=abc&x=1",
                "password=p&ss}w0rd (retry)", "password=ab,cd next", "secret=x)y", '{"secret":"a b, c&d}","next":1}',
                '{"password":"x\\"y z"}', "token='a b' ok", 'IllegalStateException: password="ab cd ef\n    at darpan.X.read(X.groovy:12)\nCaused by: "y"',
                '{"error":"unauthorized","apiToken":"sk_live_1","secret": "s3cr3t","apiKey":"AK-9","clientSecret":"cs"}',
                '{"access_token":"shpat_x","refresh_token":"r1","client_secret":"cs"}', 'body: {\\"apiToken\\":\\"sk_live\\",\\"x\\":1}',
                "password => hunter2", "x-api-key: k1", "token:\n    at darpan.X",
                'privateKey="-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA1\n-----END RSA PRIVATE KEY-----"\n    at darpan.X',
                "POST https://chat.googleapis.com/v1/spaces/AAA/messages?key=AIzaK1&token=T0k3n failed", "jdbc:mysql://darpan:s3cret@db:3306/darpan",
                "googleChatWebhookUrl=https://chat.googleapis.com/v1/spaces/A/messages rejected", "login_key=lk-1 loginKey: lk-2",
                "Cookie: JSESSIONID=abc123; moqui_visitor=xyz", "Set-Cookie: JSESSIONID=abc123; Path=/; HttpOnly",
                "AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG", "passphrase=open-sesame pwd=p1 credentials: c1",
                'oauth_signature="a%2Bb", oauth_nonce="n1"', "Authorization: Bearer eyJhbGciOi.payload.sig", "Authorization: Basic dXNlcjpwYXNz",
                'Proxy-Authorization: Digest username="svc", realm="ns", response="6629fae4"', '{"Authorization":"Basic dXNlcjpwYXNz","Accept":"application/json"}',
                "headers [authorization:Token t0k3n-v4lue, accept:json]", "X-Authorization = NTLM TlRMTVNTUAAB\n    at darpan.X",
                "authorization failed for svc", "RMA 7 is Return Authorization : Pending Refund, expected Closed",
                'orderId=123, "status":"OPEN", tokenCount=5, secretary=Ann, "keyField":"orderName", primaryKey=ID',
                // the guard's edges: a word right after "return ", one letter in, and a quote between
                "return xauthorization: y", "Return Pre-Authorization: x", "return\tAuthorization: x", "return  authorization: x",
                'return "authorization": "x"', "RETURN x-cookie: c", "return ab_authorization=x", "returnauthorization: x",
                // word starts: a prefix that holds another key word, a key after a quoted value, keys back to back
                "tokenpassword=x", 'password="a"token=b', "xtoken=1 ytoken=2", "a-b_c-password: v", '\\"secret\\": \\"v\\"',
                // URL userinfo: where a scheme may start (after a non-word character only), runs of scheme characters, case
                "-http://u:p@h", "+ftp://u:p@h", ".s3://u:p@h", "1http://u:p@h", "_http://u:p@h", "x-y.z+w://u:p@h", "a.b.1://u:p@h",
                "1.2.3://u:p@h", "1.a://u:p@h", "HTTP://U:P@H", "(https://u:p@h)", '"sftp://svc:pa/ss@host"', "a://b://u:p@h",
                "https://u:p@h https://v:q@g", "https://:p@h", "https://u:@h", "https://u:p", "http://u:p@h@x", "é.http://u:p@h",
        ]
        List<String> pieces = ["password", "token", "authorization", "Authorization", "cookie", "secret", "api_key", "webhook",
                "Return ", "return\t", "return", "x", "Pre-", "_", "-", "=", ":", "=>", " ", "\t", "\"", "'", "\\\"", "\n", "Bearer ",
                "abc", "u7u7", "@", "https://u:p", "?key=", "&", ",", "}", "Proxy-", "Set-",
                // dotted and URL pieces (S99-230 follow-up)
                "a.", ".", "+", "1", "http", "://", "u:", "p@", "h", "/", "_"]
        Random random = new Random(230L)
        4000.times {
            StringBuilder b = new StringBuilder()
            (1 + random.nextInt(14)).times { b.append(pieces[random.nextInt(pieces.size())]) }
            inputs << b.toString()
        }
        inputs.each { String input -> assertEquals(OldRedactor.redact(input), masked(input), "differs on: ${input}") }
    }

    /** The rule set as it was before S99-230, for the parity check: only the two patterns' starts differ. */
    private static final class OldRedactor {
        static final java.util.regex.Pattern PEM = ~/-----BEGIN ([A-Z0-9 ]+)-----[\s\S]*?(?:-----END \1-----|\z)/
        static final java.util.regex.Pattern USERINFO = ~/(?i)(\b[a-z][a-z0-9+.-]*:\/\/[^\/\s:@]*:)[^@\s]+@/
        static final java.util.regex.Pattern PARAM =
                ~/(?i)([?&](?:key|token|sig|signature|access_token|code|x-amz-signature|x-amz-credential|x-amz-security-token|oauth_[a-z_]+)=)[^&\s"'#]+/
        static final java.util.regex.Pattern SECRET =
                ~/(?i)(\\?["']?)([A-Za-z0-9_-]*?)(password|passphrase|pwd|credentials?|login[_-]?key|privateKey|apiToken|api[_-]?key|clientSecret|secret[_-]?access[_-]?key|secret|oauth_[a-z_]+|token|webhookUrl|webhook)\1([ \t]*(?:=>|[:=])[ \t]*)("(?:\\.|[^"\\\r\n]){0,512}"|'(?:\\.|[^'\\\r\n]){0,512}'|["'][^\r\n]*|\S+)/
        static final java.util.regex.Pattern AUTH =
                ~/(?i)(?<!return[ \t])(\\?["']?)([A-Za-z0-9_-]*?)(authorization|cookie)\1([ \t]*(?:=>|[:=])[ \t]*)("(?:\\.|[^"\\\r\n]){0,512}"|'(?:\\.|[^'\\\r\n]){0,512}'|[^\r\n]+)/
        static final java.util.regex.Pattern BEARER = ~/(?i)\b(Bearer)\s+[A-Za-z0-9._~+\/=-]+/

        static String redact(String text) {
            String out = PEM.matcher(text).replaceAll('-----BEGIN $1-----***-----END $1-----')
            out = USERINFO.matcher(out).replaceAll('$1***@')
            out = PARAM.matcher(out).replaceAll('$1***')
            out = keyed(AUTH, out)
            out = keyed(SECRET, out)
            return BEARER.matcher(out).replaceAll('$1 ***')
        }

        static String keyed(java.util.regex.Pattern pattern, String text) {
            java.util.regex.Matcher m = pattern.matcher(text)
            StringBuffer b = new StringBuffer()
            while (m.find()) {
                String secret = m.group(5)
                String q = secret.startsWith('"') || secret.startsWith("'") ? secret.substring(0, 1) : ""
                m.appendReplacement(b, java.util.regex.Matcher.quoteReplacement(m.group(1) + m.group(2) + m.group(3) + m.group(1) + m.group(4) + q + "***" + q))
            }
            m.appendTail(b)
            return b.toString()
        }
    }

    @Test
    void runErrorsGoThroughTheRedactor() {
        String text = '{"client_secret":"cs"} password=x'
        assertEquals(masked(text), AutomationRuntimeSupport.sanitizeText(text))
    }
}
