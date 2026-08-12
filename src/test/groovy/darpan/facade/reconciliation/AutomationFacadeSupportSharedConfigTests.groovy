package darpan.facade.reconciliation

import org.junit.jupiter.api.Test

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-005 review finding B5 — {@code listNsAuthConfigOptions} and {@code listNsRestletOptions}
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

        List<Map<String, Object>> options = AutomationFacadeSupport.listNsAuthConfigOptions(ec)

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

        List<Map<String, Object>> options = AutomationFacadeSupport.listNsAuthConfigOptions(ec)

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

        List<Map<String, Object>> options = AutomationFacadeSupport.listNsAuthConfigOptions(ec)

        assertEquals(["NS_AUTH_ACTIVE"], options.collect { it.nsAuthConfigId },
                "the source picker must not offer an inactive config — listAccessibleConfigRows " +
                "applies no status filter of its own by design, so this builder must keep applying " +
                "its own isActive filter after widening (same precedent as the OMS/Shopify builders)")
    }

    // --- listNsRestletOptions ----------------------------------------------

    @Test
    void listNsRestletOptionsIncludesAConfigSharedWithTheActiveTenant() {
        Map ownedRow = [nsRestletConfigId: "NS_RESTLET_MEMBER_OWN", companyUserGroupId: "BETSEY_JOHNSON",
                        description: "Member's own restlet", isActive: "Y"]
        Map sharedRow = [nsRestletConfigId: "NS_RESTLET_SM", companyUserGroupId: "STEVE_MADDEN",
                         description: "Shared restlet", isActive: "Y"]
        def ec = ecForNsRestlet("BETSEY_JOHNSON", [ownedRow, sharedRow],
                [grant("SCFG_NS_RESTLET", "NS_RESTLET_SM", "BETSEY_JOHNSON")])

        List<Map<String, Object>> options = AutomationFacadeSupport.listNsRestletOptions(ec)

        assertEquals(["NS_RESTLET_MEMBER_OWN", "NS_RESTLET_SM"] as Set,
                options.collect { it.nsRestletConfigId } as Set,
                "the picker must offer both the tenant's own restlet AND the one shared with it")
    }

    @Test
    void listNsRestletOptionsExcludesAConfigNotSharedWithTheActiveTenant() {
        Map foreignRow = [nsRestletConfigId: "NS_RESTLET_SM", companyUserGroupId: "STEVE_MADDEN",
                          description: "Not shared", isActive: "Y"]
        def ec = ecForNsRestlet("THIRD_LOVE", [foreignRow], [])

        List<Map<String, Object>> options = AutomationFacadeSupport.listNsRestletOptions(ec)

        assertTrue(options.isEmpty(),
                "a tenant with no grant and no ownership must still see nothing")
    }

    @Test
    void listNsRestletOptionsZeroGrantsPreservesOwnerVisibilityAndForeignExclusion() {
        Map ownedRow = [nsRestletConfigId: "NS_RESTLET_OWN", companyUserGroupId: "STEVE_MADDEN",
                        description: "Owned, unshared", isActive: "Y"]
        def ecAsOwner = ecForNsRestlet("STEVE_MADDEN", [ownedRow], [])
        assertEquals(["NS_RESTLET_OWN"], AutomationFacadeSupport.listNsRestletOptions(ecAsOwner)
                .collect { it.nsRestletConfigId },
                "with zero grants the owner must still see its own restlet — byte-identical to " +
                "pre-DAR-BE-005 behavior")

        def ecAsForeign = ecForNsRestlet("THIRD_LOVE", [ownedRow], [])
        assertTrue(AutomationFacadeSupport.listNsRestletOptions(ecAsForeign).isEmpty(),
                "with zero grants a foreign tenant must still be rejected exactly as before")
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

    private static Expando ecForNsRestlet(String activeTenant, List<Map> configRows, List<Map> grants) {
        return executionContext(activeTenant, "darpan.reconciliation.NsRestletConfig", configRows, grants)
    }

    private static Expando executionContext(String activeTenant, String entityName, List<Map> configRows,
            List<Map> grants) {
        return new Expando(
                user: new UserStub(userId: "aditi", nowTimestamp: NOW,
                        preferences: ["darpan.auth.activeTenantUserGroupId": activeTenant],
                        context: [activeTenantUserGroupId: activeTenant]),
                entity: new EntityFacadeStub(finders: [
                        "darpan.auth.ConfigTenantAccess": new FinderStub(listResult: grants),
                        (entityName)                    : new FinderStub(listResult: configRows),
                        "moqui.security.UserGroupAndMember": new FinderStub(listResult:
                                activeTenant ? [[userId         : "aditi",
                                                 userGroupId    : activeTenant,
                                                 groupTypeEnumId: "UgtDarpanCompany"]] : []),
                ]),
                message: new MessageFacadeStub(),
                l10n: new Expando(timeZone: "UTC"),
                resource: new Expando(properties: [:])
        )
    }

    static class UserStub {
        String userId
        String username
        Timestamp nowTimestamp = NOW
        Map<String, Object> preferences = [:]
        Map<String, Object> context = [:]
        Object userAccount = new Expando(timeZone: "UTC")

        Object getPreference(String key) { preferences[key] }
        void setPreference(String key, Object value) { preferences[key] = value }
    }

    static class MessageFacadeStub {
        List<String> errors = []
        void addError(String msg) { errors << msg }
        boolean hasError() { !errors.isEmpty() }
    }

    static class EntityFacadeStub {
        Map<String, FinderStub> finders = [:]

        FinderStub find(String entityName) {
            FinderStub f = finders[entityName]
            if (f == null) { f = new FinderStub(); finders[entityName] = f }
            // Real Moqui hands back a brand-new EntityFind with an empty condition set on every
            // find() call — reset here so a second find() on the same entity (owned-rows list, then
            // shared-rows point lookups) within one production method starts clean. Matches
            // SharedConfigAccessSupportTests.EntityFacadeStub's own rationale.
            f.conditions = [:]
            return f
        }
    }

    static class FinderStub {
        Map<String, Object> conditions = [:]
        Object oneResult
        List listResult = []

        FinderStub condition(String field, Object value) { conditions[field] = value; this }
        FinderStub conditionDate(String f, String t, Object m) { this }
        FinderStub useCache(boolean v) { this }
        FinderStub disableAuthz() { this }
        FinderStub orderBy(String s) { this }

        Object one() {
            if (oneResult != null) {
                if (oneResult instanceof Map && !conditions.every { k, v -> oneResult[k] == v }) return null
                return oneResult
            }
            List matches = list()
            return matches.isEmpty() ? null : matches[0]
        }

        List list() {
            listResult.findAll { row ->
                if (!(row instanceof Map)) return true
                conditions.every { k, v -> row[k] == v }
            }
        }
    }
}
