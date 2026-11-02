package darpan.datasource

/**
 * DAR-BE-176. A made-up system for the data-defined source tests: invoices with sections (a nested connection), lines
 * by invoice, part titles and warehouse labels by id. Shaped like a real order-items read (header, nested groups, child
 * lines placed under their group, two name lookups, units, numbering, counts), but naming no real system, so these
 * tests run with no system component installed.
 */
class AcmeLedgerFixture {

    static final String INVOICES = '''query AcmeInvoices($filter: String, $first: Int, $after: String) {
  invoices(filter: $filter, first: $first, after: $after) {
    edges { node { invoiceNo customerRef issued sections(first: 3) { edges { node { sectionNo warehouse } } } } }
    pageInfo { hasNextPage endCursor }
  }
}'''
    static final String LINES = '''query AcmeLines($filter: String!, $first: Int, $after: String) {
  lines(filter: $filter, first: $first, after: $after) { nodes { invoiceNo lineNo sectionNo qty partNo status } page { more next } }
}'''
    static final String PARTS = '''query AcmeParts($filter: String!, $first: Int, $after: String) {
  parts(filter: $filter, first: $first, after: $after) { nodes { partNo title } page { more next } }
}'''
    static final String WAREHOUSES = '''query AcmeWarehouses($filter: String!, $first: Int, $after: String) {
  warehouses(filter: $filter, first: $first, after: $after) { nodes { code label } page { more next } }
}'''

    /** Two invoices. A2's line 3 names section 9, which the invoice did not return; line 4 has no part; qty "1.0". */
    static final List<Map> INVOICE_ROWS = [
            [invoiceNo: "A1", customerRef: "C1", issued: "2026-10-01T05:00:00Z", sections: [edges: [
                    [node: [sectionNo: "1", warehouse: "W1"]], [node: [sectionNo: "2", warehouse: "W2"]]]]],
            [invoiceNo: "A2", customerRef: "C2", issued: "2026-10-01T09:00:00Z", sections: [edges: [
                    [node: [sectionNo: "1", warehouse: "W9"]]]]],
            [invoiceNo: "A3", customerRef: "C3", issued: "2026-10-02T09:00:00Z", sections: [edges: []]],
    ]
    static final List<Map> LINE_ROWS = [
            [invoiceNo: "A1", lineNo: "02", sectionNo: "2", qty: 2, partNo: "P2", status: "SHIPPED"],
            [invoiceNo: "A1", lineNo: "01", sectionNo: "1", qty: 1, partNo: "P1", status: "OPEN"],
            [invoiceNo: "A2", lineNo: "03", sectionNo: "9", qty: 3, partNo: "P1", status: "OPEN"],
            [invoiceNo: "A2", lineNo: "04", sectionNo: "1", qty: "1.0", partNo: null, status: "OPEN"],
    ]
    static final List<Map> PART_ROWS = [[partNo: "P1", title: "Bolt"], [partNo: "P2", title: "Nut"]]
    static final List<Map> WAREHOUSE_ROWS = [[code: "W1", label: "North"], [code: "W2", label: "South"]]

