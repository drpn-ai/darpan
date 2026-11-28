package darpan.audit

import org.moqui.entity.EntityCondition

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException
import java.util.regex.Matcher

/**
 * DAR-BE-091 / DAR-BE-178. An audit's periods (a day, a month or a quarter): how a period is written, its days, the
 * window a morning's run reads, and which past period a played run may still record in. Pure date arithmetic, apart
 * from resolveCheckRun and the enum codes the period kind is read from.
 *
 * DAR-OPS-029 S10: split out of AuditReportAssembler.
 */
class AuditPeriods {

    /** [first day, last day] of a period written in the audit's form (2026-10-14, 2026-10, 2026-Q4); null when it does not parse. */
    static List<LocalDate> periodBounds(String period, String periodCode) {
        if (!period) return null
        try {
            switch (periodCode) {
                case "day":
                    LocalDate d = LocalDate.parse(period)
                    return [d, d]
                case "quarter":
                    Matcher m = period =~ /^(\d{4})-Q([1-4])$/
                    if (!m.matches()) return null
                    LocalDate start = LocalDate.of(m.group(1) as int, ((m.group(2) as int) - 1) * 3 + 1, 1)
                    return [start, start.plusMonths(3).minusDays(1)]
                default:
                    YearMonth ym = YearMonth.parse(period)
                    return [ym.atDay(1), ym.atEndOfMonth()]
            }
        } catch (DateTimeParseException ignored) {
            return null
        }
    }

    /**
     * DAR-BE-091. One period of an audit as of one morning: its window (first day to the earlier of its last day and
     * the morning), whether the period still runs that morning, and (DAR-BE-178) whether a played run may still record
     * in it: its own span, or the whole of the period that follows it. Null when the period does not parse or has not
     * started by that morning.
     */
    static Map periodWindow(def ec, Map audit, String period, LocalDate day) {
        String periodCode = AuditReportAssembler.enumCodes(ec).get(audit.periodEnumId as String)
        List<LocalDate> bounds = periodBounds(period, periodCode)
        if (bounds == null || day < bounds[0]) return null
        boolean running = day <= bounds[1]
        return [period: period, from: bounds[0], thru: running ? day : bounds[1], end: bounds[1], running: running,
                recordable: running || periodStart(day, periodCode) == bounds[1].plusDays(1)]
    }

    /** DAR-BE-178. The first day a played run can record on {@code day}: the first day of the period just ended. */
    static LocalDate recordableFrom(LocalDate day, String periodCode) {
        return periodStart(periodStart(day, periodCode).minusDays(1), periodCode)
    }

    /** DAR-BE-091. The run an audit owes on one morning: its current period, from the period's first day to the morning. */
    static Map auditRun(def ec, Map audit, LocalDate day) {
        return periodWindow(ec, audit, periodOf(day, AuditReportAssembler.enumCodes(ec).get(audit.periodEnumId as String)), day)
    }

    /**
     * DAR-BE-091. The period and window of one check's run on one morning, for the ledger writers. A blank period is
     * the morning's own period, which needs the check's audits to share one kind of period. A past period must still
     * be recordable (DAR-BE-178: the period just ended, when a person plays it). Returns [period, from, thru, ...] or
     * [error: why].
     */
    static Map resolveCheckRun(def ec, String tenantId, List auditIds, LocalDate day, String period) {
        List<Map> audits = auditIds ? AuditReportAssembler.tenantRows(ec, "darpan.audit.Audit", tenantId).condition("auditId", EntityCondition.IN, auditIds)
                .list()*.getMap() : []
        Map<String, String> codes = AuditReportAssembler.enumCodes(ec)
        List<String> kinds = audits.collect { codes.get(it.periodEnumId as String) }.unique()
        if (!period) {
            if (kinds.size() != 1) return [error: "The check serves audits of ${kinds.size() ?: 'no'} kinds of period; pass the period".toString()]
            period = periodOf(day, kinds[0])
        }
        List<Map> windows = audits.collect { periodWindow(ec, it, period, day) }.findAll { it != null }
        Map recordable = windows.find { it.running } ?: windows.find { it.recordable }
        if (recordable != null) return recordable
        if (!audits.any { periodBounds(period, codes.get(it.periodEnumId as String)) != null }) {
            return [error: "${period} is not a period of the check's audits".toString()]
        }
        if (!windows) return [error: "Period ${period} has not started on ${day}".toString()]
        return [error: "Period ${period} ended on ${windows[0].end}".toString()]
    }

    /**
     * DAR-BE-091. The first day of the period containing {@code day}: the day itself for a daily audit, the 1st for a
     * monthly one, the first day of the quarter for a quarterly one. An audit check reads from here to the report day,
     * so on the 14th a monthly audit reconciles the 1st-14th and every open finding of the month is looked at again.
     */
    static LocalDate periodStart(LocalDate day, String periodCode) {
        switch (periodCode) {
            case "day": return day
            case "quarter": return LocalDate.of(day.year, ((int) ((day.monthValue - 1) / 3)) * 3 + 1, 1)
            default: return day.withDayOfMonth(1)
        }
    }

    /** report.period: the period the report day falls in, e.g. 2026-10-07 (day), 2026-10 (month), 2026-Q4 (quarter). */
    static String periodOf(LocalDate day, String periodCode) {
        switch (periodCode) {
            case "day": return day.toString()
            case "quarter": return "${day.year}-Q${(int) ((day.monthValue - 1) / 3) + 1}".toString()
            default: return day.toString().substring(0, 7)
        }
    }
}
