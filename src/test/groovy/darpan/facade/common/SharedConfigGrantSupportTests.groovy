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

        assertEquals(1, world.audited.size(), "grantAccess must write exactly one audit-log row")
        assertEquals("aditi", world.audited.first().parameters.adminUserId,
                "the audit row must carry the acting user, not the target tenant")
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
    void grantErrorIsIdenticalForARealAndANonexistentConfigWhenTheCallerAdministersNeitherEnd() {
        // The caller (admin of COMPANY_3 only) administers neither the target (COMPANY_2) nor the
        // owner of a real config (COMPANY_1). The target-admin check (step 2) must deny before the
        // config table is ever touched, so the message is byte-identical whether "OMS_HW_1" is real
        // or made up — otherwise the response itself would be a cross-tenant existence oracle for
        // configs and tenants (DAR-BE-005 review finding, 2026-08-11).
        def realWorld = world(
                adminOf: ["COMPANY_3"],
                config: [omsRestSourceConfigId: "OMS_HW_1", companyUserGroupId: "COMPANY_1"])
        def ghostWorld = world(adminOf: ["COMPANY_3"], config: null)

        assertFalse(SharedConfigGrantSupport.grantAccess(realWorld.ec, "SCFG_HOTWAX_OMS", "OMS_HW_1", "COMPANY_2"))
        assertFalse(SharedConfigGrantSupport.grantAccess(ghostWorld.ec, "SCFG_HOTWAX_OMS", "GHOST", "COMPANY_2"))

        assertEquals(realWorld.message.errors, ghostWorld.message.errors,
                "a caller who administers neither end must get an identical denial whether the " +
                "config id names a real row or a nonexistent one")
    }

    @Test
    void aSuperAdminIsStillStoppedByTenantExistenceEvenAfterPassingBothAdminChecks() {
        // A super admin trivially passes requireTenantAdmin/isTenantAdmin for ANY non-blank tenant
        // string (see TenantAccessSupport.isTenantAdmin) — including a typo. isDarpanTenant (step 5)
        // is the only thing left standing between that and a grant row pointing at a tenant nobody
        // vetted. This must hold even though the admin checks now run BEFORE the existence checks.
        def world = world(
                superAdmin: true,
                config: [omsRestSourceConfigId: "OMS_HW_1", companyUserGroupId: "COMPANY_1"])

        assertFalse(SharedConfigGrantSupport.grantAccess(world.ec, "SCFG_HOTWAX_OMS", "OMS_HW_1", "COMPANY_TYPO"),
                "a super admin passes both admin checks for any string, but a typo'd/nonexistent " +
                "tenant must still be rejected before the write")
        assertTrue(world.message.errors.any { it.contains("COMPANY_TYPO") })
        assertTrue(world.created.isEmpty(), "the write must never happen for an unvetted target")
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

        assertEquals(1, world.audited.size(), "revokeAccess must write exactly one audit-log row")
        assertEquals("aditi", world.audited.first().parameters.adminUserId)
    }

    @Test
    void revokeSucceedsForAGrantWithAFutureThruDate() {
        // The reader that gates live credential access (SharedConfigAccessSupport.isActiveGrant)
        // treats a FUTURE thruDate as still active — same as null. Before the fix, revoke filtered
        // rows on `thruDate == null` only, so a future-dated row was reachable by grant/read but
        // invisible to revoke's own filter: T could read the credential today, yet neither grant nor
        // revoke could touch that row (DAR-BE-005 review finding, 2026-08-11).
        // NOTE: this local is deliberately NOT named `world` — declaring a local `world` would
        // shadow the `world(...)` factory method for the REST of this method body (Groovy resolves
        // the identifier to the local once declared), breaking the second `world(...)` call below.
        Timestamp future = Timestamp.valueOf("2027-01-01 00:00:00")
        def beforeWorld = world(
                adminOf: ["COMPANY_1", "COMPANY_2"],
                config: [omsRestSourceConfigId: "OMS_HW_1", companyUserGroupId: "COMPANY_1"],
                grants: [grant("SCFG_HOTWAX_OMS", "OMS_HW_1", "COMPANY_2", future)])

        // Sanity: before revoke, COMPANY_2's future-thruDate row IS active per the reader — this is
        // exactly why an unrevokable future-dated row is dangerous.
        assertTrue(SharedConfigAccessSupport.listMemberTenantIds(beforeWorld.ec, "SCFG_HOTWAX_OMS", "OMS_HW_1")
                .contains("COMPANY_2"))

        assertTrue(SharedConfigGrantSupport.revokeAccess(beforeWorld.ec, "SCFG_HOTWAX_OMS", "OMS_HW_1", "COMPANY_2"),
                "revoke and the reader must agree on what 'active' means — a future thruDate is " +
                "active, so it must be revokable, not silently unreachable by both grant and revoke")
        assertEquals(1, beforeWorld.updated.size())
        assertEquals(NOW, beforeWorld.updated.first().parameters.thruDate,
                "revoke must set thruDate to now, superseding the stale future value")

        // Prove the write revoke just issued would actually remove membership: rebuild the world as
        // it would look immediately after that write lands (thruDate = NOW) and confirm the SAME
        // reader no longer counts COMPANY_2 as a member.
        def afterWorld = world(
                adminOf: ["COMPANY_1", "COMPANY_2"],
                config: [omsRestSourceConfigId: "OMS_HW_1", companyUserGroupId: "COMPANY_1"],
                grants: [grant("SCFG_HOTWAX_OMS", "OMS_HW_1", "COMPANY_2", NOW)])
        assertFalse(SharedConfigAccessSupport.listMemberTenantIds(afterWorld.ec, "SCFG_HOTWAX_OMS", "OMS_HW_1")
                .contains("COMPANY_2"), "once thruDate is set to now, the same reader must exclude the tenant")
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

    @Test
    void describeSharingErrorIsIdenticalForARealForeignConfigAndANonexistentConfig() {
        // list#ConfigTenantAccess sits behind facade\..*, granted to every Darpan role including
        // DARPAN_TENANT_USER (class Javadoc) — unlike grant/revoke, describeSharing has no two-sided
        // admin check to run first; canActiveTenantUseConfig (a membership check anyone can fail) IS
        // the whole gate. A caller whose active tenant is neither owner nor peer must therefore get
        // the SAME denial whether the config id names a real row owned by someone else or does not
        // exist at all — otherwise the response is a cross-tenant existence oracle, enumerable by
        // sweeping ids. This pins the collapse for B6, mirroring the grantErrorIsIdentical... test
        // above that pins the analogous fix from Task 4 (DAR-BE-005 review finding, 2026-08-11/12).
        def foreignWorld = world(
                adminOf: [],
                activeTenant: "COMPANY_2",
                config: [omsRestSourceConfigId: "OMS_HW_1", companyUserGroupId: "COMPANY_1"])
        def ghostWorld = world(
                adminOf: [],
                activeTenant: "COMPANY_2",
                config: null)

        // Same configId in both calls deliberately: the collapsed message echoes the id back
        // ("'<id>' was not found"), so a byte-identical comparison only pins the leak-closing
        // property when the id itself is held constant between the real-row and no-such-row cases.
        assertEquals(null, SharedConfigGrantSupport.describeSharing(foreignWorld.ec, "SCFG_HOTWAX_OMS", "OMS_HW_1"),
                "a caller with no standing must be denied, not shown the sharing panel")
        assertEquals(null, SharedConfigGrantSupport.describeSharing(ghostWorld.ec, "SCFG_HOTWAX_OMS", "OMS_HW_1"))

        assertEquals(foreignWorld.message.errors, ghostWorld.message.errors,
                "a caller with no standing over the config must get an identical denial whether the " +
                "config id names a real row owned by another tenant or does not exist at all")
    }

    @Test
    void describeSharingNormalizesAWhitespacePaddedConfigId() {
        // A whitespace-padded id was grantable (resolveAndAuthorize already normalized) but not
        // describable before this fix (DAR-BE-005 review finding, 2026-08-11).
        def world = world(
                adminOf: ["COMPANY_1"],
                config: [omsRestSourceConfigId: "OMS_HW_1", companyUserGroupId: "COMPANY_1"])

        Map result = SharedConfigGrantSupport.describeSharing(world.ec, "SCFG_HOTWAX_OMS", "  OMS_HW_1  ")

        assertEquals("OMS_HW_1", result.configId)
        assertEquals("COMPANY_1", result.ownerTenantUserGroupId)
    }

    // --- delete guard ------------------------------------------------------

    @Test
    void hasActiveGrantsIsTrueWhileAnyPeerRemainsAndFalseAfterTheLastRevoke() {
        def shared = world(
                adminOf: ["COMPANY_1"],
                config: [omsRestSourceConfigId: "OMS_HW_1", companyUserGroupId: "COMPANY_1"],
                grants: [grant("SCFG_HOTWAX_OMS", "OMS_HW_1", "COMPANY_2")])
        assertTrue(SharedConfigGrantSupport.hasActiveGrants(shared.ec, "SCFG_HOTWAX_OMS", "OMS_HW_1"),
                "a config with a live peer must not be deletable — configId has no FK to cascade")

        def unshared = world(
                adminOf: ["COMPANY_1"],
                config: [omsRestSourceConfigId: "OMS_HW_1", companyUserGroupId: "COMPANY_1"],
                grants: [])
        assertFalse(SharedConfigGrantSupport.hasActiveGrants(unshared.ec, "SCFG_HOTWAX_OMS", "OMS_HW_1"),
                "once every grant is revoked the owner may delete normally")
    }

    @Test
    void hasActiveGrantsIgnoresRevokedRows() {
        def world = world(
                adminOf: ["COMPANY_1"],
                config: [omsRestSourceConfigId: "OMS_HW_1", companyUserGroupId: "COMPANY_1"],
                grants: [[configTypeEnumId: "SCFG_HOTWAX_OMS", configId: "OMS_HW_1",
                          tenantUserGroupId: "COMPANY_2",
                          fromDate: Timestamp.valueOf("2026-01-01 00:00:00"),
                          thruDate: Timestamp.valueOf("2026-02-01 00:00:00")]])

        assertFalse(SharedConfigGrantSupport.hasActiveGrants(world.ec, "SCFG_HOTWAX_OMS", "OMS_HW_1"),
                "a revoked grant is history, not a live dependency")
    }

    // --- harness ---------------------------------------------------------

    private static Map grant(String type, String configId, String tenant, Timestamp thruDate = null) {
        return [configTypeEnumId: type, configId: configId, tenantUserGroupId: tenant,
                fromDate: Timestamp.valueOf("2026-01-01 00:00:00"), thruDate: thruDate, grantedByUserId: "aditi"]
    }

    /**
     * Builds an ec plus recorders. `adminOf` becomes DARPAN_TENANT_ADMIN rows in
     * TenantUserPermissionGroupMember; the user is deliberately NOT a super admin unless
     * `spec.superAdmin` is set, since super-admin would short-circuit every check under test —
     * except the one regression test that specifically needs it
     * ({@code aSuperAdminIsStillStoppedByTenantExistenceEvenAfterPassingBothAdminChecks}).
     */
    private static Map world(Map spec) {
        List<Map> permissionRows = ((spec.adminOf ?: []) as List).collect { String tenant ->
            [userId: "aditi", tenantUserGroupId: tenant,
             permissionUserGroupId: "DARPAN_TENANT_ADMIN", thruDate: null]
        }
        List created = [], updated = [], deleted = [], audited = []

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
                // plain tenant admin. A super-admin would short-circuit every check under test —
                // spec.superAdmin opts a single test into that short-circuit deliberately, to prove
                // isDarpanTenant (not the admin checks) is what still stops it.
                "moqui.security.UserGroupMember":
                        new SharedConfigAccessSupportTests.FinderStub(listResult: [],
                                oneResult: spec.superAdmin ? [userId: "aditi", userGroupId: "ADMIN"] : null),
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
                service: new ServiceFacadeStub(created: created, updated: updated, deleted: deleted, audited: audited),
                l10n: new Expando(timeZone: "UTC"),
                resource: new Expando(properties: [:])
        )
        return [ec: ec, message: message, created: created, updated: updated, deleted: deleted, audited: audited]
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

    /** Records service calls by verb so the tests can assert create-vs-update-vs-delete-vs-audit. */
    static class ServiceFacadeStub {
        List created, updated, deleted, audited

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
                // own "create#darpan.admin.AdminAuditLog" call. That must NOT be counted in `created`
                // — `created` asserts on ConfigTenantAccess row counts specifically — but it must be
                // observable somewhere, or the audit write is a feature with no test guarding it
                // (deleting both AdminAuditSupport.record calls would otherwise leave every test
                // green). Routed into `audited` instead (DAR-BE-005 review finding, 2026-08-11).
                if (serviceName == "create#darpan.admin.AdminAuditLog") {
                    owner.audited << [name: serviceName, parameters: parameters]
                    return [:]
                }
                if (serviceName?.startsWith("create#")) owner.created << [name: serviceName, parameters: parameters]
                else if (serviceName?.startsWith("update#")) owner.updated << [name: serviceName, parameters: parameters]
                else if (serviceName?.startsWith("delete#")) owner.deleted << [name: serviceName, parameters: parameters]
                return [:]
            }
        }
    }
}
