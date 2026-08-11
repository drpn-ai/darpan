package darpan.facade.common

import org.junit.jupiter.api.Test

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-005 — the two-sided tenant-admin gate on config sharing.
 *
 * <p>These services sit behind the {@code facade\..*} artifact fence, which every authenticated
 * Darpan user satisfies. The checks proven here are the ONLY thing preventing an ordinary tenant
 * user from widening access to an encrypted credential. Deny paths matter more than allow paths.</p>
 */
class SharedConfigGrantSupportTests {

    private static final Timestamp NOW = Timestamp.valueOf("2026-08-11 12:00:00")

    // --- the 1 / 2 / 3 worked example -----------------------------------

    @Test
    void grantSucceedsWhenTheUserAdministersBothTheOwnerAndTheTarget() {
        def world = world(
                adminOf: ["COMPANY_1", "COMPANY_2"],
                config: [omsRestSourceConfigId: "OMS_HW_1", companyUserGroupId: "COMPANY_1"])

        assertTrue(SharedConfigGrantSupport.grantAccess(world.ec, "SCFG_HOTWAX_OMS", "OMS_HW_1", "COMPANY_2"),
                "admin of owner COMPANY_1 and of target COMPANY_2 may wire them together")
        assertFalse(world.message.hasError(), "an allowed grant must add no error: ${world.message.errors}")
        assertEquals(1, world.created.size(), "exactly one ConfigTenantAccess row must be created")
        assertEquals("COMPANY_2", world.created.first().parameters.tenantUserGroupId)
        assertEquals("SCFG_HOTWAX_OMS", world.created.first().parameters.configTypeEnumId)
    }

    @Test
    void grantIsDeniedWhenTheUserDoesNotAdministerTheTargetTenant() {
        def world = world(
                adminOf: ["COMPANY_1", "COMPANY_2"],
                config: [omsRestSourceConfigId: "OMS_HW_1", companyUserGroupId: "COMPANY_1"])

        assertFalse(SharedConfigGrantSupport.grantAccess(world.ec, "SCFG_HOTWAX_OMS", "OMS_HW_1", "COMPANY_3"),
                "the user does not administer COMPANY_3 and must not be able to pull it into the group")
        assertTrue(world.message.hasError())
        assertTrue(world.created.isEmpty(), "a denied grant must write nothing")
    }

    @Test
    void grantIsDeniedWhenTheUserAdministersTheTargetButNoTenantInTheGroup() {
        def world = world(
                adminOf: ["COMPANY_3"],
                config: [omsRestSourceConfigId: "OMS_HW_1", companyUserGroupId: "COMPANY_1"])

        assertFalse(SharedConfigGrantSupport.grantAccess(world.ec, "SCFG_HOTWAX_OMS", "OMS_HW_1", "COMPANY_3"),
                "admin of the target alone must not let a user help themselves to another tenant's credential")
        assertTrue(world.created.isEmpty())
    }

    @Test
    void grantAnchorsOnAnExistingPeerNotOnlyTheOwningTenant() {
        def world = world(
                adminOf: ["COMPANY_2", "COMPANY_3"],
                config: [omsRestSourceConfigId: "OMS_HW_1", companyUserGroupId: "COMPANY_1"],
                grants: [grant("SCFG_HOTWAX_OMS", "OMS_HW_1", "COMPANY_2")])

        assertTrue(SharedConfigGrantSupport.grantAccess(world.ec, "SCFG_HOTWAX_OMS", "OMS_HW_1", "COMPANY_3"),
                "COMPANY_2 is already a peer, so admin of COMPANY_2 + COMPANY_3 is a valid two-sided grant")
    }

    // --- validation ------------------------------------------------------

    @Test
    void grantIsDeniedWhenTheReferencedConfigRowDoesNotExist() {
        def world = world(adminOf: ["COMPANY_1", "COMPANY_2"], config: null)

        assertFalse(SharedConfigGrantSupport.grantAccess(world.ec, "SCFG_HOTWAX_OMS", "GHOST", "COMPANY_2"),
                "configId is polymorphic with no DB FK — the service is the only existence check there is")
        assertTrue(world.message.errors.any { it.contains("GHOST") })
        assertTrue(world.created.isEmpty())
    }

    @Test
    void grantIsDeniedForAnUnknownConfigType() {
        def world = world(adminOf: ["COMPANY_1", "COMPANY_2"],
                config: [omsRestSourceConfigId: "OMS_HW_1", companyUserGroupId: "COMPANY_1"])

        assertFalse(SharedConfigGrantSupport.grantAccess(world.ec, "SCFG_MADE_UP", "OMS_HW_1", "COMPANY_2"))
        assertTrue(world.created.isEmpty())
    }

