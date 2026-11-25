package darpan.ops

import java.sql.Timestamp
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * DAR-BE-015. Pure assembly of the daily Darpan error digest from rows the caller already loaded: run errors grouped by
 * tenant then check, the watchdog (STALE, NO_AUTOMATION, EMPTY_STREAK) and the grouped log errors, plus the subject line.
 * No Moqui, no database, no logging.
 */
class ErrorDigestAssembler {
    static final String STALE = "STALE"
    static final String NO_AUTOMATION = "NO_AUTOMATION"
    static final String EMPTY_STREAK = "EMPTY_STREAK"
    private static final String SUCCESS = "AUT_STAT_SUCCESS"
    private static final String NO_DATA = "AUT_STAT_NO_DATA"

    static Map assemble(Map input) {
        String instance = input.instance
        Timestamp since = input.since as Timestamp
        Timestamp now = input.now as Timestamp
        List<Map> executions = (input.executions ?: []) as List<Map>
        List<Map> checks = (input.checks ?: []) as List<Map>
        int watchdogHours = (input.watchdogHours ?: 30) as int
        int emptyStreakDays = (input.emptyStreakDays ?: 7) as int

        List<Map> runErrors = buildRunErrors(executions, checks, since, now)
        List<Map> watchdog = buildWatchdog(checks, executions, now, watchdogHours, emptyStreakDays)
        List<Map> logErrors = buildLogErrors((input.logRows ?: []) as List<Map>)

        Set<String> subjects = new LinkedHashSet<String>()
        runErrors.each { subjects.add("${it.tenant}|${it.check ?: ('automation:' + it.automationId)}".toString()) }
        watchdog.each { subjects.add("${it.tenant}|${it.check ?: ('statement:' + it.statement)}".toString()) }
        int logCount = logErrors.sum { (it.count ?: 0) as int } as Integer ?: 0
        String day = now.toInstant().atZone(ZoneOffset.UTC).toLocalDate().toString()
        String subject = (subjects.isEmpty() && logCount == 0)
                ? "[${instance}] Darpan errors ${day}: none"
                : "[${instance}] Darpan errors ${day}: ${subjects.size()} checks, ${logCount} log errors"
        return [subject: subject, runErrors: runErrors, watchdog: watchdog, logErrors: logErrors,
                counts: [checks: subjects.size(), logErrors: logCount]]
    }

    private static boolean isScheduled(Map ex) {
        String t = ex.triggerType?.toString()?.trim()
        return !t || t == "scheduled"
    }

    private static Timestamp endOf(Map ex) { (ex.completedDate ?: ex.scheduledDate) as Timestamp }

    private static LocalDate dayOf(Map ex) {
        return RunErrorKinds.runDay(ex.scheduledDate as Timestamp, ex.zoneId?.toString())
    }

    private static List<Map> buildRunErrors(List<Map> executions, List<Map> checks, Timestamp since, Timestamp now) {
        // Error days per (tenant, automation), scheduled executions only, over every row given (not just the window).
        Map<String, Set<LocalDate>> errorDays = [:]
        executions.each { Map ex ->
            if (isScheduled(ex) && ex.scheduledDate != null && RunErrorKinds.of(ex) != null) {
                errorDays.computeIfAbsent("${ex.companyUserGroupId}|${ex.automationId}".toString(), { new HashSet<LocalDate>() })
                        .add(dayOf(ex))
            }
        }

        List<Map> out = []
        executions.each { Map ex ->
            String kind = RunErrorKinds.of(ex)
            Timestamp end = ex.completedDate as Timestamp
            if (kind == null || end == null || !end.after(since) || end.after(now)) return
            Integer streak = null
            if (isScheduled(ex) && ex.scheduledDate != null) {
                Set<LocalDate> days = errorDays.get("${ex.companyUserGroupId}|${ex.automationId}".toString())
                LocalDate d = dayOf(ex)
                streak = 0
                while (days.contains(d)) { streak++; d = d.minusDays(1) }
            }
            List<Map> feeds = checks.findAll { it.automationId && it.automationId == ex.automationId && it.tenantId == ex.companyUserGroupId }
            List<Map> subjectsOf = feeds ?: [[:]]
            subjectsOf.each { Map c ->
                out << [tenant: ex.companyUserGroupId, auditName: c.auditName, statement: c.statementId, check: c.checkId,
                        automationId: ex.automationId, kind: kind, triggerType: (ex.triggerType?.toString()?.trim() ?: "scheduled"),
                        daysInARow: streak, errorText: ex.errorMessage,
                        readCounts: [file1: ex.file1ReadCount, file2: ex.file2ReadCount],
                        keptCounts: [file1: ex.file1RecordCount, file2: ex.file2RecordCount],
                        files: [ex.file1Name, ex.file2Name].findAll { it },
                        runUrl: ex.runUrl ?: ex.reconciliationRunResultId,
                        completedDate: end]
            }
        }
        out.sort { a, b -> (a.tenant ?: "") <=> (b.tenant ?: "") ?: (a.check ?: a.automationId ?: "") <=> (b.check ?: b.automationId ?: "") }
        return out
    }

