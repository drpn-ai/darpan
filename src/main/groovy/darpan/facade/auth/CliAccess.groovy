package darpan.facade.auth

import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import groovy.json.JsonOutput
import org.moqui.entity.EntityCondition

import java.sql.Timestamp

/**
 * DAR-BE-195. Who may use the Darpan CLI, where, and for how long. A Darpan admin turns the CLI on for one user in one
 * tenant (admin.CliAccessAdminServices); Darpan admins themselves need no grant. A CLI sign-in lasts by the user's role:
 * 30 days for Darpan and tenant admins, 7 for everyone else, checked per tenant on every call (CliAuthSupport).
 * Spec: wrapper docs/superpowers/specs/2026-11-10-cli-access-per-user-design.md.
 *
 * Every read names the user explicitly: poll#CliLogin, which mints the key, runs anonymous, and the per-request check
 * runs before the tenant context is set (both pre-auth paths, so the reads are self-scoped and unscoped).
 */
class CliAccess {
    static final String LIVE = "LIVE"
    static final String NOT_GRANTED = "NOT_GRANTED"
    static final String ENDED = "ENDED"
    static final String PAUSED = "PAUSED"

    static final String STATUS_ACTIVE = "ACTIVE"
    static final String STATUS_PAUSED = "PAUSED"
    static final String STATUS_REVOKED = "REVOKED"

    static final String GRANT = "darpan.auth.CliAccessGrant"
    static final String EVENT = "darpan.auth.CliAccessGrantEvent"

    /** Days a CLI sign-in lasts, by permission group (one day is 24 hours). The longest of a user's groups wins. */
    static final Map<String, Integer> LIFETIME_DAYS = ([
            (TenantAccessSupport.ADMIN_USER_GROUP_ID)              : 30,
            (TenantAccessSupport.DARPAN_ADMIN_GROUP_ID)            : 30,
            (TenantAccessSupport.DARPAN_SUPER_ADMIN_GROUP_ID)      : 30,
            (TenantAccessSupport.DARPAN_TENANT_ADMIN_GROUP_ID)     : 30,
            (TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID)   : 7,
            (TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID): 7,
            (TenantAccessSupport.DARPAN_TENANT_USER_GROUP_ID)      : 7,
    ] as LinkedHashMap<String, Integer>).asImmutable()
    /** Darpan staff: use the CLI in every tenant they reach, with no grant, for the longest lifetime. */
    static final List<String> DARPAN_ADMIN_GROUP_IDS = [TenantAccessSupport.ADMIN_USER_GROUP_ID, TenantAccessSupport.DARPAN_ADMIN_GROUP_ID,
                                                        TenantAccessSupport.DARPAN_SUPER_ADMIN_GROUP_ID].asImmutable()

    static final String NOT_ON_IN_TENANT = "The Darpan CLI is not turned on for you in %s. A Darpan admin turns it on."
    static final String TOO_OLD_IN_TENANT = "This CLI sign-in is older than %d days, the limit for your role in %s. Run darpan login to sign in again."

    private static final long DAY_MS = 86_400_000L
    private static final String WHY = "self-scoped auth: CLI access for an explicitly named user (pre-auth path, no active tenant)"

    // ---------------------------------------------------------------- the rules

