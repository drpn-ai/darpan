package darpan.audit

import darpan.config.DarpanSettings
import groovy.json.JsonSlurper
import org.moqui.entity.EntityCondition

import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * DAR-BE-320 phase 1b. What one audit play read and what it cost, from the ledger's source reads (AuditSourceRead:
 * connection, window, call tally, lookup outcome): the reads grouped by endpoint and connection, the totals, and the
 * flags that say a read cost more than it needed. drpn.ai/agent promises "reads each system once"; these flags are where
 * that promise is broken, found by code, the same way every morning. The run update shows one line from it and the
 * optional reviewer (darpan-agent, agent.AgentWriterServices.review#AuditReads) turns the flags into staff tickets.
 *
 * Flags (thresholds are settings, editable in prod):
 * - DUPLICATE_READ: one connection's endpoint read more than once in the play (one read per record type is the target).
 * - WHOLE_PERIOD_READ: a read from the period's first day, more than wholePeriodGraceDays into a period the check already
 *   played (incremental reads start at the last covered day).
 * - LOOKUP_OVER_SHARE: a verify the share gate skipped (the read or the pairing is wrong).
 * - LOOKUP_EVERY_PLAY: the same check looked records up on one side on each of the last lookupEveryPlayRuns plays.
 * - THROTTLED: a connection's endpoint was throttled more than throttledMax times.
 * - CALLS_GREW: a connection's endpoint made more than callsGrewPercent % more calls than on the previous play (and at
 *   least callsGrewMinCalls).
 * A read with no known connection is counted but never flagged as a duplicate: the flag would be a guess.
 */
class AuditReadReport {
    private static final String WHY = "audit read report: explicit companyUserGroupId condition applied"

    static final String SETTING_PREFIX = "darpan.audit.readReport."
    static final List<String> FLAGS = ["DUPLICATE_READ", "WHOLE_PERIOD_READ", "LOOKUP_OVER_SHARE", "LOOKUP_EVERY_PLAY",
                                       "THROTTLED", "CALLS_GREW"].asImmutable()

    /** The thresholds, from the settings (row, then property, then catalog default). */
    static Map thresholds() {
        return [wholePeriodGraceDays: DarpanSettings.intValue(SETTING_PREFIX + "wholePeriodGraceDays"),
                lookupEveryPlayRuns : DarpanSettings.intValue(SETTING_PREFIX + "lookupEveryPlayRuns"),
                throttledMax        : DarpanSettings.intValue(SETTING_PREFIX + "throttledMax"),
                callsGrewPercent    : DarpanSettings.intValue(SETTING_PREFIX + "callsGrewPercent"),
                callsGrewMinCalls   : DarpanSettings.intValue(SETTING_PREFIX + "callsGrewMinCalls")]
    }

