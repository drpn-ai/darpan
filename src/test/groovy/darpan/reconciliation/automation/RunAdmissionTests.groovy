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

    /**
     * DAR-OPS-029 S07a. A tenant whose last waiting run started leaves the waiting map; the next turn must still go to the
     * tenant after it, not back to the first tenant in the map. Before: a1, b1, a2, c1 (C skipped once A queued again).
     */
    @Test
    void aTenantThatEmptiedItsQueueDoesNotResetTheTurnOrder() {
        HandExecutor executor = new HandExecutor()
        RunAdmission admission = admission(executor, 1, 1)
        admission.submit("A", "a1", work("a1"))
        admission.submit("A", "a2", work("a2"))
        admission.submit("A", "a3", work("a3"))
        admission.submit("B", "b1", work("b1"))
        admission.submit("C", "c1", work("c1"))

        (0..4).each { executor.finish(it) }
        assertEquals(["a1", "b1", "c1", "a2", "a3"], log)
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
        assertFalse(admission.busy("boom"), "its label is released too")
    }

    /** DAR-OPS-029 S07a QA: a settings read that fails as a run ends used to skip the release and keep the slot for good. */
    @Test
    void aSettingsReadThatFailsAsARunEndsStillFreesItsSlot() {
        HandExecutor executor = new HandExecutor()
        boolean failReads = false
        RunAdmission admission = new RunAdmission({ ->
            if (failReads) throw new IllegalStateException("settings catalog not loaded")
            return 1
        }, { -> 1 }, { -> 85 }, { -> null }, executor, { -> 50 })
        admission.submit("A", "a1", work("a1"))
        failReads = true
        executor.finish(0)
        failReads = false
        assertEquals(0, admission.snapshot().running)
        assertFalse(admission.busy("a1"))
        assertEquals(false, admission.submit("A", "a2", work("a2")).queued, "the slot came back")
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
    void oneTenantsQueueDoesNotStopAnotherTenantsRunFromStarting() {
        // Security review 2026-10-27: counting every waiting run against the instance let one tenant who played over and
        // over defer every other tenant's scheduled run while slots stood free.
        HandExecutor executor = new HandExecutor()
        RunAdmission admission = admission(executor, 8, 2)
        (1..12).each { admission.submit("A", "a${it}", work("a${it}")) }
        assertEquals(2, executor.started.size())
        assertTrue(admission.canStartNow("B"), "6 slots are free and B runs nothing")
        assertEquals(false, admission.submit("B", "b1", work("b1")).queued)
    }

    @Test
    void aTenantCannotQueueWithoutLimit() {
        HandExecutor executor = new HandExecutor()
        RunAdmission admission = new RunAdmission(1, 1, 85, { -> null }, executor, 2)
        admission.submit("A", "a1", work("a1"))
        assertEquals(true, admission.submit("A", "a2", work("a2")).queued)
        assertEquals(true, admission.submit("A", "a3", work("a3")).queued)
        Map refused = admission.submit("A", "a4", work("a4"))
        assertEquals(false, refused.submitted)
        assertTrue((refused.error as String).contains("2 runs waiting"), refused.error as String)
        assertEquals(2, admission.snapshot().queued, "the refused run was not kept")
        assertEquals(true, admission.submit("B", "b1", work("b1")).queued, "another tenant still queues")
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
        assertFalse(admission.busy("a1"), "a refused start leaves no label behind")

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

    /** Review Focus 3 (DAR-BE-304): a super admin lowers the cap while runs are going; they are never touched. */
    @Test
    void loweringTheCapMidFlightOnlyHoldsNewStarts() {
        HandExecutor executor = new HandExecutor()
        int cap = 3
        RunAdmission admission = new RunAdmission({ -> cap }, { -> 5 }, { -> 85 }, { -> null }, executor, { -> 50 })
        ["a1", "a2", "a3"].each { admission.submit("A", it, work(it)) }
        assertEquals(3, executor.started.size())

        cap = 1
        assertEquals(3, admission.snapshot().running)
        assertEquals(1, admission.snapshot().cap)
        Map fourth = admission.submit("A", "a4", work("a4"))
        assertEquals(true, fourth.queued)

        executor.finish(0)
        assertEquals(3, executor.started.size(), "two still running, above the new cap of 1: the 4th keeps waiting")
        executor.finish(1)
        assertEquals(3, executor.started.size(), "one running is at the cap of 1: still waiting")
        executor.finish(2)
        assertEquals(4, executor.started.size(), "nothing running: the 4th starts")
        assertEquals(["a1", "a2", "a3"], log)
    }

    @Test
    void raisingTheCapStartsWaitingRunsAtTheNextDrain() {
        HandExecutor executor = new HandExecutor()
        int cap = 1
        RunAdmission admission = new RunAdmission({ -> cap }, { -> 5 }, { -> 85 }, { -> null }, executor, { -> 50 })
        admission.submit("A", "a1", work("a1"))
        admission.submit("A", "a2", work("a2"))
        admission.submit("A", "a3", work("a3"))
        assertEquals(1, executor.started.size())

        cap = 3
        admission.drain()
        assertEquals(3, executor.started.size())
    }

    @Test
    void snapshotReportsTheLiveCaps() {
        int cap = 4, perTenant = 2, holdAt = 90, maxWaiting = 7
        RunAdmission admission = new RunAdmission({ -> cap }, { -> perTenant }, { -> holdAt }, { -> null },
                new HandExecutor(), { -> maxWaiting })
        Map first = admission.snapshot()
        assertEquals([4, 2, 90, 7], [first.cap, first.perTenantCap, first.holdAtHeapPercent, first.maxWaitingPerTenant])
        cap = 1; perTenant = 9; holdAt = 70; maxWaiting = 3
        Map second = admission.snapshot()
        assertEquals([1, 1, 70, 3], [second.cap, second.perTenantCap, second.holdAtHeapPercent, second.maxWaitingPerTenant],
                "the per-tenant share never exceeds the live instance cap")
    }

    /** A slow settings reload (the 30-second refresh hits the database) must not hold the admission lock. */
    @Test
    void aBlockedSettingsReadDoesNotBlockOtherCallers() {
        HandExecutor executor = new HandExecutor()
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1)
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1)
        boolean blockReader = false
        RunAdmission admission = new RunAdmission({ ->
            if (blockReader && Thread.currentThread().name == "slow-reader") { entered.countDown(); release.await() }
            return 4
        }, { -> 4 }, { -> 85 }, { -> null }, executor, { -> 50 })
        admission.submit("A", "a1", work("a1"))
        blockReader = true

        Thread reader = new Thread({ -> admission.submit("B", "b1", work("b1")) } as Runnable, "slow-reader")
        reader.daemon = true
        reader.start()
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "the reader reached the settings read")
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                assertEquals(1, pool.submit({ -> admission.snapshot().running } as java.util.concurrent.Callable<Integer>)
                        .get(3, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(pool.submit({ -> admission.busy("a1") } as java.util.concurrent.Callable<Boolean>)
                        .get(3, java.util.concurrent.TimeUnit.SECONDS))
                pool.submit({ -> executor.finish(0) } as Runnable).get(3, java.util.concurrent.TimeUnit.SECONDS)
            } finally {
                pool.shutdownNow()
            }
        } finally {
            release.countDown()
            reader.join(5000)
        }
        assertEquals(2, executor.started.size(), "the blocked submit started once its read returned")
    }

    // ---------------------------------------------------------------------------------------------- DAR-BE-379 light lane

    private RunAdmission withLightCap(HandExecutor executor, int cap, int perTenant, int lightCap,
                                      Closure<Integer> heap = { -> null }, int holdAt = 85) {
        return new RunAdmission({ -> cap }, { -> perTenant }, { -> holdAt }, heap, executor, { -> 50 }, { -> lightCap })
    }

    /** Gorjana's month close held its whole share and the instance cap; its pulse must still start, taking no run slot. */
    @Test
    void aLightRunStartsWhenItsTenantAndTheInstanceAreFull() {
        HandExecutor executor = new HandExecutor()
        RunAdmission admission = withLightCap(executor, 1, 1, 4)
        admission.submit("A", "month close", work("audit"))
        assertFalse(admission.canStartNow("A"), "precondition: tenant and instance full")

        Map light = admission.submitLight("pulse P1", work("pulse"))
        assertEquals([submitted: true, queued: false], light)
        assertEquals(2, executor.started.size())
        assertTrue(admission.busy("pulse P1"), "a light run in flight is busy, so the next tick does not start it twice")
        Map snap = admission.snapshot()
        assertEquals([1, 1, 4], [snap.running, snap.lightRunning, snap.lightCap])
        assertEquals([running: 1, queued: 0], snap.byTenant.A, "the pulse is not counted in its tenant's share")

        executor.finish(1)
        assertEquals(["pulse"], log)
        assertFalse(admission.busy("pulse P1"))
        assertEquals([1, 0], [admission.snapshot().running, admission.snapshot().lightRunning])
    }

    @Test
    void theLightLaneHasItsOwnCapAndNeverQueues() {
        HandExecutor executor = new HandExecutor()
        RunAdmission admission = withLightCap(executor, 8, 5, 2)
        assertTrue(admission.submitLight("pulse P1", work("p1")).submitted as boolean)
        assertTrue(admission.submitLight("pulse P2", work("p2")).submitted as boolean)
        Map third = admission.submitLight("pulse P3", work("p3"))
        assertEquals(false, third.submitted)
        assertEquals(false, third.queued)
        assertEquals("all 2 light run slots are in use", third.error)
        assertFalse(admission.busy("pulse P3"), "a refused light run leaves nothing behind")

        executor.finish(0)
        assertTrue(admission.submitLight("pulse P3", work("p3")).submitted as boolean, "a finished light run frees its slot")
        assertTrue(admission.canStartNow("A"), "light runs never take a regular slot")
    }

    @Test
    void theMemoryHoldRefusesALightRun() {
        HandExecutor executor = new HandExecutor()
        RunAdmission admission = withLightCap(executor, 8, 5, 4, { -> 90 }, 85)
        admission.submit("A", "a1", work("a1"))
        Map light = admission.submitLight("pulse P1", work("p1"))
        assertEquals(false, light.submitted)
        assertEquals("memory is holding new starts", light.error)
        assertEquals(0, admission.snapshot().lightRunning)
    }

    @Test
    void aLightRunThePoolRefusesGivesItsSlotBack() {
        HandExecutor executor = new HandExecutor(reject: true)
        RunAdmission admission = withLightCap(executor, 8, 5, 1)
        Map light = admission.submitLight("pulse P1", work("p1"))
        assertEquals(false, light.submitted)
        assertTrue((light.error as String).contains("pool closed"))
        assertFalse(admission.busy("pulse P1"))
        executor.reject = false
        assertTrue(admission.submitLight("pulse P1", work("p1")).submitted as boolean, "the slot came back")
    }

    @Test
    void aLightRunThatThrowsStillGivesItsSlotBack() {
        HandExecutor executor = new HandExecutor()
        RunAdmission admission = withLightCap(executor, 8, 5, 1)
        admission.submitLight("pulse P1", { -> throw new IllegalStateException("reader bug") } as Runnable)
        executor.finish(0)
        assertEquals(0, admission.snapshot().lightRunning)
        assertFalse(admission.busy("pulse P1"))
    }
}
