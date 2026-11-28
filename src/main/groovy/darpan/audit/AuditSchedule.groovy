package darpan.audit

import groovy.transform.PackageScope
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
 * DAR-OPS-029 S12 (split from AuditSettingsSupport, no behaviour change). The audit's own schedule (DAR-BE-199, Audit.scheduleTime/scheduleTimeZone/scheduleActive) and pushing it onto
 * every automation the audit's checks run through, which are never scheduled, paused or resumed on their own.
 * Every read and write is pinned to the tenant with an explicit companyUserGroupId condition.
 */
class AuditSchedule {

    static final String DEFAULT_TIME = "06:00"

    /** DAR-BE-199. The audit's own schedule: {time HH:mm, timeZone, active}, defaults for an audit never scheduled. */
    static Map scheduleOf(def ec, Map audit) {
        return [time    : normalize(audit.scheduleTime) ?: DEFAULT_TIME,
                timeZone: normalize(audit.scheduleTimeZone) ?: AuditSettingsSupport.tenantZone(ec),
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
        List<Map> checks = AuditSettingsSupport.checksByStatement(ec, tenantId, AuditSettingsSupport.statementsOf(ec, tenantId, audit, true)).values().flatten() as List<Map>
        AuditSettingsSupport.automationRows(ec, tenantId, checks*.automationId.findAll { it }.unique() as List<String>).each { automation ->
            setSchedule(automation, schedule, now)
            automation.set("lastUpdatedDate", now)
            automation.update()
        }
    }

    @PackageScope static void setSchedule(def automation, Map schedule, Timestamp now) {
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
        List checkIds = AuditSettingsSupport.rows(ec, "darpan.audit.AuditCheck", tenantId).condition("automationId", normalize(automationId)).list()*.checkId
        if (!checkIds) return null
        List statementIds = AuditSettingsSupport.rows(ec, "darpan.audit.AuditStatementCheck", tenantId)
                .condition("checkId", EntityCondition.IN, checkIds).list()*.statementId.unique()
        List auditIds = statementIds ? AuditSettingsSupport.rows(ec, "darpan.audit.AuditStatement", tenantId)
                .condition("statementId", EntityCondition.IN, statementIds).list()*.auditId.unique() : []
        def audit = auditIds ? AuditSettingsSupport.rows(ec, "darpan.audit.Audit", tenantId).condition("auditId", EntityCondition.IN, auditIds)
                .orderBy("auditCode").list().find() : null
        return audit ? [auditCode: audit.auditCode, auditName: audit.auditName] : null
    }

    /** Why this automation cannot be scheduled, paused or resumed by itself, or null when it can. */
    static String ownScheduleRefusal(def ec, def automation) {
        Map audit = auditOfAutomation(ec, automation?.companyUserGroupId as String, automation?.automationId as String)
        return audit ? ("Automation ${automation.automationId} runs audit ${audit.auditName}'s checks, so it runs on the " +
                "audit's schedule: change it in the audit's settings.").toString() : null
    }

    /** "0 30 6 * * ?" for 06:30: the Quartz form the automation scheduler reads. */
    private static String cronOf(String time) {
        List<String> hm = (time ?: DEFAULT_TIME).split(":") as List<String>
        return "0 ${hm[1] as int} ${hm[0] as int} * * ?".toString()
    }

    /** "06:30" from "0 30 6 * * ?", or null for any other form. */
    static String timeOf(String cron) {
        def m = (cron ?: "") =~ /^0 (\d{1,2}) (\d{1,2}) \* \* \?$/
        return m.matches() ? String.format("%02d:%02d", m.group(2) as int, m.group(1) as int) : null
    }
}
