package darpan.admin

import darpan.facade.auth.PasswordResetSupport
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.Moqui
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityException

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*
import static darpan.reconciliation.support.TestHelpers.upsert

/**
 * The admin.* tenant, user and membership services, exercised through the real service engine
 * (DAR-BE-074). These replace the stubbed-ec unit tests that called AdminTenantSupport /
 * AdminUserSupport / AdminMembershipSupport directly, and every assertion those carried lives here.
 *
 * Two different refusals are pinned, because they are two different layers:
 *   - the DARPAN_ADMIN_API artifact fence (authz ON, non-admin caller → ArtifactAuthorizationException);
 *   - the in-service super-admin gate, the defense in depth behind it (the same caller with the
 *     fence bypassed via disableAuthz() on the call → refused with the gate's message, no writes).
 *
 * Every allowed call runs with authz ON as a real DARPAN_SUPER_ADMIN member, on a thread of its own
 * so it does not inherit the smoke harness's disabled authz or its anonymous-authorized-all
 * artifact. That is what proves the cross-tenant reads are neither denied nor narrowed by the
 * DARPAN_ACTIVE_COMPANY_SCOPE filter (TenantSetting IS a filtered entity): the super admin's active
 * tenant is ALPHA, and BRAVO's deactivated flag must still come back.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SharedMoqui
class AdminServicesSmokeTests {
    private ExecutionContext ec

    private static final String SUPER_ID = "ADMSVC_SUPER"
    private static final String SUPER_USERNAME = "admsvc.super"
    private static final String PLAIN_ID = "ADMSVC_PLAIN"
    private static final String PLAIN_USERNAME = "admsvc.plain"
    private static final String TARGET_ID = "ADMSVC_TARGET"
    private static final String TARGET_USERNAME = "admsvc.target"
    private static final String DORMANT_ID = "ADMSVC_DORMANT"

    private static final String ALPHA = "ADMSVC_ALPHA"
    private static final String BRAVO = "ADMSVC_BRAVO"
    private static final String CHARLIE = "ADMSVC_CHARLIE"
    private static final String DELTA = "ADMSVC_DELTA"

    private static final String STRONG_PASSWORD = "Temp#Pass1234"
    private static final String GATE_MESSAGE = AdminAccessSupport.SUPER_ADMIN_REQUIRED_MESSAGE
    private static final String PREF_KEY = TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY

    private static final Timestamp T0 = Timestamp.valueOf("2026-01-01 00:00:00")
    private static final Timestamp T1 = Timestamp.valueOf("2026-02-01 00:00:00")
    private static final Timestamp T_ACTIVE = Timestamp.valueOf("2026-03-01 00:00:00")
    private static final Timestamp ALPHA_CREATED = Timestamp.valueOf("2026-01-02 03:04:05")
    private static final Timestamp BRAVO_CREATED = Timestamp.valueOf("2026-01-03 04:05:06")

    private static final List<String> MUTATIONS = [
            "admin.TenantAdminServices.update#Tenant",
            "admin.TenantAdminServices.deactivate#Tenant",
            "admin.TenantAdminServices.reactivate#Tenant",
            "admin.UserAdminServices.create#UserAccount",
            "admin.UserAdminServices.update#UserAccount",
            "admin.UserAdminServices.disable#UserAccount",
            "admin.UserAdminServices.enable#UserAccount",
            "admin.UserAdminServices.reset#Password",
            "admin.MembershipAdminServices.add#TenantMember",
            "admin.MembershipAdminServices.update#TenantMemberRole",
            "admin.MembershipAdminServices.remove#TenantMember",
            "admin.MembershipAdminServices.send#TenantWelcome",
            "admin.UserAdminServices.add#DarpanAdmin",
            "admin.UserAdminServices.remove#DarpanAdmin",
    ]

    private int uniqueCounter = 0
    private Closure originalSendEmail
    private Closure originalSendWelcomeEmail
    /** What the stubbed mail server answers a welcome send (DAR-BE-325 sends wait for it). */
    private volatile boolean mailAccepts = true
    private List<Map> sent = Collections.synchronizedList([])

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "admin-services-smoke")
        // SecuritySeedData carries the DARPAN_ADMIN_API fence, the Darpan groups, and the
        // ADMIN_PASSWORD grant the password services need. dummyFks: the file loads in one
        // transaction, and one forward FK into reference data this bare H2 lacks would roll it all back.
        ec.entity.makeDataLoader()
                .location("component://darpan/data/SecuritySeedData.xml")
                .dummyFks(true)
                .load()
        // DAR-BE-290: the SYSTEM EmailServer row (PasswordReset seed) first, then the welcome template that FKs it.
        ["PasswordResetEmailSeedData.xml", "TenantWelcomeEmailSeedData.xml"].each { String file ->
            ec.entity.makeDataLoader().location("component://darpan/data/${file}").dummyFks(true).load()
        }
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    /** Mail never leaves the JVM: AdminMembershipSupport.sendWelcomeEmail is the seam the welcome email goes through
     *  (keepCopy false = no EmailMessage row), PasswordResetSupport.sendEmail the one for the notices. */
    @BeforeEach
    void recordMail() {
        originalSendEmail = PasswordResetSupport.sendEmail
        originalSendWelcomeEmail = AdminMembershipSupport.sendWelcomeEmail
        sent.clear()
        mailAccepts = true
        PasswordResetSupport.sendEmail = { def ecArg, String templateId, String to, Map params ->
            sent << [templateId: templateId, to: to, params: params]
        }
        AdminMembershipSupport.sendWelcomeEmail = { def ecArg, String to, Map params, boolean keepCopy ->
            sent << [templateId: AdminMembershipSupport.TEMPLATE_TENANT_WELCOME, to: to, params: params, keepCopy: keepCopy]
            return mailAccepts
        }
    }

    @AfterEach
    void restoreMail() {
        PasswordResetSupport.sendEmail = originalSendEmail
        AdminMembershipSupport.sendWelcomeEmail = originalSendWelcomeEmail
    }

    @BeforeEach
    void resetFixtures() {
        // initMoqui leaves the main thread with authz disabled, so these writes are unrestricted.
        ["darpan.auth.TenantSetting", "darpan.auth.TenantUserPermissionGroupMember",
         "moqui.security.UserPreference", "moqui.security.UserLoginKey",
         "moqui.security.UserGroupMember", TempPasswordKeeper.ENTITY].each { String entityName ->
            ec.entity.find(entityName).disableAuthz().deleteAll()
        }
        // Only this class's CLI rows: other @SharedMoqui classes keep theirs.
        ec.entity.find("darpan.auth.CliSession").condition("loginKeyHash", org.moqui.entity.EntityCondition.LIKE, "admsvc-%")
                .disableAuthz().deleteAll()
        ec.entity.find("darpan.auth.CliDeviceCode").condition("deviceCodeHash", org.moqui.entity.EntityCondition.LIKE, "admsvc-%")
                .disableAuthz().deleteAll()
        AuditLogTestSupport.wipe(ec)

        upsert(ec, "moqui.security.UserAccount", [userId: SUPER_ID, username: SUPER_USERNAME,
                userFullName: "Admin Super", emailAddress: "admsvc.super@example.com", disabled: "N", requirePasswordChange: "N"])
        upsert(ec, "moqui.security.UserAccount", [userId: PLAIN_ID, username: PLAIN_USERNAME,
                userFullName: "Plain Tenant Admin", emailAddress: "admsvc.plain@example.com", disabled: "N", requirePasswordChange: "N",
                sessionsEndedDate: null])
        upsert(ec, "moqui.security.UserAccount", [userId: TARGET_ID, username: TARGET_USERNAME,
                userFullName: "Target User", emailAddress: "admsvc.target@example.com", disabled: "N", requirePasswordChange: "N",
                sessionsEndedDate: null])
        upsert(ec, "moqui.security.UserAccount", [userId: DORMANT_ID, username: "admsvc.dormant",
                userFullName: "Dormant User", emailAddress: "admsvc.dormant@example.com", disabled: "Y", requirePasswordChange: "Y"])

        upsert(ec, "moqui.security.UserGroup", [userGroupId: ALPHA, description: "Alpha Co",
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        upsert(ec, "moqui.security.UserGroup", [userGroupId: BRAVO, description: "Bravo Co",
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        // No description and no TenantSetting: label falls back to the id, disabled reads false.
        upsert(ec, "moqui.security.UserGroup", [userGroupId: CHARLIE, description: null,
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        // Lower-case label: a case-insensitive sort would put it before "Bravo Co".
        upsert(ec, "moqui.security.UserGroup", [userGroupId: DELTA, description: "beta lower",
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])

        create("darpan.auth.TenantSetting", [companyUserGroupId: ALPHA, timeZone: "America/Chicago",
                disabled: "N", createdByUserId: SUPER_ID, createdDate: ALPHA_CREATED, lastUpdatedDate: ALPHA_CREATED])
        create("darpan.auth.TenantSetting", [companyUserGroupId: BRAVO, timeZone: "Europe/London",
                disabled: "Y", createdByUserId: SUPER_ID, createdDate: BRAVO_CREATED, lastUpdatedDate: BRAVO_CREATED])
        create("darpan.auth.TenantSetting", [companyUserGroupId: DELTA, timeZone: "UTC",
                disabled: "N", createdByUserId: SUPER_ID, createdDate: BRAVO_CREATED, lastUpdatedDate: BRAVO_CREATED])

        groupMember("DARPAN_SUPER_ADMIN", SUPER_ID, T0, null)
        groupMember("DARPAN_USER", PLAIN_ID, T0, null)
        groupMember("DARPAN_USER", TARGET_ID, T0, null)
        groupMember(ALPHA, PLAIN_ID, T_ACTIVE, null)
        groupMember(ALPHA, TARGET_ID, T_ACTIVE, null)
        groupMember(BRAVO, TARGET_ID, T_ACTIVE, null)
        groupMember(ALPHA, SUPER_ID, T0, T1) // expired: never counted

        permissionMember(ALPHA, PLAIN_ID, "DARPAN_TENANT_ADMIN", T_ACTIVE, null)
        permissionMember(ALPHA, TARGET_ID, "DARPAN_TENANT_USER", T_ACTIVE, null)
        permissionMember(ALPHA, TARGET_ID, "DARPAN_COMPANY_VIEW_ONLY", T0, T1) // expired: never listed
        permissionMember(BRAVO, TARGET_ID, "DARPAN_COMPANY_EDITOR", T_ACTIVE, null)

        // The super admin is "in" ALPHA. If the tenant filter narrowed admin reads, BRAVO's row
        // (and its deactivated flag) would vanish from the board.
        preference(SUPER_ID, ALPHA)
        ec.message.clearErrors()
    }

    // ---------------------------------------------------------------- reads: tenants

    @Test
    void tenantListReturnsEveryTenantAcrossTenantsForASuperAdmin() {
        Map out = callAs(SUPER_USERNAME, "admin.TenantAdminServices.get#TenantList", [:])

        assertNoErrors(out)
        List<Map> tenants = (out.result.tenantList as List<Map>).findAll { (it.tenantUserGroupId as String).startsWith("ADMSVC_") }
        assertEquals([CHARLIE, ALPHA, BRAVO, DELTA], tenants*.tenantUserGroupId,
                "sorted by label, case-sensitively, with the id as the fallback label")
        assertEquals([CHARLIE, "Alpha Co", "Bravo Co", "beta lower"], tenants*.label)
        assertEquals([false, false, true, false], tenants*.disabled,
                "BRAVO is outside the caller's active tenant; its deactivated flag must still be read")
        assertEquals([0, 2, 1, 0], tenants*.memberCount, "only rows without a thruDate count")
        tenants.each { assertTrue(it.memberCount instanceof Integer, "memberCount stays an Integer") }
        assertTrue((out.result.tenantList as List<Map>).every { !(it.tenantUserGroupId in ["DARPAN_USER", "DARPAN_SUPER_ADMIN"]) },
                "non-tenant groups are never listed")
        List<String> labels = (out.result.tenantList as List<Map>)*.label
        assertEquals(labels.toSorted(), labels, "the whole board is label-sorted")
    }

    @Test
    void tenantListIsRefusedForANonAdminAtTheFenceAndByTheGate() {
        assertFenceRefuses(PLAIN_USERNAME, "admin.TenantAdminServices.get#TenantList", [:])

        Map gated = callAs(PLAIN_USERNAME, "admin.TenantAdminServices.get#TenantList", [:], true)
        assertGateRefused(gated)
        assertEquals([], gated.result.tenantList, "a refused list is an empty list, never another tenant's rows")
    }

    @Test
    void tenantDetailReturnsAnotherTenantsSettingsAndOnlyActiveMembers() {
        Map bravo = callAs(SUPER_USERNAME, "admin.TenantAdminServices.get#TenantDetail", [tenantUserGroupId: BRAVO])
        assertNoErrors(bravo)
        Map detail = bravo.result.tenantDetail as Map
        assertEquals(BRAVO, detail.tenantUserGroupId)
        assertEquals("Bravo Co", detail.label)
        assertEquals("Europe/London", detail.timeZone)
        assertEquals(true, detail.disabled)
        assertEquals(BRAVO_CREATED, detail.createdDate)
        assertEquals([[userId: TARGET_ID, username: TARGET_USERNAME,
                       permissionUserGroupId: "DARPAN_COMPANY_EDITOR", fromDate: T_ACTIVE]], detail.members)

        Map alpha = callAs(SUPER_USERNAME, "admin.TenantAdminServices.get#TenantDetail", [tenantUserGroupId: ALPHA])
        List<Map> members = (alpha.result.tenantDetail.members as List<Map>).sort { it.userId }
        assertEquals([PLAIN_ID, TARGET_ID], members*.userId, "the expired VIEW_ONLY row is not a member")
        assertEquals(["DARPAN_TENANT_ADMIN", "DARPAN_TENANT_USER"], members*.permissionUserGroupId)
        assertEquals([PLAIN_USERNAME, TARGET_USERNAME], members*.username)
        assertEquals(false, alpha.result.tenantDetail.disabled)

        Map charlie = callAs(SUPER_USERNAME, "admin.TenantAdminServices.get#TenantDetail", [tenantUserGroupId: CHARLIE])
        assertEquals([tenantUserGroupId: CHARLIE, label: CHARLIE, timeZone: null, goLiveDate: null, agentEnabled: false, disabled: false,
                      createdDate: null, members: []], charlie.result.tenantDetail,
                "no TenantSetting row: label falls back to the id, nothing else is invented")
    }

    @Test
    void tenantDetailRejectsUnknownAndNonTenantGroups() {
        Map unknown = callAs(SUPER_USERNAME, "admin.TenantAdminServices.get#TenantDetail", [tenantUserGroupId: "NOPE"])
        assertEquals(["Tenant NOPE was not found."], unknown.errors)
        assertNull(unknown.result?.tenantDetail)

        Map permissionGroup = callAs(SUPER_USERNAME, "admin.TenantAdminServices.get#TenantDetail",
                [tenantUserGroupId: "DARPAN_SUPER_ADMIN"])
        assertEquals(["Tenant DARPAN_SUPER_ADMIN was not found."], permissionGroup.errors)
        assertNull(permissionGroup.result?.tenantDetail)
    }

    @Test
    void tenantDetailIsRefusedForANonAdminAtTheFenceAndByTheGate() {
        assertFenceRefuses(PLAIN_USERNAME, "admin.TenantAdminServices.get#TenantDetail", [tenantUserGroupId: ALPHA])

        Map gated = callAs(PLAIN_USERNAME, "admin.TenantAdminServices.get#TenantDetail", [tenantUserGroupId: ALPHA], true)
        assertGateRefused(gated)
        assertNull(gated.result?.tenantDetail)
    }

    // ---------------------------------------------------------------- reads: users

    @Test
    void userDetailReturnsActiveMembershipsInEveryTenant() {
        Map out = callAs(SUPER_USERNAME, "admin.UserAdminServices.get#UserDetail", [userId: TARGET_ID])
        assertNoErrors(out)
        Map detail = out.result.userDetail as Map
        assertEquals(TARGET_ID, detail.userId)
        assertEquals(TARGET_USERNAME, detail.username)
        assertEquals("Target User", detail.userFullName)
        assertEquals("admsvc.target@example.com", detail.emailAddress)
        assertEquals(false, detail.disabled)
        assertEquals(false, detail.requirePasswordChange)
        assertEquals([[tenantUserGroupId: ALPHA, permissionUserGroupId: "DARPAN_TENANT_USER", fromDate: T_ACTIVE],
                      [tenantUserGroupId: BRAVO, permissionUserGroupId: "DARPAN_COMPANY_EDITOR", fromDate: T_ACTIVE]],
                (detail.memberships as List<Map>).sort { it.tenantUserGroupId },
                "both tenants, and not the expired VIEW_ONLY row")

        Map dormant = callAs(SUPER_USERNAME, "admin.UserAdminServices.get#UserDetail", [userId: DORMANT_ID])
        assertEquals(true, dormant.result.userDetail.disabled)
        assertEquals(true, dormant.result.userDetail.requirePasswordChange)
        assertEquals([], dormant.result.userDetail.memberships)
    }

    @Test
    void userDetailRejectsAnUnknownUser() {
        Map out = callAs(SUPER_USERNAME, "admin.UserAdminServices.get#UserDetail", [userId: "NOPE"])
        assertEquals(["User NOPE was not found."], out.errors)
        assertNull(out.result?.userDetail)
    }

    @Test
    void userDetailIsRefusedForANonAdminAtTheFenceAndByTheGate() {
        assertFenceRefuses(PLAIN_USERNAME, "admin.UserAdminServices.get#UserDetail", [userId: TARGET_ID])

        Map gated = callAs(PLAIN_USERNAME, "admin.UserAdminServices.get#UserDetail", [userId: TARGET_ID], true)
        assertGateRefused(gated)
        assertNull(gated.result?.userDetail)
    }

    @Test
    void userListSearchesEveryUserForASuperAdminAndIsRefusedOtherwise() {
        Map out = callAs(SUPER_USERNAME, "admin.UserAdminServices.get#UserList", [searchText: "ADMSVC.", pageSize: 3])
        assertNoErrors(out)
        assertEquals(["admsvc.dormant", PLAIN_USERNAME, SUPER_USERNAME], (out.result.users as List<Map>)*.username)
        assertEquals(4, out.result.totalCount)

        assertFenceRefuses(PLAIN_USERNAME, "admin.UserAdminServices.get#UserList", [:])
        Map gated = callAs(PLAIN_USERNAME, "admin.UserAdminServices.get#UserList", [:], true)
        assertGateRefused(gated)
        assertEquals([], gated.result.users)
        assertEquals(0, gated.result.totalCount)
    }

    // ---------------------------------------------------------------- writes: tenants

    @Test
    void updateTenantRenamesStoresTheZoneAndAudits() {
        Map out = callAs(SUPER_USERNAME, "admin.TenantAdminServices.update#Tenant",
                [tenantUserGroupId: BRAVO, label: "  Bravo Renamed  ", timeZone: " Asia/Kolkata "])

        assertNoErrors(out)
        assertEquals(true, out.result.ok)
        assertEquals("Bravo Renamed", find("moqui.security.UserGroup", [userGroupId: BRAVO]).description)
        def setting = find("darpan.auth.TenantSetting", [companyUserGroupId: BRAVO])
        assertEquals("Asia/Kolkata", setting.timeZone)
        assertEquals("Y", setting.disabled, "a rename must not touch the deactivated flag")
        assertAudit("admin.TenantAdminServices.update#Tenant", "Tenant", BRAVO, "Updated tenant ${BRAVO}.")
    }

    @Test
    void updateTenantWithOnlyALabelLeavesTheZoneAlone() {
        Map out = callAs(SUPER_USERNAME, "admin.TenantAdminServices.update#Tenant",
                [tenantUserGroupId: ALPHA, label: "Alpha Renamed"])

        assertEquals(true, out.result.ok)
        assertEquals("Alpha Renamed", find("moqui.security.UserGroup", [userGroupId: ALPHA]).description)
        assertEquals("America/Chicago", find("darpan.auth.TenantSetting", [companyUserGroupId: ALPHA]).timeZone)
    }

    // DAR-BE-145: Dolls Kill's go-live was handed over as "2026-10-20 21:05:23, PST time zone". A go-live with no offset
    // is that wall-clock time in the tenant's zone (here the zone set in the same call), not the server's.
    @Test
    void updateTenantReadsAGoLiveWithoutAnOffsetInTheTenantsZone() {
        Map out = callAs(SUPER_USERNAME, "admin.TenantAdminServices.update#Tenant",
                [tenantUserGroupId: ALPHA, timeZone: "America/Los_Angeles", goLiveDate: "2026-10-20 21:05:23"])

        assertNoErrors(out)
        assertEquals(true, out.result.ok)
        assertEquals(java.time.Instant.parse("2026-10-21T04:05:23Z"),
                (find("darpan.auth.TenantSetting", [companyUserGroupId: ALPHA]).goLiveDate as Timestamp).toInstant())
        assertEquals(java.time.Instant.parse("2026-10-21T04:05:23Z"), (callAs(SUPER_USERNAME,
                "admin.TenantAdminServices.get#TenantDetail", [tenantUserGroupId: ALPHA]).result.tenantDetail.goLiveDate as Timestamp).toInstant())
    }

    // DAR-BE-159: agentEnabled is a per-tenant switch; omitting it leaves it alone.
    @Test
    void updateTenantTurnsTheAgentOnAndOffAndLeavesItWhenOmitted() {
        Closure<Object> flag = { callAs(SUPER_USERNAME, "admin.TenantAdminServices.get#TenantDetail", [tenantUserGroupId: ALPHA]).result.tenantDetail.agentEnabled }
        assertEquals(false, flag())
        assertNoErrors(callAs(SUPER_USERNAME, "admin.TenantAdminServices.update#Tenant", [tenantUserGroupId: ALPHA, agentEnabled: true]))
        assertEquals(true, flag())
        assertEquals("Y", find("darpan.auth.TenantSetting", [companyUserGroupId: ALPHA]).agentEnabled)
        assertNoErrors(callAs(SUPER_USERNAME, "admin.TenantAdminServices.update#Tenant", [tenantUserGroupId: ALPHA, agentEnabled: false]))
        assertEquals(false, flag())
        assertEquals("N", find("darpan.auth.TenantSetting", [companyUserGroupId: ALPHA]).agentEnabled)
        assertNoErrors(callAs(SUPER_USERNAME, "admin.TenantAdminServices.update#Tenant", [tenantUserGroupId: ALPHA, label: "Alpha Again"]))
        assertEquals(false, flag(), "omitted: stays N")
    }

    @Test
    void updateTenantRejectsAnUnreadableGoLiveBeforeApplyingTheLabel() {
        Map out = callAs(SUPER_USERNAME, "admin.TenantAdminServices.update#Tenant",
                [tenantUserGroupId: ALPHA, label: "Alpha Renamed", goLiveDate: "20 Oct"])

        assertEquals(1, out.errors.size())
        assertTrue(out.errors[0].contains("20 Oct"), out.errors[0])
        assertEquals("Alpha Co", find("moqui.security.UserGroup", [userGroupId: ALPHA]).description)
        assertNull(find("darpan.auth.TenantSetting", [companyUserGroupId: ALPHA]).goLiveDate)
        assertNoAudit()
    }

    @Test
    void updateTenantRejectsAnInvalidZoneBeforeApplyingTheLabel() {
        // A rejected timezone must not leave a half-applied rename behind, and an unvalidated zone
        // is invisible downstream (the browser's Intl silently falls back to the viewer's zone).
        Map out = callAs(SUPER_USERNAME, "admin.TenantAdminServices.update#Tenant",
                [tenantUserGroupId: ALPHA, label: "Alpha Renamed", timeZone: "America/Chigago"])

        assertEquals(["Timezone is invalid."], out.errors)
        assertNotEquals(true, out.result?.ok)
        assertEquals("Alpha Co", find("moqui.security.UserGroup", [userGroupId: ALPHA]).description)
        assertEquals("America/Chicago", find("darpan.auth.TenantSetting", [companyUserGroupId: ALPHA]).timeZone)
        assertNoAudit()
    }

    @Test
    void updateTenantRejectsAnUnknownOrNonTenantGroup() {
        Map unknown = callAs(SUPER_USERNAME, "admin.TenantAdminServices.update#Tenant",
                [tenantUserGroupId: "NOPE", label: "x"])
        assertEquals(["Tenant NOPE was not found."], unknown.errors)
        assertNotEquals(true, unknown.result?.ok)

        Map group = callAs(SUPER_USERNAME, "admin.TenantAdminServices.update#Tenant",
                [tenantUserGroupId: "DARPAN_USER", label: "Hijacked"])
        assertEquals(["Tenant DARPAN_USER was not found."], group.errors)
        assertNotEquals("Hijacked", find("moqui.security.UserGroup", [userGroupId: "DARPAN_USER"]).description)
        assertNoAudit()
    }

    @Test
    void deactivateAndReactivateFlipTheFlagAndAuditEach() {
        Map off = callAs(SUPER_USERNAME, "admin.TenantAdminServices.deactivate#Tenant", [tenantUserGroupId: ALPHA])
        assertNoErrors(off)
        assertEquals(true, off.result.ok)
        assertEquals("Y", find("darpan.auth.TenantSetting", [companyUserGroupId: ALPHA]).disabled)
        assertAudit("admin.TenantAdminServices.deactivate#Tenant", "Tenant", ALPHA, "Deactivated tenant ${ALPHA}.")

        AuditLogTestSupport.wipe(ec)
        Map on = callAs(SUPER_USERNAME, "admin.TenantAdminServices.reactivate#Tenant", [tenantUserGroupId: ALPHA])
        assertEquals(true, on.result.ok)
        assertEquals("N", find("darpan.auth.TenantSetting", [companyUserGroupId: ALPHA]).disabled)
        assertAudit("admin.TenantAdminServices.reactivate#Tenant", "Tenant", ALPHA, "Reactivated tenant ${ALPHA}.")

        // A tenant with no TenantSetting row gets one (store#, not update#).
        Map charlie = callAs(SUPER_USERNAME, "admin.TenantAdminServices.deactivate#Tenant", [tenantUserGroupId: CHARLIE])
        assertEquals(true, charlie.result.ok)
        assertEquals("Y", find("darpan.auth.TenantSetting", [companyUserGroupId: CHARLIE]).disabled)
    }

    @Test
    void deactivateAnUnknownTenantWritesNothing() {
        Map out = callAs(SUPER_USERNAME, "admin.TenantAdminServices.deactivate#Tenant", [tenantUserGroupId: "NOPE"])
        assertEquals(["Tenant NOPE was not found."], out.errors)
        assertNotEquals(true, out.result?.ok)
        assertNull(find("darpan.auth.TenantSetting", [companyUserGroupId: "NOPE"]))
        assertNoAudit()
    }

    // ---------------------------------------------------------------- writes: users

    @Test
    void createUserGoesThroughTheFrameworkForcesAPasswordChangeAndJoinsDarpanUser() {
        String username = uniqueUsername()
        Map out = callAs(SUPER_USERNAME, "admin.UserAdminServices.create#UserAccount",
                [username: "  ${username}  ".toString(), userFullName: " New Person ",
                 emailAddress: "${username}@created.example.org".toString(), tempPassword: STRONG_PASSWORD])

        assertNoErrors(out)
        assertEquals(true, out.result.ok)
        String userId = out.result.userId
        assertNotNull(userId)
        def account = find("moqui.security.UserAccount", [userId: userId])
        assertEquals(username, account.username, "username is trimmed")
        assertEquals("New Person", account.userFullName)
        assertEquals("${username}@created.example.org".toString(), account.emailAddress)
        assertEquals("Y", account.requirePasswordChange)
        assertNotNull(account.currentPassword, "the framework stored a credential")
        assertNotEquals(STRONG_PASSWORD, account.currentPassword, "the credential is hashed by the framework, never stored raw")
        assertEquals(1, ec.entity.find("moqui.security.UserGroupMember").condition("userId", userId)
                .condition("userGroupId", "DARPAN_USER").disableAuthz().useCache(false).count(),
                "new users must join DARPAN_USER or no facade call works")
        assertAudit("admin.UserAdminServices.create#UserAccount", "User", userId, "Created user ${username}.")
    }

    // OC85 (standing rule: every user creation saves an email): password reset mails its code there, so an account made
    // without one is a support ticket later. Blank and missing are both refused, before the framework creates anything.
    @Test
    void createUserWithoutAnEmailIsRefusedAndCreatesNothing() {
        [null, "   "].each { String email ->
            String username = uniqueUsername()
            Map params = [username: username, tempPassword: STRONG_PASSWORD]
            if (email != null) params.emailAddress = email
            Map out = callAs(SUPER_USERNAME, "admin.UserAdminServices.create#UserAccount", params)

            assertTrue(out.hasError as boolean, "no email must be refused: ${out}")
            assertNotEquals(true, out.result?.ok)
            assertNull(out.result?.userId)
            assertEquals(0, ec.entity.find("moqui.security.UserAccount").condition("username", username)
                    .disableAuthz().useCache(false).count())
        }
        assertNoAudit()
    }

    @Test
    void createUserWithADuplicateUsernameWritesNoMembershipOrAudit() {
        Map out = callAs(SUPER_USERNAME, "admin.UserAdminServices.create#UserAccount",
                [username: TARGET_USERNAME, emailAddress: "dup.${uniqueUsername()}@created.example.org".toString(),
                 tempPassword: STRONG_PASSWORD])

        assertTrue(out.errors.any { it.contains("already in use") }, "framework refusal surfaces: ${out.errors}")
        assertNotEquals(true, out.result?.ok)
        assertNull(out.result?.userId)
        assertNoAudit()
    }

    @Test
    void createUserWithAWeakPasswordCreatesNothing() {
        String username = uniqueUsername()
        Map out = callAs(SUPER_USERNAME, "admin.UserAdminServices.create#UserAccount",
                [username: username, emailAddress: "${username}@created.example.org".toString(), tempPassword: "weak"])

        assertTrue(out.hasError)
        assertNotEquals(true, out.result?.ok)
        assertNull(out.result?.userId)
        assertEquals(0, ec.entity.find("moqui.security.UserAccount").condition("username", username)
                .disableAuthz().useCache(false).count())
        assertNoAudit()
    }

    @Test
    void updateUserWithOnlyAnEmailKeepsTheDisplayName() {
        // Entity-auto update applies EXPLICIT nulls, so an omitted name must never be sent as null.
        Map out = callAs(SUPER_USERNAME, "admin.UserAdminServices.update#UserAccount",
                [userId: TARGET_ID, emailAddress: "changed.target@example.com"])

        assertNoErrors(out)
        assertEquals(true, out.result.ok)
        def account = find("moqui.security.UserAccount", [userId: TARGET_ID])
        assertEquals("changed.target@example.com", account.emailAddress)
        assertEquals("Target User", account.userFullName)
        assertAudit("admin.UserAdminServices.update#UserAccount", "User", TARGET_ID, "Updated profile for user ${TARGET_ID}.")
    }

    @Test
    void updateUserWithNothingOrAnUnknownUserWritesNothing() {
        Map nothing = callAs(SUPER_USERNAME, "admin.UserAdminServices.update#UserAccount",
                [userId: TARGET_ID, userFullName: "   "])
        assertEquals(["Nothing to update."], nothing.errors)
        assertNotEquals(true, nothing.result?.ok)
        assertEquals("Target User", find("moqui.security.UserAccount", [userId: TARGET_ID]).userFullName)

        Map neither = callAs(SUPER_USERNAME, "admin.UserAdminServices.update#UserAccount", [userId: TARGET_ID])
        assertEquals(["Nothing to update."], neither.errors)
        assertNotEquals(true, neither.result?.ok)

        Map unknown = callAs(SUPER_USERNAME, "admin.UserAdminServices.update#UserAccount",
                [userId: "NOPE", userFullName: "Ghost"])
        assertEquals(["User NOPE was not found."], unknown.errors)
        assertNoAudit()
    }

    @Test
    void disablingYourselfIsRefusedBeforeAnyLookup() {
        Map out = callAs(SUPER_USERNAME, "admin.UserAdminServices.disable#UserAccount", [userId: SUPER_ID])
        assertEquals(["You cannot disable your own account."], out.errors)
        assertNotEquals(true, out.result?.ok)
        assertEquals("N", find("moqui.security.UserAccount", [userId: SUPER_ID]).disabled)
        assertNoAudit()
    }

    @Test
    void disableRevokesLiveLoginKeysAndEnableRestores() {
        loginKey("admsvc-key-1", TARGET_ID)
        loginKey("admsvc-key-2", TARGET_ID)
        loginKey("admsvc-key-other", PLAIN_ID)
        cliSignIns(TARGET_ID)
        cliSignIns(PLAIN_ID)

        Map off = callAs(SUPER_USERNAME, "admin.UserAdminServices.disable#UserAccount", [userId: TARGET_ID])
        assertNoErrors(off)
        assertEquals(true, off.result.ok)
        assertEquals("Y", find("moqui.security.UserAccount", [userId: TARGET_ID]).disabled)
        assertEquals(0, loginKeyCount(TARGET_ID), "live sessions must be revoked on disable")
        assertEquals(1, loginKeyCount(PLAIN_ID), "only the target's keys go")
        assertEverySignInEnded(TARGET_ID)
        assertSignInsUntouched(PLAIN_ID)
        assertAudit("admin.UserAdminServices.disable#UserAccount", "User", TARGET_ID, "Disabled user ${TARGET_ID}.")

        AuditLogTestSupport.wipe(ec)
        Map on = callAs(SUPER_USERNAME, "admin.UserAdminServices.enable#UserAccount", [userId: TARGET_ID])
        assertEquals(true, on.result.ok)
        assertEquals("N", find("moqui.security.UserAccount", [userId: TARGET_ID]).disabled)
        assertAudit("admin.UserAdminServices.enable#UserAccount", "User", TARGET_ID, "Enabled user ${TARGET_ID}.")
    }

    @Test
    void disableAnUnknownUserWritesNothing() {
        Map out = callAs(SUPER_USERNAME, "admin.UserAdminServices.disable#UserAccount", [userId: "NOPE"])
        assertEquals(["User NOPE was not found."], out.errors)
        assertNoAudit()
    }

    @Test
    void resetPasswordForcesAChangeAndRevokesLoginKeys() {
        loginKey("admsvc-key-3", TARGET_ID)
        cliSignIns(TARGET_ID)
        cliSignIns(PLAIN_ID)
        String before = find("moqui.security.UserAccount", [userId: TARGET_ID]).currentPassword

        Map out = callAs(SUPER_USERNAME, "admin.UserAdminServices.reset#Password",
                [userId: TARGET_ID, tempPassword: STRONG_PASSWORD])

        assertNoErrors(out)
        assertEquals(true, out.result.ok)
        def account = find("moqui.security.UserAccount", [userId: TARGET_ID])
        assertEquals("Y", account.requirePasswordChange)
        assertNotNull(account.currentPassword)
        assertNotEquals(before, account.currentPassword)
        assertNotEquals(STRONG_PASSWORD, account.currentPassword)
        assertEquals(0, loginKeyCount(TARGET_ID), "live sessions must be revoked on password reset")
        // OC-A: the admin answer to a compromised account ends the attacker's browser session (the stamp) and the CLI
        // sign-in they approved but had not collected (poll#CliLogin would otherwise mint a fresh key after the reset).
        assertEverySignInEnded(TARGET_ID)
        assertSignInsUntouched(PLAIN_ID)
        assertAudit("admin.UserAdminServices.reset#Password", "User", TARGET_ID,
                "Reset password for user ${TARGET_ID}; change forced at next login.")
    }

    @Test
    void resetPasswordStopsWhenTheFrameworkRefusesThePassword() {
        loginKey("admsvc-key-4", TARGET_ID)
        cliSignIns(TARGET_ID)

        Map out = callAs(SUPER_USERNAME, "admin.UserAdminServices.reset#Password",
                [userId: TARGET_ID, tempPassword: "weak"])

        assertTrue(out.hasError)
        assertNotEquals(true, out.result?.ok)
        assertEquals(1, loginKeyCount(TARGET_ID), "no login-key revocation when the framework password update failed")
        assertSignInsUntouched(TARGET_ID)
        assertEquals("N", find("moqui.security.UserAccount", [userId: TARGET_ID]).requirePasswordChange)
        assertNoAudit()
    }

    @Test
    void resetPasswordForAnUnknownUserWritesNothing() {
        Map out = callAs(SUPER_USERNAME, "admin.UserAdminServices.reset#Password",
                [userId: "NOPE", tempPassword: STRONG_PASSWORD])
        assertEquals(["User NOPE was not found."], out.errors)
        assertNoAudit()
    }

    // ---------------------------------------------------------------- writes: memberships

    @Test
    void addTenantMemberWritesBothEntitiesWithOneFromDateAndAudits() {
        Map out = callAs(SUPER_USERNAME, "admin.MembershipAdminServices.add#TenantMember",
                [userId: PLAIN_ID, tenantUserGroupId: DELTA, permissionUserGroupId: "DARPAN_COMPANY_EDITOR"])

        assertNoErrors(out)
        assertEquals(true, out.result.ok)
        List groupRows = activeGroupRows(PLAIN_ID, DELTA)
        List permissionRows = activePermissionRows(PLAIN_ID, DELTA)
        assertEquals(1, groupRows.size())
        assertEquals(1, permissionRows.size())
        assertEquals("DARPAN_COMPANY_EDITOR", permissionRows[0].permissionUserGroupId)
        assertEquals(groupRows[0].fromDate, permissionRows[0].fromDate,
                "both rows must share one fromDate so history stays joinable")
        assertAudit("admin.MembershipAdminServices.add#TenantMember", "Membership", "${DELTA}:${PLAIN_ID}",
                "Added ${PLAIN_ID} to ${DELTA} as DARPAN_COMPANY_EDITOR. Welcome email sent.")
        assertEquals(true, out.result.welcomeSent)
        assertEquals(false, out.result.welcomeWithPassword)
        assertEquals(1, sent.size(), "one welcome email per grant")
        assertEquals(AdminMembershipSupport.TEMPLATE_TENANT_WELCOME, sent[0].templateId)
        assertEquals("admsvc.plain@example.com", sent[0].to)
        assertEquals("Plain", sent[0].params.firstName)
        assertEquals(PLAIN_USERNAME, sent[0].params.username)
        assertEquals("beta lower", sent[0].params.tenantLabel)
        // OC186: the tenant the welcome serves, so its smile line's spend goes on that tenant's ledger.
        assertEquals(DELTA, sent[0].params.tenantUserGroupId)
        assertEquals("as an Editor", sent[0].params.rolePhrase)
        assertTrue(sent[0].params.containsKey("appBaseUrl"))
        // No temporary password is kept for this account, so none goes out, and the send keeps its EmailMessage copy.
        assertNull(sent[0].params.tempPassword)
        assertEquals(true, sent[0].keepCopy)
    }

    @Test
    void addTenantMemberSendsNoWelcomeWhenToldNotToOrWhenTheAccountCannotReceiveIt() {
        Map quiet = callAs(SUPER_USERNAME, "admin.MembershipAdminServices.add#TenantMember",
                [userId: PLAIN_ID, tenantUserGroupId: DELTA, permissionUserGroupId: "DARPAN_TENANT_USER", sendWelcome: false])
        assertNoErrors(quiet)
        assertEquals(true, quiet.result.ok)
        assertEquals(false, quiet.result.welcomeSent)
        assertAudit("admin.MembershipAdminServices.add#TenantMember", "Membership", "${DELTA}:${PLAIN_ID}",
                "Added ${PLAIN_ID} to ${DELTA} as DARPAN_TENANT_USER.")

        // A disabled account, and one with no email address, still get the grant; the email is skipped, not an error.
        upsert(ec, "moqui.security.UserAccount", [userId: TARGET_ID, emailAddress: null])
        Map dormant = callAs(SUPER_USERNAME, "admin.MembershipAdminServices.add#TenantMember",
                [userId: DORMANT_ID, tenantUserGroupId: DELTA, permissionUserGroupId: "DARPAN_COMPANY_VIEW_ONLY"])
        Map noEmail = callAs(SUPER_USERNAME, "admin.MembershipAdminServices.add#TenantMember",
                [userId: TARGET_ID, tenantUserGroupId: DELTA, permissionUserGroupId: "DARPAN_COMPANY_VIEW_ONLY"])
        [dormant, noEmail].each { Map out ->
            assertNoErrors(out)
            assertEquals(true, out.result.ok)
            assertEquals(false, out.result.welcomeSent)
        }
        assertEquals(1, activePermissionRows(DORMANT_ID, DELTA).size())
        assertEquals(1, activePermissionRows(TARGET_ID, DELTA).size())
        assertEquals([], sent)
    }

    @Test
    void sendTenantWelcomeEmailsACurrentMemberTheirRoleAndAuditsIt() {
        Map out = callAs(SUPER_USERNAME, "admin.MembershipAdminServices.send#TenantWelcome",
                [userId: TARGET_ID, tenantUserGroupId: ALPHA])

        assertNoErrors(out)
        assertEquals(true, out.result.ok)
        assertEquals("admsvc.target@example.com", out.result.emailAddress)
        assertEquals(1, sent.size())
        assertEquals(AdminMembershipSupport.TEMPLATE_TENANT_WELCOME, sent[0].templateId)
        assertEquals("admsvc.target@example.com", sent[0].to)
        assertEquals("Target", sent[0].params.firstName)
        assertEquals("Alpha Co", sent[0].params.tenantLabel)
        // The active role, not the expired view-only row ALPHA also holds for this user.
        assertEquals("as a User", sent[0].params.rolePhrase)
        assertAudit("admin.MembershipAdminServices.send#TenantWelcome", "Membership", "${ALPHA}:${TARGET_ID}",
                "Welcome email for ${ALPHA} sent to admsvc.target@example.com.")
        assertEquals(false, out.result.withPassword)
    }

    // ---------------------------------------------------------------- DAR-BE-325: the temporary password in the welcome

    @Test
    void aNewUsersWelcomeCarriesTheirTemporaryPasswordOnEveryGrantAndKeepsNoEmailCopy() {
        String userId = createdUser()
        def kept = find(TempPasswordKeeper.ENTITY, [userId: userId])
        assertEquals(STRONG_PASSWORD, kept.tempPassword, "kept for the welcome")
        assertTrue(ec.entity.getEntityDefinition(TempPasswordKeeper.ENTITY).getFieldInfo("tempPassword").encrypt,
                "kept encrypted, like every other secret")

        Map grant = callAs(SUPER_USERNAME, "admin.MembershipAdminServices.add#TenantMember",
                [userId: userId, tenantUserGroupId: DELTA, permissionUserGroupId: "DARPAN_TENANT_USER"])
        assertNoErrors(grant)
        assertEquals(true, grant.result.welcomeSent)
        assertEquals(true, grant.result.welcomeWithPassword)
        assertEquals(STRONG_PASSWORD, sent[0].params.tempPassword)
        assertEquals(false, sent[0].keepCopy, "a welcome with a password leaves no EmailMessage copy of its body")
        assertEquals("Added ${userId} to ${DELTA} as DARPAN_TENANT_USER. Welcome email sent, with the temporary password.".toString(),
                onlyAuditOf("admin.MembershipAdminServices.add#TenantMember").detailText)

        // A later grant (the admin app grants after create) and a re-send carry the same password.
        Map second = callAs(SUPER_USERNAME, "admin.MembershipAdminServices.add#TenantMember",
                [userId: userId, tenantUserGroupId: ALPHA, permissionUserGroupId: "DARPAN_COMPANY_EDITOR"])
        Map resend = callAs(SUPER_USERNAME, "admin.MembershipAdminServices.send#TenantWelcome",
                [userId: userId, tenantUserGroupId: DELTA])
        assertNoErrors(second)
        assertNoErrors(resend)
        assertEquals(true, resend.result.withPassword)
        assertEquals([STRONG_PASSWORD] * 3, sent*.params*.tempPassword)

        // An admin reset replaces it.
        String newTemp = "Reset#Pass5678"
        assertNoErrors(callAs(SUPER_USERNAME, "admin.UserAdminServices.reset#Password", [userId: userId, tempPassword: newTemp]))
        callAs(SUPER_USERNAME, "admin.MembershipAdminServices.send#TenantWelcome", [userId: userId, tenantUserGroupId: DELTA])
        assertEquals(newTemp, sent[-1].params.tempPassword)
    }

    @Test
    void theKeptPasswordGoesOnceTheUserSetsTheirOwn() {
        String userId = createdUser()
        assertNoErrors(callAs(SUPER_USERNAME, "admin.MembershipAdminServices.add#TenantMember",
                [userId: userId, tenantUserGroupId: DELTA, permissionUserGroupId: "DARPAN_TENANT_USER"]))

        // The person changes it themselves at first sign-in (not signed in yet: username + the temporary password). This,
        // the forgot-password reset and the signed-in change all end in update#PasswordInternal with requirePasswordChange
        // N; its commit fires the drop.
        String username = find("moqui.security.UserAccount", [userId: userId]).username
        assertNoErrors(callAs(null, "org.moqui.impl.UserServices.update#Password",
                [username: username, oldPassword: STRONG_PASSWORD, newPassword: "Mine#Own98765", newPasswordVerify: "Mine#Own98765"]))
        assertEquals("N", find("moqui.security.UserAccount", [userId: userId]).requirePasswordChange)
        assertNull(find(TempPasswordKeeper.ENTITY, [userId: userId]))

        Map resend = callAs(SUPER_USERNAME, "admin.MembershipAdminServices.send#TenantWelcome", [userId: userId, tenantUserGroupId: DELTA])
        assertNoErrors(resend)
        assertEquals(false, resend.result.withPassword)
        assertNull(sent[-1].params.tempPassword)
        assertEquals(true, sent[-1].keepCopy)
    }

    @Test
    void aKeptPasswordThatExpiredOrIsNoLongerTheAccountsIsNeverSentAndIsDeleted() {
        String userId = createdUser()
        assertNoErrors(callAs(SUPER_USERNAME, "admin.MembershipAdminServices.add#TenantMember",
                [userId: userId, tenantUserGroupId: DELTA, permissionUserGroupId: "DARPAN_TENANT_USER", sendWelcome: false]))

        // Past darpan.admin.tempPasswordKeepDays (default 7).
        upsert(ec, TempPasswordKeeper.ENTITY, [userId: userId, keptDate: new Timestamp(System.currentTimeMillis() - 8 * 86_400_000L)])
        callAs(SUPER_USERNAME, "admin.MembershipAdminServices.send#TenantWelcome", [userId: userId, tenantUserGroupId: DELTA])
        assertNull(sent[-1].params.tempPassword)
        assertNull(find(TempPasswordKeeper.ENTITY, [userId: userId]))

        // Still in the keep period, but some other path set a different temporary password (still Y, so no drop).
        upsert(ec, TempPasswordKeeper.ENTITY, [userId: userId, tempPassword: STRONG_PASSWORD, keptDate: ec.user.nowTimestamp])
        ec.service.sync().name("org.moqui.impl.UserServices.update#PasswordInternal").parameters([userId: userId,
                newPassword: "Other#Temp4321", newPasswordVerify: "Other#Temp4321", requirePasswordChange: "Y"]).call()
        assertNotNull(find(TempPasswordKeeper.ENTITY, [userId: userId]))
        callAs(SUPER_USERNAME, "admin.MembershipAdminServices.send#TenantWelcome", [userId: userId, tenantUserGroupId: DELTA])
        assertNull(sent[-1].params.tempPassword, "a password that no longer opens the account is never sent")
        assertNull(find(TempPasswordKeeper.ENTITY, [userId: userId]))
    }

    @Test
    void aMailServerThatRefusesTheWelcomeNeverFailsTheGrantButFailsTheResend() {
        mailAccepts = false
        Map grant = callAs(SUPER_USERNAME, "admin.MembershipAdminServices.add#TenantMember",
                [userId: PLAIN_ID, tenantUserGroupId: DELTA, permissionUserGroupId: "DARPAN_TENANT_USER"])
        assertNoErrors(grant)
        assertEquals(true, grant.result.ok)
        assertEquals(false, grant.result.welcomeSent)
        assertEquals(1, activePermissionRows(PLAIN_ID, DELTA).size(), "the grant stands")
        assertAudit("admin.MembershipAdminServices.add#TenantMember", "Membership", "${DELTA}:${PLAIN_ID}",
                "Added ${PLAIN_ID} to ${DELTA} as DARPAN_TENANT_USER. Welcome email not sent: the mail server did not accept it.")

        AuditLogTestSupport.wipe(ec)
        Map resend = callAs(SUPER_USERNAME, "admin.MembershipAdminServices.send#TenantWelcome",
                [userId: PLAIN_ID, tenantUserGroupId: DELTA])
        assertEquals(["The mail server did not accept the welcome email to admsvc.plain@example.com."], resend.errors)
        assertNoAudit()
    }

    @Test
    void welcomeTemplateComesFromSupportAndItsSubjectNamesTheTenant() {
        def template = ec.entity.find("moqui.basic.email.EmailTemplate")
                .condition("emailTemplateId", AdminMembershipSupport.TEMPLATE_TENANT_WELCOME).disableAuthz().one()
        assertNotNull(template)
        assertEquals("SYSTEM", template.emailServerId)
        assertEquals(System.getenv("DARPAN_MAIL_FROM") ?: "support@drpn.ai", ec.resource.expand((String) template.fromAddress, ""))
        assertEquals("Darpan", template.fromName)
        ec.context.push()
        try {
            ec.context.put("tenantLabel", "Malbon")
            assertEquals("You're in: Malbon on Darpan", ec.resource.expand((String) template.subject, ""))
        } finally {
            ec.context.pop()
        }
    }

    /**
     * OC186 (Aditi 2026-11-28 "Record it"): the welcome's smile line is asked for with the tenant the email serves, so the
     * writer charges that tenant; a person's own email (a reset code) is asked for with none, and stays log-only.
     */
    @Test
    void theWelcomeAsksForItsSmileWithItsTenantAndAResetCodeWithNone() {
        List<Map> asked = []
        darpan.email.EmailSmile.smileWriter = { def e, Map p -> asked << p; [line: null] }
        // EmailSmile keeps one line per key for the execution context; an earlier render in this class already holds them.
        ["DARPAN_TENANT_WELCOME", "DARPAN_PASSWORD_RESET_CODE"].each { ec.context.getSharedMap().remove("darpan.email.smile." + it) }
        try {
            renderBody("component://darpan/screen/email/TenantWelcome.xml", [firstName: "Rakshit", username: "rakshit.singh",
                    tenantLabel: "Malbon", tenantUserGroupId: "MALBON", rolePhrase: "as a User", appBaseUrl: "https://app.drpn.ai"])
            renderBody("component://darpan/screen/email/PasswordResetCode.xml", [firstName: "Rakshit", code: "12345678",
                    appBaseUrl: "https://app.drpn.ai", companyUserGroupId: "MALBON"])
        } finally {
            darpan.email.EmailSmile.resetForTests()
        }
        Map welcome = asked.find { it.key == "DARPAN_TENANT_WELCOME" }
        Map reset = asked.find { it.key == "DARPAN_PASSWORD_RESET_CODE" }
        assertEquals("MALBON", welcome?.companyUserGroupId, asked.toString())
        assertNotNull(reset, asked.toString())
        assertFalse(reset.containsKey("companyUserGroupId"), "a person's own email names no tenant, even with one in context: ${reset}")
    }

    @Test
    void welcomeBodySaysWhereToSignInAndFallsBackWhenTheInstanceHasNoAppAddress() {
        Map params = [firstName: "Rakshit", username: "rakshit.singh", tenantLabel: "Malbon", rolePhrase: "as a User"]
        String html = renderBody("component://darpan/screen/email/TenantWelcome.xml", params + [appBaseUrl: "https://app.drpn.ai"])
        assertTrue(html.contains("Hi Rakshit,"), html)
        assertTrue(html.contains("You now have access to Malbon on Darpan, as a User."), html)
        assertTrue(html.contains("Sign in at https://app.drpn.ai with your Darpan username (rakshit.singh)"), html)
        assertTrue(html.contains("Questions? Just reply."), html)
        assertFalse(html.contains("<b>") || html.contains("<strong>"), html)
        // DAR-BE-298: its only links are the user guide's
        (html =~ /href="([^"]*)"/).each { assertTrue((it[1] as String).startsWith("https://docs.drpn.ai/"), html) }

        String bare = renderBody("component://darpan/screen/email/TenantWelcome.xml", params + [appBaseUrl: null])
        assertTrue(bare.contains("Sign in to Darpan with your username (rakshit.singh)"), bare)
        assertFalse(bare.contains("Sign in at"), bare)
    }

    @Test
    void sendTenantWelcomeRefusalsSendNothing() {
        Map notMember = callAs(SUPER_USERNAME, "admin.MembershipAdminServices.send#TenantWelcome",
                [userId: PLAIN_ID, tenantUserGroupId: DELTA])
        assertEquals(["${PLAIN_ID} is not a member of ${DELTA}.".toString()], notMember.errors)
        assertNotEquals(true, notMember.result?.ok)

        Map deactivated = callAs(SUPER_USERNAME, "admin.MembershipAdminServices.send#TenantWelcome",
                [userId: TARGET_ID, tenantUserGroupId: BRAVO])
        assertEquals(["Tenant ${BRAVO} is deactivated.".toString()], deactivated.errors)

        Map notTenant = callAs(SUPER_USERNAME, "admin.MembershipAdminServices.send#TenantWelcome",
                [userId: TARGET_ID, tenantUserGroupId: "DARPAN_USER"])
        assertEquals(["Tenant DARPAN_USER was not found."], notTenant.errors)

        Map unknownUser = callAs(SUPER_USERNAME, "admin.MembershipAdminServices.send#TenantWelcome",
                [userId: "NOPE", tenantUserGroupId: ALPHA])
        assertEquals(["User NOPE was not found."], unknownUser.errors)

        Map disabled = callAs(SUPER_USERNAME, "admin.MembershipAdminServices.send#TenantWelcome",
                [userId: DORMANT_ID, tenantUserGroupId: ALPHA])
        assertEquals(["admsvc.dormant is disabled."], disabled.errors)

        upsert(ec, "moqui.security.UserAccount", [userId: PLAIN_ID, emailAddress: "  "])
        Map noEmail = callAs(SUPER_USERNAME, "admin.MembershipAdminServices.send#TenantWelcome",
                [userId: PLAIN_ID, tenantUserGroupId: ALPHA])
        assertEquals(["${PLAIN_USERNAME} has no email address.".toString()], noEmail.errors)

        assertEquals([], sent)
        assertNoAudit()
    }

    @Test
    void addTenantMemberRefusalsWriteNeitherEntity() {
        Map role = callAs(SUPER_USERNAME, "admin.MembershipAdminServices.add#TenantMember",
                [userId: PLAIN_ID, tenantUserGroupId: DELTA, permissionUserGroupId: "DARPAN_SUPER_ADMIN"])
        assertEquals(["DARPAN_SUPER_ADMIN is not an assignable tenant role."], role.errors)
        assertNotEquals(true, role.result?.ok)

        Map deactivated = callAs(SUPER_USERNAME, "admin.MembershipAdminServices.add#TenantMember",
                [userId: PLAIN_ID, tenantUserGroupId: BRAVO, permissionUserGroupId: "DARPAN_TENANT_USER"])
        assertEquals(["Tenant ${BRAVO} is deactivated.".toString()], deactivated.errors)

        Map notTenant = callAs(SUPER_USERNAME, "admin.MembershipAdminServices.add#TenantMember",
                [userId: PLAIN_ID, tenantUserGroupId: "DARPAN_USER", permissionUserGroupId: "DARPAN_TENANT_USER"])
        assertEquals(["Tenant DARPAN_USER was not found."], notTenant.errors)

        Map unknownUser = callAs(SUPER_USERNAME, "admin.MembershipAdminServices.add#TenantMember",
                [userId: "NOPE", tenantUserGroupId: DELTA, permissionUserGroupId: "DARPAN_TENANT_USER"])
        assertEquals(["User NOPE was not found."], unknownUser.errors)

        Map already = callAs(SUPER_USERNAME, "admin.MembershipAdminServices.add#TenantMember",
                [userId: TARGET_ID, tenantUserGroupId: ALPHA, permissionUserGroupId: "DARPAN_TENANT_ADMIN"])
        assertEquals(["${TARGET_ID} is already a member of ${ALPHA}.".toString()], already.errors)

        assertEquals([], activeGroupRows(PLAIN_ID, DELTA))
        assertEquals([], activePermissionRows(PLAIN_ID, DELTA))
        assertEquals([], activeGroupRows(PLAIN_ID, BRAVO))
        assertEquals(["DARPAN_TENANT_USER"], activePermissionRows(TARGET_ID, ALPHA)*.permissionUserGroupId)
        assertNoAudit()
        assertEquals([], sent)
    }

    @Test
    void updateTenantMemberRoleThruDatesTheOldRowAndOpensTheNewOne() {
        Map out = callAs(SUPER_USERNAME, "admin.MembershipAdminServices.update#TenantMemberRole",
                [userId: TARGET_ID, tenantUserGroupId: ALPHA, permissionUserGroupId: "DARPAN_TENANT_ADMIN"])

        assertNoErrors(out)
        assertEquals(true, out.result.ok)
        List<Map> rows = ec.entity.find("darpan.auth.TenantUserPermissionGroupMember")
                .condition("tenantUserGroupId", ALPHA).condition("userId", TARGET_ID)
                .disableAuthz().useCache(false).list()
        def closed = rows.find { it.permissionUserGroupId == "DARPAN_TENANT_USER" }
        def opened = rows.find { it.permissionUserGroupId == "DARPAN_TENANT_ADMIN" }
        assertNotNull(closed.thruDate, "old role row must be thruDated, not deleted")
        assertNull(opened.thruDate)
        assertEquals(closed.thruDate, opened.fromDate, "the new role starts exactly when the old one ends")
        assertEquals(T1, rows.find { it.permissionUserGroupId == "DARPAN_COMPANY_VIEW_ONLY" }.thruDate,
                "already-expired rows are left alone")
        assertEquals(1, activeGroupRows(TARGET_ID, ALPHA).size(), "the group membership itself is untouched")
        assertAudit("admin.MembershipAdminServices.update#TenantMemberRole", "Membership", "${ALPHA}:${TARGET_ID}",
                "Changed ${TARGET_ID} role in ${ALPHA} to DARPAN_TENANT_ADMIN.")
    }

    @Test
    void updateTenantMemberRoleRefusals() {
        Map role = callAs(SUPER_USERNAME, "admin.MembershipAdminServices.update#TenantMemberRole",
                [userId: TARGET_ID, tenantUserGroupId: ALPHA, permissionUserGroupId: "ADMIN"])
        assertEquals(["ADMIN is not an assignable tenant role."], role.errors)

        Map notMember = callAs(SUPER_USERNAME, "admin.MembershipAdminServices.update#TenantMemberRole",
                [userId: PLAIN_ID, tenantUserGroupId: DELTA, permissionUserGroupId: "DARPAN_TENANT_USER"])
        assertEquals(["${PLAIN_ID} is not a member of ${DELTA}.".toString()], notMember.errors)
        assertNotEquals(true, notMember.result?.ok)

        assertEquals(["DARPAN_TENANT_USER"], activePermissionRows(TARGET_ID, ALPHA)*.permissionUserGroupId)
        assertNoAudit()
    }

    @Test
    void removeTenantMemberThruDatesBothEntitiesAndClearsAStaleActiveTenant() {
        preference(TARGET_ID, ALPHA)
        Map out = callAs(SUPER_USERNAME, "admin.MembershipAdminServices.remove#TenantMember",
                [userId: TARGET_ID, tenantUserGroupId: ALPHA])

        assertNoErrors(out)
        assertEquals(true, out.result.ok)
        assertEquals([], activeGroupRows(TARGET_ID, ALPHA))
        assertEquals([], activePermissionRows(TARGET_ID, ALPHA))
        assertNotNull(ec.entity.find("moqui.security.UserGroupMember").condition("userGroupId", ALPHA)
                .condition("userId", TARGET_ID).disableAuthz().useCache(false).one()?.thruDate,
                "removal thruDates, never deletes")
        assertNull(find("moqui.security.UserPreference", [userId: TARGET_ID, preferenceKey: PREF_KEY]),
                "stale active-tenant preference must be cleared")
        assertEquals(1, activeGroupRows(TARGET_ID, BRAVO).size(), "other tenants are untouched")
        assertAudit("admin.MembershipAdminServices.remove#TenantMember", "Membership", "${ALPHA}:${TARGET_ID}",
                "Removed ${TARGET_ID} from ${ALPHA}.")
    }

    @Test
    void removeTenantMemberKeepsAnActiveTenantPreferencePointingElsewhere() {
        preference(TARGET_ID, BRAVO)
        Map out = callAs(SUPER_USERNAME, "admin.MembershipAdminServices.remove#TenantMember",
                [userId: TARGET_ID, tenantUserGroupId: ALPHA])

        assertEquals(true, out.result.ok)
        assertEquals(BRAVO, find("moqui.security.UserPreference", [userId: TARGET_ID, preferenceKey: PREF_KEY]).preferenceValue)
    }

    @Test
    void removeTenantMemberFromADeactivatedTenantStaysLegal() {
        Map out = callAs(SUPER_USERNAME, "admin.MembershipAdminServices.remove#TenantMember",
                [userId: TARGET_ID, tenantUserGroupId: BRAVO])

        assertNoErrors(out)
        assertEquals(true, out.result.ok)
        assertEquals([], activeGroupRows(TARGET_ID, BRAVO))
        assertEquals([], activePermissionRows(TARGET_ID, BRAVO))
    }

    @Test
    void removeTenantMemberRefusesANonMemberAndANonTenantGroup() {
        Map notMember = callAs(SUPER_USERNAME, "admin.MembershipAdminServices.remove#TenantMember",
                [userId: PLAIN_ID, tenantUserGroupId: DELTA])
        assertEquals(["${PLAIN_ID} is not a member of ${DELTA}.".toString()], notMember.errors)
        assertNotEquals(true, notMember.result?.ok)

        // Self-lockout regression: DARPAN_SUPER_ADMIN is a permission group, not a tenant, so the
        // caller must not be able to thruDate their own super-admin membership through this door.
        Map self = callAs(SUPER_USERNAME, "admin.MembershipAdminServices.remove#TenantMember",
                [userId: SUPER_ID, tenantUserGroupId: "DARPAN_SUPER_ADMIN"])
        assertEquals(["Tenant DARPAN_SUPER_ADMIN was not found."], self.errors)
        assertEquals(1, activeGroupRows(SUPER_ID, "DARPAN_SUPER_ADMIN").size())
        assertNoAudit()
    }

    // ---------------------------------------------------------------- every mutation, refused

    @Test
    void everyMutationIsRefusedForANonAdminAtTheFenceAndByTheGateWithNoWrites() {
        Map<String, Map> params = [
                "admin.TenantAdminServices.update#Tenant"          : [tenantUserGroupId: ALPHA, label: "Hijacked"],
                "admin.TenantAdminServices.deactivate#Tenant"      : [tenantUserGroupId: ALPHA],
                "admin.TenantAdminServices.reactivate#Tenant"      : [tenantUserGroupId: BRAVO],
                "admin.UserAdminServices.create#UserAccount"       : [username: "sneaky.user", emailAddress: "sneaky@example.com",
                                                                      tempPassword: STRONG_PASSWORD],
                "admin.UserAdminServices.update#UserAccount"       : [userId: TARGET_ID, userFullName: "Hijacked"],
                "admin.UserAdminServices.disable#UserAccount"      : [userId: TARGET_ID],
                "admin.UserAdminServices.enable#UserAccount"       : [userId: DORMANT_ID],
                "admin.UserAdminServices.reset#Password"           : [userId: TARGET_ID, tempPassword: STRONG_PASSWORD],
                "admin.MembershipAdminServices.add#TenantMember"   : [userId: PLAIN_ID, tenantUserGroupId: DELTA, permissionUserGroupId: "DARPAN_TENANT_ADMIN"],
                "admin.MembershipAdminServices.update#TenantMemberRole": [userId: TARGET_ID, tenantUserGroupId: ALPHA, permissionUserGroupId: "DARPAN_TENANT_ADMIN"],
                "admin.MembershipAdminServices.remove#TenantMember": [userId: TARGET_ID, tenantUserGroupId: ALPHA],
                "admin.MembershipAdminServices.send#TenantWelcome" : [userId: TARGET_ID, tenantUserGroupId: ALPHA],
                // A tenant admin promoting themselves, and demoting the only super admin.
                "admin.UserAdminServices.add#DarpanAdmin"          : [userId: PLAIN_ID, darpanRoleId: "DARPAN_SUPER_ADMIN"],
                "admin.UserAdminServices.remove#DarpanAdmin"       : [userId: SUPER_ID],
        ]
        assertEquals(MUTATIONS.toSet(), params.keySet())
        String passwordBefore = find("moqui.security.UserAccount", [userId: TARGET_ID]).currentPassword

        params.each { String serviceName, Map serviceParams ->
            // PLAIN is a DARPAN_TENANT_ADMIN of ALPHA: tenant admin is still not platform admin.
            assertFenceRefuses(PLAIN_USERNAME, serviceName, serviceParams)
            Map gated = callAs(PLAIN_USERNAME, serviceName, serviceParams, true)
            assertGateRefused(gated, serviceName)
            assertEquals(false, gated.result?.ok, "${serviceName} must report ok=false when refused")
        }

        assertEquals("Alpha Co", find("moqui.security.UserGroup", [userGroupId: ALPHA]).description)
        assertEquals("N", find("darpan.auth.TenantSetting", [companyUserGroupId: ALPHA]).disabled)
        assertEquals("Y", find("darpan.auth.TenantSetting", [companyUserGroupId: BRAVO]).disabled)
        assertEquals(0, ec.entity.find("moqui.security.UserAccount").condition("username", "sneaky.user")
                .disableAuthz().useCache(false).count())
        def target = find("moqui.security.UserAccount", [userId: TARGET_ID])
        assertEquals("Target User", target.userFullName)
        assertEquals("N", target.disabled)
        assertEquals(passwordBefore, target.currentPassword)
        assertEquals("Y", find("moqui.security.UserAccount", [userId: DORMANT_ID]).disabled)
        assertEquals([], activeGroupRows(PLAIN_ID, DELTA))
        assertEquals(["DARPAN_TENANT_USER"], activePermissionRows(TARGET_ID, ALPHA)*.permissionUserGroupId)
        assertEquals(1, activeGroupRows(TARGET_ID, ALPHA).size())
        assertEquals([], activeGroupRows(PLAIN_ID, "DARPAN_SUPER_ADMIN"))
        assertEquals(1, activeGroupRows(SUPER_ID, "DARPAN_SUPER_ADMIN").size())
        assertNoAudit()
        assertEquals([], sent, "a refused call sends no email")
    }

    // ---------------------------------------------------------------- Darpan admins (DAR-ADMIN-011)

    @Test
    void userDetailAndListShowDarpanRolesInForceOnly() {
        groupMember("DARPAN_ADMIN", TARGET_ID, T0, T1)      // ended: not a role any more
        groupMember("ADMIN", DORMANT_ID, T0, null)          // Moqui built-in: shown, read-only
        groupMember("DARPAN_ADMIN", DORMANT_ID, T0, null)

        Map superDetail = callAs(SUPER_USERNAME, "admin.UserAdminServices.get#UserDetail", [userId: SUPER_ID])
        assertNoErrors(superDetail)
        assertEquals(["DARPAN_SUPER_ADMIN"], superDetail.result.userDetail.darpanRoles)
        assertEquals([], superDetail.result.userDetail.memberships,
                "the super admin has no tenant rows (ALPHA expired): the roles are what shows their access")
        assertEquals([], callAs(SUPER_USERNAME, "admin.UserAdminServices.get#UserDetail", [userId: TARGET_ID])
                .result.userDetail.darpanRoles, "an ended DARPAN_ADMIN row is not a role")
        assertEquals(["ADMIN", "DARPAN_ADMIN"], callAs(SUPER_USERNAME, "admin.UserAdminServices.get#UserDetail",
                [userId: DORMANT_ID]).result.userDetail.darpanRoles, "fixed order: ADMIN, DARPAN_SUPER_ADMIN, DARPAN_ADMIN")

        Map all = callAs(SUPER_USERNAME, "admin.UserAdminServices.get#UserList", [searchText: "admsvc."])
        assertEquals([["ADMIN", "DARPAN_ADMIN"], [], ["DARPAN_SUPER_ADMIN"], []],
                (all.result.users as List<Map>)*.darpanRoles, "dormant, plain, super, target")

        Map admins = callAs(SUPER_USERNAME, "admin.UserAdminServices.get#UserList", [searchText: "admsvc.", darpanAdminsOnly: true])
        assertNoErrors(admins)
        assertEquals(["admsvc.dormant", SUPER_USERNAME], (admins.result.users as List<Map>)*.username)
        assertEquals(2, admins.result.totalCount, "the count is of the filtered set")
    }

    @Test
    void addDarpanAdminStartsTheRoleOnceAndAuditsIt() {
        Map out = callAs(SUPER_USERNAME, "admin.UserAdminServices.add#DarpanAdmin", [userId: TARGET_ID])
        assertNoErrors(out)
        assertEquals([ok: true, changed: true], out.result.subMap(["ok", "changed"]))
        assertEquals(1, activeGroupRows(TARGET_ID, "DARPAN_ADMIN").size(), "DARPAN_ADMIN is the default role")
        assertAudit("admin.UserAdminServices.add#DarpanAdmin", "User", TARGET_ID, "Added ${TARGET_ID} to DARPAN_ADMIN.")

        Map again = callAs(SUPER_USERNAME, "admin.UserAdminServices.add#DarpanAdmin", [userId: TARGET_ID])
        assertNoErrors(again)
        assertEquals([ok: true, changed: false], again.result.subMap(["ok", "changed"]))
        assertEquals(1, activeGroupRows(TARGET_ID, "DARPAN_ADMIN").size(), "no second row")
        assertEquals(1L, ec.entity.find("darpan.admin.AdminAuditLog").disableAuthz().useCache(false).count(), "no second audit row")

        Map superOut = callAs(SUPER_USERNAME, "admin.UserAdminServices.add#DarpanAdmin", [userId: TARGET_ID, darpanRoleId: "DARPAN_SUPER_ADMIN"])
        assertEquals(true, superOut.result.changed)
        assertEquals(["DARPAN_SUPER_ADMIN", "DARPAN_ADMIN"], callAs(SUPER_USERNAME, "admin.UserAdminServices.get#UserDetail",
                [userId: TARGET_ID]).result.userDetail.darpanRoles)
    }

    @Test
    void addDarpanAdminRefusesMoquiAdminATenantRoleAndAnUnknownUser() {
        ["ADMIN", "DARPAN_TENANT_ADMIN"].each { String roleId ->
            Map out = callAs(SUPER_USERNAME, "admin.UserAdminServices.add#DarpanAdmin", [userId: TARGET_ID, darpanRoleId: roleId])
            assertEquals(["${roleId} is not a Darpan admin role (DARPAN_ADMIN or DARPAN_SUPER_ADMIN).".toString()], out.errors)
            assertEquals(false, out.result.ok)
        }
        Map unknown = callAs(SUPER_USERNAME, "admin.UserAdminServices.add#DarpanAdmin", [userId: "NOPE"])
        assertEquals(["User NOPE was not found."], unknown.errors)
        assertEquals([], activeGroupRows(TARGET_ID, "ADMIN"))
        assertEquals(0L, ec.entity.find("moqui.security.UserGroupMember").condition("userId", "NOPE").disableAuthz().useCache(false).count())
        assertNoAudit()
    }

    @Test
    void removeDarpanAdminEndsTheRoleKeepsHistoryAndIsIdempotent() {
        groupMember("DARPAN_ADMIN", TARGET_ID, T0, null)
        groupMember("DARPAN_SUPER_ADMIN", TARGET_ID, T0, null)

        Map one = callAs(SUPER_USERNAME, "admin.UserAdminServices.remove#DarpanAdmin", [userId: TARGET_ID, darpanRoleId: "DARPAN_ADMIN"])
        assertNoErrors(one)
        assertEquals([ok: true, changed: true], one.result.subMap(["ok", "changed"]))
        assertEquals([], activeGroupRows(TARGET_ID, "DARPAN_ADMIN"))
        assertNotNull(find("moqui.security.UserGroupMember", [userGroupId: "DARPAN_ADMIN", userId: TARGET_ID, fromDate: T0]).thruDate,
                "the row is ended, not deleted")
        assertEquals(1, activeGroupRows(TARGET_ID, "DARPAN_SUPER_ADMIN").size(), "only the named role ends")
        assertAudit("admin.UserAdminServices.remove#DarpanAdmin", "User", TARGET_ID, "Removed ${TARGET_ID} from DARPAN_ADMIN.")

        Map both = callAs(SUPER_USERNAME, "admin.UserAdminServices.remove#DarpanAdmin", [userId: TARGET_ID])
        assertEquals(true, both.result.changed, "no role named: every settable role still in force ends")
        assertEquals([], activeGroupRows(TARGET_ID, "DARPAN_SUPER_ADMIN"))

        Map again = callAs(SUPER_USERNAME, "admin.UserAdminServices.remove#DarpanAdmin", [userId: TARGET_ID])
        assertNoErrors(again)
        assertEquals([ok: true, changed: false], again.result.subMap(["ok", "changed"]))
    }

    @Test
    void removeDarpanAdminRefusesYourOwnSuperAdminSoOneIsAlwaysLeft() {
        groupMember("DARPAN_ADMIN", SUPER_ID, T0, null)
        groupMember("ADMIN", TARGET_ID, T0, null)

        // SUPER is the only DARPAN_SUPER_ADMIN: refusing self-removal is what keeps one.
        ["DARPAN_SUPER_ADMIN", null].each { String roleId ->
            Map out = callAs(SUPER_USERNAME, "admin.UserAdminServices.remove#DarpanAdmin",
                    roleId ? [userId: SUPER_ID, darpanRoleId: roleId] : [userId: SUPER_ID])
            assertEquals(["You cannot remove your own super admin."], out.errors)
            assertEquals(false, out.result.ok)
        }
        assertEquals(1, activeGroupRows(SUPER_ID, "DARPAN_SUPER_ADMIN").size())
        assertEquals(1, activeGroupRows(SUPER_ID, "DARPAN_ADMIN").size(), "a refused call ends nothing, not even the other role")
        assertNoAudit()

        Map ownDarpanAdmin = callAs(SUPER_USERNAME, "admin.UserAdminServices.remove#DarpanAdmin", [userId: SUPER_ID, darpanRoleId: "DARPAN_ADMIN"])
        assertNoErrors(ownDarpanAdmin)
        assertEquals(true, ownDarpanAdmin.result.changed, "your own DARPAN_ADMIN may go: you stay a super admin")

        Map moquiAdmin = callAs(SUPER_USERNAME, "admin.UserAdminServices.remove#DarpanAdmin", [userId: TARGET_ID, darpanRoleId: "ADMIN"])
        assertEquals(["ADMIN is not a Darpan admin role (DARPAN_ADMIN or DARPAN_SUPER_ADMIN)."], moquiAdmin.errors)
        assertEquals(1, activeGroupRows(TARGET_ID, "ADMIN").size(), "Moqui ADMIN is never ended here")
    }

    @Test
    void theAuditTrailCannotBeChangedOrRemovedThroughTheApp() {
        // DAR-BE-265: AdminAuditLog and DataExportLog are create-only. Every write path short of raw SQL is refused, for a
        // super admin too, so the app cannot rewrite its own trail.
        Map out = callAs(SUPER_USERNAME, "admin.TenantAdminServices.update#Tenant", [tenantUserGroupId: ALPHA, label: "Alpha Renamed"])
        assertNoErrors(out)
        List rows = ec.entity.find("darpan.admin.AdminAuditLog").disableAuthz().useCache(false).list()
        assertEquals(1, rows.size())
        def row = rows[0]
        String id = row.adminAuditLogId
        ec.entity.makeValue("darpan.admin.DataExportLog").setAll([userId: SUPER_ID, resourceType: "GENERATED_OUTPUT", resourceRef: "r.json",
                serviceName: "test", eventTimestamp: ec.user.nowTimestamp]).setSequencedIdPrimary().create()

        assertThrows(EntityException) { row.set("detailText", "rewritten"); row.update() }
        assertThrows(EntityException) { row.delete() }
        assertThrows(EntityException) { ec.entity.find("darpan.admin.AdminAuditLog").disableAuthz().updateAll([detailText: "x"]) }
        assertThrows(EntityException) { ec.entity.find("darpan.admin.AdminAuditLog").disableAuthz().deleteAll() }
        assertThrows(EntityException) { ec.entity.find("darpan.admin.DataExportLog").disableAuthz().deleteAll() }
        ["update", "delete", "store"].each { String verb ->
            Map svc = callAs(SUPER_USERNAME, "${verb}#darpan.admin.AdminAuditLog".toString(),
                    [adminAuditLogId: id, detailText: "rewritten"], true)
            assertTrue(svc.hasError as boolean || svc.failure != null, "${verb}# must be refused: ${svc}")
        }
        assertEquals("Updated tenant ${ALPHA}.".toString(), ec.entity.find("darpan.admin.AdminAuditLog").condition("adminAuditLogId", id)
                .disableAuthz().useCache(false).one().detailText, "the row is unchanged")
        assertEquals(1L, ec.entity.find("darpan.admin.DataExportLog").disableAuthz().useCache(false).count())
        AuditLogTestSupport.wipe(ec, "darpan.admin.DataExportLog")
    }

    // ---------------------------------------------------------------- harness

    /**
     * Runs one service call on a fresh thread (fresh ExecutionContext: authz ON, empty artifact
     * stack) as the named user. bypassFence=true skips only the artifact fence, so the in-service
     * gate is what decides.
     */
    private Map callAs(String username, String serviceName, Map params, boolean bypassFence = false) {
        Map holder = [:]
        Thread worker = new Thread({
            ExecutionContext tec = Moqui.getExecutionContextFactory().getExecutionContext()
            try {
                // username null = nobody signed in (the first-sign-in password change form).
                if (username != null) {
                    if (!tec.user.internalLoginUser(username)) throw new IllegalStateException("could not log in ${username}")
                    assertTrue(tec.user.username == username)
                }
                def call = tec.service.sync().name(serviceName).parameters(new LinkedHashMap(params))
                if (bypassFence) call.disableAuthz()
                try {
                    holder.result = call.call()
                } catch (ArtifactAuthorizationException e) {
                    holder.authzDenied = e
                }
                holder.errors = new ArrayList<String>(tec.message.errors)
                holder.validationErrors = tec.message.validationErrors*.message
                holder.hasError = tec.message.hasError()
            } catch (Throwable t) {
                holder.failure = t
            } finally {
                tec.destroy()
            }
        } as Runnable, "admin-smoke-${username}")
        worker.start()
        worker.join()
        if (holder.failure) throw (Throwable) holder.failure
        return holder
    }

    private void assertFenceRefuses(String username, String serviceName, Map params) {
        Map out = callAs(username, serviceName, params)
        assertNotNull(out.authzDenied, "${serviceName} must be refused by the DARPAN_ADMIN_API fence for ${username}; got ${out}")
    }

    private static void assertGateRefused(Map out, String serviceName = "") {
        assertNull(out.authzDenied, "fence was bypassed, so the gate must be what refused ${serviceName}")
        assertEquals([GATE_MESSAGE], out.errors, "${serviceName} must be refused by the super-admin gate first")
    }

    private static void assertNoErrors(Map out) {
        assertNull(out.authzDenied, "unexpected authz denial: ${out.authzDenied}")
        assertFalse(out.hasError as boolean, "unexpected errors: ${out.errors} ${out.validationErrors}")
    }

    private void assertAudit(String serviceName, String targetType, Object targetId, Object detailText) {
        List<Map> rows = ec.entity.find("darpan.admin.AdminAuditLog").disableAuthz().useCache(false).list()
        assertEquals(1, rows.size(), "exactly one audit row expected, got ${rows}")
        Map row = rows[0]
        assertEquals(SUPER_ID, row.adminUserId)
        assertEquals(serviceName, row.serviceName)
        assertEquals(targetType, row.targetType)
        assertEquals(targetId.toString(), row.targetId)
        assertEquals(detailText.toString(), row.detailText)
        assertNotNull(row.auditTimestamp)
    }

    /** A new user made through the admin service with STRONG_PASSWORD as the temporary password. */
    private String createdUser() {
        String username = uniqueUsername()
        Map out = callAs(SUPER_USERNAME, "admin.UserAdminServices.create#UserAccount",
                [username: username, userFullName: "Kept Person", emailAddress: "${username}@created.example.org".toString(),
                 tempPassword: STRONG_PASSWORD])
        assertNoErrors(out)
        return (String) out.result.userId
    }

    private Map onlyAuditOf(String serviceName) {
        List rows = ec.entity.find("darpan.admin.AdminAuditLog").condition("serviceName", serviceName).disableAuthz().useCache(false).list()
        assertEquals(1, rows.size(), "one ${serviceName} audit row expected, got ${rows}")
        return rows[0]
    }

    private void assertNoAudit() {
        assertEquals(0, ec.entity.find("darpan.admin.AdminAuditLog").disableAuthz().useCache(false).count())
    }

    private def find(String entityName, Map pk) {
        return ec.entity.find(entityName).condition(pk).disableAuthz().useCache(false).one()
    }

    private List<Map> activeGroupRows(String userId, String groupId) {
        return ec.entity.find("moqui.security.UserGroupMember").condition("userGroupId", groupId)
                .condition("userId", userId).condition("thruDate", null).disableAuthz().useCache(false).list()
    }

    private List<Map> activePermissionRows(String userId, String tenantId) {
        return ec.entity.find("darpan.auth.TenantUserPermissionGroupMember").condition("tenantUserGroupId", tenantId)
                .condition("userId", userId).condition("thruDate", null).disableAuthz().useCache(false).list()
    }

    private long loginKeyCount(String userId) {
        return ec.entity.find("moqui.security.UserLoginKey").condition("userId", userId).disableAuthz().useCache(false).count()
    }

    private String uniqueUsername() {
        // Deliberately NOT admsvc.*: created users persist across tests and must not join the list search.
        return "newuser.${System.currentTimeMillis()}.${uniqueCounter++}".toString()
    }

    private String renderBody(String location, Map params) {
        ec.context.push()
        try {
            ec.context.putAll(params)
            return ec.screen.makeRender().rootScreen(location).webappName("webroot").renderMode("html").render()
        } finally {
            ec.context.pop()
        }
    }

    private void create(String entityName, Map fields) {
        ec.entity.makeValue(entityName).setAll(fields).create()
    }

    private void groupMember(String groupId, String userId, Timestamp fromDate, Timestamp thruDate) {
        create("moqui.security.UserGroupMember", [userGroupId: groupId, userId: userId, fromDate: fromDate, thruDate: thruDate])
    }

    private void permissionMember(String tenantId, String userId, String roleId, Timestamp fromDate, Timestamp thruDate) {
        create("darpan.auth.TenantUserPermissionGroupMember", [tenantUserGroupId: tenantId, userId: userId,
                permissionUserGroupId: roleId, fromDate: fromDate, thruDate: thruDate])
    }

    private void preference(String userId, String tenantId) {
        upsert(ec, "moqui.security.UserPreference", [userId: userId, preferenceKey: PREF_KEY, preferenceValue: tenantId])
    }

    /** A live CLI session and a CLI sign-in the person approved that their CLI has not collected yet. */
    private void cliSignIns(String userId) {
        Timestamp now = new Timestamp(System.currentTimeMillis())
        create("darpan.auth.CliSession", [loginKeyHash: "admsvc-cli-${userId}".toString(), userId: userId, createdDate: T0])
        create("darpan.auth.CliDeviceCode", [deviceCodeHash: "admsvc-dev-${userId}".toString(), userCode: "ADM-${userId}".toString(),
                statusId: "APPROVED", approvedByUserId: userId, createdDate: now, expiresDate: new Timestamp(now.time + 600_000L)])
    }

    /** TwoFactorSupport.endSessions' three marks: the stamp that ends a key-less web session, the CLI session revoked,
     *  the approved device row denied (so poll#CliLogin answers DENIED, never a fresh key). */
    private void assertEverySignInEnded(String userId) {
        assertNotNull(find("moqui.security.UserAccount", [userId: userId]).sessionsEndedDate, "the person's web sessions end")
        assertNotNull(find("darpan.auth.CliSession", [loginKeyHash: "admsvc-cli-${userId}".toString()]).revokedDate, "CLI session revoked")
        assertEquals("DENIED", find("darpan.auth.CliDeviceCode", [deviceCodeHash: "admsvc-dev-${userId}".toString()]).statusId,
                "an approved, uncollected CLI sign-in is denied")
    }

    private void assertSignInsUntouched(String userId) {
        assertNull(find("moqui.security.UserAccount", [userId: userId]).sessionsEndedDate)
        assertNull(find("darpan.auth.CliSession", [loginKeyHash: "admsvc-cli-${userId}".toString()]).revokedDate)
        assertEquals("APPROVED", find("darpan.auth.CliDeviceCode", [deviceCodeHash: "admsvc-dev-${userId}".toString()]).statusId)
    }

    private void loginKey(String key, String userId) {
        create("moqui.security.UserLoginKey", [loginKey: key, userId: userId, fromDate: T0,
                thruDate: new Timestamp(System.currentTimeMillis() + 3_600_000L)])
    }
}
