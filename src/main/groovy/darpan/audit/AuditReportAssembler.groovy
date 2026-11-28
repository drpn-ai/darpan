package darpan.audit

import darpan.config.DarpanSettings
import darpan.facade.common.TenantScopedFinder
import org.moqui.entity.EntityCondition

import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * DAR-BE-090 / DAR-BE-103. The one assembler for the audit-run JSON: the report (darpan.audit.report/v1) and the
 * finding (darpan.audit.finding/v1), built to docs/superpowers/specs/2026-10-21-audit-run-json-contract.md
 * in the wrapper repo. GraphQL, the CLI, the app screen and the digest all render these maps; none of them has
 * fields of its own.
 *
 * Read-only over stored rows. Tenant: the assembler runs on scheduler threads (no active tenant, authz off) as well
 * as under a signed-in user, so every read goes through TenantScopedFinder.findGlobalUnscoped with an explicit
 * companyUserGroupId condition, and a read by primary key goes through findOneOwnedByTenant (Moqui drops a tenant
 * condition placed beside a full primary key).
 *
 * Determinism: every age and every state is computed from stored rows and the report date, never from when the
 * request ran. The only clock value is report.generatedAt, which the caller can pin. DAR-BE-361: it is also the report's
 * now: its tenant day is today for remind days, and it picks the latest morning, whose open findings split into open
 * and acknowledged.
 */
class AuditReportAssembler {

    static final String REPORT_SCHEMA = "darpan.audit.report/v1"

    static final String OPEN = "AUDFS_OPEN"
    static final String FIXED = "AUDFS_FIXED"
    static final String RAN = "AUDCS_RAN"
    static final String PARTIAL = "AUDCS_PARTIAL"
    static final String NOT_RUN = "AUDCS_NOT_RUN"
    static final String READ_COMPLETE = "AUDSR_COMPLETE"
    /** DAR-BE-361. A person's history event (acknowledged, changed, reopened): never a check's, never a state. */
    static final String USER_ACTOR = "AUDAT_USER"

    /** Worst last: a statement or system takes the worst status among its checks or reads. */
    static final List<String> CHECK_STATUS_ORDER = [RAN, PARTIAL, NOT_RUN].asImmutable()
    static final List<String> READ_STATUS_ORDER = [READ_COMPLETE, "AUDSR_PARTIAL", "AUDSR_FAILED"].asImmutable()

    static final int FINDINGS_CAP = 50
    static final String NO_CHECK_REASON = "No check is configured for this statement yet."

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
    private static final List<String> ENUM_TYPES = ["AuditCategory", "AuditStatementWeight", "AuditFindingState",
            "AuditCheckStatus", "AuditSourceRead", "AuditFindingEvent", "AuditActorType", "AuditPeriod"].asImmutable()
    private static final String WHY = "audit assembler: explicit companyUserGroupId condition applied"

