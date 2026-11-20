package darpan.dataset

import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.ReconciliationOutputSupport
import darpan.facade.reconciliation.RunExtractSupport
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import darpan.reconciliation.source.SourceFilterSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertArrayEquals
import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 plan 3a, Task 7. A run's side is served its window from the tenant's kept copy when the copy covers it, and
 * reads live exactly as before when it does not. The copy is built with KeptCopyStore directly; the live extractor is a
 * stub (interactive) or never reached (automation, served). Every Dataset row here claims a readThru far past its copy's,
 * so a serving rule that read the row instead of the manifest (ruling P3a-12) would serve windows the copy does not hold.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeptCopySupportTests {

    private static final String A = "KREWE"
    private static final String B = "GORJANA"
    private static final String PREF_KEY = TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY
    private static final String EDITOR_ID = "TEST_CUSTOMER_USER"
    private static final String EDITOR_USERNAME = "test.customer"
    private static final Timestamp FROM_DATE = Timestamp.valueOf("2026-04-21 00:00:00")
    private static final String FACADE_GRANT = "facade.ReconciliationFacadeServices.list#Reconciliations"
    private static final String SHOPIFY_EXTRACT = "reconciliation.ShopifyOrderExtractionServices.extract#ShopifyOrders"
    private static final long DAY = 86_400_000L

    /** The copy's own readThru and readAt (its manifest). */
    private static final Timestamp READ_THRU = ts("2026-11-20T00:00:00Z")
    private static final Timestamp READ_AT = ts("2026-11-20T00:07:30Z")
    /** What every Dataset row claims instead: later than any window here. */
    private static final Timestamp ROW_READ_THRU = ts("2026-12-31T00:00:00Z")
    private static final Timestamp START = ts("2026-11-10T08:00:00Z")
    private static final Timestamp END = ts("2026-11-11T08:00:00Z")

    private ExecutionContext ec
    private int seq = 0

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "kept-copy-support")
        // the test data manager outlives the test DB, whose fresh sequence hands out the same dataset ids again
        File root = DataManagerSupport.resolveDirectoryFile(ec, DataManagerSupport.resolveDataManagerLocation(ec), true)
        assertTrue(root.path.contains("test-data-manager"), "tests never write the dev datamanager: ${root}")
        new File(root, "datasets").deleteDir()
        new File(root, DataManagerSupport.RECONCILIATION_RUNS_PATH).deleteDir()
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.loadSeedData(ec, "classpath://data/EntityTypeData.xml", "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DatasetSeedData.xml")
        ec.entity.makeDataLoader().location("component://darpan/data/SourceSystemConnectorSeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        insert("moqui.security.UserGroup", [userGroupId: B, description: "Gorjana",
                                            groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        insert("moqui.security.UserGroupMember", [userGroupId: B, userId: EDITOR_ID, fromDate: FROM_DATE])
        insert(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: B, userId: EDITOR_ID,
                permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, fromDate: FROM_DATE])
        insert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: EDITOR_ID, fromDate: FROM_DATE])
        // one declared extract option, so the options key is exercised on both paths
        def shopify = ec.entity.find(SourceSystemConnectorSupport.ENTITY_NAME).condition("systemEnumId", "SHOPIFY").disableAuthz().one()
        shopify.set("extractOptionsJson", '[{"name":"includeTest","label":"Include test orders","type":"boolean"}]')
        shopify.update()
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
    void noDatasetMeansLiveAsToday() {
        // a covering copy for another connection serves nothing here
        String other = dataset("conn-other")
        copy(A, other, [order("o1", "2026-11-10T09:00:00Z")])

        List<Map> calls = []
        Map run = interactive("conn-none", START, END, calls)
        assertEquals([], run.errors)
        assertEquals(1, calls.size(), "the live extractor is called once")

        Map connector = SourceSystemConnectorSupport.resolve(ec, "SHOPIFY")
        String fileNameValue = ReconciliationOutputSupport.sanitizeUploadFileName("File 1-orders-api.json", "file1-api.json")
        Map expected = RunExtractSupport.buildExtractParams(connector, "FILE_1", "conn-none", run.artifact as Map, START, END,
                fileNameValue, null, null, null, "UTC")
        assertEquals(expected, calls[0].params, "the live call's parameters are today's, exactly")
        assertEquals(SHOPIFY_EXTRACT, calls[0].service)

        File live = locationFile(calls[0].location as String)
        assertEquals(RunExtractSupport.sourceResultForLocation(ec, run.source, "FILE_1", calls[0].params.fileName as String,
                calls[0].location as String, 1), run.result)
        assertEquals(LIVE_TEXT, live.getText("UTF-8"), "the live file is the extractor's, untouched")
        assertEquals([live.name], live.parentFile.list().toList(), "nothing else is written beside it: no slice, no side file")
    }

    @Test
    void aServedSliceHoldsExactlyTheWindow() {
        String id = dataset("conn-window")
        copy(A, id, [
                order("at-start", "2026-11-10T08:00:00Z"),
                order("at-start-offset", "2026-11-10T00:00:00-08:00"),
                order("before-start", "2026-11-10T07:59:59.999Z"),
                order("before-end", "2026-11-11T07:59:59.999Z"),
                order("at-end", "2026-11-11T08:00:00Z"),
                order("at-end-millis", "2026-11-11T08:00:00.000Z"),
                [id: "no-created", updatedAt: "2026-11-10T10:00:00Z"],
        ])
        List<Map> calls = []
        Map run = interactive("conn-window", START, END, calls)
        assertEquals([], run.errors)
        assertEquals(0, calls.size(), "a served side never calls the live extractor")

        Map doc = json(locationFile(run.result.fileLocation as String))
        assertEquals(["at-start", "at-start-offset", "before-end"] as Set, doc.records*.id as Set)
        assertEquals(3, run.result.recordCount)
        Map manifest = KeptCopyStore.manifest(DatasetRefreshSupport.datasetDir(ec, A, id))
        assertEquals(true, doc.metadata.servedFromCopy)
        assertEquals(id, doc.metadata.datasetId)
        assertEquals(manifest.version, doc.metadata.copyVersion)
        assertEquals(READ_AT.toInstant().toString(), doc.metadata.copyReadAt)

        // the same file the live extract would have written for this side
        Map connector = SourceSystemConnectorSupport.resolve(ec, "SHOPIFY")
        Map live = RunExtractSupport.buildExtractParams(connector, "FILE_1", "conn-window", run.artifact as Map, START, END,
                ReconciliationOutputSupport.sanitizeUploadFileName("File 1-orders-api.json", "file1-api.json"), null, null, null, "UTC")
        assertEquals(locationFile(DataManagerSupport.childLocation(live.outputLocation as String, live.fileName as String)).canonicalPath,
                new File(run.result.fileLocation as String).canonicalPath)
        assertFalse(new File(locationFile(run.result.fileLocation as String).parentFile,
                ExcludedRecordsSidecar.fileNameFor(live.fileName as String)).exists(), "no filters: no side file")
    }

    @Test
    void filtersApplyAndSetAsideGoesToTheSideFile() {
        String id = dataset("conn-filters")
        copy(A, id, [
                order("web-paid", "2026-11-10T09:00:00Z", [sourceName: "web", displayFinancialStatus: "PAID"]),
                order("pos-paid", "2026-11-10T10:00:00Z", [sourceName: " pos ", displayFinancialStatus: "PAID"]),
                order("web-pending", "2026-11-10T11:00:00Z", [sourceName: "web", displayFinancialStatus: "PENDING"]),
                order("web-nostatus", "2026-11-10T12:00:00Z", [sourceName: "web"]),
                order("nosource-refunded", "2026-11-10T13:00:00Z", [displayFinancialStatus: "partially_refunded"]),
                order("pos-outside", "2026-11-12T13:00:00Z", [sourceName: "pos", displayFinancialStatus: "PAID"]),
        ])
        List<Map<String, Object>> rules = [
                [sequenceNum: 1, fieldExpression: "sourceName", operator: "EXCLUDE_IN", filterValues: "POS"],
                [sequenceNum: 2, fieldExpression: "displayFinancialStatus", operator: "INCLUDE_IN", filterValues: "PAID,PARTIALLY_REFUNDED"],
        ]
        String location = runLocation("filters")
        Map served = KeptCopySupport.sliceIfServed(ec, KeptCopySupport.side(SourceSystemConnectorSupport.resolve(ec, "SHOPIFY"),
                [shopifyAuthConfigId: "conn-filters", sourceFilters: rules, outputLocation: location, fileName: "filters.json"],
                [tenantId: A, windowStart: START, windowEnd: END]))
        assertTrue(served.served as boolean, served.reason as String)

        // what SourceFilterSupport and the sidecar produce for the same window records, in the copy's order
        List<Map> inWindow = []
        Iterator<Map> it = KeptCopyStore.records(DatasetRefreshSupport.datasetDir(ec, A, id))
        while (it.hasNext()) {
            Map r = (Map) it.next().record
            if (r.id != "pos-outside") inWindow << r
        }
        Map expected = SourceFilterSupport.applyToRecords(inWindow, SourceFilterSupport.parseRules(rules))
        File expectedSide = File.createTempFile("expected-", ".json")
        try {
            ExcludedRecordsSidecar.write(expectedSide, (Map) expected.excludedCollector, "filters.json")
            File side = new File(served.extractFile.parentFile as File, ExcludedRecordsSidecar.fileNameFor("filters.json"))
            assertTrue(side.isFile(), "the set-aside records are in the side file")
            assertEquals(expectedSide.getText("UTF-8"), side.getText("UTF-8"))
        } finally {
            expectedSide.delete()
        }
        Map doc = json(served.extractFile as File)
        assertEquals(JsonOutput.toJson(expected.records), JsonOutput.toJson(doc.records))
        assertEquals(["web-paid", "nosource-refunded"], doc.records*.id)
        assertEquals(JsonOutput.toJson(expected.configuredExclusions), JsonOutput.toJson(doc.metadata.filters.configuredExclusions))
        assertEquals(5, doc.metadata.extractedRecordCount)
        assertEquals(2, served.recordCount)
    }

    /** Review Focus 3: "today so far" ends after the copy's readThru, so it reads live, never a partial slice. */
    @Test
    void aWindowPastReadThruReadsLive() {
        String id = dataset("conn-late")
        copy(A, id, [order("late", "2026-11-19T12:00:00Z")])
        Timestamp start = ts("2026-11-19T08:00:00Z")
        Timestamp pastEnd = new Timestamp(READ_THRU.time + 1)

        List<Map> calls = []
        Map run = interactive("conn-late", start, pastEnd, calls)
        assertEquals([], run.errors)
        assertEquals(1, calls.size(), "read live")
        assertEquals(LIVE_TEXT, locationFile(calls[0].location as String).getText("UTF-8"))
        assertEquals(KeptCopySupport.PAST_READ_THRU, direct("conn-late", start, pastEnd, "late-direct").reason)
        assertFalse(new File(locationFile(runLocation("late-direct")), "slice.json").exists(), "nothing is left behind")

        // ending exactly at readThru is inside the copy
        Map atReadThru = direct("conn-late", start, READ_THRU, "late-edge")
        assertTrue(atReadThru.served as boolean, atReadThru.reason as String)
        assertEquals(["late"], json(atReadThru.extractFile as File).records*.id)
    }

    /** Review Focus 3: a window that starts before the copy's horizon reads live. */
    @Test
    void aWindowBeforeTheHorizonReadsLive() {
        String id = dataset("conn-old", [horizonDays: 10])
        copy(A, id, [order("old", "2026-11-10T12:00:00Z")], 10)
        Timestamp horizon = new Timestamp(READ_THRU.time - 10 * DAY)
        Timestamp tooEarly = new Timestamp(horizon.time - 1)

        List<Map> calls = []
        Map run = interactive("conn-old", tooEarly, END, calls)
        assertEquals([], run.errors)
        assertEquals(1, calls.size(), "read live")
        assertEquals(KeptCopySupport.BEFORE_HORIZON, direct("conn-old", tooEarly, END, "old-direct").reason)

        Map atHorizon = direct("conn-old", horizon, END, "old-edge")
        assertTrue(atHorizon.served as boolean, atHorizon.reason as String)
        assertEquals(["old"], json(atHorizon.extractFile as File).records*.id)
    }

    /**
     * I2: raising horizonDays does not widen serving: the copy covers from its last full read's horizon start (its
     * manifest's coveredFrom), so older windows read live until the full read the change forces has run.
     */
    @Test
    void raisingTheHorizonStopsServingOlderWindowsUntilAFullRead() {
        String id = dataset("conn-raise", [horizonDays: 10])
        List<Map> records = [order("recent", "2026-11-15T12:00:00Z"), order("older", "2026-11-01T12:00:00Z")]
        copy(A, id, [records[0]], 10)
        Timestamp twentyBack = new Timestamp(READ_THRU.time - 20 * DAY)
        Timestamp tenBack = new Timestamp(READ_THRU.time - 10 * DAY)
        assertEquals(KeptCopySupport.BEFORE_HORIZON, direct("conn-raise", twentyBack, END, "raise-0").reason)
        def before = ec.entity.find("darpan.dataset.Dataset").condition("datasetId", id).disableAuthz().useCache(false).one()
        before.set("lastFullReadAt", READ_AT)
        before.update()

        // the operator raises the horizon to 30 days
        assertEquals([], underFacadeGrant("dataset.DatasetServices.store#Dataset", [systemEnumId: "SHOPIFY",
                sourceConfigId: "conn-raise", options: [:], idField: "id", stampField: "updatedAt", windowField: "createdAt",
                horizonDays: 30]).errors)
        def row = ec.entity.find("darpan.dataset.Dataset").condition("datasetId", id).disableAuthz().useCache(false).one()
        assertEquals(30, row.horizonDays as int)
        assertNull(row.lastFullReadAt, "the next refresh reads in full")
        Map manifest = KeptCopyStore.manifest(DatasetRefreshSupport.datasetDir(ec, A, id))
        assertEquals("DRK_FULL", DatasetRefreshSupport.readKind(row.getMap() + [lastFullReadAt: READ_THRU],
                manifest, false, new Timestamp(READ_THRU.time + DAY)), "the copy does not cover 30 days, so the read is full")

        // the row says 30 days, the copy covers 10: a window 20 days back is still not served, a recent one still is
        Map stillOld = direct("conn-raise", twentyBack, END, "raise-1")
        assertFalse(stillOld.served as boolean)
        assertEquals(KeptCopySupport.BEFORE_HORIZON, stillOld.reason)
        Map recent = direct("conn-raise", tenBack, READ_THRU, "raise-2")
        assertTrue(recent.served as boolean, recent.reason as String)

        // the full read over 30 days lands: the older window is served, with the order the old copy never held
        copy(A, id, records, 30)
        Map served = direct("conn-raise", twentyBack, READ_THRU, "raise-3")
        assertTrue(served.served as boolean, served.reason as String)
        assertEquals(["older", "recent"] as Set, json(served.extractFile as File).records*.id as Set)
    }

    /** I2: an old manifest that records no coverage reads as covering nothing, and the next refresh is full. */
    @Test
    void aCopyWithoutCoverageFieldsIsNotServed() {
        String id = dataset("conn-legacy")
        copy(A, id, [order("l1", "2026-11-10T12:00:00Z")])
        assertTrue(direct("conn-legacy", START, END, "legacy-0").served as boolean, "control: the copy as written serves")
        File dir = DatasetRefreshSupport.datasetDir(ec, A, id)
        File manifestFile = new File(new File(dir, KeptCopyStore.manifest(dir).version as String), KeptCopyStore.MANIFEST_FILE)
        Map written = (Map) new JsonSlurper().parse(manifestFile, "UTF-8")

        // the same settings recorded, but no coveredFrom: not covering
        manifestFile.setText(JsonOutput.toJson(written.findAll { it.key != "coveredFrom" }), "UTF-8")
        Map noFrom = direct("conn-legacy", START, END, "legacy-1")
        assertFalse(noFrom.served as boolean)
        assertEquals(KeptCopySupport.BEFORE_HORIZON, noFrom.reason)

        // a manifest written before coverage was recorded at all
        manifestFile.setText(JsonOutput.toJson(written.subMap(["version", "readThru", "readAt", "recordCount"])), "UTF-8")
        Map legacy = direct("conn-legacy", START, END, "legacy-2")
        assertFalse(legacy.served as boolean)
        assertEquals(KeptCopySupport.OTHER_SETTINGS, legacy.reason)
        def row = ec.entity.find("darpan.dataset.Dataset").condition("datasetId", id).disableAuthz().useCache(false).one()
        assertEquals("DRK_FULL", DatasetRefreshSupport.readKind(row.getMap() + [lastFullReadAt: READ_THRU],
                KeptCopyStore.manifest(dir), false, new Timestamp(READ_THRU.time + DAY)))
        ["legacy-1", "legacy-2"].each { String token ->
            assertFalse(new File(locationFile(runLocation(token)), "slice.json").exists(), "nothing written for ${token}")
        }
    }

    @Test
    void aByKeysSideReadsLive() {
        String id = dataset("conn-keys")
        copy(A, id, [order("k1", "2026-11-10T12:00:00Z")])
        Map connector = SourceSystemConnectorSupport.resolve(ec, "SHOPIFY")
        // as the interactive path sends a read by the other side's keys, and as an audit re-check sends one
        [[lookupKeys: ["k1"], lookupKeyField: "id"], [lookupKeys: ["k1"], lookupKeyField: "id", lookupWithinWindow: true]].eachWithIndex { Map keys, int i ->
            String location = runLocation("keys-${i}")
            Map side = KeptCopySupport.side(connector, [shopifyAuthConfigId: "conn-keys", outputLocation: location, fileName: "keys.json"] + keys,
                    [tenantId: A, windowStart: START, windowEnd: END])
            assertEquals(true, side.byKeys)
            Map served = KeptCopySupport.sliceIfServed(ec, side)
            assertFalse(served.served as boolean)
            assertEquals(KeptCopySupport.BY_KEYS, served.reason)
            assertFalse(new File(locationFile(location), "keys.json").exists())
        }
        if (ec.service.getServiceDefinition(SHOPIFY_EXTRACT)?.inParameterNameList?.contains("lookupKeys")) {
            List<Map> calls = []
            Map run = interactive("conn-keys", START, END, calls, [:], [lookupKeys: ["k1"], lookupKeyField: "id"])
            assertEquals([], run.errors)
            assertEquals(1, calls.size(), "the interactive read by key goes live")
            assertEquals(["k1"], calls[0].params.lookupKeys)
        }
    }

    /** Review Focus 4: tenant B, with the same endpoint and connection as tenant A, is never served A's copy. */
    @Test
    void tenantBIsNeverServedTenantAsCopy() {
        String idA = dataset("conn-shared")
        copy(A, idA, [order("a-order", "2026-11-10T12:00:00Z")])
        Map connector = SourceSystemConnectorSupport.resolve(ec, "SHOPIFY")
        Closure<Map> sideFor = { String tenant, String token ->
            KeptCopySupport.side(connector, [shopifyAuthConfigId: "conn-shared", outputLocation: runLocation(token), fileName: "shared.json"],
                    [tenantId: tenant, windowStart: START, windowEnd: END])
        }
        // control: A is served A's copy
        Map own = KeptCopySupport.sliceIfServed(ec, sideFor(A, "shared-a"))
        assertTrue(own.served as boolean, own.reason as String)

        // B's interactive run reads live
        actAs(EDITOR_USERNAME, B)
        List<Map> calls = []
        Map run = interactive("conn-shared", START, END, calls)
        assertEquals([], run.errors)
        assertEquals(1, calls.size(), "B reads live")
        assertEquals(KeptCopySupport.NO_DATASET, KeptCopySupport.sliceIfServed(ec, sideFor(B, "shared-b-user")).reason)

        // B's scheduled run: nobody logged in, the tenant published by withSystemTenant
        assertEquals(KeptCopySupport.NO_DATASET, scheduledAs(B) { KeptCopySupport.sliceIfServed(ec, sideFor(B, "shared-b-job")) }.reason)

        // an automation of B started by a user whose active tenant is A: the run's tenant decides
        actAs(EDITOR_USERNAME, A)
        Map crossed = KeptCopySupport.sliceIfServed(ec, sideFor(B, "shared-b-crossed"))
        assertFalse(crossed.served as boolean)
        assertEquals(KeptCopySupport.OTHER_TENANT, crossed.reason)
        ["shared-b-user", "shared-b-job", "shared-b-crossed"].each { String token ->
            assertFalse(new File(locationFile(runLocation(token)), "shared.json").exists(), "nothing written for ${token}")
        }

        // B's own dataset for the same key is B's copy, in B's folder
        actAs(EDITOR_USERNAME, B)
        String idB = dataset("conn-shared")
        assertFalse(idA == idB)
        copy(B, idB, [order("b-order", "2026-11-10T12:00:00Z")])
        Map servedB = scheduledAs(B) { KeptCopySupport.sliceIfServed(ec, sideFor(B, "shared-b-own")) }
        assertTrue(servedB.served as boolean, servedB.reason as String)
        Map docB = json(servedB.extractFile as File)
        assertEquals(["b-order"], docB.records*.id)
        assertEquals(idB, docB.metadata.datasetId)
    }

    /** The interactive and the scheduled caller, given equivalent sides, write the same slice and side file. */
    @Test
    void bothPathsServeTheSameSlice() {
        String id = dataset("conn-both", [options: [includeTest: true]])
        copy(A, id, [
                order("web-1", "2026-11-10T09:00:00Z", [sourceName: "web"]),
                order("pos-1", "2026-11-10T10:00:00Z", [sourceName: "pos"]),
                order("web-2", "2026-11-11T07:00:00Z", [sourceName: "web"]),
                order("web-out", "2026-11-11T09:00:00Z", [sourceName: "web"]),
        ])
        // one filter, in each path's own store
        ec.entity.makeDataLoader().xmlText("""<entity-facade-xml type="seed">
            <darpan.rule.RuleSet ruleSetId="KC_RS" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareScope compareScopeId="KC_SCOPE" ruleSetId="KC_RS" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareSource compareScopeId="KC_SCOPE" fileSide="FILE_1" systemEnumId="SHOPIFY" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareSourceFilter compareScopeId="KC_SCOPE" fileSide="FILE_1" sequenceNum="1"
                fieldExpression="\$.records[*].sourceName" operator="EXCLUDE_IN" filterValues="POS" companyUserGroupId="${A}"/>
            <darpan.reconciliation.ReconciliationAutomation automationId="KC_AUTO" automationName="kept copy" companyUserGroupId="${A}"
                inputModeEnumId="${AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE}" savedRunId="KC_RS"/>
            <darpan.reconciliation.ReconciliationAutomationSource automationId="KC_AUTO" fileSide="FILE_1" companyUserGroupId="${A}"
                sourceTypeEnumId="${AutomationExecutionSupport.AUTOMATION_SOURCE_API}" systemEnumId="SHOPIFY"/>
            <darpan.reconciliation.ReconciliationAutomationSourceFilter automationId="KC_AUTO" fileSide="FILE_1" sequenceNum="1"
                fieldExpression="\$.records[*].sourceName" operator="EXCLUDE_IN" filterValues="POS" companyUserGroupId="${A}"/>
        </entity-facade-xml>""").dummyFks(true).load()

        List<Map> calls = []
        Map run = interactive("conn-both", START, END, calls, [compareScopeId: "KC_SCOPE", extractParametersJson: '{"includeTest":true}'])
        assertEquals([], run.errors)
        assertEquals(0, calls.size(), "the interactive side is served")

        Map automation = [automationId: "KC_AUTO", companyUserGroupId: A, relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_PREVIOUS_DAY]
        Map source = [automationId: "KC_AUTO", fileSide: "FILE_1", systemEnumId: "SHOPIFY", companyUserGroupId: A,
                      sourceTypeEnumId: AutomationExecutionSupport.AUTOMATION_SOURCE_API,
                      safeMetadataJson: JsonOutput.toJson([extractServiceName: SHOPIFY_EXTRACT,
                                                           parameters: [shopifyAuthConfigId: "conn-both", includeTest: true]])]
        String location = runLocation("both-auto")
        Map scheduled = scheduledAs(A) {
            AutomationExecutionSupport.callConfiguredSourceExtractor(ec, automation, source,
                    [childWindowStartDate: START, childWindowEndDate: END], [automationExecutionId: "KC_EXEC", sideOutputLocation: location])
        }
        assertEquals(true, scheduled.requestMetadata?.servedFromCopy, scheduled.toString())
        assertEquals(2, scheduled.recordCount)
        assertEquals(true, scheduled.dataAvailable)
        assertEquals(START, scheduled.readWindowFrom)
        assertEquals(END, scheduled.readWindowThru)

        File interactiveFile = locationFile(run.result.fileLocation as String)
        File scheduledFile = locationFile(scheduled.fileLocation as String)
        assertFalse(interactiveFile.canonicalPath == scheduledFile.canonicalPath)
        assertEquals(["web-1", "web-2"] as Set, json(interactiveFile).records*.id as Set)
        assertArrayEquals(interactiveFile.bytes, scheduledFile.bytes, "the same slice, byte for byte")
        File interactiveSide = new File(interactiveFile.parentFile, ExcludedRecordsSidecar.fileNameFor(interactiveFile.name))
        File scheduledSide = new File(scheduledFile.parentFile, ExcludedRecordsSidecar.fileNameFor(scheduledFile.name))
        assertTrue(interactiveSide.isFile() && scheduledSide.isFile(), "both set pos-1 aside")
        assertEquals(json(interactiveSide).records*.id, json(scheduledSide).records*.id)
        assertEquals(["pos-1"], json(scheduledSide).records*.id)
    }

    /**
     * Ruling P3a-14: a slice that fails mid-stream (here its filter throws on the second record in the window, after the
     * first was written) closes the copy's file, leaves no partial output and no folder it made, and the side reads live.
     */
    @Test
    void aSliceThatFailsMidStreamClosesTheCopyAndLeavesNothing() {
        String id = dataset("conn-abort")
        copy(A, id, (1..5).collect { int n -> order("ab-${n}".toString(), "2026-11-10T1${n}:00:00Z".toString()) })
        int openBefore = KeptCopyStore.openReaders()
        int[] evaluated = [0]
        SourceFilterSupport.metaClass.static.evaluate = { Object record, List rules ->
            if (++evaluated[0] == 2) throw new IllegalStateException("filter failed mid-stream")
            return null
        }
        try {
            String location = runLocation("abort")
            File runFolder = locationFile(location).parentFile
            assertFalse(runFolder.exists(), "test precondition: the slice makes its own folders")
            Map served = KeptCopySupport.sliceIfServed(ec, KeptCopySupport.side(SourceSystemConnectorSupport.resolve(ec, "SHOPIFY"),
                    [shopifyAuthConfigId: "conn-abort", outputLocation: location, fileName: "abort.json"],
                    [tenantId: A, windowStart: START, windowEnd: END]))
            assertFalse(served.served as boolean)
            assertTrue((served.reason as String).contains("filter failed mid-stream"), served.reason as String)
            assertEquals(2, evaluated[0], "it failed mid-stream, after a record was written")
            assertEquals(openBefore, KeptCopyStore.openReaders(), "the copy's file is closed")
            assertFalse(runFolder.exists(), "no partial output and no empty folder is left: ${runFolder.list()}")

            // through the interactive path: the live read, alone in its folder
            evaluated[0] = 0
            List<Map> calls = []
            Map run = interactive("conn-abort", START, END, calls)
            assertEquals([], run.errors)
            assertEquals(1, calls.size(), "read live")
            File live = locationFile(calls[0].location as String)
            assertEquals([live.name], live.parentFile.list().toList(), "only the live file is there")
            assertEquals(openBefore, KeptCopyStore.openReaders())
        } finally {
            GroovySystem.metaClassRegistry.removeMetaClass(SourceFilterSupport)
        }
        // and a slice that is served closes the copy too
        Map ok = direct("conn-abort", START, END, "abort-ok")
        assertTrue(ok.served as boolean, ok.reason as String)
        assertEquals(5, ok.recordCount)
        assertEquals(openBefore, KeptCopyStore.openReaders())
    }

    // ------------------------------------------------------------------------------------------------------- helpers

    private static final String LIVE_TEXT = '{"metadata":{"live":true},"records":[{"id":"live-1"}]}'

    private static Timestamp ts(String iso) {
        return Timestamp.from(java.time.Instant.parse(iso))
    }

    private static Map order(String id, String createdAt, Map extra = [:]) {
        return [id: id, createdAt: createdAt, updatedAt: createdAt] + extra
    }

    /** The active tenant's dataset for SHOPIFY + conn; its row then claims a readThru and version the copy does not have. */
    private String dataset(String conn, Map extra = [:]) {
        Map res = underFacadeGrant("dataset.DatasetServices.store#Dataset", [systemEnumId: "SHOPIFY", sourceConfigId: conn,
                options: [:], idField: "id", stampField: "updatedAt", windowField: "createdAt"] + extra)
        assertEquals([], res.errors)
        String id = res.out.datasetId
        def row = ec.entity.find("darpan.dataset.Dataset").condition("datasetId", id).disableAuthz().useCache(false).one()
        row.set("readThru", ROW_READ_THRU)
        row.set("currentVersion", "v-the-row-lags")
        row.update()
        return id
    }

    /** A full read into the copy, as refresh#Dataset makes one: it covers horizonDays back from READ_THRU. */
    private void copy(String tenant, String datasetId, List<Map> records, int horizonDays = 62, String idField = "id") {
        File dir = DatasetRefreshSupport.datasetDir(ec, tenant, datasetId)
        KeptCopyStore.replace(dir, records.iterator(), idField, "updatedAt", "createdAt",
                new Timestamp(READ_THRU.time - horizonDays * DAY), READ_THRU, READ_AT, 2)
    }

    private String runLocation(String token) {
        return DataManagerSupport.resolveReconciliationRunLocation(ec, "kc-${token}", "20261120-000000000")
    }

    private Map direct(String conn, Timestamp start, Timestamp end, String token) {
        return KeptCopySupport.sliceIfServed(ec, KeptCopySupport.side(SourceSystemConnectorSupport.resolve(ec, "SHOPIFY"),
                [shopifyAuthConfigId: conn, outputLocation: runLocation(token), fileName: "slice.json"],
                [tenantId: A, windowStart: start, windowEnd: end]))
    }

    /** One interactive side through RunExtractSupport.extractApiSource, the live extractor a stub that writes LIVE_TEXT. */
    private Map interactive(String conn, Timestamp start, Timestamp end, List<Map> calls, Map extra = [:], Map byKeys = null) {
        String token = "i${++seq}"
        Map source = [systemEnumId: "SHOPIFY", sourceConfigType: "SHOPIFY_AUTH", sourceConfigId: conn, fileSide: "FILE_1"] + extra
        Map artifact = [location: runLocation(token), runToken: token]
        Closure stub = { String service, Map params ->
            String location = DataManagerSupport.childLocation(params.outputLocation as String, params.fileName as String)
            DataManagerSupport.writeText(ec, location, LIVE_TEXT)
            calls << [service: service, params: new LinkedHashMap(params), location: location]
            return [fileLocation: location, fileName: params.fileName, recordCount: 1]
        }
        ec.message.clearAll()
        Map result = RunExtractSupport.extractApiSource(ec, source, "FILE_1", "File 1", artifact,
                [windowStartDate: start, windowEndDate: end, timeZone: "UTC"], null, stub, byKeys)
        List<String> errors = ec.message.errors.collect { it.toString() }
        ec.message.clearAll()
        return [result: result, artifact: artifact, source: source, errors: errors]
    }

    /** As a scheduled automation runs: nobody logged in (the anonymous login), the tenant published by withSystemTenant. */
    private Map scheduledAs(String tenant, Closure<Map> work) {
        ec.user.logoutUser()
        ec.user.loginAnonymousIfNoUser()
        try {
            return (Map) TenantAccessSupport.withSystemTenant(tenant) {
                assertEquals(tenant, TenantAccessSupport.currentActiveTenantUserGroupId(ec), "test precondition: the job's tenant only")
                return work.call()
            }
        } finally {
            actAs(EDITOR_USERNAME, A)
        }
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
                List<String> errors = ec.message.errors.collect { it.toString() }
                ec.message.clearAll()
                return [out: out, errors: errors]
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
