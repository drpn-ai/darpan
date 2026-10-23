package darpan.audit

import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * DAR-BE-116 phase 1. An audit reads HotWax OMS, whose API refills 2000 cost/s on gorjana, for its recent days only
 * (Audit.recentReadDays; empty reads the whole period). On a check pairing OMS with another system, that system is read
 * from EDGE_SLACK_DAYS earlier, and a record only it holds is judged only when its own date
 * (AuditCheck.otherSideDateField) is EDGE_SLACK_DAYS or more into the recent days: its OMS twin may simply be older than
 * the read, so an older one neither raises nor closes a finding. Checks between other systems keep the whole period.
 */
class AuditRecentReadSupport {

    /** The DarpanSystemSource parent of every HotWax OMS endpoint: these are the reads that are narrowed. */
    static final String RECENT_READ_PARENT_SYSTEM = "OMS"

    /**
     * Slack at the edge of the recent read: the other system is read this many days earlier than OMS, and a record only it
     * holds is judged from this many days later, so every judged record's twin, dated a little either side, was read.
     */
    static final int EDGE_SLACK_DAYS = 3

    private static final DateTimeFormatter NETSUITE_DATE = DateTimeFormatter.ofPattern("M/d/yyyy")

    /** Where the non-OMS side of an OMS-paired check starts reading: EDGE_SLACK_DAYS before the OMS read, never before the window. */
    static Timestamp otherSideStart(Timestamp recentStart, Timestamp windowStart) {
        Timestamp early = new Timestamp(recentStart.time - EDGE_SLACK_DAYS * 86_400_000L)
        return windowStart != null && windowStart.after(early) ? windowStart : early
    }

    /** From when a record only the non-OMS side holds is judged: EDGE_SLACK_DAYS after the OMS read starts. */
    static Timestamp judgedFrom(Timestamp recentStart) {
        return new Timestamp(recentStart.time + EDGE_SLACK_DAYS * 86_400_000L)
    }

    /**
     * Start of the recent read: the window's end minus `days` whole days in `zone`, or null when that is not later than
     * the window's own start (the window is already that short) or `days` is empty or 0 (read the whole period).
     */
    static Timestamp recentStart(Timestamp windowStart, Timestamp windowEnd, Integer days, ZoneId zone) {
        int n = days ?: 0
        if (n <= 0 || windowStart == null || windowEnd == null) return null
        LocalDate endDay = windowEnd.toInstant().atZone(zone).toLocalDate()
        Timestamp start = Timestamp.from(endDay.minusDays(n).atStartOfDay(zone).toInstant())
        return start.after(windowStart) ? start : null
    }

    /** True for a HotWax OMS endpoint (its DarpanSystemSource enum's parent is OMS), the system read for recent days only. */
    static boolean readsRecentOnly(def ec, String systemEnumId) {
        if (!systemEnumId) return false
        def e = ec.entity.find("moqui.basic.Enumeration").condition("enumId", systemEnumId).useCache(true).one()
        return e != null && (e.parentEnumId == RECENT_READ_PARENT_SYSTEM || e.enumId == RECENT_READ_PARENT_SYSTEM)
    }

    /** The calendar day a record's date names, in `zone`: YYYY-MM-DD, NetSuite M/d/yyyy, an ISO instant, or epoch millis. */
    static LocalDate dayOf(Object value, ZoneId zone) {
        if (value == null) return null
        if (value instanceof Number) return Instant.ofEpochMilli(((Number) value).longValue()).atZone(zone).toLocalDate()
        if (value instanceof java.util.Date) return ((java.util.Date) value).toInstant().atZone(zone).toLocalDate()
        String text = value.toString().trim()
        if (!text) return null
        try {
            if (text ==~ /\d{4}-\d{2}-\d{2}/) return LocalDate.parse(text)
            if (text ==~ /\d{1,2}\/\d{1,2}\/\d{4}/) return LocalDate.parse(text, NETSUITE_DATE)
            if (text ==~ /-?\d{10,}/) return Instant.ofEpochMilli(text as long).atZone(zone).toLocalDate()
            return Instant.parse(text).atZone(zone).toLocalDate()
        } catch (Exception ignored) {
            return null
        }
    }

    /**
     * Whether a record only the other system holds is judged this run: its `dateField` names a day on or after the recent
     * start. No field configured, or no readable date on the record: not judged (it can be neither raised nor closed).
     */
    static boolean judged(Map record, String dateField, Timestamp recentStart, ZoneId zone) {
        if (recentStart == null) return true
        String field = dateField?.trim()?.replaceFirst(/^\$\.records\[\*\]\./, "")
        if (!field || record == null) return false
        LocalDate day = dayOf(record.get(field), zone)
        return day != null && !day.isBefore(recentStart.toInstant().atZone(zone).toLocalDate())
    }
}