    @Test
    void grantIsDeniedWhenTheTargetIsTheOwningTenantOrAlreadyAMember() {
        def world = world(
                adminOf: ["COMPANY_1", "COMPANY_2"],
                config: [omsRestSourceConfigId: "OMS_HW_1", companyUserGroupId: "COMPANY_1"],
                grants: [grant("SCFG_HOTWAX_OMS", "OMS_HW_1", "COMPANY_2")])

        assertFalse(SharedConfigGrantSupport.grantAccess(world.ec, "SCFG_HOTWAX_OMS", "OMS_HW_1", "COMPANY_1"),
                "the owning tenant already has access; a grant row for it would be meaningless")
        assertFalse(SharedConfigGrantSupport.grantAccess(world.ec, "SCFG_HOTWAX_OMS", "OMS_HW_1", "COMPANY_2"),
                "duplicate grant must be rejected, not silently duplicated")
        assertTrue(world.created.isEmpty())
    }

    // --- revoke ----------------------------------------------------------

    @Test
    void revokeThruDatesTheGrantRatherThanDeletingIt() {
        def world = world(
                adminOf: ["COMPANY_1", "COMPANY_2"],
                config: [omsRestSourceConfigId: "OMS_HW_1", companyUserGroupId: "COMPANY_1"],
                grants: [grant("SCFG_HOTWAX_OMS", "OMS_HW_1", "COMPANY_2")])

        assertTrue(SharedConfigGrantSupport.revokeAccess(world.ec, "SCFG_HOTWAX_OMS", "OMS_HW_1", "COMPANY_2"))
        assertTrue(world.deleted.isEmpty(), "revoke must never delete — who could reach a credential is history")
        assertEquals(1, world.updated.size())
        assertEquals(NOW, world.updated.first().parameters.thruDate)
    }

    @Test
    void revokeIsDeniedUnderTheSameTwoSidedRuleAsGrant() {
        def world = world(
                adminOf: ["COMPANY_3"],
                config: [omsRestSourceConfigId: "OMS_HW_1", companyUserGroupId: "COMPANY_1"],
                grants: [grant("SCFG_HOTWAX_OMS", "OMS_HW_1", "COMPANY_2")])

        assertFalse(SharedConfigGrantSupport.revokeAccess(world.ec, "SCFG_HOTWAX_OMS", "OMS_HW_1", "COMPANY_2"),
                "a user administering neither end must not be able to cut another tenant's access")
        assertTrue(world.updated.isEmpty())
    }

    // --- describe --------------------------------------------------------

    @Test
    void describeSharingReportsOwnerPeersAndTheAffectedTenantCount() {
        def world = world(
                adminOf: ["COMPANY_1"],
                config: [omsRestSourceConfigId: "OMS_HW_1", companyUserGroupId: "COMPANY_1"],
                grants: [grant("SCFG_HOTWAX_OMS", "OMS_HW_1", "COMPANY_2")])

        Map result = SharedConfigGrantSupport.describeSharing(world.ec, "SCFG_HOTWAX_OMS", "OMS_HW_1")

        assertEquals("COMPANY_1", result.ownerTenantUserGroupId)
        assertEquals(["COMPANY_2"], result.memberTenantUserGroupIds)
        assertEquals(2, result.memberCount,
                "memberCount is owner + peers — it is what the UI's 'changes affect N tenants' warning shows")
    }

    // --- harness ---------------------------------------------------------

    private static Map grant(String type, String configId, String tenant) {
        return [configTypeEnumId: type, configId: configId, tenantUserGroupId: tenant,
                fromDate: Timestamp.valueOf("2026-01-01 00:00:00"), thruDate: null, grantedByUserId: "aditi"]
    }

