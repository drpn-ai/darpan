package darpan.audit

import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * DAR-BE-116 / DAR-BE-122. An audit reads rate-limited HotWax OMS from its checks' last complete read (incrementalStart);
 * the other system of an OMS-paired check reads EDGE_SLACK_DAYS earlier and is judged from EDGE_SLACK_DAYS later.
 */
class AuditRecentReadSupport {

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
     * DAR-BE-122. Where a window's read starts: the earliest of the checks' last covered days (lastCoveredByCheck: the
     * windowThruDate of each check's last complete run in the period), re-read; past the window, its last day. Null, the
     * whole window, when a check has no complete run yet or the start would be the window's own first day.
     */
    static Timestamp incrementalStart(Map<String, java.util.Date> lastCoveredByCheck, List<String> checkIds, Timestamp windowStart,
                                      Timestamp windowEnd, ZoneId zone) {
        if (checkIds.any { !lastCoveredByCheck.get(it) }) return null
        LocalDate last = checkIds.collect { new java.sql.Date(lastCoveredByCheck.get(it).time).toLocalDate() }.min()
        LocalDate windowLast = windowEnd.toInstant().atZone(zone).toLocalDate().minusDays(1)
        if (last.isAfter(windowLast)) last = windowLast
        Timestamp start = Timestamp.from(last.atStartOfDay(zone).toInstant())
        return start.after(windowStart) ? start : null
    }

    /**
     * True for a HotWax OMS endpoint, the system read from the last complete read, unless its connector reads the whole
     * population whatever the window (DAR-BE-157: windowed N, the product catalogue).
     */
    static boolean readsRecentOnly(String systemEnumId, Map connector) {
        if (connector?.windowed == false) return false
        return AuditAutomationSupport.rootSystem(systemEnumId) == "OMS"
    }

    /** readsRecentOnly for a run or automation source, through its endpoint's connector row. */
    static boolean sourceReadsRecentOnly(def ec, def source) {
        String systemEnumId = darpan.common.ValueSupport.normalize(darpan.common.ValueSupport.readField(source, "systemEnumId"))
        Map connector = ec != null && systemEnumId ? darpan.reconciliation.automation.SourceSystemConnectorSupport.resolveBySystemEnumId(ec, systemEnumId) : null
        return readsRecentOnly(systemEnumId, connector)
    }

    /** The calendar day a record's date names, in `zone`: YYYY-MM-DD, NetSuite M/d/yyyy or an ISO instant. */
    static LocalDate dayOf(Object value, ZoneId zone) {
        String text = value?.toString()?.trim()
        try {
            if (text ==~ /\d{4}-\d{2}-\d{2}/) return LocalDate.parse(text)
            if (text ==~ /\d{1,2}\/\d{1,2}\/\d{4}/) return LocalDate.parse(text, NETSUITE_DATE)
            return Instant.parse(text).atZone(zone).toLocalDate()
        } catch (Exception ignored) {
            return null
        }
    }

    /**
     * Whether a record only the other system holds is judged this run: its `dateField` names a day on or after
     * `judgedFrom`. No field, or no readable date on the record: not judged (it can be neither raised nor closed).
     */
    static boolean judged(Map record, String dateField, Timestamp judgedFrom, ZoneId zone) {
        if (!dateField || record == null) return false
        LocalDate day = dayOf(record.get(dateField), zone)
        return day != null && !day.isBefore(judgedFrom.toInstant().atZone(zone).toLocalDate())
    }
}
