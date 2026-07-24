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
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Live-H2 proof for facade.ReconciliationFacadeServices.get#ReconciliationRunStatus: live
 * status + ordered per-stage timeline for a tenant-scoped run, and a clean "not found" for a
 * caller-supplied id the active tenant cannot see. Rows are seeded directly via
 * RunObservability (the same write path Task 4/5 instrumented), then read back through the
 * facade service under a real logged-in tenant user — TenantAccessSupport.canAccessTenantRecord
 * requires an active-tenant preference, so this mirrors the canonical tenant/login fixture used
 * by SavedRunsFacadeSmokeTests / RunObservabilityPipelineIntegrationTest
 * (ReconciliationSmokeTestSupport.seedCompanyScope binds a KREWE-tenant test user) rather than
 * relying on disableAuthz alone, which only bypasses Moqui artifact authz — not this facade-level
 * tenant gate.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReconciliationRunStatusServiceTest {

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "obs_status_svc")
        // RunObservability.STATUS_* values are FK-checked against moqui.basic.Enumeration
        // (RECRES_STATUS / RECSTEP_STATUS) — without this seed, beginRun/beginStep fail closed
        // (best-effort catch, no exception) and silently mint no rows at all.
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
    }

    @AfterAll
    void teardown() { if (ec != null) ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void resetTenantContext() {
        ec.message.clearErrors()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ec.message.clearErrors()
    }

    @Test
    void returnsStatusAndOrderedSteps() {
        // Seed a run + two steps directly via RunObservability.
        String runId = RunObservability.beginRun(ec, [savedRunId: "SRX", companyUserGroupId: "KREWE"])
        def s1 = RunObservability.beginStep(ec, runId, [companyUserGroupId: "KREWE"], RunObservability.STAGE_EXTRACT_FILE1)
        RunObservability.endStep(ec, s1, RunObservability.STATUS_SUCCESS, [recordCount: 5])
        RunObservability.completeRun(ec, runId, RunObservability.STATUS_SUCCESS, [:])

        Map res = (Map) ec.service.sync()
                .name("facade.ReconciliationFacadeServices.get#ReconciliationRunStatus")
                .parameters([reconciliationRunResultId: runId]).disableAuthz().call()

        assertEquals(true, res.ok)
        assertEquals(RunObservability.STATUS_SUCCESS, res.statusEnumId)
        List steps = (List) res.steps
        assertNotNull(steps)
        assertTrue(steps.size() >= 1)
        assertEquals(RunObservability.STAGE_EXTRACT_FILE1, ((Map) steps[0]).stageCode)
        assertEquals(5, ((Map) steps[0]).recordCount)
    }

    @Test
    void nonexistentRunIsReportedNotFound() {
        Map res = (Map) ec.service.sync()
                .name("facade.ReconciliationFacadeServices.get#ReconciliationRunStatus")
                .parameters([reconciliationRunResultId: "DOES_NOT_EXIST_${System.nanoTime()}".toString()])
                .disableAuthz().call()

        assertEquals(false, res.ok)
        List errors = (List) res.errors
        assertNotNull(errors)
        assertTrue(errors.any { it.toString().contains("was not found") }, "expected a not-found error, got: ${errors}")
    }
}