    /**
     * Builds an ec plus recorders. `adminOf` becomes DARPAN_TENANT_ADMIN rows in
     * TenantUserPermissionGroupMember; the user is deliberately NOT a super admin, since
     * super-admin would short-circuit every check under test.
     */
    private static Map world(Map spec) {
        List<Map> permissionRows = ((spec.adminOf ?: []) as List).collect { String tenant ->
            [userId: "aditi", tenantUserGroupId: tenant,
             permissionUserGroupId: "DARPAN_TENANT_ADMIN", thruDate: null]
        }
        List created = [], updated = [], deleted = []

        // Every tenant this world knows about must resolve as a real UgtDarpanCompany group, or
        // isDarpanTenant rejects the grant before the two-sided rule is ever evaluated and the
        // allow-path tests fail on "Tenant ... was not found" instead of on the rule under test.
        List<String> knownTenants = (((spec.adminOf ?: []) as List) +
                ((spec.grants ?: []) as List).collect { it.tenantUserGroupId } +
                [spec.config?.companyUserGroupId, spec.target,
                 "COMPANY_1", "COMPANY_2", "COMPANY_3"]).findAll { it }.unique()

        // describeSharing gates on canActiveTenantUseConfig, which needs a resolved ACTIVE tenant.
        // That resolves through listAvailableTenants → the moqui.security.UserGroupAndMember view,
        // not from the preference alone. Defaults to the config's owning tenant.
        String activeTenant = spec.activeTenant ?: spec.config?.companyUserGroupId

        def entity = new SharedConfigAccessSupportTests.EntityFacadeStub(finders: [
                "darpan.auth.TenantUserPermissionGroupMember":
                        new SharedConfigAccessSupportTests.FinderStub(listResult: permissionRows),
                // isSuperAdmin reads this via .one(); leaving oneResult null keeps the acting user a
                // plain tenant admin. A super-admin would short-circuit every check under test.
                "moqui.security.UserGroupMember":
                        new SharedConfigAccessSupportTests.FinderStub(listResult: []),
                "moqui.security.UserGroupAndMember":
                        new SharedConfigAccessSupportTests.FinderStub(listResult:
                                activeTenant ? [[userId         : "aditi",
                                                 userGroupId    : activeTenant,
                                                 groupTypeEnumId: "UgtDarpanCompany"]] : []),
                "darpan.auth.ConfigTenantAccess":
                        new SharedConfigAccessSupportTests.FinderStub(listResult: (spec.grants ?: []) as List),
                "darpan.hotwax.HotWaxOmsRestSourceConfig":
                        new SharedConfigAccessSupportTests.FinderStub(oneResult: spec.config),
        ])
        // FinderStub.one() matches on the conditions applied, so a single stub cannot serve
        // per-tenant UserGroup lookups. TenantGroupFinderStub answers any userGroupId in
        // knownTenants as a UgtDarpanCompany group, and null for anything else — so a grant naming
        // an unknown tenant is still correctly rejected.
        entity.finders["moqui.security.UserGroup"] = new TenantGroupFinderStub(known: knownTenants)

        def message = new SharedConfigAccessSupportTests.MessageFacadeStub()
        def ec = new Expando(
                user: new SharedConfigAccessSupportTests.UserStub(userId: "aditi", nowTimestamp: NOW,
                        preferences: ["darpan.auth.activeTenantUserGroupId": activeTenant],
                        context: [activeTenantUserGroupId: activeTenant]),
                entity: entity,
                message: message,
                service: new ServiceFacadeStub(created: created, updated: updated, deleted: deleted),
                l10n: new Expando(timeZone: "UTC"),
                resource: new Expando(properties: [:])
        )
        return [ec: ec, message: message, created: created, updated: updated, deleted: deleted]
    }

    /**
     * Answers UserGroup point-lookups per userGroupId, which a single-row FinderStub cannot do
     * (its {@code one()} matches a single fixed {@code oneResult} against the applied conditions).
     *
     * <p>MUST extend {@code SharedConfigAccessSupportTests.FinderStub}. {@code EntityFacadeStub.find}
     * assigns into a {@code FinderStub}-typed local, and Groovy enforces declared types on
     * assignment — a sibling class that merely duck-types the same methods throws
     * {@code GroovyCastException} at runtime, not a compile error, so the failure would surface as
     * an unrelated-looking test error.</p>
     */
    static class TenantGroupFinderStub extends SharedConfigAccessSupportTests.FinderStub {
        List<String> known = []

        @Override
        Object one() {
            String requested = conditions["userGroupId"]?.toString()
            if (!requested || !(requested in known)) return null
            return [userGroupId: requested, groupTypeEnumId: "UgtDarpanCompany"]
        }

        @Override
        List list() {
            def row = one()
            return row ? [row] : []
        }
    }

    /** Records service calls by verb so the tests can assert create-vs-update-vs-delete. */
    static class ServiceFacadeStub {
        List created, updated, deleted

        def sync() { return new CallStub(owner: this) }

        static class CallStub {
            ServiceFacadeStub owner
            String serviceName
            Map parameters = [:]

            CallStub name(String n) { serviceName = n; this }
            CallStub parameters(Map p) { parameters = p; this }
            CallStub disableAuthz() { this }
            Map call() {
                // grantAccess/revokeAccess each also call AdminAuditSupport.record, which issues its
                // own "create#darpan.admin.AdminAuditLog" call. That call must not be counted in
                // `created` — these tests assert on ConfigTenantAccess row counts, and no test here
                // asserts on the audit-log write itself, so it is excluded rather than tracked.
                if (serviceName == "create#darpan.admin.AdminAuditLog") return [:]
                if (serviceName?.startsWith("create#")) owner.created << [name: serviceName, parameters: parameters]
                else if (serviceName?.startsWith("update#")) owner.updated << [name: serviceName, parameters: parameters]
                else if (serviceName?.startsWith("delete#")) owner.deleted << [name: serviceName, parameters: parameters]
                return [:]
            }
        }
    }
}
