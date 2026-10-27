package darpan.admin.transfer

import darpan.facade.common.SharedConfigAccessSupport
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileDynamic

import static darpan.admin.transfer.TransferSpecs.*

/**
 * DAR-ADMIN-006. The source side of a config transfer: what a tenant can send (catalog) and the bundle itself.
 *
 * <p>Every read names the tenant explicitly; a child row is read only through a parent already proven the tenant's.
 * An encrypted column is never selected, so nothing is decrypted and no secret reaches the bundle. Every id that points
 * at another record is written as a ref through a namer; the bundle builder's namer also records what was reached, which
 * is how an audit pulls in its runs, a run its queries and connections, and so on. The importer runs {@link #record} on
 * the TARGET's rows too, with a namer that keeps target ids, so both sides of a diff come out of the same code.</p>
 */
@CompileDynamic
class ConfigTransferExporter {

    /** The tenant's audits, runs and connections, for the pick and map steps. Null (with an error) for an unknown tenant. */
    static Map<String, Object> catalog(def ec, String tenantId) {
        if (!requireTenant(ec, tenantId)) return null
        List audits = finder(ec, "darpan.audit.Audit").condition("companyUserGroupId", tenantId).orderBy("auditCode")
                .useCache(false).list()
        List statements = finder(ec, "darpan.audit.AuditStatement").condition("companyUserGroupId", tenantId).useCache(false).list()
        List links = finder(ec, "darpan.audit.AuditStatementCheck").condition("companyUserGroupId", tenantId).useCache(false).list()
        List checks = finder(ec, "darpan.audit.AuditCheck").condition("companyUserGroupId", tenantId).useCache(false).list()
        Map<String, String> auditIdByStatement = statements.collectEntries { [(it.statementId as String): it.auditId as String] }
        Map<String, String> codeByAuditId = audits.collectEntries { [(it.auditId as String): it.auditCode as String] }
        Map<String, String> runByCheck = checks.collectEntries { [(it.checkId as String): it.savedRunId as String] }

        Map<String, Set<String>> auditCodesByRun = [:].withDefault { new TreeSet<String>() }
        links.each { link ->
            String runId = runByCheck[link.checkId as String]
            String auditCode = codeByAuditId[auditIdByStatement[link.statementId as String]]
            if (runId && auditCode) auditCodesByRun[runId].add(auditCode)
        }

        List<Map> auditList = audits.collect { audit ->
            Set<String> statementIds = statements.findAll { it.auditId == audit.auditId }*.statementId.collect { it as String } as Set
            [auditId       : audit.auditId, auditCode: audit.auditCode, auditName: audit.auditName,
             statementCount: statementIds.size(),
             checkCount    : links.findAll { (it.statementId as String) in statementIds }*.checkId.unique().size()] as Map
        }
        // A run a lifecycle owns stays with the lifecycle, so it is not offered.
        List<Map> runList = finder(ec, "darpan.rule.RuleSet").condition("companyUserGroupId", tenantId)
                .condition("ownerReconciliationId", null).orderBy("ruleSetName").useCache(false).list().collect { rs ->
            [savedRunId: rs.ruleSetId, ruleSetName: rs.ruleSetName, auditCodes: (auditCodesByRun.get(rs.ruleSetId as String) ?: []) as List] as Map
        }

        List<Map> connections = []
        CONNECTION_KINDS.each { String kind ->
            Map spec = spec(kind)
            if (!isEntityAvailable(ec, spec.entityName as String)) return
            finder(ec, spec.entityName as String).condition("companyUserGroupId", tenantId).selectFields([spec.pkField, "description"])
                    .useCache(false).list().each { row ->
                connections << connectionEntry(kind, row.get(spec.pkField) as String, row.description as String, false)
            }
            SharedConfigAccessSupport.listSharedConfigIdsForTenant(ec, spec.grantType as String, tenantId).each { String id ->
                def row = finder(ec, spec.entityName as String).condition(spec.pkField as String, id)
                        .selectFields([spec.pkField, "description"]).useCache(false).one()
                if (row != null) connections << connectionEntry(kind, id, row.description as String, true)
            }
        }
        return [audits: auditList, runs: runList, connections: sortConnections(connections)] as Map<String, Object>
    }

