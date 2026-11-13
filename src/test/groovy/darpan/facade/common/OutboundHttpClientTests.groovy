package darpan.facade.common

import org.eclipse.jetty.client.HttpClient
import org.eclipse.jetty.util.Promise
import org.eclipse.jetty.util.SocketAddressResolver
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Function

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-262. The outbound guard checks the address the client actually connects to: DNS rebinding (public on the first
 * lookup, loopback or cloud metadata on the next) is refused at the connect, and no socket is opened.
 */
class OutboundHttpClientTests {

    private static final InetAddress PUBLIC = InetAddress.getByAddress("rebind.test", [93, 184, 216, 34] as byte[])
    private static final InetAddress LOOPBACK = InetAddress.getByAddress("rebind.test", [127, 0, 0, 1] as byte[])
    private static final InetAddress METADATA = InetAddress.getByAddress("rebind.test", [169, 254, 169, 254] as byte[])
    private static final InetAddress PRIVATE = InetAddress.getByAddress("rebind.test", [10, 0, 0, 7] as byte[])

    /** A lookup that answers each call with the next list (the last one repeats), counting calls. */
    private static Function<String, InetAddress[]> rebinding(AtomicInteger calls, List<List<InetAddress>> answers) {
        return { String host ->
            int n = calls.getAndIncrement()
            answers[Math.min(n, answers.size() - 1)] as InetAddress[]
        } as Function<String, InetAddress[]>
    }

    @Test
    @DisplayName("a host that is public on the first lookup and loopback or metadata on the next is refused on the next")
    void rebindingIsRefusedOnTheSecondLookup() {
        AtomicInteger calls = new AtomicInteger()
        def lookup = rebinding(calls, [[PUBLIC], [LOOPBACK], [METADATA]])
        assertEquals([PUBLIC], OutboundHttpPolicy.resolveVetted("rebind.test", lookup), "first connection: the public address")
        def loopback = assertThrows(UnknownHostException) { OutboundHttpPolicy.resolveVetted("rebind.test", lookup) }
        assertTrue(loopback.message.contains("127.0.0.1") && loopback.message.contains("loopback"), loopback.message)
        def metadata = assertThrows(UnknownHostException) { OutboundHttpPolicy.resolveVetted("rebind.test", lookup) }
        assertTrue(metadata.message.contains("169.254.169.254"), metadata.message)
        assertEquals(3, calls.get())
    }

    @Test
    @DisplayName("every address is checked; literals and blocked names are refused without a lookup; no answer is a refusal")
    void everyAddressIsChecked() {
        AtomicInteger calls = new AtomicInteger()
        assertThrows(UnknownHostException) { OutboundHttpPolicy.resolveVetted("rebind.test", rebinding(calls, [[PUBLIC, PRIVATE]])) }
        assertThrows(UnknownHostException) { OutboundHttpPolicy.resolveVetted("169.254.169.254", rebinding(calls, [[PUBLIC]])) }
        assertThrows(UnknownHostException) { OutboundHttpPolicy.resolveVetted("localhost", rebinding(calls, [[PUBLIC]])) }
        assertThrows(UnknownHostException) { OutboundHttpPolicy.resolveVetted("[::1]", rebinding(calls, [[PUBLIC]])) }
        assertEquals(1, calls.get(), "only the mixed answer needed a lookup")
        assertThrows(UnknownHostException) { OutboundHttpPolicy.resolveVetted("rebind.test", rebinding(new AtomicInteger(), [[]])) }
    }

    @Test
    @DisplayName("the resolver hands Jetty exactly the vetted addresses, on the URL's port")
    void theResolverPinsTheVettedAddress() {
        def resolver = new OutboundHttpClient.VettedAddressResolver(rebinding(new AtomicInteger(), [[PUBLIC]]))
        List<InetSocketAddress> got = null
        Throwable failed = null
        resolver.resolve("rebind.test", 443, [:], new Promise<List<InetSocketAddress>>() {
            void succeeded(List<InetSocketAddress> result) { got = result }
            void failed(Throwable x) { failed = x }
        })
        assertEquals(null, failed)
        assertEquals(1, got.size())
        assertEquals(PUBLIC, got[0].address)
        assertEquals(443, got[0].port)
    }

