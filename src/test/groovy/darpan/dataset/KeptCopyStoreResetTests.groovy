package darpan.dataset

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

/** DAR-OPS-026: resetForTests clears the line-written hook. */
class KeptCopyStoreResetTests {
    @Test void resetClearsHook() {
        KeptCopyStore.lineWrittenHook = { -> throw new IllegalStateException("x") }
        KeptCopyStore.resetForTests()
        assertNull(KeptCopyStore.lineWrittenHook)
        KeptCopyStore.resetForTests()
        assertNull(KeptCopyStore.lineWrittenHook)
    }
}
