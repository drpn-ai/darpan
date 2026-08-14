package darpan.facade.settings

import darpan.facade.common.SharedConfigAccessSupport
import darpan.reconciliation.automation.SourceEndpointAccessSupport
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
 * canReadOrders='Y' must produce NO rows — absent already means enabled, and writing them would
 * make every future endpoint need a per-tenant backfill, which is exactly what the opt-out default
 * exists to avoid.
 *
 * <p>FK note: {@code SourceConfigEndpointAccess} AND the source config entities themselves
 * (HotWaxOmsRestSourceConfig, ShopifyAuthConfig) each declare real {@code type="one"} relationships
 * to {@code moqui.security.UserGroup} / {@code moqui.basic.Enumeration} — Moqui auto-creates DB-level
 * foreign keys for every such relationship, so the parent rows below are seeded first, matching the
 * convention in {@code SourceEndpointAccessSupportTests.setup()}.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SourceEndpointAccessMigrationTests {
    private ExecutionContext ec

    private static final String MIG_TENANT = "MIG_TENANT"

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "source-endpoint-access-migration")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/DarpanSystemSourceSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SourceSystemConnectorSeedData.xml")

        // companyUserGroupId -> UserGroup, on both SourceConfigEndpointAccess AND the two config
        // entities created below.
        ec.entity.makeValue("moqui.security.UserGroup")
                .setAll([userGroupId: MIG_TENANT, description: "Smoke-test tenant for the canReadOrders migration"])
                .create()
        // configTypeEnumId -> Enumeration (normally seeded by data/SecuritySeedData.xml, not loaded here;
        // same gap Task 3's SourceEndpointAccessSupportTests.setup() documents and works around).
        ec.entity.makeValue("moqui.basic.EnumerationType")
                .setAll([enumTypeId: "DarpanSharedConfigType", description: "Darpan API source config types"])
                .create()
        ec.entity.makeValue("moqui.basic.Enumeration")
                .setAll([enumId: SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, enumTypeId: "DarpanSharedConfigType"])
                .create()
        ec.entity.makeValue("moqui.basic.Enumeration")
                .setAll([enumId: SharedConfigAccessSupport.CONFIG_TYPE_SHOPIFY_AUTH, enumTypeId: "DarpanSharedConfigType"])
                .create()

        ec.entity.makeValue("darpan.hotwax.HotWaxOmsRestSourceConfig")
                .setAll([omsRestSourceConfigId: "mig-open", description: "Open",
                         companyUserGroupId   : MIG_TENANT, baseUrl: "https://open.example.com",
                         isActive             : "Y", canReadOrders: "Y"]).createOrUpdate()
        ec.entity.makeValue("darpan.hotwax.HotWaxOmsRestSourceConfig")
                .setAll([omsRestSourceConfigId: "mig-closed", description: "Closed",
                         companyUserGroupId   : MIG_TENANT, baseUrl: "https://closed.example.com",
                         isActive             : "Y", canReadOrders: "N"]).createOrUpdate()

        // Null-default risk case: a Shopify row whose canReadOrders is genuinely NULL in the DB — the
        // shape real legacy data takes when a row predates a backfill. A normal .create()/.createOrUpdate()
        // would silently fill the entity's own default ('N') via Moqui's checkSetFieldDefaults, which
        // would defeat the point of this fixture (it would never actually exercise the null branch).
        // insertEntityDirect issues a raw MERGE INTO instead, bypassing that default-fill.
        ReconciliationSmokeTestSupport.insertEntityDirect(ec, "darpan.shopify.ShopifyAuthConfig",
                [shopifyAuthConfigId: "mig-shopify-null", description: "Null legacy",
                 companyUserGroupId : MIG_TENANT, shopApiUrl: "https://null-legacy.example.com",
                 apiVersion         : "2024-01", isActive: "Y"])
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    private void runMigration() {
        ec.service.sync().name("facade.SourceEndpointFacadeServices.migrate#SourceConfigEndpointAccess")
                .disableAuthz().call()
    }

    private long accessRowCount(String configId) {
        return ec.entity.find(SourceEndpointAccessSupport.ENTITY_NAME)
                .condition("configId", configId).useCache(false).count()
    }

    @Test
    void openConfigGetsNoRowsAndStaysFullyEnabled() {
        runMigration()
        assertEquals(0L, accessRowCount("mig-open"),
                "canReadOrders='Y' must write nothing — absent already means enabled")
        assertTrue(SourceEndpointAccessSupport.isEndpointEnabled(ec,
                SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, "mig-open", "OMS_RETURNS"))
    }

    @Test
    void closedConfigIsDisabledOnEveryEndpoint() {
        runMigration()
        assertEquals(4L, accessRowCount("mig-closed"))
        ["OMS", "OMS_RECON_ORDERS", "OMS_RETURNS", "OMS_TRANSFER_ORDERS"].each { String systemEnumId ->
            assertFalse(SourceEndpointAccessSupport.isEndpointEnabled(ec,
                    SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, "mig-closed", systemEnumId),
                    "${systemEnumId} must be disabled for a canReadOrders='N' config")
        }
    }

    @Test
    void reRunningWritesNoDuplicates() {
        runMigration()
        runMigration()
        assertEquals(4L, accessRowCount("mig-closed"))
    }

    @Test
    void nullCanReadOrdersOnAShopifyConfigIsTreatedAsTheEntitysOwnDisabledDefault() {
        // Shopify's own entity default is 'N' (opt-in), unlike HotWax OMS's 'Y' (opt-out) — a raw NULL
        // must resolve to THAT entity's default, not fall open to enabled. This is the case flagged as
        // most likely to be silently wrong: a naive null-check that always falls back to 'Y' (or to
        // whichever type happens to be checked first) would leave a legacy-closed Shopify config fully
        // open after migration.
        runMigration()
        assertEquals(2L, accessRowCount("mig-shopify-null"))
        ["SHOPIFY", "SHOPIFY_RETURN_REFS"].each { String systemEnumId ->
            assertFalse(SourceEndpointAccessSupport.isEndpointEnabled(ec,
                    SharedConfigAccessSupport.CONFIG_TYPE_SHOPIFY_AUTH, "mig-shopify-null", systemEnumId),
                    "${systemEnumId} must be disabled — a NULL canReadOrders must resolve to Shopify's own 'N' default")
        }
    }
}
