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

    /**
     * What every record shows, by the names people use: columns every NetSuite transaction has, list fields by their
     * display names (BUILTIN.DF), the date formatted so it never reads as "8/14/2026". internalId is the record's key.
     */
    static final List<List<String>> COLUMNS = [["internalId", "id"], ["order", "tranid"],
                                               ["date", "TO_CHAR(trandate, 'YYYY-MM-DD') AS trandate"],
                                               ["status", "BUILTIN.DF(status) AS status"], ["customer", "BUILTIN.DF(entity) AS customer"],
                                               ["total", "foreigntotal"], ["shippingMethod", "BUILTIN.DF(shipmethod) AS shipmethod"],
                                               ["reference", "otherrefnum"]]
    /** A detail a person chose (a custom field) is selected under its own alias, apart from a leave-out rule's column. */
    static final String DETAIL_ALIAS = "detail"

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
                normalize(params.reconciliationName) ?: LifecycleStageCompiler.lifecycleName(startType), takenNames)
        def run = ec.entity.makeValue(RunFlowchartSupport.RECONCILIATION)
        run.setSequencedIdPrimary()
        run.setAll([reconciliationName: name, companyUserGroupId: tenantId, defaultTimeWindow: "3d",
                    buildMode: BUILD_MODE_STAGES, lateAfterDays: lateAfterDays])
        run.create()
        String reconciliationId = run.get("reconciliationId")

        if (!buildTree(ec, [reconciliationId: reconciliationId, name: name, authId: authId, tenantId: tenantId, userId: userId,
                            startType: startType, plan: plan, now: now])) return [:]
        // Rules chosen while setting it up: saved in the same transaction, so a refused rule saves nothing.
        for (Object rule : ((params.rules ?: []) as List)) {
            Map r = (Map) rule
            LifecycleRuleSupport.saveRule(ec, [reconciliationId: reconciliationId, fieldId: r.fieldId, fieldLabel: r.fieldLabel, value: r.value])
            if (ec.message.hasError()) return [:]
        }
        return [reconciliationId: reconciliationId, reconciliationName: name]
    }

    /**
     * Writes the queries, rules and questions for a compiled plan. With startQuestionId the START already exists (a
     * reorder keeps it, and with it the lifecycle's filters); otherwise the START is written too. False after an error.
     */
    private static boolean buildTree(def ec, Map ctx) {
        String reconciliationId = ctx.reconciliationId, name = ctx.name, authId = ctx.authId, tenantId = ctx.tenantId
        String userId = ctx.userId, startType = ctx.startType
        List<Map> plan = (List<Map>) ctx.plan
        def now = ctx.now
        // One query per distinct thing asked, shared by every question that asks it: the start's population, and
        // "is X missing?" / "has it reached X?" per stage.
        String plural = LifecycleStageCompiler.words(startType).plural
        Map<String, String> queryByAsk = [:]
        if (!ctx.startQuestionId) {
            queryByAsk["start"] = createQuery(ec, authId, tenantId, userId, now, startType, null, null,
                    "Every ${plural.toLowerCase()} in the window".toString())
        }
        plan.findAll { it.check }.each { Map q ->
            String stage = q.stageRecordType as String
            String ask = "${q.check}:${stage}".toString()
            if (queryByAsk.containsKey(ask)) return
            String stageName = LifecycleStageCompiler.words(stage).name.toLowerCase()
            queryByAsk[ask] = q.check == LifecycleStageCompiler.PRESENT
                    ? createQuery(ec, authId, tenantId, userId, now, startType, null, stage, "${plural} with a ${stageName}".toString())
                    : createQuery(ec, authId, tenantId, userId, now, startType, stage, null, "${plural} with no ${stageName}".toString())
        }

        Map<String, String> questionIdByKey = ctx.startQuestionId ? [start: ctx.startQuestionId as String] : [:]
        List<Map> tree = ctx.startQuestionId ? RunFlowchartSupport.loadQuestions(ec, reconciliationId)
                .findAll { it.reconciliationRunId == ctx.startQuestionId } : []
        int sequence = (RunFlowchartSupport.loadQuestions(ec, reconciliationId).collect { (it.runSequence ?: 0) as int }.max() ?: 0) as int
        for (Map q : plan) {
            boolean isStart = q.questionRole == RunFlowchartTree.ROLE_START
            if (isStart && ctx.startQuestionId) continue
            String queryId = isStart ? queryByAsk["start"] : queryByAsk["${q.check}:${q.stageRecordType}".toString()]
            // A question's records are the ones it finds, and its rule's name heads the page those records open on,
            // so the rule is named after them: the start's population, or its place. A question whose findings
            // carry on to the next one finds the records missing its stage. Rule names are unique per tenant, and a
            // reorder leaves the earlier rules in place for past runs, so a taken name gains a number.
            String found = isStart ? q.runName : (q.noOutcomeLabel ?: LifecycleStageCompiler.words(q.stageRecordType as String).neg.capitalize())
            String ruleSetId = createRule(ec, uniqueRuleName(ec, "${name} · ${found}".toString()), queryId)
            if (ruleSetId == null) return false
            ownAndPointAtRecords(ec, ruleSetId, reconciliationId)

            String parentId = q.parentKey ? questionIdByKey[q.parentKey as String] : null
            def question = ec.entity.makeValue(RunFlowchartSupport.QUESTION)
            question.setSequencedIdPrimary()
            question.setAll([reconciliationId: reconciliationId, companyUserGroupId: tenantId, ruleSetId: ruleSetId,
                             runName: q.runName, questionRole: q.questionRole, parentReconciliationRunId: parentId,
                             parentBranch: q.parentBranch, runSequence: ++sequence,
                             noOutcomeLabel: q.noOutcomeLabel])
            question.create()
            questionIdByKey[q.key as String] = question.get("reconciliationRunId") as String
            tree << ([reconciliationRunId: question.get("reconciliationRunId"), ruleSetId: ruleSetId,
                      parentReconciliationRunId: parentId, parentBranch: q.parentBranch, questionRole: q.questionRole,
                      runSequence: sequence] + RunFlowchartSupport.shapeFor(ec, ruleSetId))
        }
        // The same rules save#ReconciliationQuestion enforces. A compiled tree should always pass; if it does
        // not, the error rolls the whole save back rather than leave a chart the walker would refuse.
        List<String> refused = RunFlowchartTree.validate(tree)
        if (refused) { refused.each { ec.message.addError(it) }; return false }
        return true
    }

    /**
     * DAR-UI-050. Changes a lifecycle's stages (their order, or which they are). The old stage questions are turned off,
     * not deleted, so past runs stay readable; the new ones are compiled from the list under the same START, so the
     * lifecycle's filters stay. Stage exceptions follow their stage by record type. A name still as generated follows.
     */
    static Map saveStages(def ec, Map params) {
        if (!TenantAccessSupport.requireActiveTenantWriteAccess(ec, "Your active tenant only has view access for runs.")) return [:]
        String reconciliationId = normalize(params.reconciliationId)
        def run = RunFlowchartSupport.findReconciliation(ec, reconciliationId)
        if (run == null || run.get("buildMode") != BUILD_MODE_STAGES) {
            ec.message.addError("Stages belong to a lifecycle built from stages; '${reconciliationId}' is not one."); return [:]
        }
        List<String> stages = ((params.stageRecordTypes ?: []) as List).collect { normalize(it) }.findAll { it }
        List<Map> questions = RunFlowchartSupport.loadQuestions(ec, reconciliationId).findAll { it.isActive != "N" }
        Map start = questions.find { it.questionRole == RunFlowchartTree.ROLE_START }
        def startQuery = start ? LifecycleRuleSupport.queryForRule(ec, start.ruleSetId as String) : null
        if (startQuery == null) { ec.message.addError("This lifecycle's start could not be found."); return [:] }
        String startType = startQuery.get("recordType") as String
        List<String> oldStages = LifecycleRuleSupport.stagesOf(ec, reconciliationId)*.recordType
        if (oldStages == stages) return [reconciliationName: run.get("reconciliationName")]
        List<Map> plan
        try {
            plan = LifecycleStageCompiler.compile(startType, stages)
        } catch (IllegalArgumentException e) {
            ec.message.addError(e.message); return [:]
        }
        List<Map> exceptions = LifecycleRuleSupport.listExceptions(ec, reconciliationId)

        questions.findAll { it.questionRole != RunFlowchartTree.ROLE_START }.each { Map q ->
            TenantScopedFinder.findTenantScopedByIdQuiet(ec, RunFlowchartSupport.QUESTION, "reconciliationRunId", q.reconciliationRunId)
                    ?.set("isActive", "N")?.update()
        }
        // The name is the records', not the stages' order, so a reorder keeps it.
        String name = run.get("reconciliationName") as String
        if (!buildTree(ec, [reconciliationId: reconciliationId, name: name, authId: startQuery.get("nsAuthConfigId"),
                            tenantId: run.get("companyUserGroupId"), userId: TenantAccessSupport.currentUserId(ec),
                            startType: startType, plan: plan, now: ec.user.nowTimestamp, startQuestionId: start.reconciliationRunId])) return [:]
        // The new stages' checks show what the lifecycle's records show.
        applyDetails(ec, reconciliationId, detailsOf(ec, reconciliationId))
        exceptions.findAll { stages.contains(it.stageRecordType) }.each { Map e ->
            LifecycleRuleSupport.saveException(ec, [reconciliationId: reconciliationId, stageRecordType: e.stageRecordType,
                    fieldId: e.fieldId, fieldLabel: e.fieldLabel, value: e.value])
        }
        return ec.message.hasError() ? [:] : [reconciliationName: name]
    }

    /**
     * Renames the checks of a lifecycle's current stages after it: "<lifecycle> · <what the check finds>". The part
     * after the last " · " is kept (less a number a taken name gained), so a check keeps saying what it finds.
     * Past stages' checks keep their names: they head the pages of runs made under them.
     */
    static void renameRules(def ec, String reconciliationId, String name) {
        if (!name) return
        RunFlowchartSupport.loadQuestions(ec, reconciliationId).findAll { it.isActive != "N" }.each { Map q ->
            def rule = TenantScopedFinder.findTenantScopedByIdQuiet(ec, "darpan.rule.RuleSet", "ruleSetId", q.ruleSetId)
            String old = rule?.get("ruleSetName") as String
            int at = old?.lastIndexOf(" · ") ?: -1
            if (at < 0) return
            String found = old.substring(at + 3).replaceFirst(/ \(\d+\)$/, "")
            String wanted = "${name} · ${found}".toString()
            if (old == wanted || old.startsWith("${wanted} (")) return
            rule.set("ruleSetName", uniqueRuleName(ec, wanted))
            rule.update()
        }
    }

    /** A record's key for a detail, from its name: "HC Order Id" -> hcOrderId (keys are identifiers). */
    static String detailKey(String label) {
        List<String> words = (label ?: "").split(/[^A-Za-z0-9]+/).findAll { it } as List<String>
        String key = words.withIndex().collect { String w, int i -> i == 0 ? w.toLowerCase() : w.substring(0, 1).toUpperCase() + w.substring(1).toLowerCase() }.join("")
        return key && Character.isLetter(key.charAt(0)) ? key : "field${key}".toString()
    }

    /** The details a person chose for this lifecycle's records, in order: [fieldId, key], read off its start. */
    static List<Map> detailsOf(def ec, String reconciliationId) {
        Map start = RunFlowchartSupport.loadQuestions(ec, reconciliationId).find { it.questionRole == RunFlowchartTree.ROLE_START && it.isActive != "N" }
        def query = start ? LifecycleRuleSupport.queryForRule(ec, start.ruleSetId as String) : null
        if (query == null) return []
        return fieldsOf(ec, query.get("nsSuiteQlSourceQueryId") as String)
                .findAll { ((it.get("sourceColumn") as String) ?: "") ==~ /(?i).*\s+AS\s+detail\d+/ }
                .collect { [fieldId: (it.get("sourceColumn") as String).split(/(?i)\s+AS\s+/)[0].trim(), key: it.get("recordFieldName")] as Map }
    }

    /**
     * What every record of the lifecycle shows: the standard columns, then the chosen custom fields, on every one of its
     * checks (each is its own query). A leave-out rule's column on the start is kept. Only body text fields
     * (custbody_...) can be chosen: the id reaches SuiteQL as a column.
     */
    static Map saveDetails(def ec, Map params) {
        if (!TenantAccessSupport.requireActiveTenantWriteAccess(ec, "Your active tenant only has view access for runs.")) return [:]
        String reconciliationId = normalize(params.reconciliationId)
        def run = reconciliationId ? RunFlowchartSupport.findReconciliation(ec, reconciliationId) : null
        if (run == null || run.get("buildMode") != BUILD_MODE_STAGES) {
            ec.message.addError("Details belong to a lifecycle built from stages; '${reconciliationId}' is not one."); return [:]
        }
        List<Map> details = ((params.details ?: []) as List).collect { Object o ->
            Map d = (Map) o
            String fieldId = normalize(d.fieldId)?.toLowerCase()
            [fieldId: fieldId, key: detailKey(normalize(d.label) ?: fieldId?.replaceFirst(/^custbody_/, ""))] as Map
        }.findAll { it.fieldId }.unique { it.fieldId }
        Map bad = details.find { !((it.fieldId as String) ==~ /custbody_[a-z0-9_]+/) || ((it.key as String).length() > 60) }
        if (bad) { ec.message.addError("'${bad.fieldId}' is not a field a record can show."); return [:] }
        // Two details named alike (eTail Order ID, eTail Order Id) keep apart by a number.
        Set<String> taken = COLUMNS*.get(0) as Set<String>
        details.each { Map d -> String k = d.key; int n = 2; while (taken.contains(k)) k = "${d.key}${n++}".toString(); d.key = k; taken << k }
        applyDetails(ec, reconciliationId, details)
        return ec.message.hasError() ? [:] : [details: detailsOf(ec, reconciliationId)]
    }

    private static void applyDetails(def ec, String reconciliationId, List<Map> details) {
        RunFlowchartSupport.loadQuestions(ec, reconciliationId).findAll { it.isActive != "N" }
                .collect { LifecycleRuleSupport.queryForRule(ec, it.ruleSetId as String) }.findAll { it != null }
                .unique { it.get("nsSuiteQlSourceQueryId") }
                .each { writeColumns(ec, it.get("nsSuiteQlSourceQueryId") as String, details) }
    }

    private static List fieldsOf(def ec, String queryId) {
        return TenantScopedFinder.findTenantScopedChildren(ec, QUERY_FIELD, QUERY, "nsSuiteQlSourceQueryId", queryId, "nsSuiteQlSourceQueryId")
                ?.orderBy("sequenceNum")?.useCache(false)?.list() ?: []
    }

    /** Rewrites a query's shown columns (standard, then chosen) ahead of any leave-out rule column, which it keeps. */
    private static void writeColumns(def ec, String queryId, List<Map> details) {
        List existing = fieldsOf(ec, queryId)
        List kept = existing.findAll { (it.get("sourceColumn") as String)?.startsWith("NVL(") }
        existing.findAll { !kept.contains(it) }.each { it.delete() }
        List<List<String>> columns = COLUMNS + details.withIndex().collect { Map d, int i ->
            [d.key as String, "${d.fieldId} AS ${DETAIL_ALIAS}${i + 1}".toString()] }
        columns.eachWithIndex { List<String> c, int i ->
            ec.entity.makeValue(QUERY_FIELD).setAll([nsSuiteQlSourceQueryId: queryId, recordFieldName: c[0], sourceColumn: c[1],
                    sequenceNum: i + 1]).create()
        }
        kept.eachWithIndex { def f, int i -> f.set("sequenceNum", columns.size() + i + 1); f.update() }
    }

    private static String uniqueRuleName(def ec, String base) {
        Closure<Boolean> taken = { String n -> TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSet")
                .condition("ruleSetName", n).useCache(false).count() > 0 }
        if (!taken(base)) return base
        int n = 2
        while (taken("${base} (${n})".toString())) n++
        return "${base} (${n})".toString()
    }

    private static String createQuery(def ec, String authId, String tenantId, String userId, def now, String recordType,
                                      String absentChildRecordType, String presentChildRecordType, String description) {
        def query = ec.entity.makeValue(QUERY)
        query.setSequencedIdPrimary()
        query.setAll([nsAuthConfigId: authId, companyUserGroupId: tenantId, createdByUserId: userId, description: description,
                      recordType: recordType, fromTable: "transaction", dateColumn: "trandate",
                      queryTemplate: absentChildRecordType ? "LINKED_CHILD_ABSENT" : presentChildRecordType ? "LINKED_CHILD_PRESENT" : "RECORDS",
                      absentChildRecordType: absentChildRecordType, presentChildRecordType: presentChildRecordType,
                      isActive: "Y", createdDate: now, lastUpdatedDate: now])
        query.create()
        writeColumns(ec, query.get("nsSuiteQlSourceQueryId") as String, [])
        return query.get("nsSuiteQlSourceQueryId") as String
    }

    /**
     * Marks the rule as this lifecycle's, and points it at the extract's records. create#RuleSetRun clears the record
     * root and file type on a connector source and leaves them to the extract step; the walker's compare reads them off
     * the source, so without them it looks for internalId beside {metadata, records} and fails (found on the first
     * local gorjana run, 2026-10-16). The values are the ones NsChainUiSeed writes for the checks the walker runs.
     */
    private static void ownAndPointAtRecords(def ec, String ruleSetId, String reconciliationId) {
        TenantScopedFinder.findTenantScopedByIdQuiet(ec, "darpan.rule.RuleSet", "ruleSetId", ruleSetId)
                .setAll([ownerReconciliationId: reconciliationId, explosionPath: "records", primaryKeyPath: "internalId"]).update()
        def scope = TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSetCompareScope").condition("ruleSetId", ruleSetId)
                .useCache(false).list()?.find()
        def source = TenantScopedFinder.findTenantScopedChildren(ec, "darpan.rule.RuleSetCompareSource", "darpan.rule.RuleSetCompareScope",
                "compareScopeId", scope?.get("compareScopeId"), "compareScopeId")?.condition("fileSide", "FILE_1")?.useCache(false)?.one()
        source?.setAll([fileTypeEnumId: "DftJson", recordRootExpression: "records"])?.update()
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
