package darpan.pulse

import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext

import java.sql.Timestamp
import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-328 plan 3, Task 3. The reader through the check's own dispatch (callConfiguredSourceExtractor, the seeded
 * made-up PULSE_FILTERED and PULSE_LEDGER connectors), its live extractor stubbed: the window and filters sent, the records read,
 * the target asked by key, failures answered, folders removed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncPulseReaderSmokeTests {

    private static final String A = "KREWE"
    private static final String B = "PULSE_OTHER"
    private static final String AUTO = "PULSE_AUTO"
    /** Made-up systems (test-only extracts, AutomationKeepTestExtractionServices): FILTERED still takes its filter parameter and
     *  so filters at the read; LEDGER takes no Keep and is read by key. */
    private static final String FILTERED = "PULSE_FILTERED"
    private static final String LEDGER = "PULSE_LEDGER"
    private static final String GQL = "reconciliation.AutomationKeepTestExtractionServices.extract#ReadFilteredRecords"
    private static final String NS = "reconciliation.AutomationKeepTestExtractionServices.extract#LedgerRecords"
    /** DAR-BE-318: a made-up system whose extract no longer takes its filter parameter, so its sides keep in Spark. */
    private static final String ACME = "ACME_PULSE"
    private static final String ACME_EXTRACT = "reconciliation.AutomationKeepTestExtractionServices.extract#SparkKeptRecords"
    private static final String KEEP_AUTO = "PULSE_KEEP_AUTO"
    private static final Instant FROM = Instant.parse("2026-11-21T07:00:00Z")
    private static final Instant THRU = Instant.parse("2026-11-21T10:00:00Z")

    private ExecutionContext ec
    private File pulseRoot
    private List<Map> calls = []
    private Map automation
    private Map pair = [syncPairId: "SP1", companyUserGroupId: A, automationId: AUTO, upstreamSide: "FILE_1"]

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "sync-pulse-reader")
        File root = DataManagerSupport.resolveDirectoryFile(ec, DataManagerSupport.resolveDataManagerLocation(ec), true)
        assertTrue(root.path.contains("test-data-manager"), "tests never write the dev datamanager: ${root}")
        pulseRoot = new File(root, SyncPulseReader.PULSE_FOLDER)
        pulseRoot.deleteDir()
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
        ec.entity.makeDataLoader().location("component://darpan/data/SourceSystemConnectorSeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: B, description: "Other",
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]).createOrUpdate()
        ec.entity.makeDataLoader().dummyFks(true).xmlText("""<entity-facade-xml type="seed">
            <darpan.rule.RuleSet ruleSetId="PULSE_RS" ruleSetName="Pulse" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareScope compareScopeId="PULSE_SCOPE" ruleSetId="PULSE_RS" objectType="ORDER" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareSource compareScopeId="PULSE_SCOPE" fileSide="FILE_1" systemEnumId="${FILTERED}" fileTypeEnumId="DftJson"
                recordRootExpression="records" primaryIdExpression="orderId" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareSource compareScopeId="PULSE_SCOPE" fileSide="FILE_2" systemEnumId="${LEDGER}" fileTypeEnumId="DftJson"
                recordRootExpression="records" primaryIdExpression="tranId" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareSourceKeyField compareScopeId="PULSE_SCOPE" fileSide="FILE_1" sequenceNum="1"
                fieldExpression="orderName" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareSourceKeyField compareScopeId="PULSE_SCOPE" fileSide="FILE_2" sequenceNum="1"
                fieldExpression="otherRefNum" companyUserGroupId="${A}"/>
            <darpan.reconciliation.ReconciliationAutomation automationId="${AUTO}" automationName="Pulse check" companyUserGroupId="${A}"
                inputModeEnumId="${AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE}" savedRunId="PULSE_RS" compareScopeId="PULSE_SCOPE"
                relativeWindowTypeEnumId="${AutomationExecutionSupport.WINDOW_PREVIOUS_DAY}" windowTimeZone="UTC"/>
            <darpan.reconciliation.ReconciliationAutomationSource automationId="${AUTO}" fileSide="FILE_1" companyUserGroupId="${A}"
                sourceTypeEnumId="${AutomationExecutionSupport.AUTOMATION_SOURCE_API}" systemEnumId="${FILTERED}"
                safeMetadataJson='{"extractServiceName":"${GQL}","parameters":{"pulseSourceConfigId":"pulse_up"}}'/>
            <darpan.reconciliation.ReconciliationAutomationSource automationId="${AUTO}" fileSide="FILE_2" companyUserGroupId="${A}"
                sourceTypeEnumId="${AutomationExecutionSupport.AUTOMATION_SOURCE_API}" systemEnumId="${LEDGER}"
                safeMetadataJson='{"extractServiceName":"${NS}","parameters":{"pulseQueryId":"PULSE_Q"}}'/>
            <darpan.reconciliation.ReconciliationAutomationSourceFilter automationId="${AUTO}" fileSide="FILE_1" sequenceNum="1"
                fieldExpression="\$.records[*].productStoreId" operator="EXCLUDE_IN" filterValues="TEST_STORE" companyUserGroupId="${A}"/>
            <darpan.reconciliation.SourceSystemConnector systemEnumId="${FILTERED}" extractServiceName="${GQL}"
                dateFromParameterName="windowStart" dateToParameterName="windowEnd" filterParameterName="sourceFilters" enabled="Y"/>
            <darpan.reconciliation.SourceSystemConnector systemEnumId="${LEDGER}" extractServiceName="${NS}"
                dateFromParameterName="windowStart" dateToParameterName="windowEnd" enabled="Y"/>
            <darpan.reconciliation.SourceSystemConnector systemEnumId="${ACME}" extractServiceName="${ACME_EXTRACT}"
                dateFromParameterName="windowStart" dateToParameterName="windowEnd" filterParameterName="sourceFilters"
                excludedRecordsSidecar="Y" enabled="Y" keepLinesJson="[]"/>
            <darpan.rule.RuleSet ruleSetId="PULSE_KEEP_RS" ruleSetName="Pulse keep" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareScope compareScopeId="PULSE_KEEP_SCOPE" ruleSetId="PULSE_KEEP_RS" objectType="ORDER" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareSource compareScopeId="PULSE_KEEP_SCOPE" fileSide="FILE_1" systemEnumId="${ACME}" fileTypeEnumId="DftJson"
                recordRootExpression="records" primaryIdExpression="orderId" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareSource compareScopeId="PULSE_KEEP_SCOPE" fileSide="FILE_2" systemEnumId="${ACME}" fileTypeEnumId="DftJson"
                recordRootExpression="records" primaryIdExpression="tranId" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareSourceKeyField compareScopeId="PULSE_KEEP_SCOPE" fileSide="FILE_1" sequenceNum="1"
                fieldExpression="orderName" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareSourceKeyField compareScopeId="PULSE_KEEP_SCOPE" fileSide="FILE_2" sequenceNum="1"
                fieldExpression="otherRefNum" companyUserGroupId="${A}"/>
            <darpan.reconciliation.ReconciliationAutomation automationId="${KEEP_AUTO}" automationName="Pulse keep check" companyUserGroupId="${A}"
                inputModeEnumId="${AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE}" savedRunId="PULSE_KEEP_RS" compareScopeId="PULSE_KEEP_SCOPE"
                relativeWindowTypeEnumId="${AutomationExecutionSupport.WINDOW_PREVIOUS_DAY}" windowTimeZone="UTC"/>
            <darpan.reconciliation.ReconciliationAutomationSource automationId="${KEEP_AUTO}" fileSide="FILE_1" companyUserGroupId="${A}"
                sourceTypeEnumId="${AutomationExecutionSupport.AUTOMATION_SOURCE_API}" systemEnumId="${ACME}"
                safeMetadataJson='{"extractServiceName":"${ACME_EXTRACT}"}'/>
            <darpan.reconciliation.ReconciliationAutomationSource automationId="${KEEP_AUTO}" fileSide="FILE_2" companyUserGroupId="${A}"
                sourceTypeEnumId="${AutomationExecutionSupport.AUTOMATION_SOURCE_API}" systemEnumId="${ACME}"
                safeMetadataJson='{"extractServiceName":"${ACME_EXTRACT}"}'/>
            <darpan.reconciliation.ReconciliationAutomationSourceFilter automationId="${KEEP_AUTO}" fileSide="FILE_1" sequenceNum="1"
                fieldExpression="\$.records[*].status" operator="EXCLUDE_IN" filterValues="VOID" companyUserGroupId="${A}"/>
            <darpan.reconciliation.ReconciliationAutomationSourceFilter automationId="${KEEP_AUTO}" fileSide="FILE_2" sequenceNum="1"
                fieldExpression="status" operator="EXCLUDE_IN" filterValues="CLOSED" companyUserGroupId="${A}"/>
        </entity-facade-xml>""").load()
        automation = ec.entity.find("darpan.reconciliation.ReconciliationAutomation").condition("automationId", AUTO).one().getMap()
    }

    @AfterAll
    void cleanup() {
        SyncPulseReader.resetHooks()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void stubTheExtracts() {
        calls.clear()
        ec.message.clearAll()
        SyncPulseReader.setLiveExtractor(stub())
    }

    @AfterEach
    void resetTheStub() {
        SyncPulseReader.resetHooks()
    }

    @Test
    void upstreamWindowIsTheGivenInstants() {
        Map out = SyncPulseReader.readUpstream(ec, pair, automation, FROM, THRU)

        assertEquals(true, out.ok, out.toString())
        assertEquals(1, calls.size())
        Map sent = calls[0].params as Map
        assertEquals(GQL, calls[0].service)
        assertEquals(Timestamp.from(FROM), sent.windowStart, "the connector's own window parameters carry the instants exactly")
        assertEquals(Timestamp.from(THRU), sent.windowEnd)
        assertEquals(Timestamp.from(FROM), sent.windowStartDate)
        assertEquals(Timestamp.from(THRU), sent.windowEndDate)
        assertEquals(A, sent.companyUserGroupId)
        assertEquals("pulse_up", sent.pulseSourceConfigId)
        assertEquals("FILE_1", sent.fileSide)
        List filters = sent.sourceFilters as List
        assertEquals(1, filters.size(), "the check's own filters, sent as the check sends them")
        assertEquals("productStoreId", filters[0].fieldExpression)
        assertEquals("TEST_STORE", filters[0].filterValues)
        assertNull(sent.lookupKeys, "the upstream side is read by window, never by key")
        assertTrue((sent.outputLocation as String).contains("/sync-pulse/${A}/SP1/"), sent.outputLocation as String)
    }

    @Test
    void recordsCarryKeyCreatedAndOtherKey() {
        Map out = SyncPulseReader.readUpstream(ec, pair, automation, FROM, THRU)

        assertEquals(true, out.ok, out.toString())
        assertEquals([
                [key: "#GOR1", orderName: "#GOR1", createdAt: Instant.parse("2026-11-21T07:30:00Z"), otherKey: "#GOR1"],
                [key: "#GOR2", orderName: "#GOR2", createdAt: Instant.parse("2026-11-21T09:59:59Z"), otherKey: "#GOR2"],
        ], out.records, "keyed by the check's FILE_1 key field (orderName), dated by orderDate")
        assertEquals(["1 upstream record(s) skipped: no orderDate that reads as a date and time."], out.warnings)
        assertEquals([], out.errors)
    }

    @Test
    void targetLookupBatchesByOneThousand() {
        List<String> keys = (1..2500).collect { "#GOR${it}".toString() } + ["bad key"]
        Map out = SyncPulseReader.presentInTarget(ec, pair, automation, keys)

        assertEquals(true, out.ok, out.toString())
        assertEquals(3, calls.size(), "2,500 keys -> three reads by key")
        assertEquals([NS] * 3, calls*.service)
        assertEquals([1000, 1000, 500], calls.collect { ((List) it.params.lookupKeys).size() })
        calls.each { Map c ->
            assertEquals("otherRefNum", c.params.lookupKeyField, "read by the check's own FILE_2 key field")
            assertNull(c.params.lookupWithinWindow, "a pulse asks by key alone")
            assertEquals("PULSE_Q", c.params.pulseQueryId)
            assertEquals(A, c.params.companyUserGroupId)
        }
        assertEquals(1250, out.present.size(), "the target holds the even keys")
        assertTrue(out.present.contains("#GOR2500"))
        assertFalse(out.present.contains("#GOR1"))
        assertEquals(1, out.warnings.size(), out.warnings.toString())
        assertFalse(calls.any { ((List) it.params.lookupKeys).contains("bad key") }, "an invalid key is never sent")
    }

    @Test
    void invalidKeysAreSkippedNotSent() {
        Map out = SyncPulseReader.presentInTarget(ec, pair, automation, ["x'); DROP", "#GOR2", "#GOR3"])

        assertEquals(true, out.ok, out.toString())
        assertEquals([["#GOR2", "#GOR3"]], calls.collect { it.params.lookupKeys })
        assertEquals(["#GOR2"] as Set, out.present)
        assertTrue((out.warnings[0] as String).startsWith("1 key(s) not asked for"), out.warnings.toString())
        assertEquals(["x'); DROP"] as Set, out.unasked, "the skipped key comes back as unasked, so it is never read as absent")

        SyncPulseReader.setLiveExtractor({ String service, Map params -> throw new RuntimeException("ledger timed out") })
        Map failed = SyncPulseReader.presentInTarget(ec, pair, automation, ["x'); DROP", "#GOR2"])
        assertEquals(false, failed.ok)
        assertEquals(["x'); DROP"] as Set, failed.unasked, "a failed read still says which keys it never asked")
    }

    /**
     * A pulse runs on a bare thread (RunAdmission), which logs in anonymously and authorizes the artifact before the
     * reader runs (as SyncPulseSupport does); the reader signs in and elevates nothing itself, so this frame is the test's.
     */
    @Test
    void readsFromAThreadWithNoUser() {
        List<Map> seen = Collections.synchronizedList([])
        Closure inner = stub()
        SyncPulseReader.setLiveExtractor({ String service, Map params ->
            def threadEc = ec.factory.getExecutionContext()
            seen << [anonymous: threadEc.user.loggedInAnonymous, username: threadEc.user.username]
            inner.call(service, params)
        })
        Map out = null, after = null
        Throwable failure = null
        Thread t = new Thread({ ->
            def threadEc = ec.factory.getExecutionContext()
            try {
                assertNull(threadEc.user.username)
                assertFalse(threadEc.user.loggedInAnonymous as boolean)
                assertTrue(threadEc.user.loginAnonymousIfNoUser())
                threadEc.artifactExecution.push("test.pulse", ArtifactExecutionInfo.AT_OTHER, ArtifactExecutionInfo.AUTHZA_ALL, false)
                threadEc.artifactExecution.setAnonymousAuthorizedAll()
                out = SyncPulseReader.readUpstream(threadEc, pair, automation, FROM, THRU)
                threadEc.user.logoutAnonymousOnly()
                after = [anonymous: threadEc.user.loggedInAnonymous, username: threadEc.user.username]
            } catch (Throwable e) {
                failure = e
            } finally {
                threadEc.destroy()
            }
        } as Runnable, "pulse-no-user")
        t.start()
        t.join(60000)

        assertNull(failure, failure?.toString())
        assertEquals(true, out?.ok, out?.toString())
        assertEquals(2, (out.records as List).size())
        assertEquals([[anonymous: true, username: null]], seen, "the extract ran under the caller's anonymous login")
        assertEquals([anonymous: false, username: null], after, "and the frame logged out again")
        assertNoPulseFiles()
    }

    @Test
    void extractorErrorIsReturnedNotThrown() {
        SyncPulseReader.setLiveExtractor({ String service, Map params ->
            calls << [service: service, params: params]
            [errors: ["upstream answered 503"]]
        })
        Map up = SyncPulseReader.readUpstream(ec, pair, automation, FROM, THRU)
        assertEquals(false, up.ok)
        assertEquals([], up.records)
        assertTrue(up.errors.any { (it as String).contains("upstream answered 503") }, up.errors.toString())

        SyncPulseReader.setLiveExtractor({ String service, Map params -> throw new RuntimeException("ledger timed out") })
        Map target = SyncPulseReader.presentInTarget(ec, pair, automation, ["#GOR2"])
        assertEquals(false, target.ok)
        assertEquals([] as Set, target.present)
        assertTrue(target.errors.any { (it as String).contains("ledger timed out") }, target.errors.toString())
        assertFalse(ec.message.hasError(), "nothing is left on the message facade for the caller's next call")

        Map otherTenant = SyncPulseReader.readUpstream(ec, pair + [companyUserGroupId: B], automation, FROM, THRU)
        assertEquals(false, otherTenant.ok)
        assertEquals(["The upstream read failed: The sync pair and its check belong to different tenants."], otherTenant.errors)

        Map backwards = SyncPulseReader.readUpstream(ec, pair, automation, THRU, FROM)
        assertEquals(false, backwards.ok)
        assertNoPulseFiles()
    }

    @Test
    void outputFolderRemoved() {
        Map up = SyncPulseReader.readUpstream(ec, pair, automation, FROM, THRU)
        Map target = SyncPulseReader.presentInTarget(ec, pair, automation, ["#GOR1", "#GOR2"])

        assertEquals(true, up.ok, up.toString())
        assertEquals(true, target.ok, target.toString())
        assertEquals(2, calls.size())
        calls.each { Map c ->
            File folder = DataManagerSupport.resolveDirectoryFile(ec, c.params.outputLocation as String, false)
            assertFalse(folder.exists(), "the pulse folder is removed: ${folder}")
        }
        assertNoPulseFiles()
    }

    // ------------------------------------------------------------------- DAR-BE-318: a side that keeps in Spark

    /**
     * Ruling 3b2b-11 with the shared gate (Ruling 3b2b-23). ACME's extract no longer takes its filter parameter, so the
     * check's run reads it unfiltered and keeps in Spark; the pulse reads it the same way and runs the same Keep over its own
     * extract. A record Keep sets aside is neither an upstream record nor present in the target, as when the read dropped it.
     */
    @Test
    void aSideThatKeepsInSparkSkipsWhatItsKeepSetsAside() {
        Map keepPair = [syncPairId: "SP2", companyUserGroupId: A, automationId: KEEP_AUTO, upstreamSide: "FILE_1"]
        Map keepAutomation = ec.entity.find("darpan.reconciliation.ReconciliationAutomation").condition("automationId", KEEP_AUTO).one().getMap()
        SyncPulseReader.setLiveExtractor(acmeStub())

        Map up = SyncPulseReader.readUpstream(ec, keepPair, keepAutomation, FROM, THRU)

        assertEquals(true, up.ok, up.toString())
        assertEquals([
                [key: "#P1", orderName: "#P1", createdAt: Instant.parse("2026-11-21T07:30:00Z"), otherKey: "#P1"],
                [key: "#P3", orderName: "#P3", createdAt: Instant.parse("2026-11-21T07:50:00Z"), otherKey: "#P3"],
        ], up.records, "VOID and ' void ' were set aside, as the check's filter drops them; 'open' was kept")
        assertEquals([], up.warnings)
        Map sent = calls[0].params as Map
        assertEquals(ACME_EXTRACT, calls[0].service)
        assertNull(sent.sourceFilters, "a side that keeps in Spark is read unfiltered, as the check's run reads it")
        assertEquals(["status"], sent.keepRuleFields, "and keeps the field its Keep reads")

        calls.clear()
        List<String> keys = (1..1001).collect { "#K${it}".toString() }
        Map target = SyncPulseReader.presentInTarget(ec, keepPair, keepAutomation, keys)

        assertEquals(true, target.ok, target.toString())
        assertEquals([1000, 1], calls.collect { ((List) it.params.lookupKeys).size() }, "two reads by key")
        calls.each { Map c -> assertNull(c.params.sourceFilters) }
        assertEquals(1001 - 143, target.present.size(), "the target holds every key; the 143 CLOSED ones were set aside")
        assertFalse(target.present.contains("#K7"))
        assertFalse(target.present.contains("#K1001"), "the second read's Keep set aside its own CLOSED record")
        assertTrue(target.present.contains("#K1000"))
        assertNoPulseFiles()
    }

    /** A side whose extract still takes its filter parameter is read as before: filtered at the read, no Keep run. */
    @Test
    void aSideStillFilteringAtTheReadKeepsTodaysRead() {
        Map out = SyncPulseReader.readUpstream(ec, pair, automation, FROM, THRU)

        assertEquals(true, out.ok, out.toString())
        Map sent = calls[0].params as Map
        assertEquals(1, (sent.sourceFilters as List).size(), "the check's filters, sent to the read")
        assertNull(sent.keepRuleFields, "no Keep field is asked for")
        assertEquals(["#GOR1", "#GOR2"], out.records*.key, "every dated record the read wrote is read")
    }

    /**
     * DAR-BE-338: the shared gate's records guard (review M5/M6) takes the AUTOMATION's own record root, as every automation
     * path does. ACME's extract no longer filters at the read, so an automation whose own root is another array cannot be
     * filtered anywhere and its pulse read fails by name (Ruling 3b2b-25), whatever root its run has moved to since.
     */
    @Test
    void anAutomationWhoseOwnRecordRootIsAnotherArrayFailsItsPulseReadByName() {
        def source = ec.entity.find("darpan.reconciliation.ReconciliationAutomationSource").condition("automationId", KEEP_AUTO)
                .condition("fileSide", "FILE_1").forUpdate(true).one()
        source.set("recordRootExpression", "\$.orders[*]")
        source.update()
        try {
            Map keepPair = [syncPairId: "SP3", companyUserGroupId: A, automationId: KEEP_AUTO, upstreamSide: "FILE_1"]
            Map keepAutomation = ec.entity.find("darpan.reconciliation.ReconciliationAutomation").condition("automationId", KEEP_AUTO).one().getMap()
            SyncPulseReader.setLiveExtractor(acmeStub())

            Map up = SyncPulseReader.readUpstream(ec, keepPair, keepAutomation, FROM, THRU)

            assertEquals(false, up.ok, up.toString())
            assertTrue((up.errors as List).any { it.toString().contains("its records are not top-level") }, up.toString())
        } finally {
            source.set("recordRootExpression", null)
            source.update()
        }
    }

    // ------------------------------------------------------------------------------------------------ the stub

    /**
     * ACME's unfiltered extracts: the upstream read writes four orders (two VOID, however written), a read by key one record
     * per key asked, CLOSED for every key that is a multiple of 7. No side file: the extract drops nothing itself.
     */
    private Closure acmeStub() {
        return { String service, Map params ->
            calls << [service: service, params: new LinkedHashMap(params)]
            List<Map> records
            if (params.lookupKeys) {
                records = ((List<String>) params.lookupKeys).collect { String k ->
                    [otherRefNum: k, tranId: "T-${k}".toString(), status: (k.replaceAll(/\D/, '') as long) % 7 == 0 ? "CLOSED" : "OPEN"]
                }
            } else {
                records = [[orderId: "P1", orderName: "#P1", orderDate: "2026-11-21T07:30:00Z", status: "OPEN"],
                           [orderId: "P2", orderName: "#P2", orderDate: "2026-11-21T07:40:00Z", status: "VOID"],
                           [orderId: "P3", orderName: "#P3", orderDate: "2026-11-21T07:50:00Z", status: "open"],
                           [orderId: "P4", orderName: "#P4", orderDate: "2026-11-21T08:00:00Z", status: " void "]]
            }
            File dir = DataManagerSupport.resolveDirectoryFile(ec, params.outputLocation as String, true)
            new File(dir, "acme.json").setText(JsonOutput.toJson([metadata: [:], records: records]), "UTF-8")
            return [fileLocation: DataManagerSupport.childLocation(params.outputLocation as String, "acme.json"), fileName: "acme.json",
                    recordCount : records.size()]
        }
    }

    /**
     * The live extracts: the upstream read writes three orders (one with no orderDate) into its outputLocation, with
     * an excluded-records side file; a read by key writes the orders for the even keys asked.
     */
    private Closure stub() {
        return { String service, Map params ->
            calls << [service: service, params: new LinkedHashMap(params)]
            List<Map> records
            String fileName
            if (params.lookupKeys) {
                records = ((List<String>) params.lookupKeys).findAll { (it.replaceAll(/\D/, '') as long) % 2 == 0 }
                        .collect { [otherRefNum: it, tranId: "SO-${it}"] }
                fileName = "ledger-orders-by-key.json"
            } else {
                records = [[orderId: "O1", orderName: "#GOR1", orderDate: "2026-11-21T07:30:00Z", productStoreId: "STORE"],
                           [orderId: "O2", orderName: "#GOR2", orderDate: "2026-11-21T09:59:59Z", productStoreId: "STORE"],
                           [orderId: "O3", orderName: "#GOR3", productStoreId: "STORE"]]
                fileName = "filtered-orders.json"
            }
            File dir = DataManagerSupport.resolveDirectoryFile(ec, params.outputLocation as String, true)
            new File(dir, fileName).setText(JsonOutput.toJson([metadata: [:], records: records]), "UTF-8")
            new File(dir, "${fileName}.excluded.json").setText("{}", "UTF-8")
            return [fileLocation: DataManagerSupport.childLocation(params.outputLocation as String, fileName), fileName: fileName,
                    recordCount : records.size()]
        }
    }

    private void assertNoPulseFiles() {
        List<File> files = []
        if (pulseRoot.exists()) pulseRoot.eachFileRecurse { File f -> if (f.isFile()) files << f }
        assertEquals([], files, "a pulse leaves no files behind")
    }
}
