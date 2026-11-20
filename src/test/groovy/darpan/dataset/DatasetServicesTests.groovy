package darpan.dataset

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 plan 3a, Task 3. A Dataset is one tenant's kept copy of one source read; its reads are the log. Authz is ON
 * with the facade grant, so DARPAN_ACTIVE_COMPANY_SCOPE is live; a foreign datasetId reads as "does not exist".
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatasetServicesTests {

    private static final String A = "KREWE"
    private static final String B = "GORJANA"
    private static final String PREF_KEY = TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY
    private static final String EDITOR_ID = "TEST_CUSTOMER_USER"
    private static final String EDITOR_USERNAME = "test.customer"
    private static final Timestamp FROM_DATE = Timestamp.valueOf("2026-04-21 00:00:00")
    private static final String FACADE_GRANT = "facade.ReconciliationFacadeServices.list#Reconciliations"
    private static final String SERVICES = "dataset.DatasetServices"

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "dataset-services")
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.loadSeedData(ec, "classpath://data/EntityTypeData.xml", "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DatasetSeedData.xml")
        // store#Dataset checks the system's connector and its extract service (I1)
        ec.entity.makeDataLoader().location("component://darpan/data/SourceSystemConnectorSeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        insert("moqui.security.UserGroup", [userGroupId: B, description: "Gorjana",
                                            groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        insert("moqui.security.UserGroupMember", [userGroupId: B, userId: EDITOR_ID, fromDate: FROM_DATE])
        insert(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: B, userId: EDITOR_ID,
                permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, fromDate: FROM_DATE])
        insert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: EDITOR_ID, fromDate: FROM_DATE])
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void actAsTenantA() {
        actAs(EDITOR_USERNAME, A)
    }

    @Test
    void storeIsIdempotentOnTheKey() {
        Map first = call("store#Dataset", base("conn-1", [status: "open", first: 5]))
        assertEquals([], first.errors)
        String id = first.out.datasetId
        assertNotNull(id)
        Map again = call("store#Dataset", base("conn-1", [status: "open", first: 5]))
        assertEquals(id, again.out.datasetId)
        assertNotEquals(id, call("store#Dataset", base("conn-2", [status: "open", first: 5])).out.datasetId)
        def row = find("darpan.dataset.Dataset", [datasetId: id])
        assertEquals(A, row.companyUserGroupId)
        assertEquals("DST_ACTIVE", row.statusId)
        assertEquals("DRR_CHANGED_SINCE", row.readRuleEnumId)
        assertEquals(2, row.marginDays as int)
        assertEquals(7, row.fullReadDays as int)
        assertEquals(62, row.horizonDays as int)
        assertEquals(4, row.readHourLocal as int)
    }

    @Test
    void optionsInADifferentOrderHashTheSame() {
        assertEquals(DatasetSupport.optionsHash([a: 1, b: [y: 2, x: 1], c: "z"]),
                DatasetSupport.optionsHash([c: "z", b: [x: 1, y: 2], a: 1]))
        assertNotEquals(DatasetSupport.optionsHash([a: 1]), DatasetSupport.optionsHash([a: 2]))
        assertEquals(64, DatasetSupport.optionsHash([:]).length())
        String id = call("store#Dataset", base("conn-3", [a: 1, b: 2])).out.datasetId
        assertEquals(id, call("store#Dataset", base("conn-3", [b: 2, a: 1])).out.datasetId)
    }

    @Test
    void aPausedDatasetDoesNotServe() {
        Map opts = [kind: "paused-case"]
        String id = call("store#Dataset", base("conn-4", opts)).out.datasetId
        assertEquals(id, call("find#ServingDataset", [systemEnumId: "SHOPIFY", sourceConfigId: "conn-4", options: opts]).out.dataset.datasetId)
        assertEquals(id, call("store#Dataset", base("conn-4", opts) + [statusId: "DST_PAUSED"]).out.datasetId)
        assertEquals("DST_PAUSED", find("darpan.dataset.Dataset", [datasetId: id]).statusId)
        assertNull(call("find#ServingDataset", [systemEnumId: "SHOPIFY", sourceConfigId: "conn-4", options: opts]).out.dataset)
    }

    @Test
    void tenantBCannotSeeOrServeTenantAsDataset() {
        Map opts = [kind: "tenant-case"]
        String idA = call("store#Dataset", base("shared-conn", opts)).out.datasetId
        insert("darpan.dataset.DatasetRead", [datasetId: idA, readSeqId: "1", companyUserGroupId: A, readKindEnumId: "DRK_FULL", statusId: "DRS_DONE", recordsRead: 3])
        Map own = call("get#Dataset", [datasetId: idA])
        assertEquals([], own.errors)
        assertEquals(idA, own.out.dataset.datasetId)
        assertEquals(1, (own.out.reads as List).size())

        actAs(EDITOR_USERNAME, B)
        assertTrue(call("get#Dataset", [datasetId: idA]).errors.any { it.contains("Dataset ${idA} does not exist") })
        assertNull(call("find#ServingDataset", [systemEnumId: "SHOPIFY", sourceConfigId: "shared-conn", options: opts]).out.dataset)
        String idB = call("store#Dataset", base("shared-conn", opts)).out.datasetId
        assertNotEquals(idA, idB)
        assertEquals(B, find("darpan.dataset.Dataset", [datasetId: idB]).companyUserGroupId)
        assertEquals(idB, call("find#ServingDataset", [systemEnumId: "SHOPIFY", sourceConfigId: "shared-conn", options: opts]).out.dataset.datasetId)
        assertEquals([], call("get#Dataset", [datasetId: idB]).out.reads)
    }

    @Test
    void getReturnsOnlyTheLastTenReads() {
        String id = call("store#Dataset", base("conn-5", [kind: "reads"])).out.datasetId
        (1..12).each { int n ->
            insert("darpan.dataset.DatasetRead", [datasetId: id, readSeqId: String.format("%03d", n), companyUserGroupId: A,
                    readKindEnumId: "DRK_INCREMENTAL", statusId: "DRS_DONE", recordsRead: n])
        }
        List reads = call("get#Dataset", [datasetId: id]).out.reads as List
        assertEquals(10, reads.size())
        assertEquals("012", reads[0].readSeqId)
        assertEquals("003", reads[9].readSeqId)
    }

    /**
     * I1: a dataset reads by its own window field, so its system's extract service must take windowFieldName. Shopify line
     * units' does not (Moqui would drop the parameter and window on createdAt): an active dataset of it is refused, as is
     * a system no connector reads. A paused one may be stored, but not switched on.
     */
    @Test
    void aSystemWhoseExtractorTakesNoWindowFieldIsRefused() {
        Map lineUnits = base("conn-lu", [:]) + [systemEnumId: "SHOPIFY_ORDER_LINE_UNITS"]
        Map refused = call("store#Dataset", lineUnits)
        assertTrue(refused.errors.any { it.contains("A dataset of SHOPIFY_ORDER_LINE_UNITS cannot be kept") &&
                it.contains("extract#ShopifyOrderLineUnits takes no windowFieldName") }, refused.errors.toString())
        assertEquals(0, ec.entity.find("darpan.dataset.Dataset").condition("sourceConfigId", "conn-lu").disableAuthz().count())

        Map unknown = call("store#Dataset", base("conn-nope", [:]) + [systemEnumId: "NO_SUCH_SYSTEM"])
        assertTrue(unknown.errors.any { it.contains("No source connector reads NO_SUCH_SYSTEM") }, unknown.errors.toString())

        // control: Shopify orders' extract service takes it
        assertEquals([], call("store#Dataset", base("conn-orders", [:])).errors)

        Map paused = call("store#Dataset", lineUnits + [statusId: "DST_PAUSED"])
        assertEquals([], paused.errors)
        assertTrue(call("store#Dataset", lineUnits + [statusId: "DST_ACTIVE"]).errors.any { it.contains("cannot be kept") })
        assertTrue(call("store#Dataset", lineUnits).errors.isEmpty(), "a settings update to the paused dataset is not refused")
        assertEquals("DST_PAUSED", find("darpan.dataset.Dataset", [datasetId: paused.out.datasetId]).statusId)
    }

    @Test
    void datasetDirIsUnderTheTenant(@TempDir Path root) {
        File dir = DatasetSupport.datasetDir(root.toFile(), "KREWE", "1001")
        assertEquals(root.resolve("datasets").resolve("KREWE").resolve("1001").toFile(), dir)
        File hostile = DatasetSupport.datasetDir(root.toFile(), "../KREWE", "../../1001")
        assertTrue(hostile.toPath().normalize().startsWith(root.resolve("datasets")))
        assertEquals("1001", hostile.name)
        assertNotEquals(DatasetSupport.datasetDir(root.toFile(), "GORJANA", "1001"), dir)
    }

    private static Map base(String conn, Map options) {
        return [systemEnumId: "SHOPIFY", sourceConfigId: conn, options: options, idField: "id", stampField: "updatedAt", windowField: "createdAt"]
    }

    private Map call(String verbNoun, Map params) {
        return underFacadeGrant("${SERVICES}.${verbNoun}".toString(), params)
    }

    private def find(String entityName, Map pk) {
        return ec.entity.find(entityName).condition(pk).disableAuthz().useCache(false).one()
    }

    private Map underFacadeGrant(String serviceName, Map params) {
        def harnessFrame = ec.artifactExecution.peek()
        assertEquals("smokeTests", harnessFrame?.name, "test precondition: unexpected artifact stack")
        ec.artifactExecution.pop(harnessFrame)
        ec.artifactExecution.enableAuthz()
        try {
            assertFalse(ec.artifactExecution.authzDisabled, "test precondition: authz must be ON")
            def aei = ec.artifactExecution.push(FACADE_GRANT, ArtifactExecutionInfo.AT_SERVICE, ArtifactExecutionInfo.AUTHZA_ALL, true)
            try {
                return collect { ec.service.sync().name(serviceName).parameters(new HashMap(params)).call() }
            } finally {
                ec.artifactExecution.pop(aei)
            }
        } finally {
            ec.artifactExecution.disableAuthz()
            ec.artifactExecution.push("smokeTests", ArtifactExecutionInfo.AT_OTHER, ArtifactExecutionInfo.AUTHZA_ALL, false)
            ec.artifactExecution.setAnonymousAuthorizedAll()
        }
    }

    private Map collect(Closure<Map> call) {
        ec.message.clearAll()
        Map out = null
        try {
            out = call.call()
        } catch (Exception e) {
            ec.message.addError(e.toString())
        }
        List<String> errors = ec.message.errors.collect { it.toString() } +
                ec.message.validationErrors.collect { it.message?.toString() }
        ec.message.clearAll()
        return [out: out, errors: errors]
    }

    private void actAs(String username, String tenant) {
        assertTrue(ec.user.internalLoginUser(username), "could not log in ${username}")
        if (tenant) ec.user.setPreference(PREF_KEY, tenant)
        TenantAccessSupport.syncUserContext(ec)
        ec.message.clearErrors()
    }

    private void insert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }
}
