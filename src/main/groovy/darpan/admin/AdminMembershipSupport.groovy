package darpan.admin

import darpan.facade.common.TenantAccessSupport
import groovy.transform.CompileDynamic

/** Tenant membership for the admin app.
 *  INVARIANT: UserGroupMember and TenantUserPermissionGroupMember are written together or not at
 *  all — the tenant app derives availableTenants from the former and permissions from the latter,
 *  and they silently diverge if either is written alone. These methods are the ONLY writers. */
@CompileDynamic
class AdminMembershipSupport {

    static final List<String> ASSIGNABLE_TENANT_ROLES = [
        "DARPAN_TENANT_ADMIN", "DARPAN_TENANT_USER", "DARPAN_COMPANY_EDITOR", "DARPAN_COMPANY_VIEW_ONLY",
    ].asImmutable()

    static boolean addTenantMember(def ec, String userId, String tenantUserGroupId, String permissionUserGroupId) {
        if (!AdminAccessSupport.requireSuperAdmin(ec)) return false
        if (!(permissionUserGroupId in ASSIGNABLE_TENANT_ROLES)) {
            ec.message.addError("${permissionUserGroupId ?: '(missing)'} is not an assignable tenant role.")
            return false
        }
        if (!validateActiveTenant(ec, tenantUserGroupId)) return false
        if (!validateUser(ec, userId)) return false
        if (findActiveMembershipRows(ec, userId, tenantUserGroupId)) {
            ec.message.addError("${userId} is already a member of ${tenantUserGroupId}.")
            return false
        }
        def fromDate = ec.user.nowTimestamp
        ec.service.sync().name("create#moqui.security.UserGroupMember").parameters([
            userGroupId: tenantUserGroupId, userId: userId, fromDate: fromDate]).call()
        ec.service.sync().name("create#darpan.auth.TenantUserPermissionGroupMember").parameters([
            tenantUserGroupId: tenantUserGroupId, userId: userId,
            permissionUserGroupId: permissionUserGroupId, fromDate: fromDate]).call()
        AdminAuditSupport.record(ec, "admin.MembershipAdminServices.add#TenantMember", "Membership",
                "${tenantUserGroupId}:${userId}", "Added ${userId} to ${tenantUserGroupId} as ${permissionUserGroupId}.")
        return true
    }

    static boolean updateTenantMemberRole(def ec, String userId, String tenantUserGroupId, String permissionUserGroupId) {
        if (!AdminAccessSupport.requireSuperAdmin(ec)) return false
        if (!(permissionUserGroupId in ASSIGNABLE_TENANT_ROLES)) {
            ec.message.addError("${permissionUserGroupId ?: '(missing)'} is not an assignable tenant role.")
            return false
        }
        List activeRows = findActiveMembershipRows(ec, userId, tenantUserGroupId)
        if (!activeRows) { ec.message.addError("${userId} is not a member of ${tenantUserGroupId}."); return false }
        def thruDate = ec.user.nowTimestamp
        activeRows.each { row ->
            ec.service.sync().name("update#darpan.auth.TenantUserPermissionGroupMember").parameters([
                tenantUserGroupId: tenantUserGroupId, userId: userId,
                permissionUserGroupId: row.permissionUserGroupId, fromDate: row.fromDate,
                thruDate: thruDate]).call()
        }
        ec.service.sync().name("create#darpan.auth.TenantUserPermissionGroupMember").parameters([
            tenantUserGroupId: tenantUserGroupId, userId: userId,
            permissionUserGroupId: permissionUserGroupId, fromDate: thruDate]).call()
        AdminAuditSupport.record(ec, "admin.MembershipAdminServices.update#TenantMemberRole", "Membership",
                "${tenantUserGroupId}:${userId}", "Changed ${userId} role in ${tenantUserGroupId} to ${permissionUserGroupId}.")
        return true
    }

