package darpan.idindex

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import static org.junit.jupiter.api.Assertions.*
import static darpan.reconciliation.support.TestHelpers.asSystem

/** DAR-BE-374: index rows are added or touched, never deleted by a read, and one row per id can never be written twice. */
@SharedMoqui
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdIndexerWriteSmokeTests {

    private static final String T = "KREWE"
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "idindex-write")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void reset() { asSystem(ec) { ec.entity.find("darpan.idindex.RecordIdIndex").deleteAll() } }

    @Test
    void "a re-read record keeps its ids and gains new ones, nothing is deleted"() {
        IdIndexer.upsertRows(ec, T, "SHOP_A", rows("R", ["A", "B"]))
        IdIndexer.upsertRows(ec, T, "SHOP_A", rows("R", ["A", "C"]))
        assertEquals(["A", "B", "C"], idsOf("R"), "a run's extract may carry only some fields: B stays until the sweep")
    }

    @Test
    void "seeing a row again refreshes its lastSeenAt instead of adding a copy"() {
        IdIndexer.upsertRows(ec, T, "SHOP_A", rows("R", ["A"]))
        java.sql.Timestamp first = seen("R", "A")
        Thread.sleep(5)
        IdIndexer.upsertRows(ec, T, "SHOP_A", rows("R", ["A"]) + rows("R", ["A"]))
        assertEquals(1, count("R"), "one row per tenant, connection, kind, record, field and value")
        assertTrue(seen("R", "A").after(first))
    }

    @Test
    void "other connections and tenants keep their own rows"() {
        IdIndexer.upsertRows(ec, T, "SHOP_A", rows("R", ["A"]))
        IdIndexer.upsertRows(ec, T, "SHOP_B", rows("R", ["A"]))
        IdIndexer.upsertRows(ec, "OTHER", "SHOP_A", rows("R", ["A"]))
        assertEquals(["A"], idsOf("R"))
        assertEquals(["A"], idsOf("R", "SHOP_B"))
        assertEquals(["A"], idsOf("R", "SHOP_A", "OTHER"))
        assertEquals(3L, asSystem(ec) { ec.entity.find("darpan.idindex.RecordIdIndex").count() })
    }

    @Test
    void "more than one batch is written"() {
        List<Map> many = (1..1203).collectMany { rows("R${it}", ["V${it}"]) }
        assertEquals(1203, IdIndexer.upsertRows(ec, T, "SHOP_A", many))
        assertEquals(1203L, asSystem(ec) { ec.entity.find("darpan.idindex.RecordIdIndex").condition("companyUserGroupId", T).count() })
    }

    private static List<Map> rows(String key, List<String> ids) {
        return ids.collect { [systemCode: "SHOPIFY", systemEnumId: "SHOPIFY", recordKind: "ORDER", recordKey: key, fieldPath: "name",
                role: "OWN", idNorm: it, idRaw: it] }
    }

    private java.sql.Timestamp seen(String key, String id) {
        return asSystem(ec) { ec.entity.find("darpan.idindex.RecordIdIndex").condition("companyUserGroupId", T).condition("recordKey", key)
                .condition("idNorm", id).one().lastSeenAt } as java.sql.Timestamp
    }

    private long count(String key) {
        return asSystem(ec) { ec.entity.find("darpan.idindex.RecordIdIndex").condition("companyUserGroupId", T).condition("recordKey", key).count() } as long
    }

    private List<String> idsOf(String key, String conn = "SHOP_A", String tenant = T) {
        return asSystem(ec) {
            ec.entity.find("darpan.idindex.RecordIdIndex").condition("companyUserGroupId", tenant).condition("connectionId", conn)
                    .condition("recordKey", key).orderBy("idNorm").list()*.idNorm
        } as List<String>
    }
}
