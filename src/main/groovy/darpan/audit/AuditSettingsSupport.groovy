package darpan.audit

import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.RunHealth
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
 * Settings: the audit's name, one daily schedule for every check it runs, and each statement's text and on/off. The
 * schedule is the audit's own (DAR-BE-199, Audit.scheduleTime/scheduleTimeZone/scheduleActive; Aditi 2026-11-04 "audits
 * should run everything on the audit schedule"). A check runs through an automation (DAR-BE-092,
 * AuditAutomationSupport.checksFor), which carries a copy of that schedule for the scheduler and is never set, paused or
 * resumed on its own (ownScheduleRefusal). Play runs every check over the days a person picks, through the same
 * automations, submitted to the background (run#AutomationNow is synchronous and a dozen checks would outlast the
 * gateway), where RunAdmission starts them as the instance and the tenant's share allow (DAR-BE-143); a check past that
 * waits, queued.
 *
 * A check whose run has no automation gets one the first time either is asked for: one per run (a run several checks
 * share serves them all), its sources copied from the run's compare scope (each naming the config it reads), on the
 * audit's schedule. It is written directly rather than through save#Automation, which requires two sources and so cannot
 * make one for a check that reads one system (an EVALUATE scope, e.g. RAILS R1).
 *
 * Every read and write is pinned to the tenant with an explicit companyUserGroupId condition.
 */
class AuditSettingsSupport {

    static final String DEFAULT_TIME = "06:00"
    private static final String WHY = "audit settings, active tenant: explicit companyUserGroupId condition applied"

    /** The audit by its code in this tenant, or null. */
    /** The tenant's audit by its code, or by its id when no audit has that code (an agent holds the id it was given). */
    static Map auditOf(def ec, String tenantId, String auditCode) {
        String key = normalize(auditCode)
        if (!tenantId || !key) return null
        return (rows(ec, "darpan.audit.Audit", tenantId).condition("auditCode", key).list().find() ?:
                rows(ec, "darpan.audit.Audit", tenantId).condition("auditId", key).list().find())?.getMap()
    }

    static Map settings(def ec, String tenantId, Map audit) {
        Map<String, String> periods = AuditReportAssembler.enumCodes(ec)
        List<Map> statements = statementsOf(ec, tenantId, audit)
        Map<String, List<Map>> checksByStatement = checksByStatement(ec, tenantId, statements)
        List<Map> checks = checksByStatement.values().flatten().unique { it.checkId } as List<Map>
        List<Map> automations = automationsOf(ec, tenantId, checks)
        Map<String, String> runNames = runNames(ec, tenantId, checks*.savedRunId.findAll { it }.unique() as List<String>)
        Map schedule = scheduleOf(ec, audit)
        ZoneId zone = ZoneId.of(tenantZone(ec))
        return [audit     : [id: audit.auditCode, name: audit.auditName, period: periods[audit.periodEnumId as String]],
                playableFrom: playableFrom(ec, tenantId, audit, LocalDate.now(zone), zone),
                schedule  : [active          : schedule.active,
                             time            : schedule.time,
                             timeZone        : schedule.timeZone,
                             // An automation off the audit's schedule (made before DAR-BE-199): saving puts it back on.
                             mixed           : automations.any { offSchedule(it, schedule) },
                             automations     : automations.size(),
                             checksWithoutRun: checks.count { !it.automationId } as int],
                statements: statements.collect { Map st ->
                    [id    : st.statementId, code: st.statementCode, text: st.statementText, active: (st.isActive ?: "Y") != "N",
                     checks: checksByStatement[st.statementId as String].collect { Map c ->
                         [id: c.checkCode, label: c.label, savedRunId: c.savedRunId, runName: runNames[c.savedRunId as String]]
                     }]
                },
                // DAR-BE-207: the update emailed after each run.
                runEmail  : AuditRunUpdateSupport.runEmailSettings(ec, tenantId, audit.auditId as String)]
    }

