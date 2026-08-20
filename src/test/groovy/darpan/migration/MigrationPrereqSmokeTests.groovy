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
import static org.junit.jupiter.api.Assertions.assertIterableEquals
import static org.junit.jupiter.api.Assertions.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigrationPrereqSmokeTests {
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "migration-prereq-smoke")
        ReconciliationSmokeTestSupport.loadSeedData(ec,
                "component://darpan/data/MigrationRegistrySeedData.xml")

        // A synthetic dependent whose declared order DISAGREES with its declared dependency:
        // TENANT_NOTIF_SETTINGS is sequenceNum 10 but may not run until RETIRED_FIELDS
        // (sequenceNum 60) has succeeded. That disagreement is the only way BLOCKED is ever
        // observable, and proving it is the entire reason prereq rows are verified separately
        // rather than folded into the sort — see the note on aForwardPrerequisite... below.
        // Uses real registered migrations so no test-only service is needed.
        ReconciliationSmokeTestSupport.insertEntityDirect(ec, "darpan.migration.DarpanMigrationPrereq",
                [migrationId: "TENANT_NOTIF_SETTINGS", prereqMigrationId: "RETIRED_FIELDS"])
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void resetLedger() {
        // Several tests below assert against RETIRED_FIELDS's ledger state, and one of them
        // deliberately gives it a SUCCESS row. Sharing one database across an unordered class would
        // otherwise let that test decide whether the others pass.
        ec.entity.find("darpan.migration.DarpanMigrationRun").disableAuthz().deleteAll()
        ec.message.clearErrors()
    }

    @Test
    void unmetPrereqsListsPrerequisitesWithoutASuccessRow() {
        assertIterableEquals(["RETIRED_FIELDS"],
                MigrationSupervisorSupport.unmetPrereqs(ec, "TENANT_NOTIF_SETTINGS"))
    }

    @Test
    void aMetPrerequisiteDisappearsFromTheUnmetList() {
        ec.service.sync().name("migration.MigrationServices.record#MigrationRun")
                .parameters([migrationId: "RETIRED_FIELDS",
                             statusId   : MigrationLedgerSupport.STATUS_SUCCESS])
                .disableAuthz().call()

        assertTrue(MigrationSupervisorSupport.unmetPrereqs(ec, "TENANT_NOTIF_SETTINGS").isEmpty())
    }

    @Test
    void aBlockedMigrationDoesNotRunAndDoesNotStopIndependentOnes() {
        // TENANT_NOTIF_SETTINGS (sequenceNum 10) depends on RETIRED_FIELDS (sequenceNum 60), so
        // the walk reaches it before its prerequisite could possibly have run. Every other
        // migration is independent and must still be applied — one blocked migration must not
        // strand an environment on everything after it in sequence.
        Map<String, Object> out = ec.service.sync()
                .name("migration.MigrationServices.run#PendingMigrations")
                .parameters([:]).disableAuthz().call()

        Map<String, Object> blocked = (out.results as List<Map<String, Object>>)
                .find { it.migrationId == "TENANT_NOTIF_SETTINGS" }
        assertEquals("BLOCKED", blocked.outcome)
        assertEquals(1, out.blockedCount)
        assertEquals(5, out.appliedCount)

        long ledgerRows = ec.entity.find("darpan.migration.DarpanMigrationRun")
                .condition("migrationId", "TENANT_NOTIF_SETTINGS")
                .condition("statusId", "BLOCKED")
                .disableAuthz().count()
        assertEquals(1L, ledgerRows, "a block is an outcome worth recording, not a silent skip")
    }

    @Test
    void aForwardPrerequisiteIsSatisfiedWithinTheSameRunAndDoesNotBlock() {
        // The counterpart to the test above, and the reason BLOCKED needs a backwards dependency to
        // observe at all. RETIRED_FIELDS (sequenceNum 60) declaring ENDPOINT_ACCESS (sequenceNum 50)
        // as a prerequisite is a dependency that AGREES with the declared order: by the time the
        // walk reaches RETIRED_FIELDS, ENDPOINT_ACCESS has already succeeded in this same run, so
        // it applies rather than blocking.
        //
        // Asserting this pins the intended division of labour: sequenceNum decides ORDER, prereq
        // rows decide PERMISSION. A future refactor that "fixed" blocking by topologically sorting
        // on prereq rows would still pass the BLOCKED test above while silently reordering the
        // registry — this test is what would catch that.
        ReconciliationSmokeTestSupport.insertEntityDirect(ec, "darpan.migration.DarpanMigrationPrereq",
                [migrationId: "RETIRED_FIELDS", prereqMigrationId: "ENDPOINT_ACCESS"])
        try {
            Map<String, Object> out = ec.service.sync()
                    .name("migration.MigrationServices.run#PendingMigrations")
                    .parameters([:]).disableAuthz().call()

            Map<String, Object> dependent = (out.results as List<Map<String, Object>>)
                    .find { it.migrationId == "RETIRED_FIELDS" }
            assertEquals("APPLIED", dependent.outcome,
                    "a prerequisite earlier in sequence is met by the time the walk arrives")
        } finally {
            ec.entity.find("darpan.migration.DarpanMigrationPrereq")
                    .condition("migrationId", "RETIRED_FIELDS")
                    .condition("prereqMigrationId", "ENDPOINT_ACCESS")
                    .disableAuthz().deleteAll()
        }
    }
}