    static boolean isDarpanAdmin(def ec, String userId) {
        if (!userId) return false
        Timestamp now = new Timestamp(System.currentTimeMillis())
        return TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "moqui.security.UserGroupMember", WHY)
                .condition("userId", userId).condition("userGroupId", EntityCondition.IN, DARPAN_ADMIN_GROUP_IDS)
                .conditionDate("fromDate", "thruDate", now).useCache(false).count() > 0
    }

    /** The user's permission groups in the tenant, in force now. */
    static List<String> tenantGroups(def ec, String userId, String tenantId) {
        if (!userId || !tenantId) return []
        Timestamp now = new Timestamp(System.currentTimeMillis())
        return TenantScopedFinder.findGlobalUnscopedPerRequest(ec, TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, WHY)
                .condition("userId", userId).condition("tenantUserGroupId", tenantId).conditionDate("fromDate", "thruDate", now)
                .useCache(false).list().collect { it.permissionUserGroupId as String }.unique()
    }

    /** Days a CLI sign-in lasts for this user in this tenant: 30 for a Darpan admin, else the longest of their roles there; 0 for none. */
    static int lifetimeDays(def ec, String userId, String tenantId) {
        if (isDarpanAdmin(ec, userId)) return 30
        return (tenantGroups(ec, userId, tenantId).collect { LIFETIME_DAYS[it] ?: 0 }.max() ?: 0) as int
    }

    /** The user's grant in the tenant when it is ACTIVE and its window is open now; else null. */
    static def liveGrant(def ec, String userId, String tenantId) {
        def g = currentGrant(ec, tenantId, userId, true)
        return g != null && stateOf(g, new Timestamp(System.currentTimeMillis())) == LIVE ? g : null
    }

    /** The tenants where the user has a live grant and is still a member. */
    static List<String> grantedTenants(def ec, String userId) {
        if (!userId) return []
        Timestamp now = new Timestamp(System.currentTimeMillis())
        List grants = TenantScopedFinder.findGlobalUnscopedPerRequest(ec, GRANT, WHY).condition("userId", userId)
                .condition("statusId", STATUS_ACTIVE).useCache(false).list()
        return grants.findAll { stateOf(it, now) == LIVE }.collect { it.companyUserGroupId as String }.unique()
                .findAll { String t -> !tenantGroups(ec, userId, t).isEmpty() }
    }

    /** Whether the user may sign in to the CLI at all: a Darpan admin, or a live grant in a tenant they belong to. */
    static boolean cliAllowed(def ec, String userId) {
        return isDarpanAdmin(ec, userId) || !grantedTenants(ec, userId).isEmpty()
    }

    /** Days a new CLI key lives: the longest lifetime over the tenants where the user may use the CLI; 0 for none. */
    static int keyLifetimeDays(def ec, String userId) {
        if (isDarpanAdmin(ec, userId)) return 30
        return (grantedTenants(ec, userId).collect { String t -> lifetimeDays(ec, userId, t) }.max() ?: 0) as int
    }

    /**
     * Null when this CLI sign-in (signed in at signedInAt) may make a call in the tenant; otherwise why not: no live grant
     * there (Darpan admins need none), or older than the user's role there allows.
     */
    static String tenantProblem(def ec, String userId, String tenantId, Timestamp signedInAt) {
        if (!userId || !tenantId) return null
        boolean admin = isDarpanAdmin(ec, userId)
        if (!admin && liveGrant(ec, userId, tenantId) == null) return String.format(NOT_ON_IN_TENANT, tenantLabel(ec, tenantId))
        int days = admin ? 30 : lifetimeDays(ec, userId, tenantId)
        if (days <= 0) return String.format(NOT_ON_IN_TENANT, tenantLabel(ec, tenantId))
        if (signedInAt != null && System.currentTimeMillis() - signedInAt.time >= days * DAY_MS) {
            return String.format(TOO_OLD_IN_TENANT, days, tenantLabel(ec, tenantId))
        }
        return null
    }

    /** One grant's state now: REVOKED, NOT_GRANTED (window not started), ENDED, PAUSED or LIVE. */
    static String stateOf(def g, Timestamp now) {
        if (g.statusId == STATUS_REVOKED) return STATUS_REVOKED
        if ((g.fromDate as Timestamp).after(now)) return NOT_GRANTED
        if (g.thruDate != null && !(g.thruDate as Timestamp).after(now)) return ENDED
        if (g.statusId == STATUS_PAUSED) return PAUSED
        return LIVE
    }

    private static String tenantLabel(def ec, String tenantId) {
        def group = TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "moqui.security.UserGroup", WHY)
                .condition("userGroupId", tenantId).useCache(true).one()
        return (group?.description as String) ?: tenantId
    }

    /** The user's one grant in the tenant that is not REVOKED (the newest, should there ever be two). */
    private static def currentGrant(def ec, String tenantId, String userId, boolean perRequest = false) {
        if (!tenantId || !userId) return null
        def find = perRequest ? TenantScopedFinder.findGlobalUnscopedPerRequest(ec, GRANT, WHY)
                : TenantScopedFinder.findGlobalUnscoped(ec, GRANT, "admin: the user's CLI grant in a named tenant")
        return find.condition("companyUserGroupId", tenantId).condition("userId", userId)
                .condition("statusId", EntityCondition.NOT_EQUAL, STATUS_REVOKED).orderBy(["-fromDate", "-grantId"])
                .useCache(false).list().find()
    }

    // ---------------------------------------------------------------- Darpan admin (admin.CliAccessAdminServices)
    // Each service checks super admin first; these add errors to ec.message and answer null when they refuse.

    /** A grant as the admin app and CLI show it, with its state now and the sign-in lifetime it gives. */
    static Map grantView(def ec, def g) {
        def account = ec.entity.find("moqui.security.UserAccount").condition("userId", g.userId).useCache(true).one()
        return [grantId: g.grantId, userId: g.userId, username: account?.username, tenantUserGroupId: g.companyUserGroupId,
                fromDate: g.fromDate, thruDate: g.thruDate, statusId: g.statusId, pausedDate: g.pausedDate,
                state: stateOf(g, ec.user.nowTimestamp), lifetimeDays: lifetimeDays(ec, g.userId as String, g.companyUserGroupId as String)]
    }

    static List<Map> adminList(def ec, String tenantId, String userId, String statusId, Boolean includeRevoked) {
        def find = TenantScopedFinder.findGlobalUnscoped(ec, GRANT, "admin: Darpan staff list CLI grants across tenants")
        if (tenantId) find.condition("companyUserGroupId", tenantId)
        if (userId) find.condition("userId", userId)
        if (statusId) find.condition("statusId", statusId)
        else if (!includeRevoked) find.condition("statusId", EntityCondition.NOT_EQUAL, STATUS_REVOKED)
        return find.orderBy(["companyUserGroupId", "userId", "-fromDate"]).useCache(false).list().collect { grantView(ec, it) }
    }

    /** A new grant for a member of the tenant; the user's grant there that is not revoked is revoked first. */
    static Map adminCreate(def ec, String userId, String tenantId, Timestamp fromDate, Timestamp thruDate, Integer days) {
        Timestamp now = ec.user.nowTimestamp
        if (!isMember(ec, tenantId, userId)) { ec.message.addError("User ${userId} is not a member of tenant ${tenantId}.".toString()); return null }
        Timestamp from = fromDate ?: now
        Map end = windowEnd(ec, from, thruDate, days, false)
        if (end == null) return null
        def existing = currentGrant(ec, tenantId, userId)
        if (existing != null) {
            Map before = snapshot(existing)
            existing.setAll([statusId: STATUS_REVOKED, lastChangedByUserId: ec.user.userId, lastChangedDate: now])
            existing.update()
            recordEvent(ec, existing, "REVOKE", before)
        }
        def g = ec.entity.makeValue(GRANT).setAll([userId: userId, companyUserGroupId: tenantId, fromDate: from, thruDate: end.thruDate,
                statusId: STATUS_ACTIVE, createdByUserId: ec.user.userId, lastChangedByUserId: ec.user.userId, lastChangedDate: now])
        g.setSequencedIdPrimary()
        g.create()
        recordEvent(ec, g, "GRANT", null)
        return grantView(ec, g)
    }

    /** A new end (days from now, thruDate, or none) for a grant that is not revoked. */
    static Map adminUpdate(def ec, String grantId, Timestamp thruDate, Integer days, Boolean clearThruDate) {
        def g = openGrant(ec, grantId)
        if (g == null) return null
        if (thruDate == null && days == null && !clearThruDate) { ec.message.addError("Give a new end: days, thruDate or clearThruDate."); return null }
        Map end = windowEnd(ec, ec.user.nowTimestamp, thruDate, days, clearThruDate as boolean)
        if (end == null) return null
        return change(ec, g, "CHANGE", [thruDate: end.thruDate])
    }

    static Map adminPause(def ec, String grantId) {
        def g = openGrant(ec, grantId)
        if (g == null) return null
        if (g.statusId != STATUS_ACTIVE) { ec.message.addError("Grant ${grantId} is not active.".toString()); return null }
        return change(ec, g, "PAUSE", [statusId: STATUS_PAUSED, pausedDate: ec.user.nowTimestamp])
    }

    /** Lifts a pause, optionally with a new end (days from now or thruDate); refused when the grant has ended. */
    static Map adminUnpause(def ec, String grantId, Timestamp thruDate, Integer days) {
        def g = openGrant(ec, grantId)
        if (g == null) return null
        if (g.statusId != STATUS_PAUSED) { ec.message.addError("Grant ${grantId} is not paused.".toString()); return null }
        Map fields = [statusId: STATUS_ACTIVE, pausedDate: null]
        if (thruDate != null || days != null) {
            Map end = windowEnd(ec, ec.user.nowTimestamp, thruDate, days, false)
            if (end == null) return null
            fields.thruDate = end.thruDate
        }
        Timestamp thru = (fields.containsKey("thruDate") ? fields.thruDate : g.thruDate) as Timestamp
        if (thru != null && !thru.after(ec.user.nowTimestamp)) {
            ec.message.addError("Grant ${grantId} has ended; give days or a later thruDate to unpause it.".toString())
            return null
        }
        return change(ec, g, "UNPAUSE", fields)
    }

    static Map adminRevoke(def ec, String grantId) {
        def g = openGrant(ec, grantId)
        if (g == null) return null
        return change(ec, g, "REVOKE", [statusId: STATUS_REVOKED])
    }

    private static Map change(def ec, def g, String action, Map fields) {
        Map before = snapshot(g)
        g.setAll(fields + [lastChangedByUserId: ec.user.userId, lastChangedDate: ec.user.nowTimestamp])
        g.update()
        recordEvent(ec, g, action, before)
        return grantView(ec, g)
    }

    private static Map snapshot(def g) {
        return [fromDate: g.fromDate?.toString(), thruDate: g.thruDate?.toString(), statusId: g.statusId]
    }

    private static void recordEvent(def ec, def grant, String action, Map before) {
        def e = ec.entity.makeValue(EVENT).setAll([grantId: grant.grantId, companyUserGroupId: grant.companyUserGroupId, userId: grant.userId,
                action: action, byUserId: ec.user.userId, eventDate: ec.user.nowTimestamp,
                beforeJson: before == null ? null : JsonOutput.toJson(before), afterJson: JsonOutput.toJson(snapshot(grant))])
        e.setSequencedIdPrimary()
        e.create()
    }

    /** A member of the tenant, or a super admin (who enters every tenant without a membership row). */
    private static boolean isMember(def ec, String tenantId, String userId) {
        Timestamp now = ec.user.nowTimestamp
        if (ec.entity.find(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME)
                .condition("tenantUserGroupId", tenantId).condition("userId", userId).conditionDate("fromDate", "thruDate", now)
                .useCache(false).count() > 0) return true
        return ec.entity.find("moqui.security.UserGroupMember").condition("userId", userId)
                .condition("userGroupId", EntityCondition.IN, TenantAccessSupport.SUPER_ADMIN_GROUP_IDS)
                .conditionDate("fromDate", "thruDate", now).useCache(false).count() > 0
    }

    /** The grant, unless missing or revoked (both an error). */
    private static def openGrant(def ec, String grantId) {
        def g = grantId ? TenantScopedFinder.findGlobalUnscoped(ec, GRANT, "admin: one CLI grant by id").condition("grantId", grantId).useCache(false).one() : null
        if (g == null) { ec.message.addError("Grant ${grantId} was not found.".toString()); return null }
        if (g.statusId == STATUS_REVOKED) { ec.message.addError("Grant ${grantId} is revoked; grant access again instead.".toString()); return null }
        return g
    }

    /** The end of a window: thruDate, or days (each 24 hours) from start; null with an error when refused. */
    private static Map windowEnd(def ec, Timestamp start, Timestamp thruDate, Integer days, boolean clear) {
        if (clear) return [thruDate: null]
        if (days != null && thruDate != null) { ec.message.addError("Give days or thruDate, not both."); return null }
        if (days != null) {
            if (days < 1) { ec.message.addError("days must be at least 1."); return null }
            return [thruDate: new Timestamp(start.time + days * DAY_MS)]
        }
        if (thruDate != null && (!thruDate.after(start) || !thruDate.after(ec.user.nowTimestamp))) {
            ec.message.addError("thruDate must be after the start and in the future."); return null
        }
        return [thruDate: thruDate]
    }
}
