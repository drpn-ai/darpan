package darpan.facade.common

import org.eclipse.jetty.client.CompletableResponseListener
import org.eclipse.jetty.client.ContentResponse
import org.eclipse.jetty.client.HttpClient
import org.eclipse.jetty.client.InputStreamResponseListener
import org.eclipse.jetty.client.Request
import org.eclipse.jetty.client.Response
import org.eclipse.jetty.client.StringRequestContent
import org.eclipse.jetty.http.HttpCookieStore
import org.eclipse.jetty.http.HttpField
import org.eclipse.jetty.util.Promise
import org.eclipse.jetty.util.SocketAddressResolver
import org.eclipse.jetty.util.thread.QueuedThreadPool
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Function

/**
 * DAR-BE-262. The HTTP client for tenant-supplied URLs (data-defined GraphQL and REST sources). OutboundHttpPolicy.validate
 * checks a host once; a client that resolves the name again when it connects could be sent somewhere else in between
 * (DNS rebinding: public on the check, 127.0.0.1 or 169.254.169.254 on the connect). Here the client's own DNS step IS
 * the check: {@link VettedAddressResolver} resolves the host once per new connection, refuses unless every address
 * passes OutboundHttpPolicy.resolveVetted, and hands Jetty exactly those addresses to connect to.
 *
 * TLS is unchanged by the pin: Jetty still sends the URL's host name as SNI and verifies the certificate against it
 * (SslContextFactory.Client, endpoint identification HTTPS, the JVM trust store). Redirects are not followed.
 *
 * One shared client (thread-safe, daemon threads, started on first use) serves every transport, so it keeps NO cookies:
 * Jetty's default in-memory cookie store would replay one tenant's Set-Cookie on another tenant's call to the same host.
 *
 * Jetty sets the connect timeout per client, so a caller with its own connect timeout (the OMS connection probe's 5 s,
 * an agent query's 15 s) gets the shared client for that timeout: one per distinct whole-second value.
 * {@link #stream} hands the answer over as it arrives, for bodies too large to hold (a month of Shopify bulk JSONL).
 */
class OutboundHttpClient {
    static final long CONNECT_TIMEOUT_MILLIS = 30_000L
    /** The largest answer read into memory: well past any page a source returns, and a bound on a hostile one. */
    static final int MAX_RESPONSE_BYTES = 256 * 1024 * 1024

    /** Connect timeouts are whole seconds within these bounds, so a configured value cannot multiply clients unbounded. */
    static final long MIN_CONNECT_TIMEOUT_MILLIS = 1_000L
    static final long MAX_CONNECT_TIMEOUT_MILLIS = 120_000L

    private static final ConcurrentHashMap<Long, HttpClient> sharedByConnectTimeout = new ConcurrentHashMap<>()

    /** The shared guarded client (30 s connect timeout), started on first use. */
    static HttpClient shared() { return shared(CONNECT_TIMEOUT_MILLIS) }

    /** The shared guarded client for this connect timeout (rounded up to whole seconds, 1 to 120), started on first use. */
    static HttpClient shared(long connectTimeoutMillis) {
        long clamped = Math.max(MIN_CONNECT_TIMEOUT_MILLIS, Math.min(MAX_CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis))
        long wholeSeconds = ((clamped + 999L).intdiv(1000L) as long) * 1000L
        return sharedByConnectTimeout.computeIfAbsent(wholeSeconds) { Long millis -> newClient(new VettedAddressResolver(), millis) }
    }

    /** A started client whose DNS step is {@code resolver}. Tests pass a resolver with their own lookup. */
    static HttpClient newClient(SocketAddressResolver resolver) { return newClient(resolver, CONNECT_TIMEOUT_MILLIS) }

    static HttpClient newClient(SocketAddressResolver resolver, long connectTimeoutMillis) {
        HttpClient client = new HttpClient()
        QueuedThreadPool threads = new QueuedThreadPool()
        threads.setName("darpan-outbound-http")
        threads.setDaemon(true)
        client.setExecutor(threads)
        client.setScheduler(new ScheduledExecutorScheduler("darpan-outbound-http-scheduler", true))
        client.setSocketAddressResolver(resolver)
        client.setConnectTimeout(connectTimeoutMillis)
        client.setFollowRedirects(false)
        // Shared across tenants: never store or replay cookies (Jetty keeps them by default; java.net.http did not).
        client.setHttpCookieStore(new HttpCookieStore.Empty())
        client.start()
        return client
    }

