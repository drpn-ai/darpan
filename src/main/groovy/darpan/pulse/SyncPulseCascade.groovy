package darpan.pulse

import darpan.facade.common.TenantScopedFinder

import static darpan.common.ValueSupport.normalize

/** DAR-BE-328: deleting an automation takes its sync pairs (FK SYNCPAIR_AUTOMATION) and their children with it. */
class SyncPulseCascade {
    private static final String WHY = "deleting an automation removes its sync pairs: explicit companyUserGroupId condition applied"

    private SyncPulseCascade() {}

    /** Deletes the tenant's sync pairs of this automation, children first. Returns how many pairs were deleted. */
    static int deleteForAutomation(def ec, String tenantId, String automationId) {
        String tenant = normalize(tenantId)
        String automation = normalize(automationId)
        if (!tenant || !automation) return 0
        List<String> pairIds = rows(ec, "darpan.pulse.SyncPair", tenant).condition("automationId", automation).list()
                .collect { it.get("syncPairId") as String }
        pairIds.each { String pairId -> deletePair(ec, tenant, pairId) }
        return pairIds.size()
    }

    /** Deletes one sync pair of the tenant with its peak overrides, samples and orders. Returns 1 when the pair existed, else 0. */
    static int deletePair(def ec, String tenantId, String syncPairId) {
        String tenant = normalize(tenantId)
        String pairId = normalize(syncPairId)
        if (!tenant || !pairId) return 0
        ["darpan.pulse.PeakPeriodSync", "darpan.pulse.SyncPulseSample", "darpan.pulse.SyncPulseOrder"].each { String child ->
            rows(ec, child, tenant).condition("syncPairId", pairId).deleteAll()
        }
        return (int) rows(ec, "darpan.pulse.SyncPair", tenant).condition("syncPairId", pairId).deleteAll()
    }

    private static def rows(def ec, String entityName, String tenantId) {
        return TenantScopedFinder.findGlobalUnscopedPerRequest(ec, entityName, WHY).condition("companyUserGroupId", tenantId).useCache(false)
    }
}
