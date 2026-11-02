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
 * DAR-BE-176. One GraphQL POST for a data-defined source, for any system. Where to call, how to sign in, where the
 * answer reports its cost bucket and errors, which codes mean "throttled" or "cost refused", and what is retried all
 * come from the definition's connection and limits; the connection record supplies the values.
 *
 * Throws on ANY error, never "no data": an unpaced or failed read must not look like an empty one. A throttled call
 * waits for the bucket and is asked again up to limits.maxThrottleWaitSeconds; a transient failure (a retry status, a
 * timeout or reset) up to limits.maxAttempts; a cost refusal is thrown at once (costRefused) for the caller to ask for
 * a smaller page. Every document is a query (DataSourceDefinitionSupport), so asking again is safe.
 */
class GraphQlTransport {

    static final long MIN_THROTTLE_BACKOFF_MILLIS = 1_000L
    static final String DEFAULT_ERROR_CODE_AT = "errors[*].extensions.code"
    /** Headers a connection's extra headers may never set: the sign-in, cookies, routing (audit M5.3). */
    static final Set<String> BLOCKED_HEADERS = (["authorization", "cookie", "host", "proxy-authorization", "proxy-authenticate",
            "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "forwarded", "content-type", "content-length"] as Set<String>).asImmutable()

    private static final Logger logger = LoggerFactory.getLogger(GraphQlTransport.class)

    final String url
    final Map<String, String> headers
    final CostBucketGovernor governor
    private final Map limits
    private final Closure sender
    private final AtomicInteger calls = new AtomicInteger()
    Closure sleeper = { long ms -> Thread.sleep(ms) }

    /**
     * definition: a checked DataSourceDefinition document. connectionRecord: the connector's config record as a plain
     * Map (decrypted fields included). sender (tests): { url, headers, body -> [statusCode, body] }.
     */
    GraphQlTransport(Map definition, Map connectionRecord, Closure sender = null) {
        Map connection = (Map) definition.connection
        this.limits = (Map) (definition.limits ?: [:])
        this.url = buildUrl((String) connection.urlTemplate, connectionRecord ?: [:])
        this.headers = buildHeaders(connection, connectionRecord ?: [:])
        int timeout = ((connection.timeoutSeconds ?: 120) as Number).intValue()
        int maxInFlight = ((limits.maxInFlight ?: 6) as Number).intValue()
        this.sender = sender ?: defaultSender(timeout)
        this.governor = sender == null ? CostBucketGovernor.shared(bucketKey(url, headers), maxInFlight) : new CostBucketGovernor(maxInFlight)
    }

    /**
     * The bucket belongs to the credential, not the host: two tenants or two connections on one URL must never pace or
     * starve each other. Keyed by the URL and a SHA-256 of every header sent (the sign-in included), so no secret is kept.
     */
    static String bucketKey(String url, Map<String, String> headers) {
        String signed = new TreeMap<String, String>(headers ?: [:]).collect { k, v -> "${k}:${v}" }.join("\n")
        return url + "|" + java.security.MessageDigest.getInstance("SHA-256")
                .digest(signed.getBytes(StandardCharsets.UTF_8)).encodeHex().toString()
    }

    int getCallCount() { return calls.get() }

    /** The answer's `data`. reservation: the cost this call holds against the bucket. */
    Map execute(String document, Map variables, int reservation) {
        Map payload = [query: document]
        if (variables) payload.variables = variables
        String json = JsonOutput.toJson(payload)
        String operation = (document =~ /query\s+(\w+)/).with { it.find() ? it.group(1) : "query" }
        int maxAttempts = Math.max(1, ((limits.maxAttempts ?: 3) as Number).intValue())
        long retryBackoff = Math.max(0L, ((limits.retryBackoffMillis ?: 1000) as Number).longValue())
        long maxThrottleWait = ((limits.maxThrottleWaitSeconds != null ? limits.maxThrottleWaitSeconds : 300) as Number).longValue() * 1000L
        long throttledWait = 0L
        int transientFailures = 0
        for (int attempt = 0; ; attempt++) {
            long wait = governor.sleepMillisFor(reservation)
            if (wait > 0L) sleeper.call(wait)
            int n = calls.incrementAndGet()
            long started = System.currentTimeMillis()
            Map body = null
            String outcome = "error"
            long backoff = 0L
            governor.acquireCall()
            try {
                Map answer = (Map) sender.call(url, headers, json)
                int status = ((answer?.statusCode ?: 0) as Number).intValue()
                body = parseBody(status, answer?.body as String)
                Map data = dataOf(status, body)
                outcome = "ok"
                return data
            } catch (DataSourceCallException e) {
                outcome = e.code
                if (e.costRefused) throw e
                if (isRetryStatusCode(e.code)) {
                    backoff = transientBackoff(++transientFailures, maxAttempts, retryBackoff, operation, e.message)
                } else if (codes("throttledCodes").contains(e.code) && throttledWait < maxThrottleWait) {
                    backoff = MIN_THROTTLE_BACKOFF_MILLIS + (long) (Math.random() * 1000L)
                } else {
                    throw e
                }
            } catch (IOException e) {
                outcome = "IO"
                backoff = transientBackoff(++transientFailures, maxAttempts, retryBackoff, operation, e.toString())
            } finally {
                governor.settle(reservation, bucketOf(body), (Map) limits.costBucket)
                governor.releaseCall()
                logger.info("data source call op={} n={} attempt={} outcome={} waitMs={} reserved={} ms={}",
                        operation, n, attempt, outcome, wait, reservation, System.currentTimeMillis() - started)
            }
            sleeper.call(backoff)
            throttledWait += backoff + wait
        }
    }

    private Map parseBody(int status, String raw) {
        Object parsed = null
        try { parsed = raw ? new JsonSlurper().parseText(raw) : null } catch (ignored) { }
        if (parsed instanceof Map) return (Map) parsed
        if (retryStatuses().contains(status)) throw new DataSourceCallException("HTTP_${status}".toString(), "HTTP ${status}: ${raw?.take(200)}".toString())
        throw new DataSourceCallException("UNPARSEABLE", "HTTP ${status}: the answer was not JSON: ${raw?.take(200)}".toString())
    }

    /**
     * GraphQL answers errors as a list of objects; an empty list is success. A plain string or object in `errors` is a
     * gateway's own refusal. The code is read at limits.errorCodeAt.
     */
    private Map dataOf(int status, Map body) {
        Object errors = body.errors
        if (errors instanceof List && !((List) errors).isEmpty()) {
            Object code = JsonPathValues.value(body, (limits.errorCodeAt ?: DEFAULT_ERROR_CODE_AT) as String)
            Object first = ((List) errors)[0]
            String message = first instanceof Map ? ((Map) first).message as String : first as String
            String c = code?.toString() ?: "GRAPHQL_ERROR"
            throw new DataSourceCallException(c, message ?: c, codes("costRefusedCodes").contains(c))
        }
        if (errors != null && !(errors instanceof List)) throw new DataSourceCallException("REJECTED", "HTTP ${status}: ${errors}".toString())
        if (retryStatuses().contains(status)) throw new DataSourceCallException("HTTP_${status}".toString(), "HTTP ${status}".toString())
        if (status < 200 || status >= 300) throw new DataSourceCallException("HTTP_${status}".toString(), "HTTP ${status} with no error body".toString())
        return (body.data instanceof Map) ? (Map) body.data : [:]
    }

    private Map bucketOf(Map body) {
        Map bucket = (Map) limits.costBucket
        if (body == null || bucket == null) return null
        Object status = JsonPathValues.value(body, (String) bucket.at)
        return status instanceof Map ? (Map) status : null
    }

    private long transientBackoff(int failures, int maxAttempts, long retryBackoff, String operation, String cause) {
        if (failures >= maxAttempts) {
            throw new DataSourceCallException("TRANSIENT", "${operation} failed ${failures} attempts; last: ${cause}".toString())
        }
        return retryBackoff * failures
    }

    private boolean isRetryStatusCode(String code) {
        return code?.startsWith("HTTP_") && retryStatuses().contains(code.substring(5) as int)
    }

    private Set<Integer> retryStatuses() {
        return ((limits.retryStatuses ?: []) as List).collect { (it as Number).intValue() } as Set<Integer>
    }

    private Set<String> codes(String key) {
        return ((limits[key] ?: []) as List).collect { it.toString() } as Set<String>
    }

    /** The URL template filled from the connection record; a field followed by "/" loses its own trailing slashes. */
    static String buildUrl(String template, Map record) {
        StringBuffer out = new StringBuffer()
        def m = (template =~ /\{([A-Za-z][A-Za-z0-9_]*)\}/)
        while (m.find()) {
            String value = record.get(m.group(1))?.toString()?.trim()
            if (!value) throw new IllegalArgumentException("The connection has no ${m.group(1)}; the definition's URL needs it.")
            boolean slashNext = m.end() < template.length() && template.charAt(m.end()) == '/' as char
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(slashNext ? value.replaceAll('/+$', '') : value))
        }
        m.appendTail(out)
        return out.toString()
    }

    /** Accept + Content-Type, the connection's extra headers (never sign-in or routing headers), then the sign-in. */
    static Map<String, String> buildHeaders(Map connection, Map record) {
        Map<String, String> headers = new LinkedHashMap<>()
        headers.put("Accept", "application/json")
        Map auth = (Map) connection.auth
        if (auth.headersJsonField) {
            Object raw = record.get(auth.headersJsonField)
            Object extra = raw instanceof Map ? raw : (raw?.toString()?.trim() ? new JsonSlurper().parseText(raw.toString()) : [:])
            if (!(extra instanceof Map)) throw new IllegalArgumentException("The connection's extra headers must be a JSON object.")
            ((Map) extra).each { k, v ->
                String name = k?.toString()?.trim()
                String value = v?.toString()
                if (!name || BLOCKED_HEADERS.contains(name.toLowerCase()) || !(name ==~ /[A-Za-z0-9_.-]{1,64}/)) {
                    throw new IllegalArgumentException("The connection's extra headers may not set '${name}'.")
                }
                if (value == null || value.length() > 4096 || value =~ /[\x00-\x1F\x7F]/) {
                    throw new IllegalArgumentException("The connection's extra header '${name}' has a value that cannot be sent.")
                }
                headers.put(name, value)
            }
        }
        Map kinds = (Map) auth.kinds
        String kind = auth.kindField ? record.get(auth.kindField)?.toString()?.trim()?.toUpperCase() : (kinds.size() == 1 ? kinds.keySet()[0] : null)
        // A connection whose kind field is blank signs in as the definition's defaultKind, when it names one.
        if (!kind && auth.defaultKind) kind = auth.defaultKind.toString().toUpperCase()
        Map spec = (Map) kinds.find { k, v -> k.toString().toUpperCase() == kind }?.value
        if (spec == null) throw new IllegalArgumentException("The connection signs in as '${kind}', which the definition does not know.")
        switch (spec.method) {
            case "BASIC":
                String user = record.get(spec.userField)?.toString()?.trim()
                String password = record.get(spec.passwordField)?.toString()
                if (!user || !password) throw new IllegalArgumentException("Basic sign-in needs ${spec.userField} and ${spec.passwordField}.")
                headers.put("Authorization", "Basic " + "${user}:${password}".getBytes(StandardCharsets.UTF_8).encodeBase64().toString())
                break
            case "BEARER":
            case "HEADER":
                String token = ((spec.tokenFields ?: []) as List).collect { record.get(it)?.toString()?.trim() }.find { it }
                if (!token) throw new IllegalArgumentException("Sign-in '${kind}' needs one of ${spec.tokenFields}.")
                if (spec.method == "BEARER") headers.put("Authorization", "Bearer " + token)
                else headers.put(spec.header as String, token)
                break
            default:
                break
        }
        headers.put("Content-Type", "application/json")
        return headers
    }

    private static Closure defaultSender(int readTimeoutSeconds) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
        return { String url, Map headers, String body ->
            def check = OutboundHttpPolicy.validate(url)
            if (!check.ok) throw new IllegalStateException("The source URL is blocked by the outbound policy: ${check.error}")
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(readTimeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            headers.each { k, v -> b.header(k as String, v as String) }
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
            return [statusCode: resp.statusCode(), body: resp.body()]
        }
    }
}
