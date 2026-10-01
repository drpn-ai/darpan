package darpan.reconciliation.flowchart

import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.facade.reconciliation.RunObservability

import static darpan.common.ValueSupport.normalize

/**
 * DAR-UI-048. Moqui wiring for the run flowchart: tenant-scoped load and save, and the production
 * runner and recorders the walker is given. Every tree rule lives in RunFlowchartTree; this class only
 * gathers what it needs and writes what it decides.
 */
class RunFlowchartSupport {

    static final String SAVED_RUN_TYPE = "reconciliation"
    static final String RECONCILIATION = "darpan.reconciliation.Reconciliation"
    static final String QUESTION = "darpan.reconciliation.ReconciliationRun"
    static final String RUN_RESULT = "darpan.reconciliation.ReconciliationRunResult"
    static final String QUESTION_SERVICE = "reconciliation.ReconciliationFlowchartServices.run#FlowchartQuestion"

    static def findReconciliation(def ec, String reconciliationId) {
        return reconciliationId ? TenantScopedFinder.findTenantScopedByIdQuiet(ec, RECONCILIATION, "reconciliationId", reconciliationId) : null
    }

    static List<Map> loadQuestions(def ec, String reconciliationId) {
        List rows = TenantScopedFinder.findTenantScoped(ec, QUESTION)
                .condition("reconciliationId", reconciliationId).useCache(false).list() ?: []
        return rows.collect { def row ->
            String ruleSetId = row.get("ruleSetId") as String
            [reconciliationRunId      : row.get("reconciliationRunId"),
             parentReconciliationRunId: row.get("parentReconciliationRunId"),
             parentBranch             : row.get("parentBranch"),
             questionRole             : row.get("questionRole"),
             runSequence              : row.get("runSequence"),
             isActive                 : row.get("isActive"),
             runName                  : row.get("runName"),
             noOutcomeLabel           : row.get("noOutcomeLabel"),
             ruleSetId                : ruleSetId] + shapeFor(ec, ruleSetId)
        } as List<Map>
    }

    /**
     * What the tree rules need from a question's rule set: its mode, which records it starts from, and
     * (final review I5) how it builds its FILE_1 key, so a same-system parent and child can be checked.
     */
    static Map shapeFor(def ec, String ruleSetId) {
        def scope = ruleSetId ? TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSetCompareScope")
                .condition("ruleSetId", ruleSetId).useCache(false).list()?.find() : null
        def file1 = scope ? TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSetCompareSource")
                .condition("compareScopeId", scope.get("compareScopeId")).condition("fileSide", "FILE_1")
                .useCache(false).one() : null
        List keyFields = scope ? (TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSetCompareSourceKeyField")
                .condition("compareScopeId", scope.get("compareScopeId")).condition("fileSide", "FILE_1")
                .orderBy("sequenceNum").useCache(false).list() ?: []).collect { it.get("fieldExpression") } : []
        return [scopeMode     : (scope?.get("scopeMode") ?: RunFlowchartTree.MODE_COMPARE) as String,
                file1Signature: "${file1?.get('systemEnumId')}|${file1?.get('sourceConfigId')}".toString(),
                file1System   : file1?.get("systemEnumId") as String,
                file1Key      : "${file1?.get('primaryIdExpression') ?: ''}|${file1?.get('idValueNormalizer') ?: ''}|${keyFields.join(',')}".toString()]
    }

    static Map saveReconciliation(def ec, Map params) {
        if (!TenantAccessSupport.requireActiveTenantWriteAccess(ec, "Your active tenant only has view access for runs.")) return [:]
        String id = normalize(params.reconciliationId)
        def value = id ? findReconciliation(ec, id) : null
        if (id && value == null) { ec.message.addError("Run '${id}' was not found."); return [:] }
        if (value == null) {
            value = ec.entity.makeValue(RECONCILIATION)
            // Not assignTenantOwnershipOnCreate: it also stamps createdByUserId, which Reconciliation
            // does not have, and an EntityValue refuses an undeclared field.
            value.set("companyUserGroupId", TenantAccessSupport.currentActiveTenantUserGroupId(ec))
            value.setSequencedIdPrimary()
        }
        ["reconciliationName", "description", "defaultTimeWindow", "isActive", "isArchived"].each { String k ->
            if (params.containsKey(k)) value.set(k, normalize(params.get(k)))
        }
        if (id) value.update() else value.create()
        return [reconciliation: value.getMap()]
    }

