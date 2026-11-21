package darpan.peak

import darpan.audit.AuditSettingsSupport
import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.automation.AutomationExecutionSupport

import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
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

        Object share = period?.get("hotwaxReadSharePct")
        if (share != null && share.toString().trim() && !validShare(share)) {
            errors << "HotWax share must be a whole number from 1 to 100."
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

    private static boolean validShare(Object share) {
        try {
            BigDecimal n = new BigDecimal(share.toString().trim())
            return n.stripTrailingZeros().scale() <= 0 && n >= 1 && n <= 100
        } catch (NumberFormatException ignored) {
            return false
        }
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
     * The scheduled runs the period's peak hours would hold, one row per run: audit checks (maps carrying auditId +
     * auditName) fold into one row labelled with the audit. automations: automationId, label, scheduleExpr,
     * windowTimeZone. Times are tenant-zone "HH:mm". The window's run-time moves apply first, as the scheduler applies
     * them (only to the first fire of a date): a run moved out of peak hours is not listed, one moved into them is, with
     * its moved time as normalTime. A run held at several times (a sub-daily schedule) shows "first–last" as normalTime
     * and its latest start. Scans the period's first HELD_RUN_SCAN_DAYS days plus every date a move names.
     * Plan 3: allowances (PeakPeriodLoader.allowances, by automationId) hold a run with a sync pair until its read
     * window's end plus the allowance in force in this period, after the moves and before peak hours, as the scheduler
     * does. A run only the allowance moved reads "<HH:mm> waits for the sync lag allowance"; when peak hours give the
     * final start, they are the reason.
     */
    static List<Map> heldRuns(List<Map> automations, PeakWindow w, ZoneId tenantZone, Map<String, AllowanceSpec> allowances = [:]) {
        if (w == null || ((w.peakStart == null || w.peakEnd == null) && !allowances)) return []
        List<PeakWindow> withHours = [w]
        List<PeakWindow> movesOnly = [new PeakWindow(w.peakPeriodId, w.periodName, w.fromDate, w.thruDate, null, null, w.moves ?: [])]
        SortedSet<LocalDate> days = new TreeSet<LocalDate>()
        for (int d = 0; d < HELD_RUN_SCAN_DAYS && !w.fromDate.plusDays(d).isAfter(w.thruDate); d++) days << w.fromDate.plusDays(d)
        // The day after the period still reads the period's last day, so its allowance applies to that fire too.
        if (allowances != null && !allowances.isEmpty()) days << w.thruDate.plusDays(1)
        (w.moves ?: []).each { RunTimeMove m ->
            if (m.onDate != null && !m.onDate.isBefore(w.fromDate) && !m.onDate.isAfter(w.thruDate)) days << m.onDate
        }

        Map<String, Map> held = new LinkedHashMap<String, Map>()
        for (Map automation in (automations ?: [])) {
            String automationId = normalize(automation.get("automationId"))
            String auditId = normalize(automation.get("auditId"))
            String label = auditId ? (normalize(automation.get("auditName")) ?: auditId) : (normalize(automation.get("label")) ?: automationId)
            String key = auditId ? "audit:" + auditId : "automation:" + automationId
            AllowanceSpec allowance = automationId ? allowances?.get(automationId) : null
            int fires = 0
            for (LocalDate day in days) {
                Instant dayEnd = day.plusDays(1).atStartOfDay(tenantZone).toInstant()
                Timestamp cursor = Timestamp.from(day.atStartOfDay(tenantZone).toInstant().minusMillis(1))
                boolean firstOfDay = true
                while (fires < HELD_RUN_MAX_FIRES) {
                    Timestamp next = AutomationExecutionSupport.resolveNextScheduledFireTime(automation, cursor, cursor)
                    if (next == null || !next.toInstant().isBefore(dayEnd)) break
                    cursor = next
                    fires++
                    Instant fire = next.toInstant()
                    Instant moved = PeakPeriodSupport.effectiveStart(fire, automationId, movesOnly, tenantZone, firstOfDay)
                    firstOfDay = false
                    Instant allowed = moved
                    if (allowance != null) {
                        Instant bound = AutomationExecutionSupport.readWindowEnd(automation, fire)
                                .plus(Duration.ofMinutes(PeakPeriodSupport.effectiveAllowance(allowance, withHours,
                                        AutomationExecutionSupport.lastReadDay(
                                                AutomationExecutionSupport.readWindowEnd(automation, fire), tenantZone))))
                        if (allowed.isBefore(bound)) allowed = bound
                    }
                    Instant start = PeakPeriodSupport.effectiveStart(allowed, automationId, withHours, tenantZone, false)
                    if (start == moved) continue
                    Map row = held.computeIfAbsent(key) { [label: label, times: new TreeSet<String>(), latest: null, latestOffset: null, peak: false] }
                    if (start != allowed) row.peak = true
                    ((SortedSet<String>) row.times) << moved.atZone(tenantZone).format(HH_MM)
                    // Latest start by its distance from the start of the fire's day, so a start past midnight is later.
                    long offset = start.toEpochMilli() - day.atStartOfDay(tenantZone).toInstant().toEpochMilli()
                    if (row.latestOffset == null || offset > (row.latestOffset as long)) {
                        row.latestOffset = offset
                        row.latest = start.atZone(tenantZone).format(HH_MM)
                    }
                }
            }
        }
        return held.values().collect { Map row ->
            SortedSet<String> times = (SortedSet<String>) row.times
            String normalTime = times.size() == 1 ? times.first() : "${times.first()}–${times.last()}".toString()
            String why = row.peak ? "is in peak hours" : "waits for the sync lag allowance"
            [targetLabel: row.label, normalTime: normalTime, startsAt: row.latest, reason: "${normalTime} ${why}".toString()]
        }.sort { Map r -> [r.normalTime, r.targetLabel] as List<String> } as List<Map>
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

    /**
     * The period as a PeakWindow with its run-time moves, as PeakPeriodLoader builds it for the scheduler; null peak hours
     * when they are unset or do not parse. A move that names an audit applies to that audit's check automations in
     * scheduled (the maps targetsOf returns, which carry auditId); a run time whose time does not parse is left out.
     */
    static PeakWindow windowOf(def periodRow, List runTimeRows = [], List<Map> scheduled = []) {
        LocalTime start = parseTime(normalize(periodRow.get("peakHoursStart")))
        LocalTime end = parseTime(normalize(periodRow.get("peakHoursEnd")))
        if (start == null || end == null || start == end) { start = null; end = null }
        List<RunTimeMove> moves = (runTimeRows ?: []).collect { rt ->
            LocalTime at = parseTime(normalize(rt.get("runTime")))
            if (at == null) return null
            String automationId = normalize(rt.get("automationId"))
            String auditId = normalize(rt.get("auditId"))
            Set<String> ids = automationId ? ([automationId] as Set<String>) :
                    ((scheduled ?: []).findAll { Map a -> auditId && normalize(a.get("auditId")) == auditId }
                            .collect { Map a -> normalize(a.get("automationId")) }.findAll { it } as Set<String>)
            return ids ? new RunTimeMove(ids.asImmutable(), toLocalDate(rt.get("onDate")), at) : null
        }.findAll { it != null } as List<RunTimeMove>
        return new PeakWindow(normalize(periodRow.get("peakPeriodId")), normalize(periodRow.get("periodName")),
                toLocalDate(periodRow.get("fromDate")), toLocalDate(periodRow.get("thruDate")), start, end, moves)
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

    static final List<Integer> PULSE_CHOICES = [0, 60, 120, 180, 240, 360]

    /** Validation of one pair's period override: allowance 15 minutes to 48 hours or null; pulse 0/1/2/3/4/6 hours or null. */
    static List<String> validateSync(Integer allowanceMinutes, Integer pulseEveryMinutes) {
        List<String> errors = []
        if (allowanceMinutes != null && (allowanceMinutes < 15 || allowanceMinutes > 2880)) errors << "Allowance must be 15 minutes to 48 hours."
        if (pulseEveryMinutes != null && !PULSE_CHOICES.contains(pulseEveryMinutes)) errors << "Pulse must be every 1, 2, 3, 4 or 6 hours, or off."
        return errors
    }

    /** True when the pair's upstream side reads HotWax orders (OMS_GQL), the only system a pulse can sample. */
    static boolean upstreamIsHotwax(def ec, String tenantId, Map pair) {
        def source = rows(ec, "darpan.reconciliation.ReconciliationAutomationSource", tenantId)
                .condition("automationId", pair.get("automationId")).condition("fileSide", pair.get("upstreamSide")).list().find()
        return normalize(source?.get("systemEnumId")) == darpan.pulse.SyncPulseSupport.HOTWAX_ORDERS
    }

    /**
     * Why this pair's check cannot be pulsed (status mode, composite key or id normalizer), as the sentence to show; null when
     * it can. The same rule save#SyncPair and the reader use (SyncPulseFacadeSupport.pulseBlockFor): a period must not give a
     * pulse to a pair whose normal pulse could not be set either.
     */
    static String pulseBlockOf(def ec, String tenantId, Map pair) {
        Map automation = darpan.pulse.SyncPulseFacadeSupport.automationOf(ec, tenantId, normalize(pair.get("automationId")))
        if (automation == null) return null
        String scopeId = normalize(automation.get("compareScopeId"))
        return darpan.pulse.SyncPulseFacadeSupport.pulseBlockFor(automation,
                darpan.facade.reconciliation.RunConclusionStep.keySpecFor(ec, scopeId, "FILE_1"),
                darpan.facade.reconciliation.RunConclusionStep.keySpecFor(ec, scopeId, "FILE_2"))
    }

    /** Deletes every sync override of one of the tenant's periods (FK PEAKSYNC_PERIOD). */
    static void deleteSyncRowsOf(def ec, String tenantId, String peakPeriodId) {
        rows(ec, "darpan.pulse.PeakPeriodSync", tenantId).condition("peakPeriodId", peakPeriodId).deleteAll()
    }

    /** Removes one pair's override of the period; a missing row is fine. */
    static int deleteSync(def ec, String tenantId, String peakPeriodId, String syncPairId) {
        return rows(ec, "darpan.pulse.PeakPeriodSync", tenantId).condition("peakPeriodId", peakPeriodId).condition("syncPairId", syncPairId).deleteAll()
    }

    /** Replaces the pair's override row of the period: null means normal, 0 pulse means off for the period. */
    static void replaceSync(def ec, String tenantId, String peakPeriodId, String syncPairId, Integer allowanceMinutes, Integer pulseEveryMinutes) {
        ec.entity.makeValue("darpan.pulse.PeakPeriodSync").setAll([peakPeriodId: peakPeriodId, syncPairId: syncPairId,
                companyUserGroupId: tenantId, allowanceMinutes: allowanceMinutes, pulseEveryMinutes: pulseEveryMinutes]).createOrUpdate()
    }

    /** Every active sync pair of the tenant with this period's override values (null when none). */
    static List<Map> syncRows(def ec, String tenantId, String peakPeriodId) {
        Map<String, Map> overrides = [:]
        rows(ec, "darpan.pulse.PeakPeriodSync", tenantId).condition("peakPeriodId", peakPeriodId).list().each { overrides.put(it.get("syncPairId") as String, it.getMap()) }
        return (rows(ec, "darpan.pulse.SyncPair", tenantId).condition("isActive", "Y").orderBy("syncPairId").list() ?: []).collect { pair ->
            Map o = overrides.get(pair.get("syncPairId") as String)
            [syncPairId: pair.get("syncPairId"),
             label: darpan.pulse.SyncPulseSupport.pairLabel(ec, pair.getMap()),
             normalAllowanceMinutes: pair.get("allowanceMinutes"), periodAllowanceMinutes: o?.get("allowanceMinutes"),
             normalPulseMinutes: pair.get("pulseEveryMinutes"), periodPulseMinutes: o?.get("pulseEveryMinutes")]
        }
    }

    /**
     * Deletes every run time of the tenant that names this automation, so deleting the automation does not fail on the
     * PEAKRT_AUTOMATION foreign key. Returns how many were deleted.
     */
    static int deleteRunTimesForAutomation(def ec, String tenantId, String automationId) {
        if (!normalize(tenantId) || !normalize(automationId)) return 0
        return rows(ec, "darpan.peak.PeakPeriodRunTime", tenantId).condition("automationId", automationId).deleteAll()
    }

    /** Per-request reads (every page load, every scheduler tick): the DEBUG twin of findGlobalUnscoped, so no INFO line each time. */
    static def rows(def ec, String entityName, String tenantId) {
        return TenantScopedFinder.findGlobalUnscopedPerRequest(ec, entityName, WHY).condition("companyUserGroupId", tenantId).useCache(false)
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
