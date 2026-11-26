package darpan.idindex

import darpan.config.DarpanSettings

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * DAR-BE-374: what the ID index reads. Sources (IdIndexSource) are product rows; fields (IdIndexField) are product rows a
 * tenant adds to, overrides field by field (same fieldId; a blank tenant field keeps the product value) or hides (isActive N)
 * in TenantIdIndexField. A tenant field that looks like a customer detail is refused at save, so a tenant edit cannot make
 * the index a store of personal data.
 */
class IdIndexCatalog {

    static final String CUSTOMER_PATTERNS_SETTING = "darpan.idindex.customerFieldPatterns"
    /** A path segment matching any of these is a customer detail. A bare "name" is allowed: it is the Shopify order name. */
    static final List<String> DEFAULT_CUSTOMER_PATTERNS = ["(?i).*(email|phone|address|firstname|lastname|fullname|contactname).*",
                                                           "(?i)(customer|billing|shipping|buyer|contact).*",
                                                           "(?i)(city|zip.*|postal.*|province|country)",
                                                           "(?i)(note|notes|noteattributes|tags|browserip|taxid|taxnumber|company|companyname)"].asImmutable()
    /** The only path shape a tenant field may use: dotted identifier pieces, each optionally a [*] list. */
    static final Pattern PLAIN_PATH = Pattern.compile('^[A-Za-z_][A-Za-z0-9_]*(\\[\\*\\])?(\\.[A-Za-z_][A-Za-z0-9_]*(\\[\\*\\])?)*$')
    static final List<String> FIELD_FIELDS = ["systemEnumId", "fieldId", "recordKind", "fieldPath", "role", "refRecordKind", "description"].asImmutable()

    /** Active sources, ordered by systemEnumId. */
    static List<Map> sources(def ec) {
        return ec.entity.find("darpan.idindex.IdIndexSource").useCache(false).disableAuthz().orderBy("systemEnumId").list()
                .findAll { it.isActive != "N" }.collect { new LinkedHashMap(it.getMap()) }
    }

    /** The tenant's fields for one source: product rows merged with the tenant's, product order first, additions after. */
    static List<Map> fields(def ec, String tenantId, String systemEnumId) {
        List<Map> product = ec.entity.find("darpan.idindex.IdIndexField").condition("systemEnumId", systemEnumId)
                .orderBy("fieldId").useCache(false).disableAuthz().list().collect { new LinkedHashMap(it.getMap()) }
        List<Map> tenant = tenantId ? ec.entity.find("darpan.idindex.TenantIdIndexField").condition("companyUserGroupId", tenantId)
                .condition("systemEnumId", systemEnumId).orderBy("fieldId").useCache(false).disableAuthz().list()
                .collect { new LinkedHashMap(it.getMap()) } : []
        return mergeFields(product, tenant)
    }

    /** Pure merge: tenant wins by fieldId field by field; isActive N removes; new ids are added after the product rows. */
    static List<Map> mergeFields(List<Map> product, List<Map> tenant) {
        Map<String, Map> byId = new LinkedHashMap<>()
        product.each { Map row -> byId.put(row.fieldId as String, FIELD_FIELDS.collectEntries { [(it): row.get(it)] }) }
        tenant.each { Map row ->
            String id = row.fieldId as String
            if ("N" == row.isActive) { byId.remove(id); return }
            Map base = byId.containsKey(id) ? new LinkedHashMap(byId.get(id)) : [:]
            FIELD_FIELDS.each { String f -> Object v = row.get(f); if (v != null && v.toString() != "") base.put(f, v) }
            byId.put(id, base)
        }
        return new ArrayList<Map>(byId.values())
    }

    /**
     * The reason a tenant field path is refused, or null: a path outside the plain dotted shape (so no JSONPath spelling,
     * quoted key or recursive descent can reach a field the guard did not see), or a customer detail.
     */
    static String fieldPathRefusal(String fieldPath) {
        return fieldPathRefusal(fieldPath, customerPatterns())
    }

    static String fieldPathRefusal(String fieldPath, List<String> patterns) {
        if (!fieldPath || !PLAIN_PATH.matcher(fieldPath).matches()) {
            return "${fieldPath} is not a plain field path (name.name, lists as name[*]); the ID index reads only those".toString()
        }
        return customerFieldRefusal(fieldPath, patterns)
    }

    /**
     * Pure: a segment matching any pattern refuses the path; a pattern that does not compile is skipped. Each segment is
     * matched as written and with underscores and dashes removed, so e_mail or first_name read as email and firstname.
     */
    static String customerFieldRefusal(String fieldPath, List<String> patterns) {
        List<String> segments = (fieldPath ?: "").split(/\.|\[\*\]|\[\d+\]/).findAll { it }
                .collectMany { [it, it.replaceAll(/[_\-]/, "")] }
        for (String p : patterns) {
            Pattern compiled
            try { compiled = Pattern.compile(p) } catch (PatternSyntaxException ignored) { continue }
            String hit = segments.find { compiled.matcher(it).matches() }
            if (hit) return "${fieldPath} looks like a customer detail (${hit}); the ID index keeps identifiers only".toString()
        }
        return null
    }

    /**
     * Why a tenant field save is refused, or null: the system must be one the index reads; a field with no product row of the
     * same id needs a path and a role (OWN or REF); a path must be plain and no customer detail.
     */
    static String tenantFieldRefusal(def ec, Map p) {
        if (!sources(ec).any { it.systemEnumId == p.systemEnumId }) return "The ID index does not read ${p.systemEnumId}".toString()
        if (p.role && !(p.role in ["OWN", "REF"])) return "role is OWN or REF"
        boolean product = ec.entity.find("darpan.idindex.IdIndexField").condition("systemEnumId", p.systemEnumId)
                .condition("fieldId", p.fieldId).useCache(false).disableAuthz().one() != null
        if (!product && "N" != p.isActive && (!p.fieldPath || !p.role)) return "A new ID field needs fieldPath and role (OWN or REF)"
        return p.fieldPath ? fieldPathRefusal(p.fieldPath as String) : null
    }

    /** A tenant field changed: that system's kept copies count as not indexed, so the nightly catch-up reads them again. */
    static void markForReindex(def ec, String tenantId, String systemEnumId) {
        ec.entity.find(IdIndexDatasetHook.CURSOR).condition("companyUserGroupId", tenantId).condition("systemEnumId", systemEnumId)
                .disableAuthz().updateAll([indexedVersion: null])
    }

    static List<String> customerPatterns() {
        String text = null
        try { text = DarpanSettings.stringValue(CUSTOMER_PATTERNS_SETTING) } catch (Exception ignored) { }
        List<String> fromSetting = (text ?: "").split(/\r?\n/).collect { it.trim() }.findAll { it }
        return fromSetting ?: DEFAULT_CUSTOMER_PATTERNS
    }
}
