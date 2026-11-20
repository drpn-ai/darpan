package darpan.peak

import darpan.audit.AuditSettingsSupport
import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.automation.AutomationExecutionSupport

import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-328. What PeakPeriodFacadeServices checks and shows: the error sentences the UI prints verbatim, and the
 * "Waits for peak hours" view (which of a tenant's scheduled runs a period's peak hours would hold, and until when).
 * Validation and the held-runs math are pure (no Moqui); the few ec helpers read one tenant's rows with an explicit
 * companyUserGroupId condition.
 */
class PeakPeriodFacadeSupport {
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private static final String WHY = "peak period facade reads the active tenant's automations and audits: explicit companyUserGroupId condition applied"

    /** Days of a period scanned for held runs: enough to see every distinct local fire time of a daily-or-slower schedule. */
    static final int HELD_RUN_SCAN_DAYS = 7
    /** Hard stop on fires examined per automation, for a schedule that fires every minute. */
    static final int HELD_RUN_MAX_FIRES = 500

    /** Error sentences for a period about to be saved; empty when valid. otherActivePeriods: the tenant's other active periods. */
    static List<String> validatePeriod(Map period, List<Map> otherActivePeriods) {
        List<String> errors = []
        if (!normalize(period?.get("periodName"))) errors << "Name is required."
        LocalDate from = toLocalDate(period?.get("fromDate"))
        LocalDate thru = toLocalDate(period?.get("thruDate"))
        boolean datesOk = from != null && thru != null
        if (!datesOk) errors << "Dates are required."
        else if (thru.isBefore(from)) errors << "The end date is before the start date."

        String start = normalize(period?.get("peakHoursStart"))
        String end = normalize(period?.get("peakHoursEnd"))
        if ((start == null) != (end == null)) {
            errors << "Set both peak-hour times, or neither."
        } else if (start != null) {
            LocalTime s = parseTime(start)
            LocalTime e = parseTime(end)
            if (s == null || e == null) errors << "Time must be HH:mm."
            else if (s == e) errors << "Peak hours cannot start and end at the same time."
        }

        boolean selfActive = normalize(period?.get("isActive")) != "N"
        if (errors.isEmpty() && selfActive) {
            for (Map other in (otherActivePeriods ?: [])) {
                if (normalize(other.get("isActive")) == "N") continue
                LocalDate oFrom = toLocalDate(other.get("fromDate"))
                LocalDate oThru = toLocalDate(other.get("thruDate"))
                if (oFrom == null || oThru == null) continue
                if (!from.isAfter(oThru) && !thru.isBefore(oFrom)) {
                    errors << "Overlaps ${normalize(other.get('periodName'))} (${oFrom.format(MONTH_DAY)} – ${oThru.format(MONTH_DAY)}).".toString()
                    break
                }
            }
        }
        return errors
    }

    /** Error sentences for a run time about to be saved. tenantTargetIds: the tenant's own automation and audit ids. */
    static List<String> validateRunTime(Map runTime, Map period, Set<String> tenantTargetIds) {
        List<String> errors = []
        String automationId = normalize(runTime?.get("automationId"))
        String auditId = normalize(runTime?.get("auditId"))
        if (automationId && auditId) {
            errors << "Choose one run, not both."
        } else {
            String target = automationId ?: auditId
            if (!target || tenantTargetIds == null || !tenantTargetIds.contains(target)) errors << "Choose a run."
        }
        LocalDate onDate = toLocalDate(runTime?.get("onDate"))
        if (onDate != null) {
            LocalDate from = toLocalDate(period?.get("fromDate"))
            LocalDate thru = toLocalDate(period?.get("thruDate"))
            if (from == null || thru == null || onDate.isBefore(from) || onDate.isAfter(thru)) errors << "That date is outside this period."
        }
        if (parseTime(normalize(runTime?.get("runTime"))) == null) errors << "Time must be HH:mm."
        return errors
    }