    /**
     * The report of one play of an audit: its check runs on reportDate (blank: the audit's latest), of period (blank: every
     * period run that morning). Null when the audit has no check run.
     */
    static Map forPlay(def ec, String tenantId, String auditId, Date reportDate, String period, Map limits = thresholds()) {
        Map<String, Map> checks = checksOf(ec, tenantId, auditId)
        if (!checks) return null
        if (reportDate == null) {
            def latest = AuditReportAssembler.tenantRows(ec, "darpan.audit.AuditCheckRun", tenantId, WHY).condition("checkId", EntityCondition.IN, checks.keySet())
                    .orderBy("-reportDate").limit(1).list().find()
            if (latest == null) return null
            reportDate = latest.reportDate as Date
        }
        def playFind = AuditReportAssembler.tenantRows(ec, "darpan.audit.AuditCheckRun", tenantId, WHY).condition("checkId", EntityCondition.IN, checks.keySet())
                .condition("reportDate", reportDate)
        if (period) playFind.condition("period", period)
        List<Map> playRuns = playFind.list()*.getMap()
        if (!playRuns) return null

        // Earlier plays, newest first: enough for LOOKUP_EVERY_PLAY and CALLS_GREW.
        int depth = Math.max(((limits.lookupEveryPlayRuns ?: 1) as int) - 1, 1)
        List<Map> earlierRuns = AuditReportAssembler.tenantRows(ec, "darpan.audit.AuditCheckRun", tenantId, WHY).condition("checkId", EntityCondition.IN, checks.keySet())
                .condition("reportDate", EntityCondition.LESS_THAN, reportDate).orderBy("-reportDate").limit(depth * checks.size() * 2)
                .list()*.getMap()
        List<Date> earlierDays = (earlierRuns*.reportDate as List<Date>).unique().take(depth)
        earlierRuns = earlierRuns.findAll { earlierDays.contains(it.reportDate) }

        // Checks that already played this period before this morning: a whole-period read is expected only on the first.
        Set<String> playedBefore = earlierRuns.findAll { r -> playRuns.any { it.checkId == r.checkId && it.period == r.period } }
                *.checkId.toSet() as Set<String>
        if (playRuns.any { !playedBefore.contains(it.checkId) }) {
            AuditReportAssembler.tenantRows(ec, "darpan.audit.AuditCheckRun", tenantId, WHY).condition("checkId", EntityCondition.IN, playRuns*.checkId)
                    .condition("period", EntityCondition.IN, playRuns*.period.unique())
                    .condition("reportDate", EntityCondition.LESS_THAN, reportDate).list()
                    .each { r -> if (playRuns.any { it.checkId == r.checkId && it.period == r.period }) playedBefore << (r.checkId as String) }
        }

        ZoneId zone = AuditDigestSupport.tenantZone(ec, tenantId)
        Map<String, List<Map>> readsByRun = readsOf(ec, tenantId, (playRuns + earlierRuns)*.checkRunId)
        List<Map> play = playRuns.collectMany { Map run -> readsByRun[run.checkRunId as String].collect { readRow(it, run, checks, zone, playedBefore) } }
        List<List<Map>> history = earlierDays.collect { Date day ->
            earlierRuns.findAll { it.reportDate == day }.collectMany { Map run ->
                readsByRun[run.checkRunId as String].collect { readRow(it, run, checks, zone, playedBefore) }
            }
        }
        return [reportDate: reportDate.toString(), period: period ?: (playRuns*.period.unique().join(","))] + build(play, history, limits)
    }

