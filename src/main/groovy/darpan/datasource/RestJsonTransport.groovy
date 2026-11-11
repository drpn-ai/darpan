package darpan.datasource

import darpan.facade.common.OutboundHttpPolicy
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
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
        int maxAttempts = Math.max(1, ((limits.maxAttempts ?: 3) as Number).intValue())
        long retryBackoff = Math.max(0L, ((limits.retryBackoffMillis ?: 1000) as Number).longValue())
        long maxThrottleWait = ((limits.maxThrottleWaitSeconds != null ? limits.maxThrottleWaitSeconds : 300) as Number).longValue() * 1000L
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
            governor.acquireCall()
            try {
                Map answer = (Map) sender.call(method, url, headers, body)
                int status = ((answer?.statusCode ?: 0) as Number).intValue()
                String code = "HTTP_${status}".toString()
                if (codes("throttledCodes").contains(code)) {
                    retryAfter = retryAfterMillis(answer?.headers)
                    throw new DataSourceCallException(code, "HTTP ${status}: throttled".toString())
                }
                if (retryStatuses().contains(status)) throw new DataSourceCallException(code, "HTTP ${status}".toString())
                if (status < 200 || status >= 300) throw new DataSourceCallException(code, "HTTP ${status}: ${(answer?.body as String)?.take(200)}".toString())
                parsed = parseBody(status, answer?.body as String)
                outcome = "ok"
                return parsed
            } catch (DataSourceCallException e) {
                outcome = e.code
                if (codes("throttledCodes").contains(e.code) && throttledWait < maxThrottleWait) {
                    // The source's Retry-After when it gives one (at most a minute), else the GraphQL throttle pause.
                    backoff = retryAfter > 0L ? retryAfter : GraphQlTransport.MIN_THROTTLE_BACKOFF_MILLIS + (long) (Math.random() * 1000L)
                } else if (isRetryStatusCode(e.code)) {
                    backoff = transientBackoff(++transientFailures, maxAttempts, retryBackoff, url, e.message)
                } else {
                    throw e
                }
            } catch (IOException e) {
                outcome = "IO"
                backoff = transientBackoff(++transientFailures, maxAttempts, retryBackoff, url, e.toString())
            } finally {
                governor.settle(reservation, bucketOf(parsed), (Map) limits.costBucket)
                governor.releaseCall()
                logger.info("data source REST call n={} attempt={} outcome={} waitMs={} reserved={} ms={}",
                        n, attempt, outcome, wait, reservation, System.currentTimeMillis() - started)
            }
            sleeper.call(backoff)
            throttledWait += backoff + wait
        }
    }

    /** Retry-After in seconds, capped at MAX_RETRY_AFTER_MILLIS; 0 when absent or not a number. */
    static long retryAfterMillis(Object headers) {
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

    private Map bucketOf(Map body) {
        Map bucket = (Map) limits.costBucket
        if (body == null || bucket == null) return null
        Object status = JsonPathValues.value(body, (String) bucket.at)
        return status instanceof Map ? (Map) status : null
    }

    private long transientBackoff(int failures, int maxAttempts, long retryBackoff, String url, String cause) {
        if (failures >= maxAttempts) {
            throw new DataSourceCallException("TRANSIENT", "${URI.create(url).path} failed ${failures} attempts; last: ${cause}".toString())
        }
        return retryBackoff * failures
    }

    private boolean isRetryStatusCode(String code) { code?.startsWith("HTTP_") && retryStatuses().contains(code.substring(5) as int) }

    private Set<Integer> retryStatuses() { ((limits.retryStatuses ?: []) as List).collect { (it as Number).intValue() } as Set<Integer> }

    private Set<String> codes(String key) { ((limits[key] ?: []) as List).collect { it.toString() } as Set<String> }

    /** The first field of a path ("data" of "data[*].x"): present in the answer, or the answer is not this read's. */
    static String rootOf(String path) { path?.split(/[.\[]/, 2)?.getAt(0) }

    /** "$name" is the variable's value; anything else is as written. */
    static Object fillValue(Object template, Map variables) {
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
    static String queryString(Map query, Map variables) {
        List<String> parts = []
        (query ?: [:]).each { k, v ->
            Object value = fillValue(v, variables)
            if (value == null) return
            parts << URLEncoder.encode(k.toString(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value.toString(), StandardCharsets.UTF_8)
        }
        return parts ? "?" + parts.join("&") : ""
    }

    private static Closure defaultSender(int readTimeoutSeconds) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).followRedirects(HttpClient.Redirect.NEVER).build()
        return { String method, String url, Map headers, String body ->
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(readTimeoutSeconds))
            if (method == "POST") b.POST(HttpRequest.BodyPublishers.ofString(body ?: "{}", StandardCharsets.UTF_8))
            else b.GET()
            headers.each { k, v -> if (!(method == "GET" && k == "Content-Type")) b.header(k as String, v as String) }
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
            return [statusCode: resp.statusCode(), body: resp.body(), headers: resp.headers().map().collectEntries { k, v -> [(k): v ? v[0] : null] }]
        }
    }
}
