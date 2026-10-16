package darpan.reconciliation.flowchart

import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder

/**
 * DAR-UI-048 plan 3 (spec A9). Turns the NetSuite checks a tenant already has into runs: per population (account,
 * record type, and how the key is built), one START records query and rule, with every check hung under it as a
 * yes question. A check keeps reporting exactly what it reports today (its rows ∩ the window's population = its rows).
 *
 * Safe to repeat. A check already in a run is left alone, and a run is found again by its START's shape, not its
 * name, so two populations of one record type never share a START that builds the wrong key.
 */
class NetSuiteCheckImportSupport {

    static final Map<String, String> RECORD_TYPE_LABELS = [SalesOrd: "sales orders", RtnAuth: "return authorizations",
            CustInvc: "invoices", ItemShip: "fulfillments", PurchOrd: "purchase orders", TrnfrOrd: "transfer orders",
            CashSale: "cash sales", CustCred: "credit memos"]
    static final String RUN_NAME_PREFIX = "NetSuite checks: "
    static final String QUERY = "darpan.reconciliation.NsSuiteQlSourceQuery"
    static final String QUERY_FIELD = "darpan.reconciliation.NsSuiteQlSourceQueryField"
    static final String SCOPE = "darpan.rule.RuleSetCompareScope"
    static final String SOURCE = "darpan.rule.RuleSetCompareSource"
    static final String KEY_FIELD = "darpan.rule.RuleSetCompareSourceKeyField"
    /** Templates that list records rather than find something wrong with them (empty reads as RECORDS). */
    static final Set<String> POPULATION_TEMPLATES = ["RECORDS", "SALES_LINES"] as Set

    static Map importChecks(def ec) {
        if (!TenantAccessSupport.requireActiveTenantWriteAccess(ec, "Your active tenant only has view access for runs.")) return [:]
        String tenantId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        String userId = TenantAccessSupport.currentUserId(ec)
        List activeQuestions = (TenantScopedFinder.findTenantScoped(ec, RunFlowchartSupport.QUESTION).useCache(false).list() ?: [])
                .findAll { it.get("isActive") != "N" }
        Set<String> inRuns = activeQuestions.collect { it.get("ruleSetId") as String } as Set<String>

        List<Map> checks = []
        int skipped = 0
        (TenantScopedFinder.findTenantScoped(ec, SOURCE).condition("fileSide", "FILE_1")
                .condition("sourceConfigType", "NETSUITE_SUITEQL").useCache(false).list() ?: []).each { def source ->
            def scope = TenantScopedFinder.findTenantScopedByIdQuiet(ec, SCOPE, "compareScopeId", source.get("compareScopeId"))
            if (scope == null || scope.get("scopeMode") != RunFlowchartTree.MODE_EVALUATE) return
            Map check = describe(ec, scope, source)
            if (check == null || check.ruleSet.get("isArchived") == "Y" || inRuns.contains(check.ruleSetId)) return
            if (check.template in POPULATION_TEMPLATES) return
            if (check.keyColumns == null) {
                skipped++
                ec.message.addMessage("Check '${check.ruleSet.get('ruleSetName')}' was not imported: its query has no column for " +
                        "the key field ${check.missingKeyField}, so a start could not list its records.")
                return
            }
            checks << check
        }

        Map<List, List<Map>> groups = checks.groupBy { Map c -> c.signature as List }
        boolean manyAccounts = groups.keySet().collect { it[0] }.unique().size() > 1
        Map<List, Map> startsBySignature = importedStarts(ec, activeQuestions)
        Set<String> takenNames = (TenantScopedFinder.findTenantScoped(ec, RunFlowchartSupport.RECONCILIATION).useCache(false).list() ?: [])
                .findAll { it.get("isArchived") != "Y" }.collect { it.get("reconciliationName") as String } as Set<String>

        List<Map> runs = []
        int imported = 0
        groups.each { List signature, List<Map> members ->
            Map sample = members[0]
            Map target = startsBySignature[signature]
            if (target == null) {
                String label = RECORD_TYPE_LABELS[sample.recordType] ?: sample.recordType
                String name = uniqueName("${RUN_NAME_PREFIX}${label}${manyAccounts ? " (${sample.nsAuthConfigId})" : ''}".toString(), takenNames)
                takenNames << name
                target = createStartRun(ec, name, label, sample, tenantId, userId)
            }
            List<Map> tree = RunFlowchartSupport.loadQuestions(ec, target.reconciliationId as String)
            int sequence = (tree.collect { (it.runSequence ?: 0) as int }.max() ?: 0) as int
            tree = tree.findAll { it.isActive != "N" }
            members.each { Map c ->
                // Everything save#ReconciliationQuestion refuses is refused here too, as a skip rather than a broken chart.
                Map candidate = [reconciliationRunId: "__NEW__", ruleSetId: c.ruleSetId, parentReconciliationRunId: target.startQuestionId,
                                 parentBranch: RunFlowchartTree.BRANCH_YES] + RunFlowchartSupport.shapeFor(ec, c.ruleSetId as String)
                List<String> refused = RunFlowchartTree.validate(tree + [candidate])
                if (refused) {
                    skipped++
                    ec.message.addMessage("Check '${c.ruleSet.get('ruleSetName')}' was not imported: ${refused[0]}")
                    return
                }
                def question = ec.entity.makeValue(RunFlowchartSupport.QUESTION)
                question.setSequencedIdPrimary()
                question.setAll([reconciliationId: target.reconciliationId, companyUserGroupId: tenantId, ruleSetId: c.ruleSetId,
                        runName: c.ruleSet.get("ruleSetName"), parentReconciliationRunId: target.startQuestionId,
                        parentBranch: RunFlowchartTree.BRANCH_YES, runSequence: ++sequence,
                        noOutcomeLabel: (c.query.get("description") ?: c.ruleSet.get("ruleSetName"))])
                question.create()
                tree << (candidate + [reconciliationRunId: question.get("reconciliationRunId")])
                imported++
            }
            runs << [reconciliationId: target.reconciliationId, reconciliationName: target.reconciliationName,
                     questionCount: tree.size()]
        }
        return [runs: runs, imported: imported, skipped: skipped]
    }

