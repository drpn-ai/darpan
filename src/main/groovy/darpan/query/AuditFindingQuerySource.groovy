package darpan.query

import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityListIterator
import org.moqui.jexl.script.ReadConditions

import java.sql.Timestamp

/**
 * DAR-BE-334. The "auditFindings" read source: one row per finding of an audit (through its statements), open ones
 * unless the input says includeClosed, with a fixed list of fields; the input's window is on firstFoundDate.
 */
class AuditFindingQuerySource {
    static final List<String> FIELDS = ["statement", "check", "recordKey", "period", "state", "headline", "firstFoundDate",
                                        "lastSeenDate", "timesSeen", "expectedAmount", "actualAmount", "differenceAmount",
                                        "currencyUomId", "conclusion", "closedStamp"].asImmutable()

    static Map<String, Object> read(def ec, Map params) {
        Map input = (Map) params.input
        int maxRows = (params.maxRows ?: 20_000) as int
        Timestamp runDate = (params.runDate ?: ec.user.nowTimestamp) as Timestamp
        return QuerySourceScope.withTenant(ec, params) { String tenantId ->
            String auditId = input.auditId as String
            def audit = auditId ? ec.entity.find("darpan.audit.Audit").condition("auditId", auditId).useCache(false).one() : null
            if (audit == null || audit.companyUserGroupId != tenantId) {
                return QuerySourceScope.error("READ_NOT_ALLOWED", "Audit ${auditId} is not one of this tenant's.")
            }
            String unknown = QueryRows.namedFields(input).find { String f -> !FIELDS.contains(f) }
            if (unknown) return QuerySourceScope.error("READ_ERROR", "${unknown} is not a finding field; a finding has ${FIELDS.join(', ')}.")

            Map<String, String> statementText = [:]
            ec.entity.find("darpan.audit.AuditStatement").condition("auditId", auditId).condition("companyUserGroupId", tenantId)
                    .useCache(false).list().each { statementText.put(it.statementId as String, it.statementText as String) }
            if (statementText.isEmpty()) return [rows: [], rowCount: 0L] as Map<String, Object>
            Map<String, String> checkLabel = [:]
            Map<String, String> conclusionText = [:]

            def find = ec.entity.find("darpan.audit.AuditFinding").condition("companyUserGroupId", tenantId)
                    .condition("statementId", EntityCondition.IN, statementText.keySet()).useCache(false)
            if (!input.includeClosed) find.condition("closedStamp", null)
            Map windowCondition = input.window ? ReadConditions.windowCondition([field: "firstFoundDate", lastDays: ((Map) input.window).lastDays],
                    runDate, params.timeZone as String) : null
            EntityListIterator findings = find.iterator()
            try {
                Iterator<Map> rows = [hasNext: { -> findings.hasNext() }, next: { ->
                    def f = findings.next()
                    String checkId = f.checkId as String
                    if (!checkLabel.containsKey(checkId)) {
                        checkLabel.put(checkId, ec.entity.find("darpan.audit.AuditCheck").condition("checkId", checkId).useCache(false).one()?.label as String)
                    }
                    String conclusionId = f.conclusionEnumId as String
                    if (conclusionId && !conclusionText.containsKey(conclusionId)) {
                        conclusionText.put(conclusionId, ec.entity.find("moqui.basic.Enumeration").condition("enumId", conclusionId).useCache(true).one()?.description as String)
                    }
                    return [statement: statementText[f.statementId as String], check: checkLabel[checkId], recordKey: f.recordKey,
                            period: f.period, state: f.stateEnumId, headline: f.headline,
                            firstFoundDate: f.firstFoundDate?.toString(), lastSeenDate: f.lastSeenDate?.toString(), timesSeen: f.timesSeen,
                            expectedAmount: f.expectedAmount, actualAmount: f.actualAmount, differenceAmount: f.differenceAmount,
                            currencyUomId: f.currencyUomId, conclusion: conclusionId ? conclusionText[conclusionId] : null,
                            closedStamp: (f.closedStamp as Timestamp)?.toInstant()?.toString()] as Map
                }] as Iterator<Map>
                return QueryRows.collect(rows, input, windowCondition, maxRows)
            } finally {
                findings.close()
            }
        }
    }
}
