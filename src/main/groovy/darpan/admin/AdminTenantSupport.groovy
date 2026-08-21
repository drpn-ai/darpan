package darpan.admin

import darpan.facade.common.TenantAccessSupport
import groovy.transform.CompileDynamic

/** Tenant lifecycle for the admin app. A Darpan tenant IS a moqui.security.UserGroup with
 *  groupTypeEnumId=UgtDarpanCompany plus its darpan.auth.TenantSetting row. */
@CompileDynamic
class AdminTenantSupport {

    static final String TENANT_ID_PATTERN = /^[A-Z][A-Z0-9_]{1,39}$/

    static Map<String, Object> createTenant(def ec, String tenantUserGroupId, String label, String timeZone) {
        if (!AdminAccessSupport.requireSuperAdmin(ec)) return null
        String tenantId = tenantUserGroupId?.trim()
        if (!tenantId || !(tenantId ==~ TENANT_ID_PATTERN)) {
            ec.message.addError("Tenant id must be 2-40 chars: uppercase letters, digits, underscores, starting with a letter.")
            return null
        }
        if (!label?.trim()) { ec.message.addError("Tenant label is required."); return null }
        if (ec.entity.find("moqui.security.UserGroup").condition("userGroupId", tenantId).one() != null) {
            ec.message.addError("Tenant ${tenantId} already exists."); return null
        }
        String createTimeZoneError = timeZone?.trim() ? TenantAccessSupport.validateTimeZone(timeZone) : null
        if (createTimeZoneError) { ec.message.addError(createTimeZoneError); return null }
        ec.service.sync().name("create#moqui.security.UserGroup").parameters([
            userGroupId: tenantId, description: label.trim(),
            groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID,
        ]).call()
        ec.service.sync().name("create#darpan.auth.TenantSetting").parameters([
            companyUserGroupId: tenantId, createdByUserId: ec.user.userId,
            timeZone: timeZone?.trim() ?: "UTC", disabled: "N",
            createdDate: ec.user.nowTimestamp, lastUpdatedDate: ec.user.nowTimestamp,
        ]).call()
        AdminAuditSupport.record(ec, "admin.TenantAdminServices.create#Tenant", "Tenant", tenantId,
                "Created tenant ${tenantId} (${label.trim()}).")
        return [tenantUserGroupId: tenantId]
    }

    static boolean updateTenant(def ec, String tenantUserGroupId, String label, String timeZone) {
        if (!AdminAccessSupport.requireSuperAdmin(ec)) return false
        def group = findTenantGroup(ec, tenantUserGroupId)
        if (group == null) return false
        // Validate BEFORE any write, the same way TenantAccessSupport.saveUserSettings does: a
        // rejected timezone must not leave a half-applied rename behind. An unvalidated zone is
        // also invisible downstream — the browser's Intl silently falls back to the viewer's own
        // zone, so timestamps keep rendering and merely stop agreeing with the schedule label.
        if (timeZone?.trim()) {
            String timeZoneError = TenantAccessSupport.validateTimeZone(timeZone)
            if (timeZoneError) { ec.message.addError(timeZoneError); return false }
        }
        if (label?.trim()) {
            ec.service.sync().name("update#moqui.security.UserGroup").parameters([
                userGroupId: tenantUserGroupId, description: label.trim()]).call()
        }
        if (timeZone?.trim()) {
            ec.service.sync().name("store#darpan.auth.TenantSetting").parameters([
                companyUserGroupId: tenantUserGroupId, timeZone: timeZone.trim(),
                lastUpdatedDate: ec.user.nowTimestamp]).call()
        }
        AdminAuditSupport.record(ec, "admin.TenantAdminServices.update#Tenant", "Tenant", tenantUserGroupId,
                "Updated tenant ${tenantUserGroupId}.")
        return true
    }

    static boolean setTenantDisabled(def ec, String tenantUserGroupId, boolean disabled) {
        if (!AdminAccessSupport.requireSuperAdmin(ec)) return false
        def group = findTenantGroup(ec, tenantUserGroupId)
        if (group == null) return false
        ec.service.sync().name("store#darpan.auth.TenantSetting").parameters([
            companyUserGroupId: tenantUserGroupId, disabled: disabled ? "Y" : "N",
            lastUpdatedDate: ec.user.nowTimestamp]).call()
        String action = disabled ? "deactivate" : "reactivate"
        AdminAuditSupport.record(ec, "admin.TenantAdminServices.${action}#Tenant", "Tenant", tenantUserGroupId,
                "${disabled ? 'Deactivated' : 'Reactivated'} tenant ${tenantUserGroupId}.")
        return true
    }

    static List<Map<String, Object>> listTenants(def ec) {
        if (!AdminAccessSupport.requireSuperAdmin(ec)) return null
        List groups = ec.entity.find("moqui.security.UserGroup")
                .condition("groupTypeEnumId", TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID)
                .list() ?: []
        Map settingsById = (ec.entity.find("darpan.auth.TenantSetting").list() ?: [])
                .collectEntries { [(it.companyUserGroupId): it] }
        List members = ec.entity.find("moqui.security.UserGroupMember").list() ?: []
        return groups.collect { group ->
            String id = group.userGroupId
            [tenantUserGroupId: id,
             label            : group.description ?: id,
             disabled         : settingsById[id]?.disabled == "Y",
             memberCount      : members.count { it.userGroupId == id && it.thruDate == null }]
        }.sort { it.label }
    }

    static Map<String, Object> getTenantDetail(def ec, String tenantUserGroupId) {
        if (!AdminAccessSupport.requireSuperAdmin(ec)) return null
        def group = findTenantGroup(ec, tenantUserGroupId)
        if (group == null) return null
        def setting = ec.entity.find("darpan.auth.TenantSetting")
                .condition("companyUserGroupId", tenantUserGroupId).one()
        List permissionRows = ec.entity.find("darpan.auth.TenantUserPermissionGroupMember")
                .condition("tenantUserGroupId", tenantUserGroupId).list() ?: []
        List members = permissionRows.findAll { it.thruDate == null }.collect { row ->
            def account = ec.entity.find("moqui.security.UserAccount").condition("userId", row.userId).one()
            [userId: row.userId, username: account?.username,
             permissionUserGroupId: row.permissionUserGroupId, fromDate: row.fromDate]
        }
        return [tenantUserGroupId: tenantUserGroupId,
                label            : group.description ?: tenantUserGroupId,
                timeZone         : setting?.timeZone,
                disabled         : setting?.disabled == "Y",
                createdDate      : setting?.createdDate,
                members          : members]
    }

    private static def findTenantGroup(def ec, String tenantUserGroupId) {
        def group = tenantUserGroupId ? ec.entity.find("moqui.security.UserGroup")
                .condition("userGroupId", tenantUserGroupId).one() : null
        if (group == null || group.groupTypeEnumId != TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID) {
            ec.message.addError("Tenant ${tenantUserGroupId ?: '(missing)'} was not found.")
            return null
        }
        return group
    }
}