    /**
     * Applies what changed. name: blank is ignored. statements: [{id, text, active}], each of this audit. schedule:
     * {active, time HH:mm, timeZone}, written to every automation the audit's checks run through (made first if missing).
     * runEmail (DAR-BE-207): {active, alsoSendTo, chatSpaceId}, any left out unchanged; null changes nothing.
     * Errors go to ec.message; nothing is written when any input is wrong.
     */
    static void save(def ec, String tenantId, Map audit, String name, List<Map> statementChanges, Map schedule, Map runEmail = null) {
        List<Map> statements = statementsOf(ec, tenantId, audit)
        Set<String> ours = statements*.statementId as Set<String>
        (statementChanges ?: []).each { Map change ->
            if (!ours.contains(change.id as String)) ec.message.addError("Statement ${change.id} is not part of audit ${audit.auditCode}.")
            else if (change.containsKey("text") && !normalize(change.text)) ec.message.addError("Statement ${change.id} needs its text.")
        }
        Map current = scheduleOf(ec, audit)
        String time = schedule ? normalize(schedule.time) ?: current.time : null
        String zone = schedule ? normalize(schedule.timeZone) ?: current.timeZone : null
        if (time && !(time ==~ /([01]\d|2[0-3]):[0-5]\d/)) ec.message.addError("The time must be HH:mm, got ${time}.")
        if (zone) {
            try { ZoneId.of(zone) } catch (Exception ignored) { ec.message.addError("${zone} is not a time zone.") }
        }
        if (runEmail != null) {
            // Who gets every run's findings and CSVs is a tenant admin's call (security review, 2026-11-10); the rest of the
            // settings keep the edit access the service checks.
            if (!TenantAccessSupport.isTenantAdmin(ec, tenantId)) {
                ec.message.addError("Only a tenant admin can change who gets audit ${audit.auditCode}'s run email.".toString())
            } else {
                AuditRunUpdateSupport.runEmailErrors(ec, tenantId, runEmail).each { ec.message.addError(it) }
            }
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
            Map stamp = [scheduleTime: time, scheduleTimeZone: zone, scheduleActive: schedule.active in [true, "true", "Y"] ? "Y" : "N"]
            def row = rows(ec, "darpan.audit.Audit", tenantId).condition("auditId", audit.auditId).list().find()
            row.setAll(stamp); row.update()
            Map stamped = audit + stamp
            ensureAutomations(ec, tenantId, stamped)   // a new check's automation is made on the schedule just saved
            applySchedule(ec, tenantId, stamped, now)
        }
        if (runEmail != null) AuditRunUpdateSupport.saveRunEmail(ec, tenantId, audit.auditId as String, runEmail)
    }

    /** DAR-BE-199. The audit's own schedule: {time HH:mm, timeZone, active}, defaults for an audit never scheduled. */
    static Map scheduleOf(def ec, Map audit) {
        return [time    : normalize(audit.scheduleTime) ?: DEFAULT_TIME,
                timeZone: normalize(audit.scheduleTimeZone) ?: tenantZone(ec),
                active  : audit.scheduleActive == "Y"]
    }

    /** True when the automation does not run at the audit's time, in its zone, on or off as the audit is. */
    static boolean offSchedule(Map automation, Map schedule) {
        return timeOf(automation.scheduleExpr as String) != schedule.time || automation.windowTimeZone != schedule.timeZone ||
                (automation.isActive == "Y") != schedule.active
    }

    /** Writes the audit's schedule onto every automation its checks run through. */
    static void applySchedule(def ec, String tenantId, Map audit, Timestamp now) {
        Map schedule = scheduleOf(ec, audit)
        List<Map> checks = checksByStatement(ec, tenantId, statementsOf(ec, tenantId, audit, true)).values().flatten() as List<Map>
        automationRows(ec, tenantId, checks*.automationId.findAll { it }.unique() as List<String>).each { automation ->
            setSchedule(automation, schedule, now)
            automation.set("lastUpdatedDate", now)
            automation.update()
        }
    }

    private static void setSchedule(def automation, Map schedule, Timestamp now) {
        automation.set("scheduleExpr", cronOf(schedule.time as String))
        automation.set("windowTimeZone", schedule.timeZone)
        automation.set("isActive", schedule.active ? "Y" : "N")
        automation.set("nextScheduledFireTime", AutomationExecutionSupport.resolveNextScheduledFireTime(automation, now, now))
    }

    /**
     * DAR-BE-199. The audit whose checks run through this automation ({auditCode, auditName}), or null. A check that is no
     * longer on any of an audit's statements does not count: its automation is free again.
     */
    static Map auditOfAutomation(def ec, String tenantId, String automationId) {
        if (!tenantId || !normalize(automationId)) return null
        List checkIds = rows(ec, "darpan.audit.AuditCheck", tenantId).condition("automationId", normalize(automationId)).list()*.checkId
        if (!checkIds) return null
        List statementIds = rows(ec, "darpan.audit.AuditStatementCheck", tenantId)
                .condition("checkId", EntityCondition.IN, checkIds).list()*.statementId.unique()
        List auditIds = statementIds ? rows(ec, "darpan.audit.AuditStatement", tenantId)
                .condition("statementId", EntityCondition.IN, statementIds).list()*.auditId.unique() : []
        def audit = auditIds ? rows(ec, "darpan.audit.Audit", tenantId).condition("auditId", EntityCondition.IN, auditIds)
                .orderBy("auditCode").list().find() : null
        return audit ? [auditCode: audit.auditCode, auditName: audit.auditName] : null
    }

