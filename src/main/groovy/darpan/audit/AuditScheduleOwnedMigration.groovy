package darpan.audit

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-199, migration AUDIT_SCHEDULE_OWNED. An audit had no schedule of its own: the settings page derived one from the
 * automations its checks run through, and a check added later (gorjana R7/R8/R9, 2026-11-04) was made at 06:00 and resumed
 * by itself, so the audit ran at two times. Now the audit owns it (Audit.scheduleTime/scheduleTimeZone/scheduleActive).
 * This stamps each audit that has automations and no schedule yet: the time and zone most of its running automations
 * share (all of them when none runs; ties to the earliest), on when any runs. It never rewrites an automation: one off the
 * stamped schedule is named, and the audit's settings page shows it until a save puts it on. An audit with no automation
 * keeps the defaults. Idempotent: a stamped audit is skipped. Runs with authz disabled (the supervisor), so every read
 * names its tenant.
 */
class AuditScheduleOwnedMigration {

    static Map<String, Object> run(def ec, boolean dryRun) {
        List<String> messages = []
        int stamped = 0
        int off = 0
        List audits = ec.entity.find("darpan.audit.Audit").orderBy(["companyUserGroupId", "auditCode"]).useCache(false).list()
                .findAll { normalize(it.scheduleTime) == null }   // EntityList.findAll casts the result to Boolean
        audits.each { audit ->
            String tenantId = audit.companyUserGroupId as String
            List<Map> automations = AuditSettingsSupport.automationsOfAudit(ec, tenantId, audit.getMap())
            if (!automations) return
            List<Map> running = automations.findAll { it.isActive == "Y" }
            List<Map> pool = running ?: automations
            String time = mostCommon(pool.collect { AuditSettingsSupport.timeOf(it.scheduleExpr as String) }) ?: AuditSettingsSupport.DEFAULT_TIME
            String zone = mostCommon(pool.collect { normalize(it.windowTimeZone) }) ?: "UTC"
            Map schedule = [time: time, timeZone: zone, active: !running.isEmpty()]
            List<Map> offIt = automations.findAll { AuditSettingsSupport.offSchedule(it, schedule) }
            String line = "${tenantId} audit ${audit.auditCode}: ${time} ${zone}, ${running ? 'on' : 'paused'}".toString()
            if (offIt) {
                line += "; ${offIt.size()} of ${automations.size()} automation(s) off it (${offIt*.automationId.join(', ')}): " +
                        "saving the audit's settings puts them on it"
                off++
            }
            messages << line + "."
            if (!dryRun) {
                audit.set("scheduleTime", time)
                audit.set("scheduleTimeZone", zone)
                audit.set("scheduleActive", running ? "Y" : "N")
                audit.update()
            }
            stamped++
        }
        messages.add(0, "${dryRun ? 'Would stamp' : 'Stamped'} ${stamped} audit schedule(s); ${off} with automations off their schedule.".toString())
        return [rowsUpdated: stamped, auditsOffSchedule: off, messages: messages]
    }

    /** The most frequent non-null value, ties to the smallest; null when there is none. */
    private static String mostCommon(List<String> values) {
        Map<String, Integer> counts = values.findAll { it }.countBy { it }
        return counts ? counts.max { a, b -> (a.value <=> b.value) ?: (b.key <=> a.key) }.key : null
    }
}
