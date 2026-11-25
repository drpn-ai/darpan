package darpan.audit

import org.junit.jupiter.api.Test

import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime

import static org.junit.jupiter.api.Assertions.*

/** DAR-BE-361: acknowledgement rules, pure (no Moqui). */
class AcknowledgementSupportTests {

    static Timestamp ts(String iso) { Timestamp.valueOf(LocalDateTime.parse(iso)) }

    static Map ack(String remind, String stamp = "2026-11-20T10:00:00") {
        [acknowledgedStamp: ts(stamp), remindDate: remind ? LocalDate.parse(remind) : null, note: "n", acknowledgedByUserId: "U1"]
    }

    @Test
    void remindDateAddsDaysOnlyWhenOn() {
        assertEquals(LocalDate.parse("2026-11-25"), AcknowledgementSupport.remindDate(LocalDate.parse("2026-11-20"), 5, true))
        assertNull(AcknowledgementSupport.remindDate(LocalDate.parse("2026-11-20"), 5, false))
    }

    @Test
    void acknowledgedUntilTheDayBeforeRemind() {
        Map a = ack("2026-11-25")
        assertTrue(AcknowledgementSupport.effective(a, LocalDate.parse("2026-11-24"), null))
        assertFalse(AcknowledgementSupport.effective(a, LocalDate.parse("2026-11-25"), null))
    }

    @Test
    void sqlDateRemindDateIsAccepted() {
        Map a = [acknowledgedStamp: ts("2026-11-20T10:00:00"), remindDate: java.sql.Date.valueOf("2026-11-25")]
        assertTrue(AcknowledgementSupport.effective(a, LocalDate.parse("2026-11-24"), null))
        assertFalse(AcknowledgementSupport.effective(a, LocalDate.parse("2026-11-25"), null))
    }

    @Test
    void noRemindStaysAcknowledged() {
        assertTrue(AcknowledgementSupport.effective(ack(null), LocalDate.parse("2030-01-01"), null))
        assertFalse(AcknowledgementSupport.effective(null, LocalDate.parse("2026-11-24"), null))
    }

    @Test
    void reopenAfterAcknowledgeVoidsIt() {
        assertFalse(AcknowledgementSupport.effective(ack(null), LocalDate.parse("2026-11-24"), ts("2026-11-23T06:00:00")))
        assertTrue(AcknowledgementSupport.effective(ack(null), LocalDate.parse("2026-11-24"), ts("2026-11-19T06:00:00")))
    }

    @Test
    void newLineOnAcknowledgedOrderIsOpen() {
        Map acks = ["F-1": ack(null)]
        assertEquals("open", AcknowledgementSupport.unitState(["F-1", "F-2"], acks, [:], LocalDate.parse("2026-11-24")))
        assertEquals("acknowledged", AcknowledgementSupport.unitState(["F-1"], acks, [:], LocalDate.parse("2026-11-24")))
    }

    @Test
    void allPastRemindIsReminded() {
        Map acks = ["F-1": ack("2026-11-25"), "F-2": ack("2026-11-25")]
        assertEquals("reminded", AcknowledgementSupport.unitState(["F-1", "F-2"], acks, [:], LocalDate.parse("2026-11-26")))
    }

    @Test
    void reopenedLineMakesUnitOpenNotReminded() {
        Map acks = ["F-1": ack("2026-11-25"), "F-2": ack("2026-11-25")]
        Map reopen = ["F-2": ts("2026-11-23T06:00:00")]
        assertEquals("open", AcknowledgementSupport.unitState(["F-1", "F-2"], acks, reopen, LocalDate.parse("2026-11-26")))
    }

    @Test
    void emptyUnitIsOpen() {
        assertEquals("open", AcknowledgementSupport.unitState([], [:], [:], LocalDate.parse("2026-11-24")))
    }

    @Test
    void summaryShape() {
        Map s = AcknowledgementSupport.summary(ack("2026-11-25"), "reminded", ["U1": "Aditi"])
        assertEquals("n", s.note)
        assertEquals([id: "U1", label: "Aditi"], s.by)
        assertEquals("2026-11-25", s.remindOn)
        assertEquals(true, s.reminded)
        assertNotNull(s.at)
        assertNull(AcknowledgementSupport.summary(ack(null), "acknowledged", [:]).remindOn)
    }
}
