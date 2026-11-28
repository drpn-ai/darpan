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
    static final String MDC_RUN_ID         = "darpan.runId"
    static final String MDC_STAGE          = "darpan.stage"
    static final String MDC_SAVED_RUN_ID   = "darpan.savedRunId"

    /** An inbound X-Request-Id / X-Correlation-Id worth keeping: 1-128 characters a tracing id uses, no space or control. */
    private static final java.util.regex.Pattern INBOUND_ID = ~/[A-Za-z0-9._:=+\/-]{1,128}/

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

    /**
     * Remove all darpan MDC keys, including run-scoped ones (see clearRun()). Must be called in
     * <after-request> (finally path) so a request that stamped run keys but never reached a
     * terminal RunObservability call (e.g. beginRun's write failed and runId came back null,
     * skipping every later obsRunId-gated call) can't leak darpan.savedRunId/darpan.stage onto
     * the pooled thread for the next request.
     */
    static void clear() {
        ThreadContext.remove(MDC_TENANT)
        ThreadContext.remove(MDC_USER_ID)
        ThreadContext.remove(MDC_CORRELATION_ID)
        clearRun()
    }

    /** Stamp run-scoped MDC keys for the duration of a reconciliation run. Cleared via clearRun(). */
    static void stampRun(String runId, String savedRunId) {
        if (runId != null) ThreadContext.put(MDC_RUN_ID, runId)
        if (savedRunId != null) ThreadContext.put(MDC_SAVED_RUN_ID, savedRunId)
    }

    /** Stamp the current stage; overwritten as the run advances. */
    static void stampStage(String stageCode) {
        if (stageCode != null) ThreadContext.put(MDC_STAGE, stageCode)
    }

    /** Remove run-scoped keys. MUST be called in a finally on every thread that stamped them. */
    static void clearRun() {
        ThreadContext.remove(MDC_RUN_ID)
        ThreadContext.remove(MDC_STAGE)
        ThreadContext.remove(MDC_SAVED_RUN_ID)
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
            String trimmed = id?.trim()
            // The caller's text goes into every log line of the request: only a plain, short id is kept, so a newline
            // cannot forge a log line and a huge value cannot ride on each one. Anything else gets a fresh UUID.
            return trimmed && trimmed ==~ INBOUND_ID ? trimmed : null
        } catch (MissingPropertyException | NullPointerException ignored) {
            return null
        }
    }
}
