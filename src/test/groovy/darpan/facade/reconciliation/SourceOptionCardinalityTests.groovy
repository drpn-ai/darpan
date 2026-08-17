package darpan.facade.reconciliation

import darpan.facade.common.SharedConfigAccessSupport
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.automation.SourceEndpointAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * The wizard filters these lists by the selected systemEnumId. Before this change every row was
 * stamped 'OMS' or 'SHOPIFY', so selecting a returns endpoint filtered to zero options and the
 * connection step rendered empty.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SourceOptionCardinalityTests {
    private ExecutionContext ec
    private static final String CONFIG_ID = "cardinality-oms"
    private static final String TENANT = "CARDINALITY_TENANT"
    // Duplicates ReconciliationSmokeTestSupport's private TEST_COMPANY_USER_ID — same convention
    // SourceEndpointAccessSupportTests already uses since that constant isn't exposed.
    private static final String KREWE_USER_ID = "TEST_CUSTOMER_USER"
    private static final Timestamp TENANT_MEMBER_FROM_DATE = Timestamp.valueOf("2026-01-01 00:00:00")

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "source-option-cardinality")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/DarpanSystemSourceSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SourceSystemConnectorSeedData.xml")

        // listSourceConfigOptions routes through SharedConfigAccessSupport.listAccessibleConfigRows,
        // which conditions on companyUserGroupId == the CALLER's active tenant
        // (TenantAccessSupport.currentActiveTenantUserGroupId). That resolver does NOT just echo back
        // whatever ec.user.setPreference was last called with — it validates the preferred tenant
        // against TenantAccessSupport.listAvailableTenants(ec) (the caller's actual UserGroupMember
        // rows) and silently falls back to the first available tenant when the preferred one isn't a
        // membership the user actually has. So a real UserGroupMember row for TENANT is required, not
        // just the preference call. seedCompanyScope establishes a real logged-in user (defaulting the
        // active tenant to KREWE); this fixture adds membership in its own tenant and then points the
        // preference at it so the config row below is actually visible to the calls under test.
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ec.entity.makeValue("moqui.security.UserGroup")
                .setAll([userGroupId: TENANT, description: "Cardinality smoke-test tenant",
                         groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
                .createOrUpdate()
        ec.entity.makeValue("moqui.security.UserGroupMember")
                .setAll([userGroupId: TENANT, userId: KREWE_USER_ID, fromDate: TENANT_MEMBER_FROM_DATE])
                .createOrUpdate()
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, TENANT)

        // FK prerequisite for SourceConfigEndpointAccess.configTypeEnumId (aDisabledEndpointIsNotOffered
        // writes a row below): normally seeded by data/SecuritySeedData.xml, not loaded here — same
        // minimal-FK convention as SourceEndpointAccessSupportTests ("seed only what the writes below
        // touch, not the whole SecuritySeedData.xml file").
        ec.entity.makeValue("moqui.basic.EnumerationType")
                .setAll([enumTypeId: "DarpanSharedConfigType", description: "Darpan API source config types"])
                .createOrUpdate()
        ec.entity.makeValue("moqui.basic.Enumeration")
                .setAll([enumId: SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, enumTypeId: "DarpanSharedConfigType"])
                .createOrUpdate()

        ec.entity.makeValue("darpan.hotwax.HotWaxOmsRestSourceConfig")
                .setAll([omsRestSourceConfigId: CONFIG_ID, description: "Cardinality",
                         companyUserGroupId   : TENANT, baseUrl: "https://card.example.com",
                         isActive             : "Y", canReadOrders: "Y"]).createOrUpdate()
        ec.message.clearErrors()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @Test
    void oneConfigYieldsOneOptionPerEnabledEndpoint() {
        List<Map<String, Object>> options = AutomationFacadeSupport.listSourceConfigOptions(ec)
                .findAll { it.sourceConfigId == CONFIG_ID }

        assertEquals(["OMS", "OMS_RECON_ORDERS", "OMS_RETURNS", "OMS_TRANSFER_ORDERS"],
                options.collect { it.systemEnumId }.sort())
    }

    @Test
    void eachOptionCarriesItsOwnConnectorFields() {
        Map<String, Object> returnsOption = AutomationFacadeSupport.listSourceConfigOptions(ec)
                .find { it.sourceConfigId == CONFIG_ID && it.systemEnumId == "OMS_RETURNS" }

        assertNotNull(returnsOption, "The returns endpoint must appear as its own option row")
        assertEquals("HOTWAX_OMS_REST_RETURNS", returnsOption.sourceConfigType)
    }

    @Test
    void aDisabledEndpointIsNotOffered() {
        // @TestInstance(PER_CLASS) shares this fixture's ec/DB across every test method with no
        // guaranteed execution order, so a write here must not leak into a sibling test (in
        // particular oneConfigYieldsOneOptionPerEnabledEndpoint, which expects all four endpoints
        // still enabled) — delete the access row again once this test has made its point.
        try {
            ec.entity.makeValue(SourceEndpointAccessSupport.ENTITY_NAME)
                    .setAll([configTypeEnumId  : SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS,
                             configId          : CONFIG_ID, systemEnumId: "OMS_TRANSFER_ORDERS",
                             companyUserGroupId: TENANT, isEnabled: "N"]).createOrUpdate()

            assertTrue(AutomationFacadeSupport.listSourceConfigOptions(ec)
                    .findAll { it.sourceConfigId == CONFIG_ID }
                    .every { it.systemEnumId != "OMS_TRANSFER_ORDERS" })
        } finally {
            ec.entity.find(SourceEndpointAccessSupport.ENTITY_NAME)
                    .condition([configTypeEnumId: SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS,
                                configId         : CONFIG_ID, systemEnumId: "OMS_TRANSFER_ORDERS"])
                    .useCache(false).deleteAll()
        }
    }

    @Test
    void theOmsOrdersOptionIsUnchangedFromBefore() {
        Map<String, Object> ordersOption = AutomationFacadeSupport.listSourceConfigOptions(ec)
                .find { it.sourceConfigId == CONFIG_ID && it.systemEnumId == "OMS" }

        assertEquals("HOTWAX_OMS_REST", ordersOption.sourceConfigType)
        assertEquals(CONFIG_ID, ordersOption.omsRestSourceConfigId)
    }
}
