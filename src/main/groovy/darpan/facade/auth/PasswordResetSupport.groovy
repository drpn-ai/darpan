package darpan.facade.auth

import darpan.facade.common.TenantScopedFinder
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicBoolean

/** DAR-BE-028 — forgot password by emailed code, and the recovery email that makes it reachable.
 *
 *  Spec: docs/superpowers/specs/2026-10-21-forgot-password-design.md (wrapper repo). */
class PasswordResetSupport {
    private static final Logger logger = LoggerFactory.getLogger(PasswordResetSupport.class)

    static final String TEMPLATE_RESET_CODE = "DARPAN_PASSWORD_RESET_CODE"
    static final String TEMPLATE_PASSWORD_CHANGED = "DARPAN_PASSWORD_CHANGED"
    static final String TEMPLATE_RECOVERY_SET = "DARPAN_RECOVERY_EMAIL_SET"
    static final String TEMPLATE_RECOVERY_CONFLICT = "DARPAN_RECOVERY_EMAIL_CONFLICT"

    static final String MAIL_FROM_ENV = "DARPAN_MAIL_FROM"
    static final String EMAIL_SERVER_ID = "SYSTEM"

    private static final AtomicBoolean notConfiguredWarned = new AtomicBoolean(false)

    /** Every mail this feature sends goes through here, so tests can record sends instead of making them.
     *  Async, like the framework's own reset#Password: SMTP time never shows in the reply, and an SMTP
     *  failure lands in the server log rather than in the caller's answer. */
    static Closure sendEmail = { def ec, String emailTemplateId, String toAddress, Map bodyParameters ->
        ec.service.async().name("org.moqui.impl.EmailServices.send#EmailTemplate")
                .parameters([emailTemplateId: emailTemplateId, toAddresses: toAddress, bodyParameters: bodyParameters])
                .call()
    }

    /** True when a reset email could actually go out: a SYSTEM EmailServer with an SMTP host, and a from
     *  address. Darpan has no startup hook, so the first caller that finds it unconfigured logs the one WARN
     *  that tells a deploy "codes will never arrive". */
    static boolean isEmailConfigured(def ec) {
        List<String> missing = []
        def server = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.basic.email.EmailServer",
                "framework email configuration, not tenant data")
                .condition("emailServerId", EMAIL_SERVER_ID).useCache(false).one()
        if (!server?.smtpHost) missing << "no EmailServer ${EMAIL_SERVER_ID} with an smtpHost".toString()
        if (!System.getenv(MAIL_FROM_ENV)) missing << "${MAIL_FROM_ENV} is not set".toString()
        if (missing && notConfiguredWarned.compareAndSet(false, true)) {
            logger.warn("Password reset email is not configured: {}", missing.join("; "))
        }
        return missing.isEmpty()
    }

    /** First letter of the local part and the whole domain: enough for a user to recognise their own
     *  address, not enough to read someone else's off a shared screen. */
    static String maskEmail(String email) {
        if (email == null) return null
        int at = email.indexOf('@')
        if (at < 1) return "•••"
        return email.substring(0, 1) + "•••" + email.substring(at)
    }
}
