package darpan.admin

import groovy.transform.CompileDynamic

/** Tenant membership for the admin app: the role allowlist shared by add#TenantMember and
 *  update#TenantMemberRole (service/admin/MembershipAdminServices.xml).
 *  INVARIANT: UserGroupMember and TenantUserPermissionGroupMember are written together or not at
 *  all — the tenant app derives availableTenants from the former and permissions from the latter,
 *  and they silently diverge if either is written alone. Those XML services are the ONLY writers. */
@CompileDynamic
class AdminMembershipSupport {

    static final List<String> ASSIGNABLE_TENANT_ROLES = [
        "DARPAN_TENANT_ADMIN", "DARPAN_TENANT_USER", "DARPAN_COMPANY_EDITOR", "DARPAN_COMPANY_VIEW_ONLY",
    ].asImmutable()
}
