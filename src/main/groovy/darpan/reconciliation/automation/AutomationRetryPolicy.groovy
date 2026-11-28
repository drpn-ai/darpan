package darpan.reconciliation.automation

import darpan.config.DarpanSettings

import java.sql.Timestamp

import static darpan.common.ValueSupport.normalizeInt
import static darpan.common.ValueSupport.readField
import static darpan.reconciliation.automation.AutomationRuntimeSupport.safeMetadataJson
import static darpan.reconciliation.automation.AutomationRuntimeSupport.sanitizeErrorDetail
import static darpan.reconciliation.automation.AutomationRuntimeSupport.sanitizeErrorMessage
import static darpan.reconciliation.automation.AutomationRuntimeSupport.truncate

/**
 * DAR-OPS-029 S06 (split out of AutomationExecutionSupport). Transient-failure retry policy (DAR-300). A transient failure
 * requeues the execution to PENDING with a backoff nextRetryDate; the 5-min scanner re-drives due rows, incrementing retryCount,
 * and dead-letters once retryCount reaches maxRetryCount. Permanent (config/fence) failures skip retry and go straight to FAILED.
 * The limits are settings (admin app Settings), read live.
 */
class AutomationRetryPolicy {

    static int defaultMaxRetryCount() {
        return DarpanSettings.intValue("darpan.reconciliation.automation.maxRetryCount")
    }

    static long retryBackoffBaseMinutes() {
        return DarpanSettings.intValue("darpan.reconciliation.automation.retryBackoffBaseMinutes")
    }

    static long retryBackoffCapMinutes() {
        return Math.max(retryBackoffBaseMinutes(), (long) DarpanSettings.intValue("darpan.reconciliation.automation.retryBackoffCapMinutes"))
    }

    // Lower-cased substrings that mark a failure as permanent (config/fence/validation) — a retry cannot
    // fix these, so they go straight to FAILED instead of being requeued.
    static final List<String> PERMANENT_FAILURE_MARKERS = [
            "not in the allowed service list",
            "does not match an allowed extractor service name pattern",
            "no source connector is registered",
            "requires sourceconfigid",
            "is not valid for",
            "cannot read orders",
            "is inactive",
            "was not found",
            "not available in your active tenant",
            "is not configured",
            // DAR-BE-116: Spark could not analyse the compare (the run's own key/rule names a column the
            // extract does not have). The same file fails the same way on every retry.
            "[unresolved_column",
            "[unresolved_field",
            "[field_not_found",
            "[datatype_mismatch",
            // DAR-BE-116: the source answered the same key twice (gorjana R15, NetSuite lines); the data is the data.
            "produced duplicate primary",
            // DAR-BE-243: Shopify's ACCESS_DENIED ("Access denied for <field> field. Required access: ..."),
            // a missing scope or a token Shopify will not let run bulk reads. New Era RS_100357 retried
            // 95 minutes on it (2026-11-12) before dead-lettering.
            "required access:",
    ]

    protected static int resolveMaxRetryCount(def execution) {
        Integer configured = normalizeInt(readField(execution, "maxRetryCount"))
        return configured != null ? configured : defaultMaxRetryCount()
    }

    /**
     * Failure-transition fields with transient-vs-permanent classification (DAR-300). Permanent
     * (config/fence) failures go terminal (FAILED). Transient failures requeue to PENDING with a backoff
     * nextRetryDate for the scanner to re-drive; retryCount advances at pickup time.
     */
    protected static Map<String, Object> buildFailureFields(def ec, def execution, Throwable t,
            Timestamp failedAt, Map<String, Object> window) {
        boolean permanent = isPermanentFailure(t)
        int maxRetry = resolveMaxRetryCount(execution)
        boolean willRetry = !permanent && maxRetry > 0

        Map<String, Object> fields = [
                errorMessage    : truncate(sanitizeErrorMessage(t), 3900),
                errorDetail     : truncate(sanitizeErrorDetail(t), 12000),
                safeMetadataJson: safeMetadataJson([
                        mode            : "API_DATE_RANGE",
                        childWindowStart: window.childWindowStartDate,
                        childWindowEnd  : window.childWindowEndDate,
                        errorMessage    : sanitizeErrorMessage(t),
                        failureClass    : permanent ? "permanent" : "transient",
                        willRetry       : willRetry,
                ]),
                lastUpdatedDate : failedAt,
        ]
        if (willRetry) {
            int currentRetry = normalizeInt(readField(execution, "retryCount")) ?: 0
            fields.statusEnumId = AutomationExecutionSupport.STATUS_PENDING
            fields.maxRetryCount = maxRetry
            fields.nextRetryDate = new Timestamp(failedAt.time + retryBackoffMillis(currentRetry + 1))
            // completedDate intentionally left unset — the window is queued for retry, not finished.
        } else {
            fields.statusEnumId = AutomationExecutionSupport.STATUS_FAILED
            fields.completedDate = failedAt
            // A prior transient nextRetryDate (if any) is left as-is: FAILED is excluded from the
            // PENDING-only retry pickup, so it can't be re-driven. updateAutomationExecution ignores
            // nulls, so clearing it here would be a no-op anyway.
        }
        return fields
    }

    /**
     * Transient (retry) vs permanent (terminal) failure classification. Permanent = config/fence errors a
     * retry cannot fix (bad input, allow-list/naming-guard/registry/validation); everything else defaults
     * to transient so genuine flakiness (timeouts, 5xx, IO) is retried.
     */
    protected static boolean isPermanentFailure(Throwable t) {
        if (t == null) return false
        if (t instanceof IllegalArgumentException) return true
        StringBuilder sb = new StringBuilder()
        Throwable cur = t
        int depth = 0
        while (cur != null && depth < 8) {
            if (cur.message) sb.append(cur.message).append(' ')
            cur = cur.cause
            depth++
        }
        String msg = sb.toString().toLowerCase()
        return msg && PERMANENT_FAILURE_MARKERS.any { msg.contains(it) }
    }

    protected static long retryBackoffMillis(int attempt) {
        int a = Math.max(1, attempt)
        int shift = Math.min(a - 1, 20)
        long minutes = Math.min(retryBackoffBaseMinutes() * (1L << shift), retryBackoffCapMinutes())
        return minutes * 60_000L
    }
}
