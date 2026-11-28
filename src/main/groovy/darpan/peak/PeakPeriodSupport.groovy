package darpan.peak

import groovy.transform.Immutable

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/** A run-time move inside a peak period: the listed automations start at runTime (on onDate, or every date when null). */
@Immutable
class RunTimeMove {
    Set<String> automationIds
    LocalDate onDate
    LocalTime runTime
}

/** One peak period of a tenant, with optional peak hours (peakStart/peakEnd both set or both null) and its moves. */
@Immutable
class PeakWindow {
    String peakPeriodId
    String periodName
    LocalDate fromDate
    LocalDate thruDate
    LocalTime peakStart
    LocalTime peakEnd
    List<RunTimeMove> moves
}

/**
 * DAR-BE-328 plan 3: one automation's sync lag allowance, in minutes: normalMinutes, or a peak period's own value
 * (periodMinutes, keyed by peakPeriodId; a period with no value of its own is absent and means normal).
 */
@Immutable
class AllowanceSpec {
    int normalMinutes
    Map<String, Integer> periodMinutes
}

/** Pure resolver: when may a scheduled run start, given a tenant's peak periods. No Moqui dependencies. */
class PeakPeriodSupport {
    private static final int MAX_HOPS = 10

    static PeakWindow activeOn(List<PeakWindow> windows, LocalDate date) {
        if (windows == null || date == null) return null
        for (PeakWindow w in windows) {
            if (!date.isBefore(w.fromDate) && !date.isAfter(w.thruDate)) return w
        }
        return null
    }

    /** The instant peak hours end if t is inside them (start <= t < end), else null. */
    static Instant peakHoursEndIfInside(List<PeakWindow> windows, Instant t, ZoneId tenantZone) {
        if (windows == null || windows.isEmpty()) return null
        LocalDate local = t.atZone(tenantZone).toLocalDate()
        Instant latestEnd = null
        for (LocalDate d in [local, local.minusDays(1)]) {
            PeakWindow w = activeOn(windows, d)
            if (w == null || w.peakStart == null || w.peakEnd == null) continue
            Instant start = ZonedDateTime.of(d, w.peakStart, tenantZone).toInstant()
            LocalDate endDate = w.peakEnd.isAfter(w.peakStart) ? d : d.plusDays(1)
            Instant end = ZonedDateTime.of(endDate, w.peakEnd, tenantZone).toInstant()
            if (!t.isBefore(start) && t.isBefore(end) && (latestEnd == null || end.isAfter(latestEnd))) latestEnd = end
        }
        return latestEnd
    }

    static Instant effectiveStart(Instant fire, String automationId, List<PeakWindow> windows, ZoneId tenantZone, boolean applyMoves) {
        if (windows == null || windows.isEmpty()) return fire
        Instant start = fire
        if (applyMoves) {
            LocalDate date = fire.atZone(tenantZone).toLocalDate()
            PeakWindow w = activeOn(windows, date)
            RunTimeMove hit = w?.moves?.find { RunTimeMove m ->
                m.automationIds != null && m.automationIds.contains(automationId) && (m.onDate == null || m.onDate == date)
            }
            if (hit != null) start = ZonedDateTime.of(date, hit.runTime, tenantZone).toInstant()
        }
        // Peak end can land inside an overlapping window's hours; hop until clear (bounded).
        for (int i = 0; i < MAX_HOPS; i++) {
            Instant end = peakHoursEndIfInside(windows, start, tenantZone)
            if (end == null) break
            start = end
        }
        return start
    }

    /** The allowance in force on tenantDate: the active window's own value when it has one, else the normal one. */
    static int effectiveAllowance(AllowanceSpec spec, List<PeakWindow> windows, LocalDate tenantDate) {
        if (spec == null) return 0
        PeakWindow w = activeOn(windows, tenantDate)
        Integer periodMinutes = w != null ? spec.periodMinutes?.get(w.peakPeriodId) : null
        return periodMinutes != null ? periodMinutes : spec.normalMinutes
    }

    /** A stored date as a LocalDate: a LocalDate, a java.sql.Date or Date, or ISO text (its first 10 characters); null when blank or unreadable. */
    static LocalDate toLocalDate(Object value) {
        if (value == null) return null
        if (value instanceof LocalDate) return (LocalDate) value
        if (value instanceof java.sql.Date) return ((java.sql.Date) value).toLocalDate()
        if (value instanceof Date) return new java.sql.Date(((Date) value).time).toLocalDate()
        String text = value.toString().trim()
        if (!text) return null
        try {
            return LocalDate.parse(text.take(10))
        } catch (DateTimeParseException ignored) {
            return null
        }
    }

    static LocalTime parseHhMm(String s) {
        if (s == null || s.trim().isEmpty()) return null
        String v = s.trim()
        if (!(v ==~ /\d{2}:\d{2}/)) throw new IllegalArgumentException("Time must be HH:mm, got '${s}'")
        try {
            return LocalTime.parse(v)
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Time must be HH:mm, got '${s}'", e)
        }
    }
}
