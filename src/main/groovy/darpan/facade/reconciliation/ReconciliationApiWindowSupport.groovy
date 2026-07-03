package darpan.facade.reconciliation

import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp
import java.time.DateTimeException
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

import static darpan.common.ValueSupport.normalize

class ReconciliationApiWindowSupport {
    private static final Logger logger = LoggerFactory.getLogger(ReconciliationApiWindowSupport.class)
    private static final long DAY_MILLIS = Duration.ofDays(1).toMillis()

    static Map<String, Object> normalizeSavedRunApiWindow(def ec, Timestamp windowStartDate, Timestamp windowEndDate) {
        return normalizeCalendarWindow(windowStartDate, windowEndDate, TenantAccessSupport.resolveActiveTenantTimeZone(ec))
    }

    static Map<String, Object> normalizeSavedRunApiWindow(def ec, Timestamp windowStartDate, Timestamp windowEndDate,
            Object rawStartLocalDate, Object rawEndLocalDate) {
        return normalizeCalendarWindow(windowStartDate, windowEndDate, TenantAccessSupport.resolveActiveTenantTimeZone(ec),
                rawStartLocalDate, rawEndLocalDate)
    }

    static Map<String, Object> normalizeCalendarWindow(Timestamp windowStartDate, Timestamp windowEndDate, Object rawTimeZone) {
        return normalizeCalendarWindow(windowStartDate, windowEndDate, rawTimeZone, null, null)
    }

    static Map<String, Object> normalizeCalendarWindow(Timestamp windowStartDate, Timestamp windowEndDate, Object rawTimeZone,
            Object rawStartLocalDate, Object rawEndLocalDate) {
        ZoneId zone = resolveZoneId(rawTimeZone)
        Map<String, Object> result = [
                windowStartDate       : windowStartDate,
                windowEndDate         : windowEndDate,
                timeZone              : zone.id,
                calendarDateNormalized: false,
        ]
        // Audit H15.6 — parseLocalDate previously forced ZoneOffset.UTC when extracting a LocalDate
        // from a Timestamp. A UI built a Timestamp like 2026-05-28T19:00:00Z meaning 2026-05-29 IST
        // (UTC+05:30); the UTC extraction collapsed to 2026-05-28 and the window was off by a day.
        // Use the resolved tenant zone (the same zone we use to re-anchor the LocalDate below).
        LocalDate startLocalDate = parseLocalDate(rawStartLocalDate, zone)
        LocalDate endLocalDate = parseLocalDate(rawEndLocalDate, zone)
        if (startLocalDate != null && endLocalDate != null) {
            result.windowStartDate = Timestamp.from(startLocalDate.atStartOfDay(zone).toInstant())
            result.windowEndDate = Timestamp.from(endLocalDate.atStartOfDay(zone).toInstant())
            result.calendarDateNormalized = true
            return result
        }
        if (windowStartDate == null || windowEndDate == null) return result
        if (!isUtcMidnight(windowStartDate) || !isUtcMidnight(windowEndDate)) return result
        if (!isWholeDayRange(windowStartDate, windowEndDate)) return result

        LocalDate startDate = windowStartDate.toInstant().atZone(ZoneOffset.UTC).toLocalDate()
        LocalDate endDate = windowEndDate.toInstant().atZone(ZoneOffset.UTC).toLocalDate()
        result.windowStartDate = Timestamp.from(startDate.atStartOfDay(zone).toInstant())
        result.windowEndDate = Timestamp.from(endDate.atStartOfDay(zone).toInstant())
        result.calendarDateNormalized = true
        return result
    }

    static Map<String, Object> preserveExactWindow(Timestamp windowStartDate, Timestamp windowEndDate, Object rawTimeZone) {
        ZoneId zone = resolveZoneId(rawTimeZone)
        return [
                windowStartDate       : windowStartDate,
                windowEndDate         : windowEndDate,
                timeZone              : zone.id,
                calendarDateNormalized: false,
                exactWindowPreserved  : true,
        ]
    }

    private static ZoneId resolveZoneId(Object rawTimeZone) {
        String timeZone = normalize(rawTimeZone) ?: TenantAccessSupport.DEFAULT_TIME_ZONE
        try {
            return ZoneId.of(timeZone)
        } catch (DateTimeException ignored) {
            return ZoneId.of(TenantAccessSupport.DEFAULT_TIME_ZONE)
        }
    }

    private static LocalDate parseLocalDate(Object rawDate, ZoneId zone = ZoneOffset.UTC) {
        if (rawDate == null) return null
        if (rawDate instanceof LocalDate) return (LocalDate) rawDate
        if (rawDate instanceof Timestamp) return ((Timestamp) rawDate).toInstant().atZone(zone).toLocalDate()
        if (rawDate instanceof java.sql.Date) return ((java.sql.Date) rawDate).toLocalDate()
        if (rawDate instanceof Date) return rawDate.toInstant().atZone(zone).toLocalDate()

        String normalized = normalize(rawDate)
        if (!normalized) return null
        try {
            return LocalDate.parse(normalized)
        } catch (Exception e) {
            logger.debug("Could not parse '{}' as LocalDate", normalized, e)
            return null
        }
    }

    private static boolean isUtcMidnight(Timestamp timestamp) {
        return timestamp.toInstant().atZone(ZoneOffset.UTC).toLocalTime() == LocalTime.MIDNIGHT
    }

    private static boolean isWholeDayRange(Timestamp windowStartDate, Timestamp windowEndDate) {
        long durationMillis = Duration.between(windowStartDate.toInstant(), windowEndDate.toInstant()).toMillis()
        return durationMillis > 0 && durationMillis % DAY_MILLIS == 0
    }
}
