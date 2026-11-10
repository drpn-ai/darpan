package darpan.facade.auth

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-195. Who may use the CLI, where, and how long a sign-in lasts there: by role, per tenant. Tenants and users are
 * synthetic. ANN is a tenant admin in ALPHA and view-only in BETA (spec decision 5); STAFF is a Darpan admin.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CliAccessSmokeTests {
    private static final String ALPHA = "CLA_ALPHA"
    private static final String BETA = "CLA_BETA"
    private static final String GAMMA = "CLA_GAMMA"
    private static final String ANN = "CLA_ANN"
    private static final String ED = "CLA_ED"
    private static final String STAFF = "CLA_STAFF"
    private static final long HOUR = 3_600_000L
    private static final long DAY = 24 * HOUR

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "cli-access")
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        asSystem {
            [ANN, ED, STAFF].each { String u ->
                ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: u, username: u.toLowerCase(), userFullName: u,
                        emailAddress: "${u.toLowerCase()}@example.com".toString(), disabled: "N", requirePasswordChange: "N"]).createOrUpdate()
            }
            [ALPHA, BETA, GAMMA].each { String t ->
                ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: t, description: "${t} Co".toString(),
                        groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]).createOrUpdate()
            }
            role(ALPHA, ANN, TenantAccessSupport.DARPAN_TENANT_ADMIN_GROUP_ID)
            role(BETA, ANN, TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID)
            role(GAMMA, ANN, TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, [thruDate: ago(DAY)]) // membership ended
            role(ALPHA, ED, TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID)
            role(ALPHA, ED, TenantAccessSupport.DARPAN_TENANT_USER_GROUP_ID)
            role(BETA, ED, TenantAccessSupport.DARPAN_TENANT_USER_GROUP_ID)
            ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: TenantAccessSupport.DARPAN_ADMIN_GROUP_ID, userId: STAFF,
                    fromDate: ago(30 * DAY)]).createOrUpdate()
        }
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void reset() {
        asSystem { [CliAccess.EVENT, CliAccess.GRANT].each { ec.entity.find(it).deleteAll() } }
    }

    private void role(String tenant, String user, String group, Map extra = [:]) {
        ec.entity.makeValue(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME).setAll([tenantUserGroupId: tenant, userId: user,
                permissionUserGroupId: group, fromDate: ago(30 * DAY)] + extra).createOrUpdate()
    }

    private void grant(String id, String tenant, String user, Map extra = [:]) {
        asSystem {
            ec.entity.makeValue(CliAccess.GRANT).setAll([grantId: id, companyUserGroupId: tenant, userId: user, fromDate: ago(HOUR),
                    statusId: "ACTIVE"] + extra).create()
        }
    }

    private static Timestamp ago(long ms) { new Timestamp(System.currentTimeMillis() - ms) }

    private <T> T asSystem(Closure<T> work) {
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try { return work.call() } finally { if (!wasDisabled) ec.artifactExecution.enableAuthz() }
    }

    @Test
    void "a sign-in lasts by role in each tenant: 30 for a tenant admin or Darpan admin, 7 for the rest, 0 for no role"() {
        assertEquals(30, CliAccess.lifetimeDays(ec, ANN, ALPHA))
        assertEquals(7, CliAccess.lifetimeDays(ec, ANN, BETA))
        assertEquals(0, CliAccess.lifetimeDays(ec, ANN, GAMMA), "an ended membership is no role")
        assertEquals(7, CliAccess.lifetimeDays(ec, ED, ALPHA), "editor + legacy tenant user: both 7")
        assertEquals(7, CliAccess.lifetimeDays(ec, ED, BETA), "legacy DARPAN_TENANT_USER is 7")
        assertEquals(30, CliAccess.lifetimeDays(ec, STAFF, GAMMA), "a Darpan admin needs no membership")
    }

    @Test
    void "only a live grant in a tenant the user still belongs to turns the CLI on"() {
        assertFalse(CliAccess.cliAllowed(ec, ANN))
        grant("G_PAUSED", ALPHA, ANN, [statusId: "PAUSED"])
        grant("G_LATER", BETA, ANN, [fromDate: new Timestamp(System.currentTimeMillis() + DAY)])
        grant("G_GONE", GAMMA, ANN)
        assertFalse(CliAccess.cliAllowed(ec, ANN), "paused, not yet started, and a tenant whose membership ended")
        grant("G_ENDED", ALPHA, ED, [fromDate: ago(2 * DAY), thruDate: ago(DAY)])
        assertFalse(CliAccess.cliAllowed(ec, ED), "a grant past its end")
        assertTrue(CliAccess.cliAllowed(ec, STAFF), "a Darpan admin with no grant")
        grant("G_BETA", BETA, ED)
        assertEquals([BETA], CliAccess.grantedTenants(ec, ED))
        assertNull(CliAccess.liveGrant(ec, ED, ALPHA), "a grant in BETA says nothing about ALPHA")
    }

    @Test
    void "a new key lives as long as the longest role among the tenants where the CLI is on, not where the user is admin"() {
        grant("G_B", BETA, ANN)
        assertEquals(7, CliAccess.keyLifetimeDays(ec, ANN), "admin in ALPHA, but the CLI is on only in BETA, where ANN is view-only")
        grant("G_A", ALPHA, ANN)
        assertEquals(30, CliAccess.keyLifetimeDays(ec, ANN))
        assertEquals(0, CliAccess.keyLifetimeDays(ec, ED))
        assertEquals(30, CliAccess.keyLifetimeDays(ec, STAFF))
    }

    @Test
    void "each call is held to the role in its own tenant"() {
        grant("G_A", ALPHA, ANN)
        grant("G_B", BETA, ANN)
        assertNull(CliAccess.tenantProblem(ec, ANN, BETA, ago(7 * DAY - HOUR)), "view-only, just under 7 days")
        String old = CliAccess.tenantProblem(ec, ANN, BETA, ago(7 * DAY))
        assertEquals("This CLI sign-in is older than 7 days, the limit for your role in ${BETA} Co. Run darpan login to sign in again.".toString(), old)
        assertNull(CliAccess.tenantProblem(ec, ANN, ALPHA, ago(29 * DAY)), "the same sign-in still answers in ALPHA")
        assertNotNull(CliAccess.tenantProblem(ec, ANN, ALPHA, ago(30 * DAY)))
        assertEquals("The Darpan CLI is not turned on for you in ${GAMMA} Co. A Darpan admin turns it on.".toString(),
                CliAccess.tenantProblem(ec, ANN, GAMMA, ago(HOUR)))
        assertNull(CliAccess.tenantProblem(ec, STAFF, GAMMA, ago(29 * DAY)), "a Darpan admin needs no grant")
        assertNotNull(CliAccess.tenantProblem(ec, STAFF, GAMMA, ago(30 * DAY)))
    }

    @Test
    void "a paused grant or a demotion counts on the next call"() {
        grant("G_A", ALPHA, ANN)
        assertNull(CliAccess.tenantProblem(ec, ANN, ALPHA, ago(10 * DAY)))
        asSystem {
            ec.entity.find(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME).condition("tenantUserGroupId", ALPHA)
                    .condition("userId", ANN).deleteAll()
        }
        role(ALPHA, ANN, TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID)
        try {
            assertTrue(CliAccess.tenantProblem(ec, ANN, ALPHA, ago(10 * DAY))?.contains("older than 7 days"), "demoted to view-only")
        } finally {
            asSystem {
                ec.entity.find(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME).condition("tenantUserGroupId", ALPHA)
                        .condition("userId", ANN).deleteAll()
            }
            role(ALPHA, ANN, TenantAccessSupport.DARPAN_TENANT_ADMIN_GROUP_ID)
        }
        asSystem { ec.entity.find(CliAccess.GRANT).condition("grantId", "G_A").updateAll([statusId: "PAUSED"]) }
        assertTrue(CliAccess.tenantProblem(ec, ANN, ALPHA, ago(HOUR))?.contains("not turned on"), "paused")
    }
}
