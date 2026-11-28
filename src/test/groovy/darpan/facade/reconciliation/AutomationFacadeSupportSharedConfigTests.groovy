package darpan.facade.reconciliation

import darpan.common.DarpanEntityConstants
import darpan.facade.common.SharedConfigAccessSupport
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import darpan.reconciliation.support.FakeEntityFacade
import darpan.reconciliation.support.FakeTenantEc
import org.junit.jupiter.api.Test

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-005 review finding B5 — {@code listNsAuthConfigOptions}
 * (AutomationFacadeSupport.groovy) stayed on owner-only {@code TenantScopedFinder.findTenantScoped}
 * after {@code listOmsRestSourceConfigOptions} / {@code listShopifyAuthConfigOptions} were widened to
 * {@code SharedConfigAccessSupport.listAccessibleConfigRows} (Task 5). So a shared NetSuite config
 * appeared in Settings and was editable (Task 5/7), but never appeared in the automation/rule-set
 * source picker — Seam A contradicting itself inside one function family.
 *
 * <p>Stub-based (no Moqui boot), mirroring {@code SharedConfigAccessSupportTests} / {@code
 * SharedConfigPropagationTests} — this class is in {@code darpan.facade.reconciliation}, the same
 * package as {@code AutomationFacadeSupport}, so its {@code protected static} /
 * {@code static} list methods are reachable directly.</p>
 */
class AutomationFacadeSupportSharedConfigTests {

    private static final Timestamp NOW = Timestamp.valueOf("2026-08-11 12:00:00")

    // --- listNsAuthConfigOptions ------------------------------------------

    @Test
    void listNsAuthConfigOptionsIncludesAConfigSharedWithTheActiveTenant() {
        Map ownedRow = [nsAuthConfigId: "NS_AUTH_MEMBER_OWN", companyUserGroupId: "BETSEY_JOHNSON",
                        description: "Member's own NS auth", isActive: "Y"]
        Map sharedRow = [nsAuthConfigId: "NS_AUTH_SM", companyUserGroupId: "STEVE_MADDEN",
                         description: "Shared NS auth", isActive: "Y"]
        def ec = ecForNsAuth("BETSEY_JOHNSON", [ownedRow, sharedRow],
                [grant("SCFG_NS_AUTH", "NS_AUTH_SM", "BETSEY_JOHNSON")])

        List<Map<String, Object>> options = AutomationSourceOptionsSupport.listNsAuthConfigOptions(ec)

        assertEquals(["NS_AUTH_MEMBER_OWN", "NS_AUTH_SM"] as Set,
                options.collect { it.nsAuthConfigId } as Set,
                "the picker must offer both the tenant's own config AND the one shared with it — " +
                "this is exactly what Settings already shows and what save#NsAuthConfig already " +
                "accepts an edit for (Task 5/7); the picker must not contradict them")
    }

    @Test
    void listNsAuthConfigOptionsExcludesAConfigNotSharedWithTheActiveTenant() {
        Map foreignRow = [nsAuthConfigId: "NS_AUTH_SM", companyUserGroupId: "STEVE_MADDEN",
                          description: "Not shared", isActive: "Y"]
        def ec = ecForNsAuth("THIRD_LOVE", [foreignRow], [])

        List<Map<String, Object>> options = AutomationSourceOptionsSupport.listNsAuthConfigOptions(ec)

        assertTrue(options.isEmpty(),
                "sharing must widen access to named peers only — a tenant with no grant and no " +
                "ownership must still see nothing, exactly as before DAR-BE-005")
    }

    @Test
    void listNsAuthConfigOptionsPreservesTheIsActiveFilterAfterWidening() {
        Map activeRow = [nsAuthConfigId: "NS_AUTH_ACTIVE", companyUserGroupId: "STEVE_MADDEN",
                         description: "Active", isActive: "Y"]
        Map inactiveRow = [nsAuthConfigId: "NS_AUTH_INACTIVE", companyUserGroupId: "STEVE_MADDEN",
                           description: "Inactive", isActive: "N"]
        def ec = ecForNsAuth("STEVE_MADDEN", [activeRow, inactiveRow], [])

        List<Map<String, Object>> options = AutomationSourceOptionsSupport.listNsAuthConfigOptions(ec)

        assertEquals(["NS_AUTH_ACTIVE"], options.collect { it.nsAuthConfigId },
                "the source picker must not offer an inactive config — listAccessibleConfigRows " +
                "applies no status filter of its own by design, so this builder must keep applying " +
                "its own isActive filter after widening (same precedent as the OMS/Shopify builders)")
    }

    // --- listEnabledConfigEndpoints: a failed read is loud (S19 F7) --------

    @Test
    void aFailedConfigReadFailsTheSourcePickerInsteadOfOfferingNothing() {
        // It used to catch everything and return [], so a broken read showed "No API configs are
        // available" and the operator had no way to tell it from a tenant with no config.
        Expando ec = executionContext("KREWE", DarpanEntityConstants.HOT_WAX_OMS_REST_SOURCE_CONFIG, [], [])
        FakeEntityFacade entity = (FakeEntityFacade) ec.entity
        entity.table(SourceSystemConnectorSupport.ENTITY_NAME, [[systemEnumId: "OMS_GQL",
                configEntityName: DarpanEntityConstants.HOT_WAX_OMS_REST_SOURCE_CONFIG, enabled: "Y"]])
        entity.failOn(DarpanEntityConstants.HOT_WAX_OMS_REST_SOURCE_CONFIG, new IllegalStateException("config read failed"))

        assertThrows(IllegalStateException) {
            AutomationSourceOptionsSupport.listEnabledConfigEndpoints(ec, SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS)
        }
    }

    // --- fixtures ------------------------------------------------------

    private static Map grant(String type, String configId, String tenant, Timestamp thruDate = null) {
        return [configTypeEnumId: type, configId: configId, tenantUserGroupId: tenant,
                fromDate: Timestamp.valueOf("2026-01-01 00:00:00"), thruDate: thruDate,
                grantedByUserId: "aditi"]
    }

    private static Expando ecForNsAuth(String activeTenant, List<Map> configRows, List<Map> grants) {
        return executionContext(activeTenant, "darpan.reconciliation.NsAuthConfig", configRows, grants)
    }

    private static Expando executionContext(String activeTenant, String entityName, List<Map> configRows,
            List<Map> grants) {
        return new Expando(
                user: new UserStub(userId: "aditi", nowTimestamp: NOW,
                        preferences: ["darpan.auth.activeTenantUserGroupId": activeTenant],
                        context: [activeTenantUserGroupId: activeTenant]),
                entity: new FakeEntityFacade(tables: [
                        "darpan.auth.ConfigTenantAccess": grants,
                        (entityName)                    : configRows,
                        "moqui.security.UserGroupAndMember": activeTenant ? [[userId         : "aditi",
                                                 userGroupId    : activeTenant,
                                                 groupTypeEnumId: "UgtDarpanCompany"]] : [],
                ]),
                message: new FakeTenantEc.MessageFacadeStub(),
                l10n: new Expando(timeZone: "UTC"),
                resource: new Expando(properties: [:])
        )
    }

    /** The shared stub user, its clock fixed at {@link #NOW} so grant dates compare against a known moment. */
    static class UserStub extends FakeTenantEc.UserStub {
        UserStub() { nowTimestamp = NOW }
    }
}
