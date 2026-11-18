package darpan.facade.auth

import darpan.config.DarpanSettings
import darpan.facade.common.TenantAccessSupport
import org.moqui.entity.EntityCondition

import java.sql.Timestamp
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * DAR-BE-264 — who must use two-factor, and from when: the per-role TwoFactorRolePolicy rows (a date = required from then,
 * a future date = the grace period, empty = optional), the roles they cover, and the admin boards' views of them.
 */
class TwoFactorPolicy {
    static final String POLICY = "darpan.auth.TwoFactorRolePolicy"

    /** The roles the policy covers: the three Darpan-wide ones (UserGroupMember) and tenant admin in any tenant. */
    static final String TENANT_ADMIN_GROUP_ID = TenantAccessSupport.DARPAN_TENANT_ADMIN_GROUP_ID
    /** DAR-BE-297: the policy row that covers every person with a Darpan role or a tenant membership (not just admins). */
    static final String EVERYONE = "EVERYONE"
    static final List<String> POLICY_GROUP_IDS = (TenantAccessSupport.DARPAN_ROLE_GROUP_IDS + [TENANT_ADMIN_GROUP_ID, EVERYONE]).asImmutable()

    static final String ON = "ON"
    static final String OFF = "OFF"
    static final String REQUIRED_NOT_SET_UP = "REQUIRED_NOT_SET_UP"

    /**
     * When two-factor is required for this person: the earliest requiredFromDate among their roles (a live
     * UserGroupMember row in ADMIN, DARPAN_SUPER_ADMIN or DARPAN_ADMIN, or tenant admin in ANY tenant: a factor belongs
     * to the person, not the tenant; or, for EVERYONE, any membership or Darpan role; a temporary lockout does not lift it). Null when no role of theirs has a date (optional). Runs on every request
     * (sessionInfo, the CLI key check): the policy first, so while no role has a date (the shipped state) that is the only read.
     */
    static Timestamp requiredFrom(def ec, String userId) {
        if (!userId) return null
        Map<String, Timestamp> dated = datedRoles(ec, true)
        if (!dated) return null
        List<Timestamp> dates = rolesByUser(ec, [userId], dated.keySet(), true)[userId].collect { dated[it] }
        return dates ? dates.min() : null
    }

