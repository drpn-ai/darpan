package darpan.reconciliation.notification

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunNotificationStreakTests {
    private ExecutionContext ec
    private int rowCounter = 0

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "run-notification-streak")
        // FK prerequisites for a bare ReconciliationRunResult row: statusEnumId -> moqui.basic.Enumeration
        // (the fresh test DB carries no seed data), and companyUserGroupId -> moqui.security.UserGroup.
        // Same convention as TenantChatSpaceEntitySmokeTests.
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
        ["TENANT_A", "TENANT_B"].each { String tenantId ->
            ec.entity.makeValue("moqui.security.UserGroup")
                    .setAll([userGroupId: tenantId, description: "Streak smoke-test tenant"])
                    .createOrUpdate()
        }
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    /** Creates one run-result row. minutesAgo orders the series; higher means older. */
    private String makeRow(String savedRunId, String tenantId, int differenceCount,
                           String statusEnumId, int minutesAgo) {
        String resultId = "STREAK_TEST_${savedRunId}_${rowCounter++}".toString()
        ec.entity.makeValue("darpan.reconciliation.ReconciliationRunResult")
                .setAll([
                        reconciliationRunResultId: resultId,
                        savedRunId               : savedRunId,
                        companyUserGroupId       : tenantId,
                        statusEnumId             : statusEnumId,
                        differenceCount          : differenceCount,
                        completedDate            : new Timestamp(
                                System.currentTimeMillis() - (minutesAgo * 60_000L)),
                ])
                .createOrUpdate()
        return resultId
    }

    @Test
    void countsConsecutiveCleanPriorRuns() {
        String savedRunId = "SR_CLEAN_SERIES"
        makeRow(savedRunId, "TENANT_A", 0, "AUT_STAT_SUCCESS", 30)
        makeRow(savedRunId, "TENANT_A", 0, "AUT_STAT_SUCCESS", 20)
        makeRow(savedRunId, "TENANT_A", 0, "AUT_STAT_SUCCESS", 10)
        String current = makeRow(savedRunId, "TENANT_A", 0, "AUT_STAT_SUCCESS", 1)

        assertEquals(3, RunNotificationStreak.countConsecutiveCleanRuns(
                ec, savedRunId, "TENANT_A", current))
    }

    @Test
    void aFailedRunBreaksTheStreak() {
        String savedRunId = "SR_FAILED_BREAK"
        makeRow(savedRunId, "TENANT_A", 0, "AUT_STAT_SUCCESS", 40)
        makeRow(savedRunId, "TENANT_A", 0, "AUT_STAT_FAILED", 30)
        makeRow(savedRunId, "TENANT_A", 0, "AUT_STAT_SUCCESS", 10)
        String current = makeRow(savedRunId, "TENANT_A", 0, "AUT_STAT_SUCCESS", 1)

        // Only the single clean run above the failure counts.
        assertEquals(1, RunNotificationStreak.countConsecutiveCleanRuns(
                ec, savedRunId, "TENANT_A", current))
    }

    @Test
    void aRunWithDifferencesBreaksTheStreak() {
        String savedRunId = "SR_DIFF_BREAK"
        makeRow(savedRunId, "TENANT_A", 0, "AUT_STAT_SUCCESS", 40)
        makeRow(savedRunId, "TENANT_A", 7, "AUT_STAT_SUCCESS", 30)
        makeRow(savedRunId, "TENANT_A", 0, "AUT_STAT_SUCCESS", 10)
        String current = makeRow(savedRunId, "TENANT_A", 0, "AUT_STAT_SUCCESS", 1)

        assertEquals(1, RunNotificationStreak.countConsecutiveCleanRuns(
                ec, savedRunId, "TENANT_A", current))
    }

    @Test
    void theCurrentRunIsNeverCounted() {
        String savedRunId = "SR_ONLY_CURRENT"
        String current = makeRow(savedRunId, "TENANT_A", 0, "AUT_STAT_SUCCESS", 1)

        assertEquals(0, RunNotificationStreak.countConsecutiveCleanRuns(
                ec, savedRunId, "TENANT_A", current))
    }

    @Test
    void anotherTenantsCleanRunsAreNeverCounted() {
        String savedRunId = "SR_SHARED_ID"
        makeRow(savedRunId, "TENANT_B", 0, "AUT_STAT_SUCCESS", 30)
        makeRow(savedRunId, "TENANT_B", 0, "AUT_STAT_SUCCESS", 20)
        String current = makeRow(savedRunId, "TENANT_A", 0, "AUT_STAT_SUCCESS", 1)

        assertEquals(0, RunNotificationStreak.countConsecutiveCleanRuns(
                ec, savedRunId, "TENANT_A", current))
    }

    @Test
    void anAdHocRunWithNoSavedRunIdReturnsZero() {
        assertEquals(0, RunNotificationStreak.countConsecutiveCleanRuns(
                ec, null, "TENANT_A", "SOME_RESULT"))
    }

    @Test
    void theStreakStopsAtTheLookbackLimit() {
        String savedRunId = "SR_LONG_SERIES"
        (1..(RunNotificationVoice.LOOKBACK_LIMIT + 5)).each { int index ->
            makeRow(savedRunId, "TENANT_A", 0, "AUT_STAT_SUCCESS", 100 - index)
        }
        String current = makeRow(savedRunId, "TENANT_A", 0, "AUT_STAT_SUCCESS", 1)

        assertEquals(RunNotificationVoice.LOOKBACK_LIMIT,
                RunNotificationStreak.countConsecutiveCleanRuns(ec, savedRunId, "TENANT_A", current))
    }
}
