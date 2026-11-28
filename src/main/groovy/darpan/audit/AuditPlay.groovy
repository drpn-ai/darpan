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
 * DAR-OPS-029 S12 (split from AuditSettingsSupport, no behaviour change). Play (every check, or the named ones, over the days a person picks), retry one check's morning from its extracts,
 * and re-check one finding: each through the checks' automations.
 * Every read and write is pinned to the tenant with an explicit companyUserGroupId condition.
 */
class AuditPlay {

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
     * Play: every check of the audit over [from, thru], days inclusive in the tenant's zone, or (DAR-BE-373) only the
     * checks named by code: then only their automations run, so a one-off read of one check does not read every system
     * again. An automation records every check it feeds. Refused before anything runs when the days cannot be recorded
     * (in the future, backwards, or in a period that ended before the one just ended) or a named check is not the audit's.
     * Answers the automations submitted.
     */
    static List<Map> run(def ec, String tenantId, Map audit, LocalDate from, LocalDate thru, boolean fullRead,
                         List<String> checkCodes = null) {
        ZoneId zone = ZoneId.of(AuditSettingsSupport.tenantZone(ec))
        try {
            AuditAutomationSupport.pickedWindows(ec, audit, zone, LocalDate.now(zone), from, thru)
        } catch (IllegalArgumentException | IllegalStateException e) {
            ec.message.addError(e.message)
            return []
        }
        Set<String> picked = (checkCodes ?: []).collect { normalize(it as String) }.findAll { it } as Set<String>
        if (picked) {
            Set<String> known = (AuditSettingsSupport.checksByStatement(ec, tenantId, AuditSettingsSupport.statementsOf(ec, tenantId, audit, true)).values().flatten()
                    *.checkCode.collect { it as String }) as Set<String>
            List<String> unknown = picked.findAll { !known.contains(it) }.sort()
            if (unknown) {
                ec.message.addError("Audit ${audit.auditCode} has no check ${unknown.join(', ')}.".toString())
                return []
            }
        }
        // DAR-BE-172: the automations a first play makes commit in their OWN transaction before a run is handed to
        // RunAdmission, whose pool starts it on another thread at once. Made in the request's transaction, that thread
        // could not see its automation yet ("Automation ... not found", in the log only) and the play did nothing. The
        // rows are read in that transaction too: on MySQL the request's own snapshot would still hide them.
        List<Map> automations = (List<Map>) AutomationRuntimeSupport.runInNewTransaction(ec,
                "Could not make audit ${audit.auditCode}'s automations".toString()) { ->
            AuditCheckAutomation.ensureAutomations(ec, tenantId, audit)
            List<Map> checks = AuditSettingsSupport.checksByStatement(ec, tenantId, AuditSettingsSupport.statementsOf(ec, tenantId, audit, true)).values().flatten() as List<Map>
            if (picked) checks = checks.findAll { picked.contains(it.checkCode as String) }
            return AuditSettingsSupport.automationRows(ec, tenantId, checks*.automationId.findAll { it }.unique() as List<String>)
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
     * DAR-UI-086. "Retry from last read" on one check of one morning: the execution that morning's run of the check
     * recorded (AuditCheckRun.automationExecutionId) is retried from its extracts (DAR-BE-142), so nothing is read again.
     * Refused in words when the check is not this audit's, ran already, has no run that morning, or cannot be replayed.
     * Answers what retryFromExtracts submitted.
     */
    static Map retryCheck(def ec, String tenantId, Map audit, String checkCode, Date reportDate, String period) {
        Map check = AuditSettingsSupport.checksByStatement(ec, tenantId, AuditSettingsSupport.statementsOf(ec, tenantId, audit, true)).values().flatten()
                .find { it.checkCode == normalize(checkCode) } as Map
        if (!check) return [submitted: false, error: "Check ${checkCode} is not part of audit ${audit.auditCode}.".toString()]
        def run = AuditSettingsSupport.rows(ec, "darpan.audit.AuditCheckRun", tenantId).condition("checkId", check.checkId)
                .condition("reportDate", reportDate).condition("period", period).list().find()
        if (run == null || !normalize(run.get("automationExecutionId"))) {
            return [submitted: false, error: "Check ${checkCode} has no run on ${reportDate} to retry from; play the audit instead.".toString()]
        }
        if (run.get("statusEnumId") == "AUDCS_RAN") return [submitted: false, error: "Check ${checkCode} already ran on ${reportDate}.".toString()]
        String executionId = normalize(run.get("automationExecutionId"))
        if (AuditSettingsSupport.rows(ec, "darpan.reconciliation.ReconciliationAutomationExecution", tenantId).condition("automationExecutionId", executionId).list().isEmpty()) {
            return [submitted: false, error: "Check ${checkCode}'s run ${executionId} was not found.".toString()]
        }
        return AutomationExecutionSupport.retryFromExtracts(ec, [automationExecutionId: executionId])
    }

    /**
     * DAR-BE-123. Re-checks one finding's record now through its check's automation, the record read by key on both sides
     * (auditVerifyKeys). Answers whether it was submitted; refuses in words a finding whose check has no automation yet.
     */
    static boolean verifyFinding(def ec, String tenantId, String findingId) {
        def finding = AuditSettingsSupport.rows(ec, "darpan.audit.AuditFinding", tenantId).condition("findingId", findingId).list().find()
        def check = AuditSettingsSupport.rows(ec, "darpan.audit.AuditCheck", tenantId).condition("checkId", finding.checkId).list().find()
        if (!check?.automationId) {
            ec.message.addError("Finding ${findingId} cannot be re-checked: its check has no automation yet (play the audit once).")
            return false
        }
        Map submitted = AutomationExecutionSupport.submitExecuteAutomationService(ec, [automationId: check.automationId,
                scheduledFireTime: ec.user.nowTimestamp, auditVerifyKeys: [finding.recordKey as String]], tenantId)
        if (submitted.error) ec.message.addError("Finding ${findingId} could not be re-checked: ${submitted.error}")
        return submitted.submitted == true
    }
}
