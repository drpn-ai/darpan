package darpan.agent.pipeline

import darpan.audit.AuditAutomationSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.facade.reconciliation.RunConclusionStep
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.ExtractReplaySupport
import darpan.reconciliation.conclusion.ConclusionKeySupport
import darpan.reconciliation.conclusion.JsonRecordStream
import darpan.reconciliation.conclusion.RunConclusionSupport
import darpan.reconciliation.source.SourceFilterSupport
import org.moqui.entity.EntityCondition

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-159. What an agent's proposed change would do to the audit, before a person approves it: which open findings
 * would close and which records would newly raise one. Computed only from data already landed (a succeeded execution's
 * extract and result document, or the findings ledger itself): no system is read live and nothing is written, no
 * finding, execution, ledger row, pending mark or file.
 *
 * <p>Open means not fixed (AUDFS_OPEN or AUDFS_PENDING). {@code closed} lists findingIds of open findings the change
 * would stop reporting; {@code opened} lists recordKeys the change would newly report; {@code unchanged} counts the open
 * findings in scope that stay. Per kind:</p>
 * <ul>
 *   <li>EXCLUSION_FILTER: the candidate filters of the side, in memory, over the extract of the latest succeeded
 *   execution of each automation of the run that still has it; open findings of the run's checks whose recordKey the
 *   filters now drop are closed. An exclusion only removes records, so nothing opens.</li>
 *   <li>STATEMENT_NARROWING: from AuditFinding rows alone; the target statement's open findings whose conclusion is
 *   outside the new codes are closed. A finding does not record its diff type or rule, so a change to those is not
 *   backtested (DIFF_TYPE_NOT_ON_FINDINGS); rows alone cannot show what a wider set would open.</li>
 *   <li>FIX_TEXT: display only.</li>
 *   <li>CONCLUSION_ROWS: the run's conclude path (RunConclusionSupport.concludeEach) over the basis execution's result
 *   rows twice, saved rows then candidate rows; a record whose conclusion changes is closed where a statement stops
 *   counting it and opened where one starts.</li>
 * </ul>
 */
class AuditChangeBacktest {

    static final List<String> OPEN_STATES = ["AUDFS_OPEN", "AUDFS_PENDING"].asImmutable()
    /** Succeeded executions looked at per automation, newest first, for one whose files are still on disk. */
    static final int BASIS_LOOKBACK = 20
    private static final String WHY = "agent backtest: explicit companyUserGroupId condition applied"

    static Map run(def ec, String tenantId, String kind, Map target, Map merged) {
        // The planner pins the proposal's tenant; it must be the session's active tenant, whose rows every read here is.
        if (!tenantId || tenantId != TenantAccessSupport.currentActiveTenantUserGroupId(ec)) return unsupported("TENANT_MISMATCH")
        if (merged == null) return unsupported("NO_CHANGE")
        try {
            switch (kind) {
                case "EXCLUSION_FILTER": return exclusion(ec, tenantId, target, merged)
                case "STATEMENT_NARROWING": return narrowing(ec, tenantId, target, merged)
                case "FIX_TEXT": return result(true, "DISPLAY_ONLY", null, [], [], 0)
                case "CONCLUSION_ROWS": return conclusionRows(ec, tenantId, target, merged)
                default: return unsupported("UNKNOWN_KIND")
            }
        } catch (RuntimeException e) {
            // A backtest never fails a plan: the person sees why there is no preview.
            return unsupported("BACKTEST_FAILED: ${normalize(e.message) ?: e.class.simpleName}".toString())
        }
    }

    // ---------------------------------------------------------------- EXCLUSION_FILTER