    static Map saveQuestion(def ec, Map params) {
        if (!TenantAccessSupport.requireActiveTenantWriteAccess(ec, "Your active tenant only has view access for runs.")) return [:]
        String reconciliationId = normalize(params.reconciliationId)
        def reconciliation = findReconciliation(ec, reconciliationId)
        if (reconciliation == null) { ec.message.addError("Run '${reconciliationId}' was not found."); return [:] }
        String ruleSetId = normalize(params.ruleSetId)
        if (!ruleSetId || TenantScopedFinder.findTenantScopedByIdQuiet(ec, "darpan.rule.RuleSet", "ruleSetId", ruleSetId) == null) {
            ec.message.addError("Rule '${ruleSetId}' was not found."); return [:]
        }
        String id = normalize(params.reconciliationRunId)
        // Deleted (inactive) questions neither show nor constrain the tree.
        List<Map> existing = loadQuestions(ec, reconciliationId).findAll { it.isActive != "N" }
        if (id && !existing.any { it.reconciliationRunId == id }) { ec.message.addError("Question '${id}' was not found."); return [:] }

        Map candidate = (existing.find { it.reconciliationRunId == id } ?: [:]) + [
                reconciliationRunId      : id ?: "__NEW__",
                ruleSetId                : ruleSetId,
                parentReconciliationRunId: normalize(params.parentReconciliationRunId),
                parentBranch             : normalize(params.parentBranch)?.toUpperCase(),
                questionRole             : normalize(params.questionRole)?.toUpperCase(),
                runSequence              : params.runSequence,
                isActive                 : normalize(params.isActive),
        ]
        Map shaped = loadQuestionShape(ec, candidate)
        List<String> errors = RunFlowchartTree.validate(existing.findAll { it.reconciliationRunId != id } + [shaped])
        if (errors) { errors.each { ec.message.addError(it) }; return [:] }

        def value = id ? TenantScopedFinder.findTenantScopedByIdQuiet(ec, QUESTION, "reconciliationRunId", id) : ec.entity.makeValue(QUESTION)
        if (!id) value.setSequencedIdPrimary()
        value.setAll([reconciliationId         : reconciliationId,
                      companyUserGroupId       : reconciliation.get("companyUserGroupId"),
                      ruleSetId                : ruleSetId,
                      runName                  : normalize(params.runName),
                      parentReconciliationRunId: candidate.parentReconciliationRunId,
                      parentBranch             : candidate.parentBranch,
                      questionRole             : candidate.questionRole,
                      noOutcomeLabel           : normalize(params.noOutcomeLabel),
                      runSequence              : params.runSequence,
                      isActive                 : candidate.isActive])
        if (id) value.update() else value.create()
        return [question: value.getMap()]
    }

    private static Map loadQuestionShape(def ec, Map candidate) {
        return candidate + shapeFor(ec, candidate.ruleSetId as String)
    }

    static Map getReconciliation(def ec, String reconciliationId) {
        def reconciliation = findReconciliation(ec, reconciliationId)
        if (reconciliation == null) { ec.message.addError("Run '${reconciliationId}' was not found."); return [:] }
        // Inactive questions are deleted ones (soft delete); the chart never shows them.
        return [reconciliation: reconciliation.getMap(), questions: loadQuestions(ec, reconciliationId).findAll { it.isActive != "N" }]
    }

    /**
     * Final review I3: everything that would make a walk impossible is refused here, BEFORE the facade goes
     * async, where a refusal is a sentence the person sees rather than a log line.
     */
    static void validateRunnable(def ec, String reconciliationId, Object windowStartDate, Object windowEndDate) {
        def reconciliation = findReconciliation(ec, reconciliationId)
        if (reconciliation == null) { ec.message.addError("Run '${reconciliationId}' was not found."); return }
        if (reconciliation.get("isArchived") == "Y") { ec.message.addError("Run '${reconciliationId}' is archived."); return }
        if (reconciliation.get("isActive") == "N") { ec.message.addError("Run '${reconciliationId}' is switched off."); return }
        if (!RunFlowchartTree.walkOrder(loadQuestions(ec, reconciliationId))) {
            ec.message.addError("Run '${reconciliationId}' has no questions to ask."); return
        }
        if (windowStartDate == null || windowEndDate == null) {
            ec.message.addError("Choose a window: windowStartDate and windowEndDate are required.")
        }
    }