    static Map definition() {
        Map ids = [chunk: 2, separator: ","]
        return [
                connection: [urlTemplate: "{baseUrl}/graphql", auth: [kindField: "authType", kinds: [TOKEN: [method: "BEARER", tokenFields: ["token"]]]]],
                limits: [maxCostPerCall: 1000, errorCodeAt: "errors[*].extensions.code", costRefusedCodes: ["TOO_EXPENSIVE"]],
                batchRecords: 2,
                steps: [
                        [id: "invoices", kind: "query", document: INVOICES, connectionAt: "invoices", recordsAt: "edges[*].node",
                         hasNextAt: "pageInfo.hasNextPage", cursorAt: "pageInfo.endCursor",
                         bind: [window: [variables: [filter: "issued>={from} issued<{thru}"], field: "issued", sliceHours: 24, instantFormat: "ISO_INSTANT"],
                                ids: [variables: [filter: "{keyField} in ({ids})"], from: "LOOKUP_KEYS", chunk: 2, keyFields: [invoiceNo: "invoiceNo", ref: "customerRef"],
                                      idPattern: "[A-Z0-9]{1,10}"]],
                         paging: [firstVariable: "first", afterVariable: "after", pageSize: [budget: 900, costPerRecord: 300]], reservation: 950],
                        [id: "lines", kind: "query", document: LINES, connectionAt: "lines", recordsAt: "nodes[*]", hasNextAt: "page.more", cursorAt: "page.next",
                         bind: [ids: ids + [variables: [filter: "invoiceNo in ({ids})"], from: "invoices.invoiceNo"]],
                         paging: [firstVariable: "first", afterVariable: "after", pageSize: 2], reservation: 100],
                        [id: "parts", kind: "query", document: PARTS, connectionAt: "parts", recordsAt: "nodes[*]", hasNextAt: "page.more", cursorAt: "page.next",
                         bind: [ids: ids + [variables: [filter: "partNo in ({ids})"], from: "lines.partNo"]],
                         paging: [firstVariable: "first", afterVariable: "after", pageSize: 10], reservation: 100],
                        [id: "sectionList", kind: "unwrap", of: "invoices", field: "sections", path: "edges[*].node"],
                        [id: "warehouses", kind: "query", document: WAREHOUSES, connectionAt: "warehouses", recordsAt: "nodes[*]", hasNextAt: "page.more", cursorAt: "page.next",
                         bind: [ids: ids + [variables: [filter: "code in ({ids})"], from: "invoices.sections[*].warehouse"]],
                         paging: [firstVariable: "first", afterVariable: "after", pageSize: 10], reservation: 100],
                        [id: "partTitle", kind: "join", into: "lines", from: "parts", on: [[into: "partNo", from: "partNo"]], copy: [partTitle: "title"]],
                        [id: "warehouseLabel", kind: "join", into: "invoices", from: "warehouses", at: "sections[*]",
                         atOn: [[at: "warehouse", from: "code"]], copy: [warehouseLabel: "label"]],
                        [id: "linesInSections", kind: "join", into: "invoices", from: "lines", on: [[into: "invoiceNo", from: "invoiceNo"]],
                         at: "sections[*]", atOn: [[at: "sectionNo", from: "sectionNo"]], many: true, as: "lines",
                         missingAt: [create: true, set: [warehouse: null]]],
                        [id: "perLine", kind: "split", from: "invoices", path: "sections[*].lines[*]", levels: ["invoice", "section", "line"],
                         copy: [invoiceNo: "invoice.invoiceNo", lineNo: "line.lineNo", warehouse: "section.warehouse",
                                warehouseLabel: "section.warehouseLabel", partTitle: "line.partTitle", status: "line.status"]],
                        [id: "noPart", kind: "count", name: "linesWithoutPart", when: [[field: "line.partNo", op: "BLANK"]],
                         distinct: ["invoice.invoiceNo", "line.lineNo"]],
                        [id: "notOne", kind: "count", name: "quantityNotOne", when: [[field: "line.qty", op: "NOT_IN", values: ["1"]]]],
                        [id: "perUnit", kind: "repeat", times: "line.qty", parse: "STRICT_INT", index: "unit"],
                        [id: "unitNo", kind: "number", field: "unitNo", partitionBy: ["invoice.invoiceNo", "line.lineNo"], orderBy: ["unit"]],
                        [id: "statusNo", kind: "number", field: "statusNo", partitionBy: ["invoice.invoiceNo", "status"], orderBy: ["line.lineNo", "unit"]],
                ],
                record: [fields: ["invoiceNo", "lineNo", "unitNo", "statusNo", "status", "warehouse"], optionalFields: ["warehouseLabel", "partTitle"]],
        ]
    }

    /** Answers the fixture's queries like a server would: filters by the filter text, pages by first/after. */
    static class ScriptedTransport {
        final List<Map> calls = Collections.synchronizedList(new ArrayList<Map>())
        /** A filter text that fails the call, as a broken server would. */
        String failOn = null
        /** A call whose `first` is above this is refused for cost, once per call. */
        Integer refuseCostAbove = null

        Map execute(String document, Map variables, int reservation) {
            calls << [document: (document =~ /query\s+(\w+)/).with { it.find() ? it.group(1) : "?" }, variables: new LinkedHashMap(variables)]
            if (failOn != null && (variables.filter as String)?.contains(failOn)) throw new DataSourceCallException("BROKEN", "broken")
            int first = (variables.first as Number).intValue()
            if (refuseCostAbove != null && first > refuseCostAbove) throw new DataSourceCallException("TOO_EXPENSIVE", "too expensive", true)
            int after = variables.after ? (variables.after as String).substring(1) as int : 0
            String filter = variables.filter as String
            if (document.contains("AcmeInvoices")) {
                List<Map> rows = INVOICE_ROWS.findAll { matches(it, filter) }
                List<Map> page = rows.drop(after).take(first)
                boolean more = after + first < rows.size()
                return [invoices: [edges: page.collect { [node: deepCopy(it)] },
                                   pageInfo: [hasNextPage: more, endCursor: more ? "c${after + first}".toString() : null]]]
            }
            String root = document.contains("AcmeLines") ? "lines" : document.contains("AcmeParts") ? "parts" : "warehouses"
            List<Map> source = root == "lines" ? LINE_ROWS : root == "parts" ? PART_ROWS : WAREHOUSE_ROWS
            List<Map> rows = source.findAll { matches(it, filter) }
            List<Map> page = rows.drop(after).take(first)
            boolean more = after + first < rows.size()
            return [(root): [nodes: page.collect { deepCopy(it) }, page: [more: more, next: more ? "c${after + first}".toString() : null]]]
        }

        private static boolean matches(Map row, String filter) {
            def ids = filter =~ /(\w+) in \(([^)]*)\)/
            if (ids.find()) return (ids.group(2).split(",") as List).contains(row.get(ids.group(1))?.toString())
            def window = filter =~ /issued>=(\S+) issued<(\S+)/
            if (window.find()) return row.issued >= window.group(1) && row.issued < window.group(2)
            throw new IllegalArgumentException("unexpected filter ${filter}")
        }

        private static Map deepCopy(Map m) {
            return (Map) new groovy.json.JsonSlurper().parseText(groovy.json.JsonOutput.toJson(m))
        }
    }
}
