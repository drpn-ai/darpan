package reconciliation.observability

import darpan.facade.reconciliation.RunObservability
import org.junit.jupiter.api.Test

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

class RunObservabilityWriteTest {

    // ---- Recording fakes (no DB) -------------------------------------------------
    static class FakeVal {
        String entityName
        Map<String, Object> v = [:]
        List<String> ops = []
        FakeVal(String n) { entityName = n }
        def set(String k, Object val) { v[k] = val; this }
        Object get(String k) { v[k] }
        def setSequencedIdPrimary() {
            String pkField = entityName.endsWith("ReconciliationRunStep") ? "reconciliationRunStepId" : "reconciliationRunResultId"
            if (!v[pkField]) v[pkField] = "${entityName.tokenize('.').last()}_${++FakeEc.seq}".toString()
            this
        }
        def create() { ops << "create"; this }
        def update() { ops << "update"; this }
    }
    static class FakeFind {
        String entityName; FakeEc ec; Map<String, Object> cond = [:]
        FakeFind(String n, FakeEc e) { entityName = n; ec = e }
        FakeFind condition(String k, Object val) { cond[k] = val; this }
        FakeFind useCache(boolean b) { this }
        FakeFind disableAuthz() { this }
        FakeVal one() { ec.store.find { it.entityName == entityName && cond.every { c -> it.v[c.key] == c.value } } }
    }
    static class FakeEntity {
        FakeEc ec
        FakeEntity(FakeEc e) { ec = e }
        FakeVal makeValue(String n) { def x = new FakeVal(n); ec.store << x; x }
        FakeFind find(String n) { new FakeFind(n, ec) }
    }
    static class FakeTx { def runUseOrBegin(int t, String m, Closure work) { work.call() } }
    static class FakeUser { Timestamp nowTimestamp = new Timestamp(1_700_000_000_000L) }
    static class FakeEc {
        static int seq = 0
        List<FakeVal> store = []
        FakeEntity entity = new FakeEntity(this)
        FakeTx transaction = new FakeTx()
        FakeUser user = new FakeUser()
    }
    // -----------------------------------------------------------------------------

    @Test
    void beginRunCreatesRunningRowAndReturnsId() {
        def ec = new FakeEc()
        String runId = RunObservability.beginRun(ec, [savedRunId: "SR1", companyUserGroupId: "KREWE"])
        assertNotNull(runId)
        def run = ec.store.find { it.entityName == RunObservability.RUN_RESULT_ENTITY }
        assertEquals(RunObservability.STATUS_RUNNING, run.get("statusEnumId"))
        assertEquals(RunObservability.STAGE_RESOLVE, run.get("currentStage"))
        assertNotNull(run.get("startedDate"))
        assertTrue(run.ops.contains("create"))
    }

    @Test
    void stepLifecycleWritesRunningThenTerminal() {
        def ec = new FakeEc()
        String runId = RunObservability.beginRun(ec, [savedRunId: "SR1", companyUserGroupId: "KREWE"])
        def step = RunObservability.beginStep(ec, runId, [companyUserGroupId: "KREWE"], RunObservability.STAGE_EXTRACT_FILE1)
        assertEquals(RunObservability.STATUS_RUNNING, step.get("statusEnumId"))
        assertEquals(2, step.get("stageSequence"))

        RunObservability.endStep(ec, step, RunObservability.STATUS_SUCCESS, [recordCount: 4213])
        assertEquals(RunObservability.STATUS_SUCCESS, step.get("statusEnumId"))
        assertEquals(4213, step.get("recordCount"))
        assertNotNull(step.get("completedDate"))
    }

    @Test
    void endStepTruncatesErrorMessageAtTextMediumSeam() {
        // errorMessage is text-medium (VARCHAR(255)). A raw exception message longer than that
        // (e.g. passed through from the NOTIFY catch in runSavedRunDiff.groovy) must be truncated
        // here rather than left to fail the step UPDATE and strand the step in RUNNING.
        def ec = new FakeEc()
        String runId = RunObservability.beginRun(ec, [savedRunId: "SR1", companyUserGroupId: "KREWE"])
        def step = RunObservability.beginStep(ec, runId, [companyUserGroupId: "KREWE"], RunObservability.STAGE_NOTIFY)

        String longMessage = "x" * 300
        RunObservability.endStep(ec, step, RunObservability.STATUS_FAILED, [errorMessage: longMessage])

        assertEquals(255, (step.get("errorMessage") as String).length())
        assertEquals(RunObservability.STATUS_FAILED, step.get("statusEnumId"))
    }

