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
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigrationStatusSmokeTests {
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "migration-status-smoke")
        ReconciliationSmokeTestSupport.loadSeedData(ec,
                "component://darpan/data/MigrationRegistrySeedData.xml")
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void resetLedger() {
        // everyRegistryRowIsListed... asserts attemptCount 0 for every row while
        // anAppliedMigrationReports... writes two rows for AUTOMATION_FILTERS. JUnit 5 fixes no
        // order, so the ledger is cleared rather than assumed empty.
        ec.entity.find("darpan.migration.DarpanMigrationRun").disableAuthz().deleteAll()
        ec.message.clearErrors()
    }

    @Test
    void everyRegistryRowIsListedInSequenceOrderAndStartsPending() {
        Map<String, Object> out = ec.service.sync()
                .name("migration.MigrationServices.list#MigrationStatus")
                .parameters([:]).disableAuthz().call()

        List<Map<String, Object>> migrations = out.migrations as List<Map<String, Object>>
        assertEquals(6, migrations.size())
        assertEquals(["TENANT_NOTIF_SETTINGS", "UNDECRYPTABLE_HOOKS", "AUTOMATION_FILTERS",
                      "RULE_TENANT_STAMPS", "ENDPOINT_ACCESS", "RETIRED_FIELDS"],
                migrations.collect { it.migrationId })
        migrations.each { row ->
            assertEquals("PENDING", row.status)
            assertNull(row.lastRunDate)
            assertEquals(0, row.attemptCount)
        }
    }

    @Test
    void anAppliedMigrationReportsItsLastRunAndAttemptCount() {
        ec.service.sync().name("migration.MigrationServices.record#MigrationRun")
                .parameters([migrationId: "AUTOMATION_FILTERS",
                             statusId   : MigrationLedgerSupport.STATUS_FAILED])
                .disableAuthz().call()
        ec.service.sync().name("migration.MigrationServices.record#MigrationRun")
                .parameters([migrationId : "AUTOMATION_FILTERS",
                             statusId    : MigrationLedgerSupport.STATUS_SUCCESS,
                             rowsAffected: 7])
                .disableAuthz().call()

        Map<String, Object> row = (ec.service.sync()
                .name("migration.MigrationServices.list#MigrationStatus")
                .parameters([:]).disableAuthz().call()
                .migrations as List<Map<String, Object>>)
                .find { it.migrationId == "AUTOMATION_FILTERS" }

        assertEquals("APPLIED", row.status)
        assertEquals("SUCCESS", row.lastStatusId)
        assertEquals(7, row.rowsAffected)
        assertEquals(2, row.attemptCount, "the failed first attempt stays visible in the history")
        assertNotNull(row.lastRunDate)
    }
}
