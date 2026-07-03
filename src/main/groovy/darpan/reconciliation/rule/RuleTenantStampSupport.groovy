package darpan.reconciliation.rule

import darpan.facade.common.TenantScopedFinder
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.readField

/**
 * Backfill sweep for the denormalized {@code companyUserGroupId} stamp on Rule /
 * RuleSetCompareScope / RuleSetCompareSource (MACH P1 defense-in-depth).
 *
 * <p>New rows are stamped at create/save time from the parent RuleSet; this sweep heals rows
 * created before the stamp existed (and any row that slipped through a non-facade write path).
 * It is idempotent and cheap after the first pass — the NULL-stamp query walks the new
 * tenant index and finds nothing.</p>
 */
class RuleTenantStampSupport {

    private static final Logger logger = LoggerFactory.getLogger(RuleTenantStampSupport.class)

    private static final String SWEEP_REASON =
            "system backfill sweep: stamp Rule-child companyUserGroupId from the parent RuleSet (all tenants)"

    /** @return number of child rows stamped in this pass */
    static int sweep(def ec) {
        // System sweep across all tenants: the parent RuleSet rows are the stamp source.
        Map<String, String> tenantByRuleSetId = [:]
        (TenantScopedFinder.findGlobalUnscoped(ec, "darpan.rule.RuleSet", SWEEP_REASON)
                .useCache(false).list() ?: []).each { ruleSet ->
            String ruleSetId = normalize(readField(ruleSet, "ruleSetId"))
            String tenantId = normalize(readField(ruleSet, "companyUserGroupId"))
            if (ruleSetId && tenantId) tenantByRuleSetId[ruleSetId] = tenantId
        }
        if (!tenantByRuleSetId) return 0

        int stamped = 0
        Map<String, String> tenantByCompareScopeId = [:]

        // Children keyed by ruleSetId directly.
        ["darpan.rule.Rule", "darpan.rule.RuleSetCompareScope"].each { String entityName ->
            (TenantScopedFinder.findGlobalUnscoped(ec, entityName, SWEEP_REASON)
                    .condition("companyUserGroupId", null).useCache(false).list() ?: []).each { row ->
                String tenantId = tenantByRuleSetId[normalize(readField(row, "ruleSetId"))]
                if (!tenantId) return
                row.set("companyUserGroupId", tenantId)
                row.update()
                stamped++
            }
        }
        // Sources hang off the compare scope — resolve scope -> ruleSet -> tenant.
        (TenantScopedFinder.findGlobalUnscoped(ec, "darpan.rule.RuleSetCompareScope", SWEEP_REASON)
                .useCache(false).list() ?: []).each { scope ->
            String scopeId = normalize(readField(scope, "compareScopeId"))
            String tenantId = normalize(readField(scope, "companyUserGroupId")) ?:
                    tenantByRuleSetId[normalize(readField(scope, "ruleSetId"))]
            if (scopeId && tenantId) tenantByCompareScopeId[scopeId] = tenantId
        }
        (TenantScopedFinder.findGlobalUnscoped(ec, "darpan.rule.RuleSetCompareSource", SWEEP_REASON)
                .condition("companyUserGroupId", null).useCache(false).list() ?: []).each { row ->
            String tenantId = tenantByCompareScopeId[normalize(readField(row, "compareScopeId"))]
            if (!tenantId) return
            row.set("companyUserGroupId", tenantId)
            row.update()
            stamped++
        }

        if (stamped) logger.info("[rule-tenant-stamp] backfilled companyUserGroupId on {} rule-child rows", stamped)
        return stamped
    }
}
