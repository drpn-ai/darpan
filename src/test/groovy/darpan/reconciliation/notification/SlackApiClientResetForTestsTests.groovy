package darpan.reconciliation.notification

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertSame

class SlackApiClientResetForTestsTests {
    @Test
    void resetForTestsRestoresTheDefaultAndIsIdempotent() {
        SlackApiClient.setTransportHook({ Object... a -> [ok: true] })
        assertNotNull(SlackApiClient.@transportHook)
        SlackApiClient.resetForTests()
        assertNull(SlackApiClient.@transportHook)
        SlackApiClient.resetForTests()
        assertNull(SlackApiClient.@transportHook)
    }
}
