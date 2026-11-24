package darpan.audit

import darpan.facade.common.TenantScopedFinder

import java.sql.Date
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
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

    /** True when the finding is the tenant's own; another tenant's reads exactly like one that does not exist. */
    static boolean ownsFinding(def ec, String tenantId, String findingId) {
        if (!tenantId || !findingId) return false
        return TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.AuditFinding", "findingId", findingId, tenantId, WHY) != null
    }

    /**
     * DAR-UI-072. What each system's read wrote for one check's morning: a row per side of the automation execution the
     * run recorded that kept a file, file 1 first. Empty when the check did not run that morning, or ran before the
     * execution was kept. Every read is the tenant's own.
     */
    static List<Map> checkExtracts(def ec, String tenantId, String checkCode, Date reportDate, String period) {
        if (!tenantId || !checkCode || !reportDate || !period) return []
        String checkId = tenantRows(ec, "darpan.audit.AuditCheck", tenantId).condition("checkCode", checkCode).list().find()?.get("checkId")
        String executionId = checkId ? tenantRows(ec, "darpan.audit.AuditCheckRun", tenantId).condition("checkId", checkId)
                .condition("reportDate", reportDate).condition("period", period).list().find()?.get("automationExecutionId") : null
        def execution = executionId ? tenantRows(ec, "darpan.reconciliation.ReconciliationAutomationExecution", tenantId)
                .condition("automationExecutionId", executionId).list().find() : null
        if (execution == null) return []
        Map<String, String> systemBySide = tenantRows(ec, "darpan.reconciliation.ReconciliationAutomationSource", tenantId)
                .condition("automationId", execution.get("automationId")).list().collectEntries { [(it.fileSide): it.systemEnumId] }
        return [["FILE_1", "file1"], ["FILE_2", "file2"]].collect { String side, String field ->
            String path = execution.get("${field}DataManagerPath".toString()) as String
            String system = systemBySide[side]
            path ? [system  : system ? AuditReportAssembler.systemKey(AuditAutomationSupport.rootSystem(system)) : null,
                    records : execution.get("${field}RecordCount".toString()),
                    filePath: path] : null
        }.findAll() as List<Map>
    }

    private static def tenantRows(def ec, String entityName, String tenantId) {
        return TenantScopedFinder.findGlobalUnscoped(ec, entityName, WHY).condition("companyUserGroupId", tenantId).useCache(false)
    }

    /** The morning to report: date as YYYY-MM-DD, or blank for today in the tenant's zone. Throws on a malformed date. */
    static Date reportDateFor(def ec, String tenantId, String date) {
        if (date?.trim()) {
            try { return Date.valueOf(LocalDate.parse(date.trim())) }
            catch (DateTimeParseException ignored) { throw new IllegalArgumentException("date must be YYYY-MM-DD, got ${date}") }
        }
        return Date.valueOf(LocalDate.now(tenantZone(ec, tenantId)))
    }

    /**
     * DAR-BE-347. The morning a report shows: the date asked for, else the last morning that was due. Today has no runs
     * from the tenant's midnight until the audit's scheduled time, so a blank date shows yesterday until then (or until
     * a check of this audit already ran today). A retry runs now, so it keeps reportDateFor's today.
     */
    static Date morningOnShow(def ec, String tenantId, String auditId, String date, String period) {
        if (date?.trim()) return reportDateFor(ec, tenantId, date)
        ZoneId zone = tenantZone(ec, tenantId)
        ZonedDateTime now = ZonedDateTime.now(zone)
        LocalDate today = now.toLocalDate()
        Map audit = auditId ? TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.Audit", "auditId", auditId, tenantId, WHY)?.getMap() : null
        if (audit == null) return Date.valueOf(today)
        ZoneId scheduleZone = zoneOr(audit.scheduleTimeZone as String, zone)
        boolean ranToday = auditRanOn(ec, tenantId, auditId, today)
        boolean yesterdayInPeriod = !period?.trim() || AuditReportAssembler.periodWindow(ec, audit, period.trim(), today.minusDays(1)) != null
        return Date.valueOf(defaultMorning(now, zone, (audit.scheduleTime as String)?.trim() ?: AuditSettingsSupport.DEFAULT_TIME,
                scheduleZone, audit.scheduleActive == "Y", ranToday, yesterdayInPeriod))
    }

    /** DAR-BE-347. Today in the tenant's zone, or yesterday while today's scheduled run is still ahead (pure). */
    static LocalDate defaultMorning(ZonedDateTime now, ZoneId tenantZone, String scheduleTime, ZoneId scheduleZone,
                                    boolean scheduled, boolean ranToday, boolean yesterdayInPeriod) {
        LocalDate today = now.withZoneSameInstant(tenantZone).toLocalDate()
        if (!scheduled || ranToday || !yesterdayInPeriod) return today
        // A run reports on its fire date in the schedule's zone, so today's run fires at today + time there.
        ZonedDateTime due = ZonedDateTime.of(today, LocalTime.parse(scheduleTime), scheduleZone)
        return now.isBefore(due) ? today.minusDays(1) : today
    }

    private static boolean auditRanOn(def ec, String tenantId, String auditId, LocalDate day) {
        List statementIds = tenantRows(ec, "darpan.audit.AuditStatement", tenantId).condition("auditId", auditId).list()*.statementId
        if (!statementIds) return false
        List checkIds = tenantRows(ec, "darpan.audit.AuditStatementCheck", tenantId).condition("statementId", "in", statementIds)
                .list()*.checkId.unique()
        return checkIds && tenantRows(ec, "darpan.audit.AuditCheckRun", tenantId).condition("reportDate", Date.valueOf(day))
                .condition("checkId", "in", checkIds).count() > 0
    }

    private static ZoneId tenantZone(def ec, String tenantId) {
        def setting = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.auth.TenantSetting", WHY)
                .condition("companyUserGroupId", tenantId).useCache(false).one()
        return zoneOr(setting?.timeZone as String, ZoneId.of("UTC"))
    }

    private static ZoneId zoneOr(String id, ZoneId fallback) {
        try { return id?.trim() ? ZoneId.of(id.trim()) : fallback }
        catch (Exception ignored) { return fallback }
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

    /** findings(query:): each node's records as a list, as in the report. */
    static Map findingsPage(Map page) {
        if (page == null) return null
        Map out = new LinkedHashMap(page)
        out.edges = ((List<Map>) page.edges ?: []).collect { Map edge ->
            Map node = new LinkedHashMap((Map) edge.node)
            node.records = records((Map) node.records)
            [cursor: edge.cursor, node: node]
        }
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
