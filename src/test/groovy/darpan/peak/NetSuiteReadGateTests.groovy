package darpan.peak

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-328 plan 2: NetSuite reads one at a time for a tenant in a serial peak period. No Moqui: the policy seam is replaced. */
class NetSuiteReadGateTests {
    private Map<String, ReadLimits> limits

    @BeforeEach
    void setup() {
        PeakReadPolicy.resetForTests()
        NetSuiteReadGate.resetForTests()
        limits = [:]
        PeakReadPolicy.limitsLoader = { def ec, String tenantId -> limits[tenantId] ?: ReadLimits.NONE }
    }

    @AfterEach
    void teardown() {
        PeakReadPolicy.resetForTests()
        NetSuiteReadGate.resetForTests()
    }

    /** Runs reads(i) for each tenant on its own thread, all started together; the most reads in flight at once, per tenant. */
    private Map<String, Integer> maxOverlap(List<String> tenants, long readMs) {
        Map<String, AtomicInteger> inFlight = tenants.unique(false).collectEntries { [(it): new AtomicInteger()] }
        Map<String, AtomicInteger> peak = tenants.unique(false).collectEntries { [(it): new AtomicInteger()] }
        CountDownLatch start = new CountDownLatch(1)
        List<Thread> threads = tenants.collect { String tenant ->
            Thread.start {
                start.await()
                NetSuiteReadGate.run(tenant) { ->
                    int now = inFlight[tenant].incrementAndGet()
                    peak[tenant].accumulateAndGet(now, { a, b -> Math.max(a, b) } as java.util.function.IntBinaryOperator)
                    Thread.sleep(readMs)
                    inFlight[tenant].decrementAndGet()
                }
            }
        }
        start.countDown()
        threads*.join(10_000)
        return peak.collectEntries { k, v -> [(k): v.get()] } as Map<String, Integer>
    }

    @Test
    void serialTenantRunsOneReadAtATime() {
        limits.A = new ReadLimits(null, true)
        assertEquals([A: 1], maxOverlap(['A', 'A'], 200))
    }

    @Test
    void otherTenantsAreNotSerialised() {
        limits.A = new ReadLimits(null, true)
        Map<String, Integer> peak = maxOverlap(['A', 'A', 'B', 'B'], 200)
        assertEquals(1, peak.A)
        assertEquals(2, peak.B)
    }

    @Test
    void noSerialMeansParallel() {
        limits.A = new ReadLimits(50, false)
        assertEquals([A: 2], maxOverlap(['A', 'A'], 200))
    }

    @Test
    void reentrantOnOneThread() {
        limits.A = new ReadLimits(null, true)
        NetSuiteReadGate.waitLimitMs = 5_000L
        long started = System.currentTimeMillis()
        String answer = NetSuiteReadGate.run('A') { -> NetSuiteReadGate.run('A') { -> 'inner' } + '+outer' }
        assertEquals('inner+outer', answer)
        assertTrue(System.currentTimeMillis() - started < 1_000L, "a nested read must not wait for itself")
    }

    @Test
    void stuckHolderDoesNotBlockForever() {
        limits.A = new ReadLimits(null, true)
        NetSuiteReadGate.waitLimitMs = 300L
        CountDownLatch holding = new CountDownLatch(1)
        Thread holder = Thread.start {
            NetSuiteReadGate.run('A') { -> holding.countDown(); Thread.sleep(2_000) }
        }
        assertTrue(holding.await(5, TimeUnit.SECONDS))
        long started = System.currentTimeMillis()
        String answer = NetSuiteReadGate.run('A') { -> 'ran anyway' }
        long waited = System.currentTimeMillis() - started
        assertEquals('ran anyway', answer)
        assertTrue(waited >= 250L && waited < 1_500L, "waited ${waited} ms, expected about 300")
        holder.join(5_000)
    }

    @Test
    void policyFailureRunsTheReadDirectly() {
        PeakReadPolicy.limitsLoader = { def ec, String tenantId -> throw new IllegalStateException('peak rows unreadable') }
        assertEquals('read', NetSuiteReadGate.run('A') { -> 'read' })
        // and with no lock taken: two reads overlap
        assertEquals([A: 2], maxOverlap(['A', 'A'], 200))
    }

    @Test
    void noTenantRunsTheReadDirectly() {
        assertEquals('read', NetSuiteReadGate.run(null) { -> 'read' })
    }

    @Test
    void aReadThatThrowsReleasesTheGate() {
        limits.A = new ReadLimits(null, true)
        NetSuiteReadGate.waitLimitMs = 5_000L
        Thread failing = Thread.start {
            try { NetSuiteReadGate.run('A') { -> throw new IllegalStateException('NetSuite said no') } } catch (IllegalStateException ignored) { }
        }
        failing.join(5_000)
        long started = System.currentTimeMillis()
        assertEquals('next', NetSuiteReadGate.run('A') { -> 'next' })
        assertTrue(System.currentTimeMillis() - started < 1_000L, "the failed read must have let go of the gate")
    }
}
