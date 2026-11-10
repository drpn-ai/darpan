package darpan.audit

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-207 — the run update's fact sheet, CSV and message, from a report document and findings-by-order units alone
 * (no Moqui). The shapes are cut down to the keys AuditRunFacts and AuditRunMessage read.
 */
class AuditRunFactsTests {

    private static final String RMA = "Return has an RMA, no credit memo"
    private static final String BASE = "https://app.example"

    @Test
    void groupsCountDistinctOrdersAndReturnsAndListIdsOnlyUnderTwentyFive() {
        Map facts = AuditRunFacts.build(report(), units()) { String rule, String cause, String id -> "Bill order {recordKey} in NetSuite {orderInNetsuite.tranId}." }
        List<Map> groups = facts.groups as List<Map>
        assertEquals(["R2|Unexplained", "R2|Payment failed", "R2|Line not billed", "R2|Line short", "R10|${RMA}".toString()],
                groups.collect { "${it.rule}|${it.cause}".toString() }, "rule number first (R2 before R10), then count")

        Map unexplained = groups[0]
        assertEquals(30, unexplained.count)
        assertEquals([], unexplained.ids, "25 or more: no ids, the CSV has them")

        Map failed = groups[1]
        assertEquals(24, failed.count)
        assertEquals(24, (failed.ids as List).size())
        assertEquals("M201 #GOR201", (failed.ids as List)[0], "HotWax id then order name")

        // One order with two lines of different causes counts once in each cause; two lines of one cause count once.
        assertEquals(1, groups[2].count)
        assertEquals(1, groups[3].count)
        assertEquals(1, groups[4].count, "two findings on one return are one return")
        assertEquals(["RX-1 #GOR900"], groups[4].ids)

        assertEquals("Bill order in NetSuite.", failed.fixText, "unfilled placeholders are cut with their spacing")
        assertEquals([open: 57, newToday: 3, fixedToday: 1], facts.totals)
        assertEquals(["R2", "R9", "R10"], (facts.rules as List<Map>)*.id, "rules by number")
        assertEquals(["shopify-vs-netsuite"], (facts.rules as List<Map>).find { it.id == "R9" }.checksNotRun)
    }

    @Test
    void csvEscapesAsRfc4180AndNeverHandsASpreadsheetAFormula() {
        assertEquals('"a,b"', AuditRunFacts.csvField("a,b"))
        assertEquals('"say ""hi"""', AuditRunFacts.csvField('say "hi"'))
        assertEquals('"two\nlines"', AuditRunFacts.csvField("two\nlines"))
        assertEquals("'=SUM(A1)", AuditRunFacts.csvField("=SUM(A1)"))
        assertEquals("-12.50", AuditRunFacts.csvField("-12.50"), "a negative amount stays a number")
        assertEquals("", AuditRunFacts.csvField(null))
        assertEquals("a,\"b,c\"\r\nd,\r\n", AuditRunFacts.csv([["a", "b,c"], ["d", null]]))
    }

    @Test
    void eachRuleWithOpenFindingsGetsOneCsvRowPerFinding() {
        Map<String, String> csvs = AuditRunFacts.csvByRule(units()) { String rule, String cause -> cause == RMA ? "Issue the memo, then close" : null }
        assertEquals(["R2", "R10"], csvs.keySet() as List)
        List<String> r10 = csvs.R10.split("\r\n") as List<String>
        assertEquals(AuditRunFacts.CSV_HEADER.join(","), r10[0])
        assertEquals("R10,\"${RMA}\",F-900,RX-1,#GOR900,,,2026-11-02,8,\"Issue the memo, then close\"".toString(), r10[1])
        assertEquals(3, r10.size(), "header and one row per finding")
        assertEquals(1 + 30 + 24 + 2, csvs.R2.split("\r\n").length, "the mixed order's two lines are two rows")
    }

    @Test
    void theCardIsASummaryAndOneLinkNothingElse() {
        Map facts = AuditRunFacts.build(report(), units()) { String rule, String cause, String id -> null }
        Map card = AuditRunMessage.card(facts, [:], BASE)
        assertEquals("Gorjana · Month close: 57 open findings", card.text)
        Map body = (card.cardsV2 as List<Map>)[0].card as Map
        assertEquals([title: "Gorjana · Month close", subtitle: "Run of 10 Nov 2026 · 1–10 November",
                      imageUrl: "https://drpn.ai/favicon.png", imageType: "CIRCLE"], body.header)
        List<Map> sections = body.sections as List<Map>
        assertEquals(1, sections.size(), "no rule sections")
        List<Map> widgets = sections[0].widgets as List<Map>
        assertEquals(3, widgets.size(), "findings line, what did not run, the button: no headline without a writer")
        assertTrue((widgets[0].decoratedText.text as String).contains(">57 open</font>  ·  3 new  ·  "))
        assertTrue((widgets[1].decoratedText.text as String).contains("R9 did not run"))
        List buttons = widgets[2].buttonList.buttons as List
        assertEquals(1, buttons.size())
        assertEquals("Open the report", buttons[0].text)
        assertEquals("${BASE}/audits/month-close?period=2026-11&date=2026-11-10".toString(), buttons[0].onClick.openLink.url)

        Map withWords = AuditRunMessage.card(facts, [headline: "Thirty <orders> wait on a cause."], null)
        List<Map> w = ((withWords.cardsV2 as List<Map>)[0].card.sections as List<Map>)[0].widgets as List<Map>
        assertEquals("Thirty &lt;orders&gt; wait on a cause.", w[1].textParagraph.text)
        assertFalse(w.any { it.buttonList }, "no app address, no button")
    }

