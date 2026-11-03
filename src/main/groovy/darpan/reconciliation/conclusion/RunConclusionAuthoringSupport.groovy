package darpan.reconciliation.conclusion

import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import groovy.json.JsonOutput

import static darpan.common.ValueSupport.normalize

/**
 * DAR-UI-064. A run's conclusion tree, read and written from the app: which DarpanConclusion names a finding, by
 * conditions on either side's record (gorjana R16 "cancelled in HotWax, RMA still open", R8 "no order, not synced").
 * Until this the only writer was the lifecycle builder (LifecycleSupport.saveConclusions, FILE_1 / FINDING only, and only
 * for a lifecycle); every audit check's tree arrived by data load.
 *
 * <p>Saving replaces the run's whole tree, like the lifecycle's: a list in order, each node naming its parent by an
 * earlier node's key; the first root that applies and holds names the record, then its children refine it (DAR-BE-063,
 * at most three levels). Everything the conclude step would misread is refused, in words, before anything is written.</p>
 */
class RunConclusionAuthoringSupport {

    static final String RULE = "darpan.rule.RuleSetConclusionRule"
    static final String CONDITION = "darpan.rule.RuleSetConclusionCondition"
    static final List<String> BUCKETS = ["MISSING_FROM_FILE_1", "MISSING_FROM_FILE_2", "RULE", "FINDING"].asImmutable()
    static final List<String> OPERATORS = ["IN", "NOT_IN", "GT", "GT_ZERO", "WITHIN_HOURS", "BLANK", "NOT_BLANK"].asImmutable()
    static final List<String> PRESENCE = ["ANY", "KEPT", "EXCLUDED", "ABSENT"].asImmutable()
    static final List<String> KEY_SCOPES = ["FULL", "FIRST_FIELD"].asImmutable()
    static final List<String> QUANTIFIERS = ["ALL", "ANY"].asImmutable()
    static final int MAX_DEPTH = 3
    static final String FIELD_PATTERN = /[a-zA-Z][a-zA-Z0-9_]{0,59}(\.[a-zA-Z][a-zA-Z0-9_]{0,59}){0,4}/

    /** The run's tree in order, and what a node may name: the conclusion catalog and the condition vocabulary. */
    static Map<String, Object> get(def ec, String savedRunId) {
        def scope = scopeFor(ec, savedRunId)
        if (scope == null) return null
        String compareScopeId = scope.compareScopeId as String
        List rules = TenantScopedFinder.findTenantScoped(ec, RULE).condition("compareScopeId", compareScopeId)
                .orderBy("sequenceNum").useCache(false).list()
        List conditions = TenantScopedFinder.findTenantScoped(ec, CONDITION).condition("compareScopeId", compareScopeId)
                .orderBy(["sequenceNum", "conditionSeq"]).useCache(false).list()
        List<Map<String, Object>> nodes = rules.collect { r ->
            [key        : "${r.sequenceNum}".toString(), parent: r.parentSequenceNum != null ? "${r.parentSequenceNum}".toString() : null,
             conclusion : r.conclusionEnumId, label: labelOf(ec, r.conclusionEnumId as String), bucket: r.appliesToBucket,
             question   : r.questionText, fix: r.fixActionTemplate,
             // DAR-BE-198: read back so a save can keep them (the editor does not send them).
             outcome    : r.outcome ?: null, engine: r.engine ?: null, suggestedFilterJson: r.suggestedFilterJson ?: null,
             conditions : conditions.findAll { it.sequenceNum == r.sequenceNum }.collect { c ->
                 [subject : c.subject, presence: c.presence ?: "ANY", keyScope: c.keyScope ?: "FULL", quantifier: c.quantifier ?: "ALL",
                  field   : c.fieldExpression, operator: c.operator,
                  values  : ((c.conditionValues ?: "") as String).split(",").collect { it.trim() }.findAll { it },
                  checkLabel: c.checkLabel] as Map<String, Object>
             }] as Map<String, Object>
        }
        List<Map> lookups = []
        try {
            lookups = ConclusionLookupSupport.parse(scope.conclusionLookupsJson)
        } catch (IllegalArgumentException e) {
            ec.message.addMessage("This run's lookups cannot be read: ${e.message}".toString(), "warning")
        }
        return [savedRunId: savedRunId, scopeMode: (scope.scopeMode ?: "COMPARE") as String, conclusions: nodes, lookups: lookups,
                // DAR-BE-198 (b): EXPECTED when the run's Unexplained rows are expected; null when they count.
                unexplainedOutcome: (scope.unexplainedOutcome ?: null) as String,
                catalog   : ConclusionCatalogSupport.catalog(ec),
                buckets   : BUCKETS, operators: OPERATORS, presence: PRESENCE, keyScopes: KEY_SCOPES, quantifiers: QUANTIFIERS] as Map<String, Object>
    }

