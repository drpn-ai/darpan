package darpan.audit

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * DAR-BE-361. Runs one acknowledgement write (acknowledge, change, reopen) so an unexpected failure, a database or service
 * error, reaches the person as one plain line while its detail goes to the log at ERROR. The facade checks its own rules
 * (blank note, too long, not open, not found) before calling this, so those messages reach the person unchanged. The log
 * line names the write, the tenant and the finding, and the failure with the note, quoted values and email addresses
 * masked.
 */
class AcknowledgementWrites {
    protected final static Logger logger = LoggerFactory.getLogger(AcknowledgementWrites.class)

    static final String PLAIN = "Could not save the acknowledgement. Darpan has been told."
    private static final int DETAIL_MAX = 600

    /** Test seam: called with (ec, what) after the write succeeds, inside the guard, so a test can throw or add an error. */
    static Closure afterWrite = null

    /** Test-only: restores the seam. Idempotent. */
    static void resetForTests() {
        afterWrite = null
    }

    /**
     * The write service's result, or null after a failure: its errors are dropped (logged, masked) and PLAIN is the one
     * error left, so the facade's transaction rolls back and the person reads one plain line. Errors the caller already
     * held stay.
     */
    static Map run(def ec, String what, String serviceName, Map params, String tenantId, String findingId, String note) {
        Map result = null
        Throwable thrown = null
        List<String> errors = []
        ec.message.pushErrors()
        try {
            try {
                result = ec.service.sync().name(serviceName).parameters(params).call() as Map
                if (!ec.message.hasError() && afterWrite != null) afterWrite.call(ec, what)
            } catch (Throwable t) {
                thrown = t
            }
            errors.addAll((ec.message.errors ?: []).collect { it as String })
            errors.addAll((ec.message.validationErrors ?: []).collect { it.toString() })
        } finally {
            ec.message.clearErrors()
            ec.message.popErrors()
        }
        if (thrown == null && errors.isEmpty()) return result ?: [:]

        String detail = masked(([thrown ? "${thrown.getClass().name}: ${thrown.message}" : null] + errors).findAll().join(" | "), note)
        logger.error("Acknowledgement {} for {} {} failed: {}", what, tenantId, findingId, detail)
        ec.message.addError(PLAIN)
        return null
    }

    /** The failure text without the note, quoted values or email addresses, cut to a log line. */
    static String masked(String text, String note) {
        if (!text) return ""
        String out = text
        if (note) out = out.replace(note, "<note>")
        out = out.replaceAll(/'[^']*'/, "'…'").replaceAll(/"[^"]*"/, '"…"')
                .replaceAll(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/, "<email>")
        return out.length() > DETAIL_MAX ? out.substring(0, DETAIL_MAX) + "…" : out
    }
}