    static boolean removeTenantMember(def ec, String userId, String tenantUserGroupId) {
        if (!AdminAccessSupport.requireSuperAdmin(ec)) return false
        // Deliberately NOT validateActiveTenant: removal from a DEACTIVATED tenant must stay legal.
        // But the group must still exist and be a real Darpan tenant, or the raw UserGroupMember
        // lookup below would match ANY group (e.g. DARPAN_SUPER_ADMIN, ADMIN, DARPAN_USER) and let
        // this thruDate a caller's own super-admin membership or a user's base facade-access group.
        if (!validateTenantExists(ec, tenantUserGroupId)) return false
        List permissionRows = findActiveMembershipRows(ec, userId, tenantUserGroupId)
        List groupRows = (ec.entity.find("moqui.security.UserGroupMember")
                .condition("userGroupId", tenantUserGroupId).condition("userId", userId)
                .list() ?: []).findAll { it.thruDate == null }
        if (!permissionRows && !groupRows) {
            ec.message.addError("${userId} is not a member of ${tenantUserGroupId}.")
            return false
        }
        def thruDate = ec.user.nowTimestamp
        groupRows.each { row ->
            ec.service.sync().name("update#moqui.security.UserGroupMember").parameters([
                userGroupId: tenantUserGroupId, userId: userId, fromDate: row.fromDate,
                thruDate: thruDate]).call()
        }
        permissionRows.each { row ->
            ec.service.sync().name("update#darpan.auth.TenantUserPermissionGroupMember").parameters([
                tenantUserGroupId: tenantUserGroupId, userId: userId,
                permissionUserGroupId: row.permissionUserGroupId, fromDate: row.fromDate,
                thruDate: thruDate]).call()
        }
        clearStaleActiveTenantPreference(ec, userId, tenantUserGroupId)
        AdminAuditSupport.record(ec, "admin.MembershipAdminServices.remove#TenantMember", "Membership",
                "${tenantUserGroupId}:${userId}", "Removed ${userId} from ${tenantUserGroupId}.")
        return true
    }

    private static List findActiveMembershipRows(def ec, String userId, String tenantUserGroupId) {
        return (ec.entity.find("darpan.auth.TenantUserPermissionGroupMember")
                .condition("tenantUserGroupId", tenantUserGroupId).condition("userId", userId)
                .list() ?: []).findAll { it.thruDate == null }
    }

    private static boolean validateActiveTenant(def ec, String tenantUserGroupId) {
        if (!validateTenantExists(ec, tenantUserGroupId)) return false
        def setting = ec.entity.find("darpan.auth.TenantSetting")
                .condition("companyUserGroupId", tenantUserGroupId).one()
        if (setting?.disabled == "Y") {
            ec.message.addError("Tenant ${tenantUserGroupId} is deactivated.")
            return false
        }
        return true
    }

    /** Existence-and-type check only (no active/disabled opinion) — shared by callers that must
     *  reject non-tenant groups but still allow acting on a deactivated tenant. */
    private static boolean validateTenantExists(def ec, String tenantUserGroupId) {
        def group = tenantUserGroupId ? ec.entity.find("moqui.security.UserGroup")
                .condition("userGroupId", tenantUserGroupId).one() : null
        if (group == null || group.groupTypeEnumId != TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID) {
            ec.message.addError("Tenant ${tenantUserGroupId ?: '(missing)'} was not found.")
            return false
        }
        return true
    }

    private static boolean validateUser(def ec, String userId) {
        def account = userId ? ec.entity.find("moqui.security.UserAccount")
                .condition("userId", userId).one() : null
        if (account == null) { ec.message.addError("User ${userId ?: '(missing)'} was not found."); return false }
        return true
    }

    /** A removed member whose active-tenant preference points at this tenant would resolve to a
     *  dead tenant at next session sync; delete the stale preference so sync re-resolves cleanly. */
    private static void clearStaleActiveTenantPreference(def ec, String userId, String tenantUserGroupId) {
        def preference = ec.entity.find("moqui.security.UserPreference")
                .condition("userId", userId)
                .condition("preferenceKey", TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY)
                .one()
        if (preference != null && preference.preferenceValue == tenantUserGroupId) {
            ec.service.sync().name("delete#moqui.security.UserPreference").parameters([
                userId: userId, preferenceKey: TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY]).call()
        }
    }
}
