package darpan.audit

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
 *               found INV-552310 (R2, pending).
 *   2026-10-09  returns check ran over a PARTIAL NetSuite read; M258615 again; nothing closes. Invoice check did not run.
 * R5 and R12 have no check. All of that is GORJANA's "Month close" audit. Her second audit, "Daily ops" (period day),
 * has statement D1 and a check that never runs, with one finding: the month-close report must see none of it.
 * KREWE has its own audit and finding; TRUN is a third tenant for record#CheckRun alone, so those tests never change
 * the scenario's health.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
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
    private String inv552310
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
                label: "OMS returns against NetSuite credit memos", graceDays: 1L, matchedBy: "return id (custbody_hc_return_id)",
                fixOwner: "HotWax Support", fixWhereEnumId: "NETSUITE",
                fixActionTemplate: "Correct credit memo {creditMemo} so its total is {expectedAmount} and nothing remains open.",
                passesWhenTemplate: "Memo total is {expectedAmount} and amount remaining is 0.00."])
        insert("darpan.audit.AuditCheck", [checkId: INV, companyUserGroupId: GORJANA, checkCode: "invoices-without-etail-id",
                label: "NetSuite invoices and memos with no Etail id", graceDays: 1L])
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
        inv552310 = (record(INV, "2026-10-08", "06:05:00", [invoice]).findingIds as List)[0]
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
                      window: [from: "2026-10-01", thru: "2026-10-08"], running: true, extendedThru: null,
                      generatedAt: "2026-10-08T06:12:40-07:00"], doc.report, "month to date, the report day included")
        assertEquals("Month close audit for Gorjana, 2026-10. Each finding is one record that breaks one of the audit's statements R2 to R12.", doc.about)
        assertEquals(["orders", "refundsAndCredits", "returns", "exchangesAndWarranty"], (doc.categories as Map).keySet() as List)
        assertEquals("Refunds and credits", doc.categories.refundsAndCredits.label)
        assertEquals(["R4", "R5"], (doc.categories.refundsAndCredits.statements as Map).keySet() as List, "catalog order")

        Map r4 = doc.categories.refundsAndCredits.statements.R4
        assertEquals([id: "R4", text: R4_TEXT, weight: "derived", status: "ran", reason: null], r4.subMap(["id", "text", "weight", "status", "reason"]))
        assertEquals([[id: "returns-vs-credit-memos", label: "OMS returns against NetSuite credit memos", status: "ran", reason: null,
                       matchedBy: "return id (custbody_hc_return_id)", graceDays: 1, compared: [OMS: 412, NetSuite: 409],
                       window: [from: "2026-10-01", thru: "2026-10-08"]]], r4.checks)
        assertEquals([open: 1, pending: 0, newToday: 0, fixedToday: 0], r4.counts)
        Map brief = (r4.findings as List)[0]
        assertEquals([id: m258615, headline: "Credit memo is 0.01 less than the refund for return M258615", state: "open",
                      firstFound: "2026-10-07", ageDays: 1, difference: [amount: "-0.01", currency: "USD"],
                      cause: "Memo short of the refund", next: "darpan findings show ${m258615}".toString()],
                brief.findAll { it.key != "records" })
        assertEquals([Shopify : [order: [[id: "7187000000000", internalId: null, status: null]]],
                      OMS     : ["return": [[id: "M258615", internalId: null, status: "Completed"]]],
                      NetSuite: [creditMemo: [[id: "CM-88213", internalId: "51000002", status: "Open"]]]], brief.records,
                "records by ROOT system and type, every value an array")

        Map r2 = doc.categories.orders.statements.R2
        assertEquals([open: 0, pending: 1, newToday: 1, fixedToday: 0], r2.counts)
        assertEquals(["pending", null, [NetSuite: 2210]], [r2.findings[0].state, r2.findings[0].difference, r2.checks[0].compared])

        Map r8 = doc.categories.returns.statements.R8
        assertEquals([open: 0, pending: 0, newToday: 0, fixedToday: 1], r8.counts)
        assertEquals([[rx1, "fixed"]], (r8.findings as List).collect { [it.id, it.state] }, "fixed today stays on the list that day")

        assertEquals([open: 1, pending: 1, newToday: 1, fixedToday: 1], doc.summary.subMap(["open", "pending", "newToday", "fixedToday"]))
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
                          counts: [open: 0, pending: 0, newToday: 0, fixedToday: 0], findings: [], findingsOmitted: 0],
                    r5.subMap(["status", "reason", "checks", "counts", "findings", "findingsOmitted"]), day)
            assertNotNull(doc.categories.exchangesAndWarranty.statements.R12, "R12 present on ${day}")
        }
    }

    @Test
    void aPastMorningReadsEachFindingsStateFromItsHistory() {
        Map doc = report(GORJANA, "2026-10-07")
        Map r4 = doc.categories.refundsAndCredits.statements.R4
        assertEquals([open: 0, pending: 1, newToday: 1, fixedToday: 0], r4.counts, "stored state is open; on 10-07 it was pending")
        Map r8 = doc.categories.returns.statements.R8
        assertEquals([[rx1, "pending"]], (r8.findings as List).collect { [it.id, it.state] }, "stored state is fixed; on 10-07 it was pending")
        Map r2 = doc.categories.orders.statements.R2
        assertEquals([], r2.findings, "INV-552310 was first found on 10-08")
        assertEquals(["notRun", "The check did not run on the morning of 2026-10-07."], [r2.status, r2.reason])
        assertEquals(false, doc.health.complete)
        assertEquals([ran: 1, partial: 0, notRun: 1], doc.health.checks)
        assertEquals("Not complete: check invoices-without-etail-id did not run. R5 and R12 have no check yet.", doc.health.summary)
        assertNull(doc.summary.oldestOpen, "nothing was open yet")
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
        assertEquals([open: 1, pending: 0, newToday: 0, fixedToday: 0], r4.counts)
        assertEquals([OMS: 400, NetSuite: 300], r4.checks[0].compared)

        Map r2 = doc.categories.orders.statements.R2
        assertEquals("notRun", r2.status)
        assertNull(r2.checks[0].compared, "compared is null when the check did not run")
        assertEquals([open: 0, pending: 1, newToday: 0, fixedToday: 0], r2.counts, "counts are kept on a morning the check did not run")
        assertEquals(2, doc.summary.oldestOpen.ageDays)

        Map r8 = doc.categories.returns.statements.R8
        assertEquals([[], [open: 0, pending: 0, newToday: 0, fixedToday: 0]], [r8.findings, r8.counts],
                "RX-1 was fixed on 10-08, so it is off the 10-09 list")
        assertEquals(0, doc.summary.fixedToday)
    }

    @Test
    void countsAddUpAtEveryLevel() {
        ["2026-10-07", "2026-10-08", "2026-10-09"].each { String day ->
            Map doc = report(GORJANA, day)
            List<String> keys = ["open", "pending", "newToday", "fixedToday"]
            (doc.categories as Map).each { String code, Map cat ->
                (cat.statements as Map).each { String id, Map st ->
                    assertEquals((st.counts.open + st.counts.pending + st.counts.fixedToday) as int,
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
        assertEquals([open: 2, pending: 0, newToday: 2, fixedToday: 0], r1.counts, "counted by order, not by line")
        assertEquals(["M1167646", [ids[3]], "Order M1167646: line 1 is completed in HotWax and not billed in NetSuite"],
                [findings[1].id, (findings[1].lines as List<Map>)*.id, findings[1].headline])
        // A finding that only mentions an order (a memo naming its Shopify order) is not about an order line: no order.
        Map r4 = (Map) ((Map) ((Map) report(GORJANA, "2026-10-08").categories).refundsAndCredits?.statements ?: [:]).R4
        assertEquals(null, ((r4?.findings ?: []) as List<Map>).find()?.kind)
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
                      ageDays: 2, timesSeen: 3, pendingUntil: null, closedAt: null, closedBy: null], f.status)
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
        assertEquals(["fixed", "2026-10-08T06:12:40-07:00", 1, null], [s.state, s.closedAt, s.ageDays, s.pendingUntil])
        assertEquals([type: "check", id: "returns-vs-credit-memos", label: "scheduled check, 2026-10-08T06:12-07:00"], s.closedBy)
        assertEquals(["found", "fixed"], (finding(GORJANA, rx1).finding.history as List)*.event)
    }

    @Test
    void aPendingFindingSaysWhenItCountsAsOpen() {
        Map s = finding(GORJANA, inv552310).finding.status
        assertEquals(["pending", "2026-10-09"], [s.state, s.pendingUntil])
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
        assertEquals(LocalDate.of(2026, 10, 1), AuditReportAssembler.periodStart(LocalDate.of(2026, 11, 14), "quarter"))
        assertEquals(LocalDate.of(2026, 1, 1), AuditReportAssembler.periodStart(LocalDate.of(2026, 3, 31), "quarter"))
        assertEquals(LocalDate.of(2026, 4, 1), AuditReportAssembler.periodStart(LocalDate.of(2026, 4, 1), "quarter"))
        assertEquals("2026-Q4", AuditReportAssembler.periodOf(LocalDate.of(2026, 11, 14), "quarter"))
        assertEquals("2026-Q1", AuditReportAssembler.periodOf(LocalDate.of(2026, 3, 31), "quarter"))
    }

    @Test
    void aCheckRunKeepsTheWindowItRead() {
        Map run = ec.entity.find("darpan.audit.AuditCheckRun").condition("checkId", RET)
                .condition("reportDate", Date.valueOf("2026-10-08")).useCache(false).one().getMap()
        assertEquals([Date.valueOf("2026-10-01"), Date.valueOf("2026-10-08")], [run.windowFromDate, run.windowThruDate],
                "blank window = the audit's month to the report day")
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
    void anExtendedPeriodRunsBesideTheNewOneUntilItsDate() {
        // An October finding first seen on Oct 20 and still open when October ends.
        checkRunFor(RET, "2026-10-20", null, [read("OMS_RETURNS_GQL", "AUDSR_COMPLETE", 5, "06:01:00")])
        String ext1 = (recordFor(RET, "2026-10-20", null, [[statementCode: "R4", recordKey: "EXT-1", headline: "Late October memo"]])
                .findingIds as List)[0]

        // Nov 3, not extended: October's run is refused, November's runs alone.
        ec.service.sync().name("${LEDGER}.record#CheckRun").parameters([companyUserGroupId: GORJANA, checkId: RET,
                reportDate: Date.valueOf("2026-11-03"), period: "2026-10", sourceReads: []]).call()
        assertTrue(ec.message.errorsString.contains("Period 2026-10 ended on 2026-10-31 and is not extended to 2026-11-03"), ec.message.errorsString)
        ec.message.clearErrors()
        assertEquals(["2026-11"], runsOn("2026-11-03")*.period)

        // The user asks October to keep running through Nov 5.
        extend(GORJANA, MONTH_CLOSE, "2026-10", "2026-11-05")
        assertEquals([[period: "2026-11", from: "2026-11-01", thru: "2026-11-03", extendedThru: null],
                      [period: "2026-10", from: "2026-10-01", thru: "2026-10-31", extendedThru: "2026-11-05"]], runsOn("2026-11-03"),
                "the new month's run and October's, side by side")
        Map octRun = checkRunFor(RET, "2026-11-03", "2026-10", [read("OMS_RETURNS_GQL", "AUDSR_COMPLETE", 5, "06:01:00")])
        Map octStored = ec.entity.find("darpan.audit.AuditCheckRun").condition("checkRunId", octRun.checkRunId).useCache(false).one().getMap()
        assertEquals([Date.valueOf("2026-10-01"), Date.valueOf("2026-10-31")], [octStored.windowFromDate, octStored.windowThruDate],
                "an extended period re-reads its whole span")
        recordFor(RET, "2026-11-03", "2026-10", [])
        Map closed = ec.service.sync().name("${LEDGER}.close#ResolvedFindings").parameters([companyUserGroupId: GORJANA, checkId: RET,
                reportDate: Date.valueOf("2026-11-03"), checkStamp: at("2026-11-03", "06:10:00"), period: "2026-10",
                checkStatusEnumId: octRun.checkStatusEnumId, examinedRecordKeys: ["EXT-1"]]).call()
        noErrors()
        assertEquals([ext1], closed.closedFindingIds, "October's own run closes October's finding in November")

        Map october = report(GORJANA, MONTH_CLOSE, "2026-11-03", "2026-10")
        assertEquals([period: "2026-10", window: [from: "2026-10-01", thru: "2026-10-31"], running: true, extendedThru: "2026-11-05"],
                october.report.subMap(["period", "window", "running", "extendedThru"]))
        assertEquals([[ext1, "fixed"]], october.categories.refundsAndCredits.statements.R4.findings
                .findAll { it.id == ext1 }.collect { [it.id, it.state] })
        assertEquals("ran", october.categories.refundsAndCredits.statements.R4.checks[0].status)

        Map november = report(GORJANA, MONTH_CLOSE, "2026-11-03", null)
        assertEquals("2026-11", november.report.period)
        assertEquals([open: 0, pending: 0, newToday: 0, fixedToday: 0], november.categories.refundsAndCredits.statements.R4.counts,
                "October's findings are October's report's, not November's")
        assertEquals("The check did not run on the morning of 2026-11-03.", november.categories.refundsAndCredits.statements.R4.checks[0].reason)

        // Nov 6: past the extension, October stops and keeps what it had.
        assertEquals(["2026-11"], runsOn("2026-11-06")*.period)
        ec.service.sync().name("${LEDGER}.record#CheckFindings").parameters([companyUserGroupId: GORJANA, checkId: RET,
                reportDate: Date.valueOf("2026-11-06"), period: "2026-10", findings: []]).call()
        assertTrue(ec.message.errorsString.contains("is not extended to 2026-11-06"), ec.message.errorsString)
        ec.message.clearErrors()
        Map stopped = report(GORJANA, MONTH_CLOSE, "2026-11-06", "2026-10")
        assertEquals(false, stopped.report.running)
        assertEquals("The 2026-10 audit ended on 2026-11-05 and was not extended to 2026-11-06.",
                stopped.categories.refundsAndCredits.statements.R4.checks[0].reason)
        assertEquals("2026-10", finding(GORJANA, ext1).finding.period)
        extend(GORJANA, MONTH_CLOSE, "2026-10", null)
    }

    @Test
    void anExtensionIsAPeriodOfTheAuditThatRunsPastItsEnd() {
        extendExpectingError(GORJANA, MONTH_CLOSE, "2026-Q4", "2026-11-05", "2026-Q4 is not a month period of audit month-close")
        extendExpectingError(GORJANA, MONTH_CLOSE, "2026-09", "2026-09-30", "must run past its last day, 2026-09-30")
        extendExpectingError(KREWE, MONTH_CLOSE, "2026-09", "2026-10-03", "was not found for tenant KREWE")
        extend(GORJANA, MONTH_CLOSE, "2026-09", "2026-10-03")
        assertEquals(1L, extensionRows("2026-09"))
        extend(GORJANA, MONTH_CLOSE, "2026-09", null)
        assertEquals(0L, extensionRows("2026-09"), "a blank date stops it")
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
        assertEquals([amount: "-0.01", currency: "USD"], AuditReportAssembler.money(new BigDecimal("-0.010000"), "USD"))
        assertEquals([amount: "1500", currency: "JPY"], AuditReportAssembler.money(new BigDecimal("1500.00"), "JPY"))
        assertNull(AuditReportAssembler.money(null, "USD"))
        assertEquals('Fix CM-1 by {unknown}; ${x} stays text',
                AuditReportAssembler.fillTemplate('Fix {creditMemo} by {unknown}; ${x} stays text', [creditMemo: "CM-1"]))
        // DAR-BE-093: a composite compare key (order id + item seq id) is joined by an invisible unit separator; people
        // and agents read it as order/item.
        assertEquals("M1137118/01", AuditReportAssembler.displayKey("M1137118\u001F01"))
        assertEquals("INV1", AuditReportAssembler.displayKey("INV1"))
        assertNull(AuditReportAssembler.displayKey(null))
        assertEquals("NetSuite", AuditReportAssembler.systemKey("NETSUITE"))
        assertEquals("AMAZON", AuditReportAssembler.systemKey("AMAZON"), "an unmapped root keeps its enumId")
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
        return (out.runs as List<Map>).collect { [period: it.period, from: it.windowFromDate?.toString(), thru: it.windowThruDate?.toString(),
                                                  extendedThru: it.extendedThru?.toString()] }
    }

    private void extend(String tenant, String auditId, String period, String thru) {
        ec.service.sync().name("${REPORTS}.set#AuditPeriodExtension").parameters([companyUserGroupId: tenant, auditId: auditId,
                period: period, continueThruDate: thru ? Date.valueOf(thru) : null]).call()
        noErrors()
    }

    private void extendExpectingError(String tenant, String auditId, String period, String thru, String expected) {
        ec.service.sync().name("${REPORTS}.set#AuditPeriodExtension").parameters([companyUserGroupId: tenant, auditId: auditId,
                period: period, continueThruDate: Date.valueOf(thru)]).call()
        assertTrue(ec.message.errorsString.contains(expected), "${expected} / ${ec.message.errorsString}")
        ec.message.clearErrors()
    }

    private long extensionRows(String period) {
        return ec.entity.find("darpan.audit.AuditPeriodExtension").condition("auditId", MONTH_CLOSE).condition("period", period).count()
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
}