    private static Map exclusion(def ec, String tenantId, Map target, Map merged) {
        String savedRunId = target.savedRunId as String
        String side = target.fileSide as String
        def scope = scopeOf(ec, tenantId, savedRunId)
        if (scope == null) return unsupported("NO_RUN")
        Map key = RunConclusionStep.keySpecFor(ec, scope.compareScopeId as String, side)
        if (!key.keyReliable) return unsupported("KEY_NOT_REBUILDABLE")
        List<Map> candidate = ((List<Map>) (((Map) merged.filters)?.get(side) ?: [])).withIndex().collect { Map f, int i ->
            [sequenceNum: i + 1, fieldExpression: f.fieldExpression, operator: f.operator, filterValues: f.filterValues] as Map<String, Object>
        }
        List<Map<String, Object>> rules = SourceFilterSupport.parseRules(SourceFilterSupport.toRecordFieldRules(candidate))
        List<Map> bases = bases(ec, tenantId, savedRunId, [side])
        if (!bases) return unsupported("NO_LANDED_EXTRACT")

        Set<String> dropped = new LinkedHashSet<String>()
        bases.each { Map basis ->
            JsonRecordStream.forEachRecord((File) ((Map) ((Map) basis.files)[side]).file, "records") { Map record ->
                if (SourceFilterSupport.evaluate(record, rules) == null) return
                String recordKey = ConclusionKeySupport.compareKey(record, (List<String>) key.keyFields, (String) key.idNormalizer)
                if (recordKey) dropped.add(recordKey)
            }
        }
        List open = openFindings(ec, tenantId, checkIdsOf(ec, tenantId, savedRunId), null)
        List<String> closed = open.findAll { dropped.contains(it.recordKey as String) }*.findingId.collect { it as String }.sort()
        return result(true, null, basisId(bases), closed, [], open.size() - closed.size())
    }

    // ---------------------------------------------------------------- STATEMENT_NARROWING

    private static Map narrowing(def ec, String tenantId, Map target, Map merged) {
        Map newLink = ((List<Map>) (merged.statements ?: [])).find { it.id == target.statementCode }
        Map check = (Map) merged.check
        def checkRow = check ? ec.entity.find("darpan.audit.AuditCheck").condition("companyUserGroupId", tenantId)
                .condition("checkCode", check.id).useCache(false).list().find() : null
        def audit = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.Audit", "auditId", target.auditId, tenantId, WHY) ?:
                ec.entity.find("darpan.audit.Audit").condition("companyUserGroupId", tenantId).condition("auditCode", merged.audit)
                        .useCache(false).list().find()
        def statement = audit ? ec.entity.find("darpan.audit.AuditStatement").condition("companyUserGroupId", tenantId)
                .condition("auditId", audit.auditId).condition("statementCode", target.statementCode).useCache(false).list().find() : null
        if (newLink == null || checkRow == null || statement == null) return unsupported("NO_STATEMENT")
        def oldLink = ec.entity.find("darpan.audit.AuditStatementCheck").condition("companyUserGroupId", tenantId)
                .condition("checkId", checkRow.checkId).condition("statementId", statement.statementId).useCache(false).list().find()
        if (oldLink == null) return unsupported("NO_STATEMENT")
        // What a finding records is its conclusion; its diff type and rule are not kept, so only the codes are backtested.
        if (csvSet(oldLink.diffTypes) != listSet(newLink.diffTypes) || csvSet(oldLink.ruleIds) != listSet(newLink.ruleIds)) {
            return unsupported("DIFF_TYPE_NOT_ON_FINDINGS")
        }
        Set<String> codes = listSet(newLink.conclusionCodes)
        List open = openFindings(ec, tenantId, [checkRow.checkId as String], statement.statementId as String)
        Map<String, String> codeOfEnum = [:]
        List<String> closed = codes ? open.findAll { f ->
            String enumId = normalize(f.conclusionEnumId)
            !(enumId && (codes.contains(enumId) || codes.contains(enumCode(ec, enumId, codeOfEnum))))
        }*.findingId.collect { it as String }.sort() : []
        return result(true, null, null, closed, [], open.size() - closed.size())
    }

    // ---------------------------------------------------------------- CONCLUSION_ROWS