    @Test
    void theEmailCarriesEveryDetailAndFallsBackToEachCausesOwnFix() {
        Map facts = AuditRunFacts.build(report(), units()) { String rule, String cause, String id -> cause == RMA ? "Issue the credit memo {creditMemo}" : null }
        assertEquals("Gorjana Month close, 1–10 November: 57 open findings", AuditRunMessage.subject(facts))
        String html = AuditRunMessage.emailHtml(facts, [:], BASE)
        assertTrue(html.contains("ACT ON FIRST"))
        assertTrue(html.contains(">Issue the credit memo</div>"), "no writer: the cause's own fix")
        assertTrue(html.contains("gorjana-month-close-2026-11-R10.csv"))
        assertTrue(html.contains("${BASE}/audits/month-close?period=2026-11&amp;date=2026-11-10&amp;statement=R10".toString()))
        assertTrue(html.contains("M201 #GOR201, M202 #GOR202"), "ids under 25 listed")
        assertTrue(html.contains("No cause named yet"), "Unexplained reads as no cause yet")
        assertFalse(html.contains("font-weight"), "no bold")
        assertTrue(html.contains("R9 did not run on this morning."))

        String written = AuditRunMessage.emailHtml(facts, [headline: "Mostly payments.", fixes: [("R10|" + RMA): "Credit each return."]], BASE)
        assertTrue(written.contains(">Credit each return.</div>"), "the writer's sentence wins")
        assertTrue(written.contains("Mostly payments."))

        String text = AuditRunMessage.emailText(facts, [:], null)
        assertTrue(text.contains("R10 · 1 open · 0 new · Every return is credited"), text)
        assertTrue(text.contains("  All 1 in gorjana-month-close-2026-11-R10.csv\n"), text)
        assertFalse(text.contains("http"), "no app address, no links")
    }

    @Test
    void placeholdersLeaveNoGapsAndAnEmptyFixIsNone() {
        assertEquals("Bill it in NetSuite.", AuditRunFacts.stripPlaceholders("Bill {recordKey} it in NetSuite {x}."))
        assertNull(AuditRunFacts.stripPlaceholders("{recordKey}"))
        assertNull(AuditRunFacts.stripPlaceholders(null))
    }

    private static Map report() {
        return [report    : [tenant: [id: "GORJANA", name: "Gorjana"], audit: [id: "month-close", name: "Month close", period: "month"],
                             date  : "2026-11-10", period: "2026-11", window: [from: "2026-11-01", thru: "2026-11-10"]],
                summary   : [open: 57, newToday: 3, fixedToday: 1],
                categories: [
                        orders : [statements: [
                                R2: [id: "R2", text: "Every order is billed in NetSuite. More words.", counts: [open: 56, newToday: 3, fixedToday: 1],
                                     checks: [[id: "orders-billed", status: "ran"]]],
                                R9: [id: "R9", text: "Every Shopify order is in NetSuite.", counts: [open: 0, newToday: 0, fixedToday: 0],
                                     checks: [[id: "shopify-vs-netsuite", status: "notRun"]]]]],
                        returns: [statements: [
                                R10: [id: "R10", text: "Every return is credited.", counts: [open: 1, newToday: 0, fixedToday: 0],
                                      checks: [[id: "returns-credited", status: "ran"]]]]]]]
    }

    /** 30 unexplained R2 orders, 24 with a failed payment, one order with two lines of two causes, two findings on one return. */
    private static List<Map> units() {
        List<Map> out = []
        (1..30).each { int i -> out << order("M1${i.toString().padLeft(2, '0')}", "Unexplained", "F-1${i}") }
        (1..24).each { int i -> out << order("M2${i.toString().padLeft(2, '0')}", "Payment failed", "F-2${i}") }
        out << [id: "M300", order: "M300", statement: "R2", cause: null, firstFound: "2026-11-05", ageDays: 5,
                records: [OMS: [orderItem: [[id: "M300/01", orderName: "#GOR300"]]]],
                lines: [[id: "F-301", cause: "Line not billed", firstFound: "2026-11-05", ageDays: 5],
                        [id: "F-302", cause: "Line short", firstFound: "2026-11-06", ageDays: 4]]]
        [["F-900", "2026-11-02", 8], ["F-901", "2026-11-03", 7]].each { List f ->
            out << [id: f[0], statement: "R10", cause: RMA, firstFound: f[1], ageDays: f[2],
                    records: [OMS: ["return": [[id: "RX-1", orderName: "#GOR900"]]]]]
        }
        return out
    }

    private static Map order(String id, String cause, String lineId) {
        return [id: id, order: id, statement: "R2", cause: cause, firstFound: "2026-11-01", ageDays: 9,
                records: [OMS: [orderItem: [[id: "${id}/01".toString(), orderName: "#GOR${id.substring(1)}".toString()]]],
                          NetSuite: [salesOrder: [[id: "SO${id.substring(1)}".toString()]]],
                          Shopify: [order: [[id: "55${id.substring(1)}".toString()]]]],
                lines: [[id: lineId, cause: cause, firstFound: "2026-11-01", ageDays: 9]]]
    }
}
