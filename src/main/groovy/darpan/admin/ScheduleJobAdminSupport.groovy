package darpan.admin

import groovy.transform.CompileDynamic

/**
 * DAR-BE-305: the dry-run count behind a resume preview. The rest of admin.ScheduleAdminServices (finds, checks, writes, the
 * audit text) is XML actions; the cron maths is darpan.schedule.JobScheduleRules.
 */
@CompileDynamic
class ScheduleJobAdminSupport {

    /** What today's dry run counts: the sum of tables[].rows, or {error}. */
    static Map previewRows(def ec, String previewService) {
        Map out
        try { out = ec.service.sync().name(previewService).call() } catch (Exception e) { return [error: "The preview ${previewService} failed: ${e.message}".toString()] }
        if (ec.message.hasError() || out == null) return [error: "The preview ${previewService} failed: ${ec.message.errorsString}".toString()]
        long rows = (out.tables ?: []).sum { (it.rows ?: 0L) as long } ?: 0L
        return [rows: rows]
    }
}
