package darpan.reconciliation.automation

import darpan.facade.common.SharedConfigAccessSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.AutomationFacadeSupport
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
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * THE EXPANDABILITY CONTRACT. A future endpoint must need only data: one Enumeration row, one
 * SourceSystemConnector row, N SourceSystemConnectorField rows. If this test ever requires a
 * production-code change to pass, the contract has been broken and the fix belongs in the code
 * under test, not here.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndpointExpandabilityTests {
    private ExecutionContext ec
    private static final String CONFIG_ID = "expandability-oms"
    private static final String TENANT = "EXPANDABILITY_TENANT"
    private static final String NEW_ENDPOINT = "OMS_SYNTHETIC_SHIPMENTS"
    // Duplicates ReconciliationSmokeTestSupport's private TEST_COMPANY_USER_ID — same convention
    // SourceEndpointAccessSupportTests / SourceOptionCardinalityTests already use since that
    // constant isn't exposed.
    private static final String KREWE_USER_ID = "TEST_CUSTOMER_USER"
    private static final Timestamp TENANT_MEMBER_FROM_DATE = Timestamp.valueOf("2026-01-01 00:00:00")

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "endpoint-expandability")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/DarpanSystemSourceSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SourceSystemConnectorSeedData.xml")

        // AutomationFacadeSupport.listSourceConfigOptions routes through
        // SharedConfigAccessSupport.listAccessibleConfigRows -> TenantScopedFinder.findTenantScoped,
        // which needs a REAL active tenant derived from the caller's own UserGroupMember rows
        // (TenantAccessSupport.currentActiveTenantUserGroupId) — not just a preference value. Same
        // "real membership required" fixture shape SourceOptionCardinalityTests documents.
        // seedCompanyScope establishes a real logged-in user (default active tenant KREWE); this
        // fixture adds membership in its own tenant and points the preference at it so CONFIG_ID
        // (owned by TENANT below) is actually visible to the calls under test.
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ec.entity.makeValue("moqui.security.UserGroup")
                .setAll([userGroupId: TENANT, description: "Expandability smoke-test tenant",
                         groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
                .createOrUpdate()
        ec.entity.makeValue("moqui.security.UserGroupMember")
                .setAll([userGroupId: TENANT, userId: KREWE_USER_ID, fromDate: TENANT_MEMBER_FROM_DATE])
                .createOrUpdate()
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, TENANT)

        // FK prerequisite for SourceConfigEndpointAccess.configTypeEnumId (assertion 4 writes a row
        // below): normally seeded by data/SecuritySeedData.xml, not loaded here — same minimal-FK
        // convention as SourceEndpointAccessSupportTests / SourceOptionCardinalityTests.
        ec.entity.makeValue("moqui.basic.EnumerationType")
                .setAll([enumTypeId: "DarpanSharedConfigType", description: "Darpan API source config types"])
                .createOrUpdate()
        ec.entity.makeValue("moqui.basic.Enumeration")
                .setAll([enumId: SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, enumTypeId: "DarpanSharedConfigType"])
                .createOrUpdate()

        ec.entity.makeValue("darpan.hotwax.HotWaxOmsRestSourceConfig")
                .setAll([omsRestSourceConfigId: CONFIG_ID, description: "Expandability",
                         companyUserGroupId   : TENANT, baseUrl: "https://exp.example.com",
                         isActive             : "Y", canReadOrders: "Y"]).createOrUpdate()

        // DATA ONLY — the three rows a new endpoint ships as.
        ec.entity.makeValue("moqui.basic.Enumeration")
                .setAll([enumId      : NEW_ENDPOINT, enumTypeId: "DarpanSystemSource",
                         enumCode    : "HOTWAX_SYNTHETIC_SHIPMENTS",
                         description : "HotWax Shipments (synthetic)", sequenceNum: 99,
                         parentEnumId: "OMS"]).createOrUpdate()
        ec.entity.makeValue("darpan.reconciliation.SourceSystemConnector")
                .setAll([systemEnumId            : NEW_ENDPOINT,
                         extractServiceName      : "reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsSynthetic",
                         dateFromParameterName   : "windowStart", dateToParameterName: "windowEnd",
                         expectedSourceConfigType: "HOTWAX_OMS_REST_SYNTHETIC",
                         configParameterName     : "omsRestSourceConfigId",
                         configEntityName        : "darpan.hotwax.HotWaxOmsRestSourceConfig",
                         remoteId                : "HOTWAX_ORDERS_API",
                         endpointLabel           : "Synthetic Shipments API",
                         enabled                 : "Y"]).createOrUpdate()
        ec.entity.makeValue("darpan.reconciliation.SourceSystemConnectorField")
                .setAll([systemEnumId: NEW_ENDPOINT, fieldPath: "\$.records[*].shipmentId",
                         label       : "Shipment ID", fieldType: "string",
                         isPrimaryIdCandidate: "Y", sequenceNum: 1,
                         description : "Synthetic endpoint identity."]).createOrUpdate()

        ec.message.clearErrors()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @Test
    void theNewEndpointAppearsInTheSettingsPanel() {
        List<Map<String, Object>> endpoints = SourceEndpointAccessSupport.listEndpointsForConfig(ec,
                SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, CONFIG_ID)
        Map<String, Object> row = endpoints.find { it.systemEnumId == NEW_ENDPOINT }
        assertNotNull(row, "A registry row alone must surface the endpoint in the settings panel")
        assertEquals("Synthetic Shipments API", row.endpointLabel)
        assertTrue(row.isEnabled as boolean, "Absent access row must mean enabled")
    }

    @Test
    void theNewEndpointIsOfferedAsASourceOption() {
        Map<String, Object> option = AutomationFacadeSupport.listSourceConfigOptions(ec)
                .find { it.sourceConfigId == CONFIG_ID && it.systemEnumId == NEW_ENDPOINT }
        assertNotNull(option, "The wizard must offer the new endpoint with no code change")
        assertEquals("HOTWAX_OMS_REST_SYNTHETIC", option.sourceConfigType)
    }

    @Test
    void theNewEndpointOffersItsPills() {
        assertEquals(["\$.records[*].shipmentId"],
                AutomationFacadeSupport.primaryIdOptionsForSystem(ec, NEW_ENDPOINT).collect { it.fieldPath })
    }

    @Test
    void theNewEndpointIsGatedLikeAnyOther() {
        // @TestInstance(PER_CLASS) shares this fixture's ec/DB across every test method with no
        // guaranteed execution order (SourceOptionCardinalityTests hit exactly this hazard) — write
        // the disable, make the assertion, then delete the row again so assertions 1-3 above can
        // never observe it regardless of ordering.
        try {
            ec.entity.makeValue(SourceEndpointAccessSupport.ENTITY_NAME)
                    .setAll([configTypeEnumId  : SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS,
                             configId          : CONFIG_ID, systemEnumId: NEW_ENDPOINT,
                             companyUserGroupId: TENANT, isEnabled: "N"]).createOrUpdate()

            assertFalse(SourceEndpointAccessSupport.isEndpointEnabled(ec,
                    SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, CONFIG_ID, NEW_ENDPOINT))
        } finally {
            ec.entity.find(SourceEndpointAccessSupport.ENTITY_NAME)
                    .condition([configTypeEnumId: SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS,
                                configId         : CONFIG_ID, systemEnumId: NEW_ENDPOINT])
                    .useCache(false).deleteAll()
        }
    }
}
