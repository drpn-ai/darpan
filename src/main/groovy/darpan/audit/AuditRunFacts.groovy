package darpan.audit

/**
 * DAR-BE-207. The fact sheet of one audit run, built by code: every count, group and id the run update shows, so the
 * email, the chat card and the optional writer (agent.AgentWriterServices.write#AuditRunWords) never count for
 * themselves. Built from the run's report document (assemble#AuditReport) and its open findings one per order or return
 * (AuditReportAssembler.findingsByOrder). Pure: no Moqui, so it is tested on fixtures.
 */
class AuditRunFacts {
    /** A group lists its ids only when it has fewer than this many orders or returns; the CSV always has every one. */
    static final int IDS_BELOW = 25
    static final List<String> CSV_HEADER = ["Rule", "Cause", "Finding id", "HotWax id", "Order name", "NetSuite id",
                                            "Shopify id", "First found", "Days open", "Fix"].asImmutable()

    /**
     * {tenant {id, name}, audit {id, name}, period, date, window {from, thru}, totals {open, newToday, fixedToday},
     * rules [{id, text, open, newToday, fixedToday, checksNotRun}], groups [{rule, cause, count, fixText, ids}]}.
     * A group is one rule and one cause; its count is the distinct orders and returns it holds. fixTextOf(rule, cause,
     * findingId) is asked once per group, with one finding of it, for the cause's fix; any {placeholder} it leaves is cut.
     */
    static Map build(Map report, List<Map> units, Closure<String> fixTextOf) {
        Map head = (report?.report ?: [:]) as Map
        Map tenant = (head.tenant ?: [:]) as Map
        Map audit = (head.audit ?: [:]) as Map
        Map summary = (report?.summary ?: [:]) as Map
        // DAR-BE-250: rules in the order's story (each statement's step), then by number.
        Map<String, Long> stepOf = statements(report).collectEntries { Map st -> [(st.id as String): st.step == null ? null : (st.step as Long)] }
        List<Map> rules = statements(report).collect { Map st ->
            Map counts = (st.counts ?: [:]) as Map
            [id          : st.id,
             text        : st.text,
             open        : count(counts.open),
             newToday    : count(counts.newToday),
             fixedToday  : count(counts.fixedToday),
             checksNotRun: ((st.checks ?: []) as List<Map>).findAll { it.status == "notRun" }*.id]
        }.sort(false) { Map a, Map b -> stepOrder(stepOf, a.id as String, b.id as String) }

        Map<String, Map> byKey = new LinkedHashMap<>()
        (units ?: []).each { Map unit ->
            String rule = unit.statement as String
            Map ids = idsOf(unit)
            String key = (ids.hotwaxId ?: unit.id) as String
            causesOf(unit).each { List pair ->
                String cause = pair[0] as String
                Map group = byKey.computeIfAbsent("${rule}|${cause ?: ''}".toString()) {
                    [rule: rule, cause: cause, sample: pair[1], keys: new LinkedHashSet<String>(), ids: new LinkedHashSet<String>()]
                }
                if ((group.keys as Set).add(key)) (group.ids as Set).add(idLabel(unit, ids))
            }
        }
        List<Map> groups = byKey.values().collect { Map g ->
            int n = (g.keys as Set).size()
            [rule   : g.rule,
             cause  : g.cause,
             count  : n,
             fixText: stripPlaceholders(fixTextOf?.call(g.rule as String, g.cause as String, g.sample as String)),
             ids    : n < IDS_BELOW ? new ArrayList<String>(g.ids as Set) : []]
        }.sort(false) { Map a, Map b ->
            stepOrder(stepOf, a.rule as String, b.rule as String) ?: (b.count as int) <=> (a.count as int) ?:
                    ((a.cause ?: "") as String) <=> ((b.cause ?: "") as String)
        }

        return [tenant: [id: tenant.id, name: tenant.name ?: tenant.id],
                audit : [id: audit.id, name: audit.name],
                period: head.period,
                date  : head.date,
                window: head.window,
                totals: [open: count(summary.open), newToday: count(summary.newToday), fixedToday: count(summary.fixedToday)],
                rules : rules,
                groups: groups]
    }

    /**
     * One CSV per rule with open findings, keyed by rule: one row per open finding (each line of an order is its own
     * finding), RFC 4180. fixOf(rule, cause) is the fix line the email shows for that group.
     */
    static Map<String, String> csvByRule(List<Map> units, Closure<String> fixOf) {
        Map<String, List<List<String>>> rows = new LinkedHashMap<>()
        (units ?: []).each { Map unit ->
            String rule = unit.statement as String
            Map ids = idsOf(unit)
            List<Map> lines = unit.lines ? (unit.lines as List<Map>) : [unit]
            lines.each { Map line ->
                String cause = (line.cause ?: unit.cause) as String
                String fix = fixOf?.call(rule, cause) ?: (unit.cause && unit.cause != cause ? fixOf?.call(rule, unit.cause as String) : null)
                rows.computeIfAbsent(rule) { [] } << [rule, cause, line.id, ids.hotwaxId, ids.orderName, ids.netsuiteId,
                                                      ids.shopifyId, line.firstFound ?: unit.firstFound,
                                                      (line.ageDays != null ? line.ageDays : unit.ageDays)?.toString(), fix].collect { it?.toString() }
            }
        }
        return rows.collectEntries { String rule, List<List<String>> body -> [(rule): csv([CSV_HEADER] + body)] }
    }

