package darpan.facade.common

import org.eclipse.jetty.client.CompletableResponseListener
import org.eclipse.jetty.client.ContentResponse
import org.eclipse.jetty.client.HttpClient
import org.eclipse.jetty.client.Request
import org.eclipse.jetty.client.StringRequestContent
import org.eclipse.jetty.http.HttpField
import org.eclipse.jetty.util.Promise
import org.eclipse.jetty.util.SocketAddressResolver
import org.eclipse.jetty.util.thread.QueuedThreadPool
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler

import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
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
 * One shared client (thread-safe, daemon threads, started on first use) serves every transport.
 */
class OutboundHttpClient {
    static final long CONNECT_TIMEOUT_MILLIS = 30_000L
    /** The largest answer read into memory: well past any page a source returns, and a bound on a hostile one. */
    static final int MAX_RESPONSE_BYTES = 256 * 1024 * 1024

    private static volatile HttpClient shared

    /** The shared guarded client, started on first use. */
    static HttpClient shared() {
        HttpClient client = shared
        if (client != null) return client
        synchronized (OutboundHttpClient) {
            if (shared == null) shared = newClient(new VettedAddressResolver())
            return shared
        }
    }

    /** A started client whose DNS step is {@code resolver}. Tests pass a resolver with their own lookup. */
    static HttpClient newClient(SocketAddressResolver resolver) {
        HttpClient client = new HttpClient()
        QueuedThreadPool threads = new QueuedThreadPool()
        threads.setName("darpan-outbound-http")
        threads.setDaemon(true)
        client.setExecutor(threads)
        client.setScheduler(new ScheduledExecutorScheduler("darpan-outbound-http-scheduler", true))
        client.setSocketAddressResolver(resolver)
        client.setConnectTimeout(CONNECT_TIMEOUT_MILLIS)
        client.setFollowRedirects(false)
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
