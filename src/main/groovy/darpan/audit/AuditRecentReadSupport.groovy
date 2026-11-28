package darpan.audit

import java.sql.Timestamp
import java.time.LocalDate
import java.time.ZoneId

/**
 * DAR-BE-116 / DAR-BE-122. An audit reads rate-limited HotWax OMS from its checks' last complete read (incrementalStart);
 * the other system of an OMS-paired check reads EDGE_SLACK_DAYS earlier and is judged from EDGE_SLACK_DAYS later.
 * DAR-BE-320: a one-sided check reads another system so too when its extractor can say what changed (readsByChange).
 */
class AuditRecentReadSupport {

    /**
     * Slack at the edge of the recent read: the other system is read this many days earlier than OMS, and a record only it
     * holds is judged from this many days later, so every judged record's twin, dated a little either side, was read.
     */
    static final int EDGE_SLACK_DAYS = 3


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

    /**
     * DAR-BE-320 (Aditi 2026-11-26: "no runs pull month to date on a daily run"). Whether a source that readsRecentOnly does
     * not cover may still be read from the recent start: only in a one-sided (EVALUATE) check, on a windowed connector, whose
     * extractor can read the window's records that changed since an instant (declares changedSince, as DAR-BE-356 asks of
     * HotWax) and whose compare key is a plain record field (keyField: the re-check reads open findings by it). Whether its
     * query can be read so is the extractor's own answer, asked first (readByChangeFirst). A pair keeps today's reads: an
     * OMS pair's other side reads from otherSideStart and is judged from judgedFrom; a pair of two other systems (Shopify and
     * NetSuite) keeps the whole window on both sides, as twins dated either side of a recent edge would read as missing.
     */
    static boolean readsByChange(boolean oneSided, String systemEnumId, Map connector, boolean declaresChangedSince, String keyField) {
        if (!oneSided || !declaresChangedSince || !keyField || connector?.windowed == false) return false
        return !readsRecentOnly(systemEnumId, connector)
    }

    /** DAR-BE-320. How an extractor's refusal to read a query by change begins (netsuite-darpan NsSuiteQlOrderSupport). */
    static final String CHANGED_READ_REFUSED = "Not read by what changed"

    /**
     * DAR-BE-320. For a source that readsByChange (candidate), its read by change IS its read: readChanged() answers the
     * window's records that could have moved since the recent start (the new days among them), whole orders, with what the
     * extractor folds in (NetSuite: lines in a live state, orders HotWax changed). Answered: [changed: that result]. When the
     * extractor refuses the query (a message starting CHANGED_READ_REFUSED) or cannot say (null), [changed: null, reason]:
     * the side reads its whole window as before. Any other failure is the side's read failing, thrown as such: reading the
     * whole window after a failed read would ask the same failing systems for far more.
     */
    static Map readByChangeFirst(Map<String, Object> window, boolean candidate, Closure readChanged) {
        if (!candidate || window?.auditRecentReadStartDate == null) return [changed: null]
        try {
            Map changed = (Map) readChanged.call()
            if (!changed?.fileLocation) return [changed: null, reason: "its extractor cannot say what changed"]
            return [changed: changed]
        } catch (Exception e) {
            if (!refused(e)) throw e
            return [changed: null, reason: e.message]
        }
    }

    /** DAR-BE-320. Whether a failed read by change was the extractor refusing the query, not a read that failed. */
    static boolean refused(Throwable e) {
        for (Throwable t = e; t != null; t = t.cause) {
            if (t.message?.contains(CHANGED_READ_REFUSED)) return true
        }
        return false
    }

    /** readsRecentOnly for a run or automation source, through its endpoint's connector row. */
    static boolean sourceReadsRecentOnly(def ec, def source) {
        String systemEnumId = darpan.common.ValueSupport.normalize(darpan.common.ValueSupport.readField(source, "systemEnumId"))
        Map connector = ec != null && systemEnumId ? darpan.reconciliation.automation.SourceSystemConnectorSupport.resolveBySystemEnumId(ec, systemEnumId) : null
        return readsRecentOnly(systemEnumId, connector)
    }

    /**
     * Whether a record only the other system holds is judged this run: its `dateField` names a day on or after
     * `judgedFrom`. No field, or no readable date on the record: not judged (it can be neither raised nor closed).
     */
    static boolean judged(Map record, String dateField, Timestamp judgedFrom, ZoneId zone) {
        if (!dateField || record == null) return false
        // DAR-OPS-029 S99-016: the finding facts' parser, so the window and the card read the same day.
        LocalDate day = AuditRecordFacts.dayOf(record.get(dateField), zone)
        return day != null && !day.isBefore(judgedFrom.toInstant().atZone(zone).toLocalDate())
    }
}
