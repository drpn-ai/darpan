package darpan.reconciliation.notification

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertSame

class SlackOAuthSupportResetForTestsTests {
    @Test
    void resetForTestsRestoresTheDefaultAndIsIdempotent() {
        SlackOAuthSupport.setExchangeHook({ Object... a -> [ok: true] })
        assertNotNull(SlackOAuthSupport.@exchangeHook)
        SlackOAuthSupport.resetForTests()
        assertNull(SlackOAuthSupport.@exchangeHook)
        SlackOAuthSupport.resetForTests()
        assertNull(SlackOAuthSupport.@exchangeHook)
    }
}
