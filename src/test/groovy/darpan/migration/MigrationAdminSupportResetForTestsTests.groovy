package darpan.migration

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertSame

class MigrationAdminSupportResetForTestsTests {
    @Test
    void resetForTestsRestoresTheDefaultSuperAdminGateAndIsIdempotent() {
        MigrationAdminSupport.superAdminGate = { ec -> false }
        assertFalse(MigrationAdminSupport.superAdminGate.is(MigrationAdminSupport.DEFAULT_GATE))
        MigrationAdminSupport.resetForTests()
        assertSame(MigrationAdminSupport.DEFAULT_GATE, MigrationAdminSupport.superAdminGate)
        MigrationAdminSupport.resetForTests()
        assertSame(MigrationAdminSupport.DEFAULT_GATE, MigrationAdminSupport.superAdminGate)
    }
}
