package darpan.script

import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs tenant scripts inside Moqui, for any engine, on a small dedicated pool: never on request, run or Spark threads.
 * One script at a time per tenant; nothing starts while ScriptLoadGate says the core is busy or scripts are switched
 * off (DARPAN_SCRIPTS_ENABLED=false). A script that ignores its deadline is counted as stuck until it really ends.
 */
@CompileStatic
class ScriptLane {
    protected final static Logger logger = LoggerFactory.getLogger(ScriptLane.class)

    static final int THREADS = 2
    static final int QUEUE = 4
    /** wait this long past the engine's own limit before interrupting */
    static final long GRACE_MS = 1_000L

    private static final AtomicInteger threadNo = new AtomicInteger()
    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(THREADS, THREADS, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(QUEUE), { Runnable r ->
                Thread t = new Thread(r, 'darpan-script-lane-' + threadNo.incrementAndGet())
                t.daemon = true
                t.priority = Thread.NORM_PRIORITY - 1
                return t
            }, new ThreadPoolExecutor.AbortPolicy())

    private static final Map<String, Semaphore> TENANT_SLOTS = new ConcurrentHashMap<>()
    private static final AtomicInteger running = new AtomicInteger()
    private static final AtomicInteger stuck = new AtomicInteger()
    private static final AtomicLong runs = new AtomicLong()
    private static final Map<String, AtomicLong> refusedBy = new ConcurrentHashMap<>()
    private static volatile Boolean enabledOverride = null

    static boolean isEnabled() {
        if (enabledOverride != null) return enabledOverride
        return !'false'.equalsIgnoreCase(System.getenv('DARPAN_SCRIPTS_ENABLED') ?: System.getProperty('darpan.scripts.enabled'))
    }

    // NOTE: in memory only, null goes back to the environment setting
    static void setEnabled(Boolean enabled) { enabledOverride = enabled }

    /** Runs work (returns {ok, result, error, units, elapsedMs, logs}) on the lane; never throws. */
    static Map<String, Object> run(String tenantId, String engine, long limitMs, Closure<Map<String, Object>> work) {
        long started = System.nanoTime()
        Map<String, Object> refusal = admit(tenantId)
        if (refusal != null) return finish(tenantId, engine, refusal, started)

        Semaphore slot = TENANT_SLOTS.computeIfAbsent(tenantId ?: '_none') { String k -> new Semaphore(1) }
        if (!slot.tryAcquire()) {
            return finish(tenantId, engine, refused('TENANT_BUSY', 'This tenant already has a script running; try again when it ends.'), started)
        }
        // 0 = running, 1 = ended, 2 = stuck (past its deadline, still running)
        AtomicInteger state = new AtomicInteger(0)
        Future<Map<String, Object>> future
        try {
            future = POOL.submit({ ->
                running.incrementAndGet()
                try {
                    return work.call()
                } finally {
                    running.decrementAndGet()
                    if (state.getAndSet(1) == 2) stuck.decrementAndGet()
                    // release only when the work has really ended, so a stuck script can't pile up more
                    slot.release()
                }
            } as java.util.concurrent.Callable<Map<String, Object>>)
        } catch (RejectedExecutionException e) {
            slot.release()
            return finish(tenantId, engine, refused('LANE_FULL', "All ${THREADS} script threads and ${QUEUE} queue places are taken; try again shortly.".toString()), started)
        }
        try {
            return finish(tenantId, engine, future.get(limitMs + GRACE_MS, TimeUnit.MILLISECONDS), started)
        } catch (TimeoutException e) {
            if (state.compareAndSet(0, 2)) stuck.incrementAndGet()
            // NOTE: only a request; JEXL steps and Groovy checkpoints check it, a long library call may not
            future.cancel(true)
            return finish(tenantId, engine, failure('STUCK', "The script did not stop at its ${limitMs} ms limit. It was asked to stop; the lane counts it as stuck until it really ends.".toString()), started)
        } catch (ExecutionException e) {
            return finish(tenantId, engine, failure('ERROR', String.valueOf(e.cause?.message).take(300)), started)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
            return finish(tenantId, engine, failure('ERROR', 'Interrupted while waiting for the script.'), started)
        }
    }

    /** For the Script Lab screen. */
    static Map<String, Object> status() {
        Runtime rt = Runtime.runtime
        long used = rt.totalMemory() - rt.freeMemory()
        return [enabled: isEnabled(), threads: THREADS, running: running.get(), queued: POOL.queue.size(), queueCapacity: QUEUE,
                stuck: stuck.get(), runs: runs.get(), refused: refusedBy.collectEntries { String k, AtomicLong v -> [(k): v.get()] },
                heapUsedMb: used >> 20, heapMaxMb: rt.maxMemory() >> 20, heapPercent: Math.round(100d * used / rt.maxMemory()),
                heapGatePercent: ScriptLoadGate.holdAtHeapPercent(),
                busyTenants: TENANT_SLOTS.findAll { String k, Semaphore s -> s.availablePermits() == 0 }.keySet().sort()] as Map<String, Object>
    }

    private static Map<String, Object> admit(String tenantId) {
        if (!isEnabled()) return refused('SWITCHED_OFF', 'Scripts are switched off on this server.')
        if (!tenantId) return refused('NO_TENANT', 'Choose a tenant first.')
        Map<String, Object> gate = ScriptLoadGate.check()
        if (gate.busy) return refused('BUSY', ScriptLoadGate.busyMessage(gate.reason as String))
        return null
    }

    private static Map<String, Object> refused(String kind, String message) {
        refusedBy.computeIfAbsent(kind) { String k -> new AtomicLong() }.incrementAndGet()
        return failure(kind, message)
    }

    private static Map<String, Object> failure(String kind, String message) {
        return [ok: false, result: null, error: [kind: kind, message: message], units: 0L, elapsedMs: 0L, logs: []] as Map<String, Object>
    }

    private static Map<String, Object> finish(String tenantId, String engine, Map<String, Object> out, long started) {
        runs.incrementAndGet()
        long ms = (System.nanoTime() - started).intdiv(1_000_000L)
        Map error = out.error as Map
        logger.info("[script-lane] tenant=${tenantId} engine=${engine} outcome=${out.ok ? 'OK' : error?.kind} units=${out.units} laneMs=${ms}")
        Map<String, Object> result = new LinkedHashMap<String, Object>(out)
        result.put('laneMs', ms)
        result.put('engine', engine)
        return result
    }
}
