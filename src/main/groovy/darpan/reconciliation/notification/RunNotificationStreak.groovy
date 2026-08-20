package darpan.reconciliation.notification

import darpan.common.DarpanEntityConstants
import darpan.facade.common.TenantScopedFinder
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Counts how many consecutive clean runs preceded this one, for the same saved run and tenant.
 * Isolated from RunNotificationVoice so that class stays Moqui-free and unit-testable.
 */
class RunNotificationStreak {

    // Defined on RunNotificationVoice so the pure-logic class never points at this Moqui-importing
    // one — that direction would drag RunNotificationVoiceTests out of the fast unitTest pool.
    static final int LOOKBACK_LIMIT = RunNotificationVoice.LOOKBACK_LIMIT

    private static final Logger logger = LoggerFactory.getLogger(RunNotificationStreak.class)

    static int countConsecutiveCleanRuns(def ec, String savedRunId, String tenantId, String currentResultId) {
        // Ad-hoc runs have no savedRunId and therefore no series to form a streak from.
        if (!savedRunId || !tenantId) return 0
        try {
            def rows = TenantScopedFinder.findGlobalUnscoped(ec,
                            DarpanEntityConstants.RECONCILIATION_RUN_RESULT,
                            "streak lookback pinned to run tenant — explicit companyUserGroupId condition applied below")
                    ?.condition("savedRunId", savedRunId)
                    ?.condition("companyUserGroupId", tenantId)
                    ?.orderBy("-completedDate")
                    ?.limit(LOOKBACK_LIMIT + 1)
                    ?.useCache(false)?.list() ?: []

            int streak = 0
            for (def row : rows) {
                if (((row.reconciliationRunResultId)?.toString()?.trim()) == currentResultId) continue
                if (row.completedDate == null) continue
                boolean clean = ((row.statusEnumId)?.toString()?.trim()) != "AUT_STAT_FAILED" &&
                        toInt(row.differenceCount) == 0
                if (!clean) break
                streak++
                if (streak >= LOOKBACK_LIMIT) break
            }
            return streak
        } catch (Throwable t) {
            // Best-effort by design: a streak lookup failing must never cost the notification.
            logger.warn("Clean-run streak lookback failed for savedRun {} tenant {}: {}",
                    savedRunId, tenantId, t.message)
            return 0
        }
    }

    private static int toInt(Object rawValue) {
        if (rawValue instanceof Number) return ((Number) rawValue).intValue()
        String text = ((rawValue)?.toString()?.trim())
        return (text && text.isInteger()) ? text.toInteger() : 0
    }
}
