package darpan.migration

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigrationScreenSmokeTests {
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "migration-screen-smoke")
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @Test
    void migrationsScreenParsesAndIsRegisteredUnderTheDarpanTree() {
        def screen = ec.screen.getScreenDefinition("component://darpan/screen/Migrations.xml")
        assertNotNull(screen, "Migrations.xml did not parse")

        def root = ec.screen.getScreenDefinition("component://darpan/screen/darpan.xml")
        assertTrue(root.getSubscreensItemsSorted().any { it.name == "Migrations" },
                "Migrations is not registered as a subscreen of darpan.xml")
    }

    @Test
    void migrationsScreenRequiresAuthentication() {
        def screen = ec.screen.getScreenDefinition("component://darpan/screen/Migrations.xml")
        assertTrue(screen.getScreenNode().attribute("require-authentication") == "true",
                "these migrations sweep every tenant's data; the screen must require authentication")
    }

    @Test
    void migrationsScreenGatesOnSuperAdminTheSameWaySettingsDoes() {
        // Settings.xml's pre-actions denial is deliberate and documented: it writes a 403 body and
        // sets sri.dontDoRender = true, because sendRedirect would emit a 302 that silently
        // overrides the 403. A screen that invented its own denial path would drift from that.
        String source = new File(ReconciliationSmokeTestSupport.resolveBackendRoot().toString(),
                "runtime/component/darpan/screen/Migrations.xml").getText("UTF-8")
        assertTrue(source.contains("TenantAccessSupport.isSuperAdmin(ec)"),
                "the screen does not gate on isSuperAdmin")
        assertTrue(source.contains("sri.dontDoRender = true"),
                "the screen denies without suppressing the render, so the 403 body would be " +
                        "replaced by the real screen")
    }
}
