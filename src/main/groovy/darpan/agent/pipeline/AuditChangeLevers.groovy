package darpan.agent.pipeline

import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.facade.reconciliation.AutomationFacadeSupport
import darpan.facade.reconciliation.ReconciliationSavedRunSupport
import darpan.reconciliation.conclusion.RunConclusionAuthoringSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.util.regex.Pattern

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-159. The four levers an agent proposal may move, each through the save a person already uses:
 * EXCLUSION_FILTER (save#RuleSetRun, then sync#Automation), STATEMENT_NARROWING (save#AuditCheck), FIX_TEXT and
 * CONCLUSION_ROWS (save#RunConclusions).
 *
 * <p>{@link #current} reads the lever as a plain state map of the active tenant (only config fields, no stamps, so two
 * reads of an unchanged config are equal); {@link #merge} applies a proposal's R18 `after` to it; {@link #save} builds
 * the save's whole payload from a state map and calls the save. A state carries everything the save rewrites, because
 * each of these saves replaces what it is given: the whole run setup, the whole check with all its links in the audit,
 * the whole conclusion tree. Only fields this class names are ever read from a state or an `after`.</p>
 */
class AuditChangeLevers {

    static final List<String> KINDS = ["EXCLUSION_FILTER", "STATEMENT_NARROWING", "FIX_TEXT", "CONCLUSION_ROWS"].asImmutable()
    static final Map<String, List<String>> TARGET_KEYS = [
            EXCLUSION_FILTER   : ["savedRunId", "fileSide"],
            STATEMENT_NARROWING: ["auditId", "checkCode", "statementCode"],
            FIX_TEXT           : ["savedRunId", "conclusionCode"],
            CONCLUSION_ROWS    : ["savedRunId"]].asImmutable()
    static final List<String> SIDES = ["FILE_1", "FILE_2"].asImmutable()
    static final List<String> SIDE_FIELDS = ["systemEnumId", "sourceTypeEnumId", "systemMessageRemoteId", "nsRestletConfigId",
            "sourceConfigId", "sourceConfigType", "fileTypeEnumId", "schemaFileName", "recordRootExpression"].asImmutable()
    static final List<String> CHECK_TEXT_FIELDS = ["label", "file1RecordType", "file2RecordType", "matchedBy", "fixOwner",
            "expectedSource", "actualSource", "headlineTemplate", "fixActionTemplate", "passesWhenTemplate"].asImmutable()
    static final List<String> NARROWING_KEYS = ["diffTypes", "conclusionCodes", "ruleIds"].asImmutable()
    static final List<String> NODE_KEYS = ["key", "parent", "conclusion", "bucket", "question", "fix", "conditions"].asImmutable()
    static final List<String> CONDITION_KEYS = ["subject", "presence", "keyScope", "quantifier", "field", "operator", "values",
            "checkLabel"].asImmutable()
    private static final Pattern EDGE_PUNCT = Pattern.compile("^[.,:;!?()\"']+|[.,:;!?()\"']+\$")
    private static final String WHY = "agent change: tenant compared on the row"

    /** Fail-closed target contract: the kind's keys only, each a non-blank string. Throws IllegalArgumentException. */
    static void validateTarget(String kind, Map target) {
        List<String> keys = TARGET_KEYS[kind]
        if (keys == null) throw new IllegalArgumentException("Unknown proposal kind: ${kind}".toString())
        if (target == null) throw new IllegalArgumentException("Proposal 'target' is missing")
        target.keySet().each { Object k -> if (!(k in keys)) throw new IllegalArgumentException("Unknown key '${k}' in target".toString()) }
        keys.each { String k ->
            Object v = target.get(k)
            if (!(v instanceof String) || !((String) v).trim()) throw new IllegalArgumentException("target.${k} is required".toString())
        }
        if (kind == "EXCLUSION_FILTER" && !(target.fileSide in SIDES)) throw new IllegalArgumentException("target.fileSide is FILE_1 or FILE_2")
    }

    // ---------------------------------------------------------------- current

    /** The lever's state in the active tenant, or null (with an error) when the target is not the tenant's. */
    static Map current(def ec, String kind, Map target) {
        validateTarget(kind, target)
        Map t = [:]
        TARGET_KEYS[kind].each { String k -> t[k] = target.get(k) }
        switch (kind) {
            case "EXCLUSION_FILTER": return runState(ec, t)
            case "STATEMENT_NARROWING": return checkState(ec, t)
            default: return treeState(ec, kind, t)
        }
    }

    private static Map runState(def ec, Map target) {
        Map resolved = ReconciliationSavedRunSupport.resolveRuleSetRun(ec, target.savedRunId)
        if (resolved.error || resolved.savedRun == null) {
            ec.message.addError((resolved.error ?: "Run '${target.savedRunId}' was not found.").toString())
            return null
        }
        Map run = (Map) resolved.savedRun
        Map sourceBySide = (Map) resolved.sourceBySide
        Map sides = [:]
        Map filters = [:]
        SIDES.each { String side ->
            def source = sourceBySide[side]
            if (source == null) return
            Map fields = [:]
            SIDE_FIELDS.each { String f -> fields[f] = normalize(source.get(f)) ?: null }
            Map option = ((List<Map>) resolved.systemOptions).find { it.fileSide == side }
            List<String> keyFields = ((option?.idFieldExpressions ?: []) as List).collect { it as String }
            fields.primaryIdExpression = keyFields.size() > 1 ? null : (normalize(source.get("primaryIdExpression")) ?: null)
            fields.primaryIdExpressions = keyFields.size() > 1 ? keyFields : []
            sides[side] = fields
            filters[side] = ReconciliationSavedRunSupport.readExcludeFilterRows(ec, source).collect { Map r ->
                [fieldExpression: r.fieldExpression, operator: r.operator, filterValues: splitValues(r.filterValues)] as Map
            }
        }
        if (sides[target.fileSide] == null) {
            ec.message.addError("Run '${target.savedRunId}' has no ${target.fileSide}.".toString())
            return null
        }
        return [kind: "EXCLUSION_FILTER", target: target,
                run : [savedRunId: run.savedRunId, runName: run.runName, description: run.description, sides: sides],
                filters: filters]
    }

    private static Map checkState(def ec, Map target) {
        String tenantId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        def audit = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.Audit", "auditId", target.auditId, tenantId, WHY)
        if (audit == null && tenantId) {
            audit = ec.entity.find("darpan.audit.Audit").condition("companyUserGroupId", tenantId).condition("auditCode", target.auditId)
                    .useCache(false).list().find()
        }
        if (audit == null) { ec.message.addError("Audit '${target.auditId}' was not found.".toString()); return null }
        def check = ec.entity.find("darpan.audit.AuditCheck").condition("companyUserGroupId", tenantId)
                .condition("checkCode", target.checkCode).useCache(false).list().find()
        if (check == null) { ec.message.addError("Check '${target.checkCode}' was not found.".toString()); return null }
        Map<String, String> codeById = ec.entity.find("darpan.audit.AuditStatement").condition("companyUserGroupId", tenantId)
                .condition("auditId", audit.auditId).useCache(false).list()
                .collectEntries { [(it.statementId as String): it.statementCode as String] }
        List<Map> links = ec.entity.find("darpan.audit.AuditStatementCheck").condition("companyUserGroupId", tenantId)
                .condition("checkId", check.checkId).orderBy(["sequenceNum", "statementId"]).useCache(false).list()
                .findAll { codeById.containsKey(it.statementId as String) }
                .collect { [id: codeById[it.statementId as String], diffTypes: csv(it.diffTypes), conclusionCodes: csv(it.conclusionCodes),
                            ruleIds: csv(it.ruleIds)] as Map }
                .sort { Map a, Map b -> (a.id as String) <=> (b.id as String) }
        Map c = [id: check.checkCode, savedRunId: check.savedRunId, fixWhereEnumId: check.fixWhereEnumId,
                 graceDays: check.graceDays == null ? null : check.graceDays.toString(), amountCurrencyUomId: check.amountCurrencyUomId]
        CHECK_TEXT_FIELDS.each { String f -> c[f] = check.get(f) }
        return [kind: "STATEMENT_NARROWING", target: target, audit: audit.auditCode, check: c, statements: links]
    }

    private static Map treeState(def ec, String kind, Map target) {
        Map tree = RunConclusionAuthoringSupport.get(ec, target.savedRunId as String)
        if (tree == null) return null
        List<Map> nodes = ((List<Map>) tree.conclusions).collect { Map n -> pick(n, NODE_KEYS) + [conditions: conditionsOf(n.conditions)] }
        return [kind: kind, target: target, savedRunId: target.savedRunId, conclusions: nodes]
    }

    // ---------------------------------------------------------------- merge

    /**
     * The state after the proposal: a copy of current with only the lever changed. `after` must already have passed
     * AgentProvenance.validateShape; only its R18 keys are read. Throws IllegalArgumentException when it cannot apply.
     */
    static Map merge(String kind, Map current, Map after) {
        AgentProvenance.validateShape(kind, after)
        Map state = copy(current)
        Map target = (Map) state.target
        switch (kind) {
            case "EXCLUSION_FILTER":
                Map filter = [fieldExpression: after.fieldExpression as String,
                              operator       : ((after.operator ?: "EXCLUDE_IN") as String).toUpperCase(Locale.ROOT),
                              filterValues   : ((List) after.filterValues).collect { it as String }]
                List<Map> side = (List<Map>) ((Map) state.filters)[target.fileSide]
                int at = side.findIndexOf { it.fieldExpression == filter.fieldExpression }
                if (at >= 0) side[at] = filter else side << filter
                break
            case "STATEMENT_NARROWING":
                Map link = ((List<Map>) state.statements).find { it.id == target.statementCode }
                if (link == null) throw new IllegalArgumentException("Statement ${target.statementCode} is not linked to check ${target.checkCode}.".toString())
                NARROWING_KEYS.each { String k -> if (after.get(k) != null) link[k] = ((List) after.get(k)).collect { it as String } }
                break
            case "FIX_TEXT":
                onlyNode((List<Map>) state.conclusions, target.conclusionCode as String).fix = after.fixActionTemplate as String
                break
            case "CONCLUSION_ROWS":
                List<Map> nodes = (List<Map>) state.conclusions
                ((List<Map>) after.rows).each { Map row -> mergeRow(nodes, row) }
                break
        }
        return state
    }

    /** Named row replaces the one node of its code (only the fields it gives) or is added at the end. */
    private static void mergeRow(List<Map> nodes, Map row) {
        String code = row.conclusionCode as String
        Map existing = nodeOf(nodes, code)
        Map node = existing ?: [key: freshKey(nodes), parent: null, conclusion: code, bucket: null, question: null, fix: null, conditions: []]
        if (row.parentConclusionCode != null) node.parent = onlyNode(nodes, row.parentConclusionCode as String).key
        if (row.bucket != null) node.bucket = row.bucket as String
        if (row.fixActionTemplate != null) node.fix = row.fixActionTemplate as String
        if (row.conditions != null) {
            List<Map> old = (List<Map>) node.conditions
            node.conditions = ((List<Map>) row.conditions).collect { Map c ->
                String field = c.fieldExpression as String, op = c.operator as String
                Map was = old.find { it.field == field && it.operator == op }
                Object cv = c.checkValue
                List<String> values = cv == null ? [] : (op in ["IN", "NOT_IN"] ? (cv as String).split(",", -1).toList() : [cv as String])
                [subject   : was?.subject ?: "FILE_1", presence: was?.presence ?: "ANY", keyScope: was?.keyScope ?: "FULL",
                 quantifier: was?.quantifier ?: "ALL", field: field, operator: op, values: values,
                 checkLabel: was?.checkLabel ?: "${field} ${op}".toString()] as Map
            }
        }
        if (existing == null) nodes << node
    }

    // ---------------------------------------------------------------- revert

    /**
     * The part of a state a proposal moved, so a revert can put exactly that back. `after` is the proposal's own (an R18
     * after, or the lever of the revert being reverted): it names the filter's fieldExpression or the rows it touched.
     */
    static Map leverOf(String kind, Map state, Map after) {
        Map target = (Map) state.target
        switch (kind) {
            case "EXCLUSION_FILTER":
                // Only the proposal's own entry: null when it added the filter, the old entry when it replaced one.
                String field = after.fieldExpression as String
                Map was = ((List<Map>) ((Map) state.filters)[target.fileSide]).find { it.fieldExpression == field }
                return [fieldExpression: field, entry: copy(was)]
            case "STATEMENT_NARROWING": return [link: copy(((List<Map>) state.statements).find { it.id == target.statementCode })]
            case "FIX_TEXT": return [fix: onlyNode((List<Map>) state.conclusions, target.conclusionCode as String).fix]
            default:
                List<Map> nodes = (List<Map>) state.conclusions
                List<String> codes = ((List<Map>) (after.rows ?: [])).collect { (it.conclusionCode ?: it.code) as String }
                return [rows: codes.collect { String code ->
                    Map n = nodeOf(nodes, code)
                    [code: code, node: n == null ? null : [parentConclusion: nodes.find { it.key == n.parent }?.conclusion,
                                                         bucket: n.bucket, question: n.question, fix: n.fix, conditions: copy(n.conditions)]]
                }]
        }
    }

    /** Current with the lever put back as it was (a revert). Reads only the keys leverOf writes. */
    static Map restore(String kind, Map current, Map lever) {
        Map state = copy(current)
        Map target = (Map) state.target
        switch (kind) {
            case "EXCLUSION_FILTER":
                List<Map> side = (List<Map>) ((Map) state.filters)[target.fileSide]
                String field = lever.fieldExpression as String
                int at = side.findIndexOf { it.fieldExpression == field }
                Map was = (Map) lever.entry
                if (was == null) {
                    if (at >= 0) side.remove(at)
                } else {
                    Map entry = [fieldExpression: field, operator: was.operator as String,
                                 filterValues: ((List) was.filterValues).collect { it as String }]
                    if (at >= 0) side[at] = entry else side << entry
                }
                break
            case "STATEMENT_NARROWING":
                Map link = ((List<Map>) state.statements).find { it.id == target.statementCode }
                if (link == null) throw new IllegalArgumentException("Statement ${target.statementCode} is no longer linked to check ${target.checkCode}.".toString())
                NARROWING_KEYS.each { String k -> link[k] = (((Map) lever.link)?.get(k) ?: []).collect { it as String } }
                break
            case "FIX_TEXT":
                onlyNode((List<Map>) state.conclusions, target.conclusionCode as String).fix = lever.fix as String
                break
            default:
                List<Map> nodes = (List<Map>) state.conclusions
                ((List<Map>) lever.rows).each { Map row ->
                    Map existing = nodeOf(nodes, row.code as String)
                    Map was = (Map) row.node
                    if (was == null) { if (existing != null) nodes.remove(existing); return }
                    Map node = existing ?: [key: freshKey(nodes), conclusion: row.code as String]
                    node.parent = was.parentConclusion ? onlyNode(nodes, was.parentConclusion as String).key : null
                    node.bucket = was.bucket; node.question = was.question; node.fix = was.fix
                    node.conditions = conditionsOf(was.conditions)
                    if (existing == null) nodes << node
                }
        }
        return state
    }

    // ---------------------------------------------------------------- save

    /** Saves a state through the lever's own save; errors land on ec.message. Builds every payload field by name. */
    static void save(def ec, String kind, Map state) {
        Map target = (Map) state.target
        switch (kind) {
            case "EXCLUSION_FILTER":
                Map run = (Map) state.run
                Map params = [savedRunId: run.savedRunId, runName: run.runName, description: run.description]
                ((Map) run.sides).each { String side, Map f ->
                    String p = side == "FILE_1" ? "file1" : "file2"
                    SIDE_FIELDS.each { String field -> params["${p}${field.capitalize()}".toString()] = f.get(field) }
                    List keys = (List) f.primaryIdExpressions
                    if (keys) params["${p}PrimaryIdExpressions".toString()] = keys
                    else params["${p}PrimaryIdExpression".toString()] = f.primaryIdExpression
                }
                String sidePrefix = target.fileSide == "FILE_1" ? "file1" : "file2"
                // Only the proposal's side is sent; the other side stays null, the save's "no opinion".
                params["${sidePrefix}ExcludeFilters".toString()] = ((List<Map>) ((Map) state.filters)[target.fileSide]).collect { Map f ->
                    [fieldExpression: f.fieldExpression, operator: f.operator, values: f.filterValues]
                }
                ec.service.sync().name("facade.ReconciliationFacadeServices.save#RuleSetRun").parameters(params).call()
                if (ec.message.hasError()) return
                automationsOf(ec, run.savedRunId as String).each { String automationId ->
                    if (ec.message.hasError()) return
                    ec.service.sync().name("facade.ReconciliationFacadeServices.sync#Automation").parameters([automationId: automationId]).call()
                }
                break
            case "STATEMENT_NARROWING":
                Map c = (Map) state.check
                Map check = [id: c.id, savedRunId: c.savedRunId, fixWhereEnumId: c.fixWhereEnumId, graceDays: c.graceDays,
                             amountCurrencyUomId: c.amountCurrencyUomId]
                CHECK_TEXT_FIELDS.each { String f -> check[f] = c.get(f) }
                List links = ((List<Map>) state.statements).collect { Map l -> pick(l, ["id"] + NARROWING_KEYS) }
                ec.service.sync().name("facade.AuditFacadeServices.save#AuditCheck").parameters([audit: state.audit, check: check,
                        statements: links]).call()
                break
            default:
                // Lookups are left out, which the save reads as "keep them".
                List nodes = ((List<Map>) state.conclusions).collect { Map n -> pick(n, NODE_KEYS) + [conditions: conditionsOf(n.conditions)] }
                ec.service.sync().name("facade.RunConclusionFacadeServices.save#RunConclusions").parameters([savedRunId: state.savedRunId,
                        conclusions: nodes]).call()
        }
    }

    // ---------------------------------------------------------------- provenance and drift

    /** The lever's current literal values, which may vouch for a proposal's identifiers (R21). */
    static Set<String> configValues(String kind, Map state) {
        Set<String> out = new LinkedHashSet<String>()
        if (state == null) return out
        if (kind == "EXCLUSION_FILTER") {
            ((Map) state.filters).values().each { List<Map> side -> side.each { Map f -> out.addAll((List<String>) f.filterValues) } }
        } else if (kind in ["FIX_TEXT", "CONCLUSION_ROWS"]) {
            ((List<Map>) state.conclusions).each { Map n ->
                ((List<Map>) n.conditions).each { Map c -> out.addAll(((List) c.values).collect { it as String }) }
                if (n.fix) (n.fix as String).split(/\s+/).each { String w -> String t = EDGE_PUNCT.matcher(w).replaceAll(""); if (t) out.add(t) }
            }
        }
        return out
    }

    /**
     * Automations of the run (EXCLUSION_FILTER) that sync#Automation would change in any way TODAY, before the proposal:
     * compare scope, input mode, either side's source or filters, a side added or removed. Applying syncs every automation
     * of the run, which would silently overwrite such drift, so apply refuses instead. Uses the same derive and the same
     * comparison sync#Automation reports (AutomationFacadeSupport.buildAutomationSyncStatus). Answers {automationId: fields}.
     */
    static Map<String, List<String>> automationDrift(def ec, Map state) {
        Map<String, List<String>> drifted = new LinkedHashMap<String, List<String>>()
        automationRowsOf(ec, ((Map) state.run).savedRunId as String).each { automation ->
            String automationId = automation.automationId as String
            List stored = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.reconciliation.ReconciliationAutomationSource",
                    "agent apply: sources of an automation already read as the active tenant's").condition("automationId", automationId)
                    .orderBy("fileSide").useCache(false).list() ?: []
            Map derived = AutomationFacadeSupport.deriveRunAuthoritativeConfig(ec, automation)
            List<String> changed = (List<String>) AutomationFacadeSupport.buildAutomationSyncStatus(automation,
                    AutomationFacadeSupport.attachCapturedFilters(ec, stored, automationId), derived).changedFields
            if (changed) drifted.put(automationId, changed)
        }
        return drifted
    }

    private static List<String> automationsOf(def ec, String savedRunId) {
        return automationRowsOf(ec, savedRunId)*.automationId.collect { it as String }
    }

    private static List automationRowsOf(def ec, String savedRunId) {
        String tenantId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!tenantId || !savedRunId) return []
        return ec.entity.find("darpan.reconciliation.ReconciliationAutomation").condition("companyUserGroupId", tenantId)
                .condition("savedRunId", savedRunId).orderBy("automationId").useCache(false).list()
                .findAll { (normalize(it.savedRunType) ?: "ruleset").equalsIgnoreCase("ruleset") }
    }

    // ---------------------------------------------------------------- helpers

    private static Map nodeOf(List<Map> nodes, String code) {
        List<Map> found = nodes.findAll { it.conclusion == code }
        if (found.size() > 1) throw new IllegalArgumentException("The run has ${code} more than once; say which one in the run's settings.".toString())
        return found ? found[0] : null
    }

    private static Map onlyNode(List<Map> nodes, String code) {
        Map n = nodeOf(nodes, code)
        if (n == null) throw new IllegalArgumentException("The run has no ${code}.".toString())
        return n
    }

    private static String freshKey(List<Map> nodes) {
        Set<String> used = nodes*.key.collect { it as String } as Set<String>
        int i = nodes.size() + 1
        while (used.contains("new${i}".toString())) i++
        return "new${i}".toString()
    }

    private static List<Map> conditionsOf(Object raw) {
        return ((raw ?: []) as List<Map>).collect { Map c ->
            Map out = pick(c, CONDITION_KEYS)
            out.values = ((c.values ?: []) as List).collect { it as String }
            out
        }
    }

    private static Map pick(Map m, List<String> keys) {
        Map out = [:]
        keys.each { String k -> out[k] = m?.get(k) }
        return out
    }

    private static List<String> csv(Object v) {
        String s = normalize(v)
        return s ? s.split(",").collect { it.trim() }.findAll { it } : []
    }

    private static List<String> splitValues(Object v) {
        String s = normalize(v)
        return s ? s.split(",", -1).collect { it.trim() } : []
    }

    /** A deep copy through JSON: states hold only strings, lists, maps and nulls. */
    static Object copy(Object value) {
        return value == null ? null : new JsonSlurper().parseText(JsonOutput.toJson(value))
    }
}