    /**
     * The scheduled runs the period's peak hours would hold, one row per (run, usual time) whose start moves: audit checks
     * (maps carrying auditId + auditName) fold into one row labelled with the audit. automations: automationId, label,
     * scheduleExpr, windowTimeZone. Times are tenant-zone "HH:mm". Run-time moves are not applied: this is what peak hours
     * alone do.
     */
    static List<Map> heldRuns(List<Map> automations, PeakWindow w, ZoneId tenantZone) {
        if (w == null || w.peakStart == null || w.peakEnd == null) return []
        LocalDate lastDay = w.fromDate.plusDays(HELD_RUN_SCAN_DAYS - 1)
        if (w.thruDate.isBefore(lastDay)) lastDay = w.thruDate
        ZonedDateTime scanStart = w.fromDate.atStartOfDay(tenantZone)
        Instant scanEnd = lastDay.plusDays(1).atStartOfDay(tenantZone).toInstant()
        Timestamp before = Timestamp.from(scanStart.toInstant().minusMillis(1))

        Map<String, Map> rows = new LinkedHashMap<String, Map>()
        for (Map automation in (automations ?: [])) {
            String auditId = normalize(automation.get("auditId"))
            String label = auditId ? (normalize(automation.get("auditName")) ?: auditId) : (normalize(automation.get("label")) ?: normalize(automation.get("automationId")))
            String key = auditId ? "audit:" + auditId : "automation:" + normalize(automation.get("automationId"))
            Timestamp cursor = before
            for (int i = 0; i < HELD_RUN_MAX_FIRES; i++) {
                Timestamp next = AutomationExecutionSupport.resolveNextScheduledFireTime(automation, cursor, cursor)
                if (next == null || !next.toInstant().isBefore(scanEnd)) break
                cursor = next
                Instant fire = next.toInstant()
                Instant start = PeakPeriodSupport.effectiveStart(fire, normalize(automation.get("automationId")), [w], tenantZone, false)
                if (start == fire) continue
                String normalTime = fire.atZone(tenantZone).format(HH_MM)
                rows.putIfAbsent(key + "|" + normalTime, [targetLabel: label, normalTime: normalTime,
                        startsAt: start.atZone(tenantZone).format(HH_MM), reason: "${normalTime} is in peak hours".toString()])
            }
        }
        return rows.values().toList().sort { Map r -> [r.normalTime, r.targetLabel] as List<String> } as List<Map>
    }


    /**
     * The usual time of each run in tenant "HH:mm": the first fire at or after the period's first day, keyed by automationId
     * and, for an audit's checks, by auditId (the first check's time). Runs with no schedule are absent.
     */
    static Map<String, String> normalTimes(List<Map> automations, LocalDate from, ZoneId tenantZone) {
        Map<String, String> times = [:]
        Timestamp before = Timestamp.from(from.atStartOfDay(tenantZone).toInstant().minusMillis(1))
        for (Map automation in (automations ?: [])) {
            Timestamp next = AutomationExecutionSupport.resolveNextScheduledFireTime(automation, before, before)
            if (next == null) continue
            String hhmm = next.toInstant().atZone(tenantZone).format(HH_MM)
            String automationId = normalize(automation.get("automationId"))
            String auditId = normalize(automation.get("auditId"))
            if (automationId) times.putIfAbsent(automationId, hhmm)
            if (auditId) times.putIfAbsent(auditId, hhmm)
        }
        return times
    }

    /** upcoming | on | off | ended, by the tenant's local today. */
    static String stateOf(Map period, LocalDate today) {
        LocalDate from = toLocalDate(period.get("fromDate"))
        LocalDate thru = toLocalDate(period.get("thruDate"))
        if (thru != null && thru.isBefore(today)) return "ended"
        if (normalize(period.get("isActive")) == "N") return "off"
        if (from != null && from.isAfter(today)) return "upcoming"
        return "on"
    }


    /** The list rows: id, name, ISO dates, isActive, state, ordered by sortForList. */
    static List<Map> listRows(List<Map> periods, LocalDate today) {
        List<Map> rows = (periods ?: []).collect { Map p ->
            [peakPeriodId: p.get("peakPeriodId"), periodName: p.get("periodName"), fromDate: toLocalDate(p.get("fromDate")),
             thruDate    : toLocalDate(p.get("thruDate")), isActive: normalize(p.get("isActive")) ?: "Y", state: stateOf(p, today)]
        }
        return sortForList(rows).collect { Map r ->
            r + [fromDate: r.fromDate?.toString(), thruDate: r.thruDate?.toString()]
        }
    }

    /** The period's peak hours as a PeakWindow with no moves; null peak hours when they are unset or do not parse. */
    static PeakWindow windowOf(def periodRow) {
        LocalTime start = parseTime(normalize(periodRow.get("peakHoursStart")))
        LocalTime end = parseTime(normalize(periodRow.get("peakHoursEnd")))
        if (start == null || end == null || start == end) { start = null; end = null }
        return new PeakWindow(normalize(periodRow.get("peakPeriodId")), normalize(periodRow.get("periodName")),
                toLocalDate(periodRow.get("fromDate")), toLocalDate(periodRow.get("thruDate")), start, end, [])
    }

