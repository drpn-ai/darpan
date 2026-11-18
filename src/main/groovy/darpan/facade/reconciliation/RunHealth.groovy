package darpan.facade.reconciliation

import darpan.config.DarpanSettings
import groovy.transform.CompileDynamic

import java.sql.Timestamp

/**
 * Health of one run while it is RUNNING: healthy, slow or stuck, with the reason in words. Pure: a plain Map of run
 * fields and the clock in. Shared by the admin live board (DAR-ADMIN-008) and the audit page's progress (DAR-UI-085).
 *
 * Heartbeats tick only inside the extract stages (RunObservability.heartbeatStageProgress); COMPARE and the verify
 * passes write nothing until they end. So silence is read as stuck only inside an extract, or anywhere once the reaper
 * is close — never merely because a compare is long. Slow compares the run with its own last successful run.
 *
 * Run fields read: startedDate, lastHeartbeatDate, currentStage, usualDurationMs, cancelRequestedDate.
 */
@CompileDynamic
class RunHealth {

    static final long MINUTE_MS = 60_000L

    private static final String PREFIX = "darpan.reconciliation.runHealth."

    private static int setting(String name) { return DarpanSettings.intValue(PREFIX + name) }

    /** [health: healthy | slow | stuck, reason: words or null]. First match wins. */
    static Map<String, Object> of(Map run, Timestamp now) {
        Timestamp started = run.startedDate as Timestamp
        Timestamp lastBeat = (run.lastHeartbeatDate ?: started) as Timestamp
        long silentMinutes = lastBeat == null ? 0 : minutesBetween(lastBeat, now)
        long elapsedMs = started == null ? 0 : now.time - started.time
        String stage = run.currentStage as String
        // Thresholds are DarpanSettings (DAR-BE-304), read once per verdict so one run is judged on one set.
        int reaperMinutes = setting("reaperStaleMinutes")
        int silentAnyStage = setting("silentAnyStageMinutes")
        int silentExtract = setting("silentExtractMinutes")
        int slowFactor = setting("slowFactor")
        int slowFloor = setting("slowFloorMinutes")
        int cancelGrace = setting("cancelGraceMinutes")

        if (silentMinutes >= silentAnyStage) {
            return verdict("stuck", "No progress for ${duration(silentMinutes * MINUTE_MS)}; " +
                    "the stuck-run reaper fails it at ${reaperMinutes}m")
        }
        if (stage?.startsWith("EXTRACT") && silentMinutes >= silentExtract) {
            return verdict("stuck", "Extract has not reported progress for ${duration(silentMinutes * MINUTE_MS)}")
        }
        Long usual = run.usualDurationMs as Long
        if (usual != null && usual > 0 && elapsedMs >= slowFactor * usual && elapsedMs >= slowFloor * MINUTE_MS) {
            return verdict("slow", "Running ${duration(elapsedMs)}; its last successful run took ${duration(usual)}")
        }
        Timestamp cancelAt = run.cancelRequestedDate as Timestamp
        if (cancelAt != null && minutesBetween(cancelAt, now) >= cancelGrace) {
            return verdict("slow", "Cancel requested ${duration(now.time - cancelAt.time)} ago; still running " +
                    "(a cancel lands at the next stage boundary)")
        }
        return verdict("healthy", null)
    }

    /** How many recent successes are searched for one over a window of the same length. */
    static final int USUAL_LOOKBACK = 30

    /**
     * How long the same saved run took the last time it succeeded on this tenant over a window about as long as this
     * run's (within a tenth: a 30-day month for a 31-day one, never a day for a week); null without such a run.
     * DAR-BE-173: a month's read is not "usually <1m" because the daily run of the same saved run takes 42 seconds.
     * Without this run's window, the last success of any length. The finder is the caller's tenant-scoped one (an
     * explicit companyUserGroupId condition is added here as well).
     */
    static Long usualDurationMs(def find, String tenantId, String savedRunId, Object windowStart = null, Object windowEnd = null) {
        if (!savedRunId) return null
        Long span = windowDays(windowStart, windowEnd)
        def last = (find.condition("companyUserGroupId", tenantId)
                .condition("savedRunId", savedRunId)
                .condition("statusEnumId", RunObservability.STATUS_SUCCESS)
                .orderBy("-completedDate").limit(span == null ? 1 : USUAL_LOOKBACK).useCache(false).list() ?: [])
                .find { span == null || aboutAsLong(span, windowDays(it.get("windowStartDate"), it.get("windowEndDate"))) }
        Timestamp started = last?.get("startedDate") as Timestamp
        Timestamp completed = last?.get("completedDate") as Timestamp
        return (started && completed) ? completed.time - started.time : null
    }

    /** Whole days a window covers (rounded, so a zone change inside it does not split one length into two); null when open. */
    static Long windowDays(Object start, Object end) {
        if (!(start instanceof Timestamp) || !(end instanceof Timestamp)) return null
        return Math.round((((Timestamp) end).time - ((Timestamp) start).time) / (double) (24 * MINUTE_MS * 60))
    }

    private static boolean aboutAsLong(Long a, Long b) {
        return b != null && Math.abs(a - b) <= Math.max(a, b) / 10.0d
    }

    /** "45m", "2h 5m". */
    static String duration(long ms) {
        long minutes = Math.max(0L, (long) (ms / MINUTE_MS))
        if (minutes < 60) return "${minutes}m"
        long rest = minutes % 60
        return rest == 0 ? "${(long) (minutes / 60)}h" : "${(long) (minutes / 60)}h ${rest}m"
    }

    private static long minutesBetween(Timestamp from, Timestamp to) { return (long) ((to.time - from.time) / MINUTE_MS) }

    private static Map<String, Object> verdict(String health, Object reason) {
        return [health: health, reason: reason?.toString()]
    }
}
