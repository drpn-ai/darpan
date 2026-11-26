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
    /** Every value bound into SuiteQL must also fit this, whatever a tenant read's own shape allows: no quote, space,
     *  semicolon, comment marker or other SQL metacharacter can reach the query. */
    static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9#_.:@/]+(?:-[A-Za-z0-9#_.:@/]+)*")
    static final int MAX_VALUE_LENGTH = 64
    static final int MAX_TEMPLATE_LENGTH = 8000
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

        List<Map> records = [], skipped = []
        Set<String> skippedOnce = new HashSet<>()
        Closure skipOnce = { Map r, String reason -> if (skippedOnce.add("${r.readId}|${reason}".toString())) skipped << [readId: r.readId, connectionId: null, reason: reason] }
        // A broken row (a bad shape regex) is skipped, never failing the lookup.
        Closure<Boolean> fits = { Map r, String v ->
            try { return shapeFits(r, v) } catch (IllegalArgumentException e) { skipOnce(r, "broken read: ${e.message}".toString()); return false }
        }

        List<Map> queue = []
        reads.findAll { it.isFind == "Y" && fits(it, given) }.each { queue << [read: it, value: given, foundBy: [value: given], connectionId: null] }

        Set<String> done = new HashSet<>(), listed = new HashSet<>()
        Set<String> idsSeen = new LinkedHashSet<>()
        int readsUsed = 0
        boolean capped = false
        while (!queue.isEmpty()) {
            Map task = queue.remove(0)
            Map r = (Map) task.read
            List<Map> conns
            try {
                conns = connectionCache.containsKey(r.systemCode) ? connectionCache.get(r.systemCode) : (List<Map>) connections.call(r.systemCode as String)
                connectionCache.put(r.systemCode as String, conns)
            } catch (Exception e) {
                skipOnce(r, "broken read: ${e.message ?: e.class.simpleName}".toString()); continue
            }
            if (conns == null) { skipOnce(r, "not installed"); continue }
            if (conns.isEmpty()) { skipOnce(r, "no connection"); continue }
            if (task.connectionId != null) conns = conns.findAll { it.id == task.connectionId }
            for (Map c : conns) {
                String key = "${r.readId}|${c.id}|${task.value}".toString()
                if (!done.add(key)) continue
                if (readsUsed >= maxReads) { capped = true; skipped << [readId: r.readId, connectionId: c.id, reason: "read cap (${maxReads})".toString()]; continue }
                if (now.call() >= deadline) { capped = true; skipped << [readId: r.readId, connectionId: c.id, reason: "time limit"]; continue }
                readsUsed++
                idsSeen << (task.value as String)
                Map answer
                try {
                    answer = (Map) ((Closure) deps.query).call(r, c, task.value as String) ?: [:]
                } catch (Exception e) {
                    skipped << [readId: r.readId, connectionId: c.id, reason: "broken read: ${e.message ?: e.class.simpleName}".toString()]; continue
                }
                if (answer.error) { skipped << [readId: r.readId, connectionId: c.id, reason: answer.error.toString()]; continue }
                ((List<Map>) (answer.records ?: [])).each { Map record ->
                    Map shown = project(record, fieldsOf(r), all)
                    // The same record reached by two paths (HotWax -> Shopify -> HotWax) is listed once; its links still run once each.
                    if (listed.add("${r.systemCode}|${c.id}|${r.recordKind}|${groovy.json.JsonOutput.toJson(record)}".toString())) {
                        records << [system: r.systemCode, connectionId: c.id, connectionLabel: c.label, recordKind: r.recordKind,
                                    readId: r.readId, foundBy: task.foundBy, record: shown]
                    }
                    // The record's own ids (its key field and every field a link reads) match findings too, followed or not.
                    if (r.keyField) idsSeen.addAll(valuesAt(record, r.keyField as String))
                    (linksFrom.get(r.readId as String) ?: []).each { Map l -> idsSeen.addAll(valuesAt(record, l.fromField as String)) }
                    (linksFrom.get(r.readId as String) ?: []).each { Map l ->
                        Map to = readById.get(l.toReadId as String)
                        if (to == null) return
                        valuesAt(record, l.fromField as String).each { String v ->
                            if (!fits(to, v)) return
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
        // Every read is a fresh query on a connected system, which the systems allow user, editor and admin roles (run access).
        if (!TenantAccessSupport.requireActiveTenantRunAccess(ec, "Looking up an order queries your connected systems, which needs a user, editor or admin role in this tenant.")) return null
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
        if (type == null || !ec.entity.isEntityDefined(type.entityName)) return null   // the system's repo is not installed
        String tenantId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        // Owned connections only: the fresh-query services refuse a connection shared from another tenant (R25).
        return SharedConfigAccessSupport.listAccessibleConfigRows(ec, sys.configType)
                .findAll { row -> "N" != row.get("isActive")?.toString()?.toUpperCase() && row.get("companyUserGroupId") == tenantId }
                .collect { row -> [id: row.get(type.pkField) as String, label: (row.get("description") ?: row.get(type.pkField)) as String] }
    }

    /** One read on one connection through the system's read-only fresh-query service: [records] or [error]. */
    static Map liveQuery(def ec, Map read, Map connection, String value) {
        Map<String, String> sys = SYSTEMS.get(read.systemCode as String)
        if (sys == null) return [error: "unknown system ${read.systemCode}".toString()]
        String passed
        try { passed = formatValue(read.valueFormat as String, value) } catch (IllegalArgumentException e) { return [error: e.message] }
        Map params = [(sys.idParam): connection.id]
        if (read.systemCode == "NETSUITE") {
            try { params.sql = renderSuiteQl(read.queryTemplate as String, passed, null) }   // the read's shape was checked on the value as given
            catch (IllegalArgumentException e) { return [error: e.message] }
            String refused = AgentQuerySupport.checkSuiteQl(params.sql as String)
            if (refused) return [error: refused]
            params.limit = 50
        } else {
            if (!read.queryTemplate || !read.recordsAt) return [error: "broken read: a GraphQL read needs a template and recordsAt"]
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
        // An order's findings are keyed by the order (M1210830) or by one of its lines (M1210830/02, SO7371002/01).
        def ecf = ec.entity.conditionFactory
        List<String> keys = new ArrayList<String>(ids).take(60)
        List conds = [ecf.makeCondition("recordKey", org.moqui.entity.EntityCondition.IN, keys)] +
                keys.collect { String k -> ecf.makeCondition("recordKey", org.moqui.entity.EntityCondition.LIKE, k.replace("%", "").replace("_", "\\_") + "/%") }
        return ec.entity.find("darpan.audit.AuditFinding").condition("companyUserGroupId", tenantId)
                .condition(ecf.makeCondition(conds, org.moqui.entity.EntityCondition.OR)).condition("stateEnumId", "AUDFS_OPEN")
                .orderBy("-lastSeenDate").limit(50).useCache(false).list()
                .collect { [findingId: it.findingId, recordKey: it.recordKey, headline: it.headline, period: it.period] }
    }

    /** A tenant read row, merged over the product row with the same id, must be usable: null when it is, else why not. */
    static String tenantReadRefusal(def ec, String tenantId, Map p) {
        Map product = (Map) LookupCatalog.reads(ec, null).find { it.readId == p.readId } ?: [:]
        Map merged = new LinkedHashMap(product)
        p.each { k, v -> if (v != null && v.toString() != "") merged.put(k, v) }
        if ("N" == merged.isActive) return null   // hiding a product read needs nothing else
        for (String f : ["systemCode", "recordKind", "keyField", "queryTemplate"]) if (!merged.get(f)) return "A read needs ${f}.".toString()
        if (!(merged.systemCode in SYSTEMS.keySet())) return "Unknown system ${merged.systemCode}; NETSUITE, OMS or SHOPIFY.".toString()
        if (merged.systemCode != "NETSUITE" && !merged.recordsAt) return "A GraphQL read needs recordsAt, e.g. orders.edges[*].node."
        if (merged.valueShape) {
            if ((merged.valueShape as String).length() > 200) return "The value shape is longer than 200 characters."
            try { Pattern.compile(merged.valueShape as String) } catch (java.util.regex.PatternSyntaxException e) { return "The value shape is not a valid pattern.".toString() }
        }
        return templateRefusal(merged.systemCode as String, merged.queryTemplate as String) ?: formatRefusal(merged.valueFormat as String)
    }

    /** A tenant template must be read-only for its system: null when it is, else why not. */
    static String templateRefusal(String systemCode, String template) {
        if (!template?.trim()) return "The read needs a query template."
        if (template.length() > MAX_TEMPLATE_LENGTH) return "The query template is longer than ${MAX_TEMPLATE_LENGTH} characters.".toString()
        if (systemCode == "NETSUITE") {
            if (!template.contains("{value}")) return "A SuiteQL template takes the value as {value}."
            return AgentQuerySupport.checkSuiteQl(template.replace("{value}", "'1'"))
        }
        if (systemCode in ["OMS", "SHOPIFY"]) return AgentQuerySupport.checkGraphqlQuery(template)
        return "Unknown system ${systemCode}; NETSUITE, OMS or SHOPIFY.".toString()
    }

    /** SuiteQL with {value} replaced by the value, quoted, once it fits the read's shape. Throws IllegalArgumentException. */
    static String renderSuiteQl(String template, String value, String shape) {
        if (!template) throw new IllegalArgumentException("broken read: no query template")
        String v = value ?: ""
        if (v.length() > MAX_VALUE_LENGTH || !SAFE_VALUE.matcher(v).matches() || (shape && !Pattern.compile(shape).matcher(v).matches())) {
            throw new IllegalArgumentException("'${v}' is not a value this read takes.".toString())
        }
        return template.replace("{value}", "'" + v + "'")
    }

    /** valueFormat with its one literal %s replaced by the value (never a java.util.Formatter pattern). */
    static String formatValue(String format, String value) {
        if (!format) return value
        String why = formatRefusal(format)
        if (why) throw new IllegalArgumentException(why)
        return format.replace("%s", value)
    }

    /** Null when the format is plain text with exactly one %s and no other %, else why not. */
    static String formatRefusal(String format) {
        if (!format) return null
        if (format.length() > 200) return "The value format is longer than 200 characters."
        int at = format.indexOf("%s")
        if (at < 0 || format.indexOf("%s", at + 2) >= 0 || format.replace("%s", "").contains("%")) return "The value format takes the value as one %s and no other %."
        return null
    }

    /** The record with only the read's default fields (dotted paths keep their nesting), or whole with all. */
    static Map project(Map record, List<String> fields, boolean all) {
        if (all || !fields) return record
        Map out = [:]
        fields.each { String path -> mergeInto(out, pick(record, path.tokenize("."))) }
        return out
    }

    /** The part of node at path, keeping its nesting; a list along the way keeps each element's part. Null when absent. */
    private static Object pick(Object node, List<String> parts) {
        if (parts.isEmpty()) return node
        if (node instanceof List) {
            List picked = ((List) node).collect { pick(it, parts) }.findAll { it != null }
            return picked ? picked : null
        }
        if (!(node instanceof Map)) return null
        Object below = pick(((Map) node).get(parts[0]), parts.subList(1, parts.size()))
        return below == null ? null : [(parts[0]): below]
    }

    private static void mergeInto(Map into, Object part) {
        if (!(part instanceof Map)) return
        ((Map) part).each { k, v ->
            Object have = into.get(k)
            if (have instanceof Map && v instanceof Map) mergeInto((Map) have, v)
            else if (have instanceof List && v instanceof List && have.size() == v.size()) {
                ((List) have).eachWithIndex { Object h, int i -> if (h instanceof Map && v[i] instanceof Map) mergeInto((Map) h, v[i]) }
            } else into.put(k, v)
        }
    }

    /** The value fits the read's shape (at most 64 characters, checked before any tenant regex runs). Throws on a bad regex. */
    static boolean shapeFits(Map read, String value) {
        if (!value || value.length() > MAX_VALUE_LENGTH) return false
        Pattern p
        try { p = Pattern.compile((read.valueShape as String) ?: DEFAULT_SHAPE) }
        catch (java.util.regex.PatternSyntaxException e) { throw new IllegalArgumentException("bad valueShape ${read.valueShape}".toString()) }
        // A tenant shape is the tenant's own regex on Java's backtracking engine: run it against a time budget so a
        // catastrophic pattern ((a+)+b) gives up as a broken read instead of holding a thread.
        try {
            return p.matcher(new BudgetedChars(value, System.nanoTime() + SHAPE_BUDGET_NANOS)).matches()
        } catch (ShapeTimeout ignored) {
            throw new IllegalArgumentException("value shape ${read.valueShape} took too long".toString())
        }
    }

    static final long SHAPE_BUDGET_NANOS = 100_000_000L   // 100 ms

    private static class ShapeTimeout extends RuntimeException {
        ShapeTimeout() { super(null, null, false, false) }
    }

    /** The value as the regex engine reads it, checking the clock every 1,000 reads. */
    private static class BudgetedChars implements CharSequence {
        private final String text
        private final long deadline
        private int reads = 0
        BudgetedChars(String text, long deadline) { this.text = text; this.deadline = deadline }
        char charAt(int index) {
            if (++reads % 1000 == 0 && System.nanoTime() > deadline) throw new ShapeTimeout()
            return text.charAt(index)
        }
        int length() { return text.length() }
        CharSequence subSequence(int start, int end) { return new BudgetedChars(text.substring(start, end), deadline) }
        String toString() { return text }
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
