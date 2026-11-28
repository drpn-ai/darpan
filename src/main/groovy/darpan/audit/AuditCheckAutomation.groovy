package darpan.audit

import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.RunHealth
import darpan.facade.reconciliation.RunObservability
import darpan.peak.PeakReadPolicy
import darpan.peak.ReadLimits
import darpan.reconciliation.core.ReconciliationServices
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.AutomationRuntimeSupport
import darpan.reconciliation.automation.SourceExtractOptionsSupport
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import darpan.reconciliation.conclusion.RunConclusionSupport
import org.moqui.entity.EntityCondition
import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDate
import java.time.ZoneId
import static darpan.common.ValueSupport.normalize

/**
 * DAR-OPS-029 S12 (split from AuditSettingsSupport, no behaviour change). Every check runs through an automation (DAR-BE-092): a check without one takes the one another check of the same
 * run already has, or a new one made from the run's compare scope on the audit's schedule.
 * Every read and write is pinned to the tenant with an explicit companyUserGroupId condition.
 */
class AuditCheckAutomation {

    /**
     * Every check of the audit runs through an automation: a check without one takes the automation another check of the
     * same run in this audit already has, or a new paused one for that run. Answers the ids made.
     */
    static List<String> ensureAutomations(def ec, String tenantId, Map audit) {
        List<Map> checks = AuditSettingsSupport.checksByStatement(ec, tenantId, AuditSettingsSupport.statementsOf(ec, tenantId, audit, true)).values().flatten()
                .unique { it.checkId } as List<Map>
        List<String> made = []
        checks.findAll { !it.automationId && it.savedRunId }.groupBy { it.savedRunId as String }.each { String runId, List<Map> waiting ->
            String automationId = checks.find { it.savedRunId == runId && it.automationId }?.automationId as String
            if (!automationId) {
                automationId = createAutomation(ec, tenantId, audit, runId)
                if (!automationId) return
                made << automationId
            }
            waiting.each { Map check ->
                def row = AuditSettingsSupport.rows(ec, "darpan.audit.AuditCheck", tenantId).condition("checkId", check.checkId).list().find()
                row.set("automationId", automationId); row.update()
            }
        }
        return made
    }