    /**
     * The bundle: the picked audits with everything they reach, the picked runs, and the picked connections. Null (with
     * an error) when something picked is not the tenant's; another tenant's audit reads exactly like an unknown one.
     */
    static Map<String, Object> bundle(def ec, String tenantId, List auditCodes, List savedRunIds, List connectionRefs,
                                      String sourceInstanceLabel) {
        if (!requireTenant(ec, tenantId)) return null
        Map<String, Set<String>> reached = [:].withDefault { new LinkedHashSet<String>() }
        Closure<String> namer = { String kind, Object id ->
            String sourceId = text(id)
            reached[kind].add(sourceId)
            return TransferSpecs.ref(kind, sourceId)
        }

        List audits = ((auditCodes ?: []) as List).collect { Object code ->
            def audit = finder(ec, "darpan.audit.Audit").condition("companyUserGroupId", tenantId)
                    .condition("auditCode", text(code)).useCache(false).list().find()
            if (audit == null) ec.message.addError("Audit '${code}' was not found.")
            return audit
        }
        if (ec.message.hasError()) return null
        audits = audits.unique { it.auditId }.sort { it.auditCode }
        List<String> auditIds = audits*.auditId.collect { it as String }
        Map<String, Integer> auditOrder = auditIds.withIndex().collectEntries { String id, int i -> [(id): i] }

        List statements = auditIds ? finder(ec, "darpan.audit.AuditStatement").condition("companyUserGroupId", tenantId)
                .condition("auditId", "in", auditIds).useCache(false).list()
                .sort { a, b -> auditOrder[a.auditId as String] <=> auditOrder[b.auditId as String] ?:
                        (a.sequenceNum as Long) <=> (b.sequenceNum as Long) ?: a.statementCode <=> b.statementCode } : []
        List<String> statementIds = statements*.statementId.collect { it as String }
        List links = statementIds ? finder(ec, "darpan.audit.AuditStatementCheck").condition("companyUserGroupId", tenantId)
                .condition("statementId", "in", statementIds).useCache(false).list() : []
        List<String> checkIds = links*.checkId.collect { it as String }.unique()
        List checks = checkIds ? finder(ec, "darpan.audit.AuditCheck").condition("companyUserGroupId", tenantId)
                .condition("checkId", "in", checkIds).orderBy("checkCode").useCache(false).list() : []

        Set<String> runIds = new LinkedHashSet<String>(checks*.savedRunId.findAll { it }.collect { it as String })
        Set<String> pickedRunIds = ((savedRunIds ?: []) as List).collect { text(it) }.findAll { it } as Set<String>
        runIds.addAll(pickedRunIds)
        List runs = runIds ? finder(ec, "darpan.rule.RuleSet").condition("companyUserGroupId", tenantId)
                .condition("ruleSetId", "in", runIds).orderBy(["ruleSetName", "ruleSetId"]).useCache(false).list() : []
        (runIds - (runs*.ruleSetId.collect { it as String } as Set)).each { String id ->
            ec.message.addError("Run '${id}' was not found.")
        }
        if (ec.message.hasError()) return null

        List<Map> auditRecords = audits.collect { labelled(record(ec, spec(AUDIT), it, namer), it.auditCode) }
        List<Map> statementRecords = statements.collect {
            labelled(record(ec, spec(STATEMENT), it, namer), "${codeOf(audits, it.auditId)} ${it.statementCode}")
        }
        List<Map> checkRecords = checks.collect { check ->
            Map rec = labelled(record(ec, spec(CHECK), check, namer), check.label ?: check.checkCode)
            rec.links = linkRecords(ec, check.checkId as String, links.findAll { it.checkId == check.checkId }, statementIds, namer)
            return rec
        }
        List<Map> runRecords = runs.collect { labelled(record(ec, spec(RUN), it, namer), it.ruleSetName ?: it.ruleSetId) }

        List<Map> queryRecords = []
        Set<String> queryIds = reached[NETSUITE_SUITEQL]
        if (queryIds) {
            List queries = finder(ec, spec(NETSUITE_SUITEQL).entityName as String).condition("companyUserGroupId", tenantId)
                    .condition("nsSuiteQlSourceQueryId", "in", queryIds).selectFields(readableFields(ec, spec(NETSUITE_SUITEQL).entityName as String))
                    .orderBy(["description", "nsSuiteQlSourceQueryId"]).useCache(false).list()
            (queryIds - (queries*.nsSuiteQlSourceQueryId.collect { it as String } as Set)).each { String id ->
                ec.message.addError("A run uses SuiteQL query '${id}', which tenant ${tenantId} does not own.")
            }
            queryRecords = queries.collect { labelled(record(ec, spec(NETSUITE_SUITEQL), it, namer), it.description ?: it.nsSuiteQlSourceQueryId) }
        }

        List<Map> codeRecords = (reached[CONC] as List<String>).collect { String enumId -> conclusionRecord(ec, tenantId, enumId) }
                .findAll { it != null }.sort { a, b -> a.label <=> b.label ?: a.ref <=> b.ref }

        ((connectionRefs ?: []) as List).each { Object picked ->
            List<String> parsed = parseRef(picked)
            if (parsed == null || !(parsed[0] in CONNECTION_KINDS)) ec.message.addError("Connection '${picked}' was not found.")
            else reached[parsed[0]].add(parsed[1])
        }
        List<Map> connectionRecords = []
        // A Restlet names its NetSuite auth, so Restlets are read first and their auths join the reach.
        [NETSUITE_RESTLET, NETSUITE_AUTH, SHOPIFY_AUTH, HOTWAX_OMS_REST].each { String kind ->
            new ArrayList<String>(reached[kind]).each { String id ->
                Map rec = connectionRecord(ec, tenantId, kind, id, namer)
                if (rec == null) ec.message.addError("Connection '${TransferSpecs.ref(kind, id)}' was not found for tenant ${tenantId}.")
                else connectionRecords << rec
            }
        }
        if (ec.message.hasError()) return null

        return [bundleVersion  : BUNDLE_VERSION,
                source         : [instanceLabel: sourceInstanceLabel, darpanVersion: darpanVersion(ec), tenantUserGroupId: tenantId],
                connections    : sortConnections(connectionRecords),
                queries        : queryRecords,
                conclusionCodes: codeRecords,
                runs           : runRecords,
                audits         : auditRecords,
                statements     : statementRecords,
                checks         : checkRecords] as Map<String, Object>
    }

