package darpan.config

import darpan.reconciliation.support.SharedMoqui
import darpan.facade.reconciliation.RunHealth
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-304 Task 3. Run health and the stuck-run reaper read one set of thresholds from DarpanSettings: a row changes
 * both at once, and the old staleMinutes job parameter (still in prod data) no longer overrides it.
 */
@SharedMoqui
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunHealthSettingsSmokeTests {
    private static final String ENTITY = "darpan.config.DarpanSetting"
    private static final String RUN_RESULT = "darpan.reconciliation.ReconciliationRunResult"
    private static final String PREFIX = "darpan.reconciliation.runHealth."
    private static final String REAPER = PREFIX + "reaperStaleMinutes"
    private static final String SILENT = PREFIX + "silentAnyStageMinutes"
    private static final String REAPED_ID = "RUN_RESULT_HEALTH_SETTINGS"

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "run-health-settings")
        DarpanSettings.resetForTest()
    }

    @AfterEach
    void clean() {
        asSystem {
            ec.entity.find(ENTITY).condition("settingKey", "like", PREFIX + "%").deleteAll()
            ec.entity.find(RUN_RESULT).condition("reconciliationRunResultId", REAPED_ID).deleteAll()
        }
        DarpanSettings.resetForTest()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    private void saveRow(String key, String value) {
        ec.transaction.runRequireNew(60, "test save") {
            asSystem {
                ec.entity.makeValue(ENTITY).setAll([settingKey: key, settingValue: value, lastChangedDate: new Timestamp(System.currentTimeMillis())]).createOrUpdate()
                DarpanSettings.expireAfterCommit(ec)
            }
        }
    }

    private static Timestamp minutesAgo(int minutes) { return new Timestamp(System.currentTimeMillis() - minutes * 60_000L) }

    /** A RUNNING run result last updated `minutes` ago (lastUpdatedStamp is what the reaper reads). */
    private void createRunning(int minutes) {
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
        ec.transaction.runRequireNew(60, "test create") {
            asSystem {
                ec.entity.makeValue(RUN_RESULT).setAll([
                        reconciliationRunResultId: REAPED_ID,
                        companyUserGroupId: "KREWE", createdByUserId: "TEST_CUSTOMER_USER",
                        statusEnumId: "AUT_STAT_RUNNING", createdDate: minutesAgo(minutes), startedDate: minutesAgo(minutes),
                        lastUpdatedDate: minutesAgo(minutes), lastUpdatedStamp: minutesAgo(minutes)]).create()
            }
        }
        Timestamp stamp = asSystem { ec.entity.find(RUN_RESULT).condition("reconciliationRunResultId", REAPED_ID).useCache(false).one() }
                .get("lastUpdatedStamp") as Timestamp
        assertTrue(stamp.time < System.currentTimeMillis() - (minutes - 5) * 60_000L, "lastUpdatedStamp was reset to now: ${stamp}")
    }

    private String statusOfCreated() {
        return asSystem { ec.entity.find(RUN_RESULT).condition("reconciliationRunResultId", REAPED_ID).useCache(false).one() }
                .get("statusEnumId") as String
    }

    private Map sweepService(Map params = [:]) {
        return ec.service.sync().name("reconciliation.ReconciliationAutomationServices.sweep#StuckReconciliationRuns")
                .parameters(params).disableAuthz().call()
    }

    @Test
    void aSilentAnyStageRowMakesAComparePastItStuck() {
        Timestamp now = new Timestamp(System.currentTimeMillis())
        Map run = [currentStage: "COMPARE", startedDate: minutesAgo(60), lastHeartbeatDate: minutesAgo(40)]
        assertEquals("healthy", RunHealth.of(run, now).health)
        saveRow(SILENT, "30")
        assertEquals("stuck", RunHealth.of(run, now).health)
    }

    @Test
    void theReaperRowShowsInTheReasonAndDrivesTheSweep() {
        Timestamp now = new Timestamp(System.currentTimeMillis())
        Map run = [currentStage: "COMPARE", startedDate: minutesAgo(100), lastHeartbeatDate: minutesAgo(80)]
        saveRow(REAPER, "60")
        saveRow(SILENT, "45")
        Map verdict = RunHealth.of(run, now)
        assertEquals("stuck", verdict.health)
        assertTrue((verdict.reason as String).endsWith("fails it at 60m"), verdict.reason as String)

        createRunning(70)
        Map result = sweepService()
        assertTrue((result.reapedRunResultCount as int) >= 1)
        assertEquals("AUT_STAT_FAILED", statusOfCreated())
    }

    @Test
    void aSilentThresholdPastTheReaperStillShowsStuckBeforeTheReaperTakesTheRun() {
        // raw rows (not through problem()): reaper 60, silent 90
        saveRow(REAPER, "60")
        saveRow(SILENT, "90")
        Timestamp now = new Timestamp(System.currentTimeMillis())
        [61, 75, 89].each { int silent ->
            Map verdict = RunHealth.of([currentStage: "COMPARE", startedDate: minutesAgo(silent + 5), lastHeartbeatDate: minutesAgo(silent)], now)
            assertEquals("stuck", verdict.health, "silent ${silent}m")
            assertTrue((verdict.reason as String).endsWith("fails it at 60m"), verdict.reason as String)
        }
        assertEquals("healthy", RunHealth.of([currentStage: "COMPARE", startedDate: minutesAgo(40), lastHeartbeatDate: minutesAgo(30)], now).health)
    }

    @Test
    void aStaleJobParameterNoLongerOverridesTheSetting() {
        saveRow(REAPER, "60")
        createRunning(70)
        // prod still carries ServiceJobParameter staleMinutes=120 (seed loads never delete); the service must ignore it.
        sweepService([staleMinutes: 120])
        assertEquals("AUT_STAT_FAILED", statusOfCreated())
    }

    @Test
    void aRunInsideTheThresholdIsLeftAlone() {
        createRunning(70)
        sweepService()
        assertEquals("AUT_STAT_RUNNING", statusOfCreated())
    }

    @Test
    void silentAnyStageMustStayBelowTheReaper() {
        // defaults: reaper 120, silent 90
        assertNotNull(DarpanSettings.problem(SILENT, "120"))
        assertNotNull(DarpanSettings.problem(SILENT, "121"))
        assertNull(DarpanSettings.problem(SILENT, "119"))
        saveRow(SILENT, "90")
        assertNotNull(DarpanSettings.problem(REAPER, "60"))
        assertNotNull(DarpanSettings.problem(REAPER, "90"))
        assertNull(DarpanSettings.problem(REAPER, "91"))
    }

    private <T> T asSystem(Closure<T> work) {
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            return work.call()
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
    }
}
