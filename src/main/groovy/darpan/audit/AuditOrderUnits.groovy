package darpan.audit

import groovy.json.JsonSlurper

import static darpan.audit.AuditReportAssembler.FIXED
import static darpan.audit.AuditReportAssembler.OPEN

/**
 * DAR-UI-075 / DAR-UI-099 / DAR-BE-250. How the report groups ledger entries before it counts them: the lines of one
 * order are one unit; an order broken at several steps counts at its earliest open step, and its later steps' open
 * lines wait under that row; each order lists every line it has (the ones with a finding and the ones the run read
 * without one). Pure: no reads.
 *
 * DAR-OPS-029 S10: split out of AuditReportAssembler.
 */
class AuditOrderUnits {

    /**
     * The order a finding is a line of: its HotWax order item's order (the item's id before its "/"), else its NetSuite
     * sales order line's order; null when the finding is not about an order line. A finding that only mentions an order
     * (a credit memo naming its Shopify order) is not one, so returns and memos stay findings of their own.
     */
    private static String orderKeyOf(Map records) {
        Closure<String> first = { String system, String type -> ((((records ?: [:])[system] ?: [:]) as Map)[type] as List)?.find()?.id as String }
        return first("OMS", "orderItem")?.with { it.contains("/") ? it.substring(0, it.indexOf("/")) : it } ?:
                first("NetSuite", "salesOrderLine")
    }

    /**
     * Ledger entries as the report lists them: the lines of one order together as one unit (its earliest line's row, so
     * oldest-first and newToday hold; its worst state: open, else fixed), one line or many; a finding that
     * is not an order line a unit of its own. Units keep the order of their earliest line.
     */
    private static List<Map> orderUnits(List<Map> entries, Map<String, Map> recordsByFinding) {
        Map<String, List<Map>> byOrder = new LinkedHashMap<>()
        List<Object> keys = []
        entries.each { Map e ->
            String order = orderKeyOf(recordsByFinding[e.row.findingId as String])
            if (order) {
                if (!byOrder.containsKey(order)) { byOrder[order] = []; keys << order }
                byOrder[order] << e
            } else keys << e
        }
        return keys.collect { Object key ->
            if (!(key instanceof String)) return [row: ((Map) key).row, state: ((Map) key).state, entries: [(Map) key]]
            List<Map> lines = byOrder[key]
            String state = lines.any { it.state == OPEN } ? OPEN : FIXED
            return [row: lines[0].row, state: state, entries: lines, order: key]
        }
    }

    /**
     * DAR-BE-250. Earliest step wins: each statement's order units (statements in story order, sequenceNum then code),
     * split into those it counts and those waiting on an earlier step of the same order (OrderWorlds). A unit's open lines
     * wait together (they share its world and its step); its fixed lines never wait, so when its open lines wait, they
     * are a waiting unit and its fixed lines stay counted as fixed at their own step (DAR-OPS-029 S10: before, such an
     * order was counted open at both steps). Each waiting unit is hung, with its statement, on the first open unit of its
     * order world that a statement counts, which is at the step it waits on. Statements at one step (or with none) go in
     * story order: the first holds the order (DAR-OPS-029 S10). Returns statementId -> [counted, waiting].
     */
    static Map<String, Map> stepUnits(List<Map> statements, Map<String, List<Map>> entriesByStatement, Map<String, Map> recordsByFinding) {
        // Each statement's 1-based place in story order (STATEMENT_ORDER), never its raw sequenceNum: OrderWorlds waits only on a
        // strictly earlier step, so a tie (or two with none) counted one order at both; and it reads 0 as no step.
        List<Map> story = statements.sort(false, AuditReportAssembler.STATEMENT_ORDER)
        Map<String, Long> step = (0..<story.size()).collectEntries { int i -> [(story[i].statementId as String): (i + 1) as Long] }
        List<Map> all = story.collectMany { (entriesByStatement.get(it.statementId as String) ?: []) as List<Map> } as List<Map>
        Map<String, Map> worlds = OrderWorlds.rank(all, recordsByFinding, step)
        Map<String, Map> out = new LinkedHashMap<>()
        Map<String, Map> holderByWorld = [:]
        story.each { Map st ->
            List<Map> counted = [], waiting = []
            orderUnits((entriesByStatement.get(st.statementId as String) ?: []) as List<Map>, recordsByFinding).each { Map unit ->
                List<Map> lines = unit.entries as List<Map>
                String world = worlds.get(lines[0].row.findingId as String)?.world
                List<Map> open = lines.findAll { it.state == OPEN }
                String waitingOn = open ? worlds.get(open[0].row.findingId as String)?.waitingOn : null
                if (waitingOn == null) {
                    counted << (unit + [world: world])
                    return
                }
                waiting << (unit + [row: open[0].row, state: OPEN, entries: open, world: world, waitingOn: waitingOn])
                List<Map> fixed = lines.findAll { it.state != OPEN }
                if (fixed) counted << (unit + [row: fixed[0].row, state: FIXED, entries: fixed, world: world])
            }
            counted.each { Map unit -> if (unit.state == OPEN && unit.world != null) holderByWorld.putIfAbsent(unit.world as String, unit) }
            out.put(st.statementId as String, [counted: counted, waiting: waiting])
        }
        out.each { String statementId, Map split ->
            (split.waiting as List<Map>).each { Map unit ->
                Map holder = holderByWorld.get(unit.world as String)
                if (holder != null) ((List) (holder.waitingUnits = holder.waitingUnits ?: [])) << (unit + [statementId: statementId])
            }
        }
        return out
    }

