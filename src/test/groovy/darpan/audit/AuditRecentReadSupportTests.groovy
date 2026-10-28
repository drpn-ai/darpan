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
 * DAR-BE-116 phase 1 / DAR-BE-122. HotWax OMS is read for the days since the last complete read;
 * the other systems for the whole period. A record only the other system holds is judged only when its own date is
 * in those days, so an older one, whose OMS twin may simply be older than the read, neither raises nor closes a finding.
 */
class AuditRecentReadSupportTests {

    private static final ZoneId LA = ZoneId.of("America/Los_Angeles")

    private static Timestamp at(String day) { Timestamp.from(LocalDate.parse(day).atStartOfDay(LA).toInstant()) }

    /**
     * DAR-BE-122 (Aditi 2026-10-24: "lookups that build on each other and only a complete one when explicitly asked"): a
     * run reads from the last day its checks covered with a complete run, re-reading that day; a check never run, or a
     * day outside the window, reads it all. The earliest of the checks' days, so no check misses a day.
     */
    @Test
    void theReadStartsAtTheLastDayEveryCheckCoveredAndReReadsIt() {
        Timestamp from = at("2026-10-01"), end = at("2026-10-24")
        assertEquals(at("2026-10-22"), AuditRecentReadSupport.incrementalStart(
                ["A": java.sql.Date.valueOf("2026-10-23"), "B": java.sql.Date.valueOf("2026-10-22")], ["A", "B"], from, end, LA))
        assertNull(AuditRecentReadSupport.incrementalStart(["A": java.sql.Date.valueOf("2026-10-23")], ["A", "B"], from, end, LA),
                "B never ran: read the whole window")
        assertNull(AuditRecentReadSupport.incrementalStart([:], ["A"], from, end, LA), "first run: read it all")
        assertEquals(at("2026-10-23"), AuditRecentReadSupport.incrementalStart(["A": java.sql.Date.valueOf("2026-10-30")], ["A"], from, end, LA),
                "covered past the window: re-read its last day")
        assertNull(AuditRecentReadSupport.incrementalStart(["A": java.sql.Date.valueOf("2026-10-01")], ["A"], from, end, LA),
                "the window's first day: that is the whole window")
    }

    @Test
    void aRecordDateIsReadInEveryShapeTheSourcesWrite() {
        LocalDate d = LocalDate.parse("2026-10-17")
        assertEquals(d, AuditRecentReadSupport.dayOf("2026-10-17", LA))                 // NetSuite TO_CHAR(trandate, 'YYYY-MM-DD')
        assertEquals(d, AuditRecentReadSupport.dayOf("10/17/2026", LA))                 // NetSuite bare trandate
        assertEquals(d, AuditRecentReadSupport.dayOf("2026-10-17T19:30:00Z", LA))       // Shopify createdAt, still the 17th in LA
        assertNull(AuditRecentReadSupport.dayOf("not a date", LA))
        assertNull(AuditRecentReadSupport.dayOf(null, LA))
    }

    @Test
    void anOtherSideOnlyRecordIsJudgedOnlyInsideTheRecentDays() {
        Timestamp start = at("2026-10-16")
        assertTrue(AuditRecentReadSupport.judged([tranDate: "2026-10-16"], "tranDate", start, LA))
        assertFalse(AuditRecentReadSupport.judged([tranDate: "2026-10-15"], "tranDate", start, LA))
        assertTrue(AuditRecentReadSupport.judged([tranDate: "10/20/2026"], "tranDate", start, LA))
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

    /**
     * DAR-BE-157: a connector that reads its whole population whatever the window (windowed N) is never "recent only", even
     * under OMS. Otherwise an audit narrows the HotWax product sweep's start and drops every NetSuite-only item as
     * unjudged, so "missing in HotWax" could never be raised or closed.
     */
    @Test
    void anUnwindowedOmsEndpointIsNotRecentOnly() {
        assertFalse(AuditRecentReadSupport.readsRecentOnly("OMS_PRODUCTS_GQL", [windowed: false]))
        assertTrue(AuditRecentReadSupport.readsRecentOnly("OMS_GQL", [windowed: true]))
        assertTrue(AuditRecentReadSupport.readsRecentOnly("OMS_GQL", null), "no connector row: today's rule")
        assertFalse(AuditRecentReadSupport.readsRecentOnly("NETSUITE_SUITEQL", [windowed: true]))
    }
}
