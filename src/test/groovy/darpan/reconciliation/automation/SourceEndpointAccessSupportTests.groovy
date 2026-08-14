package darpan.reconciliation.automation

import darpan.facade.common.SharedConfigAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path

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
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
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
        // Re-enable everything; the previously written 'N' row must flip back, not linger.
        ec.service.sync().name("facade.SourceEndpointFacadeServices.store#SourceConfigEndpointAccess")
                .parameters([configTypeEnumId    : SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS,
                             configId            : CONFIG_ID,
                             enabledSystemEnumIds: ["OMS", "OMS_RECON_ORDERS", "OMS_RETURNS", "OMS_TRANSFER_ORDERS"]])
                .disableAuthz().call()

        assertTrue(SourceEndpointAccessSupport.isEndpointEnabled(ec,
                SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, CONFIG_ID, "OMS_RETURNS"))
    }
}
