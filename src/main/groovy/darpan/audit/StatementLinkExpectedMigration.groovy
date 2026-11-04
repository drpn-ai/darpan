package darpan.audit

import darpan.reconciliation.conclusion.RunConclusionSupport
import org.moqui.entity.EntityCondition

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-196, migration STATEMENT_LINK_EXPECTED. A statement link used to list the causes that break its statement
 * (AuditStatementCheck.conclusionCodes); the ones it left out were the expected ones. Being expected is a property of the
 * cause, so each list moves onto its check's run: every conclusion rule whose cause the list left out gets outcome
 * EXPECTED (a HIDE rule stays hidden), then the list is cleared. Unexplained was never a rule, so it now counts on every
 * statement (Aditi 2026-11-04). A run two links read with different lists is a conflict: reported, left untouched.
 * Idempotent: a second run finds no list and changes nothing. Runs with authz disabled (the supervisor), so every read
 * names its tenant.
 */
class StatementLinkExpectedMigration {

    static Map<String, Object> run(def ec, boolean dryRun) {
        List<String> messages = []
        int rulesUpdated = 0
        int linksCleared = 0
        List links = ec.entity.find("darpan.audit.AuditStatementCheck").useCache(false).list()
                .findAll { normalize(it.conclusionCodes) != null }   // EntityList.findAll casts the result to Boolean
        // tenant|run -> the links that read it, so one run gets one answer.
        Map<String, List> byRun = new LinkedHashMap<>()
        links.each { link ->
            def check = ec.entity.find("darpan.audit.AuditCheck").condition("checkId", link.checkId)
                    .condition("companyUserGroupId", link.companyUserGroupId).useCache(false).one()
            String runId = normalize(check?.savedRunId)
            if (!runId) { messages << "Link ${link.statementId}/${link.checkId}: its check has no run; list cleared, nothing to mark.".toString() }
            byRun.computeIfAbsent("${link.companyUserGroupId}|${runId ?: ''}".toString()) { [] }.add(link)
        }
        byRun.each { String key, List runLinks ->
            String tenantId = key.substring(0, key.indexOf('|'))
            String runId = key.substring(key.indexOf('|') + 1) ?: null
            List<Set<String>> lists = runLinks.collect { codesOf(it.conclusionCodes) }.unique()
            if (runId && lists.size() > 1) {
                messages << "${tenantId} run ${runId}: its links list different causes (${lists.collect { it.join(' ') }.join(' / ')}); left as it is.".toString()
                return
            }
            if (runId) {
                Set<String> listed = lists[0]
                List<String> scopeIds = ec.entity.find("darpan.rule.RuleSetCompareScope").condition("ruleSetId", runId)
                        .condition("companyUserGroupId", tenantId).useCache(false).list()*.compareScopeId.collect { it as String }
                List rules = scopeIds ? ec.entity.find("darpan.rule.RuleSetConclusionRule")
                        .condition("compareScopeId", EntityCondition.IN, scopeIds).condition("companyUserGroupId", tenantId)
                        .orderBy("sequenceNum").useCache(false).list() : []
                List marked = rules.findAll {
                    it.conclusionEnumId && !listed.contains(it.conclusionEnumId as String) && !normalize(it.outcome)
                }
                if (marked) {
                    messages << "${tenantId} run ${runId}: expected ${marked*.conclusionEnumId.unique().join(', ')}.".toString()
                }
                if (!listed.contains("UNEXPLAINED")) messages << "${tenantId} run ${runId}: Unexplained now counts.".toString()
                if (!dryRun) marked.each { it.set("outcome", RunConclusionSupport.OUTCOME_EXPECTED); it.update() }
                rulesUpdated += marked.size()
            }
            if (!dryRun) runLinks.each { it.set("conclusionCodes", null); it.update() }
            linksCleared += runLinks.size()
        }
        messages.add(0, "${dryRun ? 'Would mark' : 'Marked'} ${rulesUpdated} rule(s) expected and ${dryRun ? 'clear' : 'cleared'} ${linksCleared} link list(s).".toString())
        return [rowsUpdated: rulesUpdated, linksCleared: linksCleared, messages: messages]
    }

    private static Set<String> codesOf(Object csv) {
        return ((csv ?: "") as String).split(",")*.trim().findAll { it } as Set<String>
    }
}