    /**
     * Replaces the run's tree. conclusions: [{key, parent (an earlier node's key or blank), conclusion (a DarpanConclusion
     * id), bucket (roots), question, fix (DAR-BE-120: what to do when this is the cause), conditions: [{subject FILE_1|FILE_2, presence, keyScope, quantifier, field, operator,
     * values, checkLabel}]}]. An empty list clears it. DAR-BE-174: lookups, when given, replace the run's
     * ([{name, subject, idField, systemEnumId, sourceConfigId, keyField, many?, parameters?}], see ConclusionLookupSupport;
     * an empty list clears them); absent keeps them.
     */
    static Map<String, Object> save(def ec, String savedRunId, List submitted, List lookups = null, String unexplainedOutcome = null) {
        if (!TenantAccessSupport.requireActiveTenantWriteAccess(ec, "Your active tenant only has view access for runs.")) return null
        def scope = scopeFor(ec, savedRunId)
        if (scope == null) return null
        String lookupsJson = lookups == null ? null : lookupsJsonOf(ec, lookups)
        if (ec.message.hasError()) return null
        // DAR-BE-198 (prod gorjana R1): conclusions absent keeps the tree, so lookups can be set alone; before, an absent list
        // read as empty and cleared every rule. An empty list still clears it.
        // DAR-BE-198 (b): EXPECTED (the run's Unexplained rows are expected) or COUNTS (they count); absent keeps it.
        String unexplained = normalize(unexplainedOutcome)?.toUpperCase()
        if (unexplained && !(unexplained in [RunConclusionSupport.OUTCOME_EXPECTED, "COUNTS"])) {
            ec.message.addError("Unexplained is EXPECTED or COUNTS, not '${unexplained}'.")
            return null
        }
        if (unexplained) {
            scope.set("unexplainedOutcome", unexplained == "COUNTS" ? null : unexplained)
            scope.update()
        }
        if (submitted == null) {
            if (lookups != null) {
                scope.set("conclusionLookupsJson", lookupsJson)
                scope.update()
            }
            return get(ec, savedRunId)
        }
        // DAR-BE-198: what a node does not name (outcome, engine, suggested filter) it keeps from the rule it was read as: same
        // key (the rule's sequenceNum, as get reads it) and same conclusion.
        Map<String, Object> priorByKey = TenantScopedFinder.findTenantScoped(ec, RULE).condition("compareScopeId", scope.compareScopeId)
                .useCache(false).list().collectEntries { [("${it.sequenceNum}".toString()): it] }
        // DAR-BE-168: an older scope has no scopeMode (COMPARE); `?.` would hand conditionsOf a null boolean.
        boolean evaluate = "EVALUATE".equalsIgnoreCase(scope.scopeMode as String)
        Map converted = toRows((List) submitted, evaluate, priorByKey) { String conclusion ->
            def e = ConclusionCatalogSupport.visibleConclusion(ec, conclusion)
            e == null ? null : ((e.description ?: conclusion) as String)
        }
        ((List<String>) converted.errors).each { ec.message.addError(it) }
        if (ec.message.hasError()) return null
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) converted.rows

