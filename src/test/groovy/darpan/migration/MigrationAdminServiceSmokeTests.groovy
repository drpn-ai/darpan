package darpan.migration

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * The remote surface. Everything an agent can reach over /rpc/json for a client instance lives
 * here, and nothing else about migrations is remotely reachable at all.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigrationAdminServiceSmokeTests {
    private ExecutionContext ec

    /** Every migration capability an operator or agent can reach remotely. */
    private static final List<String> ADMIN_SERVICES = [
            "admin.MigrationAdminServices.get#MigrationStatus",
            "admin.MigrationAdminServices.get#MigrationHistory",
            "admin.MigrationAdminServices.run#PendingMigrations",
            "admin.MigrationAdminServices.dryRun#PendingMigrations",
            "admin.MigrationAdminServices.run#Migration",
            "admin.MigrationAdminServices.dryRun#Migration",
            "admin.MigrationAdminServices.set#MigrationEnabled",
    ]

    private static final String SUPER_ADMIN_USER_ID = "MIGRATION_ADMIN_TEST"
    private static final String SUPER_ADMIN_USERNAME = "migration.admin.test"

    /** The supervisor itself. Reachable only through the wrappers above. */
    private static final List<String> INTERNAL_SERVICES = [
            "migration.MigrationServices.list#MigrationStatus",
            "migration.MigrationServices.get#MigrationHistory",
            "migration.MigrationServices.run#PendingMigrations",
            "migration.MigrationServices.run#Migration",
            "migration.MigrationServices.dryRun#Migration",
            "migration.MigrationServices.set#MigrationEnabled",
            "migration.MigrationServices.record#MigrationRun",
    ]

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "migration-admin-smoke")
        // SecuritySeedData carries the DARPAN_ADMIN_API fence rows these services depend on. Loading
        // it here is not test scaffolding — it is the thing under test in
        // everyAdminMigrationServiceIsMatchedByTheSuperAdminFencePattern.
        //
        // dummyFks is required, not incidental: that file loads in ONE transaction (see its own
        // header comment), so a single forward FK into framework reference data this bare H2 test
        // database does not carry would roll back the entire file — silently leaving zero
        // UserGroups and zero fence rows behind.
        boolean seedAuthzDisabled = ec.artifactExecution.disableAuthz()
        try {
            ec.entity.makeDataLoader()
                    .location("component://darpan/data/SecuritySeedData.xml")
                    .dummyFks(true)
                    .load()
        } finally {
            if (!seedAuthzDisabled) ec.artifactExecution.enableAuthz()
        }
        ReconciliationSmokeTestSupport.loadSeedData(ec,
                "component://darpan/data/MigrationRegistrySeedData.xml")

        // Make the logged-in user a REAL super admin rather than stubbing the gate. The denial path
        // uses the seam; the allow path must exercise TenantAccessSupport.isSuperAdmin for real, or
        // nothing proves a genuine super admin can actually get through.
        // initMoqui falls back to an anonymous login when john.doe is absent, which leaves
        // ec.user.userId null — so create and sign in a real user rather than assuming one.
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        try {
            ec.entity.makeValue("moqui.security.UserAccount")
                    .set("userId", SUPER_ADMIN_USER_ID)
                    .set("username", SUPER_ADMIN_USERNAME)
                    .set("userFullName", "Migration Admin Test")
                    .createOrUpdate()
            ec.entity.makeValue("moqui.security.UserGroupMember")
                    .set("userGroupId", "DARPAN_SUPER_ADMIN")
                    .set("userId", SUPER_ADMIN_USER_ID)
                    .set("fromDate", new Timestamp(0L))
                    .createOrUpdate()
        } finally {
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
        assertTrue(ec.user.internalLoginUser(SUPER_ADMIN_USERNAME),
                "test setup failed: could not sign in the super-admin fixture user")
        assertTrue(darpan.facade.common.TenantAccessSupport.isSuperAdmin(ec),
                "test setup failed: the caller is not a super admin, so every allow-path assertion " +
                        "below would pass or fail for the wrong reason")
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void resetState() {
        ec.entity.find("darpan.migration.DarpanMigrationRun").disableAuthz().deleteAll()
        ec.entity.find("darpan.migration.DarpanMigration").disableAuthz().list().each { row ->
            if (row.getString("enabled") != "Y") { row.set("enabled", "Y"); row.update() }
        }
        ec.message.clearErrors()
    }

    @Test
    void everyAdminMigrationServiceResolves() {
        ADMIN_SERVICES.each { String name ->
            assertNotNull(ec.service.getServiceDefinition(name), "${name} does not resolve")
        }
    }

    @Test
    void onlyTheAdminWrappersAreRemoteAndTheSupervisorNeverIs() {
        // The entire safety story rests on exactly one boundary being remote. A supervisor service
        // that became allow-remote="true" would sit behind the facade.* auto-grant, which every
        // authenticated user has, instead of the super-admin admin fence.
        ADMIN_SERVICES.each { String name ->
            assertEquals("true", ec.service.getServiceDefinition(name).serviceNode.attribute("allow-remote"),
                    "${name} must be remote — an agent calls it over /rpc/json")
        }
        INTERNAL_SERVICES.each { String name ->
            assertEquals("false", ec.service.getServiceDefinition(name).serviceNode.attribute("allow-remote"),
                    "${name} must NOT be remote — the admin.* wrapper is the only entry point")
        }
    }

    @Test
    void everyAdminMigrationServiceIsMatchedByTheSuperAdminFencePattern() {
        // artifactName="admin\..*" is a regex over the service name. The group member row merely
        // EXISTING is not the same as it MATCHING these names, which is what actually gates them.
        def member = ec.entity.find("moqui.security.ArtifactGroupMember")
                .condition("artifactGroupId", "DARPAN_ADMIN_API")
                .disableAuthz().one()
        assertNotNull(member, "the DARPAN_ADMIN_API fence row is missing from seed data")

        String pattern = member.getString("artifactName")
        ADMIN_SERVICES.each { String name ->
            assertTrue(name.matches(pattern), "${name} is not matched by the admin fence ${pattern}")
        }
    }

    @Test
    void anAgentCanReadStatusRunEverythingAndSeeItSettle() {
        Map<String, Object> before = ec.service.sync()
                .name("admin.MigrationAdminServices.get#MigrationStatus")
                .parameters([:]).disableAuthz().call()
        assertTrue(before.ok as Boolean)
        assertEquals(6, (before.migrations as List).size())
        (before.migrations as List<Map<String, Object>>).each { assertEquals("PENDING", it.status) }

        Map<String, Object> run = ec.service.sync()
                .name("admin.MigrationAdminServices.run#PendingMigrations")
                .parameters([:]).disableAuthz().call()
        assertTrue(run.ok as Boolean, "run reported failure: ${run.results}")
        assertEquals(6, run.appliedCount)

        Map<String, Object> after = ec.service.sync()
                .name("admin.MigrationAdminServices.get#MigrationStatus")
                .parameters([:]).disableAuthz().call()
        (after.migrations as List<Map<String, Object>>).each { assertEquals("APPLIED", it.status) }
    }

    @Test
    void anAgentCanRunOneMigrationByNameForTargetedRemediation() {
        Map<String, Object> out = ec.service.sync()
                .name("admin.MigrationAdminServices.run#Migration")
                .parameters([migrationId: "ENDPOINT_ACCESS"]).disableAuthz().call()

        assertTrue(out.ok as Boolean)
        assertEquals("APPLIED", out.outcome)
        assertEquals(1L, ec.entity.find("darpan.migration.DarpanMigrationRun").disableAuthz().count())
    }

    @Test
    void anAgentCanParkABrokenMigrationAndLetTheRestOfTheSweepThrough() {
        Map<String, Object> parked = ec.service.sync()
                .name("admin.MigrationAdminServices.set#MigrationEnabled")
                .parameters([migrationId: "RETIRED_FIELDS", enabled: false])
                .disableAuthz().call()
        assertTrue(parked.ok as Boolean)

        Map<String, Object> run = ec.service.sync()
                .name("admin.MigrationAdminServices.run#PendingMigrations")
                .parameters([:]).disableAuthz().call()
        assertEquals(5, run.appliedCount, "parking one migration must not strand the other five")

        Map<String, Object> row = (ec.service.sync()
                .name("admin.MigrationAdminServices.get#MigrationStatus")
                .parameters([:]).disableAuthz().call()
                .migrations as List<Map<String, Object>>)
                .find { it.migrationId == "RETIRED_FIELDS" }
        assertEquals("DISABLED", row.status)
    }

    @Test
    void anAgentCanReadWhyAMigrationFailedWithoutDatabaseAccess() {
        // The closing of the loop: a failure is diagnosable entirely over the remote API.
        ec.service.sync().name("migration.MigrationServices.record#MigrationRun")
                .parameters([migrationId  : "AUTOMATION_FILTERS",
                             statusId     : MigrationLedgerSupport.STATUS_FAILED,
                             messageDetail: "connection refused reaching the OMS host"])
                .disableAuthz().call()

        Map<String, Object> status = (ec.service.sync()
                .name("admin.MigrationAdminServices.get#MigrationStatus")
                .parameters([:]).disableAuthz().call()
                .migrations as List<Map<String, Object>>)
                .find { it.migrationId == "AUTOMATION_FILTERS" }
        assertEquals("connection refused reaching the OMS host", status.lastMessageDetail)

        Map<String, Object> history = ec.service.sync()
                .name("admin.MigrationAdminServices.get#MigrationHistory")
                .parameters([migrationId: "AUTOMATION_FILTERS"]).disableAuthz().call()
        assertEquals(1, (history.attempts as List).size())
        assertEquals("connection refused reaching the OMS host",
                (history.attempts as List<Map<String, Object>>)[0].messageDetail)
    }

    @Test
    void aDeniedCallerGetsNoDataAndNoSilentEmptyList() {
        // An empty migrations list and a denial must never look alike: an agent reading [] as
        // "nothing to do" on a client that is actually behind would report an upgrade complete.
        MigrationAdminSupport.superAdminGate = { anyEc -> false }
        try {
            Map<String, Object> out = ec.service.sync()
                    .name("admin.MigrationAdminServices.get#MigrationStatus")
                    .parameters([:]).disableAuthz().call()

            assertEquals(false, out.ok, "a denied read must not report ok")
            assertTrue(ec.message.hasError(), "a denied read must say why")
        } finally {
            MigrationAdminSupport.superAdminGate = MigrationAdminSupport.DEFAULT_GATE
            ec.message.clearErrors()
        }
    }
}
