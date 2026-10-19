package darpan.admin

import groovy.transform.CompileDynamic

/** User search for the admin app, behind the DARPAN_ADMIN_API fence + super-admin guard.
 *  listUsers stays Groovy: its case-insensitive substring match, in-memory username sort and
 *  page slicing have no exact XML/SQL equivalent (LIKE wildcards, DB collation). The user
 *  mutations and detail read are XML actions in service/admin/UserAdminServices.xml, which
 *  delegate all credential work to framework org.moqui.impl.UserServices. */
@CompileDynamic
class AdminUserSupport {

    static final String DARPAN_USER_GROUP_ID = "DARPAN_USER"

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
}
