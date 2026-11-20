package darpan.peak

import darpan.facade.common.TenantAccessSupport
import groovy.transform.Immutable
import org.moqui.Moqui
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

import static darpan.common.ValueSupport.normalize

/** DAR-BE-328 plan 2. What a peak period asks of a tenant's reads: HotWax share of the normal rate, NetSuite one at a time. */
@Immutable
class ReadLimits {
    /** Whole percent 1..100 of the normal HotWax read rate; null = no limit. */
    Integer hotwaxSharePct
    boolean netsuiteSerial

    static final ReadLimits NONE = new ReadLimits(null, false)
}

/**
 * "What read limits does tenant T have right now": the active peak period covering the tenant's local today, cached per
 * tenant for 60 seconds (so a period starting or ending is seen within a minute). Fails open: any error reads as NONE,
 * so a broken lookup never stops a read. Callable from pool threads without an ec via limitsFor(String).
 */
class PeakReadPolicy {
    private static final Logger logger = LoggerFactory.getLogger(PeakReadPolicy)
    static final long CACHE_MS = 60_000L
    private static final long WARN_EVERY_MS = 60_000L

    /** Seams for tests. */
    static Closure<Long> clock = { -> System.currentTimeMillis() }
    static Closure<ReadLimits> limitsLoader = { def ec, String tenantId -> loadFromDb(ec, tenantId) }

    private static final class Entry {
        final ReadLimits limits
        final long loadedAtMs
        Entry(ReadLimits limits, long loadedAtMs) { this.limits = limits; this.loadedAtMs = loadedAtMs }
    }

    private static final ConcurrentHashMap<String, Entry> CACHE = new ConcurrentHashMap<String, Entry>()
    private static final ConcurrentHashMap<String, Long> LAST_WARN = new ConcurrentHashMap<String, Long>()

    /** For code with no ExecutionContext in hand (pool threads): serves the cache, else loads through Moqui's thread context. */
    static ReadLimits limitsFor(String tenantId) { return limitsFor(null, tenantId) }

    static ReadLimits limitsFor(def ec, String tenantId) {
        String tenant = normalize(tenantId)
        if (!tenant) return ReadLimits.NONE
        try {
            long nowMs = clock.call()
            Entry hit = CACHE.get(tenant)
            if (hit != null && nowMs - hit.loadedAtMs < CACHE_MS && nowMs >= hit.loadedAtMs) return hit.limits
            ReadLimits loaded = limitsLoader.call(ec, tenant) ?: ReadLimits.NONE
            CACHE.put(tenant, new Entry(loaded, nowMs))
            return loaded
        } catch (Throwable t) {
            warnOnce(tenant, t)
            return ReadLimits.NONE
        }
    }

    static void resetForTests() {
        CACHE.clear()
        LAST_WARN.clear()
        clock = { -> System.currentTimeMillis() }
        limitsLoader = { def ec, String tenantId -> loadFromDb(ec, tenantId) }
    }

    private static ReadLimits loadFromDb(def ec, String tenantId) {
        def context = ec ?: Moqui.getExecutionContext()
        LocalDate today = LocalDate.now(TenantAccessSupport.tenantTimeZoneFor(context, tenantId))
        return PeakPeriodLoader.readLimits(context, tenantId, today)
    }

    private static void warnOnce(String tenant, Throwable t) {
        long nowMs = System.currentTimeMillis()
        Long last = LAST_WARN.get(tenant)
        if (last == null || nowMs - last >= WARN_EVERY_MS) {
            LAST_WARN.put(tenant, nowMs)
            logger.warn("Peak read limits for tenant {} could not be read; reading with no limits: {}", tenant, t.toString())
        }
    }
}
