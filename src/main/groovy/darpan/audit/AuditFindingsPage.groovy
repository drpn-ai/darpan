package darpan.audit

import org.moqui.entity.EntityCondition

import java.sql.Date
import java.time.LocalDate
import java.time.ZoneId

import static darpan.audit.AuditReportAssembler.STATEMENT_ORDER

/**
 * DAR-BE-098, findings(query:). One page of a tenant's findings for GraphQL: each node the report's finding summary plus
 * its audit, statement, category, period and last sighting, keyset-paged with cursors that carry their sort.
 *
 * DAR-OPS-029 S10: split out of AuditReportAssembler.
 */
class AuditFindingsPage {

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
        Map<String, String> codes = AuditReportAssembler.enumCodes(ec)
        Map<String, String> auditCodes = AuditReportAssembler.tenantRows(ec, "darpan.audit.Audit", tenantId).list()
                .collectEntries { [(it.auditId): it.auditCode] }
        List<Map> statements = AuditReportAssembler.tenantRows(ec, "darpan.audit.AuditStatement", tenantId).list()*.getMap()
                .findAll { (it.isActive ?: "Y") != "N" }
        def cf = ec.entity.conditionFactory
        List conditions = []
        List<String> priorities = null
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
                case "priority":
                    List bad = values.findAll { !(it in AuditPriority.LEVELS) }
                    if (bad) return [publicError: "priority must be one of ${AuditPriority.LEVELS.join(', ')}, got ${bad.join(', ')}".toString()]
                    priorities = values as List<String>; break
                case "firstFound":
                    Date day
                    try { day = Date.valueOf(LocalDate.parse(values[0])) }
                    catch (Exception ignored) { return [publicError: "firstFound must be YYYY-MM-DD, got ${values[0]}".toString()] }
                    conditions << cf.makeCondition("firstFoundDate", COMPARATORS.get(t.comparator as String), day); break
            }
        }
        statements = statements.sort(false, STATEMENT_ORDER)
        Map<String, Map> statementById = statements.collectEntries { [(it.statementId): it] }

        // DAR-BE-348: a level is resolved from the finding's statement and reason, so the filter runs on the rows read,
        // reading on until the page is full: a filtered page holds every finding at that level, not only those in a first read.
        Map levels = AuditReportAssembler.priorityLevels(ec, tenantId, statements)
        Closure<Boolean> atLevel = { Map row ->
            !priorities || (AuditReportAssembler.unitPriority([row: row, state: row.stateEnumId], levels.statementLevels as Map, levels.overrides as Map).level in priorities)
        }
        List<Map> rows = []
        if (sort == "FIRST_FOUND") {
            if (statements) rows = matchingRows(ec, tenantId, statements*.statementId, conditions, rev, cursor ? cursor[3..4] : null, size + 1, priorities != null, atLevel)
        } else {
            List<String> order = statements*.statementId as List<String>
            if (rev) order = order.reverse()
            int from = 0
            if (cursor) {
                from = order.indexOf(cursor[2] as String)
                if (from < 0) return [publicError: "after is a cursor for a statement this query no longer reads"]
            }
            for (int i = from; i < order.size() && rows.size() <= size; i++) {
                rows.addAll(matchingRows(ec, tenantId, [order[i]], conditions, rev, cursor && i == from ? cursor[3..4] : null,
                        size + 1 - rows.size(), priorities != null, atLevel))
            }
        }
        boolean more = rows.size() > size
        rows = rows.take(size)

        LocalDate today = LocalDate.now((AuditReportAssembler.loadTenant(ec, tenantId)?.zone ?: ZoneId.of("UTC")) as ZoneId)
        Map<String, Map> records = AuditReportAssembler.recordsFor(ec, tenantId, rows*.findingId as List<String>)
        // DAR-UI-073. The cause in words (the conclusion that explains the finding), so the board can filter by it.
        Map<String, Map> causes = AuditReportAssembler.conclusionsOf(ec, rows*.conclusionEnumId as List<String>)
        List<Map> edges = rows.collect { Map row ->
            Map st = statementById[row.statementId as String]
            Map brief = AuditReportAssembler.briefFinding([row: row, state: row.stateEnumId], today, records[row.findingId as String] ?: [:], codes)
            Map node = [id: brief.id, audit: auditCodes[st?.auditId as String], statement: st?.statementCode,
                        category: codes[st?.categoryEnumId as String], period: row.period, headline: brief.headline,
                        state: brief.state, firstFound: brief.firstFound, lastSeen: AuditReportAssembler.isoDate(row.lastSeenDate as Date),
                        ageDays: brief.ageDays, records: brief.records, difference: brief.difference, next: brief.next,
                        // get(), not [ ]: a null key there dispatches to getProperty(null) and throws.
                        cause: row.conclusionEnumId ? causes.get(row.conclusionEnumId as String)?.label : null] +
                    AuditReportAssembler.priorityOf([row: row, state: row.stateEnumId], levels, causes)
            [cursor: encodeFindingCursor([sort, rev, row.statementId, AuditReportAssembler.isoDate(row.firstFoundDate as Date), row.findingId]), node: node]
        }
        return [page: [edges   : edges,
                       pageInfo: [hasNextPage    : more, hasPreviousPage: after != null,
                                  startCursor    : edges ? edges.first().cursor : null,
                                  endCursor      : edges ? edges.last().cursor : null]]]
    }

    private static final Map<String, EntityCondition.ComparisonOperator> COMPARATORS = [
            eq: EntityCondition.EQUALS, gt: EntityCondition.GREATER_THAN, gte: EntityCondition.GREATER_THAN_EQUAL_TO,
            lt: EntityCondition.LESS_THAN, lte: EntityCondition.LESS_THAN_EQUAL_TO].asImmutable()

    /** [want] findings passing [keep], reading on in batches when a filter drops some; one read of [want] when none does. */
    private static List<Map> matchingRows(def ec, String tenantId, List statementIds, List conditions, boolean rev,
                                          List keyset, int want, boolean filtered, Closure<Boolean> keep) {
        int batch = filtered ? 500 : want
        List<Map> out = []
        List ks = keyset
        while (out.size() < want) {
            List<Map> read = findingRows(ec, tenantId, statementIds, conditions, rev, ks, batch)
            for (Map row in read) { if (out.size() < want && keep(row)) out << row }
            if (read.size() < batch) break
            ks = [AuditReportAssembler.isoDate(read.last().firstFoundDate as Date), read.last().findingId]
        }
        return out
    }

    /** Findings of these statements, oldest first (newest when reversed), after the keyset [firstFound, findingId]. */
    private static List<Map> findingRows(def ec, String tenantId, List statementIds, List conditions, boolean rev,
                                         List keyset, int limit) {
        def cf = ec.entity.conditionFactory
        def find = AuditReportAssembler.tenantRows(ec, "darpan.audit.AuditFinding", tenantId)
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
}
