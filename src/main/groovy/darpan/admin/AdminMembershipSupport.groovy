package darpan.admin

import darpan.facade.auth.PasswordResetSupport
import darpan.reconciliation.notification.TenantNotificationSupport
import groovy.transform.CompileDynamic

/** Tenant membership for the admin app: the role allowlist shared by add#TenantMember and
 *  update#TenantMemberRole (service/admin/MembershipAdminServices.xml), and the tenant welcome email (DAR-BE-290).
 *  INVARIANT: UserGroupMember and TenantUserPermissionGroupMember are written together or not at
 *  all — the tenant app derives availableTenants from the former and permissions from the latter,
 *  and they silently diverge if either is written alone. Those XML services are the ONLY writers. */
@CompileDynamic
class AdminMembershipSupport {

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

    /** Queues the welcome email for a member of a tenant. The caller has already checked the membership and that
     *  the account is enabled and has an email address. No password ever goes in it: a new user's temporary
     *  password reaches the admin, not the inbox. Sent through PasswordResetSupport.sendEmail (async), so a mail
     *  failure never fails or holds up the grant, and tests record the send instead of making it. */
    static void queueWelcome(def ec, def account, def tenantGroup, String permissionUserGroupId) {
        PasswordResetSupport.sendEmail(ec, TEMPLATE_TENANT_WELCOME, (String) account.emailAddress, [
            firstName  : PasswordResetSupport.firstName(account),
            username   : (String) account.username,
            tenantLabel: (String) (tenantGroup.description ?: tenantGroup.userGroupId),
            rolePhrase : ROLE_PHRASES[permissionUserGroupId] ?: "",
            // Null when the instance has no app address configured: the email then says "sign in to Darpan".
            appBaseUrl : TenantNotificationSupport.resolveAppBaseUrl(ec),
        ])
    }
}
