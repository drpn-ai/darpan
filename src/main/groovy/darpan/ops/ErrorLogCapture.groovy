package darpan.ops

import darpan.common.MessageScope
import darpan.common.SecretRedactor
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.ThreadContext
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.util.ReadOnlyStringMap
import org.moqui.context.LogEventSubscriber
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Date as SqlDate
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiFunction
import java.util.regex.Pattern

/**
 * DAR-BE-015 (spec E). Every ERROR (and FATAL) log line of the whole instance, grouped in memory by fingerprint (logger + message
 * with numbers and ids masked) with a count, first and last seen, one sample message and stack and the thread's tenant.
 * {@link #flush} (ServiceJob flush_ErrorLog_5m, via ops.ErrorDigestServices.flush#ErrorLog) adds them into the day's
 * darpan.ops.ErrorLogEntry rows for the daily error email.
 *
 * {@link #process} runs on the thread log4j delivers on (AsyncLog in both configs): it never throws, never logs (a line it wrote
 * would come straight back) and never waits beyond one map bin. Lines logged during a flush carry FLUSH_MDC_KEY and are ignored,
 * so a failing flush cannot feed itself.
 */
@CompileStatic
class ErrorLogCapture implements LogEventSubscriber {
    /** Used only by {@link #flush}, at WARN, outside the capture (WARN is never kept). */
    private static final Logger logger = LoggerFactory.getLogger(ErrorLogCapture)

    static final ErrorLogCapture INSTANCE = new ErrorLogCapture()

    static final String FLUSH_SERVICE = "ops.OpsServices.add#ErrorLogEntries"
    /** The fingerprint every line past the cap adds to. */
    static final String OVERFLOW = "OVERFLOW"
    /** Distinct fingerprints held between two flushes; past it, lines count under OVERFLOW. */
    static final int MAX_FINGERPRINTS = 500
    /** ErrorLogEntry.fingerprint is id-long (255). */
    static final int FINGERPRINT_MAX_CHARS = 255
    /** ErrorLogEntry.sampleMessage and loggerName are text-medium (255 on MySQL). */
    static final int SAMPLE_MAX_CHARS = 255
    /** ErrorLogEntry.sampleStack is text-long: 4095 on MySQL, 4000 on Oracle and SQL Server. */
    static final int STACK_MAX_CHARS = 4000
    /** Masking reads at most this much of a message: an ERROR can carry a megabyte of SQL. */
    private static final int MASK_MAX_CHARS = 1000
    static final String TENANT_MDC_KEY = "darpan.tenant"
    /**
     * Set in the log4j thread context while a flush runs. log4j copies the context onto each event on the logging thread (before
     * AsyncLog hands it over), so a line the flush itself causes (an entity, transaction or service error) carries it and is not kept.
     */
    static final String FLUSH_MDC_KEY = "darpan.errorLogFlush"
    /** The thread-context tenant of a request with no tenant (DarpanMdcSupport); kept as no tenant. */
    private static final String NO_TENANT = "anonymous"

    private static final Pattern QUOTED_ID = Pattern.compile("'[^'\\s]{1,120}'|\"[^\"\\s]{1,120}\"|`[^`\\s]{1,120}`")
    private static final Pattern UUID_LIKE = Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b")
    private static final Pattern HEX_LIKE = Pattern.compile("(?i)\\b0x[0-9a-f]+\\b|\\b(?=[a-f]*\\d)[0-9a-f]{8,}\\b")
    private static final Pattern DIGITS = Pattern.compile("\\d+")
    private static final Pattern SPACES = Pattern.compile("\\s+")

    /** Seam: replaces the store call, called with (ec, rows) on the flushing thread. Null in production (FLUSH_SERVICE). */
    static Closure storeHook = null

    private final ConcurrentHashMap<String, Agg> buffer = new ConcurrentHashMap<String, Agg>()

    static void resetForTests() {
        INSTANCE.buffer.clear()
        storeHook = null
    }

    /** One fingerprint's aggregate. Mutated only inside the map's compute for its key. */
    private static final class Agg {
        String fingerprint, loggerName, sampleMessage, sampleStack, tenantId
        long count, firstSeen, lastSeen
    }