    /** RFC 4180: CRLF lines; a field with a comma, quote or line break quoted, its quotes doubled. */
    static String csv(List<List<String>> rows) {
        return rows.collect { List<String> row -> row.collect { csvField(it) }.join(",") }.join("\r\n") + "\r\n"
    }

    static String csvField(String value) {
        String v = value ?: ""
        // A spreadsheet runs a cell that starts with = + - @ (or a tab / carriage return before one) as a formula;
        // record data is never meant to. A plain number, a negative amount included, stays a number.
        if (v && "=+-@\t\r".indexOf(v.substring(0, 1)) >= 0 && !(v ==~ /-?\d+(\.\d+)?/)) v = "'" + v
        return v ==~ /(?s).*[",\r\n].*/ ? '"' + v.replace('"', '""') + '"' : v
    }

    /** The ids a person searches each system by. HotWax: the order id, a return's id for a return. */
    static Map idsOf(Map unit) {
        Map records = (unit?.records ?: [:]) as Map
        Closure<Map> first = { String system, String type -> ((((records[system] ?: [:]) as Map)[type]) as List)?.find() as Map }
        Closure<String> anyId = { String system ->
            ((records[system] ?: [:]) as Map).findAll { k, v -> k != AuditAutomationSupport.ORDER_LINE }
                    .values().collectMany { (it ?: []) as List }.find()?.id as String
        }
        Map omsOrder = first("OMS", "order")
        String hotwaxId = (unit?.order ?: omsOrder?.internalId ?: omsOrder?.id ?: first("OMS", "return")?.id) as String
        String orderName = records.values().collectMany { Map byType ->
            byType.findAll { k, v -> k != AuditAutomationSupport.ORDER_LINE }.values().collectMany { (it ?: []) as List }
        }.find { it instanceof Map && it.orderName }?.orderName as String
        if (!orderName && omsOrder?.internalId && omsOrder.id != omsOrder.internalId) orderName = omsOrder.id as String
        return [hotwaxId  : hotwaxId,
                orderName : orderName,
                netsuiteId: (first("NetSuite", "salesOrder")?.id ?: anyId("NetSuite")) as String,
                shopifyId : (first("Shopify", "order")?.id ?: anyId("Shopify")) as String]
    }

    /** {name} values a template could not fill are cut, with the spacing they leave; null when nothing is left. */
    static String stripPlaceholders(String text) {
        if (!text) return null
        String out = text.replaceAll(/\{[A-Za-z][A-Za-z0-9_.]*\}/, "").replaceAll(/[ \t]{2,}/, " ")
                .replaceAll(/ +([.,;:])/, '$1').trim()
        return out ?: null
    }

    /** R2 before R10; a code without a number after every numbered one. */
    /** DAR-BE-250. By the statements' steps (none last), then ruleOrder. */
    static int stepOrder(Map<String, Long> stepOf, String a, String b) {
        Long sa = stepOf?.get(a), sb = stepOf?.get(b)
        return (sa ?: Long.MAX_VALUE) <=> (sb ?: Long.MAX_VALUE) ?: ruleOrder(a, b)
    }

    static int ruleOrder(String a, String b) {
        return ruleNumber(a) <=> ruleNumber(b) ?: (a ?: "") <=> (b ?: "")
    }

    private static int ruleNumber(String code) {
        String digits = (code =~ /\d+/).with { it.find() ? it.group() : null }
        return digits ? digits.toInteger() : Integer.MAX_VALUE
    }

    /** "<HotWax id> <order name>", the name left off when it is the id or unknown. */
    private static String idLabel(Map unit, Map ids) {
        return [ids.hotwaxId ?: unit.id, ids.orderName].findAll { it }.unique().join(" ")
    }

    /**
     * The causes a unit is grouped under, each with one finding of it: a lone finding's own; an order's cause when the
     * report names one (its stuck line's, or the one all its lines share), else each cause its lines carry.
     */
    private static List<List> causesOf(Map unit) {
        List<Map> lines = (unit.lines ?: []) as List<Map>
        if (!lines) return [[unit.cause, unit.id]]
        if (unit.cause) return [[unit.cause, (lines.find { it.cause == unit.cause } ?: lines[0]).id]]
        return lines.groupBy { it.cause }.collect { cause, List<Map> same -> [cause, same[0].id] }
    }

    private static List<Map> statements(Map report) {
        return ((report?.categories ?: [:]) as Map).values().collectMany { Object category ->
            (((category as Map).statements ?: [:]) as Map).values() as List<Map>
        }
    }

    private static int count(Object value) {
        return value instanceof Number ? (value as Number).intValue() : 0
    }
}
