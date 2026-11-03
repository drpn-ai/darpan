package darpan.agent

import darpan.datasource.GraphQlDocumentCheck
import groovy.json.JsonOutput

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Timestamp
import java.util.concurrent.ExecutionException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * DAR-CLI-003. The shared core of a fresh, read-only query an agent sends to one of a tenant's systems through Darpan's
 * stored connection: the read-only checks (nothing is sent until they pass), the caps, the admission lane, secret
 * stripping, and one AgentQueryLog row per attempt. The per-system services (NetSuite, HotWax OMS, Shopify) live in
 * their own components and call {@link #run}.
 *
 * <p>Caps: default 200 rows, at most 2,000 per call; 60 seconds wall clock; response body at most 5 MB (over it the
 * rows are cut, truncated true). Spec: wrapper docs/superpowers/specs/2026-11-03-tenant-agent-cli-mcp-design.md 6.5.
 */
class AgentQuerySupport {

    private static final Logger logger = LoggerFactory.getLogger(AgentQuerySupport)

    static final int DEFAULT_LIMIT = 200
    static final int MAX_LIMIT = 2000
    static final long MAX_BODY_BYTES = 5L * 1024 * 1024
    static final int MAX_LOGGED_QUERY_CHARS = 2000

    static final String OUTCOME_OK = "OK"
    static final String OUTCOME_REFUSED = "REFUSED"
    static final String OUTCOME_THROTTLED = "THROTTLED"
    static final String OUTCOME_ERROR = "ERROR"
    static final String OUTCOME_TIMEOUT = "TIMEOUT"

    /** Final review R25: the answer when a connection another tenant granted is asked for a fresh query or a schema. */
    static final String SHARED_CONNECTION_REFUSAL =
            "This connection is shared with your tenant; fresh queries run only on your own connections."

    /** Wall clock for one query. Not final so a test can shorten it; production leaves it at 60 s. */
    static volatile long timeoutMillis = 60_000L

    /** A request the agent must change: thrown by a check or by the work, answered as code REFUSED. */
    static class AgentQueryRefused extends IllegalArgumentException {
        AgentQueryRefused(String message) { super(message) }
    }

    // The only threads a fresh query may use: AgentQueryLane.MAX_PER_INSTANCE daemon threads, handed work directly (no
    // queue), so a full pool rejects and the caller answers THROTTLED. A lane slot is held until the worker really ends
    // (see runCore), so a call stuck in non-interruptible I/O keeps its slot and its thread counted: the pool cannot grow.
    private static final java.util.concurrent.atomic.AtomicInteger WORKER_SEQ = new java.util.concurrent.atomic.AtomicInteger()
    private static final java.util.concurrent.ThreadPoolExecutor WORKERS = new java.util.concurrent.ThreadPoolExecutor(
            AgentQueryLane.MAX_PER_INSTANCE, AgentQueryLane.MAX_PER_INSTANCE, 60L, TimeUnit.SECONDS,
            new java.util.concurrent.SynchronousQueue<Runnable>(), { Runnable r ->
                Thread t = new Thread(r, "darpan-agent-query-" + WORKER_SEQ.incrementAndGet())
                t.setDaemon(true)
                return t
            } as ThreadFactory, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy())

    // ---- read-only checks: null means fine, otherwise the refusal in words -------------------------------------------

    /** SuiteQL must be exactly one statement whose first word is SELECT or WITH. Comments and literals are removed first. */
    static String checkSuiteQl(String sql) {
        if (!sql?.trim()) return "The SuiteQL is empty."
        String stripped = stripCommentsAndLiterals(sql)
        if (stripped == null) return "The SuiteQL has an unterminated comment or quote; it was not sent."
        stripped = stripped.trim()
        while (stripped.endsWith(";")) stripped = stripped.substring(0, stripped.length() - 1).trim()
        if (stripped.contains(";")) return "Only one SuiteQL statement may be sent at a time."
        String first = stripped.split(/[^A-Za-z]/, 2)[0].toUpperCase()
        if (first != "SELECT" && first != "WITH") return "Only a SELECT (or WITH ... SELECT) may be sent; this is read-only."
        return null
    }

    /** A GraphQL document must be one query: no mutation, no subscription, no introspection. */
    static String checkGraphqlQuery(String document) {
        List errors = (List) GraphQlDocumentCheck.check(document, false).errors
        return errors ? errors.join(" ") : null
    }

    /** Rows per call: null is 200, above 2,000 is 2,000, below 1 or not a number is refused. */
    static int clampLimit(Object requested) {
        if (requested == null || (requested instanceof CharSequence && !requested.toString().trim())) return DEFAULT_LIMIT
        long n
        try {
            n = requested instanceof Number ? ((Number) requested).longValue() : Long.parseLong(requested.toString().trim())
        } catch (NumberFormatException ignored) {
            throw new AgentQueryRefused("The row limit must be a whole number from 1 to ${MAX_LIMIT}.".toString())
        }
        if (n < 1) throw new AgentQueryRefused("The row limit must be a whole number from 1 to ${MAX_LIMIT}.".toString())
        return (int) Math.min(n, (long) MAX_LIMIT)
    }

    /** Text with comments and quoted strings/identifiers blanked, or null when one is never closed. */
    private static String stripCommentsAndLiterals(String sql) {
        StringBuilder out = new StringBuilder()
        int i = 0
        int n = sql.length()
        while (i < n) {
            char c = sql.charAt(i)
            if (c == ('-' as char) && i + 1 < n && sql.charAt(i + 1) == ('-' as char)) {
                int eol = sql.indexOf('\n', i)
                i = eol < 0 ? n : eol
                out.append(' ')
            } else if (c == ('/' as char) && i + 1 < n && sql.charAt(i + 1) == ('*' as char)) {
                int end = sql.indexOf('*/', i + 2)
                if (end < 0) return null
                i = end + 2
                out.append(' ')
            } else if (c == ('\'' as char) || c == ('"' as char)) {
                int j = i + 1
                boolean closed = false
                while (j < n) {
                    if (sql.charAt(j) == c) {
                        if (j + 1 < n && sql.charAt(j + 1) == c) { j += 2; continue }   // doubled quote = escaped quote
                        closed = true
                        break
                    }
                    j++
                }
                if (!closed) return null
                i = j + 1
                out.append(" x ")
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    // ---- run ---------------------------------------------------------------------------------------------------------

    /**
     * Runs one fresh query for the active tenant and writes its log row.
     *
     * <p>meta keys: systemEnumId, connectionId, queryText (all logged), and secrets, a List of strings (the connection's
     * credentials and its host) that are removed from any error text before it is returned or logged.
     *
     * <p>The work closure's HTTP client MUST set connect and read timeouts below the 60 s wall clock. A timed-out query
     * answers TIMEOUT at once, but its lane slot is kept until the worker really ends (4 worker threads in all), so a call
     * with no socket timeout would hold its slot, and starve the lane, for as long as the remote end stays silent.
     *
     * <p>work runs on a worker thread, so it must not touch the ExecutionContext: resolve the connection first, make the
     * HTTP call inside, and return [rows: List, hasMore: Boolean, nextOffset: Integer, offset: Integer]. Throwing
     * {@link AgentQueryRefused} answers REFUSED; any other exception answers ERROR.
     *
     * <p>Returns [rows, rowCount, hasMore, nextOffset?, truncated, durationMs] or [error, code] where code is REFUSED,
     * THROTTLED (with retryAfterSeconds), TIMEOUT or ERROR.
     */
    static Map run(def ec, Map meta, Closure<Map> work) {
        String tenantId = darpan.facade.common.TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        String userId = ec?.user?.userId
        return runCore(meta, tenantId, work) { Map row -> writeLog(ec, tenantId, userId, row) }
    }

    /**
     * Final review R25: fresh queries and schema reads run only on a connection the ACTIVE tenant owns. A connection
     * another tenant granted (ConfigTenantAccess) keeps working for saved runs, never for an agent's own queries, so
     * the owner never has queries against its system that it did not see. Each system calls this after its own gate
     * with the config's owning companyUserGroupId; null means owned, otherwise the refusal in words.
     */
    static String requireOwnedConnection(def ec, Object ownerCompanyUserGroupId) {
        return ownedConnectionRefusal(darpan.facade.common.TenantAccessSupport.currentActiveTenantUserGroupId(ec), ownerCompanyUserGroupId)
    }

    /** Pure: null when the owner is the active tenant, else {@link #SHARED_CONNECTION_REFUSAL}. */
    static String ownedConnectionRefusal(String activeTenantUserGroupId, Object ownerCompanyUserGroupId) {
        String active = activeTenantUserGroupId?.trim()
        String owner = ownerCompanyUserGroupId?.toString()?.trim()
        return active && owner && owner == active ? null : SHARED_CONNECTION_REFUSAL
    }

    /** A refusal's words with the connection's secrets (meta.secrets) removed. */
    static String refusalText(Map meta, String reason) {
        return strip(reason, ((meta?.secrets ?: []) as List).collect { it?.toString() }.findAll { it } as List<String>)
    }

    /** Logs a request refused before it was sent (a failed check), so the refusal is on the record. */
    static Map refuse(def ec, Map meta, String reason) {
        String tenantId = darpan.facade.common.TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        Map row = logRow(meta, OUTCOME_REFUSED, 0, 0)
        writeLog(ec, tenantId, ec?.user?.userId, row)
        // Final review B7: a refusal's words can carry the connection's host or token URL; strip them here, in the
        // core, so every system's refusals are covered (callers set meta.secrets before anything can refuse).
        return [error: refusalText(meta, reason), code: OUTCOME_REFUSED]
    }

    /** The body of {@link #run} without Moqui: tenant and a log sink are passed in. */
    static Map runCore(Map meta, String tenantId, Closure<Map> work, Closure logSink) {
        long started = System.currentTimeMillis()
        List<String> secrets = ((meta?.secrets ?: []) as List).collect { it?.toString() }.findAll { it }
        if (!AgentQueryLane.tryAcquire(tenantId)) {
            sink(logSink, logRow(meta, OUTCOME_THROTTLED, 0, 0))
            return [error: "Too many fresh queries are running; try again in ${AgentQueryLane.RETRY_AFTER_SECONDS} seconds.".toString(),
                    code: OUTCOME_THROTTLED, retryAfterSeconds: AgentQueryLane.RETRY_AFTER_SECONDS]
        }
        CompletableFuture<Map> future = new CompletableFuture<Map>()
        java.util.concurrent.atomic.AtomicReference<Thread> workerThread = new java.util.concurrent.atomic.AtomicReference<Thread>()
        try {
            // The slot belongs to the worker from here: only its own finally gives it back.
            WORKERS.execute({
                Map result = null
                Throwable failure = null
                synchronized (workerThread) { workerThread.set(Thread.currentThread()) }
                try {
                    result = work.call()
                } catch (Throwable t) {
                    failure = t
                } finally {
                    // Order matters: forget the thread (so a late interrupt cannot hit the next query this pooled thread
                    // runs), free the slot, and only then hand the answer back, so a caller that wakes sees the slot free.
                    synchronized (workerThread) { workerThread.set(null) }
                    AgentQueryLane.release(tenantId)
                }
                if (failure != null) future.completeExceptionally(failure) else future.complete(result)
            } as Runnable)
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            AgentQueryLane.release(tenantId)     // no worker ever started, so the slot is still ours
            sink(logSink, logRow(meta, OUTCOME_THROTTLED, 0, 0))
            return [error: "Too many fresh queries are running; try again in ${AgentQueryLane.RETRY_AFTER_SECONDS} seconds.".toString(),
                    code: OUTCOME_THROTTLED, retryAfterSeconds: AgentQueryLane.RETRY_AFTER_SECONDS]
        }
        Map raw
        try {
            raw = future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (TimeoutException ignored) {
            synchronized (workerThread) { workerThread.get()?.interrupt() }     // a nudge only; the slot stays with the worker until it ends
            return failure(meta, logSink, started, OUTCOME_TIMEOUT,
                    "The system did not answer within ${(long) (timeoutMillis / 1000)} seconds.".toString())
        } catch (ExecutionException e) {
            Throwable cause = e.cause ?: e
            if (cause instanceof AgentQueryRefused) return failure(meta, logSink, started, OUTCOME_REFUSED, strip(cause.message, secrets))
            return failure(meta, logSink, started, OUTCOME_ERROR, strip(cause.message ?: cause.class.simpleName, secrets))
        }
        Map shaped = shape(raw ?: [:])
        long duration = System.currentTimeMillis() - started
        shaped.durationMs = duration
        sink(logSink, logRow(meta, OUTCOME_OK, (int) shaped.rowCount, duration))
        return shaped
    }

    private static Map failure(Map meta, Closure logSink, long started, String code, String message) {
        sink(logSink, logRow(meta, code, 0, System.currentTimeMillis() - started))
        return [error: message, code: code]
    }

    /** The success shape, with the body cut at 5 MB (whole rows only). */
    private static Map shape(Map raw) {
        List rows = (raw.rows instanceof List ? (List) raw.rows : []) as List
        int offset = raw.offset instanceof Number ? ((Number) raw.offset).intValue() : 0
        boolean truncated = false
        long bytes = 2
        int keep = 0
        for (Object row : rows) {
            bytes += JsonOutput.toJson(row).getBytes(StandardCharsets.UTF_8).length + 1
            if (bytes > MAX_BODY_BYTES) { truncated = true; break }
            keep++
        }
        if (truncated) rows = new ArrayList(rows.subList(0, keep))
        Map out = [rows: rows, rowCount: rows.size(), hasMore: truncated || (raw.hasMore as boolean), truncated: truncated]
        Object next = truncated ? (offset + keep) : raw.nextOffset
        if (next != null) out.nextOffset = next
        return out
    }

    /** Removes each secret (longest first) from the text. */
    static String strip(String text, List<String> secrets) {
        String out = text ?: ""
        secrets.findAll { it }.sort { -it.length() }.each { String s -> out = out.replace(s, "[hidden]") }
        return out
    }

    private static Map logRow(Map meta, String outcome, int rowCount, long durationMs) {
        String query = (meta?.queryText ?: "").toString()
        return [systemEnumId: meta?.systemEnumId, connectionId: meta?.connectionId,
                queryHash   : sha256Hex(query),
                queryText   : query.length() > MAX_LOGGED_QUERY_CHARS ? query.substring(0, MAX_LOGGED_QUERY_CHARS) : query,
                rowCount    : rowCount, durationMs: durationMs, outcome: outcome]
    }

    static String sha256Hex(String text) {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest((text ?: "").getBytes(StandardCharsets.UTF_8))
        return digest.collect { String.format("%02x", it & 0xff) }.join()
    }

    /** A log failure must never hide the query's own answer. */
    private static void sink(Closure logSink, Map row) {
        try {
            logSink?.call(row)
        } catch (Throwable t) {
            // the answer matters more than its audit row, but the lost row is worth a line in the server log
            logger.warn("Agent query log row not written: ${t.message}".toString())
        }
    }

    /** One row, in its own transaction, so a failed query (rolled back by its caller) still leaves its row. */
    private static void writeLog(def ec, String tenantId, String userId, Map row) {
        Closure write = {
            def value = ec.entity.makeValue("darpan.agent.AgentQueryLog")
            value.setAll(row + [companyUserGroupId: tenantId, userId: userId, createdDate: new Timestamp(System.currentTimeMillis())])
            value.setSequencedIdPrimary()
            value.create()
        }
        ec.transaction.runRequireNew(30, "Write agent query log", write)
    }
}
