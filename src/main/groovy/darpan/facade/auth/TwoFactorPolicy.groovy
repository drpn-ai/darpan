package darpan.facade.auth

import darpan.facade.common.TenantAccessSupport
import org.moqui.entity.EntityCondition

import java.sql.Timestamp

/**
 * DAR-BE-264 — who must use two-factor, and from when: the per-role TwoFactorRolePolicy rows (a date = required from then,
 * a future date = the grace period, empty = optional), the roles they cover, and the admin boards' views of them.
 */
class TwoFactorPolicy {
    static final String POLICY = "darpan.auth.TwoFactorRolePolicy"

    /** The roles the policy covers: the three Darpan-wide ones (UserGroupMember) and tenant admin in any tenant. */
    static final String TENANT_ADMIN_GROUP_ID = TenantAccessSupport.DARPAN_TENANT_ADMIN_GROUP_ID
    static final List<String> POLICY_GROUP_IDS = (TenantAccessSupport.DARPAN_ROLE_GROUP_IDS + [TENANT_ADMIN_GROUP_ID]).asImmutable()
    /** At least two enrolled super admins before super-admin enforcement can be switched on (break-glass). */
    static final int MIN_ENROLLED_SUPER_ADMINS = 2

    static final String ON = "ON"
    static final String OFF = "OFF"
    static final String REQUIRED_NOT_SET_UP = "REQUIRED_NOT_SET_UP"

    /**
     * When two-factor is required for this person: the earliest requiredFromDate among their roles (a live
     * UserGroupMember row in ADMIN, DARPAN_SUPER_ADMIN or DARPAN_ADMIN, or tenant admin in ANY tenant: a factor belongs
     * to the person, not the tenant). Null when no role of theirs has a date (optional). Runs on every request
     * (sessionInfo, the CLI key check): the policy first, so while no role has a date (the shipped state) that is the only read.
     */
    static Timestamp requiredFrom(def ec, String userId) {
        if (!userId) return null
        Map<String, Timestamp> dated = datedRoles(ec, true)
        if (!dated) return null
        List<Timestamp> dates = rolesByUser(ec, [userId], dated.keySet(), true)[userId].collect { dated[it] }
        return dates ? dates.min() : null
    }

    static boolean isRequiredNow(def ec, String userId) {
        Timestamp from = requiredFrom(ec, userId)
        return from != null && requiredNow(from, ec.user.nowTimestamp)
    }

    /** A CLI key of a person who is required and not enrolled is refused (the policy switched on after they signed in). */
    static boolean requiredButNotEnrolled(def ec, String userId) {
        return isRequiredNow(ec, userId) && !TwoFactorRecords.enabled(ec, userId)
    }

    /** The admin boards' word for a person: ON (a factor is on), REQUIRED_NOT_SET_UP or OFF. Pure. */
    static String adminState(boolean enabled, Timestamp requiredFrom, Timestamp now) {
        return enabled ? ON : (requiredNow(requiredFrom, now) ? REQUIRED_NOT_SET_UP : OFF)
    }

    /** userId -> adminState, for the admin users board: one read each of factors, policy and memberships for the page. */
    static Map<String, String> adminStates(def ec, List<String> userIds) {
        if (!userIds) return [:]
        Timestamp now = ec.user.nowTimestamp
        Set<String> enrolled = TwoFactorRecords.find(ec, TwoFactorRecords.FACTOR, "admin: two-factor state for the users board")
                .condition("statusId", TwoFactorRecords.ACTIVE).condition("userId", EntityCondition.IN, userIds)
                .useCache(false).list()*.userId as Set<String>
        Set<String> requiringRoles = datedRoles(ec, false).findAll { requiredNow(it.value, now) }.keySet()
        Map<String, Set<String>> roles = requiringRoles ? rolesByUser(ec, userIds, requiringRoles, false) : [:]
        return userIds.collectEntries { String userId ->
            [(userId): userId in enrolled ? ON : (roles[userId] ? REQUIRED_NOT_SET_UP : OFF)]
        }
    }

    /** get#TwoFactorPolicies: each role's date with who in it is and is not enrolled (never the secret), and the
     *  enrolled super-admin count. */
    static Map<String, Object> policies(def ec) {
        Timestamp now = ec.user.nowTimestamp
        Map<String, Object> rows = TwoFactorRecords.find(ec, POLICY, "two-factor policy is platform configuration, not tenant data")
                .useCache(false).list().collectEntries { [(it.permissionGroupId): it] }
        Set<String> enrolled = enrolledUserIds(ec)
        Map<String, List<String>> members = POLICY_GROUP_IDS.collectEntries { [(it): roleMembers(ec, it, now)] }
        Map<String, String> names = usernames(ec, members.values().flatten().unique() as List<String>)
        List<Map> roles = POLICY_GROUP_IDS.collect { String groupId ->
            def row = rows[groupId]
            Map<Boolean, List<String>> split = members[groupId].groupBy { it in enrolled }
            List<String> yes = split[true] ?: []
            List<String> no = split[false] ?: []
            [permissionGroupId  : groupId, requiredFromDate: row?.requiredFromDate, lastChangedByUserId: row?.lastChangedByUserId,
             lastChangedDate    : row?.lastChangedDate, enrolledCount: yes.size(), notEnrolledCount: no.size(),
             enrolled           : yes.collect { [userId: it, username: names[it]] },
             notEnrolled        : no.collect { [userId: it, username: names[it]] }]
        }
        Set<String> supers = TenantAccessSupport.SUPER_ADMIN_GROUP_IDS.collectMany { members[it] } as Set<String>
        return [policies: roles, enrolledSuperAdminCount: supers.count { it in enrolled } as int]
    }

