package darpan.config

import darpan.reconciliation.support.SharedMoqui
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.AutomationScheduler
import darpan.reconciliation.automation.AutomationRetryPolicy
import darpan.reconciliation.automation.AutomationWindows
import darpan.reconciliation.automation.RunAdmission
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp
import java.time.Instant

import static org.junit.jupiter.api.Assertions.*
import static darpan.reconciliation.support.TestHelpers.asSystem

/**
 * DAR-BE-304 Task 2. A DarpanSetting row changes what run admission, the retry backoff and the window ceiling do, with no
 * restart: the code reads the settings at each decision.
 */
@SharedMoqui
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunSettingsSmokeTests {
    private static final String ENTITY = "darpan.config.DarpanSetting"
    private static final String CAP = "darpan.reconciliation.automation.maxConcurrentExecutions"
    private static final String BACKOFF_CAP = "darpan.reconciliation.automation.retryBackoffCapMinutes"

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "run-settings")
        DarpanSettings.resetForTest()
    }

    @AfterEach
    void clean() {
        asSystem(ec) { ec.entity.find(ENTITY).condition("settingKey", "like", "darpan.reconciliation.automation.%").deleteAll() }
        DarpanSettings.resetForTest()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    private void saveRow(String key, String value) {
        ec.transaction.runRequireNew(60, "test save") {
            asSystem(ec) {
                ec.entity.makeValue(ENTITY).setAll([settingKey: key, settingValue: value, lastChangedDate: new Timestamp(System.currentTimeMillis())]).createOrUpdate()
                DarpanSettings.expireAfterCommit(ec)
            }
        }
    }

    @Test
    void aRowChangesTheCapAtOnce() {
        assertEquals(8, AutomationScheduler.maxConcurrentExecutions())
        saveRow(CAP, "3")
        assertEquals(3, AutomationScheduler.maxConcurrentExecutions())
        assertEquals(3, RunAdmission.SHARED.snapshot().cap)
        assertEquals(3, AutomationScheduler.remainingSubmissionBudget(0))
    }

    @Test
    void theBackoffCapRowIsHonoured() {
        // base 5 min, doubling: attempt 10 would be 2560 min, so it sits at the cap.
        assertEquals(60L * 60_000L, AutomationRetryPolicy.retryBackoffMillis(10))
        saveRow(BACKOFF_CAP, "30")
        assertEquals(30L * 60_000L, AutomationRetryPolicy.retryBackoffMillis(10))
    }

    @Test
    void theWindowCeilingStaysAtTheSettingAndRefusesBeyondIt() {
        Map automation = [relativeWindowTypeEnumId: AutomationWindows.WINDOW_CUSTOM, windowTimeZone: "UTC", maxWindowDays: 28]
        assertNotNull(AutomationWindows.resolveWindows(automation, [
                windowStartDate: ts("2025-01-01T00:00:00Z"), windowEndDate: ts("2026-02-05T00:00:00Z")]))
        IllegalArgumentException refused = assertThrows(IllegalArgumentException) {
            AutomationWindows.resolveWindows(automation, [
                    windowStartDate: ts("2010-01-01T00:00:00Z"), windowEndDate: ts("2021-01-01T00:00:00Z")])
        }
        assertTrue(refused.message.contains("maximum of 3660"), refused.message)
    }

    @Test
    void aWindowCeilingRowTakesEffectWithNoRestart() {
        Map automation = [relativeWindowTypeEnumId: AutomationWindows.WINDOW_CUSTOM, windowTimeZone: "UTC", maxWindowDays: 28]
        saveRow("darpan.reconciliation.automation.maxWindowSpanDays", "400")
        assertNotNull(AutomationWindows.resolveWindows(automation, [
                windowStartDate: ts("2025-01-01T00:00:00Z"), windowEndDate: ts("2026-01-26T00:00:00Z")]))   // 390 days
        IllegalArgumentException refused = assertThrows(IllegalArgumentException) {
            AutomationWindows.resolveWindows(automation, [
                    windowStartDate: ts("2025-01-01T00:00:00Z"), windowEndDate: ts("2026-02-07T00:00:00Z")])   // 402 days
        }
        assertTrue(refused.message.contains("maximum of 400"), refused.message)
    }

    private static Timestamp ts(String iso) { return Timestamp.from(Instant.parse(iso)) }
}
