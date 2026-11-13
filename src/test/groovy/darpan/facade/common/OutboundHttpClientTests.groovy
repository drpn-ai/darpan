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
    @DisplayName("TLS is unchanged by the pin: certificates are verified against the URL's host name")
    void tlsHostnameVerificationStaysOn() {
        HttpClient client = OutboundHttpClient.shared()
        assertEquals("HTTPS", client.sslContextFactory.endpointIdentificationAlgorithm)
        assertFalse(client.sslContextFactory.trustAll)
        assertTrue(client.socketAddressResolver instanceof OutboundHttpClient.VettedAddressResolver)
        assertFalse(client.followRedirects)
    }
}