    // ---------------------------------------------------------------------------------------------------------
    // The report
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The report for one period of one audit as of one morning (DAR-BE-091: each period is its own run and report; a
     * blank period is the morning's own). Null when the tenant or the audit (in that tenant) does not exist; throws
     * IllegalArgumentException when the period does not parse or has not started by that morning.
     */
    static Map<String, Object> assembleReport(def ec, String tenantId, String auditId, Date reportDate, String period,
                                              Timestamp generatedStamp) {
        Map tenant = loadTenant(ec, tenantId)
        if (tenant == null) return null
        Map audit = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.Audit", "auditId", auditId, tenantId, WHY)?.getMap()
        if (audit == null) return null
        ZoneId zone = tenant.zone as ZoneId
        LocalDate day = reportDate.toLocalDate()
        Map<String, String> codes = enumCodes(ec)
        String periodCode = codes[audit.periodEnumId as String]
        period = period ?: AuditPeriods.periodOf(day, periodCode)
        Map window = AuditPeriods.periodWindow(ec, audit, period, day)
        if (window == null) throw new IllegalArgumentException("${period} is not a ${periodCode} period of audit ${audit.auditCode} that has started by ${day}")

        List<Map> categories = ec.entity.find("moqui.basic.Enumeration").condition("enumTypeId", "AuditCategory")
                .orderBy("sequenceNum").useCache(true).list()*.getMap()
        List<Map> statements = activeStatements(ec, tenantId, auditId)
        Set<String> statementIds = statements*.statementId.toSet() as Set<String>
        Map<String, Map> checksById = tenantRows(ec, "darpan.audit.AuditCheck", tenantId).list()
                .collectEntries { [(it.checkId): it.getMap()] }
        List<Map> links = tenantRows(ec, "darpan.audit.AuditStatementCheck", tenantId)
                .orderBy(["sequenceNum", "checkId"]).list()*.getMap()
        Map<String, List<String>> checkIdsByStatement = [:].withDefault { [] }
        links.each { if (checksById[it.checkId] && statementIds.contains(it.statementId)) checkIdsByStatement[it.statementId as String] << (it.checkId as String) }
        Set<String> configuredCheckIds = checkIdsByStatement.values().flatten().toSet() as Set<String>
        // DAR-BE-371. The systems each check compares, from its automation's sources, so a check not run today still names them.
        Map<String, String> statementTextById = statements.collectEntries { [(it.statementId as String): it.statementText as String] }
        Map<String, String> statementTextByCheck = [:]
        links.each { if (statementTextById[it.statementId as String]) statementTextByCheck.putIfAbsent(it.checkId as String, statementTextById[it.statementId as String]) }
        Map<String, List<String>> systemsByCheck = checkSystems(ec, tenantId, configuredCheckIds.collect { checksById[it] } as List<Map>,
                statementTextByCheck)

        // This morning's runs and their reads.
        Map<String, Map> runByCheck = tenantRows(ec, "darpan.audit.AuditCheckRun", tenantId).condition("period", period)
                .condition("reportDate", reportDate).list().collectEntries { [(it.checkId): it.getMap()] }
        Map<String, List<Map>> readsByRun = [:].withDefault { [] }
        if (runByCheck) {
            tenantRows(ec, "darpan.audit.AuditSourceRead", tenantId)
                    .condition("checkRunId", "in", runByCheck.values()*.checkRunId)
                    .orderBy(["checkRunId", "readSeqId"]).list().each { readsByRun[it.checkRunId as String] << it.getMap() }
        }
        Map<String, String> checkStatus = [:]
        Map<String, String> checkReason = [:]
        configuredCheckIds.each { String checkId ->
            Map run = runByCheck[checkId]
            checkStatus[checkId] = (run?.statusEnumId ?: NOT_RUN) as String
            checkReason[checkId] = run ? (run.reason as String) : (window.running ?
                    "The check did not run on the morning of ${day}." :
                    "The ${period} audit ended on ${window.end}.").toString()
        }

        // The ledger as of the end of the report date.
        List<Map> ledger = findingsAsOf(ec, tenantId, statementIds, period, day, zone)
        Map<String, List<Map>> ledgerByStatement = ledger.groupBy { it.row.statementId as String }

        Map<String, Map> statementJsonById = [:]
        Map<String, String> statementCodeById = statements.collectEntries { [(it.statementId): it.statementCode] }
        // DAR-UI-075. One finding per order: every line's records name its order, so the order's lines are one unit,
        // counted and capped as one. The ledger stays per line, so each line still opens and closes on its own.
        // DAR-BE-250. Earliest step wins: an order broken at several steps counts once, at the earliest; the later
        // steps' units wait on it and are listed under it, not counted. Every statement's records are read up front.
        Map<String, Map> recordsByFinding = recordsFor(ec, tenantId, ledger*.row*.findingId as List<String>)
        Map<String, List<Map>> entriesByStatement = statements.collectEntries { Map st ->
            [(st.statementId as String): AuditOrderUnits.sortOldestFirst(ledgerByStatement[st.statementId as String] ?: [])] }
        Map<String, Map> split = AuditOrderUnits.stepUnits(statements, entriesByStatement, recordsByFinding)
        Set<String> waitingIds = split.values().collectMany { ((it.waiting ?: []) as List<Map>).collectMany { (it.entries as List<Map>)*.row*.findingId } } as Set<String>
        // DAR-BE-361. Current state on the current report: the latest morning's report (the one a blank date shows, which
        // is yesterday's until today's run) splits each open row into open, acknowledged or reminded, with remind days
        // read against the tenant's real today. An older morning is as it was.
        Instant now = (generatedStamp ?: new Timestamp(System.currentTimeMillis())).toInstant()
        LocalDate today = now.atZone(zone).toLocalDate()
        if (!day.isBefore(AuditReportGraph.latestMorning(ec, tenantId, auditId, period, now).toLocalDate())) {
            markAcknowledgements(ec, tenantId, split.values().collectMany { it.counted as List<Map> } as List<Map>,
                    ledger.findAll { it.state == OPEN }*.row*.findingId as List<String>, today, zone)
        }
        Map levels = priorityLevels(ec, tenantId, statements)
        statements.each { Map st ->
            List<String> stChecks = checkIdsByStatement[st.statementId as String]
            List<Map> checkJson = stChecks.collect { String checkId ->
                Map check = checksById[checkId]
                String status = checkStatus[checkId]
                Map run = runByCheck[checkId]
                [id       : check.checkCode,
                 label    : check.label,
                 status   : codes[status],
                 reason   : status == RAN ? null : checkReason[checkId],
                 matchedBy: check.matchedBy ?: null,
                 compared : status == NOT_RUN || run == null ? null : compared(readsByRun[run.checkRunId as String]),
                 systems  : systemsByCheck[checkId] ?: [],
                 // DAR-UI-063. The days this morning's run read: the period to date, or the days a person played.
                 window   : run == null ? null : [from: isoDate(run.windowFromDate as Date), thru: isoDate(run.windowThruDate as Date)]]
            }
            String worst = stChecks ? stChecks.collect { checkStatus[it] }.max { CHECK_STATUS_ORDER.indexOf(it) } : NOT_RUN
            String reason = null
            if (!stChecks) reason = NO_CHECK_REASON
            else if (worst != RAN) {
                List<String> bad = stChecks.findAll { checkStatus[it] != RAN }
                reason = bad.size() == 1 && stChecks.size() == 1 ? checkReason[bad[0]] :
                        bad.collect { "${checksById[it].checkCode}: ${checkReason[it]}" }.join(" ")
            }

            Map stSplit = split[st.statementId as String]
            List<Map> units = stSplit.counted as List<Map>
            Map counts = countsOf(units, day, levels)
            List<Map> listed = units.take(FINDINGS_CAP)
            List<Map> shown = listed + listed.collectMany { ((it.waitingUnits ?: []) as List<Map>) }
            Map<String, Map> causes = conclusionsOf(ec, shown*.entries.flatten()*.row*.conclusionEnumId as List<String>)
            statementJsonById[st.statementId as String] = [
                    id             : st.statementCode,
                    // DAR-BE-250: the statement's place in the order's story (sequenceNum); the earliest broken step wins.
                    step           : st.sequenceNum == null ? null : (st.sequenceNum as Long),
                    text           : st.statementText,
                    weight         : codes[st.weightEnumId as String],
                    priority       : (levels.statementLevels as Map).get(st.statementId) ?: AuditPriority.DEFAULT_LEVEL,
                    status         : codes[worst],
                    reason         : reason,
                    checks         : checkJson,
                    counts         : counts,
                    findings       : listed.collect { Map unit ->
                        unitFinding(unit, day, recordsByFinding, causes, codes, levels) +
                                (unit.waitingUnits ? [waiting: waitingJson(unit, day, recordsByFinding, causes, codes, statementCodeById, levels)] : [:]) },
                    findingsOmitted: units.size() - listed.size(),
                    // DAR-BE-250: this statement's findings on orders an earlier step already reports; listed there.
                    waiting        : (stSplit.waiting as List).size()]
        }

        Map<String, Object> categoryJson = new LinkedHashMap<>()
        Map<String, Object> byCategory = new LinkedHashMap<>()
        categories.each { Map cat ->
            Map<String, Object> stJson = new LinkedHashMap<>()
            statements.findAll { it.categoryEnumId == cat.enumId }.each { stJson[it.statementCode as String] = statementJsonById[it.statementId as String] }
            Map counts = sumCounts(stJson.values()*.counts)
            categoryJson[cat.enumCode as String] = [label: cat.description, counts: counts, statements: stJson]
            byCategory[cat.enumCode as String] = counts
        }

        Map oldest = ledger.findAll { it.state == OPEN && !waitingIds.contains(it.row.findingId as String) }.with { AuditOrderUnits.sortOldestFirst(it) }.find()
        Map summary = sumCounts(byCategory.values() as List) + [
                oldestOpen: oldest == null ? null : [
                        id        : oldest.row.findingId,
                        statement : statementCodeById[oldest.row.statementId as String],
                        firstFound: isoDate(oldest.row.firstFoundDate as Date),
                        ageDays   : days(oldest.row.firstFoundDate as Date, day)],
                byCategory: byCategory]

        List<String> notCovered = statements.findAll { !checkIdsByStatement[it.statementId as String] }*.statementCode as List<String>
        Map health = health(configuredCheckIds, checkStatus, checkReason, checksById, runByCheck, readsByRun,
                checkIdsByStatement, statements, notCovered, zone, codes)

        String name = tenant.name as String
        List<String> allCodes = statements*.statementCode as List<String>
        String range = allCodes.size() > 1 ? " ${allCodes.first()} to ${allCodes.last()}" : (allCodes ? " ${allCodes[0]}" : "")

        return [schema    : REPORT_SCHEMA,
                about     : "${audit.auditName} audit for ${name}, ${period}. Each finding is one record that breaks one of the audit's statements${range}.".toString(),
                report    : [tenant      : [id: tenantId, name: name, timeZone: zone.id],
                             audit       : [id: audit.auditCode, name: audit.auditName, period: periodCode],
                             date        : day.toString(),
                             period      : period,
                             // DAR-BE-091: the period to date, the report day included; a past period's whole span.
                             window      : [from: (window.from as LocalDate).toString(), thru: (window.thru as LocalDate).toString()],
                             running     : window.running,
                             generatedAt : isoDateTime(generatedStamp, zone),
                             // DAR-BE-361: the remind days a new acknowledgement gets, so the popup's words match the rule.
                             acknowledgeRemindDays: DarpanSettings.intValue("audit.acknowledge.remindDays")],
                health    : health,
                summary   : summary,
                categories: categoryJson]
    }

    private static Map health(Set<String> configuredCheckIds, Map<String, String> checkStatus, Map<String, String> checkReason,
                              Map<String, Map> checksById, Map<String, Map> runByCheck, Map<String, List<Map>> readsByRun,
                              Map<String, List<String>> checkIdsByStatement, List<Map> statements, List<String> notCovered,
                              ZoneId zone, Map<String, String> codes) {
        // Reads of configured checks only, grouped by root system in first-seen order.
        Map<String, List<Map>> readsBySystem = new LinkedHashMap<>()
        Map<String, Set<String>> badChecksBySystem = [:].withDefault { new LinkedHashSet<String>() }
        configuredCheckIds.sort().each { String checkId ->
            Map run = runByCheck[checkId]
            if (run == null) return
            readsByRun[run.checkRunId as String].each { Map read ->
                String key = AuditRecordFacts.systemKey(read.systemEnumId as String)
                readsBySystem.computeIfAbsent(key) { [] } << read
                if (read.statusEnumId != READ_COMPLETE) badChecksBySystem[key] << checkId
            }
        }
        Map<String, Object> sources = new LinkedHashMap<>()
        readsBySystem.each { String key, List<Map> reads ->
            String worst = reads*.statusEnumId.max { READ_STATUS_ORDER.indexOf(it) } as String
            // DAR-UI-063. Checks run by one automation share one read per window (same stamp, same count): count it once,
            // or two checks on one run show twice the records read.
            List counted = reads.unique(false) { [it.systemEnumId, it.endpointEnumId, it.readStamp, it.recordCount] }
                    *.recordCount.findAll { it != null }
            List<Timestamp> stamps = reads*.readStamp.findAll { it != null } as List<Timestamp>
            Set<String> badChecks = badChecksBySystem[key]
            sources[key] = [
                    read   : codes[worst],
                    records: counted ? (counted.sum { it as long } as long) : null,
                    readAt : stamps ? isoDateTime(stamps.max(), zone) : null,
                    reason : worst == READ_COMPLETE ? null : reads.findAll { it.statusEnumId != READ_COMPLETE }
                            .collect { it.reason ?: "no reason was given" }.unique().join("; "),
                    affects: statements.findAll { st -> checkIdsByStatement[st.statementId as String].any { badChecks.contains(it) } }
                            *.statementCode]
        }

        int ran = configuredCheckIds.count { checkStatus[it] == RAN } as int
        int partial = configuredCheckIds.count { checkStatus[it] == PARTIAL } as int
        int notRun = configuredCheckIds.count { checkStatus[it] == NOT_RUN } as int
        // No hollow green: an audit with no check configured has read nothing, so it is not complete.
        boolean complete = !configuredCheckIds.isEmpty() && partial == 0 && notRun == 0

        List<String> sentences = []
        if (configuredCheckIds.isEmpty()) sentences << "No check is configured yet."
        else if (complete) sentences << "Every configured check ran over complete reads."
        else {
            List<String> parts = []
            sources.each { String key, Map src -> if (src.read != "complete") parts << "the ${key} read ${src.read == 'failed' ? 'failed' : 'was partial'}".toString() }
            List<String> notRunCodes = configuredCheckIds.findAll { checkStatus[it] == NOT_RUN }.collect { checksById[it].checkCode as String }.sort()
            if (notRunCodes) parts << "${notRunCodes.size() == 1 ? 'check' : 'checks'} ${listPhrase(notRunCodes)} did not run".toString()
            sentences << "Not complete: ${parts.join('; ')}.".toString()
        }
        if (notCovered) sentences << "${listPhrase(notCovered)} ${notCovered.size() == 1 ? 'has' : 'have'} no check yet.".toString()

        return [complete  : complete,
                summary   : sentences.join(" "),
                sources   : sources,
                checks    : [ran: ran, partial: partial, notRun: notRun],
                notCovered: notCovered]
    }

    /**
     * Every finding that existed by the end of {@code day}, with its state on that day. Today's state is the stored
     * one; a past day's state comes from the last history event on or before it (contract: "History for a past
     * date"): open after a found, still-open or reopened event, fixed after a fixed one.
     * Fixed findings are kept only when they became fixed on {@code day}. DAR-BE-361: a person's events (acknowledged,
     * changed, reopened; actor AUDAT_USER) are not check events and never decide a state.
     */
    private static List<Map> findingsAsOf(def ec, String tenantId, Set<String> statementIds, String period, LocalDate day,
                                          ZoneId zone) {
        if (!statementIds) return []
        Date dayDate = Date.valueOf(day)
        // A day of margin on the closed stamp; the events decide.
        Timestamp closedFloor = new Timestamp(day.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        def cf = ec.entity.conditionFactory
        List<Map> rows = tenantRows(ec, "darpan.audit.AuditFinding", tenantId)
                .condition("statementId", EntityCondition.IN, statementIds as List)
                .condition("period", period)
                .condition("firstFoundDate", EntityCondition.LESS_THAN_EQUAL_TO, dayDate)
                .condition(cf.makeCondition([
                        cf.makeCondition("stateEnumId", EntityCondition.EQUALS, OPEN),
                        cf.makeCondition("closedStamp", EntityCondition.GREATER_THAN_EQUAL_TO, closedFloor)],
                        EntityCondition.OR))
                .list()*.getMap()
        Map<String, List<Map>> eventsByFinding = [:].withDefault { [] }
        rows*.findingId.collate(500).each { List chunk ->
            tenantRows(ec, "darpan.audit.AuditFindingEvent", tenantId).condition("findingId", "in", chunk)
                    .condition("byTypeEnumId", EntityCondition.NOT_EQUAL, USER_ACTOR)
                    .orderBy(["eventStamp", "eventSeqId"]).list().each { eventsByFinding[it.findingId as String] << it.getMap() }
        }
        List<Map> result = []
        rows.each { Map row ->
            List<Map> events = eventsByFinding[row.findingId as String]
            List<Map> upToDay = events.findAll { (it.eventDate as Date).toLocalDate() <= day }
            boolean later = events.any { (it.eventDate as Date).toLocalDate() > day }
            String state
            if (!later) state = row.stateEnumId
            else if (!upToDay) return
            else state = upToDay.last().eventTypeEnumId == "AUDFE_FIXED" ? FIXED : OPEN
            if (state == FIXED) {
                Map fixedEvent = upToDay.findAll { it.eventTypeEnumId == "AUDFE_FIXED" }.with { it ? it.last() : null }
                if (fixedEvent == null || (fixedEvent.eventDate as Date).toLocalDate() != day) return
            }
            result << [row: row, state: state]
        }
        return result
    }

    private static Map countsOf(List<Map> entries, LocalDate day, Map levels) {
        // DAR-BE-361: an acknowledged row is counted apart from open (a reminded one is open); 0 on a past morning.
        List<Map> open = entries.findAll { it.state == OPEN && !acknowledged(it) }
        // DAR-BE-348: every open unit at its level, the 50-row cap aside, so the levels always add up to open.
        Map<String, Integer> byPriority = AuditPriority.zeroCounts()
        open.each { Map unit ->
            String level = unitPriority(unit, levels.statementLevels as Map, levels.overrides as Map).level
            byPriority[level] = byPriority[level] + 1
        }
        return [open        : open.size(),
                acknowledged: entries.count { it.state == OPEN && acknowledged(it) } as int,
                newToday    : entries.count { it.state != FIXED && (it.row.firstFoundDate as Date).toLocalDate() == day } as int,
                fixedToday  : entries.count { it.state == FIXED } as int,
                byPriority  : byPriority]
    }

    private static Map sumCounts(List<Map> counts) {
        Map<String, Integer> sum = [open: 0, acknowledged: 0, newToday: 0, fixedToday: 0]
        Map<String, Integer> byPriority = AuditPriority.zeroCounts()
        counts.each { Map c ->
            sum.keySet().each { String k -> sum[k] = sum[k] + (c[k] as int) }
            byPriority.keySet().each { String k -> byPriority[k] = byPriority[k] + (((Map) c.byPriority)[k] as int) }
        }
        return sum + [byPriority: byPriority]
    }

    // ---------------------------------------------------------------------------------------------------------
    // DAR-BE-348. Priority: computed on assembly, never stored on the finding
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The tenant's levels, read once: statementLevels (statementId -> its own level's code; absent when unset) and
     * overrides (statementId -> conclusionEnumId -> code). Only AuditPriority codes: a level that is not one reads as
     * unset (P2), so it can never rank above P0 or leave a unit out of byPriority.
     */
    static Map priorityLevels(def ec, String tenantId, Collection<Map> statements) {
        Map<String, String> codes = ec.entity.find("moqui.basic.Enumeration").condition("enumTypeId", "AuditPriority").useCache(true).list()
                .findAll { it.enumCode in AuditPriority.LEVELS }.collectEntries { [(it.enumId): it.enumCode] }
        Map<String, String> statementLevels = statements.findAll { codes.get(it.priorityEnumId) }
                .collectEntries { [(it.statementId as String): codes.get(it.priorityEnumId)] }
        Map<String, Map<String, String>> overrides = [:]
        tenantRows(ec, "darpan.audit.AuditReasonPriority", tenantId).list().each {
            String code = codes.get(it.priorityEnumId)
            if (code) overrides.computeIfAbsent(it.statementId as String) { [:] }.put(it.conclusionEnumId as String, code)
        }
        return [statementLevels: statementLevels, overrides: overrides]
    }

    /**
     * A unit's [level, source, conclusionEnumId, line] under its own statement (a waiting unit's is its own, not the unit
     * it waits under): a lone finding's reason or statement level; an order's most urgent open line (every line when none
     * is open), `line` being that winning entry. A unit may be a bare [row, state].
     */
    static Map unitPriority(Map unit, Map<String, String> statementLevels, Map<String, Map<String, String>> overrides) {
        List<Map> lines = (unit.entries ?: [[row: unit.row, state: unit.state]]) as List<Map>
        List<Map> open = lines.findAll { it.state == OPEN }
        List<Map> considered = open ?: lines
        String statementId = lines[0].row.statementId as String
        Map p = AuditPriority.resolve(statementLevels?.get(statementId), overrides?.get(statementId),
                considered.collect { it.row.conclusionEnumId as String })
        Map winner = considered[p.index as int]
        return p + [line: winner]
    }

    /** A unit's level fields; `causes` names the winning reason, `priorityLine` is the order's winning line (orders only). */
    static Map priorityOf(Map unit, Map levels, Map<String, Map> causes = [:]) {
        Map p = unitPriority(unit, levels.statementLevels as Map, levels.overrides as Map)
        Map out = [priority: p.level, prioritySource: p.source,
                   priorityReason: p.source == "reason" ? causes.get(p.conclusionEnumId as String)?.label : null]
        if (unit.order) out.priorityLine = (p.line as Map)?.row?.findingId
        return out
    }

    // ---------------------------------------------------------------------------------------------------------
    // DAR-UI-075. A unit as the report lists it (the grouping itself is AuditOrderUnits)
    // ---------------------------------------------------------------------------------------------------------

    /** DAR-BE-250. The findings waiting on a counted one, as it lists them: each with its statement, id, headline, cause and
     *  its own statement's level (DAR-BE-348). */
    private static List<Map> waitingJson(Map unit, LocalDate day, Map<String, Map> recordsByFinding, Map<String, Map> causes,
                                         Map<String, String> codes, Map<String, String> statementCodeById, Map levels) {
        return ((unit.waitingUnits ?: []) as List<Map>).collect { Map w ->
            Map f = unitFinding(w, day, recordsByFinding, causes, codes, levels)
            [statement: statementCodeById[w.statementId as String], id: f.id, headline: f.headline, cause: f.cause, item: AuditOrderUnits.itemOf(f),
             priority: f.priority, prioritySource: f.prioritySource, priorityReason: f.priorityReason]
        }
    }

    /** Each conclusion's words and, when it is held by another line's cause (relatedEnumId), that cause. */
    static Map<String, Map> conclusionsOf(def ec, List<String> ids) {
        List<String> wanted = (ids ?: []).findAll() .unique() as List<String>
        if (!wanted) return [:]
        return ec.entity.find("moqui.basic.Enumeration").condition("enumId", EntityCondition.IN, wanted).useCache(true).list()
                .collectEntries { [(it.enumId): [label: it.description, heldBy: it.relatedEnumId ?: null]] }
    }

    /**
     * A unit as the report lists it: a lone line as before; an order as one finding whose lines sit under it, each with
     * its cause. The stuck lines are those whose cause is their own while another line's is held by one (DAR-BE-118's
     * "another line of its order is backordered"); the order says which line holds it up and how many wait on it.
     */
    private static Map unitFinding(Map unit, LocalDate day, Map<String, Map> recordsByFinding, Map<String, Map> causes,
                                   Map<String, String> codes, Map levels) {
        Closure<Map> line = { Map e ->
            // get(), not [ ]: most lines have no conclusion, and map[null] dispatches to getProperty(null) and throws.
            Map cause = e.row.conclusionEnumId ? causes.get(e.row.conclusionEnumId as String) : null
            briefFinding(e, day, recordsByFinding[e.row.findingId as String] ?: [:], codes) +
                    [cause: cause?.label ?: null, held: cause?.heldBy != null] + priorityOf([entries: [e]], levels, causes)
        }
        // DAR-BE-125: a lone finding keeps its cause in the catalog's words, which the board heads its findings by.
        // DAR-BE-361: the row's acknowledgement (null when it reads open, and on a past morning).
        Map acknowledgement = [acknowledgement: (unit.ack as Map)?.summary]
        if (!unit.order) return line((unit.entries as List<Map>)[0]).findAll { k, v -> k != "held" } + acknowledgement
        String order = unit.order as String
        List<Map> lines = (unit.entries as List<Map>).collect(line)
        List<Map> context = AuditOrderUnits.takeOrderLineRecords(lines)
        List<Map> stuck = lines.any { it.held } ? lines.findAll { !it.held && it.cause } : []
        List<String> own = lines*.cause.findAll().unique() as List<String>
        String cause = stuck ? stuck[0].cause : (own.size() == 1 ? own[0] : null)
        List<String> numbers = stuck.collect { Map l -> AuditOrderUnits.lineNumber(order, l) }
        String named = numbers.size() == 1 ? "line ${numbers[0]}" :
                "lines ${numbers.size() > 1 ? numbers[0..-2].join(', ') + ' and ' + numbers[-1] : ''}"
        String headline = stuck ?
                "Order ${order}: ${named} ${AuditOrderUnits.lowerFirst(cause)}, holding ${lines.size() - stuck.size()} more line${lines.size() - stuck.size() == 1 ? '' : 's'}" :
                lines.size() == 1 ? "Order ${order}: ${AuditOrderUnits.oneLine(order, lines[0])}" :
                "Order ${order}: ${lines.size()} lines${cause ? ', ' + AuditOrderUnits.lowerFirst(cause) : ''}"
        Map first = lines[0]
        return [id        : order,
                kind      : "order",
                order     : order,
                headline  : headline.toString(),
                state     : codes[unit.state as String],
                firstFound: first.firstFound,
                ageDays   : first.ageDays,
                records   : AuditOrderUnits.mergedRecords(lines*.records as List<Map>),
                difference: null,
                cause     : cause,
                stuck     : stuck*.id,
                lines     : lines,
                orderLines: AuditOrderUnits.orderLines(order, lines, context),
                next      : "darpan findings show ${(stuck ? stuck[0] : first).id}".toString()] + priorityOf(unit, levels, causes) + acknowledgement
    }

    /**
     * The tenant's findings for one audit and period, one statement or every one in catalog order, as the report lists
     * them: one per order, each with its statement and category. Only open findings, as of today. The board's "N more",
     * filters and export read these.
     */
    static List<Map> findingsByOrder(def ec, String tenantId, String auditId, String statementCode, String period) {
        return findingsByOrder(ec, tenantId, auditId, statementCode, period, null)
    }

    /** DAR-BE-348: with a priority, only the units at that level, filtered after resolution so none is lost to a cap. */
    static List<Map> findingsByOrder(def ec, String tenantId, String auditId, String statementCode, String period, String priority) {
        return findingsByOrder(ec, tenantId, auditId, statementCode, period, priority, null)
    }

    /**
     * DAR-BE-361: ackState "open" (a reminded row is open) or "acknowledged" picks the rows by today's acknowledgements;
     * blank or "all" keeps every open row, as before acknowledgements (the run update's groups and CSVs read it so).
     * Each row carries its acknowledgement (null when it reads open).
     */
    static List<Map> findingsByOrder(def ec, String tenantId, String auditId, String statementCode, String period, String priority,
                                     String ackState) {
        String wanted = ackState?.trim() ?: "all"
        Map<String, String> codes = enumCodes(ec)
        // Only the asked statement's counted findings are returned, each with those waiting on it.
        Map open = openStepUnits(ec, tenantId, auditId, period)
        List<Map> statements = open.statements as List<Map>
        Map<String, List<Map>> entriesByStatement = open.entriesByStatement as Map<String, List<Map>>
        Map<String, Map> records = open.records as Map<String, Map>
        Map<String, Map> split = open.split as Map<String, Map>
        Map<String, String> statementCodeById = statements.collectEntries { [(it.statementId as String): it.statementCode as String] }
        ZoneId zone = (loadTenant(ec, tenantId)?.zone ?: ZoneId.of("UTC")) as ZoneId
        LocalDate today = LocalDate.now(zone)
        Map<String, Map> causes = conclusionsOf(ec, entriesByStatement.values().flatten()*.row*.conclusionEnumId as List<String>)
        Map levels = priorityLevels(ec, tenantId, statements)
        List<Map> asked = statements.findAll { !statementCode || it.statementCode == statementCode }
        markAcknowledgements(ec, tenantId, asked.collectMany { (split[it.statementId as String].counted as List<Map>) } as List<Map>,
                entriesByStatement.values().flatten()*.row*.findingId as List<String>, today, zone)
        return asked.collectMany { Map st ->
            (split[st.statementId as String].counted as List<Map>)
                    .findAll { Map unit -> wanted == "all" || (wanted == "acknowledged") == acknowledged(unit) }.collect { Map unit ->
                unitFinding(unit, today, records, causes, codes, levels) + [statement: st.statementCode, category: codes[st.categoryEnumId as String]] +
                        (unit.waitingUnits ? [waiting: waitingJson(unit, today, records, causes, codes, statementCodeById, levels)] : [:])
            }
        }.findAll { !priority || it.priority == priority } as List<Map>
    }

    /**
     * Today's open findings of one audit (one period, or every one when blank) as the report groups them: [statements
     * (active, in story order), entriesByStatement, records, split (stepUnits)]. DAR-BE-250: every active statement is
     * read, even for one statement's list, to know which orders an earlier step already reports.
     */
    private static Map openStepUnits(def ec, String tenantId, String auditId, String period) {
        List<Map> statements = activeStatements(ec, tenantId, auditId)
        Map<String, List<Map>> entriesByStatement = statements.collectEntries { Map st ->
            def find = tenantRows(ec, "darpan.audit.AuditFinding", tenantId).condition("statementId", st.statementId)
                    .condition("stateEnumId", OPEN)
            if (period) find.condition("period", period)
            [(st.statementId as String): AuditOrderUnits.sortOldestFirst(find.list()*.getMap().collect { Map row -> [row: row, state: row.stateEnumId] })]
        }
        Map<String, Map> records = recordsFor(ec, tenantId, entriesByStatement.values().flatten()*.row*.findingId as List<String>)
        return [statements: statements, entriesByStatement: entriesByStatement, records: records,
                split: AuditOrderUnits.stepUnits(statements, entriesByStatement, records)]
    }

    /**
     * DAR-BE-361. The findings one Acknowledge covers (the report row holding findingId): the counted order unit that row
     * is, its open lines plus the open lines of the later-step units waiting under it. Any finding of the row resolves to
     * the same set: a waiting line resolves to the row it is listed under. A waiting unit with no row to wait under, or a
     * finding that is not an order line, covers its own unit; one not open today covers itself alone. Empty when the
     * tenant does not own the finding.
     */
    static List<String> unitMemberIds(def ec, String tenantId, String findingId) {
        Map f = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.AuditFinding", "findingId", findingId, tenantId, WHY)?.getMap()
        if (f == null) return []
        Map statement = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.AuditStatement", "statementId",
                f.statementId as String, tenantId, WHY)?.getMap()
        if (f.stateEnumId != OPEN || statement == null) return [findingId]
        Map<String, Map> split = openStepUnits(ec, tenantId, statement.auditId as String, f.period as String).split as Map<String, Map>
        // The row is a counted unit: the line's own, or the one it waits under (holders carry their waiting units).
        Map row = split.values().collectMany { it.counted as List<Map> }.find { Map u -> AuditOrderUnits.rowLines(u).any { it.row.findingId == findingId } }
        // A waiting unit whose order has no open counted unit is listed nowhere else: it covers itself.
        Map unit = row ?: ((split[f.statementId as String]?.waiting ?: []) as List<Map>).find { Map u ->
            (u.entries as List<Map>).any { it.row.findingId == findingId } }
        if (unit == null) return [findingId]
        return AuditOrderUnits.openRowIds(unit)
    }

