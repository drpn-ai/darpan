package darpan.datasource

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

/** DAR-OPS-026: resetForTests empties the shared bucket map. Pure: no Moqui. */
class CostBucketGovernorResetTests {
    @Test
    void resetForTestsDropsSharedBuckets() {
        CostBucketGovernor first = CostBucketGovernor.shared('reset-test-key', 3)
        assertSame(first, CostBucketGovernor.shared('reset-test-key', 3))
        CostBucketGovernor.resetForTests()
        assertNotSame(first, CostBucketGovernor.shared('reset-test-key', 3))
        CostBucketGovernor.resetForTests()
        CostBucketGovernor.resetForTests()
    }
}
