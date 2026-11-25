package darpan.audit

import darpan.email.EmailCopyReader
import darpan.email.SeedCopy
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-015. The client's run update says a statement's checks did not run (neutral line, Darpan alerted) or partly
 * ran (that line plus its counts), never "0 open / clean". Pure: facts maps through the seeded copy defaults.
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
    private static final String ROW_NOT_RUN = "Did not run this morning."
    private static final String ROW_PARTLY = "1 of 5 checks did not run this morning."

    private static int times(String text, String part) { return text.split(java.util.regex.Pattern.quote(part), -1).length - 1 }

    @Test
    void aRuleWhoseOnlyCheckDidNotRunShowsTheShortRowAndTheNoticeNamesItNotZero() {
        String text = AuditRunMessage.emailText(facts(), [:], null, COPY)
        assertTrue(text.contains("  R1  Every order is billed: ${ROW_NOT_RUN}\n"), text)
        assertFalse(text.contains("R1  Every order is billed: clean"))
        assertTrue(text.contains(NOTICE), "the notice names wholly not-run rules, then partly ran ones, then the alerted sentence once: ${text}")
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
        String text = AuditRunMessage.emailText(f, [:], null, COPY)
        assertTrue(text.contains("R2 partly ran this morning. ${ALERTED}"), text)
        assertFalse(text.contains("did not run this morning. Darpan"), "no rule wholly not run, so no 'did not run' notice: ${text}")
        assertEquals(1, times(AuditRunMessage.card(f, [:], null, COPY).toString(), ALERTED))
    }

    @Test
    void onlyWhollyNotRunRulesGetTheDidNotRunNotice() {
        Map f = facts()
        f.rules[1] = f.rules[1] + [checksNotRun: []]
        String html = AuditRunMessage.emailHtml(f, [:], null, COPY)
        assertTrue(html.contains("R1 did not run this morning. ${ALERTED}"), html)
        assertFalse(html.contains("partly ran"), html)
        assertEquals(1, times(html, ALERTED))
    }

    @Test
    void theAlertedSentenceIsInTheNoticeOnceInEmailHtmlTextAndCard() {
        assertEquals(1, times(AuditRunMessage.emailText(facts(), [:], null, COPY), ALERTED))
        String html = AuditRunMessage.emailHtml(facts(), [:], null, COPY)
        assertEquals(1, times(html, ALERTED))
        assertTrue(html.contains(NOTICE) && html.contains(ROW_NOT_RUN) && html.contains(ROW_PARTLY) && html.contains("3 open"))
        String card = AuditRunMessage.card(facts(), [:], null, COPY).toString()
        assertEquals(1, times(card, ALERTED))
        assertTrue(card.contains(NOTICE), card)
    }
}
