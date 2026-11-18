package darpan.admin

import darpan.schedule.JobScheduleRules
import groovy.transform.CompileDynamic
import org.moqui.impl.service.ServiceDefinition
import org.moqui.impl.service.ServiceFacadeImpl
import org.moqui.util.MNode
import org.moqui.util.ObjectUtilities

import java.sql.Timestamp
import java.time.Instant

/**
 * DAR-BE-305: the work behind the job services of admin.ScheduleAdminServices. Reads and plans here (cron maths, parameter
 * checks against the job's service, the resume preview); the service XML does the writes and the audit row. A refusal is
 * {error: "..."} and has changed nothing. The caller has checked super admin.
 *
 * Only Darpan's own jobs (a defaultCronExpression) are editable; the framework's are listed read-only.
 */
@CompileDynamic
class ScheduleJobAdminSupport {
    static final String JOB = "moqui.service.job.ServiceJob"
    static final String PARAM = "moqui.service.job.ServiceJobParameter"
    static final int NEXT_RUNS_LISTED = 3
    static final int NEXT_RUNS_PREVIEWED = 5

    // ------------------------------------------------------------------ reads

    static List<Map> describeAll(def ec) {
        Map<String, List> paramsByJob = ec.entity.find(PARAM).orderBy("parameterName").useCache(false).list().groupBy { it.jobName }
        Instant now = ec.user.nowTimestamp.toInstant()
        return ec.entity.find(JOB).orderBy("jobName").useCache(false).list().collect { def job ->
            describe(ec, job, paramsByJob[job.jobName] ?: [], lastRun(ec, job.jobName), now)
        }
    }

    static Map describeOne(def ec, String jobName) {
        def job = ec.entity.find(JOB).condition("jobName", jobName).useCache(false).one()
        if (job == null) return null
        List params = ec.entity.find(PARAM).condition("jobName", jobName).orderBy("parameterName").useCache(false).list()
        return describe(ec, job, params, lastRun(ec, jobName), ec.user.nowTimestamp.toInstant())
    }

    private static def lastRun(def ec, String jobName) {
        return ec.entity.find("moqui.service.job.ServiceJobRun").condition("jobName", jobName).orderBy("-startTime")
                .limit(1).useCache(false).list()?.getAt(0)
    }

    static Map describe(def ec, def job, List params, def lastRun, Instant now) {
        boolean editable = job.defaultCronExpression != null
        Map types = inParameterTypes(ec, job.serviceName)
        return [
                jobName              : job.jobName,
                description          : job.description,
                serviceName          : job.serviceName,
                cronExpression       : job.cronExpression,
                paused               : job.paused ?: "N",
                defaultCronExpression: job.defaultCronExpression,
                defaultPaused        : editable ? (job.defaultPaused ?: "N") : null,
                minIntervalMinutes   : job.minIntervalMinutes,
                editable             : editable,
                edited               : job.editedDate != null,
                editedByUserId       : job.editedByUserId,
                editedDate           : job.editedDate,
                nextRuns             : job.cronExpression ? JobScheduleRules.nextRuns(job.cronExpression, now, NEXT_RUNS_LISTED).collect { Timestamp.from(it) } : [],
                lastRun              : lastRun == null ? null : [startTime: lastRun.startTime, endTime: lastRun.endTime, hasError: lastRun.hasError],
                resumeNeedsPreview   : job.resumePreviewService != null,
                parameters           : params.collect { def p ->
                    [parameterName: p.parameterName, value: p.parameterValue, defaultValue: p.defaultValue,
                     edited: p.editedDate != null, type: types?.get(p.parameterName)]
                },
        ]
    }

    // ------------------------------------------------------------------ checks

    /** The cron check behind preview#JobSchedule. */
    static Map previewSchedule(def ec, def job, String cron) {
        Instant now = ec.user.nowTimestamp.toInstant()
        String text = cron?.trim()
        String problem = JobScheduleRules.cronProblem(text, job.minIntervalMinutes, now)
        return [problem: problem, nextRuns: text ? JobScheduleRules.nextRuns(text, now, NEXT_RUNS_PREVIEWED).collect { Timestamp.from(it) } : [],
                smallestGapMinutes: text ? JobScheduleRules.smallestGapMinutes(text, now) : null]
    }

