package darpan.agent

/**
 * DAR-CLI-003. The admission lane for fresh read-only queries an agent sends to a tenant's own systems (NetSuite, HotWax
 * OMS, Shopify). Separate from RunAdmission: a person's agent asking questions must never queue behind, or starve, a
 * reconciliation run. 4 concurrent per instance, 2 per tenant; a full lane refuses at once (THROTTLED), it never waits.
 * Pure: no Moqui.
 */
class AgentQueryLane {

    static final int MAX_PER_INSTANCE = 4
    static final int MAX_PER_TENANT = 2
    static final int RETRY_AFTER_SECONDS = 10

    private static final Map<String, Integer> IN_FLIGHT = new HashMap<>()
    private static int total = 0

    /** Takes a slot for the tenant, or answers false when the tenant or the instance is full. */
    static synchronized boolean tryAcquire(String tenantId) {
        String key = tenantId ?: ""
        int mine = IN_FLIGHT.getOrDefault(key, 0)
        if (mine >= MAX_PER_TENANT || total >= MAX_PER_INSTANCE) return false
        IN_FLIGHT.put(key, mine + 1)
        total++
        return true
    }

    /** Gives a slot back. A release with nothing held is ignored. */
    static synchronized void release(String tenantId) {
        String key = tenantId ?: ""
        int mine = IN_FLIGHT.getOrDefault(key, 0)
        if (mine <= 0) return
        if (mine == 1) IN_FLIGHT.remove(key) else IN_FLIGHT.put(key, mine - 1)
        total--
    }

    static synchronized int inFlight(String tenantId) { return IN_FLIGHT.getOrDefault(tenantId ?: "", 0) }

    /** Test seam: empties the lane. */
    static synchronized void reset() {
        IN_FLIGHT.clear()
        total = 0
    }
}
