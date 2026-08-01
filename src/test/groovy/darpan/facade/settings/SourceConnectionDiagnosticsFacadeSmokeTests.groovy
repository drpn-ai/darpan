package darpan.facade.settings

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Gate coverage for facade.SettingsFacadeServices.test#SourceConnection.
 *
 * Every path here returns BEFORE a probe is dispatched (or dispatches deliberately into a missing
 * service), so no connector component and no network are involved. Connector-specific probe
 * behaviour is covered in shopify-darpan / darpan-hotwax.
 *
 * The fixtures use core's own tenant-scoped SftpServer as the stand-in config entity, so this test
 * exercises the registry seam without depending on a connector component being installed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SourceConnectionDiagnosticsFacadeSmokeTests {
    private static final String SERVICE = "facade.SettingsFacadeServices.test#SourceConnection"
    private static final String CONNECTOR_ENTITY = "darpan.reconciliation.SourceSystemConnector"
    // Mirrors the private fixture constants in ReconciliationSmokeTestSupport.seedCompanyScope.
    private static final String TENANT_ID = "KREWE"
    private static final String TEST_USER_ID = "TEST_CUSTOMER_USER"
    private static final String FOREIGN_TENANT_ID = "DIAG_OTHER_TENANT"

    /** Passes the probe fence, but names a service that does not exist — exercises the dispatch guard. */
    private static final String GOOD_SHAPE_MISSING_SERVICE = "facade.FakeFacadeServices.probe#FakeConnection"
    /** An extract service: registered, real, and correctly refused by the fence. */
    private static final String FENCE_VIOLATING_SERVICE = "reconciliation.ShopifyOrderExtractionServices.extract#ShopifyOrders"

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "source-connection-diagnostics-smoke")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SourceSystemConnectorSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)

        upsertConnector("DIAGSYS_OK", GOOD_SHAPE_MISSING_SERVICE)
        upsertConnector("DIAGSYS_BADFENCE", FENCE_VIOLATING_SERVICE)
        upsertConnector("DIAGSYS_NOPROBE", null)

        // Tenant-owned config the diagnostics can resolve, plus one owned by a different tenant.
        // SftpServer.companyUserGroupId is FK-checked, so the foreign tenant needs a real UserGroup.
        upsert("moqui.security.UserGroup", [
                userGroupId    : FOREIGN_TENANT_ID,
                description    : "Diagnostics foreign-tenant fixture",
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID,
        ])
        upsertSftpServer("DIAG_OWNED", TENANT_ID)
        upsertSftpServer("DIAG_FOREIGN", FOREIGN_TENANT_ID)
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void resetTenantScope() {
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
    }

    @Test
    void dispatchFailureReportsAVerdictRatherThanThrowing() {
        // The registry row is well-formed and passes the fence, but the probe service is absent.
        // An operator must still get a rendered FAIL row, never a stack trace.
        Map<String, Object> result = call("DIAGSYS_OK", "DIAG_OWNED")

        assertTrue(result.ok as boolean, "the diagnostic itself ran: ${result.errors}")
        assertTrue(result.available as boolean)
        assertFalse(result.connectionOk as boolean)
        List checks = (List) result.checks
        assertEquals(1, checks.size())
        assertEquals(SourceConnectionDiagnosticsSupport.STATUS_FAIL, checks[0].status)
        // No exception text leaks into the operator-visible detail.
        assertFalse((checks[0].detail as String).toLowerCase().contains("exception"))
    }

    @Test
    void fenceViolatingRegistryRowIsNeverDispatched() {
        Map<String, Object> result = call("DIAGSYS_BADFENCE", "DIAG_OWNED")

        assertTrue(result.ok as boolean)
        assertFalse(result.available as boolean, "a fence-rejected probe must report unavailable")
        assertFalse(result.connectionOk as boolean)
        assertTrue(((List) result.checks).isEmpty())
    }

    @Test
    void connectorWithoutAProbeReportsUnavailable() {
        Map<String, Object> result = call("DIAGSYS_NOPROBE", "DIAG_OWNED")
        assertTrue(result.ok as boolean)
        assertFalse(result.available as boolean)

        // The shipped NETSUITE row is the real-world instance of this: registered, but no probe.
        Map<String, Object> netsuite = call("NETSUITE", "DIAG_OWNED")
        assertTrue(netsuite.ok as boolean)
        assertFalse(netsuite.available as boolean)
    }

    @Test
    void unknownSystemReportsUnavailable() {
        Map<String, Object> result = call("NO_SUCH_SYSTEM", "DIAG_OWNED")
        assertTrue(result.ok as boolean)
        assertFalse(result.available as boolean)
        assertTrue(((List) result.checks).isEmpty())
    }

    @Test
    void foreignTenantConfigIsRefusedIdenticallyToAMissingOne() {
        Map<String, Object> foreign = call("DIAGSYS_OK", "DIAG_FOREIGN")
        assertFalse(foreign.ok as boolean)
        String foreignError = ((List) foreign.errors).join(" ")
        ec.message.clearErrors()

        Map<String, Object> missing = call("DIAGSYS_OK", "NO_SUCH_CONFIG_AT_ALL")
        assertFalse(missing.ok as boolean)
        String missingError = ((List) missing.errors).join(" ")
        ec.message.clearErrors()

        // Identical wording: the response must not reveal that the foreign config exists.
        assertEquals(missingError, foreignError)
    }

    @Test
    void readOnlyTenantCannotRunDiagnostics() {
        // Diagnostics spend a real credential against a real remote system, so they are gated like
        // edit/delete rather than like a view.
        makeActiveTenantReadOnly()

        Map<String, Object> result = call("DIAGSYS_OK", "DIAG_OWNED")
        assertFalse(result.ok as boolean)
        assertTrue(((List) result.errors).join(" ").toLowerCase().contains("read-only"))
        assertTrue(((List) result.checks).isEmpty())
        ec.message.clearErrors()
    }

    @Test
    void blankInputsAreRejected() {
        Map<String, Object> noSystem = callRaw([systemEnumId: " ", configId: "DIAG_OWNED"])
        assertFalse(noSystem.ok as boolean)
        ec.message.clearErrors()

        Map<String, Object> noConfig = callRaw([systemEnumId: "DIAGSYS_OK", configId: " "])
        assertFalse(noConfig.ok as boolean)
        ec.message.clearErrors()
    }

    private Map<String, Object> call(String systemEnumId, String configId) {
        return callRaw([systemEnumId: systemEnumId, configId: configId])
    }

    private Map<String, Object> callRaw(Map<String, Object> parameters) {
        return (Map<String, Object>) ec.service.sync()
                .name(SERVICE)
                .parameters(parameters)
                .disableAuthz()
                .call()
    }

    private void upsertConnector(String systemEnumId, String healthCheckServiceName) {
        upsert(CONNECTOR_ENTITY, [
                systemEnumId            : systemEnumId,
                expectedSourceConfigType: "${systemEnumId}_TYPE".toString(),
                configParameterName     : "sftpServerId",
                configEntityName        : "darpan.reconciliation.SftpServer",
                healthCheckServiceName  : healthCheckServiceName,
                systemAliases           : systemEnumId,
                enabled                 : "Y",
        ])
    }

    private void upsertSftpServer(String sftpServerId, String companyUserGroupId) {
        upsert("darpan.reconciliation.SftpServer", [
                sftpServerId      : sftpServerId,
                description       : "Diagnostics fixture ${sftpServerId}".toString(),
                companyUserGroupId: companyUserGroupId,
                host              : "sftp.example.com",
                port              : 22,
                username          : "fixture",
        ])
    }

    private void upsert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }

    /**
     * Strip the active tenant's permission-group membership so the user keeps tenant context but
     * loses write access — the read-only operator shape.
     */
    private void makeActiveTenantReadOnly() {
        ec.entity.find(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME)
                .condition("tenantUserGroupId", TENANT_ID)
                .condition("userId", TEST_USER_ID)
                .disableAuthz()
                .deleteAll()
    }
}
