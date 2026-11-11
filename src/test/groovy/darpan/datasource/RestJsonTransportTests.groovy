package darpan.datasource

import groovy.json.JsonOutput
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-234. The REST_JSON transport on a made-up system (an order list at ledger.acme.invalid): its pages are joined in
 * each paging mode, a throttle is waited out, and a private or metadata host is refused before anything is sent.
 */
class RestJsonTransportTests {

    static final Map CONNECTION = [baseUrl: "https://ledger.acme.invalid/", authType: "TOKEN", token: "t0k"]
    static final long FROM = Date.parse("yyyy-MM-dd'T'HH:mm:ssX", "2026-10-01T00:00:00Z").time
    static final long THRU = FROM + 86_400_000L

    static Map cursorRequest() {
        [method: "GET", path: "/v1/orders", query: [limit: '$first', cursor: '$after', updated_min: '$since', updated_max: '$until'],
         recordsAt: "data[*]", paging: [mode: "CURSOR", nextAt: "meta.next"]]
    }

    /** A checked definition: the page fields are filled by the check, as on load and on save. */
    static Map definition(Map request, Map limits = [:]) {
        Map d = [connection: [urlTemplate: "{baseUrl}/api", auth: [kindField: "authType", kinds: [TOKEN: [method: "BEARER", tokenFields: ["token"]]]]],
                 limits    : [maxCostPerCall: 100, retryStatuses: [502, 503], throttledCodes: ["HTTP_429"], maxAttempts: 3, retryBackoffMillis: 5] + limits,
                 steps     : [[id    : "orders", kind: "query", document: JsonOutput.toJson(request),
                               bind  : [window: [variables: [since: "{from}", until: "{thru}"], field: "updated", sliceHours: 24, instantFormat: "ISO_INSTANT"]],
                               paging: [firstVariable: "first", afterVariable: "after", pageSize: 2], reservation: 1]],
                 record    : [fields: ["orderNo"], optionalFields: ["updated"]]]
        return DataSourceDefinitionSupport.parse("REST_JSON", JsonOutput.toJson(d))
    }

    static Map order(String no) { [orderNo: no, updated: "2026-10-01T05:00:00Z"] }

    static Map query(String url) {
        String q = URI.create(url).rawQuery
        return q ? q.split("&").collectEntries { String kv -> List<String> p = kv.split("=", 2) as List<String>; [(p[0]): URLDecoder.decode(p[1], "UTF-8")] } : [:]
    }

    static List<String> run(Map definition, RestJsonTransport transport) {
        List<String> seen = []
        new DataSourceRunner(definition, transport).run([mode: "window", from: FROM, thru: THRU]) { List<Map> batch -> seen.addAll(batch*.orderNo) }
        return seen
    }

    @Test
    void cursorPagesAreJoinedAndTheFirstPageSendsNoCursor() {
        List<String> urls = []
        Map pages = [(null): [data: [order("A1"), order("A2")], meta: [next: "c2"]], c2: [data: [order("A3"), order("A4")], meta: [next: "c3"]],
                     c3: [data: [order("A5")], meta: [next: null]]]
        def t = new RestJsonTransport(definition(cursorRequest()), CONNECTION, { String m, String url, Map h, String b ->
            urls << url
            [statusCode: 200, body: JsonOutput.toJson(pages[query(url).cursor])]
        })
        assertEquals(["A1", "A2", "A3", "A4", "A5"], run(definition(cursorRequest()), t))
        assertEquals(3, urls.size())
        assertTrue(urls.every { it.startsWith("https://ledger.acme.invalid/api/v1/orders?") }, urls.toString())
        assertEquals(null, query(urls[0]).cursor, "the first page names no cursor")
        assertEquals(["2", "c2", "2026-10-01T00:00:00Z"], [query(urls[1]).limit, query(urls[1]).cursor, query(urls[1]).updated_min])
        assertEquals("Bearer t0k", t.headers.Authorization)
    }

    @Test
    void offsetPagesAdvanceByWhatCameBackAndStopOnAShortPage() {
        Map request = cursorRequest() + [query: [size: '$first', from: '$after'], paging: [mode: "OFFSET", sizeVariable: "first", offsetVariable: "after"]]
        List<String> froms = []
        List<List<Map>> pages = [[order("B1"), order("B2")], [order("B3"), order("B4")], [order("B5")]]
        def t = new RestJsonTransport(definition(request), CONNECTION, { String m, String url, Map h, String b ->
            String from = query(url).from
            froms << from
            [statusCode: 200, body: JsonOutput.toJson([data: pages[((from ?: "0") as int).intdiv(2)]])]
        })
        assertEquals(["B1", "B2", "B3", "B4", "B5"], run(definition(request), t))
        assertEquals([null, "2", "4"], froms)
    }

    @Test
    void aRelativeNextLinkIsFollowedOnTheSameHost() {
        Map request = cursorRequest() + [paging: [mode: "NEXT_LINK", nextAt: "links.next", cursorVariable: "after"]]
        List<String> urls = []
        def t = new RestJsonTransport(definition(request), CONNECTION, { String m, String url, Map h, String b ->
            urls << url
            url.contains("page=2") ? [statusCode: 200, body: JsonOutput.toJson([data: [order("C2")], links: [next: null]])]
                    : [statusCode: 200, body: JsonOutput.toJson([data: [order("C1")], links: [next: "/api/v1/orders?page=2"]])]
        })
        assertEquals(["C1", "C2"], run(definition(request), t))
        assertEquals("https://ledger.acme.invalid/api/v1/orders?page=2", urls[1])
    }

