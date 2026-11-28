package darpan.audit

import darpan.config.DarpanSettings
import darpan.facade.common.TenantScopedFinder
import org.moqui.entity.EntityCondition

import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

import static darpan.audit.AuditReportAssembler.FIXED
import static darpan.audit.AuditReportAssembler.OPEN
import static darpan.audit.AuditReportAssembler.USER_ACTOR

/**
 * DAR-BE-090 / DAR-BE-103. The finding document (darpan.audit.finding/v1): one finding's status, records with their
 * data, evidence, diagnosis, how to fix and verify it (the cause's own fix first, DAR-BE-120), and its history.
 * Read-only over stored rows; every read is the tenant's own.
 *
 * DAR-OPS-029 S10: split out of AuditReportAssembler.
 */
class AuditFindingDocument {

    static final String FINDING_SCHEMA = "darpan.audit.finding/v1"
    static final String VERIFY_BY = "Darpan's next scheduled check"
    static final String DARPAN_CHANGES = "Nothing. Darpan reads NetSuite and the OMS; it never writes to them."
    static final String ACCESS_NOTE = "This API has no mutations. Fix the record in its own system; Darpan closes the finding at its next scheduled check."
    private static final DateTimeFormatter DATE_TIME_MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmXXX")
    private static final String WHY = "audit finding document: explicit companyUserGroupId condition applied"

    /** The finding document, or null when the finding does not exist or belongs to another tenant. */
    static Map<String, Object> assembleFinding(def ec, String tenantId, String findingId) {
        def findingValue = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.AuditFinding", "findingId",
                findingId, tenantId, WHY)
        if (findingValue == null) return null
        Map f = findingValue.getMap()
        Map tenant = AuditReportAssembler.loadTenant(ec, tenantId)
        ZoneId zone = (tenant?.zone ?: ZoneId.of("UTC")) as ZoneId
        Map<String, String> codes = AuditReportAssembler.enumCodes(ec)

