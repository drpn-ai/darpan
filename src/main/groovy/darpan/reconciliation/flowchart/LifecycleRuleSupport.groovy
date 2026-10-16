package darpan.reconciliation.flowchart

import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.util.regex.Pattern

import static darpan.common.ValueSupport.normalize

/**
 * DAR-UI-050. A lifecycle's rules leave records out: "leave out sales orders where HC Sales Channel is Shopify Draft
 * Order", "... where HC Order Id is No value". A rule is an EXCLUDE_IN source filter on the start's records, and every
 * later question only asks about records that passed the start, so a left-out record is in no place.
 *
 * The start's query gains the field as NVL(field, 'NO_VALUE'), so an empty value is a value a rule can name: a filter
 * can never match a null (DAR-BE-032). One filter row per field; its values are the rules on that field.
 */
class LifecycleRuleSupport {

    static final String NO_VALUE = "NO_VALUE"
    static final String FILTER = "darpan.rule.RuleSetCompareSourceFilter"
    /** NetSuite field ids reach SuiteQL by interpolation, so only a plain lower-case identifier passes. */
    static final Pattern FIELD_ID = ~/^[a-z][a-z0-9_]{0,99}$/

    static Map saveRule(def ec, Map params) {
        if (!TenantAccessSupport.requireActiveTenantWriteAccess(ec, "Your active tenant only has view access for runs.")) return [:]
        String fieldId = normalize(params.fieldId)
        String fieldLabel = normalize(params.fieldLabel) ?: fieldId
        String value = normalize(params.value)
        if (!fieldId || !FIELD_ID.matcher(fieldId).matches()) { ec.message.addError("'${fieldId}' is not a field a rule can use."); return [:] }
        if (!value) { ec.message.addError("Choose a value."); return [:] }
        // The filter stores its values comma-separated, so a comma would split one value into two.
        if (value.contains(",")) { ec.message.addError("A value with a comma cannot be used in a rule yet."); return [:] }
        Map start = startOf(ec, normalize(params.reconciliationId))
        if (start == null) return [:]

        def now = ec.user.nowTimestamp
        String queryId = start.source.get("sourceConfigId") as String
        def column = TenantScopedFinder.findTenantScopedChildren(ec, LifecycleSupport.QUERY_FIELD, LifecycleSupport.QUERY,
                "nsSuiteQlSourceQueryId", queryId, "nsSuiteQlSourceQueryId")?.condition("recordFieldName", fieldId)?.useCache(false)?.one()
        // A standard field (status) is read as every record shows it, by name; it is never empty, so needs no NVL.
        List<String> standard = LifecycleSupport.COLUMNS.find { it[0] == fieldId && it[0] != "internalId" }
        if (standard && column != null && column.get("sourceColumn") != standard[1]) column.set("sourceColumn", standard[1]).update()
        if (column == null) {
            long next = (TenantScopedFinder.findTenantScopedChildren(ec, LifecycleSupport.QUERY_FIELD, LifecycleSupport.QUERY,
                    "nsSuiteQlSourceQueryId", queryId, "nsSuiteQlSourceQueryId")?.useCache(false)?.list() ?: [])
                    .collect { (it.get("sequenceNum") ?: 0) as long }.max() ?: 0L
            ec.entity.makeValue(LifecycleSupport.QUERY_FIELD).setAll([nsSuiteQlSourceQueryId: queryId, recordFieldName: fieldId,
                    sourceColumn: standard ? standard[1] : "NVL(${fieldId}, '${NO_VALUE}') AS ${fieldId}".toString(), sequenceNum: next + 1]).create()
        }

        def filter = filtersOf(ec, start).find { it.get("fieldExpression") == fieldId }
        if (filter == null) {
            long next = (filtersOf(ec, start).collect { (it.get("sequenceNum") ?: 0) as long }.max() ?: 0L) as long
            ec.entity.makeValue(FILTER).setAll([compareScopeId: start.scope.get("compareScopeId"), fileSide: "FILE_1",
                    sequenceNum: next + 1, fieldExpression: fieldId, fieldLabel: fieldLabel,
                    operator: "EXCLUDE_IN", filterValues: value,
                    companyUserGroupId: start.reconciliation.get("companyUserGroupId"), createdDate: now, lastUpdatedDate: now]).create()
        } else {
            List<String> values = valuesOf(filter)
            if (!values.contains(value)) {
                filter.setAll([filterValues: (values + [value]).join(","), fieldLabel: fieldLabel, lastUpdatedDate: now]).update()
            }
        }
        return [rules: listRules(ec, start)]
    }