    /**
     * A NetSuite SuiteQL FILE_1 source, its query, and the population it starts from. {@code signature} is
     * everything a START must share with a check for the check's rows to be a subset of the START's:
     * the account, record type, table, date column, and how the key is built and which columns feed it.
     * {@code keyColumns} is null when the query has no column for some part of the key.
     */
    static Map describe(def ec, def scope, def source) {
        String ruleSetId = scope.get("ruleSetId") as String
        def ruleSet = TenantScopedFinder.findTenantScopedByIdQuiet(ec, "darpan.rule.RuleSet", "ruleSetId", ruleSetId)
        def query = TenantScopedFinder.findTenantScopedByIdQuiet(ec, QUERY, "nsSuiteQlSourceQueryId", source.get("sourceConfigId"))
        if (ruleSet == null || query == null) return null
        List<String> keyFields = (TenantScopedFinder.findTenantScoped(ec, KEY_FIELD).condition("compareScopeId", scope.get("compareScopeId"))
                .condition("fileSide", "FILE_1").orderBy("sequenceNum").useCache(false).list() ?: [])
                .collect { it.get("fieldExpression") as String }
        String primaryId = (source.get("primaryIdExpression") ?: "internalId") as String
        // A composite key replaces primaryIdExpression; a "|NORMALIZER" suffix is not part of the record field name.
        List<String> keyRecordFields = (keyFields ?: [primaryId]).collect { it.tokenize("|")[0].trim() }.unique()
        // The field rows carry no tenant column; they are reached through their (tenant-gated) query.
        Map<String, Object> columnByField = (TenantScopedFinder.findTenantScopedChildren(ec, QUERY_FIELD, QUERY, "nsSuiteQlSourceQueryId",
                query.get("nsSuiteQlSourceQueryId"), "nsSuiteQlSourceQueryId")?.useCache(false)?.list() ?: [])
                .collectEntries { [(it.get("recordFieldName")): it] }
        String missing = keyRecordFields.find { !columnByField.containsKey(it) }
        List<List> keyColumns = missing ? null : keyRecordFields.collect { String f ->
            [f, columnByField[f].get("sourceColumn"), columnByField[f].get("sourceColumnAlias")] }
        return [ruleSetId      : ruleSetId, ruleSet: ruleSet, scope: scope, source: source, query: query,
                template       : ((query.get("queryTemplate") ?: "RECORDS") as String).toUpperCase(),
                nsAuthConfigId : query.get("nsAuthConfigId") as String, recordType: query.get("recordType") as String,
                keyFields      : keyFields, keyColumns: keyColumns, missingKeyField: missing,
                signature      : [query.get("nsAuthConfigId"), query.get("recordType"), query.get("fromTable") ?: "transaction",
                                  query.get("dateColumn") ?: "trandate", primaryId, source.get("idValueNormalizer"), keyFields, keyColumns]]
    }

