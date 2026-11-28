package darpan.reconciliation.automation

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull

/**
 * DAR-OPS-029 S07a. sanitizeText guards every run error Darpan stores (execution errorMessage/errorDetail, the audit ledger's
 * read reason) and so every card and daily email built from it. A secret in a system's JSON error body used to pass through:
 * the mask read key=value only. No Moqui.
 */
class AutomationRuntimeSupportTests {

    private static String masked(String text) { return AutomationRuntimeSupport.sanitizeText(text) }

    @Test
    void aKeyValueSecretKeepsItsShape() {
        assertEquals("NetSuite 401 for apiToken=***, retry", masked("NetSuite 401 for apiToken=sk_live_secret, retry"))
        assertEquals("password=*** token=***", masked("password=hunter2 token=abc.def"))
        assertEquals("access_token=***", masked("access_token=abc&x=1"), "a bare value runs to a space, comma or ')': over-masking is the safe side")
        assertEquals("password=*** (retry)", masked("password=p&ss}w0rd (retry)"))
    }

    @Test
    void aQuotedSecretIsMaskedUpToItsClosingQuote() {
        assertEquals('{"secret":"***","next":1}', masked('{"secret":"a b, c&d}","next":1}'))
        assertEquals('{"password":"***"}', masked('{"password":"x\\"y z"}'), "an escaped quote does not end the value")
        assertEquals("token='***' ok", masked("token='a b' ok"))
    }

    @Test
    void aJsonQuotedSecretIsMasked() {
        String body = '{"error":"unauthorized","apiToken":"sk_live_1","secret": "s3cr3t","apiKey":"AK-9","clientSecret":"cs"}'
        String out = masked(body)
        ["sk_live_1", "s3cr3t", "AK-9", '"cs"'].each { assertFalse(out.contains(it), "${it} leaked: ${out}") }
        assertEquals('{"error":"unauthorized","apiToken":"***","secret": "***","apiKey":"***","clientSecret":"***"}', out)
    }

    @Test
    void aBearerHeaderIsMasked() {
        assertEquals("Authorization: Bearer ***", masked("Authorization: Bearer eyJhbGciOi.payload.sig"))
    }

    @Test
    void nothingButASecretIsMasked() {
        String plain = 'orderId=123, "status":"OPEN", tokenCount=5, secretary=Ann, "keyField":"orderName"'
        assertEquals(plain, masked(plain))
        assertNull(masked(null))
    }
}