    /** Parameter name to its Java type name for the in-parameters of `serviceName`; null when the service is unknown. */
    static Map<String, String> inParameterTypes(def ec, String serviceName) {
        ServiceDefinition sd = serviceDefinition(ec, serviceName)
        if (sd == null) return null
        Map<String, String> out = [:]
        sd.getInParameterNames().each { String n -> out[n] = sd.getInParameter(n)?.attribute("type") ?: "String" }
        return out
    }

    private static ServiceDefinition serviceDefinition(def ec, String serviceName) {
        try { return ((ServiceFacadeImpl) ec.service).getServiceDefinition(serviceName) } catch (Exception ignored) { return null }
    }

    /** Null when `value` (trimmed, not blank) converts to the service's in-parameter `name`, else why not. */
    static String parameterProblem(def ec, String serviceName, String name, Object value) {
        Map<String, String> types = inParameterTypes(ec, serviceName)
        if (types == null) return "The service ${serviceName} is not defined.".toString()
        if (!types.containsKey(name)) {
            return "${name} is not a parameter of ${serviceName}; it takes ${types.keySet().sort().join(', ') ?: 'none'}.".toString()
        }
        String text = value?.toString()?.trim()
        if (!text) return "${name} is blank; reset it to its default instead.".toString()
        String type = types[name]
        if (type in ["String", "java.lang.String", "Object", "CharSequence"]) return null
        Object converted = ObjectUtilities.basicConvert(text, type)
        Class target = org.moqui.util.MClassLoader.getCommonClass(type)
        if (target != null && !target.isInstance(converted)) return "${name} must be ${type}; '${text}' is not.".toString()
        return null
    }

    /** What today's dry run counts: the sum of tables[].rows, or {error}. */
    static Map previewRows(def ec, String previewService) {
        Map out
        try { out = ec.service.sync().name(previewService).call() } catch (Exception e) { return [error: "The preview ${previewService} failed: ${e.message}".toString()] }
        if (ec.message.hasError() || out == null) return [error: "The preview ${previewService} failed: ${ec.message.errorsString}".toString()]
        long rows = (out.tables ?: []).sum { (it.rows ?: 0L) as long } ?: 0L
        return [rows: rows]
    }

    // ------------------------------------------------------------------ plans

    /**
     * Plans an edit without writing. Returns {error} or {cronExpression, paused (new values or null), jobChanged,
     * paramWrites [{parameterName, value}], audit}.
     */
    static Map planUpdate(def ec, def job, String cron, String paused, Map parameters, Long previewedRows) {
        if (job.defaultCronExpression == null) return [error: "${job.jobName} is not one of Darpan's jobs and cannot be edited here.".toString()]
        if (cron != null && !cron.trim()) return [error: "The cron is blank; pause the job instead of clearing its cron."]
        String newCron = cron?.trim() ?: null
        String newPaused = paused?.trim()?.toUpperCase() ?: null
        if (newCron == null && newPaused == null && !parameters) return [error: "Nothing to change: send cronExpression, paused or parameters."]
        List<String> changes = []
        Map plan = [jobChanged: false, paramWrites: []]
        if (newCron != null) {
            String problem = JobScheduleRules.cronProblem(newCron, job.minIntervalMinutes, ec.user.nowTimestamp.toInstant())
            if (problem != null) return [error: problem]
            if (newCron != job.cronExpression) {
                plan.cronExpression = newCron
                changes << "cron: ${job.cronExpression} -> ${newCron}".toString()
            }
        }
        if (newPaused != null) {
            if (!(newPaused in ["Y", "N"])) return [error: "paused is Y or N, not '${paused}'.".toString()]
            String live = job.paused ?: "N"
            if (newPaused != live) {
                plan.paused = newPaused
                String note = ""
                if (newPaused == "N" && job.resumePreviewService != null) {
                    Map p = checkPreview(ec, job.resumePreviewService, previewedRows)
                    if (p.error) return [error: p.error]
                    note = " (${p.note})"
                }
                changes << "paused: ${live} -> ${newPaused}${note}".toString()
            }
        }
        plan.jobChanged = plan.cronExpression != null || plan.paused != null
        for (Map.Entry e in (parameters ?: [:]).entrySet()) {
            String name = e.key.toString()
            String problem = parameterProblem(ec, job.serviceName, name, e.value)
            if (problem != null) return [error: problem]
            String value = e.value.toString().trim()
            def row = ec.entity.find(PARAM).condition("jobName", job.jobName).condition("parameterName", name).useCache(false).one()
            if (row?.parameterValue == value) continue
            plan.paramWrites << [parameterName: name, value: value]
            changes << "${name}: ${row?.parameterValue ?: '(none)'} -> ${value}".toString()
        }
        plan.audit = changes.join("; ")
        plan.changed = !changes.isEmpty()
        return plan
    }

