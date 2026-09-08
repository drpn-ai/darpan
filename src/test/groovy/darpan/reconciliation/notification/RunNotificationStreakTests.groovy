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

    /**
     * Registers an automation so ReconciliationAutomationExecution rows can point at it (RECAUTEX_AUT
     * is a real FK). Two automations sharing one savedRunId is the ordinary case this suite exists for:
     * savedRunId is the operator's chosen saved run and equals ruleSetId for RuleSet-backed runs, so a
     * daily and a weekly schedule over the same ruleset carry the same value.
     */
    private void makeAutomation(String automationId, String savedRunId, String tenantId) {
        ec.entity.makeValue("darpan.reconciliation.ReconciliationAutomation")
                .setAll([
                        automationId      : automationId,
                        automationName    : automationId,
                        companyUserGroupId: tenantId,
                        inputModeEnumId   : "AUT_IN_API_RANGE",
                        savedRunId        : savedRunId,
                ])
                .createOrUpdate()
    }

    /** A run-result row plus the execution row that binds it to {@code automationId}. */
    private String makeAutomationRun(String automationId, String savedRunId, String tenantId,
                                     int differenceCount, String statusEnumId, int minutesAgo) {
        String resultId = makeRow(savedRunId, tenantId, differenceCount, statusEnumId, minutesAgo)
        ec.entity.makeValue("darpan.reconciliation.ReconciliationAutomationExecution")
                .setAll([
                        automationExecutionId    : "STREAK_EXEC_${rowCounter++}".toString(),
                        automationId             : automationId,
                        companyUserGroupId       : tenantId,
                        statusEnumId             : statusEnumId,
                        reconciliationRunResultId: resultId,
                        // createdDate is what the lookback orders executions by, so the test data has to
                        // carry it rather than leaving every row null and relying on insertion order.
                        createdDate              : new Timestamp(
                                System.currentTimeMillis() - (minutesAgo * 60_000L)),
                        completedDate            : new Timestamp(
                                System.currentTimeMillis() - (minutesAgo * 60_000L)),
                ])
                .createOrUpdate()
        return resultId
    }

    @Test
    void anotherAutomationsRunsOnTheSameSavedRunAreNeverCounted() {
        // DAR-BE-043: the lookback was scoped to savedRunId + tenant and nothing else, so a weekly
        // automation's notification counted the daily automation's runs over the same saved run and
        // announced a streak of 7 where this schedule had run twice.
        String savedRunId = "SR_TWO_AUTOMATIONS"
        makeAutomation("AUT_STREAK_DAILY", savedRunId, "TENANT_A")
        makeAutomation("AUT_STREAK_WEEKLY", savedRunId, "TENANT_A")

        makeAutomationRun("AUT_STREAK_WEEKLY", savedRunId, "TENANT_A", 0, "AUT_STAT_SUCCESS", 60)
        makeAutomationRun("AUT_STREAK_WEEKLY", savedRunId, "TENANT_A", 0, "AUT_STAT_SUCCESS", 50)
        (1..5).each { int index ->
            makeAutomationRun("AUT_STREAK_DAILY", savedRunId, "TENANT_A", 0, "AUT_STAT_SUCCESS", 40 - index)
        }
        String current = makeAutomationRun("AUT_STREAK_WEEKLY", savedRunId, "TENANT_A", 0, "AUT_STAT_SUCCESS", 1)

        assertEquals(2, RunNotificationStreak.countConsecutiveCleanRuns(
                ec, savedRunId, "TENANT_A", current))
    }

    @Test
    void anotherAutomationsFailureDoesNotBreakThisAutomationsStreak() {
        // The mirror of the count bug and the more damaging half: a shared-savedRunId series also let a
        // DIFFERENT schedule's bad run silently reset this one's streak.
        String savedRunId = "SR_SHARED_WITH_A_FAILURE"
        makeAutomation("AUT_FAIL_DAILY", savedRunId, "TENANT_A")
        makeAutomation("AUT_FAIL_WEEKLY", savedRunId, "TENANT_A")

        makeAutomationRun("AUT_FAIL_WEEKLY", savedRunId, "TENANT_A", 0, "AUT_STAT_SUCCESS", 60)
        makeAutomationRun("AUT_FAIL_WEEKLY", savedRunId, "TENANT_A", 0, "AUT_STAT_SUCCESS", 50)
        makeAutomationRun("AUT_FAIL_DAILY", savedRunId, "TENANT_A", 0, "AUT_STAT_FAILED", 40)
        String current = makeAutomationRun("AUT_FAIL_WEEKLY", savedRunId, "TENANT_A", 0, "AUT_STAT_SUCCESS", 1)

        assertEquals(2, RunNotificationStreak.countConsecutiveCleanRuns(
                ec, savedRunId, "TENANT_A", current))
    }

    @Test
    void thisAutomationsOwnBadRunStillBreaksTheStreak() {
        // Guard for the guard above: narrowing the scope to one automation must not make the streak
        // unbreakable — a bad run of THIS schedule still stops the count.
        String savedRunId = "SR_OWN_FAILURE"
        makeAutomation("AUT_OWN_WEEKLY", savedRunId, "TENANT_A")

        makeAutomationRun("AUT_OWN_WEEKLY", savedRunId, "TENANT_A", 0, "AUT_STAT_SUCCESS", 60)
        makeAutomationRun("AUT_OWN_WEEKLY", savedRunId, "TENANT_A", 4, "AUT_STAT_SUCCESS", 50)
        makeAutomationRun("AUT_OWN_WEEKLY", savedRunId, "TENANT_A", 0, "AUT_STAT_SUCCESS", 40)
        String current = makeAutomationRun("AUT_OWN_WEEKLY", savedRunId, "TENANT_A", 0, "AUT_STAT_SUCCESS", 1)

        assertEquals(1, RunNotificationStreak.countConsecutiveCleanRuns(
                ec, savedRunId, "TENANT_A", current))
    }

    @Test
    void theAutomationScopedLookbackReadsNewestRunsFirst() {
        // Ordering is load-bearing and nothing else exercises it on the automation-scoped path: the
        // lookback caps at LOOKBACK_LIMIT + 1 rows, so reading the series oldest-first would truncate
        // away the recent runs the streak is actually about and answer from ancient history instead.
        // Here that is the difference between 2 and the full lookback limit.
        String savedRunId = "SR_ORDERING"
        makeAutomation("AUT_ORDERING", savedRunId, "TENANT_A")

        (1..(RunNotificationVoice.LOOKBACK_LIMIT + 4)).each { int index ->
            makeAutomationRun("AUT_ORDERING", savedRunId, "TENANT_A", 0, "AUT_STAT_SUCCESS", 100 - index)
        }
        makeAutomationRun("AUT_ORDERING", savedRunId, "TENANT_A", 9, "AUT_STAT_SUCCESS", 5)
        makeAutomationRun("AUT_ORDERING", savedRunId, "TENANT_A", 0, "AUT_STAT_SUCCESS", 4)
        makeAutomationRun("AUT_ORDERING", savedRunId, "TENANT_A", 0, "AUT_STAT_SUCCESS", 3)
        String current = makeAutomationRun("AUT_ORDERING", savedRunId, "TENANT_A", 0, "AUT_STAT_SUCCESS", 1)

        // Newest first: two clean runs, then the run with differences stops the count.
        assertEquals(2, RunNotificationStreak.countConsecutiveCleanRuns(
                ec, savedRunId, "TENANT_A", current))
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