    private static Map conclusionRows(def ec, String tenantId, Map target, Map merged) {
        String savedRunId = target.savedRunId as String
        def scope = scopeOf(ec, tenantId, savedRunId)
        if (scope == null) return unsupported("NO_RUN")
        String compareScopeId = scope.compareScopeId as String
        // The brain and lookups read systems live; the backtest reads nothing live.
        if (RunConclusionStep.brainKind(scope.conclusionBrain)) return unsupported("BRAIN_SCOPE")
        if (normalize(scope.conclusionLookupsJson) && normalize(scope.conclusionLookupsJson) != "[]") return unsupported("NEEDS_LIVE_LOOKUP")
        List<Map> saved = RunConclusionStep.scopeRules(ec, compareScopeId)
        // A hidden row left the result document, so its new conclusion cannot be seen; and the tree's save writes no HIDE.
        if (saved.any { it.outcome == RunConclusionSupport.OUTCOME_HIDE }) return unsupported("HIDDEN_ROWS")
        List<Map> candidate = candidateRules(ec, (List<Map>) (merged.conclusions ?: []))

        boolean evaluate = "EVALUATE".equalsIgnoreCase(scope.scopeMode as String)
        List<String> sides = evaluate ? [ExtractReplaySupport.SIDE_1] : [ExtractReplaySupport.SIDE_1, ExtractReplaySupport.SIDE_2]
        List<Map> bases = bases(ec, tenantId, savedRunId, sides + [ExtractReplaySupport.RESULT])
        if (!bases) return unsupported("NO_LANDED_EXTRACT")

        List<String> checkIds = checkIdsOf(ec, tenantId, savedRunId)
        List<Map> links = linksOf(ec, tenantId, checkIds)
        Set<String> changedKeys = new HashSet<String>()
        Set<String> savedReported = new HashSet<String>()
        Set<String> candidateReported = new HashSet<String>()
        bases.each { Map basis ->
            Map args = conclusionArgs(ec, compareScopeId, basis, evaluate)
            List<String> savedCodes = []
            RunConclusionSupport.concludeEach(args + [rules: saved]) { Map row, Map conclusion -> savedCodes << (conclusion.code as String) }
            int i = 0
            RunConclusionSupport.concludeEach(args + [rules: candidate]) { Map row, Map conclusion ->
                String before = savedCodes[i++]
                String after = conclusion.code as String
                String recordKey = (row.primaryId ?: row.id) as String
                if (!recordKey) return
                if (before != after) changedKeys.add(recordKey)
                String type = (row.diffType ?: row.type) as String
                String ruleId = normalize(row.ruleId)
                links.each { Map link ->
                    String id = "${link.checkId}|${link.statementId}|${recordKey}".toString()
                    if (counts(link, type, before, ruleId)) savedReported.add(id)
                    if (counts(link, type, after, ruleId)) candidateReported.add(id)
                }
            }
        }
        List open = openFindings(ec, tenantId, checkIds, null)
        List<String> closed = open.findAll { f ->
            changedKeys.contains(f.recordKey as String) && !candidateReported.contains("${f.checkId}|${f.statementId}|${f.recordKey}".toString())
        }*.findingId.collect { it as String }.sort()
        Set<String> openIds = open.collect { "${it.checkId}|${it.statementId}|${it.recordKey}".toString() } as Set<String>
        List<String> opened = (candidateReported - savedReported - openIds).collect { it.substring(it.indexOf('|', it.indexOf('|') + 1) + 1) }
                .unique().sort()
        return result(true, null, basisId(bases), closed, opened, open.size() - closed.size())
    }

