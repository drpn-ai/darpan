package darpan.script

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-169: a script sent to the worker JVM comes back with ScriptSupervisor's own result shape. */
class ScriptWorkerTests {
    private HttpServer server

    @AfterEach
    void stop() {
        server?.stop(0)
        System.clearProperty(ScriptWorker.URL_PROPERTY)
        System.clearProperty(ScriptWorker.TOKEN_PROPERTY)
    }

    private String startWorkerExpecting(String token) {
        server = ScriptWorker.start(0, ScriptWorker.sha256Hex(token))
        return "http://127.0.0.1:${server.address.port}".toString()
    }

    private String startWorker() {
        server = ScriptWorker.start(0)
        return "http://127.0.0.1:${server.address.port}".toString()
    }

    private static String deadUrl() {
        // A port that was free a moment ago and has nothing listening now.
        ServerSocket socket = new ServerSocket(0)
        int port = socket.localPort
        socket.close()
        return "http://127.0.0.1:${port}".toString()
    }

    @Test
    void scriptRunsInTheWorkerAndReturnsTheSameShape() {
        Map<String, Object> out = ScriptWorker.call(startWorker(), 'SCRIPT', 'var t = 0; for (var x : input.qty) { t = t + x }; t', [qty: [2, 3, 5]])
        assertTrue(out.ok as boolean, "${out.error}")
        assertEquals(10, (out.result as Number).intValue())
        assertTrue((out.units as Number).longValue() > 0L)
        assertTrue(out.containsKey('logs') && out.containsKey('elapsedMs'))
    }

    @Test
    void sandboxRefusalsComeBackFromTheWorker() {
        Map<String, Object> out = ScriptWorker.call(startWorker(), 'EXPRESSION', 'secret + 1', [:])
        assertFalse(out.ok as boolean)
        assertEquals('ACCESS', (out.error as Map).kind)
    }

    @Test
    void anUnreachableWorkerIsAnErrorNotALocalRun() {
        Map<String, Object> out = ScriptWorker.call(deadUrl(), 'EXPRESSION', '1 + 1', null)
        assertFalse(out.ok as boolean)
        assertEquals('WORKER', (out.error as Map).kind)
        assertEquals(0L, (out.units as Number).longValue())
    }

    @Test
    void evaluateGoesToTheWorkerOnceAUrlIsConfigured() {
        System.setProperty(ScriptWorker.URL_PROPERTY, deadUrl())
        Map<String, Object> remote = ScriptSupervisor.evaluate('EXPRESSION', '1 + 1', null)
        assertEquals('WORKER', (remote.error as Map)?.kind, 'a configured worker must be used, never a silent local run')

        System.clearProperty(ScriptWorker.URL_PROPERTY)
        Map<String, Object> local = ScriptSupervisor.evaluate('EXPRESSION', '1 + 1', null)
        assertTrue(local.ok as boolean, "${local.error}")
    }

    /** A worker that answers every /evaluate with this body, as an escaped script could make it do. */
    private String startHostileWorker(String body) {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 4)
        server.createContext('/evaluate', { ex ->
            byte[] bytes = body.getBytes('UTF-8')
            ex.sendResponseHeaders(200, bytes.length)
            ex.responseBody.withCloseable { it.write(bytes) }
        } as com.sun.net.httpserver.HttpHandler)
        server.start()
        return "http://127.0.0.1:${server.address.port}".toString()
    }

    @Test
    void anOversizedReplyIsRefusedNotBuffered() {
        String huge = '{"ok":true,"result":"' + ('x' * (ScriptWorker.MAX_RESPONSE_CHARS + 1)) + '"}'
        Map<String, Object> out = ScriptWorker.call(startHostileWorker(huge), 'EXPRESSION', '1 + 1', null)
        assertFalse(out.ok as boolean)
        assertEquals('WORKER', (out.error as Map).kind)
        assertEquals(null, out.result)
    }

    @Test
    void aReplyOutsideTheContractIsRefused() {
        Map<String, Object> out = ScriptWorker.call(startHostileWorker('[1,2,3]'), 'EXPRESSION', '1 + 1', null)
        assertFalse(out.ok as boolean)
        assertEquals('WORKER', (out.error as Map).kind)
    }

    @Test
    void theWorkerRefusesAnythingButPost() {
        String base = startWorker()
        HttpURLConnection get = (HttpURLConnection) new URL(base + '/evaluate').openConnection()
        assertEquals(405, get.responseCode)
        HttpURLConnection health = (HttpURLConnection) new URL(base + '/health').openConnection()
        assertEquals(200, health.responseCode)
    }

    // AWS UAT (0.25 vCPU): a fresh worker's first EXPRESSION ran past its 250 ms wall clock. main() warms both
    // profiles before the port opens, so the health check (and with it Cloud Map) never shows a cold worker.
    @Test
    void warmUpRunsEveryProfileOnceAndReportsEach() {
        Map<String, Map<String, Object>> warmed = ScriptWorker.warmUp()
        assertEquals(ScriptSupervisor.LIMITS.keySet(), warmed.keySet())
        warmed.each { String profile, Map<String, Object> out -> assertTrue(out.ok as boolean, "${profile}: ${out.error}") }
    }

    // The worker holds only the token's SHA-256, so an escaped script reading its own environment learns nothing
    // it can call the worker with. Where NetworkPolicy is not enforced, this is the only wall.
    @Test
    void aWorkerWithATokenHashRunsScriptsForTheRightToken() {
        String base = startWorkerExpecting('right-token-0123456789')
        System.setProperty(ScriptWorker.TOKEN_PROPERTY, 'right-token-0123456789')
        Map<String, Object> out = ScriptWorker.call(base, 'EXPRESSION', '2 * 3', null)
        assertTrue(out.ok as boolean, "${out.error}")
        assertEquals(6, (out.result as Number).intValue())
    }

    @Test
    void aWorkerWithATokenHashRefusesAMissingOrWrongToken() {
        String base = startWorkerExpecting('right-token-0123456789')
        Map<String, Object> none = ScriptWorker.call(base, 'EXPRESSION', '2 * 3', null)
        assertFalse(none.ok as boolean)
        assertEquals('WORKER', (none.error as Map).kind)
        assertTrue(((none.error as Map).message as String).contains('401'), "${none.error}")
        System.setProperty(ScriptWorker.TOKEN_PROPERTY, 'wrong-token')
        Map<String, Object> wrong = ScriptWorker.call(base, 'EXPRESSION', '2 * 3', null)
        assertFalse(wrong.ok as boolean)
        assertTrue(((wrong.error as Map).message as String).contains('401'), "${wrong.error}")
        HttpURLConnection health = (HttpURLConnection) new URL(base + '/health').openConnection()
        assertEquals(200, health.responseCode)
    }
}
