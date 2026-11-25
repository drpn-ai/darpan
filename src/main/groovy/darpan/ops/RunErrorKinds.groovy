package darpan.ops

import java.sql.Timestamp
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * DAR-BE-015. Which error, if any, an automation execution row is. Pure: reads statusEnumId and the per-side read and
 * kept counts. Agrees with AutomationExecutionSupport.noDataReason: ONE_SIDE_EMPTY is NO_DATA with one side read 0 and the
 * other above 0; KEPT_NOTHING is NO_DATA with a side that read records but kept 0. A side's read is the larger of its
 * readCount and its recordCount, because an audit re-check appends records to a side whose recent read was empty.
 * Not errors: CANCELLED, SKIP_DUP, and NO_DATA with both sides read 0.
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
            case "AUT_STAT_NO_DATA": return noDataKind(execution)
            default: return null
        }
    }

    private static String noDataKind(Map execution) {
        Integer kept1 = count(execution.file1RecordCount)
        Integer kept2 = count(execution.file2RecordCount)
        int read1 = Math.max(count(execution.file1ReadCount) ?: 0, kept1 ?: 0)
        int read2 = Math.max(count(execution.file2ReadCount) ?: 0, kept2 ?: 0)
        if (read1 == 0 && read2 == 0) return null
        if (read1 == 0 || read2 == 0) return ONE_SIDE_EMPTY
        if ((kept1 != null && kept1 == 0) || (kept2 != null && kept2 == 0)) return KEPT_NOTHING
        return null
    }

    private static Integer count(Object value) {
        return value == null ? null : ((Number) value).intValue()
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