    /**
     * One call through {@code client}: [statusCode, body, headers: {name: first value}]. A refused address surfaces as the
     * IOException-family cause (UnknownHostException with the policy's reason), never as an answer.
     */
    static Map send(HttpClient client, String method, String url, Map headers, String body, int timeoutSeconds) {
        Request request = client.newRequest(url).method(method).timeout(Math.max(1, timeoutSeconds), TimeUnit.SECONDS)
        request.headers { fields -> (headers ?: [:]).each { k, v -> if (k != null && v != null) fields.put(k as String, v as String) } }
        if (body != null) request.body(new StringRequestContent("application/json", body, StandardCharsets.UTF_8))
        ContentResponse response
        try {
            response = new CompletableResponseListener(request, MAX_RESPONSE_BYTES).send().get()
        } catch (ExecutionException e) {
            Throwable cause = e.cause ?: e
            if (cause instanceof IOException) throw (IOException) cause
            throw new IOException(cause.message ?: cause.class.simpleName, cause)
        }
        Map<String, String> responseHeaders = [:]
        for (HttpField field : response.headers) {
            if (!responseHeaders.containsKey(field.name)) responseHeaders.put(field.name, field.value)
        }
        return [statusCode: response.status, body: response.contentAsString, headers: responseHeaders]
    }

    /**
     * One call whose answer is streamed, never buffered and not size-capped: {@code consumer} gets
     * [statusCode, headers: {name: first value}, body: InputStream] and reads as much as it wants; the stream is closed
     * afterwards, which abandons whatever was not read. {@code idleTimeoutMillis} bounds each wait for the answer or its
     * next bytes (a read timeout), not the whole exchange, so a large download is not cut off for taking a long time.
     * A refused address or a failed exchange surfaces as an IOException; the consumer's own exceptions pass through.
     */
    static <T> T stream(HttpClient client, String method, String url, Map headers, String body, long idleTimeoutMillis,
                        Closure<T> consumer) {
        long idle = Math.max(1L, idleTimeoutMillis)
        Request request = client.newRequest(url).method(method).idleTimeout(idle, TimeUnit.MILLISECONDS)
        request.headers { fields -> (headers ?: [:]).each { k, v -> if (k != null && v != null) fields.put(k as String, v as String) } }
        if (body != null) request.body(new StringRequestContent("application/json", body, StandardCharsets.UTF_8))
        InputStreamResponseListener listener = new InputStreamResponseListener()
        request.send(listener)
        Response response
        try {
            // The connect is bounded by the client's connect timeout; the wait for the status line by the idle timeout.
            response = listener.get(client.connectTimeout + idle, TimeUnit.MILLISECONDS)
        } catch (ExecutionException e) {
            Throwable cause = e.cause ?: e
            if (cause instanceof IOException) throw (IOException) cause
            throw new IOException(cause.message ?: cause.class.simpleName, cause)
        } catch (TimeoutException e) {
            request.abort(e)
            throw new IOException("No answer from ${request.host} within ${client.connectTimeout + idle} ms".toString(), e)
        }
        Map<String, String> responseHeaders = [:]
        for (HttpField field : response.headers) {
            if (!responseHeaders.containsKey(field.name)) responseHeaders.put(field.name, field.value)
        }
        InputStream input = listener.getInputStream()
        try {
            return consumer.call([statusCode: response.status, headers: responseHeaders, body: input])
        } finally {
            input.close()
        }
    }

    /** Jetty's DNS step for a guarded connection: OutboundHttpPolicy.resolveVetted, once per new connection. */
    static class VettedAddressResolver implements SocketAddressResolver {
        final Function<String, InetAddress[]> lookup

        VettedAddressResolver(Function<String, InetAddress[]> lookup = { String host -> InetAddress.getAllByName(host) } as Function<String, InetAddress[]>) {
            this.lookup = lookup
        }

        @Override
        void resolve(String host, int port, Map<String, Object> context, Promise<List<InetSocketAddress>> promise) {
            try {
                promise.succeeded(OutboundHttpPolicy.resolveVetted(host, lookup).collect { InetAddress addr -> new InetSocketAddress(addr, port) })
            } catch (Throwable t) {
                promise.failed(t)
            }
        }
    }
}
