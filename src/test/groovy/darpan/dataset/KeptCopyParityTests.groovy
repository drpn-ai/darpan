package darpan.dataset

import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.RunExtractSupport
import darpan.facade.reconciliation.RunRetrySupport
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.ExtractReplaySupport
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

import java.nio.file.Files
import java.sql.Timestamp
import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertArrayEquals
import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 plan 3a, Task 8. Parity: a run served from the kept copy is the run read live.
 *
 * The fixture (src/test/resources/dataset/shopify-orders) is 30 synthetic Shopify orders shaped like a bulk extract, around
 * the window [2026-11-10T08:00Z, 2026-11-11T08:00Z): one order exactly at the start (in), one exactly at the end (out), two
 * of a store a source filter excludes, and two updated long after they were created. orders.json is what the stub extractor
 * reads for the refresh AND for the live read, so the copy and the live read hold the same records. oms-orders.json is the
 * other side of the compare: it lacks the start order and holds the end order, a test-store order and a stranger.
 *
 * How parity is compared: the diff document (writeDiffDatasetOutput over the compare's diff frame, fixed metadata), the
 * result (the compare's own outputs, minus its Spark frames, as sorted JSON) and the compare's records file beside the extract
 * are compared byte for byte. None of them carries the extract's metadata (datasetId, copyVersion, copyReadAt, servedFromCopy
 * live only in the extract's own metadata), so no key needs removing. The extract itself differs in its metadata only:
 * its records and its .excluded.json side file are compared apart from it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeptCopyParityTests {

    private static final String A = "KREWE"
    private static final String PREF_KEY = TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY
    private static final String EDITOR_ID = "TEST_CUSTOMER_USER"
    private static final String EDITOR_USERNAME = "test.customer"
    private static final Timestamp FROM_DATE = Timestamp.valueOf("2026-04-21 00:00:00")
    private static final String FACADE_GRANT = "facade.ReconciliationFacadeServices.list#Reconciliations"
    private static final String SHOPIFY_EXTRACT = "reconciliation.ShopifyOrderExtractionServices.extract#ShopifyOrders"
    private static final Timestamp START = Timestamp.from(Instant.parse("2026-11-10T08:00:00Z"))
    private static final Timestamp END = Timestamp.from(Instant.parse("2026-11-11T08:00:00Z"))
    private static final Instant COPY_READ_AT = Instant.parse("2026-11-20T00:00:00Z")
    private static final String STAMP = "20261120-000000000"
    private static final String START_ORDER = "5000103"
    private static final String END_ORDER = "5000122"
    private static final List<String> TEST_STORE_ORDERS = ["5000107", "5000112"]

    private ExecutionContext ec
    private File dataManagerRoot
    private File fixtures
    private Map<String, String> datasetIds = [:]
    private int seq = 0

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "kept-copy-parity")
        dataManagerRoot = DataManagerSupport.resolveDirectoryFile(ec, DataManagerSupport.resolveDataManagerLocation(ec), true)
        assertTrue(dataManagerRoot.path.contains("test-data-manager"), "tests never write the dev datamanager: ${dataManagerRoot}")
        // the test data manager outlives the test DB, whose fresh sequence hands out the same ids again
        ["datasets", DataManagerSupport.RECONCILIATION_RUNS_PATH, "kcp-out", "kcp-fixtures", "judge-replays"].each { new File(dataManagerRoot, it).deleteDir() }
        fixtures = new File(dataManagerRoot, "kcp-fixtures")
        fixtures.mkdirs()
        Files.copy(fixtureFile("oms-orders.json").toPath(), new File(fixtures, "oms-orders.json").toPath())

        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.loadSeedData(ec, "classpath://data/EntityTypeData.xml",
                "component://moqui-rules/data/RuleSeedData.xml", "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/JudgeSeedData.xml", "component://darpan/data/MappingSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml", "component://darpan/data/AutomationSeedData.xml",
                "component://darpan/data/ConclusionCatalogSeedData.xml", "component://darpan/data/DatasetSeedData.xml")
        ec.entity.makeDataLoader().location("component://darpan/data/SourceSystemConnectorSeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        insert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: EDITOR_ID, fromDate: FROM_DATE])
        seedSavedRun()
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
    void aServedRunMatchesALiveRun() {
        String id = keptDataset("conn-parity")

        List<Map> servedCalls = []
        Map served = interactive("conn-parity", servedCalls, "parity")
        assertEquals([], served.errors)
        assertEquals(0, servedCalls.size(), "the served run never calls the live extractor")

        setStatus(id, "DST_PAUSED")
        List<Map> liveCalls = []
        Map live
        try {
            live = interactive("conn-parity", liveCalls, "parity")
        } finally {
            setStatus(id, "DST_ACTIVE")
        }
        assertEquals([], live.errors)
        assertEquals(1, liveCalls.size(), "the paused dataset reads live, once")
        assertEquals(served.result.fileName, live.result.fileName)
        assertNotEquals(served.result.fileLocation, live.result.fileLocation)

        // the extracts: the copy's metadata apart, the same records and the same set-aside records
        Map servedDoc = json(new File(served.result.fileLocation as String))
        Map liveDoc = json(new File(live.result.fileLocation as String))
        assertEquals(true, servedDoc.metadata.servedFromCopy)
        assertEquals(id, servedDoc.metadata.datasetId)
        assertEquals(true, liveDoc.metadata.live)
        assertEquals(null, liveDoc.metadata.servedFromCopy)
        List<String> keptIds = servedDoc.records*.legacyResourceId
        assertEquals(17, keptIds.size(), "19 orders in the window, two of them from the filtered store")
        assertTrue(keptIds.contains(START_ORDER), "an order exactly at the window start is in")
        assertFalse(keptIds.contains(END_ORDER), "an order exactly at the window end is out")
        assertTrue(keptIds.intersect(TEST_STORE_ORDERS).isEmpty(), "the filtered store is not read")
        assertEquals(JsonOutput.toJson(liveDoc.records), JsonOutput.toJson(servedDoc.records))
        assertEquals(liveDoc.records.size(), served.result.recordCount)
        File servedSide = sidecarOf(served)
        File liveSide = sidecarOf(live)
        assertTrue(servedSide.isFile() && liveSide.isFile(), "the filtered orders are set aside in both")
        assertEquals(TEST_STORE_ORDERS as Set, json(servedSide).records*.legacyResourceId as Set)
        assertEquals(liveSide.getText("UTF-8"), servedSide.getText("UTF-8"))

        // the compare over each, and what it writes
        Map servedOut = compareAndWrite(served, "served")
        Map liveOut = compareAndWrite(live, "live")
        ["diff.json", "result.json"].each { String name ->
            assertTrue(servedOut[name].length > 0, name)
            assertArrayEquals(liveOut[name], servedOut[name], "${name} differs between the served run and the live run")
        }
        assertArrayEquals(recordsFileOf(live).bytes, recordsFileOf(served).bytes, "the compare's own records file differs")

        // the diff is the real thing, not two empty documents
        Map<String, String> typeById = ((List<Map>) json(new File(dataManagerRoot, "kcp-out/served/diff.json")).differences)
                .collectEntries { [(it.primaryId as String): it.diffType as String] }
        assertEquals("MISSING_IN_FILE_2", typeById[START_ORDER], "the start order is in the window and the OMS lacks it")
        assertEquals("MISSING_IN_FILE_1", typeById[END_ORDER], "the OMS holds the end order, which the window leaves out")
        assertEquals("MISSING_IN_FILE_1", typeById[TEST_STORE_ORDERS[0]], "the OMS holds a test-store order the filter drops")
        assertEquals("MISSING_IN_FILE_1", typeById["5009999"])
        assertTrue(typeById.values().count { it == "MISSING_IN_FILE_2" } >= 2, typeById.toString())
    }

    /** A composite-id rolling dataset serves the same records, byte for byte, as a live read (no _copyId leaks). */
    @Test
    void aServedCompositeRollingRunMatchesALiveRun() {
        Map stored = underFacadeGrant("dataset.DatasetServices.store#Dataset", [systemEnumId: "SHOPIFY", sourceConfigId: "conn-composite",
                options: [:], idField: "id,legacyResourceId", windowField: "createdAt", readRuleEnumId: "DRR_ROLLING"])
        assertEquals([], stored.errors)
        String id = stored.out.datasetId
        datasetIds["conn-composite"] = id
        Map res = atCopyClock(0) { refresh(id, stub([])) }
        assertEquals([], res.errors)
        assertEquals("DRK_FULL", res.out.readKindEnumId)
        assertEquals(30, KeptCopyStore.manifest(DatasetRefreshSupport.datasetDir(ec, A, id)).recordCount)

        List<Map> servedCalls = []
        Map served = interactive("conn-composite", servedCalls, "composite")
        assertEquals([], served.errors)
        assertEquals(0, servedCalls.size(), "the served run never calls the live extractor")
        Map dsRow = ec.entity.find("darpan.dataset.Dataset").condition("datasetId", id).disableAuthz().useCache(false).one().getMap()
        underFacadeGrant("dataset.DatasetServices.store#Dataset", [datasetId: id, statusId: "DST_PAUSED", systemEnumId: "SHOPIFY",
                sourceConfigId: "conn-composite", options: [:], idField: "id,legacyResourceId", windowField: "createdAt"])
        List<Map> liveCalls = []
        Map live
        try {
            live = interactive("conn-composite", liveCalls, "composite")
        } finally {
            underFacadeGrant("dataset.DatasetServices.store#Dataset", [datasetId: id, statusId: "DST_ACTIVE", systemEnumId: "SHOPIFY",
                    sourceConfigId: "conn-composite", options: [:], idField: "id,legacyResourceId", windowField: "createdAt"])
        }
        assertEquals(1, liveCalls.size())
        assertEquals("DRR_ROLLING", dsRow.readRuleEnumId)
        List servedRecords = json(new File(served.result.fileLocation as String)).records
        assertFalse(servedRecords.isEmpty())
        assertEquals(JsonOutput.toJson(json(new File(live.result.fileLocation as String)).records), JsonOutput.toJson(servedRecords))
    }

    @Test
    void aServedRunRetriesFromItsOwnSlice() {
        String id = keptDataset("conn-retry")
        File datasetDir = DatasetRefreshSupport.datasetDir(ec, A, id)

        // the interactive run, and the automation run in the layout an execution's folder has
        Map run = interactive("conn-retry", [], "retry")
        assertEquals([], run.errors)
        byte[] slice = new File(run.result.fileLocation as String).bytes
        String runsLocation = DataManagerSupport.resolveReconciliationRunsLocation(ec)
        String runLocation = DataManagerSupport.childLocation(runsLocation, "KCP_EXEC_R")
        Map scheduled = automationSlice("conn-retry", DataManagerSupport.childLocation(runLocation, STAMP))
        byte[] scheduledSlice = locationFile(scheduled.fileLocation as String).bytes
        String copyVersion = KeptCopyStore.manifest(datasetDir).version
        assertEquals(copyVersion, json(new File(run.result.fileLocation as String)).metadata.copyVersion)

        // the copy moves on: an order created inside the window arrives and is merged
        Map res = atCopyClock(1) { refresh(id, stub([], [order("5009001", "2026-11-10T12:00:00Z", "2026-11-19T12:00:00Z")])) }
        assertEquals([], res.errors)
        assertEquals("DRK_INCREMENTAL", res.out.readKindEnumId)
        assertNotEquals(copyVersion, KeptCopyStore.manifest(datasetDir).version, "the copy has a newer version")

        // a run started now is served the newer copy ...
        Map fresh = direct("conn-retry", "retry-fresh")
        assertTrue(fresh.served as boolean, fresh.reason as String)
        assertTrue(json(fresh.extractFile as File).records*.legacyResourceId.contains("5009001"), "the newer copy holds the new order")
        assertNotEquals(slice.length, (fresh.extractFile as File).length())

        // ... and the retry of the old run replays its own slice (interactive: RunRetrySupport; automation: the run folder)
        Map plan = [sides: [FILE_1: [dataManagerPath: run.result.dataManagerPath, fileName: run.result.fileName,
                                     recordCount: run.result.recordCount]]]
        Map replayed = RunRetrySupport.replayedSide(ec, plan, run.source, "FILE_1")
        assertEquals(new File(run.result.fileLocation as String).canonicalPath, new File(replayed.fileLocation as String).canonicalPath)
        assertArrayEquals(slice, new File(replayed.fileLocation as String).bytes)
        assertEquals(run.result.recordCount, replayed.recordCount)
        assertFalse(json(new File(replayed.fileLocation as String)).records*.legacyResourceId.contains("5009001"))
        assertEquals(copyVersion, json(new File(replayed.fileLocation as String)).metadata.copyVersion, "it still names the version it was read from")

        Map picked = ExtractReplaySupport.pickFromRunFolder(DataManagerSupport.resolveDirectoryFile(ec, runLocation, false), runLocation, false)
        assertEquals(null, picked.error)
        File pickedFile = DataManagerSupport.resolveDirectoryFile(ec, picked.files.FILE_1.fileLocation as String, false)
        assertEquals(locationFile(scheduled.fileLocation as String).canonicalPath, pickedFile.canonicalPath,
                "the side set aside beside it is not an extract: the retry picks the slice alone")
        assertArrayEquals(scheduledSlice, pickedFile.bytes)
        assertEquals(copyVersion, json(pickedFile).metadata.copyVersion)
    }

    @Test
    void aServedRunReplaysThroughThePlan2Judge() {
        String id = keptDataset("conn-judge")
        String runsLocation = DataManagerSupport.resolveReconciliationRunsLocation(ec)
        File omsFixture = new File(fixtures, "oms-orders.json")

        // KCP_EXEC_S: side 1 served from the copy (the scheduled dispatcher), side 2 the OMS file
        Map served = automationSlice("conn-judge", DataManagerSupport.childLocation(runsLocation, "KCP_EXEC_S/${STAMP}".toString()))
        File servedExtract = locationFile(served.fileLocation as String)
        assertEquals(true, json(servedExtract).metadata.servedFromCopy)
        placeSide2("KCP_EXEC_S")

        // KCP_EXEC_L: the same side 1 read live by the same dispatcher (the dataset paused), in an execution folder of its own
        List<Map> liveCalls = []
        setStatus(id, "DST_PAUSED")
        Map live
        try {
            live = automationSlice("conn-judge", DataManagerSupport.childLocation(runsLocation, "KCP_EXEC_L/${STAMP}".toString()), liveCalls)
        } finally {
            setStatus(id, "DST_ACTIVE")
        }
        assertEquals(1, liveCalls.size(), "the paused dataset reads live through the dispatcher")
        File liveStampDir = locationFile(live.fileLocation as String).parentFile
        placeSide2("KCP_EXEC_L")
        assertEquals(servedExtract.parentFile.list().sort() as List, liveStampDir.list().sort() as List, "both run folders hold the same files")

        Map onServed = replay("KCP_EXEC_S")
        Map onLive = replay("KCP_EXEC_L")
        assertEquals([], onServed.errors)
        assertEquals([], onLive.errors)

        // the replay judged the slice: its copy of the extract is the served slice itself
        File replayDir = new File(dataManagerRoot, onServed.out.replayFolder as String)
        File replayedExtract = new File(replayDir, "extracts/FILE_1/${servedExtract.name}")
        assertArrayEquals(servedExtract.bytes, replayedExtract.bytes, "the replay reads the run's own slice")
        assertEquals(true, json(replayedExtract).metadata.servedFromCopy)
        assertEquals([ExcludedRecordsSidecar.fileNameFor(servedExtract.name)],
                new File(replayDir, "extracts/FILE_1").list().findAll { it.endsWith(".excluded.json") } as List)

        // and what it found is what the live read's replay found
        assertTrue(onServed.out.oldFindings.size() >= 2, "findings exist: ${onServed.out.oldFindings}")
        assertEquals(START_ORDER, onServed.out.oldFindings*.recordKey.sort().find { it == START_ORDER })
        assertEquals(JsonOutput.toJson(onLive.out.oldFindings), JsonOutput.toJson(onServed.out.oldFindings))
        // the judge ran (its decisions over the slice): findings exist and are the live read's, record for record
        List<Map> judgedServed = onServed.out.judgedFindings
        assertTrue(judgedServed.size() >= 2, "the judge found something, or this proves nothing: ${judgedServed}")
        assertTrue(judgedServed*.recordKey.contains(START_ORDER), judgedServed*.recordKey.toString())
        assertEquals(["S1"], judgedServed*.statementCode.unique())
        assertEquals(["NO_OMS_ORDER"], judgedServed*.claimOutcomeId.unique())
        assertEquals(JsonOutput.toJson(onLive.out.judgedFindings), JsonOutput.toJson(judgedServed))
        assertEquals(onLive.out.judgedPairCounts, onServed.out.judgedPairCounts)
        assertTrue((onServed.out.judgedPairCounts.matched as int) > 0 && (onServed.out.judgedPairCounts.missing as int) > 0, "${onServed.out.judgedPairCounts}")
        assertTrue(new File(replayDir, "judged-findings.json").isFile())
        Map servedDiff = json(new File(replayDir, "diff.json"))
        Map liveDiff = json(new File(dataManagerRoot, "${onLive.out.replayFolder}/diff.json".toString()))
        assertEquals(JsonOutput.toJson(liveDiff.differences), JsonOutput.toJson(servedDiff.differences))
        assertEquals(JsonOutput.toJson(liveDiff.summary), JsonOutput.toJson(servedDiff.summary))
        assertTrue(servedDiff.differences.size() > 0)
        Map servedResult = json(new File(replayDir, "result.json"))
        Map liveResult = json(new File(dataManagerRoot, "${onLive.out.replayFolder}/result.json".toString()))
        ["replayOfRunFolder"].each { servedResult.remove(it); liveResult.remove(it) }
        assertEquals(JsonOutput.toJson(new TreeMap(liveResult)), JsonOutput.toJson(new TreeMap(servedResult)))
    }

    /** The scheduled dispatcher, like the interactive one: ACTIVE serves, PAUSED reads live, and the compare cannot tell. */
    @Test
    void anAutomationRunServedMatchesTheSameRunReadLive() {
        String id = keptDataset("conn-auto")
        String runsLocation = DataManagerSupport.resolveReconciliationRunsLocation(ec)
        List<Map> servedCalls = []
        Map served = automationSlice("conn-auto", DataManagerSupport.childLocation(runsLocation, "KCP_EXEC_AS/${STAMP}".toString()), servedCalls)
        assertEquals(0, servedCalls.size(), "a served automation side never calls the live extractor")
        assertEquals(true, served.requestMetadata?.servedFromCopy)

        List<Map> liveCalls = []
        setStatus(id, "DST_PAUSED")
        Map live
        try {
            live = automationSlice("conn-auto", DataManagerSupport.childLocation(runsLocation, "KCP_EXEC_AL/${STAMP}".toString()), liveCalls)
        } finally {
            setStatus(id, "DST_ACTIVE")
        }
        assertEquals(1, liveCalls.size())
        assertEquals(null, live.requestMetadata?.servedFromCopy)
        assertEquals(served.fileName, live.fileName)

        File servedFile = locationFile(served.fileLocation as String)
        File liveFile = locationFile(live.fileLocation as String)
        assertEquals(17, json(servedFile).records.size())
        assertEquals(JsonOutput.toJson(json(liveFile).records), JsonOutput.toJson(json(servedFile).records))
        assertEquals(new File(liveFile.parentFile, ExcludedRecordsSidecar.fileNameFor(liveFile.name)).getText("UTF-8"),
                new File(servedFile.parentFile, ExcludedRecordsSidecar.fileNameFor(servedFile.name)).getText("UTF-8"))
        Map servedOut = compareAndWrite([result: [fileLocation: servedFile.absolutePath, fileName: servedFile.name]], "auto-served")
        Map liveOut = compareAndWrite([result: [fileLocation: liveFile.absolutePath, fileName: liveFile.name]], "auto-live")
        ["diff.json", "result.json"].each { String name ->
            assertTrue(servedOut[name].length > 0, name)
            assertArrayEquals(liveOut[name], servedOut[name], "${name} differs between the served and the live automation side")
        }
    }

    // --------------------------------------------------------------------------------------------------------- the runs

    /** One interactive FILE_1 side through RunExtractSupport.extractApiSource; the live extractor is the stub below. */
    private Map interactive(String conn, List<Map> calls, String token) {
        String runToken = "p${++seq}"
        Map source = [systemEnumId: "SHOPIFY", sourceConfigType: "SHOPIFY_AUTH", sourceConfigId: conn, fileSide: "FILE_1",
                      compareScopeId: "KCP_SCOPE"]
        // the same artifact file name for every run, in a folder of its own
        Map artifact = [location: DataManagerSupport.resolveReconciliationRunLocation(ec, "kcp-${token}-${runToken}", STAMP), runToken: "parity"]
        ec.message.clearAll()
        Map result = RunExtractSupport.extractApiSource(ec, source, "FILE_1", "File 1", artifact,
                [windowStartDate: START, windowEndDate: END, timeZone: "UTC"], null, liveExtractor(calls))
        List<String> errors = ec.message.errors.collect { it.toString() }
        ec.message.clearAll()
        return [result: result, artifact: artifact, source: source, errors: errors]
    }

    /** The scheduled dispatcher, as a job runs it, with the run's side landing in outputLocation. */
    private Map automationSlice(String conn, String outputLocation, List<Map> liveCalls = null) {
        Map automation = [automationId: "KCP_AUTO", companyUserGroupId: A, relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_PREVIOUS_DAY]
        Map source = [automationId: "KCP_AUTO", fileSide: "FILE_1", systemEnumId: "SHOPIFY", companyUserGroupId: A,
                      sourceTypeEnumId: AutomationExecutionSupport.AUTOMATION_SOURCE_API,
                      safeMetadataJson: JsonOutput.toJson([extractServiceName: SHOPIFY_EXTRACT, parameters: [shopifyAuthConfigId: conn]])]
        ec.user.logoutUser()
        ec.user.loginAnonymousIfNoUser()
        try {
            Map result = (Map) TenantAccessSupport.withSystemTenant(A) {
                AutomationExecutionSupport.callConfiguredSourceExtractor(ec, automation, source,
                        [childWindowStartDate: START, childWindowEndDate: END], [automationExecutionId: "KCP_EXEC", sideOutputLocation: outputLocation] +
                        (liveCalls != null ? [liveExtractor: liveExtractor(liveCalls)] : [:]))
            }
            if (liveCalls == null) assertEquals(true, result.requestMetadata?.servedFromCopy, result.toString())
            return result
        } finally {
            actAs(EDITOR_USERNAME, A)
        }
    }

    private Map direct(String conn, String token) {
        return KeptCopySupport.sliceIfServed(ec, KeptCopySupport.side(
                darpan.reconciliation.automation.SourceSystemConnectorSupport.resolve(ec, "SHOPIFY"),
                [shopifyAuthConfigId: conn, outputLocation: DataManagerSupport.resolveReconciliationRunLocation(ec, "kcp-${token}", STAMP),
                 fileName: "slice.json"], [tenantId: A, windowStart: START, windowEnd: END]))
    }

    /** What a live Shopify read answers for the window: the fixture's orders in [start, end), the side's filters applied. */
    private Closure liveExtractor(List<Map> calls) {
        return { String service, Map params ->
            calls << [service: service, params: new LinkedHashMap(params)]
            // the interactive path sends ISO instants, the scheduled one raw Timestamps
            Closure<Instant> instantOf = { Object v -> v instanceof Timestamp ? ((Timestamp) v).toInstant() : Instant.parse(v as String) }
            Instant from = instantOf(params.windowStart), to = instantOf(params.windowEnd)
            String fileName = (params.fileName ?: "shopify-${from.toEpochMilli()}-${to.toEpochMilli()}.json") as String
            String field = (params.windowFieldName ?: "createdAt") as String
            List<Map> inWindow = fixtureRecords().findAll { Map r ->
                Instant at = Instant.parse(r[field] as String)
                !at.isBefore(from) && at.isBefore(to)
            }
            Map applied = SourceFilterSupport.applyToRecords(inWindow, SourceFilterSupport.parseRules(params.sourceFilters as List))
            File dir = DataManagerSupport.resolveDirectoryFile(ec, params.outputLocation as String, true)
            File out = new File(dir, fileName)
            out.setText(JsonOutput.toJson([metadata: [live: true, windowStart: from.toString(), windowEnd: to.toString(),
                                                      filters: applied.configuredExclusions],
                                           records : applied.records]), "UTF-8")
            if (applied.excludedCollector) {
                ExcludedRecordsSidecar.writeBeside(ec, (Map) applied.excludedCollector, params.outputLocation as String, fileName, dir)
            }
            return [fileLocation: DataManagerSupport.childLocation(params.outputLocation as String, fileName), fileName: fileName, recordCount: ((List) applied.records).size()]
        }
    }

    /** A refresh's extractor: the whole fixture, or the given records, where the read asked, as a live extract writes it. */
    private Closure stub(List<Map> calls, List<Map> records = null) {
        return { String svc, Map p ->
            calls << [service: svc, params: new LinkedHashMap(p)]
            List<Map> rows = records != null ? records : fixtureRecords()
            File out = new File(DataManagerSupport.resolveDirectoryFile(ec, p.outputLocation as String, true), p.fileName as String)
            out.setText(JsonOutput.toJson([metadata: [windowStart: p.windowStart, windowEnd: p.windowEnd], records: rows]), "UTF-8")
            return [fileLocation: out.absolutePath, recordCount: rows.size(), dataAvailable: !rows.isEmpty()]
        }
    }

    /** The ACTIVE dataset for SHOPIFY + conn, its copy read in full from the fixture at the day the fixture ends. */
    private String keptDataset(String conn) {
        Map stored = underFacadeGrant("dataset.DatasetServices.store#Dataset", [systemEnumId: "SHOPIFY", sourceConfigId: conn,
                options: [:], idField: "id", stampField: "updatedAt", windowField: "createdAt"])
        assertEquals([], stored.errors)
        String id = stored.out.datasetId
        datasetIds[conn] = id
        Map res = atCopyClock(0) { refresh(id, stub([])) }
        assertEquals([], res.errors)
        assertEquals("DRK_FULL", res.out.readKindEnumId)
        Map manifest = KeptCopyStore.manifest(DatasetRefreshSupport.datasetDir(ec, A, id))
        assertEquals(30, manifest.recordCount)
        assertEquals(COPY_READ_AT, (manifest.readThru as Timestamp).toInstant(), "the copy reads through the day the fixture ends")
        return id
    }

    /** Runs work with the clock at the copy's day (plus days), so no read depends on the wall clock; restored after. */
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
                systemEnumId: "SHOPIFY", sourceConfigId: datasetIds.find { it.value == id }.key, options: [:], idField: "id",
                stampField: "updatedAt", windowField: "createdAt"]).errors)
    }

    // ------------------------------------------------------------------------------------------------------- the compare

    /**
     * reconcile#RuleSetCompareScope over the run's FILE_1 and the OMS file, then the diff document written as the live writer
     * writes it, and the compare's own outputs as sorted JSON. Returns the bytes of both, and leaves them in kcp-out/<tag>.
     */
    private Map compareAndWrite(Map run, String tag) {
        Map called = underFacadeGrant("reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope", [
                ruleSetId: "KCP_RS", compareScopeId: "KCP_SCOPE", file1Location: run.result.fileLocation,
                file2Location: new File(fixtures, "oms-orders.json").absolutePath, file1Name: run.result.fileName,
                file2Name: "oms-orders.json", sparkAppName: "KeptCopyParityTests"])
        assertEquals([], called.errors, tag)
        Map out = called.out
        try {
            File dir = new File(dataManagerRoot, "kcp-out/${tag}")
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

    private File recordsFileOf(Map run) {
        File extract = new File(run.result.fileLocation as String)
        File records = new File(extract.parentFile, extract.name + ".records.jsonl")
        assertTrue(records.isFile(), "the compare writes its records file beside the extract: ${extract.parentFile.list()}")
        return records
    }

    private File sidecarOf(Map run) {
        File extract = new File(run.result.fileLocation as String)
        return new File(extract.parentFile, ExcludedRecordsSidecar.fileNameFor(extract.name))
    }

    private void placeSide2(String executionId) {
        File dir = new File(dataManagerRoot, "${DataManagerSupport.RECONCILIATION_RUNS_PATH}/${executionId}/${STAMP}-FILE_2")
        dir.mkdirs()
        Files.copy(new File(fixtures, "oms-orders.json").toPath(), new File(dir, "oms-orders.json").toPath())
    }

    private Map replay(String executionId) {
        // judgeWithDecisions left at the service's default: the judge runs
        return underFacadeGrant("judge.JudgeReplayServices.replay#CheckJudgement", [checkId: checkId,
                runFolder: "${DataManagerSupport.RECONCILIATION_RUNS_PATH}/${executionId}".toString()])
    }

    // ------------------------------------------------------------------------------------------------------------ setup

    private String checkId

    /** One saved run (Shopify orders against the OMS file, a store filter on side 1), its automation and its audit check. */
    private void seedSavedRun() {
        ec.entity.makeDataLoader().xmlText("""<entity-facade-xml type="seed">
            <darpan.rule.RuleSet ruleSetId="KCP_RS" ruleSetName="Shopify orders vs OMS" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareScope compareScopeId="KCP_SCOPE" ruleSetId="KCP_RS" objectType="ORDER" companyUserGroupId="${A}"
                description="Shopify orders against OMS orders"/>
            <darpan.rule.RuleSetCompareSource compareScopeId="KCP_SCOPE" fileSide="FILE_1" systemEnumId="SHOPIFY" fileTypeEnumId="DftJson"
                recordRootExpression="records" primaryIdExpression="legacyResourceId" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareSource compareScopeId="KCP_SCOPE" fileSide="FILE_2" systemEnumId="NETSUITE" fileTypeEnumId="DftJson"
                recordRootExpression="records" primaryIdExpression="externalOrderId" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareSourceFilter compareScopeId="KCP_SCOPE" fileSide="FILE_1" sequenceNum="1"
                fieldExpression="\$.records[*].shopifyStore" operator="EXCLUDE_IN" filterValues="syn-test" companyUserGroupId="${A}"/>
            <darpan.reconciliation.ReconciliationAutomation automationId="KCP_AUTO" automationName="Parity orders" companyUserGroupId="${A}"
                inputModeEnumId="${AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE}" savedRunId="KCP_RS"/>
            <darpan.reconciliation.ReconciliationAutomationSource automationId="KCP_AUTO" fileSide="FILE_1" companyUserGroupId="${A}"
                sourceTypeEnumId="${AutomationExecutionSupport.AUTOMATION_SOURCE_API}" systemEnumId="SHOPIFY"/>
            <darpan.reconciliation.ReconciliationAutomationSourceFilter automationId="KCP_AUTO" fileSide="FILE_1" sequenceNum="1"
                fieldExpression="\$.records[*].shopifyStore" operator="EXCLUDE_IN" filterValues="syn-test" companyUserGroupId="${A}"/>
            <darpan.reconciliation.ReconciliationAutomationExecution automationExecutionId="KCP_EXEC_S" automationId="KCP_AUTO"
                companyUserGroupId="${A}" statusEnumId="AUT_STAT_SUCCESS"/>
            <darpan.reconciliation.ReconciliationAutomationExecution automationExecutionId="KCP_EXEC_L" automationId="KCP_AUTO"
                companyUserGroupId="${A}" statusEnumId="AUT_STAT_SUCCESS"/>
            <darpan.audit.Audit auditId="KCP_AUDIT" companyUserGroupId="${A}" auditCode="kcp" auditName="Parity" periodEnumId="AUDP_MONTH"/>
            <darpan.audit.AuditStatement statementId="KCP_S1" companyUserGroupId="${A}" auditId="KCP_AUDIT" statementCode="S1"
                categoryEnumId="AUDC_RETURNS" weightEnumId="AUDSW_FIRM" statementText="Every Shopify order is in the OMS" sequenceNum="1"/>
        </entity-facade-xml>""").dummyFks(true).load()
        checkId = ec.entity.sequencedIdPrimary("darpan.audit.AuditCheck", null, null)
        insert("darpan.audit.AuditCheck", [checkId: checkId, companyUserGroupId: A, checkCode: "kcp-orders", label: "Orders vs OMS",
                savedRunId: "KCP_RS", automationId: "KCP_AUTO", file1RecordType: "order", file2RecordType: "order"])
        insert("darpan.audit.AuditStatementCheck", [checkId: checkId, statementId: "KCP_S1", companyUserGroupId: A, sequenceNum: 1,
                diffTypes: "MISSING_IN_FILE_2"])
        seedDecisions()
    }

    /**
     * The judge's config for the check, as JudgeReplayTests seeds it: a Must hold decision (NO_OMS_ORDER: the OMS holds no record)
     * that statement S1 promises, and an Explains decision (a partly refunded order is EXPECTED, the rest UNEXPLAINED).
     */
    private void seedDecisions() {
        actAs(EDITOR_USERNAME, A)
        Map generated = ok(underFacadeGrant("judge.PairFactServices.generate#PairFactType", [checkId: checkId,
                file1SystemEnumId: "SHOPIFY", file2SystemEnumId: "NETSUITE",
                sampleKeys: [file1: ["legacyResourceId", "displayFinancialStatus"], file2: ["externalOrderId", "total"]]]))
        factTypeId = generated.factTypeId
        String mustHold = createDecision("N", ["NO_OMS_ORDER"])
        storeRule(mustHold, "no OMS order", 10, [line("01", "netsuite_presence", "ENTCO_NOT_EQUALS", "KEPT")], "NO_OMS_ORDER")
        meaning(mustHold, "NO_OMS_ORDER", "JOK_FINDING", "The OMS holds no order")
        String explains = createDecision("Y", ["PARTLY_REFUNDED", "UNEXPLAINED"])
        storeRule(explains, "partly refunded", 10, [line("01", "claim", "ENTCO_EQUALS", "NO_OMS_ORDER"),
                line("02", "shopify_displayFinancialStatus", "ENTCO_EQUALS", "PARTIALLY_REFUNDED")], "PARTLY_REFUNDED")
        meaning(explains, "PARTLY_REFUNDED", "JOK_EXPECTED", "Partly refunded, not in the OMS")
        meaning(explains, "UNEXPLAINED", "JOK_FINDING", "Unexplained")
        insert("darpan.judge.CheckDecision", [checkId: checkId, stageEnumId: "CDS_MUST_HOLD", companyUserGroupId: A, ruleSetId: mustHold])
        insert("darpan.judge.CheckDecision", [checkId: checkId, stageEnumId: "CDS_EXPLAINS", companyUserGroupId: A, ruleSetId: explains])
        def statement = ec.entity.find("darpan.audit.AuditStatement").condition("statementId", "KCP_S1").disableAuthz().useCache(false).one()
        statement.setAll([claimRuleSetId: mustHold, claimOutcomeId: "NO_OMS_ORDER"])
        statement.update()
    }

    private String factTypeId

    private String createDecision(String firstMatchOnly, List<String> outcomeIds) {
        Map created = ok(underFacadeGrant("create#rules.RuleSet", [description: "Parity decision", firstMatchOnly: firstMatchOnly,
                factTypes: [[factTypeId: factTypeId]], outcomes: outcomeIds.collect { [outcomeId: it] }]))
        return created.ruleSetId as String
    }

    private String storeRule(String ruleSetId, String ruleName, int priority, List<Map> lines, String outcomeId) {
        return ok(underFacadeGrant("moqui.rules.RuleServices.store#Rule", [ruleSetId: ruleSetId, ruleName: ruleName, priority: priority,
                conditions: lines, actions: [[actionTypeId: "RAT_OUTCOME", outcomeId: outcomeId]]])).ruleId as String
    }

    private Map line(String seq, String fieldName, String operatorEnumId, String compareValue) {
        return [conditionSeqId: seq, conditionTypeId: "RCT_COMPARE", factTypeId: factTypeId, fieldName: fieldName,
                operatorEnumId: operatorEnumId, compareValue: compareValue]
    }

    private void meaning(String ruleSetId, String outcomeId, String kind, String label) {
        ok(underFacadeGrant("judge.JudgeConfigServices.store#DecisionOutcome", [ruleSetId: ruleSetId, outcomeId: outcomeId,
                outcomeKindEnumId: kind, label: label]))
    }

    private static Map ok(Map result) {
        assertEquals([], result.errors, "call failed")
        return result.out as Map
    }

    private File fixtureFile(String name) {
        return ReconciliationSmokeTestSupport.resolveBackendRoot()
                .resolve("runtime/component/darpan/src/test/resources/dataset/shopify-orders/${name}").toFile()
    }

    private List<Map> fixtureRecords() {
        return (List<Map>) json(fixtureFile("orders.json")).records
    }

    private static Map order(String legacyId, String createdAt, String updatedAt) {
        return [id: "gid://shopify/Order/${legacyId}".toString(), legacyResourceId: legacyId, name: "#SYN${legacyId}".toString(),
                createdAt: createdAt, updatedAt: updatedAt, shopifyStore: "syn-main", currencyCode: "USD"]
    }

    private File locationFile(String location) {
        return location.startsWith("/") ? new File(location) : ec.resource.getLocationReference(location).getFile()
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
