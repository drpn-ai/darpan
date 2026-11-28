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

    @Test
    void runErrorsGoThroughTheRedactor() {
        String text = '{"client_secret":"cs"} password=x'
        assertEquals(masked(text), AutomationRuntimeSupport.sanitizeText(text))
    }
}
