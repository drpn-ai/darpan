package darpan.common

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals

/** DAR-OPS-029 S99-072. rootMessage, once (it was identical in AuditChangePlanner and TenantConfigPendingSupport). */
class ThrowableSupportTests {

    @Test
    void theDeepestCauseSpeaks() {
        Throwable root = new IllegalStateException("  the table is locked ")
        assertEquals("the table is locked", ThrowableSupport.rootMessage(new RuntimeException("wrapper", new RuntimeException("middle", root))))
    }

    @Test
    void aSilentCauseFallsBackToTheOuterMessageThenTheClassName() {
        assertEquals("wrapper", ThrowableSupport.rootMessage(new RuntimeException("wrapper", new IllegalStateException())))
        assertEquals("IllegalStateException", ThrowableSupport.rootMessage(new IllegalStateException()))
    }

    @Test
    void aSelfCausingFailureEnds() {
        Throwable loop = new RuntimeException("loop") {
            @Override synchronized Throwable getCause() { return this }
        }
        assertEquals("loop", ThrowableSupport.rootMessage(loop))
    }
}
