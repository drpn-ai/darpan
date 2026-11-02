package darpan.datasource

import groovy.json.JsonOutput
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-176. A data-defined source is checked before it is stored or run, and the checks are generic: the definition
 * here is for a made-up system ("Acme Ledger"), so the tests pass with no system component installed
 * (Aditi 2026-11-02: "system specific repos are optional").
 */
class DataSourceDefinitionSupportTests {

    private static final String INVOICES = '''query AcmeInvoices($filter: String, $first: Int, $after: String) {
  invoices(filter: $filter, first: $first, after: $after) {
    edges { node { invoiceNo customerRef total } }
    pageInfo { hasNextPage endCursor }
  }
}'''

    private static final String LINES = '''query AcmeLines($filter: String!, $first: Int, $after: String) {
  lines(filter: $filter, first: $first, after: $after) { nodes { invoiceNo lineNo amount } page { more next } }
}'''

    private static final List<String> CONNECTION_FIELDS = ["baseUrl", "authType", "token", "user", "secret", "extraHeaders"]

    private static Map definition(Map overrides = [:]) {
        Map d = [
                connection: [urlTemplate: "{baseUrl}/api/graphql",
                             auth: [kindField: "authType", headersJsonField: "extraHeaders",
                                    kinds: [TOKEN: [method: "BEARER", tokenFields: ["token"]],
                                            KEY: [method: "HEADER", header: "X-Acme-Key", tokenFields: ["token", "secret"]],
                                            PASSWORD: [method: "BASIC", userField: "user", passwordField: "secret"],
                                            OPEN: [method: "NONE"]]],
                             timeoutSeconds: 60],
                limits: [maxCostPerCall: 500, maxInFlight: 4, errorCodeAt: "errors[*].extensions.code",
                         throttledCodes: ["SLOW_DOWN"], costRefusedCodes: ["TOO_EXPENSIVE"], maxThrottleWaitSeconds: 120,
                         retryStatuses: [429, 503], maxAttempts: 3, retryBackoffMillis: 1000,
                         costBucket: [at: "extensions.cost.bucket", available: "left", maximum: "max", restoreRate: "perSecond"]],
                steps: [
                        [id: "invoices", kind: "query", document: INVOICES, connectionAt: "invoices", recordsAt: "edges[*].node",
                         hasNextAt: "pageInfo.hasNextPage", cursorAt: "pageInfo.endCursor",
                         bind: [window: [variables: [filter: "issued>={from} issued<{thru}"], field: "issued", sliceHours: 24,
                                         instantFormat: "ISO_INSTANT", parallel: 2],
                                ids: [variables: [filter: "{keyField} in ({ids})"], from: "LOOKUP_KEYS", chunk: 50, parallel: 2,
                                      keyFields: [invoiceNo: "invoiceNo"], idPattern: "[A-Z0-9-]{1,30}", separator: ","]],
                         paging: [firstVariable: "first", afterVariable: "after", pageSize: [budget: 400, costPerRecord: 4.5],
                                  maxPages: 1000],
                         reservation: 450],
                        [id: "lines", kind: "query", document: LINES, connectionAt: "lines", recordsAt: "nodes[*]",
                         hasNextAt: "page.more", cursorAt: "page.next",
                         bind: [ids: [variables: [filter: "invoiceNo in ({ids})"], from: "invoices.invoiceNo", chunk: 100]],
                         paging: [firstVariable: "first", afterVariable: "after", pageSize: 100], reservation: 300],
                        [id: "lineOnInvoice", kind: "join"],
                        [id: "perLine", kind: "split"],
                ],
                record: [fields: ["invoiceNo", "lineNo", "amount", "customerRef"], optionalFields: ["total"]],
                counts: [[name: "linesWithoutAmount"]],
        ]
        return d + overrides
    }

    private static List<String> errorsOf(Map d, String protocol = "GRAPHQL") {
        return DataSourceDefinitionSupport.check(protocol, d, CONNECTION_FIELDS)
    }

    private static Map withStep(int index, Map change) {
        Map d = definition()
        List steps = ((List) d.steps).collect { new LinkedHashMap((Map) it) }
        steps[index] = steps[index] + change
        return d + [steps: steps]
    }

    @Test
    void aWholeDefinitionForAnyGraphQlSystemPasses() {
        assertEquals([], errorsOf(definition()))
        Map parsed = DataSourceDefinitionSupport.parse("GRAPHQL", JsonOutput.toJson(definition()), CONNECTION_FIELDS)
        assertEquals("invoices", ((List<Map>) parsed.steps)[0].id)
    }

    @Test
    void onlyAQueryMayBeStored() {
        String mutation = 'mutation Wipe { deleteInvoices(all: true) { count } }'
        assertTrue(errorsOf(withStep(0, [document: mutation])).any { it.contains("Only a query may be stored, not a mutation") })
        String subscription = 'subscription Watch { invoices { invoiceNo } }'
        assertTrue(errorsOf(withStep(0, [document: subscription])).any { it.contains("not a subscription") })
        String two = INVOICES + '\nquery Other { invoices(first: 1) { edges { node { invoiceNo } } } }'
        assertTrue(errorsOf(withStep(0, [document: two])).any { it.contains("exactly one operation, found 2") })
        assertTrue(errorsOf(withStep(0, [document: "query {"])).any { it.contains("does not parse") })
    }

