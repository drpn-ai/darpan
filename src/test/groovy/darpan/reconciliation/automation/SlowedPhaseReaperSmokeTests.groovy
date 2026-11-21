package darpan.reconciliation.automation

import darpan.config.DarpanSettings
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.RunObservability
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-328 against a real Moqui: an extract phase slowed on purpose by a peak period (HotWax share, NetSuite one at a
 * time) can run longer than reaperStaleMinutes with no phase boundary in it. Its page heartbeats keep the run-result row
 * fresh and touch the automation execution that owns the run, so StuckRunReaper leaves both alone; an execution whose
 * run sent no heartbeat over the same time is still reaped.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SlowedPhaseReaperSmokeTests {
    private static final String TENANT = "REAP_SLOW"
    private static final String EXECUTION_ENTITY = "darpan.reconciliation.ReconciliationAutomationExecution"
    private static final String RUN_ENTITY = "darpan.reconciliation.ReconciliationRunResult"

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "slowed-phase-reaper-smoke")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ec.artifactExecution.disableAuthz()
        try {
            ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: TENANT, description: TENANT,
                    groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]).createOrUpdate()
            ec.entity.makeValue("darpan.reconciliation.ReconciliationAutomation").setAll([automationId: "REAP_AUT",
                    automationName: "REAP_AUT", companyUserGroupId: TENANT, inputModeEnumId: "AUT_IN_API_RANGE",
                    savedRunId: "SR_REAP_AUT", scheduleExpr: "0 0 2 * * ?", windowTimeZone: "UTC", isActive: "Y"]).createOrUpdate()
        } finally {
            ec.artifactExecution.enableAuthz()
        }
    }

    @AfterEach
    void reset() { RunObservability.resetExecutionTouchesForTests() }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @Test
    void aPhaseThatKeepsHeartbeatingPastTheReaperThresholdIsNotReaped() {
        int staleMinutes = DarpanSettings.intValue("darpan.reconciliation.runHealth.reaperStaleMinutes")
        // The phase began, and its rows were last written at its boundary, longer ago than the reaper allows.
        Timestamp phaseStart = new Timestamp(System.currentTimeMillis() - (staleMinutes + 30) * 60_000L)
        runningRun("REAP_RR_SLOW", "REAP_EX_SLOW", phaseStart)
        runningRun("REAP_RR_IDLE", "REAP_EX_IDLE", phaseStart)

        // A page of the slowed extract lands now.
        asSystem { RunObservability.heartbeatStageProgress(ec, "REAP_RR_SLOW", RunObservability.STAGE_EXTRACT_FILE2, 500, null) }
        asSystem { StuckRunReaper.sweep(ec) }

        assertEquals(StuckRunReaper.STATUS_RUNNING, status(EXECUTION_ENTITY, "automationExecutionId", "REAP_EX_SLOW"),
                "the heartbeating run's execution is not reaped")
        assertEquals(StuckRunReaper.STATUS_RUNNING, status(RUN_ENTITY, "reconciliationRunResultId", "REAP_RR_SLOW"))
        assertEquals(StuckRunReaper.STATUS_FAILED, status(EXECUTION_ENTITY, "automationExecutionId", "REAP_EX_IDLE"),
                "an execution with no heartbeat over the same time is reaped")
    }

    @Test
    void anExecutionIsTouchedAtMostOnceAMinute() {
        long clockMs = 5_000_000L
        RunObservability.executionTouchClock = { -> clockMs }
        Timestamp stale = new Timestamp(System.currentTimeMillis() - 300 * 60_000L)
        runningRun("REAP_RR_TICK", "REAP_EX_TICK", stale)

        asSystem { RunObservability.heartbeatStageProgress(ec, "REAP_RR_TICK", RunObservability.STAGE_EXTRACT_FILE2, 10, null) }
        assertTrue(stamp("REAP_EX_TICK") > stale.time, "the first heartbeat touches the execution")

        restamp("REAP_EX_TICK", stale)
        clockMs += 59_000L
        asSystem { RunObservability.heartbeatStageProgress(ec, "REAP_RR_TICK", RunObservability.STAGE_EXTRACT_FILE2, 20, null) }
        assertEquals(stale.time, stamp("REAP_EX_TICK"), "a heartbeat within the minute leaves the execution alone")

        clockMs += 1_000L
        asSystem { RunObservability.heartbeatStageProgress(ec, "REAP_RR_TICK", RunObservability.STAGE_EXTRACT_FILE2, 30, null) }
        assertTrue(stamp("REAP_EX_TICK") > stale.time, "a minute after the last touch it is touched again")
    }

    private void runningRun(String runId, String executionId, Timestamp lastWritten) {
        asSystem {
            ec.entity.makeValue(RUN_ENTITY).setAll([reconciliationRunResultId: runId, companyUserGroupId: TENANT,
                    statusEnumId: StuckRunReaper.STATUS_RUNNING, currentStage: RunObservability.STAGE_EXTRACT_FILE2,
                    startedDate: lastWritten, lastUpdatedDate: lastWritten, lastUpdatedStamp: lastWritten]).create()
            ec.entity.makeValue("darpan.reconciliation.ReconciliationRunStep").setAll([reconciliationRunStepId: "${runId}_S2".toString(),
                    reconciliationRunResultId: runId, companyUserGroupId: TENANT, stageCode: RunObservability.STAGE_EXTRACT_FILE2,
                    statusEnumId: StuckRunReaper.STATUS_RUNNING, startedDate: lastWritten, heartbeatDate: lastWritten]).create()
            ec.entity.makeValue(EXECUTION_ENTITY).setAll([automationExecutionId: executionId, automationId: "REAP_AUT",
                    companyUserGroupId: TENANT, statusEnumId: StuckRunReaper.STATUS_RUNNING, startedDate: lastWritten,
                    reconciliationRunResultId: runId, lastUpdatedDate: lastWritten, lastUpdatedStamp: lastWritten]).create()
        }
        assertEquals(lastWritten.time, stamp(executionId), "the fixture keeps its stale stamp")
    }

    private void restamp(String executionId, Timestamp at) {
        asSystem {
            def row = ec.entity.find(EXECUTION_ENTITY).condition("automationExecutionId", executionId).useCache(false).one()
            row.set("lastUpdatedDate", new Timestamp(at.time + 1L))
            row.set("lastUpdatedStamp", at)
            row.update()
        }
    }

    private long stamp(String executionId) {
        return asSystem {
            ((Timestamp) ec.entity.find(EXECUTION_ENTITY).condition("automationExecutionId", executionId).useCache(false).one()
                    .get("lastUpdatedStamp")).time
        } as long
    }

    private String status(String entity, String pkField, String id) {
        return asSystem { ec.entity.find(entity).condition(pkField, id).useCache(false).one().get("statusEnumId") } as String
    }

    private Object asSystem(Closure work) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        try {
            return work.call()
        } finally {
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
    }
}