    /**
     * Pure. play: this morning's reads, as {@link #readRow} makes them; history: earlier plays' reads, newest play first.
     * Answers totals, groups (one per endpoint + connection) and flags, each flag with its plain-words text.
     */
    static Map build(List<Map> play, List<List<Map>> history, Map limits) {
        Map<String, Map> groups = new LinkedHashMap<>()
        play.each { Map r ->
            Map g = groups.computeIfAbsent(groupKey(r)) {
                [endpointEnumId: r.endpointEnumId, systemEnumId: r.systemEnumId, sourceConfigId: r.sourceConfigId,
                 reads: 0, records: 0L, checks: [], windows: [], calls: new LinkedHashMap<String, Long>(), failed: 0]
            }
            g.reads = (g.reads as int) + 1
            g.records = (g.records as long) + ((r.recordCount ?: 0) as long)
            if (r.statusEnumId == "AUDSR_FAILED") g.failed = (g.failed as int) + 1
            if (!(g.checks as List).contains(r.checkCode)) (g.checks as List) << r.checkCode
            if (r.windowFrom || r.windowThru) (g.windows as List) << [check: r.checkCode, from: r.windowFrom, thru: r.windowThru]
            AuditReadReport.addCalls(g.calls, r.calls)
        }
        Map<String, Long> totalCalls = new LinkedHashMap<>()
        groups.values().each { AuditReadReport.addCalls(totalCalls, it.calls) }
        Map totals = [reads      : play.size(), connections: groups.values().count { it.sourceConfigId } as int,
                      records    : groups.values().sum(0L) { it.records as long }, calls: totalCalls]

        List<Map> flags = []
        groups.values().each { Map g ->
            String where = where(g)
            if (g.sourceConfigId && (g.reads as int) > 1) {
                flags << flag("DUPLICATE_READ", g, g.checks as List, [reads: g.reads],
                        "${where} was read ${g.reads} times this morning (${(g.checks as List).join(', ')}); one read should serve them all.")
            }
            long throttled = ((g.calls as Map).throttled ?: 0L) as long
            if (throttled > ((limits.throttledMax ?: 0) as long)) {
                flags << flag("THROTTLED", g, g.checks as List, [throttled: throttled],
                        "${where} was throttled ${throttled} times.")
            }
            Map previous = history ? groupOf(history[0], g) : null
            long now = ((g.calls as Map).graphql ?: 0L) as long
            long before = ((previous?.calls as Map)?.graphql ?: 0L) as long
            if (before > 0 && now >= ((limits.callsGrewMinCalls ?: 0) as long)
                    && (now - before) * 100L > ((limits.callsGrewPercent ?: 0) as long) * before) {
                flags << flag("CALLS_GREW", g, g.checks as List, [calls: now, previousCalls: before],
                        "${where} made ${now} calls, up from ${before} on the play before.")
            }
        }
        play.each { Map r ->
            String where = where(r)
            if (r.playedBefore && r.windowFromDay && r.periodFirstDay && r.reportDay
                    && r.windowFromDay <= r.periodFirstDay
                    && ChronoUnit.DAYS.between(LocalDate.parse(r.periodFirstDay as String), LocalDate.parse(r.reportDay as String))
                    > ((limits.wholePeriodGraceDays ?: 0) as long)) {
                flags << flag("WHOLE_PERIOD_READ", r, [r.checkCode], [from: r.windowFromDay, reportDay: r.reportDay],
                        "${r.checkCode} read ${where} from ${r.windowFromDay}, the start of ${r.period}; it only needed the days since its last play.")
            }
            Map lookup = r.lookup as Map
            if (lookup?.skipped == "OVER_SHARE") {
                flags << flag("LOOKUP_OVER_SHARE", r, [r.checkCode],
                        [candidates: lookup.candidates, checkedAgainst: lookup.checkedAgainst, maxSharePercent: lookup.maxSharePercent],
                        "${r.checkCode}: ${lookup.candidates} of ${lookup.checkedAgainst} records were missing on ${where}, above the ${lookup.maxSharePercent}% a lookup is for; the read or the pairing is wrong.")
            }
            int runs = (limits.lookupEveryPlayRuns ?: 0) as int
            if (lookedUp(r) && runs > 1 && history.size() >= runs - 1
                    && history.take(runs - 1).every { List<Map> earlier -> earlier.any { sameSide(it, r) && lookedUp(it) } }) {
                flags << flag("LOOKUP_EVERY_PLAY", r, [r.checkCode], [plays: runs, lookedUp: lookup.lookedUp],
                        "${r.checkCode} looked records up on ${where} on each of the last ${runs} plays; a lookup that runs every play means the read should carry that data.")
            }
        }
        return [totals: totals, groups: groups.values() as List, flags: flags]
    }

    /** One source read as the report reads it: the ledger row with its check, its run's period and the window as days. */
    static Map readRow(Map read, Map run, Map<String, Map> checks, ZoneId zone, Set<String> playedBefore) {
        return [checkId       : run.checkId, checkCode: checks[run.checkId as String]?.checkCode ?: run.checkId,
                period        : run.period, reportDay: run.reportDate?.toString(), periodFirstDay: run.windowFromDate?.toString(),
                playedBefore  : playedBefore.contains(run.checkId as String),
                endpointEnumId: read.endpointEnumId ?: read.systemEnumId, systemEnumId: read.systemEnumId,
                sourceConfigId: read.sourceConfigId, statusEnumId: read.statusEnumId, recordCount: read.recordCount,
                windowFrom    : stamp(read.windowFromDate), windowThru: stamp(read.windowThruDate),
                windowFromDay : read.windowFromDate ? ((Timestamp) read.windowFromDate).toInstant().atZone(zone).toLocalDate().toString() : null,
                calls         : json(read.callsJson), lookup: json(read.lookupJson)]
    }

