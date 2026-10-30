package darpan.admin.transfer

import darpan.facade.common.SharedConfigAccessSupport
import darpan.facade.common.TenantScopedFinder
import groovy.transform.CompileDynamic

import java.sql.Timestamp

/**
 * DAR-ADMIN-006. What an admin config transfer moves, as data: one entry per kind, in creation order, so export, diff
 * and import walk the same table and every column of every entity travels without a field list to keep in step.
 *
 * <p>A kind's {@code refFields} are columns holding another record's id; they travel as refs ({@code KIND:sourceId}),
 * never as ids, and only the importer turns a ref back into a target id. {@code children} hang off their parent by
 * {@code parentFields} (same-named columns copied from the parent row); a child with its own minted {@code pkField} keeps
 * its target id by position on update, any other child is replaced. Secrets ({@code encrypt="true"}, and a kind's
 * {@code extraSecretFields}) never travel.</p>
 */
@CompileDynamic
class TransferSpecs {

    static final int BUNDLE_VERSION = 1

    static final String NETSUITE_AUTH = "NETSUITE_AUTH"
    static final String SHOPIFY_AUTH = "SHOPIFY_AUTH"
    static final String HOTWAX_OMS_REST = "HOTWAX_OMS_REST"
    static final String NETSUITE_RESTLET = "NETSUITE_RESTLET"
    static final String NETSUITE_SUITEQL = "NETSUITE_SUITEQL"
    static final String CONC = "CONC"
    static final String RUN = "RUN"
    static final String RULE = "RULE"
    static final String AUDIT = "AUDIT"
    static final String STATEMENT = "STATEMENT"
    static final String CHECK = "CHECK"
    /** DAR-BE-160. Carried only by a tenant config version (bundle withAutomations), never by an admin transfer. */
    static final String AUTOMATION = "AUTOMATION"
    /** DAR-BE-161. A tenant's knowledge entries (darpan.knowledge.KnowledgeEntry); only the ACTIVE ones travel. */
    static final String KNOWLEDGE = "KNOWLEDGE"

    static final List<String> CONNECTION_KINDS = [NETSUITE_AUTH, SHOPIFY_AUTH, HOTWAX_OMS_REST, NETSUITE_RESTLET].asImmutable()

    /** Never carried: the tenant, who and when. The importer stamps its own. */
    static final Set<String> STAMP_FIELDS = (["companyUserGroupId", "lastUpdatedStamp", "createdDate", "lastUpdatedDate",
                                              "createdByUserId"] as Set<String>).asImmutable()

    /** Text columns holding a JSON list whose items name a connection by sourceConfigId (DAR-BE-118 lookups). */
    static final List<String> JSON_REF_FIELDS = ["conclusionLookupsJson"].asImmutable()

    /**
     * DAR-BE-160. Text columns holding a JSON map whose parameters name a config by id: an automation source keeps its
     * connection or query id in safeMetadataJson.parameters, under its connector's configParameterName.
     */
    static final List<String> JSON_PARAM_REF_FIELDS = ["safeMetadataJson"].asImmutable()

    static final String CONCLUSION_ENUM_TYPE = "DarpanConclusion"

