package darpan.facade.reconciliation

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

    /** StuckRunReaper.DEFAULT_STALE_MINUTES fails a run this long after its last heartbeat. */
    static final int REAPER_MINUTES = 120
    /** Silence this long in ANY stage means the reaper is close; stuck regardless of stage. */
    static final int SILENT_ANY_STAGE_MINUTES = 90
    /** Extracts heartbeat every page, so this much silence inside one is not a slow page. */
    static final int SILENT_EXTRACT_MINUTES = 15
    static final int SLOW_FACTOR = 2
    /** Below this a run is never "slow", whatever its history: a 2-minute run is nobody's problem. */
    static final int SLOW_FLOOR_MINUTES = 15
    static final int CANCEL_GRACE_MINUTES = 5

    /** [health: healthy | slow | stuck, reason: words or null]. First match wins. */
    static Map<String, Object> of(Map run, Timestamp now) {
        Timestamp started = run.startedDate as Timestamp
        Timestamp lastBeat = (run.lastHeartbeatDate ?: started) as Timestamp
        long silentMinutes = lastBeat == null ? 0 : minutesBetween(lastBeat, now)
        long elapsedMs = started == null ? 0 : now.time - started.time
        String stage = run.currentStage as String

        if (silentMinutes >= SILENT_ANY_STAGE_MINUTES) {
            return verdict("stuck", "No progress for ${duration(silentMinutes * MINUTE_MS)}; " +
                    "the stuck-run reaper fails it at ${REAPER_MINUTES}m")
        }
        if (stage?.startsWith("EXTRACT") && silentMinutes >= SILENT_EXTRACT_MINUTES) {
            return verdict("stuck", "Extract has not reported progress for ${duration(silentMinutes * MINUTE_MS)}")
        }
        Long usual = run.usualDurationMs as Long
        if (usual != null && usual > 0 && elapsedMs >= SLOW_FACTOR * usual && elapsedMs >= SLOW_FLOOR_MINUTES * MINUTE_MS) {
            return verdict("slow", "Running ${duration(elapsedMs)}; its last successful run took ${duration(usual)}")
        }
        Timestamp cancelAt = run.cancelRequestedDate as Timestamp
        if (cancelAt != null && minutesBetween(cancelAt, now) >= CANCEL_GRACE_MINUTES) {
            return verdict("slow", "Cancel requested ${duration(now.time - cancelAt.time)} ago; still running " +
                    "(a cancel lands at the next stage boundary)")
        }
        return verdict("healthy", null)
    }

    /**
     * How long the same saved run took the last time it succeeded on this tenant; null without history. The finder is
     * the caller's tenant-scoped one (an explicit companyUserGroupId condition is added here as well).
     */
    static Long usualDurationMs(def find, String tenantId, String savedRunId) {
        if (!savedRunId) return null
        def last = (find.condition("companyUserGroupId", tenantId)
                .condition("savedRunId", savedRunId)
                .condition("statusEnumId", RunObservability.STATUS_SUCCESS)
                .orderBy("-completedDate").limit(1).useCache(false).list() ?: [])[0]
        Timestamp started = last?.get("startedDate") as Timestamp
        Timestamp completed = last?.get("completedDate") as Timestamp
        return (started && completed) ? completed.time - started.time : null
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
