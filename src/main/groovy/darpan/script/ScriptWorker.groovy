package darpan.script

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.Executors

/**
 * DAR-BE-169. The worker JVM (sandbox design §4, stage 2a): tenant scripts run in a separate process that holds no
 * database credentials, no tenant tokens and no network route out, so a script that escaped the JEXL sandbox would
 * find nothing to read and nowhere to send it, and one that exhausted memory takes down only this process.
 *
 * <p>Two halves in one class. {@link #main} is the worker: the JDK's own HTTP server, {@code POST /evaluate} runs
 * {@link ScriptSupervisor#evaluateHere} and returns its map as JSON, {@code GET /health} answers for the probe.
 * {@link #call} is the Moqui side: {@link ScriptSupervisor#evaluate} sends every script here once a worker URL is
 * configured (system property {@code darpan.script.worker.url} or env {@code DARPAN_SCRIPT_WORKER_URL}).</p>
 *
 * <p>An unreachable worker is an error, never a reason to run the script in Moqui instead: otherwise crashing the
 * worker on purpose would be the way back into the privileged JVM.</p>
 */
@CompileStatic
class ScriptWorker {
    static final String URL_PROPERTY = 'darpan.script.worker.url'
    static final String URL_ENV = 'DARPAN_SCRIPT_WORKER_URL'
    static final int DEFAULT_PORT = 8090
    /** The largest input evaluateHere accepts, plus room for a SCRIPT body and the envelope. */
    static final int MAX_REQUEST_CHARS = ScriptSupervisor.MAX_INPUT_JSON_CHARS + 64_000 + 4_096
    /**
     * The largest reply Moqui reads. A real one is under ~220k (a 64k result, 50 log lines of 500, all escaped); the
     * worker is untrusted once a script escapes, so its reply must not be able to fill Moqui's heap.
     */
    static final int MAX_RESPONSE_CHARS = 512_000
    /** Slack over the profile's own timeout for the round trip and JSON on both ends. */
    static final long NETWORK_MARGIN_MS = 5_000L

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .version(HttpClient.Version.HTTP_1_1)
            .build()

    static String configuredUrl() {
        String url = System.getProperty(URL_PROPERTY) ?: System.getenv(URL_ENV)
        return url?.trim() ?: null
    }

    // Caller authentication. Moqui sends the token; the worker holds only its SHA-256 (hex), so a script that escaped
    // the sandbox and read the worker's own environment learns nothing it could call the worker with.
    static final String TOKEN_PROPERTY = 'darpan.script.worker.token'
    static final String TOKEN_ENV = 'DARPAN_SCRIPT_WORKER_TOKEN'
    static final String TOKEN_SHA256_ENV = 'DARPAN_SCRIPT_WORKER_TOKEN_SHA256'
    static final String TOKEN_HEADER = 'X-Darpan-Worker-Token'

    static String configuredToken() {
        String token = System.getProperty(TOKEN_PROPERTY) ?: System.getenv(TOKEN_ENV)
        return token?.trim() ?: null
    }

    static String sha256Hex(String text) {
        byte[] digest = MessageDigest.getInstance('SHA-256').digest(text.getBytes(StandardCharsets.UTF_8))
        StringBuilder hex = new StringBuilder(64)
        for (byte b : digest) hex.append(String.format('%02x', b & 0xff))
        return hex.toString()
    }