    private static final Map RULE_CHILD = [kind: RULE, entityName: "darpan.rule.Rule", pkField: "ruleId",
                                           parentFields: ["ruleSetId"], orderBy: ["sequenceNum", "ruleId"]]
    private static final Map SCOPE_CHILD = [entityName: "darpan.rule.RuleSetCompareScope", pkField: "compareScopeId",
            mintPrefix: "CS_", parentFields: ["ruleSetId"], orderBy: ["objectType", "compareScopeId"], children: [
            [entityName: "darpan.rule.RuleSetCompareSource", parentFields: ["compareScopeId"], orderBy: ["fileSide"],
             refFields: [nsRestletConfigId: NETSUITE_RESTLET], polymorphicRef: "sourceConfigId", children: [
                    [entityName: "darpan.rule.RuleSetCompareSourceKeyField", parentFields: ["compareScopeId", "fileSide"],
                     orderBy: ["sequenceNum"]],
                    [entityName: "darpan.rule.RuleSetCompareSourceFilter", parentFields: ["compareScopeId", "fileSide"],
                     orderBy: ["sequenceNum"]]]],
            [entityName: "darpan.rule.RuleSetConclusionRule", parentFields: ["compareScopeId"], orderBy: ["sequenceNum"],
             refFields: [conclusionEnumId: CONC], children: [
                    [entityName: "darpan.rule.RuleSetConclusionCondition", parentFields: ["compareScopeId", "sequenceNum"],
                     orderBy: ["conditionSeq"]]]]]]

    /** Creation order. naturalKey matches a record on the target; null means the operator maps it (connections). */
    static final List<Map> KINDS = [
            [kind: NETSUITE_AUTH, entityName: "darpan.reconciliation.NsAuthConfig", pkField: "nsAuthConfigId", naturalKey: null,
             grantType: SharedConfigAccessSupport.CONFIG_TYPE_NS_AUTH],
            [kind: SHOPIFY_AUTH, entityName: "darpan.shopify.ShopifyAuthConfig", pkField: "shopifyAuthConfigId", naturalKey: null,
             grantType: SharedConfigAccessSupport.CONFIG_TYPE_SHOPIFY_AUTH],
            [kind: HOTWAX_OMS_REST, entityName: "darpan.hotwax.HotWaxOmsRestSourceConfig", pkField: "omsRestSourceConfigId",
             naturalKey: null, grantType: SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, extraSecretFields: ["headersJson"]],
            [kind: NETSUITE_RESTLET, entityName: "darpan.reconciliation.NsRestletConfig", pkField: "nsRestletConfigId",
             naturalKey: null, grantType: SharedConfigAccessSupport.CONFIG_TYPE_NS_RESTLET, refFields: [nsAuthConfigId: NETSUITE_AUTH],
             extraSecretFields: ["headersJson"]],
            [kind: NETSUITE_SUITEQL, entityName: "darpan.reconciliation.NsSuiteQlSourceQuery", pkField: "nsSuiteQlSourceQueryId",
             naturalKey: "description", refFields: [nsAuthConfigId: NETSUITE_AUTH, omsStatusConfigId: HOTWAX_OMS_REST], children: [
                    [entityName: "darpan.reconciliation.NsSuiteQlSourceQueryField", parentFields: ["nsSuiteQlSourceQueryId"],
                     orderBy: ["sequenceNum", "recordFieldName"]]]],
            [kind: CONC, entityName: "moqui.basic.Enumeration", pkField: "enumId", naturalKey: "description"],
            // ownerReconciliationId names a lifecycle, which stays behind like automations do.
            [kind: RUN, entityName: "darpan.rule.RuleSet", pkField: "ruleSetId", mintPrefix: "RS_", naturalKey: "ruleSetName",
             dropFields: ["ownerReconciliationId"], children: [RULE_CHILD, SCOPE_CHILD]],
            [kind: AUDIT, entityName: "darpan.audit.Audit", pkField: "auditId", naturalKey: "auditCode"],
            [kind: STATEMENT, entityName: "darpan.audit.AuditStatement", pkField: "statementId", naturalKey: "statementCode",
             refFields: [auditId: AUDIT]],
            [kind: CHECK, entityName: "darpan.audit.AuditCheck", pkField: "checkId", naturalKey: "checkCode",
             refFields: [savedRunId: RUN], dropFields: ["automationId"],
             // A check's links to statements: only those of the audits in the bundle travel; links to other audits stay.
             links: [entityName: "darpan.audit.AuditStatementCheck", parentFields: ["checkId"], refFields: [statementId: STATEMENT],
                     textListRefs: [ruleIds: RULE, conclusionCodes: CONC]]],
            // DAR-BE-160. The morning audit runs an automation's own copy of its run's sources and exclusion filters, so a
            // version holds them. When it last and next fires and whether it is on are operational: they stay the
            // target's, and changing them is no config change. A source's config id travels as a ref inside
            // safeMetadataJson (JSON_PARAM_REF_FIELDS); its other id columns stay plain, as a version restores into the
            // tenant it came from.
            [kind: AUTOMATION, entityName: "darpan.reconciliation.ReconciliationAutomation", pkField: "automationId",
             naturalKey: "automationName", refFields: [savedRunId: RUN],
             dropFields: ["isActive", "nextScheduledFireTime", "lastScheduledFireTime"], children: [
                    [entityName: "darpan.reconciliation.ReconciliationAutomationSource", parentFields: ["automationId"],
                     orderBy: ["fileSide"], refFields: [nsRestletConfigId: NETSUITE_RESTLET], children: [
                            [entityName: "darpan.reconciliation.ReconciliationAutomationSourceFilter",
                             parentFields: ["automationId", "fileSide"], orderBy: ["sequenceNum"]]]]]],
            // DAR-BE-161. Matched by the tenant's entryKey; the importer stamps the tenant, creator and date.
            [kind: KNOWLEDGE, entityName: "darpan.knowledge.KnowledgeEntry", pkField: "knowledgeEntryId", mintPrefix: "KE_",
             naturalKey: "entryKey"],
    ].asImmutable()

