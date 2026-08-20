package darpan.migration

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
 * The surface an operator — or an agent acting for one — needs to finish a client's migration work
 * without SSH or a Gradle task: run one migration by name, park a broken one, and read back WHY
 * something failed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigrationAgentSurfaceSmokeTests {
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "migration-agent-smoke")
        ReconciliationSmokeTestSupport.loadSeedData(ec,
                "component://darpan/data/MigrationRegistrySeedData.xml")
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void resetState() {
        ec.entity.find("darpan.migration.DarpanMigrationRun").disableAuthz().deleteAll()
        // setEnabled tests mutate the registry, which is a cached configuration entity — reset it
        // so an unordered runner cannot let one test decide another's result.
        ec.entity.find("darpan.migration.DarpanMigration").disableAuthz().list().each { row ->
            if (row.getString("enabled") != "Y") { row.set("enabled", "Y"); row.update() }
        }
        ec.message.clearErrors()
    }

    // ---- run one migration by name ----

    @Test
    void runMigrationAppliesExactlyTheNamedMigrationAndNothingElse() {
        Map<String, Object> out = ec.service.sync()
                .name("migration.MigrationServices.run#Migration")
                .parameters([migrationId: "RETIRED_FIELDS"])
                .disableAuthz().call()

        assertEquals("APPLIED", out.outcome)
        assertNotNull(out.runId)
        assertEquals(1L, ec.entity.find("darpan.migration.DarpanMigrationRun")
                .disableAuthz().count(),
                "targeted run wrote a ledger row for something other than the named migration")
    }

    @Test
    void runMigrationOnAnUnknownIdFailsLoudlyRatherThanReportingSuccess() {
        // An agent looping over ids from a stale list must not read a typo as "done".
        Map<String, Object> out = ec.service.sync()
                .name("migration.MigrationServices.run#Migration")
                .parameters([migrationId: "NO_SUCH_MIGRATION"])
                .disableAuthz().call()

        assertEquals("NOT_FOUND", out.outcome)
        assertEquals(0L, ec.entity.find("darpan.migration.DarpanMigrationRun").disableAuthz().count())
        ec.message.clearErrors()
    }

    @Test
    void runMigrationSkipsAnAlreadyAppliedMigrationUnlessForced() {
        ec.service.sync().name("migration.MigrationServices.run#Migration")
                .parameters([migrationId: "RETIRED_FIELDS"]).disableAuthz().call()

        Map<String, Object> second = ec.service.sync()
                .name("migration.MigrationServices.run#Migration")
                .parameters([migrationId: "RETIRED_FIELDS"]).disableAuthz().call()
        assertEquals("ALREADY_APPLIED", second.outcome)

        Map<String, Object> forced = ec.service.sync()
                .name("migration.MigrationServices.run#Migration")
                .parameters([migrationId: "RETIRED_FIELDS", force: true]).disableAuthz().call()
        assertEquals("APPLIED", forced.outcome,
                "force is what makes re-running a client's backfill possible without editing the ledger by hand")
    }

    @Test
    void forceCannotOverridePrerequisites() {
        // force re-runs something already applied. It must NEVER let an agent run a migration ahead
        // of a prerequisite — that is the one ordering guarantee the whole design rests on.
        ReconciliationSmokeTestSupport.insertEntityDirect(ec, "darpan.migration.DarpanMigrationPrereq",
                [migrationId: "TENANT_NOTIF_SETTINGS", prereqMigrationId: "RETIRED_FIELDS"])
        try {
            Map<String, Object> out = ec.service.sync()
                    .name("migration.MigrationServices.run#Migration")
                    .parameters([migrationId: "TENANT_NOTIF_SETTINGS", force: true])
                    .disableAuthz().call()

            assertEquals("BLOCKED", out.outcome)
            assertTrue((out.detail as String).contains("RETIRED_FIELDS"),
                    "a blocked result must name the prerequisite an agent has to satisfy first")
        } finally {
            ec.entity.find("darpan.migration.DarpanMigrationPrereq")
                    .condition("migrationId", "TENANT_NOTIF_SETTINGS")
                    .disableAuthz().deleteAll()
        }
    }

    // ---- park a broken migration ----

    @Test
    void aDisabledMigrationIsSkippedByRunPendingAndRefusedByTargetedRun() {
        ec.service.sync().name("migration.MigrationServices.set#MigrationEnabled")
                .parameters([migrationId: "RETIRED_FIELDS", enabled: false])
                .disableAuthz().call()

        Map<String, Object> sweep = ec.service.sync()
                .name("migration.MigrationServices.run#PendingMigrations")
                .parameters([:]).disableAuthz().call()
        assertEquals(5, sweep.appliedCount, "a parked migration must not run in the sweep")

        Map<String, Object> targeted = ec.service.sync()
                .name("migration.MigrationServices.run#Migration")
                .parameters([migrationId: "RETIRED_FIELDS"]).disableAuthz().call()
        assertEquals("DISABLED", targeted.outcome,
                "naming a parked migration explicitly must say it is parked, not silently run it")
    }

    @Test
    void aDisabledMigrationReportsAsDisabledInStatusRatherThanPending() {
        // THE DEFECT THIS TEST EXISTS FOR: statusList does not filter on enabled, so a parked
        // migration used to read PENDING while run#PendingMigrations skipped it — an agent would
        // run the sweep, see nothing happen, and have no way to tell why.
        ec.service.sync().name("migration.MigrationServices.set#MigrationEnabled")
                .parameters([migrationId: "ENDPOINT_ACCESS", enabled: false])
                .disableAuthz().call()

        Map<String, Object> row = (ec.service.sync()
                .name("migration.MigrationServices.list#MigrationStatus")
                .parameters([:]).disableAuthz().call()
                .migrations as List<Map<String, Object>>)
                .find { it.migrationId == "ENDPOINT_ACCESS" }

        assertEquals("DISABLED", row.status)
        assertFalse(row.enabled as Boolean)
    }

    // ---- read back why something failed ----

    @Test
    void statusCarriesTheFailureDetailSoAnAgentCanDiagnoseWithoutDatabaseAccess() {
        ec.service.sync().name("migration.MigrationServices.record#MigrationRun")
                .parameters([migrationId  : "AUTOMATION_FILTERS",
                             statusId     : MigrationLedgerSupport.STATUS_FAILED,
                             messageDetail: "connection refused reaching the OMS host"])
                .disableAuthz().call()

        Map<String, Object> row = (ec.service.sync()
                .name("migration.MigrationServices.list#MigrationStatus")
                .parameters([:]).disableAuthz().call()
                .migrations as List<Map<String, Object>>)
                .find { it.migrationId == "AUTOMATION_FILTERS" }

        assertEquals("FAILED", row.lastStatusId)
        assertEquals("connection refused reaching the OMS host", row.lastMessageDetail,
                "the supervisor sanitizes and stores failure text; nothing read it back, making the " +
                        "ledger write-only from the API's point of view")
        assertNotNull(row.lastRunId)
    }

    @Test
    void historyReturnsEveryAttemptNewestFirstWithItsOwnDetail() {
        ec.service.sync().name("migration.MigrationServices.record#MigrationRun")
                .parameters([migrationId: "UNDECRYPTABLE_HOOKS",
                             statusId   : MigrationLedgerSupport.STATUS_FAILED,
                             messageDetail: "first attempt"])
                .disableAuthz().call()
        ec.service.sync().name("migration.MigrationServices.record#MigrationRun")
                .parameters([migrationId : "UNDECRYPTABLE_HOOKS",
                             statusId    : MigrationLedgerSupport.STATUS_SUCCESS,
                             rowsAffected: 4,
                             messageDetail: "second attempt"])
                .disableAuthz().call()

        Map<String, Object> out = ec.service.sync()
                .name("migration.MigrationServices.get#MigrationHistory")
                .parameters([migrationId: "UNDECRYPTABLE_HOOKS"])
                .disableAuthz().call()

        List<Map<String, Object>> attempts = out.attempts as List<Map<String, Object>>
        assertEquals(2, attempts.size())
        assertEquals("SUCCESS", attempts[0].statusId)
        assertEquals("second attempt", attempts[0].messageDetail)
        assertEquals("FAILED", attempts[1].statusId)
        assertEquals("first attempt", attempts[1].messageDetail,
                "the failed attempt must stay readable — it is the whole reason runId is in the key")
    }

    @Test
    void historyForAMigrationThatNeverRanIsEmptyRatherThanAnError() {
        Map<String, Object> out = ec.service.sync()
                .name("migration.MigrationServices.get#MigrationHistory")
                .parameters([migrationId: "RULE_TENANT_STAMPS"])
                .disableAuthz().call()

        assertEquals([], out.attempts)
        assertTrue(out.ok as Boolean)
    }
}