    private static boolean acknowledged(Map unit) {
        return (unit.ack as Map)?.state == "acknowledged"
    }

    /**
     * DAR-BE-361. Marks each open row with its acknowledgement as of today: unit.ack = [state, summary]. The rows and
     * the last reopen of these findings are read once, for findingIds only (the ledger's open findings), never per row.
     */
    private static void markAcknowledgements(def ec, String tenantId, List<Map> units, List<String> findingIds, LocalDate today, ZoneId zone) {
        Map acks = acknowledgementsOf(ec, tenantId, findingIds)
        if (!(acks.rows as Map)) return
        units.findAll { it.state == OPEN }.each { Map unit -> unit.ack = acknowledgementOf(AuditOrderUnits.openRowIds(unit), acks, today, zone) }
    }

    /**
     * DAR-BE-361. [rows: findingId -> acknowledgement row, reopens: findingId -> its last AUDFE_REOPENED stamp, labels:
     * userId -> name] for these findings: one read of the rows, and of reopen events and accounts only when there are rows.
     */
    static Map acknowledgementsOf(def ec, String tenantId, Collection<String> findingIds) {
        Map<String, Map> rows = [:]
        ((findingIds ?: []) as List<String>).findAll().unique().collate(500).each { List<String> chunk ->
            tenantRows(ec, "darpan.audit.AuditFindingAcknowledgement", tenantId).condition("findingId", EntityCondition.IN, chunk)
                    .list().each { rows[it.findingId as String] = it.getMap() }
        }
        Map<String, Timestamp> reopens = [:]
        Map<String, String> labels = [:]
        if (!rows) return [rows: rows, reopens: reopens, labels: labels]
        (rows.keySet() as List<String>).collate(500).each { List<String> chunk ->
            tenantRows(ec, "darpan.audit.AuditFindingEvent", tenantId).condition("findingId", EntityCondition.IN, chunk)
                    .condition("eventTypeEnumId", "AUDFE_REOPENED").list().each {
                Timestamp at = it.eventStamp as Timestamp
                Timestamp seen = reopens[it.findingId as String]
                if (at != null && (seen == null || at.after(seen))) reopens[it.findingId as String] = at
            }
        }
        List<String> userIds = rows.values()*.acknowledgedByUserId.findAll().unique() as List<String>
        if (userIds) {
            TenantScopedFinder.findGlobalUnscoped(ec, "moqui.security.UserAccount", "audit assembler: who acknowledged a finding, by name")
                    .condition("userId", EntityCondition.IN, userIds).useCache(false).list()
                    .each { labels[it.userId as String] = (it.userFullName ?: it.username) as String }
        }
        return [rows: rows, reopens: reopens, labels: labels]
    }

