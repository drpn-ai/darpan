package darpan.facade.common

import darpan.facade.reconciliation.ReconciliationSavedRunSupport
import darpan.reconciliation.automation.AutomationExecutionSupport
import org.junit.jupiter.api.Test

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-005 — the property that makes this "link, not copy".
 *
 * <p>There is exactly ONE config row. A member tenant resolves the SAME row the owner does, so an
 * edit made under either tenant is immediately effective for both. These tests assert the resolution
 * identity; they do not assert wall-clock propagation, because there is nothing to propagate.</p>
 */
class SharedConfigPropagationTests {

    private static final Timestamp NOW = Timestamp.valueOf("2026-08-11 12:00:00")

    @Test
    void ownerAndMemberResolveTheSameUnderlyingConfigRow() {
        Map sharedRow = [omsRestSourceConfigId: "OMS_SM", companyUserGroupId: "STEVE_MADDEN",
                         description: "HotWax shared", baseUrl: "https://oms.example.com",
                         isActive: "Y", canReadOrders: "Y"]

        def asOwner = ecFor("STEVE_MADDEN", sharedRow)
        def asMember = ecFor("BETSEY_JOHNSON", sharedRow)

        assertTrue(SharedConfigAccessSupport.canActiveTenantUseConfig(asOwner, "SCFG_HOTWAX_OMS", sharedRow))
        assertTrue(SharedConfigAccessSupport.canActiveTenantUseConfig(asMember, "SCFG_HOTWAX_OMS", sharedRow))

        List ownerRows = SharedConfigAccessSupport.listAccessibleConfigRows(asOwner, "SCFG_HOTWAX_OMS")
        List memberRows = SharedConfigAccessSupport.listAccessibleConfigRows(asMember, "SCFG_HOTWAX_OMS")

        assertEquals(["OMS_SM"], ownerRows.collect { it.omsRestSourceConfigId })
        assertEquals(["OMS_SM"], memberRows.collect { it.omsRestSourceConfigId })
        assertTrue(ownerRows.first().is(memberRows.first()),
                "both tenants must resolve the SAME row instance — a copy would silently diverge")
    }

    @Test
    void anEditUnderTheMemberTenantIsVisibleToTheOwnerBecauseThereIsOnlyOneRow() {
        Map sharedRow = [omsRestSourceConfigId: "OMS_SM", companyUserGroupId: "STEVE_MADDEN",
                         description: "before", isActive: "Y", canReadOrders: "Y"]
        def asOwner = ecFor("STEVE_MADDEN", sharedRow)
        def asMember = ecFor("BETSEY_JOHNSON", sharedRow)

        // The member edits the shared row (what save#... does after the widened access check).
        SharedConfigAccessSupport.listAccessibleConfigRows(asMember, "SCFG_HOTWAX_OMS")
                .first().description = "after"

        assertEquals("after",
                SharedConfigAccessSupport.listAccessibleConfigRows(asOwner, "SCFG_HOTWAX_OMS").first().description,
                "one row, many tenants: the owner sees the member's edit with no propagation step")
    }

    @Test
    void aTenantOutsideTheGroupStillCannotResolveTheConfig() {
        Map sharedRow = [omsRestSourceConfigId: "OMS_SM", companyUserGroupId: "STEVE_MADDEN",
                         description: "HotWax shared", isActive: "Y", canReadOrders: "Y"]
        def asOutsider = ecFor("THIRD_LOVE", sharedRow)

        assertFalse(SharedConfigAccessSupport.canActiveTenantUseConfig(asOutsider, "SCFG_HOTWAX_OMS", sharedRow))
        assertTrue(SharedConfigAccessSupport.listAccessibleConfigRows(asOutsider, "SCFG_HOTWAX_OMS").isEmpty(),
                "sharing must widen access to named peers only — tenant isolation is otherwise intact")
    }

    // --- Seam B: the reference validators (ReconciliationSavedRunSupport) ---------------------
    //
    // The three tests above exercise SharedConfigAccessSupport only (Task 3/5). They would stay
    // green even if this task's rewrite of validateHotWaxOmsConfig / findSingleActiveConfigId were
    // reverted to the old tenant-only shape. The tests below call the seam B validators directly —
    // Groovy's dynamic dispatch permits a same-module cross-package call to a `protected static`
    // method — so a regression in either widened method fails HERE, not silently.