    /** Constant time over the two digests, so the comparison leaks nothing about how much of a guess was right. */
    static boolean tokenMatches(String presented, String expectedSha256Hex) {
        if (!presented) return false
        byte[] got = sha256Hex(presented).getBytes(StandardCharsets.US_ASCII)
        byte[] want = expectedSha256Hex.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII)
        return MessageDigest.isEqual(got, want)
    }

    // -- Moqui side

    /** Same contract as ScriptSupervisor.evaluate: {ok, profile, result, units, elapsedMs, logs, error}. Never throws. */
    static Map<String, Object> call(String baseUrl, Object profile, String body, Object input) {
        String p = ScriptSupervisor.profileOf(profile)
        String payload
        try {
            payload = JsonOutput.toJson([profile: p, body: body, input: input])
        } catch (Exception e) {
            return workerFailure(p, 'INPUT', "The input could not be sent to the script worker: ${e.message}".toString())
        }
        if (payload.length() > MAX_REQUEST_CHARS) {
            return workerFailure(p, 'INPUT', "The input is too large for the script worker (${payload.length()} characters).".toString())
        }
        long timeoutMs = ((ScriptSupervisor.LIMITS[p] as Map)?.timeoutMs as Long ?: 2_000L) + NETWORK_MARGIN_MS
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl.replaceAll('/+$', '') + '/evaluate'))
                .timeout(Duration.ofMillis(timeoutMs))
                .header('Content-Type', 'application/json')
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
        String token = configuredToken()
        if (token) builder.header(TOKEN_HEADER, token)
        HttpRequest request = builder.build()
        try {
            HttpResponse<InputStream> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream())
            String text = response.body().withCloseable { InputStream s -> readCapped(s, MAX_RESPONSE_CHARS) }
            if (text == null) {
                return workerFailure(p, 'WORKER', 'The script worker sent a reply too large to read; the script result was dropped.')
            }
            Object parsed = text ? new JsonSlurper().parseText(text) : null
            if (response.statusCode() == 200 && parsed instanceof Map && ((Map) parsed).containsKey('ok')) {
                return (Map<String, Object>) parsed
            }
            return workerFailure(p, 'WORKER', "The script worker answered ${response.statusCode()}; the script was not run.".toString())
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
            return workerFailure(p, 'WORKER', 'Interrupted while waiting for the script worker; the script was not run.')
        } catch (Exception e) {
            return workerFailure(p, 'WORKER', "The script worker did not answer (${e.getClass().simpleName}); the script was not run.".toString())
        }
    }

    private static Map<String, Object> workerFailure(String profile, String kind, String message) {
        return [ok: false, profile: profile, result: null, error: [kind: kind, message: message],
                units: 0L, elapsedMs: 0L, logs: [] as List<String>] as Map<String, Object>
    }

    // -- worker side

    static void main(String[] args) {
        int port = (System.getenv('DARPAN_SCRIPT_WORKER_PORT') ?: DEFAULT_PORT.toString()) as int
        Map<String, Map<String, Object>> warmed = warmUp()
        println "Script worker warmed: ${warmed.collect { String p, Map<String, Object> o -> "${p} ${o.ok ? 'ok' : (o.error as Map)?.kind}" }.join(', ')}"
        String expectedSha256 = System.getenv(TOKEN_SHA256_ENV)?.trim() ?: null
        if (!expectedSha256) {
            println "WARNING: ${TOKEN_SHA256_ENV} is not set; any caller that can reach this port can run scripts."
        }
        HttpServer server = start(port, expectedSha256)
        println "Script worker listening on :${server.address.port} (caller token ${expectedSha256 ? 'required' : 'NOT required'})"
    }

    /** A cold JVM's first evaluation (class loading, first JEXL compile) ran past EXPRESSION's 250 ms on 0.25 vCPU. */
    static final int WARM_UP_ROUNDS = 3
    private static final Map<String, String> WARM_UP_BODIES = [
            (ScriptSupervisor.EXPRESSION): '1 + 1',
            (ScriptSupervisor.SCRIPT)    : 'var t = 0; for (var x : input) { t = t + x }; t',
    ] as Map<String, String>

    /**
     * Runs every profile in this JVM until it succeeds (at most WARM_UP_ROUNDS times) and returns the last outcome per
     * profile. main() calls it before the port opens, so a health check, and with it service discovery, never shows
     * Moqui a cold worker. A profile that still fails is reported, not fatal: the worker starts and the caps still hold.
     */
    static Map<String, Map<String, Object>> warmUp() {
        Map<String, Map<String, Object>> out = [:]
        for (String profile : ScriptSupervisor.LIMITS.keySet()) {
            Map<String, Object> last = null
            for (int i = 0; i < WARM_UP_ROUNDS && !(last?.ok as boolean); i++) {
                last = ScriptSupervisor.evaluateHere(profile, WARM_UP_BODIES[profile], [1, 2, 3])
            }
            out[profile] = last
        }
        return out
    }

    static HttpServer start(int port) {
        return start(port, null)
    }

    /** expectedTokenSha256 null = no caller check (lab only; main() warns). /health never needs the token. */
    static HttpServer start(int port, String expectedTokenSha256) {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 32)
        server.createContext('/health', { HttpExchange ex -> reply(ex, 200, '{"ok":true}') } as HttpHandler)
        server.createContext('/evaluate', { HttpExchange ex -> handle(ex, expectedTokenSha256) } as HttpHandler)
        server.executor = Executors.newFixedThreadPool(4)
        server.start()
        return server
    }

    private static void handle(HttpExchange ex, String expectedTokenSha256) {
        try {
            if (ex.requestMethod != 'POST') {
                reply(ex, 405, '{"ok":false,"error":{"kind":"WORKER","message":"POST only"}}')
                return
            }
            if (expectedTokenSha256 && !tokenMatches(ex.requestHeaders.getFirst(TOKEN_HEADER), expectedTokenSha256)) {
                reply(ex, 401, '{"ok":false,"error":{"kind":"WORKER","message":"Caller token missing or wrong"}}')
                return
            }
            String text = readCapped(ex.requestBody, MAX_REQUEST_CHARS)
            if (text == null) {
                reply(ex, 413, '{"ok":false,"error":{"kind":"INPUT","message":"Request too large"}}')
                return
            }
            Map request = new JsonSlurper().parseText(text) as Map
            Map<String, Object> out = ScriptSupervisor.evaluateHere(request.profile, request.body as String, request.input)
            reply(ex, 200, JsonOutput.toJson(out))
        } catch (Throwable e) {
            // Throwable, not Exception: a NoClassDefFoundError here once left the caller waiting out its timeout.
            // A real OutOfMemoryError never gets this far; -XX:+ExitOnOutOfMemoryError ends the process first.
            e.printStackTrace()
            reply(ex, 500, JsonOutput.toJson([ok: false, error: [kind: 'WORKER', message: e.message ?: e.getClass().simpleName]]))
        }
    }

    /** The body as text, or null once it passes maxChars (a UTF-8 char is at least one byte, so bytes bound chars). */
    private static String readCapped(InputStream stream, int maxChars) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream()
        byte[] buffer = new byte[8192]
        int read
        while ((read = stream.read(buffer)) != -1) {
            bytes.write(buffer, 0, read)
            if (bytes.size() > maxChars * 4L) return null
        }
        String text = new String(bytes.toByteArray(), StandardCharsets.UTF_8)
        return text.length() > maxChars ? null : text
    }

    private static void reply(HttpExchange ex, int status, String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8)
        ex.responseHeaders.set('Content-Type', 'application/json')
        ex.sendResponseHeaders(status, bytes.length)
        ex.responseBody.withCloseable { it.write(bytes) }
    }
}