    static Map deleteRule(def ec, Map params) {
        if (!TenantAccessSupport.requireActiveTenantWriteAccess(ec, "Your active tenant only has view access for runs.")) return [:]
        Map start = startOf(ec, normalize(params.reconciliationId))
        if (start == null) return [:]
        String fieldId = normalize(params.fieldId), value = normalize(params.value)
        def filter = filtersOf(ec, start).find { it.get("fieldExpression") == fieldId }
        if (filter == null || !valuesOf(filter).contains(value)) { ec.message.addError("That rule was not found."); return [:] }
        List<String> left = valuesOf(filter) - [value]
        // The query keeps the column: harmless, and a past run's records were read with it.
        if (left) filter.setAll([filterValues: left.join(","), lastUpdatedDate: ec.user.nowTimestamp]).update()
        else filter.delete()
        return [rules: listRules(ec, start)]
    }

    /**
     * A stage exception: a record whose field holds this value counts as having the stage ("Fulfillment is not expected
     * when Shipping Method is Store Pickup"). It is written on every check of this lifecycle that asks about the stage,
     * the "is it missing?" ones and the "has it?" ones, so the record lands where it would had it reached the stage.
     * One field per stage; its values are the exceptions.
     */
    static Map saveException(def ec, Map params) {
        if (!TenantAccessSupport.requireActiveTenantWriteAccess(ec, "Your active tenant only has view access for runs.")) return [:]
        String reconciliationId = normalize(params.reconciliationId)
        String stage = normalize(params.stageRecordType), fieldId = normalize(params.fieldId), value = normalize(params.value)
        String fieldLabel = normalize(params.fieldLabel) ?: fieldId
        if (!fieldId || !FIELD_ID.matcher(fieldId).matches()) { ec.message.addError("'${fieldId}' is not a field an exception can use."); return [:] }
        if (!value) { ec.message.addError("Choose a value."); return [:] }
        if (value.contains(",") || value.contains("'")) { ec.message.addError("A value with a comma or a quote cannot be used yet."); return [:] }
        if (startOf(ec, reconciliationId) == null) return [:]
        List queries = stageQueries(ec, reconciliationId, stage)
        if (!queries) { ec.message.addError("'${stage}' is not a stage of this lifecycle."); return [:] }
        // A stage holds exceptions on any number of fields, each with its values; a record any of them names is waived.
        List<Map> rules = waiveRulesOf(queries[0])
        Map rule = rules.find { it.fieldId == fieldId }
        if (rule == null) rules << (rule = [fieldId: fieldId, fieldLabel: fieldLabel, values: []])
        if (!(rule.values as List).contains(value)) (rule.values as List) << value
        writeWaiveRules(ec, queries, rules)
        return [exceptions: listExceptions(ec, reconciliationId)]
    }

    static Map deleteException(def ec, Map params) {
        if (!TenantAccessSupport.requireActiveTenantWriteAccess(ec, "Your active tenant only has view access for runs.")) return [:]
        String reconciliationId = normalize(params.reconciliationId)
        String stage = normalize(params.stageRecordType), value = normalize(params.value), fieldId = normalize(params.fieldId)
        if (startOf(ec, reconciliationId) == null) return [:]
        List queries = stageQueries(ec, reconciliationId, stage)
        List<Map> rules = queries ? waiveRulesOf(queries[0]) : []
        // fieldId names which field's value goes; without it, the one field that holds the value.
        Map rule = rules.find { (!fieldId || it.fieldId == fieldId) && (it.values as List).contains(value) }
        if (rule == null) { ec.message.addError("That exception was not found."); return [:] }
        rule.values = (rule.values as List) - [value]
        writeWaiveRules(ec, queries, rules.findAll { it.values })
        return [exceptions: listExceptions(ec, reconciliationId)]
    }

