package reconciliation.observability

import darpan.facade.reconciliation.RunObservability
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Live-H2 proof that the interactive run#SavedRunDiff pipeline is instrumented with the
 * RunObservability status lifecycle: the returned run row always ends in a TERMINAL status
 * (never abandoned RUNNING) and an ordered, fully-terminal ReconciliationRunStep timeline
 * exists for the run — including early validation-error exits.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunObservabilityPipelineIntegrationTest {

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "obs_pipeline")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SourceSystemConnectorSeedData.xml")
        ReconciliationSmokeTestSupport.seedBaseCompareRuleSet(ec)
    }

    @AfterAll
    void teardown() {
        if (ec != null) ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void resetTenantContext() {
        ec.message.clearErrors()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ec.message.clearErrors()
    }

    @Test
    void happyRunWritesOrderedStepsAndTerminalStatus() {
        Map<String, Object> createResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.create#CsvRun")
                .parameters([
                        runName           : "Obs Pipeline Compare",
                        file1SystemEnumId : "OMS",
                        file2SystemEnumId : "SHOPIFY",
                        file1CompareColumn: "order_id",
                        file2CompareColumn: "order_id",
                ])
                .disableAuthz()
                .call()
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertNotNull(createResult.savedRun.savedRunId)

        Map<String, Object> runResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.run#SavedRunDiff")
                .parameters([
                        savedRunId: createResult.savedRun.savedRunId,
                        file1Name : "orders-1.csv",
                        file1Text : "order_id\nA100\nA200\nA300\n",
                        file2Name : "orders-2.csv",
                        file2Text : "order_id\nA200\nA300\nA400\n",
                        hasHeader : true,
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        String runId = runResult.runResult.reconciliationRunResultId as String
        assertTrue(runId != null && !runId.isEmpty(), "run must return a reconciliationRunResultId")

        def run = ec.entity.find(RunObservability.RUN_RESULT_ENTITY)
                .condition("reconciliationRunResultId", runId)
                .disableAuthz()
                .useCache(false)
                .one()
        assertNotNull(run)
        assertTrue(RunObservability.isTerminalStatus(run.statusEnumId as String),
                "run must end in a terminal status, was ${run.statusEnumId}")
        assertFalse(RunObservability.STATUS_RUNNING == (run.statusEnumId as String))
        assertNotNull(run.startedDate)
        assertNotNull(run.completedDate)

        List steps = ec.entity.find(RunObservability.RUN_STEP_ENTITY)
                .condition("reconciliationRunResultId", runId)
                .orderBy("stageSequence")
                .disableAuthz()
                .useCache(false)
                .list() as List
        assertTrue(steps.size() >= 1, "at least one step row must be written")
        int prev = -1
        List<String> stageCodes = []
        steps.each { def step ->
            int seq = (step.stageSequence ?: 0) as int
            assertTrue(seq >= prev, "steps out of order at ${step.stageCode}")
            prev = seq
            stageCodes.add(step.stageCode as String)
            assertTrue(RunObservability.isTerminalStatus(step.statusEnumId as String),
                    "step ${step.stageCode} not terminal: ${step.statusEnumId}")
        }
        assertTrue(stageCodes.contains(RunObservability.STAGE_RESOLVE), "expected a RESOLVE step, got ${stageCodes}")
        assertTrue(stageCodes.contains(RunObservability.STAGE_COMPARE), "expected a COMPARE step, got ${stageCodes}")
    }

    @Test
    void validationFailureStillEndsRunTerminal() {
        Map<String, Object> runResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.run#SavedRunDiff")
                .parameters([
                        savedRunId: "OBS_MISSING_RUN",
                        file1Name : "orders-1.csv",
                        file1Text : "order_id\nA100\n",
                        file2Name : "orders-2.csv",
                        file2Text : "order_id\nA200\n",
                        hasHeader : true,
                ])
                .disableAuthz()
                .call()

        assertEquals(false, runResult.ok)
        ec.message.clearErrors()

        List runs = ec.entity.find(RunObservability.RUN_RESULT_ENTITY)
                .condition("savedRunId", "OBS_MISSING_RUN")
                .disableAuthz()
                .useCache(false)
                .list() as List
        assertEquals(1, runs.size(), "a validation-error run must still leave exactly one terminal run row")
        def run = runs[0]
        assertEquals(RunObservability.STATUS_FAILED, run.statusEnumId as String)
        assertNotNull(run.completedDate)
        assertNotNull(run.errorMessage)

        List steps = ec.entity.find(RunObservability.RUN_STEP_ENTITY)
                .condition("reconciliationRunResultId", run.reconciliationRunResultId as String)
                .orderBy("stageSequence")
                .disableAuthz()
                .useCache(false)
                .list() as List
        steps.each { def step ->
            assertTrue(RunObservability.isTerminalStatus(step.statusEnumId as String),
                    "step ${step.stageCode} not terminal: ${step.statusEnumId}")
        }
    }
}
