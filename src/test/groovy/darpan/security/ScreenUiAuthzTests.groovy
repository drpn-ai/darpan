package darpan.security

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ArtifactExecutionFacadeImpl
import org.moqui.impl.context.ExecutionContextImpl

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * MACH maturity audit P0 #3 — the legacy server-rendered admin UI under {@code /apps/darpan}
 * ({@code component://darpan/screen/**}) drives transitions that call domain services directly,
 * bypassing the facade where tenant scoping and input validation are concentrated. It must stay
 * gated to super-admins only: a regular tenant user and a DARPAN_ADMIN must NOT reach it, while
 * DARPAN_SUPER_ADMIN and framework ADMIN must. The gate is data-driven (SecuritySeedData.xml
 * artifact-authz), so this test guards the seed against a silent regression that would re-expose
 * the facade-bypass surface.
 *
 * <p>Grant alignment (Task 9, 2026-06-27): removed DARPAN_SCREEN_UI_ADMIN so that URL-authz and
 * the isSuperAdmin() pre-actions content gate are in sync. DARPAN_ADMIN previously passed the URL
 * check but was then content-denied — misleading and not a real grant. Only DARPAN_SUPER_ADMIN
 * (and framework ADMIN via DARPAN_APP_ADMIN) now have URL-level access.</p>
 *
 * <p>DAR-OPS-029 S36a (Aditi, 2026-11-28): the legacy UI itself is deleted. The seed still guards any screen under
 * component://darpan/screen, so the authz checks now ask about the surviving root, darpan.xml, and a structural guard
 * keeps that root to its two transitions, with no screen hung under it again unnoticed.</p>
 */
@SharedMoqui
class ScreenUiAuthzTests {
    private static ExecutionContext ec

    // Moqui resolves screen authz by the screen's component location; "AT_XML_SCREEN:AUTHZA_VIEW:<name>"
    // is the framework's resource-access string form (ArtifactExecutionFacadeImpl.isPermitted).
    private static final String SCREEN_RESOURCE =
            "AT_XML_SCREEN:AUTHZA_VIEW:component://darpan/screen/darpan.xml"

    private static final String REGULAR_USER_ID      = "AUTHZ_TEST_REGULAR"
    private static final String APP_ADMIN_USER_ID    = "AUTHZ_TEST_APP_ADMIN"
    private static final String SUPER_ADMIN_USER_ID  = "AUTHZ_TEST_SUPER_ADMIN"
    private static final String TENANT_USER_ID       = "AUTHZ_TEST_TENANT_USER"

    @BeforeAll
    static void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "screen-ui-authz")
        // The smoke harness boots a near-empty DB, so load the foundational security model: framework
        // 'seed' (artifact-type + authz enumerations, embedded in SecurityEntities seed-data) and
        // 'seed-initial' (base ALL_USERS / ADMIN groups), plus 'darpan-seed' (the DARPAN_APP / screen
        // artifact-authz and DARPAN_* permission groups from SecuritySeedData.xml) — exactly as deployed.
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        try {
            // dummyFks(true): SecuritySeedData.xml references some user groups before defining them
            // (e.g. DARPAN_FACADE_APP_USER -> DARPAN_USER); placeholder FK targets let the forward refs
            // resolve, mirroring how the standard `gradle load` ingests this file.
            ec.entity.makeDataLoader().dummyFks(true)
                    .dataTypes(['seed', 'seed-initial', 'darpan-seed'] as Set).load()
        } finally {
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
        createUserInGroup(REGULAR_USER_ID,     "DARPAN_USER")
        createUserInGroup(APP_ADMIN_USER_ID,   "DARPAN_ADMIN")
        createUserInGroup(SUPER_ADMIN_USER_ID, "DARPAN_SUPER_ADMIN")
        createUserInGroup(TENANT_USER_ID,      "DARPAN_TENANT_USER")
    }

    @AfterAll
    static void teardown() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @Test
    void regularTenantUserIsDeniedTheLegacyScreenUi() {
        assertFalse(screenPermittedFor(REGULAR_USER_ID),
                "A regular DARPAN_USER must NOT reach the facade-bypassing /apps/darpan screen UI")
    }

    /**
     * Task 9 grant change: DARPAN_ADMIN is now DENIED at URL-authz level.
     * Previously DARPAN_SCREEN_UI_ADMIN granted URL access but isSuperAdmin() blocked content;
     * removing that grant makes the two layers consistent.
     */
    @Test
    void appAdminIsDeniedTheLegacyScreenUi() {
        assertFalse(screenPermittedFor(APP_ADMIN_USER_ID),
                "A DARPAN_ADMIN must NOT reach the /apps/darpan screen UI — super-admin required")
    }

    /** Task 9: DARPAN_SUPER_ADMIN must be allowed at URL-authz level. */
    @Test
    void superAdminIsAllowedTheLegacyScreenUi() {
        assertTrue(screenPermittedFor(SUPER_ADMIN_USER_ID),
                "A DARPAN_SUPER_ADMIN must be able to reach the /apps/darpan screen UI")
    }

    @Test
    void tenantUserIsDeniedTheLegacyScreenUi() {
        assertFalse(screenPermittedFor(TENANT_USER_ID),
                "A DARPAN_TENANT_USER must NOT reach the facade-bypassing /apps/darpan screen UI")
    }

    /** Evaluate the real Moqui authz gate for the screen artifact as the given user. */
    private static boolean screenPermittedFor(String userId) {
        ec.artifactExecution.disableAuthz()
        try {
            loginAs(userId)
        } finally {
            ec.artifactExecution.enableAuthz()
        }
        try {
            return ArtifactExecutionFacadeImpl.isPermitted(SCREEN_RESOURCE, (ExecutionContextImpl) ec)
        } finally {
            ec.artifactExecution.disableAuthz()
        }
    }

    /**
     * DAR-OPS-029 S36a: the darpan screen root carries only the SPA's CSRF bootstrap and the Slack install callback, and no
     * subscreen. A screen added under it again would be reachable at /apps/darpan by a super admin and must be a deliberate
     * change (with its own authz review), not a quiet one; the deleted legacy trees must stay deleted.
     */
    @Test
    void theScreenRootCarriesOnlyItsTwoTransitionsAndNoSubscreen() {
        Path screenDir = ReconciliationSmokeTestSupport.resolveBackendRoot().resolve("runtime/component/darpan/screen")
        def root = new groovy.xml.XmlParser(false, false).parse(screenDir.resolve("darpan.xml").toFile())
        assertEquals(["csrfToken", "slackOauthCallback"], root.transition.collect { it.@name }.sort())
        assertTrue(root.subscreens.isEmpty(), "darpan.xml has subscreens again: ${root.subscreens}")
        ["Reconciliation", "Reconciliation.xml", "Settings", "Settings.xml", "Migrations.xml"].each { String gone ->
            assertFalse(screenDir.resolve(gone).toFile().exists(), "legacy screen ${gone} is back")
        }
    }

    /** Logs in by userId (the realm's case-insensitive username lookup matches userId.toLowerCase()) and proves it took. */
    private static void loginAs(String userId) {
        assertTrue(ec.user.internalLoginUser(userId), "could not log in ${userId}")
        assertEquals(userId, ec.user.userId, "logged in as the wrong user")
    }

    private static void createUserInGroup(String userId, String userGroupId) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        try {
            ec.entity.makeValue("moqui.security.UserAccount")
                    .set("userId", userId)
                    .set("username", userId.toLowerCase())
                    .set("userFullName", userId)
                    .createOrUpdate()
            ec.entity.makeValue("moqui.security.UserGroupMember")
                    .set("userGroupId", userGroupId)
                    .set("userId", userId)
                    .set("fromDate", new Timestamp(0L))
                    .createOrUpdate()
        } finally {
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
    }
}
