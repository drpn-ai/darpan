package darpan.lookup

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
