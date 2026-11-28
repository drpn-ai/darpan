package darpan.audit

import darpan.email.EmailCopyReader
import darpan.email.SeedCopy
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-015. The client's run update says a statement's checks did not run or partly ran (a neutral row, plus its counts),
 * never "0 open / clean". F-E6 (Aditi 2026-11-28): the notice ("Darpan has been alerted…") goes to Darpan's alerts room only
 * (alertText), never the client's email or card. Pure: facts maps through the seeded copy defaults.
 */
class AuditRunMessageTests {
    private static final EmailCopyReader COPY = SeedCopy.reader(AuditRunMessage.TEMPLATE)
    private static final String NOT_RUN = "R1 did not run this morning. Darpan has been alerted and is working on a fix."
    private static final String PARTLY = "R2 partly ran this morning: 1 of 5 checks did not run. Darpan has been alerted and is working on a fix."

    private static Map facts() {
        return [tenant: [id: "T", name: "Tenant"], audit: [id: "a", name: "Audit"], period: "month", date: "2026-11-10",
                window: [from: "2026-11-01", thru: "2026-11-10"], totals: [open: 3, newToday: 0, fixedToday: 0], groups: [],
                rules : [[id: "R1", text: "Every order is billed.", open: 0, newToday: 0, fixedToday: 0, checksNotRun: ["c1"], checksTotal: 1],
                         [id: "R2", text: "Every order ships.", open: 3, newToday: 0, fixedToday: 0, checksNotRun: ["c2"], checksTotal: 5],
                         [id: "R3", text: "Every return is credited.", open: 0, newToday: 0, fixedToday: 0, checksNotRun: [], checksTotal: 2]]]
    }

    private static final String ALERTED = "Darpan has been alerted and is working on a fix."
    private static final String NOTICE = "R1 did not run this morning. R2 partly ran this morning. ${ALERTED}"
    private static final String ROW_NOT_RUN = "Not checked this morning."
    private static final String ROW_PARTLY = "1 of 5 checks not checked this morning."

    private static int times(String text, String part) { return text.split(java.util.regex.Pattern.quote(part), -1).length - 1 }

    // OC102 (Aditi 2026-11-28, "dont ever send this to client"): no client render of a run (email text, HTML email, chat card)
    // carries what Darpan read or what it cost; Darpan's alerts room does, and only when there is something to act on.
    @Test
    void noClientRenderCarriesTheReadOrCostLine() {
        Map f = facts() + [reads: [reads: 9, connections: 4, calls: 214L, flags: 2]]
        List<String> client = [AuditRunMessage.emailText(f, [:], "https://app.example", COPY),
                               AuditRunMessage.emailHtml(f, [:], "https://app.example", COPY),
                               AuditRunMessage.card(f, [:], "https://app.example", COPY).toString()]
        ["read your systems", "API calls", "could cost less"].each { String words ->
            client.eachWithIndex { String render, int i -> assertFalse(render.contains(words), "client render ${i} carries '${words}'") }
        }
        String staff = AuditRunMessage.staffText(f, "https://app.example", COPY)
        assertTrue(staff.contains("read your systems") && staff.contains("could cost less") && staff.contains(ALERTED), staff)

        Map clean = facts() + [reads: [reads: 3, connections: 1, calls: 0L, flags: 0]]
        clean.rules.each { Map r -> r.checksNotRun = [] }
        assertEquals(null, AuditRunMessage.staffText(clean, "https://app.example", COPY), "a clean run with clean reads posts nothing")
    }

    @Test
    void aRuleWhoseOnlyCheckDidNotRunShowsTheShortRowAndTheNoticeNamesItNotZero() {
        String text = AuditRunMessage.emailText(facts(), [:], null, COPY)
        assertTrue(text.contains("  R1  Every order is billed: ${ROW_NOT_RUN}\n"), text)
        assertFalse(text.contains("R1  Every order is billed: clean"))
        assertFalse(text.contains(ALERTED), "F-E6: no notice in the client's email: ${text}")
        assertTrue(AuditRunMessage.alertText(facts(), null, COPY).contains(NOTICE),
                "the alerts room's notice names wholly not-run rules, then partly ran ones, then the alerted sentence once")
        assertTrue(text.contains("  R3  Every return is credited: clean"), "a rule that ran shows counts as before")
    }

    @Test
    void aRuleWithOneOfFiveChecksNotRunShowsPartlyAndItsCounts() {
        String line = AuditRunMessage.emailText(facts(), [:], null, COPY).readLines().find { it.startsWith("  R2  ") }
        assertTrue(line.contains(ROW_PARTLY) && line.contains("3 open"), line)
    }

    @Test
    void aNotRunRuleKeepsItsOpenCount() {
        Map f = facts()
        f.rules[0] = f.rules[0] + [open: 3]
        String line = AuditRunMessage.emailText(f, [:], null, COPY).readLines().find { it.startsWith("  R1  ") }
        assertTrue(line.endsWith("${ROW_NOT_RUN} · 3 open"), line)
        assertTrue(AuditRunMessage.emailHtml(f, [:], null, COPY).contains("${ROW_NOT_RUN} · 3 open"))
    }

