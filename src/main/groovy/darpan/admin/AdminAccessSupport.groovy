package darpan.admin

import darpan.facade.common.TenantAccessSupport
import groovy.transform.CompileDynamic

/** Shared guard + session identity for the admin.* service package (platform operator surface). */
@CompileDynamic
class AdminAccessSupport {

    static final String SUPER_ADMIN_REQUIRED_MESSAGE = "This operation requires super-admin access."

    /** Defense-in-depth behind the DARPAN_ADMIN_API artifact fence. Adds an error when denied. */
    static boolean requireSuperAdmin(def ec) {
        return TenantAccessSupport.requireSuperAdmin(ec, SUPER_ADMIN_REQUIRED_MESSAGE)
    }

    /** Identity payload for the admin app's route guard. Null (with error) when not super admin. */
    static Map<String, Object> buildAdminSessionInfo(def ec) {
        if (!requireSuperAdmin(ec)) return null
        return [
            userId      : ec?.user?.userId,
            username    : ec?.user?.username,
            isSuperAdmin: true,
        ]
    }
}
