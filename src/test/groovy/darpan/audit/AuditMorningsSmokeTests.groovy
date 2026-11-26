package darpan.audit

import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterAll
import org.moqui.entity.EntityCondition
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDateTime
import java.time.ZoneId

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-093 end to end: an R2 check ("every invoice carries the Etail id") from a tenant config pack (the fixture
 * data/test/AuditMorningsFixtureData.xml, DAR-BE-160), run by a real audit automation through the real executor, Spark
 * evaluate, conclusion step and ledger, on two mornings. Only the NetSuite read is stubbed, with a fixture per morning.
 *
 *   14 Oct  INV9000002 (no Etail id) and INV9000003 (blank Etail id) are found: open on their first morning
 *   15 Oct  INV9000002 now carries its Etail id: fixed. INV9000003 still has none: open
 *
 * R2's check reads ONE system, so this is also the scheduled path's single-sided (EVALUATE) run.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditMorningsSmokeTests {

    private static final String TENANT = "MORNINGS"
    private static final String AUTOMATION = "MORNINGS_AUTO_INVOICE_ETAIL"
    private static final String CHECK = "MORNINGS_CHK_INVOICE_ETAIL"
    private static final ZoneId LA = ZoneId.of("America/Los_Angeles")

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "mornings-month-close-mornings")
        upsert("moqui.security.UserGroup", [userGroupId: TENANT], [userGroupId: TENANT, description: "mornings"])
        ReconciliationSmokeTestSupport.loadSeedData(ec,
                "component://darpan/data/MappingSeedData.xml",
                "component://darpan/data/AutomationSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml",
                "component://darpan/data/SourceSystemConnectorSeedData.xml",
                // DAR-BE-320: the NetSuite endpoint's own fields (its Keep line), as prod has them, for a source naming its query
                "component://netsuite-darpan/data/NetSuiteConnectorSeedData.xml",
                "component://darpan/data/ConclusionCatalogSeedData.xml",
                "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/test/AuditMorningsFixtureData.xml")
        // These mornings prove each check's mechanics, so every statement is on here whatever the fixture says.
        ec.entity.find("darpan.audit.AuditStatement").condition("auditId", "MORNINGS_MONTH_CLOSE").disableAuthz().useCache(false)
                .list().each { it.set("isActive", "Y"); it.update() }
        // The loader rolls a file back on a referential failure and still reports a count: prove the rows.
        assertEquals("MORNINGS_INVOICE_ETAIL_RS", find("darpan.audit.AuditCheck", [checkId: CHECK])?.savedRunId)
        assertEquals(TENANT, find("darpan.rule.RuleSetConclusionRule",
                [compareScopeId: "MORNINGS_INVOICE_ETAIL_SCOPE", sequenceNum: 10L])?.companyUserGroupId)

        // An audit automation is created per environment (DAR-BE-093 prod-owed); this is its shape: one NetSuite
        // source, the R2 rule set, the tenant's zone.
        Timestamp now = ec.user.nowTimestamp
        upsert("darpan.reconciliation.ReconciliationAutomation", [automationId: AUTOMATION], [automationId: AUTOMATION,
                automationName: "Month close: invoices without an Etail id", companyUserGroupId: TENANT,
                inputModeEnumId: AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE,
                savedRunId: "MORNINGS_INVOICE_ETAIL_RS", savedRunType: "ruleset", ruleSetId: "MORNINGS_INVOICE_ETAIL_RS",
                compareScopeId: "MORNINGS_INVOICE_ETAIL_SCOPE", relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_PREVIOUS_DAY,
                relativeWindowCount: 1, windowTimeZone: "America/Los_Angeles", isActive: "Y", createdDate: now, lastUpdatedDate: now])
        upsert("darpan.reconciliation.ReconciliationAutomationSource", [automationId: AUTOMATION, fileSide: "FILE_1"], [
                automationId: AUTOMATION, fileSide: "FILE_1", companyUserGroupId: TENANT,
                sourceTypeEnumId: AutomationExecutionSupport.AUTOMATION_SOURCE_API, systemEnumId: "NETSUITE_SUITEQL",
                fileTypeEnumId: "DftJson", createdDate: now, lastUpdatedDate: now,
                // the query the source reads, as a saved automation names it: DAR-BE-320 asks its extract what it declares
                safeMetadataJson: '{"parameters":{"nsSuiteQlSourceQueryId":"NS_INVOICE_ETAIL"}}'])
        def check = ec.entity.find("darpan.audit.AuditCheck").condition("checkId", CHECK).disableAuthz().useCache(false).one()
        check.set("automationId", AUTOMATION)
        check.update()
    }

    @AfterAll
    void cleanup() {
        AutomationExecutionSupport.resetExecutionHooks()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @Test
    void anInvoiceIsFoundOneMorningAndFixedTheNext() {
        morning("2026-10-14", fixture("component://darpan/data/test/mornings-invoices-morning-1.json"))
        assertEquals([[kind: "window", from: at("2026-10-01"), changedSince: null, keys: null]], stubReads,
                "the first morning reads the month to date")

        Map<String, Map> first = findingsByRecord()
        assertEquals(["INV9000002", "INV9000003"] as Set, first.keySet(), "the two invoices with no Etail id")
        first.values().each { Map f ->
            // DAR-BE-206: what bit was a new check showing long-stuck records as pending on its first morning.
            assertEquals(["MORNINGS_R2", "AUDFS_OPEN", Date.valueOf("2026-10-14")], [f.statementId, f.stateEnumId, f.firstFoundDate],
                    "${f.recordKey}: open the morning it is first found")
        }
        assertEquals("Invoice INV9000002 has no Etail id", first.INV9000002.headline)
        // DAR-UI-080: the finding keeps both records of its pair, the HotWax order beside the NetSuite invoice.
        assertEquals([["NETSUITE", "invoice", "INV9000002", "81000002", "Invoice : Open"], ["OMS", "order", "M2000002", null, null]],
                records(first.INV9000002.findingId),
                "the record a person searches by, NetSuite's own key, and its status in NetSuite's words")
        Map run1 = checkRun("2026-10-14")
        assertEquals(["AUDCS_RAN", "2026-10"], [run1.statusEnumId, run1.period])
        assertEquals([["NETSUITE", "AUDSR_COMPLETE", 3L]], reads(run1.checkRunId), "one system read, all three invoices")

        List<Map> second2 = fixture("component://darpan/data/test/mornings-invoices-morning-2.json")
        // DAR-BE-320 (design approved 2026-11-26): the morning reads what could have moved in the month since the 14th, the
        // day the first morning's read ran through, and nothing else: INV9000002's Etail id was entered (it is dated the 13th)
        // and INV9000004 is new. INV9000003, open and untouched, is not in that read, so it is read again by key.
        morning("2026-10-15", [], second2.findAll { it.tranId in ["INV9000002", "INV9000004"] }, second2)
        assertEquals([[kind: "changed", from: at("2026-10-01"), changedSince: at("2026-10-14"), keys: null],
                      [kind: "byKey", from: at("2026-10-01"), changedSince: null, keys: ["INV9000003"]]], stubReads,
                "one read of what changed, over the month; then the open finding it lacks, by key; never the month to date")

        Map<String, Map> second = findingsByRecord()
        assertEquals(["INV9000002", "INV9000003"] as Set, second.keySet(), "a fixed finding stays on the ledger")
        assertEquals("AUDFS_FIXED", second.INV9000002.stateEnumId, "seen with its Etail id, so it closes")
        assertEquals("AUDFS_OPEN", second.INV9000003.stateEnumId, "still reported, still open")
        assertEquals(Date.valueOf("2026-10-14"), second.INV9000003.firstFoundDate, "first found does not move")
        assertEquals(["AUDFE_FOUND", "AUDFE_FIXED"], events(second.INV9000002.findingId))
        assertEquals(["AUDFE_FOUND", "AUDFE_STILL_OPEN"], events(second.INV9000003.findingId))

        Map report = (Map) ec.service.sync().name("audit.AuditReportServices.assemble#AuditReport").parameters([
                companyUserGroupId: TENANT, auditId: "MORNINGS_MONTH_CLOSE", reportDate: Date.valueOf("2026-10-15")])
                .disableAuthz().call()?.document
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        Map r2 = (Map) ((Map) ((Map) ((Map) report.categories).orders).statements).R2
        List<Map> findings = (List<Map>) r2.findings
        Map open = findings.find { it.records?.NetSuite?.invoice?.getAt(0)?.id == "INV9000003" }
        assertEquals("open", open?.state, "the report reads the ledger: ${findings*.headline}")
        // its day too, since the source names its query and so reads through the NetSuite connector, as in prod (DAR-BE-320)
        assertEquals([[id: "INV9000003", internalId: "81000003", status: "Invoice : Open", orderDate: "2026-10-14"]], open.records.NetSuite.invoice)
        assertNull(findings.find { it.records?.NetSuite?.invoice?.getAt(0)?.id == "INV9000004" }, "an invoice with its Etail id is no finding")

        // DAR-BE-320, 16 Oct: what moved since the 15th. INV9000001 (dated the 13th) lost its Etail id, INV9000005 is new.
        // INV9000003 (open) did not move: read again by key. INV9000004 (no finding) did not move: never read, no event.
        Map inv1 = second2.find { it.tranId == "INV9000001" }, inv3 = second2.find { it.tranId == "INV9000003" }
        Map inv4 = second2.find { it.tranId == "INV9000004" }
        Map inv5 = inv4 + [tranId: "INV9000005", internalId: "81000005", etailOrderId: "7187000000005", orderId: "M2000005"]
        Map inv6 = inv4 + [tranId: "INV9000006", internalId: "81000006", etailOrderId: "7187000000006", orderId: "M2000006",
                           tranDate: "2026-10-16"]
        Map inv1Blank = inv1 + [etailOrderId: " "]
        morning("2026-10-16", [], [inv1Blank, inv5], [inv1Blank, inv3, inv4])
        assertEquals([[kind: "changed", from: at("2026-10-01"), changedSince: at("2026-10-15"), keys: null],
                      [kind: "byKey", from: at("2026-10-01"), changedSince: null, keys: ["INV9000003"]]], stubReads,
                "the re-check reads by key within the month, as a full read would, and not what the changed read holds")
        Map<String, Map> third = findingsByRecord()
        assertNull(third.INV9000004, "never read, never a finding")
        assertEquals(["AUDFS_OPEN", Date.valueOf("2026-10-16")], [third.INV9000001?.stateEnumId, third.INV9000001?.firstFoundDate],
                "an invoice dated before the read that changed since is judged, and found")
        assertEquals("AUDFS_OPEN", third.INV9000003.stateEnumId)
        assertEquals("AUDFE_STILL_OPEN", events(third.INV9000003.findingId as String).last(), "read again by key: examined, still reported")

        // 17 Oct: read from the 16th, and nothing changed. Both open findings are read again by key, oldest first:
        // INV9000003 now carries its Etail id and closes, INV9000001 is still open.
        morning("2026-10-17", [], [inv6], [inv1Blank, inv3 + [etailOrderId: "7187000000003"]])
        assertEquals([[kind: "changed", from: at("2026-10-01"), changedSince: at("2026-10-16"), keys: null],
                      [kind: "byKey", from: at("2026-10-01"), changedSince: null, keys: ["INV9000003", "INV9000001"]]], stubReads)
        Map<String, Map> fourth = findingsByRecord()
        assertEquals("AUDFS_FIXED", fourth.INV9000003.stateEnumId, "read again by key, so examined: it closes")
        assertEquals("AUDFS_OPEN", fourth.INV9000001.stateEnumId)
        assertEquals("AUDFE_STILL_OPEN", events(fourth.INV9000001.findingId as String).last(), "examined and still reported")
        Map run4 = checkRun("2026-10-17")
        assertEquals("AUDCS_RAN", run4.statusEnumId, "the re-check finished: the check ran, so it can close findings")
        assertEquals([["NETSUITE", "AUDSR_COMPLETE", 3L]], reads(run4.checkRunId as String), "one read, two read again by key")
    }

    /**
     * DAR-BE-320 (read economy; gorjana R10, R11 and R12 read one NetSuite query): checks of one morning that read the same
     * source over the same window from the same start share one read. The first check reads it; the second copies its file
     * and judges it with its own rules, and each re-reads only its own open findings by key. A first morning (no complete
     * read yet) is not shared: each check reads its whole window.
     */
    @Test
    void twoChecksOfOneMorningReadingTheSameSourceShareOneRead() {
        List<String> autos = ["MORNINGS_AUTO_SHARE_1", "MORNINGS_AUTO_SHARE_2"]
        List<String> checks = ["MORNINGS_CHK_SHARE_1", "MORNINGS_CHK_SHARE_2"]
        Map automation = find("darpan.reconciliation.ReconciliationAutomation", [automationId: AUTOMATION]).getMap()
        Map source = find("darpan.reconciliation.ReconciliationAutomationSource", [automationId: AUTOMATION, fileSide: "FILE_1"]).getMap()
        Map check = find("darpan.audit.AuditCheck", [checkId: CHECK]).getMap()
        Map link = find("darpan.audit.AuditStatementCheck", [statementId: "MORNINGS_R2", checkId: CHECK]).getMap()
        [autos, checks].transpose().eachWithIndex { List pair, int i ->
            String a = pair[0], c = pair[1]
            upsert("darpan.reconciliation.ReconciliationAutomation", [automationId: a], automation + [automationId: a])
            upsert("darpan.reconciliation.ReconciliationAutomationSource", [automationId: a, fileSide: "FILE_1"], source + [automationId: a])
            upsert("darpan.audit.AuditCheck", [checkId: c], check + [checkId: c, checkCode: "shared-read-${i + 1}".toString(), automationId: a])
            upsert("darpan.audit.AuditStatementCheck", [statementId: "MORNINGS_R2", checkId: c], link + [checkId: c])
        }
        List<Map> rows = fixture("component://darpan/data/test/mornings-invoices-morning-1.json")
        mornings("2026-09-14", autos, rows, rows, rows)
        assertEquals(["window", "window"], stubReads*.kind, "a first morning reads each check's whole window, unshared")

        List<Map> rows2 = fixture("component://darpan/data/test/mornings-invoices-morning-2.json")
        Timestamp before = new Timestamp(System.currentTimeMillis())
        mornings("2026-09-15", autos, [], rows2.findAll { it.tranId in ["INV9000002", "INV9000004"] }, rows2)
        assertEquals([[kind: "changed", from: at("2026-09-01"), changedSince: at("2026-09-14"), keys: null],
                      [kind: "byKey", from: at("2026-09-01"), changedSince: null, keys: ["INV9000003"]],
                      [kind: "byKey", from: at("2026-09-01"), changedSince: null, keys: ["INV9000003"]]], stubReads,
                "one read of what changed for both checks; each re-reads its own open finding")
        List shared = ec.entity.find("darpan.audit.AuditSharedRead").condition("companyUserGroupId", TENANT)
                .condition("statusEnumId", AuditSharedReadSupport.DONE).condition("completedDate", EntityCondition.GREATER_THAN_EQUAL_TO, before)
                .disableAuthz().useCache(false).list()
        assertEquals(1, shared.size(), "one shared read for the morning")
        assertEquals(find("darpan.reconciliation.ReconciliationAutomationExecution", [automationId: autos[0]]) == null ? null :
                ec.entity.find("darpan.reconciliation.ReconciliationAutomationExecution").condition("automationId", autos[0])
                        .orderBy("-createdDate").disableAuthz().useCache(false).list()[0].automationExecutionId,
                shared[0].readerExecutionId, "read by the first check of the morning")
        checks.each { String c ->
            Map<String, Map> f = ec.entity.find("darpan.audit.AuditFinding").condition("checkId", c).disableAuthz().useCache(false)
                    .list().collectEntries { [(it.recordKey): it.getMap()] }
            assertEquals(["AUDFS_FIXED", "AUDFS_OPEN"], [f.INV9000002?.stateEnumId, f.INV9000003?.stateEnumId],
                    "${c}: judged from the shared read (INV9000002 fixed) and its own re-check (INV9000003 still open)")
        }
    }

    /**
     * DAR-BE-320 (review): a shared read is never stuck behind a reader that is gone. A row left READING by a run that no
     * longer runs is taken over at once, not waited out; a reader that was taken over cannot overwrite its successor's row;
     * a read that fails marks its row failed, so its waiters do not wait for it.
     */
    @Test
    void aSharedReadIsNeverStuckBehindAReaderThatIsGone() {
        String key = AuditSharedReadSupport.key(["MORNINGS", "dead-reader-test", System.nanoTime()])
        ec.artifactExecution.disableAuthz()
        try {
            ec.entity.makeValue("darpan.audit.AuditSharedRead").setAll([sharedReadKey: key, companyUserGroupId: TENANT,
                    statusEnumId: AuditSharedReadSupport.READING, readerExecutionId: "NO_SUCH_EXECUTION",
                    startedDate: new Timestamp(System.currentTimeMillis())]).create()
        } finally {
            ec.artifactExecution.enableAuthz()
        }
        File out = File.createTempFile("shared-dead-", ".json")
        out.deleteOnExit()
        out.text = JsonOutput.toJson([records: [[tranId: "INV1"]]])
        long started = System.currentTimeMillis()
        Map read = AuditSharedReadSupport.readOnce(ec, TENANT, key, "EXEC_NEW", { -> [fileLocation: out.absolutePath, recordCount: 1] },
                { String name -> new File(out.parentFile, "copy-" + name).absolutePath })
        assertFalse(read.shared as boolean, "the dead reader's row was taken over and read, not copied")
        assertTrue(System.currentTimeMillis() - started < AuditSharedReadSupport.pollMillis, "taken over at once, not waited out")
        Map row = find("darpan.audit.AuditSharedRead", [sharedReadKey: key]).getMap()
        assertEquals([AuditSharedReadSupport.DONE, "EXEC_NEW"], [row.statusEnumId, row.readerExecutionId])

        AuditSharedReadSupport.finish(ec, TENANT, key, "NO_SUCH_EXECUTION", [statusEnumId: AuditSharedReadSupport.FAILED, errorMessage: "late"])
        assertEquals(AuditSharedReadSupport.DONE, find("darpan.audit.AuditSharedRead", [sharedReadKey: key]).statusEnumId,
                "a reader that was taken over never overwrites the row")

        String failingKey = AuditSharedReadSupport.key(["MORNINGS", "failing-reader-test", System.nanoTime()])
        assertThrows(IllegalStateException) {
            AuditSharedReadSupport.readOnce(ec, TENANT, failingKey, "EXEC_FAIL", { -> throw new IllegalStateException("NetSuite 503") },
                    { String name -> name })
        }
        Map failed = find("darpan.audit.AuditSharedRead", [sharedReadKey: failingKey]).getMap()
        assertEquals([AuditSharedReadSupport.FAILED, "NetSuite 503"], [failed.statusEnumId, failed.errorMessage],
                "never left READING for its waiters")
    }

    @Test
    void aSingleSidedAutomationWithASecondSourceIsRefused() {
        String twoSided = "MORNINGS_AUTO_INVOICE_ETAIL_2"
        Timestamp now = ec.user.nowTimestamp
        Map automation = find("darpan.reconciliation.ReconciliationAutomation", [automationId: AUTOMATION]).getMap()
        upsert("darpan.reconciliation.ReconciliationAutomation", [automationId: twoSided],
                automation.findAll { it.key != "lastUpdatedStamp" } + [automationId: twoSided])
        ["FILE_1", "FILE_2"].each { String side ->
            upsert("darpan.reconciliation.ReconciliationAutomationSource", [automationId: twoSided, fileSide: side], [
                    automationId: twoSided, fileSide: side, companyUserGroupId: TENANT,
                    sourceTypeEnumId: AutomationExecutionSupport.AUTOMATION_SOURCE_API, systemEnumId: "NETSUITE_SUITEQL",
                    fileTypeEnumId: "DftJson", createdDate: now, lastUpdatedDate: now])
        }
        IllegalArgumentException refused = assertThrows(IllegalArgumentException) {
            AutomationExecutionSupport.executeAutomation(ec, [automationId: twoSided,
                    scheduledFireTime: Timestamp.from(LocalDateTime.parse("2026-10-14T06:00:00").atZone(LA).toInstant())])
        }
        assertTrue(refused.message.contains("EVALUATE scope, which takes one source"), refused.message)
    }

    /**
     * DAR-BE-094 / DAR-BE-095: one two-sided compare (exchange credit memos against the replacement orders that name
     * them), two field rules, two checks on ONE automation. Each statement takes only its own rule's mismatches.
     *   memo 81000101  replacement under another customer           -> R14
     *   memo 81000102  replacement is the return's original order   -> R13
     *   memo 81000103  neither side has an order id (two sentinels) -> nothing
     *   memo 81000104  its "replacement" is a 2023 order               -> R13 (the year-older rule)
     */
    @Test
    void oneCompareServesR14AndR13ThroughTheirOwnRules() {
        String auto = "MORNINGS_AUTO_MEMO_REPLACEMENT"
        Timestamp now = ec.user.nowTimestamp
        upsert("darpan.reconciliation.ReconciliationAutomation", [automationId: auto], [automationId: auto,
                automationName: "Month close: exchange credits against replacements", companyUserGroupId: TENANT,
                inputModeEnumId: AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE,
                savedRunId: "MORNINGS_MEMO_REPLACEMENT_RS", savedRunType: "ruleset", ruleSetId: "MORNINGS_MEMO_REPLACEMENT_RS",
                compareScopeId: "MORNINGS_MEMO_REPLACEMENT_SCOPE", relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_PREVIOUS_DAY,
                relativeWindowCount: 1, windowTimeZone: "America/Los_Angeles", isActive: "Y", createdDate: now, lastUpdatedDate: now])
        ["FILE_1", "FILE_2"].each { String side ->
            upsert("darpan.reconciliation.ReconciliationAutomationSource", [automationId: auto, fileSide: side], [
                    automationId: auto, fileSide: side, companyUserGroupId: TENANT,
                    sourceTypeEnumId: AutomationExecutionSupport.AUTOMATION_SOURCE_API, systemEnumId: "NETSUITE_SUITEQL",
                    fileTypeEnumId: "DftJson", createdDate: now, lastUpdatedDate: now])
        }
        ["MORNINGS_CHK_REPLACEMENT_CUSTOMER", "MORNINGS_CHK_REPLACEMENT_NOT_ORIGINAL"].each { String id ->
            def c = ec.entity.find("darpan.audit.AuditCheck").condition("checkId", id).disableAuthz().useCache(false).one()
            c.set("automationId", auto); c.update()
        }
        AutomationExecutionSupport.resetExecutionHooks()
        AutomationExecutionSupport.setSourceExtractor { def ignoredEc, def ignoredAutomation, def source,
                Map<String, Object> ignoredWindow, Map<String, Object> ignoredParams ->
            boolean memos = source.get("fileSide") == "FILE_1"
            return [dataAvailable: true, fileTypeEnumId: "DftJson", recordCount: 4,
                    fileLocation: "component://darpan/data/test/mornings-${memos ? 'exchange-memos' : 'replacement-orders'}.json".toString(),
                    fileName: "${memos ? 'memos' : 'orders'}.json".toString()]
        }
        Timestamp fire = Timestamp.from(LocalDateTime.parse("2026-10-14T06:00:00").atZone(LA).toInstant())
        Map result = AutomationExecutionSupport.executeAutomation(ec, [automationId: auto, scheduledFireTime: fire,
                sparkMaster: "local[1]", sparkAppName: "AuditMorningsSmokeTests"])
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertEquals(1, result.executedCount, "${result}")

        Closure<List<Map>> findingsOf = { String checkId ->
            ec.entity.find("darpan.audit.AuditFinding").condition("checkId", checkId).disableAuthz().useCache(false).list()*.getMap()
        }
        List<Map> r14 = findingsOf("MORNINGS_CHK_REPLACEMENT_CUSTOMER")
        List<Map> r13 = findingsOf("MORNINGS_CHK_REPLACEMENT_NOT_ORIGINAL")
        // DAR-UI-081: the headline names the memo by the number a person searches NetSuite by, not its internal id.
        assertEquals([["MORNINGS_R14", "81000101", "Credit memo id CM9000101 and its replacement order are under different customers"]],
                r14.collect { [it.statementId, it.recordKey, it.headline] })
        assertEquals([["MORNINGS_R13", "81000102", "Credit memo id CM9000102 names an existing order as its replacement"],
                      ["MORNINGS_R13", "81000104", "Credit memo id CM9000104 names an existing order as its replacement"]],
                r13.sort { it.recordKey }.collect { [it.statementId, it.recordKey, it.headline] })
        // DAR-UI-080: a FIELD_MISMATCH row carries both sides' records (sideRecords), so the memo is named by its own number
        // with its internal id beside it, and the replacement chain it points at is listed with it. With no conclusion
        // rules there is no evidence state.
        assertEquals([["NETSUITE", "creditMemo", "CM9000101", "81000101", null], ["NETSUITE", "replacementOrder", "SO9000101", null, null],
                      ["OMS", "order", "GR1000101", null, null], ["OMS", "return", "M300101", null, null],
                      ["OMS", "order", "EXC-#GOR100101-1", "M1000101", null]], records(r14[0].findingId as String),
                "the memo and the replacement chain it names")
    }

    /**
     * R12 as Aditi set it: every warranty claim has a credit memo for the amount claimed. Four claims: one credited at
     * its amount (65 against 65.00: one amount), one credited above it, one credited a cent below, one with no memo;
     * plus a memo for a return that is no warranty claim, which no statement takes.
     */
    @Test
    void r12FindsAClaimWithNoMemoAndMemosNotAtTheClaimedAmount() {
        String auto = "MORNINGS_AUTO_WARRANTY_CLAIM"
        Timestamp now = ec.user.nowTimestamp
        upsert("darpan.reconciliation.ReconciliationAutomation", [automationId: auto], [automationId: auto,
                automationName: "Month close: warranty claims against credit memos", companyUserGroupId: TENANT,
                inputModeEnumId: AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE,
                savedRunId: "MORNINGS_WARRANTY_CLAIM_RS", savedRunType: "ruleset", ruleSetId: "MORNINGS_WARRANTY_CLAIM_RS",
                compareScopeId: "MORNINGS_WARRANTY_CLAIM_SCOPE", relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_PREVIOUS_DAY,
                relativeWindowCount: 1, windowTimeZone: "America/Los_Angeles", isActive: "Y", createdDate: now, lastUpdatedDate: now])
        ["FILE_1", "FILE_2"].each { String side ->
            upsert("darpan.reconciliation.ReconciliationAutomationSource", [automationId: auto, fileSide: side], [
                    automationId: auto, fileSide: side, companyUserGroupId: TENANT,
                    sourceTypeEnumId: AutomationExecutionSupport.AUTOMATION_SOURCE_API, systemEnumId: "NETSUITE_SUITEQL",
                    fileTypeEnumId: "DftJson", createdDate: now, lastUpdatedDate: now])
        }
        ["MORNINGS_CHK_WARRANTY_NO_MEMO", "MORNINGS_CHK_WARRANTY_MEMO_AMOUNT"].each { String id ->
            def c = ec.entity.find("darpan.audit.AuditCheck").condition("checkId", id).disableAuthz().useCache(false).one()
            c.set("automationId", auto); c.update()
        }
        AutomationExecutionSupport.resetExecutionHooks()
        AutomationExecutionSupport.setSourceExtractor { def ignoredEc, def ignoredAutomation, def source,
                Map<String, Object> ignoredWindow, Map<String, Object> ignoredParams ->
            boolean claims = source.get("fileSide") == "FILE_1"
            return [dataAvailable: true, fileTypeEnumId: "DftJson", recordCount: 4,
                    fileLocation: "component://darpan/data/test/mornings-warranty-${claims ? 'claims' : 'claim-memos'}.json".toString(),
                    fileName: "${claims ? 'claims' : 'memos'}.json".toString()]
        }
        Timestamp fire = Timestamp.from(LocalDateTime.parse("2026-10-14T06:00:00").atZone(LA).toInstant())
        Map result = AutomationExecutionSupport.executeAutomation(ec, [automationId: auto, scheduledFireTime: fire,
                sparkMaster: "local[1]", sparkAppName: "AuditMorningsSmokeTests"])
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertEquals(1, result.executedCount, "${result}")

        Closure<List<Map>> findingsOf = { String checkId ->
            ec.entity.find("darpan.audit.AuditFinding").condition("checkId", checkId).disableAuthz().useCache(false).list()
                    *.getMap().sort { it.recordKey }
        }
        List<Map> noMemo = findingsOf("MORNINGS_CHK_WARRANTY_NO_MEMO")
        assertEquals([["MORNINGS_R12", "M400103", "Warranty claim RMA900103 has no credit memo"]],
                noMemo.collect { [it.statementId, it.recordKey, it.headline] }, "only the claim with no memo; not the stray memo")
        // DAR-UI-080: the claim's HotWax return is kept beside it.
        assertEquals([["NETSUITE", "warrantyClaim", "RMA900103", "82000103", null], ["OMS", "return", "M400103", null, null]],
                records(noMemo[0].findingId as String))

        List<Map> amount = findingsOf("MORNINGS_CHK_WARRANTY_MEMO_AMOUNT")
        assertEquals(["M400102", "M400104"], amount*.recordKey, "65 and 65.00 are one amount; above and a cent below are not")
        assertEquals("Credit memo for return M400102 is 70.77; the claim is 65.00", amount[0].headline, "money to the cent")
        assertEquals([65.00, 70.77, 5.77, "USD"], [amount[0].expectedAmount, amount[0].actualAmount, amount[0].differenceAmount,
                amount[0].currencyUomId].collect { it instanceof BigDecimal ? (it as BigDecimal).setScale(2) : it })
        assertEquals([75.44, 75.43, -0.01], [amount[1].expectedAmount, amount[1].actualAmount, amount[1].differenceAmount]
                .collect { (it as BigDecimal).setScale(2) })
        assertEquals(["Warranty claim (RMA total)", "NetSuite credit memo total"], [amount[0].expectedSource, amount[0].actualSource])
    }

    /**
     * R16: a return the OMS cancelled whose NetSuite RMA is still open. Three cancelled returns: one with an open RMA, a
     * numeric Happiness Guarantee one whose RMA ("HG-628301", keyed without the prefix) is open, one with none; and an open
     * RMA for a return nobody cancelled, which no statement takes.
     */
    @Test
    void r16FindsACancelledReturnWhoseRmaIsStillOpen() {
        String auto = "MORNINGS_AUTO_CANCELLED_RETURN"
        Timestamp now = ec.user.nowTimestamp
        upsert("darpan.reconciliation.ReconciliationAutomation", [automationId: auto], [automationId: auto,
                automationName: "Month close: cancelled returns left open", companyUserGroupId: TENANT,
                inputModeEnumId: AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE,
                savedRunId: "MORNINGS_CANCELLED_RETURN_RS", savedRunType: "ruleset", ruleSetId: "MORNINGS_CANCELLED_RETURN_RS",
                compareScopeId: "MORNINGS_CANCELLED_RETURN_SCOPE", relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_PREVIOUS_DAY,
                relativeWindowCount: 1, windowTimeZone: "America/Los_Angeles", isActive: "Y", createdDate: now, lastUpdatedDate: now])
        upsert("darpan.reconciliation.ReconciliationAutomationSource", [automationId: auto, fileSide: "FILE_1"], [
                automationId: auto, fileSide: "FILE_1", companyUserGroupId: TENANT,
                sourceTypeEnumId: AutomationExecutionSupport.AUTOMATION_SOURCE_API, systemEnumId: "OMS_RETURNS_GQL",
                fileTypeEnumId: "DftJson", createdDate: now, lastUpdatedDate: now])
        upsert("darpan.reconciliation.ReconciliationAutomationSource", [automationId: auto, fileSide: "FILE_2"], [
                automationId: auto, fileSide: "FILE_2", companyUserGroupId: TENANT,
                sourceTypeEnumId: AutomationExecutionSupport.AUTOMATION_SOURCE_API, systemEnumId: "NETSUITE_SUITEQL",
                fileTypeEnumId: "DftJson", createdDate: now, lastUpdatedDate: now])
        def c = ec.entity.find("darpan.audit.AuditCheck").condition("checkId", "MORNINGS_CHK_CANCELLED_RETURN_OPEN").disableAuthz().useCache(false).one()
        c.set("automationId", auto); c.update()
        AutomationExecutionSupport.resetExecutionHooks()
        AutomationExecutionSupport.setSourceExtractor { def ignoredEc, def ignoredAutomation, def source,
                Map<String, Object> ignoredWindow, Map<String, Object> ignoredParams ->
            boolean oms = source.get("fileSide") == "FILE_1"
            return [dataAvailable: true, fileTypeEnumId: "DftJson", recordCount: 3,
                    fileLocation: "component://darpan/data/test/mornings-${oms ? 'cancelled-returns' : 'open-rmas'}.json".toString(),
                    fileName: "${oms ? 'returns' : 'rmas'}.json".toString()]
        }
        Timestamp fire = Timestamp.from(LocalDateTime.parse("2026-10-14T06:00:00").atZone(LA).toInstant())
        Map result = AutomationExecutionSupport.executeAutomation(ec, [automationId: auto, scheduledFireTime: fire,
                sparkMaster: "local[1]", sparkAppName: "AuditMorningsSmokeTests"])
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertEquals(1, result.executedCount, "${result}")
        List<Map> found = ec.entity.find("darpan.audit.AuditFinding").condition("checkId", "MORNINGS_CHK_CANCELLED_RETURN_OPEN")
                .disableAuthz().useCache(false).list()*.getMap().sort { it.recordKey }
        assertEquals([["MORNINGS_R16", "628301", "Return 628301 is cancelled in the OMS; its RMA is Return Authorization : Pending Refund"],
                      ["MORNINGS_R16", "M500101", "Return M500101 is cancelled in the OMS; its RMA is Return Authorization : Pending Receipt"]],
                found.collect { [it.statementId, it.recordKey, it.headline] }, "the cancelled one with no RMA and the uncancelled RMA are not findings")
    }

    /** DAR-BE-320: the reads the NetSuite stub answered on the last morning, in order. */
    private List<Map> stubReads = []

    /**
     * One scheduled morning at 06:00 in Los Angeles. The NetSuite read answers `main` for the morning's own read, `changed`
     * for what changed in the window since the recent start (DAR-BE-320), and the records of `pool` by key, each from a
     * fresh file, since a re-check appends to the file it is given.
     */
    private void morning(String day, List<Map> main, List<Map> changed = main, List<Map> pool = main) {
        mornings(day, [AUTOMATION], main, changed, pool)
    }

    /** The morning of each automation in turn, every one answered by the same NetSuite stub (DAR-BE-320: shared reads). */
    private void mornings(String day, List<String> automationIds, List<Map> main, List<Map> changed = main, List<Map> pool = main) {
        stubReads = []
        AutomationExecutionSupport.resetExecutionHooks()
        AutomationExecutionSupport.setSourceExtractor { def ignoredEc, def ignoredAutomation, def source,
                Map<String, Object> window, Map<String, Object> params ->
            assertEquals("FILE_1", source.get("fileSide"), "a single-sided check never reads a second side")
            List keys = params?.lookupKeys as List
            String kind = params?.changedSince != null ? "changed" : keys ? "byKey" : "window"
            stubReads << [kind: kind, from: window.childWindowStartDate, changedSince: params?.changedSince, keys: keys]
            List<Map> rows = kind == "changed" ? changed : kind == "byKey" ? pool.findAll { keys.contains(it.tranId) } : main
            File f = File.createTempFile("mornings-${kind}-", ".json")
            f.deleteOnExit()
            f.text = JsonOutput.toJson([records: rows])
            return [dataAvailable: !rows.isEmpty(), fileLocation: f.absolutePath, fileName: f.name, fileTypeEnumId: "DftJson",
                    recordCount: rows.size()]
        }
        Timestamp fire = Timestamp.from(LocalDateTime.parse("${day}T06:00:00").atZone(LA).toInstant())
        automationIds.each { String automationId ->
            Map result = AutomationExecutionSupport.executeAutomation(ec, [automationId: automationId, scheduledFireTime: fire,
                    sparkMaster: "local[1]", sparkAppName: "AuditMorningsSmokeTests"])
            assertFalse(ec.message.hasError(), ec.message.errorsString)
            assertEquals(1, result.executedCount, "${day} ${automationId}: ${result}")
            def execution = ec.entity.find("darpan.reconciliation.ReconciliationAutomationExecution")
                    .condition("automationId", automationId).orderBy("-createdDate").disableAuthz().useCache(false).list()[0]
            assertEquals(AutomationExecutionSupport.STATUS_SUCCEEDED, execution.statusEnumId, "${day} ${automationId}: ${execution.errorMessage}")
        }
    }

    private List<Map> fixture(String location) {
        return (List<Map>) ((Map) new JsonSlurper().parseText(ec.resource.getLocationReference(location).getText())).records
    }

    private static Timestamp at(String day) {
        return Timestamp.from(java.time.LocalDate.parse(day).atStartOfDay(LA).toInstant())
    }

    private Map<String, Map> findingsByRecord() {
        return ec.entity.find("darpan.audit.AuditFinding").condition("checkId", CHECK).disableAuthz().useCache(false).list()
                .collectEntries { [(it.recordKey): it.getMap()] }
    }

    private List<List> records(String findingId) {
        return ec.entity.find("darpan.audit.AuditFindingRecord").condition("findingId", findingId).orderBy("recordSeqId")
                .disableAuthz().useCache(false).list().collect { [it.systemEnumId, it.recordType, it.recordId, it.internalId, it.recordStatus] }
    }

    private List<String> events(String findingId) {
        return ec.entity.find("darpan.audit.AuditFindingEvent").condition("findingId", findingId).orderBy("eventSeqId")
                .disableAuthz().useCache(false).list()*.eventTypeEnumId
    }

    private Map checkRun(String day) {
        return ec.entity.find("darpan.audit.AuditCheckRun").condition("checkId", CHECK).condition("reportDate", Date.valueOf(day))
                .disableAuthz().useCache(false).one()?.getMap()
    }

    private List<List> reads(String checkRunId) {
        return ec.entity.find("darpan.audit.AuditSourceRead").condition("checkRunId", checkRunId).orderBy("readSeqId")
                .disableAuthz().useCache(false).list().collect { [it.systemEnumId, it.statusEnumId, it.recordCount as Long] }
    }

    private def find(String entityName, Map pk) {
        return ec.entity.find(entityName).condition(pk).disableAuthz().useCache(false).one()
    }

    private void upsert(String entityName, Map pk, Map fields) {
        if (find(entityName, pk) == null) ec.entity.makeValue(entityName).setAll(fields).create()
    }
}