    /** Why this automation cannot be scheduled, paused or resumed by itself, or null when it can. */
    static String ownScheduleRefusal(def ec, def automation) {
        Map audit = auditOfAutomation(ec, automation?.companyUserGroupId as String, automation?.automationId as String)
        return audit ? ("Automation ${automation.automationId} runs audit ${audit.auditName}'s checks, so it runs on the " +
                "audit's schedule: change it in the audit's settings.").toString() : null
    }

    /**
     * The earliest day play can record this morning: the first day of the period just ended (DAR-BE-178: on Nov 2 a
     * monthly audit plays October), or (DAR-BE-145) the tenant's go-live day when that is later. The run page offers
     * nothing before it.
     */
    static String playableFrom(def ec, String tenantId, Map audit, LocalDate today, ZoneId zone) {
        LocalDate from = AuditReportAssembler.recordableFrom(today, AuditReportAssembler.enumCodes(ec)[audit.periodEnumId as String])
        Timestamp goLive = AuditAutomationSupport.goLive(ec, tenantId)
        LocalDate goLiveDay = goLive != null ? AuditAutomationSupport.goLiveDay(goLive, zone) : null
        return (from != null && goLiveDay != null && goLiveDay > from ? goLiveDay : from)?.toString()
    }

    /**
     * Play: every check of the audit over [from, thru], days inclusive in the tenant's zone. Refused before anything runs
     * when the days cannot be recorded (in the future, backwards, or in a period that ended before the one just ended).
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
        // DAR-BE-172: the automations a first play makes commit in their OWN transaction before a run is handed to
        // RunAdmission, whose pool starts it on another thread at once. Made in the request's transaction, that thread
        // could not see its automation yet ("Automation ... not found", in the log only) and the play did nothing. The
        // rows are read in that transaction too: on MySQL the request's own snapshot would still hide them.
        List<Map> automations = (List<Map>) AutomationRuntimeSupport.runInNewTransaction(ec,
                "Could not make audit ${audit.auditCode}'s automations".toString()) { ->
            ensureAutomations(ec, tenantId, audit)
            List<Map> checks = checksByStatement(ec, tenantId, statementsOf(ec, tenantId, audit, true)).values().flatten() as List<Map>
            return automationRows(ec, tenantId, checks*.automationId.findAll { it }.unique() as List<String>)
                    .collect { [automationId: it.automationId, savedRunId: it.savedRunId] }
        }
        Timestamp now = ec.user.nowTimestamp
        return automations.collect { Map automation ->
            Map submitted = AutomationExecutionSupport.submitExecuteAutomationService(ec, [automationId: automation.automationId,
                    scheduledFireTime: now, auditWindowFromDate: Date.valueOf(from), auditWindowThruDate: Date.valueOf(thru),
                    auditFullRead: fullRead], tenantId)
            [automationId: automation.automationId, savedRunId: automation.savedRunId, submitted: submitted.submitted == true,
             queued: submitted.queued == true, error: submitted.error]
        }
    }

    /**
     * DAR-UI-085. What a play (or the morning run) is doing right now, for the audit page's Run health: one entry per
     * automation of the audit with an execution RUNNING or PENDING, naming the checks it feeds, its stage and progress,
     * and its health (RunHealth). The ledger row a check writes lands only when its window ends, so without this the
     * report reads every check as "not run" until then.
     *
     * Answers {checks: configured checks, activeChecks, active: [{run, checks: [codes], state: running | queued,
     * attempt, stage, progressPercent, elapsedMs, usualDurationMs, health, healthReason, retryAt}]}.
     */
    static Map progress(def ec, String tenantId, Map audit, Timestamp now) {
        List<Map> checks = checksByStatement(ec, tenantId, statementsOf(ec, tenantId, audit, true)).values().flatten()
                .unique { it.checkId } as List<Map>
        Map<String, List<Map>> checksByAutomation = checks.findAll { it.automationId }.groupBy { it.automationId as String }
        List<Map> active = []
        if (checksByAutomation) {
            Map<String, Map> automations = automationsOf(ec, tenantId, checks).collectEntries { [(it.automationId): it] }
            Map<String, String> names = runNames(ec, tenantId, automations.values()*.savedRunId.findAll { it }.unique() as List<String>)
            rows(ec, "darpan.reconciliation.ReconciliationAutomationExecution", tenantId)
                    .condition("automationId", EntityCondition.IN, checksByAutomation.keySet() as List)
                    .condition("statusEnumId", EntityCondition.IN, [AutomationExecutionSupport.STATUS_RUNNING, AutomationExecutionSupport.STATUS_PENDING])
                    .list()*.getMap().groupBy { it.automationId as String }.each { String automationId, List<Map> executions ->
                Map execution = executions.find { it.statusEnumId == AutomationExecutionSupport.STATUS_RUNNING } ?:
                        executions.max { (it.createdDate as Timestamp)?.time ?: 0L }
                boolean running = execution.statusEnumId == AutomationExecutionSupport.STATUS_RUNNING
                Map automation = automations[automationId] ?: [:]
                Map run = running && execution.reconciliationRunResultId ? rows(ec, "darpan.reconciliation.ReconciliationRunResult", tenantId)
                        .condition("reconciliationRunResultId", execution.reconciliationRunResultId).list().find()?.getMap() : null
                Map entry = [run         : names[automation.savedRunId as String] ?: automation.automationName ?: automationId,
                             checks      : checksByAutomation[automationId]*.checkCode.sort(),
                             state       : running ? "running" : "queued",
                             attempt     : ((execution.retryCount ?: 0) as Integer) + 1,
                             retryAt     : running ? null : execution.nextRetryDate,
                             stage       : run?.currentStage,
                             progressPercent: run?.progressPercent]
                if (run) {
                    Timestamp started = (run.startedDate ?: execution.startedDate) as Timestamp
                    Long usual = RunHealth.usualDurationMs(rows(ec, "darpan.reconciliation.ReconciliationRunResult", tenantId),
                            tenantId, run.savedRunId as String)
                    Map health = RunHealth.of(run + [startedDate: started, usualDurationMs: usual], now)
                    entry += [elapsedMs: started ? now.time - started.time : null, usualDurationMs: usual,
                              health: health.health, healthReason: health.reason]
                }
                active << entry
            }
        }
        // Running before queued, then by run name.
        return [checks      : checks.size(),
                activeChecks: (active.sum { it.checks.size() } ?: 0) as int,
                active      : active.sort { a, b -> (b.state <=> a.state) ?: (a.run <=> b.run) }]
    }

