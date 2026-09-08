package darpan.reconciliation.notification

import darpan.common.DarpanEntityConstants
import darpan.facade.common.TenantScopedFinder
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Counts how many consecutive clean runs preceded this one, for the same SCHEDULE and tenant.
 * Isolated from RunNotificationVoice so that class stays Moqui-free and unit-testable.
 *
 * <p>DAR-BE-043: the series used to be keyed on savedRunId + tenant alone. That is not one schedule's
 * history — {@code ReconciliationAutomation.savedRunId} is the operator's chosen saved run, and for
 * RuleSet-backed runs it simply equals ruleSetId, so every automation over the same ruleset shares it.
 * A weekly automation's notification therefore counted a daily automation's runs, and worse, a daily
 * automation's bad run silently reset the weekly's streak. The series is now the runs of the automation
 * that produced THIS run; a run with no automation behind it (manual, ad-hoc) keeps the saved-run
 * series it has always had, because there is no schedule to scope it to.</p>
 */
class RunNotificationStreak {

    // Defined on RunNotificationVoice so the pure-logic class never points at this Moqui-importing
    // one — that direction would drag RunNotificationVoiceTests out of the fast unitTest pool.
    static final int LOOKBACK_LIMIT = RunNotificationVoice.LOOKBACK_LIMIT

    /**
     * How many execution rows to read when resolving one automation's own run-result ids. Deliberately
     * larger than LOOKBACK_LIMIT: an execution that never reached RUNNING carries no
     * reconciliationRunResultId and contributes nothing to the series, so reading exactly LOOKBACK_LIMIT
     * rows could return fewer results than the lookback is entitled to.
     */
    private static final int EXECUTION_SCAN_LIMIT = (LOOKBACK_LIMIT * 3) + 10

    private static final Logger logger = LoggerFactory.getLogger(RunNotificationStreak.class)

    static int countConsecutiveCleanRuns(def ec, String savedRunId, String tenantId, String currentResultId) {
        // Ad-hoc runs have no savedRunId and therefore no series to form a streak from.
        if (!savedRunId || !tenantId) return 0
        try {
            String automationId = resolveAutomationIdForRunResult(ec, currentResultId, tenantId)
            List rows = automationId ?
                    loadRunResultsForAutomation(ec, automationId, tenantId) :
                    loadRunResultsForSavedRun(ec, savedRunId, tenantId)

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

    /**
     * The automation this run belongs to, or null when nothing scheduled it. The link only exists in
     * this direction — ReconciliationRunResult carries no automationId, while
     * ReconciliationAutomationExecution carries both ids — so the scope is resolved by looking the
     * execution up rather than by reading a column off the run.
     *
     * <p>Read as a list rather than {@code one()} on purpose: nothing in the schema forbids two
     * execution rows naming the same run result, and a "found more than one" throw here would cost the
     * whole streak for a case that has an obvious answer.</p>
     */
    private static String resolveAutomationIdForRunResult(def ec, String currentResultId, String tenantId) {
        if (!currentResultId) return null
        List executions = TenantScopedFinder.findGlobalUnscoped(ec,
                        DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION,
                        "streak scope lookup pinned to run tenant — explicit companyUserGroupId condition applied below")
                ?.condition("reconciliationRunResultId", currentResultId)
                ?.condition("companyUserGroupId", tenantId)
                ?.limit(1)
                ?.useCache(false)?.list() ?: []
        if (!executions) return null
        return ((executions[0].automationId)?.toString()?.trim()) ?: null
    }

    /** The run results this automation produced, newest first. */
    private static List loadRunResultsForAutomation(def ec, String automationId, String tenantId) {
        List executions = TenantScopedFinder.findGlobalUnscoped(ec,
                        DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION,
                        "streak lookback pinned to run tenant — explicit companyUserGroupId condition applied below")
                ?.condition("automationId", automationId)
                ?.condition("companyUserGroupId", tenantId)
                ?.orderBy("-createdDate")
                ?.limit(EXECUTION_SCAN_LIMIT)
                ?.useCache(false)?.list() ?: []

        List<String> resultIds = executions
                .collect { ((it.reconciliationRunResultId)?.toString()?.trim()) }
                .findAll { it }
                .unique() as List<String>
        if (!resultIds) return []

        return TenantScopedFinder.findGlobalUnscoped(ec,
                        DarpanEntityConstants.RECONCILIATION_RUN_RESULT,
                        "streak lookback pinned to run tenant — explicit companyUserGroupId condition applied below")
                ?.condition("reconciliationRunResultId", "in", resultIds)
                ?.condition("companyUserGroupId", tenantId)
                ?.orderBy("-completedDate")
                ?.limit(LOOKBACK_LIMIT + 1)
                ?.useCache(false)?.list() ?: []
    }

    /** The pre-DAR-BE-043 series, kept for runs that no automation produced. */
    private static List loadRunResultsForSavedRun(def ec, String savedRunId, String tenantId) {
        return TenantScopedFinder.findGlobalUnscoped(ec,
                        DarpanEntityConstants.RECONCILIATION_RUN_RESULT,
                        "streak lookback pinned to run tenant — explicit companyUserGroupId condition applied below")
                ?.condition("savedRunId", savedRunId)
                ?.condition("companyUserGroupId", tenantId)
                ?.orderBy("-completedDate")
                ?.limit(LOOKBACK_LIMIT + 1)
                ?.useCache(false)?.list() ?: []
    }

    private static int toInt(Object rawValue) {
        if (rawValue instanceof Number) return ((Number) rawValue).intValue()
        String text = ((rawValue)?.toString()?.trim())
        return (text && text.isInteger()) ? text.toInteger() : 0
    }
}
