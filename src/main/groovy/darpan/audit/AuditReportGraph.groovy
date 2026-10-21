package darpan.audit

import darpan.facade.common.TenantScopedFinder

import java.sql.Date
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * DAR-BE-098. The audit-run JSON in its GraphQL shape (contract: "GraphQL mapping"). GraphQL has no maps with dynamic
 * keys, so each keyed object becomes a list whose elements carry the key as a field; every other key, type, null and
 * enum code is the JSON's own. Pure functions over the assembler's documents: the JSON stays primary, and this is the
 * only place the two shapes differ.
 *
 *   health.sources.NetSuite             -> sources: [{ system: "NetSuite", ... }]
 *   summary.byCategory.orders           -> byCategory: [{ category: "orders", ... }]
 *   categories.returns                  -> categories: [{ id: "returns", ... }]
 *   statements.R8                       -> statements: [{ id: "R8", ... }]
 *   compared.OMS                        -> compared: [{ system: "OMS", count }]
 *   records.NetSuite.creditMemo[]       -> records: [{ system, recordType, id, internalId, status }]
 */
class AuditReportGraph {

    private static final String WHY = "audit graph: explicit companyUserGroupId condition applied"

    /** The audit's id from its code (report.audit.id in the JSON), within the tenant only; null when it has none. */
    static String auditIdFor(def ec, String tenantId, String auditCode) {
        if (!tenantId || !auditCode) return null
        return TenantScopedFinder.findGlobalUnscoped(ec, "darpan.audit.Audit", WHY).condition("companyUserGroupId", tenantId)
                .condition("auditCode", auditCode).useCache(false).list().find()?.get("auditId") as String
    }

    /** The morning to report: date as YYYY-MM-DD, or blank for today in the tenant's zone. Throws on a malformed date. */
    static Date reportDateFor(def ec, String tenantId, String date) {
        if (date?.trim()) {
            try { return Date.valueOf(LocalDate.parse(date.trim())) }
            catch (DateTimeParseException ignored) { throw new IllegalArgumentException("date must be YYYY-MM-DD, got ${date}") }
        }
        def setting = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.auth.TenantSetting", WHY)
                .condition("companyUserGroupId", tenantId).useCache(false).one()
        ZoneId zone
        try { zone = setting?.timeZone ? ZoneId.of(setting.timeZone as String) : ZoneId.of("UTC") }
        catch (Exception ignored) { zone = ZoneId.of("UTC") }
        return Date.valueOf(LocalDate.now(zone))
    }

    static Map report(Map document) {
        if (document == null) return null
        Map out = new LinkedHashMap(document)
        Map health = new LinkedHashMap((Map) document.health ?: [:])
        health.sources = keyed((Map) health.sources, "system")
        out.health = health
        Map summary = new LinkedHashMap((Map) document.summary ?: [:])
        summary.byCategory = keyed((Map) summary.byCategory, "category")
        out.summary = summary
        out.categories = keyed((Map) document.categories, "id").collect { Map category ->
            category.statements = keyed((Map) category.statements, "id").collect { Map st -> statement(st) }
            category
        }
        return out
    }

    static Map finding(Map document) {
        if (document == null) return null
        Map out = new LinkedHashMap(document)
        Map f = new LinkedHashMap((Map) document.finding ?: [:])
        f.records = records((Map) f.records)
        out.finding = f
        return out
    }

    private static Map statement(Map st) {
        st.checks = ((List<Map>) st.checks ?: []).collect { Map check ->
            Map c = new LinkedHashMap(check)
            c.compared = ((Map) check.compared ?: [:]).collect { k, v -> [system: k, count: v] }
            c
        }
        st.findings = ((List<Map>) st.findings ?: []).collect { Map finding ->
            Map f = new LinkedHashMap(finding)
            f.records = records((Map) finding.records)
            f
        }
        return st
    }

    /** {key: {...}} -> [{keyName: key, ...}], in the document's order (categories and statements are ordered). */
    private static List<Map> keyed(Map members, String keyName) {
        return (members ?: [:]).collect { k, v -> ([(keyName): k] + ((Map) v ?: [:])) as Map }
    }

    /** {System: {recordType: [{id, internalId, status}]}} -> [{system, recordType, id, internalId, status}]. */
    private static List<Map> records(Map bySystem) {
        List<Map> out = []
        (bySystem ?: [:]).each { system, byType ->
            ((Map) byType ?: [:]).each { recordType, list ->
                ((List<Map>) list ?: []).each { Map r -> out << ([system: system, recordType: recordType] + r) }
            }
        }
        return out
    }
}