    /** The stages in order, as the spine runs: each the first question on the previous one's "yes". */
    static List<Map> stagesOf(def ec, String reconciliationId) {
        List<Map> qs = RunFlowchartSupport.loadQuestions(ec, reconciliationId).findAll { it.isActive != "N" }
        Closure<Map> firstYes = { String id -> qs.findAll { it.parentReconciliationRunId == id && it.parentBranch == RunFlowchartTree.BRANCH_YES }
                .min { (it.runSequence ?: 0) as int } }
        List<Map> out = []
        Map current = qs.find { it.questionRole == RunFlowchartTree.ROLE_START }
        while ((current = current ? firstYes(current.reconciliationRunId as String) : null) != null) {
            String type = queryOf(ec, current.ruleSetId as String)?.get("absentChildRecordType") as String
            if (type) out << [recordType: type, label: LifecycleStageCompiler.RECORD_TYPES[type]?.name ?: current.runName]
        }
        return out
    }

    static List<Map> listExceptions(def ec, String reconciliationId) {
        return stagesOf(ec, reconciliationId).collectMany { Map st ->
            def q = stageQueries(ec, reconciliationId, st.recordType as String).find()
            (q ? waiveRulesOf(q) : []).collectMany { Map r -> (r.values as List).collect { v ->
                [stageRecordType: st.recordType, stageLabel: st.label, fieldId: r.fieldId, fieldLabel: r.fieldLabel ?: r.fieldId, value: v] } }
        } as List<Map>
    }

    /** A stage check's exceptions, as stored (NsSuiteQlSourceQuery.waiveRules): [{fieldId, fieldLabel, values}]. */
    private static List<Map> waiveRulesOf(def query) {
        String raw = query?.get("waiveRules") as String
        if (!raw?.trim()) return []
        return ((new JsonSlurper().parseText(raw) ?: []) as List<Map>).collect { Map r ->
            [fieldId: r.fieldId, fieldLabel: r.fieldLabel ?: r.fieldId, values: ((r.values ?: []) as List).collect { it as String }] as Map }
    }

    private static void writeWaiveRules(def ec, List queries, List<Map> rules) {
        String json = rules ? JsonOutput.toJson(rules) : null
        queries.each { it.setAll([waiveRules: json, lastUpdatedDate: ec.user.nowTimestamp]).update() }
    }

    /** Every check of this lifecycle that asks about the stage, once each (questions share queries). */
    private static List stageQueries(def ec, String reconciliationId, String stage) {
        if (!stage) return []
        return RunFlowchartSupport.loadQuestions(ec, reconciliationId)
                .findAll { it.questionRole != RunFlowchartTree.ROLE_START && it.isActive != "N" }
                .collect { queryOf(ec, it.ruleSetId as String) }.findAll { it != null }
                .findAll { it.get("absentChildRecordType") == stage || it.get("presentChildRecordType") == stage }
                .unique { it.get("nsSuiteQlSourceQueryId") }
    }

    static def queryForRule(def ec, String ruleSetId) { return queryOf(ec, ruleSetId) }

    private static def queryOf(def ec, String ruleSetId) {
        def scope = ruleSetId ? TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSetCompareScope")
                .condition("ruleSetId", ruleSetId).useCache(false).list()?.find() : null
        def source = scope ? TenantScopedFinder.findTenantScopedChildren(ec, "darpan.rule.RuleSetCompareSource", "darpan.rule.RuleSetCompareScope",
                "compareScopeId", scope.get("compareScopeId"), "compareScopeId")?.condition("fileSide", "FILE_1")?.useCache(false)?.one() : null
        return source ? TenantScopedFinder.findTenantScopedByIdQuiet(ec, LifecycleSupport.QUERY, "nsSuiteQlSourceQueryId", source.get("sourceConfigId")) : null
    }