    @Test
    void beginStepClearsRunProgressPercentFromPriorStage() {
        // The run-level progressPercent mirrors the CURRENT stage's advisory progress (only the
        // extract stages report one today). Advancing to a new stage must clear the mirror, or the
        // UI shows the old extract percent against the new stage label ("Comparing records · 99%").
        def ec = new FakeEc()
        String runId = RunObservability.beginRun(ec, [savedRunId: "SR1", companyUserGroupId: "KREWE"])
        RunObservability.beginStep(ec, runId, [companyUserGroupId: "KREWE"], RunObservability.STAGE_EXTRACT_FILE2)
        RunObservability.heartbeatStageProgress(ec, runId, RunObservability.STAGE_EXTRACT_FILE2, 4200, 4213)
        def run = ec.store.find { it.entityName == RunObservability.RUN_RESULT_ENTITY }
        assertEquals(99, run.get("progressPercent"))

        RunObservability.beginStep(ec, runId, [companyUserGroupId: "KREWE"], RunObservability.STAGE_COMPARE)
        assertEquals(RunObservability.STAGE_COMPARE, run.get("currentStage"))
        assertNull(run.get("progressPercent"))
    }

    @Test
    void heartbeatStageProgressCountsRecordsWithoutAnExpectedTotal() {
        // The first extract of a run has no denominator to divide by — nothing has finished yet —
        // so its progress is a running count. Requiring an expected total meant file1 reported no
        // progress at all for the whole stage, leaving the live view frozen on "Running".
        def ec = new FakeEc()
        String runId = RunObservability.beginRun(ec, [savedRunId: "SR1", companyUserGroupId: "KREWE"])
        def step = RunObservability.beginStep(ec, runId, [companyUserGroupId: "KREWE"], RunObservability.STAGE_EXTRACT_FILE1)

        RunObservability.heartbeatStageProgress(ec, runId, RunObservability.STAGE_EXTRACT_FILE1, 8450, null)

        assertEquals(8450, step.get("recordCount"))
        assertNotNull(step.get("heartbeatDate"))
        // No denominator means no percent to report — the count is the progress.
        def run = ec.store.find { it.entityName == RunObservability.RUN_RESULT_ENTITY }
        assertNull(run.get("progressPercent"))
        assertNotNull(run.get("lastHeartbeatDate"))
    }

    @Test
    void failRunSetsFailedAndClosesOpenStep() {
        def ec = new FakeEc()
        String runId = RunObservability.beginRun(ec, [savedRunId: "SR1", companyUserGroupId: "KREWE"])
        def step = RunObservability.beginStep(ec, runId, [companyUserGroupId: "KREWE"], RunObservability.STAGE_COMPARE)

        RunObservability.failRun(ec, runId, step, RunObservability.STAGE_COMPARE, "spark timed out")
        def run = ec.store.find { it.entityName == RunObservability.RUN_RESULT_ENTITY }
        assertEquals(RunObservability.STATUS_FAILED, run.get("statusEnumId"))
        assertEquals("spark timed out", run.get("errorMessage"))
        assertEquals(RunObservability.STATUS_FAILED, step.get("statusEnumId"))
    }

    @Test
    void completeRunSetsTerminalStatus() {
        def ec = new FakeEc()
        String runId = RunObservability.beginRun(ec, [savedRunId: "SR1", companyUserGroupId: "KREWE"])
        RunObservability.completeRun(ec, runId, RunObservability.STATUS_SUCCESS, [:])
        def run = ec.store.find { it.entityName == RunObservability.RUN_RESULT_ENTITY }
        assertEquals(RunObservability.STATUS_SUCCESS, run.get("statusEnumId"))
        assertNotNull(run.get("completedDate"))
    }

    @Test
    void observabilityWriteNeverThrows() {
        // A broken ec (throws on any entity op AND on ec.user, so the timestamp source itself
        // is also broken) must not propagate from ANY write method — observability is best-effort
        // and must never mask the run's real error.
        def brokenUser = new Object() {
            def getNowTimestamp() { throw new RuntimeException("user ctx gone") }
        }
        def brokenEc = new Object() {
            def entity = new Object() { def makeValue(String n) { throw new RuntimeException("db down") }
                                        def find(String n) { throw new RuntimeException("db down") } }
            def transaction = new FakeTx()
            def user = brokenUser
        }
        def brokenStep = new Object() {
            def get(String k) { throw new RuntimeException("step read broken") }
            def set(String k, Object val) { throw new RuntimeException("step write broken") }
            def update() { throw new RuntimeException("step update broken") }
        }

        // Every write method must return/complete without throwing, even when both entity ops
        // and ec.user.nowTimestamp are broken.
        String runId = RunObservability.beginRun(brokenEc, [savedRunId: "SR1"])
        RunObservability.beginStep(brokenEc, runId, [companyUserGroupId: "KREWE"], RunObservability.STAGE_EXTRACT_FILE1)
        RunObservability.heartbeat(brokenEc, brokenStep, [recordCount: 10])
        RunObservability.endStep(brokenEc, brokenStep, RunObservability.STATUS_SUCCESS, [recordCount: 10])
        RunObservability.completeRun(brokenEc, "R1", RunObservability.STATUS_SUCCESS, [:])
        RunObservability.failRun(brokenEc, "R1", brokenStep, RunObservability.STAGE_COMPARE, "boom")
    }
}
