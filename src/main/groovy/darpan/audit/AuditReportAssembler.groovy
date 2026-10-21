package darpan.audit

import darpan.facade.common.TenantScopedFinder
import org.moqui.entity.EntityCondition

import java.math.RoundingMode
import java.sql.Date
import java.sql.Timestamp
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
 * request ran. The only clock value is report.generatedAt, which the caller can pin.
 */
class AuditReportAssembler {

    static final String REPORT_SCHEMA = "darpan.audit.report/v1"
    static final String FINDING_SCHEMA = "darpan.audit.finding/v1"

    static final Map<String, String> SYSTEM_KEYS = [OMS: "OMS", NETSUITE: "NetSuite", SHOPIFY: "Shopify"].asImmutable()

    static final String OPEN = "AUDFS_OPEN"
    static final String PENDING = "AUDFS_PENDING"
    static final String FIXED = "AUDFS_FIXED"
    static final String RAN = "AUDCS_RAN"
    static final String PARTIAL = "AUDCS_PARTIAL"
    static final String NOT_RUN = "AUDCS_NOT_RUN"
    static final String READ_COMPLETE = "AUDSR_COMPLETE"

    /** Worst last: a statement or system takes the worst status among its checks or reads. */
    static final List<String> CHECK_STATUS_ORDER = [RAN, PARTIAL, NOT_RUN].asImmutable()
    static final List<String> READ_STATUS_ORDER = [READ_COMPLETE, "AUDSR_PARTIAL", "AUDSR_FAILED"].asImmutable()

    static final int FINDINGS_CAP = 50
    static final String NO_CHECK_REASON = "No check is configured for this statement yet."
    static final String VERIFY_BY = "Darpan's next scheduled check"
    static final String DARPAN_CHANGES = "Nothing. Darpan reads NetSuite and the OMS; it never writes to them."
    static final String ACCESS_NOTE = "This API has no mutations. Fix the record in its own system; Darpan closes the finding at its next scheduled check."

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
    private static final DateTimeFormatter DATE_TIME_MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmXXX")
    private static final List<String> ENUM_TYPES = ["AuditCategory", "AuditStatementWeight", "AuditFindingState",
            "AuditCheckStatus", "AuditSourceRead", "AuditFindingEvent", "AuditActorType", "AuditPeriod"].asImmutable()
    private static final String WHY = "audit assembler: explicit companyUserGroupId condition applied"

    /** The JSON key for a ROOT system enum (OMS, NETSUITE, SHOPIFY); an unmapped root uses its enumId. */
    static String systemKey(String rootEnumId) {
        return SYSTEM_KEYS[rootEnumId] ?: rootEnumId
    }

    // ---------------------------------------------------------------------------------------------------------
    // The report
    // ---------------------------------------------------------------------------------------------------------

    /** The report for one audit on one morning, or null when the tenant or the audit (in that tenant) does not exist. */
    static Map<String, Object> assembleReport(def ec, String tenantId, String auditId, Date reportDate, Timestamp generatedStamp) {
        Map tenant = loadTenant(ec, tenantId)
        if (tenant == null) return null
        Map audit = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.Audit", "auditId", auditId, tenantId, WHY)?.getMap()
        if (audit == null) return null
        ZoneId zone = tenant.zone as ZoneId
        LocalDate day = reportDate.toLocalDate()
        Map<String, String> codes = enumCodes(ec)

        List<Map> categories = ec.entity.find("moqui.basic.Enumeration").condition("enumTypeId", "AuditCategory")
                .orderBy("sequenceNum").useCache(true).list()*.getMap()
        List<Map> statements = tenantRows(ec, "darpan.audit.AuditStatement", tenantId).condition("auditId", auditId)
                .orderBy(["sequenceNum", "statementCode"]).list()*.getMap()
        Set<String> statementIds = statements*.statementId.toSet() as Set<String>
        Map<String, Map> checksById = tenantRows(ec, "darpan.audit.AuditCheck", tenantId).list()
                .collectEntries { [(it.checkId): it.getMap()] }
        List<Map> links = tenantRows(ec, "darpan.audit.AuditStatementCheck", tenantId)
                .orderBy(["sequenceNum", "checkId"]).list()*.getMap()
        Map<String, List<String>> checkIdsByStatement = [:].withDefault { [] }
        links.each { if (checksById[it.checkId] && statementIds.contains(it.statementId)) checkIdsByStatement[it.statementId as String] << (it.checkId as String) }
        Set<String> configuredCheckIds = checkIdsByStatement.values().flatten().toSet() as Set<String>

        // This morning's runs and their reads.
        Map<String, Map> runByCheck = tenantRows(ec, "darpan.audit.AuditCheckRun", tenantId)
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
            checkReason[checkId] = run ? (run.reason as String) : "The check did not run on the morning of ${day}.".toString()
        }

