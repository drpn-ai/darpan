package darpan.facade.settings

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * OC123 (DAR-OPS-029 S27): a NetSuite endpoint's custom headers can carry a credential, so list#NsRestletConfigs gives
 * their VALUES only to those who may save the endpoint (run access, as save#NsRestletConfig and the app's connection
 * pages); a view-only member sees the header names, as the OMS REST connection shows customHeaderNames.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SharedMoqui
class NsRestletConfigHeadersSmokeTests {
    private static final String TENANT = "KREWE"
    private static final String USER_ID = "TEST_CUSTOMER_USER"
    private static final String AUTH_ID = "OC123_AUTH"
    private static final String ENDPOINT_ID = "OC123_ENDPOINT"
    private static final String HEADERS = '{"X-Api-Key":"secret-value","Accept":"application/json"}'

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "ns-restlet-headers-smoke")
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void seed() {
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)   // the KREWE test user, as an editor
        ec.entity.makeValue("darpan.reconciliation.NsAuthConfig").setAll([nsAuthConfigId: AUTH_ID, companyUserGroupId: TENANT,
                description: "OC123 auth", authType: "BEARER", apiToken: "token", isActive: "Y"]).createOrUpdate()
        ec.entity.makeValue("darpan.reconciliation.NsRestletConfig").setAll([nsRestletConfigId: ENDPOINT_ID, companyUserGroupId: TENANT,
                description: "OC123 endpoint", endpointUrl: "https://1234567.suitetalk.api.netsuite.com/restlet", httpMethod: "POST",
                nsAuthConfigId: AUTH_ID, headersJson: HEADERS, isActive: "Y"]).createOrUpdate()
        ec.message.clearErrors()
    }

    @AfterEach
    void removeRows() {
        actAs("DARPAN_COMPANY_EDITOR")
        ec.entity.find("darpan.reconciliation.NsRestletConfig").condition("nsRestletConfigId", ENDPOINT_ID).disableAuthz().deleteAll()
        ec.entity.find("darpan.reconciliation.NsAuthConfig").condition("nsAuthConfigId", AUTH_ID).disableAuthz().deleteAll()
    }

    @Test
    void whoMaySaveTheEndpointReadsItsHeaderValues() {
        ["DARPAN_COMPANY_EDITOR", "DARPAN_TENANT_USER"].each { String role ->
            actAs(role)
            Map row = listedEndpoint()
            assertEquals(HEADERS, row.headersJson, "${role} manages connections, so it reads the values")
            assertEquals(["X-Api-Key", "Accept"], row.headerNames, role)
        }
    }

    @Test
    void aViewOnlyMemberSeesOnlyTheHeaderNames() {
        actAs("DARPAN_COMPANY_VIEW_ONLY")
        Map row = listedEndpoint()
        assertNull(row.headersJson, "a viewer must not receive header values")
        assertEquals(["X-Api-Key", "Accept"], row.headerNames, "a viewer still sees which headers are set")
    }

    private Map listedEndpoint() {
        Map out = ec.service.sync().name("facade.SettingsFacadeServices.list#NsRestletConfigs").parameters([:]).disableAuthz().call()
        assertTrue(out.ok as boolean, "${out.errors}")
        return (Map) (out.restletConfigs as List<Map>).find { it.nsRestletConfigId == ENDPOINT_ID }
    }

    /** The KREWE test user holds exactly this tenant role. */
    private void actAs(String roleId) {
        ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: roleId, groupTypeEnumId: "UgtDarpanPermission"]).createOrUpdate()
        ec.entity.find("darpan.auth.TenantUserPermissionGroupMember").condition("tenantUserGroupId", TENANT).condition("userId", USER_ID)
                .disableAuthz().deleteAll()
        ec.entity.makeValue("darpan.auth.TenantUserPermissionGroupMember").setAll([tenantUserGroupId: TENANT, userId: USER_ID,
                permissionUserGroupId: roleId, fromDate: Timestamp.valueOf("2026-04-21 00:00:00")]).create()
    }
}
