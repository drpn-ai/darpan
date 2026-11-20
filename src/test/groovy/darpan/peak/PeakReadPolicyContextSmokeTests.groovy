package darpan.peak

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ExecutionContextFactoryImpl

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertSame

/** DAR-BE-328: limitsFor(tenant) from a bare thread must not leave an execution context behind. Real Moqui. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PeakReadPolicyContextSmokeTests {
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "peak-read-policy-ctx-smoke")
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void reset() { PeakReadPolicy.resetForTests() }

    @Test
    void freshThreadLeavesNoContextBehind() {
        ExecutionContextFactoryImpl ecfi = (ExecutionContextFactoryImpl) Moqui.executionContextFactory
        ReadLimits result = null
        Object seenAfter = "unset"
        boolean inMapAfter = true
        Thread t = new Thread({
            result = PeakReadPolicy.limitsFor("NO_SUCH_TENANT")
            seenAfter = ecfi.activeContext.get()
            inMapAfter = ecfi.activeContextMap.containsKey(Thread.currentThread().getId())
        } as Runnable)
        t.start()
        t.join(60_000)
        assertEquals(ReadLimits.NONE, result)
        assertNull(seenAfter, "the context the policy created was destroyed")
        assertEquals(false, inMapAfter)
    }

    @Test
    void existingContextOnTheThreadIsNotDestroyed() {
        ExecutionContextFactoryImpl ecfi = (ExecutionContextFactoryImpl) Moqui.executionContextFactory
        ExecutionContext mine = ecfi.getExecutionContext()
        assertNotNull(mine)
        assertEquals(ReadLimits.NONE, PeakReadPolicy.limitsFor("NO_SUCH_TENANT"))
        assertSame(mine, ecfi.activeContext.get(), "the thread's own context survives the call")
        PeakReadPolicy.resetForTests()
        assertEquals(ReadLimits.NONE, PeakReadPolicy.limitsFor(mine, "OTHER_TENANT"))
        assertSame(mine, ecfi.activeContext.get(), "a caller-supplied context survives the call")
    }
}
