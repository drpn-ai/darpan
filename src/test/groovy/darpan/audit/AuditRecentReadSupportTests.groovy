package darpan.audit

import org.junit.jupiter.api.Test

import java.sql.Timestamp
import java.time.LocalDate
import java.time.ZoneId

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-116 phase 1 (Aditi 2026-10-23: "re-read only the last 7 days"). HotWax OMS is read for the audit's recent days;
 * the other systems for the whole period. A record only the other system holds is judged only when its own date is
 * in those days, so an older one, whose OMS twin may simply be older than the read, neither raises nor closes a finding.
 */
class AuditRecentReadSupportTests {

    private static final ZoneId LA = ZoneId.of("America/Los_Angeles")

    private static Timestamp at(String day) { Timestamp.from(LocalDate.parse(day).atStartOfDay(LA).toInstant()) }

    @Test
    void theRecentStartIsThatManyDaysBeforeTheEndAndNeverBeforeTheWindow() {
        assertEquals(at("2026-10-16"), AuditRecentReadSupport.recentStart(at("2026-10-01"), at("2026-10-23"), 7, LA))
        assertNull(AuditRecentReadSupport.recentStart(at("2026-10-01"), at("2026-10-23"), null, LA), "empty reads the whole period")
        assertEquals(at("2026-10-20"), AuditRecentReadSupport.recentStart(at("2026-10-01"), at("2026-10-23"), 3, LA))
        assertNull(AuditRecentReadSupport.recentStart(at("2026-10-01"), at("2026-10-05"), 7, LA), "the window is already that short")
        assertNull(AuditRecentReadSupport.recentStart(at("2026-10-01"), at("2026-10-23"), 0, LA), "zero reads the whole period")
    }

    @Test
    void aRecordDateIsReadInEveryShapeTheSourcesWrite() {
        LocalDate d = LocalDate.parse("2026-10-17")
        assertEquals(d, AuditRecentReadSupport.dayOf("2026-10-17", LA))                 // NetSuite TO_CHAR(trandate, 'YYYY-MM-DD')
        assertEquals(d, AuditRecentReadSupport.dayOf("10/17/2026", LA))                 // NetSuite bare trandate
        assertEquals(d, AuditRecentReadSupport.dayOf("2026-10-17T19:30:00Z", LA))       // Shopify createdAt, still the 17th in LA
        assertEquals(d, AuditRecentReadSupport.dayOf(at("2026-10-17").time + 3600_000L, LA)) // epoch millis
        assertNull(AuditRecentReadSupport.dayOf("not a date", LA))
        assertNull(AuditRecentReadSupport.dayOf(null, LA))
    }

    @Test
    void anOtherSideOnlyRecordIsJudgedOnlyInsideTheRecentDays() {
        Timestamp start = at("2026-10-16")
        assertTrue(AuditRecentReadSupport.judged([tranDate: "2026-10-16"], "tranDate", start, LA))
        assertFalse(AuditRecentReadSupport.judged([tranDate: "2026-10-15"], "tranDate", start, LA))
        assertTrue(AuditRecentReadSupport.judged(['$.records[*].tranDate': null, tranDate: "10/20/2026"], '$.records[*].tranDate', start, LA))
        assertFalse(AuditRecentReadSupport.judged([tranDate: "2026-10-20"], null, start, LA), "no date field: not judged")
        assertFalse(AuditRecentReadSupport.judged([other: 1], "tranDate", start, LA), "no date on the record: not judged")
    }

    /** Live 2026-10-23: a month of NetSuite lines took 12 minutes, so the other side is narrowed too, with slack both ways. */
    @Test
    void theOtherSideReadsThreeDaysEarlierAndIsJudgedFromThreeDaysLater() {
        Timestamp recent = at("2026-10-16")
        assertEquals(at("2026-10-13"), AuditRecentReadSupport.otherSideStart(recent, at("2026-10-01")))
        assertEquals(at("2026-10-15"), AuditRecentReadSupport.otherSideStart(recent, at("2026-10-15")), "never before the window")
        assertEquals(at("2026-10-19"), AuditRecentReadSupport.judgedFrom(recent))
        assertFalse(AuditRecentReadSupport.judged([tranDate: "2026-10-18"], "tranDate", AuditRecentReadSupport.judgedFrom(recent), LA),
                "its OMS twin may be dated just before the OMS read")
        assertTrue(AuditRecentReadSupport.judged([tranDate: "2026-10-19"], "tranDate", AuditRecentReadSupport.judgedFrom(recent), LA))
    }
}
