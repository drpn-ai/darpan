package darpan.peak

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertSame

/** DAR-BE-328 plan 2: the per-tenant read-limits cache and its fail-open behavior. No Moqui: both seams are replaced. */
class PeakReadPolicyTests {
    private long now = 1_000_000L
    private Map<String, Integer> calls
    private ReadLimits answer

    @BeforeEach
    void setup() {
        PeakReadPolicy.resetForTests()
        now = 1_000_000L
        calls = [:]
        answer = new ReadLimits(25, true)
        PeakReadPolicy.clock = { -> now }
        PeakReadPolicy.limitsLoader = { def ec, String tenantId -> calls[tenantId] = (calls[tenantId] ?: 0) + 1; answer }
    }

    @AfterEach
    void teardown() { PeakReadPolicy.resetForTests() }

    @Test
    void noTenantMeansNoLimits() {
        assertSame(ReadLimits.NONE, PeakReadPolicy.limitsFor(null, null))
        assertSame(ReadLimits.NONE, PeakReadPolicy.limitsFor(null, '  '))
        assertEquals([:], calls)
    }

    @Test
    void limitsFromTheActivePeriod() {
        assertEquals(new ReadLimits(25, true), PeakReadPolicy.limitsFor(null, 'A'))
    }

    @Test
    void cachedForSixtySecondsPerTenant() {
        PeakReadPolicy.limitsFor(null, 'A')
        now += 59_000
        PeakReadPolicy.limitsFor(null, 'A')
        assertEquals(1, calls['A'])
        now += 2_000   // 61 s after the load
        PeakReadPolicy.limitsFor(null, 'A')
        assertEquals(2, calls['A'])
        PeakReadPolicy.limitsFor(null, 'B')
        assertEquals(1, calls['B'])
    }

    @Test
    void periodEndingIsSeenWithinAMinute() {
        assertEquals(new ReadLimits(25, true), PeakReadPolicy.limitsFor(null, 'A'))
        answer = ReadLimits.NONE
        now += 61_000
        assertEquals(ReadLimits.NONE, PeakReadPolicy.limitsFor(null, 'A'))
    }

    @Test
    void loaderThrowingFailsOpen() {
        PeakReadPolicy.limitsLoader = { def ec, String tenantId -> throw new IllegalStateException('db down') }
        assertEquals(ReadLimits.NONE, PeakReadPolicy.limitsFor(null, 'A'))
        assertEquals(ReadLimits.NONE, PeakReadPolicy.limitsFor('ec', 'A'))
    }

    @Test
    void sixtySecondBoundary() {
        PeakReadPolicy.limitsFor(null, 'A')
        now += 59_999   // still inside the 60 s
        PeakReadPolicy.limitsFor(null, 'A')
        assertEquals(1, calls['A'])
        now += 1        // exactly 60 s after the load: reloads
        PeakReadPolicy.limitsFor(null, 'A')
        assertEquals(2, calls['A'])
    }

    @Test
    void failedLoadIsCachedBriefly() {
        int failures = 0
        PeakReadPolicy.limitsLoader = { def ec, String tenantId -> failures++; throw new IllegalStateException('db down') }
        assertSame(ReadLimits.NONE, PeakReadPolicy.limitsFor(null, 'A'))
        assertEquals(1, failures)
        now += 14_999   // within 15 s: the failed NONE is served, the loader is not asked again
        assertSame(ReadLimits.NONE, PeakReadPolicy.limitsFor(null, 'A'))
        assertEquals(1, failures)
        now += 1_001    // 16 s after the failure: tried again
        assertSame(ReadLimits.NONE, PeakReadPolicy.limitsFor(null, 'A'))
        assertEquals(2, failures)
        // a recovered lookup is cached the full 60 s again
        PeakReadPolicy.limitsLoader = { def ec, String tenantId -> calls[tenantId] = (calls[tenantId] ?: 0) + 1; answer }
        now += 16_000
        assertEquals(new ReadLimits(25, true), PeakReadPolicy.limitsFor(null, 'A'))
        now += 59_000
        assertEquals(new ReadLimits(25, true), PeakReadPolicy.limitsFor(null, 'A'))
        assertEquals(1, calls['A'])
    }

    @Test
    void ecFreeOverloadSharesTheCache() {
        assertEquals(new ReadLimits(25, true), PeakReadPolicy.limitsFor('A'))
        PeakReadPolicy.limitsFor(null, 'A')
        PeakReadPolicy.limitsFor('A')
        assertEquals(1, calls['A'])
        assertSame(ReadLimits.NONE, PeakReadPolicy.limitsFor((String) null))
    }
}
