package darpan.audit

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.AutomationRuntimeSupport
import darpan.reconciliation.automation.SourceExtractOptionsSupport
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import org.moqui.entity.EntityCondition

import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDate
import java.time.ZoneId

import static darpan.common.ValueSupport.normalize

/**
 * DAR-UI-063. An audit run's settings and its play button, from the audit's page.
 *
 * Settings: the audit's name, one daily schedule for every check it runs, and each statement's text and on/off. A check
 * runs through an automation (DAR-BE-092, AuditAutomationSupport.checksFor), so the schedule IS the schedule of those
 * automations, written to each one. Play runs every check over the days a person picks, through the same automations,
 * submitted to the async pool (run#AutomationNow is synchronous and a dozen checks would outlast the gateway).
 *
 * A check whose run has no automation gets one the first time either is asked for: one per run (a run several checks
 * share serves them all), its sources copied from the run's compare scope (each naming the config it reads), paused at
 * 06:00 in the tenant's zone. It is
 * written directly rather than through save#Automation, which requires two sources and so cannot make one for a check
 * that reads one system (an EVALUATE scope, e.g. RAILS R1).
 *
 * Every read and write is pinned to the tenant with an explicit companyUserGroupId condition.
 */
class AuditSettingsSupport {

    static final String DEFAULT_TIME = "06:00"
    private static final String WHY = "audit settings, active tenant: explicit companyUserGroupId condition applied"

    /** The audit by its code in this tenant, or null. */
    static Map auditOf(def ec, String tenantId, String auditCode) {
        if (!tenantId || !normalize(auditCode)) return null
        return rows(ec, "darpan.audit.Audit", tenantId).condition("auditCode", normalize(auditCode)).list().find()?.getMap()
    }

    static Map settings(def ec, String tenantId, Map audit) {
        Map<String, String> periods = AuditReportAssembler.enumCodes(ec)
        List<Map> statements = statementsOf(ec, tenantId, audit)
        Map<String, List<Map>> checksByStatement = checksByStatement(ec, tenantId, statements)
        List<Map> checks = checksByStatement.values().flatten().unique { it.checkId } as List<Map>
        List<Map> automations = automationsOf(ec, tenantId, checks)
        Map<String, String> runNames = runNames(ec, tenantId, checks*.savedRunId.findAll { it }.unique() as List<String>)
        List<String> times = automations.collect { timeOf(it.scheduleExpr as String) }.unique()
        List<String> zones = automations.collect { it.windowTimeZone as String }.unique()
        LocalDate today = LocalDate.now(ZoneId.of(tenantZone(ec)))
        // The earliest day play can record this morning: the current period's first day. The run page offers nothing before it.
        LocalDate playableFrom = (LocalDate) AuditReportAssembler.auditRun(ec, audit, today)?.from
        return [audit     : [id: audit.auditCode, name: audit.auditName, period: periods[audit.periodEnumId as String]],
                playableFrom: playableFrom?.toString(),
                schedule  : [active          : automations.any { it.isActive == "Y" },
                             time            : times.size() == 1 && times[0] ? times[0] : DEFAULT_TIME,
                             timeZone        : zones.size() == 1 && zones[0] ? zones[0] : tenantZone(ec),
                             mixed           : times.size() > 1 || zones.size() > 1 || automations.collect { it.isActive }.unique().size() > 1,
                             automations     : automations.size(),
                             checksWithoutRun: checks.count { !it.automationId } as int],
                statements: statements.collect { Map st ->
                    [id    : st.statementId, code: st.statementCode, text: st.statementText, active: (st.isActive ?: "Y") != "N",
                     checks: checksByStatement[st.statementId as String].collect { Map c ->
                         [id: c.checkCode, label: c.label, savedRunId: c.savedRunId, runName: runNames[c.savedRunId as String]]
                     }]
                }]
    }