    /**
     * One row as a bundle record: {ref (kinds and ruled children), fields, refs, children}. namer(kind, id) writes each
     * id-bearing column; the polymorphic compare-source id takes its kind from the source's connector.
     */
    static Map<String, Object> record(def ec, Map spec, def row, Closure<String> namer) {
        Map<String, Object> out = [:]
        if (spec.kind && spec.pkField) out.ref = namer(spec.kind as String, row.get(spec.pkField as String))
        Map<String, Object> fields = [:]
        valueFields(ec, spec).each { String f ->
            Object value = row.get(f)
            fields[f] = f in JSON_REF_FIELDS ? rewriteLookups(value) { Map item -> namer(lookupKind(ec, item.systemEnumId) ?: "UNKNOWN", item.sourceConfigId) }
                    : text(value)
        }
        out.fields = fields
        Map<String, String> refs = [:]
        ((spec.refFields ?: [:]) as Map<String, String>).each { String f, String kind ->
            Object value = row.get(f)
            if (text(value)) refs[f] = namer(kind, value)
        }
        if (spec.polymorphicRef && text(row.get(spec.polymorphicRef as String))) {
            refs[spec.polymorphicRef as String] = namer(sourceKind(ec, row), row.get(spec.polymorphicRef as String))
        }
        out.refs = refs
        if (spec.children) {
            Map<String, List> children = [:]
            (spec.children as List<Map>).each { Map child ->
                children[shortName(child.entityName as String)] = childRows(ec, child, row).collect { record(ec, child, it, namer) }
            }
            out.children = children
        }
        return out
    }

    /** A child spec's rows under one parent row, in the spec's order, without encrypted columns. */
    static List childRows(def ec, Map child, def parentRow) {
        def query = finder(ec, child.entityName as String).selectFields(readableFields(ec, child.entityName as String))
        (child.parentFields as List<String>).each { String f -> query.condition(f, parentRow.get(f)) }
        return query.orderBy(child.orderBy as List<String>).useCache(false).list()
    }

    /** A check's links to the given statements, in statement order: refs for the statement, the rules and the codes. */
    static List<Map> linkRecords(def ec, String checkId, List links, List<String> statementOrder, Closure<String> namer) {
        Map linkSpec = spec(CHECK).links as Map
        return links.findAll { (it.statementId as String) in statementOrder }
                .sort { statementOrder.indexOf(it.statementId as String) }
                .collect { link ->
                    Map rec = record(ec, linkSpec, link, namer)
                    Map<String, List<String>> listRefs = [:]
                    (linkSpec.textListRefs as Map<String, String>).each { String f, String kind ->
                        listRefs[f] = splitList(link.get(f)).collect { String id ->
                            // A code that is no conclusion enum (UNEXPLAINED) is a literal, not a record.
                            kind == CONC && !isConclusionEnum(ec, id) ? id : namer(kind, id)
                        }
                    }
                    rec.listRefs = listRefs
                    return rec
                }
    }

    /** The kind of a compare source's sourceConfigId: its type when that is a kind, else its connector's config entity. */
    static String sourceKind(def ec, def source) {
        String type = text(source.get("sourceConfigType"))
        if (type in [NETSUITE_AUTH, SHOPIFY_AUTH, HOTWAX_OMS_REST, NETSUITE_SUITEQL]) return type
        return lookupKind(ec, source.get("systemEnumId")) ?: (type ?: "UNKNOWN")
    }

