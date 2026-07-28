package darpan.admin

import groovy.transform.CompileDynamic

/** User management for the admin app. Delegates credential work to framework
 *  org.moqui.impl.UserServices; both live behind the DARPAN_ADMIN_API fence + super-admin guard. */
@CompileDynamic
class AdminUserSupport {

    static final String DARPAN_USER_GROUP_ID = "DARPAN_USER"

    static Map<String, Object> createUser(def ec, String username, String userFullName,
                                          String emailAddress, String tempPassword) {
        if (!AdminAccessSupport.requireSuperAdmin(ec)) return null
        if (!username?.trim()) { ec.message.addError("Username is required."); return null }
        if (!tempPassword) { ec.message.addError("Temporary password is required."); return null }
        Map created = ec.service.sync().name("org.moqui.impl.UserServices.create#UserAccount").parameters([
            username: username.trim(), userFullName: userFullName?.trim(),
            emailAddress: emailAddress?.trim() ?: null,
            newPassword: tempPassword, newPasswordVerify: tempPassword,
            requirePasswordChange: "Y",
        ]).call()
        if (ec.message.hasError() || !created?.userId) return null
        ec.service.sync().name("create#moqui.security.UserGroupMember").parameters([
            userGroupId: DARPAN_USER_GROUP_ID, userId: created.userId, fromDate: ec.user.nowTimestamp,
        ]).call()
        AdminAuditSupport.record(ec, "admin.UserAdminServices.create#UserAccount", "User",
                (String) created.userId, "Created user ${username.trim()}.")
        return [userId: created.userId]
    }

    static boolean updateUser(def ec, String userId, String userFullName, String emailAddress) {
        if (!AdminAccessSupport.requireSuperAdmin(ec)) return false
        if (findUser(ec, userId) == null) return false
        // Entity-auto update applies EXPLICIT nulls (setIfEmpty), so an omitted field must be left
        // out of the parameter map entirely — putting userFullName: null would wipe the display
        // name on an email-only update, contradicting the "unchanged when omitted" service contract.
        Map<String, Object> params = [userId: userId]
        if (userFullName?.trim()) params.userFullName = userFullName.trim()
        if (emailAddress?.trim()) params.emailAddress = emailAddress.trim()
        if (params.size() == 1) { ec.message.addError("Nothing to update."); return false }
        ec.service.sync().name("org.moqui.impl.UserServices.update#UserAccount").parameters(params).call()
        if (ec.message.hasError()) return false
        AdminAuditSupport.record(ec, "admin.UserAdminServices.update#UserAccount", "User", userId,
                "Updated profile for user ${userId}.")
        return true
    }

    static boolean setUserDisabled(def ec, String userId, boolean disabled) {
        if (!AdminAccessSupport.requireSuperAdmin(ec)) return false
        if (disabled && userId == ec.user.userId) {
            ec.message.addError("You cannot disable your own account."); return false
        }
        if (findUser(ec, userId) == null) return false
        String frameworkService = disabled ? "disable#UserAccount" : "enable#UserAccount"
        ec.service.sync().name("org.moqui.impl.UserServices.${frameworkService}")
                .parameters([userId: userId]).call()
        if (ec.message.hasError()) return false
        if (disabled) revokeLoginKeys(ec, userId)
        AdminAuditSupport.record(ec, "admin.UserAdminServices.${disabled ? 'disable' : 'enable'}#UserAccount",
                "User", userId, "${disabled ? 'Disabled' : 'Enabled'} user ${userId}.")
        return true
    }

    static boolean resetPassword(def ec, String userId, String tempPassword) {
        if (!AdminAccessSupport.requireSuperAdmin(ec)) return false
        if (!tempPassword) { ec.message.addError("Temporary password is required."); return false }
        if (findUser(ec, userId) == null) return false
        ec.service.sync().name("org.moqui.impl.UserServices.update#PasswordInternal").parameters([
            userId: userId, newPassword: tempPassword, newPasswordVerify: tempPassword,
            requirePasswordChange: "Y",
        ]).call()
        if (ec.message.hasError()) return false
        revokeLoginKeys(ec, userId)
        AdminAuditSupport.record(ec, "admin.UserAdminServices.reset#Password", "User", userId,
                "Reset password for user ${userId}; change forced at next login.")
        return true
    }

    static Map<String, Object> listUsers(def ec, String searchText, Integer pageIndex, Integer pageSize) {
        if (!AdminAccessSupport.requireSuperAdmin(ec)) return null
        List accounts = ec.entity.find("moqui.security.UserAccount").list() ?: []
        String needle = searchText?.trim()?.toLowerCase()
        List filtered = needle ? accounts.findAll {
            ((it.username ?: "") as String).toLowerCase().contains(needle) ||
            ((it.emailAddress ?: "") as String).toLowerCase().contains(needle) ||
            ((it.userFullName ?: "") as String).toLowerCase().contains(needle)
        } : accounts
        int size = pageSize ?: 25
        int index = pageIndex ?: 0
        List page = filtered.sort { it.username }.drop(index * size).take(size)
        return [users: page.collect {
            [userId: it.userId, username: it.username, userFullName: it.userFullName,
             emailAddress: it.emailAddress, disabled: it.disabled == "Y"]
        }, totalCount: filtered.size()]
    }

    static Map<String, Object> getUserDetail(def ec, String userId) {
        if (!AdminAccessSupport.requireSuperAdmin(ec)) return null
        def account = findUser(ec, userId)
        if (account == null) return null
        List memberships = (ec.entity.find("darpan.auth.TenantUserPermissionGroupMember")
                .condition("userId", userId).list() ?: [])
                .findAll { it.thruDate == null }
                .collect { [tenantUserGroupId: it.tenantUserGroupId,
                            permissionUserGroupId: it.permissionUserGroupId, fromDate: it.fromDate] }
        return [userId: account.userId, username: account.username,
                userFullName: account.userFullName, emailAddress: account.emailAddress,
                disabled: account.disabled == "Y",
                requirePasswordChange: account.requirePasswordChange == "Y",
                memberships: memberships]
    }

    private static def findUser(def ec, String userId) {
        def account = userId ? ec.entity.find("moqui.security.UserAccount")
                .condition("userId", userId).one() : null
        if (account == null) ec.message.addError("User ${userId ?: '(missing)'} was not found.")
        return account
    }

    /** UserLoginKey's PK is loginKey alone (not composite with userId), so the implicit delete
     *  CrUD service needs each row's key individually: find live keys for the user, delete each. */
    private static void revokeLoginKeys(def ec, String userId) {
        (ec.entity.find("moqui.security.UserLoginKey").condition("userId", userId).list() ?: []).each { row ->
            ec.service.sync().name("delete#moqui.security.UserLoginKey")
                    .parameters([loginKey: row.loginKey]).call()
        }
    }
}