    /** One line for the run update: {reads, connections, calls, flags}, or null when nothing was read. */
    static Map summary(Map report) {
        if (!report || !((report.totals as Map)?.reads)) return null
        Map t = report.totals as Map
        return [reads: t.reads, connections: t.connections, calls: ((t.calls as Map)?.graphql ?: 0L), flags: (report.flags as List).size()]
    }

    private static Map flag(String code, Map at, List checks, Map detail, String text) {
        return [flag: code, endpointEnumId: at.endpointEnumId, systemEnumId: at.systemEnumId, sourceConfigId: at.sourceConfigId,
                checks: checks, detail: detail, text: text.toString()]
    }

    private static String where(Map at) {
        return at.sourceConfigId ? "${at.endpointEnumId} on ${at.sourceConfigId}".toString() : (at.endpointEnumId as String)
    }

    private static String groupKey(Map r) { return "${r.endpointEnumId}|${r.sourceConfigId ?: ''}".toString() }

    private static Map groupOf(List<Map> reads, Map g) {
        List<Map> mine = reads.findAll { groupKey(it) == groupKey(g) }
        if (!mine) return null
        Map<String, Long> calls = new LinkedHashMap<>()
        mine.each { AuditReadReport.addCalls(calls, it.calls) }
        return [calls: calls]
    }

    private static boolean sameSide(Map a, Map b) { return a.checkId == b.checkId && groupKey(a) == groupKey(b) }

    private static boolean lookedUp(Map r) { return (((r.lookup as Map)?.lookedUp ?: 0) as long) > 0 }

    /**
     * DAR-BE-394: called qualified and with no inline cast, from closures. Groovy 5.0.3 fails a bare static call from a
     * closure when an inline-cast argument is null ("argument type mismatch"), and a read with no call tally has none.
     */
    private static void addCalls(Object target, Object tally) {
        Map<String, Long> into = (Map<String, Long>) target
        if (!(tally instanceof Map)) return
        ((Map) tally).each { k, v -> if (v instanceof Number) into.put(k as String, ((into.get(k as String) ?: 0L) as long) + (v as long)) }
    }

    private static String stamp(Object value) { return value instanceof Timestamp ? ((Timestamp) value).toInstant().toString() : null }

    private static Map json(Object text) {
        if (!(text instanceof String) || !text) return null
        try {
            Object parsed = new JsonSlurper().parseText(text as String)
            return parsed instanceof Map ? parsed as Map : null
        } catch (groovy.json.JsonException ignored) {
            return null // a tally cut at the column's length reads as unknown, never as zero
        }
    }

    private static Map<String, Map> checksOf(def ec, String tenantId, String auditId) {
        List statementIds = AuditReportAssembler.tenantRows(ec, "darpan.audit.AuditStatement", tenantId, WHY).condition("auditId", auditId).list()*.statementId
        if (!statementIds) return [:]
        List checkIds = AuditReportAssembler.tenantRows(ec, "darpan.audit.AuditStatementCheck", tenantId, WHY).condition("statementId", EntityCondition.IN, statementIds)
                .list()*.checkId.unique()
        if (!checkIds) return [:]
        return AuditReportAssembler.tenantRows(ec, "darpan.audit.AuditCheck", tenantId, WHY).condition("checkId", EntityCondition.IN, checkIds).list()
                .collectEntries { [(it.checkId as String): it.getMap()] }
    }

    private static Map<String, List<Map>> readsOf(def ec, String tenantId, List runIds) {
        Map<String, List<Map>> out = [:].withDefault { [] }
        if (!runIds) return out
        AuditReportAssembler.tenantRows(ec, "darpan.audit.AuditSourceRead", tenantId, WHY).condition("checkRunId", EntityCondition.IN, runIds)
                .orderBy(["checkRunId", "readSeqId"]).list().each { out[it.checkRunId as String] << it.getMap() }
        return out
    }

}