        // The ledger as of the end of the report date.
        List<Map> ledger = findingsAsOf(ec, tenantId, statementIds, day, zone, checksById)
        Map<String, List<Map>> ledgerByStatement = ledger.groupBy { it.row.statementId as String }

        Map<String, Map> statementJsonById = [:]
        Map<String, String> statementCodeById = statements.collectEntries { [(it.statementId): it.statementCode] }
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
                 graceDays: (check.graceDays ?: 0) as int,
                 compared : status == NOT_RUN || run == null ? null : compared(readsByRun[run.checkRunId as String])]
            }
            String worst = stChecks ? stChecks.collect { checkStatus[it] }.max { CHECK_STATUS_ORDER.indexOf(it) } : NOT_RUN
            String reason = null
            if (!stChecks) reason = NO_CHECK_REASON
            else if (worst != RAN) {
                List<String> bad = stChecks.findAll { checkStatus[it] != RAN }
                reason = bad.size() == 1 && stChecks.size() == 1 ? checkReason[bad[0]] :
                        bad.collect { "${checksById[it].checkCode}: ${checkReason[it]}" }.join(" ")
            }

            List<Map> entries = sortOldestFirst(ledgerByStatement[st.statementId as String] ?: [])
            Map counts = countsOf(entries, day)
            List<Map> listed = entries.take(FINDINGS_CAP)
            Map<String, Map> recordsByFinding = recordsFor(ec, tenantId, listed*.row*.findingId as List<String>)
            statementJsonById[st.statementId as String] = [
                    id             : st.statementCode,
                    text           : st.statementText,
                    weight         : codes[st.weightEnumId as String],
                    status         : codes[worst],
                    reason         : reason,
                    checks         : checkJson,
                    counts         : counts,
                    findings       : listed.collect { Map e -> briefFinding(e, day, recordsByFinding[e.row.findingId as String] ?: [:], codes) },
                    findingsOmitted: entries.size() - listed.size()]
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

        Map oldest = ledger.findAll { it.state == OPEN }.with { sortOldestFirst(it) }.find()
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

        List<Date> checkedDays = runByCheck.values()*.checkedDay.findAll { it != null } as List<Date>
        LocalDate checkedDay = checkedDays ? (checkedDays.countBy { it }.max { it.value }.key as Date).toLocalDate() : day.minusDays(1)
        String name = tenant.name as String
        List<String> allCodes = statements*.statementCode as List<String>
        String range = allCodes.size() > 1 ? " ${allCodes.first()} to ${allCodes.last()}" : (allCodes ? " ${allCodes[0]}" : "")
        String periodCode = codes[audit.periodEnumId as String]

        return [schema    : REPORT_SCHEMA,
                about     : "${audit.auditName} audit for ${name}. Each finding is one record that breaks one of the audit's statements${range}.".toString(),
                report    : [tenant     : [id: tenantId, name: name, timeZone: zone.id],
                             audit      : [id: audit.auditCode, name: audit.auditName, period: periodCode],
                             date       : day.toString(),
                             checkedDay : checkedDay.toString(),
                             period     : periodOf(checkedDay, periodCode),
                             generatedAt: isoDateTime(generatedStamp, zone)],
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
                String key = systemKey(read.systemEnumId as String)
                readsBySystem.computeIfAbsent(key) { [] } << read
                if (read.statusEnumId != READ_COMPLETE) badChecksBySystem[key] << checkId
            }
        }
        Map<String, Object> sources = new LinkedHashMap<>()
        readsBySystem.each { String key, List<Map> reads ->
            String worst = reads*.statusEnumId.max { READ_STATUS_ORDER.indexOf(it) } as String
            List counted = reads*.recordCount.findAll { it != null }
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
        boolean complete = partial == 0 && notRun == 0

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
     * date"), with pending vs open decided by the age at that sighting, as record#CheckFindings decided it.
     * Fixed findings are kept only when they became fixed on {@code day}.
     */
    private static List<Map> findingsAsOf(def ec, String tenantId, Set<String> statementIds, LocalDate day, ZoneId zone,
                                          Map<String, Map> checksById) {
        if (!statementIds) return []
        Date dayDate = Date.valueOf(day)
        // A day of margin on the closed stamp; the events decide.
        Timestamp closedFloor = new Timestamp(day.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        def cf = ec.entity.conditionFactory
        List<Map> rows = tenantRows(ec, "darpan.audit.AuditFinding", tenantId)
                .condition("statementId", EntityCondition.IN, statementIds as List)
                .condition("firstFoundDate", EntityCondition.LESS_THAN_EQUAL_TO, dayDate)
                .condition(cf.makeCondition([
                        cf.makeCondition("stateEnumId", EntityCondition.IN, [OPEN, PENDING]),
                        cf.makeCondition("closedStamp", EntityCondition.GREATER_THAN_EQUAL_TO, closedFloor)],
                        EntityCondition.OR))
                .list()*.getMap()
        Map<String, List<Map>> eventsByFinding = [:].withDefault { [] }
        rows*.findingId.collate(500).each { List chunk ->
            tenantRows(ec, "darpan.audit.AuditFindingEvent", tenantId).condition("findingId", "in", chunk)
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
            else if (upToDay.last().eventTypeEnumId == "AUDFE_FIXED") state = FIXED
            else {
                int grace = (checksById[row.checkId as String]?.graceDays ?: 0) as int
                state = days(row.firstFoundDate as Date, (upToDay.last().eventDate as Date).toLocalDate()) < grace ? PENDING : OPEN
            }
            if (state == FIXED) {
                Map fixedEvent = upToDay.findAll { it.eventTypeEnumId == "AUDFE_FIXED" }.with { it ? it.last() : null }
                if (fixedEvent == null || (fixedEvent.eventDate as Date).toLocalDate() != day) return
            }
            result << [row: row, state: state]
        }
        return result
    }

    private static Map countsOf(List<Map> entries, LocalDate day) {
        return [open      : entries.count { it.state == OPEN } as int,
                pending   : entries.count { it.state == PENDING } as int,
                newToday  : entries.count { it.state != FIXED && (it.row.firstFoundDate as Date).toLocalDate() == day } as int,
                fixedToday: entries.count { it.state == FIXED } as int]
    }

    private static Map sumCounts(List<Map> counts) {
        Map<String, Integer> sum = [open: 0, pending: 0, newToday: 0, fixedToday: 0]
        counts.each { Map c -> sum.keySet().each { String k -> sum[k] = sum[k] + (c[k] as int) } }
        return sum
    }

    private static Map briefFinding(Map entry, LocalDate day, Map records, Map<String, String> codes) {
        Map row = entry.row as Map
        return [id        : row.findingId,
                headline  : row.headline,
                state     : codes[entry.state as String],
                firstFound: isoDate(row.firstFoundDate as Date),
                ageDays   : days(row.firstFoundDate as Date, day),
                records   : records,
                difference: money(row.differenceAmount as BigDecimal, row.currencyUomId as String),
                next      : "darpan findings show ${row.findingId}".toString()]
    }

    /** Records compared on each side of one run: the reads' counts summed per root system. */
    private static Map compared(List<Map> reads) {
        Map<String, Object> out = new LinkedHashMap<>()
        reads.groupBy { systemKey(it.systemEnumId as String) }.each { String key, List<Map> rs ->
            List counted = rs*.recordCount.findAll { it != null }
            out[key] = counted ? (counted.sum { it as long } as long) : null
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------------------
    // The finding
    // ---------------------------------------------------------------------------------------------------------

    /** The finding document, or null when the finding does not exist or belongs to another tenant. */
    static Map<String, Object> assembleFinding(def ec, String tenantId, String findingId) {
        def findingValue = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.AuditFinding", "findingId",
                findingId, tenantId, WHY)
        if (findingValue == null) return null
        Map f = findingValue.getMap()
        Map tenant = loadTenant(ec, tenantId)
        ZoneId zone = (tenant?.zone ?: ZoneId.of("UTC")) as ZoneId
        Map<String, String> codes = enumCodes(ec)

        Map statement = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.AuditStatement", "statementId",
                f.statementId, tenantId, WHY)?.getMap()
        Map check = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.AuditCheck", "checkId",
                f.checkId, tenantId, WHY)?.getMap()
        if (statement == null || check == null) return null
        Map audit = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.Audit", "auditId", statement.auditId, tenantId, WHY)?.getMap()
        def category = ec.entity.find("moqui.basic.Enumeration").condition("enumId", statement.categoryEnumId).useCache(true).one()
        List<Map> events = tenantRows(ec, "darpan.audit.AuditFindingEvent", tenantId).condition("findingId", findingId)
                .orderBy(["eventStamp", "eventSeqId"]).list()*.getMap()
        List<Map> evidenceChecks = tenantRows(ec, "darpan.audit.AuditFindingCondition", tenantId).condition("findingId", findingId)
                .orderBy("sequenceNum").list()*.getMap()
        List<Map> recordRows = tenantRows(ec, "darpan.audit.AuditFindingRecord", tenantId).condition("findingId", findingId)
                .orderBy("recordSeqId").list()*.getMap()
        Map records = recordsJson(recordRows)

        String state = f.stateEnumId
        Date firstFound = f.firstFoundDate as Date
        Map lastFixed = events.findAll { it.eventTypeEnumId == "AUDFE_FIXED" }.with { it ? it.last() : null }
        LocalDate ageTo = state == FIXED ?
                ((lastFixed?.eventDate as Date)?.toLocalDate() ?: (f.closedStamp as Timestamp)?.toInstant()?.atZone(zone)?.toLocalDate()) :
                (f.lastSeenDate as Date).toLocalDate()
        int graceDays = (check.graceDays ?: 0) as int
        Map<String, String> checkCodes = [(check.checkId as String): check.checkCode as String]

        String currency = f.currencyUomId
        Map expected = f.expectedAmount == null ? null : money(f.expectedAmount as BigDecimal, currency) + [source: f.expectedSource]
        Map actual = f.actualAmount == null ? null : money(f.actualAmount as BigDecimal, currency) + [source: f.actualSource]
        def conclusion = f.conclusionEnumId ? ec.entity.find("moqui.basic.Enumeration").condition("enumId", f.conclusionEnumId).useCache(true).one() : null
        Map<String, String> fill = templateValues(f, recordRows)
        String fixWhere = check.fixWhereEnumId ? systemKey(check.fixWhereEnumId as String) : (records ? records.keySet().first() as String : null)

        Timestamp lastChecked = (f.lastCheckedStamp ?: events.with { it ? it.last().eventStamp : null }) as Timestamp
        return [schema : FINDING_SCHEMA,
                finding: [
                        id       : f.findingId,
                        headline : f.headline,
                        audit    : [id: audit?.auditCode, name: audit?.auditName],
                        category : [id: category?.enumCode, label: category?.description],
                        statement: [id: statement.statementCode, text: statement.statementText, weight: codes[statement.weightEnumId as String]],
                        check    : [id: check.checkCode, label: check.label, matchedBy: check.matchedBy ?: null],
                        status   : [
                                state       : codes[state],
                                firstFound  : isoDate(firstFound),
                                lastSeen    : isoDate(f.lastSeenDate as Date),
                                lastChecked : isoDateTime(lastChecked, zone),
                                ageDays     : ageTo == null ? 0 : days(firstFound, ageTo),
                                timesSeen   : (f.timesSeen ?: 0) as int,
                                pendingUntil: state == PENDING ? firstFound.toLocalDate().plusDays(graceDays).toString() : null,
                                closedAt    : state == FIXED ? isoDateTime(f.closedStamp as Timestamp, zone) : null,
                                closedBy    : state == FIXED ? actor(f.closedByTypeEnumId as String, f.closedById as String,
                                        f.closedStamp as Timestamp, zone, checkCodes, codes) : null],
                        records  : records,
                        evidence : [
                                rule      : f.ruleText ?: statement.statementText,
                                expected  : expected,
                                actual    : actual,
                                difference: expected != null && actual != null ? money(f.differenceAmount as BigDecimal, currency) : null,
                                checks    : evidenceChecks.collect { [label: it.label, passed: it.passed == "Y", value: it.checkValue ?: null] }],
                        diagnosis: [
                                conclusion : conclusion == null ? null : [code: conclusion.enumCode ?: conclusion.enumId, label: conclusion.description],
                                explanation: conclusion == null ? null : (f.diagnosisText ?: "${conclusion.description}.".toString())],
                        fix      : [
                                owner        : check.fixOwner ?: "Not assigned yet",
                                where        : fixWhere,
                                action       : fillTemplate(check.fixActionTemplate as String, fill) ?:
                                        "Correct the record so that the check no longer reports it: ${f.headline}.".toString(),
                                verify       : [
                                        by        : VERIFY_BY,
                                        // The check's schedule arrives with DAR-BE-092 (automation linked to the check).
                                        nextCheck : null,
                                        passesWhen: fillTemplate(check.passesWhenTemplate as String, fill) ?:
                                                "The check looks at ${f.recordKey} and no longer reports it.".toString()],
                                darpanChanges: DARPAN_CHANGES],
                        history  : events.collect { Map e ->
                            [at: isoDateTime(e.eventStamp as Timestamp, zone), event: codes[e.eventTypeEnumId as String],
                             by: actor(e.byTypeEnumId as String, e.byId as String, e.eventStamp as Timestamp, zone, checkCodes, codes)]
                        }],
                access : [mode: "read-only", note: ACCESS_NOTE]]
    }

    private static Map actor(String typeEnumId, String byId, Timestamp stamp, ZoneId zone, Map<String, String> checkCodes,
                             Map<String, String> codes) {
        if (typeEnumId == "AUDAT_CHECK") {
            String when = stamp == null ? "" : ", ${stamp.toInstant().atZone(zone).format(DATE_TIME_MINUTE)}"
            return [type: codes[typeEnumId], id: checkCodes[byId] ?: byId, label: "scheduled check${when}".toString()]
        }
        return [type: codes[typeEnumId] ?: typeEnumId, id: byId, label: "Darpan user"]
    }

    /**
     * Values a check's fix templates may name: {recordKey}, {headline}, {expectedAmount}, {actualAmount},
     * {differenceAmount}, {differenceAbs}, {currency}, and {recordType} for the first id of each record type, e.g.
     * {creditMemo} or {return}.
     */
    private static Map<String, String> templateValues(Map f, List<Map> recordRows) {
        String currency = f.currencyUomId
        Map<String, String> values = [recordKey: f.recordKey as String, headline: f.headline as String, currency: currency]
        ["expectedAmount", "actualAmount", "differenceAmount"].each { String k ->
            if (f[k] != null) values[k] = amountText(f[k] as BigDecimal, currency)
        }
        if (f.differenceAmount != null) values.differenceAbs = amountText((f.differenceAmount as BigDecimal).abs(), currency)
        recordRows.each { Map r -> values.putIfAbsent(r.recordType as String, r.recordId as String) }
        return values
    }

    /** Literal {name} replacement. Tenant configuration never runs code: no Groovy, no FTL. Unknown names stay as written. */
    static String fillTemplate(String template, Map<String, String> values) {
        if (!template) return null
        return template.replaceAll(/\{([A-Za-z][A-Za-z0-9_]*)\}/) { List<String> m -> values[m[1]] != null ? values[m[1]] : m[0] }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Shared shapes and reads
    // ---------------------------------------------------------------------------------------------------------

    /** records.<System>.<recordType>: [ {id, internalId, status} ], systems and types in stored order. */
    static Map recordsJson(List<Map> recordRows) {
        Map<String, Map<String, List>> out = new LinkedHashMap<>()
        recordRows.each { Map r ->
            Map<String, List> byType = out.computeIfAbsent(systemKey(r.systemEnumId as String)) { new LinkedHashMap<>() }
            byType.computeIfAbsent(r.recordType as String) { [] } << [id: r.recordId, internalId: r.internalId ?: null, status: r.recordStatus ?: null]
        }
        return out
    }

    private static Map<String, Map> recordsFor(def ec, String tenantId, List<String> findingIds) {
        if (!findingIds) return [:]
        List<Map> rows = tenantRows(ec, "darpan.audit.AuditFindingRecord", tenantId).condition("findingId", "in", findingIds)
                .orderBy(["findingId", "recordSeqId"]).list()*.getMap()
        return rows.groupBy { it.findingId as String }.collectEntries { k, v -> [(k): recordsJson(v)] }
    }

    /** Money: amount at the currency's scale as a string, sign included. Null when there is no amount. */
    static Map money(BigDecimal amount, String currency) {
        if (amount == null) return null
        return [amount: amountText(amount, currency), currency: currency]
    }

    private static String amountText(BigDecimal amount, String currency) {
        int scale = 2
        try { if (currency) scale = Currency.getInstance(currency).defaultFractionDigits } catch (IllegalArgumentException ignored) { }
        return amount.setScale(Math.max(scale, 0), RoundingMode.HALF_UP).toPlainString()
    }

    /** Oldest first: firstFound ascending, then id ascending with F-99 before F-100. */
    private static List<Map> sortOldestFirst(List<Map> entries) {
        return entries.sort(false) { Map a, Map b ->
            ((a.row.firstFoundDate as Date) <=> (b.row.firstFoundDate as Date)) ?:
                    ((a.row.findingId as String).length() <=> (b.row.findingId as String).length()) ?:
                    ((a.row.findingId as String) <=> (b.row.findingId as String))
        }
    }

    private static Map loadTenant(def ec, String tenantId) {
        if (!tenantId) return null
        def group = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.security.UserGroup", WHY)
                .condition("userGroupId", tenantId).useCache(false).one()
        if (group == null) return null
        def setting = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.auth.TenantSetting", WHY)
                .condition("companyUserGroupId", tenantId).useCache(false).one()
        ZoneId zone
        try { zone = setting?.timeZone ? ZoneId.of(setting.timeZone as String) : ZoneId.of("UTC") }
        catch (Exception ignored) { zone = ZoneId.of("UTC") }
        return [name: group.description ?: tenantId, zone: zone]
    }

    private static def tenantRows(def ec, String entityName, String tenantId) {
        return TenantScopedFinder.findGlobalUnscoped(ec, entityName, WHY).condition("companyUserGroupId", tenantId).useCache(false)
    }

    /** enumId -> enumCode for the audit-run vocabulary (the JSON emits the code, never the id). */
    private static Map<String, String> enumCodes(def ec) {
        return ec.entity.find("moqui.basic.Enumeration").condition("enumTypeId", "in", ENUM_TYPES).useCache(true).list()
                .collectEntries { [(it.enumId): it.enumCode] }
    }

    /** report.period: the period the checked day falls in, e.g. 2026-10-07 (day), 2026-10 (month), 2026-Q4 (quarter). */
    static String periodOf(LocalDate day, String periodCode) {
        switch (periodCode) {
            case "day": return day.toString()
            case "quarter": return "${day.year}-Q${(int) ((day.monthValue - 1) / 3) + 1}".toString()
            default: return day.toString().substring(0, 7)
        }
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
}
