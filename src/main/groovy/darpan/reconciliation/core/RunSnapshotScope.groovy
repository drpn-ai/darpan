package darpan.reconciliation.core

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * DAR-BE-073. What one press of Run may read once and share across its questions.
 *
 * A lifecycle's questions all ask about the same records in the same window, and each used to read them from the
 * source again. The walk opens a scope around itself; a connector that can answer a question from what an earlier
 * question already read keeps it here, under a key naming exactly what was read, and the scope throws it away when
 * the walk ends. A connector with nothing to share ignores the scope, so every question still runs as an ordinary run.
 *
 * Thread-bound: the walk calls each question synchronously on its own thread (RunFlowchartSupport), so the scope is
 * seen by the extract and by nothing running beside it. A question run outside a walk sees no scope.
 *
 * Off switch: -Ddarpan.reconciliation.runSnapshot=off makes every question read live, as before.
 */
class RunSnapshotScope {

    private static final Logger logger = LoggerFactory.getLogger(RunSnapshotScope.class)
    static final String SWITCH_PROPERTY = "darpan.reconciliation.runSnapshot"
    private static final ThreadLocal<RunSnapshotScope> CURRENT = new ThreadLocal<>()

    /** Where a connector may write what it read; the scope deletes what it registers there. */
    final File dir
    private final Map<String, Object> entries = [:]
    private final List<Closure> closers = []

    private RunSnapshotScope(File dir) { this.dir = dir }

    private static boolean enabled() {
        return !"off".equalsIgnoreCase(System.getProperty(SWITCH_PROPERTY)?.trim())
    }

    /** Runs body inside a scope; a nested call joins the scope already open. */
    static <T> T within(File dir, Closure<T> body) {
        if (!enabled() || CURRENT.get() != null) return body.call()
        RunSnapshotScope scope = new RunSnapshotScope(new File(dir, "run-snapshot"))
        CURRENT.set(scope)
        try {
            return body.call()
        } finally {
            CURRENT.remove()
            scope.close()
        }
    }

    static RunSnapshotScope current() { return CURRENT.get() }

    /** Test-harness reset: drops the calling thread's scope. Never called from src/main. */
    static void resetForTests() { CURRENT.remove() }

    Object get(String key) { return entries.get(key) }

    void put(String key, Object value, Closure onClose = null) {
        entries.put(key, value)
        if (onClose != null) closers.add(onClose)
    }

    private void close() {
        // A closer that fails leaves a file behind; the walk's answer stands, but the leak is logged, not hidden.
        closers.each { Closure c ->
            try { c.call() } catch (Throwable t) { logger.warn("Run snapshot closer failed in {}: {}", dir, t.toString()) }
        }
        entries.clear()
        if (dir.isDirectory()) dir.deleteDir()
    }
}