    /** Not ended by start date, then ended by end date, newest first. Rows carry state, fromDate, thruDate (LocalDate). */
    static List<Map> sortForList(List<Map> periods) {
        List<Map> live = periods.findAll { it.state != "ended" }.sort { Map p -> p.fromDate as LocalDate }
        List<Map> ended = periods.findAll { it.state == "ended" }.sort { Map p -> p.thruDate as LocalDate }.reverse()
        return live + ended
    }

    /**
     * The tenant's run targets: scheduled automations not owned by an audit, plus its audits.
     * Returns [targets: [{automationId|auditId, label}], ids: Set, scheduled: [automation maps for heldRuns]].
     */
    static Map<String, Object> targetsOf(def ec, String tenantId) {
        List<Map> targets = []
        Set<String> ids = new LinkedHashSet<String>()
        List<Map> scheduled = []
        Set<String> auditOwned = new HashSet<String>()

        rows(ec, "darpan.audit.AuditCheck", tenantId).list().each { check ->
            String id = normalize(check.get("automationId"))
            if (id) auditOwned << id
        }
        rows(ec, "darpan.audit.Audit", tenantId).orderBy("auditName").list().each { audit ->
            String auditId = normalize(audit.get("auditId"))
            String name = normalize(audit.get("auditName")) ?: auditId
            targets << [auditId: auditId, label: name]
            ids << auditId
            AuditSettingsSupport.automationsOfAudit(ec, tenantId, [auditId: auditId]).each { Map a ->
                String automationId = normalize(a.get("automationId"))
                auditOwned << automationId
                if (isScheduled(a)) scheduled << heldInput(a, auditId, name)
            }
        }
        rows(ec, "darpan.reconciliation.ReconciliationAutomation", tenantId).orderBy("automationName").list().each { row ->
            String automationId = normalize(row.get("automationId"))
            if (auditOwned.contains(automationId) || !isScheduled(row.getMap())) return
            targets << [automationId: automationId, label: normalize(row.get("automationName")) ?: automationId]
            ids << automationId
            scheduled << heldInput(row.getMap(), null, null)
        }
        return [targets: targets, ids: ids, scheduled: scheduled]
    }

    /** The tenant's peak periods as maps (explicit companyUserGroupId condition). */
    static List<Map> periodsOf(def ec, String tenantId) {
        return (rows(ec, "darpan.peak.PeakPeriod", tenantId).list() ?: []).collect { it.getMap() }
    }

    /** Deletes every run time of one of the tenant's periods. */
    static void deleteRunTimesOf(def ec, String tenantId, String peakPeriodId) {
        rows(ec, "darpan.peak.PeakPeriodRunTime", tenantId).condition("peakPeriodId", peakPeriodId).deleteAll()
    }

    static def rows(def ec, String entityName, String tenantId) {
        return TenantScopedFinder.findGlobalUnscoped(ec, entityName, WHY).condition("companyUserGroupId", tenantId).useCache(false)
    }

    static LocalDate toLocalDate(Object value) {
        if (value == null) return null
        if (value instanceof LocalDate) return (LocalDate) value
        if (value instanceof java.sql.Date) return ((java.sql.Date) value).toLocalDate()
        if (value instanceof Date) return new java.sql.Date(((Date) value).time).toLocalDate()
        String text = normalize(value)
        if (!text) return null
        try {
            return LocalDate.parse(text.take(10))
        } catch (DateTimeParseException ignored) {
            return null
        }
    }

    private static boolean isScheduled(Map automation) {
        return normalize(automation.get("scheduleExpr")) != null && normalize(automation.get("isArchived")) != "Y" &&
                normalize(automation.get("isActive")) != "N"
    }

    private static Map heldInput(Map a, String auditId, String auditName) {
        return [automationId: normalize(a.get("automationId")), label: normalize(a.get("automationName")),
                scheduleExpr: a.get("scheduleExpr"), windowTimeZone: a.get("windowTimeZone"),
                auditId     : auditId, auditName: auditName]
    }

    private static LocalTime parseTime(String text) {
        try {
            return PeakPeriodSupport.parseHhMm(text)
        } catch (IllegalArgumentException ignored) {
            return null
        }
    }
}
