package darpan.facade.common

import org.apache.logging.log4j.ThreadContext

/**
 * Stamps per-request MDC (log4j2 ThreadContext) correlation keys for every /rpc/json call and
 * clears them at request end so values never leak across pooled threads.
 *
 * Keys stamped:
 *   darpan.tenant        — active tenant userGroupId (or "anonymous")
 *   darpan.userId        — authenticated userId (or "anonymous")
 *   darpan.correlationId — per-request UUID; reuses X-Request-Id / X-Correlation-Id if present
 *
 * Hook: called from MoquiConf.xml <before-request> (stamp) and <after-request> (clear).
 * TenantAccessSupport.syncUserContext delegates here after resolving tenant context so the MDC
 * reflects the same tenant the request sees.
 */
class DarpanMdcSupport {

    static final String MDC_TENANT         = "darpan.tenant"
    static final String MDC_USER_ID        = "darpan.userId"
    static final String MDC_CORRELATION_ID = "darpan.correlationId"

    /**
     * Stamp MDC keys from the resolved execution context. Safe to call multiple times per request
     * (stamp is idempotent — correlationId is only generated on the first call per thread).
     *
     * @param ec         Moqui ExecutionContext
     * @param tenantId   resolved active tenant userGroupId (may be null for anonymous)
     */
    static void stamp(def ec, String tenantId) {
        // Preserve an existing correlationId (set earlier in before-request) so re-stamps after
        // syncUserContext don't rotate the ID mid-request.
        if (!ThreadContext.get(MDC_CORRELATION_ID)) {
            String inbound = resolveInboundCorrelationId(ec)
            ThreadContext.put(MDC_CORRELATION_ID, inbound ?: UUID.randomUUID().toString())
        }

        String userId = TenantAccessSupport.currentUserId(ec) ?: "anonymous"
        ThreadContext.put(MDC_USER_ID, userId)
        ThreadContext.put(MDC_TENANT, tenantId ?: "anonymous")
    }

    /** Remove all darpan MDC keys. Must be called in <after-request> (finally path). */
    static void clear() {
        ThreadContext.remove(MDC_TENANT)
        ThreadContext.remove(MDC_USER_ID)
        ThreadContext.remove(MDC_CORRELATION_ID)
    }

    // ── internal ─────────────────────────────────────────────────────────────

    private static String resolveInboundCorrelationId(def ec) {
        if (ec == null) return null
        try {
            def web = ec.web
            if (web == null) return null
            // Try X-Request-Id first (GitHub/AWS convention), then X-Correlation-Id
            String id = web.request?.getHeader("X-Request-Id")
            if (!id || id.trim().isEmpty()) id = web.request?.getHeader("X-Correlation-Id")
            return id?.trim() ?: null
        } catch (MissingPropertyException | NullPointerException ignored) {
            return null
        }
    }
}
