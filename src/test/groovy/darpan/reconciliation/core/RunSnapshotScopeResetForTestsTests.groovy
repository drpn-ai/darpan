package darpan.reconciliation.core

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull

class RunSnapshotScopeResetForTestsTests {
    @Test
    void resetForTestsDropsTheCallingThreadsScopeAndIsIdempotent() {
        File dir = File.createTempFile("snap", "")
        dir.delete()
        dir.mkdirs()
        try {
            RunSnapshotScope.within(dir) {
                assertNotNull(RunSnapshotScope.current())
                RunSnapshotScope.resetForTests()
                assertNull(RunSnapshotScope.current())
            }
            RunSnapshotScope.resetForTests()
            assertNull(RunSnapshotScope.current())
        } finally {
            dir.deleteDir()
        }
    }
}
