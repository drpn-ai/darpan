package darpan.datasource

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * DAR-BE-176. Paces calls against a server-reported cost bucket, for any system whose answers report one. Where the
 * bucket is reported, and under which member names, is the definition's `limits.costBucket`; this class only keeps
 * the balance.
 *
 * A token bucket that may go into debt: each call takes its reservation when it is let go, so callers queue behind one
 * another; each answer resets the balance to what the server reports, less the reservations still out (the server
 * charges a call's actual cost, often less than reserved, and only its report gives the difference back). Pure: it
 * returns a duration and the caller sleeps, so the policy is testable without a clock.
 */
class CostBucketGovernor {

    private static final long SAFETY_MARGIN_MS = 500L

    /** One bucket per endpoint and credential: every read on that credential spends from it. */
    private static final Map<String, CostBucketGovernor> SHARED = new ConcurrentHashMap<>()

    static CostBucketGovernor shared(String key, int maxInFlight) {
        return SHARED.computeIfAbsent(key ?: "", { new CostBucketGovernor(maxInFlight) })
    }

    static void resetShared() { SHARED.clear() }

    private final Semaphore inFlight
    private final Closure<Long> clock
    private double available = 0d
    private boolean observed = false
    private double restoreRate = 50d
    private double maximum = Double.MAX_VALUE
    private long updatedAt = 0L
    private long outstanding = 0L

    CostBucketGovernor(int maxInFlight = 6, Closure<Long> clock = { -> System.currentTimeMillis() }) {
        this.inFlight = new Semaphore(Math.max(1, maxInFlight), true)
        this.clock = clock
    }

    void acquireCall() { inFlight.acquire() }

    void releaseCall() { inFlight.release() }

    synchronized int getAvailable() { if (!observed) return -1; refill(); return (int) Math.floor(available) }

    private void refill() {
        long now = (long) clock.call()
        if (observed && now > updatedAt) available = Math.min(maximum, available + (now - updatedAt) * restoreRate / 1000d)
        updatedAt = now
    }

    /** Milliseconds to wait before a call reserving `reservation`; the reservation is taken. */
    synchronized long sleepMillisFor(int reservation) {
        outstanding += reservation
        if (!observed) return 0L
        refill()
        long wait = available >= reservation ? 0L :
                (long) Math.ceil((reservation - available) / Math.max(1d, restoreRate) * 1000d) + SAFETY_MARGIN_MS
        available -= reservation
        return wait
    }

    /**
     * A call let go with `reservation` has its answer. `status` is the reported bucket (already read from the answer at
     * the definition's path) with the definition's member names: [available, maximum, restoreRate]. Null: the answer
     * reported none (an error), so only the reservation is given up.
     */
    synchronized void settle(int reservation, Map status, Map names) {
        outstanding = Math.max(0L, outstanding - reservation)
        if (!(status instanceof Map) || names == null) return
        Object rate = status.get(names.restoreRate)
        Object max = status.get(names.maximum)
        Object now = status.get(names.available)
        if (rate instanceof Number) restoreRate = ((Number) rate).doubleValue()
        if (max instanceof Number) maximum = ((Number) max).doubleValue()
        if (now instanceof Number) {
            available = ((Number) now).doubleValue() - outstanding
            updatedAt = (long) clock.call()
            observed = true
        }
    }
}