    @Test
    void introspectionIsRefusedEvenInsideAFragment() {
        String viaFragment = '''query Peek($filter: String, $first: Int, $after: String) {
  invoices(filter: $filter, first: $first, after: $after) { ...F pageInfo { hasNextPage endCursor } }
}
fragment F on InvoiceConnection { edges { node { invoiceNo } } meta: __schema { types { name } } }'''
        assertTrue(errorsOf(withStep(0, [document: viaFragment])).any { it.contains("may not ask for __schema") })
        String typename = INVOICES.replace("invoiceNo customerRef", "__typename invoiceNo customerRef")
        assertEquals([], errorsOf(withStep(0, [document: typename])))
    }

    @Test
    void everyVariableTheBindingFillsIsDeclaredAndEveryRequiredOneIsFilled() {
        Map undeclared = withStep(0, [bind: [ids: [variables: [where: "{keyField}:{ids}"], from: "LOOKUP_KEYS", chunk: 10]]])
        assertTrue(errorsOf(undeclared).any { it.contains("fills \$where, which the query does not declare") })
        // lines declares $filter String!; a binding that fills only paging leaves it empty.
        Map unfilled = withStep(1, [bind: [ids: [variables: [:], from: "invoices.invoiceNo", chunk: 10]]])
        assertTrue(errorsOf(unfilled).any { it.contains("nothing fills the required \$filter") })
    }

    @Test
    void templatesUseOnlyTheirOwnPlaceholders() {
        Map d = withStep(0, [bind: [window: [variables: [filter: "issued>={from} {ids}"], field: "issued", sliceHours: 24]]])
        assertTrue(errorsOf(d).any { it.contains("bind.window may use {from} {thru}, not {ids}") })
    }

    @Test
    void aStepReadsIdsOnlyFromAnEarlierStep() {
        Map later = withStep(0, [bind: [ids: [variables: [filter: "{ids}"], from: "lines.invoiceNo", chunk: 10]]])
        assertTrue(errorsOf(later).any { it.contains("bind.ids.from is LOOKUP_KEYS or <an earlier step>.<field>") })
    }

    @Test
    void aCallCannotHoldMoreThanTheCostLimit() {
        assertTrue(errorsOf(withStep(0, [reservation: 501])).any { it.contains("reservation 501 is above maxCostPerCall 500") })
    }

    @Test
    void theRootFieldAndAnswerPathsMustBeThere() {
        assertTrue(errorsOf(withStep(0, [connectionAt: "payments"])).any { it.contains("does not select 'payments' at its root") })
        assertTrue(errorsOf(withStep(0, [cursorAt: "page info"])).any { it.contains("needs cursorAt") })
    }

    @Test
    void theConnectionNamesOnlyFieldsTheConnectionRecordHas() {
        Map d = definition()
        d.connection = ((Map) d.connection) + [urlTemplate: "{host}/graphql"]
        assertTrue(errorsOf(d).any { it.contains("names host, which the connection record does not have") })
        d.connection = ((Map) d.connection) + [urlTemplate: "http://10.0.0.1/graphql"]
        assertTrue(errorsOf(d).any { it.contains("starts with https:// or a connection field") })
    }

    @Test
    void signInMethodsAreOnlyTheOnesCoreImplements() {
        Map d = definition()
        d.connection = ((Map) d.connection) + [auth: [kindField: "authType", kinds: [MAGIC: [method: "OAUTH9"]]]]
        assertTrue(errorsOf(d).any { it.contains("Auth kind 'MAGIC' uses NONE, BASIC, BEARER, HEADER, not 'OAUTH9'") })
    }

    @Test
    void stepsAreNamedOnceAndOfAKnownKind() {
        Map d = definition()
        d.steps = ((List) d.steps) + [[id: "invoices", kind: "query"], [id: "guess", kind: "script"]]
        List<String> errors = errorsOf(d)
        assertTrue(errors.any { it.contains("Two steps are named 'invoices'") })
        assertTrue(errors.any { it.contains("Step 'guess' is one of query, join, split, number, derive, count, filter, not 'script'") })
    }

    @Test
    void parseNamesEveryProblemAtOnceAndAnUnknownProtocolIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException) {
            DataSourceDefinitionSupport.parse("SOAP", JsonOutput.toJson([steps: []]), CONNECTION_FIELDS)
        }
        ["The protocol is GRAPHQL or REST_JSON, not 'SOAP'", "needs a connection", "needs limits", "needs steps",
         "needs record"].each { String expected -> assertTrue(e.message.contains(expected), expected) }
        assertThrows(IllegalArgumentException) { DataSourceDefinitionSupport.parse("GRAPHQL", "{not json", null) }
    }
}