    /**
     * Params: reconciliationId, reconciliationExecutionId?, the window fields, and optionally
     * systemTenantRun (the scheduler: no user, tenant already asserted), onQuestionStart (Closure(Map)),
     * questionCallExtras (Closure(Map) -> Map, smoke tests only).
     */
    static Map runReconciliation(def ec, Map params) {
        String reconciliationId = normalize(params.reconciliationId)
        def reconciliation = findReconciliation(ec, reconciliationId)
        if (reconciliation == null) throw new IllegalArgumentException("Run '${reconciliationId}' was not found.")
        if (reconciliation.get("isArchived") == "Y" || reconciliation.get("isActive") == "N") {
            throw new IllegalStateException("Run '${reconciliationId}' is switched off or archived.")
        }
        String executionId = normalize(params.reconciliationExecutionId) ?: UUID.randomUUID().toString().replace("-", "")
        File workDir = DataManagerSupport.resolveDirectoryFile(ec,
                DataManagerSupport.resolveReconciliationRunLocation(ec, "flowchart-${executionId}", DataManagerSupport.formatRunTimestamp(ec)), true)
        Closure<Map> extras = (Closure<Map>) params.questionCallExtras
        String tenantId = reconciliation.get("companyUserGroupId") as String
        String userId = TenantAccessSupport.currentUserId(ec)
        Map base = [reconciliationExecutionId: executionId, windowStartDate: params.windowStartDate, windowEndDate: params.windowEndDate,
                    windowStartLocalDate: params.windowStartLocalDate, windowEndLocalDate: params.windowEndLocalDate,
                    systemTenantRun: params.systemTenantRun == true ? true : null]
        List<Map> questions = loadQuestions(ec, reconciliationId)

        Map walked = RunFlowchartWalker.walk([
                questions      : questions,
                workDir        : workDir,
                onQuestionStart: (Closure) params.onQuestionStart,
                runQuestion    : { Map q, Map call ->
                    // Final review I7: the active tenant is a server-side preference every tab shares. A
                    // switch mid-walk must not run the rest of the chart in another tenant.
                    String active = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
                    if (active != tenantId) {
                        throw new IllegalStateException("the active tenant changed to ${active} during the run, which belongs to ${tenantId}.")
                    }
                    Map input = (base + call + [savedRunId: q.ruleSetId, reconciliationRunId: q.reconciliationRunId] +
                            (extras ? extras.call(q) : [:])).findAll { it.value != null }
                    Map out = ec.service.sync().name(QUESTION_SERVICE).parameters(input).call() ?: [:]
                    String runResultId = ((Map) out.runResult)?.reconciliationRunResultId as String
                    // Final review I1: a refusal before the pipeline minted a row leaves its reason only on
                    // the context. Keep it: the walker records this question FAILED with that sentence.
                    String why = ec.message.hasError() ? ec.message.getErrorsString()?.trim() : null
                    ec.message.clearErrors()
                    if (!runResultId) throw new IllegalStateException(why ?: "the run was refused before it started.")
                    Map row = RunObservability.readRunRow(ec, runResultId) ?: [statusEnumId: RunObservability.STATUS_FAILED]
                    File doc = row.resultDataManagerPath ? DataManagerSupport.resolveDataManagerFile(ec, row.resultDataManagerPath, false) : null
                    return [runResultId: runResultId, statusEnumId: row.statusEnumId, resultDocument: doc?.isFile() ? doc : null]
                },
                recordSkipped  : { Map q, String parentRunResultId, String status, String reason ->
                    RunObservability.recordTerminalRun(ec, [savedRunId: q.ruleSetId, savedRunType: SAVED_RUN_TYPE,
                            ruleSetId: q.ruleSetId, reconciliationRunId: q.reconciliationRunId,
                            reconciliationExecutionId: executionId, parentRunResultId: parentRunResultId,
                            companyUserGroupId: tenantId, createdByUserId: userId,
                            windowStartDate: params.windowStartDate, windowEndDate: params.windowEndDate], status, reason)
                },
                recordFailure  : { String runResultId, String reason -> RunObservability.failRun(ec, runResultId, null, null, reason) },
                recordCounts   : { String runResultId, long y, long n, long u -> RunObservability.recordCounts(ec, runResultId, y, n, u) },
        ])
        String first = RunFlowchartTree.walkOrder(questions).collect {
            ((Map) walked.outcomes)[it.reconciliationRunId]?.runResultId }.find { it }
        return [reconciliationExecutionId: executionId, statusEnumId: walked.statusEnumId,
                firstRunResultId: first, outcomes: walked.outcomes]
    }

