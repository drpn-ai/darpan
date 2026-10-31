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
    static Map<String, Object> save(def ec, String savedRunId, List submitted, List lookups = null) {
        if (!TenantAccessSupport.requireActiveTenantWriteAccess(ec, "Your active tenant only has view access for runs.")) return null
        def scope = scopeFor(ec, savedRunId)
        if (scope == null) return null
        String lookupsJson = lookups == null ? null : lookupsJsonOf(ec, lookups)
        if (ec.message.hasError()) return null
        // DAR-BE-168: an older scope has no scopeMode (COMPARE); `?.` would hand conditionsOf a null boolean.
        boolean evaluate = "EVALUATE".equalsIgnoreCase(scope.scopeMode as String)
        Map<String, Integer> seqByKey = [:], depthByKey = [:]
        List<Map<String, Object>> nodes = []
        ((submitted ?: []) as List).eachWithIndex { Object o, int i ->
            if (ec.message.hasError()) return
            if (!(o instanceof Map)) { ec.message.addError("Conclusion ${i + 1} is not readable."); return }
            Map n = (Map) o
            String key = normalize(n.key) ?: "${i + 1}".toString()
            if (seqByKey.containsKey(key)) { ec.message.addError("Two conclusions share the key '${key}'."); return }
            String conclusion = normalize(n.conclusion)
            def e = ConclusionCatalogSupport.visibleConclusion(ec, conclusion)
            if (e == null) { ec.message.addError("'${conclusion}' is not a conclusion."); return }
            String label = (e.description ?: conclusion) as String
            String parent = normalize(n.parent)
            if (parent && !seqByKey.containsKey(parent)) { ec.message.addError("'${label}' names a parent that is not above it."); return }
            int depth = parent ? depthByKey[parent] + 1 : 1
            if (depth > MAX_DEPTH) { ec.message.addError("'${label}' is more than ${MAX_DEPTH} levels deep."); return }
            String bucket = normalize(n.bucket)?.toUpperCase()
            if (bucket && !(bucket in BUCKETS)) { ec.message.addError("'${label}' applies to ${BUCKETS.join(', ')} or any, not '${bucket}'."); return }
            if (bucket && parent) { ec.message.addError("Only a top-level conclusion says which rows it applies to ('${label}')."); return }
            String question = normalize(n.question)
            if (question && question.length() > 255) { ec.message.addError("'${label}' has a question longer than 255 characters."); return }
            String fix = normalize(n.fix)
            if (fix && fix.length() > 1000) { ec.message.addError("'${label}' has a fix longer than 1000 characters."); return }
            List<Map<String, Object>> conditions = conditionsOf(ec, label, n.conditions, evaluate)
            if (conditions == null) return
            int seq = (nodes.size() + 1) * 10
            seqByKey[key] = seq
            depthByKey[key] = depth
            nodes << [sequenceNum: seq, parentSequenceNum: parent ? seqByKey[parent] : null, conclusionEnumId: conclusion,
                      appliesToBucket: bucket, questionText: question, fixActionTemplate: fix, conditions: conditions] as Map<String, Object>
        }
        if (ec.message.hasError()) return null

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
                    fixActionTemplate: node.fixActionTemplate, companyUserGroupId: tenantId,
                    createdDate: now, lastUpdatedDate: now]).create()
            ((List<Map>) node.conditions).eachWithIndex { Map c, int j ->
                ec.entity.makeValue(CONDITION).setAll(c + [compareScopeId: compareScopeId, sequenceNum: node.sequenceNum,
                        conditionSeq: j + 1, companyUserGroupId: tenantId]).create()
            }
        }
        return get(ec, savedRunId)
    }

    private static List<Map<String, Object>> conditionsOf(def ec, String label, Object raw, boolean evaluate) {
        List<Map<String, Object>> out = []
        for (Object o : ((raw ?: []) as List)) {
            Map c = o instanceof Map ? (Map) o : [:]
            String subject = (normalize(c.subject) ?: "FILE_1").toUpperCase()
            if (!(subject in ["FILE_1", "FILE_2"])) { ec.message.addError("'${label}': a condition reads FILE_1 or FILE_2."); return null }
            if (subject == "FILE_2" && evaluate) { ec.message.addError("'${label}': this run reads one system, so a condition reads FILE_1."); return null }
            String presence = (normalize(c.presence) ?: "ANY").toUpperCase()
            String keyScope = (normalize(c.keyScope) ?: "FULL").toUpperCase()
            String quantifier = (normalize(c.quantifier) ?: "ALL").toUpperCase()
            if (!(presence in PRESENCE) || !(keyScope in KEY_SCOPES) || !(quantifier in QUANTIFIERS)) {
                ec.message.addError("'${label}': a condition's presence, key scope or quantifier is not one the conclude step reads."); return null
            }
            String field = normalize(c.field)
            String operator = normalize(c.operator)?.toUpperCase()
            if (field && !(field ==~ FIELD_PATTERN)) { ec.message.addError("'${label}': '${field}' is not a record field."); return null }
            if (operator && !(operator in OPERATORS)) { ec.message.addError("'${label}': a condition is one of ${OPERATORS.join(', ')}, not '${operator}'."); return null }
            if (operator && !field) { ec.message.addError("'${label}': ${operator} needs a field."); return null }
            if (field && !operator) { ec.message.addError("'${label}': say what '${field}' must be."); return null }
            if (!field && presence == "ANY") { ec.message.addError("'${label}': a condition needs a field or a presence."); return null }
            List<String> values = ((c.values ?: []) as List).collect { normalize(it) }.findAll { it } as List<String>
            if (operator in ["IN", "NOT_IN"] && (!values || values.any { it.contains(",") || it.length() > 100 })) {
                ec.message.addError("'${label}': '${field}' ${operator == 'IN' ? 'is' : 'is not'} needs values without commas."); return null
            }
            if (operator in ["GT", "WITHIN_HOURS"] && !(values.size() == 1 && values[0] ==~ /-?[0-9]{1,15}(\.[0-9]{1,6})?/)) {
                ec.message.addError("'${label}': '${field}' ${operator == 'GT' ? 'is more than' : 'within hours'} needs one number."); return null
            }
            if (!(operator in ["IN", "NOT_IN", "GT", "WITHIN_HOURS"]) && values) { ec.message.addError("'${label}': ${operator ?: 'a presence check'} takes no values."); return null }
            String checkLabel = normalize(c.checkLabel)
            if (!checkLabel) { ec.message.addError("'${label}': every condition says what it checks."); return null }
            out << [subject: subject, presence: presence, keyScope: keyScope, quantifier: quantifier, fieldExpression: field,
                    operator: operator, conditionValues: values ? values.join(",") : null, checkLabel: checkLabel] as Map<String, Object>
        }
        if (!out) { ec.message.addError("'${label}' needs at least one condition."); return null }
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
