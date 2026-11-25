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

    @Test
    void aRuleWhoseOnlyCheckDidNotRunShowsTheNeutralLineNotZero() {
        String text = AuditRunMessage.emailText(facts(), [:], null, COPY)
        assertTrue(text.contains("  R1  Every order is billed: ${NOT_RUN}"), text)
        assertFalse(text.contains("R1  Every order is billed: clean"))
        assertTrue(text.contains("  R3  Every return is credited: clean"), "a rule that ran shows counts as before")
    }

    @Test
    void aRuleWithOneOfFiveChecksNotRunShowsPartlyAndItsCounts() {
        String line = AuditRunMessage.emailText(facts(), [:], null, COPY).readLines().find { it.startsWith("  R2  ") }
        assertTrue(line.contains(PARTLY), line)
        assertTrue(line.contains("3 open"), line)
    }

    @Test
    void theEmailHtmlSaysTheSame() {
        String html = AuditRunMessage.emailHtml(facts(), [:], null, COPY)
        assertTrue(html.contains(NOT_RUN), "neutral line")
        assertTrue(html.contains(PARTLY) && html.contains("3 open"), "partly and counts")
        assertFalse(html.contains("R1 did not run on"), "old wording gone")
    }

    @Test
    void theChatCardSaysTheSame() {
        String card = AuditRunMessage.card(facts(), [:], null, COPY).toString()
        assertTrue(card.contains("R1 did not run this morning. Darpan has been alerted"), card)
        assertTrue(card.contains(PARTLY), card)
    }
}
