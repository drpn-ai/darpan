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
import static org.junit.jupiter.api.Assertions.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigrationSupervisorSmokeTests {
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "migration-supervisor-smoke")
        ReconciliationSmokeTestSupport.loadSeedData(ec,
                "component://darpan/data/MigrationRegistrySeedData.xml")
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void resetLedger() {
        // Every test here asserts against a known ledger state, and the class shares one database
        // and one Moqui boot. JUnit 5 guarantees no method order, so the ledger is cleared rather
        // than assumed — otherwise bootstrapRun...'s six SUCCESS rows would decide whether
        // aMigrationWithOnlyAFailedRow... passes, depending purely on execution order.
        // (The migrations themselves are idempotent, so re-running them per test is safe.)
        ec.entity.find("darpan.migration.DarpanMigrationRun").disableAuthz().deleteAll()
        ec.message.clearErrors()
    }

    @Test
    void bootstrapRunAppliesEverySixMigrationsAndRecordsThem() {
        // The ledger starts empty, so the first supervised run IS the backfill. Every registered
        // migration documents idempotence, so running them all is safe even where an operator
        // already ran them by hand.
        Map<String, Object> out = ec.service.sync()
                .name("migration.MigrationServices.run#PendingMigrations")
                .parameters([:])
                .disableAuthz()
                .call()

        assertTrue(out.ok as Boolean, "bootstrap run reported failure: ${out.results}")
        assertEquals(6, out.appliedCount)
        assertEquals(0, out.failedCount)
        assertEquals(0, out.blockedCount)

        long successRows = ec.entity.find("darpan.migration.DarpanMigrationRun")
                .condition("statusId", "SUCCESS").disableAuthz().count()
        assertEquals(6L, successRows)
    }

    @Test
    void aSecondRunAppliesNothingBecauseEveryMigrationHasASuccessRow() {
        ec.service.sync().name("migration.MigrationServices.run#PendingMigrations")
                .parameters([:]).disableAuthz().call()
        Map<String, Object> second = ec.service.sync()
                .name("migration.MigrationServices.run#PendingMigrations")
                .parameters([:]).disableAuthz().call()

        assertEquals(0, second.appliedCount)
        assertEquals(6, second.skippedCount)
        (second.results as List<Map<String, Object>>).each { result ->
            assertEquals("ALREADY_APPLIED", result.outcome)
        }
    }

    @Test
    void aMigrationWithOnlyAFailedRowIsRunAgain() {
        ec.service.sync().name("migration.MigrationServices.record#MigrationRun")
                .parameters([migrationId: "RETIRED_FIELDS",
                             statusId   : MigrationLedgerSupport.STATUS_FAILED])
                .disableAuthz().call()

        assertFalse(MigrationSupervisorSupport.hasSucceeded(ec, "RETIRED_FIELDS"),
                "a FAILED row must not satisfy the already-applied check")
    }

    @Test
    void aMigrationWithOnlyADryRunRowIsRunAgain() {
        ec.service.sync().name("migration.MigrationServices.record#MigrationRun")
                .parameters([migrationId: "ENDPOINT_ACCESS",
                             statusId   : MigrationLedgerSupport.STATUS_DRY_RUN])
                .disableAuthz().call()

        assertFalse(MigrationSupervisorSupport.hasSucceeded(ec, "ENDPOINT_ACCESS"),
                "a preview must never count as an application")
    }
}
