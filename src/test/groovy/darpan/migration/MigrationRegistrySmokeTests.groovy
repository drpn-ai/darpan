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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigrationRegistrySmokeTests {
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "migration-registry-smoke")
        ReconciliationSmokeTestSupport.loadSeedData(ec,
                "component://darpan/data/MigrationRegistrySeedData.xml")
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @Test
    void registrySeedLoadsEverySixExistingMigration() {
        List<String> ids = ec.entity.find("darpan.migration.DarpanMigration")
                .orderBy("sequenceNum")
                .disableAuthz()
                .list()
                .collect { it.getString("migrationId") }

        assertEquals(["TENANT_NOTIF_SETTINGS", "UNDECRYPTABLE_HOOKS", "AUTOMATION_FILTERS",
                      "RULE_TENANT_STAMPS", "ENDPOINT_ACCESS", "RETIRED_FIELDS"], ids)
    }

    @Test
    void everyRegisteredServiceNameResolvesToARealService() {
        // A registry row naming a service that does not exist would fail mid-run, after earlier
        // migrations had already written. This asserts the seed is honest at load time.
        ec.entity.find("darpan.migration.DarpanMigration").disableAuthz().list().each { row ->
            String serviceName = row.getString("serviceName")
            assertNotNull(ec.service.getServiceDefinition(serviceName),
                    "registry row ${row.getString('migrationId')} names an unresolvable service: ${serviceName}")
        }
    }

    @Test
    void recurringWatchdogIsNotRegistered() {
        // sweep#StuckReconciliationRuns is a recurring reaper, not a one-time migration. It shares
        // the sweep# verb with RULE_TENANT_STAMPS, which is exactly why registration is explicit
        // data and not a naming-convention scan.
        long count = ec.entity.find("darpan.migration.DarpanMigration")
                .condition("serviceName",
                        "reconciliation.ReconciliationAutomationServices.sweep#StuckReconciliationRuns")
                .disableAuthz()
                .count()
        assertEquals(0L, count)
    }

    @Test
    void ledgerAndPrereqEntitiesExistAndAreEmpty() {
        assertEquals(0L, ec.entity.find("darpan.migration.DarpanMigrationRun").disableAuthz().count())
        assertEquals(0L, ec.entity.find("darpan.migration.DarpanMigrationPrereq").disableAuthz().count())
    }
}
