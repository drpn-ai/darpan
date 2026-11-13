package darpan.admin

import darpan.facade.common.TenantAccessSupport
import groovy.transform.CompileDynamic
import org.moqui.entity.EntityCondition

/** User search for the admin app, behind the DARPAN_ADMIN_API fence + super-admin guard.
 *  listUsers stays Groovy: its case-insensitive substring match, in-memory username sort and
 *  page slicing have no exact XML/SQL equivalent (LIKE wildcards, DB collation). The user
 *  mutations and detail read are XML actions in service/admin/UserAdminServices.xml, which
 *  delegate all credential work to framework org.moqui.impl.UserServices. */
@CompileDynamic
class AdminUserSupport {

    static final String DARPAN_USER_GROUP_ID = "DARPAN_USER"

    /** DAR-ADMIN-011. Darpan-wide roles shown on a user: Moqui's ADMIN (read-only, never set here), super admin, Darpan admin. */
    static final List<String> DARPAN_ROLE_GROUP_IDS = [TenantAccessSupport.ADMIN_USER_GROUP_ID,
            TenantAccessSupport.DARPAN_SUPER_ADMIN_GROUP_ID, TenantAccessSupport.DARPAN_ADMIN_GROUP_ID].asImmutable()
    /** The roles add#DarpanAdmin / remove#DarpanAdmin may set or end. */
    static final List<String> SETTABLE_DARPAN_ROLES = [TenantAccessSupport.DARPAN_ADMIN_GROUP_ID,
            TenantAccessSupport.DARPAN_SUPER_ADMIN_GROUP_ID].asImmutable()

    static Map<String, Object> listUsers(def ec, String searchText, Integer pageIndex, Integer pageSize, Boolean darpanAdminsOnly = false) {
        if (!AdminAccessSupport.requireSuperAdmin(ec)) return null
        List accounts = ec.entity.find("moqui.security.UserAccount").list() ?: []
        Map<String, List<String>> rolesByUser = darpanRolesByUser(ec)
        String needle = searchText?.trim()?.toLowerCase()
        List filtered = accounts.findAll {
            (!darpanAdminsOnly || rolesByUser[it.userId as String]) && (!needle ||
            ((it.username ?: "") as String).toLowerCase().contains(needle) ||
            ((it.emailAddress ?: "") as String).toLowerCase().contains(needle) ||
            ((it.userFullName ?: "") as String).toLowerCase().contains(needle))
        }
        int size = pageSize ?: 25
        int index = pageIndex ?: 0
        List page = filtered.sort { it.username }.drop(index * size).take(size)
        return [users: page.collect {
            [userId: it.userId, username: it.username, userFullName: it.userFullName,
             emailAddress: it.emailAddress, disabled: it.disabled == "Y",
             darpanRoles: rolesByUser[it.userId as String] ?: []]
        }, totalCount: filtered.size()]
    }

    /** userId -> the Darpan-wide roles in force now, in DARPAN_ROLE_GROUP_IDS order. One read for the whole board. */
    private static Map<String, List<String>> darpanRolesByUser(def ec) {
        Map<String, Set<String>> found = [:].withDefault { new HashSet<String>() }
        ec.entity.find("moqui.security.UserGroupMember")
                .condition("userGroupId", EntityCondition.IN, DARPAN_ROLE_GROUP_IDS)
                .conditionDate("fromDate", "thruDate", ec.user.nowTimestamp)
                .useCache(false).list()
                .each { found[it.userId as String] << (it.userGroupId as String) }
        return found.collectEntries { String userId, Set<String> groups ->
            [(userId): DARPAN_ROLE_GROUP_IDS.findAll { it in groups }]
        } as Map<String, List<String>>
    }
}