    /**
     * DAR-BE-254. What a finding is about, for a row that names it in a word (Aditi: "it says R4 here.. shouldnt it be the
     * SKU"): its records' SKUs (up to two, then "and N more"), else its line ids, else null (an order-level finding).
     */
    static String itemOf(Map finding) {
        List<Map> recs = []
        (((finding?.records ?: [:]) as Map).values()).each { Object byType ->
            ((byType ?: [:]) as Map).values().each { Object list -> recs.addAll(((list ?: []) as List).findAll { it instanceof Map } as List<Map>) }
        }
        List<String> skus = recs.collect { it.sku as String }.findAll().unique()
        List<String> picked = skus ?: ((finding?.lines ?: []) as List<Map>).collect { it.id as String }.findAll().unique()
        if (!picked) return null
        return picked.size() <= 2 ? picked.join(', ') : "${picked.take(2).join(', ')} and ${picked.size() - 2} more".toString()
    }

    /**
     * DAR-OPS-029 S99-012 (S10's read side of S11 e462d3c9). The lines of an order left off past the 60-per-side read cap,
     * per system (report key: OMS, NetSuite, Shopify), the most any of the given ledger rows saw; null when none were.
     */
    static Map<String, Integer> linesOmitted(List<Map> rows) {
        Map<String, Integer> out = new TreeMap<String, Integer>()
        (rows ?: []).each { Map row ->
            String json = row?.orderLinesOmittedJson as String
            if (!json) return
            ((Map) new JsonSlurper().parseText(json)).each { String root, Object n ->
                String key = AuditRecordFacts.systemKey(root)
                out.put(key, Math.max(out.get(key) ?: 0, (n as Number).intValue()))
            }
        }
        return out ?: null
    }

    /**
     * DAR-UI-099: the other lines of the order its findings carry (AuditAutomationSupport.ORDER_LINE records), taken off
     * each line's records so they are listed once, in orderLines, and not as records of their own.
     */
    static List<Map> takeOrderLineRecords(List<Map> lines) {
        List<Map> context = []
        lines.each { Map l ->
            Map copy = new LinkedHashMap()
            ((Map) (l.records ?: [:])).each { String system, Map byType ->
                Map kept = new LinkedHashMap(byType)
                ((List) (kept.remove(AuditAutomationSupport.ORDER_LINE) ?: [])).each { context << [system: system, record: it] }
                if (kept) copy.put(system, kept)
            }
            l.records = copy
        }
        return context
    }

    /**
     * DAR-UI-099 (Aditi: "always make sure all lines are present within the order... a store credit line item is added").
     * Every line of the order, in line order: those with a finding (its id, cause and state) and those without (as the run
     * read them: completed and billed, or set aside, e.g. approved and not yet fulfilled), with SKU, status and units; then
     * the lines only another system has with no line of their own, e.g. NetSuite's "Store Credit - Redemption".
     */
    static List<Map> orderLines(String order, List<Map> lines, List<Map> context) {
        Map<String, Map> byItem = new LinkedHashMap<>()
        String ownSystem = null
        lines.each { Map l ->
            ((Map) (l.records ?: [:])).each { String system, Map byType ->
                byType.each { String type, List list ->
                    list.each { Map r ->
                        String id = r.id as String
                        if (!id?.startsWith(order + "/") || byItem.containsKey(id)) return
                        ownSystem = ownSystem ?: system
                        if (system != ownSystem) return
                        byItem.put(id, [line: itemNumber(order, id), item: id,
                                        sku: r.sku, status: r.status, finding: l.id, cause: l.cause, state: l.state])
                    }
                }
            }
        }
        List<Map> others = []
        context.each { Map c ->
            Map r = (Map) c.record
            String id = r.id as String
            if (id?.startsWith(order + "/")) {
                if ((ownSystem == null || c.system == ownSystem) && !byItem.containsKey(id)) {
                    ownSystem = ownSystem ?: c.system
                    byItem.put(id, [line: itemNumber(order, id), item: id, sku: r.sku,
                                    status: r.status, quantity: r.quantity, setAside: r.setAside == true, finding: null])
                }
            } else if (!others.any { it.system == c.system && it.item == (r.item ?: id) }) {
                others << [system: c.system, item: r.item ?: id, itemType: r.itemType, amount: r.amount, status: r.status]
            }
        }
        List<Map> sorted = byItem.values().sort(false) { Map a, Map b ->
            ((a.line as String)?.isInteger() ? (a.line as String).toInteger() : Integer.MAX_VALUE) <=>
                    ((b.line as String)?.isInteger() ? (b.line as String).toInteger() : Integer.MAX_VALUE) ?: (a.item as String) <=> (b.item as String)
        }
        return (sorted.collect { it.findAll { k, v -> v != null } } + others.collect { it.findAll { k, v -> v != null } }) as List<Map>
    }

