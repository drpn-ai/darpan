package darpan.audit

import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.core.ReconciliationServices
import org.moqui.entity.EntityCondition

import java.math.RoundingMode
import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException
import java.util.regex.Matcher
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
        period = period ?: periodOf(day, periodCode)
        Map window = periodWindow(ec, tenantId, audit, period, day)
        if (window == null) throw new IllegalArgumentException("${period} is not a ${periodCode} period of audit ${audit.auditCode} that has started by ${day}")

        List<Map> categories = ec.entity.find("moqui.basic.Enumeration").condition("enumTypeId", "AuditCategory")
                .orderBy("sequenceNum").useCache(true).list()*.getMap()
        // DAR-UI-063. A statement turned off is not part of the report.
        List<Map> statements = tenantRows(ec, "darpan.audit.AuditStatement", tenantId).condition("auditId", auditId)
                .orderBy(["sequenceNum", "statementCode"]).list()*.getMap().findAll { (it.isActive ?: "Y") != "N" }
        Set<String> statementIds = statements*.statementId.toSet() as Set<String>
        Map<String, Map> checksById = tenantRows(ec, "darpan.audit.AuditCheck", tenantId).list()
                .collectEntries { [(it.checkId): it.getMap()] }
        List<Map> links = tenantRows(ec, "darpan.audit.AuditStatementCheck", tenantId)
                .orderBy(["sequenceNum", "checkId"]).list()*.getMap()
        Map<String, List<String>> checkIdsByStatement = [:].withDefault { [] }
        links.each { if (checksById[it.checkId] && statementIds.contains(it.statementId)) checkIdsByStatement[it.statementId as String] << (it.checkId as String) }
        Set<String> configuredCheckIds = checkIdsByStatement.values().flatten().toSet() as Set<String>

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
                    "The ${period} audit ended on ${window.extendedThru ?: window.end} and was not extended to ${day}.").toString()
        }

        // The ledger as of the end of the report date.
        List<Map> ledger = findingsAsOf(ec, tenantId, statementIds, period, day, zone, checksById)
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
                 compared : status == NOT_RUN || run == null ? null : compared(readsByRun[run.checkRunId as String]),
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

            List<Map> entries = sortOldestFirst(ledgerByStatement[st.statementId as String] ?: [])
            // DAR-UI-075. One finding per order: every line's records name its order, so the order's lines are one unit,
            // counted and capped as one. The ledger stays per line, so each line still opens and closes on its own.
            Map<String, Map> recordsByFinding = recordsFor(ec, tenantId, entries*.row*.findingId as List<String>)
            List<Map> units = orderUnits(entries, recordsByFinding)
            Map counts = countsOf(units, day)
            List<Map> listed = units.take(FINDINGS_CAP)
            Map<String, Map> causes = conclusionsOf(ec, listed*.entries.flatten()*.row*.conclusionEnumId as List<String>)
            statementJsonById[st.statementId as String] = [
                    id             : st.statementCode,
                    text           : st.statementText,
                    weight         : codes[st.weightEnumId as String],
                    status         : codes[worst],
                    reason         : reason,
                    checks         : checkJson,
                    counts         : counts,
                    findings       : listed.collect { Map unit -> unitFinding(unit, day, recordsByFinding, causes, codes) },
                    findingsOmitted: units.size() - listed.size()]
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
                             extendedThru: (window.extendedThru as LocalDate)?.toString(),
                             generatedAt : isoDateTime(generatedStamp, zone)],
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
    private static List<Map> findingsAsOf(def ec, String tenantId, Set<String> statementIds, String period, LocalDate day,
                                          ZoneId zone, Map<String, Map> checksById) {
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

    // ---------------------------------------------------------------------------------------------------------
    // DAR-UI-075. Findings by order
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The order a finding is a line of: its HotWax order item's order (the item's id before its "/"), else its NetSuite
     * sales order line's order; null when the finding is not about an order line. A finding that only mentions an order
     * (a credit memo naming its Shopify order) is not one, so returns and memos stay findings of their own.
     */
    static String orderKeyOf(Map records) {
        Closure<String> first = { String system, String type -> ((((records ?: [:])[system] ?: [:]) as Map)[type] as List)?.find()?.id as String }
        return first("OMS", "orderItem")?.with { it.contains("/") ? it.substring(0, it.indexOf("/")) : it } ?:
                first("NetSuite", "salesOrderLine")
    }

    /**
     * Ledger entries as the report lists them: the lines of one order together as one unit (its earliest line's row, so
     * oldest-first and newToday hold; its worst state: open, else pending, else fixed), one line or many; a finding that
     * is not an order line a unit of its own. Units keep the order of their earliest line.
     */
    static List<Map> orderUnits(List<Map> entries, Map<String, Map> recordsByFinding) {
        Map<String, List<Map>> byOrder = new LinkedHashMap<>()
        List<Object> keys = []
        entries.each { Map e ->
            String order = orderKeyOf(recordsByFinding[e.row.findingId as String])
            if (order) {
                if (!byOrder.containsKey(order)) { byOrder[order] = []; keys << order }
                byOrder[order] << e
            } else keys << e
        }
        return keys.collect { Object key ->
            if (!(key instanceof String)) return [row: ((Map) key).row, state: ((Map) key).state, entries: [(Map) key]]
            List<Map> lines = byOrder[key]
            String state = lines.any { it.state == OPEN } ? OPEN : lines.any { it.state == PENDING } ? PENDING : FIXED
            return [row: lines[0].row, state: state, entries: lines, order: key]
        }
    }

    /** Each conclusion's words and, when it is held by another line's cause (relatedEnumId), that cause. */
    private static Map<String, Map> conclusionsOf(def ec, List<String> ids) {
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
                                   Map<String, String> codes) {
        Closure<Map> line = { Map e ->
            // get(), not [ ]: most lines have no conclusion, and map[null] dispatches to getProperty(null) and throws.
            Map cause = e.row.conclusionEnumId ? causes.get(e.row.conclusionEnumId as String) : null
            briefFinding(e, day, recordsByFinding[e.row.findingId as String] ?: [:], codes) +
                    [cause: cause?.label ?: null, held: cause?.heldBy != null]
        }
        // DAR-BE-125: a lone finding keeps its cause in the catalog's words, which the board heads its findings by.
        if (!unit.order) return line((unit.entries as List<Map>)[0]).findAll { k, v -> k != "held" }
        String order = unit.order as String
        List<Map> lines = (unit.entries as List<Map>).collect(line)
        List<Map> stuck = lines.any { it.held } ? lines.findAll { !it.held && it.cause } : []
        List<String> own = lines*.cause.findAll().unique() as List<String>
        String cause = stuck ? stuck[0].cause : (own.size() == 1 ? own[0] : null)
        List<String> numbers = stuck.collect { Map l -> lineNumber(order, l) }
        String named = numbers.size() == 1 ? "line ${numbers[0]}" :
                "lines ${numbers.size() > 1 ? numbers[0..-2].join(', ') + ' and ' + numbers[-1] : ''}"
        String headline = stuck ?
                "Order ${order}: ${named} ${lowerFirst(cause)}, holding ${lines.size() - stuck.size()} more line${lines.size() - stuck.size() == 1 ? '' : 's'}" :
                lines.size() == 1 ? "Order ${order}: ${oneLine(order, lines[0])}" :
                "Order ${order}: ${lines.size()} lines${cause ? ', ' + lowerFirst(cause) : ''}"
        Map first = lines[0]
        return [id        : order,
                kind      : "order",
                order     : order,
                headline  : headline.toString(),
                state     : codes[unit.state as String],
                firstFound: first.firstFound,
                ageDays   : first.ageDays,
                records   : mergedRecords(lines*.records as List<Map>),
                difference: null,
                cause     : cause,
                stuck     : stuck*.id,
                lines     : lines,
                next      : "darpan findings show ${(stuck ? stuck[0] : first).id}".toString()]
    }

    /**
     * The tenant's findings for one audit and period, one statement or every one in catalog order, as the report lists
     * them: one per order, each with its statement and category. State is as of today, narrowed to `states` (open,
     * pending; empty is both). The board's "N more", filters and export read these.
     */
    static List<Map> findingsByOrder(def ec, String tenantId, String auditId, String statementCode, String period,
                                     List<String> states) {
        Map<String, String> codes = enumCodes(ec)
        List<String> stateIds = codes.findAll { k, v -> k.startsWith("AUDFS_") && v in (states ?: ["open", "pending"]) }*.key as List<String>
        List<Map> statements = tenantRows(ec, "darpan.audit.AuditStatement", tenantId).condition("auditId", auditId).list()*.getMap()
                .findAll { (it.isActive ?: "Y") != "N" && (!statementCode || it.statementCode == statementCode) }
                .sort(false) { Map a, Map b ->
                    ((a.sequenceNum ?: Long.MAX_VALUE) as Long) <=> ((b.sequenceNum ?: Long.MAX_VALUE) as Long) ?:
                            (a.statementCode as String) <=> (b.statementCode as String)
                }
        LocalDate today = LocalDate.now((loadTenant(ec, tenantId)?.zone ?: ZoneId.of("UTC")) as ZoneId)
        return statements.collectMany { Map st ->
            def find = tenantRows(ec, "darpan.audit.AuditFinding", tenantId).condition("statementId", st.statementId)
                    .condition("stateEnumId", EntityCondition.IN, stateIds ?: ["__NONE__"])
            if (period) find.condition("period", period)
            List<Map> entries = sortOldestFirst(find.list()*.getMap().collect { Map row -> [row: row, state: row.stateEnumId] })
            Map<String, Map> records = recordsFor(ec, tenantId, entries*.row*.findingId as List<String>)
            Map<String, Map> causes = conclusionsOf(ec, entries*.row*.conclusionEnumId as List<String>)
            orderUnits(entries, records).collect { Map unit ->
                unitFinding(unit, today, records, causes, codes) + [statement: st.statementCode, category: codes[st.categoryEnumId as String]]
            }
        } as List<Map>
    }

    /** An order of one line, in the check's own words with the item named as its line: "line 1 is completed in HotWax
     *  and not billed in NetSuite" from "Item M1167975/01 is completed ...". With a cause and no such words, the cause. */
    private static String oneLine(String order, Map line) {
        String item = (((((line.records ?: [:]) as Map).OMS ?: [:]) as Map).orderItem as List)?.find()?.id as String
        String headline = (line.headline ?: "") as String
        int at = item ? headline.indexOf(item) : -1
        if (at >= 0) return "line ${lineNumber(order, line)}${headline.substring(at + item.length())}".toString()
        return "line ${lineNumber(order, line)}${line.cause ? ', ' + lowerFirst(line.cause as String) : ''}".toString()
    }

    /** "1" for item M1168785/01 of order M1168785 (a person reads "line 1", not "/01"); else the line's finding id. */
    private static String lineNumber(String order, Map line) {
        String item = (((((line.records ?: [:]) as Map).OMS ?: [:]) as Map).orderItem as List)?.find()?.id as String
        if (!item?.startsWith(order) || item.length() <= order.length()) return line.id as String
        String suffix = item.substring(order.length()).replaceFirst(/^[\/\-_ ]+/, "")
        return suffix.isInteger() ? String.valueOf(suffix.toInteger()) : suffix
    }

    private static String lowerFirst(String text) {
        return text ? text.substring(0, 1).toLowerCase() + text.substring(1) : text
    }

    /** Every line's records once each, systems and types in first-seen order. */
    private static Map mergedRecords(List<Map> perLine) {
        Map<String, Map<String, List>> out = new LinkedHashMap<>()
        perLine.each { Map bySystem ->
            (bySystem ?: [:]).each { String system, Map byType ->
                byType.each { String type, List list ->
                    List into = out.computeIfAbsent(system) { new LinkedHashMap<>() }.computeIfAbsent(type) { [] }
                    list.each { Map r -> if (!into.any { it.id == r.id }) into << r }
                }
            }
        }
        return out
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

    static final int FINDINGS_PAGE_DEFAULT = 50
    static final int FINDINGS_PAGE_MAX = 100
    private static final List<String> FINDING_SORT_KEYS = ["FIRST_FOUND", "STATEMENT"].asImmutable()

    /**
     * DAR-BE-098, findings(query:). One page of the tenant's findings, each node the report's finding summary plus its
     * audit, statement, category, period and last sighting. ageDays is as of today in the tenant's zone.
     *
     * terms are the engine's parsed `query:` ([key, comparator, values]); audit / statement / category narrow the
     * tenant's statements, state / period / firstFound condition the findings. FIRST_FOUND (the default) is one keyset
     * query on (firstFoundDate, findingId). STATEMENT walks the statements in catalog order (sequenceNum, then code), each
     * oldest first, so it is at most one query per statement and a missing sequenceNum cannot break it. reverse flips
     * both. A cursor carries its sort and direction and is refused under another.
     *
     * Answers [page: [edges, pageInfo]] or [publicError: text a caller may see].
     */
    static Map<String, Object> findingsPage(def ec, String tenantId, List<Map> terms, String sortKey, Boolean reverse,
                                            Integer first, String after) {
        String sort = sortKey ?: "FIRST_FOUND"
        if (!(sort in FINDING_SORT_KEYS)) return [publicError: "sortKey must be one of ${FINDING_SORT_KEYS.join(', ')}".toString()]
        boolean rev = reverse == true
        int size = Math.min(Math.max(first ?: FINDINGS_PAGE_DEFAULT, 1), FINDINGS_PAGE_MAX)
        List cursor = null
        if (after) {
            cursor = decodeFindingCursor(after)
            if (cursor == null) return [publicError: "after is not a cursor from findings"]
            if (cursor[0] != sort || cursor[1] != rev) return [publicError: "after is a cursor for another sortKey or reverse"]
        }
        Map<String, String> codes = enumCodes(ec)
        Map<String, String> auditCodes = tenantRows(ec, "darpan.audit.Audit", tenantId).list()
                .collectEntries { [(it.auditId): it.auditCode] }
        List<Map> statements = tenantRows(ec, "darpan.audit.AuditStatement", tenantId).list()*.getMap()
                .findAll { (it.isActive ?: "Y") != "N" }
        def cf = ec.entity.conditionFactory
        List conditions = []
        for (Map t in (terms ?: [])) {
            List<String> values = (t.values ?: []) as List<String>
            switch (t.key) {
                case "audit": statements = statements.findAll { auditCodes[it.auditId as String] in values }; break
                case "statement": statements = statements.findAll { it.statementCode in values }; break
                case "category": statements = statements.findAll { codes[it.categoryEnumId as String] in values }; break
                case "state":
                    conditions << cf.makeCondition("stateEnumId", EntityCondition.IN,
                            codes.findAll { k, v -> k.startsWith("AUDFS_") && v in values }*.key ?: ["__NONE__"]); break
                case "period": conditions << cf.makeCondition("period", EntityCondition.IN, values); break
                case "firstFound":
                    Date day
                    try { day = Date.valueOf(LocalDate.parse(values[0])) }
                    catch (Exception ignored) { return [publicError: "firstFound must be YYYY-MM-DD, got ${values[0]}".toString()] }
                    conditions << cf.makeCondition("firstFoundDate", COMPARATORS[t.comparator as String], day); break
            }
        }
        statements = statements.sort(false) { Map a, Map b ->
            ((a.sequenceNum ?: Long.MAX_VALUE) as Long) <=> ((b.sequenceNum ?: Long.MAX_VALUE) as Long) ?:
                    (a.statementCode as String) <=> (b.statementCode as String) ?: (a.statementId as String) <=> (b.statementId as String)
        }
        Map<String, Map> statementById = statements.collectEntries { [(it.statementId): it] }

        List<Map> rows = []
        if (sort == "FIRST_FOUND") {
            if (statements) rows = findingRows(ec, tenantId, statements*.statementId, conditions, rev, cursor ? cursor[3..4] : null, size + 1)
        } else {
            List<String> order = statements*.statementId as List<String>
            if (rev) order = order.reverse()
            int from = 0
            if (cursor) {
                from = order.indexOf(cursor[2] as String)
                if (from < 0) return [publicError: "after is a cursor for a statement this query no longer reads"]
            }
            for (int i = from; i < order.size() && rows.size() <= size; i++) {
                rows.addAll(findingRows(ec, tenantId, [order[i]], conditions, rev, cursor && i == from ? cursor[3..4] : null,
                        size + 1 - rows.size()))
            }
        }
        boolean more = rows.size() > size
        rows = rows.take(size)

        LocalDate today = LocalDate.now((loadTenant(ec, tenantId)?.zone ?: ZoneId.of("UTC")) as ZoneId)
        Map<String, Map> records = recordsFor(ec, tenantId, rows*.findingId as List<String>)
        // DAR-UI-073. The cause in words (the conclusion that explains the finding), so the board can filter by it.
        Map<String, Map> causes = conclusionsOf(ec, rows*.conclusionEnumId as List<String>)
        List<Map> edges = rows.collect { Map row ->
            Map st = statementById[row.statementId as String]
            Map brief = briefFinding([row: row, state: row.stateEnumId], today, records[row.findingId as String] ?: [:], codes)
            Map node = [id: brief.id, audit: auditCodes[st?.auditId as String], statement: st?.statementCode,
                        category: codes[st?.categoryEnumId as String], period: row.period, headline: brief.headline,
                        state: brief.state, firstFound: brief.firstFound, lastSeen: isoDate(row.lastSeenDate as Date),
                        ageDays: brief.ageDays, records: brief.records, difference: brief.difference, next: brief.next,
                        // get(), not [ ]: a null key there dispatches to getProperty(null) and throws.
                        cause: row.conclusionEnumId ? causes.get(row.conclusionEnumId as String)?.label : null]
            [cursor: encodeFindingCursor([sort, rev, row.statementId, isoDate(row.firstFoundDate as Date), row.findingId]), node: node]
        }
        return [page: [edges   : edges,
                       pageInfo: [hasNextPage    : more, hasPreviousPage: after != null,
                                  startCursor    : edges ? edges.first().cursor : null,
                                  endCursor      : edges ? edges.last().cursor : null]]]
    }

    private static final Map<String, EntityCondition.ComparisonOperator> COMPARATORS = [
            eq: EntityCondition.EQUALS, gt: EntityCondition.GREATER_THAN, gte: EntityCondition.GREATER_THAN_EQUAL_TO,
            lt: EntityCondition.LESS_THAN, lte: EntityCondition.LESS_THAN_EQUAL_TO].asImmutable()

    /** Findings of these statements, oldest first (newest when reversed), after the keyset [firstFound, findingId]. */
    private static List<Map> findingRows(def ec, String tenantId, List statementIds, List conditions, boolean rev,
                                         List keyset, int limit) {
        def cf = ec.entity.conditionFactory
        def find = tenantRows(ec, "darpan.audit.AuditFinding", tenantId)
                .condition("statementId", EntityCondition.IN, statementIds)
        conditions.each { find.condition(it) }
        if (keyset) {
            Date day = Date.valueOf(keyset[0] as String)
            def beyond = rev ? EntityCondition.LESS_THAN : EntityCondition.GREATER_THAN
            find.condition(cf.makeCondition([
                    cf.makeCondition("firstFoundDate", beyond, day),
                    cf.makeCondition([cf.makeCondition("firstFoundDate", EntityCondition.EQUALS, day),
                                      cf.makeCondition("findingId", beyond, keyset[1])], EntityCondition.AND)],
                    EntityCondition.OR))
        }
        return find.orderBy(rev ? ["-firstFoundDate", "-findingId"] : ["firstFoundDate", "findingId"]).limit(limit).list()*.getMap()
    }

    private static String encodeFindingCursor(List parts) {
        return Base64.urlEncoder.withoutPadding().encodeToString(groovy.json.JsonOutput.toJson(parts).getBytes("UTF-8"))
    }

    /** [sort, reverse, statementId, firstFound, findingId], or null when it is not one of ours. */
    private static List decodeFindingCursor(String cursor) {
        try {
            Object parts = new groovy.json.JsonSlurper().parseText(new String(Base64.urlDecoder.decode(cursor), "UTF-8"))
            if (!(parts instanceof List) || ((List) parts).size() != 5 || !(((List) parts)[1] instanceof Boolean)) return null
            LocalDate.parse(((List) parts)[3] as String)
            return (List) parts
        } catch (Exception ignored) {
            return null
        }
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
                        period   : f.period,
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
                                action       : causeFixOf(ec, tenantId, check, f.conclusionEnumId as String, evidenceChecks, fill) ?:
                                        fillTemplate(check.fixActionTemplate as String, fill) ?:
                                        "Correct the record so that the check no longer reports it: ${f.headline}.".toString(),
                                verify       : [
                                        by        : VERIFY_BY,
                                        // The check's schedule arrives with DAR-BE-092 (automation linked to the check).
                                        nextCheck : null,
                                        passesWhen: fillTemplate(check.passesWhenTemplate as String, fill) ?:
                                                "The check looks at ${displayKey(f.recordKey as String)} and no longer reports it.".toString()],
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
        Map<String, String> values = [recordKey: displayKey(f.recordKey as String), headline: f.headline as String, currency: currency]
        ["expectedAmount", "actualAmount", "differenceAmount"].each { String k ->
            if (f[k] != null) values[k] = amountText(f[k] as BigDecimal, currency)
        }
        if (f.differenceAmount != null) values.differenceAbs = amountText((f.differenceAmount as BigDecimal).abs(), currency)
        recordRows.each { Map r -> values.putIfAbsent(r.recordType as String, r.recordId as String) }
        return values
    }

    /**
     * A record key as people and agents read it. A composite compare key (order id + item seq id) is joined by an
     * invisible unit separator; it reads as order/item. The ledger keeps the raw key, which closing matches against the
     * examined keys.
     */
    static String displayKey(String key) {
        return key?.replace(ReconciliationServices.COMPOSITE_KEY_DELIMITER, "/")
    }

    /**
     * Literal {name} replacement; a name may be a dotted field (DAR-BE-120: {orderInNetsuite.tranId}). Tenant configuration
     * never runs code: no Groovy, no FTL. Unknown names stay as written.
     */
    static String fillTemplate(String template, Map<String, String> values) {
        if (!template) return null
        return template.replaceAll(/\{([A-Za-z][A-Za-z0-9_.]*)\}/) { List<String> m -> values[m[1]] != null ? values[m[1]] : m[0] }
    }

    /**
     * DAR-BE-120. The fix of the cause that concluded this finding (the conclusion rule of the check's run), or null when
     * the cause has none of its own and the check's fix stands. Read at report time, so a cause's fix, once written,
     * shows on every finding it explains.
     */
    private static String causeFixOf(def ec, String tenantId, Map check, String conclusionEnumId, List<Map> evidenceChecks,
                                     Map<String, String> values) {
        if (!conclusionEnumId || !check.savedRunId) return null
        List scopeIds = tenantRows(ec, "darpan.rule.RuleSetCompareScope", tenantId).condition("ruleSetId", check.savedRunId)
                .list()*.compareScopeId
        if (!scopeIds) return null
        Map rule = tenantRows(ec, "darpan.rule.RuleSetConclusionRule", tenantId)
                .condition("compareScopeId", EntityCondition.IN, scopeIds).condition("conclusionEnumId", conclusionEnumId)
                .orderBy("sequenceNum").list()*.getMap().find { it.fixActionTemplate }
        if (rule == null) return null
        List<Map> conditions = tenantRows(ec, "darpan.rule.RuleSetConclusionCondition", tenantId)
                .condition("compareScopeId", rule.compareScopeId).list()*.getMap()
        return causeFix(rule.fixActionTemplate as String, conditions, evidenceChecks, values)
    }

    /**
     * The cause's fix filled: the check's values plus each of the scope's condition fields, valued from the finding's
     * evidence by the condition's checkLabel (the evidence is what those conditions read). Pure.
     */
    static String causeFix(String template, List<Map> conditions, List<Map> evidenceChecks, Map<String, String> values) {
        Map<String, String> byLabel = evidenceChecks.collectEntries { [(it.label): it.checkValue] }
        Map<String, String> fields = [:]
        conditions.each { Map c ->
            String value = byLabel[c.checkLabel] as String
            if (c.fieldExpression && value != null) fields.putIfAbsent(c.fieldExpression as String, value)
        }
        return fillTemplate(template, fields + values)
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
        // DAR-UI-075: a statement's every line now, so in chunks rather than one IN of every id.
        List<Map> rows = findingIds.collate(500).collectMany { List<String> chunk ->
            tenantRows(ec, "darpan.audit.AuditFindingRecord", tenantId).condition("findingId", "in", chunk)
                    .orderBy(["findingId", "recordSeqId"]).list()*.getMap()
        }
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
    static Map<String, String> enumCodes(def ec) {
        return ec.entity.find("moqui.basic.Enumeration").condition("enumTypeId", "in", ENUM_TYPES).useCache(true).list()
                .collectEntries { [(it.enumId): it.enumCode] }
    }

    /** [first day, last day] of a period written in the audit's form (2026-10-14, 2026-10, 2026-Q4); null when it does not parse. */
    static List<LocalDate> periodBounds(String period, String periodCode) {
        if (!period) return null
        try {
            switch (periodCode) {
                case "day":
                    LocalDate d = LocalDate.parse(period)
                    return [d, d]
                case "quarter":
                    Matcher m = period =~ /^(\d{4})-Q([1-4])$/
                    if (!m.matches()) return null
                    LocalDate start = LocalDate.of(m.group(1) as int, ((m.group(2) as int) - 1) * 3 + 1, 1)
                    return [start, start.plusMonths(3).minusDays(1)]
                default:
                    YearMonth ym = YearMonth.parse(period)
                    return [ym.atDay(1), ym.atEndOfMonth()]
            }
        } catch (DateTimeParseException ignored) {
            return null
        }
    }

    /**
     * DAR-BE-091. One period of an audit as of one morning: its window (first day to the earlier of its last day and
     * the morning), the extension a user asked for, and whether the period still runs that morning — inside its own
     * span, or after it while extended. Null when the period does not parse or has not started by that morning.
     */
    static Map periodWindow(def ec, String tenantId, Map audit, String period, LocalDate day) {
        String periodCode = enumCodes(ec)[audit.periodEnumId as String]
        List<LocalDate> bounds = periodBounds(period, periodCode)
        if (bounds == null || day < bounds[0]) return null
        def extension = tenantRows(ec, "darpan.audit.AuditPeriodExtension", tenantId).condition("auditId", audit.auditId)
                .condition("period", period).list().find()
        LocalDate extendedThru = (extension?.continueThruDate as Date)?.toLocalDate()
        boolean running = day <= bounds[1] || (extendedThru != null && day <= extendedThru)
        return [period: period, from: bounds[0], thru: day < bounds[1] ? day : bounds[1], end: bounds[1],
                extendedThru: extendedThru, running: running]
    }

    /**
     * DAR-BE-091. Every run an audit owes on one morning: its current period, from the period's first day to the
     * morning, then each ended period a user extended past that morning, over the period's whole span. "Meanwhile the
     * new month audit will start": on Nov 3 an October extended to Nov 5 and November both run.
     */
    static List<Map> auditRuns(def ec, String tenantId, Map audit, LocalDate day) {
        String periodCode = enumCodes(ec)[audit.periodEnumId as String]
        List<Map> runs = [periodWindow(ec, tenantId, audit, periodOf(day, periodCode), day)]
        tenantRows(ec, "darpan.audit.AuditPeriodExtension", tenantId).condition("auditId", audit.auditId)
                .condition("continueThruDate", EntityCondition.GREATER_THAN_EQUAL_TO, Date.valueOf(day))
                .orderBy("period").list().each { ext ->
            Map w = periodWindow(ec, tenantId, audit, ext.period as String, day)
            if (w != null && w.end < day) runs << w
        }
        return runs
    }

    /**
     * DAR-BE-091. The period and window of one check's run on one morning, for the ledger writers. A blank period is
     * the morning's own period, which needs the check's audits to share one kind of period. A past period must still
     * be running (extended). Returns [period, from, thru, ...] or [error: why].
     */
    static Map resolveCheckRun(def ec, String tenantId, List auditIds, LocalDate day, String period) {
        List<Map> audits = auditIds ? tenantRows(ec, "darpan.audit.Audit", tenantId).condition("auditId", EntityCondition.IN, auditIds)
                .list()*.getMap() : []
        Map<String, String> codes = enumCodes(ec)
        List<String> kinds = audits.collect { codes[it.periodEnumId as String] }.unique()
        if (!period) {
            if (kinds.size() != 1) return [error: "The check serves audits of ${kinds.size() ?: 'no'} kinds of period; pass the period".toString()]
            period = periodOf(day, kinds[0])
        }
        List<Map> windows = audits.collect { periodWindow(ec, tenantId, it, period, day) }.findAll { it != null }
        Map running = windows.find { it.running }
        if (running != null) return running
        if (!audits.any { periodBounds(period, codes[it.periodEnumId as String]) != null }) {
            return [error: "${period} is not a period of the check's audits".toString()]
        }
        if (!windows) return [error: "Period ${period} has not started on ${day}".toString()]
        return [error: "Period ${period} ended on ${windows[0].end} and is not extended to ${day}".toString()]
    }

    /**
     * DAR-BE-091. The first day of the period containing {@code day}: the day itself for a daily audit, the 1st for a
     * monthly one, the first day of the quarter for a quarterly one. An audit check reads from here to the report day,
     * so on the 14th a monthly audit reconciles the 1st-14th and every open finding of the month is looked at again.
     */
    static LocalDate periodStart(LocalDate day, String periodCode) {
        switch (periodCode) {
            case "day": return day
            case "quarter": return LocalDate.of(day.year, ((int) ((day.monthValue - 1) / 3)) * 3 + 1, 1)
            default: return day.withDayOfMonth(1)
        }
    }

    /** report.period: the period the report day falls in, e.g. 2026-10-07 (day), 2026-10 (month), 2026-Q4 (quarter). */
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
