package darpan.reconciliation.flowchart

import darpan.facade.common.SharedConfigAccessSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder

import static darpan.common.ValueSupport.normalize

/**
 * DAR-UI-050. Saves a lifecycle from the builder's dropdown choices: a NetSuite account, a start record type and
 * its stages in order. Everything a person used to author by hand (the SuiteQL check, its rule, the question, the
 * finding's name) is written here, so the builder sends record types and nothing else.
 *
 * Each question gets its own rule, even where two questions ask the same stage, so no two questions of one run
 * share a saved-run id or its result files. Those rules belong to the lifecycle (RuleSet.ownerReconciliationId)
 * and stay out of the saved-run list. Default transaction: a refused save leaves nothing behind.
 */
class LifecycleSupport {

    static final String BUILD_MODE_STAGES = "STAGES"
    static final String QUERY = "darpan.reconciliation.NsSuiteQlSourceQuery"
    static final String QUERY_FIELD = "darpan.reconciliation.NsSuiteQlSourceQueryField"
    static final String CREATE_RULE = "facade.ReconciliationFacadeServices.create#RuleSetRun"

    /** Plain columns every NetSuite transaction has; the date is formatted so it never reads as "8/14/2026". */
    static final List<List<String>> COLUMNS = [["internalId", "id"], ["tranId", "tranid"], ["status", "status"],
                                               ["tranDate", "TO_CHAR(trandate, 'YYYY-MM-DD') AS trandate"]]

    static Map saveLifecycle(def ec, Map params) {
        if (!TenantAccessSupport.requireActiveTenantWriteAccess(ec, "Your active tenant only has view access for runs.")) return [:]
        String tenantId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        String userId = TenantAccessSupport.currentUserId(ec)
        String authId = normalize(params.nsAuthConfigId)
        String startType = normalize(params.recordType)
        List<String> stages = ((params.stageRecordTypes ?: []) as List).collect { normalize(it) }.findAll { it }
        Integer lateAfterDays = params.lateAfterDays != null ? (params.lateAfterDays as Integer) : null
        if (lateAfterDays != null && (lateAfterDays < 0 || lateAfterDays > 90)) {
            ec.message.addError("Late after must be between 0 and 90 days."); return [:]
        }

        // Owned or shared with the active tenant; the same row set the account dropdown lists.
        def auth = authId ? SharedConfigAccessSupport.listAccessibleConfigRows(ec, SharedConfigAccessSupport.CONFIG_TYPE_NS_AUTH)
                .find { it.get("nsAuthConfigId") == authId } : null
        if (auth == null) {
            ec.message.addError("NetSuite account '${authId}' was not found."); return [:]
        }
        List<Map> plan
        try {
            plan = LifecycleStageCompiler.compile(startType, stages)
        } catch (IllegalArgumentException e) {
            ec.message.addError(e.message); return [:]
        }

        def now = ec.user.nowTimestamp
        Set<String> takenNames = (TenantScopedFinder.findTenantScoped(ec, RunFlowchartSupport.RECONCILIATION).useCache(false).list() ?: [])
                .findAll { it.get("isArchived") != "Y" }.collect { it.get("reconciliationName") as String } as Set<String>
        String name = NetSuiteCheckImportSupport.uniqueName(
                normalize(params.reconciliationName) ?: LifecycleStageCompiler.lifecycleName(startType, stages), takenNames)
        def run = ec.entity.makeValue(RunFlowchartSupport.RECONCILIATION)
        run.setSequencedIdPrimary()
        run.setAll([reconciliationName: name, companyUserGroupId: tenantId, defaultTimeWindow: "3d",
                    buildMode: BUILD_MODE_STAGES, lateAfterDays: lateAfterDays])
        run.create()
        String reconciliationId = run.get("reconciliationId")

        // One query per distinct record type asked: the start's population, and one "is X missing?" per stage.
        Map<String, String> queryByType = [:]
        queryByType["start"] = createQuery(ec, authId, tenantId, userId, now, startType, null,
                "Every ${LifecycleStageCompiler.words(startType).plural.toLowerCase()} in the window".toString())
        stages.each { String stage ->
            queryByType[stage] = createQuery(ec, authId, tenantId, userId, now, startType, stage,
                    "${LifecycleStageCompiler.words(startType).plural} with no ${LifecycleStageCompiler.words(stage).name.toLowerCase()}".toString())
        }

        Map<String, String> questionIdByKey = [:]
        List<Map> tree = []
        int sequence = 0
        for (Map q : plan) {
            boolean isStart = q.questionRole == RunFlowchartTree.ROLE_START
            String queryId = isStart ? queryByType["start"] : queryByType[q.stageRecordType as String]
            // Rule names are unique per tenant and never shown for a lifecycle's own rules, so they carry its id.
            String ruleSetId = createRule(ec, "Lifecycle ${reconciliationId} · ${q.key} · ${q.runName}".toString(), queryId)
            if (ruleSetId == null) return [:]
            TenantScopedFinder.findTenantScopedByIdQuiet(ec, "darpan.rule.RuleSet", "ruleSetId", ruleSetId)
                    .set("ownerReconciliationId", reconciliationId).update()

            String parentId = q.parentKey ? questionIdByKey[q.parentKey as String] : null
            def question = ec.entity.makeValue(RunFlowchartSupport.QUESTION)
            question.setSequencedIdPrimary()
            question.setAll([reconciliationId: reconciliationId, companyUserGroupId: tenantId, ruleSetId: ruleSetId,
                             runName: q.runName, questionRole: q.questionRole, parentReconciliationRunId: parentId,
                             parentBranch: q.parentBranch, runSequence: ++sequence,
                             noOutcomeLabel: q.noOutcomeLabel, yesOutcomeLabel: q.yesOutcomeLabel])
            question.create()
            questionIdByKey[q.key as String] = question.get("reconciliationRunId") as String
            tree << ([reconciliationRunId: question.get("reconciliationRunId"), ruleSetId: ruleSetId,
                      parentReconciliationRunId: parentId, parentBranch: q.parentBranch, questionRole: q.questionRole,
                      runSequence: sequence] + RunFlowchartSupport.shapeFor(ec, ruleSetId))
        }
        // The same rules save#ReconciliationQuestion enforces. A compiled tree should always pass; if it does
        // not, the error rolls the whole save back rather than leave a chart the walker would refuse.
        List<String> refused = RunFlowchartTree.validate(tree)
        if (refused) { refused.each { ec.message.addError(it) }; return [:] }
        return [reconciliationId: reconciliationId, reconciliationName: name]
    }

