package darpan.reconciliation.automation

import darpan.facade.common.SharedConfigAccessSupport
import darpan.facade.reconciliation.ReconciliationSavedRunSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Auto-resolution must consider a config eligible per ENDPOINT. A tenant with one config that has
 * returns disabled must not have it silently auto-selected for a returns automation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutomationEndpointGateTests {
    private ExecutionContext ec

    // Reuses the house seedCompanyScope + HotWaxOmsRestSourceConfig convention
    // (SourceEndpointAccessSupportTests does the same) instead of hand-rolling a bespoke tenant's
    // UserGroup + login FK prerequisites. "KREWE" duplicates ReconciliationSmokeTestSupport's private
    // TEST_COMPANY_USER_GROUP_ID, which isn't exposed to tests.
    private static final String TENANT = "KREWE"
    private static final String CONFIG_ID = "auto-gate-oms"
    // Deliberately the mirror image of CONFIG_ID: parent OMS endpoint disabled, OMS_RETURNS
    // explicitly enabled. Proves the dispatch fix checks the run's OWN endpoint, not a naive
    // parent/base-system lookup. Created and torn down INSIDE the one test that needs it (not in
    // @BeforeAll) — a second KREWE-owned config sitting in @BeforeAll state would make
    // findSingleActiveConfigId's auto-detect query in refusesToResolveForADisabledEndpoint() no
    // longer ambiguous-when-it-should-be (that test relies on TENANT owning EXACTLY one config).
    private static final String CONFIG_ID_PARENT_DISABLED = "auto-gate-oms-parent-disabled"

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "automation-endpoint-gate")
        // DarpanSystemSourceSeedData supplies the systemEnumId -> Enumeration FK targets ("OMS",
        // "OMS_RETURNS", ...); SourceSystemConnectorSeedData supplies the connector catalog rows that
        // are the whole basis of SourceEndpointAccessSupport.isEndpointEnabled's "the catalog is the
        // registry" behavior — without it every endpoint reads as disabled regardless of test intent.
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/DarpanSystemSourceSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SourceSystemConnectorSeedData.xml")

        // FK prerequisite not covered by either seed file above: SourceConfigEndpointAccess.configTypeEnumId
        // -> Enumeration. Normally seeded by data/SecuritySeedData.xml, which is not auto-loaded here
        // (same gap SourceEndpointAccessSupportTests documents and works around).
        ec.entity.makeValue("moqui.basic.EnumerationType")
                .setAll([enumTypeId: "DarpanSharedConfigType", description: "Darpan API source config types"])
                .create()
        ec.entity.makeValue("moqui.basic.Enumeration")
                .setAll([enumId: SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, enumTypeId: "DarpanSharedConfigType"])
                .create()

        // seedCompanyScope() creates the KREWE UserGroup and logs a test user in as its member — the
        // companyUserGroupId -> UserGroup FK both HotWaxOmsRestSourceConfig and
        // SourceConfigEndpointAccess require, and an authenticated principal for the plain
        // (non-disableAuthz) entity reads inside SourceEndpointAccessSupport to run under.
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)

        ec.entity.makeValue("darpan.hotwax.HotWaxOmsRestSourceConfig")
                .setAll([omsRestSourceConfigId: CONFIG_ID, description: "Auto gate test config",
                         companyUserGroupId   : TENANT, baseUrl: "https://auto-gate.example.invalid",
                         isActive             : "Y", canReadOrders: "Y"])
                .createOrUpdate()
        // Returns explicitly disabled for this config. Every other registered OMS endpoint (OMS,
        // OMS_TRANSFER_ORDERS, OMS_RECON_ORDERS) stays absent from this table entirely — proving the
        // absent-means-enabled default rather than requiring an explicit 'Y' row for each.
        ec.entity.makeValue(SourceEndpointAccessSupport.ENTITY_NAME)
                .setAll([configTypeEnumId : SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS,
                         configId          : CONFIG_ID,
                         systemEnumId      : "OMS_RETURNS",
                         companyUserGroupId: TENANT,
                         isEnabled         : "N"])
                .createOrUpdate()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @Test
    void resolvesForAnEnabledEndpoint() {
        assertEquals(CONFIG_ID, AutomationExecutionSupport.findSingleActiveConfigId(ec,
                TENANT, "darpan.hotwax.HotWaxOmsRestSourceConfig", "omsRestSourceConfigId", "OMS"),
                "OMS has no access row for this config, so absent-means-enabled must let auto-detect find it")
    }

    @Test
    void refusesToResolveForADisabledEndpoint() {
        assertNull(AutomationExecutionSupport.findSingleActiveConfigId(ec,
                TENANT, "darpan.hotwax.HotWaxOmsRestSourceConfig", "omsRestSourceConfigId", "OMS_RETURNS"),
                "A config with returns disabled must not be auto-selected for a returns run")
    }

    @Test
    void savedRunGuardRefusesADisabledEndpoint() {
        ec.message.clearErrors()
        // Build the smallest saved-run shape the guard reads: config row plus the side's system.
        def config = ec.entity.find("darpan.hotwax.HotWaxOmsRestSourceConfig")
                .condition("omsRestSourceConfigId", CONFIG_ID).useCache(false).one()
        ReconciliationSavedRunSupport.requireEndpointEnabledForSide(ec,
                SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, CONFIG_ID, "OMS_RETURNS")
        assertTrue(ec.message.hasError())
        ec.message.clearErrors()

        ReconciliationSavedRunSupport.requireEndpointEnabledForSide(ec,
                SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, CONFIG_ID, "OMS")
        assertFalse(ec.message.hasError())
        ec.message.clearErrors()
    }

    // --- Fix round 1: the dispatch switch in resolveApiSourceConfig, not just the shared helper ---
    //
    // requireEndpointEnabledForSide (proven above) is correct in isolation, but both call sites in
    // validateShopifyAuthConfig/validateHotWaxOmsConfig were passing a HARDCODED constant
    // (SYSTEM_SHOPIFY / SYSTEM_HOTWAX_OMS), not the run's own systemEnumId — so an OMS_RETURNS run
    // was validated against "OMS". Worse, the dispatch switch in resolveApiSourceConfig had no case
    // for OMS_RETURNS / SHOPIFY_RETURN_REFS at all, so those runs reached NO validator whatsoever.
    // These tests exercise the real entry point, resolveApiSourceConfig, not the helper directly.

    @Test
    void resolveApiSourceConfigRefusesAReturnsRunWhoseReturnsEndpointIsDisabled() {
        ec.message.clearErrors()
        ReconciliationSavedRunSupport.resolveApiSourceConfig(ec, "File 1",
                ReconciliationSavedRunSupport.SYSTEM_HOTWAX_OMS_RETURNS, CONFIG_ID, null)
        assertTrue(ec.message.hasError(),
                "OMS_RETURNS is explicitly disabled for this config — a run naming OMS_RETURNS must be refused, " +
                "not silently validated against the unrelated OMS endpoint")
        ec.message.clearErrors()
    }

    @Test
    void resolveApiSourceConfigPermitsAReturnsRunWhenOnlyTheParentSystemIsDisabled() {
        // Self-contained fixture (create + delete inside this test, not @BeforeAll): OMS disabled,
        // OMS_RETURNS explicitly enabled. A dispatch bug that validates every run against a fixed
        // constant (or a "parent endpoint" lookup) would refuse this — proving the fix checks the
        // run's OWN named endpoint, not its base system. Kept out of @BeforeAll because a second
        // KREWE-owned config there would make refusesToResolveForADisabledEndpoint's auto-detect
        // query no longer ambiguous-when-it-should-be.
        try {
            ec.entity.makeValue("darpan.hotwax.HotWaxOmsRestSourceConfig")
                    .setAll([omsRestSourceConfigId: CONFIG_ID_PARENT_DISABLED, description: "Parent-disabled gate test config",
                             companyUserGroupId   : TENANT, baseUrl: "https://auto-gate-parent-disabled.example.invalid",
                             isActive             : "Y", canReadOrders: "Y"])
                    .createOrUpdate()
            ec.entity.makeValue(SourceEndpointAccessSupport.ENTITY_NAME)
                    .setAll([configTypeEnumId : SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS,
                             configId          : CONFIG_ID_PARENT_DISABLED,
                             systemEnumId      : "OMS",
                             companyUserGroupId: TENANT,
                             isEnabled         : "N"])
                    .createOrUpdate()
            ec.entity.makeValue(SourceEndpointAccessSupport.ENTITY_NAME)
                    .setAll([configTypeEnumId : SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS,
                             configId          : CONFIG_ID_PARENT_DISABLED,
                             systemEnumId      : "OMS_RETURNS",
                             companyUserGroupId: TENANT,
                             isEnabled         : "Y"])
                    .createOrUpdate()

            ec.message.clearErrors()
            ReconciliationSavedRunSupport.resolveApiSourceConfig(ec, "File 1",
                    ReconciliationSavedRunSupport.SYSTEM_HOTWAX_OMS_RETURNS, CONFIG_ID_PARENT_DISABLED, null)
            assertFalse(ec.message.hasError(),
                    "OMS_RETURNS is enabled for this config even though its parent OMS endpoint is disabled")
            ec.message.clearErrors()
        } finally {
            ec.entity.find(SourceEndpointAccessSupport.ENTITY_NAME)
                    .condition("configTypeEnumId", SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS)
                    .condition("configId", CONFIG_ID_PARENT_DISABLED)
                    .deleteAll()
            ec.entity.find("darpan.hotwax.HotWaxOmsRestSourceConfig")
                    .condition("omsRestSourceConfigId", CONFIG_ID_PARENT_DISABLED)
                    .deleteAll()
        }
    }

    @Test
    void resolveApiSourceConfigStillPermitsAPlainOmsRun() {
        ec.message.clearErrors()
        // Unchanged coverage: a run naming the base OMS system must keep working through the same
        // dispatch path post-fix. CONFIG_ID has no access row for OMS at all (absent means enabled).
        ReconciliationSavedRunSupport.resolveApiSourceConfig(ec, "File 1",
                ReconciliationSavedRunSupport.SYSTEM_HOTWAX_OMS, CONFIG_ID, null)
        assertFalse(ec.message.hasError(),
                "a plain OMS run must behave exactly as it did before the registry-driven dispatch fix")
        ec.message.clearErrors()
    }
}
