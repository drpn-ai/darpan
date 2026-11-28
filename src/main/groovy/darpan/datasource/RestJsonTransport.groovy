package darpan.datasource

import darpan.facade.common.OutboundHttpClient
import darpan.facade.common.OutboundHttpPolicy
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/**
 * DAR-BE-234. One JSON-over-HTTP call for a data-defined source, for any system: the REST_JSON protocol beside
 * GraphQlTransport, with the same connection (URL template, sign-in, extra headers) and the same limits (retries,
 * throttle wait, cost bucket, calls in flight).
 *
 * A REST_JSON query step's document is its request, as JSON (DataSourceDefinitionSupport.checkRestRequest):
 * {method: GET|POST, path (after the connection URL), query {name: value}, body (POST), recordsAt (the records' path in
 * the answer), paging: {mode: CURSOR (nextAt: the next cursor's path) | OFFSET (sizeVariable, offsetVariable) |
 * NEXT_LINK (nextAt: the next page's URL, cursorVariable)}}. A value "$name" is the run's variable of that name, and a
 * query parameter whose variable is unset is left out. The answer is the runner's page: {page: {records, hasNext, cursor}}.
 *
 * Every URL, a next link included, passes the outbound policy before it is called, and a next link must stay on the
 * connection's own scheme, host and port: the sign-in is never sent anywhere else. Throws on any error, never "no data".
 */
class RestJsonTransport {

    static final String PAGE = "page"
    static final List<String> METHODS = ["GET", "POST"].asImmutable()
    static final List<String> PAGING_MODES = ["CURSOR", "OFFSET", "NEXT_LINK"].asImmutable()
    static final long MAX_RETRY_AFTER_MILLIS = 60_000L

    private static final Logger logger = LoggerFactory.getLogger(RestJsonTransport.class)

    final String baseUrl
    final Map<String, String> headers
    final CostBucketGovernor governor
    private final Map limits
    private final Closure sender
    private final AtomicInteger calls = new AtomicInteger()
    Closure sleeper = { long ms -> Thread.sleep(ms) }

    /** sender (tests): { method, url, headers, body -> [statusCode, body, headers: {name: value}] }. */
    RestJsonTransport(Map definition, Map connectionRecord, Closure sender = null) {
        Map connection = (Map) definition.connection
        this.limits = (Map) (definition.limits ?: [:])
        this.baseUrl = GraphQlTransport.buildUrl((String) connection.urlTemplate, connectionRecord ?: [:]).replaceAll('/+$', '')
        this.headers = GraphQlTransport.buildHeaders(connection, connectionRecord ?: [:])
        int timeout = ((connection.timeoutSeconds ?: 120) as Number).intValue()
        int maxInFlight = ((limits.maxInFlight ?: 6) as Number).intValue()
        this.sender = sender ?: defaultSender(timeout)
        this.governor = sender == null ? CostBucketGovernor.shared(GraphQlTransport.bucketKey(baseUrl, headers), maxInFlight) : new CostBucketGovernor(maxInFlight)
    }

    int getCallCount() { return calls.get() }

    /** The runner's page for one request: [page: [records, hasNext, cursor]]. */
    Map execute(String document, Map variables, int reservation) {
        Map request = (Map) new JsonSlurper().parseText(document)
        Map paging = (Map) (request.paging ?: [mode: "CURSOR"])
        String method = (request.method ?: "GET").toString()
        String link = paging.mode == "NEXT_LINK" ? variables?.get(paging.cursorVariable)?.toString() : null
        String url = link ? sameOrigin(link) : baseUrl + fill(request.path?.toString() ?: "", variables) + queryString((Map) request.query, variables)
        String body = method == "POST" ? JsonOutput.toJson(fillValue(request.body ?: [:], variables)) : null
        Map answer = call(method, url, body, reservation)
        Object records = JsonPathValues.value(answer, rootOf((String) request.recordsAt)) == null ? null :
                JsonPathValues.values(answer, (String) request.recordsAt).findAll { it instanceof Map }
        if (records == null) throw new DataSourceCallException("NO_RECORDS", "The answer has no ${rootOf((String) request.recordsAt)}.".toString())
        List<Map> page = (List<Map>) records
        String next = null
        boolean hasNext
        switch (paging.mode) {
            case "OFFSET":
                long offset = (variables?.get(paging.offsetVariable) ?: 0) as long
                int asked = ((variables?.get(paging.sizeVariable) ?: 0) as Number).intValue()
                hasNext = !page.isEmpty() && page.size() >= asked
                next = hasNext ? (offset + page.size()).toString() : null
                break
            default:
                next = JsonPathValues.value(answer, (String) paging.nextAt)?.toString()?.trim() ?: null
                hasNext = next != null
        }
        return [(PAGE): [records: page, hasNext: hasNext, cursor: next]]
    }