    /**
     * Plans a reset: {error} or {resetJob, paramRestores [{parameterName, defaultValue}], paramDeletes [names], audit,
     * changed}. `parameterNames` null = the cron, the pause state and every parameter.
     */
    static Map planReset(def ec, def job, List parameterNames, Long previewedRows) {
        if (job.defaultCronExpression == null) return [error: "${job.jobName} is not one of Darpan's jobs and cannot be reset here.".toString()]
        boolean all = parameterNames == null
        List<String> changes = []
        Map plan = [resetJob: all, paramRestores: [], paramDeletes: []]
        if (all) {
            String wantPaused = job.defaultPaused ?: "N"
            String live = job.paused ?: "N"
            if (job.cronExpression != job.defaultCronExpression) changes << "cron: ${job.cronExpression} -> ${job.defaultCronExpression}".toString()
            if (live != wantPaused) {
                String note = ""
                if (wantPaused == "N" && job.resumePreviewService != null) {
                    Map p = checkPreview(ec, job.resumePreviewService, previewedRows)
                    if (p.error) return [error: p.error]
                    note = " (${p.note})"
                }
                changes << "paused: ${live} -> ${wantPaused}${note}".toString()
            }
            plan.markerSet = job.editedDate != null
        }
        List rows = ec.entity.find(PARAM).condition("jobName", job.jobName).orderBy("parameterName").useCache(false).list()
        if (!all) {
            Set known = rows*.parameterName as Set
            String missing = parameterNames.collect { it.toString() }.find { !known.contains(it) }
            if (missing != null) return [error: "${job.jobName} has no parameter ${missing} to reset.".toString()]
        }
        rows.each { def r ->
            if (!all && !parameterNames.collect { it.toString() }.contains(r.parameterName)) return
            if (r.defaultValue != null) {
                if (r.parameterValue != r.defaultValue || r.editedDate != null) {
                    plan.paramRestores << [parameterName: r.parameterName, defaultValue: r.defaultValue]
                    if (r.parameterValue != r.defaultValue) changes << "${r.parameterName}: ${r.parameterValue} -> ${r.defaultValue}".toString()
                }
            } else if (!all || r.editedDate != null) {
                plan.paramDeletes << r.parameterName
                changes << "${r.parameterName}: ${r.parameterValue} -> (removed)".toString()
            }
        }
        plan.changed = !changes.isEmpty() || plan.markerSet || !plan.paramRestores.isEmpty() || !plan.paramDeletes.isEmpty()
        plan.audit = changes ? changes.join("; ") : "follows the shipped defaults again"
        return plan
    }

    /** Runs today's dry run and applies the tolerance rule; {error} or {note: "preview showed N rows, today's dry run counts M"}. */
    private static Map checkPreview(def ec, String previewService, Long previewedRows) {
        if (previewedRows == null) return [error: JobScheduleRules.previewProblem(null, 0L)]
        Map counted = previewRows(ec, previewService)
        if (counted.error) return counted
        String problem = JobScheduleRules.previewProblem(previewedRows, counted.rows as long)
        if (problem != null) return [error: problem]
        return [note: "preview showed ${previewedRows} rows, today's dry run counts ${counted.rows}".toString()]
    }
}
