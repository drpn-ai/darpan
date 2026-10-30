package darpan.config

import darpan.admin.transfer.ConfigTransferImporter
import darpan.admin.transfer.TransferSpecs
import groovy.json.JsonOutput
import groovy.transform.CompileDynamic

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * DAR-BE-160 phase A. Restoring one of a tenant's config versions, so its audits behave exactly as they did then.
 *
 * <p>What the version holds is put back through the DAR-ADMIN-006 importer into the same tenant: records match by their
 * codes and names, so an untouched record is UNCHANGED and an edited one is updated back. What was added since is not
 * deleted, because findings, run results and automations point at it: a statement the version does not hold is switched
 * off, and a check link the version does not hold is removed (a link is config, not history). Runs added since are kept
 * and listed; nothing in the restored audits uses them. (Queries are not listed: a version holds a query only as a run
 * reaches it, so one no run uses is in no version.)</p>
 *
 * <p>The restore applies only the plan that was previewed (planHash), keeps the current config as a version first so the
 * restore can itself be undone, and runs in the caller's one transaction: all of it or none of it.</p>
 */
@CompileDynamic
class TenantConfigRestoreSupport {

    static final String SERVICE_NAME = "facade.TenantConfigFacadeServices.restore#TenantConfigVersion"
    static final String STALE_PLAN_MESSAGE = "The config changed since the preview. Preview the restore again."

    /**
     * The restore plan: the importer's lines, blocking and warnings, plus switchOff [{statementId, audit, statement}],
     * unlink [{statementId, checkId, statement, check}] and kept [{kind, label}]. Null (with an error) when the version is
     * not this tenant's.
     */
    static Map<String, Object> preview(def ec, String tenantId, String configVersionId) {
        Map version = TenantConfigVersionSupport.version(ec, tenantId, configVersionId)
        if (version == null) return null
        Map pack = (Map) version.remove("pack")
        Map<String, String> connectionMap = sameConnections(pack)
        Map imported = ConfigTransferImporter.preview(ec, tenantId, pack, connectionMap)
        Map plan = new LinkedHashMap((Map) imported.plan)
        plan.blocking = ((plan.blocking ?: []) as List<Map>).collect { Map b -> restoreWording(b) }
        Map since = addedSince(ec, tenantId, pack)
        plan.putAll(since)
        return [version         : version,
                plan            : plan,
                planHash        : restoreHash(configVersionId, imported.planHash as String, since),
                pack            : pack,
                connectionMap   : connectionMap,
                importerPlanHash: imported.planHash] as Map<String, Object>
    }

    /** Applies the previewed plan. Answers [restored, before, after, plan]; null (with an error, nothing written) when refused. */
    static Map<String, Object> restore(def ec, String tenantId, String configVersionId, String expectedPlanHash) {
        Map p = preview(ec, tenantId, configVersionId)
        if (p == null) return null
        Map plan = (Map) p.plan
        if (plan.blocking) {
            ec.message.addError("The restore has blocking items (${(plan.blocking as List<Map>)*.message.join(' ')}).")
            return null
        }
        if (TransferSpecs.text(expectedPlanHash) != p.planHash) {
            ec.message.addError(STALE_PLAN_MESSAGE)
            return null
        }
        Long seq = p.version.seq as Long
        Map before = TenantConfigVersionSupport.snapshot(ec, tenantId, "Before restoring version ${seq}".toString(), "RESTORE")
        if (before == null) return null

        Map applied = ConfigTransferImporter.apply(ec, tenantId, (Map) p.pack, (Map) p.connectionMap, p.importerPlanHash as String,
                "version ${seq}".toString(), null, SERVICE_NAME)
        if (applied == null) return null

        (plan.switchOff as List<Map>).each { Map s ->
            def row = statement(ec, tenantId, s.statementId as String)
            if (row != null && row.isActive != "N") {
                row.set("isActive", "N")
                row.update()
            }
        }
        (plan.unlink as List<Map>).each { Map l ->
            ec.entity.find("darpan.audit.AuditStatementCheck").condition("companyUserGroupId", tenantId)
                    .condition("statementId", l.statementId).condition("checkId", l.checkId).useCache(false).list()
                    .each { it.delete() }
        }

        Map after = TenantConfigVersionSupport.snapshot(ec, tenantId, "Restored version ${seq}".toString(), "RESTORE")
        if (after == null) return null
        return [restored: true, before: before.version, after: after.version, plan: plan] as Map<String, Object>
    }

    /** Each of the version's connections maps to itself; the importer blocks one that is gone or no longer the tenant's. */
    static Map<String, String> sameConnections(Map pack) {
        return ((pack?.connections ?: []) as List<Map>).collectEntries { Map c ->
            List<String> parsed = TransferSpecs.parseRef(c.ref)
            parsed ? [(c.ref as String): parsed[1]] : [:]
        } as Map<String, String>
    }

