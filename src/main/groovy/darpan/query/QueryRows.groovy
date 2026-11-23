package darpan.query

import org.moqui.jexl.script.ReadConditions

/**
 * DAR-BE-334. One input's rows from a stream of records: window, where, fields, group-by with measures, order, and the
 * row cap (rows handed to the script; groups for a grouped read). Stops when the lane asks (thread interrupted).
 */
class QueryRows {
    /** {rows, rowCount} or {error}. records: an iterator of plain maps. */
    static Map<String, Object> collect(Iterator<Map> records, Map input, Map windowCondition, int maxRows) {
        String name = input.name ?: "input"
        List<String> fields = (List<String>) (input.fields ?: [])
        List<String> groupBy = (List<String>) (input.groupBy ?: [])
        List<Map> measures = (List<Map>) (input.measures ?: [])
        List<String> orderBy = (List<String>) (input.orderBy ?: [])
        Map where = (Map) input.where
        boolean grouped = groupBy || measures
        ReadConditions.GroupAccumulator groups = grouped ? new ReadConditions.GroupAccumulator(groupBy, measures) : null
        List<Map> rows = []
        while (records.hasNext()) {
            if (Thread.currentThread().isInterrupted()) return QuerySourceScope.error("READ_ERROR", "Input '${name}': the read was stopped (it ran past its time).")
            Map record = records.next()
            if (record == null) continue
            if (windowCondition && !ReadConditions.matches(windowCondition, record)) continue
            if (where && !ReadConditions.matches(where, record)) continue
            if (grouped) {
                groups.add(record)
                if (groups.size() > maxRows) return QuerySourceScope.error("READ_TOO_LARGE", "Input '${name}' has more than ${maxRows} groups; the limit is ${maxRows}. Group it more coarsely or narrow it.")
            } else {
                Map row = new LinkedHashMap()
                fields.each { String f -> row.put(f, record.get(f)) }
                rows.add(row)
                if (rows.size() > maxRows) return QuerySourceScope.error("READ_TOO_LARGE", "Input '${name}' has more than ${maxRows} rows; the limit is ${maxRows}. Narrow it or group it.")
            }
        }
        List<Map> out = ReadConditions.order(grouped ? groups.rows() : rows, orderBy)
        return [rows: out, rowCount: (long) out.size()] as Map<String, Object>
    }

    /** Every field an input names (fields, where, groupBy, measures, orderBy unless grouped). */
    static Set<String> namedFields(Map input) {
        Set<String> named = new LinkedHashSet<>()
        named.addAll((List<String>) (input.fields ?: []))
        named.addAll((List<String>) (input.groupBy ?: []))
        ((List<Map>) (input.measures ?: [])).each { Map m -> if (m.field) named.add(m.field as String) }
        if (input.where) whereFields((Map) input.where, named)
        if (!(input.groupBy || input.measures)) ((List<String>) (input.orderBy ?: [])).each { String o -> named.add(o.startsWith("-") ? o.substring(1) : o) }
        return named
    }

    private static void whereFields(Map where, Set<String> out) {
        if (where.containsKey("and") || where.containsKey("or")) {
            ((List) where.get(where.containsKey("and") ? "and" : "or")).each { Object c -> whereFields((Map) c, out) }
        } else if (where.get("field")) {
            out.add(where.get("field") as String)
        }
    }
}