    /**
     * DAR-BE-361. A row's state (AcknowledgementSupport.unitState over its open findings) and its acknowledgement block:
     * the first covered finding's row, its time in the tenant's zone, reminded when past its remind day. The block is
     * null when the row reads open (no row, a line broken since, or an acknowledgement a reopen voided), so the row
     * offers Acknowledge again.
     */
    static Map acknowledgementOf(List<String> memberIds, Map acks, LocalDate today, ZoneId zone) {
        Map<String, Map> rows = acks.rows as Map<String, Map>
        String state = AcknowledgementSupport.unitState(memberIds, rows, acks.reopens as Map<String, Timestamp>, today)
        if (state == "open") return [state: state, summary: null]
        Map summary = AcknowledgementSupport.summary(rows[memberIds[0]], state, acks.labels as Map<String, String>)
        summary.at = isoDateTime(summary.at as Timestamp, zone)
        return [state: state, summary: summary]
    }

    static Map briefFinding(Map entry, LocalDate day, Map records, Map<String, String> codes) {
        Map row = entry.row as Map
        return [id        : row.findingId,
                headline  : row.headline,
                state     : codes[entry.state as String],
                firstFound: isoDate(row.firstFoundDate as Date),
                ageDays   : days(row.firstFoundDate as Date, day),
                records   : records,
                difference: AuditRecordFacts.money(row.differenceAmount as BigDecimal, row.currencyUomId as String),
                next      : "darpan findings show ${row.findingId}".toString()]
    }

