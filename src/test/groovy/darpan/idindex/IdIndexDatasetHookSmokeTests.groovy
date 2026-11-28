package darpan.idindex

import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
import org.moqui.Moqui
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-374: every dataset read of a system the index reads is indexed (add-only) inside the refresh; an index failure never
 * fails the read and the nightly catch-up repairs it; a run's live extract is handed to the index; the sweep ages rows out.
 * Not @SharedMoqui: the indexer starts the embedded Spark session.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdIndexDatasetHookSmokeTests {

    private static final String A = "KREWE"
    private static final String EDITOR_USERNAME = "test.customer"
    private static final String FACADE_GRANT = "facade.ReconciliationFacadeServices.list#Reconciliations"
    private static final String SERVICES = "dataset.DatasetServices"
    private static final String ISO_EXTRACT = "reconciliation.KeptCopyTestExtractionServices.extract#MadeUpRecords"
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS)

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "idindex-dataset-hook")
        new File(DataManagerSupport.resolveDirectoryFile(ec, DataManagerSupport.resolveDataManagerLocation(ec), true), "datasets").deleteDir()
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.loadSeedData(ec, "classpath://data/EntityTypeData.xml", "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DatasetSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ec.entity.makeValue(TenantAccessSupport.TENANT_SETTING_ENTITY_NAME).setAll([companyUserGroupId: A, timeZone: "UTC"]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: "DARPAN_USER", userId: "TEST_CUSTOMER_USER",
                fromDate: Timestamp.valueOf("2026-04-21 00:00:00")]).createOrUpdate()
        ec.entity.makeDataLoader().xmlText("""<entity-facade-xml type="seed">
            <darpan.reconciliation.SourceSystemConnector systemEnumId="KC_ISO" extractServiceName="${ISO_EXTRACT}"
                dateFromParameterName="windowStart" dateToParameterName="windowEnd" expectedSourceConfigType="KC_ISO_TYPE"
                configParameterName="projConfigId" preserveWindowInstants="Y" enabled="Y"/>
        </entity-facade-xml>""").dummyFks(true).load()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void reset() {
        ["darpan.idindex.IdIndexSource", "darpan.idindex.IdIndexField", "darpan.idindex.TenantIdIndexField", "darpan.idindex.RecordIdIndex", "darpan.idindex.RecordIdIndexCursor"]
                .each { ec.entity.find(it).disableAuthz().deleteAll() }
        source([:])
        ec.entity.makeValue("darpan.idindex.IdIndexField").setAll([systemEnumId: "KC_ISO", fieldId: "NAME", fieldPath: "name", role: "OWN"]).create()
        ec.entity.makeValue("darpan.idindex.IdIndexField").setAll([systemEnumId: "KC_ISO", fieldId: "ID", fieldPath: "id", role: "OWN"]).create()
        assertTrue(ec.user.internalLoginUser(EDITOR_USERNAME))
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, A)
        TenantAccessSupport.syncUserContext(ec)
        ec.message.clearErrors()
    }

    @Test
    void "a dataset's good read is indexed and its first full read marks the copy indexed"() {
        String id = dataset("idx-done")
        Map res = refresh(id, stub([order(1, "#GOR1"), order(2, "#GOR2")]))
        assertEquals([], res.errors)
        assertEquals(["1", "2", "GOR1", "GOR2"], rows("conn-idx-done")*.idNorm.unique().sort())
        def cursor = cursor(id)
        assertEquals(["OK", 4, res.out.readSeqId, "conn-idx-done", "KC_ISO"],
                [cursor.lastStatus, cursor.rowsLastPass as int, cursor.lastIndexedReadSeqId, cursor.connectionId, cursor.systemEnumId])
        assertEquals(datasetRow(id).currentVersion, cursor.indexedVersion)
    }

    @Test
    void "an index failure leaves the read done, and the nightly catch-up indexes the copy"() {
        source([recordKind: null, recordKindField: "kind", recordKindMapJson: "{not json"])
        String id = dataset("idx-fail")
        Map res = refresh(id, stub([order(1, "#GOR1") + [kind: "SalesOrd"]]))
        assertEquals([], res.errors, "the read itself succeeded")
        assertEquals("DRS_DONE", ec.entity.find("darpan.dataset.DatasetRead").condition([datasetId: id, readSeqId: res.out.readSeqId])
                .disableAuthz().useCache(false).one().statusId)
        assertEquals("FAILED", cursor(id).lastStatus)
        assertNull(cursor(id).indexedVersion)
        assertEquals([], rows("conn-idx-fail"))

        source([:])   // the field is fixed
        Map out = asJob { ec.service.sync().name("idindex.IdIndexServices.maintain#IdIndex").call() }
        assertEquals([], out.errors)
        assertTrue((out.out.indexed as int) >= 1)
        assertEquals(["1", "GOR1"], rows("conn-idx-fail")*.idNorm.sort())
        assertEquals(["OK", datasetRow(id).currentVersion], [cursor(id).lastStatus, cursor(id).indexedVersion])
    }

    @Test
    void "an incremental read over a copy never indexed in full leaves the copy behind for the catch-up"() {
        String id = dataset("idx-incr")
        source([recordKind: null, recordKindField: "kind", recordKindMapJson: "{not json"])
        assertEquals([], refresh(id, stub([order(1, "#GOR1") + [kind: "x"]])).errors)   // the full read's index pass fails
        source([:])
        Map second = refresh(id, stub([order(2, "#GOR2")]))
        assertEquals("DRK_INCREMENTAL", second.out.readKindEnumId)
        assertEquals("OK", cursor(id).lastStatus)
        assertNull(cursor(id).indexedVersion, "only what changed was indexed; record 1 waits for the catch-up")
    }

    @Test
    void "a dataset whose system the index does not read is untouched"() {
        ec.entity.find("darpan.idindex.IdIndexSource").disableAuthz().deleteAll()
        String id = dataset("idx-nosource")
        assertEquals([], refresh(id, stub([order(1, "#GOR1")])).errors)
        assertEquals([], rows("conn-idx-nosource"))
        assertNull(cursor(id))
    }

    @Test
    void "a run's live extract is handed to the index and indexed with its query's record type"() {
        List<Map> handed = []
        IdIndexRunHook.dispatchHook = { Map p -> handed << p }
        try {
            IdIndexRunHook.afterLiveRead(ec, A, "KC_ISO", "Q_SHIP", "/tmp/x.json")
            IdIndexRunHook.afterLiveRead(ec, A, "NOT_INDEXED", "Q", "/tmp/y.json")
        } finally {
            IdIndexRunHook.resetForTests()
        }
        assertEquals([[tenantId: A, systemEnumId: "KC_ISO", connectionId: "Q_SHIP", fileLocation: "/tmp/x.json"]], handed)

        source([recordKind: null, recordKindMapJson: '{"ItemShip":"FULFILLMENT"}', kindHintEntity: "darpan.reconciliation.NsSuiteQlSourceQuery",
                kindHintField: "recordType"])
        ec.entity.makeDataLoader().xmlText("""<entity-facade-xml type="seed"><darpan.reconciliation.NsSuiteQlSourceQuery nsSuiteQlSourceQueryId="Q_SHIP"
                nsAuthConfigId="NS1" companyUserGroupId="${A}" recordType="ItemShip"/></entity-facade-xml>""").dummyFks(true).load()
        File extract = File.createTempFile("idx-run", ".json")
        extract.text = JsonOutput.toJson([metadata: [:], records: [[id: "501", name: "IF501"]]])
        Map out = IdIndexRunHook.indexExtract(ec, A, "KC_ISO", "Q_SHIP", extract.absolutePath)
        assertEquals(1, out.records as int)
        assertEquals(["FULFILLMENT"], rows("Q_SHIP")*.recordKind.unique())
        assertTrue(extract.exists(), "the run's own file is left as it was")
    }

    @Test
    void "the nightly sweep deletes rows not seen for thirteen months"() {
        [OLD: 400, NEW: 300].each { String v, int days ->
            ec.entity.makeValue("darpan.idindex.RecordIdIndex").setAll([rowKey: "k${v}".toString(), companyUserGroupId: A, idNorm: v, systemCode: "SHOPIFY",
                    systemEnumId: "KC_ISO", connectionId: "c", recordKind: "ORDER", recordKey: v, fieldPath: "id", role: "OWN",
                    lastSeenAt: Timestamp.from(NOW.minus(days, ChronoUnit.DAYS))]).create()
        }
        Map out = asJob { ec.service.sync().name("idindex.IdIndexServices.maintain#IdIndex").call() }
        assertEquals([], out.errors)
        assertEquals(1, out.out.deleted as int)
        assertEquals(["NEW"], rows("c")*.idNorm)
    }

    /** OC100: the sweep keeps rows for darpan.idindex.months as configured. It read the required setting with optionalIntValue,
     *  which always throws for it; the catch kept 13 months whatever was set. */
    @Test
    void "OC100: the nightly sweep keeps index rows for the configured number of months"() {
        [FORTY_DAYS: 40, TEN_DAYS: 10].each { String v, int days ->
            ec.entity.makeValue("darpan.idindex.RecordIdIndex").setAll([rowKey: "k${v}".toString(), companyUserGroupId: A, idNorm: v, systemCode: "SHOPIFY",
                    systemEnumId: "KC_ISO", connectionId: "c-months", recordKind: "ORDER", recordKey: v, fieldPath: "id", role: "OWN",
                    lastSeenAt: Timestamp.from(NOW.minus(days, ChronoUnit.DAYS))]).create()
        }
        ec.entity.makeValue("darpan.config.DarpanSetting").setAll([settingKey: IdIndexMaintain.MONTHS_SETTING, settingValue: "1"]).createOrUpdate()
        darpan.config.DarpanSettings.resetForTests()
        Map out
        try {
            out = asJob { ec.service.sync().name("idindex.IdIndexServices.maintain#IdIndex").call() }
        } finally {
            ec.entity.find("darpan.config.DarpanSetting").condition("settingKey", IdIndexMaintain.MONTHS_SETTING).disableAuthz().deleteAll()
            darpan.config.DarpanSettings.resetForTests()
        }
        assertEquals([], out.errors)
        assertEquals(["TEN_DAYS"], rows("c-months")*.idNorm, "one month configured: a row last seen 40 days ago goes, one seen 10 days ago stays")
    }

    /**
     * OC92 (from S42c): a catch-up pass already holding a dataset when its connection's kept data is dropped
     * (drop#ConnectionKeptData, Shopify's shop/redact) must not write that dataset's rows or cursor back. The index commits per
     * batch, so the drop is run, and committed, at the start of the second batch.
     */
    @Test
    void "OC92: a catch-up pass that loses its dataset to a drop mid-pass writes no rows and no cursor back"() {
        source([recordKind: null, recordKindField: "kind", recordKindMapJson: "{not json"])
        String id = dataset("idx-drop")
        List<Map> orders = (1..300).collect { order(it, "#DROP${it}") + [kind: "SalesOrd"] }   // 600 rows: two batches
        assertEquals([], refresh(id, stub(orders)).errors)                                       // its own index pass fails
        assertNull(cursor(id).indexedVersion)
        source([:])

        int batches = 0
        Throwable dropFailure = null
        IdIndexer.beforeBatchHook = { String tenant, String connection ->
            if (connection != "conn-idx-drop" || ++batches != 2) return
            dropFailure = onOwnThread {
                it.service.sync().name("${SERVICES}.drop#ConnectionKeptData".toString())
                        .parameters([companyUserGroupId: A, sourceConfigIds: ["conn-idx-drop"]]).disableAuthz().call()
            }
        }
        Map out
        try {
            out = asJob { ec.service.sync().name("idindex.IdIndexServices.maintain#IdIndex").call() }
        } finally {
            IdIndexer.resetForTests()
        }
        assertNull(dropFailure, "the drop itself: ${dropFailure}")
        assertEquals([], out.errors)
        assertNull(datasetRow(id), "the drop took the dataset")
        assertEquals([], rows("conn-idx-drop"), "no index row of the dropped connection is written back after the drop")
        assertNull(cursor(id), "no cursor is written back for a dataset that is gone")
    }

    /**
     * OC104 (security review of OC92): the run path. A run's extract indexed in the background was checked against its
     * connection's config row, which drop#ConnectionKeptData never locks, so an extract read before the drop wrote the
     * connection's rows back after it. The drop now marks the tenant connection dropped first (RecordIdIndexDrop, locked) and
     * each batch checks that mark against the extract's own read time.
     */
    @Test
    void "OC104: a run's extract read before its connection's kept data is dropped writes nothing after the drop"() {
        File extract = extractOf("Q_DROP", 300, System.currentTimeMillis() - 60_000L)   // 600 rows, read a minute ago
        int batches = 0
        Throwable dropFailure = null
        IdIndexer.beforeBatchHook = { String tenant, String connection ->
            if (connection != "Q_DROP" || ++batches != 2) return
            dropFailure = drop(A, "Q_DROP")
        }
        try {
            IdIndexRunHook.indexExtract(ec, A, "KC_ISO", "Q_DROP", extract.absolutePath)
        } finally {
            IdIndexer.resetForTests()
            extract.delete()
        }
        assertNull(dropFailure, "the drop itself: ${dropFailure}")
        assertEquals([], rows("Q_DROP"), "the drop took the first batch; nothing read before it is written after it")
    }

    @Test
    void "OC104: a run read after a connection's drop indexes again, so a store installed again on the same connection is searchable"() {
        assertNull(drop(A, "Q_AGAIN"))
        Thread.sleep(20)
        File extract = extractOf("Q_AGAIN", 300, System.currentTimeMillis())
        try {
            IdIndexRunHook.indexExtract(ec, A, "KC_ISO", "Q_AGAIN", extract.absolutePath)
        } finally {
            extract.delete()
        }
        assertEquals(600, rows("Q_AGAIN").size())
    }

    @Test
    void "OC104: one tenant's drop leaves another tenant's runs on a shared connection indexed"() {
        assertNull(drop(A, "Q_SHARED"))
        File extract = extractOf("Q_SHARED", 300, System.currentTimeMillis() - 60_000L)   // read before A's drop
        try {
            IdIndexRunHook.indexExtract(ec, "PEER_TENANT", "KC_ISO", "Q_SHARED", extract.absolutePath)
        } finally {
            extract.delete()
        }
        assertEquals(600, ec.entity.find("darpan.idindex.RecordIdIndex").condition("companyUserGroupId", "PEER_TENANT")
                .condition("connectionId", "Q_SHARED").disableAuthz().useCache(false).count() as int)
    }

    /** drop#ConnectionKeptData for one tenant connection, committed on a thread of its own; its failure, or null. */
    private Throwable drop(String tenant, String connection) {
        return onOwnThread {
            it.service.sync().name("${SERVICES}.drop#ConnectionKeptData".toString())
                    .parameters([companyUserGroupId: tenant, sourceConfigIds: [connection]]).disableAuthz().call()
        }
    }

    /** An extract of n records (id and name each: two rows a record), its file modified at readAtMillis (when it was read). */
    private static File extractOf(String prefix, int n, long readAtMillis) {
        File extract = File.createTempFile("idx-run-${prefix}", ".json")
        extract.text = JsonOutput.toJson([metadata: [:], records: (1..n).collect { [id: "${it}".toString(), name: "${prefix}${it}".toString()] }])
        extract.setLastModified(readAtMillis)
        return extract
    }

    /** Runs work on a thread of its own (its own ExecutionContext and transaction), committed before this returns. */
    private Throwable onOwnThread(Closure work) {
        Throwable failure = null
        Thread t = new Thread({
            ExecutionContext tec = Moqui.getExecutionContextFactory().getExecutionContext()
            try {
                if (!tec.user.internalLoginUser(EDITOR_USERNAME)) throw new IllegalStateException("could not sign in ${EDITOR_USERNAME}")
                tec.artifactExecution.disableAuthz()
                tec.transaction.runRequireNew(null, "OC92 test: the concurrent change") { work.call(tec) }
                if (tec.message.hasError()) failure = new IllegalStateException(tec.message.errors.join("; "))
            } catch (Throwable e) {
                failure = e
            } finally {
                tec.destroy()
            }
        } as Runnable, "oc92-concurrent")
        t.start()
        t.join(60_000L)
        return failure
    }

    // ---- helpers ----

    private void source(Map overrides) {
        ec.entity.makeValue("darpan.idindex.IdIndexSource").setAll([systemEnumId: "KC_ISO", systemCode: "SHOPIFY", recordKind: "ORDER",
                idField: "id", recordKindField: null, recordKindMapJson: null, kindHintEntity: null, kindHintField: null, isActive: "Y"] + overrides).createOrUpdate()
    }

    private static Map order(int n, String name) {
        return [id: "${n}".toString(), name: name, createdAt: NOW.minus(3, ChronoUnit.DAYS).toString(), updatedAt: NOW.minus(1, ChronoUnit.DAYS).toString()]
    }

    private Closure stub(List<Map> records) {
        return { String svc, Map p ->
            File out = new File(DataManagerSupport.resolveDirectoryFile(ec, p.outputLocation as String, true), p.fileName as String)
            out.setText(JsonOutput.toJson([metadata: [windowStart: p.windowStart, windowEnd: p.windowEnd], records: records]), "UTF-8")
            return [fileLocation: out.absolutePath, recordCount: records.size(), dataAvailable: !records.isEmpty()]
        }
    }

    private def datasetRow(String id) {
        return ec.entity.find("darpan.dataset.Dataset").condition("datasetId", id).disableAuthz().useCache(false).one()
    }

    private String dataset(String conn) {
        Map res = underFacadeGrant("${SERVICES}.store#Dataset".toString(), [systemEnumId: "KC_ISO", sourceConfigId: "conn-${conn}".toString(),
                options: [:], idField: "id", stampField: "updatedAt", windowField: "createdAt"])
        assertEquals([], res.errors)
        return res.out.datasetId
    }

    private Map refresh(String id, Closure extractor) {
        return underFacadeGrant("${SERVICES}.refresh#Dataset".toString(), [datasetId: id, extractor: extractor])
    }

    private List rows(String conn) {
        return ec.entity.find("darpan.idindex.RecordIdIndex").condition("companyUserGroupId", A).condition("connectionId", conn)
                .disableAuthz().useCache(false).list()
    }

    private def cursor(String datasetId) {
        return ec.entity.find("darpan.idindex.RecordIdIndexCursor").condition("companyUserGroupId", A).condition("datasetId", datasetId)
                .disableAuthz().useCache(false).one()
    }

    private Map underFacadeGrant(String serviceName, Map params) {
        def harnessFrame = ec.artifactExecution.peek()
        ec.artifactExecution.pop(harnessFrame)
        ec.artifactExecution.enableAuthz()
        try {
            def aei = ec.artifactExecution.push(FACADE_GRANT, ArtifactExecutionInfo.AT_SERVICE, ArtifactExecutionInfo.AUTHZA_ALL, true)
            try {
                return collect { ec.service.sync().name(serviceName).parameters(new HashMap(params)).call() }
            } finally {
                ec.artifactExecution.pop(aei)
            }
        } finally {
            restoreHarness()
        }
    }

    private Map asJob(Closure<Map> work) {
        def harnessFrame = ec.artifactExecution.peek()
        ec.artifactExecution.pop(harnessFrame)
        ec.user.logoutUser()
        ec.artifactExecution.enableAuthz()
        try {
            return collect(work)
        } finally {
            restoreHarness()
            assertTrue(ec.user.internalLoginUser(EDITOR_USERNAME))
            ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, A)
            TenantAccessSupport.syncUserContext(ec)
        }
    }

    private void restoreHarness() {
        ec.artifactExecution.disableAuthz()
        ec.artifactExecution.push("smokeTests", ArtifactExecutionInfo.AT_OTHER, ArtifactExecutionInfo.AUTHZA_ALL, false)
        ec.artifactExecution.setAnonymousAuthorizedAll()
    }

    private Map collect(Closure<Map> call) {
        ec.message.clearAll()
        Map out = null
        try {
            out = call.call()
        } catch (Exception e) {
            ec.message.addError(e.toString())
        }
        List<String> errors = ec.message.errors.collect { it.toString() } + ec.message.validationErrors.collect { it.message?.toString() }
        ec.message.clearAll()
        return [out: out, errors: errors]
    }
}
