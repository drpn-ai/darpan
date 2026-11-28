package darpan.audit

import darpan.reconciliation.support.SharedMoqui
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-090 — check health (record#CheckRun) and the one assembler for the audit-run JSON, against real entities.
 *
 * One scenario is written once, through the ledger's own writers, and every report test reads it:
 *   2026-10-07  returns check ran over complete reads; finds M258615 (R4) and RX-1 (R8). Invoice check did not run.
 *   2026-10-08  returns check ran; M258615 again (now open), RX-1 looked at and gone (fixed). Invoice check ran and
 *               found INV-552310 (R2, open on its first morning).
 *   2026-10-09  returns check ran over a PARTIAL NetSuite read; M258615 again; nothing closes. Invoice check did not run.
 * R5 and R12 have no check. All of that is GORJANA's "Month close" audit. Her second audit, "Daily ops" (period day),
 * has statement D1 and a check that never runs, with one finding: the month-close report must see none of it.
 * KREWE has its own audit and finding; TRUN is a third tenant for record#CheckRun alone, so those tests never change
 * the scenario's health.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SharedMoqui
class AuditReportAssemblerSmokeTests {

    private static final String LEDGER = "audit.AuditLedgerServices"
    private static final String REPORTS = "audit.AuditReportServices"
    private static final String GORJANA = "GORJANA"
    private static final String KREWE = "KREWE"
    private static final String TRUN = "TRUN"
    private static final ZoneId LA = ZoneId.of("America/Los_Angeles")
    private static final String RET = "TREP_RET"
    private static final String INV = "TREP_INV"
    private static final String MONTH_CLOSE = "TREP_AUDIT"
    private static final String DAILY_OPS = "TREP_OPS_AUDIT"
    private static final String R4_TEXT = "Every refund we give a customer is booked in NetSuite at the same amount, to the cent, with nothing left open."

    /** Keyed objects: their member names are data, so each member is compared with the golden's first member. */
    private static final Set<String> KEYED = ["categories", "statements", "sources", "records", "compared", "byCategory"] as Set

    private ExecutionContext ec
    private String m258615
    private String rx1
    private String kreweFinding
    private String opsFinding

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "audit-report")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml")
        insert("moqui.basic.EnumerationType", [enumTypeId: "DarpanConclusion", description: "Run result conclusion"])
        insert("moqui.basic.Enumeration", [enumId: "CONC_MEMO_SHORT_OF_REFUND", enumTypeId: "DarpanConclusion",
                enumCode: "CONC_MEMO_SHORT_OF_REFUND", description: "Memo short of the refund"])
        [(GORJANA): "Gorjana", (KREWE): "Krewe", (TRUN): "Trun"].each { String id, String name ->
            insert("moqui.security.UserGroup", [userGroupId: id, description: name])
        }
        insert("darpan.auth.TenantSetting", [companyUserGroupId: GORJANA, timeZone: "America/Los_Angeles"])
        audit(GORJANA, MONTH_CLOSE, "month-close", "Month close", "AUDP_MONTH")
        audit(GORJANA, DAILY_OPS, "daily-ops", "Daily ops", "AUDP_DAY")
        audit(KREWE, "TREP_K_AUDIT", "month-close", "Month close", "AUDP_MONTH")
        audit(TRUN, "TREP_T_AUDIT", "month-close", "Month close", "AUDP_MONTH")

        statement(GORJANA, MONTH_CLOSE, "TREP_R2", "R2", "AUDC_ORDERS", "AUDSW_FIRM", 2, "Every invoice and credit memo carries the Shopify order id (the Etail id).")
        statement(GORJANA, MONTH_CLOSE, "TREP_R4", "R4", "AUDC_REFUNDS_CREDITS", "AUDSW_DERIVED", 4, R4_TEXT)
        statement(GORJANA, MONTH_CLOSE, "TREP_R5", "R5", "AUDC_REFUNDS_CREDITS", "AUDSW_FIRM", 5, "Store credit given in Shopify is in NetSuite at the same amount.")
        statement(GORJANA, MONTH_CLOSE, "TREP_R8", "R8", "AUDC_RETURNS", "AUDSW_DERIVED", 8, "Every return reaches NetSuite.")
        statement(GORJANA, MONTH_CLOSE, "TREP_R12", "R12", "AUDC_EXCHANGES_WARRANTY", "AUDSW_DERIVED", 12, "A warranty replacement is paid by that claim's own credit.")
        insert("darpan.audit.AuditCheck", [checkId: RET, companyUserGroupId: GORJANA, checkCode: "returns-vs-credit-memos",
                label: "OMS returns against NetSuite credit memos", matchedBy: "return id (custbody_hc_return_id)",
                fixOwner: "HotWax Support", fixWhereEnumId: "NETSUITE",
                fixActionTemplate: "Correct credit memo {creditMemo} so its total is {expectedAmount} and nothing remains open.",
                passesWhenTemplate: "Memo total is {expectedAmount} and amount remaining is 0.00."])
        insert("darpan.audit.AuditCheck", [checkId: INV, companyUserGroupId: GORJANA, checkCode: "invoices-without-etail-id",
                label: "NetSuite invoices and memos with no Etail id"])
        link(GORJANA, RET, "TREP_R4", 1)
        link(GORJANA, RET, "TREP_R8", 2)
        link(GORJANA, INV, "TREP_R2", 1)
        statement(GORJANA, DAILY_OPS, "TREP_D1", "D1", "AUDC_ORDERS", "AUDSW_FIRM", 1, "Every order is released the day it is placed.")
        insert("darpan.audit.AuditCheck", [checkId: "TREP_OPS", companyUserGroupId: GORJANA, checkCode: "unreleased-orders", label: "OMS orders not released"])
        link(GORJANA, "TREP_OPS", "TREP_D1", 1)

        statement(KREWE, "TREP_K_AUDIT", "TREP_K_R4", "R4", "AUDC_REFUNDS_CREDITS", "AUDSW_FIRM", 4, "krewe R4")
        insert("darpan.audit.AuditCheck", [checkId: "TREP_K", companyUserGroupId: KREWE, checkCode: "krewe-check", label: "krewe"])
        link(KREWE, "TREP_K", "TREP_K_R4", 1)
        statement(TRUN, "TREP_T_AUDIT", "TREP_T_R1", "R1", "AUDC_ORDERS", "AUDSW_FIRM", 1, "trun R1")
        insert("darpan.audit.AuditCheck", [checkId: "TREP_T", companyUserGroupId: TRUN, checkCode: "trun-check", label: "trun"])
        link(TRUN, "TREP_T", "TREP_T_R1", 1)
        // A check that tests a monthly and a daily audit's statements: its window cannot be guessed.
        audit(TRUN, "TREP_T_DAILY", "daily", "Daily", "AUDP_DAY")
        statement(TRUN, "TREP_T_DAILY", "TREP_T_D1", "D1", "AUDC_ORDERS", "AUDSW_FIRM", 1, "trun D1")
        insert("darpan.audit.AuditCheck", [checkId: "TREP_T2", companyUserGroupId: TRUN, checkCode: "two-periods", label: "two periods"])
        link(TRUN, "TREP_T2", "TREP_T_R1", 1)
        link(TRUN, "TREP_T2", "TREP_T_D1", 2)

        Map memo = [statementCode: "R4", recordKey: "M258615", headline: "Credit memo is 0.01 less than the refund for return M258615",
                ruleText: "Credit memo total equals the refund given, to the cent, and nothing is left open.",
                expectedAmount: "160.23", actualAmount: "160.22", currencyUomId: "USD",
                expectedSource: "Shopify refund transactions", actualSource: "NetSuite credit memo total",
                conclusionEnumId: "CONC_MEMO_SHORT_OF_REFUND",
                diagnosisText: "NetSuite booked the memo one cent below what the customer was refunded.",
                records: [[systemEnumId: "SHOPIFY", recordType: "order", recordId: "7187000000000"],
                          [systemEnumId: "OMS_RETURNS_GQL", recordType: "return", recordId: "M258615", recordStatus: "Completed"],
                          [systemEnumId: "NETSUITE_SUITEQL", recordType: "creditMemo", recordId: "CM-88213", internalId: "51000002", recordStatus: "Open"]],
                checks: [[label: "NetSuite has a credit memo for the return", passed: true, value: "CM-88213"],
                         [label: "Memo total equals the refund", passed: false, value: "160.22 vs 160.23"]]]
        Map rx = [statementCode: "R8", recordKey: "RX-1", headline: "Return RX-1 has no RMA in NetSuite",
                records: [[systemEnumId: "OMS_RETURNS_GQL", recordType: "return", recordId: "RX-1"]]]
        Map invoice = [statementCode: "R2", recordKey: "INV-552310", headline: "Invoice INV-552310 has no Etail id",
                records: [[systemEnumId: "NETSUITE_SUITEQL", recordType: "invoice", recordId: "INV-552310", internalId: "51000940", recordStatus: "Paid In Full"]]]
        List complete = [read("OMS_RETURNS_GQL", "AUDSR_COMPLETE", 412, "06:02:55"), read("NETSUITE_SUITEQL", "AUDSR_COMPLETE", 409, "06:04:11")]

        // 2026-10-07
        Map run = checkRun(GORJANA, RET, "2026-10-07", "06:10:02", complete)
        Map found = record(RET, "2026-10-07", "06:10:02", [memo, rx])
        m258615 = (found.findingIds as List)[0]
        rx1 = (found.findingIds as List)[1]
        close(RET, "2026-10-07", "06:10:02", run.checkStatusEnumId as String, ["M258615", "RX-1"])
        // 2026-10-08
        run = checkRun(GORJANA, RET, "2026-10-08", "06:12:40", complete)
        record(RET, "2026-10-08", "06:12:40", [memo])
        close(RET, "2026-10-08", "06:12:40", run.checkStatusEnumId as String, ["M258615", "RX-1"])
        run = checkRun(GORJANA, INV, "2026-10-08", "06:05:00", [read("NETSUITE_SUITEQL", "AUDSR_COMPLETE", 2210, "06:04:30")])
        record(INV, "2026-10-08", "06:05:00", [invoice])
        close(INV, "2026-10-08", "06:05:00", run.checkStatusEnumId as String, ["INV-552310"])
        // 2026-10-09: a partial NetSuite read
        run = checkRun(GORJANA, RET, "2026-10-09", "06:11:00", [read("OMS_RETURNS_GQL", "AUDSR_COMPLETE", 400, "06:03:00"),
                read("NETSUITE_SUITEQL", "AUDSR_PARTIAL", 300, "06:05:00", "page ceiling reached")])
        assertEquals("AUDCS_PARTIAL", run.checkStatusEnumId)
        record(RET, "2026-10-09", "06:11:00", [memo])
        assertEquals(0, close(RET, "2026-10-09", "06:11:00", run.checkStatusEnumId as String, ["M258615", "RX-1"]).closedCount)

        opsFinding = (ec.service.sync().name("${LEDGER}.record#CheckFindings").parameters([companyUserGroupId: GORJANA, checkId: "TREP_OPS",
                reportDate: Date.valueOf("2026-10-08"), checkStamp: at("2026-10-08", "06:01:00"),
                findings: [[statementCode: "D1", recordKey: "ORD-9", headline: "Order ORD-9 is not released"]]]).call().findingIds as List)[0]
        noErrors()

        Map kfound = ec.service.sync().name("${LEDGER}.record#CheckFindings").parameters([companyUserGroupId: KREWE, checkId: "TREP_K",
                reportDate: Date.valueOf("2026-10-08"), findings: [[statementCode: "R4", recordKey: "K-1", headline: "krewe finding"]]]).call()
        noErrors()
        kreweFinding = (kfound.findingIds as List)[0]
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearMessages() {
        ec.message.clearErrors()
    }

    // ---- the report ----------------------------------------------------------------------------------------------

    @Test
    void theReportMatchesTheContractKeyForKey() {
        Map doc = report(GORJANA, "2026-10-08")
        assertSameShape(golden("golden-report.json"), doc, '$', null, templates(golden("golden-report.json")))

        assertEquals("darpan.audit.report/v1", doc.schema)
        assertEquals([tenant: [id: GORJANA, name: "Gorjana", timeZone: "America/Los_Angeles"],
                      audit: [id: "month-close", name: "Month close", period: "month"], date: "2026-10-08", period: "2026-10",
                      window: [from: "2026-10-01", thru: "2026-10-08"], running: true,
                      generatedAt: "2026-10-08T06:12:40-07:00", acknowledgeRemindDays: 5], doc.report, "month to date, the report day included")
        assertEquals("Month close audit for Gorjana, 2026-10. Each finding is one record that breaks one of the audit's statements R2 to R12.", doc.about)
        assertEquals(["orders", "refundsAndCredits", "returns", "exchangesAndWarranty"], (doc.categories as Map).keySet() as List)
        assertEquals("Refunds and credits", doc.categories.refundsAndCredits.label)
        assertEquals(["R4", "R5"], (doc.categories.refundsAndCredits.statements as Map).keySet() as List, "catalog order")

        Map r4 = doc.categories.refundsAndCredits.statements.R4
        assertEquals([id: "R4", text: R4_TEXT, weight: "derived", status: "ran", reason: null], r4.subMap(["id", "text", "weight", "status", "reason"]))
        assertEquals([[id: "returns-vs-credit-memos", label: "OMS returns against NetSuite credit memos", status: "ran", reason: null,
                       matchedBy: "return id (custbody_hc_return_id)", compared: [OMS: 412, NetSuite: 409], systems: ["OMS", "NetSuite"],
                       window: [from: "2026-10-01", thru: "2026-10-08"], outsideRules: null]], r4.checks, "outsideRules null: this run predates D11")
        assertEquals(counted(1, 0, 0), r4.counts)
        Map brief = (r4.findings as List)[0]
        assertEquals([id: m258615, headline: "Credit memo is 0.01 less than the refund for return M258615", state: "open",
                      firstFound: "2026-10-07", ageDays: 1, difference: [amount: "-0.01", currency: "USD"],
                      cause: "Memo short of the refund", next: "darpan findings show ${m258615}".toString(),
                      priority: "P2", prioritySource: "statement", priorityReason: null, acknowledgement: null],
                brief.findAll { it.key != "records" })
        assertEquals([Shopify : [order: [[id: "7187000000000", internalId: null, status: null]]],
                      OMS     : ["return": [[id: "M258615", internalId: null, status: "Completed"]]],
                      NetSuite: [creditMemo: [[id: "CM-88213", internalId: "51000002", status: "Open"]]]], brief.records,
                "records by ROOT system and type, every value an array")

        Map r2 = doc.categories.orders.statements.R2
        assertEquals(counted(1, 1, 0), r2.counts, "open on the morning it is first found")
        assertEquals(["open", null, [NetSuite: 2210]], [r2.findings[0].state, r2.findings[0].difference, r2.checks[0].compared])

        Map r8 = doc.categories.returns.statements.R8
        assertEquals(counted(0, 0, 1), r8.counts)
        assertEquals([[rx1, "fixed"]], (r8.findings as List).collect { [it.id, it.state] }, "fixed today stays on the list that day")

        assertEquals([open: 2, newToday: 1, fixedToday: 1], doc.summary.subMap(["open", "newToday", "fixedToday"]))
        assertFalse((doc.summary as Map).containsKey("pending"), "there is no pending count")
        assertEquals([id: m258615, statement: "R4", firstFound: "2026-10-07", ageDays: 1], doc.summary.oldestOpen)

        assertEquals(true, doc.health.complete)
        assertEquals("Every configured check ran over complete reads. R5 and R12 have no check yet.", doc.health.summary)
        assertEquals([ran: 2, partial: 0, notRun: 0], doc.health.checks)
        assertEquals([read: "complete", records: 2210 + 409, readAt: "2026-10-08T06:04:30-07:00", reason: null, affects: []],
                doc.health.sources.NetSuite)
        assertEquals(["NetSuite", "OMS"] as Set, (doc.health.sources as Map).keySet())
    }

    @Test
    void aStatementWithNoCheckIsNotRunWithItsReasonAndNeverMissing() {
        ["2026-10-07", "2026-10-08", "2026-10-09"].each { String day ->
            Map doc = report(GORJANA, day)
            assertEquals(["R5", "R12"], doc.health.notCovered, day)
            Map r5 = doc.categories.refundsAndCredits.statements.R5
            assertEquals([status: "notRun", reason: "No check is configured for this statement yet.", checks: [],
                          counts: counted(0, 0, 0), findings: [], findingsOmitted: 0],
                    r5.subMap(["status", "reason", "checks", "counts", "findings", "findingsOmitted"]), day)
            assertNotNull(doc.categories.exchangesAndWarranty.statements.R12, "R12 present on ${day}")
        }
    }

    // DAR-OPS-029 S10 (no hollow green): an audit with no check configured read complete, so its Run health said
    // "complete" with nothing asking for attention.
    @Test
    void anAuditWithNoCheckIsNotComplete() {
        insert("moqui.security.UserGroup", [userGroupId: "TNOC", description: "No checks"])
        audit("TNOC", "TNOC_AUDIT", "no-checks", "No checks", "AUDP_MONTH")
        statement("TNOC", "TNOC_AUDIT", "TNOC_S1", "S1", "AUDC_ORDERS", "AUDSW_FIRM", 1, "no-checks S1")
        Map health = report("TNOC", "TNOC_AUDIT", "2026-10-08").health as Map
        assertEquals([complete: false, summary: "No check is configured yet. S1 has no check yet.",
                      checks: [ran: 0, partial: 0, notRun: 0], notCovered: ["S1"]], health.subMap(["complete", "summary", "checks", "notCovered"]))
    }

    @Test
    void aPastMorningReadsEachFindingsStateFromItsHistory() {
        Map doc = report(GORJANA, "2026-10-07")
        Map r4 = doc.categories.refundsAndCredits.statements.R4
        assertEquals(counted(1, 1, 0), r4.counts)
        Map r8 = doc.categories.returns.statements.R8
        assertEquals([[rx1, "open"]], (r8.findings as List).collect { [it.id, it.state] }, "stored state is fixed; on 10-07 it was open")
        Map r2 = doc.categories.orders.statements.R2
        assertEquals([], r2.findings, "INV-552310 was first found on 10-08")
        assertEquals(["notRun", "The check did not run on the morning of 2026-10-07."], [r2.status, r2.reason])
        assertEquals(false, doc.health.complete)
        assertEquals([ran: 1, partial: 0, notRun: 1], doc.health.checks)
        assertEquals("Not complete: check invoices-without-etail-id did not run. R5 and R12 have no check yet.", doc.health.summary)
        assertEquals([id: m258615, statement: "R4", firstFound: "2026-10-07", ageDays: 0], doc.summary.oldestOpen,
                "open on the morning it was found")
    }

    @Test
    void aPartialReadIsNamedWithTheStatementsItAffects() {
        Map doc = report(GORJANA, "2026-10-09")
        assertEquals([read: "partial", records: 300, readAt: "2026-10-09T06:05:00-07:00", reason: "page ceiling reached",
                      affects: ["R4", "R8"]], doc.health.sources.NetSuite)
        assertEquals([], doc.health.sources.OMS.affects)
        assertEquals([ran: 0, partial: 1, notRun: 1], doc.health.checks)
        assertEquals(false, doc.health.complete)
        assertEquals("Not complete: the NetSuite read was partial; check invoices-without-etail-id did not run. R5 and R12 have no check yet.",
                doc.health.summary)

        Map r4 = doc.categories.refundsAndCredits.statements.R4
        assertEquals(["partial", "The NetSuite read was partial: page ceiling reached."], [r4.status, r4.reason])
        assertEquals(counted(1, 0, 0), r4.counts)
        assertEquals([OMS: 400, NetSuite: 300], r4.checks[0].compared)

        Map r2 = doc.categories.orders.statements.R2
        assertEquals("notRun", r2.status)
        assertNull(r2.checks[0].compared, "compared is null when the check did not run")
        assertEquals(counted(1, 0, 0), r2.counts, "counts are kept on a morning the check did not run")
        assertEquals(2, doc.summary.oldestOpen.ageDays)

        Map r8 = doc.categories.returns.statements.R8
        assertEquals([[], counted(0, 0, 0)], [r8.findings, r8.counts],
                "RX-1 was fixed on 10-08, so it is off the 10-09 list")
        assertEquals(0, doc.summary.fixedToday)
    }

    @Test
    void countsAddUpAtEveryLevel() {
        ["2026-10-07", "2026-10-08", "2026-10-09"].each { String day ->
            Map doc = report(GORJANA, day)
            List<String> keys = ["open", "acknowledged", "newToday", "fixedToday"]
            (doc.categories as Map).each { String code, Map cat ->
                (cat.statements as Map).each { String id, Map st ->
                    assertEquals((st.counts.open + st.counts.acknowledged + st.counts.fixedToday) as int,
                            ((st.findings as List).size() + (st.findingsOmitted as int)) as int, "${day} ${id} findings vs counts")
                }
                keys.each { String k -> assertEquals(cat.counts[k], (cat.statements as Map).values().sum(0) { it.counts[k] }, "${day} ${code}.${k}") }
                assertEquals(cat.counts, doc.summary.byCategory[code])
            }
            keys.each { String k -> assertEquals(doc.summary[k], (doc.categories as Map).values().sum(0) { it.counts[k] }, "${day} summary.${k}") }
        }
    }

    // DAR-UI-075. One finding per order: the order's line findings sit under it, and the line whose cause is its own (not
    // held by another line's) is the one named as holding the order up.
    @Test
    void anOrdersLineFindingsAreOneFindingThatNamesTheLineHoldingItUp() {
        insert("moqui.basic.Enumeration", [enumId: "TREP_CONC_BACKORDERED", enumTypeId: "DarpanConclusion",
                enumCode: "TREP_CONC_BACKORDERED", description: "Backordered in NetSuite"])
        insert("moqui.basic.Enumeration", [enumId: "TREP_CONC_HELD", enumTypeId: "DarpanConclusion", enumCode: "TREP_CONC_HELD",
                description: "Shipped, not billed: another line of its order is backordered in NetSuite", relatedEnumId: "TREP_CONC_BACKORDERED"])
        Closure<Map> line = { String item, String cause ->
            [statementCode: "R1", recordKey: item, headline: "Item ${item} is completed in HotWax and not billed in NetSuite".toString(),
             conclusionEnumId: cause, records: [[systemEnumId: "OMS_RETURNS_GQL", recordType: "orderItem", recordId: item, recordStatus: "Completed"],
                                                [systemEnumId: "NETSUITE_SUITEQL", recordType: "salesOrder", recordId: "SO7331269"]]]
        }
        Map found = ec.service.sync().name("${LEDGER}.record#CheckFindings").parameters([companyUserGroupId: TRUN, checkId: "TREP_T",
                reportDate: Date.valueOf("2026-10-22"), checkStamp: at("2026-10-22", "07:27:00"),
                findings: [line("M1168785/01", "TREP_CONC_BACKORDERED"), line("M1168785/02", "TREP_CONC_HELD"),
                           line("M1168785/03", "TREP_CONC_HELD"), line("M1167646/01", null)]]).call()
        noErrors()
        List<String> ids = found.findingIds as List<String>

        Map r1 = (Map) ((Map) ((Map) report(TRUN, "TREP_T_AUDIT", "2026-10-22").categories).orders.statements).R1
        List<Map> findings = r1.findings as List<Map>
        assertEquals(["order", "order"], findings*.kind, "M1168785's three lines are one finding; M1167646's one line is an order too")
        Map order = findings[0]
        assertEquals(["M1168785", "open", ids[0..2], [ids[0]], "Backordered in NetSuite"],
                [order.order, order.state, (order.lines as List<Map>)*.id, order.stuck, order.cause])
        assertEquals(["Backordered in NetSuite", "Shipped, not billed: another line of its order is backordered in NetSuite"],
                ((order.lines as List<Map>)*.cause).unique())
        // DAR-UI-075: a line by its number, not its "/01" suffix.
        assertEquals("Order M1168785: line 1 backordered in NetSuite, holding 2 more lines", order.headline)
        assertEquals(counted(2, 2, 0), r1.counts, "counted by order, not by line")
        assertEquals(["M1167646", [ids[3]], "Order M1167646: line 1 is completed in HotWax and not billed in NetSuite"],
                [findings[1].id, (findings[1].lines as List<Map>)*.id, findings[1].headline])
        // A finding that only mentions an order (a memo naming its Shopify order) is not about an order line: no order.
        Map r4 = (Map) ((Map) ((Map) report(GORJANA, "2026-10-08").categories).refundsAndCredits?.statements ?: [:]).R4
        assertEquals(null, ((r4?.findings ?: []) as List<Map>).find()?.kind)
    }

    // DAR-BE-250 (Aditi 2026-11-12: "earliest step wins"). gorjana's M1210830: R10 (step 4) has its exchange's open line in
    // HotWax's brokering queue, and R1 (step 8) cannot bill its other line because of it. One order, reported once, at
    // step 4; R1's finding waits on it, listed under it, not counted. An unrelated R1 order still counts.
    @Test
    void anOrderBrokenAtTwoStepsCountsOnceAtTheEarliest() {
        audit(TRUN, "TREP_T_STORY", "story", "Story", "AUDP_MONTH")
        statement(TRUN, "TREP_T_STORY", "TREP_S4", "S4", "AUDC_ORDERS", "AUDSW_FIRM", 40, "Every order line open in NetSuite is at a facility that can fulfil it.")
        statement(TRUN, "TREP_T_STORY", "TREP_S8", "S8", "AUDC_ORDERS", "AUDSW_FIRM", 80, "Every line HotWax completes is shipped and invoiced in NetSuite.")
        insert("darpan.audit.AuditCheck", [checkId: "TREP_C4", companyUserGroupId: TRUN, checkCode: "story-open-lines", label: "open lines"])
        insert("darpan.audit.AuditCheck", [checkId: "TREP_C8", companyUserGroupId: TRUN, checkCode: "story-completed", label: "completed"])
        link(TRUN, "TREP_C4", "TREP_S4", 1)
        link(TRUN, "TREP_C8", "TREP_S8", 1)
        Closure<List> recordFindings = { String checkId, List findings ->
            List ids = ec.service.sync().name("${LEDGER}.record#CheckFindings").parameters([companyUserGroupId: TRUN, checkId: checkId,
                    reportDate: Date.valueOf("2026-10-22"), checkStamp: at("2026-10-22", "07:30:00"), findings: findings]).call().findingIds as List
            noErrors()
            return ids
        }
        List s4 = recordFindings("TREP_C4", [[statementCode: "S4", recordKey: "SO7371002/01", headline: "Line M1210830/01 is open in NetSuite and not at a facility",
                records: [[systemEnumId: "NETSUITE_SUITEQL", recordType: "salesOrderLine", recordId: "SO7371002", internalId: "81531855"],
                          [systemEnumId: "OMS_RETURNS_GQL", recordType: "order", recordId: "EXC-#GOR197136981-1", internalId: "M1210830"]]]])
        List s8 = recordFindings("TREP_C8", [
                [statementCode: "S8", recordKey: "M1210830/02", headline: "Item M1210830/02 is completed in HotWax and not billed in NetSuite",
                 records: [[systemEnumId: "OMS_RETURNS_GQL", recordType: "orderItem", recordId: "M1210830/02",
                            recordData: JsonOutput.toJson([omsOrderName: "EXC-#GOR197136981-1"])],
                           [systemEnumId: "NETSUITE_SUITEQL", recordType: "salesOrderLine", recordId: "SO7371002", internalId: "81531855"]]],
                [statementCode: "S8", recordKey: "M9/01", headline: "Item M9/01 is completed in HotWax and not billed in NetSuite",
                 records: [[systemEnumId: "OMS_RETURNS_GQL", recordType: "orderItem", recordId: "M9/01"]]]])

        Map doc = report(TRUN, "TREP_T_STORY", "2026-10-22")
        Map statements = (Map) doc.categories.orders.statements
        assertEquals([4L * 10, 8L * 10], [statements.S4.step, statements.S8.step]*.toLong())
        assertEquals(counted(1, 1, 0), statements.S4.counts, "the order counts at its earliest step")
        assertEquals(counted(1, 1, 0), statements.S8.counts, "only M9 counts at step 8")
        assertEquals([0, 1], [statements.S4.waiting, statements.S8.waiting])
        assertEquals(["M9"], (statements.S8.findings as List<Map>)*.id)
        assertEquals([[statement: "S8", id: "M1210830", cause: null]],
                ((statements.S4.findings[0].waiting) as List<Map>).collect { it.subMap(["statement", "id", "cause"]) })
        assertEquals(2, doc.summary.open, "two broken orders, not three findings")
        assertEquals(s4[0], doc.summary.oldestOpen.id)

        ec.artifactExecution.disableAuthz()
        try {
            List<Map> all = AuditReportAssembler.findingsByOrder(ec, TRUN, "TREP_T_STORY", null, "2026-10")
            assertEquals([["S4", "SO7371002", ["S8"]], ["S8", "M9", []]],
                    all.collect { [it.statement, it.id, ((it.waiting ?: []) as List<Map>)*.statement] })
            assertEquals(["M9"], AuditReportAssembler.findingsByOrder(ec, TRUN, "TREP_T_STORY", "S8", "2026-10")*.id,
                    "one statement's list leaves out what waits on an earlier step")
        } finally {
            ec.artifactExecution.enableAuthz()
        }
        assertEquals(2, s8.size())
    }

    // DAR-OPS-029 S10, OC80 (findings are never hidden): a statement whose categoryEnumId is not an AuditCategory enum (the
    // foreign key takes any Enumeration; only the facade checks the type, so an import can bring one) vanished from the
    // report: categories and summary iterate the AuditCategory enums. It is listed under "uncategorised" instead.
    @Test
    void aStatementOutsideTheCategoriesIsListedUncategorisedNotDropped() {
        insert("moqui.security.UserGroup", [userGroupId: "TUNC", description: "Off-enum category"])
        audit("TUNC", "TUNC_AUDIT", "off-enum", "Off enum", "AUDP_MONTH")
        statement("TUNC", "TUNC_AUDIT", "TUNC_S1", "S1", "AUDSW_FIRM", "AUDSW_FIRM", 1, "off-enum S1")
        insert("darpan.audit.AuditCheck", [checkId: "TUNC_C", companyUserGroupId: "TUNC", checkCode: "off-enum-check", label: "off enum"])
        link("TUNC", "TUNC_C", "TUNC_S1", 1)
        String id = (ec.service.sync().name("${LEDGER}.record#CheckFindings").parameters([companyUserGroupId: "TUNC", checkId: "TUNC_C",
                reportDate: Date.valueOf("2026-10-08"), findings: [[statementCode: "S1", recordKey: "U-1", headline: "U-1 is off"]]]).call()
                .findingIds as List)[0]
        noErrors()

        Map doc = report("TUNC", "TUNC_AUDIT", "2026-10-08")
        Map loose = doc.categories.uncategorised as Map
        assertNotNull(loose, "listed, not dropped")
        assertEquals(["Uncategorised", counted(1, 1, 0), [id]], [loose.label, loose.counts, (loose.statements.S1.findings as List<Map>)*.id])
        assertEquals([1, counted(1, 1, 0)], [doc.summary.open, doc.summary.byCategory.uncategorised])
        assertFalse((doc.categories as Map).findAll { k, v -> k != "uncategorised" }.values().any { (it.statements as Map).containsKey("S1") })
        assertEquals([id: "uncategorised", label: "Uncategorised"], finding("TUNC", id).finding.category)
        ec.artifactExecution.disableAuthz()
        try {
            assertEquals(["uncategorised"], AuditReportAssembler.findingsByOrder(ec, "TUNC", "TUNC_AUDIT", null, "2026-10")*.category)
            Map page = AuditFindingsPage.findingsPage(ec, "TUNC", [[key: "category", comparator: "eq", values: ["uncategorised"]]], null, false, 10, null)
            assertEquals([[id, "uncategorised"]], (page.page.edges as List<Map>).collect { [it.node.id, it.node.category] })
        } finally {
            ec.artifactExecution.enableAuthz()
        }
        // A report with every statement categorised keeps its four categories only.
        assertFalse((report(GORJANA, "2026-10-08").categories as Map).containsKey("uncategorised"))
    }

    // D11 (Aditi 2026-11-28: "U1, but show the rest"; OC53). The Unexplained rows no rule of a check targets are outside the
    // rules: no statement counts them, but each check lists them, the first 200 shown and the rest counted as omitted.
    @Test
    void rowsOutsideEveryRuleAreListedPerCheckAndNeverCounted() {
        insert("moqui.security.UserGroup", [userGroupId: "TOUT", description: "Outside rules"])
        audit("TOUT", "TOUT_AUDIT", "outside", "Outside", "AUDP_MONTH")
        statement("TOUT", "TOUT_AUDIT", "TOUT_S1", "S1", "AUDC_ORDERS", "AUDSW_FIRM", 1, "outside S1")
        [A: 1, B: 2, C: 3].each { String c, int seq ->
            insert("darpan.audit.AuditCheck", [checkId: "TOUT_${c}".toString(), companyUserGroupId: "TOUT", checkCode: "outside-${c}".toString(), label: c])
            link("TOUT", "TOUT_${c}".toString(), "TOUT_S1", seq)
        }
        Closure run = { String checkId, Map outsideRules ->
            Map base = [companyUserGroupId: "TOUT", checkId: checkId, reportDate: Date.valueOf("2026-10-08")]
            ec.service.sync().name("${LEDGER}.record#CheckRun").parameters(base + [sourceReads: [read("OMS", "AUDSR_COMPLETE", 5, null)]]).call()
            ec.service.sync().name("${LEDGER}.record#CheckFindings").parameters(base + [findings: [], outsideRules: outsideRules]).call()
            noErrors()
        }
        // A: three outside the rules, two kept (as if past the cap). B: a run that had none. C: no run that morning.
        run("TOUT_A", [count: 3, rows: [
                [recordKey: "M1\u001F01", diffType: "MISSING_IN_FILE_2", bucket: "MISSING_FROM_FILE_2", note: "not in NetSuite",
                 records: [[systemEnumId: "OMS_RETURNS_GQL", recordType: "orderItem", recordId: "M1/01", recordData: '{"sku":"S-1"}']]],
                [recordKey: "M2\u001F01", diffType: "MISSING_IN_FILE_2", bucket: "MISSING_FROM_FILE_2", note: null, records: []]]])
        run("TOUT_B", [count: 0, rows: []])

        Map doc = report("TOUT", "TOUT_AUDIT", "2026-10-08")
        Map s1 = doc.categories.orders.statements.S1 as Map
        Map<String, Map> byCheck = (s1.checks as List<Map>).collectEntries { [(it.id): it] }
        assertEquals([count: 3, rows: [
                [recordKey: "M1/01", diffType: "MISSING_IN_FILE_2", bucket: "MISSING_FROM_FILE_2", note: "not in NetSuite",
                 records: [OMS: [orderItem: [[id: "M1/01", internalId: null, status: null, sku: "S-1"]]]]],
                [recordKey: "M2/01", diffType: "MISSING_IN_FILE_2", bucket: "MISSING_FROM_FILE_2", note: null, records: [:]]], omitted: 1],
                byCheck["outside-A"].outsideRules)
        assertEquals([count: 0, rows: [], omitted: 0], byCheck["outside-B"].outsideRules, "a run that had none says 0")
        assertTrue(byCheck["outside-C"].containsKey("outsideRules"), "the key is always there")
        assertNull(byCheck["outside-C"].outsideRules, "no run that morning: unknown, not 0")
        assertEquals(counted(0, 0, 0), s1.counts, "never a statement's finding")
        assertEquals([outsideRules: 3, open: 0, newToday: 0], doc.summary.subMap(["outsideRules", "open", "newToday"]))
        assertEquals([], s1.findings)
    }

    // ---- the finding ---------------------------------------------------------------------------------------------

    @Test
    void theFindingMatchesTheContractKeyForKey() {
        Map doc = finding(GORJANA, m258615)
        assertSameShape(golden("golden-finding.json"), doc, '$', null, templates(golden("golden-finding.json")))

        Map f = doc.finding
        assertEquals([id: "refundsAndCredits", label: "Refunds and credits"], f.category)
        assertEquals("2026-10", f.period)
        assertEquals([id: "R4", text: R4_TEXT, weight: "derived"], f.statement)
        assertEquals([state: "open", firstFound: "2026-10-07", lastSeen: "2026-10-09", lastChecked: "2026-10-09T06:11:00-07:00",
                      ageDays: 2, timesSeen: 3, closedAt: null, closedBy: null], f.status)
        assertEquals([amount: "160.23", currency: "USD", source: "Shopify refund transactions"], f.evidence.expected)
        assertEquals([amount: "-0.01", currency: "USD"], f.evidence.difference)
        assertEquals([label: "Memo total equals the refund", passed: false, value: "160.22 vs 160.23"], f.evidence.checks[1])
        assertEquals([conclusion: [code: "CONC_MEMO_SHORT_OF_REFUND", label: "Memo short of the refund"],
                      explanation: "NetSuite booked the memo one cent below what the customer was refunded."], f.diagnosis)
        assertEquals("HotWax Support", f.fix.owner)
        assertEquals("NetSuite", f.fix.where)
        assertEquals("Correct credit memo CM-88213 so its total is 160.23 and nothing remains open.", f.fix.action)
        assertEquals("Memo total is 160.23 and amount remaining is 0.00.", f.fix.verify.passesWhen)
        assertEquals(["found", "stillOpen", "stillOpen"], (f.history as List)*.event)
        assertEquals([type: "check", id: "returns-vs-credit-memos", label: "scheduled check, 2026-10-07T06:10-07:00"], f.history[0].by)
        assertEquals("read-only", doc.access.mode)
    }

    // DAR-BE-120 / DAR-BE-124. A cause with a fix of its own: the finding's fix is that one, its fact filled from the
    // evidence its conditions read (the rule rows are read as Moqui EntityLists, whose own find() wants a Boolean).
    // DAR-BE-125 (Aditi: "these are more sentences than headings"). The board heads findings by cause; a finding's headline is
    // written when it is seen, so a cause renamed since reads in its new words only from the catalog.
    @Test
    void aListedFindingCarriesItsCauseInTheCatalogsWords() {
        Map r4 = report(GORJANA, "2026-10-08").categories.refundsAndCredits.statements.R4 as Map
        assertEquals("Memo short of the refund", (r4.findings as List<Map>).find { it.id == m258615 }?.cause)
    }

    @Test
    void aFindingsFixIsItsCausesNamingTheFactItFound() {
        withoutForeignKeys {
            insert("darpan.rule.RuleSetCompareScope", [compareScopeId: "TREP_SCOPE", ruleSetId: "TREP_RS", companyUserGroupId: GORJANA])
            insert("darpan.rule.RuleSetConclusionRule", [compareScopeId: "TREP_SCOPE", sequenceNum: 10, companyUserGroupId: GORJANA,
                    conclusionEnumId: "CONC_MEMO_SHORT_OF_REFUND", fixActionTemplate: "Raise {creditMemo.tranId} to the refund."])
            insert("darpan.rule.RuleSetConclusionCondition", [compareScopeId: "TREP_SCOPE", sequenceNum: 10, conditionSeq: 1,
                    companyUserGroupId: GORJANA, subject: "FILE_2", presence: "ANY", keyScope: "FULL", quantifier: "ALL", operator: "NOT_BLANK",
                    fieldExpression: "creditMemo.tranId", checkLabel: "NetSuite has a credit memo for the return"])
            insert("darpan.audit.AuditCheck", [checkId: RET, companyUserGroupId: GORJANA, savedRunId: "TREP_RS"])
        }
        try {
            assertEquals("Raise CM-88213 to the refund.", finding(GORJANA, m258615).finding.fix.action)
        } finally {
            withoutForeignKeys { insert("darpan.audit.AuditCheck", [checkId: RET, companyUserGroupId: GORJANA, savedRunId: null]) }
        }
    }

    /** DAR-BE-371. A check names the systems it compares from its automation's sources, on a morning it did not run too. */
    @Test
    void aCheckNamesItsSystemsWhetherOrNotItRanFromItsWordsThenItsSources() {
        Timestamp now = ec.user.nowTimestamp
        withoutForeignKeys {
            insert("darpan.reconciliation.ReconciliationAutomation", [automationId: "TREP_AUTO_INV", automationName: "invoices",
                    companyUserGroupId: GORJANA, inputModeEnumId: "AUT_IN_API_RANGE", savedRunId: "TREP_RS", isActive: "Y",
                    createdDate: now, lastUpdatedDate: now])
            [FILE_1: "OMS_GQL", FILE_2: "NETSUITE_SUITEQL", FILE_3: "NETSUITE"].each { String side, String system ->
                insert("darpan.reconciliation.ReconciliationAutomationSource", [automationId: "TREP_AUTO_INV", fileSide: side,
                        companyUserGroupId: GORJANA, sourceTypeEnumId: "AUT_SRC_API", systemEnumId: system, createdDate: now,
                        lastUpdatedDate: now])
            }
            insert("darpan.audit.AuditCheck", [checkId: INV, companyUserGroupId: GORJANA, automationId: "TREP_AUTO_INV"])
        }
        try {
            Map doc = report(GORJANA, "2026-10-09")
            Map r2 = doc.categories.orders.statements.R2
            assertEquals("notRun", r2.status)
            assertNull(r2.checks[0].compared, "no reads that morning")
            // DAR-BE-372: the check's own words name its systems first, over what its automation reads.
            assertEquals(["NetSuite"], r2.checks[0].systems, "its label names NetSuite alone")
            assertEquals(["OMS", "NetSuite"], doc.categories.refundsAndCredits.statements.R4.checks[0].systems)
            // A label and statement that name no system: the automation's sources, FILE_1 then FILE_2, each system once.
            withoutForeignKeys { insert("darpan.audit.AuditCheck", [checkId: INV, companyUserGroupId: GORJANA, label: "Invoices with no Etail id"]) }
            withoutForeignKeys { insert("darpan.audit.AuditStatement", [statementId: "TREP_R2", statementText: "Every invoice carries the Etail id."]) }
            assertEquals(["OMS", "NetSuite"], report(GORJANA, "2026-10-09").categories.orders.statements.R2.checks[0].systems)
        } finally {
            withoutForeignKeys {
                insert("darpan.audit.AuditStatement", [statementId: "TREP_R2", statementText: "Every invoice and credit memo carries the Shopify order id (the Etail id)."])
                insert("darpan.audit.AuditCheck", [checkId: INV, companyUserGroupId: GORJANA, automationId: null,
                        label: "NetSuite invoices and memos with no Etail id"])
                ec.entity.find("darpan.reconciliation.ReconciliationAutomationSource").condition("automationId", "TREP_AUTO_INV")
                        .disableAuthz().deleteAll()
                ec.entity.find("darpan.reconciliation.ReconciliationAutomation").condition("automationId", "TREP_AUTO_INV")
                        .disableAuthz().deleteAll()
            }
        }
    }

    private void withoutForeignKeys(Closure work) {
        java.sql.Connection c = ec.entity.getConnection(ec.entity.getEntityGroupName("darpan.audit.AuditCheck"))
        try {
            c.createStatement().execute("SET REFERENTIAL_INTEGRITY FALSE")
            work.call()
        } finally {
            c.createStatement().execute("SET REFERENTIAL_INTEGRITY TRUE")
            c.close()
        }
    }

    @Test
    void aFixedFindingSaysWhenAndByWhat() {
        Map s = finding(GORJANA, rx1).finding.status
        assertEquals(["fixed", "2026-10-08T06:12:40-07:00", 1], [s.state, s.closedAt, s.ageDays])
        assertEquals([type: "check", id: "returns-vs-credit-memos", label: "scheduled check, 2026-10-08T06:12-07:00"], s.closedBy)
        assertEquals(["found", "fixed"], (finding(GORJANA, rx1).finding.history as List)*.event)
    }

    @Test
    void twoAuditsOfOneTenantStayApart() {
        Map month = report(GORJANA, MONTH_CLOSE, "2026-10-08")
        assertFalse(JsonOutput.toJson(month).contains("D1"), "the month-close report names no daily-ops statement")
        assertFalse(JsonOutput.toJson(month).contains(opsFinding), "nor its finding")
        assertEquals(true, month.health.complete, "a daily-ops check that never ran does not touch month-close health")

        Map ops = report(GORJANA, DAILY_OPS, "2026-10-08")
        assertEquals([id: "daily-ops", name: "Daily ops", period: "day"], ops.report.audit)
        assertEquals("2026-10-08", ops.report.period, "a daily audit's period is its day")
        assertEquals([from: "2026-10-08", thru: "2026-10-08"], ops.report.window)
        Map d1 = ops.categories.orders.statements.D1
        assertEquals([opsFinding], d1.findings*.id)
        assertEquals("notRun", d1.status)
        assertEquals([ran: 0, partial: 0, notRun: 1], ops.health.checks)
        assertEquals(["D1"], (ops.categories as Map).values().collectMany { (it.statements as Map).keySet() as List })
        assertEquals([id: "daily-ops", name: "Daily ops"], finding(GORJANA, opsFinding).finding.audit)
    }

    // ---- the window (DAR-BE-091) ------------------------------------------------------------------------------------

    @Test
    void aMonthlyAuditReconcilesItsMonthToDate() {
        assertEquals(["2026-10-01", "2026-10-14", "2026-10"], window(GORJANA, MONTH_CLOSE, "2026-10-14"), "on the 14th, 1-14")
        assertEquals(["2026-10-01", "2026-10-15", "2026-10"], window(GORJANA, MONTH_CLOSE, "2026-10-15"), "on the 15th, 1-15")
        assertEquals(["2026-11-01", "2026-11-01", "2026-11"], window(GORJANA, MONTH_CLOSE, "2026-11-01"), "the 1st starts a new month")
        assertEquals(["2026-10-14", "2026-10-14", "2026-10-14"], window(GORJANA, DAILY_OPS, "2026-10-14"), "a daily audit reads its day")
        ec.service.sync().name("${REPORTS}.get#AuditWindow").parameters([companyUserGroupId: KREWE, auditId: MONTH_CLOSE,
                reportDate: Date.valueOf("2026-10-14")]).call()
        assertTrue(ec.message.errorsString.contains("was not found for tenant KREWE"), ec.message.errorsString)
        ec.message.clearErrors()
    }

    @Test
    void aQuarterStartsOnItsFirstMonth() {
        assertEquals(LocalDate.of(2026, 10, 1), AuditPeriods.periodStart(LocalDate.of(2026, 11, 14), "quarter"))
        assertEquals(LocalDate.of(2026, 1, 1), AuditPeriods.periodStart(LocalDate.of(2026, 3, 31), "quarter"))
        assertEquals(LocalDate.of(2026, 4, 1), AuditPeriods.periodStart(LocalDate.of(2026, 4, 1), "quarter"))
        assertEquals("2026-Q4", AuditPeriods.periodOf(LocalDate.of(2026, 11, 14), "quarter"))
        assertEquals("2026-Q1", AuditPeriods.periodOf(LocalDate.of(2026, 3, 31), "quarter"))
    }

    @Test
    void aCheckRunKeepsTheWindowItRead() {
        Map run = ec.entity.find("darpan.audit.AuditCheckRun").condition("checkId", RET)
                .condition("reportDate", Date.valueOf("2026-10-08")).useCache(false).one().getMap()
        assertEquals([Date.valueOf("2026-10-01"), Date.valueOf("2026-10-08")], [run.windowFromDate, run.windowThruDate],
                "blank window = the audit's month to the report day")
    }

    @Test
    void aSourceReadKeepsWhatItReadAndWhatItCost() {
        // DAR-BE-320 phase 1b: the audit's read report needs the connection, the window and the extract's cost.
        Map stats = read("SHOPIFY", "AUDSR_COMPLETE", 812, null) + [sourceConfigId: "SHOP_US",
                windowFromDate: java.sql.Timestamp.valueOf("2026-11-12 08:00:00"), windowThruDate: java.sql.Timestamp.valueOf("2026-11-16 08:00:00"),
                callsJson: '{"graphql":14,"bulkStarts":1,"bulkPolls":12,"downloads":1}',
                lookupJson: '{"candidates":3,"checkedAgainst":790,"maxSharePercent":5,"lookedUp":3,"found":2}']
        Map ok = ec.service.sync().name("${LEDGER}.record#CheckRun").parameters([companyUserGroupId: TRUN, checkId: "TREP_T",
                reportDate: Date.valueOf("2026-11-15"), period: "2026-11", sourceReads: [stats]]).call()
        noErrors()
        Map row = ec.entity.find("darpan.audit.AuditSourceRead").condition("checkRunId", ok.checkRunId).useCache(false).one().getMap()
        assertEquals(["SHOP_US", 812L, '{"graphql":14,"bulkStarts":1,"bulkPolls":12,"downloads":1}'],
                [row.sourceConfigId, (row.recordCount as Number).longValue(), row.callsJson])
        assertEquals(java.sql.Timestamp.valueOf("2026-11-12 08:00:00"), row.windowFromDate)
        assertEquals(java.sql.Timestamp.valueOf("2026-11-16 08:00:00"), row.windowThruDate)
        assertTrue((row.lookupJson as String).contains('"found":2'))
    }

    @Test
    void aPlaysReadReportGroupsItsReadsAndFlagsTheOnesThatCostMore() {
        // DAR-BE-320 phase 1b: two checks of one audit read the same store on two mornings; the second morning one of
        // them reads from the period's first day again.
        audit(TRUN, "TREP_RR_AUDIT", "read-report", "Read report", "AUDP_MONTH")
        statement(TRUN, "TREP_RR_AUDIT", "TREP_RR_S1", "S1", "AUDC_ORDERS", "AUDSW_FIRM", 1, "read report S1")
        insert("darpan.audit.AuditCheck", [checkId: "TREP_RR1", companyUserGroupId: TRUN, checkCode: "rr-orders", label: "orders"])
        insert("darpan.audit.AuditCheck", [checkId: "TREP_RR2", companyUserGroupId: TRUN, checkCode: "rr-loop", label: "loop"])
        link(TRUN, "TREP_RR1", "TREP_RR_S1", 1)
        link(TRUN, "TREP_RR2", "TREP_RR_S1", 2)
        Closure<Map> shop = { String from, long calls ->
            read("SHOPIFY", "AUDSR_COMPLETE", 500, null) + [sourceConfigId: "SHOP_RR",
                    windowFromDate: java.sql.Timestamp.valueOf("${from} 12:00:00"), windowThruDate: java.sql.Timestamp.valueOf("2026-11-11 12:00:00"),
                    callsJson: "{\"graphql\":${calls},\"bulkStarts\":1}".toString()]
        }
        ["2026-11-10": "2026-11-01", "2026-11-11": "2026-11-07"].each { String day, String from ->
            ec.service.sync().name("${LEDGER}.record#CheckRun").parameters([companyUserGroupId: TRUN, checkId: "TREP_RR1",
                    reportDate: Date.valueOf(day), period: "2026-11", sourceReads: [shop(from, 10L)]]).call()
            ec.service.sync().name("${LEDGER}.record#CheckRun").parameters([companyUserGroupId: TRUN, checkId: "TREP_RR2",
                    reportDate: Date.valueOf(day), period: "2026-11", sourceReads: [shop("2026-11-01", 12L)]]).call()
            noErrors()
        }

        Map report = AuditReadReport.forPlay(ec, TRUN, "TREP_RR_AUDIT", null, null,
                [wholePeriodGraceDays: 4, lookupEveryPlayRuns: 3, throttledMax: 0, callsGrewPercent: 50, callsGrewMinCalls: 50])
        assertEquals(["2026-11-11", "2026-11"], [report.reportDate, report.period], "blank date: the latest play")
        assertEquals([reads: 2, connections: 1, records: 1000L, calls: [graphql: 22L, bulkStarts: 2L]], report.totals)
        assertEquals([["DUPLICATE_READ", ["rr-orders", "rr-loop"]], ["WHOLE_PERIOD_READ", ["rr-loop"]]],
                (report.flags as List<Map>).collect { [it.flag, it.checks] })

        Map first = AuditReadReport.forPlay(ec, TRUN, "TREP_RR_AUDIT", Date.valueOf("2026-11-10"), "2026-11",
                [wholePeriodGraceDays: 4, lookupEveryPlayRuns: 3, throttledMax: 0, callsGrewPercent: 50, callsGrewMinCalls: 50])
        assertEquals(["DUPLICATE_READ"], (first.flags as List<Map>)*.flag, "the period's first play may read it all")
        assertNull(AuditReadReport.forPlay(ec, KREWE, "TREP_RR_AUDIT", null, null), "another tenant sees no such audit")
    }

    @Test
    void aCheckServingAuditsOfTwoPeriodsMustBeGivenItsWindow() {
        ec.service.sync().name("${LEDGER}.record#CheckRun").parameters([companyUserGroupId: TRUN, checkId: "TREP_T2",
                reportDate: Date.valueOf("2026-11-14"), sourceReads: [read("OMS", "AUDSR_COMPLETE", 1, null)]]).call()
        assertTrue(ec.message.errorsString.contains("serves audits of 2 kinds of period; pass the period"), ec.message.errorsString)
        ec.message.clearErrors()
        Map ok = ec.service.sync().name("${LEDGER}.record#CheckRun").parameters([companyUserGroupId: TRUN, checkId: "TREP_T2",
                reportDate: Date.valueOf("2026-11-14"), period: "2026-11",
                sourceReads: [read("OMS", "AUDSR_COMPLETE", 1, null)]]).call()
        noErrors()
        assertEquals(["AUDCS_RAN", "2026-11"], [ok.checkStatusEnumId, ok.period])
        Map stored = ec.entity.find("darpan.audit.AuditCheckRun").condition("checkRunId", ok.checkRunId).useCache(false).one().getMap()
        assertEquals([Date.valueOf("2026-11-01"), Date.valueOf("2026-11-14")], [stored.windowFromDate, stored.windowThruDate])
        ec.service.sync().name("${LEDGER}.record#CheckRun").parameters([companyUserGroupId: TRUN, checkId: "TREP_T",
                reportDate: Date.valueOf("2026-11-14"), windowFromDate: Date.valueOf("2026-11-20"), sourceReads: []]).call()
        assertTrue(ec.message.errorsString.contains("runs backwards"), ec.message.errorsString)
        ec.message.clearErrors()
    }

    @Test
    void anEndedPeriodStopsAndKeepsWhatItHad() {
        // An October finding first seen on Oct 20 and still open when October ends.
        checkRunFor(RET, "2026-10-20", null, [read("OMS_RETURNS_GQL", "AUDSR_COMPLETE", 5, "06:01:00")])
        String ext1 = (recordFor(RET, "2026-10-20", null, [[statementCode: "R4", recordKey: "EXT-1", headline: "Late October memo"]])
                .findingIds as List)[0]

        // Dec 1: October is two months gone, its run is refused (DAR-BE-178: only the period just ended still records).
        ec.service.sync().name("${LEDGER}.record#CheckRun").parameters([companyUserGroupId: GORJANA, checkId: RET,
                reportDate: Date.valueOf("2026-12-01"), period: "2026-10", sourceReads: []]).call()
        assertTrue(ec.message.errorsString.contains("Period 2026-10 ended on 2026-10-31"), ec.message.errorsString)
        ec.message.clearErrors()
        // Nov 3: the morning owes November alone; October records only when played.
        assertEquals([[period: "2026-11", from: "2026-11-01", thru: "2026-11-03"]], runsOn("2026-11-03"))

        Map november = report(GORJANA, MONTH_CLOSE, "2026-11-03", null)
        assertEquals("2026-11", november.report.period)
        assertEquals(counted(0, 0, 0), november.categories.refundsAndCredits.statements.R4.counts,
                "October's findings are October's report's, not November's")
        assertEquals("The check did not run on the morning of 2026-11-03.", november.categories.refundsAndCredits.statements.R4.checks[0].reason)

        Map october = report(GORJANA, MONTH_CLOSE, "2026-11-03", "2026-10")
        assertEquals([period: "2026-10", window: [from: "2026-10-01", thru: "2026-10-31"], running: false],
                october.report.subMap(["period", "window", "running"]))
        assertEquals("The 2026-10 audit ended on 2026-10-31.", october.categories.refundsAndCredits.statements.R4.checks[0].reason)
        assertEquals("2026-10", finding(GORJANA, ext1).finding.period)
    }

    // DAR-BE-178: SM on Nov 2 plays October 1-31. The month just ended records under its own period, over its whole span,
    // and its report shows the run; November's report does not take it.
    @Test
    void theMonthJustEndedStillRecordsWhenPlayed() {
        Map ok = checkRunFor(RET, "2026-11-02", "2026-10", [read("OMS_RETURNS_GQL", "AUDSR_COMPLETE", 7, "01:00:00")])
        noErrors()
        assertEquals(["AUDCS_RAN", "2026-10"], [ok.checkStatusEnumId, ok.period])
        Map stored = ec.entity.find("darpan.audit.AuditCheckRun").condition("checkRunId", ok.checkRunId).useCache(false).one().getMap()
        assertEquals([Date.valueOf("2026-10-01"), Date.valueOf("2026-10-31")], [stored.windowFromDate, stored.windowThruDate])

        Map october = report(GORJANA, MONTH_CLOSE, "2026-11-02", "2026-10")
        Map octoberCheck = october.categories.refundsAndCredits.statements.R4.checks[0] as Map
        assertEquals([null, [from: "2026-10-01", thru: "2026-10-31"]], [octoberCheck.reason, octoberCheck.window],
                "October's report shows the played run over the whole month")
        Map november = report(GORJANA, MONTH_CLOSE, "2026-11-02", null)
        assertEquals("The check did not run on the morning of 2026-11-02.", november.categories.refundsAndCredits.statements.R4.checks[0].reason)
    }

    @Test
    void aReportIsOfAPeriodThatHasStarted() {
        ec.service.sync().name("${REPORTS}.assemble#AuditReport").parameters([companyUserGroupId: GORJANA, auditId: MONTH_CLOSE,
                reportDate: Date.valueOf("2026-10-08"), period: "2026-12"]).call()
        assertTrue(ec.message.errorsString.contains("2026-12 is not a month period of audit month-close that has started by 2026-10-08"),
                ec.message.errorsString)
        ec.message.clearErrors()
    }

    // ---- tenant ---------------------------------------------------------------------------------------------------

    @Test
    void eachDocumentShowsOneTenantOnly() {
        Map krewe = report(KREWE, "TREP_K_AUDIT", "2026-10-08")
        assertEquals(["R4"], (krewe.categories as Map).values().collectMany { (it.statements as Map).keySet() as List })
        assertEquals([kreweFinding], krewe.categories.refundsAndCredits.statements.R4.findings*.id)
        assertEquals("UTC", krewe.report.tenant.timeZone, "no tenant setting reads as UTC")
        assertFalse(JsonOutput.toJson(krewe).contains("M258615"))

        ec.service.sync().name("${REPORTS}.assemble#AuditFinding").parameters([companyUserGroupId: KREWE, findingId: m258615]).call()
        assertTrue(ec.message.errorsString.contains("Finding ${m258615} was not found"), ec.message.errorsString)
        ec.message.clearErrors()
        ec.service.sync().name("${REPORTS}.assemble#AuditReport").parameters([companyUserGroupId: "NO_SUCH_TENANT",
                auditId: MONTH_CLOSE, reportDate: Date.valueOf("2026-10-08")]).call()
        assertTrue(ec.message.errorsString.contains("Audit ${MONTH_CLOSE} was not found for tenant NO_SUCH_TENANT"), ec.message.errorsString)
        ec.message.clearErrors()
        ec.service.sync().name("${REPORTS}.assemble#AuditReport").parameters([companyUserGroupId: KREWE,
                auditId: MONTH_CLOSE, reportDate: Date.valueOf("2026-10-08")]).call()
        assertTrue(ec.message.errorsString.contains("Audit ${MONTH_CLOSE} was not found for tenant KREWE"), "another tenant's audit reads as not found")
        ec.message.clearErrors()
    }

    // ---- record#CheckRun (tenant TRUN, so the scenario's health never moves) ----------------------------------------

    @Test
    void aCheckRunTakesItsStatusFromItsReads() {
        Map ran = checkRun(TRUN, "TREP_T", "2026-11-01", "06:00:00", [read("OMS", "AUDSR_COMPLETE", 5, "06:00:00")])
        assertEquals(["AUDCS_RAN", null], [ran.checkStatusEnumId, ran.reason])

        Map partial = checkRun(TRUN, "TREP_T", "2026-11-02", "06:00:00", [read("OMS_RETURNS_GQL", "AUDSR_FAILED", null, null, "HTTP 504 after retries"),
                read("SHOPIFY", "AUDSR_PARTIAL", 10, "06:00:00", "past the 60-day window")])
        assertEquals("AUDCS_PARTIAL", partial.checkStatusEnumId)
        assertEquals("The OMS read failed: HTTP 504 after retries. The Shopify read was partial: past the 60-day window.", partial.reason)
        List reads = ec.entity.find("darpan.audit.AuditSourceRead").condition("checkRunId", partial.checkRunId)
                .orderBy("readSeqId").useCache(false).list()
        assertEquals([["OMS", "OMS_RETURNS_GQL"], ["SHOPIFY", "SHOPIFY"]], reads.collect { [it.systemEnumId, it.endpointEnumId] },
                "root system and endpoint both kept")

        assertEquals(["AUDCS_NOT_RUN", "The check recorded no source read."],
                checkRun(TRUN, "TREP_T", "2026-11-03", "06:00:00", []).subMap(["checkStatusEnumId", "reason"]).values() as List)
        Map notRun = ec.service.sync().name("${LEDGER}.record#CheckRun").parameters([companyUserGroupId: TRUN, checkId: "TREP_T",
                reportDate: Date.valueOf("2026-11-04"), notRunReason: "No saved run is configured for this check.",
                sourceReads: [read("OMS", "AUDSR_COMPLETE", 5, null)]]).call()
        noErrors()
        assertEquals(["AUDCS_NOT_RUN", "No saved run is configured for this check."], [notRun.checkStatusEnumId, notRun.reason])
    }

    @Test
    void aSecondRunTheSameMorningReplacesTheFirst() {
        Map first = checkRun(TRUN, "TREP_T", "2026-11-10", "06:00:00", [read("OMS", "AUDSR_PARTIAL", 1, "06:00:00", "x"),
                read("NETSUITE", "AUDSR_COMPLETE", 2, "06:00:00")])
        Map second = checkRun(TRUN, "TREP_T", "2026-11-10", "07:00:00", [read("OMS", "AUDSR_COMPLETE", 3, "07:00:00")])
        assertEquals(first.checkRunId, second.checkRunId)
        assertEquals(1L, ec.entity.find("darpan.audit.AuditCheckRun").condition("checkId", "TREP_T")
                .condition("reportDate", Date.valueOf("2026-11-10")).count())
        assertEquals([3L], ec.entity.find("darpan.audit.AuditSourceRead").condition("checkRunId", second.checkRunId)
                .useCache(false).list()*.recordCount.collect { it as Long })
        assertEquals("AUDCS_RAN", second.checkStatusEnumId)
    }

    @Test
    void aBadReadOrAnotherTenantsCheckWritesNothing() {
        ec.service.sync().name("${LEDGER}.record#CheckRun").parameters([companyUserGroupId: TRUN, checkId: "TREP_T",
                reportDate: Date.valueOf("2026-11-20"), sourceReads: [read("OMS", "COMPLETE", 1, "06:00:00")]]).call()
        assertTrue(ec.message.errorsString.contains("is not an AuditSourceRead status"), ec.message.errorsString)
        ec.message.clearErrors()
        ec.service.sync().name("${LEDGER}.record#CheckRun").parameters([companyUserGroupId: TRUN, checkId: "TREP_T",
                reportDate: Date.valueOf("2026-11-20"), sourceReads: [read("NOT_A_SYSTEM", "AUDSR_COMPLETE", 1, "06:00:00")]]).call()
        assertTrue(ec.message.errorsString.contains("Unknown system NOT_A_SYSTEM"), ec.message.errorsString)
        ec.message.clearErrors()
        ec.service.sync().name("${LEDGER}.record#CheckRun").parameters([companyUserGroupId: TRUN, checkId: RET,
                reportDate: Date.valueOf("2026-11-20"), sourceReads: []]).call()
        assertTrue(ec.message.errorsString.contains("does not belong to tenant TRUN"), ec.message.errorsString)
        ec.message.clearErrors()
        assertEquals(0L, ec.entity.find("darpan.audit.AuditCheckRun").condition("reportDate", Date.valueOf("2026-11-20")).count())
    }

    // ---- pure helpers ---------------------------------------------------------------------------------------------

    @Test
    void moneyAndTemplatesFollowTheConventions() {
        assertEquals([amount: "-0.01", currency: "USD"], AuditRecordFacts.money(new BigDecimal("-0.010000"), "USD"))
        assertEquals([amount: "1500", currency: "JPY"], AuditRecordFacts.money(new BigDecimal("1500.00"), "JPY"))
        assertNull(AuditRecordFacts.money(null, "USD"))
        assertEquals('Fix CM-1 by {unknown}; ${x} stays text',
                AuditFindingDocument.fillTemplate('Fix {creditMemo} by {unknown}; ${x} stays text', [creditMemo: "CM-1"]))
        // DAR-BE-093: a composite compare key (order id + item seq id) is joined by an invisible unit separator; people
        // and agents read it as order/item.
        assertEquals("M1137118/01", AuditRecordFacts.displayKey("M1137118\u001F01"))
        assertEquals("INV1", AuditRecordFacts.displayKey("INV1"))
        assertNull(AuditRecordFacts.displayKey(null))
        assertEquals("NetSuite", AuditRecordFacts.systemKey("NETSUITE"))
        assertEquals("AMAZON", AuditRecordFacts.systemKey("AMAZON"), "an unmapped root keeps its enumId")
    }

    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Same keys at every level as the golden document, and the same JSON type wherever both sides are non-null.
     * Under a keyed object (categories, statements, sources, records, compared, byCategory) the member names are data,
     * so every member is compared with that key's first golden member; records.<System> is keyed by record type.
     */
    private void assertSameShape(Object golden, Object actual, String path, String keyedName, Map<String, Object> templates) {
        if (golden == null || actual == null) return
        if (golden instanceof Map) {
            assertTrue(actual instanceof Map, "${path} is an object")
            if (keyedName != null) {
                Object template = templates[keyedName]
                if (template == null) return
                (actual as Map).each { k, v -> assertSameShape(template, v, "${path}.${k}", keyedName == "records" ? "recordTypes" : null, templates) }
                return
            }
            assertEquals((golden as Map).keySet(), (actual as Map).keySet(), "keys at ${path}")
            (golden as Map).each { k, v -> assertSameShape(v, actual[k], "${path}.${k}", KEYED.contains(k) ? k as String : null, templates) }
        } else if (golden instanceof List) {
            assertTrue(actual instanceof List, "${path} is an array")
            if (golden && actual) (actual as List).each { assertSameShape((golden as List)[0], it, "${path}[]", null, templates) }
        } else {
            assertEquals(kind(golden), kind(actual), "JSON type at ${path}")
        }
    }

    /** The first non-empty golden member under each keyed name, and under records.<System> ("recordTypes"). */
    private static Map<String, Object> templates(Object golden) {
        Map<String, Object> out = [:]
        Closure walk
        walk = { Object node ->
            if (node instanceof Map) {
                (node as Map).each { k, v ->
                    if (KEYED.contains(k) && v instanceof Map && v) {
                        out.putIfAbsent(k as String, (v as Map).values().first())
                        if (k == "records") out.putIfAbsent("recordTypes", ((v as Map).values().first() as Map).values().first())
                    }
                    walk(v)
                }
            } else if (node instanceof List) (node as List).each { walk(it) }
        }
        walk(golden)
        return out
    }

    private static String kind(Object v) {
        if (v instanceof Map) return "object"
        if (v instanceof List) return "array"
        if (v instanceof Number) return "number"
        if (v instanceof Boolean) return "boolean"
        return "string"
    }

    private static Object golden(String name) {
        return new JsonSlurper().parse(AuditReportAssemblerSmokeTests.class.getResource("/audit/${name}"))
    }

    /** The document as a consumer sees it: serialized to JSON and read back. */
    private Map report(String tenant, String day) {
        return report(tenant, MONTH_CLOSE, day)
    }

    private Map report(String tenant, String auditId, String day) {
        return report(tenant, auditId, day, null)
    }

    private Map report(String tenant, String auditId, String day, String period) {
        Map out = ec.service.sync().name("${REPORTS}.assemble#AuditReport").parameters([companyUserGroupId: tenant, auditId: auditId, period: period,
                reportDate: Date.valueOf(day), generatedStamp: at(day, "06:12:40")]).call()
        noErrors()
        return new JsonSlurper().parseText(JsonOutput.toJson(out.document)) as Map
    }

    private List<Map> runsOn(String day) {
        Map out = ec.service.sync().name("${REPORTS}.get#AuditWindow").parameters([companyUserGroupId: GORJANA, auditId: MONTH_CLOSE,
                reportDate: Date.valueOf(day)]).call()
        noErrors()
        return (out.runs as List<Map>).collect { [period: it.period, from: it.windowFromDate?.toString(), thru: it.windowThruDate?.toString()] }
    }

    private Map checkRunFor(String checkId, String day, String period, List reads) {
        Map out = ec.service.sync().name("${LEDGER}.record#CheckRun").parameters([companyUserGroupId: GORJANA, checkId: checkId,
                reportDate: Date.valueOf(day), period: period, runStamp: at(day, "06:10:00"), sourceReads: reads.collect { Map r ->
                    r.findAll { it.key != "time" } + [readStamp: r.time ? at(day, r.time as String) : null] }]).call()
        noErrors()
        return out
    }

    private Map recordFor(String checkId, String day, String period, List findings) {
        Map out = ec.service.sync().name("${LEDGER}.record#CheckFindings").parameters([companyUserGroupId: GORJANA, checkId: checkId,
                reportDate: Date.valueOf(day), period: period, checkStamp: at(day, "06:10:00"), findings: findings]).call()
        noErrors()
        return out
    }

    private List<String> window(String tenant, String auditId, String day) {
        Map out = ec.service.sync().name("${REPORTS}.get#AuditWindow").parameters([companyUserGroupId: tenant, auditId: auditId,
                reportDate: Date.valueOf(day)]).call()
        noErrors()
        return [out.windowFromDate?.toString(), out.windowThruDate?.toString(), out.period as String]
    }

    private Map finding(String tenant, String findingId) {
        Map out = ec.service.sync().name("${REPORTS}.assemble#AuditFinding").parameters([companyUserGroupId: tenant, findingId: findingId]).call()
        noErrors()
        return new JsonSlurper().parseText(JsonOutput.toJson(out.document)) as Map
    }

    private Map checkRun(String tenant, String checkId, String day, String time, List reads) {
        Map out = ec.service.sync().name("${LEDGER}.record#CheckRun").parameters([companyUserGroupId: tenant, checkId: checkId,
                reportDate: Date.valueOf(day), runStamp: at(day, time), sourceReads: reads.collect { Map r ->
                    r.findAll { it.key != "time" } + [readStamp: r.time ? at(day, r.time as String) : null] }]).call()
        noErrors()
        return out
    }

    private static Map read(String system, String status, Integer count, String time, String reason = null) {
        return [systemEnumId: system, statusEnumId: status, recordCount: count, time: time, reason: reason]
    }

    private Map record(String checkId, String day, String time, List findings) {
        Map out = ec.service.sync().name("${LEDGER}.record#CheckFindings").parameters([companyUserGroupId: GORJANA, checkId: checkId,
                reportDate: Date.valueOf(day), checkStamp: at(day, time), findings: findings]).call()
        noErrors()
        return out
    }

    private Map close(String checkId, String day, String time, String status, List<String> examined) {
        Map out = ec.service.sync().name("${LEDGER}.close#ResolvedFindings").parameters([companyUserGroupId: GORJANA, checkId: checkId,
                reportDate: Date.valueOf(day), checkStamp: at(day, time), checkStatusEnumId: status, examinedRecordKeys: examined]).call()
        noErrors()
        return out
    }

    /** A wall-clock time on a tenant day in Los Angeles, as the instant a check would stamp. */
    private static Timestamp at(String day, String time) {
        return Timestamp.from(LocalDateTime.parse("${day}T${time}").atZone(LA).toInstant())
    }

    private void noErrors() {
        assertFalse(ec.message.hasError(), ec.message.errorsString)
    }

    private void audit(String tenant, String id, String code, String name, String periodEnumId) {
        insert("darpan.audit.Audit", [auditId: id, companyUserGroupId: tenant, auditCode: code, auditName: name, periodEnumId: periodEnumId])
    }

    private void statement(String tenant, String auditId, String id, String code, String category, String weight, int seq, String text) {
        insert("darpan.audit.AuditStatement", [statementId: id, companyUserGroupId: tenant, auditId: auditId, statementCode: code,
                categoryEnumId: category, weightEnumId: weight, sequenceNum: seq as Long, statementText: text])
    }

    private void link(String tenant, String checkId, String statementId, int seq) {
        insert("darpan.audit.AuditStatementCheck", [checkId: checkId, statementId: statementId, companyUserGroupId: tenant, sequenceNum: seq as Long])
    }

    private void insert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }

    /** Counts with no level set anywhere: every open finding is P2 (DAR-BE-348). */
    private static Map counted(int open, int newToday, int fixedToday) {
        return [open: open, acknowledged: 0, newToday: newToday, fixedToday: fixedToday, byPriority: [P0: 0, P1: 0, P2: open, KNOWN: 0]]
    }

    // ---- priority (DAR-BE-348) -----------------------------------------------------------------------------------
    // Each test has an audit of its own in tenant TPRI (statement S1, check <name>-check), found on 2026-10-22.

    private static final String PRI = "TPRI"
    private static final String PRI_B = "TPRI_B"
    private static final String C1 = "TPRI_C1"
    private static final String C2 = "TPRI_C2"

    private void priorityTenant(String tenant) {
        insert("moqui.security.UserGroup", [userGroupId: tenant, description: tenant])
        insert("moqui.basic.Enumeration", [enumId: C1, enumTypeId: "DarpanConclusion", enumCode: C1, description: "Known carrier delay"])
        insert("moqui.basic.Enumeration", [enumId: C2, enumTypeId: "DarpanConclusion", enumCode: C2, description: "Short shipped"])
    }

    /** Audit <tenant>_<name> with statement S1 at this level (null: unset); answers S1's statementId. */
    private String priorityAudit(String tenant, String name, String priorityEnumId) {
        priorityTenant(tenant)
        String auditId = "${tenant}_${name}".toString()
        audit(tenant, auditId, name, name, "AUDP_MONTH")
        statement(tenant, auditId, "${auditId}_S1".toString(), "S1", "AUDC_ORDERS", "AUDSW_FIRM", 1, "${name} S1".toString())
        if (priorityEnumId) insert("darpan.audit.AuditStatement", [statementId: "${auditId}_S1".toString(), priorityEnumId: priorityEnumId])
        insert("darpan.audit.AuditCheck", [checkId: "${auditId}_C".toString(), companyUserGroupId: tenant, checkCode: "${name}-check".toString(), label: name])
        link(tenant, "${auditId}_C".toString(), "${auditId}_S1".toString(), 1)
        return "${auditId}_S1".toString()
    }

    private List<String> priorityFindings(String tenant, String checkId, List findings) {
        List ids = ec.service.sync().name("${LEDGER}.record#CheckFindings").parameters([companyUserGroupId: tenant, checkId: checkId,
                reportDate: Date.valueOf("2026-10-22"), checkStamp: at("2026-10-22", "07:00:00"), findings: findings]).call().findingIds as List
        noErrors()
        return ids as List<String>
    }

    private static Map lone(String key, String conclusionEnumId, String statementCode = "S1") {
        return [statementCode: statementCode, recordKey: key, headline: "Invoice ${key} has no Etail id".toString(), conclusionEnumId: conclusionEnumId,
                records: [[systemEnumId: "NETSUITE_SUITEQL", recordType: "invoice", recordId: key]]]
    }

    private void reasonPriority(String tenant, String statementId, String conclusionEnumId, String priorityEnumId) {
        insert("darpan.audit.AuditReasonPriority", [reasonPriorityId: "${tenant}_${statementId}_${conclusionEnumId}".toString().take(40),
                companyUserGroupId: tenant, statementId: statementId, conclusionEnumId: conclusionEnumId, priorityEnumId: priorityEnumId])
    }

    private Map priorityStatement(String tenant, String auditId) {
        return report(tenant, auditId, "2026-10-22").categories.orders.statements.S1 as Map
    }

    private static List<List> marks(List<Map> findings) {
        return findings.collect { [it.id, it.priority, it.prioritySource] }
    }

    @Test
    void statementPriorityDefaultsToP2() {
        Map r4 = report(GORJANA, "2026-10-08").categories.refundsAndCredits.statements.R4 as Map
        assertEquals("P2", r4.priority)
        assertEquals([P0: 0, P1: 0, P2: 1, KNOWN: 0], r4.counts.byPriority)
        assertEquals([[m258615, "P2", "statement"]], marks(r4.findings as List<Map>))

        // A level that is not an AuditPriority enum reads as unset, statement and reason alike: P2, and it still adds up.
        String st = priorityAudit(PRI, "pri-stray", "AUDC_ORDERS")
        reasonPriority(PRI, st, C1, "AUDSW_FIRM")
        priorityFindings(PRI, "${PRI}_pri-stray_C", [lone("PX-1", C1)])
        Map s1 = priorityStatement(PRI, "${PRI}_pri-stray")
        assertEquals("P2", s1.priority)
        assertEquals([P0: 0, P1: 0, P2: 1, KNOWN: 0], s1.counts.byPriority)
        assertEquals(["P2", "statement"], [s1.findings[0].priority, s1.findings[0].prioritySource])
        assertNull(s1.findings[0].priorityReason, "a statement-level unit has no reason")
    }

    @Test
    void reasonOverrideMovesItsFindings() {
        String st = priorityAudit(PRI, "pri-reason", "AUDP_P2")
        List<String> ids = priorityFindings(PRI, "${PRI}_pri-reason_C", [lone("PR-1", C1), lone("PR-2", C2), lone("PR-3", null)])
        reasonPriority(PRI, st, C1, "AUDP_KNOWN")
        Map s1 = priorityStatement(PRI, "${PRI}_pri-reason")
        assertEquals("P2", s1.priority)
        assertEquals([[ids[0], "KNOWN", "reason"], [ids[1], "P2", "statement"], [ids[2], "P2", "statement"]] as Set,
                marks(s1.findings as List<Map>) as Set)
        assertEquals([P0: 0, P1: 0, P2: 2, KNOWN: 1], s1.counts.byPriority)
    }

    @Test
    void orderCountsOnceAtMostUrgentLine() {
        String st = priorityAudit(PRI, "pri-order", null)
        reasonPriority(PRI, st, C1, "AUDP_P0")
        Closure<Map> line = { String item, String cause ->
            [statementCode: "S1", recordKey: item, headline: "Item ${item} is completed in HotWax and not billed in NetSuite".toString(),
             conclusionEnumId: cause, records: [[systemEnumId: "OMS_RETURNS_GQL", recordType: "orderItem", recordId: item]]]
        }
        // The C2 line first: the order is P0 because its C1 line is, not because it is the first line.
        List<String> ids = priorityFindings(PRI, "${PRI}_pri-order_C", [line("MP1/01", C2), line("MP1/02", C1)])
        Map s1 = priorityStatement(PRI, "${PRI}_pri-order")
        assertEquals(1, s1.counts.open)
        assertEquals([P0: 1, P1: 0, P2: 0, KNOWN: 0], s1.counts.byPriority)
        Map order = (s1.findings as List<Map>)[0]
        assertEquals(["order", "MP1", "P0", "reason"], [order.kind, order.id, order.priority, order.prioritySource])
        assertEquals([["P2", "statement"], ["P0", "reason"]], (order.lines as List<Map>).collect { [it.priority, it.prioritySource] })
        // The unit says which line and which reason put it at P0; a line's own level stays its own.
        assertEquals([ids[1], "Known carrier delay"], [order.priorityLine, order.priorityReason])
        assertEquals([null, "Known carrier delay"], (order.lines as List<Map>).collect { it.priorityReason })
    }

    @Test
    void waitingUnitsKeepOwnLevelUncounted() {
        priorityTenant(PRI)
        audit(PRI, "TPRI_W", "pri-wait", "Wait", "AUDP_MONTH")
        statement(PRI, "TPRI_W", "TPRI_W4", "S4", "AUDC_ORDERS", "AUDSW_FIRM", 40, "Every open line is at a facility that can fulfil it.")
        statement(PRI, "TPRI_W", "TPRI_W8", "S8", "AUDC_ORDERS", "AUDSW_FIRM", 80, "Every completed line is invoiced.")
        insert("darpan.audit.AuditStatement", [statementId: "TPRI_W8", priorityEnumId: "AUDP_P0"])
        ["4", "8"].each { String n ->
            insert("darpan.audit.AuditCheck", [checkId: "TPRI_WC${n}".toString(), companyUserGroupId: PRI, checkCode: "pri-wait-${n}".toString(), label: n])
            link(PRI, "TPRI_WC${n}".toString(), "TPRI_W${n}".toString(), 1)
        }
        priorityFindings(PRI, "TPRI_WC4", [[statementCode: "S4", recordKey: "SO7371002/01", headline: "Line M1210830/01 is open in NetSuite and not at a facility",
                records: [[systemEnumId: "NETSUITE_SUITEQL", recordType: "salesOrderLine", recordId: "SO7371002", internalId: "81531855"],
                          [systemEnumId: "OMS_RETURNS_GQL", recordType: "order", recordId: "EXC-#GOR197136981-1", internalId: "M1210830"]]]])
        priorityFindings(PRI, "TPRI_WC8", [[statementCode: "S8", recordKey: "M1210830/02", headline: "Item M1210830/02 is completed in HotWax and not billed in NetSuite",
                records: [[systemEnumId: "OMS_RETURNS_GQL", recordType: "orderItem", recordId: "M1210830/02",
                           recordData: JsonOutput.toJson([omsOrderName: "EXC-#GOR197136981-1"])],
                          [systemEnumId: "NETSUITE_SUITEQL", recordType: "salesOrderLine", recordId: "SO7371002", internalId: "81531855"]]]])

        Map statements = (Map) report(PRI, "TPRI_W", "2026-10-22").categories.orders.statements
        Map s4 = statements.S4, s8 = statements.S8
        Map counted = (s4.findings as List<Map>)[0]
        assertEquals(["P2", "statement"], [counted.priority, counted.prioritySource], "a P0 finding waiting under it does not raise it")
        assertEquals([[statement: "S8", priority: "P0", prioritySource: "statement"]],
                (counted.waiting as List<Map>).collect { it.subMap(["statement", "priority", "prioritySource"]) })
        assertEquals([P0: 0, P1: 0, P2: 1, KNOWN: 0], s4.counts.byPriority)
        assertEquals(["P0", 1, [P0: 0, P1: 0, P2: 0, KNOWN: 0]], [s8.priority, s8.waiting, s8.counts.byPriority], "a waiting finding is not counted")
    }

    @Test
    void byPrioritySumsToOpen() {
        String st = priorityAudit(PRI, "pri-sixty", null)
        reasonPriority(PRI, st, C1, "AUDP_P1")
        priorityFindings(PRI, "${PRI}_pri-sixty_C", (1..60).collect { int n -> lone("PS-${n}".toString(), n <= 10 ? C1 : null) })
        Map doc = report(PRI, "${PRI}_pri-sixty", "2026-10-22")
        Map s1 = doc.categories.orders.statements.S1 as Map
        assertEquals([60, 50, 10], [s1.counts.open, (s1.findings as List).size(), s1.findingsOmitted], "more than the 50 listed")
        assertEquals([P0: 0, P1: 10, P2: 50, KNOWN: 0], s1.counts.byPriority, "every finding counted, not the 50 listed")
        (doc.categories as Map).each { String code, Map cat ->
            assertEquals(cat.counts.open, (cat.counts.byPriority as Map).values().sum(), code)
            assertEquals(cat.counts.byPriority, doc.summary.byCategory[code].byPriority, code)
        }
        assertEquals([P0: 0, P1: 10, P2: 50, KNOWN: 0], doc.summary.byPriority)
        assertEquals(doc.summary.open, (doc.summary.byPriority as Map).values().sum())
    }

    @Test
    void overrideIsTenantScoped() {
        String st = priorityAudit(PRI, "pri-tenant", null)
        List<String> ids = priorityFindings(PRI, "${PRI}_pri-tenant_C", [lone("PT-1", C1)])
        // Tenant B has the same audit, statement code and conclusion, with an override; and a row of B's naming A's statement.
        String stB = priorityAudit(PRI_B, "pri-tenant", null)
        reasonPriority(PRI_B, stB, C1, "AUDP_P0")
        reasonPriority(PRI_B, st, C1, "AUDP_P0")
        priorityFindings(PRI_B, "${PRI_B}_pri-tenant_C", [lone("PT-1", C1)])

        Map s1 = priorityStatement(PRI, "${PRI}_pri-tenant")
        assertEquals([[ids[0], "P2", "statement"]], marks(s1.findings as List<Map>))
        assertEquals([P0: 0, P1: 0, P2: 1, KNOWN: 0], s1.counts.byPriority)
        assertEquals([level: "P2", source: "statement", reason: null], finding(PRI, ids[0]).finding.priority)
        assertEquals([P0: 1, P1: 0, P2: 0, KNOWN: 0], priorityStatement(PRI_B, "${PRI_B}_pri-tenant").counts.byPriority, "B's own override holds in B")
    }

    // DAR-OPS-029 S99-012 (S10's read side of S11 e462d3c9 / S13 0694eab8). Lines of an order left off past the 60-per-side
    // read cap: the order unit says how many per system (the most any of its lines saw), each line and its document its own;
    // an order with none left off carries no such field.
    @Test
    void anOrderSaysHowManyOfItsLinesTheReadLeftOff() {
        priorityAudit(PRI, "pri-cut", null)
        Closure<Map> line = { String item, Map omitted ->
            [statementCode: "S1", recordKey: item, headline: "Item ${item} is completed in HotWax and not billed in NetSuite".toString(),
             records: [[systemEnumId: "OMS_RETURNS_GQL", recordType: "orderItem", recordId: item, recordStatus: "Completed"]]] +
                    (omitted ? [orderLinesOmitted: omitted] : [:])
        }
        List<String> ids = priorityFindings(PRI, "${PRI}_pri-cut_C", [line("M9000001/01", [OMS_RETURNS_GQL: 4, NETSUITE_SUITEQL: 2]),
                line("M9000001/02", [OMS_RETURNS_GQL: 7]), line("M9000002/01", null)])
        List<Map> units = priorityStatement(PRI, "${PRI}_pri-cut").findings as List<Map>
        Map cut = units.find { it.id == "M9000001" }
        assertEquals([NetSuite: 2, OMS: 7], cut.orderLinesOmitted, "per system, the most any of its lines saw")
        assertEquals([[NetSuite: 2, OMS: 4], [OMS: 7]], (cut.lines as List<Map>)*.orderLinesOmitted, "each line its own")
        Map whole = units.find { it.id == "M9000002" }
        assertFalse(whole.containsKey("orderLinesOmitted") || ((whole.lines as List<Map>)[0]).containsKey("orderLinesOmitted"),
                "none left off: absent")
        assertEquals([NetSuite: 2, OMS: 4], finding(PRI, ids[0]).finding.orderLinesOmitted)
        assertFalse((finding(PRI, ids[2]).finding as Map).containsKey("orderLinesOmitted"))
    }

    @Test
    void findingDocumentSaysWhereLevelCameFrom() {
        String st = priorityAudit(PRI, "pri-doc", "AUDP_P1")
        reasonPriority(PRI, st, C1, "AUDP_KNOWN")
        List<String> ids = priorityFindings(PRI, "${PRI}_pri-doc_C", [lone("PD-1", C1), lone("PD-2", C2)])
        assertEquals([level: "KNOWN", source: "reason", reason: "Known carrier delay"], finding(PRI, ids[0]).finding.priority)
        assertEquals([level: "P1", source: "statement", reason: null], finding(PRI, ids[1]).finding.priority)
    }

    /**
     * DAR-UI-080 (Aditi: "the minimal amount of information needed to draw a meaningful conclusion ... and the JSON should be
     * comprehensive"): the report lists a record's few telling facts from its data; the finding's own document adds the data.
     */
    @Test
    void aRecordListsItsTellingFactsAndItsDocumentCarriesAllItsData() {
        Map item = [omsOrderId: "M1167975", omsOrderName: "#GOR195470263", omsOrderStatusId: "ORDER_COMPLETED", orderGrandTotal: 324.89]
        Map ret = [returnId: "M263376", returnChannelEnumId: "AFTSHIP_RTN_CHANNEL", warrantyId: "6URJ7WK3", returnTotal: "118",
                   currencyUomId: "USD", orderName: "#GOR195470263", orderGrandTotal: 236]
        Map line = [tranId: "SO7330484", deposits: [[tranId: "CD1", amount: "118", status: "Customer Deposit : Deposited"]]]
        Map shopify = [id: "18954083434627", displayFinancialStatus: "PARTIALLY_REFUNDED", displayFulfillmentStatus: "FULFILLED",
                       totalPrice: "236.00", currencyCode: "USD"]
        List<Map> rows = [[systemEnumId: "OMS", recordType: "orderItem", recordId: "M1167975/01", recordStatus: "Completed", recordData: JsonOutput.toJson(item)],
                          [systemEnumId: "OMS", recordType: "return", recordId: "M263376", recordData: JsonOutput.toJson(ret)],
                          [systemEnumId: "NETSUITE", recordType: "salesOrderLine", recordId: "SO7330484", recordStatus: "Pending Billing", recordData: JsonOutput.toJson(line)],
                          [systemEnumId: "SHOPIFY", recordType: "order", recordId: "18954083434627", recordData: JsonOutput.toJson(shopify)],
                          [systemEnumId: "NETSUITE", recordType: "rma", recordId: "RMA1", recordData: "not json"]]
        Map report = AuditRecordFacts.recordsJson(rows)
        // Aditi: "order and return totals are missing": an item names its order's total, a return its own and its order's.
        assertEquals([id: "M1167975/01", internalId: null, status: "Completed", orderName: "#GOR195470263", orderStatus: "Completed",
                      orderTotal: [amount: "324.89", currency: null]], report.OMS.orderItem[0])
        assertEquals([id: "M263376", internalId: null, status: null, orderName: "#GOR195470263", channel: "AFTSHIP_RTN_CHANNEL",
                      warrantyId: "6URJ7WK3", orderTotal: [amount: "236.00", currency: "USD"], returnTotal: [amount: "118.00", currency: "USD"]],
                report.OMS['return'][0])
        assertEquals([id: "SO7330484", internalId: null, status: "Pending Billing", deposits: [[id: "CD1", amount: "118", status: "Deposited"]],
                      paymentStatus: "Deposited"],
                report.NetSuite.salesOrderLine[0])
        assertEquals([id: "18954083434627", internalId: null, status: "Fulfilled", orderTotal: [amount: "236.00", currency: "USD"],
                      paymentStatus: "Partially refunded"],
                report.Shopify.order[0])
        assertEquals([id: "RMA1", internalId: null, status: null], report.NetSuite.rma[0])

        // DAR-UI-081 (R8 F-104639): a return held behind another names it and its exchange order, which has no NetSuite id.
        Map held = [returnId: "M259806", statusId: "RETURN_COMPLETED", exchangeOrderId: "M1147567", exchangeOrderName: "EXC-#GOR196740771-3",
                    openReturnOnOrderId: "M192313", openReturnOnOrderStatus: "RETURN_REQUESTED"]
        Map heldReport = AuditRecordFacts.recordsJson([[systemEnumId: "OMS", recordType: "return", recordId: "M259806", recordData: JsonOutput.toJson(held)]])
        assertEquals([id: "M259806", internalId: null, status: "Completed", heldBy: [id: "M192313", status: "Requested"],
                      exchange: [id: "EXC-#GOR196740771-3", netsuiteId: null]], heldReport.OMS['return'][0])

        // DAR-UI-080 (Aditi: "add order date and return date"), as the tenant's day: 2026-10-22 00:30Z is still the 21st in LA.
        ZoneId la = ZoneId.of("America/Los_Angeles")
        Map dated = AuditRecordFacts.recordFacts([returnDate: 1792187864000L, orderDate: "2026-10-22T00:30:00Z"], "return", la)
        assertEquals(["2026-10-21", "2026-10-16"], [dated.orderDate, dated.returnDate])
        assertEquals("2026-10-21", AuditRecordFacts.recordFacts([omsOrderDate: "2026-10-21T18:22:10Z"], "orderItem", la).orderDate)
        assertEquals("2026-10-20", AuditRecordFacts.recordFacts([createdAt: "2026-10-20T10:00:00-07:00"], "order", la).orderDate)
        assertEquals("2026-10-21", AuditRecordFacts.recordFacts([orderDate: "2026-10-21 18:22:10.0"], "order", la).orderDate)
        // DAR-BE-372: NetSuite's M/d/yyyy is a day (it was read as none, so an R10 card said Day "—").
        assertEquals("2026-10-21", AuditRecordFacts.recordFacts([orderDate: "10/21/2026", createdAt: "2026-10-20T10:00:00Z"], "salesOrderLine", la).orderDate)

        // DAR-UI-081 (Aditi: "show item as a subheading with SKU", "add customer name", "add location ID to the line box").
        Map unit = AuditRecordFacts.recordFacts([sku: "263-203-186-G", customerName: "Ada Lovelace", facilityId: "71", facilityName: "Portland"], "orderItem", la)
        assertEquals(["263-203-186-G", "Ada Lovelace", "71", "Portland"], [unit.sku, unit.customer, unit.location, unit.locationName])
        assertEquals("28", AuditRecordFacts.recordFacts([lineLocation: "28"], "salesOrderLine", la).location)
        Map nsLine = AuditRecordFacts.recordFacts([salesChannel: "POS Channel", fulfillments: ["IF7456843"]], "salesOrderLine", la)
        assertEquals(["POS Channel", ["IF7456843"]], [nsLine.salesChannel, nsLine.fulfillments])
        assertEquals("STANDARD", AuditRecordFacts.recordFacts([shipmentMethodTypeId: "STANDARD"], "orderItem", la).shipMethod)
        assertEquals(null, AuditRecordFacts.recordFacts([fulfillments: []], "salesOrderLine", la).fulfillments)

        // Aditi: "all findings should have basic order info... maintain some consistency": the same facts from each
        // system's own fields (R20 pairs a Shopify order with a HotWax order).
        Map shopifyOrder = AuditRecordFacts.recordFacts([name: "#GOR197395673", createdAt: "2026-10-24T17:02:11Z", totalPrice: "118.00",
                currencyCode: "USD", sourceName: "pos", displayFinancialStatus: "PAID", displayFulfillmentStatus: "UNFULFILLED"], "order", la)
        assertEquals(["#GOR197395673", "2026-10-24", "POS", [amount: "118.00", currency: "USD"], "Unfulfilled", "Paid"],
                [shopifyOrder.orderName, shopifyOrder.orderDate, shopifyOrder.salesChannel, shopifyOrder.orderTotal, shopifyOrder.status,
                 shopifyOrder.paymentStatus])
        Map hotwaxOrder = AuditRecordFacts.recordFacts([orderName: "#GOR197395673", statusId: "ORDER_COMPLETED", grandTotal: 118,
                currencyUomId: "USD", billToCustomer: [firstName: "Ada", lastName: "Lovelace"],
                shipGroups: [[shipGroupSeqId: "00001", shipmentMethodTypeId: "STANDARD"], [shipGroupSeqId: "00002", shipmentMethodTypeId: "STANDARD"]]], "order", la)
        assertEquals(["Completed", "Ada Lovelace", "STANDARD", [amount: "118.00", currency: "USD"]],
                [hotwaxOrder.status, hotwaxOrder.customer, hotwaxOrder.shipMethod, hotwaxOrder.orderTotal])
        assertEquals("Web", AuditRecordFacts.recordFacts([salesChannelEnumId: "WEB_SALES_CHANNEL"], "orderItem", la).salesChannel)

        Map document = AuditRecordFacts.recordsJson(rows, true)
        assertEquals(shopify, document.Shopify.order[0].data)
        assertEquals(line, document.NetSuite.salesOrderLine[0].data)
        assertFalse(report.Shopify.order[0].containsKey("data"))
    }

    // ---- acknowledgements (DAR-BE-361) ---------------------------------------------------------------------------
    // Each test has an audit of its own in tenant TACK (statement S1, three lone invoices found on 2026-10-21 at 08:00).
    // A report's now is its generatedAt (the one clock value, pinned here): its tenant day is today for remind days, and it
    // picks the latest morning (the one a blank date shows). That morning's report and later split; older ones do not.
    // Unscheduled audits' latest morning is today; a scheduled one (09:00 LA, no run recorded) shows yesterday until 09:00.

    private static final String ACK = "TACK"
    private static final String ACK_USER = "TACK_SAM"

    private List<String> ackAudit(String name, boolean scheduled = false) {
        insert("moqui.security.UserGroup", [userGroupId: ACK, description: "Ack"])
        insert("darpan.auth.TenantSetting", [companyUserGroupId: ACK, timeZone: "America/Los_Angeles"])
        insert("moqui.security.UserAccount", [userId: ACK_USER, username: "tack.sam", userFullName: "Sam R."])
        String auditId = "${ACK}_${name}".toString()
        audit(ACK, auditId, name, name, "AUDP_MONTH")
        if (scheduled) insert("darpan.audit.Audit", [auditId: auditId, scheduleActive: "Y", scheduleTime: "09:00", scheduleTimeZone: "America/Los_Angeles"])
        statement(ACK, auditId, "${auditId}_S1".toString(), "S1", "AUDC_ORDERS", "AUDSW_FIRM", 1, "${name} S1".toString())
        insert("darpan.audit.AuditCheck", [checkId: "${auditId}_C".toString(), companyUserGroupId: ACK, checkCode: "${name}-check".toString(), label: name])
        link(ACK, "${auditId}_C".toString(), "${auditId}_S1".toString(), 1)
        List ids = ec.service.sync().name("${LEDGER}.record#CheckFindings").parameters([companyUserGroupId: ACK, checkId: "${auditId}_C".toString(),
                reportDate: Date.valueOf("2026-10-21"), checkStamp: at("2026-10-21", "08:00:00"),
                findings: (1..3).collect { lone("AK-${name}-${it}".toString(), null) }]).call().findingIds as List
        noErrors()
        return ids as List<String>
    }

    /** An acknowledgement row as acknowledge#AuditFinding writes it, on a tenant day and time in Los Angeles. */
    private void acknowledgeRow(String findingId, Timestamp stamp, String remindDate) {
        insert("darpan.audit.AuditFindingAcknowledgement", [findingId: findingId, companyUserGroupId: ACK, note: "Known: the 3PL is late",
                acknowledgedByUserId: ACK_USER, acknowledgedStamp: stamp, remindDate: remindDate ? Date.valueOf(remindDate) : null])
    }

    private void userEvent(String findingId, String seq, String type, Timestamp stamp) {
        insert("darpan.audit.AuditFindingEvent", [findingId: findingId, eventSeqId: seq, companyUserGroupId: ACK, eventTypeEnumId: type,
                eventStamp: stamp, eventDate: Date.valueOf(stamp.toInstant().atZone(LA).toLocalDate()), byTypeEnumId: "AUDAT_USER", byId: ACK_USER])
    }

    /** The report as of reportDay, built at generatedTime (default 11:00) on generatedDay: the report's now. */
    private Map ackReport(String auditId, String reportDay, String generatedDay, String generatedTime = "11:00:00") {
        Map out = ec.service.sync().name("${REPORTS}.assemble#AuditReport").parameters([companyUserGroupId: ACK, auditId: auditId,
                reportDate: Date.valueOf(reportDay), generatedStamp: at(generatedDay, generatedTime)]).call()
        noErrors()
        return new JsonSlurper().parseText(JsonOutput.toJson(out.document)) as Map
    }

    @Test
    void acknowledgedRowLeavesOpenCount() {
        List<String> ids = ackAudit("ack-count")
        acknowledgeRow(ids[0], at("2026-10-22", "09:00:00"), "2026-10-27")
        Map doc = ackReport("${ACK}_ack-count", "2026-10-22", "2026-10-22")
        Map s1 = doc.categories.orders.statements.S1 as Map
        assertEquals([open: 2, acknowledged: 1], s1.counts.subMap(["open", "acknowledged"]))
        assertEquals(2, (s1.counts.byPriority as Map).values().sum(), "byPriority counts the open units only")
        assertEquals([open: 2, acknowledged: 1], doc.summary.subMap(["open", "acknowledged"]))
        assertEquals(1, doc.summary.byCategory.orders.acknowledged)
        assertEquals(5, doc.report.acknowledgeRemindDays, "the setting's value, so the popup's label reads the rule")
        // Every unit is still listed (the acknowledged pill lists them); only the acknowledged one carries its note.
        assertEquals(3, (s1.findings as List).size())
        Map acked = (s1.findings as List<Map>).find { it.id == ids[0] }
        assertEquals([note: "Known: the 3PL is late", by: [id: ACK_USER, label: "Sam R."], at: "2026-10-22T09:00:00-07:00",
                      remindOn: "2026-10-27", reminded: false], acked.acknowledgement)
        assertEquals([null, null], (s1.findings as List<Map>).findAll { it.id != ids[0] }*.acknowledgement)
    }

    @Test
    void remindedRowCountsOpenWithItsNote() {
        List<String> ids = ackAudit("ack-remind")
        // Acknowledged six days ago with the reminder on (5 days): its remind day was yesterday.
        acknowledgeRow(ids[0], at("2026-10-16", "09:00:00"), "2026-10-21")
        Map s1 = ackReport("${ACK}_ack-remind", "2026-10-22", "2026-10-22").categories.orders.statements.S1 as Map
        assertEquals([open: 3, acknowledged: 0], s1.counts.subMap(["open", "acknowledged"]))
        Map acked = (s1.findings as List<Map>).find { it.id == ids[0] }
        assertEquals(["Known: the 3PL is late", true, "2026-10-21"],
                [acked.acknowledgement?.note, acked.acknowledgement?.reminded, acked.acknowledgement?.remindOn])
    }

    @Test
    void pastReportHasNoSplit() {
        List<String> ids = ackAudit("ack-past")
        acknowledgeRow(ids[0], at("2026-10-22", "09:00:00"), "2026-10-27")
        Map doc = ackReport("${ACK}_ack-past", "2026-10-21", "2026-10-22")
        Map s1 = doc.categories.orders.statements.S1 as Map
        assertEquals([open: 3, acknowledged: 0], s1.counts.subMap(["open", "acknowledged"]), "yesterday's morning as it was")
        assertEquals([open: 3, acknowledged: 0], doc.summary.subMap(["open", "acknowledged"]))
        assertEquals([null, null, null], (s1.findings as List<Map>)*.acknowledgement)
        assertEquals(5, doc.report.acknowledgeRemindDays)
    }

    // (a) Before today's 09:00 run the page opens on yesterday's report: an acknowledgement made this morning shows on it.
    @Test
    void acknowledgedBeforeTodaysRunSplitsTheLatestMorning() {
        List<String> ids = ackAudit("ack-early", true)
        acknowledgeRow(ids[0], at("2026-10-22", "06:30:00"), "2026-10-27")
        Map doc = ackReport("${ACK}_ack-early", "2026-10-21", "2026-10-22", "07:00:00")
        Map s1 = doc.categories.orders.statements.S1 as Map
        assertEquals([open: 2, acknowledged: 1], s1.counts.subMap(["open", "acknowledged"]), "yesterday is the latest morning until the run")
        assertEquals([open: 2, acknowledged: 1], doc.summary.subMap(["open", "acknowledged"]))
        assertEquals([false], (s1.findings as List<Map>).findAll { it.acknowledgement }*.acknowledgement*.reminded)
    }

    // (b) Once today's run is due, yesterday's report is an older morning's: as it was.
    @Test
    void olderMorningHasNoSplit() {
        List<String> ids = ackAudit("ack-older", true)
        acknowledgeRow(ids[0], at("2026-10-22", "06:30:00"), "2026-10-27")
        Map s1 = ackReport("${ACK}_ack-older", "2026-10-21", "2026-10-22", "10:00:00").categories.orders.statements.S1 as Map
        assertEquals([open: 3, acknowledged: 0], s1.counts.subMap(["open", "acknowledged"]))
        assertEquals([null, null, null], (s1.findings as List<Map>)*.acknowledgement)
    }

    // (c) On yesterday's report (the latest morning), remind days run to the real today, not to the report's date.
    @Test
    void reminderReadsTheRealToday() {
        List<String> ids = ackAudit("ack-real", true)
        acknowledgeRow(ids[0], at("2026-10-16", "09:00:00"), "2026-10-21")   // six days ago, remind day yesterday
        acknowledgeRow(ids[1], at("2026-10-17", "09:00:00"), "2026-10-22")   // remind day today: reminded, though not by 10-21
        Map s1 = ackReport("${ACK}_ack-real", "2026-10-21", "2026-10-22", "07:00:00").categories.orders.statements.S1 as Map
        assertEquals([open: 3, acknowledged: 0], s1.counts.subMap(["open", "acknowledged"]))
        Map<String, Map> byId = (s1.findings as List<Map>).collectEntries { [(it.id): it] }
        assertEquals([true, true, null], [byId[ids[0]].acknowledgement?.reminded, byId[ids[1]].acknowledgement?.reminded,
                                          byId[ids[2]].acknowledgement])
    }

    @Test
    void userEventIsNotLastChecked() {
        List<String> ids = ackAudit("ack-checked")
        // A finding stamped before lastCheckedStamp existed: lastChecked falls back to its latest check event.
        ec.entity.find("darpan.audit.AuditFinding").condition("findingId", ids[0]).one().set("lastCheckedStamp", null).update()
        acknowledgeRow(ids[0], at("2026-10-21", "10:00:00"), null)
        userEvent(ids[0], "U01", "AUDFE_ACKNOWLEDGED", at("2026-10-21", "10:00:00"))
        Map doc = finding(ACK, ids[0])
        assertEquals("2026-10-21T08:00:00-07:00", doc.finding.status.lastChecked, "the check at 08:00, not the acknowledgement at 10:00")
        assertEquals(["found", "acknowledged"], (doc.finding.history as List<Map>)*.event, "the acknowledgement stays in history")
        Map ack = doc.finding.acknowledgement as Map
        assertEquals(["Known: the 3PL is late", null, false], [ack?.note, ack?.remindOn, ack?.reminded])
        assertNull(finding(ACK, ids[1]).finding.acknowledgement, "no row, no acknowledgement")
    }

    @Test
    void userEventIsNotAPastMorningsState() {
        List<String> ids = ackAudit("ack-state")
        String f = ids[0]
        // Fixed on 10-22 at 06:00; a person reopens its old acknowledgement at 10:00; a check finds it again on 10-23.
        insert("darpan.audit.AuditFindingEvent", [findingId: f, eventSeqId: "C01", companyUserGroupId: ACK, eventTypeEnumId: "AUDFE_FIXED",
                eventStamp: at("2026-10-22", "06:00:00"), eventDate: Date.valueOf("2026-10-22"), byTypeEnumId: "AUDAT_CHECK", byId: "${ACK}_ack-state_C".toString()])
        userEvent(f, "U01", "AUDFE_ACK_REOPENED", at("2026-10-22", "10:00:00"))
        insert("darpan.audit.AuditFindingEvent", [findingId: f, eventSeqId: "C02", companyUserGroupId: ACK, eventTypeEnumId: "AUDFE_REOPENED",
                eventStamp: at("2026-10-23", "06:00:00"), eventDate: Date.valueOf("2026-10-23"), byTypeEnumId: "AUDAT_CHECK", byId: "${ACK}_ack-state_C".toString()])
        Map s1 = ackReport("${ACK}_ack-state", "2026-10-22", "2026-10-23").categories.orders.statements.S1 as Map
        assertEquals("fixed", (s1.findings as List<Map>).find { it.id == f }?.state, "10-22 ended fixed: the person's event is not a check's")
        assertEquals(1, s1.counts.fixedToday)
    }

    @Test
    void findingsByOrderAcknowledgedFilter() {
        List<String> ids = ackAudit("ack-list")
        LocalDate today = LocalDate.now(LA)
        Timestamp now = new Timestamp(System.currentTimeMillis())
        acknowledgeRow(ids[0], now, null)
        // Reminded: acknowledged six days ago, its remind day yesterday. It lists under open, with its note.
        acknowledgeRow(ids[1], Timestamp.from(now.toInstant().minusSeconds(6 * 86400L)), today.minusDays(1).toString())
        ec.artifactExecution.disableAuthz()
        try {
            String auditId = "${ACK}_ack-list".toString()
            assertEquals([ids[0]], AuditReportAssembler.findingsByOrder(ec, ACK, auditId, null, null, null, "acknowledged")*.id)
            List<Map> open = AuditReportAssembler.findingsByOrder(ec, ACK, auditId, null, null, null, "open")
            assertEquals([ids[1], ids[2]], open*.id)
            assertEquals([true, null], open.collect { it.acknowledgement?.reminded })
            // Blank, null or "all" keeps every open row, the acknowledged one too: callers from before acknowledgements
            // (the run update, its CSVs) lose nothing.
            [null, "", "all"].each { String state ->
                List<Map> every = AuditReportAssembler.findingsByOrder(ec, ACK, auditId, null, null, null, state)
                assertEquals(ids, every*.id, "ackState ${state}".toString())
                assertEquals("Known: the 3PL is late", every.find { it.id == ids[0] }.acknowledgement?.note)
            }
            assertEquals(ids, AuditReportAssembler.findingsByOrder(ec, ACK, auditId, null, null)*.id, "the 5-argument overload keeps every row")
        } finally {
            ec.artifactExecution.enableAuthz()
        }
    }

    // DAR-BE-254 (Aditi on RAILS #1028236: "it says R4 here.. shouldnt it be the SKU"): what a waiting finding is about.
    @Test
    void aWaitingFindingIsNamedByItsItem() {
        Map item = [records: [OMS: [orderItem: [[id: "165068/02", sku: "889C-369B-82848284:S"]]], NetSuite: [salesOrderLine: [[id: "SO8453628"]]]]]
        assertEquals("889C-369B-82848284:S", AuditOrderUnits.itemOf(item))
        Map threeSkus = [records: [OMS: [orderItem: [[sku: "A"], [sku: "B"], [sku: "C"], [sku: "A"]]]]]
        assertEquals("A, B and 1 more", AuditOrderUnits.itemOf(threeSkus))
        assertEquals("M1/01, M1/02", AuditOrderUnits.itemOf([records: [:], lines: [[id: "M1/01"], [id: "M1/02"]]]),
                "no SKU on the records: its lines")
        assertNull(AuditOrderUnits.itemOf([records: [NetSuite: [salesOrder: [[id: "SO8450832"]]]]]), "an order-level finding names no item")
    }
}