        Map statement = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.AuditStatement", "statementId",
                f.statementId, tenantId, WHY)?.getMap()
        Map check = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.AuditCheck", "checkId",
                f.checkId, tenantId, WHY)?.getMap()
        if (statement == null || check == null) return null
        Map audit = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.Audit", "auditId", statement.auditId, tenantId, WHY)?.getMap()
        def category = ec.entity.find("moqui.basic.Enumeration").condition("enumId", statement.categoryEnumId).useCache(true).one()
        List<Map> events = AuditReportAssembler.tenantRows(ec, "darpan.audit.AuditFindingEvent", tenantId, WHY).condition("findingId", findingId)
                .orderBy(["eventStamp", "eventSeqId"]).list()*.getMap()
        List<Map> evidenceChecks = AuditReportAssembler.tenantRows(ec, "darpan.audit.AuditFindingCondition", tenantId, WHY).condition("findingId", findingId)
                .orderBy("sequenceNum").list()*.getMap()
        List<Map> recordRows = AuditReportAssembler.tenantRows(ec, "darpan.audit.AuditFindingRecord", tenantId, WHY).condition("findingId", findingId)
                .orderBy("recordSeqId").list()*.getMap()
        Map records = AuditRecordFacts.recordsJson(recordRows, true, zone)

        String state = f.stateEnumId
        Date firstFound = f.firstFoundDate as Date
        Map lastFixed = events.findAll { it.eventTypeEnumId == "AUDFE_FIXED" }.with { it ? it.last() : null }
        LocalDate ageTo = state == FIXED ?
                ((lastFixed?.eventDate as Date)?.toLocalDate() ?: (f.closedStamp as Timestamp)?.toInstant()?.atZone(zone)?.toLocalDate()) :
                (f.lastSeenDate as Date).toLocalDate()
        Map<String, String> checkCodes = [(check.checkId as String): check.checkCode as String]

        String currency = f.currencyUomId
        Map expected = f.expectedAmount == null ? null : AuditRecordFacts.money(f.expectedAmount as BigDecimal, currency) + [source: f.expectedSource]
        Map actual = f.actualAmount == null ? null : AuditRecordFacts.money(f.actualAmount as BigDecimal, currency) + [source: f.actualSource]
        def conclusion = f.conclusionEnumId ? ec.entity.find("moqui.basic.Enumeration").condition("enumId", f.conclusionEnumId).useCache(true).one() : null
        Map levels = AuditReportAssembler.priorityLevels(ec, tenantId, [statement])
        Map level = AuditReportAssembler.unitPriority([row: f, state: state], levels.statementLevels as Map, levels.overrides as Map)
        Map<String, String> fill = templateValues(f, recordRows)
        Map<String, String> recordFields = recordFieldValues(recordRows)
        String fixWhere = check.fixWhereEnumId ? AuditRecordFacts.systemKey(check.fixWhereEnumId as String) : (records ? records.keySet().first() as String : null)

        // DAR-BE-361: a person's event (an acknowledgement) is not a check looking at the record.
        Timestamp lastChecked = (f.lastCheckedStamp ?: events.findAll { it.byTypeEnumId != USER_ACTOR }
                .with { it ? it.last().eventStamp : null }) as Timestamp
        Map acknowledgement = state == OPEN ? findingAcknowledgement(ec, tenantId, findingId, zone) : null
        // DAR-OPS-029 S99-012: lines of its order the read left off past the cap, per system; absent when none were.
        Map omitted = AuditOrderUnits.linesOmitted([f])
        return [schema : FINDING_SCHEMA,
                finding: [
                        id       : f.findingId,
                        headline : f.headline,
                        audit    : [id: audit?.auditCode, name: audit?.auditName],
                        period   : f.period,
                        category : category?.enumTypeId == "AuditCategory" ? [id: category.enumCode, label: category.description] :
                                [id: AuditReportAssembler.UNCATEGORISED, label: AuditReportAssembler.UNCATEGORISED_LABEL],
                        statement: [id: statement.statementCode, text: statement.statementText, weight: codes.get(statement.weightEnumId as String)],
                        // DAR-BE-348: reason is the overriding conclusion's label when the level is the reason's.
                        priority : [level: level.level, source: level.source, reason: level.source == "reason" ? conclusion?.description : null],
                        check    : [id: check.checkCode, label: check.label, matchedBy: check.matchedBy ?: null],
                        status   : [
                                state       : codes[state],
                                firstFound  : AuditReportAssembler.isoDate(firstFound),
                                lastSeen    : AuditReportAssembler.isoDate(f.lastSeenDate as Date),
                                lastChecked : AuditReportAssembler.isoDateTime(lastChecked, zone),
                                ageDays     : ageTo == null ? 0 : AuditReportAssembler.days(firstFound, ageTo),
                                timesSeen   : (f.timesSeen ?: 0) as int,
                                closedAt    : state == FIXED ? AuditReportAssembler.isoDateTime(f.closedStamp as Timestamp, zone) : null,
                                closedBy    : state == FIXED ? actor(f.closedByTypeEnumId as String, f.closedById as String,
                                        f.closedStamp as Timestamp, zone, checkCodes, codes) : null],
                        // DAR-BE-361: its report row's acknowledgement as of today, or null.
                        acknowledgement: acknowledgement,
                        // DAR-BE-361: the team's notes to Darpan on this statement and cause in the shown days (the bubble's count).
                        feedback : [recentCount: FeedbackSupport.recentCount(ec, tenantId, f.statementId as String,
                                FeedbackSupport.conclusionCode(ec, f.conclusionEnumId as String),
                                DarpanSettings.intValue(FeedbackSupport.SHOWN_DAYS_SETTING))],
                        records  : records,
                        evidence : [
                                rule      : f.ruleText ?: statement.statementText,
                                expected  : expected,
                                actual    : actual,
                                difference: expected != null && actual != null ? AuditRecordFacts.money(f.differenceAmount as BigDecimal, currency) : null,
                                checks    : evidenceChecks.collect { [label: it.label, passed: it.passed == "Y", value: it.checkValue ?: null] }],
                        diagnosis: [
                                conclusion : conclusion == null ? null : [code: conclusion.enumCode ?: conclusion.enumId, label: conclusion.description],
                                explanation: conclusion == null ? null : (f.diagnosisText ?: "${conclusion.description}.".toString())],
                        fix      : [
                                owner        : check.fixOwner ?: "Not assigned yet",
                                where        : fixWhere,
                                action       : causeFixOf(ec, tenantId, check, f.conclusionEnumId as String, evidenceChecks, fill, recordFields) ?:
                                        fillTemplate(check.fixActionTemplate as String, recordFields + fill) ?:
                                        "Correct the record so that the check no longer reports it: ${f.headline}.".toString(),
                                verify       : [
                                        by        : VERIFY_BY,
                                        // The check's schedule arrives with DAR-BE-092 (automation linked to the check).
                                        nextCheck : null,
                                        passesWhen: fillTemplate(check.passesWhenTemplate as String, recordFields + fill) ?:
                                                "The check looks at ${AuditRecordFacts.displayKey(f.recordKey as String)} and no longer reports it.".toString()],
                                darpanChanges: DARPAN_CHANGES],
                        history  : events.collect { Map e ->
                            [at: AuditReportAssembler.isoDateTime(e.eventStamp as Timestamp, zone), event: codes.get(e.eventTypeEnumId as String),
                             by: actor(e.byTypeEnumId as String, e.byId as String, e.eventStamp as Timestamp, zone, checkCodes, codes)]
                        }] + (omitted ? [orderLinesOmitted: omitted] : [:]),
                access : [mode: "read-only", note: ACCESS_NOTE]]
    }

    /**
     * DAR-BE-361. An open finding's row acknowledgement as of today. A finding with no row of its own reads open whatever
     * its row's other lines hold, so the row is resolved (unitMemberIds) only when it has one.
     */
    private static Map findingAcknowledgement(def ec, String tenantId, String findingId, ZoneId zone) {
        if (TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.AuditFindingAcknowledgement", "findingId", findingId, tenantId, WHY) == null) return null
        List<String> members = AuditReportAssembler.unitMemberIds(ec, tenantId, findingId)
        return AuditReportAssembler.acknowledgementOf(members, AuditReportAssembler.acknowledgementsOf(ec, tenantId, members), LocalDate.now(zone), zone).summary as Map
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
        Map<String, String> values = [recordKey: AuditRecordFacts.displayKey(f.recordKey as String), headline: f.headline as String, currency: currency]
        ["expectedAmount", "actualAmount", "differenceAmount"].each { String k ->
            if (f[k] != null) values[k] = AuditRecordFacts.amountText(f[k] as BigDecimal, currency)
        }
        if (f.differenceAmount != null) values.differenceAbs = AuditRecordFacts.amountText((f.differenceAmount as BigDecimal).abs(), currency)
        recordRows.each { Map r -> values.putIfAbsent(r.recordType as String, r.recordId as String) }
        return values
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
    static String causeFixOf(def ec, String tenantId, Map check, String conclusionEnumId, List<Map> evidenceChecks,
                                     Map<String, String> values, Map<String, String> recordFields = [:]) {
        if (!conclusionEnumId || !check.savedRunId) return null
        List scopeIds = AuditReportAssembler.tenantRows(ec, "darpan.rule.RuleSetCompareScope", tenantId, WHY).condition("ruleSetId", check.savedRunId)
                .list()*.compareScopeId
        if (!scopeIds) return null
        Map rule = AuditReportAssembler.tenantRows(ec, "darpan.rule.RuleSetConclusionRule", tenantId, WHY)
                .condition("compareScopeId", EntityCondition.IN, scopeIds).condition("conclusionEnumId", conclusionEnumId)
                .orderBy("sequenceNum").list()*.getMap().find { it.fixActionTemplate }
        if (rule == null) return null
        List<Map> conditions = AuditReportAssembler.tenantRows(ec, "darpan.rule.RuleSetConclusionCondition", tenantId, WHY)
                .condition("compareScopeId", rule.compareScopeId).list()*.getMap()
        return causeFix(rule.fixActionTemplate as String, conditions, evidenceChecks, values, recordFields)
    }

    /**
     * The cause's fix filled: the check's values plus each of the scope's condition fields, valued from the finding's
     * evidence by the condition's checkLabel (the evidence is what those conditions read). Pure.
     */
    static String causeFix(String template, List<Map> conditions, List<Map> evidenceChecks, Map<String, String> values,
                           Map<String, String> recordFields = [:]) {
        Map<String, String> byLabel = evidenceChecks.collectEntries { [(it.label): it.checkValue] }
        Map<String, String> fields = [:]
        conditions.each { Map c ->
            String value = byLabel[c.checkLabel] as String
            if (c.fieldExpression && value != null) fields.putIfAbsent(c.fieldExpression as String, value)
        }
        return fillTemplate(template, recordFields + fields + values)
    }

    /**
     * DAR-BE-294. The scalar fields of the finding's stored records (AuditFindingRecord.recordData), first record first:
     * the lowest-ranked fill for a fix or passes-when placeholder, so {orderName} fills without a condition reading it.
     */
    static Map<String, String> recordFieldValues(List<Map> recordRows) {
        Map<String, String> out = [:]
        recordRows.each { Map r ->
            AuditRecordFacts.recordData(r.recordData)?.each { k, v ->
                if (v != null && !(v instanceof Map) && !(v instanceof Collection)) out.putIfAbsent(k as String, v.toString())
            }
        }
        return out
    }
}