    /** Every kind's name, for telling a ref inside JSON from a plain value. */
    static final Set<String> KIND_NAMES = ((KINDS*.kind as List<String>) + [RULE] as Set<String>).asImmutable()

    /** The bundle list each top-level kind travels in. */
    static final Map<String, String> BUNDLE_LISTS = [(NETSUITE_AUTH): "connections", (SHOPIFY_AUTH): "connections",
            (HOTWAX_OMS_REST): "connections", (NETSUITE_RESTLET): "connections", (NETSUITE_SUITEQL): "queries",
            (CONC): "conclusionCodes", (RUN): "runs", (AUDIT): "audits", (STATEMENT): "statements", (CHECK): "checks",
            (AUTOMATION): "automations", (KNOWLEDGE): "knowledge"].asImmutable()

    static Map spec(String kind) {
        return KINDS.find { it.kind == kind }
    }

    static Map specForEntity(String entityName) {
        return KINDS.find { it.entityName == entityName }
    }

    static String ref(String kind, String sourceId) {
        return "${kind}:${sourceId}".toString()
    }

    /** [kind, id] of a ref; null when the text is not one (a literal such as UNEXPLAINED in a code list). */
    static List<String> parseRef(Object value) {
        String text = value?.toString()
        int colon = text ? text.indexOf(':') : -1
        if (colon <= 0 || colon == text.length() - 1) return null
        String kind = text.substring(0, colon)
        return kind ==~ /[A-Z_]+/ ? [kind, text.substring(colon + 1)] : null
    }

    /** A connection kind whose entity is not defined on this instance is skipped in the catalog and blocks on import. */
    static boolean isEntityAvailable(def ec, String entityName) {
        return entityName && ec.entity.isEntityDefined(entityName)
    }

    static String shortName(String entityName) {
        return entityName.substring(entityName.lastIndexOf('.') + 1)
    }

    /** Columns that travel as plain values: every field less keys, stamps, dropped and secret columns and ref columns. */
    static List<String> valueFields(def ec, Map spec) {
        def ed = ec.entity.getEntityDefinition(spec.entityName as String)
        Set<String> skip = new HashSet<String>(STAMP_FIELDS)
        skip.addAll((spec.parentFields ?: []) as List<String>)
        skip.addAll((spec.dropFields ?: []) as List<String>)
        skip.addAll(((spec.refFields ?: [:]) as Map).keySet() as Set<String>)
        skip.addAll(((spec.textListRefs ?: [:]) as Map).keySet() as Set<String>)
        if (spec.polymorphicRef) skip.add(spec.polymorphicRef as String)
        if (spec.pkField) skip.add(spec.pkField as String)
        return (ed.getAllFieldNames() as List<String>).findAll { !(it in skip) && !isSecret(ed, it) }
    }