    /** The candidate tree as save#RunConclusions would store it, then as the conclude step compiles it. */
    private static List<Map> candidateRules(def ec, List<Map> nodes) {
        Map<String, Integer> seqByKey = [:]
        List<Map> rows = []
        List<Map> conditionRows = []
        nodes.eachWithIndex { Map n, int i ->
            int seq = (i + 1) * 10
            seqByKey[n.key as String] = seq
            String parent = normalize(n.parent)
            rows << [sequenceNum: seq, parentSequenceNum: parent ? seqByKey[parent] : null, conclusionEnumId: normalize(n.conclusion),
                     appliesToBucket: normalize(n.bucket)?.toUpperCase(), questionText: normalize(n.question)]
            ((List<Map>) (n.conditions ?: [])).eachWithIndex { Map c, int j ->
                conditionRows << [sequenceNum: seq, conditionSeq: j + 1, subject: (normalize(c.subject) ?: "FILE_1").toUpperCase(),
                                  presence: (normalize(c.presence) ?: "ANY").toUpperCase(), keyScope: (normalize(c.keyScope) ?: "FULL").toUpperCase(),
                                  quantifier: (normalize(c.quantifier) ?: "ALL").toUpperCase(), fieldExpression: normalize(c.field),
                                  operator: normalize(c.operator)?.toUpperCase(),
                                  conditionValues: ((c.values ?: []) as List).collect { normalize(it) }.findAll { it }.join(","),
                                  checkLabel: normalize(c.checkLabel)]
            }
        }
        return RunConclusionStep.buildRules(rows, conditionRows, RunConclusionStep.labelsOf(ec, rows))
    }

    /** RunConclusionSupport arguments for one basis: its landed result document and extracts, keyed as the compare keyed them. */
    private static Map conclusionArgs(def ec, String compareScopeId, Map basis, boolean evaluate) {
        Map files = (Map) basis.files
        File diffFile = (File) ((Map) files[ExtractReplaySupport.RESULT]).file
        Map labels = RunConclusionStep.documentLabels(diffFile)
        boolean singleSided = evaluate || labels.file2Label == null
        Closure<Map> spec = { String side ->
            def source = ec.entity.find("darpan.reconciliation.ReconciliationAutomationSource").condition("companyUserGroupId", basis.tenantId)
                    .condition("automationId", basis.automationId).condition("fileSide", side).useCache(false).list().find()
            RunConclusionStep.sideSpec(ec, compareScopeId, side, source, [fileLocation: ((File) ((Map) files[side]).file).absolutePath])
        }
        return [diffFile: diffFile, file1Label: labels.file1Label, file2Label: labels.file2Label, singleSided: singleSided,
                file1: spec(ExtractReplaySupport.SIDE_1), file2: singleSided ? null : spec(ExtractReplaySupport.SIDE_2)]
    }

    /** As AuditAutomationSupport.findingsFrom decides it: the row's type, conclusion and rule are each one the link counts. */
    private static boolean counts(Map link, String type, String code, String ruleId) {
        return AuditAutomationSupport.matches(link.diffTypes as String, type) &&
                AuditAutomationSupport.matches(link.conclusionCodes as String, code) &&
                AuditAutomationSupport.matches(link.ruleIds as String, ruleId)
    }

    // ---------------------------------------------------------------- shared reads

    /**
     * Per automation of the run: its latest succeeded execution whose named files are all still on disk.
     * [automationId, executionId, tenantId, files: ExtractReplaySupport.landedFilesOfSucceeded].
     */
    private static List<Map> bases(def ec, String tenantId, String savedRunId, List<String> needed) {
        List<Map> out = []
        automationIdsOf(ec, tenantId, savedRunId).each { String automationId ->
            List executions = ec.entity.find("darpan.reconciliation.ReconciliationAutomationExecution")
                    .condition("companyUserGroupId", tenantId).condition("automationId", automationId)
                    .condition("statusEnumId", AutomationExecutionSupport.STATUS_SUCCEEDED)
                    .orderBy(["-completedDate", "-automationExecutionId"]).limit(BASIS_LOOKBACK).useCache(false).list()
            for (def execution : executions) {
                Map files = ExtractReplaySupport.landedFilesOfSucceeded(ec, tenantId, execution.automationExecutionId as String)
                if (needed.every { files.containsKey(it) }) {
                    out << [automationId: automationId, executionId: execution.automationExecutionId, tenantId: tenantId, files: files]
                    break
                }
            }
        }
        return out
    }