    static String lookupKind(def ec, Object systemEnumId) {
        String entityName = text(systemEnumId) ? SourceSystemConnectorSupport.resolve(ec, text(systemEnumId))?.configEntityName : null
        return entityName ? specForEntity(entityName as String)?.kind : null
    }

    /** A JSON list of lookups with each sourceConfigId mapped; text that is no such list is kept as it is. */
    static String rewriteLookups(Object raw, Closure<String> mapId) {
        String json = text(raw)
        if (!json?.trim()) return json
        Object parsed
        try {
            parsed = new JsonSlurper().parseText(json)
        } catch (Exception ignored) {
            return json
        }
        if (!(parsed instanceof List)) return json
        return JsonOutput.toJson(((List) parsed).collect { Object item ->
            if (!(item instanceof Map) || !text(((Map) item).sourceConfigId)) return item
            Map copy = new LinkedHashMap((Map) item)
            copy.sourceConfigId = mapId(copy)
            return copy
        })
    }

    static List<String> splitList(Object raw) {
        return (text(raw) ?: "").split(",").collect { it.trim() }.findAll { it } as List<String>
    }

    static boolean isConclusionEnum(def ec, String enumId) {
        def e = enumId ? ec.entity.find("moqui.basic.Enumeration").condition("enumId", enumId).useCache(true).one() : null
        return e != null && e.enumTypeId == CONCLUSION_ENUM_TYPE
    }

    static String darpanVersion(def ec) {
        return ec.factory.getComponentInfoList().find { it.name == "darpan" }?.version as String
    }

    static boolean requireTenant(def ec, String tenantId) {
        def group = tenantId ? ec.entity.find("moqui.security.UserGroup").condition("userGroupId", tenantId).useCache(false).one() : null
        if (group == null || group.groupTypeEnumId != TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID) {
            ec.message.addError("Tenant ${tenantId ?: '(missing)'} was not found.")
            return false
        }
        return true
    }

    private static Map conclusionRecord(def ec, String tenantId, String enumId) {
        def e = ec.entity.find("moqui.basic.Enumeration").condition("enumId", enumId).useCache(false).one()
        if (e == null || e.enumTypeId != CONCLUSION_ENUM_TYPE) return null
        def owner = finder(ec, "darpan.rule.TenantConclusion").condition("enumId", enumId).useCache(false).list().find()
        boolean own = owner != null && owner.companyUserGroupId == tenantId
        Map rec = [ref: TransferSpecs.ref(CONC, enumId), kind: CONC, label: e.description ?: enumId, tenantOwned: own]
        // A shared code is looked up by its id first on the target; a tenant's own code only by its label.
        if (!own) rec.enumId = enumId
        return rec
    }

    /** An owned connection with its non-secret columns; a granted one by name only; null when the tenant has neither. */
    private static Map connectionRecord(def ec, String tenantId, String kind, String id, Closure<String> namer) {
        Map spec = spec(kind)
        String entityName = spec.entityName as String
        if (!isEntityAvailable(ec, entityName)) return null
        def owned = finder(ec, entityName).condition("companyUserGroupId", tenantId).condition(spec.pkField as String, id)
                .selectFields(readableFields(ec, entityName)).useCache(false).list().find()
        if (owned != null) {
            Map rec = record(ec, spec, owned, namer)
            return [ref: rec.ref, kind: kind, label: owned.description ?: id, description: owned.description, granted: false,
                    fields: rec.fields, refs: rec.refs, secretFields: secretFields(ec, entityName)]
        }
        if (!SharedConfigAccessSupport.isSharedWithTenant(ec, spec.grantType as String, id, tenantId)) return null
        def shared = finder(ec, entityName).condition(spec.pkField as String, id).selectFields([spec.pkField, "description"])
                .useCache(false).one()
        if (shared == null) return null
        return [ref: namer(kind, id), kind: kind, label: shared.description ?: id, description: shared.description, granted: true]
    }

    private static Map connectionEntry(String kind, String id, String description, boolean granted) {
        return [ref: TransferSpecs.ref(kind, id), kind: kind, id: id, description: description, granted: granted]
    }

    private static List<Map> sortConnections(List<Map> connections) {
        return connections.unique { it.ref }.sort { a, b ->
            CONNECTION_KINDS.indexOf(a.kind) <=> CONNECTION_KINDS.indexOf(b.kind) ?:
                    (a.description ?: "") <=> (b.description ?: "") ?: a.ref <=> b.ref
        }
    }

    private static Map labelled(Map rec, Object label) {
        return [ref: rec.ref, label: text(label)] + rec
    }

    private static String codeOf(List audits, Object auditId) {
        return audits.find { it.auditId == auditId }?.auditCode
    }
}
