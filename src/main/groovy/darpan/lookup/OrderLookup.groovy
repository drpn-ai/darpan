package darpan.lookup

import darpan.agent.AgentQuerySupport
import darpan.config.DarpanSettings
import darpan.facade.common.SharedConfigAccessSupport
import darpan.facade.common.TenantAccessSupport

import java.util.regex.Pattern

/**
 * DAR-CLI-008 part 3: follow one order through every connected system. Every "find" read whose value shape matches is tried
 * on every connection of its system; each record found feeds its links (a field's value into the linked read), breadth-first,
 * each (read, connection, value) at most once, within a read cap and a time limit. A link inside one system stays on the
 * connection the record came from; a link to another system searches every connection of that system.
 *
 * run() is pure: the catalog, the connections, the query and the findings come in as deps (liveDeps wires the real ones).
 */
class OrderLookup {

    static final String DEFAULT_SHAPE = "[A-Za-z0-9#_\\-.]{1,64}"
    static final int DEFAULT_MAX_READS = 30
    static final int DEFAULT_TIMEOUT_SECONDS = 60

    /** Each system: its connection config type and fresh-query service (in its own repo; absent = not installed). */
    static final Map<String, Map<String, String>> SYSTEMS = [
            NETSUITE: [configType: SharedConfigAccessSupport.CONFIG_TYPE_NS_AUTH, service: "facade.NetSuiteQueryFacadeServices.run#NsSuiteQlAgentQuery", idParam: "nsAuthConfigId"],
            OMS     : [configType: SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, service: "facade.HotWaxOmsFacadeServices.run#OmsGraphqlAgentQuery", idParam: "omsRestSourceConfigId"],
            SHOPIFY : [configType: SharedConfigAccessSupport.CONFIG_TYPE_SHOPIFY_AUTH, service: "facade.ShopifyFacadeServices.run#ShopifyGraphqlAgentQuery", idParam: "shopifyAuthConfigId"],
    ].asImmutable()

    /** Test seam: (service name, parameters) -> the service's out map, in place of calling it. */
    static Closure liveQueryHook = null
    static void resetForTests() { liveQueryHook = null }

    /**
     * deps: reads (List<Map>), links (List<Map>), connections (Closure<List<Map>> by systemCode, each [id, label]),
     * query (Closure<Map>(read, connection, value) -> [records: List<Map>] or [error: String]),
     * findings (Closure<List<Map>>(Collection<String> ids)), maxReads (int), deadlineMillis (long), now (Closure<Long>).
     */
    static Map run(Map deps, String value, boolean all) {
        String given = value?.trim() ?: ""
        List<Map> reads = (List<Map>) deps.reads
        Map<String, Map> readById = reads.collectEntries { [(it.readId as String): it] }
        Map<String, List<Map>> linksFrom = ((List<Map>) deps.links).groupBy { it.fromReadId as String }
        Closure<List<Map>> connections = (Closure<List<Map>>) deps.connections
        Map<String, List<Map>> connectionCache = [:]
        int maxReads = (deps.maxReads ?: 30) as int
        long deadline = (deps.deadlineMillis ?: Long.MAX_VALUE) as long
        Closure<Long> now = (Closure<Long>) (deps.now ?: { System.currentTimeMillis() })

        List<Map> queue = []
        reads.findAll { it.isFind == "Y" && shapeFits(it, given) }.each { queue << [read: it, value: given, foundBy: [value: given], connectionId: null] }

        List<Map> records = [], skipped = []
        Set<String> done = new HashSet<>()
        Set<String> idsSeen = new LinkedHashSet<>()
        int readsUsed = 0
        boolean capped = false
        while (!queue.isEmpty()) {
            Map task = queue.remove(0)
            Map r = (Map) task.read
            List<Map> conns = connectionCache.computeIfAbsent(r.systemCode as String) { String s -> (List<Map>) (connections.call(s) ?: []) }
            if (task.connectionId != null) conns = conns.findAll { it.id == task.connectionId }
            for (Map c : conns) {
                String key = "${r.readId}|${c.id}|${task.value}".toString()
                if (!done.add(key)) continue
                if (readsUsed >= maxReads) { capped = true; skipped << [readId: r.readId, connectionId: c.id, reason: "read cap (${maxReads})".toString()]; continue }
                if (now.call() >= deadline) { capped = true; skipped << [readId: r.readId, connectionId: c.id, reason: "time limit"]; continue }
                readsUsed++
                idsSeen << (task.value as String)
                Map answer = (Map) ((Closure) deps.query).call(r, c, task.value as String) ?: [:]
                if (answer.error) { skipped << [readId: r.readId, connectionId: c.id, reason: answer.error.toString()]; continue }
                ((List<Map>) (answer.records ?: [])).each { Map record ->
                    records << [system: r.systemCode, connectionId: c.id, connectionLabel: c.label, recordKind: r.recordKind,
                                readId: r.readId, foundBy: task.foundBy, record: project(record, fieldsOf(r), all)]
                    // The record's own ids (its key field and every field a link reads) match findings too, followed or not.
                    if (r.keyField) idsSeen.addAll(valuesAt(record, r.keyField as String))
                    (linksFrom.get(r.readId as String) ?: []).each { Map l -> idsSeen.addAll(valuesAt(record, l.fromField as String)) }
                    (linksFrom.get(r.readId as String) ?: []).each { Map l ->
                        Map to = readById.get(l.toReadId as String)
                        if (to == null) return
                        valuesAt(record, l.fromField as String).each { String v ->
                            if (!shapeFits(to, v)) return
                            queue << [read: to, value: v, foundBy: [value: v, linkId: l.linkId, fromReadId: r.readId],
                                      connectionId: to.systemCode == r.systemCode ? c.id : null]
                        }
                    }
                }
            }
        }
        List<Map> findings = idsSeen ? (List<Map>) (((Closure) deps.findings)?.call(idsSeen) ?: []) : []
        return [value: given, records: records, findings: findings, skipped: skipped, readsUsed: readsUsed, capped: capped]
    }

