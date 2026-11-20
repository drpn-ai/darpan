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
    void ecFreeOverloadSharesTheCache() {
        assertEquals(new ReadLimits(25, true), PeakReadPolicy.limitsFor('A'))
        PeakReadPolicy.limitsFor(null, 'A')
        PeakReadPolicy.limitsFor('A')
        assertEquals(1, calls['A'])
        assertSame(ReadLimits.NONE, PeakReadPolicy.limitsFor((String) null))
    }
}
