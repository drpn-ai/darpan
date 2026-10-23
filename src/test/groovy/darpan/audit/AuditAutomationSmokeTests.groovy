package darpan.audit

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Connection
import java.sql.Statement
import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDateTime
import java.time.ZoneId

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-092 — the scheduled side of an audit run, against the real ledger services: the audit's windows replace the
 * automation's own, and after each window the check's health, findings and closings are written, in that order.
 * A hand-written diff document and key files stand in for the Spark compare, which RuleSetCompareScopeServiceSmokeTests
 * covers (both sides' keys out).
 *
 * The check tests R4 (rows missing in NetSuite) and R8 (rows missing in the OMS with conclusion CONC_X only).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditAutomationSmokeTests {

    private static final String TENANT = "TAUTO"
    private static final String AUDIT = "TAUTO_AUDIT"
    private static final String CHECK = "TAUTO_CHECK"
    private static final ZoneId LA = ZoneId.of("America/Los_Angeles")

    private ExecutionContext ec
    private File dir
    private final Map automation = [automationId: "TAUTO_AUTO", companyUserGroupId: TENANT, windowTimeZone: "America/Los_Angeles"]
    private final Map omsSource = [systemEnumId: "OMS_RETURNS_GQL", fileSide: "FILE_1"]
    private final Map nsSource = [systemEnumId: "NETSUITE_SUITEQL", fileSide: "FILE_2"]

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "audit-automation")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml")
        insert("moqui.basic.EnumerationType", [enumTypeId: "DarpanConclusion", description: "Run result conclusion"])
        insert("moqui.basic.Enumeration", [enumId: "CONC_X", enumTypeId: "DarpanConclusion", enumCode: "CONC_X", description: "X"])
        insert("moqui.security.UserGroup", [userGroupId: TENANT, description: "Tauto"])
        insert("darpan.audit.Audit", [auditId: AUDIT, companyUserGroupId: TENANT, auditCode: "month-close", auditName: "Month close",
                periodEnumId: "AUDP_MONTH"])
        insert("darpan.audit.Audit", [auditId: "TAUTO_AUDIT2", companyUserGroupId: TENANT, auditCode: "other", auditName: "Other",
                periodEnumId: "AUDP_MONTH"])
        statement("TAUTO_R4", AUDIT, "R4")
        statement("TAUTO_R8", AUDIT, "R8")
        statement("TAUTO_O1", "TAUTO_AUDIT2", "O1")
        // The automation row itself is AutomationExecution*Tests' concern; here only its id links the check, so the
        // foreign key is held off for this one insert.
        Connection connection = ec.entity.getConnection(ec.entity.getEntityGroupName("darpan.audit.AuditCheck"))
        try {
            Statement statement = connection.createStatement()
            statement.execute("SET REFERENTIAL_INTEGRITY FALSE")
            try {
                ReconciliationSmokeTestSupport.insertEntityDirect(ec, "darpan.audit.AuditCheck", [checkId: CHECK, companyUserGroupId: TENANT,
                        checkCode: "returns-vs-credit-memos", label: "returns vs memos", automationId: "TAUTO_AUTO",
                        file1RecordType: "return", file2RecordType: "creditMemo", headlineTemplate: "Return {recordKey}: {note}"])
            } finally {
                statement.execute("SET REFERENTIAL_INTEGRITY TRUE")
                statement.close()
            }
        } finally {
            connection.close()
        }
        insert("darpan.audit.AuditStatementCheck", [checkId: CHECK, statementId: "TAUTO_R4", companyUserGroupId: TENANT,
                sequenceNum: 1L, diffTypes: "MISSING_IN_FILE_2"])
        insert("darpan.audit.AuditStatementCheck", [checkId: CHECK, statementId: "TAUTO_R8", companyUserGroupId: TENANT,
                sequenceNum: 2L, diffTypes: "MISSING_IN_FILE_1", conclusionCodes: "CONC_X"])
        dir = File.createTempDir("audit-automation-", "")
    }

    @AfterAll
    void cleanup() {
        dir?.deleteDir()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearMessages() {
        ec.message.clearErrors()
    }

    @Test
    void theAutomationReadsTheAuditsWindowsInItsZone() {
        List<Map> checks = AuditAutomationSupport.checksFor(ec, automation)
        assertEquals([CHECK], checks*.checkId)
        Map audit = AuditAutomationSupport.auditFor(ec, TENANT, checks)
        assertEquals(AUDIT, audit.auditId)

        List<Map> windows = AuditAutomationSupport.resolveWindows(ec, automation, audit, at("2026-10-14", "06:00:00"))
        assertEquals(1, windows.size())
        assertEquals([at("2026-10-01", "00:00:00"), at("2026-10-15", "00:00:00"), "2026-10", Date.valueOf("2026-10-14")],
                [windows[0].childWindowStartDate, windows[0].childWindowEndDate, windows[0].auditPeriod, windows[0].auditReportDate],
                "on the 14th the window is the 1st to the end of the 14th, in Los Angeles")

        extend("2026-09", "2026-10-03")
        List<Map> both = AuditAutomationSupport.resolveWindows(ec, automation, audit, at("2026-10-02", "06:00:00"))
        assertEquals(["2026-10", "2026-09"], both*.auditPeriod, "the new month and the extended September")
        assertEquals([at("2026-09-01", "00:00:00"), at("2026-10-01", "00:00:00")], [both[1].childWindowStartDate, both[1].childWindowEndDate])
        extend("2026-09", null)
    }

    // DAR-UI-063: play runs the days a person picks, not the period to date. Each period's days are their own window,
    // dated as picked, reported on the run's own morning; a period that ended and was not extended is refused up front.
    @Test
    void picksDaysAreSplitByPeriodAndKeepTheirOwnDates() {
        List<Map> checks = AuditAutomationSupport.checksFor(ec, automation)
        Map audit = AuditAutomationSupport.auditFor(ec, TENANT, checks)
        Map explicit = [auditWindowFromDate: Date.valueOf("2026-10-10"), auditWindowThruDate: Date.valueOf("2026-10-14")]
        List<Map> windows = AuditAutomationSupport.resolveWindows(ec, automation, audit, at("2026-10-22", "16:00:00"), explicit)
        assertEquals(1, windows.size())
        assertEquals([at("2026-10-10", "00:00:00"), at("2026-10-15", "00:00:00"), "2026-10", Date.valueOf("2026-10-22"),
                      Date.valueOf("2026-10-10"), Date.valueOf("2026-10-14")],
                [windows[0].childWindowStartDate, windows[0].childWindowEndDate, windows[0].auditPeriod, windows[0].auditReportDate,
                 windows[0].auditWindowFromDate, windows[0].auditWindowThruDate])

        extend("2026-09", "2026-10-25")
        List<Map> both = AuditAutomationSupport.resolveWindows(ec, automation, audit, at("2026-10-22", "16:00:00"),
                [auditWindowFromDate: Date.valueOf("2026-09-28"), auditWindowThruDate: Date.valueOf("2026-10-02")])
        assertEquals(["2026-09", "2026-10"], both*.auditPeriod, "one window per period")
        assertEquals([Date.valueOf("2026-09-28"), Date.valueOf("2026-09-30")], [both[0].auditWindowFromDate, both[0].auditWindowThruDate])
        assertEquals([at("2026-10-01", "00:00:00"), at("2026-10-03", "00:00:00")], [both[1].childWindowStartDate, both[1].childWindowEndDate])
        extend("2026-09", null)

        IllegalStateException ended = assertThrows(IllegalStateException) {
            AuditAutomationSupport.resolveWindows(ec, automation, audit, at("2026-10-22", "16:00:00"),
                    [auditWindowFromDate: Date.valueOf("2026-09-28"), auditWindowThruDate: Date.valueOf("2026-10-02")])
        }
        assertTrue(ended.message.contains("2026-09"), ended.message)
        assertThrows(IllegalArgumentException) {
            AuditAutomationSupport.resolveWindows(ec, automation, audit, at("2026-10-22", "16:00:00"),
                    [auditWindowFromDate: Date.valueOf("2026-10-14"), auditWindowThruDate: Date.valueOf("2026-10-23")])
        }
    }

    @Test
    void aCheckReportingToTwoAuditsIsAConfigError() {
        // A check whose statements belong to two audits (or none) cannot pick its windows.
        insert("darpan.audit.AuditCheck", [checkId: "TAUTO_SPLIT", companyUserGroupId: TENANT, checkCode: "split", label: "split"])
        insert("darpan.audit.AuditStatementCheck", [checkId: "TAUTO_SPLIT", statementId: "TAUTO_R4", companyUserGroupId: TENANT])
        insert("darpan.audit.AuditStatementCheck", [checkId: "TAUTO_SPLIT", statementId: "TAUTO_O1", companyUserGroupId: TENANT])
        IllegalStateException split = assertThrows(IllegalStateException) {
            AuditAutomationSupport.auditFor(ec, TENANT, [[checkId: "TAUTO_SPLIT", checkCode: "split"]])
        }
        assertTrue(split.message.contains("report to 2 audits"), split.message)
    }

    @Test
    void aWindowWritesHealthThenFindingsThenClosings() {
        // Oct 7: A1 is missing in NetSuite (R4); B1 is missing in the OMS with CONC_X (R8); B2 has another conclusion.
        Map day1 = window("2026-10-07", ["A1", "B1", "B2"], ["B1", "B2", "C1"])
        AuditAutomationSupport.recordWindow(ec, automation, checks(), day1, omsSource, nsSource,
                [recordCount: 3, fileLocation: "x"], [recordCount: 3, fileLocation: "y"],
                [diffLocation: diff("d1", [row("MISSING_IN_FILE_2", "A1", "missing in NetSuite"),
                                           withChecks(row("MISSING_IN_FILE_1", "B1", "missing in HotWax", "CONC_X")),
                                           row("MISSING_IN_FILE_1", "B2", "missing in HotWax", "CONC_OTHER")])], null)
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        Map run = checkRun("2026-10-07")
        assertEquals(["AUDCS_RAN", "2026-10", Date.valueOf("2026-10-01"), Date.valueOf("2026-10-07")],
                [run.statusEnumId, run.period, run.windowFromDate, run.windowThruDate])
        assertEquals([["OMS", "OMS_RETURNS_GQL", "AUDSR_COMPLETE", 3L], ["NETSUITE", "NETSUITE_SUITEQL", "AUDSR_COMPLETE", 3L]],
                reads(run.checkRunId as String).collect { [it.systemEnumId, it.endpointEnumId, it.statusEnumId, it.recordCount as Long] })
        Map a1 = finding("A1")
        assertEquals(["TAUTO_R4", "Return A1: missing in NetSuite", "2026-10"], [a1.statementId, a1.headline, a1.period])
        assertEquals([["OMS", "return", "A1"]], records(a1.findingId as String).collect { [it.systemEnumId, it.recordType, it.recordId] },
                "missing in NetSuite: the record is the OMS return")
        Map b1 = finding("B1")
        assertEquals(["TAUTO_R8", "CONC_X"], [b1.statementId, b1.conclusionEnumId])
        assertEquals([["NETSUITE", "creditMemo", "B1"]], records(b1.findingId as String).collect { [it.systemEnumId, it.recordType, it.recordId] })
        assertEquals(0L, count("B2"), "a conclusion the link does not name breaks nothing")
        // The conclusion's checks are the finding's evidence, each with the value it read (R4: the memo's unapplied amount).
        assertEquals([["The credit memo is open", "Y", "Credit Memo : Open"], ["More than \$5 unapplied", "Y", "6.19"]],
                ec.entity.find("darpan.audit.AuditFindingCondition").condition("findingId", b1.findingId).orderBy("sequenceNum")
                        .disableAuthz().list().collect { [it.label, it.passed, it.checkValue] })

        // Oct 8: A1 is gone from the diff but both sides still held it (examined), so it closes; B1 is still reported.
        AuditAutomationSupport.recordWindow(ec, automation, checks(), window("2026-10-08", ["A1", "B1"], ["A1", "B1"]), omsSource, nsSource,
                [recordCount: 2], [recordCount: 2],
                [diffLocation: diff("d2", [row("MISSING_IN_FILE_1", "B1", "missing in HotWax", "CONC_X")])], null)
        assertEquals("AUDFS_FIXED", finding("A1").stateEnumId, "examined and no longer reported")
        assertEquals(["AUDFS_OPEN", 2L], [finding("B1").stateEnumId, finding("B1").timesSeen as Long])
    }

    @Test
    void aFailedReadIsRecordedAndClosesNothing() {
        AuditAutomationSupport.recordWindow(ec, automation, checks(), window("2026-10-10", ["F1"], []), omsSource, nsSource,
                [recordCount: 1], [recordCount: 0], [diffLocation: diff("d3", [row("MISSING_IN_FILE_2", "F1", "missing in NetSuite")])], null)
        assertEquals("AUDFS_OPEN", finding("F1").stateEnumId)

        // Oct 11: the NetSuite read times out. F1 was looked at by nobody, so it stays open.
        AuditAutomationSupport.recordWindow(ec, automation, checks(), window("2026-10-11", ["F1"], []), omsSource, nsSource,
                [recordCount: 1], null, null, new IllegalStateException("NetSuite SuiteQL page at offset 0 failed after 3 attempts"))
        Map run = checkRun("2026-10-11")
        assertEquals("AUDCS_PARTIAL", run.statusEnumId)
        assertTrue((run.reason as String).contains("The NetSuite read failed: NetSuite SuiteQL page at offset 0"), run.reason as String)
        assertEquals("AUDFS_OPEN", finding("F1").stateEnumId)

        // Oct 12: the OMS read fails first, so NetSuite is never read.
        AuditAutomationSupport.recordWindow(ec, automation, checks(), window("2026-10-12", [], []), omsSource, nsSource,
                null, null, null, new IllegalStateException("OMS 504"))
        List rs = reads(checkRun("2026-10-12").checkRunId as String)
        assertEquals(["AUDSR_FAILED", "AUDSR_FAILED"], rs*.statusEnumId)
        assertEquals("Not read: the OMS_RETURNS_GQL read failed first.", rs[1].reason)

        // Oct 13: both reads finished but the compare failed: the check did not run, nothing closes.
        AuditAutomationSupport.recordWindow(ec, automation, checks(), window("2026-10-13", ["F1"], []), omsSource, nsSource,
                [recordCount: 1], [recordCount: 1], null, new IllegalStateException("Spark ran out of memory"))
        Map notRun = checkRun("2026-10-13")
        assertEquals(["AUDCS_NOT_RUN", "The compare failed: Spark ran out of memory"], [notRun.statusEnumId, notRun.reason])
        assertEquals("AUDFS_OPEN", finding("F1").stateEnumId)
    }

    @Test
    void aCompositeKeyReadsAsOrderSlashItemButStaysRawForClosing() {
        Map check = [checkId: "X", headlineTemplate: "Item {recordKey} is not billed", file1RecordType: "orderItem"]
        Map row = [type: "MISSING_IN_FILE_2", id: "M1137118\u001F01", note: "missing"]
        assertEquals("Item M1137118/01 is not billed", AuditAutomationSupport.headline(check, row))
        assertEquals(["M1137118/01"], AuditAutomationSupport.recordsOf(check, row, omsSource, nsSource)*.recordId)
    }

    @Test
    void aLinkCanNameTheRulesWhoseMismatchesBreakItsStatement() {
        // DAR-BE-095: one compare, two field rules, two statements. A link's ruleIds picks its rule's FIELD_MISMATCH rows.
        statement("TAUTO_R14", AUDIT, "R14")
        statement("TAUTO_R13", AUDIT, "R13")
        insert("darpan.audit.AuditCheck", [checkId: "TAUTO_PAIR", companyUserGroupId: TENANT, checkCode: "memo-vs-replacement",
                label: "memo against replacement", file1RecordType: "creditMemo", file2RecordType: "salesOrder"])
        insert("darpan.audit.AuditStatementCheck", [checkId: "TAUTO_PAIR", statementId: "TAUTO_R14", companyUserGroupId: TENANT,
                sequenceNum: 1L, diffTypes: "FIELD_MISMATCH", ruleIds: "RULE_SAME_CUSTOMER"])
        insert("darpan.audit.AuditStatementCheck", [checkId: "TAUTO_PAIR", statementId: "TAUTO_R13", companyUserGroupId: TENANT,
                sequenceNum: 2L, diffTypes: "FIELD_MISMATCH", ruleIds: "RULE_NOT_OWN_ORIGINAL, RULE_OTHER"])
        Map customer = row("FIELD_MISMATCH", "80443722", "customer differs") + [ruleId: "RULE_SAME_CUSTOMER"]
        Map original = row("FIELD_MISMATCH", "80443999", "replacement is the original order") + [ruleId: "RULE_NOT_OWN_ORIGINAL"]
        Map amount = row("FIELD_MISMATCH", "80443000", "amount differs") + [ruleId: "RULE_AMOUNT"]
        List<Map> found = AuditAutomationSupport.findingsFrom(ec, TENANT, [checkId: "TAUTO_PAIR", file1RecordType: "creditMemo",
                file2RecordType: "salesOrder"], new File(diff("pair", [customer, original, amount])), nsSource, nsSource)
        assertEquals([["R14", "80443722"], ["R13", "80443999"]], found.collect { [it.statementCode, it.recordKey] },
                "each rule's rows break only its statement; a rule no link names breaks nothing")
        assertEquals(true, AuditAutomationSupport.matches(null, "ANY_RULE"), "a blank ruleIds takes every rule")
    }

    @Test
    void aRecordIsNamedByTheIdAPersonSearchesByAndOnlyOnTheSidesTheCheckNames() {
        // DAR-BE-095: the compare's key is the memo's internal id; the memo is CM7650999 to a person. The replacement
        // order's own id is not on the row, so a check that names no FILE_2 record type lists the memo alone.
        Map check = [checkId: "X", file1RecordType: "creditMemo", headlineTemplate: "Credit memo {creditMemo} and its replacement differ"]
        Map row = [type: "FIELD_MISMATCH", id: "80443722", data: [tranId: "CM7650999", internalId: "80443722"],
                   systems: [[side: "FILE_1", state: "Credit Memo : Fully Applied"]]]
        List<Map> recs = AuditAutomationSupport.recordsOf(check, row, nsSource, nsSource)
        assertEquals([["creditMemo", "CM7650999", "80443722", "Credit Memo : Fully Applied"]],
                recs.collect { [it.recordType, it.recordId, it.internalId, it.recordStatus] })
        assertEquals("Credit memo CM7650999 and its replacement differ", AuditAutomationSupport.headline(check, row, recs))
        // A check with no record types at all still lists both sides, as before.
        assertEquals(["record", "record"], AuditAutomationSupport.recordsOf([checkId: "Y"], [type: "RULE", id: "K1"], omsSource, nsSource)*.recordType)
    }

    @Test
    void aFindingNamesEveryRecordItsRowsIdsReach() {
        // DAR-BE-117: R13's mismatch row carries both records' ids. The memo and its replacement order are named by
        // their document numbers; the HotWax order each names follows once, the replacement's sentinel names none.
        Map check = [checkId: "X", file1RecordType: "creditMemo", file2RecordType: "replacementOrder"]
        Map row = [type: "FIELD_MISMATCH", id: "80852690", ids: AuditAutomationSupport.rowData(
                '{"file1":{"tranId":"CM7647980","internalId":"80852690","orderId":"M1085548"},' +
                '"file2":{"tranId":"SO761841","orderId":"NO_ORDER_ON_REPLACEMENT"}}')]
        assertEquals([["NETSUITE_SUITEQL", "creditMemo", "CM7647980", "80852690"],
                      ["NETSUITE_SUITEQL", "replacementOrder", "SO761841", null],
                      ["OMS", "order", "M1085548", null]],
                AuditAutomationSupport.recordsOf(check, row, nsSource, nsSource).collect { [it.systemEnumId, it.recordType, it.recordId, it.internalId] })

        // A NetSuite order names its HotWax order by name and id, its HotWax return, and its eTail (Shopify) order.
        List<Map> fromNs = AuditAutomationSupport.recordsOf([checkId: "Y", file2RecordType: "salesOrder"], [type: "MISSING_IN_FILE_1",
                id: "SO1", data: [tranId: "SO1", otherRef: "#GOR1", orderId: "M1", returnId: "M9", etailOrderId: "7156795932803"]],
                omsSource, nsSource)
        assertEquals([["NETSUITE_SUITEQL", "salesOrder", "SO1"], ["OMS", "order", "#GOR1"], ["OMS", "return", "M9"], ["SHOPIFY", "order", "7156795932803"]],
                fromNs.collect { [it.systemEnumId, it.recordType, it.recordId] })
        assertEquals("M1", fromNs[1].internalId)

        // A HotWax return names no HotWax record again, and its externalId is its Shopify RETURN, not an order.
        List<Map> fromOms = AuditAutomationSupport.recordsOf([checkId: "Z", file1RecordType: "return"], [type: "MISSING_IN_FILE_2",
                id: "M7", data: [returnId: "M7", externalId: "SR1", orderExternalId: "7156"]], omsSource, nsSource)
        assertEquals([["OMS_RETURNS_GQL", "return", "M7"], ["SHOPIFY", "order", "7156"]], fromOms.collect { [it.systemEnumId, it.recordType, it.recordId] })
        assertEquals([], AuditAutomationSupport.relatedRecords([returnId: "M7", externalId: "SR1"], "OMS_RETURNS_GQL"))
    }

    @Test
    void aHeadlineCanNameTheRowsConclusion() {
        Map check = [checkId: "X", headlineTemplate: "RMA {recordKey}: {conclusion}"]
        assertEquals("RMA RMA348610: No address it came from", AuditAutomationSupport.headline(check,
                [type: "FINDING", id: "RMA348610", note: "n", conclusionLabel: "No address it came from"]))
    }

    @Test
    void aRecordCarriesItsInternalIdAndItsStatusInItsSystemsWords() {
        Map check = [checkId: "X", file1RecordType: "return", file2RecordType: "creditMemo"]
        List systems = [[side: "FILE_1", state: "Completed"], [side: "FILE_2", state: "Credit Memo : Open"]]
        // A value difference: both sides, the row's record is FILE_1's; FILE_2's internal key is not on the row.
        List<Map> both = AuditAutomationSupport.recordsOf(check, [type: "RULE", id: "M258615", systems: systems,
                data: AuditAutomationSupport.rowData('{"returnId":"M258615","internalId":"51000002"}')], omsSource, nsSource)
        assertEquals([["return", "51000002", "Completed"], ["creditMemo", null, "Credit Memo : Open"]],
                both.collect { [it.recordType, it.internalId, it.recordStatus] })
        // Missing in FILE_1: the row's record is FILE_2's.
        List<Map> second = AuditAutomationSupport.recordsOf(check, [type: "MISSING_IN_FILE_1", id: "CM-88213", systems: systems,
                data: [tranId: "CM-88213", internalId: "51000002"]], omsSource, nsSource)
        assertEquals([["creditMemo", "51000002", "Credit Memo : Open"]], second.collect { [it.recordType, it.internalId, it.recordStatus] })
        // No conclusions, no evidence: no status. An internal id equal to the id says nothing new.
        List<Map> bare = AuditAutomationSupport.recordsOf(check, [type: "MISSING_IN_FILE_2", id: "M1", data: [internalId: "M1"]],
                omsSource, nsSource)
        assertEquals([[null, null]], bare.collect { [it.internalId, it.recordStatus] })
        assertEquals(null, AuditAutomationSupport.rowData("not json"))
    }

    @Test
    void aFailureMessageIsSanitizedBeforeTheLedgerStoresIt() {
        AuditAutomationSupport.recordWindow(ec, automation, checks(), window("2026-10-14", [], []), omsSource, nsSource,
                [recordCount: 1], null, null, new IllegalStateException("NetSuite 401 for apiToken=sk_live_secret, retry"))
        String readReason = reads(checkRun("2026-10-14").checkRunId as String)[1].reason as String
        assertFalse(readReason.contains("sk_live_secret"), readReason)
        assertTrue(readReason.contains("apiToken=***"), readReason)

        AuditAutomationSupport.recordWindow(ec, automation, checks(), window("2026-10-15", [], []), omsSource, nsSource,
                [recordCount: 1], [recordCount: 1], null, new IllegalStateException("password: hunter2 " + ("x" * 2000)))
        String notRun = checkRun("2026-10-15").reason as String
        assertFalse(notRun.contains("hunter2"), notRun.take(80))
        assertTrue(notRun.length() <= 1000, "length ${notRun.length()}")
    }

    @Test
    void theRunsOwnErrorsSurviveTheLedgerWrite() {
        ec.message.addError("the run's own error")
        AuditAutomationSupport.recordWindow(ec, automation, checks(), window("2026-10-20", [], []), omsSource, nsSource,
                [recordCount: 0], [recordCount: 0], null, null)
        assertEquals(["the run's own error"], ec.message.errors as List)
        ec.message.clearErrors()
        assertEquals("AUDCS_RAN", checkRun("2026-10-20").statusEnumId)
    }

    @Test
    void anAutomationWithNoAuditChecksIsUntouched() {
        assertEquals([], AuditAutomationSupport.checksFor(ec, [automationId: "SOME_OTHER", companyUserGroupId: TENANT]))
        assertEquals([], AuditAutomationSupport.checksFor(ec, [automationId: "TAUTO_AUTO", companyUserGroupId: "ANOTHER_TENANT"]),
                "another tenant's automation id finds nothing")
    }

    // -----------------------------------------------------------------------------------------------------------------

    private List<Map> checks() {
        return AuditAutomationSupport.checksFor(ec, automation)
    }

    private Map<String, Object> window(String day, List<String> file1Keys, List<String> file2Keys) {
        Map<String, Object> w = [auditId: AUDIT, auditPeriod: day.substring(0, 7), auditReportDate: Date.valueOf(day)] as Map<String, Object>
        File keys1 = new File(dir, "${day}-1.txt")
        File keys2 = new File(dir, "${day}-2.txt")
        keys1.text = file1Keys.join("\n")
        keys2.text = file2Keys.join("\n")
        w.auditFile1KeysOut = keys1.absolutePath
        w.auditFile2KeysOut = keys2.absolutePath
        return w
    }

    /** A row as the diff document on disk carries it (diffType / primaryId / message), not the compare's in-memory shape. */
    private static Map row(String type, String id, String note, String conclusion = null) {
        Map r = [diffType: type, compareScopeId: "S", objectType: "ORDER", primaryId: id, presentIn: "x", missingIn: "y",
                 data: "{}", message: note]
        if (conclusion) r.conclusion = [code: conclusion, label: conclusion]
        return r
    }

    private static Map withChecks(Map r) {
        ((Map) r.conclusion).checks = [[label: "The credit memo is open", value: "Credit Memo : Open", passed: true],
                                       [label: "More than \$5 unapplied", value: "6.19", passed: true]]
        return r
    }

    /** A diff document in the shape the compare writes: one row per line between the differences header and "]". */
    private String diff(String name, List<Map> rows) {
        File f = new File(dir, "${name}-diff.json")
        List<String> lines = ['{"summary":{},']
        lines << '"differences":['
        rows.eachWithIndex { Map r, int i -> lines << JsonOutput.toJson(r) + (i == rows.size() - 1 ? "]" : ",") }
        if (!rows) lines[-1] = '"differences":[]'
        lines << "}"
        f.text = lines.join("\n")
        return f.absolutePath
    }

    private void extend(String period, String thru) {
        ec.service.sync().name("audit.AuditReportServices.set#AuditPeriodExtension").parameters([companyUserGroupId: TENANT,
                auditId: AUDIT, period: period, continueThruDate: thru ? Date.valueOf(thru) : null]).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
    }

    private Map checkRun(String day) {
        return ec.entity.find("darpan.audit.AuditCheckRun").condition("checkId", CHECK).condition("reportDate", Date.valueOf(day))
                .useCache(false).one().getMap()
    }

    private List reads(String checkRunId) {
        return ec.entity.find("darpan.audit.AuditSourceRead").condition("checkRunId", checkRunId).orderBy("readSeqId").useCache(false).list()
    }

    private Map finding(String key) {
        return ec.entity.find("darpan.audit.AuditFinding").condition("checkId", CHECK).condition("recordKey", key).useCache(false).one().getMap()
    }

    private List records(String findingId) {
        return ec.entity.find("darpan.audit.AuditFindingRecord").condition("findingId", findingId).orderBy("recordSeqId").useCache(false).list()
    }

    private long count(String key) {
        return ec.entity.find("darpan.audit.AuditFinding").condition("checkId", CHECK).condition("recordKey", key).count()
    }

    private static Timestamp at(String day, String time) {
        return Timestamp.from(LocalDateTime.parse("${day}T${time}").atZone(LA).toInstant())
    }

    private void statement(String id, String auditId, String code) {
        insert("darpan.audit.AuditStatement", [statementId: id, companyUserGroupId: TENANT, auditId: auditId, statementCode: code,
                categoryEnumId: "AUDC_REFUNDS_CREDITS", weightEnumId: "AUDSW_FIRM", statementText: "${code} text".toString()])
    }

    private void insert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }

    @Test
    void aMismatchValueIsOnlyAnAmountWhenItIsAPlausibleOne() {
        Map money = [amountCurrencyUomId: "USD"]
        assertEquals("64.80", AuditAutomationSupport.valueText(money, "64.8"))
        assertEquals("1e999999999", AuditAutomationSupport.valueText(money, "1e999999999"), "a huge exponent stays text, never expanded")
        assertEquals("abc", AuditAutomationSupport.valueText(money, "abc"))
        assertEquals("64.8", AuditAutomationSupport.valueText([:], "64.8"), "not a money check: as it came")
        assertEquals([:], AuditAutomationSupport.amountsOf(money, [type: "FIELD_MISMATCH", file1Value: "65", file2Value: "1E+500"]),
                "no evidence from an implausible value")
        assertEquals(new BigDecimal("65"), AuditAutomationSupport.amountsOf(money + [expectedSource: "a"],
                [type: "FIELD_MISMATCH", file1Value: "65", file2Value: "70.77"]).expectedAmount)
    }
}