    private static List<Map> buildWatchdog(List<Map> checks, List<Map> executions, Timestamp now, int hours, int streakDays) {
        long cutoff = now.time - hours * 3600_000L
        List<Map> out = []
        checks.each { Map c ->
            if (!c.auditScheduleActive || !c.statementActive) return
            Map base = [tenant: c.tenantId, auditName: c.auditName, statement: c.statementId, check: c.checkId]
            if (!c.automationId) {
                out << (base + [kind: NO_AUTOMATION, lastSuccessDate: c.lastSuccessDate])
                return
            }
            if (!c.automationActive) return
            Timestamp last = c.lastSuccessDate as Timestamp
            executions.each { Map ex ->
                if (ex.automationId != c.automationId || ex.companyUserGroupId != c.tenantId || !isScheduled(ex)) return
                boolean healthy = ex.statusEnumId == SUCCESS ||
                        (ex.statusEnumId == NO_DATA && RunErrorKinds.of(ex) == null)
                Timestamp end = healthy ? endOf(ex) : null
                if (end != null && (last == null || end.after(last))) last = end
            }
            if (last == null || last.time < cutoff) {
                out << (base + [kind: STALE, lastSuccessDate: last])
            }
            List reads = (c.recentScheduledReads ?: []) as List
            if (streakDays > 0 && reads.size() >= streakDays &&
                    reads.take(streakDays).every { List r -> (r[0] ?: 0) as long == 0 && (r[1] ?: 0) as long == 0 }) {
                out << (base + [kind: EMPTY_STREAK, lastSuccessDate: c.lastSuccessDate])
            }
        }
        return out
    }

    private static List<Map> buildLogErrors(List<Map> rows) {
        // One line per fingerprint, tenant and UTC day: a row's count is its day's whole count (ErrorLogEntry, one row per
        // fingerprint per day), so a day seen by two emails says which day it is rather than adding into another.
        Map<String, Map> byKey = new LinkedHashMap<String, Map>()
        rows.each { Map r ->
            String key = "${r.fingerprint}|${r.tenantId ?: ''}|${r.logDay ?: ''}".toString()
            Map m = byKey.get(key)
            long n = (r.count ?: 0) as long
            if (m == null) {
                byKey.put(key, [fingerprint: r.fingerprint, loggerName: r.loggerName, sampleMessage: r.sampleMessage,
                                sampleStack: r.sampleStack, count: n, firstSeen: r.firstSeen, lastSeen: r.lastSeen,
                                tenantId: r.tenantId, logDay: r.logDay])
            } else {
                m.count = (m.count as long) + n
                if (r.firstSeen && (m.firstSeen == null || (r.firstSeen as Timestamp).before(m.firstSeen as Timestamp))) m.firstSeen = r.firstSeen
                if (r.lastSeen && (m.lastSeen == null || (r.lastSeen as Timestamp).after(m.lastSeen as Timestamp))) m.lastSeen = r.lastSeen
            }
        }
        return byKey.values().toList().sort { -(it.count as long) }
    }
}
