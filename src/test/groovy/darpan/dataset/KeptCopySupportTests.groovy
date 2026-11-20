package darpan.dataset

import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.ReconciliationApiWindowSupport
import darpan.facade.reconciliation.ReconciliationOutputSupport
import darpan.facade.reconciliation.RunExtractSupport
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import darpan.reconciliation.conclusion.RunConclusionSupport
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
import java.time.OffsetDateTime

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
    private static final String RETURNS_EXTRACT = "reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlReturns"
    private static final String DATA_DEFINED_EXTRACT = "reconciliation.DataDefinedExtractionServices.extract#DataDefinedSource"
    /** A tenant east of UTC, so a day read in UTC is not the tenant's day. */
    private static final String TENANT_ZONE = "Asia/Kolkata"
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
        // Shopify's own side-file declarations on its rows (ruling P3b1-9). The OMS returns row is used here as a windowed
        // source with no projection: its side-file N is set below, and its projection service (darpan-hotwax's classes, not
        // on this classpath) is exercised by darpan-hotwax's HotWaxRollingCopyParityTests.
        ec.entity.makeDataLoader().location("component://shopify-darpan/data/ShopifyConnectorSeedData.xml").dummyFks(true).load()
        def omsReturns = ec.entity.find(SourceSystemConnectorSupport.ENTITY_NAME).condition("systemEnumId", "OMS_RETURNS_GQL").disableAuthz().one()
        omsReturns.set("excludedRecordsSidecar", "N")
        omsReturns.update()
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

    @Test
    void aServedSliceOfACompositeDatasetCarriesNoCopyId() {
        String id = dataset("conn-composite", [idField: "id,sub"])
        List<Map> records = [order("o1", "2026-11-10T09:00:00Z", [sub: "1"]), order("o1", "2026-11-10T10:00:00Z", [sub: "2"])]
                .collect { it + [_copyId: "${it.id}|${it.sub}".toString()] }
        copy(A, id, records, 62, "id,sub")
        assertTrue(KeptCopyStore.records(DatasetRefreshSupport.datasetDir(ec, A, id)).every { it.record.containsKey("_copyId") },
                "the copy itself keeps the derived key")
        Map served = direct("conn-composite", START, END, "composite")
        assertTrue(served.served as boolean, served.reason as String)
        List<Map> out = json(served.extractFile as File).records
        assertEquals(2, out.size())
        assertTrue(out.every { !it.containsKey("_copyId") }, out.toString())
        assertEquals(["1", "2"], out*.sub)
    }

    // ------------------------------------------------------------------- plan 3b-1, Task 2b: the live call's own window

    /**
     * Ruling 3b1-9. A run of one day, Nov 10 in the tenant's zone, is served exactly the days its live read would fetch, on
     * both paths and with the seeded connector rows:
     * - OMS returns (no preserveWindowInstants, no windowFieldName) are sent the instants the live window code anchored at
     *   the tenant's midnights, and read them half-open: 23:59 on Nov 10 is in, 00:00 on Nov 11 is out;
     * - a whole-days source (windowDateOnly, as NetSuite) is sent the calendar days the live code read in the tenant's zone,
     *   and reads them half-open on whole days: Nov 10 is in, Nov 11 is out. A record it dates by instant is not placed on
     *   a day here (the source compares days in its own zone), so that copy is not served.
     */
    @Test
    void aDayWindowSourceIsServedTheSameDaysTheLiveReadSends() {
        // the run's day as each path's live code anchors it
        Map window = ReconciliationApiWindowSupport.normalizeCalendarWindow(ts("2026-11-10T00:00:00Z"), ts("2026-11-11T00:00:00Z"), TENANT_ZONE)
        Map automation = [automationId: "KC_DAYS_AUTO", companyUserGroupId: A, windowTimeZone: TENANT_ZONE,
                          relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_PREVIOUS_DAY]
        Map autoWindow = AutomationExecutionSupport.resolveWindows(automation, [scheduledFireTime: ts("2026-11-11T04:30:00Z")])[0]
        assertEquals(ts("2026-11-09T18:30:00Z"), window.windowStartDate)
        assertEquals(ts("2026-11-10T18:30:00Z"), window.windowEndDate)
        assertEquals([window.windowStartDate, window.windowEndDate], [autoWindow.childWindowStartDate, autoWindow.childWindowEndDate])

        // --- OMS returns: the seeded row, sent instants, no window field
        Map returnsConnector = SourceSystemConnectorSupport.resolve(ec, "OMS_RETURNS_GQL")
        assertFalse(returnsConnector.preserveWindowInstants as boolean, "the seeded row, unchanged")
        assertFalse(returnsConnector.windowDateOnly as boolean)
        assertNull(returnsConnector.windowFieldName)
        String returnsId = datasetOf("OMS_RETURNS_GQL", "conn-days-returns", "returnId", "entryDate")
        copyOf(A, returnsId, [
                omsReturn("first-minute", "2026-11-10T00:00:00+05:30"),
                omsReturn("day-before", "2026-11-09T23:59:59+05:30"),
                omsReturn("last-minute", "2026-11-10T23:59:00+05:30"),
                omsReturn("next-midnight", "2026-11-11T00:00:00+05:30"),
                omsReturn("utc-day-before", "2026-11-09T20:00:00Z"),
        ], "returnId", "entryDate")
        List<Map> calls = []
        Map run = interactiveSide([systemEnumId: "OMS_RETURNS_GQL", sourceConfigType: "HOTWAX_OMS_GQL_RETURNS",
                                   sourceConfigId: "conn-days-returns", fileSide: "FILE_1"], window, calls)
        assertEquals([], run.errors)
        assertEquals(0, calls.size(), "served: the live extractor is never called")
        File interactiveFile = locationFile(run.result.fileLocation as String)
        assertEquals(["first-minute", "last-minute", "utc-day-before"] as Set, json(interactiveFile).records*.returnId as Set)
        // what the live call would have sent: the same instants, and no window field (its extractor windows on entryDate)
        Map sent = RunExtractSupport.buildExtractParams(returnsConnector, "FILE_1", "conn-days-returns", run.artifact as Map,
                (Timestamp) window.windowStartDate, (Timestamp) window.windowEndDate, "f.json", null, null, null, window.timeZone)
        assertEquals("2026-11-09T18:30:00Z", sent.windowStart)
        assertEquals("2026-11-10T18:30:00Z", sent.windowEnd)
        assertFalse(sent.containsKey("windowFieldName"))

        Map scheduled = scheduledAs(A) {
            AutomationExecutionSupport.callConfiguredSourceExtractor(ec, automation, automationSource("OMS_RETURNS_GQL", RETURNS_EXTRACT,
                    [omsRestSourceConfigId: "conn-days-returns"]), autoWindow,
                    [automationExecutionId: "KC_DAYS_EXEC", sideOutputLocation: runLocation("days-returns-auto")])
        }
        assertEquals(true, scheduled.requestMetadata?.servedFromCopy, scheduled.toString())
        assertArrayEquals(interactiveFile.bytes, locationFile(scheduled.fileLocation as String).bytes, "the same slice on both paths")

        // --- a whole-days source: a made-up row whose read sends calendar days (windowDateOnly), with its own day window field
        loadDaysConnector()
        String daysId = datasetOf("KC_DAYS", "conn-days", "id", "trandate")
        copyOf(A, daysId, [[id: "nov-10", trandate: "2026-11-10"], [id: "nov-09", trandate: "2026-11-09"],
                           [id: "nov-11", trandate: "2026-11-11"]], "id", "trandate")
        List<Map> dayCalls = []
        Map dayRun = interactiveSide([systemEnumId: "KC_DAYS", sourceConfigType: "KC_DAYS_TYPE", sourceConfigId: "conn-days",
                                      fileSide: "FILE_1"], window, dayCalls)
        assertEquals([], dayRun.errors)
        assertEquals(0, dayCalls.size(), "served")
        File dayFile = locationFile(dayRun.result.fileLocation as String)
        assertEquals(["nov-10"], json(dayFile).records*.id, "the day the live read sends, and only it")
        Map daySent = RunExtractSupport.buildExtractParams(SourceSystemConnectorSupport.resolve(ec, "KC_DAYS"), "FILE_1", "conn-days",
                dayRun.artifact as Map, (Timestamp) window.windowStartDate, (Timestamp) window.windowEndDate, "f.json", null, null, null,
                window.timeZone)
        assertEquals(["2026-11-10", "2026-11-11"], [daySent.windowStart, daySent.windowEnd])
        Map dayScheduled = scheduledAs(A) {
            AutomationExecutionSupport.callConfiguredSourceExtractor(ec, automation, automationSource("KC_DAYS", RETURNS_EXTRACT,
                    [omsRestSourceConfigId: "conn-days"]), autoWindow,
                    [automationExecutionId: "KC_DAYS_EXEC2", sideOutputLocation: runLocation("days-auto")])
        }
        assertEquals(true, dayScheduled.requestMetadata?.servedFromCopy, dayScheduled.toString())
        assertArrayEquals(dayFile.bytes, locationFile(dayScheduled.fileLocation as String).bytes)

        // a copy that dates a record by instant cannot be cut on the source's days: read live
        String instantId = datasetOf("KC_DAYS", "conn-days-instant", "id", "trandate")
        copyOf(A, instantId, [[id: "i1", trandate: "2026-11-10"], [id: "i2", trandate: "2026-11-10T12:00:00Z"]], "id", "trandate")
        Map byInstant = daySide("conn-days-instant", window, "days-instant")
        assertFalse(byInstant.served as boolean)
        assertTrue((byInstant.reason as String).contains("calendar day"), byInstant.reason as String)
        assertFalse(new File(locationFile(runLocation("days-instant")), "slice.json").exists(), "nothing is left behind")

        // the last day is not past the copy's readThru in every zone (Nov 20 ends at 12:00Z west of UTC): read live,
        // although the run's own end (Nov 19 18:30Z, the tenant's midnight) is before it
        Map late = ReconciliationApiWindowSupport.normalizeCalendarWindow(ts("2026-11-19T00:00:00Z"), ts("2026-11-20T00:00:00Z"), TENANT_ZONE)
        assertTrue((late.windowEndDate as Timestamp).time <= READ_THRU.time, "test precondition")
        assertEquals(KeptCopySupport.DAYS_PAST_READ_THRU, daySide("conn-days", late, "days-late").reason)
        // the copy covers from Sep 21 12:00Z, which is already Sep 22 east of UTC: a run of Sep 21, anchored at 12:00Z by a
        // tenant at UTC-12, starts inside the copy as an instant but its day may not all be in it, so it reads live
        String edgeId = datasetOf("KC_DAYS", "conn-days-edge", "id", "trandate")
        copyOf(A, edgeId, [[id: "e1", trandate: "2026-09-21"]], "id", "trandate", ts("2026-09-21T12:00:00Z"))
        Map early = ReconciliationApiWindowSupport.normalizeCalendarWindow(ts("2026-09-21T00:00:00Z"), ts("2026-09-22T00:00:00Z"), "Etc/GMT+12")
        assertEquals(ts("2026-09-21T12:00:00Z"), early.windowStartDate, "test precondition")
        assertEquals(KeptCopySupport.DAYS_BEFORE_HORIZON, daySide("conn-days-edge", early, "days-early").reason)
        Map nextDay = ReconciliationApiWindowSupport.normalizeCalendarWindow(ts("2026-09-22T00:00:00Z"), ts("2026-09-23T00:00:00Z"), "Etc/GMT+12")
        assertTrue(daySide("conn-days-edge", nextDay, "days-next").served as boolean, "the next day is wholly in the copy")
    }

    /**
     * Ruling 3b1-4 / 3b1-9. The seeded OMS returns row names no windowFieldName, so the dispatchers send none and its
     * extractor windows on its own declared field, entryDate: that is the side's field. A dataset kept on another field is
     * not served; an extractor that declares no field of its own is never served without the connector naming one.
     */
    @Test
    void aSourceWithNoWindowFieldNameUsesItsExtractorsField() {
        assertEquals("entryDate", ec.service.getServiceDefinition(RETURNS_EXTRACT).getInParameter("windowFieldName")?.attribute("default-value"),
                "the extractor declares its own window field")
        Map connector = SourceSystemConnectorSupport.resolve(ec, "OMS_RETURNS_GQL")
        String id = datasetOf("OMS_RETURNS_GQL", "conn-own-field", "returnId", "entryDate")
        copyOf(A, id, [omsReturn("r1", "2026-11-10T12:00:00Z"), omsReturn("r2", "2026-11-12T12:00:00Z")], "returnId", "entryDate")
        Map side = returnsSide(connector, "conn-own-field", "own-field")
        assertNull(side.windowFieldName, "nothing is sent")
        Map served = KeptCopySupport.sliceIfServed(ec, side)
        assertTrue(served.served as boolean, served.reason as String)
        assertEquals("entryDate", served.metadata.windowFieldName)
        assertEquals(["r1"], json(served.extractFile as File).records*.returnId)
        // with no projection service on its row, the same side asking its source to keep only some fields reads live
        Map keeping = RunExtractSupport.buildExtractParams(connector, "FILE_1", "conn-own-field", [location: runLocation("own-keep"), runToken: "k"],
                START, END, "slice.json", ["returnId", "entryDate"], null, null, "UTC")
        assertNull(connector.servingProjectionServiceName)
        assertEquals(KeptCopySupport.KEEPS_FIELDS, KeptCopySupport.sliceIfServed(ec, KeptCopySupport.side(connector, keeping,
                [tenantId: A, windowStart: START, windowEnd: END])).reason)

        // the same source kept on another field than its extractor windows on
        String other = datasetOf("OMS_RETURNS_GQL", "conn-other-field", "returnId", "returnDate")
        copyOf(A, other, [omsReturn("r1", "2026-11-10T12:00:00Z") + [returnDate: ts("2026-11-10T12:00:00Z").time]], "returnId", "returnDate")
        assertEquals(KeptCopySupport.OTHER_WINDOW_FIELD, KeptCopySupport.sliceIfServed(ec, returnsSide(connector, "conn-other-field", "other-field")).reason)

        // an extractor with no field of its own (a data-defined read windows on its definition's), and no connector field
        assertNull(ec.service.getServiceDefinition(DATA_DEFINED_EXTRACT).getInParameter("windowFieldName")?.attribute("default-value"))
        Map noField = KeptCopySupport.sliceIfServed(ec, KeptCopySupport.side([systemEnumId: "KC_NOFIELD", extractServiceName: DATA_DEFINED_EXTRACT,
                dateFromParameterName: "windowStart", dateToParameterName: "windowEnd", preserveWindowInstants: false],
                [sourceConfigId: "conn-nofield", windowStart: START.toInstant().toString(), windowEnd: END.toInstant().toString(),
                 outputLocation: runLocation("nofield"), fileName: "slice.json"], [tenantId: A, windowStart: START, windowEnd: END]))
        assertFalse(noField.served as boolean)
        assertEquals(KeptCopySupport.NO_WINDOW_FIELD, noField.reason)
    }

    /**
     * Plan 3a stays as it was: a Shopify orders side defaults its field to createdAt, its slice skips a record whose
     * createdAt is not an ISO instant (never an abort), and a source whose extractor rounds its window to whole days
     * unless told to keep instants (Shopify return refs, preserveWindowInstants N) reads live.
     */
    @Test
    void shopifyOrdersServingIsUnchanged() {
        String id = dataset("conn-unchanged")
        copy(A, id, [order("iso", "2026-11-10T09:00:00Z"), [id: "millis", createdAt: ts("2026-11-10T10:00:00Z").time, updatedAt: "2026-11-10T10:00:00Z"],
                     [id: "other-format", createdAt: "11/10/2026 11:00", updatedAt: "2026-11-10T11:00:00Z"]])
        Map side = KeptCopySupport.side(SourceSystemConnectorSupport.resolve(ec, "SHOPIFY"),
                [shopifyAuthConfigId: "conn-unchanged", outputLocation: runLocation("unchanged"), fileName: "slice.json"],
                [tenantId: A, windowStart: START, windowEnd: END])
        assertEquals("createdAt", side.windowFieldName)
        assertNull(side.liveOnly)
        Map served = KeptCopySupport.sliceIfServed(ec, side)
        assertTrue(served.served as boolean, served.reason as String)
        assertEquals(["iso"], json(served.extractFile as File).records*.id)

        Map refs = KeptCopySupport.sliceIfServed(ec, KeptCopySupport.side(SourceSystemConnectorSupport.resolve(ec, "SHOPIFY_RETURN_REFS"),
                [shopifyAuthConfigId: "conn-refs", windowStart: START.toInstant().toString(), windowEnd: END.toInstant().toString(),
                 outputLocation: runLocation("refs"), fileName: "slice.json"], [tenantId: A, windowStart: START, windowEnd: END]))
        assertFalse(refs.served as boolean)
        assertEquals(KeptCopySupport.ROUNDS_TO_DAYS, refs.reason)
    }

    /**
     * Ruling P3b1-6: a slice drops a record only when it places it outside the window. An OMS order-item unit carries
     * omsOrderDate, never the orderDate its connector windows on (the server filters on the order's date), so a unit copy
     * cannot be placed and the side reads live; it is never served an empty slice. A whole-days record with no date reads
     * live the same way.
     */
    @Test
    void aRecordTheSliceCannotPlaceMakesTheSideReadLive() {
        Map units = SourceSystemConnectorSupport.resolve(ec, "OMS_ORDER_ITEMS_GQL")
        assertEquals("orderDate", units.windowFieldName, "the seeded row")
        String id = datasetOf("OMS_ORDER_ITEMS_GQL", "conn-units", "unitId", "orderDate")
        copyOf(A, id, [[unitId: "u1", orderId: "o1", omsOrderDate: "2026-11-10T09:00:00Z"],
                       [unitId: "u2", orderId: "o1", omsOrderDate: "2026-11-10T09:00:00Z"]], "unitId", "orderDate")
        List<Map> calls = []
        Map run = interactiveSide([systemEnumId: "OMS_ORDER_ITEMS_GQL", sourceConfigType: "HOTWAX_OMS_GQL_ORDER_ITEMS",
                                   sourceConfigId: "conn-units", fileSide: "FILE_1"], [windowStartDate: START, windowEndDate: END, timeZone: "UTC"], calls)
        assertEquals([], run.errors)
        assertEquals(1, calls.size(), "read live, never served an empty slice")
        assertEquals("orderDate", calls[0].params.windowFieldName)
        Map params = RunExtractSupport.buildExtractParams(units, "FILE_1", "conn-units", [location: runLocation("units"), runToken: "units"],
                START, END, "slice.json", null, null, null, "UTC")
        params.outputLocation = runLocation("units")
        params.fileName = "slice.json"
        Map direct = KeptCopySupport.sliceIfServed(ec, KeptCopySupport.side(units, params, [tenantId: A, windowStart: START, windowEnd: END]))
        assertFalse(direct.served as boolean)
        assertTrue((direct.reason as String).contains("has no orderDate"), direct.reason as String)
        assertFalse(new File(locationFile(runLocation("units")), "slice.json").exists(), "nothing is left behind")

        // a small integer is no epoch-millis date: unplaceable, never a 1970 instant
        String smallId = datasetOf("OMS_RETURNS_GQL", "conn-small-int", "returnId", "entryDate")
        copyOf(A, smallId, [[returnId: "s1", entryDate: 5]], "returnId", "entryDate")
        Map small = KeptCopySupport.sliceIfServed(ec, returnsSide(SourceSystemConnectorSupport.resolve(ec, "OMS_RETURNS_GQL"),
                "conn-small-int", "small-int"))
        assertFalse(small.served as boolean)
        assertTrue((small.reason as String).contains("neither an ISO instant nor epoch milliseconds"), small.reason as String)

        loadDaysConnector()
        String daysId = datasetOf("KC_DAYS", "conn-days-undated", "id", "trandate")
        copyOf(A, daysId, [[id: "nov-10", trandate: "2026-11-10"], [id: "undated"]], "id", "trandate")
        Map window = ReconciliationApiWindowSupport.normalizeCalendarWindow(ts("2026-11-10T00:00:00Z"), ts("2026-11-11T00:00:00Z"), TENANT_ZONE)
        Map undated = daySide("conn-days-undated", window, "days-undated")
        assertFalse(undated.served as boolean)
        assertTrue((undated.reason as String).contains("has no trandate"), undated.reason as String)
    }

    /**
     * Ruling P3b1-9: a served slice writes the excluded-records side file exactly when its connector row says the live
     * extract does (excludedRecordsSidecar, declared by the system's own component), on a made-up system: Y writes it, N
     * applies the filters and writes none (the conclude pass then reads the side UNKNOWN, as on a live run), undeclared reads
     * live. Shopify's real declaration (Y) behaves the same.
     */
    @Test
    void aServedSliceWritesASideFileOnlyWhenItsConnectorSaysItsExtractDoes() {
        loadDaysConnector()
        Map window = ReconciliationApiWindowSupport.normalizeCalendarWindow(ts("2026-11-10T00:00:00Z"), ts("2026-11-11T00:00:00Z"), TENANT_ZONE)
        List<Map> dropX = [[sequenceNum: 1, fieldExpression: "id", operator: "EXCLUDE_IN", filterValues: "x"]]
        Closure<Map> serve = { String declared, String token ->
            // the test row given a filter parameter and a declaration, as a system's component would declare it
            Map days = SourceSystemConnectorSupport.resolve(ec, "KC_DAYS") + [filterParameterName: "sourceFilters", excludedRecordsSidecar: declared]
            String daysId = datasetOf("KC_DAYS", "conn-days-${token}", "id", "trandate")
            copyOf(A, daysId, [[id: "d1", trandate: "2026-11-10"], [id: "x", trandate: "2026-11-10"]], "id", "trandate")
            Map dayParams = RunExtractSupport.buildExtractParams(days, "FILE_1", "conn-days-${token}", [location: runLocation("days-${token}"), runToken: "d"],
                    (Timestamp) window.windowStartDate, (Timestamp) window.windowEndDate, "slice.json", null, dropX, null, window.timeZone)
            return KeptCopySupport.sliceIfServed(ec, KeptCopySupport.side(days, dayParams,
                    [tenantId: A, windowStart: window.windowStartDate, windowEnd: window.windowEndDate]))
        }
        Map writes = serve("Y", "y")
        assertTrue(writes.served as boolean, writes.reason as String)
        File yExtract = writes.extractFile as File
        assertEquals(["d1"], json(yExtract).records*.id, "the filters apply")
        assertEquals(["x"], json(new File(yExtract.parentFile, ExcludedRecordsSidecar.fileNameFor(yExtract.name))).records*.id)
        Map writesNone = serve("N", "n")
        assertTrue(writesNone.served as boolean, writesNone.reason as String)
        File nExtract = writesNone.extractFile as File
        assertEquals(["d1"], json(nExtract).records*.id, "the filters still apply")
        assertFalse(new File(nExtract.parentFile, ExcludedRecordsSidecar.fileNameFor(nExtract.name)).exists(), "no side file, as live")
        assertTrue(RunConclusionSupport.indexSide([extractFile: nExtract, keyFields: ["id"]], ["x"] as Set<String>,
                [] as Set<String>).unknownWhenAbsent as boolean, "the filtered side reads UNKNOWN, as on a live run")
        assertEquals(KeptCopySupport.SIDE_FILE_UNDECLARED, serve(null, "undeclared").reason)

        // a real declaration, from Shopify's own data
        String shopify = dataset("conn-sidefile-shopify")
        copy(A, shopify, [order("web", "2026-11-10T09:00:00Z", [sourceName: "web"]), order("pos", "2026-11-10T10:00:00Z", [sourceName: "pos"])])
        Map shopifyConnector = SourceSystemConnectorSupport.resolve(ec, "SHOPIFY")
        assertEquals("Y", shopifyConnector.excludedRecordsSidecar)
        Map shopifyServed = KeptCopySupport.sliceIfServed(ec, KeptCopySupport.side(shopifyConnector,
                [shopifyAuthConfigId: "conn-sidefile-shopify", sourceFilters: [[sequenceNum: 1, fieldExpression: "sourceName", operator: "EXCLUDE_IN", filterValues: "POS"]],
                 outputLocation: runLocation("sidefile-shopify"), fileName: "slice.json"], [tenantId: A, windowStart: START, windowEnd: END]))
        assertTrue(shopifyServed.served as boolean, shopifyServed.reason as String)
        File shopifySide = new File((shopifyServed.extractFile as File).parentFile, ExcludedRecordsSidecar.fileNameFor("slice.json"))
        assertEquals(["pos"], json(shopifySide).records*.id)
    }

    /**
     * Ruling P3b1-9, on a made-up system: a connector that names a projection service has its side served projected by it.
     * The service is asked first (no records), then trims the slice's records after the filters and writes its side file
     * beside the slice, whose location lands on the served result.
     */
    @Test
    void aConnectorsProjectionServiceTrimsAServedSide() {
        loadProjectionConnector()
        Map connector = SourceSystemConnectorSupport.resolve(ec, "KC_PROJ")
        String id = datasetOf("KC_PROJ", "conn-proj", "id", "createdAt")
        copyOf(A, id, [[id: "p1", createdAt: "2026-11-10T09:00:00Z", a: 1, b: 2, always: "k"],
                       [id: "p2", createdAt: "2026-11-10T10:00:00Z", a: 3, b: 4, always: "k", drop: "y"]], "id", "createdAt")
        MadeUpKeptRecords.CALLS.clear()
        Map served = KeptCopySupport.sliceIfServed(ec, projSide(connector, ["id", "a"], "proj", [[sequenceNum: 1, fieldExpression: "drop",
                operator: "EXCLUDE_IN", filterValues: "y"]]))
        assertTrue(served.served as boolean, served.reason as String)
        assertEquals([[id: "p1", a: 1, always: "k"]], json(served.extractFile as File).records, "trimmed by the system's own rule, after the filters")
        assertEquals(2, MadeUpKeptRecords.CALLS.size(), "asked before the copy is opened, then given the records")
        assertNull(MadeUpKeptRecords.CALLS[0].recordsFile)
        assertEquals(["id", "a"], MadeUpKeptRecords.CALLS[0].side.keepFields)
        assertEquals(["drop"], MadeUpKeptRecords.CALLS[0].side.filterFields)
        assertEquals([:], MadeUpKeptRecords.CALLS[0].copy.sideFiles == null ? [:] : MadeUpKeptRecords.CALLS[0].copy.sideFiles)
        File madeUp = locationFile(served.madeUpFileLocation as String)
        assertEquals([records: 1], json(madeUp), "its side file beside the slice")
        assertEquals(served.madeUpFileLocation, KeptCopySupport.extractResult(served).madeUpFileLocation, "and on the automation path's result")

        // the same connector with no projection service: a side that keeps fields reads live, as before
        assertEquals(KeptCopySupport.KEEPS_FIELDS, KeptCopySupport.side(connector + [servingProjectionServiceName: null],
                projParams(connector, ["id", "a"], "proj-none", null), [tenantId: A, windowStart: START, windowEnd: END]).liveOnly)
        // one that names a service of another shape, or one not installed, never serves
        assertEquals("the connector names an invalid projection service", KeptCopySupport.side(connector + [servingProjectionServiceName:
                "dataset.DatasetServices.store#Dataset"], projParams(connector, ["id"], "proj-bad", null), [tenantId: A, windowStart: START, windowEnd: END]).liveOnly)
        assertEquals(KeptCopySupport.PROJECTION_NOT_INSTALLED, KeptCopySupport.sliceIfServed(ec, projSide(connector + [servingProjectionServiceName:
                "test.KeptCopyTestServices.project#NoSuchRecords"], ["id"], "proj-missing")).reason)
    }

    /**
     * Ruling 3b1-7 / P3b1-9, on a made-up system: a side the copy cannot serve (its service says the copy lacks a field)
     * reads live, nothing is left behind, and the dataset is queued for the read that would keep the field.
     */
    @Test
    void aSideTheProjectionServiceRefusesReadsLiveAndQueuesTheRebuild() {
        loadProjectionConnector()
        Map connector = SourceSystemConnectorSupport.resolve(ec, "KC_PROJ")
        String id = datasetOf("KC_PROJ", "conn-proj-lack", "id", "createdAt")
        copyOf(A, id, [[id: "p1", createdAt: "2026-11-10T09:00:00Z", a: 1]], "id", "createdAt", new Timestamp(READ_THRU.time - 60 * DAY),
                [keptFields: ["id", "createdAt", "a"]])
        def row = ec.entity.find("darpan.dataset.Dataset").condition("datasetId", id).disableAuthz().one()
        row.nextReadDate = ts("2026-12-25T00:00:00Z")
        row.update()
        Map refused = KeptCopySupport.sliceIfServed(ec, projSide(connector, ["id", "b"], "proj-lack"))
        assertFalse(refused.served as boolean)
        assertEquals("the made-up copy lacks b", refused.reason)
        assertFalse(new File(locationFile(runLocation("proj-lack")), "slice.json").exists(), "nothing is left behind")
        // no source reads this copy, so its next read keeps whole records: a different list, so it is due now
        def after = ec.entity.find("darpan.dataset.Dataset").condition("datasetId", id).disableAuthz().one()
        assertTrue((after.nextReadDate as Timestamp).time <= System.currentTimeMillis(), "queued for the read that keeps the field")
    }

    /**
     * M1/M2: a projection service never replaces what the slice answers itself, and an abandoned slice removes only files in
     * its own folder, whatever the service lists.
     */
    @Test
    void aProjectionServiceNeverReplacesTheSlicesAnswerNorDeletesOutsideItsFolder() {
        loadProjectionConnector()
        Map connector = SourceSystemConnectorSupport.resolve(ec, "KC_PROJ")
        String id = datasetOf("KC_PROJ", "conn-proj-hostile", "id", "createdAt")
        copyOf(A, id, [[id: "p1", createdAt: "2026-11-10T09:00:00Z", hostileResult: 1, hostileCleanup: 2]], "id", "createdAt")
        Map served = KeptCopySupport.sliceIfServed(ec, projSide(connector, ["id", "hostileResult"], "proj-hostile"))
        assertTrue(served.served as boolean, served.reason as String)
        assertTrue((served.fileLocation as String).endsWith("slice.json"), "the slice's own location: ${served.fileLocation}")
        assertNotNull(served.madeUpFileLocation, "a key of its own is still added")

        MadeUpKeptRecords.outsideFile = File.createTempFile("outside-a-slice-", ".json")
        try {
            Map params = projParams(connector, ["id", "hostileCleanup"], "proj-hostile-2", null)
            params.projConfigId = "conn-proj-hostile"
            Map abandoned = KeptCopySupport.sliceIfServed(ec, KeptCopySupport.side(connector, params, [tenantId: A, windowStart: START, windowEnd: END]))
            assertFalse(abandoned.served as boolean)
            assertTrue(MadeUpKeptRecords.outsideFile.exists(), "a listed path outside the slice's folder is never deleted")
            assertFalse(new File(locationFile(runLocation("proj-hostile-2")), "slice.madeup.json").exists(), "its own side file is removed")
        } finally {
            MadeUpKeptRecords.outsideFile.delete()
        }
    }

    /** A test-only connector row of a made-up system that names the test-only extract and projection service. */
    private void loadProjectionConnector() {
        ec.entity.makeDataLoader().location("classpath://dataset/MadeUpProjectionConnector.xml").dummyFks(true).load()
    }

    private Map projParams(Map connector, List<String> keep, String token, List<Map> filters) {
        return RunExtractSupport.buildExtractParams(connector, "FILE_1", "conn-${token}".toString(), [location: runLocation(token), runToken: token],
                START, END, "slice.json", keep, filters, null, "UTC") + [outputLocation: runLocation(token), fileName: "slice.json"]
    }

    private Map projSide(Map connector, List<String> keep, String token, List<Map> filters = null) {
        Map params = projParams(connector, keep, token, filters)
        params.projConfigId = "conn-${token == 'proj-missing' ? 'proj' : token}".toString()
        return KeptCopySupport.side(connector, params, [tenantId: A, windowStart: START, windowEnd: END])
    }

    private static final String LIVE_TEXT = '{"metadata":{"live":true},"records":[{"id":"live-1"}]}'

    /** A test-only connector row shaped as NETSUITE_SUITEQL (whole days, a DATE field), on an extractor that keeps copies. */
    private void loadDaysConnector() {
        ec.entity.makeDataLoader().xmlText("""<entity-facade-xml type="seed">
            <darpan.reconciliation.SourceSystemConnector systemEnumId="KC_DAYS" extractServiceName="${RETURNS_EXTRACT}"
                dateFromParameterName="windowStart" dateToParameterName="windowEnd" expectedSourceConfigType="KC_DAYS_TYPE"
                configParameterName="omsRestSourceConfigId" preserveWindowInstants="N" windowDateOnly="Y" windowFieldName="trandate"
                enabled="Y"/>
        </entity-facade-xml>""").dummyFks(true).load()
    }

    /** An OMS return as the GraphQL extract writes it: entryDate in epoch milliseconds (OmsGqlReturnAssembler). */
    private static Map omsReturn(String id, String entryDate) {
        return [returnId: id, statusId: "RETURN_REQUESTED", entryDate: OffsetDateTime.parse(entryDate).toInstant().toEpochMilli()]
    }

    /** A rolling dataset of the active tenant (stampField = windowField). */
    private String datasetOf(String systemEnumId, String conn, String idField, String windowField) {
        Map res = underFacadeGrant("dataset.DatasetServices.store#Dataset", [systemEnumId: systemEnumId, sourceConfigId: conn,
                options: [:], idField: idField, windowField: windowField, readRuleEnumId: "DRR_ROLLING"])
        assertEquals([], res.errors)
        return res.out.datasetId as String
    }

    /** A full read into a rolling copy, covering 60 days back from READ_THRU. */
    private void copyOf(String tenant, String datasetId, List<Map> records, String idField, String windowField,
                        Timestamp coveredFrom = new Timestamp(READ_THRU.time - 60 * DAY), Map copyInfo = null) {
        KeptCopyStore.replace(DatasetRefreshSupport.datasetDir(ec, tenant, datasetId), records.iterator(), idField, windowField, windowField,
                coveredFrom, READ_THRU, READ_AT, 2, null, false, copyInfo)
    }

    /** The interactive dispatcher's own parameters for an OMS returns side over START..END, as the side is built from them. */
    private Map returnsSide(Map connector, String conn, String token) {
        Map params = RunExtractSupport.buildExtractParams(connector, "FILE_1", conn, [location: runLocation(token), runToken: token],
                START, END, "slice.json", null, null, null, "UTC")
        return KeptCopySupport.side(connector, params, [tenantId: A, windowStart: START, windowEnd: END])
    }

    /** A KC_DAYS side over the given window, built from the interactive dispatcher's own parameters. */
    private Map daySide(String conn, Map window, String token) {
        Map connector = SourceSystemConnectorSupport.resolve(ec, "KC_DAYS")
        Map params = RunExtractSupport.buildExtractParams(connector, "FILE_1", conn, [location: runLocation(token), runToken: token],
                (Timestamp) window.windowStartDate, (Timestamp) window.windowEndDate, "slice.json", null, null, null, window.timeZone)
        params.outputLocation = runLocation(token)
        params.fileName = "slice.json"
        return KeptCopySupport.sliceIfServed(ec, KeptCopySupport.side(connector, params,
                [tenantId: A, windowStart: window.windowStartDate, windowEnd: window.windowEndDate]))
    }

    private static Map automationSource(String systemEnumId, String serviceName, Map parameters) {
        return [automationId: "KC_DAYS_AUTO", fileSide: "FILE_1", systemEnumId: systemEnumId, companyUserGroupId: A,
                sourceTypeEnumId: AutomationExecutionSupport.AUTOMATION_SOURCE_API,
                safeMetadataJson: JsonOutput.toJson([extractServiceName: serviceName, parameters: parameters])]
    }

    /** One interactive side of any source through RunExtractSupport.extractApiSource, over an anchored window (with its zone). */
    private Map interactiveSide(Map source, Map apiWindow, List<Map> calls) {
        String token = "i${++seq}"
        Map artifact = [location: runLocation(token), runToken: token]
        Closure stub = { String service, Map params ->
            String location = DataManagerSupport.childLocation(params.outputLocation as String, params.fileName as String)
            DataManagerSupport.writeText(ec, location, LIVE_TEXT)
            calls << [service: service, params: new LinkedHashMap(params), location: location]
            return [fileLocation: location, fileName: params.fileName, recordCount: 1]
        }
        ec.message.clearAll()
        Map result = RunExtractSupport.extractApiSource(ec, source, "FILE_1", "File 1", artifact, apiWindow, null, stub)
        List<String> errors = ec.message.errors.collect { it.toString() }
        ec.message.clearAll()
        return [result: result, artifact: artifact, source: source, errors: errors]
    }

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