    /** A next link (absolute, or relative to the connection URL) on the connection's own scheme, host and port, or refused. */
    private String sameOrigin(String link) {
        URI base = URI.create(baseUrl), next
        try { next = base.resolve(link) } catch (IllegalArgumentException e) { throw new DataSourceCallException("BAD_NEXT_LINK", "The next link is not a URL.") }
        if (next.scheme?.toLowerCase() != base.scheme?.toLowerCase() || next.host?.toLowerCase() != base.host?.toLowerCase() || next.port != base.port) {
            throw new DataSourceCallException("BAD_NEXT_LINK", "The next link leaves the connection's host.")
        }
        return next.toString()
    }

    private Map call(String method, String url, String body, int reservation) {
        Map retry = GraphQlTransport.retryLimitsOf(limits)
        int maxAttempts = (int) retry.maxAttempts
        long retryBackoff = (long) retry.retryBackoff
        long maxThrottleWait = (long) retry.maxThrottleWait
        long throttledWait = 0L
        int transientFailures = 0
        def check = OutboundHttpPolicy.validate(url)
        if (!check.ok) throw new DataSourceCallException("BLOCKED", "The source URL is blocked by the outbound policy: ${check.error}".toString())
        for (int attempt = 0; ; attempt++) {
            long wait = governor.sleepMillisFor(reservation)
            if (wait > 0L) sleeper.call(wait)
            int n = calls.incrementAndGet()
            long started = System.currentTimeMillis()
            Map parsed = null
            String outcome = "error"
            long backoff = 0L
            long retryAfter = 0L
            GraphQlTransport.acquireOrGiveBack(governor, reservation)
            try {
                Map answer = (Map) sender.call(method, url, headers, body)
                int status = ((answer?.statusCode ?: 0) as Number).intValue()
                String code = "HTTP_${status}".toString()
                if (GraphQlTransport.codesOf(limits, "throttledCodes").contains(code)) {
                    retryAfter = retryAfterMillis(answer?.headers)
                    throw new DataSourceCallException(code, "HTTP ${status}: throttled".toString())
                }
                if (GraphQlTransport.retryStatusesOf(limits).contains(status)) throw new DataSourceCallException(code, "HTTP ${status}".toString())
                if (status < 200 || status >= 300) throw new DataSourceCallException(code, "HTTP ${status}: ${(answer?.body as String)?.take(200)}".toString())
                parsed = parseBody(status, answer?.body as String)
                outcome = "ok"
                return parsed
            } catch (DataSourceCallException e) {
                outcome = e.code
                if (GraphQlTransport.codesOf(limits, "throttledCodes").contains(e.code) && throttledWait < maxThrottleWait) {
                    // The source's Retry-After when it gives one (at most a minute), else the GraphQL throttle pause.
                    backoff = retryAfter > 0L ? retryAfter : GraphQlTransport.MIN_THROTTLE_BACKOFF_MILLIS + (long) (Math.random() * 1000L)
                } else if (GraphQlTransport.isRetryStatusCode(limits, e.code)) {
                    backoff = GraphQlTransport.transientBackoff(++transientFailures, maxAttempts, retryBackoff, URI.create(url).path, e.message)
                } else {
                    throw e
                }
            } catch (IOException e) {
                outcome = "IO"
                backoff = GraphQlTransport.transientBackoff(++transientFailures, maxAttempts, retryBackoff, URI.create(url).path, e.toString())
            } finally {
                governor.settle(reservation, GraphQlTransport.bucketOf(limits, parsed), (Map) limits.costBucket)
                governor.releaseCall()
                logger.info("data source REST call n={} attempt={} outcome={} waitMs={} reserved={} ms={}",
                        n, attempt, outcome, wait, reservation, System.currentTimeMillis() - started)
            }
            sleeper.call(backoff)
            throttledWait += backoff + wait
        }
    }