    @Override
    void process(LogEvent event) {
        try {
            if (event == null) return
            Level level = event.getLevel()
            if (level == null || !level.isMoreSpecificThan(Level.ERROR)) return
            long when = event.getTimeMillis()
            if (when <= 0L) when = System.currentTimeMillis()
            ReadOnlyStringMap context = event.getContextData()
            if (context != null && context.getValue(FLUSH_MDC_KEY) != null) return
            String loggerName = event.getLoggerName() ?: ""
            String raw = event.getMessage()?.getFormattedMessage() ?: ""
            // DAR-BE-408: a secret in an ERROR line never reaches the digest; only the head the fingerprint reads is masked
            String message = SecretRedactor.redact(raw.length() > MASK_MAX_CHARS ? raw.substring(0, MASK_MAX_CHARS) : raw)
            Throwable thrown = event.getThrown()
            String tenantId = tenantOf(context)

            String fp = fingerprint(loggerName, message)
            if (!buffer.containsKey(fp) && buffer.size() >= MAX_FINGERPRINTS) fp = OVERFLOW
            final String key = fp
            final long at = when
            buffer.compute(key, { String k, Agg agg ->
                if (agg == null) {
                    agg = new Agg(fingerprint: k, loggerName: cap(loggerName, SAMPLE_MAX_CHARS), sampleMessage: cap(message, SAMPLE_MAX_CHARS),
                            sampleStack: thrown != null ? stackText(thrown) : null, tenantId: tenantId, firstSeen: at, lastSeen: at)
                }
                agg.count++
                if (at < agg.firstSeen) agg.firstSeen = at
                if (at > agg.lastSeen) agg.lastSeen = at
                if (agg.tenantId == null) agg.tenantId = tenantId
                return agg
            } as BiFunction<String, Agg, Agg>)
        } catch (Throwable ignored) {
            // never throw and never log from the logging thread
        }
    }

    /** True when a line with this fingerprint waits for the next flush. */
    boolean holds(String fingerprint) { return fingerprint != null && buffer.containsKey(fingerprint) }

    /**
     * Returns and clears the aggregates: [fingerprint, loggerName, sampleMessage, sampleStack, count, firstSeen, lastSeen, tenantId].
     * Each key is removed on its own, so a line logged meanwhile lands in a fresh aggregate, never in one already taken.
     */
    List<Map> drain() {
        List<Map> out = []
        for (String key : new ArrayList<String>(buffer.keySet())) {
            Agg agg = buffer.remove(key)
            if (agg == null) continue
            out.add([fingerprint: agg.fingerprint, loggerName: agg.loggerName, sampleMessage: agg.sampleMessage, sampleStack: agg.sampleStack,
                    count: agg.count, firstSeen: new Timestamp(agg.firstSeen), lastSeen: new Timestamp(agg.lastSeen), tenantId: agg.tenantId] as Map)
        }
        return out
    }

    /** Adds drained aggregates back (a flush that could not store them), keeping the earliest sample. */
    void restore(List<Map> entries) {
        for (Map e : entries) {
            if (e?.fingerprint == null) continue
            // the cap holds here too: a store that keeps failing must not let the buffer grow by a window's worth each time
            String key = e.fingerprint as String
            if (!buffer.containsKey(key) && buffer.size() >= MAX_FINGERPRINTS) key = OVERFLOW
            final Agg back = new Agg(fingerprint: key, loggerName: e.loggerName as String, sampleMessage: e.sampleMessage as String,
                    sampleStack: e.sampleStack as String, tenantId: e.tenantId as String, count: (e.count ?: 0L) as long,
                    firstSeen: ((Timestamp) e.firstSeen)?.time ?: 0L, lastSeen: ((Timestamp) e.lastSeen)?.time ?: 0L)
            buffer.merge(back.fingerprint, back, { Agg cur, Agg restored ->
                cur.count += restored.count
                if (restored.lastSeen > cur.lastSeen) cur.lastSeen = restored.lastSeen
                if (restored.firstSeen < cur.firstSeen) {
                    cur.firstSeen = restored.firstSeen
                    cur.loggerName = restored.loggerName; cur.sampleMessage = restored.sampleMessage; cur.sampleStack = restored.sampleStack
                    if (restored.tenantId != null) cur.tenantId = restored.tenantId
                }
                return cur
            } as BiFunction<Agg, Agg, Agg>)
        }
    }

