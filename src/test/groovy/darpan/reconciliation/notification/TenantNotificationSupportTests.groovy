package darpan.reconciliation.notification

import darpan.facade.reconciliation.ExchangePairVerificationSupport
import darpan.facade.reconciliation.MissingDiffVerificationSupport
import darpan.facade.reconciliation.ReturnPresenceVerificationSupport
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * I6: partitionAuditNotes must recognize every verification pass's always-emitted "show your work"
 * sentence, or that pass's runs are permanently misclassified WITH ISSUES even when all-clear.
 *
 * Before this fix, ReturnPresenceVerificationSupport.AUDIT_NOTE_PREFIX ("Return presence check: ")
 * was absent from the classifier's prefix list — see TenantNotificationSupport.partitionAuditNotes
 * — so every returns run, all-clear ones included, had its always-on audit note fall into
 * "warnings" and the chat header read "completed WITH ISSUES" every single time.
 */
class TenantNotificationSupportTests {

    @Test
    void anAllClearReturnPresenceAuditNoteIsNotClassifiedAsAWarning() {
        String note = "${ReturnPresenceVerificationSupport.AUDIT_NOTE_PREFIX}3 matched, 0 missing in Shopify, 0 missing in OMS, 0 pending (younger than 3h).".toString()

        Map<String, List<String>> partitioned = TenantNotificationSupport.partitionAuditNotes([note])

        assertEquals(0, partitioned.warnings.size(), "the always-on audit note must never read as an issue: ${partitioned.warnings}")
        assertEquals(1, partitioned.auditNotes.size())
        assertTrue(partitioned.auditNotes.contains(note))
    }

    @Test
    void aGenuineReturnPresenceFailureStillClassifiesAsAWarning() {
        // Deliberately NOT the exact AUDIT_NOTE_PREFIX literal ("Return presence check: ") — a real
        // failure from this pass must still land in "warnings", the same distinction
        // ExchangePairVerificationSupport's own warnings rely on ("confirming"/"could not"/
        // "skipped:" rather than an immediate colon after the opening words).
        String failure = "Return presence check could not write diff rows: disk full"

        Map<String, List<String>> partitioned = TenantNotificationSupport.partitionAuditNotes([failure])

        assertEquals(1, partitioned.warnings.size(), "a genuine failure must still surface as a warning: ${partitioned}")
        assertEquals(0, partitioned.auditNotes.size())
    }

    @Test
    void stillClassifiesExchangeAndMissingDiffAuditNotesCorrectly() {
        // Regression guard: extending the prefix list to include returns must not disturb the two
        // pre-existing producers.
        String exchangeNote = "${ExchangePairVerificationSupport.AUDIT_NOTE_PREFIX}0 Shopify exchange(s) in window — 0 matched in OMS, 0 confirmed by lookup, 0 missing from OMS, 0 pending (younger than 3h).".toString()
        String missingDiffNote = "${MissingDiffVerificationSupport.AUDIT_NOTE_PREFIX}0 of 0 differences confirmed present and removed.".toString()

        Map<String, List<String>> partitioned = TenantNotificationSupport.partitionAuditNotes([exchangeNote, missingDiffNote])

        assertEquals(0, partitioned.warnings.size())
        assertEquals(2, partitioned.auditNotes.size())
    }

    @Test
    void blankAndNullEntriesAreIgnored() {
        Map<String, List<String>> partitioned = TenantNotificationSupport.partitionAuditNotes([null, "", "   "])

        assertEquals(0, partitioned.warnings.size())
        assertEquals(0, partitioned.auditNotes.size())
    }
}