    /** DAR-BE-361. A row's lines: its own, then those of the later-step units waiting under it. */
    static List<Map> rowLines(Map unit) {
        return (unit.entries as List<Map>) + ((unit.waitingUnits ?: []) as List<Map>).collectMany { it.entries as List<Map> }
    }

    /** DAR-BE-361. The findings one Acknowledge of this row covers, and whose acknowledgements its state reads: its open lines. */
    static List<String> openRowIds(Map unit) {
        return rowLines(unit).findAll { it.state == OPEN }.collect { it.row.findingId as String }.unique()
    }

    /** An order of one line, in the check's own words with the item named as its line: "line 1 is completed in HotWax
     *  and not billed in NetSuite" from "Item M1167975/01 is completed ...". With a cause and no such words, the cause. */
    static String oneLine(String order, Map line) {
        String item = lineItemId(line)
        String headline = (line.headline ?: "") as String
        int at = item ? headline.indexOf(item) : -1
        if (at >= 0) return "line ${lineNumber(order, line)}${headline.substring(at + item.length())}".toString()
        return "line ${lineNumber(order, line)}${line.cause ? ', ' + lowerFirst(line.cause as String) : ''}".toString()
    }

    /** DAR-UI-099: "2" for item M1132010/02 of order M1132010; the item id when it has no number after the order. */
    private static String itemNumber(String order, String item) {
        if (!item?.startsWith(order) || item.length() <= order.length()) return item
        String suffix = item.substring(order.length()).replaceFirst(/^[\/\-_ ]+/, "")
        return suffix.isInteger() ? String.valueOf(suffix.toInteger()) : suffix
    }

    /**
     * "1" for item M1168785/01 of order M1168785 (a person reads "line 1", not "/01"); the item id itself when it is not
     * under the order's key (R10 keys an order by its NetSuite sales order). DAR-BE-372 rule 5: never the finding id; a
     * line nothing names reads "?".
     */
    static String lineNumber(String order, Map line) {
        String item = lineItemId(line)
        return item ? itemNumber(order, item) : "?"
    }

    /**
     * DAR-BE-372 rule 5. A line's HotWax item id: its HotWax item record's, else the one its own headline names ("Line
     * M1230020/01 is open in NetSuite ..." on an R10 line that holds only a NetSuite record). Null when neither does.
     */
    static String lineItemId(Map line) {
        String item = (((((line.records ?: [:]) as Map).OMS ?: [:]) as Map).orderItem as List)?.find()?.id as String
        if (item) return item
        def named = ((line.headline ?: "") as String) =~ /^(?:Line|Item) (\S+)/
        return named.find() ? named.group(1) : null
    }

    static String lowerFirst(String text) {
        return text ? text.substring(0, 1).toLowerCase() + text.substring(1) : text
    }

    /** Every line's records once each, systems and types in first-seen order. */
    static Map mergedRecords(List<Map> perLine) {
        Map<String, Map<String, List>> out = new LinkedHashMap<>()
        perLine.each { Map bySystem ->
            (bySystem ?: [:]).each { String system, Map byType ->
                byType.each { String type, List list ->
                    List into = out.computeIfAbsent(system) { new LinkedHashMap<>() }.computeIfAbsent(type) { [] }
                    list.each { Map r -> if (!into.any { it.id == r.id }) into << r }
                }
            }
        }
        return out
    }

    /** Oldest first: firstFound ascending, then id ascending with F-99 before F-100. */
    static List<Map> sortOldestFirst(List<Map> entries) {
        return entries.sort(false) { Map a, Map b ->
            ((a.row.firstFoundDate as Date) <=> (b.row.firstFoundDate as Date)) ?:
                    ((a.row.findingId as String).length() <=> (b.row.findingId as String).length()) ?:
                    ((a.row.findingId as String) <=> (b.row.findingId as String))
        }
    }
}