    private static List<String> automationIdsOf(def ec, String tenantId, String savedRunId) {
        return ec.entity.find("darpan.reconciliation.ReconciliationAutomation").condition("companyUserGroupId", tenantId)
                .condition("savedRunId", savedRunId).orderBy("automationId").useCache(false).list()
                .findAll { (normalize(it.savedRunType) ?: "ruleset").equalsIgnoreCase("ruleset") }*.automationId.collect { it as String }
    }

    /** The run's audit checks: those naming the run, and those its automations run. */
    private static List<String> checkIdsOf(def ec, String tenantId, String savedRunId) {
        List<String> automations = automationIdsOf(ec, tenantId, savedRunId)
        EntityCondition byRun = ec.entity.conditionFactory.makeCondition("savedRunId", EntityCondition.EQUALS, savedRunId)
        EntityCondition either = automations ? ec.entity.conditionFactory.makeCondition([byRun,
                ec.entity.conditionFactory.makeCondition("automationId", EntityCondition.IN, automations)], EntityCondition.OR) : byRun
        return ec.entity.find("darpan.audit.AuditCheck").condition("companyUserGroupId", tenantId).condition(either)
                .useCache(false).list()*.checkId.collect { it as String }.unique()
    }

    /** The checks' links to their active statements. */
    private static List<Map> linksOf(def ec, String tenantId, List<String> checkIds) {
        if (!checkIds) return []
        List<Map> links = ec.entity.find("darpan.audit.AuditStatementCheck").condition("companyUserGroupId", tenantId)
                .condition("checkId", EntityCondition.IN, checkIds).useCache(false).list()*.getMap()
        Set<String> active = (links ? ec.entity.find("darpan.audit.AuditStatement").condition("companyUserGroupId", tenantId)
                .condition("statementId", EntityCondition.IN, links*.statementId).useCache(false).list()
                .findAll { (it.isActive ?: "Y") != "N" }*.statementId.collect { it as String } : []) as Set<String>
        return links.findAll { active.contains(it.statementId as String) }
    }

    private static List openFindings(def ec, String tenantId, List<String> checkIds, String statementId) {
        if (!checkIds) return []
        def find = ec.entity.find("darpan.audit.AuditFinding").condition("companyUserGroupId", tenantId)
                .condition("checkId", EntityCondition.IN, checkIds).condition("stateEnumId", EntityCondition.IN, OPEN_STATES)
        if (statementId) find.condition("statementId", statementId)
        return find.orderBy("findingId").useCache(false).list()
    }

    private static def scopeOf(def ec, String tenantId, String savedRunId) {
        if (!savedRunId) return null
        return ec.entity.find("darpan.rule.RuleSetCompareScope").condition("companyUserGroupId", tenantId)
                .condition("ruleSetId", savedRunId).useCache(false).list().find()
    }

    private static String enumCode(def ec, String enumId, Map<String, String> cache) {
        if (!cache.containsKey(enumId)) {
            cache[enumId] = ec.entity.find("moqui.basic.Enumeration").condition("enumId", enumId).useCache(true).one()?.enumCode as String
        }
        return cache[enumId]
    }

    private static Set<String> csvSet(Object v) {
        String s = normalize(v)
        return (s ? s.split(",").collect { it.trim() }.findAll { it } : []) as Set<String>
    }

    private static Set<String> listSet(Object v) {
        return (((v ?: []) as List).collect { normalize(it) }.findAll { it }) as Set<String>
    }

    private static String basisId(List<Map> bases) {
        return bases*.executionId.collect { it as String }.join(",")
    }

    private static Map unsupported(String reason) {
        return result(false, reason, null, [], [], 0)
    }

    private static Map result(boolean supported, String reason, String basisExecutionId, List<String> closed, List<String> opened,
                              int unchanged) {
        return [supported: supported, reason: reason, basisExecutionId: basisExecutionId, closed: closed, opened: opened,
                unchanged: unchanged]
    }
}
