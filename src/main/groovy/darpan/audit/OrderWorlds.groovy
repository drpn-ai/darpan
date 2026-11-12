package darpan.audit

/**
 * DAR-BE-250 (Aditi 2026-11-12: "earliest step wins"; "make sure every issue reported is unique ... or else the numbers
 * will be inflated"). An audit's statements follow an order's story (AuditStatement.sequenceNum). When one order breaks
 * at several steps, it is reported once: at its earliest broken step. The findings at later steps wait on it: they are
 * not counted, and the report lists them under the finding that is.
 *
 * An order world is every finding that names the same order, through the ids its stored records already carry
 * (AuditReportAssembler.recordsJson): the HotWax order (an item's order, an order's id), the order's name (#GOR..., and an
 * exchange's EXC-... name, which a return names as its exchange), a HotWax return, a NetSuite sales order (tranId and
 * internal id) and a Shopify order. Ids are typed, so a return id is never taken for an order id with the same digits.
 * No reads: pure, so the report, the by-order list and everything fed by them agree.
 */
class OrderWorlds {

    static final String OPEN = "AUDFS_OPEN"

    /** The typed order ids a finding's records name (recordsJson shape: system -> recordType -> [record]). */
    static Set<String> keysOf(Map records) {
        Set<String> keys = new LinkedHashSet<>()
        Closure add = { String kind, Object value ->
            String v = value?.toString()?.trim()
            if (v) keys << "${kind}:${v}".toString()
        }
        ((records ?: [:]) as Map).each { Object system, Object byType ->
            ((byType ?: [:]) as Map).each { Object type, Object list ->
                ((list ?: []) as List).each { Object o ->
                    if (!(o instanceof Map)) return
                    Map r = (Map) o
                    String id = r.id?.toString()
                    switch (type as String) {
                        case "orderItem":
                            add("order", id?.contains("/") ? id.substring(0, id.indexOf("/")) : id)
                            add("name", r.orderName)
                            break
                        case "order":
                            if (system == "Shopify") add("shopify", id)
                            else if (system == "NetSuite") add("ns-so", id)
                            else {
                                add("order", r.internalId)
                                add(id ==~ /M\d+/ ? "order" : "name", id)
                            }
                            add("name", r.orderName)
                            break
                        case "salesOrderLine":
                        case "salesOrder":
                            add("ns-so", id)
                            add("ns-so-int", r.internalId)
                            break
                        case "return":
                            add("return", id)
                            add("name", r.orderName)
                            if (r.exchange instanceof Map) {
                                add("name", ((Map) r.exchange).id)
                                add("ns-so-int", ((Map) r.exchange).netsuiteId)
                            }
                            if (r.heldBy instanceof Map) add("return", ((Map) r.heldBy).id)
                            break
                        default:
                            add("${system}:${type}".toString(), id)
                    }
                }
            }
        }
        return keys
    }

    /**
     * Per finding id: [world: the world's id, waitingOn: the statement id of the earliest step with an open finding in its
     * world when that step is strictly earlier than its own and it is open itself, else null]. entries are ledger entries
     * ([row: AuditFinding map, state]); stepByStatement is each statement's sequenceNum (none sorts last).
     */
    static Map<String, Map> rank(List<Map> entries, Map<String, Map> recordsByFinding, Map<String, Long> stepByStatement) {
        Map<String, String> parent = [:]
        entries.each { Map e -> parent.put(e.row.findingId as String, e.row.findingId as String) }
        Closure<String> root = { String x ->
            String r = x
            while (parent[r] != r) r = parent[r]
            while (parent[x] != r) { String next = parent[x]; parent[x] = r; x = next }
            return r
        }
        Map<String, String> firstWithKey = [:]
        entries.each { Map e ->
            String id = e.row.findingId as String
            keysOf(recordsByFinding?.get(id)).each { String key ->
                String other = firstWithKey.putIfAbsent(key, id)
                if (other != null) {
                    String a = root(other), b = root(id)
                    if (a != b) parent[b] = a
                }
            }
        }
        Closure<Long> stepOf = { Map e -> (stepByStatement?.get(e.row.statementId as String) ?: Long.MAX_VALUE) as Long }
        Map<String, Map> earliest = [:]
        entries.each { Map e ->
            if (e.state != OPEN) return
            String world = root(e.row.findingId as String)
            Map best = earliest[world]
            long step = stepOf(e)
            if (best == null || step < (best.step as long)) earliest[world] = [step: step, statementId: e.row.statementId]
        }
        Map<String, Map> out = [:]
        entries.each { Map e ->
            String id = e.row.findingId as String
            String world = root(id)
            Map best = earliest[world]
            boolean waits = e.state == OPEN && best != null && (best.step as long) < stepOf(e)
            out[id] = [world: world, waitingOn: waits ? best.statementId : null]
        }
        return out
    }
}