        String compareScopeId = scope.compareScopeId as String
        String tenantId = scope.companyUserGroupId as String
        def now = ec.user.nowTimestamp
        if (lookups != null) {
            scope.set("conclusionLookupsJson", lookupsJson)
            scope.update()
        }
        TenantScopedFinder.findTenantScoped(ec, CONDITION).condition("compareScopeId", compareScopeId).useCache(false).list()*.delete()
        TenantScopedFinder.findTenantScoped(ec, RULE).condition("compareScopeId", compareScopeId).useCache(false).list()*.delete()
        nodes.each { Map<String, Object> node ->
            ec.entity.makeValue(RULE).setAll([compareScopeId: compareScopeId, sequenceNum: node.sequenceNum,
                    parentSequenceNum: node.parentSequenceNum, conclusionEnumId: node.conclusionEnumId,
                    appliesToBucket: node.appliesToBucket, questionText: node.questionText,
                    fixActionTemplate: node.fixActionTemplate, outcome: node.outcome, engine: node.engine,
                    suggestedFilterJson: node.suggestedFilterJson, companyUserGroupId: tenantId,
                    createdDate: now, lastUpdatedDate: now]).create()
            ((List<Map>) node.conditions).eachWithIndex { Map c, int j ->
                ec.entity.makeValue(CONDITION).setAll(c + [compareScopeId: compareScopeId, sequenceNum: node.sequenceNum,
                        conditionSeq: j + 1, companyUserGroupId: tenantId]).create()
            }
        }
        return get(ec, savedRunId)
    }

    /**
     * DAR-BE-159. Pure: submitted nodes to the rows the save stores, or the first refusal in words. Each row is
     * [sequenceNum (10, 20, ...), parentSequenceNum, conclusionEnumId, appliesToBucket, questionText, fixActionTemplate,
     * conditions: [subject, presence, keyScope, quantifier, fieldExpression, operator, conditionValues (comma-joined, null
     * when none), checkLabel]]. A node without a key is keyed by its position (1, 2, ...). labelOfConclusion answers a
     * visible DarpanConclusion's label, or null when the id is not one. Answers [rows, errors]; rows is null on an error.
     */
    static Map toRows(List submitted, boolean evaluate, Closure<String> labelOfConclusion) {
        return toRows(submitted, evaluate, [:], labelOfConclusion)
    }

    /**
     * DAR-BE-198 in toRows: priorByKey holds the rules as get read them (key = the rule's sequenceNum). A node that does not
     * name outcome, engine or suggestedFilterJson keeps its prior rule's (same key and same conclusion); one that names it
     * sets it (HIDE or EXPECTED, BRAIN, or blank). Each row then also carries outcome, engine and suggestedFilterJson.
     */
    static Map toRows(List submitted, boolean evaluate, Map priorByKey, Closure<String> labelOfConclusion) {
        List<String> errors = []
        Map<String, Integer> seqByKey = [:], depthByKey = [:]
        List<Map<String, Object>> nodes = []
        for (int i = 0; i < ((submitted ?: []) as List).size(); i++) {
            Object o = submitted[i]
            if (!(o instanceof Map)) { errors << "Conclusion ${i + 1} is not readable.".toString(); break }
            Map n = (Map) o
            String key = normalize(n.key) ?: "${i + 1}".toString()
            if (seqByKey.containsKey(key)) { errors << "Two conclusions share the key '${key}'.".toString(); break }
            String conclusion = normalize(n.conclusion)
            String label = conclusion ? labelOfConclusion.call(conclusion) : null
            if (label == null) { errors << "'${conclusion}' is not a conclusion.".toString(); break }
            String parent = normalize(n.parent)
            if (parent && !seqByKey.containsKey(parent)) { errors << "'${label}' names a parent that is not above it.".toString(); break }
            int depth = parent ? depthByKey[parent] + 1 : 1
            if (depth > MAX_DEPTH) { errors << "'${label}' is more than ${MAX_DEPTH} levels deep.".toString(); break }
            String bucket = normalize(n.bucket)?.toUpperCase()
            if (bucket && !(bucket in BUCKETS)) { errors << "'${label}' applies to ${BUCKETS.join(', ')} or any, not '${bucket}'.".toString(); break }
            if (bucket && parent) { errors << "Only a top-level conclusion says which rows it applies to ('${label}').".toString(); break }
            String question = normalize(n.question)
            if (question && question.length() > 255) { errors << "'${label}' has a question longer than 255 characters.".toString(); break }
            String fix = normalize(n.fix)
            if (fix && fix.length() > 1000) { errors << "'${label}' has a fix longer than 1000 characters.".toString(); break }
            List<Map<String, Object>> conditions = conditionsOf(errors, label, n.conditions, evaluate)
            if (conditions == null) break
            def prior = priorByKey?.get(key)
            if (prior != null && prior.conclusionEnumId != conclusion) prior = null
            String outcome = n.containsKey("outcome") ? normalize(n.outcome)?.toUpperCase() : (prior?.outcome as String)
            if (outcome && !(outcome in [RunConclusionSupport.OUTCOME_HIDE, RunConclusionSupport.OUTCOME_EXPECTED])) {
                errors << "'${label}' has outcome '${outcome}'; it is HIDE or EXPECTED, or blank.".toString(); break
            }
            String engine = n.containsKey("engine") ? normalize(n.engine)?.toUpperCase() : (prior?.engine as String)
            if (engine && engine != "BRAIN") { errors << "'${label}' has engine '${engine}'; it is BRAIN, or blank.".toString(); break }
            String suggestedFilterJson = n.containsKey("suggestedFilterJson") ? normalize(n.suggestedFilterJson) : (prior?.suggestedFilterJson as String)
            int seq = (nodes.size() + 1) * 10
            seqByKey[key] = seq
            depthByKey[key] = depth
            nodes << [sequenceNum: seq, parentSequenceNum: parent ? seqByKey[parent] : null, conclusionEnumId: conclusion,
                      appliesToBucket: bucket, questionText: question, fixActionTemplate: fix, outcome: outcome ?: null,
                      engine: engine ?: null, suggestedFilterJson: suggestedFilterJson ?: null, conditions: conditions] as Map<String, Object>
        }
        return [rows: errors ? null : nodes, errors: errors]
    }

    private static List<Map<String, Object>> conditionsOf(List<String> errors, String label, Object raw, boolean evaluate) {
        List<Map<String, Object>> out = []
        for (Object o : ((raw ?: []) as List)) {
            Map c = o instanceof Map ? (Map) o : [:]
            String subject = (normalize(c.subject) ?: "FILE_1").toUpperCase()
            if (!(subject in ["FILE_1", "FILE_2"])) { errors << "'${label}': a condition reads FILE_1 or FILE_2.".toString(); return null }
            if (subject == "FILE_2" && evaluate) { errors << "'${label}': this run reads one system, so a condition reads FILE_1.".toString(); return null }
            String presence = (normalize(c.presence) ?: "ANY").toUpperCase()
            String keyScope = (normalize(c.keyScope) ?: "FULL").toUpperCase()
            String quantifier = (normalize(c.quantifier) ?: "ALL").toUpperCase()
            if (!(presence in PRESENCE) || !(keyScope in KEY_SCOPES) || !(quantifier in QUANTIFIERS)) {
                errors << "'${label}': a condition's presence, key scope or quantifier is not one the conclude step reads.".toString(); return null
            }
            String field = normalize(c.field)
            String operator = normalize(c.operator)?.toUpperCase()
            if (field && !(field ==~ FIELD_PATTERN)) { errors << "'${label}': '${field}' is not a record field.".toString(); return null }
            if (operator && !(operator in OPERATORS)) { errors << "'${label}': a condition is one of ${OPERATORS.join(', ')}, not '${operator}'.".toString(); return null }
            if (operator && !field) { errors << "'${label}': ${operator} needs a field.".toString(); return null }
            if (field && !operator) { errors << "'${label}': say what '${field}' must be.".toString(); return null }
            if (!field && presence == "ANY") { errors << "'${label}': a condition needs a field or a presence.".toString(); return null }
            List<String> values = ((c.values ?: []) as List).collect { normalize(it) }.findAll { it } as List<String>
            if (operator in ["IN", "NOT_IN"] && (!values || values.any { it.contains(",") || it.length() > 100 })) {
                errors << "'${label}': '${field}' ${operator == 'IN' ? 'is' : 'is not'} needs values without commas.".toString(); return null
            }
            if (operator in ["GT", "WITHIN_HOURS"] && !(values.size() == 1 && values[0] ==~ /-?[0-9]{1,15}(\.[0-9]{1,6})?/)) {
                errors << "'${label}': '${field}' ${operator == 'GT' ? 'is more than' : 'within hours'} needs one number.".toString(); return null
            }
            if (!(operator in ["IN", "NOT_IN", "GT", "WITHIN_HOURS"]) && values) { errors << "'${label}': ${operator ?: 'a presence check'} takes no values.".toString(); return null }
            String checkLabel = normalize(c.checkLabel)
            if (!checkLabel) { errors << "'${label}': every condition says what it checks.".toString(); return null }
            out << [subject: subject, presence: presence, keyScope: keyScope, quantifier: quantifier, fieldExpression: field,
                    operator: operator, conditionValues: values ? values.join(",") : null, checkLabel: checkLabel] as Map<String, Object>
        }
        if (!out) { errors << "'${label}' needs at least one condition.".toString(); return null }
        return out
    }

    /**
     * DAR-BE-174. The lookups as the scope stores them (null for none), or an error in words: a part missing, two of one
     * name, or a system whose connector cannot be asked by id (its extract takes no lookupKeys; it would read a window).
     */
    private static String lookupsJsonOf(def ec, List lookups) {
        List<Map> parsed
        try {
            parsed = ConclusionLookupSupport.parse(JsonOutput.toJson(lookups))
            parsed.each { ConclusionLookupSupport.requireSafeParameters(it) }
        } catch (IllegalArgumentException e) {
            ec.message.addError(e.message)
            return null
        }
        parsed*.name.countBy { it }.findAll { it.value > 1 }.each { ec.message.addError("Two lookups are named ${it.key}.") }
        parsed.each { Map l ->
            Map connector = SourceSystemConnectorSupport.resolveBySystemEnumId(ec, (String) l.systemEnumId)
            String service = normalize(connector?.extractServiceName)
            if (!service || !ec.service.getServiceDefinition(service)?.inParameterNameList?.contains("lookupKeys")) {
                ec.message.addError("Lookup ${l.name}: ${l.systemEnumId} cannot be asked by id.")
            }
        }
        return parsed ? JsonOutput.toJson(parsed) : null
    }

    private static def scopeFor(def ec, String savedRunId) {
        String id = normalize(savedRunId)
        def ruleSet = id ? TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSet").condition("ruleSetId", id).useCache(false).list().find() : null
        def scope = ruleSet ? TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSetCompareScope").condition("ruleSetId", id)
                .useCache(false).list().find() : null
        if (scope == null) ec.message.addError("Run '${savedRunId}' was not found.")
        return scope
    }

    private static String labelOf(def ec, String enumId) {
        def e = enumId ? ec.entity.find("moqui.basic.Enumeration").condition("enumId", enumId).useCache(true).one() : null
        return (e?.description ?: enumId) as String
    }
}
