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
    /**
     * DAR-BE-160. Carried by a tenant config version, and (DAR-BE-188) by an admin transfer asked for its automations
     * (export#ConfigBundle includeAutomations).
     */
    static final String AUTOMATION = "AUTOMATION"
    /** DAR-BE-160. A lifecycle (darpan.reconciliation.Reconciliation) and its questions; version-only, like AUTOMATION. */
    static final String LIFECYCLE = "LIFECYCLE"
    static final String QUESTION = "QUESTION"
    /**
     * DAR-BE-188. A tenant's chat space (darpan.reconciliation.TenantChatSpace), carried when an automation posts to it.
     * Its webhooks are stored in clear but are bearer secrets, so they travel only sealed, like a connection's credentials.
     */
    static final String CHAT_SPACE = "CHAT_SPACE"
    /**
     * DAR-BE-188. A run's compare scope by its run and position ({@code SCOPE:<ruleSetId>#<index>}), as an automation
     * names it in a transfer: a scope has no ref of its own, and keeps its target id by position like a rule does.
     */
    static final String SCOPE = "SCOPE"
    /**
     * DAR-BE-161. A tenant's knowledge entries (darpan.knowledge.KnowledgeEntry), ACTIVE and RETIRED (statusId travels, so a
     * retire reaches the target). Imported only into the tenant they came from (ConfigTransferImporter.knowledgeGate).
     */
    static final String KNOWLEDGE = "KNOWLEDGE"
    /**
     * DAR-BE-236. A tenant's scripts (darpan.script.TenantScript) with their deployments, carried with automations (a
     * whole-tenant transfer, and every config version). A deployment's chat space and saved run travel as refs in a
     * transfer and as plain ids in a version; when it runs is operational and stays the target's, and one created by an
     * import arrives switched off.
     */
    static final String SCRIPT = "SCRIPT"
    /** DAR-BE-237. A script's deployment, a child kind like RULE: its ref lets a version restore put it back under its own id. */
    static final String DEPLOYMENT = "DEPLOYMENT"
    /** Kinds that live only as children of another kind's record (refs to them resolve inside their parent). */
    static final List<String> CHILD_KINDS = [RULE, QUESTION, SCOPE, DEPLOYMENT].asImmutable()

    static final List<String> CONNECTION_KINDS = [NETSUITE_AUTH, SHOPIFY_AUTH, HOTWAX_OMS_REST, NETSUITE_RESTLET].asImmutable()

    /** DAR-BE-188. The bundle lists whose records may carry sealedSecrets. */
    static final List<String> SEALED_LISTS = ["connections", "chatSpaces"].asImmutable()

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

    /** DAR-BE-160. Text columns holding a JSON list whose items name a conclusion code by conclusionEnumId (a lifecycle's rules). */
    static final List<String> JSON_CONC_REF_FIELDS = ["conclusionRules"].asImmutable()

    static final String CONCLUSION_ENUM_TYPE = "DarpanConclusion"

    private static final Map RULE_CHILD = [kind: RULE, entityName: "darpan.rule.Rule", pkField: "ruleId",
                                           parentFields: ["ruleSetId"], orderBy: ["sequenceNum", "ruleId"]]
    // positionKind: refs name a scope as SCOPE:<ruleSetId>#<index in this order> (DAR-BE-188); the scope record itself
    // carries no ref, so a run's records are what they were.
    private static final Map SCOPE_CHILD = [entityName: "darpan.rule.RuleSetCompareScope", pkField: "compareScopeId",
            mintPrefix: "CS_", parentFields: ["ruleSetId"], orderBy: ["objectType", "compareScopeId"], positionKind: SCOPE, children: [
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

    /** DAR-BE-236. A deployment keeps its target id by position under its script, like a rule under its run. */
    private static final Map DEPLOYMENT_CHILD = [kind: DEPLOYMENT, entityName: "darpan.script.TenantScriptDeployment", pkField: "tenantScriptDeploymentId",
            parentFields: ["tenantScriptId"], orderBy: ["createdDate", "tenantScriptDeploymentId"],
            refFields: [chatSpaceId: CHAT_SPACE, inputSavedRunId: RUN], versionPlainFields: ["chatSpaceId", "inputSavedRunId"],
            dropFields: ["isActive", "nextRunDate", "lastRunDate", "lastRunStatus", "lastRunMessage", "lastRunUnits", "lastUpdatedByUserId"]]

    /** Creation order. naturalKey matches a record on the target; null means the operator maps it (connections). */
    static final List<Map> KINDS = [
            [kind: NETSUITE_AUTH, entityName: "darpan.reconciliation.NsAuthConfig", pkField: "nsAuthConfigId", naturalKey: null,
             grantType: SharedConfigAccessSupport.CONFIG_TYPE_NS_AUTH],
            // DAR-BE-275: an app install's refresh token is single-use, so a copy on another instance would let the first
            // instance to refresh cut the other off. The install itself stays behind; the target installs the app again.
            [kind: SHOPIFY_AUTH, entityName: "darpan.shopify.ShopifyAuthConfig", pkField: "shopifyAuthConfigId", naturalKey: null,
             grantType: SharedConfigAccessSupport.CONFIG_TYPE_SHOPIFY_AUTH,
             dropFields: ["shopifyAppId", "refreshToken", "accessTokenExpiresDate", "refreshTokenExpiresDate", "grantedScopes", "installedDate"]],
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
            // DAR-BE-199. The schedule is the instance's own, like the automations that carry it: it never travels.
            [kind: AUDIT, entityName: "darpan.audit.Audit", pkField: "auditId", naturalKey: "auditCode",
             dropFields: ["scheduleTime", "scheduleTimeZone", "scheduleActive"]],
            [kind: STATEMENT, entityName: "darpan.audit.AuditStatement", pkField: "statementId", naturalKey: "statementCode",
             refFields: [auditId: AUDIT]],
            // DAR-BE-188. automationId is no value; it travels as a ref (heldRefs) only in a transfer that carries automations,
            // and only when the bundle holds the automation the check runs under. A version leaves it out, as before.
            // DAR-BE-206. graceDays is retired (a grace is an expected conclusion rule): a bundle or version made before
            // still carries it, and the import drops it (retiredFields) instead of refusing it as UNKNOWN_FIELD.
            [kind: CHECK, entityName: "darpan.audit.AuditCheck", pkField: "checkId", naturalKey: "checkCode",
             refFields: [savedRunId: RUN], dropFields: ["automationId"], heldRefs: [automationId: AUTOMATION],
             retiredFields: ["graceDays"],
             // A check's links to statements: only those of the audits in the bundle travel; links to other audits stay.
             // DAR-BE-196: conclusionCodes no longer travels (expected causes are the run's rules); a bundle carrying it is refused.
             links: [entityName: "darpan.audit.AuditStatementCheck", parentFields: ["checkId"], refFields: [statementId: STATEMENT],
                     textListRefs: [ruleIds: RULE], dropFields: ["conclusionCodes"]]],
            // DAR-BE-160. The morning audit runs an automation's own copy of its run's sources and exclusion filters, so a
            // version holds them. When it last and next fires and whether it is on are operational: they stay the
            // target's, and changing them is no config change. A source's config id travels as a ref inside
            // safeMetadataJson (JSON_PARAM_REF_FIELDS); its other id columns stay plain, as a version restores into the
            // tenant it came from.
            // DAR-BE-160. A lifecycle's questions: only those switched on (a rebuild switches the old ones off and keeps
            // them for history), parent before child. Its runs travel as RUN records marked ownedBy.
            [kind: LIFECYCLE, entityName: "darpan.reconciliation.Reconciliation", pkField: "reconciliationId",
             naturalKey: "reconciliationName", children: [
                    [kind: QUESTION, entityName: "darpan.reconciliation.ReconciliationRun", pkField: "reconciliationRunId",
                     parentFields: ["reconciliationId"], orderBy: ["runSequence", "reconciliationRunId"], activeOnly: "isActive",
                     selfRef: "parentReconciliationRunId", refFields: [ruleSetId: RUN, parentReconciliationRunId: QUESTION]]]],
            // DAR-BE-188. Matched by spaceName, unique per tenant whatever its case. The webhooks are sealed secrets; the
            // Slack workspace install it posts through, and when it last sent a badge GIF, are this instance's. Transfer
            // only (notInVersion): a version's automations name their space by id, so a version holds no space.
            [kind: CHAT_SPACE, entityName: "darpan.reconciliation.TenantChatSpace", pkField: "chatSpaceId", naturalKey: "spaceName",
             notInVersion: true,
             extraSecretFields: ["webhookUrl", "googleChatWebhookUrl"], dropFields: ["slackInstallId", "lastBadgeGifDate"]],
            // savedRunId names a run, or a lifecycle when savedRunType is "reconciliation" (polymorphicKind). In a transfer
            // its run, compare scope and chat space travel as refs; the legacy question and mapping ids stay behind (a
            // mapping's automation is never carried). A version keeps all of these as the plain ids they are
            // (versionPlainFields): it restores into its own tenant, and its pack, so its hash, is what it was before.
            [kind: AUTOMATION, entityName: "darpan.reconciliation.ReconciliationAutomation", pkField: "automationId",
             naturalKey: "automationName", polymorphicRef: "savedRunId",
             refFields: [ruleSetId: RUN, chatSpaceId: CHAT_SPACE], positionRefs: [compareScopeId: SCOPE],
             dropFields: ["isActive", "nextScheduledFireTime", "lastScheduledFireTime", "reconciliationRunId", "reconciliationMappingId"],
             versionPlainFields: ["ruleSetId", "chatSpaceId", "compareScopeId", "reconciliationRunId", "reconciliationMappingId"], children: [
                    [entityName: "darpan.reconciliation.ReconciliationAutomationSource", parentFields: ["automationId"],
                     orderBy: ["fileSide"], refFields: [nsRestletConfigId: NETSUITE_RESTLET], children: [
                            [entityName: "darpan.reconciliation.ReconciliationAutomationSourceFilter",
                             parentFields: ["automationId", "fileSide"], orderBy: ["sequenceNum"]]]]]],
            // DAR-BE-161. Matched by id in its own version restore, else by entryKey within the target tenant; the importer
            // stamps the tenant, creator and date.
            [kind: KNOWLEDGE, entityName: "darpan.knowledge.KnowledgeEntry", pkField: "knowledgeEntryId", mintPrefix: "KE_",
             naturalKey: "entryKey"],
            // DAR-BE-236. After the chat spaces and runs its deployments name. A script's own isActive is its content.
            [kind: SCRIPT, entityName: "darpan.script.TenantScript", pkField: "tenantScriptId", naturalKey: "scriptName",
             dropFields: ["lastUpdatedByUserId"], children: [DEPLOYMENT_CHILD]],
    ].asImmutable()

    /** Every kind's name, for telling a ref inside JSON from a plain value. */
    static final Set<String> KIND_NAMES = ((KINDS*.kind as List<String>) + CHILD_KINDS as Set<String>).asImmutable()

    /** The bundle list each top-level kind travels in. */
    static final Map<String, String> BUNDLE_LISTS = [(NETSUITE_AUTH): "connections", (SHOPIFY_AUTH): "connections",
            (HOTWAX_OMS_REST): "connections", (NETSUITE_RESTLET): "connections", (NETSUITE_SUITEQL): "queries",
            (CONC): "conclusionCodes", (RUN): "runs", (AUDIT): "audits", (STATEMENT): "statements", (CHECK): "checks",
            (CHAT_SPACE): "chatSpaces", (AUTOMATION): "automations", (LIFECYCLE): "lifecycles", (KNOWLEDGE): "knowledge",
            (SCRIPT): "scripts"].asImmutable()

    static Map spec(String kind) {
        return KINDS.find { it.kind == kind }
    }

    static Map specForEntity(String entityName) {
        return KINDS.find { it.entityName == entityName }
    }

    /**
     * DAR-BE-188. A kind as a tenant config version carries it: its versionPlainFields travel as plain values, neither
     * refs nor dropped, exactly as before automations could leave their tenant.
     */
    static Map versionSpec(Map spec) {
        List<String> plain = (spec?.versionPlainFields ?: []) as List<String>
        List<Map> children = (spec?.children ?: []) as List<Map>
        // DAR-BE-236: a child's plain fields too (a deployment names its chat space by id in a version).
        List<Map> versionChildren = children.collect { versionSpec(it) }
        boolean childChanged = (0..<children.size()).any { !versionChildren[it].is(children[it]) }
        if (!plain && !childChanged) return spec
        Map out = new LinkedHashMap(spec)
        if (childChanged) out.children = versionChildren
        out.refFields = ((spec.refFields ?: [:]) as Map).findAll { k, v -> !(k in plain) }
        out.positionRefs = ((spec.positionRefs ?: [:]) as Map).findAll { k, v -> !(k in plain) }
        out.dropFields = ((spec.dropFields ?: []) as List<String>).findAll { !(it in plain) }
        out.remove("versionPlainFields")
        return out.asImmutable()
    }

    /**
     * DAR-BE-188. A record's heldRefs: each column naming a record the bundle holds becomes a ref; one naming a record it
     * does not hold is left out, as a dropped column is.
     */
    static void addHeldRefs(Map spec, Map rec, def row, Set<String> held) {
        ((spec.heldRefs ?: [:]) as Map<String, String>).each { String f, String kind ->
            String value = text(row.get(f))
            String r = value ? ref(kind, value) : null
            if (r && r in held) ((Map) rec.refs).put(f, r)
        }
    }

    /** DAR-BE-188. The ref of the scope at index in its run (SCOPE_CHILD order). */
    static String scopeRef(String ruleSetId, int index) {
        return ref(SCOPE, "${ruleSetId}#${index}".toString())
    }

    /** [ruleSetId, index] of a scope ref's id; null when it is not one. */
    static List parseScopeId(String id) {
        int hash = id ? id.lastIndexOf('#') : -1
        if (hash <= 0 || !(id.substring(hash + 1) ==~ /\d+/)) return null
        return [id.substring(0, hash), id.substring(hash + 1) as int]
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
        skip.addAll(((spec.positionRefs ?: [:]) as Map).keySet() as Set<String>)
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
        out.addAll(((spec.positionRefs ?: [:]) as Map).keySet() as Set<String>)
        out.addAll(((spec.heldRefs ?: [:]) as Map).keySet() as Set<String>)
        if (spec.polymorphicRef) out.add(spec.polymorphicRef as String)
        return out
    }

    /** Every column that is not encrypted: what a read may select. */
    static List<String> readableFields(def ec, String entityName) {
        def ed = ec.entity.getEntityDefinition(entityName)
        return (ed.getAllFieldNames() as List<String>).findAll { !isSecret(ed, it) }
    }

    /** Encrypted columns that travel sealed. A secret the kind drops (dropFields) stays behind like any dropped column. */
    static List<String> secretFields(def ec, String entityName) {
        def ed = ec.entity.getEntityDefinition(entityName)
        List<String> dropped = (specForEntity(entityName)?.dropFields ?: []) as List<String>
        return (ed.getAllFieldNames() as List<String>).findAll { isSecret(ed, it) && !(it in dropped) }.sort()
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
