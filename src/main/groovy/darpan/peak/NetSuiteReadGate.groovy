package darpan.peak

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-328 plan 2. While a tenant's active peak period says "NetSuite one at a time" (PeakReadPolicy netsuiteSerial),
 * that tenant's NetSuite reads take turns: a fair lock per tenant, held for one read and nothing else. Reentrant, so a
 * read inside a read on one thread never waits for itself. Fails open: a policy that cannot be read, or a turn that has
 * not come after waitLimitMs (a stuck holder), runs the read anyway, the second with one warning.
 *
 * Hold it around one read only, never around code that waits on another thread's read (a side read on a pool thread
 * would wait for a lock its joiner holds).
 */
class NetSuiteReadGate {
    private static final Logger logger = LoggerFactory.getLogger(NetSuiteReadGate)

    static final long WAIT_LIMIT_MS = 30L * 60L * 1000L

    /** Seam for tests. */
    static volatile long waitLimitMs = WAIT_LIMIT_MS

    private static final ConcurrentHashMap<String, ReentrantLock> LOCKS = new ConcurrentHashMap<String, ReentrantLock>()

    /** A turn taken (or not): close() lets the next read go. Idempotent. */
    static final class Turn implements AutoCloseable {
        private ReentrantLock lock
        private Turn(ReentrantLock lock) { this.lock = lock }
        boolean isHeld() { return lock != null }
        @Override
        void close() {
            ReentrantLock held = lock
            lock = null
            if (held != null) held.unlock()
        }
    }

    /** read(), in the tenant's turn when its peak period serialises NetSuite; otherwise directly. */
    static <T> T run(String tenantId, Closure<T> read) {
        Turn turn = enter(null, tenantId)
        try {
            return read.call()
        } finally {
            turn.close()
        }
    }

    /**
     * The tenant's turn for one read, for a caller that cannot wrap its read in a closure (a service script): close it in
     * a finally. Waits at most waitLimitMs; an interrupt while waiting is thrown, so a cancelled read does not start.
     */
    static Turn enter(def ec, String tenantId) {
        String tenant = normalize(tenantId)
        if (!tenant || !serial(ec, tenant)) return new Turn(null)
        ReentrantLock lock = LOCKS.computeIfAbsent(tenant, { String k -> new ReentrantLock(true) })
        if (lock.tryLock(waitLimitMs, TimeUnit.MILLISECONDS)) return new Turn(lock)
        logger.warn("NetSuite read for tenant {} waited {} ms for its turn (peak period: one at a time); reading anyway",
                tenant, waitLimitMs)
        return new Turn(null)
    }

    private static boolean serial(def ec, String tenant) {
        try {
            return PeakReadPolicy.limitsFor(ec, tenant)?.netsuiteSerial == true
        } catch (Throwable t) {
            // PeakReadPolicy already fails open; this guards the guard.
            logger.warn("NetSuite read gate for tenant {} could not read its peak limits; reading with no limit: {}", tenant, t.toString())
            return false
        }
    }

    static void resetForTests() {
        waitLimitMs = WAIT_LIMIT_MS
        LOCKS.clear()
    }
}
