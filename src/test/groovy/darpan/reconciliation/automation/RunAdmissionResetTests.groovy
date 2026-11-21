package darpan.reconciliation.automation

import org.junit.jupiter.api.Test

import java.util.concurrent.Executor

import static org.junit.jupiter.api.Assertions.*

/** DAR-OPS-026: resetForTests replaces SHARED only when idle. */
class RunAdmissionResetTests {
    @Test void idleSharedIsReplaced() {
        RunAdmission before = RunAdmission.SHARED
        RunAdmission.resetForTests()
        assertNotSame(before, RunAdmission.SHARED)
        RunAdmission now = RunAdmission.SHARED
        RunAdmission.resetForTests()  // idempotent, still idle
        assertNotNull(RunAdmission.SHARED)
        assertNotSame(now, RunAdmission.SHARED)
    }

    @Test void busySharedIsLeftInPlace() {
        RunAdmission.resetForTests()
        RunAdmission busy = new RunAdmission(1, 1, 100, { -> 0 }, { Runnable r -> } as Executor, 5)
        busy.submit("t", "a", { -> } as Runnable)  // executor never runs it, so it stays running
        RunAdmission saved = RunAdmission.SHARED
        try {
            RunAdmission.SHARED = busy
            RunAdmission.resetForTests()
            assertSame(busy, RunAdmission.SHARED)
        } finally {
            RunAdmission.SHARED = saved
        }
    }
}
