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

    @Test
    void aBearerHeaderIsMasked() {
        assertEquals("Authorization: Bearer ***", masked("Authorization: Bearer eyJhbGciOi.payload.sig"))
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
