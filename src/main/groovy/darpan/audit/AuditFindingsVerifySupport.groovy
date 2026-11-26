package darpan.audit

import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.conclusion.ConclusionKeySupport
import org.moqui.entity.EntityCondition

import java.sql.Timestamp

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-369. "Re-check all": every open finding of one statement, or of the whole audit, read again now by key, as
 * Resolve (DAR-BE-123, verify#AuditFinding) does for one. Aditi 2026-11-25 on RAILS R10: store fulfillments reached
 * NetSuite hours after the 02:06 check, and closing 26 findings meant 26 Resolves, each its own run and its own reads.
 * Here one verify run per automation carries all its keys: a run re-judges every check of its automation for those keys,
 * so checks sharing a saved run share the run. Each side reads the keys by their first field, at most
 * AuditRecheckSupport.MAX_KEYS of them a run. Like Resolve it writes no check run: health and the next read are unchanged.
 * Every read is pinned to the tenant with an explicit companyUserGroupId condition.
 */
class AuditFindingsVerifySupport {

    private static final String WHY = "audit re-check all, active tenant: explicit companyUserGroupId condition applied"

    /**
     * findings: [findingId, checkId, recordKey]; automationByCheck: checkId -> automationId. Answers
     * [runs: [[automationId, recordKeys]], skipped: [findingId whose check has no automation]]. Runs follow the
     * automations' first appearance; within one, a record key appears once and the batches split on the first key field
     * (an order's lines ride together), at most maxKeys first keys a run.
     */
    static Map plan(List<Map> findings, Map<String, String> automationByCheck, int maxKeys) {
        Map<String, Map<String, LinkedHashSet<String>>> byAutomation = [:]   // automation -> first key -> record keys
        List<String> skipped = []
        (findings ?: []).each { Map f ->
            String automationId = normalize(automationByCheck?.get(f.checkId as String))
            String key = normalize(f.recordKey as String)
            if (!key) return
            if (!automationId) { skipped << (f.findingId as String); return }
            byAutomation.computeIfAbsent(automationId) { new LinkedHashMap() }
                    .computeIfAbsent(ConclusionKeySupport.firstField(key)) { new LinkedHashSet<String>() } << key
        }
        List<Map> runs = []
        byAutomation.each { String automationId, Map<String, LinkedHashSet<String>> byFirst ->
            byFirst.keySet().toList().collate(Math.max(1, maxKeys)).each { List<String> firsts ->
                runs << [automationId: automationId, recordKeys: firsts.collectMany { byFirst[it] as List<String> }]
            }
        }
        return [runs: runs, skipped: skipped]
    }

    /**
     * Submits the re-check of the audit's open findings in one period, of one statement (by code) or all. Refused in words
     * (answer.error) when the statement is not the audit's. submit(params) defaults to the automation runner; a test
     * passes its own. Answers [findings, runs, skipped, checkedSince] (checkedSince: before the first submit, for
     * progress()).
     */
    static Map verify(def ec, String tenantId, Map audit, String statementCode, String period,
                      Closure<Map> submit = null) {
        List<String> statementIds = statementIds(ec, tenantId, audit, statementCode)
        if (statementIds == null) return [error: "Statement ${statementCode} is not part of audit ${audit.auditCode}.".toString()]
        List<Map> open = statementIds ? find(ec, tenantId, "darpan.audit.AuditFinding")
                .condition("statementId", EntityCondition.IN, statementIds).condition("period", normalize(period))
                .condition("stateEnumId", "AUDFS_OPEN").selectField("findingId,checkId,recordKey").orderBy("findingId")
                .list()*.getMap() : []
        List<String> checkIds = open*.checkId.unique() as List<String>
        Map<String, String> automationByCheck = checkIds ? find(ec, tenantId, "darpan.audit.AuditCheck")
                .condition("checkId", EntityCondition.IN, checkIds).list()
                .collectEntries { [(it.checkId as String): normalize(it.automationId as String)] } : [:]
        Map plan = plan(open, automationByCheck, AuditRecheckSupport.MAX_KEYS)
        Timestamp since = ec.user.nowTimestamp
        Closure<Map> send = submit ?: { Map params -> AutomationExecutionSupport.submitExecuteAutomationService(ec, params, tenantId) }
        int submitted = 0
        (plan.runs as List<Map>).each { Map run ->
            Map out = send([automationId: run.automationId, scheduledFireTime: since, auditVerifyKeys: run.recordKeys]) ?: [:]
            if (out.submitted == true) submitted++
            else ec.message.addError("Re-check of ${run.automationId} could not start: ${out.error ?: 'not submitted'}")
        }
        return [findings: open.size() - (plan.skipped as List).size(), runs: submitted, skipped: (plan.skipped as List).size(),
                checkedSince: since]
    }

    /**
     * How far a re-check started at `since` has come: of the audit's findings in the period (one statement or all),
     * those checked since then, and of those how many are now closed. A finding whose read did not finish is not
     * checked, so a caller stops waiting after its own limit.
     */
    static Map progress(def ec, String tenantId, Map audit, String statementCode, String period, Object sinceGiven) {
        Timestamp since = sinceStamp(sinceGiven)
        List<String> statementIds = statementIds(ec, tenantId, audit, statementCode)
        if (statementIds == null) return [error: "Statement ${statementCode} is not part of audit ${audit.auditCode}.".toString()]
        List<Map> checked = statementIds && since ? find(ec, tenantId, "darpan.audit.AuditFinding")
                .condition("statementId", EntityCondition.IN, statementIds).condition("period", normalize(period))
                .condition("lastCheckedStamp", EntityCondition.GREATER_THAN_EQUAL_TO, since)
                .selectField("findingId,stateEnumId").list()*.getMap() : []
        return [checked: checked.size(), closed: checked.count { it.stateEnumId == "AUDFS_FIXED" } as int]
    }

    /**
     * DAR-BE-375. `since` as the page sends it back: verify#AuditFindings' checkedSince reaches JSON as epoch millis, and
     * the page returns that number, which the service's Timestamp parameter does not convert (prod 3.4.10-3.4.12: "No
     * signature of method ... progress ... Long"). A Timestamp, epoch millis (number or digits) or an ISO instant; else null.
     */
    static Timestamp sinceStamp(Object since) {
        if (since instanceof Timestamp) return (Timestamp) since
        if (since instanceof Date) return new Timestamp(((Date) since).time)
        if (since instanceof Number) return new Timestamp(((Number) since).longValue())
        String text = since?.toString()?.trim()
        if (!text) return null
        if (text ==~ /\d{10,}/) return new Timestamp(text as long)
        try { return Timestamp.from(java.time.Instant.parse(text)) } catch (Exception ignored) { }
        try { return Timestamp.valueOf(text) } catch (Exception ignored) { }
        return null
    }

    /** The audit's statement ids: all of them for a blank code, the one with that code, or null when it has none. */
    private static List<String> statementIds(def ec, String tenantId, Map audit, String statementCode) {
        def q = find(ec, tenantId, "darpan.audit.AuditStatement").condition("auditId", audit.auditId)
        String code = normalize(statementCode)
        if (code) q = q.condition("statementCode", code)
        List<String> ids = q.list()*.statementId.collect { it as String }
        return code && !ids ? null : ids
    }

    private static def find(def ec, String tenantId, String entityName) {
        return darpan.facade.common.TenantScopedFinder.findGlobalUnscoped(ec, entityName, WHY)
                .condition("companyUserGroupId", tenantId).useCache(false)
    }
}