    /** Retry-After in seconds, capped at MAX_RETRY_AFTER_MILLIS; 0 when absent or not a number. */
    private static long retryAfterMillis(Object headers) {
        Object v = headers instanceof Map ? ((Map) headers).find { k, x -> k?.toString()?.equalsIgnoreCase("Retry-After") }?.value : null
        String t = v?.toString()?.trim()
        return t ==~ /\d{1,6}/ ? Math.min((t as long) * 1000L, MAX_RETRY_AFTER_MILLIS) : 0L
    }

    private static Map parseBody(int status, String raw) {
        Object parsed = null
        try { parsed = raw ? new JsonSlurper().parseText(raw) : null } catch (ignored) { }
        if (parsed instanceof Map) return (Map) parsed
        if (parsed instanceof List) return [records: parsed]
        throw new DataSourceCallException("UNPARSEABLE", "HTTP ${status}: the answer was not JSON: ${raw?.take(200)}".toString())
    }

    /** The first field of a path ("data" of "data[*].x"): present in the answer, or the answer is not this read's. */
    private static String rootOf(String path) { path?.split(/[.\[]/, 2)?.getAt(0) }

    /** "$name" is the variable's value; anything else is as written. */
    private static Object fillValue(Object template, Map variables) {
        if (template instanceof Map) return ((Map) template).collectEntries { k, v -> [(k): fillValue(v, variables)] }
        if (template instanceof List) return ((List) template).collect { fillValue(it, variables) }
        String t = template?.toString()
        return t != null && t ==~ /\$[A-Za-z][A-Za-z0-9_]*/ ? variables?.get(t.substring(1)) : template
    }

    /**
     * A path's "$name" segments filled and URL-encoded. A value that is blank, "." or ".." is refused: encoding leaves dots
     * as they are, and a dot segment would move the request to another path on the host.
     */
    static String fill(String path, Map variables) {
        return path.replaceAll(/\$([A-Za-z][A-Za-z0-9_]*)/) { List m ->
            Object v = variables?.get(m[1])
            if (v == null) throw new IllegalArgumentException("The request path needs \$${m[1]}, which the run did not set.")
            if (v.toString().trim() in ["", ".", ".."]) {
                throw new DataSourceCallException("BAD_PATH_VALUE", "The request path's \$${m[1]} is '${v}'.".toString())
            }
            URLEncoder.encode(v.toString(), StandardCharsets.UTF_8).replace("+", "%20")
        }
    }

    /** "?a=1&b=2" from the query, a parameter whose "$name" is unset left out. */
    private static String queryString(Map query, Map variables) {
        List<String> parts = []
        (query ?: [:]).each { k, v ->
            Object value = fillValue(v, variables)
            if (value == null) return
            parts << URLEncoder.encode(k.toString(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value.toString(), StandardCharsets.UTF_8)
        }
        return parts ? "?" + parts.join("&") : ""
    }

    /** DAR-BE-262: through OutboundHttpClient, which connects only to addresses the outbound policy checked (no DNS rebinding). */
    private static Closure defaultSender(int readTimeoutSeconds) {
        return { String method, String url, Map headers, String body ->
            Map sent = method == "GET" ? headers.findAll { k, v -> k != "Content-Type" } : headers
            return OutboundHttpClient.send(OutboundHttpClient.shared(), method, url, sent, method == "POST" ? (body ?: "{}") : null, readTimeoutSeconds)
        }
    }
}