    static Map listReconciliations(def ec) {
        List rows = TenantScopedFinder.findTenantScoped(ec, RECONCILIATION).orderBy("reconciliationName").useCache(false).list() ?: []
        return [reconciliations: rows.findAll { it.get("isArchived") != "Y" }.collect { def r ->
            String id = r.get("reconciliationId") as String
            ["reconciliationId", "reconciliationName", "description", "defaultTimeWindow", "isActive", "isArchived"]
                    .collectEntries { [(it): r.get(it)] } +
                    [questionCount: (TenantScopedFinder.findTenantScoped(ec, QUESTION).condition("reconciliationId", id)
                            .useCache(false).list() ?: []).count { it.get("isActive") != "N" }]
        }]
    }

    static Map deleteQuestion(def ec, String reconciliationRunId) {
        if (!TenantAccessSupport.requireActiveTenantWriteAccess(ec, "Your active tenant only has view access for runs.")) return [:]
        def question = TenantScopedFinder.findTenantScopedByIdQuiet(ec, QUESTION, "reconciliationRunId", reconciliationRunId)
        if (question == null) { ec.message.addError("Question '${reconciliationRunId}' was not found."); return [:] }
        long children = (TenantScopedFinder.findTenantScoped(ec, QUESTION)
                .condition("parentReconciliationRunId", reconciliationRunId).useCache(false).list() ?: [])
                .count { it.get("isActive") != "N" }
        if (children > 0) { ec.message.addError("Remove the questions under it first."); return [:] }
        // Plan 2 final review I1: a soft delete. Every question that ever ran has result rows pointing at
        // it (FK RECRES_RUN), and those past results must stay readable.
        question.set("isActive", "N")
        question.update()
        return [deleted: true]
    }

    /** DAR-UI-048 plan 3. One row per press of Run, newest first. A deleted question's rows still count. */
    static Map listExecutions(def ec, String reconciliationId) {
        if (findReconciliation(ec, reconciliationId) == null) { ec.message.addError("Run '${reconciliationId}' was not found."); return [:] }
        List<String> questionIds = loadQuestions(ec, reconciliationId).collect { it.reconciliationRunId as String }
        if (!questionIds) return [executions: []]
        List rows = (TenantScopedFinder.findTenantScoped(ec, RUN_RESULT)
                .condition("reconciliationRunId", "in", questionIds).orderBy("-createdDate").useCache(false).list() ?: [])
                .findAll { it.get("reconciliationExecutionId") != null }   // EntityList.findAll casts to Boolean
        Map<String, List> byExecution = new LinkedHashMap<String, List>()
        rows.each { def r -> byExecution.computeIfAbsent(r.get("reconciliationExecutionId") as String) { [] } << r }
        return [executions: byExecution.collect { String id, List group ->
            [reconciliationExecutionId: id,
             startedDate              : group.collect { it.get("startedDate") }.findAll { it != null }.min(),
             windowStartDate          : group[0].get("windowStartDate"),
             windowEndDate            : group[0].get("windowEndDate"),
             statusEnumId             : rollupStatus(group.collect { it.get("statusEnumId") as String }),
             questionCount            : group.size()]
        }.take(20)]
    }

    static String rollupStatus(List<String> statuses) {
        if (statuses.any { it in ["AUT_STAT_PENDING", "AUT_STAT_RUNNING"] }) return "AUT_STAT_RUNNING"
        if (statuses.any { it == "AUT_STAT_CANCELLED" }) return "AUT_STAT_CANCELLED"
        if (statuses.any { it == "AUT_STAT_FAILED" }) return "AUT_STAT_FAILED"
        return "AUT_STAT_SUCCESS"
    }

    static Map getExecution(def ec, String reconciliationExecutionId) {
        List rows = TenantScopedFinder.findTenantScoped(ec, RUN_RESULT)
                .condition("reconciliationExecutionId", reconciliationExecutionId).orderBy("createdDate").useCache(false).list() ?: []
        return [results: rows.collect { def r ->
            ["reconciliationRunResultId", "reconciliationRunId", "parentRunResultId", "statusEnumId", "yesCount",
             "noCount", "unaskedCount", "differenceCount", "errorMessage", "resultDataManagerPath"].collectEntries { [(it): r.get(it)] }
        }]
    }
}