    @Test
    void checksNotRunWithoutATotalCountsAsWhollyNotRun() {
        Map f = facts()
        f.rules[1] = [id: "R2", text: "Every order ships.", open: 0, newToday: 0, fixedToday: 0, checksNotRun: ["c2"]]
        String line = AuditRunMessage.emailText(f, [:], null, COPY).readLines().find { it.startsWith("  R2  ") }
        assertTrue(line.endsWith(ROW_NOT_RUN), line)
    }

    // DAR-BE-358 (Aditi 2026-11-25: "emails are expected to have personality... not dry"). Darpan's own Unexplained note
    // wears every Darpan email's shape: frame, what happened with a wink, the fact, what next, the sign-off, the guides.
    // DAR-BE-361: one "N acknowledged" line, in the email's html and text, only when some are.
    @Test
    void acknowledgedLineOnlyWhenSome() {
        Map f = facts()
        f.totals = f.totals + [acknowledged: 3]
        String text = AuditRunMessage.emailText(f, [:], null, COPY)
        assertTrue(text.contains("3 acknowledged"), text)
        assertEquals(1, times(text, "3 acknowledged"))
        assertTrue(AuditRunMessage.emailHtml(f, [:], null, COPY).contains("3 acknowledged"))
        f.totals = f.totals + [acknowledged: 0]
        assertFalse(AuditRunMessage.emailText(f, [:], null, COPY).contains("acknowledged"))
        assertFalse(AuditRunMessage.emailHtml(f, [:], null, COPY).contains("acknowledged"))
        assertFalse(AuditRunMessage.emailText(facts(), [:], null, COPY).contains("acknowledged"), "no key, no line")
    }

    @Test
    void theUnexplainedNoteHasTheEmailShapeInHtmlAndText() {
        Map email = AuditRunMessage.unexplainedEmail(facts(), 4, COPY)
        assertEquals("Tenant Audit: 4 Unexplained findings", email.subject, "the subject stays plain")
        String intro = "The 10 Nov 2026 run of Tenant Audit left 4 findings with no cause. Unsolved, for now."
        String body = "Each one is a row in the attached CSV."
        String next = "Give one a conclusion and the next run files its lookalikes under it. One less mystery every morning."
        String html = email.html as String
        assertTrue(html.contains("DARPAN · AUDIT"), "the frame's eyebrow")
        [intro, body, next].each { assertEquals(1, times(html, it), it) }
        assertTrue(html.indexOf(intro) < html.indexOf(body) && html.indexOf(body) < html.indexOf(next), "intro, fact, then what next")
        assertTrue(html.contains("— Darpan") && html.contains("guides/audits#resolve-a-finding"), "signed, with the guides")
        String text = email.text as String
        assertTrue(text.startsWith(intro), text)
        [body, next, "— Darpan", "docs.drpn.ai/guides/audits"].each { assertTrue(text.contains(it), it) }
        assertEquals("t-a-2026-11-10-unexplained.csv", email.fileName)
    }

    @Test
    void aPartlyRanRuleIsNeverSaidNotToHaveRun() {
        Map f = facts()
        f.rules[0] = f.rules[0] + [checksNotRun: []]
        String alert = AuditRunMessage.alertText(f, null, COPY)
        assertTrue(alert.contains("R2 partly ran this morning. ${ALERTED}"), alert)
        assertFalse(alert.contains("did not run this morning. Darpan"), "no rule wholly not run, so no 'did not run' notice: ${alert}")
        assertTrue(AuditRunMessage.emailText(f, [:], null, COPY).contains(ROW_PARTLY), "the email keeps R2's neutral row")
        assertEquals(0, times(AuditRunMessage.card(f, [:], null, COPY).toString(), ALERTED), "DAR-BE-378: never in the client's card")
    }

    @Test
    void onlyWhollyNotRunRulesGetTheDidNotRunNotice() {
        Map f = facts()
        f.rules[1] = f.rules[1] + [checksNotRun: []]
        String alert = AuditRunMessage.alertText(f, null, COPY)
        assertTrue(alert.contains("R1 did not run this morning. ${ALERTED}"), alert)
        assertFalse(alert.contains("partly ran"), alert)
        assertEquals(1, times(alert, ALERTED))
    }

    // F-E6 (Aditi 2026-11-28): the alerted sentence reaches the alerts room once and the client nowhere; the email keeps a
    // neutral row for each rule that did not or partly ran, so neither reads clean.
    @Test
    void theAlertedSentenceGoesOnlyToTheAlertsRoomAndTheEmailKeepsTheNeutralRows() {
        String text = AuditRunMessage.emailText(facts(), [:], null, COPY)
        String html = AuditRunMessage.emailHtml(facts(), [:], null, COPY)
        assertEquals(0, times(text, ALERTED), text)
        assertEquals(0, times(html, ALERTED))
        assertFalse(html.contains(NOTICE) || text.contains(NOTICE))
        assertTrue(html.contains(ROW_NOT_RUN) && html.contains(ROW_PARTLY) && html.contains("3 open"))
        assertTrue(text.contains(ROW_NOT_RUN) && text.contains(ROW_PARTLY))
        assertEquals(1, times(AuditRunMessage.alertText(facts(), null, COPY), ALERTED))
        // DAR-BE-378: what did not run goes to Darpan's alerts room (alertText), never the client's chat card
        String card = AuditRunMessage.card(facts(), [:], null, COPY).toString()
        assertEquals(0, times(card, ALERTED), card)
        assertFalse(card.contains(NOTICE), card)
    }
}
