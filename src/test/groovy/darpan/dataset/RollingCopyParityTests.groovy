package darpan.dataset

import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.RunExtractSupport
import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import darpan.reconciliation.core.ReconciliationServices
import darpan.reconciliation.source.SourceFilterSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.apache.spark.sql.Dataset
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext

import java.sql.Timestamp
import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertArrayEquals
import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 plan 3b-1, Task 2. HotWax returns kept as a ROLLING copy: the whole window by entryDate, re-read in full each
 * day, and a run served from it is the run read live.
 *
 * The fixture is 20 synthetic returns (R001..R020) built in this class, entryDate-ordered around the window
 * [2026-11-10T08:00Z, 2026-11-11T08:00Z): R004 exactly at the start (in), R017 exactly at the end (out), two of a channel a
 * source filter excludes, and a few days before and after. The stub extractor stands in for extract#HotWaxOmsGqlReturns for
 * the refresh and for the live read, so no OMS is ever called.
 *
 * Serving a returns side needs two connector settings the seed does not carry yet (the live dispatchers send the connector's
 * windowFieldName, and OMS_RETURNS_GQL reads whole days until preserveWindowInstants is Y). This class sets both on its own
 * test DB's connector row, which is what a later change makes real.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RollingCopyParityTests {

    private static final String A = "KREWE"
    private static final String PREF_KEY = TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY
    private static final String EDITOR_ID = "TEST_CUSTOMER_USER"
    private static final String EDITOR_USERNAME = "test.customer"
    private static final Timestamp FROM_DATE = Timestamp.valueOf("2026-04-21 00:00:00")
    private static final String FACADE_GRANT = "facade.ReconciliationFacadeServices.list#Reconciliations"
    private static final String RETURNS_EXTRACT = "reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlReturns"
    private static final Timestamp START = Timestamp.from(Instant.parse("2026-11-10T08:00:00Z"))
    private static final Timestamp END = Timestamp.from(Instant.parse("2026-11-11T08:00:00Z"))
    private static final Instant COPY_READ_AT = Instant.parse("2026-11-20T00:00:00Z")
    private static final String STAMP = "20261120-000000000"
    private static final String CONN = "rcp-hw"
    private static final List<Map> FILTERS = [[sequenceNum: 1, fieldExpression: "returnChannelEnumId", operator: "EXCLUDE_IN", filterValues: "POS_RTN_CHANNEL"]]

    private ExecutionContext ec
    private File dataManagerRoot
    private File fixtures
    private int seq = 0
    private String datasetId

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "rolling-copy-parity")
        dataManagerRoot = DataManagerSupport.resolveDirectoryFile(ec, DataManagerSupport.resolveDataManagerLocation(ec), true)
        assertTrue(dataManagerRoot.path.contains("test-data-manager"), "tests never write the dev datamanager: ${dataManagerRoot}")
        // the test data manager outlives the test DB, whose fresh sequence hands out the same ids again
        ["datasets", DataManagerSupport.RECONCILIATION_RUNS_PATH, "rcp-out", "rcp-fixtures"].each { new File(dataManagerRoot, it).deleteDir() }
        fixtures = new File(dataManagerRoot, "rcp-fixtures")
        fixtures.mkdirs()

        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.loadSeedData(ec, "classpath://data/EntityTypeData.xml",
                "component://moqui-rules/data/RuleSeedData.xml", "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/JudgeSeedData.xml", "component://darpan/data/MappingSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml", "component://darpan/data/AutomationSeedData.xml",
                "component://darpan/data/ConclusionCatalogSeedData.xml", "component://darpan/data/DatasetSeedData.xml")
        ec.entity.makeDataLoader().location("component://darpan/data/SourceSystemConnectorSeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        insert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: EDITOR_ID, fromDate: FROM_DATE])
        // the two connector settings serving a returns side needs (see the class comment): this test DB only
        def connector = ec.entity.find("darpan.reconciliation.SourceSystemConnector").condition("systemEnumId", "OMS_RETURNS_GQL")
                .disableAuthz().useCache(false).one()
        assertNotNull(connector)
        connector.setAll([windowFieldName: "entryDate", preserveWindowInstants: "Y"])
        connector.update()
        ec.entity.makeDataLoader().xmlText("""<entity-facade-xml type="seed">
            <darpan.rule.RuleSet ruleSetId="RCP_RS" ruleSetName="HotWax returns vs NetSuite" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareScope compareScopeId="RCP_SCOPE" ruleSetId="RCP_RS" objectType="RETURN" companyUserGroupId="${A}"
                description="HotWax returns against NetSuite returns"/>
            <darpan.rule.RuleSetCompareSource compareScopeId="RCP_SCOPE" fileSide="FILE_1" systemEnumId="OMS_RETURNS_GQL" fileTypeEnumId="DftJson"
                recordRootExpression="records" primaryIdExpression="returnId" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareSource compareScopeId="RCP_SCOPE" fileSide="FILE_2" systemEnumId="NETSUITE" fileTypeEnumId="DftJson"
                recordRootExpression="records" primaryIdExpression="returnId" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareSourceFilter compareScopeId="RCP_SCOPE" fileSide="FILE_1" sequenceNum="1"
                fieldExpression="\$.records[*].returnChannelEnumId" operator="EXCLUDE_IN" filterValues="POS_RTN_CHANNEL" companyUserGroupId="${A}"/>
        </entity-facade-xml>""").dummyFks(true).load()
        // the other side: NetSuite lacks R004 (the start return), holds R017 (the end return, outside the window) and a stranger
        List<Map> netsuite = returns().findAll { it.returnId != "R004" && it.returnId in ["R001", "R005", "R006", "R007", "R017"] }.collect {
            [returnId: it.returnId] } + [[returnId: "R999"]]
        new File(fixtures, "ns-returns.json").setText(JsonOutput.toJson([records: netsuite]), "UTF-8")
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void actAsTenantA() {
        actAs(EDITOR_USERNAME, A)
    }

    // ----------------------------------------------------------------------------------------------------- the tests

    @Test
    void aRollingReturnsDatasetIsStoredAndRefreshed() {
        String id = rollingReturns(CONN + "-store")
        def row = ec.entity.find("darpan.dataset.Dataset").condition("datasetId", id).disableAuthz().useCache(false).one()
        assertEquals("DRR_ROLLING", row.readRuleEnumId)
        assertEquals(60, row.horizonDays as int)
        assertEquals("entryDate", row.windowField)
        assertEquals("returnId", row.idField)
        assertEquals("OMS_RETURNS_GQL", row.systemEnumId)

        List<Map> calls = []
        Map res = atCopyClock(0) { refresh(id, stub(calls)) }
        assertEquals([], res.errors)
        assertEquals("DRK_FULL", res.out.readKindEnumId)
        assertEquals(1, calls.size())
        assertEquals(RETURNS_EXTRACT, calls[0].service)
        assertEquals("entryDate", calls[0].params.windowFieldName, "the read windows on the returns' own field")
        assertEquals(Timestamp.from(COPY_READ_AT.minusSeconds(60 * 86400L)).toInstant(),
                instantOf(calls[0].params.windowStart), "the whole 60-day window, not a changed-since range")
        assertEquals(20, KeptCopyStore.manifest(DatasetRefreshSupport.datasetDir(ec, A, id)).recordCount)
        assertNull(res.out.missCount, "a rolling read counts no misses")

        // a second day's refresh is another whole read, never an incremental one
        Map again = atCopyClock(1) { refresh(id, stub([])) }
        assertEquals([], again.errors)
        assertEquals("DRK_FULL", again.out.readKindEnumId)
        assertNull(again.out.missCount)
    }

    @Test
    void aServedReturnsRunMatchesALiveRun() {
        String id = rollingReturns(CONN)
        assertEquals([], atCopyClock(0) { refresh(id, stub([])) }.errors)

        List<Map> servedCalls = []
        Map served = interactive(servedCalls, "served")
        assertEquals([], served.errors)
        assertEquals(0, servedCalls.size(), "the served run never calls the live extractor")

        setStatus(id, "DST_PAUSED")
        List<Map> liveCalls = []
        Map live
        try {
            live = interactive(liveCalls, "live")
        } finally {
            setStatus(id, "DST_ACTIVE")
        }
        assertEquals([], live.errors)
        assertEquals(1, liveCalls.size(), "the paused dataset reads live, once")
        assertEquals("entryDate", liveCalls[0].params.windowFieldName)
        assertEquals(served.result.fileName, live.result.fileName)

        Map servedDoc = json(new File(served.result.fileLocation as String))
        Map liveDoc = json(new File(live.result.fileLocation as String))
        assertEquals(true, servedDoc.metadata.servedFromCopy)
        assertEquals(true, liveDoc.metadata.live)
        List<String> keptIds = servedDoc.records*.returnId
        assertTrue(keptIds.contains("R004"), "a return exactly at the window start is in")
        assertFalse(keptIds.contains("R017"), "a return exactly at the window end is out")
        assertEquals(["R002", "R003"].intersect(keptIds), [], "the filtered channel is not read")
        assertTrue(keptIds.size() >= 8, keptIds.toString())
        assertEquals(JsonOutput.toJson(liveDoc.records), JsonOutput.toJson(servedDoc.records))
        assertFalse(JsonOutput.toJson(servedDoc.records).contains("_copyId"), "no derived id leaks into a slice")
        File servedSide = sidecarOf(served)
        File liveSide = sidecarOf(live)
        assertTrue(servedSide.isFile() && liveSide.isFile(), "the filtered returns are set aside in both")
        assertEquals(liveSide.getText("UTF-8"), servedSide.getText("UTF-8"))

        Map servedOut = compareAndWrite(served, "served")
        Map liveOut = compareAndWrite(live, "live")
        ["diff.json", "result.json"].each { String name ->
            assertTrue(servedOut[name].length > 0, name)
            assertArrayEquals(liveOut[name], servedOut[name], "${name} differs between the served run and the live run")
        }
        Map<String, String> typeById = ((List<Map>) json(new File(dataManagerRoot, "rcp-out/served/diff.json")).differences)
                .collectEntries { [(it.primaryId as String): it.diffType as String] }
        assertEquals("MISSING_IN_FILE_2", typeById["R004"], "the start return is in the window and NetSuite lacks it")
        assertEquals("MISSING_IN_FILE_1", typeById["R017"], "NetSuite holds the end return, which the window leaves out")
        assertEquals("MISSING_IN_FILE_1", typeById["R999"])
    }

    @Test
    void aReturnThatLeftTheWindowIsRemovedNotMissed() {
        String id = rollingReturns(CONN + "-left")
        assertEquals([], atCopyClock(0) { refresh(id, stub([])) }.errors)
        File dir = DatasetRefreshSupport.datasetDir(ec, A, id)
        assertEquals(20, KeptCopyStore.manifest(dir).recordCount)

        // next day: R001 aged out of the OMS window, R005 changed its status without any stamp moving, R021 arrived
        List<Map> next = returns().findAll { it.returnId != "R001" }.collect { Map r ->
            r.returnId == "R005" ? r + [statusId: "RETURN_COMPLETED"] : r } + [ret("R021", "2026-11-19T10:00:00Z")]
        Map res = atCopyClock(1) { refresh(id, stub([], next)) }
        assertEquals([], res.errors)
        assertEquals("DRK_FULL", res.out.readKindEnumId)
        assertNull(res.out.missCount)

        def row = ec.entity.find("darpan.dataset.Dataset").condition("datasetId", id).disableAuthz().useCache(false).one()
        assertNull(row.lastMissCount, "no miss is counted on a rolling dataset")
        assertNull(row.warningText, "and no miss warning is raised")
        def read = ec.entity.find("darpan.dataset.DatasetRead").condition("datasetId", id).orderBy("-readSeqId").disableAuthz().useCache(false).list().first()
        assertNull(read.missCount)
        assertEquals(1, read.removedCount as int, "the return that left the window is removed")
        assertEquals(1, read.addedCount as int)
        assertEquals(1, read.changedCount as int, "a status change with no stamp is a change, not a miss")
        Map byId = KeptCopyStore.records(dir).collectEntries { [(it.id): it.record] }
        assertFalse(byId.containsKey("R001"))
        assertEquals("RETURN_COMPLETED", byId["R005"].statusId)
        assertEquals(20, byId.size())
    }

    // --------------------------------------------------------------------------------------------------------- the runs

    /** One interactive FILE_1 side through RunExtractSupport.extractApiSource; the live extractor is the stub below. */
    private Map interactive(List<Map> calls, String token) {
        String runToken = "p${++seq}"
        Map source = [systemEnumId: "OMS_RETURNS_GQL", sourceConfigType: "HOTWAX_OMS_GQL_RETURNS", sourceConfigId: CONN, fileSide: "FILE_1",
                      compareScopeId: "RCP_SCOPE", sourceFilters: FILTERS]
        Map artifact = [location: DataManagerSupport.resolveReconciliationRunLocation(ec, "rcp-${token}-${runToken}", STAMP), runToken: "parity"]
        ec.message.clearAll()
        Map result = RunExtractSupport.extractApiSource(ec, source, "FILE_1", "File 1", artifact,
                [windowStartDate: START, windowEndDate: END, timeZone: "UTC"], null, liveExtractor(calls))
        List<String> errors = ec.message.errors.collect { it.toString() }
        ec.message.clearAll()
        return [result: result, artifact: artifact, source: source, errors: errors]
    }

    /** What a live OMS returns read answers for the window: the returns with entryDate in [start, end), the side's filters applied. */
    private Closure liveExtractor(List<Map> calls) {
        return { String service, Map params ->
            calls << [service: service, params: new LinkedHashMap(params)]
            Closure<Instant> at = { Object v -> v instanceof Timestamp ? ((Timestamp) v).toInstant() : Instant.parse(v as String) }
            Instant from = at(params.windowStart), to = at(params.windowEnd)
            String fileName = (params.fileName ?: "oms-returns-${from.toEpochMilli()}-${to.toEpochMilli()}.json") as String
            List<Map> inWindow = returns().findAll { Map r ->
                Instant e = Instant.parse(r.entryDate as String)
                !e.isBefore(from) && e.isBefore(to)
            }
            Map applied = SourceFilterSupport.applyToRecords(inWindow, SourceFilterSupport.parseRules(params.sourceFilters as List))
            File dir = DataManagerSupport.resolveDirectoryFile(ec, params.outputLocation as String, true)
            new File(dir, fileName).setText(JsonOutput.toJson([metadata: [live: true, windowStart: from.toString(), windowEnd: to.toString(),
                                                                          filters: applied.configuredExclusions],
                                                               records : applied.records]), "UTF-8")
            if (applied.excludedCollector) {
                ExcludedRecordsSidecar.writeBeside(ec, (Map) applied.excludedCollector, params.outputLocation as String, fileName, dir)
            }
            return [fileLocation: DataManagerSupport.childLocation(params.outputLocation as String, fileName), fileName: fileName,
                    recordCount: ((List) applied.records).size()]
        }
    }

    /** A refresh's extractor: every return, or the given ones, as a live extract writes them. */
    private Closure stub(List<Map> calls, List<Map> records = null) {
        return { String svc, Map p ->
            calls << [service: svc, params: new LinkedHashMap(p)]
            List<Map> rows = records != null ? records : returns()
            File out = new File(DataManagerSupport.resolveDirectoryFile(ec, p.outputLocation as String, true), p.fileName as String)
            out.setText(JsonOutput.toJson([metadata: [windowStart: p.windowStart, windowEnd: p.windowEnd], records: rows]), "UTF-8")
            return [fileLocation: out.absolutePath, recordCount: rows.size(), dataAvailable: !rows.isEmpty()]
        }
    }

    private String rollingReturns(String conn) {
        Map stored = underFacadeGrant("dataset.DatasetServices.store#Dataset", [systemEnumId: "OMS_RETURNS_GQL", sourceConfigId: conn,
                options: [:], idField: "returnId", windowField: "entryDate", readRuleEnumId: "DRR_ROLLING"])
        assertEquals([], stored.errors)
        if (conn == CONN) datasetId = stored.out.datasetId
        return stored.out.datasetId as String
    }

    private Map atCopyClock(int plusDays, Closure<Map> work) {
        ec.user.setEffectiveTime(Timestamp.from(COPY_READ_AT.plusSeconds(plusDays * 86400L)))
        try {
            return work.call()
        } finally {
            ec.user.setEffectiveTime(null)
        }
    }

    private Map refresh(String id, Closure extractor) {
        return underFacadeGrant("dataset.DatasetServices.refresh#Dataset", [datasetId: id, extractor: extractor, forceFull: false])
    }

    private void setStatus(String id, String statusId) {
        assertEquals([], underFacadeGrant("dataset.DatasetServices.store#Dataset", [datasetId: id, statusId: statusId,
                systemEnumId: "OMS_RETURNS_GQL", sourceConfigId: CONN, options: [:], idField: "returnId", windowField: "entryDate"]).errors)
    }

    private static Instant instantOf(Object v) {
        return v instanceof Timestamp ? ((Timestamp) v).toInstant() : Instant.parse(v as String)
    }

    // ------------------------------------------------------------------------------------------------------- the compare

    private Map compareAndWrite(Map run, String tag) {
        Map called = underFacadeGrant("reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope", [
                ruleSetId: "RCP_RS", compareScopeId: "RCP_SCOPE", file1Location: run.result.fileLocation,
                file2Location: new File(fixtures, "ns-returns.json").absolutePath, file1Name: run.result.fileName,
                file2Name: "ns-returns.json", sparkAppName: "RollingCopyParityTests"])
        assertEquals([], called.errors, tag)
        Map out = called.out
        try {
            File dir = new File(dataManagerRoot, "rcp-out/${tag}")
            dir.deleteDir()
            Map written = ReconciliationServices.writeDiffDatasetOutput(ec, (Dataset) out.diffDf, dir.absolutePath, "diff.json", "diff.json",
                    [timestamp: "fixed", ruleSetId: out.ruleSetId, compareScopeId: out.compareScopeId],
                    [totalDifferences: out.differenceCount], (List) out.validationErrors, (List) out.processingWarnings)
            new File(dir, "result.json").setText(JsonOutput.toJson(new TreeMap(out.findAll { k, v ->
                !(v instanceof Dataset) && k != "persistedSources" })), "UTF-8")
            return ["diff.json": new File(written.diffLocation as String).bytes, "result.json": new File(dir, "result.json").bytes]
        } finally {
            ReconciliationServices.unpersistDatasets((List) (out?.persistedSources ?: []))
        }
    }

    private File sidecarOf(Map run) {
        File extract = new File(run.result.fileLocation as String)
        return new File(extract.parentFile, ExcludedRecordsSidecar.fileNameFor(extract.name))
    }

    // ------------------------------------------------------------------------------------------------------------ fixture

    private static Map ret(String id, String entryDate, String channel = "ECOM_RTN_CHANNEL") {
        return [returnId: id, statusId: "RETURN_REQUESTED", entryDate: entryDate, externalId: "SHR${id.substring(1)}".toString(),
                returnChannelEnumId: channel, currencyUomId: "USD", returnTotal: 10]
    }

    /** 20 synthetic returns, ordered by entryDate, around the window [2026-11-10T08:00Z, 2026-11-11T08:00Z). */
    private static List<Map> returns() {
        return [
                ret("R001", "2026-10-02T09:00:00Z"), ret("R002", "2026-11-10T09:30:00Z", "POS_RTN_CHANNEL"),
                ret("R003", "2026-11-10T22:00:00Z", "POS_RTN_CHANNEL"), ret("R004", "2026-11-10T08:00:00Z"),
                ret("R005", "2026-11-10T08:00:01Z"), ret("R006", "2026-11-10T11:00:00Z"), ret("R007", "2026-11-10T13:15:00Z"),
                ret("R008", "2026-11-10T15:45:00Z"), ret("R009", "2026-11-10T18:00:00Z"), ret("R010", "2026-11-10T20:30:00Z"),
                ret("R011", "2026-11-11T00:10:00Z"), ret("R012", "2026-11-11T03:00:00Z"), ret("R013", "2026-11-11T07:59:59Z"),
                ret("R014", "2026-11-10T07:59:59Z"), ret("R015", "2026-11-09T12:00:00Z"), ret("R016", "2026-11-12T12:00:00Z"),
                ret("R017", "2026-11-11T08:00:00Z"), ret("R018", "2026-11-15T12:00:00Z"), ret("R019", "2026-11-18T12:00:00Z"),
                ret("R020", "2026-10-25T12:00:00Z"),
        ]
    }

    private static Map json(File f) {
        return (Map) new JsonSlurper().parse(f, "UTF-8")
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
                ec.message.clearAll()
                Map out = null
                try {
                    out = ec.service.sync().name(serviceName).parameters(new HashMap(params)).call()
                } catch (Exception e) {
                    ec.message.addError(e.toString())
                }
                List<String> errors = ec.message.errors.collect { it.toString() } +
                        ec.message.validationErrors.collect { it.message?.toString() }
                ec.message.clearAll()
                return [out: errors ? null : out, errors: errors]
            } finally {
                ec.artifactExecution.pop(aei)
            }
        } finally {
            ec.artifactExecution.disableAuthz()
            ec.artifactExecution.push("smokeTests", ArtifactExecutionInfo.AT_OTHER, ArtifactExecutionInfo.AUTHZA_ALL, false)
            ec.artifactExecution.setAnonymousAuthorizedAll()
        }
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