    /**
     * Applies what changed. name: blank is ignored. statements: [{id, text, active}], each of this audit. schedule:
     * {active, time HH:mm, timeZone}, written to every automation the audit's checks run through (made first if missing).
     * Errors go to ec.message; nothing is written when any input is wrong.
     */
    static void save(def ec, String tenantId, Map audit, String name, List<Map> statementChanges, Map schedule) {
        List<Map> statements = statementsOf(ec, tenantId, audit)
        Set<String> ours = statements*.statementId as Set<String>
        (statementChanges ?: []).each { Map change ->
            if (!ours.contains(change.id as String)) ec.message.addError("Statement ${change.id} is not part of audit ${audit.auditCode}.")
            else if (change.containsKey("text") && !normalize(change.text)) ec.message.addError("Statement ${change.id} needs its text.")
        }
        String time = schedule ? normalize(schedule.time) ?: DEFAULT_TIME : null
        String zone = schedule ? normalize(schedule.timeZone) ?: tenantZone(ec) : null
        if (time && !(time ==~ /([01]\d|2[0-3]):[0-5]\d/)) ec.message.addError("The time must be HH:mm, got ${time}.")
        if (zone) {
            try { ZoneId.of(zone) } catch (Exception ignored) { ec.message.addError("${zone} is not a time zone.") }
        }
        if (ec.message.hasError()) return

        Timestamp now = ec.user.nowTimestamp
        if (normalize(name) && normalize(name) != audit.auditName) {
            def row = rows(ec, "darpan.audit.Audit", tenantId).condition("auditId", audit.auditId).list().find()
            row.set("auditName", normalize(name)); row.update()
        }
        (statementChanges ?: []).each { Map change ->
            def row = rows(ec, "darpan.audit.AuditStatement", tenantId).condition("statementId", change.id).list().find()
            if (change.containsKey("text")) row.set("statementText", normalize(change.text))
            if (change.containsKey("active")) row.set("isActive", change.active in [true, "true", "Y"] ? "Y" : "N")
            row.update()
        }
        if (schedule) {
            ensureAutomations(ec, tenantId, audit)
            String cron = cronOf(time)
            List<Map> checks = checksByStatement(ec, tenantId, statementsOf(ec, tenantId, audit, true)).values().flatten() as List<Map>
            automationRows(ec, tenantId, checks*.automationId.findAll { it }.unique() as List<String>).each { automation ->
                automation.set("scheduleExpr", cron)
                automation.set("windowTimeZone", zone)
                automation.set("isActive", schedule.active in [true, "true", "Y"] ? "Y" : "N")
                automation.set("nextScheduledFireTime", AutomationExecutionSupport.resolveNextScheduledFireTime(automation, now, now))
                automation.set("lastUpdatedDate", now)
                automation.update()
            }
        }
    }

    /**
     * Play: every check of the audit over [from, thru], days inclusive in the tenant's zone. Refused before anything runs
     * when the days cannot be recorded (in the future, backwards, or in a period that has ended).
     * Answers the automations submitted.
     */
    static List<Map> run(def ec, String tenantId, Map audit, LocalDate from, LocalDate thru, boolean fullRead) {
        ZoneId zone = ZoneId.of(tenantZone(ec))
        try {
            AuditAutomationSupport.pickedWindows(ec, audit, zone, LocalDate.now(zone), from, thru)
        } catch (IllegalArgumentException | IllegalStateException e) {
            ec.message.addError(e.message)
            return []
        }
        ensureAutomations(ec, tenantId, audit)
        List<Map> checks = checksByStatement(ec, tenantId, statementsOf(ec, tenantId, audit, true)).values().flatten() as List<Map>
        Timestamp now = ec.user.nowTimestamp
        return automationRows(ec, tenantId, checks*.automationId.findAll { it }.unique() as List<String>).collect { automation ->
            Map submitted = AutomationExecutionSupport.submitExecuteAutomationService(ec, [automationId: automation.automationId,
                    scheduledFireTime: now, auditWindowFromDate: Date.valueOf(from), auditWindowThruDate: Date.valueOf(thru),
                    auditFullRead: fullRead])
            [automationId: automation.automationId, savedRunId: automation.savedRunId, submitted: submitted.submitted == true,
             error: submitted.error]
        }
    }

    /**
     * DAR-BE-123. Re-checks one finding's record now through its check's automation, the record read by key on both sides
     * (auditVerifyKeys). Answers whether it was submitted; refuses in words a finding whose check has no automation yet.
     */
    static boolean verifyFinding(def ec, String tenantId, String findingId) {
        def finding = rows(ec, "darpan.audit.AuditFinding", tenantId).condition("findingId", findingId).list().find()
        def check = rows(ec, "darpan.audit.AuditCheck", tenantId).condition("checkId", finding.checkId).list().find()
        if (!check?.automationId) {
            ec.message.addError("Finding ${findingId} cannot be re-checked: its check has no automation yet (play the audit once).")
            return false
        }
        Map submitted = AutomationExecutionSupport.submitExecuteAutomationService(ec, [automationId: check.automationId,
                scheduledFireTime: ec.user.nowTimestamp, auditVerifyKeys: [finding.recordKey as String]])
        if (submitted.error) ec.message.addError("Finding ${findingId} could not be re-checked: ${submitted.error}")
        return submitted.submitted == true
    }

