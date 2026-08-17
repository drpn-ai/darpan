package darpan.reconciliation.automation

import darpan.facade.common.SharedConfigAccessSupport
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * The enablement predicate. The load-bearing rule is ABSENT MEANS ENABLED: a connector row shipped
 * in a later release must be usable with no per-tenant backfill, so a missing access row can never
 * read as disabled.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SourceEndpointAccessSupportTests {
    private ExecutionContext ec

    private static final String CONFIG_ID = "endpoint-access-test-oms"
    private static final String TENANT = "ENDPOINT_ACCESS_TENANT"
    // Owner tenant for the store#SourceConfigEndpointAccess tests (Task 3). "KREWE" matches
    // ReconciliationSmokeTestSupport's private TEST_COMPANY_USER_GROUP_ID — same duplicated-literal
    // convention AutomationFacadeSmokeTests already uses, since that constant isn't exposed.
    private static final String KREWE = "KREWE"
    // Same reasoning: "TEST_CUSTOMER_USER" / "test.customer" duplicate ReconciliationSmokeTestSupport's
    // private TEST_COMPANY_USER_ID / TEST_COMPANY_USERNAME — the user seedCompanyScope() logs in as and
    // that owns CONFIG_ID. internalLoginUser looks up by USERNAME, not userId (confirmed: passing the
    // userId logs "No account found for username ..." and silently leaves the PRIOR login active) —
    // seedCompanyScope's own try-userId-then-username fallback is there for exactly this reason.
    private static final String KREWE_USER_ID = "TEST_CUSTOMER_USER"
    private static final String KREWE_USERNAME = "test.customer"

    // Fix round 1: a tenant with NO relationship (no ownership, no ConfigTenantAccess grant) to
    // CONFIG_ID — proves the list-service read gate denies non-peers (finding 1) and the
    // store-service write gate is owner-only, not merely peer-only (finding 3).
    private static final String FOREIGN_TENANT = "ENDPOINT_ACCESS_FOREIGN_TENANT"
    private static final String FOREIGN_USER_ID = "ENDPOINT_ACCESS_FOREIGN_USER"
    private static final String FOREIGN_USERNAME = "endpoint-access-foreign-user"
    private static final Timestamp FOREIGN_TENANT_FROM_DATE = Timestamp.valueOf("2026-01-01 00:00:00")

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "source-endpoint-access")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/DarpanSystemSourceSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SourceSystemConnectorSeedData.xml")

        // FK prerequisites for SourceConfigEndpointAccess (same convention as
        // TenantChatSpaceEntitySmokeTests: seed only what the writes below touch, not the whole
        // SecuritySeedData.xml / DarpanSystemSourceSeedData.xml files).
        // companyUserGroupId -> UserGroup.
        ec.entity.makeValue("moqui.security.UserGroup")
                .setAll([userGroupId: TENANT, description: "Smoke-test tenant for endpoint access"])
                .create()
        // configTypeEnumId -> Enumeration (normally seeded by data/SecuritySeedData.xml, not loaded here).
        ec.entity.makeValue("moqui.basic.EnumerationType")
                .setAll([enumTypeId: "DarpanSharedConfigType", description: "Darpan API source config types"])
                .create()
        ec.entity.makeValue("moqui.basic.Enumeration")
                .setAll([enumId: SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, enumTypeId: "DarpanSharedConfigType"])
                .create()
        // systemEnumId -> Enumeration. unregisteredEndpointIsNeverEnabled writes a systemEnumId that has
        // no SourceSystemConnector row on purpose (that's the point of the test) but the FK still needs
        // an Enumeration row to exist — the catalog of known systems is broader than the operational
        // connector registry.
        ec.entity.makeValue("moqui.basic.Enumeration")
                .setAll([enumId: "OMS_NOT_A_REAL_ENDPOINT", enumTypeId: "DarpanSystemSource"])
                .create()

        // Task 3: store#SourceConfigEndpointAccess authorizes writes by loading the parent config row
        // through TenantScopedFinder.findTenantScopedByIdQuiet, which needs a REAL logged-in tenant
        // owner (not just FK rows) — it resolves the active tenant from the user's own group
        // membership. Reuses the house seedCompanyScope + HotWaxOmsRestSourceConfig convention already
        // used by AutomationFacadeSmokeTests rather than hand-rolling tenant membership again.
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ec.entity.makeValue("darpan.hotwax.HotWaxOmsRestSourceConfig")
                .setAll([omsRestSourceConfigId: CONFIG_ID,
                         description          : "Endpoint-access smoke test OMS config",
                         companyUserGroupId   : KREWE,
                         baseUrl              : "https://oms.endpoint-access-test.invalid"])
                .create()

        // Fix round 1 (findings 1 & 3): a second tenant with NO relationship to CONFIG_ID — not the
        // owner, not a ConfigTenantAccess peer. moqui.basic.EnumerationType "UserGroupType" and
        // moqui.basic.Enumeration "UgtDarpanCompany" already exist from seedCompanyScope() above.
        ec.entity.makeValue("moqui.security.UserGroup")
                .setAll([userGroupId: FOREIGN_TENANT,
                         description: "Smoke-test tenant with no relationship to CONFIG_ID",
                         groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
                .create()
        ec.entity.makeValue("moqui.security.UserAccount")
                .setAll([userId: FOREIGN_USER_ID, username: FOREIGN_USERNAME,
                         userFullName: "Endpoint Access Foreign Tenant User",
                         currentPassword: "", disabled: "N"])
                .create()
        ec.entity.makeValue("moqui.security.UserGroupMember")
                .setAll([userGroupId: FOREIGN_TENANT, userId: FOREIGN_USER_ID, fromDate: FOREIGN_TENANT_FROM_DATE])
                .create()

        // seedCompanyScope() and the foreign-tenant seeding above both switch the logged-in user;
        // leave the fixture in the KREWE-owner state every other test in this file assumes.
        if (!ec.user.internalLoginUser(KREWE_USER_ID)) ec.user.internalLoginUser(KREWE_USERNAME)
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, KREWE)
        ec.message.clearErrors()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    /**
     * Runs {@code work} as {@link #FOREIGN_TENANT} — a tenant with no ownership of and no
     * ConfigTenantAccess grant to {@link #CONFIG_ID} — then restores the {@link #KREWE} owner login
     * so later-running tests (order is not guaranteed under {@code @TestInstance(PER_CLASS)}) see the
     * fixture unchanged.
     *
     * <p>{@code clearErrors()} runs AFTER each login, not before: {@code internalLoginUser} tries a
     * username lookup first and a userId lookup second, and logs a benign
     * {@code ec.message} error for the failed first attempt even when the overall call succeeds via
     * the second. Left uncleared, {@code ServiceCallSyncImpl} sees that stale error as "already
     * failed" and refuses to run the service at all — returning {@code null}, not an error result.</p>
     */
    private <T> T actingAsForeignTenant(Closure<T> work) {
        if (!ec.user.internalLoginUser(FOREIGN_USER_ID)) ec.user.internalLoginUser(FOREIGN_USERNAME)
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, FOREIGN_TENANT)
        ec.message.clearErrors()
        try {
            return work.call()
        } finally {
            if (!ec.user.internalLoginUser(KREWE_USER_ID)) ec.user.internalLoginUser(KREWE_USERNAME)
            ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, KREWE)
            ec.message.clearErrors()
        }
    }

    private void writeAccessRow(String systemEnumId, String isEnabled) {
        ec.entity.makeValue(SourceEndpointAccessSupport.ENTITY_NAME)
                .setAll([configTypeEnumId : SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS,
                         configId          : CONFIG_ID,
                         systemEnumId      : systemEnumId,
                         companyUserGroupId: TENANT,
                         isEnabled         : isEnabled])
                .createOrUpdate()
    }

    @Test
    void absentRowMeansEnabled() {
        assertTrue(SourceEndpointAccessSupport.isEndpointEnabled(ec,
                SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, CONFIG_ID, "OMS_RETURNS"),
                "A config with no access row at all must be able to use OMS_RETURNS")
    }

    @Test
    void explicitNoDisables() {
        writeAccessRow("OMS_TRANSFER_ORDERS", "N")
        assertFalse(SourceEndpointAccessSupport.isEndpointEnabled(ec,
                SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, CONFIG_ID, "OMS_TRANSFER_ORDERS"))
    }

    @Test
    void unregisteredEndpointIsNeverEnabled() {
        // No SourceSystemConnector row exists for this id, so no access row can grant it.
        writeAccessRow("OMS_NOT_A_REAL_ENDPOINT", "Y")
        assertFalse(SourceEndpointAccessSupport.isEndpointEnabled(ec,
                SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, CONFIG_ID, "OMS_NOT_A_REAL_ENDPOINT"),
                "Access rows may only turn an endpoint OFF; they must never add one to the catalog")
    }

    @Test
    void catalogComesFromRegistryFilteredByConfigEntity() {
        List<Map<String, Object>> endpoints = SourceEndpointAccessSupport.listEndpointsForConfig(ec,
                SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, CONFIG_ID)
        assertEquals(["OMS", "OMS_RECON_ORDERS", "OMS_RETURNS", "OMS_TRANSFER_ORDERS"],
                endpoints.collect { it.systemEnumId }.sort())
        assertEquals("Reconciliation Returns API",
                endpoints.find { it.systemEnumId == "OMS_RETURNS" }.endpointLabel)
    }

    @Test
    void catalogDoesNotLeakAcrossConfigTypes() {
        List<Map<String, Object>> endpoints = SourceEndpointAccessSupport.listEndpointsForConfig(ec,
                SharedConfigAccessSupport.CONFIG_TYPE_SHOPIFY_AUTH, "endpoint-access-test-shopify")
        assertEquals(["SHOPIFY", "SHOPIFY_RETURN_REFS"],
                endpoints.collect { it.systemEnumId }.sort())
    }

    @Test
    void registryDisabledConnectorNeverBecomesReachable() {
        // A connector row switched OFF in the registry (enabled="N") must stay unreachable no matter
        // what tenant access data says — tenant rows may only turn an endpoint OFF, never ON.
        String disabledSystemEnumId = "OMS_DISABLED_TEST_ENDPOINT"

        // FK prerequisite: SourceConfigEndpointAccess.systemEnumId -> Enumeration. SourceSystemConnector
        // itself declares no such relationship, so the connector row below needs no enum row on its
        // own — only the writeAccessRow call further down does.
        ec.entity.makeValue("moqui.basic.Enumeration")
                .setAll([enumId: disabledSystemEnumId, enumTypeId: "DarpanSystemSource"])
                .create()

        ec.entity.makeValue(SourceSystemConnectorSupport.ENTITY_NAME)
                .setAll([systemEnumId    : disabledSystemEnumId,
                         configEntityName: "darpan.hotwax.HotWaxOmsRestSourceConfig",
                         endpointLabel   : "Disabled Test Endpoint",
                         enabled         : "N"])
                .create()

        List<Map<String, Object>> endpoints = SourceEndpointAccessSupport.listEndpointsForConfig(ec,
                SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, CONFIG_ID)
        assertFalse(endpoints.any { it.systemEnumId == disabledSystemEnumId },
                "A registry-disabled connector must never appear in the catalog")

        // The point that matters most: even an explicit isEnabled="Y" tenant decision cannot switch
        // on an endpoint the registry itself has disabled.
        writeAccessRow(disabledSystemEnumId, "Y")
        assertFalse(SourceEndpointAccessSupport.isEndpointEnabled(ec,
                SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, CONFIG_ID, disabledSystemEnumId),
                "Tenant data must never switch on a registry-disabled endpoint")
    }

    @Test
    void storeServiceWritesOnlyExplicitDisables() {
        // gorjana-style config: everything on except returns.
        ec.service.sync().name("facade.SourceEndpointFacadeServices.store#SourceConfigEndpointAccess")
                .parameters([configTypeEnumId    : SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS,
                             configId            : CONFIG_ID,
                             enabledSystemEnumIds: ["OMS", "OMS_RECON_ORDERS", "OMS_TRANSFER_ORDERS"]])
                .disableAuthz().call()

        assertFalse(SourceEndpointAccessSupport.isEndpointEnabled(ec,
                SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, CONFIG_ID, "OMS_RETURNS"))
        assertTrue(SourceEndpointAccessSupport.isEndpointEnabled(ec,
                SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, CONFIG_ID, "OMS"))
    }

    @Test
    void storeServiceIsIdempotentAndReEnables() {
        ec.service.sync().name("facade.SourceEndpointFacadeServices.store#SourceConfigEndpointAccess")
                .parameters([configTypeEnumId    : SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS,
                             configId            : CONFIG_ID,
                             enabledSystemEnumIds: ["OMS", "OMS_RECON_ORDERS", "OMS_TRANSFER_ORDERS"]])
                .disableAuthz().call()
        // Fix round 1 (finding 4): absent-means-enabled makes the final assertTrue below pass even if
        // the store service is a total no-op, so prove the FIRST call actually took effect before the
        // re-enabling call would otherwise paper over that.
        assertFalse(SourceEndpointAccessSupport.isEndpointEnabled(ec,
                SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, CONFIG_ID, "OMS_RETURNS"),
                "The first store call must have actually disabled OMS_RETURNS before we can call re-enabling it meaningful")
        // Re-enable everything; the previously written 'N' row must flip back, not linger.
        ec.service.sync().name("facade.SourceEndpointFacadeServices.store#SourceConfigEndpointAccess")
                .parameters([configTypeEnumId    : SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS,
                             configId            : CONFIG_ID,
                             enabledSystemEnumIds: ["OMS", "OMS_RECON_ORDERS", "OMS_RETURNS", "OMS_TRANSFER_ORDERS"]])
                .disableAuthz().call()

        assertTrue(SourceEndpointAccessSupport.isEndpointEnabled(ec,
                SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, CONFIG_ID, "OMS_RETURNS"))
    }

    @Test
    void listServiceDeniesATenantWithNoRelationshipToTheConfig() {
        // Finding 1: list#SourceConfigEndpoints had no tenant scoping at all — any authenticated user
        // of any tenant could pass another tenant's configId and read its endpoint enablement state.
        Map<String, Object> result = actingAsForeignTenant {
            ec.service.sync().name("facade.SourceEndpointFacadeServices.list#SourceConfigEndpoints")
                    .parameters([configTypeEnumId: SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS,
                                 configId         : CONFIG_ID])
                    .disableAuthz().call()
        } as Map<String, Object>

        assertFalse((result.ok as boolean),
                "A tenant with no ownership or shared-peer relationship to the config must be denied")
        assertTrue((result.endpoints as List).isEmpty(),
                "A tenant with no relationship to the config must get no endpoints back, not the catalog")
    }

    @Test
    void storeServiceRejectsANonOwnerTenantAndLeavesRowsUnchanged() {
        // Finding 3: the owner-controlled write gate had no coverage. Assert BOTH the rejection and
        // that the rows are byte-identical afterward — an implementation that errors AFTER writing
        // would still pass an error-only assertion.
        List<Map<String, Object>> before = SourceEndpointAccessSupport.listEndpointsForConfig(ec,
                SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, CONFIG_ID)

        Map<String, Object> result = actingAsForeignTenant {
            // A non-empty, otherwise-plausible payload: an empty List fails required-parameter
            // validation (Moqui treats it as blank) before the service body — and this authorization
            // check — ever runs, which would prove nothing about the owner-only gate.
            ec.service.sync().name("facade.SourceEndpointFacadeServices.store#SourceConfigEndpointAccess")
                    .parameters([configTypeEnumId    : SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS,
                                 configId            : CONFIG_ID,
                                 enabledSystemEnumIds: ["OMS", "OMS_RECON_ORDERS", "OMS_RETURNS", "OMS_TRANSFER_ORDERS"]])
                    .disableAuthz().call()
        } as Map<String, Object>

        assertFalse((result.ok as boolean),
                "A non-owner tenant (no ConfigTenantAccess relationship either) must not be able to store endpoint access")
        assertFalse((result.errors as List).isEmpty(), "The rejection must surface as an error, not a silent no-op")

        List<Map<String, Object>> after = SourceEndpointAccessSupport.listEndpointsForConfig(ec,
                SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, CONFIG_ID)
        assertEquals(before, after,
                "A rejected write by a non-owner tenant must leave every access row exactly as it was")
    }

    @Test
    void deletingAConfigRemovesItsAccessRows() {
        // Task 13: SourceConfigEndpointAccess.configId is polymorphic, so nothing cascades on
        // delete. companyUserGroupId is KREWE (not the file's TENANT constant) because the real
        // delete service authorizes by comparing the config row's companyUserGroupId against the
        // CALLER's currently active tenant (TenantAccessSupport.canAccessTenantRecord) — KREWE is
        // the tenant this fixture is logged in as at the end of setup() and after every other test
        // restores it, so the doomed config must be owned by KREWE to be deletable here at all.
        String doomedId = "endpoint-access-doomed"
        ec.entity.makeValue("darpan.hotwax.HotWaxOmsRestSourceConfig")
                .setAll([omsRestSourceConfigId: doomedId, description: "Doomed",
                         companyUserGroupId   : KREWE, baseUrl: "https://doomed.example.com",
                         isActive             : "Y", canReadOrders: "Y"]).createOrUpdate()
        ec.entity.makeValue(SourceEndpointAccessSupport.ENTITY_NAME)
                .setAll([configTypeEnumId  : SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS,
                         configId          : doomedId, systemEnumId: "OMS_RETURNS",
                         companyUserGroupId: KREWE, isEnabled: "N"]).createOrUpdate()

        // Real service name (verified against HotWaxOmsFacadeServices.xml and the sibling
        // HotWaxOmsRestSourceConfigFacadeSmokeTests.deleteFacade helper): verb="delete"
        // noun="HotWaxOmsRestSourceConfig" -> delete#HotWaxOmsRestSourceConfig, not the brief's
        // guessed delete#OmsRestSourceConfig.
        Map<String, Object> result = ec.service.sync()
                .name("facade.HotWaxOmsFacadeServices.delete#HotWaxOmsRestSourceConfig")
                .parameters([omsRestSourceConfigId: doomedId])
                .disableAuthz().call() as Map<String, Object>

        // Not hollow: prove the delete itself actually succeeded, so a passing access-row count
        // below can't be explained by the delete having been silently rejected instead.
        assertTrue((result.ok as boolean),
                "Delete must succeed for a KREWE-owned config with no active grants: ${result.errors}")
        assertEquals(0L, ec.entity.find(SourceEndpointAccessSupport.ENTITY_NAME)
                .condition("configId", doomedId).useCache(false).count(),
                "A deleted config must not leave access rows for a future config with the same id")
    }
}