    /** For get#Reconciliation on a lifecycle built from stages: its rules, and what its start reads (for the dropdowns). */
    static Map describe(def ec, String reconciliationId) {
        Map start = startOf(ec, reconciliationId, true)
        if (start == null) return [:]
        String recordType = start.query.get("recordType") as String
        return [details  : LifecycleSupport.detailsOf(ec, reconciliationId),
                rules    : listRules(ec, start),
                stages   : stagesOf(ec, reconciliationId),
                exceptions: listExceptions(ec, reconciliationId),
                lifecycle: [nsAuthConfigId: start.query.get("nsAuthConfigId"), recordType: recordType,
                            recordLabel   : LifecycleStageCompiler.RECORD_TYPES[recordType]?.plural ?: recordType]]
    }

    static List<Map> listRules(def ec, Map start) {
        return filtersOf(ec, start).findAll { it.get("operator") == "EXCLUDE_IN" }.collectMany { def f ->
            valuesOf(f).collect { String v ->
                [fieldId   : f.get("fieldExpression"), fieldLabel: f.get("fieldLabel") ?: f.get("fieldExpression"),
                 value     : v, valueLabel: v == NO_VALUE ? "No value" : valueLabel(f.get("fieldExpression") as String, v)]
            }
        } as List<Map>
    }

    /** A standard field's value reads "Sales Order : Closed"; on a lifecycle of sales orders, "Closed" says it. */
    private static String valueLabel(String fieldId, String value) {
        boolean standard = LifecycleSupport.COLUMNS.any { it[0] == fieldId }
        int at = value.indexOf(" : ")
        return standard && at > 0 ? value.substring(at + 3) : value
    }

    private static List filtersOf(def ec, Map start) {
        return (TenantScopedFinder.findTenantScoped(ec, FILTER).condition("compareScopeId", start.scope.get("compareScopeId"))
                .condition("fileSide", "FILE_1").orderBy("sequenceNum").useCache(false).list() ?: []) as List
    }

    private static List<String> valuesOf(def filter) {
        return ((filter.get("filterValues") ?: "") as String).split(",").collect { it.trim() }.findAll { it } as List<String>
    }

    /** The lifecycle's START with its rule's scope, FILE_1 source and query; null (with an error unless quiet) otherwise. */
    private static Map startOf(def ec, String reconciliationId, boolean quiet = false) {
        def reconciliation = RunFlowchartSupport.findReconciliation(ec, reconciliationId)
        if (reconciliation == null || reconciliation.get("buildMode") != LifecycleSupport.BUILD_MODE_STAGES) {
            if (!quiet) ec.message.addError("Rules belong to a lifecycle built from stages; '${reconciliationId}' is not one.")
            return null
        }
        Map startQ = RunFlowchartSupport.loadQuestions(ec, reconciliationId)
                .find { it.questionRole == RunFlowchartTree.ROLE_START && it.isActive != "N" }
        def scope = startQ ? TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSetCompareScope")
                .condition("ruleSetId", startQ.ruleSetId).useCache(false).list()?.find() : null
        def source = scope ? TenantScopedFinder.findTenantScopedChildren(ec, "darpan.rule.RuleSetCompareSource", "darpan.rule.RuleSetCompareScope",
                "compareScopeId", scope.get("compareScopeId"), "compareScopeId")?.condition("fileSide", "FILE_1")?.useCache(false)?.one() : null
        def query = source ? TenantScopedFinder.findTenantScopedByIdQuiet(ec, LifecycleSupport.QUERY, "nsSuiteQlSourceQueryId",
                source.get("sourceConfigId")) : null
        if (query == null) {
            if (!quiet) ec.message.addError("This lifecycle's start could not be found.")
            return null
        }
        return [reconciliation: reconciliation, scope: scope, source: source, query: query]
    }
}
