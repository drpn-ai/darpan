package darpan.migration

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigrationLedgerSmokeTests {
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "migration-ledger-smoke")
        ReconciliationSmokeTestSupport.loadSeedData(ec,
                "component://darpan/data/MigrationRegistrySeedData.xml")
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @Test
    void recordWritesOneLedgerRowAndReturnsItsRunId() {
        Map<String, Object> out = ec.service.sync()
                .name("migration.MigrationServices.record#MigrationRun")
                .parameters([migrationId : "RETIRED_FIELDS",
                             statusId    : MigrationLedgerSupport.STATUS_SUCCESS,
                             rowsAffected: 3,
                             messageDetail: "Deleted 3 retired field row(s)."])
                .disableAuthz()
                .call()

        String runId = out.runId as String
        assertNotNull(runId)

        def row = ec.entity.find("darpan.migration.DarpanMigrationRun")
                .condition("migrationId", "RETIRED_FIELDS")
                .condition("runId", runId)
                .disableAuthz()
                .one()
        assertEquals("SUCCESS", row.getString("statusId"))
        assertEquals(3, row.get("rowsAffected"))
        assertNotNull(row.get("startedDate"))
        assertNotNull(row.get("completedDate"))
    }

    @Test
    void aSecondAttemptAddsARowRatherThanOverwritingTheFirst() {
        ec.service.sync().name("migration.MigrationServices.record#MigrationRun")
                .parameters([migrationId: "ENDPOINT_ACCESS",
                             statusId   : MigrationLedgerSupport.STATUS_FAILED,
                             messageDetail: "first attempt blew up"])
                .disableAuthz().call()
        ec.service.sync().name("migration.MigrationServices.record#MigrationRun")
                .parameters([migrationId: "ENDPOINT_ACCESS",
                             statusId   : MigrationLedgerSupport.STATUS_SUCCESS,
                             rowsAffected: 12])
                .disableAuthz().call()

        long total = ec.entity.find("darpan.migration.DarpanMigrationRun")
                .condition("migrationId", "ENDPOINT_ACCESS").disableAuthz().count()
        assertEquals(2L, total, "a retry must not erase the record of the failure it is retrying")
    }

    @Test
    void ledgerRowSurvivesTheRollbackOfTheTransactionItWasWrittenInside() {
        // THE GUARANTEE THIS DESIGN RESTS ON.
        //
        // A migration that fails leaves its transaction rolled back. If the ledger write joined
        // that transaction, the row recording the failure would go with it, and the ledger would
        // silently contain successes only — a migration failing on every attempt would look
        // identical to one never attempted.
        //
        // transaction="force-new" on record#MigrationRun suspends the ambient transaction and
        // commits the row independently. This wraps the write in a transaction that is then rolled
        // back outright: if force-new were absent or ineffective, the row would not survive.
        boolean began = ec.transaction.begin(60)
        String runId
        try {
            runId = ec.service.sync()
                    .name("migration.MigrationServices.record#MigrationRun")
                    .parameters([migrationId  : "AUTOMATION_FILTERS",
                                 statusId     : MigrationLedgerSupport.STATUS_FAILED,
                                 messageDetail: "required parameters missing"])
                    .disableAuthz()
                    .call()
                    ?.runId as String
        } finally {
            ec.transaction.rollback(began, "deliberate rollback: proving the ledger row is not enlisted", null)
        }

        assertNotNull(runId, "record#MigrationRun returned no runId, so nothing was written")

        def row = ec.entity.find("darpan.migration.DarpanMigrationRun")
                .condition("migrationId", "AUTOMATION_FILTERS")
                .condition("runId", runId)
                .disableAuthz()
                .one()
        assertNotNull(row, "the FAILED ledger row was rolled back with the transaction around it")
        assertEquals("FAILED", row.getString("statusId"))
    }

    @Test
    void moquiRefusesToRunTheRecorderWhileErrorsAreStillOnTheMessageFacade() {
        // Pins the OTHER half of the failure-recording contract, and the reason
        // MigrationSupervisorSupport.runOne calls clearErrors() before recording.
        //
        // ServiceCallSyncImpl.java:135 refuses to run ANY service when ec.message already has
        // errors ("Found error(s) before service ..., so not running service"). So a supervisor
        // that recorded a failure without clearing first would silently record nothing at all —
        // and force-new would never even be reached. This asserts that skip is real, so nobody
        // "simplifies" the clearErrors() call away later.
        //
        // prepare#RuleSetCompareScope is used purely as a service that reliably fails: it declares
        // required in-parameters and is called here with none.
        ec.service.sync()
                .name("reconciliation.ReconciliationCoreServices.prepare#RuleSetCompareScope")
                .parameters([:])
                .disableAuthz()
                .call()
        assertTrue(ec.message.hasError(), "the fixture service was expected to fail and did not")

        Map<String, Object> skipped = ec.service.sync()
                .name("migration.MigrationServices.record#MigrationRun")
                .parameters([migrationId: "UNDECRYPTABLE_HOOKS",
                             statusId   : MigrationLedgerSupport.STATUS_FAILED])
                .disableAuthz()
                .call()

        assertNull(skipped?.runId,
                "Moqui ran the recorder despite pre-existing errors; clearErrors() may no longer be required")
        assertEquals(0L, ec.entity.find("darpan.migration.DarpanMigrationRun")
                .condition("migrationId", "UNDECRYPTABLE_HOOKS").disableAuthz().count())

        ec.message.clearErrors()

        // ...and after clearing, the very same call writes its row. This is exactly what the
        // supervisor does.
        String runId = ec.service.sync()
                .name("migration.MigrationServices.record#MigrationRun")
                .parameters([migrationId: "UNDECRYPTABLE_HOOKS",
                             statusId   : MigrationLedgerSupport.STATUS_FAILED])
                .disableAuthz()
                .call()
                ?.runId as String
        assertNotNull(runId)
        assertEquals(1L, ec.entity.find("darpan.migration.DarpanMigrationRun")
                .condition("migrationId", "UNDECRYPTABLE_HOOKS").disableAuthz().count())
    }
}
