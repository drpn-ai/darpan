package darpan.admin.transfer

import darpan.facade.common.SharedConfigAccessSupport
import darpan.facade.reconciliation.ReconciliationSavedRunSupport
import groovy.json.JsonOutput
import groovy.transform.CompileDynamic

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import static darpan.admin.transfer.TransferSpecs.*

/**
 * DAR-ADMIN-006. The target side of a config transfer: the plan (a dry run that writes nothing), its hash, and the apply
 * that writes exactly what was planned.
 *
 * <p>Matching: connections only through the operator's connectionMap (an existing target id, or CREATE); queries by
 * description; conclusion codes by id (a shared code) or label; runs by ruleSetName; audits by auditCode; statements by
 * their audit and code; checks by checkCode. A matched record is diffed by exporting the TARGET row with the same
 * exporter and comparing both after every ref is rewritten to the target id it lands on (a rule by its position in its
 * run), so a record that would come out the same is UNCHANGED.</p>
 */
@CompileDynamic
class ConfigTransferImporter {

    static final List<String> ACTIONS = ["CREATE", "UPDATE", "UNCHANGED", "MAPPED", "NEEDS_CREDENTIALS"].asImmutable()
    static final String CREATE = "CREATE"

    /** The dry run: [lines, blocking, warnings, counts]. Writes nothing. */
    static Map<String, Object> plan(def ec, String targetTenantId, Map bundle, Map<String, String> connectionMap) {
        return analyse(ec, targetTenantId, bundle, connectionMap).plan as Map<String, Object>
    }

