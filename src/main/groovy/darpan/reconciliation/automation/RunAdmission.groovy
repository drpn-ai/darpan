package darpan.reconciliation.automation

import darpan.admin.SystemLoadReader
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * DAR-BE-143. Which automation execution starts when, so several audits run at once without overloading the instance.
 *
 * Every execution goes through here: the morning scan, its retries, play, a retry from the last read and a finding's
 * re-check. Play used to hand each check straight to Moqui's worker pool, past the scan's cap, so two plays could take
 * every worker. Now:
 *
 * - the instance runs at most {@code cap} executions at once (maxConcurrentExecutions);
 * - one tenant runs at most {@code perTenantCap} of them (maxConcurrentExecutionsPerTenant), so one tenant's audit
 *   leaves room for another tenant's. A tenant's audit is bounded by its own systems' rate limits (HotWax refills
 *   2000 cost/s per endpoint), not by how many of its checks run at once, so the share costs it little;
 * - waiting tenants take turns, first-come within a tenant;
 * - while old-generation heap after the last GC is at or above {@code holdAtHeapPercent}, nothing new starts. One
 *   execution always may when none is running, so a stale reading never stops the instance for good.
 *
 * Executions run on this class's own threads, not Moqui's worker pool: each holds its thread for the whole run (40+
 * minutes for an audit check, mostly waiting on rate limits), and the pool's 16 core threads also run the scan job and
 * mail. The thread count is bounded by {@code cap}.
 *
 * In memory, per JVM. The scan submits only what can start at once ({@link #canStartNow}) and leaves the rest due,
 * so a restart loses no scheduled window; what waits here was asked for by a person (play, retry, re-check).
 */
class RunAdmission {

    private static final Logger logger = LoggerFactory.getLogger(RunAdmission)

    static final String PER_TENANT_PROPERTY = "darpan.reconciliation.automation.maxConcurrentExecutionsPerTenant"
    static final String HOLD_HEAP_PROPERTY = "darpan.reconciliation.automation.holdAtHeapPercent"
    static final int DEFAULT_PER_TENANT = 5
    static final int DEFAULT_HOLD_AT_HEAP_PERCENT = 85

    static final RunAdmission SHARED = new RunAdmission(AutomationExecutionSupport.MAX_CONCURRENT_EXECUTIONS,
            intProperty(PER_TENANT_PROPERTY, DEFAULT_PER_TENANT), intProperty(HOLD_HEAP_PROPERTY, DEFAULT_HOLD_AT_HEAP_PERCENT),
            { -> SystemLoadReader.heapAfterGcPercent() }, runPool())

    private static class Waiting {
        String tenantId
        String label
        Runnable work
        boolean started
        String error
    }

    private final int cap
    private final int perTenantCap
    private final int holdAtHeapPercent
    private final Closure<Integer> heapPercent
    private final Executor executor
    /** Waiting runs per tenant, tenants in the order they first waited. */
    private final LinkedHashMap<String, ArrayDeque<Waiting>> waiting = new LinkedHashMap<>()
    private final Map<String, Integer> runningByTenant = [:]
    private int running = 0
    private String lastStarted = null

    RunAdmission(int cap, int perTenantCap, int holdAtHeapPercent, Closure<Integer> heapPercent, Executor executor) {
        this.cap = Math.max(1, cap)
        this.perTenantCap = Math.max(1, Math.min(perTenantCap, this.cap))
        this.holdAtHeapPercent = holdAtHeapPercent
        this.heapPercent = heapPercent
        this.executor = executor
    }

    /**
     * Starts the work now, or keeps it waiting for a slot. Answers submitted (false only when the start was refused),
     * queued and, when queued, position (1 = next of the instance's waiting runs).
     */
    synchronized Map<String, Object> submit(String tenantId, String label, Runnable work) {
        Waiting entry = new Waiting(tenantId: tenantKey(tenantId), label: label, work: work)
        waiting.computeIfAbsent(entry.tenantId, { new ArrayDeque<Waiting>() }).addLast(entry)
        drain()
        if (entry.error) return [submitted: false, queued: false, error: entry.error]
        if (entry.started) return [submitted: true, queued: false]
        int position = waitingTotal()
        return [submitted: true, queued: true, position: position]
    }

    /** Whether a run of this tenant submitted now would start without waiting. */
    synchronized boolean canStartNow(String tenantId) {
        String key = tenantKey(tenantId)
        int tenantLoad = (runningByTenant[key] ?: 0) + (waiting[key]?.size() ?: 0)
        return running + waitingTotal() < cap && tenantLoad < perTenantCap && !held()
    }

    /** Starts every waiting run there is room for, tenants in turn. Called on submit, on finish, and by each scan tick. */
    synchronized void drain() {
        while (running < cap && !held()) {
            Waiting next = nextEligible()
            if (next == null) return
            start(next)
        }
    }

    synchronized Map<String, Object> snapshot() {
        Set<String> tenants = new TreeSet<String>(runningByTenant.keySet())
        tenants.addAll(waiting.keySet())
        Map<String, Map<String, Integer>> byTenant = tenants.collectEntries { String t ->
            [(t): [running: runningByTenant[t] ?: 0, queued: waiting[t]?.size() ?: 0]]
        }
        return [running      : running, queued: waitingTotal(), cap: cap, perTenantCap: perTenantCap,
                holdAtHeapPercent: holdAtHeapPercent, heldForMemory: running > 0 && heapAtOrAbove(), byTenant: byTenant]
    }

    private Waiting nextEligible() {
        List<String> tenants = new ArrayList<String>(waiting.keySet())
        if (!tenants) return null
        int after = lastStarted != null ? tenants.indexOf(lastStarted) : -1
        for (int i = 1; i <= tenants.size(); i++) {
            String tenant = tenants[(after + i) % tenants.size()]
            if ((runningByTenant[tenant] ?: 0) < perTenantCap) return waiting[tenant].peekFirst()
        }
        return null
    }

    private void start(Waiting entry) {
        ArrayDeque<Waiting> lane = waiting[entry.tenantId]
        lane.removeFirst()
        if (lane.isEmpty()) waiting.remove(entry.tenantId)
        running++
        runningByTenant[entry.tenantId] = (runningByTenant[entry.tenantId] ?: 0) + 1
        lastStarted = entry.tenantId
        try {
            executor.execute({ -> runThenRelease(entry) } as Runnable)
            entry.started = true
        } catch (Throwable refused) {
            release(entry.tenantId)
            entry.error = "Could not start ${entry.label}: ${refused.message ?: refused.toString()}".toString()
            logger.error(entry.error)
        }
    }

    private void runThenRelease(Waiting entry) {
        try {
            entry.work.run()
        } catch (Throwable t) {
            logger.error("Automation execution {} ended with an error: {}", entry.label, t.message ?: t.toString())
        } finally {
            synchronized (this) {
                release(entry.tenantId)
                drain()
            }
        }
    }

    private void release(String tenantId) {
        running = Math.max(0, running - 1)
        int left = (runningByTenant[tenantId] ?: 1) - 1
        if (left > 0) runningByTenant[tenantId] = left
        else runningByTenant.remove(tenantId)
    }

    /** Memory holds new starts only while something is running; with nothing running one start is always allowed. */
    private boolean held() { return running > 0 && heapAtOrAbove() }

    private boolean heapAtOrAbove() {
        Integer percent
        try { percent = heapPercent.call() } catch (Throwable ignored) { percent = null }
        return percent != null && percent >= holdAtHeapPercent
    }

    private int waitingTotal() { return (int) (waiting.values().sum { it.size() } ?: 0) }

    private static String tenantKey(String tenantId) { return tenantId ?: "_none" }

    private static int intProperty(String name, int fallback) {
        String value = System.getProperty(name)
        return value?.isInteger() ? value.toInteger() : fallback
    }

    private static Executor runPool() {
        AtomicInteger count = new AtomicInteger()
        return Executors.newCachedThreadPool({ Runnable r ->
            Thread t = new Thread(r, "darpan-run-" + count.incrementAndGet()); t.daemon = true; t
        } as ThreadFactory)
    }
}
