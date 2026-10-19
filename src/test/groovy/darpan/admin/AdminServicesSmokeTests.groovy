package darpan.admin

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.Moqui
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

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
    ]

    private int uniqueCounter = 0

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
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void resetFixtures() {
        // initMoqui leaves the main thread with authz disabled, so these writes are unrestricted.
        ["darpan.auth.TenantSetting", "darpan.auth.TenantUserPermissionGroupMember",
         "darpan.admin.AdminAuditLog", "moqui.security.UserPreference", "moqui.security.UserLoginKey",
         "moqui.security.UserGroupMember"].each { String entityName ->
            ec.entity.find(entityName).disableAuthz().deleteAll()
        }

        upsert("moqui.security.UserAccount", [userId: SUPER_ID, username: SUPER_USERNAME,
                userFullName: "Admin Super", emailAddress: "admsvc.super@example.com", disabled: "N", requirePasswordChange: "N"])
        upsert("moqui.security.UserAccount", [userId: PLAIN_ID, username: PLAIN_USERNAME,
                userFullName: "Plain Tenant Admin", emailAddress: "admsvc.plain@example.com", disabled: "N", requirePasswordChange: "N"])
        upsert("moqui.security.UserAccount", [userId: TARGET_ID, username: TARGET_USERNAME,
                userFullName: "Target User", emailAddress: "admsvc.target@example.com", disabled: "N", requirePasswordChange: "N"])
        upsert("moqui.security.UserAccount", [userId: DORMANT_ID, username: "admsvc.dormant",
                userFullName: "Dormant User", emailAddress: "admsvc.dormant@example.com", disabled: "Y", requirePasswordChange: "Y"])

        upsert("moqui.security.UserGroup", [userGroupId: ALPHA, description: "Alpha Co",
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        upsert("moqui.security.UserGroup", [userGroupId: BRAVO, description: "Bravo Co",
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        // No description and no TenantSetting: label falls back to the id, disabled reads false.
        upsert("moqui.security.UserGroup", [userGroupId: CHARLIE, description: null,
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        // Lower-case label: a case-insensitive sort would put it before "Bravo Co".
        upsert("moqui.security.UserGroup", [userGroupId: DELTA, description: "beta lower",
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
        assertEquals([tenantUserGroupId: CHARLIE, label: CHARLIE, timeZone: null, disabled: false,
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

        ec.entity.find("darpan.admin.AdminAuditLog").disableAuthz().deleteAll()
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

    @Test
    void createUserWithADuplicateUsernameWritesNoMembershipOrAudit() {
        Map out = callAs(SUPER_USERNAME, "admin.UserAdminServices.create#UserAccount",
                [username: TARGET_USERNAME, tempPassword: STRONG_PASSWORD])

        assertTrue(out.errors.any { it.contains("already in use") }, "framework refusal surfaces: ${out.errors}")
        assertNotEquals(true, out.result?.ok)
        assertNull(out.result?.userId)
        assertNoAudit()
    }

    @Test
    void createUserWithAWeakPasswordCreatesNothing() {
        String username = uniqueUsername()
        Map out = callAs(SUPER_USERNAME, "admin.UserAdminServices.create#UserAccount",
                [username: username, tempPassword: "weak"])

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

        Map off = callAs(SUPER_USERNAME, "admin.UserAdminServices.disable#UserAccount", [userId: TARGET_ID])
        assertNoErrors(off)
        assertEquals(true, off.result.ok)
        assertEquals("Y", find("moqui.security.UserAccount", [userId: TARGET_ID]).disabled)
        assertEquals(0, loginKeyCount(TARGET_ID), "live sessions must be revoked on disable")
        assertEquals(1, loginKeyCount(PLAIN_ID), "only the target's keys go")
        assertAudit("admin.UserAdminServices.disable#UserAccount", "User", TARGET_ID, "Disabled user ${TARGET_ID}.")

        ec.entity.find("darpan.admin.AdminAuditLog").disableAuthz().deleteAll()
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
        assertAudit("admin.UserAdminServices.reset#Password", "User", TARGET_ID,
                "Reset password for user ${TARGET_ID}; change forced at next login.")
    }

    @Test
    void resetPasswordStopsWhenTheFrameworkRefusesThePassword() {
        loginKey("admsvc-key-4", TARGET_ID)

        Map out = callAs(SUPER_USERNAME, "admin.UserAdminServices.reset#Password",
                [userId: TARGET_ID, tempPassword: "weak"])

        assertTrue(out.hasError)
        assertNotEquals(true, out.result?.ok)
        assertEquals(1, loginKeyCount(TARGET_ID), "no login-key revocation when the framework password update failed")
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
                "Added ${PLAIN_ID} to ${DELTA} as DARPAN_COMPANY_EDITOR.")
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
                "admin.UserAdminServices.create#UserAccount"       : [username: "sneaky.user", tempPassword: STRONG_PASSWORD],
                "admin.UserAdminServices.update#UserAccount"       : [userId: TARGET_ID, userFullName: "Hijacked"],
                "admin.UserAdminServices.disable#UserAccount"      : [userId: TARGET_ID],
                "admin.UserAdminServices.enable#UserAccount"       : [userId: DORMANT_ID],
                "admin.UserAdminServices.reset#Password"           : [userId: TARGET_ID, tempPassword: STRONG_PASSWORD],
                "admin.MembershipAdminServices.add#TenantMember"   : [userId: PLAIN_ID, tenantUserGroupId: DELTA, permissionUserGroupId: "DARPAN_TENANT_ADMIN"],
                "admin.MembershipAdminServices.update#TenantMemberRole": [userId: TARGET_ID, tenantUserGroupId: ALPHA, permissionUserGroupId: "DARPAN_TENANT_ADMIN"],
                "admin.MembershipAdminServices.remove#TenantMember": [userId: TARGET_ID, tenantUserGroupId: ALPHA],
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
        assertNoAudit()
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
                if (!tec.user.internalLoginUser(username)) throw new IllegalStateException("could not log in ${username}")
                assertTrue(tec.user.username == username)
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

    private void upsert(String entityName, Map fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
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
        upsert("moqui.security.UserPreference", [userId: userId, preferenceKey: PREF_KEY, preferenceValue: tenantId])
    }

    private void loginKey(String key, String userId) {
        create("moqui.security.UserLoginKey", [loginKey: key, userId: userId, fromDate: T0,
                thruDate: new Timestamp(System.currentTimeMillis() + 3_600_000L)])
    }
}
