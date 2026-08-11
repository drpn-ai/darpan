package darpan.facade.common

import org.junit.jupiter.api.Test

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-005 — read-side resolution for shared API source configs.
 *
 * <p>Stub-based (no Moqui boot), mirroring {@code TenantScopedFinderTests}.</p>
 */
class SharedConfigAccessSupportTests {

    private static final Timestamp NOW = Timestamp.valueOf("2026-08-11 12:00:00")
    private static final Timestamp PAST = Timestamp.valueOf("2026-01-01 00:00:00")
    private static final Timestamp FUTURE = Timestamp.valueOf("2027-01-01 00:00:00")

    // --- registry -------------------------------------------------------

    @Test
    void registryCoversTheFourShareableConfigTypesAndNothingElse() {
        assertEquals(
                ["SCFG_HOTWAX_OMS", "SCFG_NS_AUTH", "SCFG_NS_RESTLET", "SCFG_SHOPIFY_AUTH"],
                SharedConfigAccessSupport.CONFIG_TYPE_REGISTRY.keySet().sort(),
                "SystemMessageRemote is deliberately absent — it is a framework endpoint descriptor " +
                "already global to every tenant, not a per-tenant credential")

        assertEquals("darpan.hotwax.HotWaxOmsRestSourceConfig",
                SharedConfigAccessSupport.configType("SCFG_HOTWAX_OMS").entityName)
        assertEquals("omsRestSourceConfigId",
                SharedConfigAccessSupport.configType("SCFG_HOTWAX_OMS").pkField)
        assertEquals("shopifyAuthConfigId",
                SharedConfigAccessSupport.configType("SCFG_SHOPIFY_AUTH").pkField)
        assertEquals("nsAuthConfigId",
                SharedConfigAccessSupport.configType("SCFG_NS_AUTH").pkField)
        assertEquals("nsRestletConfigId",
                SharedConfigAccessSupport.configType("SCFG_NS_RESTLET").pkField)
    }

    @Test
    void configTypeReturnsNullForAnUnknownOrBlankType() {
        assertNull(SharedConfigAccessSupport.configType("SCFG_MADE_UP"))
        assertNull(SharedConfigAccessSupport.configType(null))
        assertNull(SharedConfigAccessSupport.configType("   "))
    }

    // --- peer group -----------------------------------------------------

    @Test
    void listMemberTenantIdsReturnsEveryActivePeerSorted() {
        def ec = ecWithGrants([
                grant("SCFG_HOTWAX_OMS", "OMS_HW_1", "STEVE_MADDEN"),
                grant("SCFG_HOTWAX_OMS", "OMS_HW_1", "BETSEY_JOHNSON"),
        ])

        assertEquals(["BETSEY_JOHNSON", "STEVE_MADDEN"],
                SharedConfigAccessSupport.listMemberTenantIds(ec, "SCFG_HOTWAX_OMS", "OMS_HW_1"),
                "the active grant rows for one config ARE the peer group")
    }

    @Test
    void listMemberTenantIdsExcludesRevokedGrants() {
        def ec = ecWithGrants([
                grant("SCFG_HOTWAX_OMS", "OMS_HW_1", "STEVE_MADDEN"),
                grant("SCFG_HOTWAX_OMS", "OMS_HW_1", "BETSEY_JOHNSON", PAST),
        ])

        assertEquals(["STEVE_MADDEN"],
                SharedConfigAccessSupport.listMemberTenantIds(ec, "SCFG_HOTWAX_OMS", "OMS_HW_1"),
                "a thruDate in the past is a revoked grant and must not confer access")
    }

    @Test
    void listMemberTenantIdsTreatsAFutureThruDateAsStillActive() {
        def ec = ecWithGrants([grant("SCFG_HOTWAX_OMS", "OMS_HW_1", "STEVE_MADDEN", FUTURE)])

        assertEquals(["STEVE_MADDEN"],
                SharedConfigAccessSupport.listMemberTenantIds(ec, "SCFG_HOTWAX_OMS", "OMS_HW_1"),
                "a grant that expires later is active now")
    }

