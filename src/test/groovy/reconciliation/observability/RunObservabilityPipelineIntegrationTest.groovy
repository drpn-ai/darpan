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
 * exists for the run. Pre-validated/access-denied requests mint NO run row at all; errors
 * after beginRun (resolution/source validation) end the minted row terminal FAILED.
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
        // DAR-BE-049: brings DARPAN_TEST_EVALUATE_RS, a RuleSet whose single compare scope is
        // single-sided, so the evaluate fork can be driven through the real facade service.
        ReconciliationSmokeTestSupport.seedCompareScopeFixtures(ec)
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
    void singleSidedEvaluateRunProducesFindingsAndNeverStagesASecondSide() {
        // END TO END through the real facade service, which is the only thing that proves the EVALUATE
        // fork is REACHABLE. Every earlier slice was green while the Run button still refused this run:
        // the engine accepted one side, then the resolver refused it, then the upload guard demanded a
        // second file. A passing unit test could not have caught any of those.
        String ordersJson = '[{"data":{"orders":{"edges":[' +
                '{"node":{"id":"gid://shopify/Order/1001"}},' +
                '{"node":{"id":"gid://shopify/Order/1002"}}' +
                ']}}}]'

        Map<String, Object> runResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.run#SavedRunDiff")
                .parameters([
                        savedRunId: "DARPAN_TEST_EVALUATE_RS",
                        file1Name : "orders.json",
                        file1Text : ordersJson,
                        // NO file2Name / file2Text at all — that is the point.
                ])
                .disableAuthz()
                .call()

        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        Map result = (Map) runResult.runResult
        assertNotNull(result, "a single-sided run must return a runResult")
        assertEquals("EVALUATE", result.scopeMode)
        // The extractor owns the predicate, so both source rows are findings.
        assertEquals(2L, (result.differenceCount as Number)?.longValue())
        assertNotNull(result.resultDataManagerPath, "the findings document must be written")

        String runId = result.reconciliationRunResultId as String
        assertTrue(runId != null && !runId.isEmpty())

        def run = ec.entity.find(RunObservability.RUN_RESULT_ENTITY)
                .condition("reconciliationRunResultId", runId)
                .disableAuthz().useCache(false).one()
        assertNotNull(run)
        assertTrue(RunObservability.isTerminalStatus(run.statusEnumId as String),
                "run must end terminal, was ${run.statusEnumId}")

        List<String> stageCodes = (ec.entity.find(RunObservability.RUN_STEP_ENTITY)
                .condition("reconciliationRunResultId", runId)
                .orderBy("stageSequence").disableAuthz().useCache(false).list() as List)
                .collect { def step -> step.stageCode as String }

        assertTrue(stageCodes.contains(RunObservability.STAGE_EXTRACT_FILE1), "expected EXTRACT_FILE1, got ${stageCodes}")
        assertTrue(stageCodes.contains(RunObservability.STAGE_COMPARE), "expected COMPARE, got ${stageCodes}")
        // THE ASSERTION THAT MATTERS MOST: no second side was staged. A fork that fell through to the
        // two-sided path would still produce findings and a terminal status — this is what tells them
        // apart, and it is why the timeline is asserted rather than just the counts.
        assertFalse(stageCodes.contains(RunObservability.STAGE_EXTRACT_FILE2),
                "a single-sided run must not open EXTRACT_FILE2, got ${stageCodes}")
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
    void preValidatedRequestMintsNoRunRow() {
        // Errors raised BEFORE beginRun (here: file1/file2 system-enum pairing) must not mint a
        // ReconciliationRunResult row or any ReconciliationRunStep rows at all.
        long runRowsBefore = ec.entity.find(RunObservability.RUN_RESULT_ENTITY).disableAuthz().useCache(false).count()
        long stepRowsBefore = ec.entity.find(RunObservability.RUN_STEP_ENTITY).disableAuthz().useCache(false).count()

        Map<String, Object> runResult = ec.service.sync()
                .name("facade.ReconciliationFacadeServices.run#SavedRunDiff")
                .parameters([
                        savedRunId       : "OBS_PREVALID_RUN",
                        file1SystemEnumId: "OMS",
                        file1Name        : "orders-1.csv",
                        file1Text        : "order_id\nA100\n",
                        file2Name        : "orders-2.csv",
                        file2Text        : "order_id\nA200\n",
                        hasHeader        : true,
                ])
                .disableAuthz()
                .call()

        assertEquals(false, runResult.ok)
        ec.message.clearErrors()

        assertEquals(0, ec.entity.find(RunObservability.RUN_RESULT_ENTITY)
                .condition("savedRunId", "OBS_PREVALID_RUN")
                .disableAuthz()
                .useCache(false)
                .list()
                .size(), "pre-validated request must not mint a run row")
        assertEquals(runRowsBefore, ec.entity.find(RunObservability.RUN_RESULT_ENTITY).disableAuthz().useCache(false).count(),
                "pre-validated request must not create ANY run row")
        assertEquals(stepRowsBefore, ec.entity.find(RunObservability.RUN_STEP_ENTITY).disableAuthz().useCache(false).count(),
                "pre-validated request must not create ANY step row")
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
        assertTrue(steps.size() >= 1, "post-beginRun validation failure must leave a closed step timeline")
        steps.each { def step ->
            assertTrue(RunObservability.isTerminalStatus(step.statusEnumId as String),
                    "step ${step.stageCode} not terminal: ${step.statusEnumId}")
        }
    }
}