    // ---------------------------------------------------------------- live

    /** The lookup for the active tenant, wired to its catalog, connections, fresh-query services and open findings. */
    static Map runLive(def ec, String value, boolean all) {
        String tenantId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!tenantId) { ec.message.addError("No active tenant."); return null }
        if (!value?.trim()) { ec.message.addError("Give the id to look up."); return null }
        int maxReads = setting("darpan.lookup.maxReads", DEFAULT_MAX_READS)
        int timeoutSeconds = setting("darpan.lookup.timeoutSeconds", DEFAULT_TIMEOUT_SECONDS)
        long start = System.currentTimeMillis()
        Map deps = [reads      : LookupCatalog.reads(ec, tenantId), links: LookupCatalog.links(ec, tenantId),
                    connections: { String system -> connectionsOf(ec, system) },
                    query      : { Map read, Map connection, String v -> liveQuery(ec, read, connection, v) },
                    findings   : { Collection<String> ids -> openFindings(ec, tenantId, ids) },
                    maxReads   : maxReads, deadlineMillis: start + timeoutSeconds * 1000L, now: { System.currentTimeMillis() }]
        return run(deps, value, all)
    }

    private static int setting(String key, int fallback) {
        try { return DarpanSettings.intValue(key) } catch (Exception ignored) { return fallback }
    }

    /** The tenant's active connections of one system, [id, label]. */
    static List<Map> connectionsOf(def ec, String system) {
        Map<String, String> sys = SYSTEMS.get(system)
        if (sys == null) return []
        Map<String, String> type = SharedConfigAccessSupport.configType(sys.configType)
        if (type == null) return []
        return SharedConfigAccessSupport.listAccessibleConfigRows(ec, sys.configType)
                .findAll { row -> "N" != row.get("isActive")?.toString()?.toUpperCase() }
                .collect { row -> [id: row.get(type.pkField) as String, label: (row.get("description") ?: row.get(type.pkField)) as String] }
    }

    /** One read on one connection through the system's read-only fresh-query service: [records] or [error]. */
    static Map liveQuery(def ec, Map read, Map connection, String value) {
        Map<String, String> sys = SYSTEMS.get(read.systemCode as String)
        if (sys == null) return [error: "unknown system ${read.systemCode}".toString()]
        String passed = read.valueFormat ? String.format(read.valueFormat as String, value) : value
        Map params = [(sys.idParam): connection.id]
        if (read.systemCode == "NETSUITE") {
            try { params.sql = renderSuiteQl(read.queryTemplate as String, passed, read.valueShape as String) }
            catch (IllegalArgumentException e) { return [error: e.message] }
            params.limit = 50
        } else {
            params.document = read.queryTemplate
            params.variables = [value: passed]
        }
        Map out
        if (liveQueryHook != null) {
            out = (Map) liveQueryHook.call(sys.service, params)
        } else {
            if (!ec.service.isServiceDefined(sys.service)) return [error: "not installed"]
            out = (Map) ec.service.sync().name(sys.service).parameters(params).call() ?: [:]
        }
        List errors = ((List) (out.errors ?: [])) + (ec.message.hasError() ? ec.message.errors : [])
        ec.message.clearErrors()   // one read's refusal must not stop the next (Moqui runs no service while errors stand)
        if (out.ok == false || errors) {
            String why = out.code ? "${out.code}${errors ? ': ' + errors[0] : ''}" : (errors ? errors[0].toString() : "the read failed")
            return [error: why.toString()]
        }
        List<Map> records = read.systemCode == "NETSUITE" ? (List<Map>) (out.rows ?: []) : recordsAt(out.data, read.recordsAt as String)
        return [records: records]
    }

    /** The maps at a GraphQL path such as orders.edges[*].node ([*] walks a list). */
    static List<Map> recordsAt(Object data, String path) {
        List<Object> current = [data]
        for (String token : (path ?: "").tokenize(".")) {
            boolean each = token.endsWith("[*]")
            String key = each ? token.substring(0, token.length() - 3) : token
            List<Object> next = []
            current.each { Object n ->
                Object v = n instanceof Map ? ((Map) n).get(key) : null
                if (v == null) return
                if (each && v instanceof Collection) next.addAll((Collection) v) else next << v
            }
            current = next
        }
        return current.collectMany { it instanceof Collection ? (Collection) it : [it] }.findAll { it instanceof Map } as List<Map>
    }

    /** The tenant's open findings whose record key is any id seen, at most 50. */
    static List<Map> openFindings(def ec, String tenantId, Collection<String> ids) {
        if (!ids) return []
        return ec.entity.find("darpan.audit.AuditFinding").condition("companyUserGroupId", tenantId)
                .condition("recordKey", "in", new ArrayList<String>(ids)).condition("stateEnumId", "AUDFS_OPEN")
                .orderBy("-lastSeenDate").limit(50).useCache(false).list()
                .collect { [findingId: it.findingId, recordKey: it.recordKey, headline: it.headline, period: it.period] }
    }

    /** A tenant template must be read-only for its system: null when it is, else why not. */
    static String templateRefusal(String systemCode, String template) {
        if (!template?.trim()) return "The read needs a query template."
        if (systemCode == "NETSUITE") return AgentQuerySupport.checkSuiteQl(template.replace("{value}", "'1'"))
        if (systemCode in ["OMS", "SHOPIFY"]) return AgentQuerySupport.checkGraphqlQuery(template)
        return "Unknown system ${systemCode}; NETSUITE, OMS or SHOPIFY.".toString()
    }

    /** SuiteQL with {value} replaced by the value, quoted, once it fits the read's shape. Throws IllegalArgumentException. */
    static String renderSuiteQl(String template, String value, String shape) {
        String v = value ?: ""
        if (!Pattern.compile(shape ?: DEFAULT_SHAPE).matcher(v).matches()) throw new IllegalArgumentException("'${v}' is not a value this read takes.".toString())
        return template.replace("{value}", "'" + v.replace("'", "''") + "'")
    }

    /** The record with only the read's default fields (dotted paths keep their nesting), or whole with all. */
    static Map project(Map record, List<String> fields, boolean all) {
        if (all || !fields) return record
        Map out = [:]
        fields.each { String path ->
            List<String> parts = path.tokenize(".")
            Object v = record
            for (String p : parts) { v = v instanceof Map ? ((Map) v).get(p) : null; if (v == null) break }
            if (v == null) return
            Map at = out
            parts.eachWithIndex { String p, int i -> if (i == parts.size() - 1) at.put(p, v) else at = (Map) at.computeIfAbsent(p) { [:] } }
        }
        return out
    }

    static boolean shapeFits(Map read, String value) {
        return value && Pattern.compile((read.valueShape as String) ?: DEFAULT_SHAPE).matcher(value).matches()
    }

    private static List<String> fieldsOf(Map read) {
        return ((read.fieldsDefault as String) ?: "").split(",").collect { it.trim() }.findAll { it }
    }

    /** The values at a dotted path; a list along the way yields each element's value. */
    static List<String> valuesAt(Object node, String path) {
        List<Object> current = [node]
        for (String p : (path ?: "").tokenize(".")) {
            List<Object> next = []
            current.each { Object n ->
                Object v = n instanceof Map ? ((Map) n).get(p) : null
                if (v instanceof Collection) next.addAll((Collection) v) else if (v != null) next << v
            }
            current = next
        }
        return current.findAll { it != null && !(it instanceof Map) && !(it instanceof Collection) && it.toString().trim() }.collect { it.toString().trim() }.unique()
    }
}
