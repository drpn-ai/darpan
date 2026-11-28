package darpan.migration

import darpan.reconciliation.support.SharedMoqui
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.xml.XmlSlurper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue
import static org.junit.jupiter.api.Assertions.assertNotNull

@SharedMoqui
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
    void registrySeedLoadsEveryRegisteredMigration() {
        List<String> ids = ec.entity.find("darpan.migration.DarpanMigration")
                .orderBy("sequenceNum")
                .disableAuthz()
                .list()
                .collect { it.getString("migrationId") }

        assertEquals(registeredInSeed(), ids)
    }

    /** DAR-BE-319 / OC142: no seed row states enabled="Y", so a deploy's seed reload never re-enables a migration an
     *  operator parked; a fresh load starts a row enabled through the entity default. Only "N" (ships parked) is allowed. */
    @Test
    void noSeedRowStatesEnabledY() {
        File seed = new File(ReconciliationSmokeTestSupport.resolveBackendRoot().toString(),
                "runtime/component/darpan/data/MigrationRegistrySeedData.xml")
        List<String> stated = new XmlSlurper().parse(seed).'**'
                .findAll { it.name() == "darpan.migration.DarpanMigration" && it.@enabled.text() == "Y" }
                .collect { it.@migrationId.text() }
        assertEquals([], stated, "seed rows must not state enabled=\"Y\": ${stated}")
    }

    /** The seed file's DarpanMigration rows by sequenceNum: the list grows with every migration, so it is read, not restated here. */
    static List<String> registeredInSeed() {
        File seed = new File(ReconciliationSmokeTestSupport.resolveBackendRoot().toString(),
                "runtime/component/darpan/data/MigrationRegistrySeedData.xml")
        List<String> ids = new XmlSlurper().parse(seed).'**'.findAll { it.name() == "darpan.migration.DarpanMigration" }
                .sort { (it.@sequenceNum.text()) as long }
                .collect { it.@migrationId.text() }
        assertTrue(ids.size() >= 10, "the seed holds at least the ten migrations registered by 2026-11-10, read ${ids}")
        return ids
    }

    /** The seed rows run#PendingMigrations takes: a row may ship disabled (enabled="N", e.g. ORPHAN_SCHEMA_DROPS, which drops
     *  tables only once someone enables it); no enabled attribute means the entity default, Y. */
    static List<String> enabledInSeed() {
        File seed = new File(ReconciliationSmokeTestSupport.resolveBackendRoot().toString(),
                "runtime/component/darpan/data/MigrationRegistrySeedData.xml")
        Set<String> disabled = new XmlSlurper().parse(seed).'**'
                .findAll { it.name() == "darpan.migration.DarpanMigration" && it.@enabled.text() == "N" }
                .collect { it.@migrationId.text() } as Set<String>
        return registeredInSeed().findAll { !(it in disabled) }
    }

    @Test
    void everyRegisteredServiceNameResolvesToARealService() {
        // A registry row naming a service that does not exist would fail mid-run, after earlier
        // migrations had already written. This asserts the seed is honest at load time.
        List rows = ec.entity.find("darpan.migration.DarpanMigration").disableAuthz().list()
        assertEquals(registeredInSeed().size(), rows.size(), "every seeded row is checked, not an empty list")
        rows.each { row ->
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
}
