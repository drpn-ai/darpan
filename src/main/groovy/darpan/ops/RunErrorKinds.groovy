package darpan.ops

import java.sql.Timestamp
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * DAR-BE-015. Which error, if any, an automation execution row is. Pure: reads statusEnumId and, for a NO_DATA close, the
 * noDataKind the run persisted from the same derivation as its did-not-run reason (AutomationExecutionSupport.windowNoDataFinding),
 * so the card, the digest and the row's errorMessage cannot disagree. A NO_DATA row with no noDataKind (a quiet day, a verify)
 * is not an error. Not errors: CANCELLED, SKIP_DUP.
 */
class RunErrorKinds {
    static final String FAILED = "FAILED"
    static final String DEAD_LETTER = "DEAD_LETTER"
    static final String ONE_SIDE_EMPTY = "ONE_SIDE_EMPTY"
    static final String KEPT_NOTHING = "KEPT_NOTHING"

    static String of(Map execution) {
        if (execution == null) return null
        switch (execution.statusEnumId?.toString()) {
            case "AUT_STAT_FAILED": return FAILED
            case "AUT_STAT_DEAD_LETTER": return DEAD_LETTER
            case "AUT_STAT_NO_DATA":
                String kind = execution.noDataKind?.toString()
                return kind in [ONE_SIDE_EMPTY, KEPT_NOTHING] ? kind : null
            default: return null
        }
    }

    /** The calendar day of a scheduled moment in the automation's own zone (UTC when the zone is blank or unknown). */
    static LocalDate runDay(Timestamp scheduledDate, String zoneId) {
        ZoneId zone = ZoneOffset.UTC
        if (zoneId?.trim()) {
            try { zone = ZoneId.of(zoneId.trim()) } catch (DateTimeException ignored) { }
        }
        return scheduledDate.toInstant().atZone(zone).toLocalDate()
    }
}