    /**
     * DAR-UI-086. "Retry from last read" on one check of one morning: the execution that morning's run of the check
     * recorded (AuditCheckRun.automationExecutionId) is retried from its extracts (DAR-BE-142), so nothing is read again.
     * Refused in words when the check is not this audit's, ran already, has no run that morning, or cannot be replayed.
     * Answers what retryFromExtracts submitted.
     */
    static Map retryCheck(def ec, String tenantId, Map audit, String checkCode, Date reportDate, String period) {
        Map check = checksByStatement(ec, tenantId, statementsOf(ec, tenantId, audit, true)).values().flatten()
                .find { it.checkCode == normalize(checkCode) } as Map
        if (!check) return [submitted: false, error: "Check ${checkCode} is not part of audit ${audit.auditCode}.".toString()]
        def run = rows(ec, "darpan.audit.AuditCheckRun", tenantId).condition("checkId", check.checkId)
                .condition("reportDate", reportDate).condition("period", period).list().find()
        if (run == null || !normalize(run.get("automationExecutionId"))) {
            return [submitted: false, error: "Check ${checkCode} has no run on ${reportDate} to retry from; play the audit instead.".toString()]
        }
        if (run.get("statusEnumId") == "AUDCS_RAN") return [submitted: false, error: "Check ${checkCode} already ran on ${reportDate}.".toString()]
        String executionId = normalize(run.get("automationExecutionId"))
        if (rows(ec, "darpan.reconciliation.ReconciliationAutomationExecution", tenantId).condition("automationExecutionId", executionId).list().isEmpty()) {
            return [submitted: false, error: "Check ${checkCode}'s run ${executionId} was not found.".toString()]
        }
        return AutomationExecutionSupport.retryFromExtracts(ec, [automationExecutionId: executionId])
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
                scheduledFireTime: ec.user.nowTimestamp, auditVerifyKeys: [finding.recordKey as String]], tenantId)
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
                relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_PREVIOUS_DAY,
                isArchived      : "N", createdDate: now, lastUpdatedDate: now])
        setSchedule(automation, scheduleOf(ec, audit), now)   // DAR-BE-199: a new check runs when the audit runs
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

    /** Every automation the audit's checks run through, on any of its statements (the settings page's count). */
    static List<Map> automationsOfAudit(def ec, String tenantId, Map audit) {
        List<Map> checks = checksByStatement(ec, tenantId, statementsOf(ec, tenantId, audit)).values().flatten()
                .unique { it.checkId } as List<Map>
        return automationsOf(ec, tenantId, checks)
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