    /** The STARTs an earlier import made, by signature, so a repeat import adds to them instead of making a second run. */
    private static Map<List, Map> importedStarts(def ec, List activeQuestions) {
        Map<List, Map> found = [:]
        activeQuestions.findAll { it.get("questionRole") == RunFlowchartTree.ROLE_START }.each { def start ->
            def run = RunFlowchartSupport.findReconciliation(ec, start.get("reconciliationId") as String)
            if (run == null || run.get("isArchived") == "Y" || !(run.get("reconciliationName") as String)?.startsWith(RUN_NAME_PREFIX)) return
            def scope = TenantScopedFinder.findTenantScoped(ec, SCOPE).condition("ruleSetId", start.get("ruleSetId")).useCache(false).list()?.find()
            def source = scope ? TenantScopedFinder.findTenantScoped(ec, SOURCE).condition("compareScopeId", scope.get("compareScopeId"))
                    .condition("fileSide", "FILE_1").useCache(false).one() : null
            if (source?.get("sourceConfigType") != "NETSUITE_SUITEQL") return
            Map described = describe(ec, scope, source)
            if (described?.keyColumns == null) return
            found.putIfAbsent(described.signature as List, [reconciliationId: run.get("reconciliationId"),
                    reconciliationName: run.get("reconciliationName"), startQuestionId: start.get("reconciliationRunId")])
        }
        return found
    }

    static String uniqueName(String base, Set<String> taken) {
        if (!taken.contains(base)) return base
        int n = 2
        while (taken.contains("${base} (${n})".toString())) n++
        return "${base} (${n})".toString()
    }

    /** One run whose START lists every record of the check's population: a keys-only RECORDS query and a one-source rule. */
    private static Map createStartRun(def ec, String name, String label, Map sample, String tenantId, String userId) {
        def now = ec.user.nowTimestamp
        def query = ec.entity.makeValue(QUERY)
        query.setSequencedIdPrimary()
        query.setAll([nsAuthConfigId: sample.nsAuthConfigId, companyUserGroupId: tenantId, createdByUserId: userId,
                description: "Every ${label} in the window".toString(), recordType: sample.recordType,
                fromTable: sample.query.get("fromTable"), dateColumn: sample.query.get("dateColumn"), queryTemplate: "RECORDS",
                isActive: "Y", createdDate: now, lastUpdatedDate: now])
        query.create()
        (sample.keyColumns as List<List>).eachWithIndex { List column, int i ->
            ec.entity.makeValue(QUERY_FIELD).setAll([nsSuiteQlSourceQueryId: query.get("nsSuiteQlSourceQueryId"), recordFieldName: column[0],
                    sourceColumn: column[1], sourceColumnAlias: column[2], sequenceNum: i + 1]).create()
        }

        def ruleSet = ec.entity.makeValue("darpan.rule.RuleSet")
        ruleSet.setSequencedIdPrimary()
        ruleSet.setAll([ruleSetName: "${name}: every ${label}".toString(), companyUserGroupId: tenantId, createdByUserId: userId,
                explosionPath: sample.ruleSet.get("explosionPath"), primaryKeyPath: sample.ruleSet.get("primaryKeyPath"),
                createdDate: now, lastUpdatedDate: now])
        ruleSet.create()
        def scope = ec.entity.makeValue(SCOPE)
        scope.setSequencedIdPrimary()
        scope.setAll([ruleSetId: ruleSet.get("ruleSetId"), scopeMode: RunFlowchartTree.MODE_EVALUATE, objectType: sample.scope.get("objectType"),
                companyUserGroupId: tenantId, createdDate: now, lastUpdatedDate: now])
        scope.create()
        def source = ec.entity.makeValue(SOURCE)
        source.setAll(((Map) sample.source.getMap()).findAll { k, v -> !(k in ["compareScopeId", "sourceConfigId", "lastUpdatedStamp"]) })
        source.setAll([compareScopeId: scope.get("compareScopeId"), sourceConfigId: query.get("nsSuiteQlSourceQueryId"),
                companyUserGroupId: tenantId, createdDate: now, lastUpdatedDate: now])
        source.create()
        (sample.keyFields as List<String>).eachWithIndex { String expression, int i ->
            ec.entity.makeValue(KEY_FIELD).setAll([compareScopeId: scope.get("compareScopeId"), fileSide: "FILE_1", sequenceNum: i + 1,
                    fieldExpression: expression, companyUserGroupId: tenantId, createdDate: now, lastUpdatedDate: now]).create()
        }

        def run = ec.entity.makeValue(RunFlowchartSupport.RECONCILIATION)
        // Not assignTenantOwnershipOnCreate: Reconciliation has no createdByUserId (see saveReconciliation).
        run.setSequencedIdPrimary()
        run.setAll([reconciliationName: name, companyUserGroupId: tenantId, defaultTimeWindow: "1d"])
        run.create()
        def start = ec.entity.makeValue(RunFlowchartSupport.QUESTION)
        start.setSequencedIdPrimary()
        start.setAll([reconciliationId: run.get("reconciliationId"), companyUserGroupId: tenantId, ruleSetId: ruleSet.get("ruleSetId"),
                questionRole: RunFlowchartTree.ROLE_START, runName: "Every ${label}".toString(), runSequence: 1])
        start.create()
        return [reconciliationId: run.get("reconciliationId"), reconciliationName: name, startQuestionId: start.get("reconciliationRunId")]
    }
}
