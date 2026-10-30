package darpan.admin.transfer

import darpan.admin.AdminAuditSupport
import darpan.facade.common.SharedConfigAccessSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import groovy.json.JsonOutput
import groovy.transform.CompileDynamic

import javax.crypto.SecretKey
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.sql.Timestamp

import reconciliation.rule.RuleEngineSupport

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
 *
 * <p>The dry run predicts the apply: anything the apply would refuse or fail on (a column a transfer does not write,
 * a ref the bundle cannot resolve, a natural key that names no one record, reference data the target lacks, a compare
 * scope that would be deleted from under a run result) is a blocking line, and the apply refuses any plan that has one.
 * The one thing only the apply sees is the rule engine's compile, which fails the whole transaction.</p>
 */
@CompileDynamic
class ConfigTransferImporter {

    static final List<String> ACTIONS = ["CREATE", "UPDATE", "UNCHANGED", "MAPPED", "NEEDS_CREDENTIALS"].asImmutable()
    static final String CREATE = "CREATE"
    static final String STALE_PLAN_MESSAGE = "The target changed since the preview. Preview again."
    static final String SERVICE_NAME = "admin.TransferAdminServices.import#ConfigBundle"
    /** Length of a Moqui "id" column (MoquiDefaultConf: VARCHAR(40)). */
    static final int ID_LENGTH = 40
    static final String REMOTE_ENTITY = "moqui.service.message.SystemMessageRemote"

    /** The dry run: [lines, blocking, warnings, counts]. Writes nothing. */
    static Map<String, Object> plan(def ec, String targetTenantId, Map bundle, Map<String, String> connectionMap,
                                    String secretHandoffToken = null) {
        return analyse(ec, targetTenantId, bundle, connectionMap, secretHandoffToken).plan as Map<String, Object>
    }

    /** The dry run and its hash, which covers the handoff key the sealed credentials were opened with. */
    static Map<String, Object> preview(def ec, String targetTenantId, Map bundle, Map<String, String> connectionMap,
                                       String secretHandoffToken = null) {
        Map ctx = analyse(ec, targetTenantId, bundle, connectionMap, secretHandoffToken)
        return [plan    : ctx.plan,
                planHash: planHash(targetTenantId, bundle, connectionMap, ctx.plan as Map, ctx.handoffFingerprint as String)] as Map<String, Object>
    }