    /** Columns pointing at reference data that the entity declares no relationship for: field -> [entity, its key]. */
    static final Map<String, Map<String, List<String>>> UNDECLARED_REFERENCES = [
            "darpan.audit.AuditCheck": [amountCurrencyUomId: ["moqui.basic.Uom", "uomId"]]].asImmutable()

    /** Reference data a source points at that the target must already have; an instance-local remote is gated apart. */
    static final Set<String> REFERENCE_SKIP_ENTITIES = (["moqui.service.message.SystemMessageRemote"] as Set<String>).asImmutable()

    /** Value columns that name a reference-data row (an Enumeration, a Uom): [field, entityName, relatedField]. */
    static List<List<String>> referenceColumns(def ec, Map spec) {
        String entityName = spec.entityName as String
        Set<String> values = valueFields(ec, spec) as Set<String>
        List<List<String>> out = []
        ec.entity.getEntityDefinition(entityName).getRelationshipsInfo(false).each { rel ->
            if (!rel.isTypeOne || rel.relatedEntityName in REFERENCE_SKIP_ENTITIES || rel.keyMap.size() != 1) return
            String field = rel.keyMap.keySet().first() as String
            if (field in values) out << [field, rel.relatedEntityName as String, rel.keyMap[field] as String]
        }
        (UNDECLARED_REFERENCES[entityName] ?: [:]).each { String field, List<String> target ->
            if (field in values) out << [field, target[0], target[1]]
        }
        return out
    }

    /** The ref columns a record of this spec may carry. */
    static Set<String> refColumns(Map spec) {
        Set<String> out = new HashSet<String>(((spec.refFields ?: [:]) as Map).keySet() as Set<String>)
        if (spec.polymorphicRef) out.add(spec.polymorphicRef as String)
        return out
    }

    /** Every column that is not encrypted: what a read may select. */
    static List<String> readableFields(def ec, String entityName) {
        def ed = ec.entity.getEntityDefinition(entityName)
        return (ed.getAllFieldNames() as List<String>).findAll { !isSecret(ed, it) }
    }

    static List<String> secretFields(def ec, String entityName) {
        def ed = ec.entity.getEntityDefinition(entityName)
        return (ed.getAllFieldNames() as List<String>).findAll { isSecret(ed, it) }.sort()
    }

    /**
     * An encrypted column, or one a kind names in extraSecretFields: headersJson is plain text but may carry an auth
     * header (OmsRestSourceSupport only ever shows safeHeaderNames), so it is never read, sent, diffed or written.
     */
    static boolean isSecret(def ed, String fieldName) {
        if (ed.getFieldInfo(fieldName).encrypt) return true
        return fieldName in ((specForEntity(ed.getFullEntityName() as String)?.extraSecretFields ?: []) as List<String>)
    }

    /** A bundle carries every value as text, so the same value reads the same on any database. */
    static String text(Object value) {
        if (value == null) return null
        if (value instanceof BigDecimal) return ((BigDecimal) value).stripTrailingZeros().toPlainString()
        if (value instanceof Timestamp) return value.toString()
        return value.toString()
    }

    /** Admin reads span tenants by design; every caller adds its explicit companyUserGroupId (or parent) condition. */
    static def finder(def ec, String entityName) {
        return TenantScopedFinder.findGlobalUnscoped(ec, entityName,
                "DAR-ADMIN-006 config transfer: the caller names the tenant or a parent already proven the tenant's")
    }
}
