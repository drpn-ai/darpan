package darpan.lookup

import darpan.facade.common.TenantAccessSupport
import darpan.idindex.IdIndexSearch

/**
 * DAR-BE-374 (replaces DAR-CLI-008 part 3's live lookup): an id is answered from what Darpan already holds — the ID index (every
 * id Darpan's runs and dataset reads have seen, with the records each belongs to and how they connect) and the tenant's open
 * findings. Nothing here calls Shopify, HotWax or NetSuite. An id no Darpan read has seen yet comes back as notSeen.
 */
class OrderLookup {

    /** The lookup for the active tenant: [value, records, findings, notSeen, capped, cappedReason]; null with an error message. */
    static Map runLive(def ec, String value) {
        String tenantId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!tenantId) { ec.message.addError("No active tenant."); return null }
        if (!value?.trim()) { ec.message.addError("Give the id to look up."); return null }
        Map search = IdIndexSearch.search(IdIndexSearch.liveDeps(ec, tenantId), value)
        List<Map> records = (List<Map>) search.records
        Set<String> ids = new LinkedHashSet<String>([value.trim()])
        records.each { Map r -> ((List<Map>) (r.ids ?: [])).each { ids << (it.idRaw as String) } }
        List<Map> findings = openFindings(ec, tenantId, ids)
        return [value: value.trim(), records: records, findings: findings, notSeen: records.isEmpty() && findings.isEmpty(),
                capped: search.capped, cappedReason: search.cappedReason]
    }

    private static List<Map> openFindings(def ec, String tenantId, Collection<String> ids) {
        if (!ids) return []
        def ecf = ec.entity.conditionFactory
        List<String> keys = new ArrayList<String>(ids).take(60)
        Closure<List> idOrLine = { String field -> [ecf.makeCondition(field, org.moqui.entity.EntityCondition.IN, keys)] +
                keys.collect { String k -> ecf.makeCondition(field, org.moqui.entity.EntityCondition.LIKE, k.replace("%", "").replace("_", "\\_") + "/%") } }
        Set<String> findingIds = new LinkedHashSet<>()
        ec.entity.find("darpan.audit.AuditFinding").condition("companyUserGroupId", tenantId)
                .condition(ecf.makeCondition(idOrLine("recordKey"), org.moqui.entity.EntityCondition.OR)).condition("stateEnumId", "AUDFS_OPEN")
                .selectField("findingId").limit(50).useCache(false).list().each { findingIds << (it.findingId as String) }
        ec.entity.find("darpan.audit.AuditFindingRecord").condition("companyUserGroupId", tenantId)
                .condition(ecf.makeCondition(idOrLine("recordId") + [ecf.makeCondition("internalId", org.moqui.entity.EntityCondition.IN, keys)], org.moqui.entity.EntityCondition.OR))
                .selectField("findingId").limit(200).useCache(false).list().each { findingIds << (it.findingId as String) }
        if (!findingIds) return []
        return ec.entity.find("darpan.audit.AuditFinding").condition("companyUserGroupId", tenantId)
                .condition("findingId", org.moqui.entity.EntityCondition.IN, new ArrayList<String>(findingIds)).condition("stateEnumId", "AUDFS_OPEN")
                .orderBy("-lastSeenDate").limit(50).useCache(false).list()
                .collect { [findingId: it.findingId, recordKey: it.recordKey, headline: it.headline, period: it.period] }
    }
}