    @Test
    @DisplayName("end to end: a rebound host never gets a socket; the same request without the guard does")
    void aReboundHostNeverGetsASocket() {
        ServerSocket server = new ServerSocket(0, 5, LOOPBACK)
        server.setSoTimeout(200)
        AtomicInteger accepted = new AtomicInteger()
        CountDownLatch stop = new CountDownLatch(1)
        Thread acceptor = Thread.start {
            while (stop.count > 0) {
                try { Socket s = server.accept(); accepted.incrementAndGet(); s.close() } catch (SocketTimeoutException ignored) { } catch (IOException ignored) { break }
            }
        }
        String url = "http://rebind.test:${server.localPort}/graphql".toString()
        AtomicInteger lookups = new AtomicInteger()
        HttpClient guarded = OutboundHttpClient.newClient(new OutboundHttpClient.VettedAddressResolver(rebinding(lookups, [[LOOPBACK]])))
        // The control: the same request with a resolver that does not check — proves the server would see a connection.
        HttpClient unguarded = OutboundHttpClient.newClient({ String host, int port, Map ctx, Promise promise ->
            promise.succeeded([new InetSocketAddress(LOOPBACK, port)])
        } as SocketAddressResolver)
        try {
            def refused = assertThrows(IOException) { OutboundHttpClient.send(guarded, "POST", url, ["Content-Type": "application/json"], "{}", 5) }
            assertTrue(refused.toString().contains("non-routable") || refused.cause?.toString()?.contains("non-routable"), refused.toString())
            assertTrue(lookups.get() >= 1)
            Thread.sleep(300)
            assertEquals(0, accepted.get(), "the guarded client opened no socket to the loopback address")

            assertThrows(IOException) { OutboundHttpClient.send(unguarded, "POST", url, ["Content-Type": "application/json"], "{}", 5) }
            long deadline = System.currentTimeMillis() + 3000L
            while (accepted.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertTrue(accepted.get() >= 1, "without the guard the same request reaches the loopback server")
        } finally {
            stop.countDown()
            guarded.stop()
            unguarded.stop()
            server.close()
            acceptor.join(2000)
        }
    }

    @Test
    @DisplayName("security review: a Set-Cookie from one call is never sent on the next (the client is shared across tenants)")
    void cookiesAreNeverReplayed() {
        ServerSocket server = new ServerSocket(0, 5, LOOPBACK)
        List<String> requests = Collections.synchronizedList([])
        Thread responder = Thread.start {
            2.times {
                Socket s = server.accept()
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(s.inputStream, "ISO-8859-1"))
                    StringBuilder head = new StringBuilder()
                    String line
                    while ((line = reader.readLine()) != null && !line.isEmpty()) head.append(line).append("\n")
                    requests << head.toString()
                    String reply = "HTTP/1.1 200 OK\r\nSet-Cookie: tenantSession=tenant-a-secret; Path=/\r\nContent-Type: application/json\r\n" +
                            "Content-Length: 2\r\nConnection: close\r\n\r\n{}"
                    s.outputStream.write(reply.getBytes("ISO-8859-1"))
                    s.outputStream.flush()
                } finally {
                    s.close()
                }
            }
        }
        HttpClient client = OutboundHttpClient.newClient({ String host, int port, Map ctx, Promise promise ->
            promise.succeeded([new InetSocketAddress(LOOPBACK, port)])
        } as SocketAddressResolver)
        try {
            String url = "http://shared-host.test:${server.localPort}/graphql".toString()
            assertEquals(200, OutboundHttpClient.send(client, "GET", url, [:], null, 5).statusCode, "tenant A's call")
            assertEquals(200, OutboundHttpClient.send(client, "GET", url, [:], null, 5).statusCode, "tenant B's call")
            responder.join(3000)
            assertEquals(2, requests.size())
            assertFalse(requests[1].toLowerCase().contains("cookie:"), "the second call carried a cookie:\n${requests[1]}")
            assertTrue(client.httpCookieStore instanceof org.eclipse.jetty.http.HttpCookieStore.Empty)
        } finally {
            client.stop()
            server.close()
        }
    }

    /** A loopback server that answers one request with {@code head} then streams {@code chunks} (each after {@code gapMillis}). */
    private static Map oneShotServer(String head, List<byte[]> chunks, long gapMillis = 0L) {
        ServerSocket server = new ServerSocket(0, 5, LOOPBACK)
        List<String> requests = Collections.synchronizedList([])
        Thread responder = Thread.start {
            Socket s = server.accept()
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(s.inputStream, "ISO-8859-1"))
                StringBuilder seen = new StringBuilder()
                String line
                while ((line = reader.readLine()) != null && !line.isEmpty()) seen.append(line).append("\n")
                requests << seen.toString()
                s.outputStream.write(head.getBytes("ISO-8859-1"))
                s.outputStream.flush()
                chunks.each { byte[] chunk ->
                    if (gapMillis > 0) Thread.sleep(gapMillis)
                    s.outputStream.write(chunk)
                    s.outputStream.flush()
                }
            } catch (IOException ignored) {
                // the client hung up early, which some tests intend
            } finally {
                s.close()
            }
        }
        return [server: server, responder: responder, requests: requests]
    }

    private static HttpClient loopbackClient() {
        return OutboundHttpClient.newClient({ String host, int port, Map ctx, Promise promise ->
            promise.succeeded([new InetSocketAddress(LOOPBACK, port)])
        } as SocketAddressResolver)
    }

    @Test
    @DisplayName("stream: the answer arrives as an InputStream with no size cap — 260 MB, past send's 256 MB buffer limit")
    void streamHandsOverTheBodyAsItArrives() {
        byte[] chunk = new byte[1024 * 1024]
        Arrays.fill(chunk, (byte) 'x')
        int chunks = 260
        assertTrue((long) chunk.length * chunks > OutboundHttpClient.MAX_RESPONSE_BYTES)
        Map srv = oneShotServer("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${chunk.length * chunks}\r\nConnection: close\r\n\r\n",
                Collections.nCopies(chunks, chunk))
        HttpClient client = loopbackClient()
        try {
            String url = "http://stream.test:${(srv.server as ServerSocket).localPort}/result.jsonl".toString()
            Map got = OutboundHttpClient.stream(client, "GET", url, [Accept: "*/*"], null, 5000L) { Map answer ->
                long total = 0
                byte[] buffer = new byte[64 * 1024]
                InputStream input = (InputStream) answer.body
                int n
                while ((n = input.read(buffer)) >= 0) total += n
                return [status: answer.statusCode, bytes: total, type: (answer.headers as Map)["Content-Type"]]
            }
            assertEquals([status: 200, bytes: (long) chunk.length * chunks, type: "application/octet-stream"], got)
            assertTrue((srv.requests as List)[0].startsWith("GET /result.jsonl"))
        } finally {
            client.stop()
            (srv.server as ServerSocket).close()
        }
    }

    @Test
    @DisplayName("stream: the idle timeout is a read timeout — a slow but steady answer completes, a stalled one fails as IO")
    void streamIdleTimeoutIsAReadTimeout() {
        List<byte[]> slow = (1..6).collect { "line ${it}\n".getBytes("UTF-8") }
        int length = slow.sum { it.length } as int
        Map steady = oneShotServer("HTTP/1.1 200 OK\r\nContent-Length: ${length}\r\nConnection: close\r\n\r\n", slow, 300L)
        HttpClient client = loopbackClient()
        try {
            // 6 gaps of 300 ms = 1.8 s in all, past the 1 s idle timeout, but never 1 s without bytes.
            String text = OutboundHttpClient.stream(client, "GET", "http://slow.test:${(steady.server as ServerSocket).localPort}/".toString(),
                    [:], null, 1000L) { Map answer -> ((InputStream) answer.body).getText("UTF-8") }
            assertEquals((1..6).collect { "line ${it}\n" }.join(""), text)
        } finally {
            (steady.server as ServerSocket).close()
        }

        Map stalled = oneShotServer("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n",
                ["partial".getBytes("UTF-8"), "never".getBytes("UTF-8")], 2500L)
        try {
            assertThrows(IOException) {
                OutboundHttpClient.stream(client, "GET", "http://stall.test:${(stalled.server as ServerSocket).localPort}/".toString(),
                        [:], null, 1000L) { Map answer -> ((InputStream) answer.body).getText("UTF-8") }
            }
        } finally {
            client.stop()
            (stalled.server as ServerSocket).close()
        }
    }

    @Test
    @DisplayName("stream: the consumer's own exception passes through unchanged, and an error status is handed over, not thrown")
    void streamPassesConsumerExceptionsThrough() {
        Map srv = oneShotServer("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 4\r\nConnection: close\r\n\r\n", ["busy".getBytes("UTF-8")])
        HttpClient client = loopbackClient()
        try {
            def thrown = assertThrows(IllegalStateException) {
                OutboundHttpClient.stream(client, "POST", "http://err.test:${(srv.server as ServerSocket).localPort}/".toString(),
                        ["Content-Type": "application/json"], "{}", 5000L) { Map answer ->
                    assertEquals(503, answer.statusCode)
                    throw new IllegalStateException("consumer gave up at status ${answer.statusCode}")
                }
            }
            assertEquals("consumer gave up at status 503", thrown.message)
        } finally {
            client.stop()
            (srv.server as ServerSocket).close()
        }
    }

    @Test
    @DisplayName("a caller's connect timeout gets its own shared guarded client, rounded up to whole seconds and bounded")
    void sharedClientsPerConnectTimeout() {
        assertTrue(OutboundHttpClient.shared().is(OutboundHttpClient.shared(30_000L)))
        HttpClient probe = OutboundHttpClient.shared(5_000L)
        assertEquals(5_000L, probe.connectTimeout)
        assertTrue(probe.is(OutboundHttpClient.shared(4_200L)), "4.2 s rounds up to the 5 s client")
        assertEquals(1_000L, OutboundHttpClient.shared(0L).connectTimeout)
        assertEquals(120_000L, OutboundHttpClient.shared(10_000_000L).connectTimeout)
        [probe, OutboundHttpClient.shared(0L)].each { HttpClient c ->
            assertTrue(c.socketAddressResolver instanceof OutboundHttpClient.VettedAddressResolver)
            assertFalse(c.followRedirects)
            assertTrue(c.httpCookieStore instanceof org.eclipse.jetty.http.HttpCookieStore.Empty)
        }
    }

    @Test
    @DisplayName("TLS is unchanged by the pin: certificates are verified against the URL's host name")
    void tlsHostnameVerificationStaysOn() {
        HttpClient client = OutboundHttpClient.shared()
        assertEquals("HTTPS", client.sslContextFactory.endpointIdentificationAlgorithm)
        assertFalse(client.sslContextFactory.trustAll)
        assertTrue(client.socketAddressResolver instanceof OutboundHttpClient.VettedAddressResolver)
        assertFalse(client.followRedirects)
    }
}
