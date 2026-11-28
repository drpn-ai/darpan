package darpan.admin

import darpan.common.MessageScope
import darpan.facade.auth.PasswordResetSupport
import darpan.reconciliation.notification.TenantNotificationSupport
import groovy.transform.CompileDynamic
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Tenant membership for the admin app: the role allowlist shared by add#TenantMember and
 *  update#TenantMemberRole (service/admin/MembershipAdminServices.xml), and the tenant welcome email (DAR-BE-290).
 *  INVARIANT: UserGroupMember and TenantUserPermissionGroupMember are written together or not at
 *  all — the tenant app derives availableTenants from the former and permissions from the latter,
 *  and they silently diverge if either is written alone. Those XML services are the ONLY writers. */
@CompileDynamic
class AdminMembershipSupport {
    private static final Logger logger = LoggerFactory.getLogger(AdminMembershipSupport)

    static final List<String> ASSIGNABLE_TENANT_ROLES = [
        "DARPAN_TENANT_ADMIN", "DARPAN_TENANT_USER", "DARPAN_COMPANY_EDITOR", "DARPAN_COMPANY_VIEW_ONLY",
    ].asImmutable()

    static final String TEMPLATE_TENANT_WELCOME = "DARPAN_TENANT_WELCOME"

    /** How the welcome email names each role: "You now have access to Malbon on Darpan, as a User." */
    static final Map<String, String> ROLE_PHRASES = [
        DARPAN_TENANT_ADMIN     : "as an Admin",
        DARPAN_TENANT_USER      : "as a User",
        DARPAN_COMPANY_EDITOR   : "as an Editor",
        DARPAN_COMPANY_VIEW_ONLY: "with view-only access",
    ].asImmutable()

    /** Sends one welcome email and says whether the mail server accepted it (send#EmailTemplate returns a messageId
     *  only then, and logs any failure). Its own transaction, so SMTP time never sits in the grant's. keepCopy false
     *  sends without the EmailMessage row, which would hold the rendered body and so the password. Tests replace it. */
    private static final Closure<Boolean> DEFAULT_SEND_WELCOME_EMAIL = { def ec, String toAddress, Map bodyParameters, boolean keepCopy ->
        Map result = ec.service.sync().name("org.moqui.impl.EmailServices.send#EmailTemplate").requireNewTransaction(true)
                .parameters([emailTemplateId: TEMPLATE_TENANT_WELCOME, toAddresses: toAddress, bodyParameters: bodyParameters,
                             createEmailMessage: keepCopy])
                .call()
        return result?.messageId as boolean
    }

    static Closure<Boolean> sendWelcomeEmail = DEFAULT_SEND_WELCOME_EMAIL

    /** Test-only: puts the welcome-email seam back to the real sender. Idempotent. */
    static void resetForTests() {
        sendWelcomeEmail = DEFAULT_SEND_WELCOME_EMAIL
    }

    /** Sends the welcome email to a member of a tenant: which tenant, their role, their username, where to sign in,
     *  and (DAR-BE-325, Aditi 2026-11-20: "include the temporary password in the email always") the temporary password
     *  an admin set, while TempPasswordKeeper still holds a usable one. The caller has already checked the membership
     *  and that the account is enabled and has an email address. Call it after every write: it waits for the mail
     *  server, and a failed send never fails the caller (its errors are dropped, sent comes back false).
     *  Returns [sent: Boolean, withPassword: Boolean]. */
    static Map<String, Boolean> sendWelcome(def ec, def account, def tenantGroup, String permissionUserGroupId) {
        String tempPassword = TempPasswordKeeper.forWelcome(ec, account)
        Map params = [
            firstName   : PasswordResetSupport.firstName(account),
            username    : (String) account.username,
            tenantLabel : (String) (tenantGroup.description ?: tenantGroup.userGroupId),
            // OC186: the tenant the email serves, so its smile line's model spend goes on that tenant's ledger.
            tenantUserGroupId: (String) tenantGroup.userGroupId,
            rolePhrase  : ROLE_PHRASES[permissionUserGroupId] ?: "",
            // Null when the instance has no app address configured: the email then says "sign in to Darpan".
            appBaseUrl  : TenantNotificationSupport.resolveAppBaseUrl(ec),
            // Null when there is none to send: the email then has no password lines.
            tempPassword: tempPassword,
        ]
        // DAR-OPS-029 S99-024: the send's errors are logged and dropped, never left in the admin's reply (clearErrors turned
        // them into danger messages there).
        boolean sent = MessageScope.isolated(ec) {
            boolean ok = false
            try {
                ok = sendWelcomeEmail(ec, ((String) account.emailAddress).trim(), params, tempPassword == null)
            } catch (Throwable t) {
                logger.warn("Welcome email for {} to user {} failed: {}", params.tenantLabel, account.userId, t.toString())
            }
            if (ec.message.hasError()) logger.warn("Welcome email for user {} not sent: {}", account.userId, ec.message.errorsString)
            return ok
        }
        return [sent: sent, withPassword: sent && tempPassword != null]
    }
}
