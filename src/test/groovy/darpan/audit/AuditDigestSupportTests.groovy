package darpan.audit

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-102 — the digest text, from a report document alone (no Moqui). The documents here are cut-down
 * darpan.audit.report/v1 shapes: only the keys the digest reads.
 */
class AuditDigestSupportTests {

    private static final String URL = "https://app.example/audits/month-close?date=2026-10-22"

    @Test
    void aDailyDigestCountsTheMorningByCategoryAndNamesTheOldestOpen() {
        String text = AuditDigestSupport.render(report(), AuditDigestSupport.DAILY, false, null, URL)
        assertEquals([
                "Month close · 2026-10 · Thu 22 Oct",
                "",
                "Open 2 · Pending 1 · New today 1 · Fixed today 1",
                "Orders: open 1, pending 1",
                "Returns: open 1",
                "",
                "Oldest open",
                "F-1 · R2 · 6 days · Invoice INV1 has no Etail id",
                "F-3 · R8 · 1 day · Return RX-1 has no RMA in NetSuite",
                "",
                "Report: ${URL}".toString()].join("\n"), text)
    }

    @Test
    void aReportWithACheckThatDidNotRunSaysSoInTheDigest() {
        Map doc = report()
        doc.health = [complete: false, summary: "Not complete: check invoices-without-etail-id did not run.",
                      checks: [ran: 1, partial: 0, notRun: 1]]
        String text = AuditDigestSupport.render(doc, AuditDigestSupport.DAILY, false, null, null)
        assertEquals("Not complete: check invoices-without-etail-id did not run.", text.readLines()[1],
                "said under the title, before any count")
        assertFalse(text.contains("Report:"), "no app address, no link line")
    }

    @Test
    void aCompleteMorningSaysNothingAboutHealth() {
        String text = AuditDigestSupport.render(report(), AuditDigestSupport.DAILY, false, null, null)
        assertFalse(text.contains("complete"), text)
    }

    @Test
    void aWeeklySummaryStandsEachCountBesideAWeekBefore() {
        Map before = [summary: [open: 5, pending: 0]]
        String text = AuditDigestSupport.render(report(), AuditDigestSupport.WEEKLY, false, before, null)
        List<String> lines = text.readLines()
        assertEquals("Month close · 2026-10 · week to Thu 22 Oct", lines[0])
        assertEquals("Open 2 (5 a week ago) · Pending 1 (0 a week ago)", lines[2])
        assertFalse(text.contains("New today"), "today's movement is the daily digest's")
    }

    @Test
    void aFullListNamesEveryOpenAndPendingFinding() {
        String text = AuditDigestSupport.render(report(), AuditDigestSupport.DAILY, true, null, null)
        List<String> lines = text.readLines()
        int open = lines.indexOf("Open (2)")
        int pending = lines.indexOf("Pending (1)")
        assertTrue(open > 0 && pending > open, text)
        assertEquals("F-2 · R2 · 0 days · Invoice INV2 has no Etail id", lines[pending + 1])
        assertFalse(text.contains("more in the report"), "everything fit")
    }

    @Test
    void aFullListTooLongForOneMessageSaysHowManyItLeftOut() {
        Map doc = report()
        List findings = (1..400).collect { int i ->
            [id: "F-${1000 + i}".toString(), headline: "Invoice INV${i} has no Etail id", state: "open", ageDays: i % 9]
        }
        doc.categories.orders.statements.R2.findings = findings
        doc.categories.orders.statements.R2.findingsOmitted = 25
        String text = AuditDigestSupport.render(doc, AuditDigestSupport.DAILY, true, null, URL)
        assertTrue(text.length() <= AuditDigestSupport.MESSAGE_LIMIT, "${text.length()}")
        int shown = text.readLines().count { it.startsWith("F-") } as int
        int left = (text.readLines().find { it.contains("more in the report") } =~ /(\d+) more/)[0][1] as int
        assertEquals(400 + 1 + 25, shown + left, "shown plus left out is every finding (R2's 400, R8's one, the 25 omitted)")
        assertTrue(text.endsWith("Report: ${URL}"), "the link survives the cut")
    }

    @Test
    void nothingOpenSaysSo() {
        Map doc = report()
        doc.summary = [open: 0, pending: 0, newToday: 0, fixedToday: 2]
        doc.categories = [orders: [label: "Orders", counts: [open: 0, pending: 0], statements: [:]]]
        String text = AuditDigestSupport.render(doc, AuditDigestSupport.DAILY, false, null, null)
        assertEquals("Nothing open or pending.", text.readLines().last())
    }

    @Test
    void theOldestOpenListStopsAtFive() {
        Map doc = report()
        doc.categories.orders.statements.R2.findings = (1..8).collect { int i ->
            [id: "F-${i}".toString(), headline: "h${i}", state: "open", ageDays: i]
        }
        List<String> lines = AuditDigestSupport.render(doc, AuditDigestSupport.DAILY, false, null, null).readLines()
        List<String> shown = lines.findAll { it.startsWith("F-") }
        assertEquals(["F-8", "F-7", "F-6", "F-5", "F-4"], shown.collect { it.split(" · ")[0] }, "oldest first")
    }

    /** One morning: R2 has an open and a pending finding, R8 an open one; R2 also fixed one today. */
    private static Map report() {
        return [
                schema    : "darpan.audit.report/v1",
                report    : [audit: [id: "month-close", name: "Month close"], date: "2026-10-22", period: "2026-10"],
                health    : [complete: true, summary: "Every configured check ran over complete reads.",
                             checks: [ran: 2, partial: 0, notRun: 0]],
                summary   : [open: 2, pending: 1, newToday: 1, fixedToday: 1],
                categories: [
                        orders : [label: "Orders", counts: [open: 1, pending: 1], statements: [
                                R2: [id: "R2", findingsOmitted: 0, findings: [
                                        [id: "F-1", headline: "Invoice INV1 has no Etail id", state: "open", ageDays: 6],
                                        [id: "F-2", headline: "Invoice INV2 has no Etail id", state: "pending", ageDays: 0],
                                        [id: "F-9", headline: "Invoice INV9 has no Etail id", state: "fixed", ageDays: 3]]]]],
                        refundsAndCredits: [label: "Refunds and credits", counts: [open: 0, pending: 0], statements: [:]],
                        returns: [label: "Returns", counts: [open: 1, pending: 0], statements: [
                                R8: [id: "R8", findingsOmitted: 0, findings: [
                                        [id: "F-3", headline: "Return RX-1 has no RMA in NetSuite", state: "open", ageDays: 1]]]]],
                ],
        ]
    }
}
