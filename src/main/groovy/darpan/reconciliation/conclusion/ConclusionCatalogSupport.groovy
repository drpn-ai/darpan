package darpan.reconciliation.conclusion

import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder

import static darpan.common.ValueSupport.normalize

/**
 * DAR-UI-064. Which conclusion codes a tenant may see and name. A code is a DarpanConclusion Enumeration: shared when it
 * came with a release (no TenantConclusion row), or one tenant's own when made in the app (a TenantConclusion row names
 * the tenant). Another tenant's code is neither listed nor accepted, so its label never reaches anyone else.
 */
class ConclusionCatalogSupport {

    static final String ENUM_TYPE = "DarpanConclusion"
    static final String OWNER = "darpan.rule.TenantConclusion"
    static final int MAX_LABEL = 100

    /** The active tenant's catalog: shared codes plus its own, by label. */
    static List<Map<String, Object>> catalog(def ec) {
        Map<String, String> ownerById = owners(ec)
        String tenantId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        return ec.entity.find("moqui.basic.Enumeration").condition("enumTypeId", ENUM_TYPE).orderBy("description")
                .useCache(false).list()
                .findAll { visibleTo(ownerById, it.enumId as String, tenantId) }
                .collect { [id: it.enumId, label: it.description ?: it.enumId, own: ownerById.containsKey(it.enumId as String)] as Map<String, Object> }
    }

    /** The code's Enumeration when the active tenant may name it; null otherwise (missing, not a conclusion, or another tenant's). */
    static def visibleConclusion(def ec, String enumId) {
        String id = normalize(enumId)
        def e = id ? ec.entity.find("moqui.basic.Enumeration").condition("enumId", id).useCache(true).one() : null
        if (e == null || e.enumTypeId != ENUM_TYPE) return null
        def owner = TenantScopedFinder.findGlobalUnscoped(ec, OWNER, "conclusion ownership: is this code another tenant's")
                .condition("enumId", id).useCache(false).one()
        return owner == null || owner.companyUserGroupId == TenantAccessSupport.currentActiveTenantUserGroupId(ec) ? e : null
    }

    /** Makes a code of the active tenant's own; refuses a label its catalog already has. Answers {id, label, own}. */
    static Map<String, Object> create(def ec, Object labelValue) {
        if (!TenantAccessSupport.requireActiveTenantWriteAccess(ec, "Your active tenant only has view access for runs.")) return null
        String tenantId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        String label = normalize(labelValue)
        if (!label) { ec.message.addError("Name the conclusion."); return null }
        if (label.length() > MAX_LABEL) { ec.message.addError("A conclusion's name is at most ${MAX_LABEL} characters."); return null }
        Map<String, Object> same = catalog(ec).find { (it.label as String).equalsIgnoreCase(label) }
        if (same) { ec.message.addError("'${label}' is already a conclusion; pick it."); return null }
        String enumId = "CONC_" + ec.entity.sequencedIdPrimary(OWNER, null, null)
        def now = ec.user.nowTimestamp
        ec.entity.makeValue("moqui.basic.Enumeration").setAll([enumId: enumId, enumTypeId: ENUM_TYPE, enumCode: enumId,
                description: label]).create()
        ec.entity.makeValue(OWNER).setAll([enumId: enumId, companyUserGroupId: tenantId,
                createdByUserId: TenantAccessSupport.currentUserId(ec), createdDate: now]).create()
        return [id: enumId, label: label, own: true] as Map<String, Object>
    }

    private static Map<String, String> owners(def ec) {
        // Every owner row, not only this tenant's: a code another tenant owns must be recognised so it can be HIDDEN.
        return TenantScopedFinder.findGlobalUnscoped(ec, OWNER, "conclusion ownership: hide other tenants' codes")
                .useCache(false).list().collectEntries { [(it.enumId as String): it.companyUserGroupId as String] }
    }

    private static boolean visibleTo(Map<String, String> ownerById, String enumId, String tenantId) {
        String owner = ownerById[enumId]
        return owner == null || owner == tenantId
    }
}