    /**
     * DAR-BE-371/372. The systems each check speaks about, as report keys (OMS, NetSuite, Shopify), whether or not it ran:
     * the ones its own label names, in its words' order ("Orders cancelled in Shopify and not cancelled in HotWax"), else
     * the ones its statement names, else its automation's sources (FILE_1 then FILE_2). Words before sources because a
     * one-sided check reads one system and judges another (R13 reads Shopify and asks HotWax). Each system once.
     */
    private static Map<String, List<String>> checkSystems(def ec, String tenantId, List<Map> checks, Map<String, String> statementTextByCheck = [:]) {
        Map<String, List<String>> out = [:]
        List<Map> unnamed = []
        checks.findAll { it }.each { Map check ->
            List<String> named = systemsNamed(check.label as String) ?: systemsNamed(statementTextByCheck[check.checkId as String])
            if (named) out[check.checkId as String] = named
            else unnamed << check
        }
        List<String> automationIds = unnamed.collect { it.automationId as String }.findAll { it }.unique()
        if (!automationIds) return out
        Map<String, List<Map>> byAutomation = tenantRows(ec, "darpan.reconciliation.ReconciliationAutomationSource", tenantId)
                .condition("automationId", "in", automationIds).orderBy(["automationId", "fileSide"]).list()*.getMap()
                .groupBy { it.automationId as String }
        unnamed.findAll { it.automationId }.each { Map check ->
            out[check.checkId as String] = (byAutomation[check.automationId as String] ?: []).collect { Map source ->
                String enumId = source.systemEnumId as String
                enumId ? AuditRecordFacts.systemKey(AuditAutomationSupport.rootSystem(enumId)) : null
            }.findAll { it }.unique()
        }
        return out
    }

