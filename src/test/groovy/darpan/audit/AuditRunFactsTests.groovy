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
    private static final darpan.email.EmailCopyReader COPY = darpan.email.SeedCopy.reader(AuditRunMessage.TEMPLATE)

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
        assertEquals([open: 57, acknowledged: 0, newToday: 3, fixedToday: 1], facts.totals)
        assertEquals(["R2", "R9", "R10"], (facts.rules as List<Map>)*.id, "rules by number")
        assertEquals(["shopify-vs-netsuite"], (facts.rules as List<Map>).find { it.id == "R9" }.checksNotRun)
        assertEquals([1, 1, 1], (facts.rules as List<Map>)*.checksTotal, "every rule carries how many checks it has")
    }

    // DAR-BE-250: statements follow the order's story, so the update lists rules and groups by each statement's step.
    @Test
    void rulesAndGroupsFollowTheStatementsStepsWhenTheReportGivesThem() {
        Map stepped = report()
        Map statements = [:]
        stepped.categories.each { k, cat -> statements.putAll(cat.statements as Map) }
        statements.R10.step = 40
        statements.R2.step = 80
        statements.R9.step = 120
        Map facts = AuditRunFacts.build(stepped, units()) { String rule, String cause, String id -> "" }
        assertEquals(["R10", "R2", "R9"], (facts.rules as List<Map>)*.id, "R10 is step 4 of the story, R2 step 8")
        assertEquals("R10", (facts.groups as List<Map>)[0].rule)
    }

    // DAR-OPS-029 S11 (S10 QA): step 0 sorts first; only a statement with no step sorts last.
    @Test
    void stepZeroSortsFirstAndNoStepLast() {
        Map<String, Long> steps = [R5: 0L, R2: 10L]
        assertEquals(["R5", "R2", "R1"], ["R1", "R2", "R5"].sort(false) { String a, String b -> AuditRunFacts.stepOrder(steps, a, b) })
    }

    @Test
    void csvEscapesAsRfc4180AndNeverHandsASpreadsheetAFormula() {
        assertEquals('"a,b"', AuditRunFacts.csvField("a,b"))
        assertEquals('"say ""hi"""', AuditRunFacts.csvField('say "hi"'))
        assertEquals('"two\nlines"', AuditRunFacts.csvField("two\nlines"))
        assertEquals("'=SUM(A1)", AuditRunFacts.csvField("=SUM(A1)"))
        assertEquals("-12.50", AuditRunFacts.csvField("-12.50"), "a negative amount stays a number")
        assertEquals("'-1+cmd|' /C calc'!A0", AuditRunFacts.csvField("-1+cmd|' /C calc'!A0"), "a - that is not a number is a formula")
        assertEquals("'\t=1+1", AuditRunFacts.csvField("\t=1+1"), "a leading tab hides a formula")
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

    // DAR-BE-358: Darpan's own CSV of every finding no cause explains, across rules; a line with a cause of its own is
    // explained even when its order has none (M300), and a run with nothing Unexplained sends nothing.
    @Test
    void theUnexplainedCsvHoldsOnlyFindingsNoCauseExplainsAndNothingWhenThereAreNone() {
        Map out = AuditRunFacts.unexplainedCsv(units(), "GORJANA", "month-close") { String rule, String cause -> null }
        assertEquals(30, out.count)
        List<String> rows = (out.csv as String).split("\r\n") as List<String>
        assertEquals(AuditRunFacts.UNEXPLAINED_HEADER.join(","), rows[0])
        assertEquals(31, rows.size(), "header and the 30 Unexplained orders; M300's lines and R10's returns have causes")
        assertTrue(rows[1].startsWith("GORJANA,month-close,R2,Unexplained,F-11,M101,"), rows[1])
        assertEquals(null, AuditRunFacts.unexplainedCsv(units().findAll { it.cause != "Unexplained" }, "GORJANA", "month-close", null))

        Map facts = AuditRunFacts.build(report(), units()) { String rule, String cause, String id -> null }
        Map mail = AuditRunMessage.unexplainedEmail(facts, 30, COPY)
        assertEquals("Gorjana Month close: 30 Unexplained findings", mail.subject)
        assertTrue((mail.text as String).startsWith("The 10 Nov 2026 run of Gorjana Month close left 30 findings with no cause."), mail.text as String)
        assertEquals("gorjana-month-close-2026-11-10-unexplained.csv", mail.fileName)
    }

    @Test
    void theCardIsASummaryAndOneLinkNothingElse() {
        Map facts = AuditRunFacts.build(report(), units()) { String rule, String cause, String id -> null }
        Map card = AuditRunMessage.card(facts, [:], BASE, COPY)
        // the card alone in Chat: the one line is fallbackText (notifications, Slack), never text above the card
        assertEquals("Gorjana · Month close: 57 open findings", card.fallbackText)
        assertFalse(card.containsKey("text"))
        Map body = (card.cardsV2 as List<Map>)[0].card as Map
        assertEquals([title: "Gorjana · Month close", subtitle: "Run of 10 Nov 2026 · 1–10 November"], body.header)
        List<Map> sections = body.sections as List<Map>
        assertEquals(1, sections.size(), "no rule sections")
        List<Map> widgets = sections[0].widgets as List<Map>
        // DAR-BE-378: what did not run goes to Darpan's alerts room, never the client's card
        assertEquals(2, widgets.size(), "findings line and the button: no headline without a writer, no did-not-run line")
        assertTrue((widgets[0].decoratedText.text as String).contains(">57 open</font>  ·  3 new  ·  "))
        assertFalse(card.toString().contains("did not run"), "the client's card never says what did not run")
        List buttons = widgets[1].buttonList.buttons as List
        assertEquals(1, buttons.size())
        assertEquals("Open the report", buttons[0].text)
        // DAR-BE-293: the link names its tenant, last, as a run link does, so the app switches into it.
        assertEquals("${BASE}/audits/month-close?period=2026-11&date=2026-11-10&tenantId=GORJANA".toString(), buttons[0].onClick.openLink.url)

        Map withWords = AuditRunMessage.card(facts, [headline: "Thirty <orders> wait on a cause."], null, COPY)
        List<Map> w = ((withWords.cardsV2 as List<Map>)[0].card.sections as List<Map>)[0].widgets as List<Map>
        assertEquals("Thirty &lt;orders&gt; wait on a cause.", w[1].textParagraph.text)
        assertFalse(w.any { it.buttonList }, "no app address, no button")
    }

    @Test
    void theReadsLineIsDarpansAloneNeverTheClients() {
        // DAR-BE-320 phase 1b, OC102 (Aditi 2026-11-28: "this part is for darpan team not the client... dont ever send this to
        // client"): the read and cost line goes to Darpan's alerts room (staffText), never the client's email or card.
        Map facts = AuditRunFacts.build(report(), units()) { String rule, String cause, String id -> null }
        assertNull(AuditRunMessage.readsLine(facts, COPY))

        Map flagged = facts + [reads: [reads: 9, connections: 4, calls: 214L, flags: 2]]
        String line = "This morning Darpan read your systems 9 times over 4 connections. 214 API calls were counted. " +
                "2 of those reads could cost less; the Darpan team reviews them."
        assertEquals(line, AuditRunMessage.readsLine(flagged, COPY))
        assertFalse(AuditRunMessage.emailHtml(flagged, [:], BASE, COPY).contains("read your systems"))
        assertFalse(AuditRunMessage.emailText(flagged, [:], null, COPY).contains("read your systems"))
        assertFalse(AuditRunMessage.card(flagged, [:], BASE, COPY).toString().contains("read your systems"))
        assertTrue(AuditRunMessage.staffText(flagged, BASE, COPY).contains(line), "Darpan's alerts room gets it")

        assertEquals("This morning Darpan read your systems 3 times over 0 connections.",
                AuditRunMessage.readsLine(facts + [reads: [reads: 3, connections: 0, calls: 0L, flags: 0]], COPY), "no tally, no flags: the reads alone")
    }

    @Test
    void theEmailCarriesEveryDetailAndFallsBackToEachCausesOwnFix() {
        Map facts = AuditRunFacts.build(report(), units()) { String rule, String cause, String id -> cause == RMA ? "Issue the credit memo {creditMemo}" : null }
        assertEquals("Gorjana Month close, 1–10 November: 57 open findings", AuditRunMessage.subject(facts, darpan.email.SeedCopy.reader("DARPAN_AUDIT_RUN")))
        String html = AuditRunMessage.emailHtml(facts, [:], BASE, COPY)
        assertTrue(html.contains("ACT ON FIRST"))
        assertTrue(html.contains(">Issue the credit memo</div>"), "no writer: the cause's own fix")
        assertTrue(html.contains("gorjana-month-close-2026-11-R10.csv"))
        assertTrue(html.contains("${BASE}/audits/month-close?period=2026-11&amp;date=2026-11-10&amp;statement=R10&amp;tenantId=GORJANA".toString()))
        assertTrue(html.contains("M201 #GOR201, M202 #GOR202"), "ids under 25 listed")
        assertTrue(html.contains("No cause named yet"), "Unexplained reads as no cause yet")
        assertFalse(html.contains("font-weight"), "no bold")
        List<String> hues = (html =~ /#[0-9a-fA-F]{6}\b/).collect { it as String }.findAll { String c -> c[1..2] != c[3..4] || c[3..4] != c[5..6] }
        assertTrue(hues.isEmpty(), "the email wears the app's monochrome light theme, greys only: ${hues.unique()}".toString())
        assertTrue(html.contains("'IBM Plex Mono'"), "the app's typeface")
        // F-E6: the notice goes to the alerts room only; the email keeps R9's neutral row.
        assertFalse(html.contains("Darpan has been alerted"), "no notice in the client's email")
        assertTrue(html.contains("Not checked this morning."), "R9 never reads clean: its neutral row (F-E6)")
        assertTrue(AuditRunMessage.alertText(facts, BASE, COPY).contains("R9 did not run this morning. Darpan has been alerted and is working on a fix."))

        String written = AuditRunMessage.emailHtml(facts, [headline: "Mostly payments.", fixes: [("R10|" + RMA): "Credit each return."]], BASE, COPY)
        assertTrue(written.contains(">Credit each return.</div>"), "the writer's sentence wins")
        assertTrue(written.contains("Mostly payments."))

        String text = AuditRunMessage.emailText(facts, [:], null, COPY)
        assertTrue(text.contains("R10 · 1 open · 0 new · Every return is credited"), text)
        assertTrue(text.contains("  All 1 in gorjana-month-close-2026-11-R10.csv\n"), text)
        // no app address, no app links; the only addresses are the user guide's (DAR-BE-298)
        assertFalse(text.replace("https://docs.drpn.ai/", "").contains("http"), text)
    }

    // DAR-BE-252: findings with no cause are still findings; the email invites the reader to explain them to the agent,
    // on the rule's page with the Agent tab open. Only under a no-cause group, and only when there is an app address.
    @Test
    void aNoCauseGroupInvitesTheReaderToExplainItToTheAgent() {
        Map facts = AuditRunFacts.build(report(), units()) { String rule, String cause, String id -> null }
        String agentUrl = "${BASE}/audits/month-close?period=2026-11&date=2026-11-10&statement=R2&tab=agent&tenantId=GORJANA".toString()
        String invite = COPY.text("cause.explain")
        assertTrue(invite && invite != "cause.explain", "the words are a seeded copy block")

        String html = AuditRunMessage.emailHtml(facts, [:], BASE, COPY)
        assertTrue(html.contains("href=\"${agentUrl.replace('&', '&amp;')}\"".toString()), "R2's Unexplained group links its Agent tab")
        assertTrue(html.contains(">${AuditRunMessage.esc(invite)}</a>".toString()))
        assertEquals(1, html.count("tab=agent"), "R10 has only named causes: no invitation there")

        String text = AuditRunMessage.emailText(facts, [:], BASE, COPY)
        assertTrue(text.contains("      ${invite}: ${agentUrl}\n".toString()), text)

        assertFalse(AuditRunMessage.emailHtml(facts, [:], null, COPY).contains(invite), "no app address: no invitation")
        assertFalse(AuditRunMessage.emailText(facts, [:], null, COPY).contains(invite))
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
