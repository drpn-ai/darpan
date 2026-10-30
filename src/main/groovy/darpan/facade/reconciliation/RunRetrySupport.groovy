package darpan.facade.reconciliation

import darpan.common.DarpanEntityConstants
import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.automation.ExtractReplaySupport
import groovy.transform.CompileDynamic

import java.sql.Timestamp

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.readField

/**
 * DAR-BE-163. Retry a failed run from the step it failed at: the sides it finished reading are handed back as they
 * landed and only the rest is read again. SM run 100766 read HotWax for 17m53s and then lost it to a Shopify OOM.
 *
 * A retry is a new run, linked to the failed one by parentRunResultId, over the failed run's own window. Refused, in
 * words, unless the failed run is the active tenant's, FAILED, a run of this rule set, read at least one side, and its
 * rule set has not been edited since it started: an older extract against a newer config answers a different question.
 * A deleted source, key field or filter row leaves no stamp behind, so the edit check cannot see a removal.
 */
@CompileDynamic
class RunRetrySupport {

    private static final String WHY = "run retry: explicit companyUserGroupId condition applied"
    /** What a rule-set run's extracts and compare are made of, below the RuleSet row itself (by compareScopeId). */
    static final List<String> COMPARE_SCOPE_ENTITIES = [
            DarpanEntityConstants.RULE_SET_COMPARE_SCOPE,
            "darpan.rule.RuleSetCompareSource",
            "darpan.rule.RuleSetCompareSourceKeyField",
            "darpan.rule.RuleSetCompareSourceFilter",
    ]

    /**
     * [sourceRunResultId, windowStartDate, windowEndDate, windowTimeZone, sides: side -> landed file] for a retry of
     * sourceRunResultId as a run of savedRunId, or [error: words].
     */
    static Map<String, Object> plan(def ec, String tenantId, String sourceRunResultId, String savedRunId) {
        String runId = normalize(sourceRunResultId)
        def run = TenantScopedFinder.findOneOwnedByTenant(ec, DarpanEntityConstants.RECONCILIATION_RUN_RESULT,
                "reconciliationRunResultId", runId, tenantId, "run retry: the failed run's owner is checked on the row")
        if (run == null) return [error: "Run ${runId} was not found.".toString()]
        String status = normalize(readField(run, "statusEnumId"))
        if (status != RunObservability.STATUS_FAILED) {
            return [error: "Only a failed run can be retried; run ${runId} is ${status?.replace('AUT_STAT_', '') ?: 'not finished'}.".toString()]
        }
        if (normalize(readField(run, "savedRunId")) != normalize(savedRunId)) {
            return [error: "Run ${runId} is not a run of ${savedRunId}.".toString()]
        }
        String ruleSetId = normalize(readField(run, "ruleSetId"))
        String compareScopeId = normalize(readField(run, "compareScopeId"))
        if (!ruleSetId || !compareScopeId) return [error: "Only a rule-set run can be retried from a step; run ${savedRunId} again.".toString()]
        Timestamp started = (Timestamp) readField(run, "startedDate")
        if (started == null || editedSince(ec, tenantId, ruleSetId, compareScopeId, started)) {
            return [error: ("${savedRunId} was edited after run ${runId} started, so its extracts no longer answer the same " +
                    "question; run it again instead.").toString()]
        }
        Map<String, Map<String, Object>> sides = ExtractReplaySupport.landedSides(ec, tenantId, runId, run)
        if (!sides) {
            return [error: "Run ${runId} did not finish reading either side, so there is nothing to retry from; run it again instead.".toString()]
        }
        return [sourceRunResultId: runId,
                windowStartDate  : readField(run, "windowStartDate"),
                windowEndDate    : readField(run, "windowEndDate"),
                windowTimeZone   : readField(run, "windowTimeZone"),
                sides            : sides]
    }

    /**
     * The side a retry hands back instead of reading it: what RunExtractSupport.extractApiSource returns for a fresh read
     * of that file, or null when the failed run did not finish reading this side.
     */
    static Map<String, Object> replayedSide(def ec, Map<String, Object> plan, Object source, String fileSide) {
        Map<String, Object> landed = (Map<String, Object>) ((Map) plan?.sides)?.get(fileSide)
        if (landed == null) return null
        String location = DataManagerSupport.childLocation(DataManagerSupport.resolveDataManagerLocation(ec),
                landed.dataManagerPath as String)
        return RunExtractSupport.sourceResultForLocation(ec, source, fileSide, landed.fileName as String, location,
                landed.recordCount as Integer)
    }

    /** True when the rule set or anything under its compare scope was written after `started`. */
    static boolean editedSince(def ec, String tenantId, String ruleSetId, String compareScopeId, Timestamp started) {
        if (TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.RULE_SET, WHY)
                .condition("companyUserGroupId", tenantId).condition("ruleSetId", ruleSetId)
                .condition("lastUpdatedStamp", "greater", started).useCache(false).count() > 0) return true
        return COMPARE_SCOPE_ENTITIES.any { String entityName ->
            TenantScopedFinder.findGlobalUnscoped(ec, entityName, WHY)
                    .condition("companyUserGroupId", tenantId).condition("compareScopeId", compareScopeId)
                    .condition("lastUpdatedStamp", "greater", started).useCache(false).count() > 0
        }
    }
}
