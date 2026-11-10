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
 * DAR-BE-159. The five levers an agent proposal may move, each through the save a person already uses:
 * EXCLUSION_FILTER (save#RuleSetRun, then sync#Automation), STATEMENT_NARROWING and EXPECTED_CAUSES (save#AuditCheck),
 * FIX_TEXT and CONCLUSION_ROWS (save#RunConclusions). EXPECTED_CAUSES is the check's run's expected causes (DAR-BE-196: a
 * cause no statement counts is expected on its run, so it reaches every check on that run).
 *
 * <p>{@link #current} reads the lever as a plain state map of the active tenant (only config fields, no stamps, so two
 * reads of an unchanged config are equal); {@link #merge} applies a proposal's R18 `after` to it; {@link #save} builds
 * the save's whole payload from a state map and calls the save. A state carries everything the save rewrites, because
 * each of these saves replaces what it is given: the whole run setup, the whole check with all its links in the audit,
 * the whole conclusion tree. Only fields this class names are ever read from a state or an `after`.</p>
 */
class AuditChangeLevers {

    static final List<String> KINDS = ["EXCLUSION_FILTER", "STATEMENT_NARROWING", "EXPECTED_CAUSES", "FIX_TEXT", "CONCLUSION_ROWS"].asImmutable()
    static final Map<String, List<String>> TARGET_KEYS = [
            EXCLUSION_FILTER   : ["savedRunId", "fileSide"],
            STATEMENT_NARROWING: ["auditId", "checkCode", "statementCode"],
            EXPECTED_CAUSES    : ["auditId", "checkCode"],
            FIX_TEXT           : ["savedRunId", "conclusionCode"],
            CONCLUSION_ROWS    : ["savedRunId"]].asImmutable()
    static final List<String> SIDES = ["FILE_1", "FILE_2"].asImmutable()
    static final List<String> SIDE_FIELDS = ["systemEnumId", "sourceTypeEnumId", "systemMessageRemoteId", "nsRestletConfigId",
            "sourceConfigId", "sourceConfigType", "fileTypeEnumId", "schemaFileName", "recordRootExpression"].asImmutable()
    static final List<String> CHECK_TEXT_FIELDS = ["label", "file1RecordType", "file2RecordType", "matchedBy", "fixOwner",
            "expectedSource", "actualSource", "headlineTemplate", "fixActionTemplate", "passesWhenTemplate"].asImmutable()
    // DAR-BE-196: a link no longer lists causes (AuditStatementCheck.conclusionCodes is retired); a cause is expected on its run.
    static final List<String> NARROWING_KEYS = ["diffTypes", "ruleIds"].asImmutable()
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
            case "STATEMENT_NARROWING":
            case "EXPECTED_CAUSES": return checkState(ec, kind, t)
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

    /**
     * The check, all its links in the audit and, for EXPECTED_CAUSES, the run's expected causes (check.expectedCodes, as
     * save#AuditCheck describes them) and the other checks on the same run (otherChecks), which the change reaches too.
     */
    private static Map checkState(def ec, String kind, Map target) {
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
                .collect { [id: codeById[it.statementId as String], diffTypes: csv(it.diffTypes), ruleIds: csv(it.ruleIds)] as Map }
                .sort { Map a, Map b -> (a.id as String) <=> (b.id as String) }
        Map c = [id: check.checkCode, savedRunId: check.savedRunId, fixWhereEnumId: check.fixWhereEnumId,
                 amountCurrencyUomId: check.amountCurrencyUomId]
        CHECK_TEXT_FIELDS.each { String f -> c[f] = check.get(f) }
        Map state = [kind: kind, target: target, audit: audit.auditCode, check: c, statements: links]
        if (kind == "EXPECTED_CAUSES") {
            if (!check.savedRunId) { ec.message.addError("Check '${target.checkCode}' has no run, so it has no causes.".toString()); return null }
            c.expectedCodes = expectedCodesOf(ec, check.savedRunId as String)
            state.otherChecks = ec.entity.find("darpan.audit.AuditCheck").condition("companyUserGroupId", tenantId)
                    .condition("savedRunId", check.savedRunId).useCache(false).list()
                    .findAll { it.checkId != check.checkId }*.checkCode.collect { it as String }.sort()
        }
        return state
    }

    /** The run's causes marked EXPECTED (its conclusion rules' outcome), sorted, each once. */
    private static List<String> expectedCodesOf(def ec, String savedRunId) {
        def scope = TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSetCompareScope").condition("ruleSetId", savedRunId)
                .useCache(false).list().find()
        if (scope == null) return []
        return TenantScopedFinder.findTenantScoped(ec, RunConclusionAuthoringSupport.RULE).condition("compareScopeId", scope.compareScopeId)
                .useCache(false).list().findAll { normalize(it.outcome) == "EXPECTED" }*.conclusionEnumId
                .collect { it as String }.unique().sort()
    }

    private static Map treeState(def ec, String kind, Map target) {
        Map tree = RunConclusionAuthoringSupport.get(ec, target.savedRunId as String)
        if (tree == null) return null
        List<Map> nodes = ((List<Map>) tree.conclusions).collect { Map n -> pick(n, NODE_KEYS) + [conditions: conditionsOf(n.conditions)] }
        return [kind: kind, target: target, savedRunId: target.savedRunId, conclusions: nodes]
    }

    /**
     * R44. What an EXCLUSION_FILTER that names no fieldExpression excludes by: the target side's single primary id
     * expression, from the state {@link #current} read. Null when the side has none, or a composite key (several
     * primaryIdExpressions): the planner then marks the proposal INVALID with {@link #NO_PRIMARY_ID_MESSAGE}, never a guess.
     */
    static String primaryIdExpressionOf(Map state, String fileSide) {
        Map sides = state?.run instanceof Map && ((Map) state.run).sides instanceof Map ? (Map) ((Map) state.run).sides : null
        Map side = sides == null ? null : (Map) sides.get(fileSide)
        if (side == null || ((List) (side.primaryIdExpressions ?: [])).size() > 1) return null
        return normalize(side.primaryIdExpression) ?: null
    }

    /** The INVALID statusMessage (a constant, so a reader can match it by identity) when R44's default has nothing to use. */
    static final String NO_PRIMARY_ID_MESSAGE = "NO_PRIMARY_ID_EXPRESSION: this run side has no single primary id expression to exclude by; name the field (fieldExpression)."

    static final String EXCLUDE_IN = "EXCLUDE_IN"

    /**
     * R51: the statusMessage prefix (a constant, matched by identity of its start) when the field already has a filter
     * whose operator is not EXCLUDE_IN (an INCLUDE_IN or INCLUDE_PREFIX scope); the rest of the message names the operator.
     */
    static final String EXISTING_FILTER_OPERATOR_PREFIX = "EXISTING_FILTER_OPERATOR: "

    /** The INVALID statusMessage when every proposed cause is already expected on the run. */
    static final String NO_CHANGE_EXPECTED_MESSAGE = "NO_CHANGE: every cause is already expected on this check's run; nothing would change."

    /** R51: the INVALID statusMessage when every proposed value is already excluded on the field. */
    static final String NO_CHANGE_MESSAGE = "NO_CHANGE: every value is already excluded on this field; nothing would change."

    /** A stored filter's operator; blank reads as EXCLUDE_IN, as the extract reads it (SourceFilterSupport). */
    private static String existingOperator(Map filter) {
        return (normalize(filter?.operator) ?: EXCLUDE_IN).toUpperCase(Locale.ROOT)
    }

    /**
     * The proposed values an EXCLUSION_FILTER would add to the field's existing exclusions, in the order proposed: those
     * not already excluded (compared ignoring case, as the extract matches) and each once. All of them when the field has
     * no filter. Answers [] when the field has a filter that is not EXCLUDE_IN (merge refuses that).
     */
    static List<String> addedValues(Map existing, List<String> proposed) {
        if (existing != null && existingOperator(existing) != EXCLUDE_IN) return []
        Set<String> seen = new HashSet<String>(((List) (existing?.filterValues ?: [])).collect { (it as String).toUpperCase(Locale.ROOT) })
        List<String> out = []
        (proposed ?: []).each { String v -> if (seen.add(v.toUpperCase(Locale.ROOT))) out << v }
        return out
    }

    /** What an EXCLUSION_FILTER `after` adds on top of the state it is merged into: [fieldExpression, values]. */
    static Map exclusionAdds(Map current, Map after) {
        String field = after?.fieldExpression as String
        String side = ((Map) current?.target)?.fileSide as String
        Map existing = field && current?.filters instanceof Map ?
                ((List<Map>) (((Map) current.filters).get(side) ?: [])).find { it.fieldExpression == field } : null
        return [fieldExpression: field, values: addedValues(existing, ((List) (after?.filterValues ?: [])).collect { it as String })]
    }

    // ---------------------------------------------------------------- merge

    /**
     * The state after the proposal: a copy of current with only the lever changed. `after` must already have passed
     * AgentProvenance.validateShape; only its R18 keys are read. Throws IllegalArgumentException when it cannot apply.
     * EXCLUSION_FILTER (R51): the values are added to the side's EXCLUDE_IN filter on the field (kept in order, new ones
     * appended, none twice), or a new EXCLUDE_IN filter is added; a field filtered with another operator is refused
     * (EXISTING_FILTER_OPERATOR), and values all already excluded are refused (NO_CHANGE). An exclusion is never removed.
     */
    static Map merge(String kind, Map current, Map after) {
        AgentProvenance.validateShape(kind, after)
        Map state = copy(current)
        Map target = (Map) state.target
        switch (kind) {
            case "EXCLUSION_FILTER":
                // The planner resolves an omitted fieldExpression (R44) before it merges; none here is a caller bug.
                if (!after.fieldExpression) throw new IllegalArgumentException(NO_PRIMARY_ID_MESSAGE)
                String field = after.fieldExpression as String
                List<String> proposed = ((List) after.filterValues).collect { it as String }
                List<Map> side = (List<Map>) ((Map) state.filters)[target.fileSide]
                int at = side.findIndexOf { it.fieldExpression == field }
                if (at < 0) {
                    side << [fieldExpression: field, operator: EXCLUDE_IN, filterValues: addedValues(null, proposed)]
                    break
                }
                // R51: the values join the field's existing exclusions; an include scope on the field is never replaced.
                Map existing = side[at]
                String operator = existingOperator(existing)
                if (operator != EXCLUDE_IN) throw new IllegalArgumentException(EXISTING_FILTER_OPERATOR_PREFIX + "this run side already filters ${field} with ${operator}; an exclusion cannot change that scope. Edit the run's filters instead.".toString())
                List<String> added = addedValues(existing, proposed)
                if (!added) throw new IllegalArgumentException(NO_CHANGE_MESSAGE)
                side[at] = [fieldExpression: field, operator: EXCLUDE_IN,
                            filterValues   : ((List) existing.filterValues).collect { it as String } + added]
                break
            case "STATEMENT_NARROWING":
                Map link = ((List<Map>) state.statements).find { it.id == target.statementCode }
                if (link == null) throw new IllegalArgumentException("Statement ${target.statementCode} is not linked to check ${target.checkCode}.".toString())
                NARROWING_KEYS.each { String k -> if (after.get(k) != null) link[k] = ((List) after.get(k)).collect { it as String } }
                break
            case "EXPECTED_CAUSES":
                // Added to the run's expected causes, none twice; a cause is never taken out (a revert restores the set).
                Map check = (Map) state.check
                List<String> was = ((List) (check.expectedCodes ?: [])).collect { it as String }
                List<String> added = ((List) after.expectedCodes).collect { it as String }.unique().findAll { !(it in was) }
                if (!added) throw new IllegalArgumentException(NO_CHANGE_EXPECTED_MESSAGE)
                check.expectedCodes = (was + added).sort()
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
                // Only the proposal's own entry: null when it added the filter, the exact old entry when it added to one (R51).
                String field = after.fieldExpression as String
                Map was = ((List<Map>) ((Map) state.filters)[target.fileSide]).find { it.fieldExpression == field }
                return [fieldExpression: field, entry: copy(was)]
            case "STATEMENT_NARROWING": return [link: copy(((List<Map>) state.statements).find { it.id == target.statementCode })]
            case "EXPECTED_CAUSES": return [expectedCodes: copy(((Map) state.check).expectedCodes ?: [])]
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
            case "EXPECTED_CAUSES":
                ((Map) state.check).expectedCodes = ((List) (lever.expectedCodes ?: [])).collect { it as String }
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
            case "EXPECTED_CAUSES":
                Map c = (Map) state.check
                Map check = [id: c.id, savedRunId: c.savedRunId, fixWhereEnumId: c.fixWhereEnumId,
                             amountCurrencyUomId: c.amountCurrencyUomId]
                CHECK_TEXT_FIELDS.each { String f -> check[f] = c.get(f) }
                // Named only by EXPECTED_CAUSES: left out, save#AuditCheck keeps the run's expected causes as they are.
                if (kind == "EXPECTED_CAUSES") check.expectedCodes = ((List) (c.expectedCodes ?: [])).collect { it as String }
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

    static final String UNSAFE_TREE_MESSAGE = "This run's conclusions include hidden or brain rows that cannot be saved safely yet; raise a ticket."

    /**
     * R29. save#RunConclusions rewrites every row without its HIDE outcome or BRAIN engine flag, so a FIX_TEXT or
     * CONCLUSION_ROWS change to a run whose tree holds either would silently un-hide rows or turn brain rows into tree rows.
     * True when the active tenant's run has such a row now.
     */
    static boolean treeHasUnsavableRows(def ec, String savedRunId) {
        def scope = savedRunId ? TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSetCompareScope").condition("ruleSetId", savedRunId)
                .useCache(false).list().find() : null
        if (scope == null) return false
        return TenantScopedFinder.findTenantScoped(ec, RunConclusionAuthoringSupport.RULE).condition("compareScopeId", scope.compareScopeId)
                .useCache(false).list().any { normalize(it.outcome) == "HIDE" || normalize(it.engine) == "BRAIN" }
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