    /** What the tenant has now that the version does not: statements to switch off, check links to remove, and the rest. */
    static Map<String, List<Map>> addedSince(def ec, String tenantId, Map pack) {
        Map<String, String> auditCodeByRef = ((pack.audits ?: []) as List<Map>).collectEntries {
            [(it.ref as String): it.fields?.auditCode as String] } as Map<String, String>
        Map<String, String> statementKeyByRef = ((pack.statements ?: []) as List<Map>).collectEntries {
            [(it.ref as String): key(auditCodeByRef[it.refs?.auditId as String], it.fields?.statementCode)] } as Map<String, String>
        Set<String> versionStatements = statementKeyByRef.values() as Set<String>
        Set<String> versionLinks = new HashSet<String>()
        ((pack.checks ?: []) as List<Map>).each { Map check ->
            ((check.links ?: []) as List<Map>).each { Map link ->
                String statementKey = statementKeyByRef[link.refs?.statementId as String]
                if (statementKey) versionLinks.add(key(statementKey, check.fields?.checkCode))
            }
        }

        Map<String, String> auditCodeById = rows(ec, tenantId, "darpan.audit.Audit").collectEntries {
            [(it.auditId as String): it.auditCode as String] } as Map<String, String>
        Map<String, String> checkCodeById = rows(ec, tenantId, "darpan.audit.AuditCheck").collectEntries {
            [(it.checkId as String): it.checkCode as String] } as Map<String, String>
        List statements = rows(ec, tenantId, "darpan.audit.AuditStatement")
        Map<String, String> statementKeyById = statements.collectEntries {
            [(it.statementId as String): key(auditCodeById[it.auditId as String], it.statementCode)] } as Map<String, String>

        List<Map> switchOff = statements.findAll { it.isActive != "N" && !(statementKeyById[it.statementId as String] in versionStatements) }
                .collect { [statementId: it.statementId, audit: auditCodeById[it.auditId as String], statement: it.statementCode] as Map }
                .sort { a, b -> a.audit <=> b.audit ?: a.statement <=> b.statement }
        List<Map> unlink = rows(ec, tenantId, "darpan.audit.AuditStatementCheck").findAll { link ->
            String statementKey = statementKeyById[link.statementId as String]
            statementKey in versionStatements && !(key(statementKey, checkCodeById[link.checkId as String]) in versionLinks)
        }.collect { link ->
            [statementId: link.statementId, checkId: link.checkId,
             audit: auditCodeById[statements.find { it.statementId == link.statementId }?.auditId as String],
             statement: statements.find { it.statementId == link.statementId }?.statementCode,
             check: checkCodeById[link.checkId as String]] as Map
        }.sort { a, b -> a.statement <=> b.statement ?: a.check <=> b.check }

        // Runs only: a version holds every run a lifecycle does not own, so one missing from it was added since. Queries
        // are held only as a run reaches them, so a query no run uses is in no version and proves nothing.
        Set<String> versionRuns = ((pack.runs ?: []) as List<Map>).collect { it.fields?.ruleSetName as String } as Set<String>
        List<Map> kept = ec.entity.find("darpan.rule.RuleSet").condition("companyUserGroupId", tenantId)
                .condition("ownerReconciliationId", null).useCache(false).list()
                .findAll { !((it.ruleSetName as String) in versionRuns) }
                .collect { [kind: "RUN", label: it.ruleSetName] as Map }
        // An automation of a saved run that the version does not hold was added since; it keeps running as it is.
        Set<String> versionAutomations = ((pack.automations ?: []) as List<Map>)
                .collect { TransferSpecs.parseRef(it.ref)?.get(1) }.findAll { it } as Set<String>
        rows(ec, tenantId, "darpan.reconciliation.ReconciliationAutomation")
                .findAll { it.savedRunId && !((it.automationId as String) in versionAutomations) }
                .each { kept << ([kind: "AUTOMATION", label: it.automationName ?: it.automationId] as Map) }
        Set<String> versionLifecycles = ((pack.lifecycles ?: []) as List<Map>)
                .collect { TransferSpecs.parseRef(it.ref)?.get(1) }.findAll { it } as Set<String>
        rows(ec, tenantId, "darpan.reconciliation.Reconciliation").findAll { !((it.reconciliationId as String) in versionLifecycles) }
                .each { kept << ([kind: "LIFECYCLE", label: it.reconciliationName ?: it.reconciliationId] as Map) }
        return [switchOff: switchOff, unlink: unlink, kept: kept.sort { a, b -> a.kind <=> b.kind ?: a.label <=> b.label }]
    }

    /** SHA-256 over the version, the importer's plan hash and exactly which rows are switched off and unlinked. */
    static String restoreHash(String configVersionId, String importerPlanHash, Map since) {
        Map hashed = [version: configVersionId, importer: importerPlanHash,
                      switchOff: (since.switchOff as List<Map>)*.statementId,
                      unlink: (since.unlink as List<Map>).collect { "${it.statementId}|${it.checkId}".toString() }]
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(JsonOutput.toJson(TenantConfigVersionSupport.canonical(hashed))
                .getBytes(StandardCharsets.UTF_8))
        return digest.collect { String.format("%02x", it) }.join()
    }

    /** The importer speaks of instances and mapping; a restore's only such block is a connection deleted since. */
    private static Map restoreWording(Map b) {
        if (b.code in ["CONNECTION_NOT_FOUND", "CONNECTION_UNMAPPED"]) {
            return b + [message: "Connection ${b.ref} was deleted or unshared after this version. A restore cannot bring back " +
                    "credentials; recreate the connection first.".toString()]
        }
        return b
    }

    private static List rows(def ec, String tenantId, String entityName) {
        return ec.entity.find(entityName).condition("companyUserGroupId", tenantId).useCache(false).list()
    }

    private static def statement(def ec, String tenantId, String statementId) {
        return ec.entity.find("darpan.audit.AuditStatement").condition("companyUserGroupId", tenantId)
                .condition("statementId", statementId).useCache(false).list().find()
    }

    private static String key(Object... parts) {
        return parts.collect { it == null ? "" : it.toString() }.join("|")
    }
}