    @Test
    void aNextLinkToAnotherHostIsRefusedAndNeverCalled() {
        Map request = cursorRequest() + [paging: [mode: "NEXT_LINK", nextAt: "links.next", cursorVariable: "after"]]
        List<String> urls = []
        def t = new RestJsonTransport(definition(request), CONNECTION, { String m, String url, Map h, String b ->
            urls << url
            [statusCode: 200, body: JsonOutput.toJson([data: [order("C1")], links: [next: "https://collector.example.invalid/steal"]])]
        })
        DataSourceCallException e = assertThrows(DataSourceCallException) { run(definition(request), t) }
        assertEquals("BAD_NEXT_LINK", e.code)
        assertEquals(1, urls.size(), "the sign-in never goes to the other host")
    }

    @Test
    void aThrottleIsWaitedOutForItsRetryAfter() {
        int n = 0
        List<Long> slept = []
        def t = new RestJsonTransport(definition(cursorRequest()), CONNECTION, { String m, String url, Map h, String b ->
            ++n <= 2 ? [statusCode: 429, body: "slow down", headers: ["retry-after": "2"]]
                    : [statusCode: 200, body: JsonOutput.toJson([data: [order("D1")], meta: [next: null]])]
        })
        t.sleeper = { long ms -> slept << ms }
        assertEquals(["D1"], run(definition(cursorRequest()), t))
        assertEquals(3, n)
        assertEquals([2000L, 2000L], slept.findAll { it > 0 })
    }

    @Test
    void aThrottleThatOutlastsItsLimitFails() {
        def t = new RestJsonTransport(definition(cursorRequest(), [maxThrottleWaitSeconds: 3]), CONNECTION,
                { String m, String url, Map h, String b -> [statusCode: 429, body: "", headers: ["Retry-After": "2"]] })
        t.sleeper = { long ms -> }
        assertEquals("HTTP_429", assertThrows(DataSourceCallException) { run(definition(cursorRequest()), t) }.code)
    }

    @Test
    void aTransientStatusIsRetriedThenGivenUp() {
        int n = 0
        def t = new RestJsonTransport(definition(cursorRequest()), CONNECTION, { String m, String url, Map h, String b -> n++; [statusCode: 503, body: ""] })
        t.sleeper = { long ms -> }
        assertEquals("TRANSIENT", assertThrows(DataSourceCallException) { run(definition(cursorRequest()), t) }.code)
        assertEquals(3, n)
    }

    @Test
    void anAnswerWithoutItsRecordsIsAnErrorNotAnEmptyRead() {
        def t = new RestJsonTransport(definition(cursorRequest()), CONNECTION, { String m, String url, Map h, String b ->
            [statusCode: 200, body: JsonOutput.toJson([error: "maintenance"])]
        })
        assertEquals("NO_RECORDS", assertThrows(DataSourceCallException) { run(definition(cursorRequest()), t) }.code)
    }

    @Test
    void aPrivateOrMetadataHostIsRefusedBeforeAnythingIsSent() {
        ["https://169.254.169.254", "https://10.1.2.3", "https://127.0.0.1", "https://localhost", "http://ledger.acme.invalid"].each { String base ->
            int sent = 0
            def t = new RestJsonTransport(definition(cursorRequest()), CONNECTION + [baseUrl: base], { String m, String url, Map h, String b ->
                sent++; [statusCode: 200, body: "{}"]
            })
            DataSourceCallException e = assertThrows(DataSourceCallException) { run(definition(cursorRequest()), t) }
            assertEquals("BLOCKED", e.code, base)
            assertEquals(0, sent, base)
        }
    }

    @Test
    void aPostSendsItsBodyWithTheRunsValues() {
        Map request = [method: "POST", path: "/v1/orders/search", body: [filter: [since: '$since', until: '$until'], page: [size: '$first', after: '$after']],
                       recordsAt: "data[*]", paging: [mode: "CURSOR", nextAt: "meta.next"]]
        List<String> bodies = []
        def t = new RestJsonTransport(definition(request), CONNECTION, { String m, String url, Map h, String b ->
            assertEquals("POST", m)
            bodies << b
            [statusCode: 200, body: JsonOutput.toJson([data: [order("E1")], meta: [:]])]
        })
        assertEquals(["E1"], run(definition(request), t))
        assertEquals([filter: [since: "2026-10-01T00:00:00Z", until: "2026-10-02T00:00:00Z"], page: [size: 2, after: null]],
                new groovy.json.JsonSlurper().parseText(bodies[0]))
    }

    @Test
    void aBadRequestIsRefusedWithItsReasons() {
        Map d = [connection: [urlTemplate: "{baseUrl}", auth: [kinds: [NONE: [method: "NONE"]]]], limits: [maxCostPerCall: 10],
                 steps: [[id: "orders", kind: "query", connectionAt: "orders",
                          document: JsonOutput.toJson([method: "DELETE", path: "v1/../admin?x=1", query: [q: '$nobody'],
                                                       recordsAt: "data[*]", paging: [mode: "NEXT_LINK", nextAt: "links.next", cursorVariable: "page"]]),
                          bind: [all: [variables: [:]]], paging: [firstVariable: "first", afterVariable: "after", pageSize: 2], reservation: 1]],
                 record: [fields: ["orderNo"]]]
        List<String> errors = DataSourceDefinitionSupport.check("REST_JSON", d)
        ["connectionAt is page", "method is GET or POST", "path starts with /", "NEXT_LINK paging names the step's afterVariable",
         'uses $nobody'].each { String part -> assertTrue(errors.any { it.contains(part) }, "${part} in ${errors}") }
        assertTrue(DataSourceDefinitionSupport.check("REST_JSON", d + [steps: [((List<Map>) d.steps)[0] + [document: "not json"]]])
                .any { it.contains("the request as a JSON object") })
    }
}