    /** Super admins (ADMIN, DARPAN_SUPER_ADMIN) with two-factor on: the break-glass rule admin.SecurityAdminServices
     *  update#TwoFactorPolicy checks (at least MIN_ENROLLED_SUPER_ADMINS before a super-admin role can be required). */
    static int enrolledSuperAdminCount(def ec) {
        Timestamp now = ec.user.nowTimestamp
        Set<String> enrolled = enrolledUserIds(ec)
        Set<String> supers = TenantAccessSupport.SUPER_ADMIN_GROUP_IDS.collectMany { roleMembers(ec, it, now) } as Set<String>
        return supers.count { it in enrolled } as int
    }

    // ---- reads ----

    private static boolean requiredNow(Timestamp requiredFrom, Timestamp now) {
        return requiredFrom != null && !requiredFrom.after(now)
    }

    /** Role -> requiredFromDate for every role with a date. */
    private static Map<String, Timestamp> datedRoles(def ec, boolean perRequest) {
        String reason = "two-factor policy is platform configuration, not tenant data"
        def finder = perRequest ? TwoFactorRecords.findPerRequest(ec, POLICY, reason) : TwoFactorRecords.find(ec, POLICY, reason)
        return finder.useCache(false).list().findAll { it.requiredFromDate != null }
                .collectEntries { [(it.permissionGroupId as String): it.requiredFromDate as Timestamp] }
    }

    /** userId -> the roles among groupIds each of userIds holds now: at most one read per kind of role. */
    private static Map<String, Set<String>> rolesByUser(def ec, Collection<String> userIds, Collection<String> groupIds,
                                                        boolean perRequest) {
        Timestamp now = ec.user.nowTimestamp
        Map<String, Set<String>> roles = userIds.collectEntries { [(it): new HashSet<String>()] }
        Closure finder = { String entity, String reason ->
            perRequest ? TwoFactorRecords.findPerRequest(ec, entity, reason) : TwoFactorRecords.find(ec, entity, reason)
        }
        List<String> darpanRoles = groupIds.findAll { it in TenantAccessSupport.DARPAN_ROLE_GROUP_IDS } as List<String>
        if (darpanRoles) {
            finder("moqui.security.UserGroupMember", "self-scoped auth: Darpan-wide roles for the two-factor policy")
                    .condition("userId", EntityCondition.IN, userIds).condition("userGroupId", EntityCondition.IN, darpanRoles)
                    .conditionDate("fromDate", "thruDate", now).useCache(false).list()
                    .each { roles[it.userId as String] << (it.userGroupId as String) }
        }
        if (TENANT_ADMIN_GROUP_ID in groupIds) {
            finder("darpan.auth.TenantUserPermissionGroupMember", "self-scoped auth: tenant admin in any tenant, for the two-factor policy")
                    .condition("userId", EntityCondition.IN, userIds).condition("permissionUserGroupId", TENANT_ADMIN_GROUP_ID)
                    .conditionDate("fromDate", "thruDate", now).useCache(false).list()
                    .each { roles[it.userId as String] << TENANT_ADMIN_GROUP_ID }
        }
        return roles
    }

    private static Set<String> enrolledUserIds(def ec) {
        return TwoFactorRecords.find(ec, TwoFactorRecords.FACTOR, "admin: who has two-factor on, for the policy")
                .condition("statusId", TwoFactorRecords.ACTIVE).useCache(false).list()*.userId as Set<String>
    }

    private static List<String> roleMembers(def ec, String groupId, Timestamp now) {
        if (groupId == TENANT_ADMIN_GROUP_ID) {
            return TwoFactorRecords.find(ec, "darpan.auth.TenantUserPermissionGroupMember", "admin: tenant admins in every tenant, for the two-factor policy")
                    .condition("permissionUserGroupId", groupId).conditionDate("fromDate", "thruDate", now).useCache(false)
                    .list()*.userId.unique() as List<String>
        }
        return TwoFactorRecords.find(ec, "moqui.security.UserGroupMember", "admin: members of a Darpan-wide role, for the two-factor policy")
                .condition("userGroupId", groupId).conditionDate("fromDate", "thruDate", now).useCache(false)
                .list()*.userId.unique() as List<String>
    }

    private static Map<String, String> usernames(def ec, List<String> userIds) {
        if (!userIds) return [:]
        return TwoFactorRecords.find(ec, "moqui.security.UserAccount", "admin: usernames for the two-factor policy board")
                .condition("userId", EntityCondition.IN, userIds).useCache(false).list()
                .collectEntries { [(it.userId): it.username] }
    }
}