    @Test
    void listMemberTenantIdsIsEmptyForAnUnknownConfigTypeWithoutTouchingTheDatabase() {
        def finder = new FinderStub(listResult: [grant("SCFG_HOTWAX_OMS", "OMS_HW_1", "STEVE_MADDEN")])
        def ec = executionContext(entity: new EntityFacadeStub(
                finders: ["darpan.auth.ConfigTenantAccess": finder]))

        assertTrue(SharedConfigAccessSupport.listMemberTenantIds(ec, "SCFG_MADE_UP", "OMS_HW_1").isEmpty())
        assertTrue(finder.conditions.isEmpty(),
                "an unknown config type must short-circuit before any query is built")
    }

    // --- per-tenant lookup ----------------------------------------------

    @Test
    void listSharedConfigIdsForTenantReturnsOnlyThatTenantsGrantsOfThatType() {
        def ec = ecWithGrants([
                grant("SCFG_HOTWAX_OMS", "OMS_HW_1", "BETSEY_JOHNSON"),
                grant("SCFG_HOTWAX_OMS", "OMS_HW_2", "BETSEY_JOHNSON"),
                grant("SCFG_HOTWAX_OMS", "OMS_HW_9", "THIRD_LOVE"),
                grant("SCFG_SHOPIFY_AUTH", "SHOP_1", "BETSEY_JOHNSON"),
        ])

        assertEquals(["OMS_HW_1", "OMS_HW_2"] as Set,
                SharedConfigAccessSupport.listSharedConfigIdsForTenant(ec, "SCFG_HOTWAX_OMS", "BETSEY_JOHNSON"))
    }

    @Test
    void listSharedConfigIdsForTenantIsEmptyForABlankTenant() {
        def ec = ecWithGrants([grant("SCFG_HOTWAX_OMS", "OMS_HW_1", "BETSEY_JOHNSON")])

        assertTrue(SharedConfigAccessSupport.listSharedConfigIdsForTenant(ec, "SCFG_HOTWAX_OMS", null).isEmpty(),
                "no tenant means no shared access — never fall open to every grant")
        assertTrue(SharedConfigAccessSupport.listSharedConfigIdsForTenant(ec, "SCFG_HOTWAX_OMS", " ").isEmpty())
    }

    @Test
    void isSharedWithTenantIsTrueOnlyForAnActiveGrant() {
        def ec = ecWithGrants([
                grant("SCFG_HOTWAX_OMS", "OMS_HW_1", "BETSEY_JOHNSON"),
                grant("SCFG_HOTWAX_OMS", "OMS_HW_2", "BETSEY_JOHNSON", PAST),
        ])

        assertTrue(SharedConfigAccessSupport.isSharedWithTenant(ec, "SCFG_HOTWAX_OMS", "OMS_HW_1", "BETSEY_JOHNSON"))
        assertFalse(SharedConfigAccessSupport.isSharedWithTenant(ec, "SCFG_HOTWAX_OMS", "OMS_HW_2", "BETSEY_JOHNSON"),
                "revoked")
        assertFalse(SharedConfigAccessSupport.isSharedWithTenant(ec, "SCFG_HOTWAX_OMS", "OMS_HW_1", "THIRD_LOVE"),
                "granted to a different tenant")
    }

    // --- owner-or-shared decision ---------------------------------------

    @Test
    void canActiveTenantUseConfigAllowsTheOwningTenantWithNoGrantRow() {
        def ec = ecWithGrants([], "STEVE_MADDEN")
        def config = [omsRestSourceConfigId: "OMS_HW_1", companyUserGroupId: "STEVE_MADDEN"]

        assertTrue(SharedConfigAccessSupport.canActiveTenantUseConfig(ec, "SCFG_HOTWAX_OMS", config),
                "sharing is additive — an unshared config must behave exactly as it does today")
    }