    /**
     * SHA-256 hex over the canonical JSON (keys sorted at every level) of what was previewed and where; with sealed
     * credentials, also the fingerprint of the handoff key that opened them.
     */
    static String planHash(String targetTenantId, Map bundle, Map connectionMap, Map plan, String handoffFingerprint = null) {
        Map hashed = [tenant: targetTenantId, bundle: bundle, connectionMap: connectionMap ?: [:], plan: plan]
        if (handoffFingerprint) hashed.handoffFingerprint = handoffFingerprint
        String json = JsonOutput.toJson(sorted(hashed))
        return MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)).encodeHex().toString()
    }

    /**
     * Writes the plan, refusing unless it has no blocking item and hashes to what was previewed. One transaction (the
     * service's): any failure, a run that does not compile included, throws and rolls every row back. Answers [plan,
     * planHash, idMap: every bundle ref to its target id]; null, with an error and nothing written, when refused.
     */
    static Map<String, Object> apply(def ec, String targetTenantId, Map bundle, Map connectionMap, String expectedPlanHash,
                                     String sourceInstanceLabel, String secretHandoffToken = null,
                                     String logServiceName = SERVICE_NAME) {
        Map ctx = analyse(ec, targetTenantId, bundle, connectionMap, secretHandoffToken)
        Map plan = ctx.plan as Map
        String hash = planHash(targetTenantId, bundle, connectionMap, plan, ctx.handoffFingerprint as String)
        if (plan.blocking) {
            ec.message.addError("The plan has blocking items (${(plan.blocking as List<Map>)*.code.unique().join(', ')}); resolve them and preview again.")
            return null
        }
        if (text(expectedPlanHash) != hash) {
            ec.message.addError(STALE_PLAN_MESSAGE)
            return null
        }
        ctx.idMap = new LinkedHashMap<String, String>(ctx.resolved as Map)
        ctx.now = ec.user.nowTimestamp
        ctx.userId = ec.user.userId

        records(ctx, "connections").each { Map c ->
            if (ctx.actions[c.ref] in [CREATE, "NEEDS_CREDENTIALS"]) {
                Map kindSpec = TransferSpecs.spec(c.kind as String)
                // Secret columns are not in the record: a created connection has them empty unless sealed ones were opened.
                createRow(ctx, kindSpec, c, TenantScopedFinder.mintId(ec, kindSpec.entityName as String, kindSpec.pkField as String),
                        fillsFor(ctx, c))
            } else if (ctx.actions[c.ref] == "MAPPED" && fillsFor(ctx, c)) {
                fillMapped(ctx, c)
            }
        }
        records(ctx, "queries").each { Map q -> writeTree(ctx, spec(NETSUITE_SUITEQL), q, "") }
        records(ctx, "conclusionCodes").each { Map c -> if (ctx.actions[c.ref] == CREATE) createConclusion(ctx, c) }
        records(ctx, "runs").findAll { ctx.actions[it.ref] != "UNCHANGED" }.collectMany { sources(it) }.each { Map source ->
            // A shared remote this instance has not needed yet is made from the run's connector row, as
            // ensureVirtualApiOrdersRemote does for the canonical OMS and Shopify ones; one it has is left as it is.
            Map f = (source.fields ?: [:]) as Map
            String remote = text(f.systemMessageRemoteId)
            Object found = remote ? remoteSource(ctx, text(f.systemEnumId), remote) : null
            if (found instanceof Map) {
                ec.entity.makeValue(REMOTE_ENTITY).setAll([systemMessageRemoteId: remote, description: found.endpointLabel,
                        sendUrl: found.sendUrlTemplate, sendServiceName: found.remoteSendServiceName]).create()
            }
        }
        records(ctx, "runs").each { Map r ->
            if (ctx.actions[r.ref] == "UNCHANGED") {
                keepRuleIds(ctx, r)
            } else {
                writeTree(ctx, spec(RUN), r, "RS_")
                compile(ctx, ctx.idMap[r.ref] as String, r.label as String)
            }
        }
        records(ctx, "audits").each { Map a -> writeTree(ctx, spec(AUDIT), a, "") }
        records(ctx, "statements").each { Map s -> writeStatement(ctx, s) }
        records(ctx, "checks").each { Map c -> writeCheck(ctx, c) }
        records(ctx, "automations").each { Map a -> writeAutomation(ctx, a) }

        String counts = ACTIONS.collect { "${it} ${plan.counts[it]}" }.join(", ")
        // DAR-BE-160: a tenant's config restore applies through here too, and logs under its own service name.
        AdminAuditSupport.record(ec, logServiceName ?: SERVICE_NAME, "Tenant", targetTenantId,
                ("${sourceInstanceLabel ?: '?'}/${bundle.source?.tenantUserGroupId ?: '?'} bundle ${hash.take(12)}: ${counts}" +
                        (ctx.carriesSecrets ? "; credentials filled: ${ctx.filledCount ?: 0}" : "")).toString())
        return [plan: plan, planHash: hash, idMap: ctx.idMap] as Map<String, Object>
    }

    static Map<String, Object> analyse(def ec, String tenantId, Map bundle, Map connectionMap, String secretHandoffToken = null) {
        Map ctx = [ec      : ec, tenantId: tenantId, bundle: bundle ?: [:], map: (connectionMap ?: [:]),
                   blocking: [], warnings: [], resolved: [:], matches: [:], actions: [:], changes: [:], ruleIdentity: [:],
                   secretValues: [:], fills: [:], kept: [:], unreadable: [:]]
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
        checkRefs(ctx)
        openSecrets(ctx, secretHandoffToken)

        matchConnections(ctx)
        matchByKey(ctx, "queries", "description", { Map q -> text(q.fields?.description) }) { Map q ->
            scoped(ctx, spec(NETSUITE_SUITEQL).entityName as String).condition("description", q.fields?.description).list()
        }
        records(ctx, "conclusionCodes").each { Map c -> matchCode(ctx, c) }
        matchByKey(ctx, "runs", "ruleSetName", { Map r -> text(r.fields?.ruleSetName) }) { Map r ->
            scoped(ctx, "darpan.rule.RuleSet").condition("ruleSetName", r.fields?.ruleSetName).list()
        }
        ownedRunGate(ctx)
        matchByKey(ctx, "audits", "auditCode", { Map a -> text(a.fields?.auditCode) }) { Map a ->
            scoped(ctx, "darpan.audit.Audit").condition("auditCode", a.fields?.auditCode).list()
        }
        matchByKey(ctx, "statements", "statementCode",
                { Map s -> text(s.fields?.statementCode) ? "${s.refs?.auditId} ${s.fields.statementCode}".toString() : null }) { Map s ->
            String auditId = ctx.resolved[s.refs?.auditId] as String
            auditId ? scoped(ctx, "darpan.audit.AuditStatement").condition("auditId", auditId)
                    .condition("statementCode", s.fields?.statementCode).list() : []
        }
        matchByKey(ctx, "checks", "checkCode", { Map c -> text(c.fields?.checkCode) }) { Map c ->
            scoped(ctx, "darpan.audit.AuditCheck").condition("checkCode", c.fields?.checkCode).list()
        }
        // DAR-BE-160. Only a config version carries automations, and a version restores into its own tenant, where an
        // automation is matched by its own id (names need not be unique); from another tenant, by its name.
        boolean sameTenant = text(bundle.source?.tenantUserGroupId) == tenantId
        matchByKey(ctx, "automations", sameTenant ? "id" : "automationName",
                { Map a -> sameTenant ? parseRef(a.ref)?.get(1) : text(a.fields?.automationName) }) { Map a ->
            sameTenant ? scoped(ctx, spec(AUTOMATION).entityName as String).condition("automationId", parseRef(a.ref)?.get(1)).list()
                    : scoped(ctx, spec(AUTOMATION).entityName as String).condition("automationName", a.fields?.automationName).list()
        }

        sourceRuleIdentities(ctx)
        ["queries": NETSUITE_SUITEQL, "runs": RUN, "audits": AUDIT, "statements": STATEMENT, "checks": CHECK,
         "automations": AUTOMATION].each { String list, String kind ->
            records(ctx, list).findAll { ctx.matches.containsKey(it.ref) }.each { Map rec -> diff(ctx, spec(kind), rec) }
        }
        scopeGate(ctx)
        targetOnlyWarnings(ctx)
        rulesReplacedWarnings(ctx)

        List<Map> lines = []
        records(ctx, "connections").each { Map c ->
            if (ctx.actions[c.ref]) lines << line(ctx, c.kind as String, c)
        }
        [queries: NETSUITE_SUITEQL, conclusionCodes: CONC, runs: RUN, audits: AUDIT, statements: STATEMENT, checks: CHECK,
         automations: AUTOMATION].each { String list, String kind ->
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
                if (remote && remoteSource(ctx, text(f.systemEnumId), remote) == null) {
                    block(ctx, "SYSTEM_MESSAGE_REMOTE", run.ref, "Run ${run.label} reads endpoint ${remote}, which this instance does not " +
                            "have and no connector here makes for ${f.systemEnumId}. Load that remote here, or point the run's " +
                            "${f.fileSide} at a connection.")
                }
            }
        }
    }

    /**
     * A record's columns: each must exist here (UNKNOWN_FIELD), be one a transfer writes in that part of the record
     * (FIELD_NOT_ALLOWED: never a key, the tenant, a stamp, a secret, or an id column sent as a plain value), name
     * reference data this instance has (MISSING_REFERENCE_DATA), and a lookups column must read as lookups.
     */
    private static void checkFields(Map ctx, Map spec, Map rec, String ownerRef) {
        def ec = ctx.ec
        String entityName = spec.entityName as String
        def ed = ec.entity.getEntityDefinition(entityName)
        Map<String, Set<String>> allowed = [fields  : valueFields(ec, spec) as Set<String>, refs: refColumns(spec),
                                            listRefs: ((spec.textListRefs ?: [:]) as Map).keySet() as Set<String>]
        allowed.each { String part, Set<String> columns ->
            ((rec.get(part) ?: [:]) as Map).keySet().collect { it as String }.each { String name ->
                if (!ed.isField(name)) {
                    block(ctx, "UNKNOWN_FIELD", ownerRef, "${entityName} has no field ${name} on this instance; the source is ahead. " +
                            "Deploy this instance first.")
                } else if (!(name in columns)) {
                    block(ctx, "FIELD_NOT_ALLOWED", ownerRef, "${shortName(entityName)}.${name} is not a column a transfer writes " +
                            "as ${part}; the bundle was edited. Export it again.")
                }
            }
        }
        referenceColumns(ec, spec).each { List<String> column ->
            String value = text(rec.fields?.get(column[0]))
            if (value && !referenceExists(ctx, column[1], column[2], value)) {
                block(ctx, "MISSING_REFERENCE_DATA", ownerRef, "${shortName(entityName)}.${column[0]} is ${value}, which this " +
                        "instance has no ${shortName(column[1])} for. Load it here first.")
            }
        }
        JSON_REF_FIELDS.findAll { text(rec.fields?.get(it))?.trim() }.each { String field ->
            if (lookupsOf(rec.fields.get(field)) == null) {
                block(ctx, "REF_UNRESOLVED", ownerRef, "${shortName(entityName)}.${field} is not a list of lookups naming their " +
                        "connections by ref, so its connection ids cannot be moved. Fix it at the source.")
            }
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

    /**
     * Every ref must name a record the bundle carries, and a check's rules must be rules of its own run. A code the
     * source exported as no conclusion at all is refused by name.
     */
    private static void checkRefs(Map ctx) {
        Set<String> carried = new HashSet<String>()
        Map<String, String> runOfRule = [:]
        new LinkedHashSet<String>(BUNDLE_LISTS.values()).each { String list -> records(ctx, list).each { if (it.ref) carried.add(it.ref as String) } }
        records(ctx, "runs").each { Map run ->
            ((run.children?.Rule ?: []) as List<Map>).each { Map rule ->
                if (rule.ref) {
                    carried.add(rule.ref as String)
                    runOfRule[rule.ref as String] = run.ref as String
                }
            }
        }
        records(ctx, "conclusionCodes").findAll { it.missing }.each { Map c ->
            block(ctx, "REF_UNRESOLVED", c.ref, "${c.label} is not a conclusion code on the source, so the runs that name it cannot move.")
        }
        new LinkedHashSet<String>(BUNDLE_LISTS.values()).each { String list ->
            records(ctx, list).findAll { it.kind != CONC }.each { Map rec ->
                allRefs(rec).findAll { it != null }.unique().each { String r ->
                    List<String> parsed = parseRef(r)
                    boolean knownKind = parsed && (parsed[0] == RULE || TransferSpecs.spec(parsed[0]) != null)
                    if (knownKind && !(r in carried)) {
                        block(ctx, "REF_UNRESOLVED", rec.ref, "${rec.label ?: rec.ref} names ${r}, which the bundle does not carry.")
                    }
                }
            }
        }
        records(ctx, "checks").each { Map check ->
            ((check.links ?: []) as List<Map>).each { Map link ->
                ((link.listRefs?.ruleIds ?: []) as List).findAll { it in runOfRule && runOfRule[it] != check.refs?.savedRunId }.each {
                    block(ctx, "REF_UNRESOLVED", check.ref, "${check.label} names rule ${it}, which is not in its run.")
                }
            }
        }
    }

    // ---------------------------------------------------------------- credentials (DAR-ADMIN-007)

    /**
     * A bundle with sealed credentials needs this instance's handoff token: readable here, the target tenant's, unexpired,
     * and for the key the bundle was sealed to. Every sealed value is opened now, so a value that will not open blocks the
     * plan rather than the apply. Plaintext is kept in ctx for the apply only, never in the plan.
     */
    private static void openSecrets(Map ctx, String token) {
        Map secrets = ctx.bundle.secrets instanceof Map ? (Map) ctx.bundle.secrets : null
        if (secrets == null) return
        ctx.carriesSecrets = true
        if (!text(token)) {
            block(ctx, "SECRET_HANDOFF_MISSING", null, "The bundle carries sealed credentials but no handoff token came with it. Preview again.")
            return
        }
        Map handoff = SecretHandoff.open(ctx.ec, text(token), ctx.tenantId as String)
        SecretKey key = null
        if (handoff != null && handoff.keyFingerprint == text(secrets.keyFingerprint)) {
            try {
                key = SecretSeal.unwrap(handoff.privateKey as String, text(secrets.wrappedKey))
            } catch (Exception ignored) {
                key = null
            }
        }
        if (key == null) {
            block(ctx, "SECRET_HANDOFF_INVALID", null, "The credentials handoff is expired, unreadable, for another tenant or for " +
                    "another key. Preview again.")
            return
        }
        ctx.handoffFingerprint = handoff.keyFingerprint
        records(ctx, "connections").each { Map c ->
            Map kindSpec = TransferSpecs.spec(c.kind as String)
            List<String> allowed = kindSpec && isEntityAvailable(ctx.ec, kindSpec.entityName as String) && !c.granted ?
                    secretFields(ctx.ec, kindSpec.entityName as String) : []
            // The source could not decrypt these (Moqui's placeholder): nothing to fill, so the operator enters them here.
            List<String> unreadable = ((c.unreadableSecrets instanceof List ? c.unreadableSecrets : []) as List).collect { it as String }.sort()
            unreadable.findAll { !(it in allowed) }.each { String field ->
                block(ctx, "FIELD_NOT_ALLOWED", c.ref, "${kindSpec ? shortName(kindSpec.entityName as String) : c.kind}.${field} is not " +
                        "a credential a transfer writes; the bundle was edited. Export it again.")
            }
            unreadable = unreadable.findAll { it in allowed }
            if (unreadable) {
                ctx.unreadable[c.ref] = unreadable
                warn(ctx, "SECRET_UNREADABLE_AT_SOURCE", c.ref, "The source cannot decrypt ${unreadable.join(', ')} of ${c.label}, so " +
                        "it is not copied; enter it on this instance.")
            }
            Map sealed = c.sealedSecrets instanceof Map ? (Map) c.sealedSecrets : null
            if (!sealed) return
            sealed.keySet().collect { it as String }.sort().each { String field ->
                if (!(field in allowed)) {
                    block(ctx, "FIELD_NOT_ALLOWED", c.ref, "${kindSpec ? shortName(kindSpec.entityName as String) : c.kind}.${field} is not " +
                            "a credential a transfer writes; the bundle was edited. Export it again.")
                    return
                }
                try {
                    String value = SecretSeal.open(key, c.ref as String, field, sealed[field] as Map)
                    Map opened = (Map) (ctx.secretValues[c.ref] ?: (ctx.secretValues[c.ref] = new TreeMap()))
                    opened[field] = value
                } catch (GeneralSecurityException ignored) {
                    block(ctx, "SECRET_UNREADABLE", c.ref, "The sealed ${field} of ${c.label} does not open: it was altered or sealed " +
                            "for another connection or field. Export again.")
                }
            }
        }
    }

    /** A mapped connection takes a credential only where its own is empty, and only when it is the target tenant's own. */
    private static void planMappedFills(Map ctx, Map kindSpec, Map c, def target) {
        Map opened = (ctx.secretValues[c.ref] ?: [:]) as Map
        if (!opened) return
        boolean owned = target?.companyUserGroupId == ctx.tenantId
        List<String> filled = []
        List<String> kept = []
        opened.keySet().collect { it as String }.sort().each { String field ->
            boolean set = !owned || !isSecretEmpty(ctx.ec, kindSpec.entityName as String, kindSpec.pkField as String,
                    ctx.resolved[c.ref] as String, field)
            (set ? kept : filled) << field
        }
        ctx.fills[c.ref] = filled
        ctx.kept[c.ref] = kept
    }

    /** The opened credentials a connection takes in this transfer: all of them on CREATE, the empty ones on MAPPED. */
    private static Map<String, String> fillsFor(Map ctx, Map c) {
        Map opened = (ctx.secretValues[c.ref] ?: [:]) as Map
        return ((ctx.fills[c.ref] ?: []) as List<String>).collectEntries { [(it): opened[it] as String] } as Map<String, String>
    }

    private static void fillMapped(Map ctx, Map c) {
        Map kindSpec = TransferSpecs.spec(c.kind as String)
        List<String> filled = fillEmptySecrets(ctx.ec, kindSpec.entityName as String, kindSpec.pkField as String,
                ctx.resolved[c.ref] as String, ctx.tenantId as String, fillsFor(ctx, c))
        ctx.filledCount = ((ctx.filledCount ?: 0) as int) + filled.size()
    }

    /**
     * Writes each credential into the tenant's own connection only where that column is empty at the moment of writing,
     * so a value saved since the plan read the row is never overwritten. Answers the fields written.
     */
    static List<String> fillEmptySecrets(def ec, String entityName, String pkField, String id, String tenantId, Map<String, String> secrets) {
        List<String> empty = secrets.keySet().findAll { isSecretEmpty(ec, entityName, pkField, id, it as String) }.collect { it as String }.sort()
        def value = finder(ec, entityName).condition(pkField, id).selectFields([pkField, "companyUserGroupId"]).useCache(false).one()
        if (value == null || value.companyUserGroupId != tenantId) throw new IllegalStateException("Connection ${id} changed on this instance during the transfer.")
        if (!empty) return []
        empty.each { String field -> value.set(field, secrets[field]) }
        value.update()
        return empty
    }

    /**
     * A credential column is empty when it is NULL or '' as stored (checked as raw SQL: a condition on an encrypted column
     * is itself encrypted), or when it decrypts to ''. A value that does not decrypt is not empty: it is never overwritten.
     */
    private static boolean isSecretEmpty(def ec, String entityName, String pkField, String id, String field) {
        String column = ec.entity.getEntityDefinition(entityName).getColumnName(field)
        if (finder(ec, entityName).condition(pkField, id)
                .condition(ec.entity.conditionFactory.makeConditionWhere("(${column} IS NULL OR ${column} = '')".toString()))
                .useCache(false).count() > 0) return true
        def row = finder(ec, entityName).condition(pkField, id).selectFields([pkField, field]).useCache(false).one()
        return row != null && (row.get(field) as String) == ""
    }

    // ---------------------------------------------------------------- matching

    /**
     * Matches by natural key, which must name one record on each side: a blank key, a key two bundle records share, or a
     * key two target rows answer to blocks (AMBIGUOUS_MATCH) rather than guessing.
     */
    private static void matchByKey(Map ctx, String list, String keyName, Closure<String> keyOf, Closure<List> rowsFor) {
        List<Map> recs = records(ctx, list)
        Map<String, Integer> uses = [:]
        recs.each { Map rec -> String key = keyOf(rec); if (key) uses[key] = (uses[key] ?: 0) + 1 }
        recs.each { Map rec ->
            String key = keyOf(rec)
            if (!key?.trim()) {
                block(ctx, "AMBIGUOUS_MATCH", rec.ref, "${rec.label ?: rec.ref} has no ${keyName}, so it cannot be matched here. Name it at the source.")
            } else if (uses[key] > 1) {
                block(ctx, "AMBIGUOUS_MATCH", rec.ref, "${rec.label ?: rec.ref} shares its ${keyName} with another record in the bundle.")
            } else {
                List rows = rowsFor(rec) ?: []
                if (rows.size() > 1) {
                    block(ctx, "AMBIGUOUS_MATCH", rec.ref, "${rows.size()} records here have the ${keyName} of ${rec.label ?: rec.ref}.")
                } else {
                    matchOne(ctx, rec, rows ? rows[0] : null)
                }
            }
        }
    }

    /** A run a lifecycle owns here is the lifecycle's to change, never a transfer's. */
    private static void ownedRunGate(Map ctx) {
        records(ctx, "runs").each { Map run ->
            def row = ctx.matches[run.ref]
            if (row != null && text(row.ownerReconciliationId)) {
                block(ctx, "TARGET_RUN_OWNED", run.ref, "Run ${run.label} here belongs to a lifecycle; rename one of them first.")
                ctx.matches.remove(run.ref)
                ctx.resolved.remove(run.ref)
                ctx.actions.remove(run.ref)
            }
        }
    }

    /**
     * An updated run keeps its compare scopes by position. A scope the update would delete, or turn into another object's
     * scope, must have no run result or automation pointing at it (RECRES_SCOPE, RECAUT_SCOPE).
     */
    private static void scopeGate(Map ctx) {
        Map scopeSpec = (spec(RUN).children as List<Map>).find { it.pkField == "compareScopeId" }
        records(ctx, "runs").findAll { ctx.actions[it.ref] == "UPDATE" }.each { Map run ->
            List<Map> incoming = (run.children?.RuleSetCompareScope ?: []) as List<Map>
            List existing = ConfigTransferExporter.childRows(ctx.ec, scopeSpec, ctx.matches[run.ref])
            existing.eachWithIndex { scope, int i ->
                boolean deleted = i >= incoming.size()
                boolean repurposed = !deleted && text(incoming[i].fields?.objectType) != text(scope.objectType)
                if (!deleted && !repurposed) return
                String scopeId = scope.compareScopeId as String
                long dependents = finder(ctx.ec, "darpan.reconciliation.ReconciliationRunResult").condition("compareScopeId", scopeId).count() +
                        finder(ctx.ec, "darpan.reconciliation.ReconciliationAutomation").condition("compareScopeId", scopeId).count()
                if (dependents > 0) {
                    block(ctx, "SCOPE_IN_USE", run.ref, "Run ${run.label} would ${deleted ? 'drop' : 'change the object of'} its " +
                            "${scope.objectType ?: ''} compare scope here, which ${dependents} run results or automations still name.")
                }
            }
        }
    }

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
                Map opened = (ctx.secretValues[c.ref] ?: [:]) as Map
                ctx.fills[c.ref] = opened.keySet().sort()
                // With sealed credentials it needs them only when the source had none to send, or could not read some.
                List<String> unreadable = (ctx.unreadable[c.ref] ?: []) as List<String>
                boolean needs = secrets && (!opened || unreadable)
                ctx.actions[c.ref] = needs ? "NEEDS_CREDENTIALS" : CREATE
                if (needs) {
                    warn(ctx, "NEEDS_CREDENTIALS", c.ref, "${c.label} is created without ${(unreadable ?: secrets).join(', ')}; " +
                            "enter them on this instance.")
                }
            } else if (connectionTarget(ctx, kindSpec, choice) == null) {
                // One answer for a missing id, another kind's id and another tenant's id (DAR-BE-135).
                block(ctx, "CONNECTION_NOT_FOUND", c.ref, "Connection ${choice} was not found for ${c.label}.")
            } else {
                ctx.resolved[c.ref] = choice
                ctx.actions[c.ref] = "MAPPED"
                planMappedFills(ctx, kindSpec, c, connectionTarget(ctx, kindSpec, choice))
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

    /** A shared code by its id when this instance has it; otherwise any code of the target's catalog with the same label. */
    private static void matchCode(Map ctx, Map c) {
        if (c.missing) return
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
        Map source = canonical(ec, spec, rec, sourceIdOf(ctx))
        Map existing = canonical(ec, spec, target, targetIdOf(ctx))
        List<Map> changes = changesBetween(existing, source)
        if (changes) {
            ctx.actions[rec.ref] = "UPDATE"
            ctx.changes[rec.ref] = changes
        }
    }

    /**
     * A record with every ref replaced by the identity it has on the target, keys sorted. A blank or absent column reads
     * as this instance's default for it: an older source row holds null where the entity now declares a default, the
     * create fills the default, and both must still read the same or a re-preview never converges.
     */
    static Map canonical(def ec, Map spec, Map rec, Closure<String> idOf) {
        Map out = new TreeMap()
        Map fields = new TreeMap()
        def ed = ec.entity.getEntityDefinition(spec.entityName as String)
        Map recFields = (rec.fields ?: [:]) as Map
        new LinkedHashSet<String>((valueFields(ec, spec) as List<String>) + recFields.keySet().collect { it as String }).each { String k ->
            Object v = text(recFields[k]) ?: defaultOf(ed, k)
            if (v == null) return
            fields[k] = k in JSON_REF_FIELDS ? ConfigTransferExporter.rewriteLookups(v) { Map item -> idOf(item.sourceConfigId) }
                    : k in JSON_PARAM_REF_FIELDS ? ConfigTransferExporter.mapParamRefs(v) { String ref -> idOf(ref) } : text(v)
        }
        out.fields = fields
        out.refs = new TreeMap(((rec.refs ?: [:]) as Map).collectEntries { k, v -> [(k): idOf(v)] })
        if (rec.listRefs != null) out.listRefs = new TreeMap(((Map) rec.listRefs).collectEntries { k, v -> [(k): ((v ?: []) as List).collect { idOf(it) }] })
        if (spec.children) {
            Map children = new TreeMap()
            (spec.children as List<Map>).each { Map child ->
                String name = shortName(child.entityName as String)
                children[name] = ((rec.children?.get(name) ?: []) as List<Map>).collect { canonical(ec, child, it, idOf) }
            }
            out.children = children
        }
        if (spec.links) out.links = ((rec.links ?: []) as List<Map>).collect { canonical(ec, spec.links as Map, it, idOf) }
        return out
    }

    /** A field's declared default as the text a create stores ('Y' -> Y, 30 -> 30); null when it has none. */
    private static String defaultOf(def ed, String field) {
        String raw = ed.isField(field) ? ed.getFieldInfo(field).defaultStr : null
        if (!raw?.trim()) return null
        raw = raw.trim()
        return (raw ==~ /'.*'/ || raw ==~ /".*"/) ? raw.substring(1, raw.length() - 1) : raw
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
            if (fromChildren[k] == toChildren[k]) return
            int from = ((List) fromChildren[k] ?: []).size()
            int to = ((List) toChildren[k] ?: []).size()
            changes << [field: k, from: "${from} rows".toString(), to: (from == to ? "${to} rows changed" : "${to} rows").toString()]
        }
        if (existing.links != incoming.links) {
            int from = ((List) existing.links ?: []).size()
            int to = ((List) incoming.links ?: []).size()
            changes << [field: "AuditStatementCheck", from: "${from} links".toString(),
                        to   : (from == to ? "${to} links changed" : "${to} links").toString()]
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

    // ---------------------------------------------------------------- apply: writers

    /** A top-level record and its children: created with a minted id, updated in place, or left alone when unchanged. */
    private static void writeTree(Map ctx, Map spec, Map rec, String mintPrefix) {
        String action = ctx.actions[rec.ref]
        if (action == CREATE) {
            def row = createRow(ctx, spec, rec, TenantScopedFinder.mintId(ctx.ec, spec.entityName as String, spec.pkField as String, mintPrefix))
            writeChildren(ctx, spec, row, rec, true)
        } else if (action == "UPDATE") {
            def row = updateRow(ctx, spec, rec)
            writeChildren(ctx, spec, row, rec, false)
        }
    }

    private static def createRow(Map ctx, Map spec, Map rec, String id, Map<String, String> secrets = [:]) {
        def value = ctx.ec.entity.makeValue(spec.entityName as String)
        value.setAll(columns(ctx, spec, rec))
        // Plaintext only here, in memory; the entity layer encrypts the encrypt="true" columns on write.
        secrets.each { String field, String secret -> value.set(field, secret) }
        ctx.filledCount = ((ctx.filledCount ?: 0) as int) + secrets.size()
        value.set(spec.pkField as String, id)
        stamp(ctx, spec.entityName as String, value, true)
        value.create()
        ctx.idMap[rec.ref] = id
        return value
    }

    /** The matched target row, re-read and its owner checked in code (a tenant condition beside a full key is dropped). */
    private static def updateRow(Map ctx, Map spec, Map rec) {
        String id = ctx.idMap[rec.ref] as String
        def value = finder(ctx.ec, spec.entityName as String).condition(spec.pkField as String, id).useCache(false).one()
        if (value == null || value.companyUserGroupId != ctx.tenantId) throw new IllegalStateException("${rec.label} changed on this instance during the transfer.")
        value.setAll(columns(ctx, spec, rec))
        stamp(ctx, spec.entityName as String, value, false)
        value.update()
        return value
    }

    /**
     * A record's children under one parent. A child with its own minted id (a rule, a compare scope) keeps its target id
     * by position, so checks and findings that name a rule still find it; any other child is replaced.
     */
    private static void writeChildren(Map ctx, Map parentSpec, def parentRow, Map rec, boolean parentIsNew) {
        def ec = ctx.ec
        ((parentSpec.children ?: []) as List<Map>).each { Map child ->
            List<Map> incoming = (rec.children?.get(shortName(child.entityName as String)) ?: []) as List<Map>
            List existing = parentIsNew ? [] : ConfigTransferExporter.childRows(ec, child, parentRow)
            boolean keepsIds = child.pkField != null
            if (!keepsIds) existing.each { deleteTree(ctx, child, it) }
            incoming.eachWithIndex { Map childRec, int i ->
                boolean creating = !keepsIds || i >= existing.size()
                def value = creating ? ec.entity.makeValue(child.entityName as String) : existing[i]
                value.setAll(columns(ctx, child, childRec))
                (child.parentFields as List<String>).each { String f -> value.set(f, parentRow.get(f)) }
                if (creating && keepsIds) {
                    value.set(child.pkField as String, TenantScopedFinder.mintId(ec, child.entityName as String, child.pkField as String,
                            (child.mintPrefix ?: "") as String))
                }
                stamp(ctx, child.entityName as String, value, creating)
                if (creating) value.create() else value.update()
                if (childRec.ref) ctx.idMap[childRec.ref] = value.get(child.pkField as String) as String
                writeChildren(ctx, child, value, childRec, creating)
            }
            if (keepsIds) existing.drop(incoming.size()).each { deleteTree(ctx, child, it) }
        }
    }

    private static void deleteTree(Map ctx, Map spec, def row) {
        ((spec.children ?: []) as List<Map>).each { Map child ->
            ConfigTransferExporter.childRows(ctx.ec, child, row).each { deleteTree(ctx, child, it) }
        }
        row.delete()
    }

    /** An unchanged run's rules, by position, so the checks that name them resolve. */
    private static void keepRuleIds(Map ctx, Map run) {
        List<String> ruleIds = finder(ctx.ec, "darpan.rule.Rule").condition("ruleSetId", ctx.idMap[run.ref])
                .orderBy(["sequenceNum", "ruleId"]).useCache(false).list()*.ruleId.collect { it as String }
        ((run.children?.Rule ?: []) as List<Map>).eachWithIndex { Map rule, int i -> if (i < ruleIds.size()) ctx.idMap[rule.ref] = ruleIds[i] }
    }

    /** As ConclusionCatalogSupport.create does: a DarpanConclusion enum and the TenantConclusion row that makes it the tenant's. */
    private static void createConclusion(Map ctx, Map c) {
        def ec = ctx.ec
        // Sequenced as ConclusionCatalogSupport.create does, skipping any value an older hand-typed code already holds.
        String enumId
        while (true) {
            enumId = "CONC_" + ec.entity.sequencedIdPrimary("darpan.rule.TenantConclusion", null, null)
            if (ec.entity.find("moqui.basic.Enumeration").condition("enumId", enumId).useCache(false).one() == null) break
        }
        ec.entity.makeValue("moqui.basic.Enumeration").setAll([enumId: enumId, enumTypeId: CONCLUSION_ENUM_TYPE, enumCode: enumId,
                description: text(c.label)]).create()
        ec.entity.makeValue("darpan.rule.TenantConclusion").setAll([enumId: enumId, companyUserGroupId: ctx.tenantId,
                createdByUserId: ctx.userId, createdDate: ctx.now]).create()
        ctx.idMap[c.ref] = enumId
    }

    /** A new statement's id extends its audit's with the kept code, as add#AuditStatement does. */
    private static void writeStatement(Map ctx, Map s) {
        String action = ctx.actions[s.ref]
        if (action == "UPDATE") {
            updateRow(ctx, spec(STATEMENT), s)
        } else if (action == CREATE) {
            String id = "${targetId(ctx, s.refs?.auditId)}_${s.fields?.statementCode}".toString()
            // An id column holds 40 characters; a long code keeps its code and takes a minted id.
            if (id.length() > ID_LENGTH ||
                    finder(ctx.ec, "darpan.audit.AuditStatement").condition("statementId", id).useCache(false).one() != null) {
                id = TenantScopedFinder.mintId(ctx.ec, "darpan.audit.AuditStatement", "statementId")
            }
            createRow(ctx, spec(STATEMENT), s, id)
        }
    }

    /** A check, then its links to the bundle's statements; links to statements of other audits are left alone. */
    private static void writeCheck(Map ctx, Map c) {
        def ec = ctx.ec
        Map checkSpec = spec(CHECK)
        String action = ctx.actions[c.ref]
        if (action == CREATE) {
            // Sequenced as save#AuditCheck does, skipping any value a hand-typed check id already holds.
            createRow(ctx, checkSpec, c, TenantScopedFinder.mintId(ec, checkSpec.entityName as String, "checkId"))
        } else if (action == "UPDATE") {
            def existing = finder(ec, checkSpec.entityName as String).condition("checkId", ctx.idMap[c.ref]).useCache(false).one()
            // A changed run makes the check's automation stale, as save#AuditCheck treats it.
            if (existing != null && existing.savedRunId != (c.refs?.savedRunId ? targetId(ctx, c.refs.savedRunId) : null)) {
                existing.set("automationId", null)
                existing.update()
            }
            updateRow(ctx, checkSpec, c)
        } else {
            return
        }
        String checkId = ctx.idMap[c.ref] as String
        List<String> statementIds = records(ctx, "statements").collect { ctx.idMap[it.ref] as String }.findAll { it }
        if (statementIds) {
            finder(ec, "darpan.audit.AuditStatementCheck").condition("checkId", checkId).condition("statementId", "in", statementIds)
                    .useCache(false).list().each { it.delete() }
        }
        Map linkSpec = checkSpec.links as Map
        ((c.links ?: []) as List<Map>).each { Map link ->
            def value = ec.entity.makeValue(linkSpec.entityName as String)
            value.setAll(columns(ctx, linkSpec, link))
            value.set("checkId", checkId)
            stamp(ctx, linkSpec.entityName as String, value, true)
            value.create()
        }
    }

    /**
     * DAR-BE-160. An automation, its sources and their filters. A new one starts paused, as every audit automation does;
     * an existing one keeps being on or paused. Its next firing follows the schedule written.
     */
    private static void writeAutomation(Map ctx, Map a) {
        String action = ctx.actions[a.ref]
        if (!(action in [CREATE, "UPDATE"])) return
        writeTree(ctx, spec(AUTOMATION), a, "")
        def row = finder(ctx.ec, spec(AUTOMATION).entityName as String).condition("automationId", ctx.idMap[a.ref]).useCache(false).one()
        if (row == null) return
        if (action == CREATE) row.set("isActive", "N")
        row.set("nextScheduledFireTime", AutomationExecutionSupport.resolveNextScheduledFireTime(row, ctx.now as Timestamp, ctx.now as Timestamp))
        row.update()
    }

    /** A run's rules must compile here too, against this instance's rule engine; the transfer fails if they do not. */
    private static void compile(Map ctx, String ruleSetId, String label) {
        def ec = ctx.ec
        if (finder(ec, "darpan.rule.Rule").condition("ruleSetId", ruleSetId).condition("enabled", "Y").useCache(false).count() == 0) return
        Map result
        // The compile gate reads the ACTIVE tenant, and a signed-in user's own tenant always wins over a system tenant.
        // So the compile runs as a scheduled run does: no user, the target tenant published for this thread only.
        // Pushed only when there is a user to step aside from: with none, pushUser fills the empty slot in place and a
        // pop would discard it.
        boolean pushed = ec.user.userId != null
        if (pushed) ec.user.pushUser(null)
        try {
            result = TenantAccessSupport.withSystemTenant(ctx.tenantId as String) {
                RuleEngineSupport.compileRuleSet(ec, ruleSetId, false, true)
            } as Map
        } finally {
            if (pushed) ec.user.popUser()
        }
        if (result?.error) throw new IllegalStateException("Run ${label} does not compile on this instance: ${result.error}")
    }

    /**
     * The record's columns for the target: values as sent, refs as target ids (inside JSON lookups too), lists joined.
     * Only the columns a transfer writes are taken, whatever else the record carries (the plan has already refused it).
     */
    private static Map<String, Object> columns(Map ctx, Map spec, Map rec) {
        Map<String, Object> out = [:]
        Set<String> values = valueFields(ctx.ec, spec) as Set<String>
        Set<String> refs = refColumns(spec)
        Set<String> lists = ((spec.textListRefs ?: [:]) as Map).keySet() as Set<String>
        ((rec.fields ?: [:]) as Map).findAll { k, v -> k in values }.each { k, v ->
            out[k as String] = k in JSON_REF_FIELDS ? ConfigTransferExporter.rewriteLookups(v) { Map item -> targetId(ctx, item.sourceConfigId) }
                    : k in JSON_PARAM_REF_FIELDS ? ConfigTransferExporter.mapParamRefs(v) { String ref -> targetId(ctx, ref) } : text(v)
        }
        ((rec.refs ?: [:]) as Map).findAll { k, v -> k in refs }.each { k, v -> out[k as String] = targetId(ctx, v) }
        ((rec.listRefs ?: [:]) as Map).findAll { k, v -> k in lists }.each { k, v ->
            out[k as String] = ((v ?: []) as List).collect { parseRef(it) ? targetId(ctx, it) : text(it) }.join(",") ?: null
        }
        return out
    }

    /** The target id a ref lands on; a ref the bundle does not resolve fails the transfer. */
    private static String targetId(Map ctx, Object ref) {
        String id = ctx.idMap[ref] as String
        if (id == null) throw new IllegalStateException("${ref} is not in the bundle, so it has no record on this instance.")
        return id
    }

    private static void stamp(Map ctx, String entityName, def value, boolean creating) {
        def ed = ctx.ec.entity.getEntityDefinition(entityName)
        if (ed.isField("companyUserGroupId")) value.set("companyUserGroupId", ctx.tenantId)
        if (creating && ed.isField("createdDate")) value.set("createdDate", ctx.now)
        if (creating && ed.isField("createdByUserId")) value.set("createdByUserId", ctx.userId)
        if (ed.isField("lastUpdatedDate")) value.set("lastUpdatedDate", ctx.now)
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Where a compare source's systemMessageRemoteId comes from on this instance: PRESENT when it already has that remote
     * (instance config, not a tenant's), the connector registry row of the source's own systemEnumId when that row names
     * it (so the apply can make it), and null when neither, which blocks.
     */
    private static Object remoteSource(Map ctx, String systemEnumId, String remoteId) {
        if (TenantScopedFinder.findGlobalUnscoped(ctx.ec, REMOTE_ENTITY, "instance-level endpoint config, not tenant-owned (DAR-ADMIN-006)")
                .condition("systemMessageRemoteId", remoteId).useCache(false).one() != null) return "PRESENT"
        Map connector = systemEnumId ? SourceSystemConnectorSupport.resolve(ctx.ec, systemEnumId) : null
        return text(connector?.remoteId) == remoteId ? connector : null
    }

    private static boolean referenceExists(Map ctx, String entityName, String keyField, String value) {
        Map cache = (Map) (ctx.references ?: (ctx.references = [:]))
        String key = "${entityName}|${value}".toString()
        if (!cache.containsKey(key)) {
            cache[key] = ctx.ec.entity.find(entityName).condition(keyField, value).useCache(true).one() != null
        }
        return cache[key] as boolean
    }

    /** A lookups column as its list of lookups, each sourceConfigId a ref; null when it is anything else. */
    private static List<Map> lookupsOf(Object raw) {
        Object parsed
        try {
            parsed = new groovy.json.JsonSlurper().parseText(text(raw))
        } catch (Exception ignored) {
            return null
        }
        if (!(parsed instanceof List) || ((List) parsed).any { !(it instanceof Map) }) return null
        List<Map> items = (List<Map>) parsed
        return items.every { !text(it.sourceConfigId) || parseRef(it.sourceConfigId) != null } ? items : null
    }

    private static Map line(Map ctx, String kind, Map rec) {
        String action = ctx.actions[rec.ref] as String
        Map out = [kind    : kind, ref: rec.ref, label: rec.label, action: action,
                   targetId: action in [CREATE, "NEEDS_CREDENTIALS"] ? null : ctx.resolved[rec.ref],
                   changes : (ctx.changes[rec.ref] ?: []) as List]
        if (ctx.carriesSecrets && kind in CONNECTION_KINDS) {
            out.secretsFilled = (ctx.fills[rec.ref] ?: []) as List
            out.secretsKept = (ctx.kept[rec.ref] ?: []) as List
        }
        return out
    }

    private static Map<String, Object> finish(Map ctx, List<Map> lines) {
        Map counts = ACTIONS.collectEntries { String a -> [(a): lines.count { it.action == a } as int] }
        counts.CREDENTIALS_FILLED = lines.count { it.secretsFilled } as int
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
            if (k in JSON_PARAM_REF_FIELDS) ConfigTransferExporter.mapParamRefs(v) { String ref -> out << ref; ref }
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