    /**
     * Every check of the audit runs through an automation: a check without one takes the automation another check of the
     * same run in this audit already has, or a new paused one for that run. Answers the ids made.
     */
    static List<String> ensureAutomations(def ec, String tenantId, Map audit) {
        List<Map> checks = checksByStatement(ec, tenantId, statementsOf(ec, tenantId, audit, true)).values().flatten()
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
                def row = rows(ec, "darpan.audit.AuditCheck", tenantId).condition("checkId", check.checkId).list().find()
                row.set("automationId", automationId); row.update()
            }
        }
        return made
    }

    private static String createAutomation(def ec, String tenantId, Map audit, String ruleSetId) {
        def ruleSet = rows(ec, "darpan.rule.RuleSet", tenantId).condition("ruleSetId", ruleSetId).list().find()
        def scope = rows(ec, "darpan.rule.RuleSetCompareScope", tenantId).condition("ruleSetId", ruleSetId).list().find()
        List sources = scope ? rows(ec, "darpan.rule.RuleSetCompareSource", tenantId)
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
                scheduleExpr    : cronOf(DEFAULT_TIME), windowTimeZone: tenantZone(ec),
                relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_PREVIOUS_DAY,
                isActive        : "N", isArchived: "N", createdDate: now, lastUpdatedDate: now])
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
                    safeMetadataJson: metadata ? AutomationRuntimeSupport.safeMetadataJson(metadata) : null]).create()
            // DAR-UI-063 (found in DAR-UI-064's walk): the run's source filters too. An automation reads its OWN filter rows
            // (loadAutomationSourceFilters), so without this copy every run that filters its source (R1 "completed units
            // only", R15 "EXC- orders, not WH") ran unfiltered and raised findings for records it never meant to read.
            darpan.facade.reconciliation.AutomationFacadeSupport.seedFromRuleSet(ec, scope.compareScopeId as String, source.fileSide as String)
                    .eachWithIndex { Map filter, int i ->
                        ec.entity.makeValue("darpan.reconciliation.ReconciliationAutomationSourceFilter").setAll([
                                automationId: automationId, fileSide: source.fileSide, sequenceNum: i + 1,
                                fieldExpression: filter.fieldExpression, operator: filter.operator, filterValues: filter.filterValues,
                                companyUserGroupId: tenantId, createdByUserId: userId, createdDate: now, lastUpdatedDate: now]).create()
                    }
        }
        return automationId
    }

    /** "0 30 6 * * ?" for 06:30: the Quartz form the automation scheduler reads. */
    static String cronOf(String time) {
        List<String> hm = (time ?: DEFAULT_TIME).split(":") as List<String>
        return "0 ${hm[1] as int} ${hm[0] as int} * * ?".toString()
    }

    /** "06:30" from "0 30 6 * * ?", or null for any other form. */
    static String timeOf(String cron) {
        def m = (cron ?: "") =~ /^0 (\d{1,2}) (\d{1,2}) \* \* \?$/
        return m.matches() ? String.format("%02d:%02d", m.group(2) as int, m.group(1) as int) : null
    }

    private static String tenantZone(def ec) {
        return TenantAccessSupport.resolveActiveTenantTimeZone(ec) ?: "UTC"
    }

    /** The audit's statements in report order; activeOnly drops those turned off (they run nothing). */
    private static List<Map> statementsOf(def ec, String tenantId, Map audit, boolean activeOnly = false) {
        List<Map> all = rows(ec, "darpan.audit.AuditStatement", tenantId).condition("auditId", audit.auditId)
                .orderBy(["sequenceNum", "statementCode"]).list()*.getMap()
        return activeOnly ? all.findAll { (it.isActive ?: "Y") != "N" } : all
    }

    private static Map<String, List<Map>> checksByStatement(def ec, String tenantId, List<Map> statements) {
        Map<String, List<Map>> out = statements.collectEntries { [(it.statementId as String): []] }
        if (!statements) return out
        List<Map> links = rows(ec, "darpan.audit.AuditStatementCheck", tenantId)
                .condition("statementId", EntityCondition.IN, statements*.statementId).orderBy(["sequenceNum", "checkId"]).list()*.getMap()
        Map<String, Map> checks = links ? rows(ec, "darpan.audit.AuditCheck", tenantId)
                .condition("checkId", EntityCondition.IN, links*.checkId.unique()).list().collectEntries { [(it.checkId): it.getMap()] } : [:]
        links.each { Map l -> if (checks[l.checkId as String]) out[l.statementId as String] << checks[l.checkId as String] }
        return out
    }

    private static List<Map> automationsOf(def ec, String tenantId, List<Map> checks) {
        return automationRows(ec, tenantId, checks*.automationId.findAll { it }.unique() as List<String>)*.getMap()
    }

    private static List automationRows(def ec, String tenantId, List<String> ids) {
        return ids ? rows(ec, "darpan.reconciliation.ReconciliationAutomation", tenantId)
                .condition("automationId", EntityCondition.IN, ids).orderBy("automationId").list() : []
    }

    private static Map<String, String> runNames(def ec, String tenantId, List<String> ids) {
        return ids ? rows(ec, "darpan.rule.RuleSet", tenantId).condition("ruleSetId", EntityCondition.IN, ids).list()
                .collectEntries { [(it.ruleSetId): it.ruleSetName] } : [:]
    }

    private static def rows(def ec, String entityName, String tenantId) {
        return darpan.facade.common.TenantScopedFinder.findGlobalUnscoped(ec, entityName, WHY)
                .condition("companyUserGroupId", tenantId).useCache(false)
    }
}