    @Test
    void canActiveTenantUseConfigAllowsAMemberTenantOfTheSharedGroup() {
        def ec = ecWithGrants([grant("SCFG_HOTWAX_OMS", "OMS_HW_1", "BETSEY_JOHNSON")], "BETSEY_JOHNSON")
        def config = [omsRestSourceConfigId: "OMS_HW_1", companyUserGroupId: "STEVE_MADDEN"]

        assertTrue(SharedConfigAccessSupport.canActiveTenantUseConfig(ec, "SCFG_HOTWAX_OMS", config),
                "this is the whole feature: Betsey Johnson reaching Steve Madden's HotWax config")
    }

    @Test
    void canActiveTenantUseConfigDeniesANonMemberForeignTenant() {
        def ec = ecWithGrants([grant("SCFG_HOTWAX_OMS", "OMS_HW_1", "BETSEY_JOHNSON")], "THIRD_LOVE")
        def config = [omsRestSourceConfigId: "OMS_HW_1", companyUserGroupId: "STEVE_MADDEN"]

        assertFalse(SharedConfigAccessSupport.canActiveTenantUseConfig(ec, "SCFG_HOTWAX_OMS", config),
                "tenant isolation must not loosen for tenants outside the group")
    }

    @Test
    void canActiveTenantUseConfigDeniesNullConfigAndNullActiveTenant() {
        def withTenant = ecWithGrants([], "STEVE_MADDEN")
        assertFalse(SharedConfigAccessSupport.canActiveTenantUseConfig(withTenant, "SCFG_HOTWAX_OMS", null))

        def noTenant = ecWithGrants([grant("SCFG_HOTWAX_OMS", "OMS_HW_1", "BETSEY_JOHNSON")], null)
        def config = [omsRestSourceConfigId: "OMS_HW_1", companyUserGroupId: "STEVE_MADDEN"]
        assertFalse(SharedConfigAccessSupport.canActiveTenantUseConfig(noTenant, "SCFG_HOTWAX_OMS", config),
                "no active tenant means no access — default-deny, same as TenantScopedFinder")
    }

    // --- builders -------------------------------------------------------

    private static Map grant(String type, String configId, String tenant, Timestamp thruDate = null) {
        return [configTypeEnumId: type, configId: configId, tenantUserGroupId: tenant,
                fromDate: PAST, thruDate: thruDate, grantedByUserId: "aditi"]
    }

    private static Expando ecWithGrants(List<Map> grants, String activeTenantUserGroupId = "STEVE_MADDEN") {
        return executionContext(
                user: new UserStub(userId: "aditi", nowTimestamp: NOW,
                        preferences: ["darpan.auth.activeTenantUserGroupId": activeTenantUserGroupId],
                        context: [activeTenantUserGroupId: activeTenantUserGroupId]),
                entity: new EntityFacadeStub(finders: [
                        "darpan.auth.ConfigTenantAccess": new FinderStub(listResult: grants),
                        // REQUIRED: currentActiveTenantUserGroupId resolves through listAvailableTenants,
                        // which reads this view. Without a matching row the active tenant is null and
                        // every owner-or-shared assertion below silently tests the wrong thing.
                        // Both fields must be present — FinderStub.list() filters on the conditions
                        // listTenantMembershipRecords applies.
                        "moqui.security.UserGroupAndMember": new FinderStub(listResult:
                                activeTenantUserGroupId ? [[userId          : "aditi",
                                                            userGroupId     : activeTenantUserGroupId,
                                                            groupTypeEnumId : "UgtDarpanCompany"]] : []),
                ])
        )
    }

    private static Expando executionContext(Map overrides = [:]) {
        return new Expando(
                user   : overrides.user    ?: new UserStub(),
                entity : overrides.entity  ?: new EntityFacadeStub(),
                message: overrides.message ?: new MessageFacadeStub(),
                l10n   : new Expando(timeZone: "UTC"),
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
            if (oneResult instanceof Map && !conditions.every { k, v -> oneResult[k] == v }) return null
            return oneResult
        }

        List list() {
            listResult.findAll { row ->
                if (!(row instanceof Map)) return true
                conditions.every { k, v -> row[k] == v }
            }
        }
    }
}
