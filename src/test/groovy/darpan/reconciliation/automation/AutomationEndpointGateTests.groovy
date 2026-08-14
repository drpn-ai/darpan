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
import static org.junit.jupiter.api.Assertions.assertNull

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
}