    /**
     * DAR-BE-302, break-glass: whether this person keeps a way back in if one factor is lost: two or more ACTIVE factors, or
     * unused recovery codes. admin.SecurityAdminServices update#TwoFactorPolicy asks it of the acting super admin before a
     * super-admin role or EVERYONE can be required.
     */
    static boolean hasWayBackIn(def ec, String userId) {
        return TwoFactorRecords.activeFactors(ec, userId).size() >= 2 || TwoFactorSupport.recoveryCodesLeft(ec, userId) > 0
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

    /** Super admins (ADMIN, DARPAN_SUPER_ADMIN) with two-factor on: informational (the policy board shows it). */
    static int enrolledSuperAdminCount(def ec) {
        Timestamp now = ec.user.nowTimestamp
        Set<String> enrolled = enrolledUserIds(ec)
        Set<String> supers = TenantAccessSupport.SUPER_ADMIN_GROUP_IDS.collectMany { roleMembers(ec, it, now) } as Set<String>
        return supers.count { it in enrolled } as int
    }

    /**
     * DAR-BE-297: the userIds the EVERYONE policy covers: an in-force tenant membership (any permission group, any tenant) or
     * an in-force Darpan-wide role, minus PERMANENTLY disabled accounts (disabled=Y with no disabledDateTime, the same reading
     * as PasswordResetSupport.lockedOut / CliAuthSupport.canSignIn). A temporary lockout (disabled=Y with a disabledDateTime)
     * is still covered: it lifts by itself and the person signs in again. Reads every member: for reminders and the admin
     * report, never the per-request path (that asks about the one user, rolesByUser, and never consults the flag: sign-in
     * already refuses a disabled account).
     */
    static List<String> coveredPeople(def ec, Timestamp now) {
        Set<String> found = coveredAmong(ec, null, now, false)
        List<String> candidates = found.toList()
        for (int i = 0; i < candidates.size(); i += 500) {
            List<String> chunk = candidates.subList(i, Math.min(i + 500, candidates.size()))
            found.removeAll(TwoFactorRecords.find(ec, "moqui.security.UserAccount", "admin: permanently disabled accounts, for the two-factor policy covering everyone")
                    .condition("userId", EntityCondition.IN, chunk).condition("disabled", "Y").condition("disabledDateTime", null)
                    .useCache(false).list()*.userId as List<String>)
        }
        return found.toList()
    }

    // ---- reminders (DAR-BE-297) ----

    static final String REMINDER_SENT = "REMINDER_SENT"

    /**
     * Whether today's reminder is due, in calendar days (UTC): never sent; or one of daysBefore (settings: 7, 3, 1, 0) days before the date; or the date
     * has passed and repeatDays have gone by since the last one. Never twice on one day. Pure.
     */
    static boolean reminderDue(Timestamp requiredFrom, Timestamp now, Timestamp lastSent, List<Integer> daysBefore = [7, 3, 1, 0], int repeatDays = 7) {
        if (requiredFrom == null || now == null) return false
        if (lastSent == null) return true
        LocalDate today = now.toInstant().atZone(ZoneOffset.UTC).toLocalDate()
        LocalDate last = lastSent.toInstant().atZone(ZoneOffset.UTC).toLocalDate()
        if (!last.isBefore(today)) return false
        long daysLeft = ChronoUnit.DAYS.between(today, requiredFrom.toInstant().atZone(ZoneOffset.UTC).toLocalDate())
        if (daysLeft < 0) return ChronoUnit.DAYS.between(last, today) >= repeatDays
        return daysLeft in daysBefore.collect { it as long }
    }

    /**
     * The daily job: everyone the policy covers, without an ACTIVE factor, whose reminder is due, is sent
     * DARPAN_2FA_REMINDER and gets a REMINDER_SENT event. No email on file: nothing is sent and no event is written (so the
     * person is tried again the day an address is added). Returns how many were sent.
     */
    static int sendReminders(def ec) {
        Timestamp now = ec.user.nowTimestamp
        // No dated policy: nobody is owed a reminder, so skip the member, factor and event reads.
        if (datedRoles(ec, false).isEmpty()) return 0
        List<String> covered = coveredPeople(ec, now)
        List<Integer> daysBefore = DarpanSettings.intListValue('darpan.twoFactor.reminderDaysBefore')
        int repeatDays = DarpanSettings.intValue('darpan.twoFactor.reminderRepeatDays')
        int sent = 0
        for (int i = 0; i < covered.size(); i += 500) {
            List<String> chunk = covered.subList(i, Math.min(i + 500, covered.size()))
            Set<String> enrolled = TwoFactorRecords.find(ec, TwoFactorRecords.FACTOR, "admin: who has a two-factor factor, for the reminders")
                    .condition("userId", EntityCondition.IN, chunk).condition("statusId", TwoFactorRecords.ACTIVE).useCache(false)
                    .list()*.userId as Set<String>
            Map<String, Timestamp> lastSent = [:]
            TwoFactorRecords.find(ec, TwoFactorRecords.EVENT, "admin: when each person was last reminded about two-factor")
                    .condition("userId", EntityCondition.IN, chunk).condition("eventType", REMINDER_SENT).useCache(false).list().each {
                Timestamp at = it.eventDate as Timestamp
                if (at != null && (lastSent[it.userId as String] == null || at.after(lastSent[it.userId as String]))) lastSent[it.userId as String] = at
            }
            for (String userId in chunk) {
                if (userId in enrolled) continue
                Timestamp from = requiredFrom(ec, userId)
                if (from == null || !reminderDue(from, now, lastSent[userId], daysBefore, repeatDays)) continue
                def account = TwoFactorRecords.find(ec, "moqui.security.UserAccount", "self-scoped auth: the person a two-factor reminder goes to")
                        .condition("userId", userId).useCache(false).one()
                if (!(account?.emailAddress as String)?.trim()) continue
                // The stored day is a UTC calendar day (the CLI sends "2026-12-02"), the same calendar reminderDue counts in.
                SimpleDateFormat fmt = new SimpleDateFormat("d MMMM yyyy", Locale.ENGLISH)
                fmt.setTimeZone(TimeZone.getTimeZone("UTC"))
                String requiredDate = fmt.format(from)
                boolean past = !from.after(now)
                TwoFactorRecords.notify(ec, userId, TwoFactorSupport.TEMPLATE_REMINDER, [requiredDate: requiredDate,
                        appUrl: CliAuthSupport.appBaseUrl(ec), past: past,
                        // The subject's words are copy blocks (DAR-BE-303), so an edit in production changes them without a release.
                        subjectLine: darpan.email.EmailCopy.text(ec, TwoFactorSupport.TEMPLATE_REMINDER, past ? "subject.after" : "subject.before", [requiredDate: requiredDate])])
                TwoFactorRecords.event(ec, userId, REMINDER_SENT)
                sent++
            }
        }
        return sent
    }

    // ---- reads ----

    /** The people among userIds (null: all) with a membership or a Darpan role: two reads. Never consults UserAccount.disabled. */
    private static Set<String> coveredAmong(def ec, Collection<String> userIds, Timestamp now, boolean perRequest) {
        Closure finder = { String entity, String reason ->
            perRequest ? TwoFactorRecords.findPerRequest(ec, entity, reason) : TwoFactorRecords.find(ec, entity, reason)
        }
        Set<String> found = new HashSet<String>()
        def members = finder("darpan.auth.TenantUserPermissionGroupMember", "self-scoped auth: tenant members, for the two-factor policy covering everyone")
        if (userIds != null) members.condition("userId", EntityCondition.IN, userIds)
        List memberRows = members.conditionDate("fromDate", "thruDate", now).useCache(false).list()
        // A membership in a DISABLED tenant (an ex-customer) covers no one (the TenantAccessSupport.listDisabledTenantIds rule:
        // TenantSetting.disabled = Y); the read stays bounded: only the tenants these members sit in.
        Set<String> tenantIds = memberRows*.tenantUserGroupId as Set<String>
        Set<String> disabledTenants = new HashSet<String>()
        if (tenantIds) {
            def settings = finder(TenantAccessSupport.TENANT_SETTING_ENTITY_NAME, "self-scoped auth: which tenants are disabled, for the two-factor policy covering everyone")
                    .condition("disabled", "Y")
            if (userIds != null) settings.condition("companyUserGroupId", EntityCondition.IN, tenantIds)
            disabledTenants.addAll(settings.useCache(false).list()*.companyUserGroupId as List<String>)
        }
        found.addAll(memberRows.findAll { !(it.tenantUserGroupId as String in disabledTenants) }*.userId as List<String>)
        def roles = finder("moqui.security.UserGroupMember", "self-scoped auth: Darpan-wide roles, for the two-factor policy covering everyone")
        if (userIds != null) roles.condition("userId", EntityCondition.IN, userIds)
        found.addAll(roles.condition("userGroupId", EntityCondition.IN, TenantAccessSupport.DARPAN_ROLE_GROUP_IDS)
                .conditionDate("fromDate", "thruDate", now).useCache(false).list()*.userId as List<String>)
        return found
    }

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
        if (EVERYONE in groupIds) {
            coveredAmong(ec, userIds, now, perRequest).each { roles[it] << EVERYONE }
        }
        return roles
    }

    private static Set<String> enrolledUserIds(def ec) {
        return TwoFactorRecords.find(ec, TwoFactorRecords.FACTOR, "admin: who has two-factor on, for the policy")
                .condition("statusId", TwoFactorRecords.ACTIVE).useCache(false).list()*.userId as Set<String>
    }

    private static List<String> roleMembers(def ec, String groupId, Timestamp now) {
        if (groupId == EVERYONE) return coveredPeople(ec, now)
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