    private static String createQuery(def ec, String authId, String tenantId, String userId, def now,
                                      String recordType, String absentChildRecordType, String description) {
        def query = ec.entity.makeValue(QUERY)
        query.setSequencedIdPrimary()
        query.setAll([nsAuthConfigId: authId, companyUserGroupId: tenantId, createdByUserId: userId, description: description,
                      recordType: recordType, fromTable: "transaction", dateColumn: "trandate",
                      queryTemplate: absentChildRecordType ? "LINKED_CHILD_ABSENT" : "RECORDS",
                      absentChildRecordType: absentChildRecordType, isActive: "Y", createdDate: now, lastUpdatedDate: now])
        query.create()
        COLUMNS.eachWithIndex { List<String> c, int i ->
            ec.entity.makeValue(QUERY_FIELD).setAll([nsSuiteQlSourceQueryId: query.get("nsSuiteQlSourceQueryId"),
                    recordFieldName: c[0], sourceColumn: c[1], sequenceNum: i + 1]).create()
        }
        return query.get("nsSuiteQlSourceQueryId") as String
    }

    /** A one-source NetSuite rule, built by the same service that builds every saved run (as the gorjana uploader does). */
    private static String createRule(def ec, String runName, String queryId) {
        Map out = ec.service.sync().name(CREATE_RULE).parameters([
                runName: runName, scopeMode: RunFlowchartTree.MODE_EVALUATE,
                file1SystemEnumId: "NETSUITE_SUITEQL", file1SourceTypeEnumId: "AUT_SRC_API", file1SourceConfigId: queryId,
                file1SourceConfigType: "NETSUITE_SUITEQL", file1FileTypeEnumId: "DftJson",
                file1RecordRootExpression: "records", file1PrimaryIdExpression: "internalId", rules: []]).call() ?: [:]
        String id = (out.savedRun as Map)?.savedRunId as String
        if (!id && !ec.message.hasError()) ec.message.addError("A stage's rule could not be created.")
        return ec.message.hasError() ? null : id
    }
}
