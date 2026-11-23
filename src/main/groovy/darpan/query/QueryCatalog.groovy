package darpan.query

import darpan.facade.common.TenantScopedFinder
import groovy.json.JsonOutput
import org.moqui.jexl.script.ReadSpec

/**
 * DAR-BE-334 Task 9. What a tenant's query may read, and a proposed query checked against it then run before anyone sees
 * it. A query reads only what audits already keep: a dataset's kept fields (none listed = whole records), a saved run's
 * newest differences, an audit's findings (a fixed field list). A field or source outside the catalog is refused with a
 * plain reason and never run.
 */
class QueryCatalog {
    static final String PROPOSED = "PROPOSED"
    static final String REFUSED = "REFUSED"
    private static final String WHY = "DAR-BE-334 query catalog: conditioned on the active tenant"

    private static def tenantRows(def ec, String entityName, String tenantId) {
        return TenantScopedFinder.findGlobalUnscoped(ec, entityName, WHY).condition("companyUserGroupId", tenantId).useCache(false)
    }

    static Map catalog(def ec, String tenantId) {
        List<Map> datasets = tenantRows(ec, "darpan.dataset.Dataset", tenantId).orderBy("datasetId").list()
                .findAll { it.statusId == "DST_ACTIVE" }.collect { d ->
            Set<String> kept = DatasetQuerySource.keptFieldsOf.call(ec, d.getMap())
            def system = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.basic.Enumeration", "system name (reference data)")
                    .condition("enumId", d.systemEnumId).useCache(true).one()
            [datasetId: d.datasetId, system: system?.description ?: d.systemEnumId, idField: d.idField, windowField: d.windowField,
             recordCount: d.recordCount, fields: kept == null ? null : (kept as List<String>).sort(), wholeRecords: kept == null]
        }
        List<Map> runs = tenantRows(ec, "darpan.rule.RuleSet", tenantId).orderBy("ruleSetName").list().collect { r ->
            [savedRunId: r.ruleSetId, runName: r.ruleSetName ?: r.ruleSetId,
             fields: ["bucket", "key"], note: "plus each record's own data fields, flat"]
        }
        List<Map> audits = tenantRows(ec, "darpan.audit.Audit", tenantId).orderBy("auditCode").list().collect { a ->
            [auditId: a.auditId, auditCode: a.auditCode, auditName: a.auditName, fields: AuditFindingQuerySource.FIELDS]
        }
        return [datasets: datasets, savedRuns: runs, audits: audits]
    }

    /** Why the read cannot be proposed ([code, field, message]), or null when every input is in the catalog. */
    static Map problem(def ec, String tenantId, String readSpecJson) {
        if (!readSpecJson?.trim()) return null
        ReadSpec spec
        try {
            spec = ReadSpec.parse(readSpecJson)
        } catch (ReadSpec.Invalid e) {
            return [code: "INVALID_READ", message: e.message]
        }
        Map catalog = catalog(ec, tenantId)
        for (ReadSpec.Input input : spec.inputs) {
            Map raw = (Map) input.raw
            Set<String> named = QueryRows.namedFields(raw)
            if (input.source == "dataset") {
                Map ds = ((List<Map>) catalog.datasets).find { it.datasetId == raw.datasetId }
                if (ds == null) return [code: "UNKNOWN_SOURCE", message: "Dataset ${raw.datasetId} is not one of this tenant's kept datasets.".toString()]
                String gone = ds.fields == null ? null : named.find { !((List) ds.fields).contains(it) }
                if (gone) return [code: "NOT_KEPT", field: gone,
                        message: "${gone} is not kept by any audit for dataset ${raw.datasetId}, so a query cannot read it.".toString()]
            } else if (input.source == "runResult") {
                if (!((List<Map>) catalog.savedRuns).any { it.savedRunId == raw.savedRunId })
                    return [code: "UNKNOWN_SOURCE", message: "Saved run ${raw.savedRunId} is not one of this tenant's.".toString()]
            } else if (input.source == "auditFindings") {
                if (!((List<Map>) catalog.audits).any { it.auditId == raw.auditId })
                    return [code: "UNKNOWN_SOURCE", message: "Audit ${raw.auditId} is not one of this tenant's.".toString()]
                String unknown = named.find { !AuditFindingQuerySource.FIELDS.contains(it) }
                if (unknown) return [code: "NOT_KEPT", field: unknown, message: "${unknown} is not a finding field.".toString()]
            } else {
                return [code: "UNKNOWN_SOURCE", message: "A query reads datasets, saved run results or audit findings, not ${input.source}.".toString()]
            }
        }
        return null
    }

    /** A proposed query, checked against the catalog then run on the tenant's data; nothing is saved. */
    static Map propose(def ec, String tenantId, Map p) {
        String readSpecJson = p.readSpecJson instanceof Map ? JsonOutput.toJson(p.readSpecJson) : (p.readSpecJson as String)
        Map proposal = [queryName: p.queryName, description: p.description, readSpecJson: readSpecJson, scriptBody: p.scriptBody,
                        readText: TenantQuerySupport.readText(readSpecJson)]
        Map refused = problem(ec, tenantId, readSpecJson)
        if (refused) return [status: REFUSED, code: refused.code, field: refused.field, message: refused.message, proposal: proposal]
        Map preview = TenantQuerySupport.runQuery(ec, tenantId, [readSpecJson: readSpecJson, scriptBody: p.scriptBody])
        if (preview == null) return null
        return [status: PROPOSED, proposal: proposal, preview: preview]
    }
}