    @Test
    void validateHotWaxOmsConfigAllowsAMemberTenantToReferenceTheSharedConfig() {
        Map sharedRow = [omsRestSourceConfigId: "OMS_SM", companyUserGroupId: "STEVE_MADDEN",
                         description: "HotWax shared", isActive: "Y", canReadOrders: "Y"]
        def asMember = ecFor("BETSEY_JOHNSON", sharedRow)

        ReconciliationSavedRunSupport.validateHotWaxOmsConfig(asMember, "File 1", "OMS_SM")

        assertFalse(asMember.message.hasError(),
                "seam B: a member tenant that can SEE a shared config in the settings list must also " +
                "be able to REFERENCE it from a saved run — the half-open state this seam prevents")
    }

    @Test
    void validateHotWaxOmsConfigDeniesAnOutsiderTenantWithTheExactMessageText() {
        Map sharedRow = [omsRestSourceConfigId: "OMS_SM", companyUserGroupId: "STEVE_MADDEN",
                         description: "HotWax shared", isActive: "Y", canReadOrders: "Y"]
        def asOutsider = ecFor("THIRD_LOVE", sharedRow)

        ReconciliationSavedRunSupport.validateHotWaxOmsConfig(asOutsider, "File 1", "OMS_SM")

        assertEquals(["File 1 HotWax source config 'OMS_SM' is not available in your active tenant."],
                asOutsider.message.errors,
                "the error text must stay byte-identical to what requireTenantRecordAccess used to " +
                "produce — the UI and existing specs match on it")
    }

    @Test
    void validateHotWaxOmsConfigReportsNotFoundWithTheExactMessageTextForAnUnknownId() {
        Map sharedRow = [omsRestSourceConfigId: "OMS_SM", companyUserGroupId: "STEVE_MADDEN",
                         description: "HotWax shared", isActive: "Y", canReadOrders: "Y"]
        def asOwner = ecFor("STEVE_MADDEN", sharedRow)

        ReconciliationSavedRunSupport.validateHotWaxOmsConfig(asOwner, "File 1", "OMS_DOES_NOT_EXIST")

        assertEquals(["File 1 HotWax source config 'OMS_DOES_NOT_EXIST' was not found."],
                asOwner.message.errors)
    }

    // --- Seam B: automation auto-detect (AutomationExecutionSupport.findSingleActiveConfigId) ---

    @Test
    void findSingleActiveConfigIdAutoDetectsASharedConfigForAMemberTenantWithNoOwnedRowOfItsOwn() {
        Map sharedRow = [omsRestSourceConfigId: "OMS_SM", companyUserGroupId: "STEVE_MADDEN",
                         isActive: "Y", canReadOrders: "Y"]
        Map grant = [configTypeEnumId: "SCFG_HOTWAX_OMS", configId: "OMS_SM",
                     tenantUserGroupId: "BETSEY_JOHNSON",
                     fromDate: Timestamp.valueOf("2026-01-01 00:00:00"), thruDate: null,
                     grantedByUserId: "aditi"]
        def ec = ecForAutomation("BETSEY_JOHNSON", [sharedRow], [grant])

        String resolved = AutomationExecutionSupport.findSingleActiveConfigId(
                ec, "BETSEY_JOHNSON", "darpan.hotwax.HotWaxOmsRestSourceConfig", "omsRestSourceConfigId")

        assertEquals("OMS_SM", resolved,
                "a member tenant's automation must auto-resolve the ONE config it can reach, owned or shared")
    }

    @Test
    void findSingleActiveConfigIdDeclinesWhenSharingMakesTheChoiceAmbiguous() {
        Map ownedRow = [omsRestSourceConfigId: "OMS_BJ", companyUserGroupId: "BETSEY_JOHNSON",
                        isActive: "Y", canReadOrders: "Y"]
        Map sharedRow = [omsRestSourceConfigId: "OMS_SM", companyUserGroupId: "STEVE_MADDEN",
                         isActive: "Y", canReadOrders: "Y"]
        Map grant = [configTypeEnumId: "SCFG_HOTWAX_OMS", configId: "OMS_SM",
                     tenantUserGroupId: "BETSEY_JOHNSON",
                     fromDate: Timestamp.valueOf("2026-01-01 00:00:00"), thruDate: null,
                     grantedByUserId: "aditi"]
        def ec = ecForAutomation("BETSEY_JOHNSON", [ownedRow, sharedRow], [grant])

        String resolved = AutomationExecutionSupport.findSingleActiveConfigId(
                ec, "BETSEY_JOHNSON", "darpan.hotwax.HotWaxOmsRestSourceConfig", "omsRestSourceConfigId")

        assertNull(resolved,
                "unchanged contract: sharing can take a tenant from one candidate to two, and " +
                "auto-detect must correctly DECLINE rather than guess — the automation must name its " +
                "config explicitly")
    }