    /**
     * Drains the aggregates and adds them into darpan.ops.ErrorLogEntry, one row per fingerprint per UTC day (the day of the
     * aggregate's last line). Runs in its own transaction, never the caller's; when the store fails the aggregates go back for the
     * next flush. The caller's messages and errors come back exactly as they were. Lines logged during the flush are not kept.
     */
    @CompileDynamic
    static void flush(def ec) {
        List<Map> entries = INSTANCE.drain()
        if (entries.isEmpty()) return
        List<Map> rows = entries.collect { Map e ->
            [logDay: SqlDate.valueOf(Instant.ofEpochMilli(((Timestamp) e.lastSeen).time).atZone(ZoneOffset.UTC).toLocalDate())] + e
        }
        boolean stored = false
        String failure = null
        String priorFlag = ThreadContext.get(FLUSH_MDC_KEY)
        ThreadContext.put(FLUSH_MDC_KEY, "true")
        try {
            // the caller's messages and errors come back exactly as they were; the store's own are discarded (MessageScope)
            MessageScope.isolated(ec) {
                try {
                    if (storeHook != null) storeHook.call(ec, rows)
                    else ec.service.sync().name(FLUSH_SERVICE).requireNewTransaction(true).parameters([entries: rows]).call()
                    stored = !ec.message.hasError()
                    if (!stored) failure = "${ec.message.getErrors().size()} store error(s)"
                } catch (Throwable t) {
                    failure = t.getClass().name
                }
            }
        } finally {
            if (priorFlag != null) ThreadContext.put(FLUSH_MDC_KEY, priorFlag) else ThreadContext.remove(FLUSH_MDC_KEY)
            if (!stored) INSTANCE.restore(entries)
        }
        if (!stored) logger.warn("Error log flush kept {} fingerprints for the next try: {}", entries.size(), failure)
    }

    /**
     * Logger plus the message with quoted ids, uuids, hex runs and digits masked and whitespace collapsed. One past the key
     * length keeps a hash of the whole, so two long lines differing only at the end stay apart.
     */
    static String fingerprint(String loggerName, String message) {
        String m = message ?: ""
        if (m.length() > MASK_MAX_CHARS) m = m.substring(0, MASK_MAX_CHARS)
        m = QUOTED_ID.matcher(m).replaceAll("*")
        m = UUID_LIKE.matcher(m).replaceAll("*")
        m = HEX_LIKE.matcher(m).replaceAll("*")
        m = DIGITS.matcher(m).replaceAll("#")
        String fp = SPACES.matcher(((loggerName ?: "") + " " + m)).replaceAll(" ").trim()
        if (fp.length() <= FINGERPRINT_MAX_CHARS) return fp
        String hash = "~" + Integer.toHexString(fp.hashCode())
        return fp.substring(0, FINGERPRINT_MAX_CHARS - hash.length()) + hash
    }

    private static String tenantOf(ReadOnlyStringMap context) {
        if (context == null || context.isEmpty()) return null
        Object v = context.getValue(TENANT_MDC_KEY)
        if (v == null || NO_TENANT == v.toString()) return null
        return cap(v.toString(), 40)
    }

    private static String cap(String s, int max) { return s == null || s.length() <= max ? s : s.substring(0, max) }

    /** Frames kept per throwable, so the causes (often the useful part) still fit. */
    private static final int FRAMES_PER_THROWABLE = 12

    /** The throwable and its causes, up to FRAMES_PER_THROWABLE frames each, stopped at STACK_MAX_CHARS, secrets masked. */
    private static String stackText(Throwable thrown) {
        StringBuilder sb = new StringBuilder()
        Throwable t = thrown
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>())
        while (t != null && seen.add(t) && sb.length() < STACK_MAX_CHARS) {
            if (t != thrown) sb.append("Caused by: ")
            sb.append(t.getClass().name)
            String msg = t.getMessage()
            if (msg != null) sb.append(": ").append(msg.length() > STACK_MAX_CHARS ? msg.substring(0, STACK_MAX_CHARS) : msg)
            sb.append('\n')
            StackTraceElement[] frames = t.getStackTrace()
            int shown = Math.min(frames.length, FRAMES_PER_THROWABLE)
            for (int i = 0; i < shown && sb.length() < STACK_MAX_CHARS; i++) sb.append("\tat ").append(frames[i].toString()).append('\n')
            if (frames.length > shown) sb.append("\t... ").append(frames.length - shown).append(" more\n")
            t = t.getCause()
        }
        // DAR-BE-408: masked, then capped again (a mask can be longer than what it hides)
        String masked = SecretRedactor.redact(sb.length() > STACK_MAX_CHARS ? sb.substring(0, STACK_MAX_CHARS) : sb.toString())
        return masked.length() > STACK_MAX_CHARS ? masked.substring(0, STACK_MAX_CHARS) : masked
    }
}
