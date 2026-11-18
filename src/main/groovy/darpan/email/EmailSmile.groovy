package darpan.email

import darpan.reconciliation.automation.AutomationExecutionSupport
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * DAR-BE-298 (Aditi 2026-11-18: "you are welcome to use Haiku for these email on prod", live at send time). An email's
 * one playful line, written fresh by Claude Haiku when darpan-agent is installed and on, else the template's own line.
 *
 * Core never imports darpan-agent: it asks for {@link #SERVICE} by name when that service is defined, the way the audit
 * run update asks for its words. Only the line's key and the template's own line go to the model, never a name, an
 * address, a passkey label or anything else about the reader. Whatever comes back is checked here as well ({@link #ok}):
 * one short line, no digits, no address, no instruction to click, reply or verify. Anything else, a slow model, an
 * error or no agent at all gives the template's line, so an email never waits on or breaks for the model.
 *
 * An email renders twice (html, then the text part) in one send: the line is kept in the context's shared map for the
 * send, so both parts carry the same one.
 */
class EmailSmile {
    private static final Logger logger = LoggerFactory.getLogger(EmailSmile.class)

    static final String SERVICE = "agent.AgentWriterServices.write#EmailSmile"
    static final int MAX_CHARS = 80
    private static final String MEMO = "darpan.email.smile."
    /** Words that would turn a smile into an instruction (the phishing shape), or a line into a claim about the account. */
    private static final List<String> REFUSED = ["http", "www", ".com", ".ai", "@", "click", "link", "reply", "call us",
            "verify", "confirm", "download", "password is", "code is", "urgent", "immediately", "account will"]

    /** The line for this send: the model's when it passes {@link #ok}, else fallback. Never throws. */
    static String line(def ec, String key, String fallback) {
        Map<String, Object> shared = ec.context.getSharedMap()
        String memo = MEMO + key
        if (shared.containsKey(memo)) return shared.get(memo) as String
        String out = fallback
        try {
            if (ec.service.isServiceDefined(SERVICE)) {
                Map result = AutomationExecutionSupport.dispatchInternalService(ec, SERVICE, [key: key, fallback: fallback])
                if (ec.message.hasError()) {
                    logger.warn("Email smile {}: the writer refused: {}", key, ec.message.errorsString)
                    ec.message.clearErrors()
                } else if (ok(result?.line as String)) {
                    out = (result.line as String).trim()
                }
            }
        } catch (Throwable t) {
            ec.message.clearErrors()
            logger.warn("Email smile {}: the writer failed ({}); the template's line goes", key, t.class.simpleName)
        }
        shared.put(memo, out)
        return out
    }

    /** One short line of plain words: no digits, no address, no instruction, no markup, no exclamation. Pure. */
    static boolean ok(String line) {
        String text = line?.trim()
        if (!text || text.length() > MAX_CHARS || text.contains("\n")) return false
        if (text =~ /[0-9<>{}\[\]!*_#`|\\]/) return false
        String lower = text.toLowerCase(Locale.ROOT)
        return !REFUSED.any { lower.contains(it) }
    }
}
