package darpan.reconciliation.automation

import org.junit.jupiter.api.Test

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-143. Which run starts when: the instance-wide cap, each tenant's share of it, tenants taking turns, and the
 * memory hold. The executor here only collects what was started; a test finishes a run by calling it, so every order
 * is deterministic.
 */
class RunAdmissionTests {

    /** Collects started work; nothing runs until the test runs it. */
    private static class HandExecutor implements Executor {
        List<Runnable> started = []
        boolean reject = false
        void execute(Runnable work) {
            if (reject) throw new RejectedExecutionException("pool closed")
            started << work
        }
        void finish(int index) { started[index].run() }
    }

    private List<String> log = []

    private Runnable work(String name) { return { -> log << name } as Runnable }

    private RunAdmission admission(HandExecutor executor, int cap, int perTenant, Closure<Integer> heap = { -> null }, int holdAt = 85) {
        return new RunAdmission(cap, perTenant, holdAt, heap, executor)
    }

    @Test
    void aRunBelowEveryCapStartsAtOnce() {
        HandExecutor executor = new HandExecutor()
        Map submitted = admission(executor, 4, 2).submit("A", "a1", work("a1"))
        assertEquals(false, submitted.queued)
        assertEquals(1, executor.started.size())
    }

    @Test
    void pastTheInstanceCapARunWaitsAndStartsWhenOneFinishes() {
        HandExecutor executor = new HandExecutor()
        RunAdmission admission = admission(executor, 2, 2)
        admission.submit("A", "a1", work("a1"))
        admission.submit("B", "b1", work("b1"))
        Map third = admission.submit("C", "c1", work("c1"))
        assertEquals(true, third.queued)
        assertEquals(1, third.position)
        assertEquals(2, executor.started.size())

        executor.finish(0)
        assertEquals(3, executor.started.size())
        executor.finish(2)
        assertEquals(["a1", "c1"], log)
    }

    @Test
    void oneTenantsFullAuditLeavesRoomForAnotherTenant() {
        HandExecutor executor = new HandExecutor()
        RunAdmission admission = admission(executor, 4, 2)
        (1..4).each { admission.submit("A", "a${it}", work("a${it}")) }
        assertEquals(2, executor.started.size(), "A runs only its share")

        Map b = admission.submit("B", "b1", work("b1"))
        assertEquals(false, b.queued, "B is not stuck behind A's queue")
        assertEquals(3, executor.started.size())
    }

    @Test
    void waitingTenantsTakeTurns() {
        HandExecutor executor = new HandExecutor()
        RunAdmission admission = admission(executor, 1, 1)
        admission.submit("A", "a1", work("a1"))
        admission.submit("A", "a2", work("a2"))
        admission.submit("A", "a3", work("a3"))
        admission.submit("B", "b1", work("b1"))

        executor.finish(0)
        executor.finish(1)
        executor.finish(2)
        executor.finish(3)
        assertEquals(["a1", "b1", "a2", "a3"], log)
    }

    @Test
    void underMemoryPressureNewRunsWaitButOneAlwaysRuns() {
        HandExecutor executor = new HandExecutor()
        int heap = 90
        RunAdmission admission = admission(executor, 4, 4, { -> heap }, 85)

        assertEquals(false, admission.submit("A", "a1", work("a1")).queued, "nothing running: one starts regardless")
        assertEquals(true, admission.submit("A", "a2", work("a2")).queued)
        assertTrue(admission.snapshot().heldForMemory as boolean)

        heap = 60
        admission.drain()
        assertEquals(2, executor.started.size())
        assertFalse(admission.snapshot().heldForMemory as boolean)
    }

    @Test
    void aRunThatThrowsStillFreesItsSlot() {
        HandExecutor executor = new HandExecutor()
        RunAdmission admission = admission(executor, 1, 1)
        admission.submit("A", "boom", { -> throw new IllegalStateException("boom") } as Runnable)
        admission.submit("A", "a2", work("a2"))

        executor.finish(0)
        assertEquals(2, executor.started.size())
        assertEquals(0, admission.snapshot().queued)
    }

    @Test
    void canStartNowIsFalseWhenARunWouldHaveToWait() {
        HandExecutor executor = new HandExecutor()
        RunAdmission admission = admission(executor, 3, 2)
        admission.submit("A", "a1", work("a1"))
        assertTrue(admission.canStartNow("A"))
        admission.submit("A", "a2", work("a2"))
        assertFalse(admission.canStartNow("A"), "A's share is used")
        assertTrue(admission.canStartNow("B"))
        admission.submit("B", "b1", work("b1"))
        assertFalse(admission.canStartNow("C"), "the instance is full")
    }

    @Test
    void canStartNowRespectsTheMemoryHold() {
        HandExecutor executor = new HandExecutor()
        RunAdmission admission = admission(executor, 4, 4, { -> 95 }, 85)
        assertTrue(admission.canStartNow("A"), "nothing running: one may start")
        admission.submit("A", "a1", work("a1"))
        assertFalse(admission.canStartNow("B"))
    }

    @Test
    void aRefusedStartLeavesNoSlotTaken() {
        HandExecutor executor = new HandExecutor(reject: true)
        RunAdmission admission = admission(executor, 1, 1)
        Map submitted = admission.submit("A", "a1", work("a1"))
        assertNotNull(submitted.error)
        assertEquals(false, submitted.submitted)
        assertEquals(0, admission.snapshot().running)

        executor.reject = false
        assertEquals(false, admission.submit("A", "a2", work("a2")).queued)
    }

    @Test
    void snapshotNamesRunningAndWaitingRunsByTenant() {
        HandExecutor executor = new HandExecutor()
        RunAdmission admission = admission(executor, 2, 1)
        admission.submit("A", "a1", work("a1"))
        admission.submit("A", "a2", work("a2"))
        admission.submit("B", "b1", work("b1"))

        Map snapshot = admission.snapshot()
        assertEquals(2, snapshot.running)
        assertEquals(1, snapshot.queued)
        assertEquals(2, snapshot.cap)
        assertEquals(1, snapshot.perTenantCap)
        assertEquals([A: [running: 1, queued: 1], B: [running: 1, queued: 0]], snapshot.byTenant)
    }

    @Test
    void aShareLargerThanTheCapIsTheCap() {
        RunAdmission admission = admission(new HandExecutor(), 3, 10)
        assertEquals(3, admission.snapshot().perTenantCap)
    }
}