    /** Builds an EC for the automation auto-detect path. All config rows share one FinderStub, since
     *  findSingleActiveConfigId queries the same entity twice: once by companyUserGroupId (owned),
     *  once by explicit PK (shared point lookup). */
    private static Expando ecForAutomation(String activeTenant, List<Map> configRows, List<Map> grants) {
        return new Expando(
                user: new SharedConfigAccessSupportTests.UserStub(userId: "aditi", nowTimestamp: NOW,
                        preferences: ["darpan.auth.activeTenantUserGroupId": activeTenant],
                        context: [activeTenantUserGroupId: activeTenant]),
                entity: new SharedConfigAccessSupportTests.EntityFacadeStub(finders: [
                        "darpan.auth.ConfigTenantAccess": new SharedConfigAccessSupportTests.FinderStub(
                                listResult: grants),
                        "darpan.hotwax.HotWaxOmsRestSourceConfig": new LimitAwareFinderStub(
                                listResult: configRows),
                        "moqui.security.UserGroupAndMember":
                                new SharedConfigAccessSupportTests.FinderStub(listResult:
                                        activeTenant ? [[userId         : "aditi",
                                                         userGroupId    : activeTenant,
                                                         groupTypeEnumId: "UgtDarpanCompany"]] : []),
                ]),
                message: new SharedConfigAccessSupportTests.MessageFacadeStub(),
                l10n: new Expando(timeZone: "UTC"),
                resource: new Expando(properties: [:])
        )
    }

    /** {@code findSingleActiveConfigId} chains {@code .limit(2)}, which the shared harness FinderStub
     *  does not implement (no production caller needed it before this task). Extending locally rather
     *  than editing the shared stub keeps this task's file footprint to the three files it owns. */
    private static class LimitAwareFinderStub extends SharedConfigAccessSupportTests.FinderStub {
        LimitAwareFinderStub limit(int max) { this }
    }

    /** STEVE_MADDEN owns the row; BETSEY_JOHNSON holds an active grant; THIRD_LOVE holds none. */
    private static Expando ecFor(String activeTenant, Map sharedRow) {
        return new Expando(
                user: new SharedConfigAccessSupportTests.UserStub(userId: "aditi", nowTimestamp: NOW,
                        preferences: ["darpan.auth.activeTenantUserGroupId": activeTenant],
                        context: [activeTenantUserGroupId: activeTenant]),
                entity: new SharedConfigAccessSupportTests.EntityFacadeStub(finders: [
                        "darpan.auth.ConfigTenantAccess": new SharedConfigAccessSupportTests.FinderStub(
                                listResult: [[configTypeEnumId: "SCFG_HOTWAX_OMS", configId: "OMS_SM",
                                              tenantUserGroupId: "BETSEY_JOHNSON",
                                              fromDate: Timestamp.valueOf("2026-01-01 00:00:00"),
                                              thruDate: null, grantedByUserId: "aditi"]]),
                        "darpan.hotwax.HotWaxOmsRestSourceConfig":
                                new SharedConfigAccessSupportTests.FinderStub(
                                        listResult: [sharedRow], oneResult: sharedRow),
                        // REQUIRED — see the Global Constraint on active-tenant stubs. Without this
                        // view row the active tenant is null, canActiveTenantUseConfig returns false
                        // for BOTH tenants, and `aTenantOutsideTheGroupStillCannotResolveTheConfig`
                        // passes while the two tests that prove the feature works fail.
                        "moqui.security.UserGroupAndMember":
                                new SharedConfigAccessSupportTests.FinderStub(listResult:
                                        activeTenant ? [[userId         : "aditi",
                                                         userGroupId    : activeTenant,
                                                         groupTypeEnumId: "UgtDarpanCompany"]] : []),
                ]),
                message: new SharedConfigAccessSupportTests.MessageFacadeStub(),
                l10n: new Expando(timeZone: "UTC"),
                resource: new Expando(properties: [:])
        )
    }
}
