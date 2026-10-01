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
            def scope = ruleSetId ? TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSetCompareScope")
                    .condition("ruleSetId", ruleSetId).useCache(false).list()?.find() : null
            def file1 = scope ? TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSetCompareSource")
                    .condition("compareScopeId", scope.get("compareScopeId")).condition("fileSide", "FILE_1")
                    .useCache(false).one() : null
            [reconciliationRunId      : row.get("reconciliationRunId"),
             parentReconciliationRunId: row.get("parentReconciliationRunId"),
             parentBranch             : row.get("parentBranch"),
             questionRole             : row.get("questionRole"),
             runSequence              : row.get("runSequence"),
             isActive                 : row.get("isActive"),
             runName                  : row.get("runName"),
             noOutcomeLabel           : row.get("noOutcomeLabel"),
             ruleSetId                : ruleSetId,
             scopeMode                : (scope?.get("scopeMode") ?: RunFlowchartTree.MODE_COMPARE) as String,
             file1Signature           : "${file1?.get('systemEnumId')}|${file1?.get('sourceConfigId')}".toString()]
        } as List<Map>
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
        List<Map> existing = loadQuestions(ec, reconciliationId)
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

    /** The candidate's scopeMode and FILE_1 signature come from its rule set, exactly as loadQuestions reads them. */
    private static Map loadQuestionShape(def ec, Map candidate) {
        def scope = TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSetCompareScope")
                .condition("ruleSetId", candidate.ruleSetId).useCache(false).list()?.find()
        def file1 = scope ? TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSetCompareSource")
                .condition("compareScopeId", scope.get("compareScopeId")).condition("fileSide", "FILE_1").useCache(false).one() : null
        return candidate + [scopeMode     : (scope?.get("scopeMode") ?: RunFlowchartTree.MODE_COMPARE) as String,
                            file1Signature: "${file1?.get('systemEnumId')}|${file1?.get('sourceConfigId')}".toString()]
    }

    static Map getReconciliation(def ec, String reconciliationId) {
        def reconciliation = findReconciliation(ec, reconciliationId)
        if (reconciliation == null) { ec.message.addError("Run '${reconciliationId}' was not found."); return [:] }
        return [reconciliation: reconciliation.getMap(), questions: loadQuestions(ec, reconciliationId)]
    }

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
                    windowStartLocalDate: params.windowStartLocalDate, windowEndLocalDate: params.windowEndLocalDate]

        Map walked = RunFlowchartWalker.walk([
                questions     : loadQuestions(ec, reconciliationId),
                workDir       : workDir,
                runQuestion   : { Map q, Map call ->
                    Map input = (base + call + [savedRunId: q.ruleSetId, reconciliationRunId: q.reconciliationRunId] +
                            (extras ? extras.call(q) : [:])).findAll { it.value != null }
                    Map out = ec.service.sync().name(QUESTION_SERVICE).parameters(input).call() ?: [:]
                    String runResultId = ((Map) out.runResult)?.reconciliationRunResultId as String
                    // A question that failed before or during its run leaves errors on the context; they
                    // belong to that question's row, not to the next question.
                    ec.message.clearErrors()
                    Map row = RunObservability.readRunRow(ec, runResultId) ?: [statusEnumId: RunObservability.STATUS_FAILED]
                    File doc = row.resultDataManagerPath ? DataManagerSupport.resolveDataManagerFile(ec, row.resultDataManagerPath, false) : null
                    return [runResultId: runResultId, statusEnumId: row.statusEnumId, resultDocument: doc?.isFile() ? doc : null]
                },
                recordSkipped : { Map q, String parentRunResultId, String status, String reason ->
                    RunObservability.recordTerminalRun(ec, [savedRunId: q.ruleSetId, savedRunType: SAVED_RUN_TYPE,
                            ruleSetId: q.ruleSetId, reconciliationRunId: q.reconciliationRunId,
                            reconciliationExecutionId: executionId, parentRunResultId: parentRunResultId,
                            companyUserGroupId: tenantId, createdByUserId: userId,
                            windowStartDate: params.windowStartDate, windowEndDate: params.windowEndDate], status, reason)
                },
                recordYesCount: { String runResultId, long n -> RunObservability.recordYesCount(ec, runResultId, n) },
        ])
        String first = RunFlowchartTree.walkOrder(loadQuestions(ec, reconciliationId)).collect {
            ((Map) walked.outcomes)[it.reconciliationRunId]?.runResultId }.find { it }
        return [reconciliationExecutionId: executionId, statusEnumId: walked.statusEnumId,
                firstRunResultId: first, outcomes: walked.outcomes]
    }

    static Map getExecution(def ec, String reconciliationExecutionId) {
        List rows = TenantScopedFinder.findTenantScoped(ec, RUN_RESULT)
                .condition("reconciliationExecutionId", reconciliationExecutionId).orderBy("createdDate").useCache(false).list() ?: []
        return [results: rows.collect { def r ->
            ["reconciliationRunResultId", "reconciliationRunId", "parentRunResultId", "statusEnumId", "yesCount",
             "differenceCount", "errorMessage", "resultDataManagerPath"].collectEntries { [(it): r.get(it)] }
        }]
    }
}
