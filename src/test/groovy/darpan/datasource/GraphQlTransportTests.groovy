package darpan.datasource

import groovy.json.JsonOutput
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-176. The generic GraphQL transport: where to call, how to sign in, where the answer reports its cost bucket and
 * errors, and which codes are waited out, retried or handed back, all from the definition. A made-up system throughout.
 */
class GraphQlTransportTests {

    private static Map definition(Map limits = [:]) {
        return [connection: [urlTemplate: "{baseUrl}/api/graphql",
                             auth: [kindField: "authType", headersJsonField: "extraHeaders",
                                    kinds: [TOKEN: [method: "BEARER", tokenFields: ["token", "secret"]],
                                            KEY: [method: "HEADER", header: "X-Acme-Key", tokenFields: ["token"]],
                                            PASSWORD: [method: "BASIC", userField: "user", passwordField: "secret"],
                                            OPEN: [method: "NONE"]]]],
                limits: [maxCostPerCall: 1000, errorCodeAt: "errors[*].extensions.code", throttledCodes: ["SLOW_DOWN"],
                         costRefusedCodes: ["TOO_EXPENSIVE"], retryStatuses: [503], maxAttempts: 3, retryBackoffMillis: 5,
                         maxThrottleWaitSeconds: 60,
                         costBucket: [at: "extensions.cost.bucket", available: "left", maximum: "max", restoreRate: "perSecond"]] + limits]
    }

    private static final Map RECORD = [baseUrl: "https://acme.example/", authType: "token", token: "t-1", user: "u", secret: "s",
                                       extraHeaders: '{"X-Tenant":"blue"}']

    private static Closure answering(List<Map> answers, List<Map> seen) {
        return { String url, Map headers, String body ->
            seen << [url: url, headers: new LinkedHashMap(headers), body: body]
            Map a = answers.size() > 1 ? answers.remove(0) : answers[0]
            return [statusCode: a.status ?: 200, body: a.raw != null ? a.raw : JsonOutput.toJson(a.json)]
        }
    }

    private static GraphQlTransport transport(List<Map> answers, List<Map> seen, Map limits = [:], Map record = RECORD) {
        GraphQlTransport t = new GraphQlTransport(definition(limits), record, answering(answers, seen))
        t.sleeper = { long ms -> }
        return t
    }

    @Test
    void theUrlAndSignInComeFromTheDefinitionAndTheConnectionRecord() {
        List<Map> seen = []
        Map data = transport([[json: [data: [ok: true], errors: []]]], seen).execute("query Q { ok }", [a: 1], 10)
        assertEquals([ok: true], data)
        assertEquals("https://acme.example/api/graphql", seen[0].url)
        assertEquals("Bearer t-1", seen[0].headers.Authorization)
        assertEquals("blue", seen[0].headers."X-Tenant")
        assertEquals([query: "query Q { ok }", variables: [a: 1]], new groovy.json.JsonSlurper().parseText((String) seen[0].body))

        assertEquals("t-1", GraphQlTransport.buildHeaders(definition().connection as Map, RECORD + [authType: "KEY"])."X-Acme-Key")
        assertEquals("Basic " + "u:s".bytes.encodeBase64().toString(),
                GraphQlTransport.buildHeaders(definition().connection as Map, RECORD + [authType: "password"]).Authorization)
        assertFalse(GraphQlTransport.buildHeaders(definition().connection as Map, RECORD + [authType: "OPEN"]).containsKey("Authorization"))
    }

    @Test
    void extraHeadersCannotSignInOrRouteAndAnUnknownSignInIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException) {
            GraphQlTransport.buildHeaders(definition().connection as Map, RECORD + [extraHeaders: '{"Authorization":"Bearer stolen"}'])
        }
        assertTrue(e.message.contains("may not set 'Authorization'"))
        assertThrows(IllegalArgumentException) {
            GraphQlTransport.buildHeaders(definition().connection as Map, RECORD + [extraHeaders: '{"X-Bad":"a\\r\\nHost: evil"}'])
        }
        IllegalArgumentException kind = assertThrows(IllegalArgumentException) {
            GraphQlTransport.buildHeaders(definition().connection as Map, RECORD + [authType: "MAGIC"])
        }
        assertTrue(kind.message.contains("signs in as 'MAGIC', which the definition does not know"))
        IllegalArgumentException url = assertThrows(IllegalArgumentException) { GraphQlTransport.buildUrl("{host}/graphql", RECORD) }
        assertTrue(url.message.contains("has no host"))
    }

    @Test
    void aThrottledAnswerIsWaitedOutAndTheBucketIsReadWhereTheDefinitionSays() {
        List<Map> seen = []
        Map bucket = [extensions: [cost: [bucket: [left: 120, max: 1000, perSecond: 50]]]]
        GraphQlTransport t = transport([[json: [data: null, errors: [[message: "slow", extensions: [code: "SLOW_DOWN"]]]] + bucket],
                                        [json: [data: [n: 1], errors: []] + bucket]], seen)
        assertEquals([n: 1], t.execute("query Q { n }", [:], 100))
        assertEquals(2, seen.size())
        // The answer reported 120 left with nothing outstanding; it refills at 50/s from there.
        int left = t.governor.available
        assertTrue(left >= 120 && left < 200, "available ${left}")
    }

    @Test
    void aCostRefusalIsHandedBackAtOnceForASmallerPage() {
        List<Map> seen = []
        DataSourceCallException e = assertThrows(DataSourceCallException) {
            transport([[json: [errors: [[message: "too much", extensions: [code: "TOO_EXPENSIVE"]]]]]], seen).execute("query Q { n }", [:], 10)
        }
        assertTrue(e.costRefused)
        assertEquals(1, seen.size())
    }

    @Test
    void aRetryStatusIsAskedAgainUpToMaxAttemptsThenFailsClosed() {
        List<Map> seen = []
        assertEquals([n: 2], transport([[status: 503, raw: "<html>busy</html>"], [json: [data: [n: 2], errors: []]]], seen)
                .execute("query Q { n }", [:], 10))
        assertEquals(2, seen.size())
        List<Map> always = []
        DataSourceCallException e = assertThrows(DataSourceCallException) {
            transport([[status: 503, raw: ""]], always).execute("query Q { n }", [:], 10)
        }
        assertEquals("TRANSIENT", e.code)
        assertEquals(3, always.size())
    }

    @Test
    void otherErrorsFailAtOnceWithTheSystemsOwnCode() {
        DataSourceCallException code = assertThrows(DataSourceCallException) {
            transport([[json: [errors: [[message: "no such field", extensions: [code: "BAD_FIELD"]]]]]], []).execute("query Q { n }", [:], 10)
        }
        assertEquals("BAD_FIELD", code.code)
        assertEquals("REJECTED", assertThrows(DataSourceCallException) {
            transport([[status: 401, json: [errors: "not signed in"]]], []).execute("query Q { n }", [:], 10)
        }.code)
        assertEquals("UNPARSEABLE", assertThrows(DataSourceCallException) {
            transport([[status: 200, raw: "not json"]], []).execute("query Q { n }", [:], 10)
        }.code)
        assertEquals("HTTP_500", assertThrows(DataSourceCallException) {
            transport([[status: 500, json: [data: null]]], []).execute("query Q { n }", [:], 10)
        }.code)
    }
}