    /** The words a person uses for each system, to the report key. */
    private static final Map<String, String> SYSTEM_WORDS = [hotwax: "OMS", oms: "OMS", netsuite: "NetSuite", shopify: "Shopify"].asImmutable()

    /** DAR-BE-372. The systems a sentence names, in the order it names them, each once; empty when it names none. */
    static List<String> systemsNamed(String text) {
        if (!text) return []
        List<String> found = []
        (text =~ /(?i)\b(hotwax|oms|netsuite|shopify)\b/).each { List m -> found << SYSTEM_WORDS[(m[1] as String).toLowerCase()] }
        return found.unique()
    }

    private static Map compared(List<Map> reads) {
        Map<String, Object> out = new LinkedHashMap<>()
        reads.groupBy { AuditRecordFacts.systemKey(it.systemEnumId as String) }.each { String key, List<Map> rs ->
            List counted = rs*.recordCount.findAll { it != null }
            out[key] = counted ? (counted.sum { it as long } as long) : null
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------------------
    // Shared shapes and reads
    // ---------------------------------------------------------------------------------------------------------

    static Map<String, Map> recordsFor(def ec, String tenantId, List<String> findingIds) {
        if (!findingIds) return [:]
        // DAR-UI-075: a statement's every line now, so in chunks rather than one IN of every id.
        List<Map> rows = findingIds.collate(500).collectMany { List<String> chunk ->
            tenantRows(ec, "darpan.audit.AuditFindingRecord", tenantId).condition("findingId", "in", chunk)
                    .orderBy(["findingId", "recordSeqId"]).list()*.getMap()
        }
        ZoneId zone = (loadTenant(ec, tenantId)?.zone ?: ZoneId.of("UTC")) as ZoneId
        return rows.groupBy { it.findingId as String }.collectEntries { k, v -> [(k): AuditRecordFacts.recordsJson(v, false, zone)] }
    }

    static Map loadTenant(def ec, String tenantId) {
        if (!tenantId) return null
        def group = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.security.UserGroup", WHY)
                .condition("userGroupId", tenantId).useCache(false).one()
        if (group == null) return null
        return [name: group.description ?: tenantId, zone: AuditReportGraph.tenantZone(ec, tenantId)]
    }

    /**
     * Statements in story order: sequenceNum (none last), then code, then id. The one order the report, the by-order
     * list and the findings page share (DAR-OPS-029 S10: the report sorted in the database, where NULL comes first).
     */
    static final Comparator<Map> STATEMENT_ORDER = { Map a, Map b ->
        ((a.sequenceNum ?: Long.MAX_VALUE) as Long) <=> ((b.sequenceNum ?: Long.MAX_VALUE) as Long) ?:
                (a.statementCode as String) <=> (b.statementCode as String) ?: (a.statementId as String) <=> (b.statementId as String)
    } as Comparator<Map>

    /** One audit's statements that are on (DAR-UI-063: a statement turned off is not part of the report), in STATEMENT_ORDER. */
    private static List<Map> activeStatements(def ec, String tenantId, String auditId) {
        return tenantRows(ec, "darpan.audit.AuditStatement", tenantId).condition("auditId", auditId).list()*.getMap()
                .findAll { (it.isActive ?: "Y") != "N" }.sort(false, STATEMENT_ORDER)
    }

    /** A tenant's rows of one entity, with the explicit companyUserGroupId condition every audit read carries; why names the reader. */
    static def tenantRows(def ec, String entityName, String tenantId, String why = WHY) {
        return TenantScopedFinder.findGlobalUnscoped(ec, entityName, why).condition("companyUserGroupId", tenantId).useCache(false)
    }

    /** enumId -> enumCode for the audit-run vocabulary (the JSON emits the code, never the id). */
    static Map<String, String> enumCodes(def ec) {
        return ec.entity.find("moqui.basic.Enumeration").condition("enumTypeId", "in", ENUM_TYPES).useCache(true).list()
                .collectEntries { [(it.enumId): it.enumCode] }
    }

    static String isoDate(Date d) { return d?.toLocalDate()?.toString() }

    static String isoDateTime(Timestamp ts, ZoneId zone) {
        return ts == null ? null : ts.toInstant().atZone(zone).format(DATE_TIME)
    }

    static int days(Date from, LocalDate to) { return ChronoUnit.DAYS.between(from.toLocalDate(), to) as int }

    private static String listPhrase(List<String> items) {
        if (items.size() <= 1) return items.join("")
        return "${items[0..-2].join(', ')} and ${items[-1]}".toString()
    }

    // ---------------------------------------------------------------------------------------------------------
    // DAR-OPS-029 S10: delegates for callers outside S10 until S99 repoints them to the class that owns each one.
    // ---------------------------------------------------------------------------------------------------------

    static final Map<String, List<String>> CARD_FIELDS = AuditRecordFacts.CARD_FIELDS
    static String systemKey(String rootEnumId) { AuditRecordFacts.systemKey(rootEnumId) }
    static String displayKey(String key) { AuditRecordFacts.displayKey(key) }
    static Map recordsJson(List<Map> rows, boolean withData = false, ZoneId zone = ZoneId.of("UTC")) { AuditRecordFacts.recordsJson(rows, withData, zone) }
    static Map recordFacts(Map data, String recordType, ZoneId zone = ZoneId.of("UTC")) { AuditRecordFacts.recordFacts(data, recordType, zone) }
    static List<LocalDate> periodBounds(String period, String periodCode) { AuditPeriods.periodBounds(period, periodCode) }
    static String periodOf(LocalDate day, String periodCode) { AuditPeriods.periodOf(day, periodCode) }
    static Map periodWindow(def ec, Map audit, String period, LocalDate day) { AuditPeriods.periodWindow(ec, audit, period, day) }
    static LocalDate recordableFrom(LocalDate day, String periodCode) { AuditPeriods.recordableFrom(day, periodCode) }
    static Map resolveCheckRun(def ec, String tenantId, List auditIds, LocalDate day, String period) { AuditPeriods.resolveCheckRun(ec, tenantId, auditIds, day, period) }
    static String lineItemId(Map line) { AuditOrderUnits.lineItemId(line) }
    static List<Map> orderLines(String order, List<Map> lines, List<Map> context) { AuditOrderUnits.orderLines(order, lines, context) }
    static Map<String, Object> assembleFinding(def ec, String tenantId, String findingId) { AuditFindingDocument.assembleFinding(ec, tenantId, findingId) }
    static String causeFixOf(def ec, String tenantId, Map check, String conclusionEnumId, List<Map> evidenceChecks, Map<String, String> values,
                             Map<String, String> recordFields = [:]) { AuditFindingDocument.causeFixOf(ec, tenantId, check, conclusionEnumId, evidenceChecks, values, recordFields) }
    static String fillTemplate(String template, Map<String, String> values) { AuditFindingDocument.fillTemplate(template, values) }
}