    /** SHA-256 hex over the canonical JSON (keys sorted at every level) of what was previewed and where. */
    static String planHash(String targetTenantId, Map bundle, Map connectionMap, Map plan) {
        String json = JsonOutput.toJson(sorted([tenant: targetTenantId, bundle: bundle, connectionMap: connectionMap ?: [:], plan: plan]))
        return MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)).encodeHex().toString()
    }

    static Map<String, Object> analyse(def ec, String tenantId, Map bundle, Map connectionMap) {
        Map ctx = [ec      : ec, tenantId: tenantId, bundle: bundle ?: [:], map: (connectionMap ?: [:]),
                   blocking: [], warnings: [], resolved: [:], matches: [:], actions: [:], changes: [:], ruleIdentity: [:]]
        if (text(bundle?.bundleVersion) != text(BUNDLE_VERSION)) {
            block(ctx, "BUNDLE_VERSION", null,
                    "This instance reads bundle version ${BUNDLE_VERSION}, not ${bundle?.bundleVersion}. Export again from a matching source.")
            return finish(ctx, [])
        }
        String sourceVersion = text(bundle.source?.darpanVersion)
        String localVersion = ConfigTransferExporter.darpanVersion(ec)
        if (sourceVersion != localVersion) {
            warn(ctx, "VERSION_DIFFERS", null, "The source runs Darpan ${sourceVersion}; this instance runs ${localVersion}.")
        }
        checkShape(ctx)

        matchConnections(ctx)
        records(ctx, "queries").each { Map q -> matchOne(ctx, q, queryMatch(ctx, q)) }
        records(ctx, "conclusionCodes").each { Map c -> matchCode(ctx, c) }
        records(ctx, "runs").each { Map r ->
            matchOne(ctx, r, scoped(ctx, "darpan.rule.RuleSet").condition("ruleSetName", r.fields?.ruleSetName).list().find())
        }
        records(ctx, "audits").each { Map a ->
            matchOne(ctx, a, scoped(ctx, "darpan.audit.Audit").condition("auditCode", a.fields?.auditCode).list().find())
        }
        records(ctx, "statements").each { Map s ->
            String auditId = ctx.resolved[s.refs?.auditId] as String
            matchOne(ctx, s, auditId ? scoped(ctx, "darpan.audit.AuditStatement").condition("auditId", auditId)
                    .condition("statementCode", s.fields?.statementCode).list().find() : null)
        }
        records(ctx, "checks").each { Map c ->
            matchOne(ctx, c, scoped(ctx, "darpan.audit.AuditCheck").condition("checkCode", c.fields?.checkCode).list().find())
        }

        sourceRuleIdentities(ctx)
        ["queries": NETSUITE_SUITEQL, "runs": RUN, "audits": AUDIT, "statements": STATEMENT, "checks": CHECK].each { String list, String kind ->
            records(ctx, list).findAll { ctx.matches.containsKey(it.ref) }.each { Map rec -> diff(ctx, spec(kind), rec) }
        }
        targetOnlyWarnings(ctx)
        rulesReplacedWarnings(ctx)

        List<Map> lines = []
        records(ctx, "connections").each { Map c ->
            if (ctx.actions[c.ref]) lines << line(ctx, c.kind as String, c)
        }
        [queries: NETSUITE_SUITEQL, conclusionCodes: CONC, runs: RUN, audits: AUDIT, statements: STATEMENT, checks: CHECK].each { String list, String kind ->
            records(ctx, list).each { Map rec -> if (ctx.actions[rec.ref]) lines << line(ctx, kind, rec) }
        }
        return finish(ctx, lines)
    }

    // ---------------------------------------------------------------- shape gates

    /** Kinds this instance lacks, columns its entities do not declare, and instance-local endpoints all block. */
    private static void checkShape(Map ctx) {
        def ec = ctx.ec
        new LinkedHashSet<String>(BUNDLE_LISTS.values()).each { String list ->
            records(ctx, list).each { Map rec ->
                String kind = (rec.kind ?: parseRef(rec.ref)?.get(0)) as String
                Map kindSpec = TransferSpecs.spec(kind)
                if (kindSpec == null || !isEntityAvailable(ec, kindSpec.entityName as String)) {
                    block(ctx, "KIND_UNAVAILABLE", rec.ref, "This instance has no ${kindSpec?.entityName ?: kind}, so ${rec.label ?: rec.ref} cannot be transferred.")
                    return
                }
                if (kind == CONC) return
                checkFields(ctx, kindSpec, rec, rec.ref as String)
                allRefs(rec).each { String r ->
                    String refKind = parseRef(r)?.get(0)
                    if (refKind && TransferSpecs.spec(refKind) == null && refKind != RULE) {
                        block(ctx, "KIND_UNAVAILABLE", rec.ref, "${rec.label ?: rec.ref} names ${r}, a kind of record a transfer does not carry.")
                    }
                }
            }
        }
        records(ctx, "runs").each { Map run ->
            sources(run).each { Map source ->
                Map f = (source.fields ?: [:]) as Map
                String remote = text(f.systemMessageRemoteId)
                if (remote && !ReconciliationSavedRunSupport.isVirtualApiOrdersRemote(f.systemEnumId, remote, f.sourceConfigType)) {
                    block(ctx, "SYSTEM_MESSAGE_REMOTE", run.ref, "Run ${run.label} reads endpoint ${remote}, which belongs to the source " +
                            "instance. Point the run's ${f.fileSide} at a connection before transferring it.")
                }
            }
        }
    }

    private static void checkFields(Map ctx, Map spec, Map rec, String ownerRef) {
        def ed = ctx.ec.entity.getEntityDefinition(spec.entityName as String)
        List<String> names = []
        ["fields", "refs", "listRefs"].each { String part -> names.addAll(((rec.get(part) ?: [:]) as Map).keySet().collect { it as String }) }
        names.unique().findAll { !ed.isField(it) }.each { String name ->
            block(ctx, "UNKNOWN_FIELD", ownerRef, "${spec.entityName} has no field ${name} on this instance; the source is ahead. " +
                    "Deploy this instance first.")
        }
        Map<String, Map> childSpecs = ((spec.children ?: []) as List<Map>).collectEntries { [(shortName(it.entityName as String)): it] }
        ((rec.children ?: [:]) as Map<String, List>).each { String name, List rows ->
            Map childSpec = childSpecs[name]
            if (childSpec == null) {
                block(ctx, "UNKNOWN_FIELD", ownerRef, "${spec.entityName} carries ${name}, which this instance does not know.")
                return
            }
            (rows ?: []).each { checkFields(ctx, childSpec, (Map) it, ownerRef) }
        }
        if (spec.links) ((rec.links ?: []) as List<Map>).each { checkFields(ctx, spec.links as Map, it, ownerRef) }
    }

    // ---------------------------------------------------------------- matching

    private static void matchConnections(Map ctx) {
        records(ctx, "connections").each { Map c ->
            Map kindSpec = TransferSpecs.spec(c.kind as String)
            if (kindSpec == null || !isEntityAvailable(ctx.ec, kindSpec.entityName as String)) return
            String choice = text(ctx.map[c.ref])
            if (!choice) {
                block(ctx, "CONNECTION_UNMAPPED", c.ref, "Choose a connection on this instance for ${c.label}, or create one.")
            } else if (choice == CREATE) {
                if (c.granted) {
                    block(ctx, "CONNECTION_GRANTED_CREATE", c.ref, "${c.label} is shared to the source tenant, not owned by it, so it " +
                            "cannot be created here. Choose a connection on this instance.")
                    return
                }
                List secrets = (c.secretFields ?: []) as List
                ctx.actions[c.ref] = secrets ? "NEEDS_CREDENTIALS" : CREATE
                if (secrets) warn(ctx, "NEEDS_CREDENTIALS", c.ref, "${c.label} is created without ${secrets.join(', ')}; enter them on this instance.")
            } else if (connectionTarget(ctx, kindSpec, choice) == null) {
                // One answer for a missing id, another kind's id and another tenant's id (DAR-BE-135).
                block(ctx, "CONNECTION_NOT_FOUND", c.ref, "Connection ${choice} was not found for ${c.label}.")
            } else {
                ctx.resolved[c.ref] = choice
                ctx.actions[c.ref] = "MAPPED"
            }
        }
    }

    /** The target connection the operator chose: the target tenant's own, or one shared to it. */
    private static def connectionTarget(Map ctx, Map spec, String id) {
        def row = finder(ctx.ec, spec.entityName as String).condition(spec.pkField as String, id)
                .selectFields([spec.pkField, "companyUserGroupId"]).useCache(false).one()
        if (row == null) return null
        if (row.companyUserGroupId == ctx.tenantId) return row
        return SharedConfigAccessSupport.isSharedWithTenant(ctx.ec, spec.grantType as String, id, ctx.tenantId as String) ? row : null
    }

    private static def queryMatch(Map ctx, Map q) {
        return scoped(ctx, spec(NETSUITE_SUITEQL).entityName as String).condition("description", q.fields?.description)
                .orderBy("nsSuiteQlSourceQueryId").list().find()
    }

    /** A shared code by its id when this instance has it; otherwise any code of the target's catalog with the same label. */
    private static void matchCode(Map ctx, Map c) {
        def ec = ctx.ec
        if (ctx.catalog == null) {
            Map<String, String> owners = finder(ec, "darpan.rule.TenantConclusion").useCache(false).list()
                    .collectEntries { [(it.enumId as String): it.companyUserGroupId as String] }
            ctx.owners = owners
            ctx.catalog = ec.entity.find("moqui.basic.Enumeration").condition("enumTypeId", CONCLUSION_ENUM_TYPE).orderBy("enumId")
                    .useCache(false).list().findAll { owners[it.enumId as String] in [null, ctx.tenantId] }
        }
        String target = null
        String enumId = text(c.enumId)
        if (!c.tenantOwned && enumId && ((List) ctx.catalog).any { it.enumId == enumId }) target = enumId
        if (target == null) target = ((List) ctx.catalog).find { (it.description ?: "").equalsIgnoreCase(text(c.label) ?: "") }?.enumId
        if (target != null) {
            ctx.resolved[c.ref] = target
            ctx.actions[c.ref] = "UNCHANGED"
            return
        }
        ctx.actions[c.ref] = CREATE
        if (!c.tenantOwned) {
            warn(ctx, "SEED_CONCLUSION_CREATED", c.ref, "${c.label} is a shared code on the source; this instance does not have it, " +
                    "so it is created as ${ctx.tenantId}'s own.")
        }
    }

    private static void matchOne(Map ctx, Map rec, def row) {
        if (rec.ref == null || ctx.actions.containsKey(rec.ref)) return
        if (row == null) {
            ctx.actions[rec.ref] = CREATE
            return
        }
        Map kindSpec = TransferSpecs.spec(parseRef(rec.ref)[0])
        ctx.matches[rec.ref] = row
        ctx.resolved[rec.ref] = row.get(kindSpec.pkField as String) as String
        ctx.actions[rec.ref] = "UNCHANGED"
    }

    // ---------------------------------------------------------------- diff

    /** UPDATE with changes when the target row, exported and canonicalised, differs from the bundle record. */
    private static void diff(Map ctx, Map spec, Map rec) {
        def ec = ctx.ec
        def row = ctx.matches[rec.ref]
        Closure<String> targetNamer = { String kind, Object id -> TransferSpecs.ref(kind, text(id)) }
        Map target = ConfigTransferExporter.record(ec, spec, row, targetNamer)
        if (spec.kind == CHECK) {
            List<String> statementOrder = records(ctx, "statements").collect { ctx.resolved[it.ref] as String }.findAll { it }
            List links = statementOrder ? finder(ec, "darpan.audit.AuditStatementCheck").condition("companyUserGroupId", ctx.tenantId)
                    .condition("checkId", row.checkId).useCache(false).list() : []
            target.links = ConfigTransferExporter.linkRecords(ec, row.checkId as String, links, statementOrder, targetNamer)
        }
        Map source = canonical(spec, rec, sourceIdOf(ctx))
        Map existing = canonical(spec, target, targetIdOf(ctx))
        List<Map> changes = changesBetween(existing, source)
        if (changes) {
            ctx.actions[rec.ref] = "UPDATE"
            ctx.changes[rec.ref] = changes
        }
    }

    /** A record with every ref replaced by the identity it has on the target, blanks dropped, keys sorted. */
    static Map canonical(Map spec, Map rec, Closure<String> idOf) {
        Map out = new TreeMap()
        Map fields = new TreeMap()
        ((rec.fields ?: [:]) as Map).each { k, v ->
            if (v == null) return
            fields[k] = k in JSON_REF_FIELDS ? ConfigTransferExporter.rewriteLookups(v) { Map item -> idOf(item.sourceConfigId) } : text(v)
        }
        out.fields = fields
        out.refs = new TreeMap(((rec.refs ?: [:]) as Map).collectEntries { k, v -> [(k): idOf(v)] })
        if (rec.listRefs != null) out.listRefs = new TreeMap(((Map) rec.listRefs).collectEntries { k, v -> [(k): ((v ?: []) as List).collect { idOf(it) }] })
        if (spec.children) {
            Map children = new TreeMap()
            (spec.children as List<Map>).each { Map child ->
                String name = shortName(child.entityName as String)
                children[name] = ((rec.children?.get(name) ?: []) as List<Map>).collect { canonical(child, it, idOf) }
            }
            out.children = children
        }
        if (spec.links) out.links = ((rec.links ?: []) as List<Map>).collect { canonical(spec.links as Map, it, idOf) }
        return out
    }

    private static List<Map> changesBetween(Map existing, Map incoming) {
        List<Map> changes = []
        ["fields", "refs", "listRefs"].each { String part ->
            Map from = (existing[part] ?: [:]) as Map
            Map to = (incoming[part] ?: [:]) as Map
            (new TreeSet(from.keySet() + to.keySet())).each { k ->
                Object a = from[k] instanceof List ? (from[k] as List).join(",") : from[k]
                Object b = to[k] instanceof List ? (to[k] as List).join(",") : to[k]
                if (a != b) changes << [field: k, from: a, to: b]
            }
        }
        Map fromChildren = (existing.children ?: [:]) as Map
        Map toChildren = (incoming.children ?: [:]) as Map
        (new TreeSet(fromChildren.keySet() + toChildren.keySet())).each { k ->
            if (fromChildren[k] != toChildren[k]) changes << [field: k, from: "${((List) fromChildren[k] ?: []).size()} rows".toString(),
                                                                to  : "${((List) toChildren[k] ?: []).size()} rows".toString()]
        }
        if (existing.links != incoming.links) {
            changes << [field: "AuditStatementCheck", from: "${((List) existing.links ?: []).size()} links".toString(),
                        to   : "${((List) incoming.links ?: []).size()} links".toString()]
        }
        return changes
    }

    /** A bundle ref as the id it lands on: a matched or mapped record's target id, else NEW; a rule by run and position. */
    private static Closure<String> sourceIdOf(Map ctx) {
        return { Object value ->
            List<String> parsed = parseRef(value)
            if (parsed == null) return text(value)
            if (parsed[0] == RULE) return (ctx.ruleIdentity[value] ?: "NEW:${value}") as String
            return (ctx.resolved[value] ?: "NEW:${value}") as String
        }
    }

    /** A target-side ref as its id; a rule by its run and position there. */
    private static Closure<String> targetIdOf(Map ctx) {
        return { Object value ->
            List<String> parsed = parseRef(value)
            if (parsed == null) return text(value)
            return parsed[0] == RULE ? targetRuleIdentity(ctx, parsed[1]) : parsed[1]
        }
    }

    private static void sourceRuleIdentities(Map ctx) {
        records(ctx, "runs").each { Map run ->
            String runIdentity = (ctx.resolved[run.ref] ?: "NEW:${run.ref}") as String
            ((run.children?.Rule ?: []) as List<Map>).eachWithIndex { Map rule, int i -> ctx.ruleIdentity[rule.ref] = "RULE@${runIdentity}#${i}".toString() }
        }
    }

    private static String targetRuleIdentity(Map ctx, String ruleId) {
        Map cache = (Map) (ctx.targetRules ?: (ctx.targetRules = [:]))
        if (cache.containsKey(ruleId)) return cache[ruleId]
        def rule = finder(ctx.ec, "darpan.rule.Rule").condition("ruleId", ruleId).useCache(false).one()
        String identity = "RULE?${ruleId}"
        if (rule != null) {
            List<String> ids = finder(ctx.ec, "darpan.rule.Rule").condition("ruleSetId", rule.ruleSetId)
                    .orderBy(["sequenceNum", "ruleId"]).useCache(false).list()*.ruleId.collect { it as String }
            identity = "RULE@${rule.ruleSetId}#${ids.indexOf(ruleId)}".toString()
        }
        cache[ruleId] = identity
        return identity
    }

    // ---------------------------------------------------------------- warnings

    /** A matched audit's statements and checks the bundle does not carry are left alone; say so. */
    private static void targetOnlyWarnings(Map ctx) {
        records(ctx, "audits").findAll { ctx.matches.containsKey(it.ref) }.each { Map audit ->
            String auditId = ctx.resolved[audit.ref] as String
            Set<String> carried = records(ctx, "statements").findAll { it.refs?.auditId == audit.ref }*.fields*.statementCode as Set<String>
            List statements = scoped(ctx, "darpan.audit.AuditStatement").condition("auditId", auditId).list()
            List<String> extraStatements = statements.findAll { !((it.statementCode as String) in carried) }*.statementCode.collect { it as String }.sort()
            List<String> statementIds = statements*.statementId.collect { it as String }
            Set<String> carriedChecks = records(ctx, "checks")*.fields*.checkCode as Set<String>
            List<String> checkIds = statementIds ? scoped(ctx, "darpan.audit.AuditStatementCheck").condition("statementId", "in", statementIds)
                    .list()*.checkId.collect { it as String }.unique() : []
            List<String> extraChecks = checkIds ? scoped(ctx, "darpan.audit.AuditCheck").condition("checkId", "in", checkIds).list()
                    .findAll { !((it.checkCode as String) in carriedChecks) }*.checkCode.collect { it as String }.sort() : []
            if (extraStatements || extraChecks) {
                warn(ctx, "TARGET_ONLY_CHILDREN", audit.ref, "Audit ${audit.label} keeps what the bundle does not carry, untouched: " +
                        ([extraStatements ? "statements ${extraStatements.join(', ')}" : null,
                          extraChecks ? "checks ${extraChecks.join(', ')}" : null].findAll { it }.join("; ")) + ".")
            }
        }
    }

    /** Rule ids are kept by position; a run whose rules change while findings exist is worth a word. */
    private static void rulesReplacedWarnings(Map ctx) {
        records(ctx, "runs").findAll { ctx.actions[it.ref] == "UPDATE" && (ctx.changes[it.ref] as List<Map>).any { c -> c.field == "Rule" } }
                .each { Map run ->
            List<String> checkIds = scoped(ctx, "darpan.audit.AuditCheck").condition("savedRunId", ctx.resolved[run.ref]).list()
                    *.checkId.collect { it as String }
            if (checkIds && scoped(ctx, "darpan.audit.AuditFinding").condition("checkId", "in", checkIds).count() > 0) {
                warn(ctx, "RULES_REPLACED_WITH_FINDINGS", run.ref, "Run ${run.label} changes its rules while findings exist; " +
                        "findings already recorded keep the rule they were raised by.")
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Map line(Map ctx, String kind, Map rec) {
        String action = ctx.actions[rec.ref] as String
        return [kind    : kind, ref: rec.ref, label: rec.label, action: action,
                targetId: action in [CREATE, "NEEDS_CREDENTIALS"] ? null : ctx.resolved[rec.ref],
                changes : (ctx.changes[rec.ref] ?: []) as List]
    }

    private static Map<String, Object> finish(Map ctx, List<Map> lines) {
        Map counts = ACTIONS.collectEntries { String a -> [(a): lines.count { it.action == a } as int] }
        ctx.plan = [lines: lines, blocking: ctx.blocking, warnings: ctx.warnings, counts: counts]
        return ctx
    }

    private static void block(Map ctx, String code, Object ref, String message) {
        Map entry = [code: code, ref: ref, message: message]
        if (!((List) ctx.blocking).contains(entry)) ((List) ctx.blocking).add(entry)
    }

    private static void warn(Map ctx, String code, Object ref, String message) {
        ((List) ctx.warnings).add([code: code, ref: ref, message: message])
    }

    static List<Map> records(Map ctx, String list) {
        return ((ctx.bundle?.get(list) ?: []) as List).findAll { it instanceof Map } as List<Map>
    }

    /** The target tenant's rows of an entity. */
    private static def scoped(Map ctx, String entityName) {
        return finder(ctx.ec, entityName).condition("companyUserGroupId", ctx.tenantId).useCache(false)
    }

    /** Every compare source of a run record. */
    static List<Map> sources(Map run) {
        return ((run.children?.RuleSetCompareScope ?: []) as List<Map>).collectMany { Map scope ->
            ((scope.children?.RuleSetCompareSource ?: []) as List<Map>)
        }
    }

    /** Every ref a record carries, its children's and links' included, and those inside JSON lookups. */
    private static List<String> allRefs(Map rec) {
        List<String> out = []
        out.addAll(((rec.refs ?: [:]) as Map).values().collect { it as String })
        ((rec.listRefs ?: [:]) as Map).values().each { out.addAll(((List) it).collect { it as String }) }
        ((rec.fields ?: [:]) as Map).each { k, v ->
            if (k in JSON_REF_FIELDS) ConfigTransferExporter.rewriteLookups(v) { Map item -> out << (item.sourceConfigId as String); item.sourceConfigId }
        }
        ((rec.children ?: [:]) as Map).values().each { ((List) it).each { out.addAll(allRefs((Map) it)) } }
        ((rec.links ?: []) as List).each { out.addAll(allRefs((Map) it)) }
        return out
    }

    private static Object sorted(Object value) {
        if (value instanceof Map) {
            Map out = new TreeMap()
            ((Map) value).each { k, v -> out[k as String] = sorted(v) }
            return out
        }
        if (value instanceof List) return ((List) value).collect { sorted(it) }
        if (value instanceof GString) return value.toString()
        return value
    }
}
