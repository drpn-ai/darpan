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
 * DAR-BE-195. Who may use the CLI, where, and how long a sign-in lasts (DAR-BE-261: 7 days for every role, admins included).
 * Tenants and users are synthetic. ANN is a tenant admin in ALPHA and view-only in BETA (spec decision 5); STAFF is a Darpan admin.
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
    void "DAR-BE-261: a sign-in lasts 7 days for every role, a tenant admin or Darpan admin no longer than the rest, 0 for no role"() {
        assertEquals(7, CliAccess.SIGN_IN_DAYS)
        assertEquals(7, CliAccess.lifetimeDays(ec, ANN, ALPHA), "a tenant admin: no longer than view-only")
        assertEquals(7, CliAccess.lifetimeDays(ec, ANN, BETA))
        assertEquals(0, CliAccess.lifetimeDays(ec, ANN, GAMMA), "an ended membership is no role")
        assertEquals(7, CliAccess.lifetimeDays(ec, ED, ALPHA), "editor + legacy tenant user: both 7")
        assertEquals(7, CliAccess.lifetimeDays(ec, ED, BETA), "legacy DARPAN_TENANT_USER is 7")
        assertEquals(7, CliAccess.lifetimeDays(ec, STAFF, GAMMA), "a Darpan admin needs no membership, and gets no longer")
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
    void "DAR-BE-261: a new key lives 7 days wherever the CLI is on, an admin's too, none without a grant"() {
        grant("G_B", BETA, ANN)
        assertEquals(7, CliAccess.keyLifetimeDays(ec, ANN), "the CLI is on only in BETA, where ANN is view-only")
        grant("G_A", ALPHA, ANN)
        assertEquals(7, CliAccess.keyLifetimeDays(ec, ANN), "on in ALPHA too, where ANN is tenant admin: still 7")
        assertEquals(0, CliAccess.keyLifetimeDays(ec, ED))
        assertEquals(7, CliAccess.keyLifetimeDays(ec, STAFF), "a Darpan admin: 7, not the 30 it was")
    }

    @Test
    void "DAR-BE-261: a sign-in is too old at 7 days, whoever holds it"() {
        assertFalse(CliAccess.signInExpired(ago(7 * DAY - HOUR)))
        assertTrue(CliAccess.signInExpired(ago(7 * DAY)))
        assertTrue(CliAccess.signInExpired(ago(29 * DAY)), "an admin key issued for 30 days before the change")
        assertFalse(CliAccess.signInExpired(null))
    }

    @Test
    void "each call is held to a live grant and a CLI role in its own tenant"() {
        grant("G_B", BETA, ANN)
        assertNull(CliAccess.tenantProblem(ec, ANN, BETA))
        assertEquals("The Darpan CLI is not turned on for you in ${ALPHA} Co. A Darpan admin turns it on.".toString(),
                CliAccess.tenantProblem(ec, ANN, ALPHA), "no grant in ALPHA")
        grant("G_GAMMA", GAMMA, ANN)
        assertEquals("The Darpan CLI is not turned on for you in ${GAMMA} Co. A Darpan admin turns it on.".toString(),
                CliAccess.tenantProblem(ec, ANN, GAMMA), "a grant, but the membership ended")
        assertNull(CliAccess.tenantProblem(ec, STAFF, GAMMA), "a Darpan admin needs no grant")
    }

    @Test
    void "a paused grant or a removed role counts on the next call"() {
        grant("G_A", ALPHA, ANN)
        assertNull(CliAccess.tenantProblem(ec, ANN, ALPHA))
        asSystem {
            ec.entity.find(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME).condition("tenantUserGroupId", ALPHA)
                    .condition("userId", ANN).deleteAll()
        }
        try {
            assertTrue(CliAccess.tenantProblem(ec, ANN, ALPHA)?.contains("not turned on"), "no role left in ALPHA")
        } finally {
            role(ALPHA, ANN, TenantAccessSupport.DARPAN_TENANT_ADMIN_GROUP_ID)
        }
        asSystem { ec.entity.find(CliAccess.GRANT).condition("grantId", "G_A").updateAll([statusId: "PAUSED"]) }
        assertTrue(CliAccess.tenantProblem(ec, ANN, ALPHA)?.contains("not turned on"), "paused")
    }
}