    /**
     * A paused automation for the run, made under the AUDIT's tenant. This stays Groovy and does not call save#Automation
     * (DAR-OPS-029 S99-054): save#Automation stamps and gates on the session's active tenant (requireActiveTenantWriteAccess,
     * assignTenantOwnershipOnCreate), and the agent pipeline makes these while another tenant is active; XML entity-finds
     * over RuleSet, RuleSetCompareScope and RuleSetCompareSource would take the DARPAN_ACTIVE_COMPANY_SCOPE filter and read
     * the active tenant's rows instead. The rest is connector, extract-option and filter work in Groovy helpers.
     */
    private static String createAutomation(def ec, String tenantId, Map audit, String ruleSetId) {
        def ruleSet = AuditSettingsSupport.rows(ec, "darpan.rule.RuleSet", tenantId).condition("ruleSetId", ruleSetId).list().find()
        // ruleSetId is not the scope's key: the first by id, so a run with several scopes always gets the same one.
        def scope = AuditSettingsSupport.rows(ec, "darpan.rule.RuleSetCompareScope", tenantId).condition("ruleSetId", ruleSetId)
                .orderBy("compareScopeId").list().find()
        List sources = scope ? AuditSettingsSupport.rows(ec, "darpan.rule.RuleSetCompareSource", tenantId)
                .condition("compareScopeId", scope.compareScopeId).orderBy("fileSide").list() : []
        if (!ruleSet || !scope || !sources) {
            ec.message.addError("Run ${ruleSetId} has no compare scope with sources, so it cannot be scheduled.")
            return null
        }
        Timestamp now = ec.user.nowTimestamp
        String userId = TenantAccessSupport.currentUserId(ec)
        String automationId = ec.entity.sequencedIdPrimary("darpan.reconciliation.ReconciliationAutomation", null, null)
        def automation = ec.entity.makeValue("darpan.reconciliation.ReconciliationAutomation").setAll([
                automationId    : automationId,
                automationName  : "${audit.auditName}: ${ruleSet.ruleSetName ?: ruleSetId}".toString().take(255),
                description     : "DAR-UI-063. Runs audit ${audit.auditCode}'s checks on run ${ruleSetId}.".toString(),
                companyUserGroupId: tenantId, createdByUserId: userId,
                inputModeEnumId : AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE,
                savedRunId      : ruleSetId, savedRunType: "ruleset", ruleSetId: ruleSetId, compareScopeId: scope.compareScopeId,
                relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_PREVIOUS_DAY,
                isArchived      : "N", createdDate: now, lastUpdatedDate: now])
        AuditSchedule.setSchedule(automation, AuditSchedule.scheduleOf(ec, audit), now)   // DAR-BE-199: a new check runs when the audit runs
        automation.create()
        sources.each { source ->
            // The config this side reads (a NetSuite query, an OMS or Shopify config), under the connector's own parameter:
            // without it the extract falls back to "the tenant's single active config", which a tenant with several has none of.
            Map connector = SourceSystemConnectorSupport.resolve(ec, source.systemEnumId as String)
            String configParameter = normalize(connector?.configParameterName)
            // DAR-UI-064's per-side extract options (R8 includeReturnsWithoutShopifyRef, R16 onlyCancelledReturns), under the
            // config id: a stored option never displaces it.
            Map parameters = new LinkedHashMap(SourceExtractOptionsSupport.runtimeOptions(connector, SourceExtractOptionsSupport.storedOn(source)) ?: [:])
            if (configParameter && normalize(source.sourceConfigId)) parameters[configParameter] = normalize(source.sourceConfigId)
            Map metadata = parameters ? [parameters: parameters] : [:]
            ec.entity.makeValue("darpan.reconciliation.ReconciliationAutomationSource").setAll([
                    automationId: automationId, fileSide: source.fileSide, companyUserGroupId: tenantId, createdByUserId: userId,
                    sourceTypeEnumId: source.sourceTypeEnumId ?: AutomationExecutionSupport.AUTOMATION_SOURCE_API,
                    systemEnumId: source.systemEnumId, fileTypeEnumId: source.fileTypeEnumId, schemaFileName: source.schemaFileName,
                    recordRootExpression: source.recordRootExpression, primaryIdExpression: source.primaryIdExpression,
                    idValueNormalizer: source.idValueNormalizer, createdDate: now, lastUpdatedDate: now,
                    // A3 (S19): the extract reads safeMetadataJson, not these, but they are sync-drift fields and shared-read keys:
                    // without them an audit-made automation reports a false drift and does not share a saved one's read.
                    systemMessageRemoteId: source.systemMessageRemoteId,
                    safeMetadataJson: metadata ? AutomationRuntimeSupport.safeMetadataJson(metadata) : null]).create()
            // DAR-UI-063 (found in DAR-UI-064's walk): the run's source filters too. An automation reads its OWN filter rows
            // (loadAutomationSourceFilters), so without this copy every run that filters its source (R1 "completed units
            // only", R15 "EXC- orders, not WH") ran unfiltered and raised findings for records it never meant to read.
            // A1: the audit's own tenant, not the session's (the agent pipeline makes these under another active tenant).
            darpan.facade.reconciliation.AutomationSaveSupport.seedFromRuleSet(ec, tenantId, scope.compareScopeId as String, source.fileSide as String)
                    .eachWithIndex { Map filter, int i ->
                        ec.entity.makeValue("darpan.reconciliation.ReconciliationAutomationSourceFilter").setAll([
                                automationId: automationId, fileSide: source.fileSide, sequenceNum: i + 1,
                                fieldExpression: filter.fieldExpression, operator: filter.operator, filterValues: filter.filterValues,
                                companyUserGroupId: tenantId, createdByUserId: userId, createdDate: now, lastUpdatedDate: now]).create()
                    }
        }
        return automationId
    }
}
