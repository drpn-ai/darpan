package darpan.query

import darpan.reconciliation.notification.TenantNotificationSupport
import groovy.json.JsonOutput

/**
 * DAR-BE-334 migration TENANT_SCRIPTS_TO_QUERIES: every darpan.script.TenantScript becomes a query (jexl.script.UserScript,
 * profile QUERY, same tenant, name, body) and every TenantScriptDeployment a schedule with one chat delivery to its space,
 * switched off (it runs again only once someone allowed to schedule turns it on and so owns it). A script that read a
 * run result gets a runResult read of that saved run, but its input changed shape (rows are flat now), so it arrives
 * switched off with a note to check it. Idempotent: rows already migrated (MIG_ ids) are skipped. The old engine's
 * five-minute scan job (scan_TenantScriptDeployments_5m) is removed with its runs and parameters: its service is gone,
 * and a seed load never deletes the row.
 */
class QueryMigration {
    static final String PREFIX = "MIG_"
    static final String OLD_JOB = "scan_TenantScriptDeployments_5m"

    static Map<String, Object> migrateAll(def ec) {
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        int scripts = 0, schedules = 0, skipped = 0
        List<String> notes = []
        try {
            List deployments = ec.entity.find("darpan.script.TenantScriptDeployment").useCache(false).list()
            ec.entity.find("darpan.script.TenantScript").useCache(false).list().each { s ->
                String id = PREFIX + s.tenantScriptId
                if (ec.entity.find("jexl.script.UserScript").condition("userScriptId", id).useCache(false).one() != null) { skipped++; return }
                boolean runResult = s.inputSource == "RUN_RESULT"
                String savedRunId = runResult ? deployments.find { it.tenantScriptId == s.tenantScriptId && it.inputSavedRunId }?.inputSavedRunId : null
                String readSpec = runResult ? JsonOutput.toJson([inputs: [[name: "differences", source: "runResult", savedRunId: savedRunId,
                        fields: ["bucket", "key"]]]]) : null
                String description = runResult ? "Migrated from the old scripts: it read a run result, whose rows are now flat (input.differences). Check its read and script, then switch it on." : s.description
                ec.entity.makeValue("jexl.script.UserScript").setAll([userScriptId: id, companyUserGroupId: s.companyUserGroupId,
                        scriptName: s.scriptName, description: description, scriptProfile: "QUERY", scriptBody: s.scriptBody,
                        sampleInputJson: s.sampleInputJson, readSpecJson: readSpec, ownerUserId: s.lastUpdatedByUserId ?: s.createdByUserId,
                        isActive: runResult ? "N" : (s.isActive ?: "Y")]).create()
                scripts++
                if (runResult && !savedRunId) notes << "${s.scriptName} (${s.companyUserGroupId}) read a run result with no saved run; its read needs one.".toString()
            }
            deployments.each { d ->
                String id = PREFIX + d.tenantScriptDeploymentId
                if (ec.entity.find("jexl.script.UserScriptDeployment").condition("scriptDeploymentId", id).useCache(false).one() != null) { skipped++; return }
                String owner = d.lastUpdatedByUserId ?: d.createdByUserId
                if (!owner) { notes << "Deployment ${d.tenantScriptDeploymentId} had no user to own it; not migrated.".toString(); return }
                ec.entity.makeValue("jexl.script.UserScriptDeployment").setAll([scriptDeploymentId: id, userScriptId: PREFIX + d.tenantScriptId,
                        companyUserGroupId: d.companyUserGroupId, cronExpression: d.scheduleExpr,
                        timeZone: TenantNotificationSupport.resolveTenantZone(ec, d.companyUserGroupId as String).id,
                        ownerUserId: owner, isActive: "N"]).create()
                if (d.chatSpaceId) {
                    ec.entity.makeValue("jexl.script.UserScriptDeploymentDelivery").setAll([scriptDeploymentId: id, deliverySeqId: "01",
                            deliveryServiceName: "query.QueryHostServices.deliver#QueryChat", parametersJson: JsonOutput.toJson([chatSpaceId: d.chatSpaceId]),
                            isActive: "Y"]).create()
                }
                schedules++
            }
            if (ec.entity.find("moqui.service.job.ServiceJob").condition("jobName", OLD_JOB).useCache(false).one() != null) {
                ["moqui.service.job.ServiceJobRunLock", "moqui.service.job.ServiceJobRun", "moqui.service.job.ServiceJobParameter",
                 "moqui.service.job.ServiceJobUser", "moqui.service.job.ServiceJob"].each { String entityName ->
                    if (ec.entity.isEntityDefined(entityName)) ec.entity.find(entityName).condition("jobName", OLD_JOB).deleteAll()
                }
                notes << "Removed the old scripts' scan job ${OLD_JOB}.".toString()
            }
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
        return [queries: scripts, schedules: schedules, skipped: skipped, notes: notes] as Map<String, Object>
    }
}
