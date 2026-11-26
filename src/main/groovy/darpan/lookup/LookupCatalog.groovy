package darpan.lookup

/**
 * DAR-CLI-008 part 3: the follow-the-order lookup's reads and links. Product rows (LookupRead, LookupLink) ship in each
 * system repo's seed data; a tenant's rows (TenantLookupRead, TenantLookupLink) add new ones, override a product row with
 * the same id field by field (a blank tenant field keeps the product value), or hide it (isActive N).
 */
class LookupCatalog {

    static final List<String> READ_FIELDS = ["readId", "systemCode", "recordKind", "keyField", "queryTemplate", "valueShape",
                                             "valueFormat", "recordsAt", "fieldsDefault", "isFind", "sequenceNum", "description"].asImmutable()
    static final List<String> LINK_FIELDS = ["linkId", "fromReadId", "fromField", "toReadId", "description"].asImmutable()

    /** Product reads merged with the tenant's, ordered by sequenceNum then readId. */
    static List<Map> reads(def ec, String tenantId) {
        return merged(ec, "darpan.lookup.LookupRead", "darpan.lookup.TenantLookupRead", "readId", READ_FIELDS, tenantId)
                .sort { Map a, Map b -> ((a.sequenceNum ?: 0) as long) <=> ((b.sequenceNum ?: 0) as long) ?: (a.readId as String) <=> (b.readId as String) }
    }

    /** Product links merged with the tenant's, ordered by linkId. */
    static List<Map> links(def ec, String tenantId) {
        return merged(ec, "darpan.lookup.LookupLink", "darpan.lookup.TenantLookupLink", "linkId", LINK_FIELDS, tenantId)
                .sort { Map a, Map b -> (a.linkId as String) <=> (b.linkId as String) }
    }

    private static List<Map> merged(def ec, String productEntity, String tenantEntity, String idField, List<String> fields, String tenantId) {
        Map<String, Map> byId = new LinkedHashMap<>()
        ec.entity.find(productEntity).useCache(false).disableAuthz().list().each { row ->
            byId.put(row.get(idField) as String, fields.collectEntries { [(it): row.get(it)] })
        }
        if (tenantId) {
            ec.entity.find(tenantEntity).condition("companyUserGroupId", tenantId).useCache(false).disableAuthz().list().each { row ->
                String id = row.get(idField) as String
                if ("N" == row.get("isActive")) { byId.remove(id); return }
                Map base = byId.containsKey(id) ? new LinkedHashMap(byId.get(id)) : [:]
                fields.each { String f -> Object v = row.get(f); if (v != null && v.toString() != "") base.put(f, v) }
                byId.put(id, base)
            }
        }
        return new ArrayList<Map>(byId.values())
    }
}
