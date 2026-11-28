package darpan.dataset

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-OPS-029 S42c (OC92). drop#ConnectionKeptData erases one tenant's kept data read through named connections: index
 * rows and cursors, kept copy reads, rows and folders. What could bite: another tenant's data under the same connection
 * id, another connection's data, and a refresh writing the copy back after the erase.
 */
@SharedMoqui
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConnectionKeptDataDropTests {
    private static final String A = "KREWE"
    private static final String B = "GORJANA"
    private static final String SERVICE = "dataset.DatasetServices.drop#ConnectionKeptData"

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "dataset-drop")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/DatasetSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ec.artifactExecution.disableAuthz()
        ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: B, description: "Gorjana",
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]).createOrUpdate()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void seed() {
        ec.message.clearAll()
        List<String> mine = ["DROP_A1", "DROP_A2", "DROP_B1", "DROP_A3"]
        ec.entity.find("darpan.idindex.RecordIdIndex").condition("rowKey", "like", "rk-%-DROP_%").deleteAll()
        ec.entity.find("darpan.idindex.RecordIdIndexDrop").condition("companyUserGroupId", "in", [A, B])
                .condition("connectionId", "in", ["conn-1", "conn-2"]).deleteAll()
        ["darpan.idindex.RecordIdIndexCursor", "darpan.dataset.DatasetRead", "darpan.dataset.Dataset"].each {
            ec.entity.find(it).condition("datasetId", "in", mine).deleteAll()
        }
        keptCopy(A, "DROP_A1", "conn-1")
        keptCopy(A, "DROP_A2", "conn-2")
        keptCopy(B, "DROP_B1", "conn-1") // same connection id string, another tenant
    }

    @Test
    void dropsOnlyThatTenantsDataForThatConnection() {
        Map out = drop(A, ["conn-1"])
        assertEquals([], ec.message.errors)
        assertEquals(1, out.datasetsDropped)
        assertEquals(1L, out.indexRowsDropped)

        assertFalse(present(A, "DROP_A1"), "the store's copy, reads, index rows, cursor and folder are gone")
        assertTrue(present(A, "DROP_A2"), "another connection of the same tenant stays")
        assertTrue(present(B, "DROP_B1"), "another tenant's data under the same connection id stays")
    }

    @Test
    void aCopyThatIsRefreshingRefusesAndNothingIsDropped() {
        def row = ec.entity.find("darpan.dataset.Dataset").condition("datasetId", "DROP_A1").useCache(false).one()
        row.set("refreshingSince", ec.user.nowTimestamp); row.update()
        drop(A, ["conn-1"])
        assertTrue(ec.message.errors.join(" ").contains("is refreshing"), "${ec.message.errors}")
        ec.message.clearAll()
        assertTrue(present(A, "DROP_A1"), "a refused drop leaves every row and the folder")
    }

    @Test
    void aStaleRefreshLockDoesNotBlockTheDrop() {
        def row = ec.entity.find("darpan.dataset.Dataset").condition("datasetId", "DROP_A1").useCache(false).one()
        row.set("refreshingSince", new Timestamp(ec.user.nowTimestamp.time - 7 * 3600_000L)); row.update()
        drop(A, ["conn-1"])
        assertEquals([], ec.message.errors)
        assertFalse(present(A, "DROP_A1"))
    }

    @Test
    void noConnectionsDropsNothing() {
        Map out = drop(A, [])
        assertEquals(0, out.datasetsDropped)
        ["DROP_A1", "DROP_A2"].each { assertTrue(present(A, it)) }
        assertTrue(present(B, "DROP_B1"))
    }

    /** S99-214 (OC104, S14): each drop marks its connections; a repeat advances the mark, and nothing else is marked. */
    @Test
    void eachDropMarksItsConnectionsAndARepeatAdvancesTheMark() {
        Timestamp first = Timestamp.valueOf("2026-11-20 10:00:00")
        Timestamp later = Timestamp.valueOf("2026-11-21 10:00:00")
        try {
            ec.user.setEffectiveTime(first)
            drop(A, ["conn-1", "conn-2"])
            assertEquals([first, first], ["conn-1", "conn-2"].collect { marker(A, it)?.droppedAt })
            ec.user.setEffectiveTime(later)
            drop(A, ["conn-1"])
            assertEquals(later, marker(A, "conn-1")?.droppedAt, "a repeat drop advances its connection's mark")
            assertEquals(first, marker(A, "conn-2")?.droppedAt, "a connection not dropped again keeps its mark")
            assertNull(marker(B, "conn-1"), "another tenant's same connection id is never marked")
        } finally {
            ec.user.setEffectiveTime(null)
        }
    }

    /**
     * Security review of OC232: config ids are per-entity sequences, so a NetSuite connection can carry the same id as a Shopify
     * one. A drop names its systems and takes only theirs: the same tenant's same-id connection of another system keeps its copy.
     */
    @Test
    void anotherSystemsConnectionWithTheSameIdKeepsItsData() {
        keptCopy(A, "DROP_A3", "conn-1", "NETSUITE_SUITEQL")
        Map out = drop(A, ["conn-1"])
        assertEquals([], ec.message.errors)
        assertEquals(1, out.datasetsDropped)
        assertFalse(present(A, "DROP_A1"), "the Shopify copy goes")
        assertTrue(present(A, "DROP_A3", "NETSUITE_SUITEQL"), "the NetSuite copy under the same connection id stays")
    }

    // ---- helpers -------------------------------------------------------------------------------

    private def marker(String tenant, String connection) {
        return ec.entity.find("darpan.idindex.RecordIdIndexDrop").condition([companyUserGroupId: tenant, connectionId: connection]).useCache(false).one()
    }

    private Map drop(String tenant, List<String> connections, List<String> systems = ["SHOPIFY"]) {
        return (Map) ec.service.sync().name(SERVICE).parameters([companyUserGroupId: tenant, sourceConfigIds: connections, systemEnumIds: systems])
                .disableAuthz().call()
    }

    private void keptCopy(String tenant, String datasetId, String connection, String system = "SHOPIFY") {
        Timestamp now = ec.user.nowTimestamp
        ec.entity.makeValue("darpan.dataset.Dataset").setAll([datasetId: datasetId, companyUserGroupId: tenant, systemEnumId: system,
                sourceConfigId: connection, optionsHash: "h-${datasetId}".toString(), statusId: "DST_ACTIVE",
                readRuleEnumId: "DRR_CHANGED_SINCE", idField: "id", stampField: "updatedAt", windowField: "createdAt"]).create()
        ec.entity.makeValue("darpan.dataset.DatasetRead").setAll([datasetId: datasetId, readSeqId: "000001", companyUserGroupId: tenant,
                readKindEnumId: "DRK_FULL", statusId: "DRS_DONE", readAt: now]).create()
        ec.entity.makeValue("darpan.idindex.RecordIdIndexCursor").setAll([companyUserGroupId: tenant, datasetId: datasetId,
                systemEnumId: system, connectionId: connection, lastIndexedAt: now]).create()
        ec.entity.makeValue("darpan.idindex.RecordIdIndex").setAll([rowKey: "rk-${tenant}-${datasetId}".toString(), companyUserGroupId: tenant,
                idNorm: "1001", idRaw: "1001", systemCode: system, systemEnumId: system, connectionId: connection, recordKind: "ORDER",
                recordKey: "1001", fieldPath: "id", role: "OWN", lastSeenAt: now]).create()
        File dir = DatasetRefreshSupport.datasetDir(ec, tenant, datasetId)
        dir.mkdirs()
        new File(dir, "current.json").text = '{"version":"v1"}'
    }

    /** True when ANY trace of the copy is left; asserted false only after a drop, so a partial drop fails too. */
    private boolean present(String tenant, String datasetId, String system = "SHOPIFY") {
        boolean dataset = ec.entity.find("darpan.dataset.Dataset").condition([companyUserGroupId: tenant, datasetId: datasetId]).useCache(false).count() > 0
        boolean reads = ec.entity.find("darpan.dataset.DatasetRead").condition([companyUserGroupId: tenant, datasetId: datasetId]).useCache(false).count() > 0
        boolean cursor = ec.entity.find("darpan.idindex.RecordIdIndexCursor").condition([companyUserGroupId: tenant, datasetId: datasetId, systemEnumId: system]).useCache(false).count() > 0
        boolean index = ec.entity.find("darpan.idindex.RecordIdIndex").condition("rowKey", "rk-${tenant}-${datasetId}".toString()).useCache(false).count() > 0
        boolean folder = DatasetRefreshSupport.datasetDir(ec, tenant, datasetId).exists()
        boolean all = dataset && reads && cursor && index && folder
        boolean none = !dataset && !reads && !cursor && !index && !folder
        assertTrue(all || none, "${datasetId} is half dropped: dataset=${dataset} reads=${reads} cursor=${cursor} index=${index} folder=${folder}")
        return all
    }
}
