package darpan.agent

import darpan.facade.common.TenantScopedFinder
import groovy.json.JsonOutput
import org.moqui.entity.EntityCondition

import java.math.RoundingMode
import java.sql.Timestamp

/**
 * DAR-BE-194. A user's AI access in one tenant. A Darpan admin grants it (admin.AgentAccessAdminServices) for a window and
 * a dollar limit; spend is the sum of AgentTurn.costMicros of the grant's turns, the ledger the tenant's daily cap reads.
 * The agent asks {@link #access} before any model call and before each role, and the usage ledger calls
 * {@link #pauseIfOverLimit} after every priced request.
 */
class AgentAccess {
    static final String LIVE = "LIVE"
    static final String NOT_GRANTED = "NOT_GRANTED"
    static final String EXPIRED = "EXPIRED"
    static final String PAUSED = "PAUSED"

    static final String STATUS_ACTIVE = "ACTIVE"
    static final String STATUS_PAUSED = "PAUSED"
    static final String STATUS_REVOKED = "REVOKED"
    static final String REASON_COST_LIMIT = "COST_LIMIT"
    static final String REASON_ADMIN = "ADMIN"
    static final String SYSTEM = "SYSTEM"

    static final String GRANT = "darpan.agent.AgentAccessGrant"
    static final String EVENT = "darpan.agent.AgentAccessGrantEvent"
    private static final String TURN = "darpan.agent.AgentTurn"
    private static final String WHY = "agent access: tenant and user named explicitly on every read"

    /**
     * The user's access in the tenant, from their one grant that is not REVOKED: [state, grantId, fromDate, thruDate,
     * capMicros, spentMicros, pauseReason]. NOT_GRANTED with no grant or one whose window has not started; EXPIRED once
     * thruDate is reached; PAUSED when paused, or when spend is at or over the limit (before the pause is written); else LIVE.
     */
    static Map access(def ec, String tenantId, String userId) {
        // DAR-BE-246 (Aditi 2026-11-12): a Darpan admin has AI access in every tenant without a grant, and no per-user limit; the
        // tenant switch, its daily budget and the hourly turn limit still apply.
        if (tenantId && isSuperAdminUser(ec, userId))
            return [state: LIVE, grantId: null, fromDate: null, thruDate: null, capMicros: null, spentMicros: 0L, pauseReason: null]
        def g = (tenantId && userId) ? currentGrant(ec, tenantId, userId) : null
        if (g == null) return [state: NOT_GRANTED, grantId: null, fromDate: null, thruDate: null, capMicros: null, spentMicros: 0L, pauseReason: null]
        long spent = spentMicros(ec, g.grantId as String)
        Long cap = g.costCapMicros as Long
        boolean overLimit = cap != null && spent >= cap
        String reason = g.statusId == STATUS_PAUSED ? (g.pauseReason as String) : (overLimit ? REASON_COST_LIMIT : null)
        return [state: stateOf(g, spent, ec.user.nowTimestamp), grantId: g.grantId, fromDate: g.fromDate, thruDate: g.thruDate, capMicros: cap,
                spentMicros: spent, pauseReason: reason]
    }

    /** One grant's state now: REVOKED, NOT_GRANTED (window not started), EXPIRED, PAUSED (or at its limit) or LIVE. */
    static String stateOf(def g, long spent, Timestamp now) {
        if (g.statusId == STATUS_REVOKED) return STATUS_REVOKED
        if ((g.fromDate as Timestamp).after(now)) return NOT_GRANTED
        if (g.thruDate != null && !(g.thruDate as Timestamp).after(now)) return EXPIRED
        if (g.statusId == STATUS_PAUSED || (g.costCapMicros != null && spent >= (g.costCapMicros as long))) return PAUSED
        return LIVE
    }

    /** What a grant has spent, in micro-dollars: its turns' costMicros. */
    static long spentMicros(def ec, String grantId) {
        if (!grantId) return 0L
        return (TenantScopedFinder.findGlobalUnscoped(ec, TURN, WHY).condition("grantId", grantId).selectField("costMicros")
                .useCache(false).list().sum(0L) { ((it.costMicros ?: 0L) as long) }) as long
    }

    /**
     * Pauses an ACTIVE grant with a limit once its spend reaches it (COST_LIMIT, with an AUTO_PAUSE event by SYSTEM), in the
     * caller's transaction. Answers whether the grant is PAUSED after the call; false for a missing, revoked or unlimited one.
     */
    static boolean pauseIfOverLimit(def ec, String grantId) {
        if (!grantId) return false
        def g = TenantScopedFinder.findGlobalUnscoped(ec, GRANT, WHY).condition("grantId", grantId).useCache(false).one()
        if (g == null) return false
        if (g.statusId == STATUS_PAUSED) return true
        if (g.statusId != STATUS_ACTIVE || g.costCapMicros == null) return false
        if (spentMicros(ec, grantId) < (g.costCapMicros as long)) return false
        Map before = snapshot(g)
        Timestamp now = ec.user.nowTimestamp
        g.setAll([statusId: STATUS_PAUSED, pauseReason: REASON_COST_LIMIT, pausedDate: now, lastChangedByUserId: SYSTEM, lastChangedDate: now])
        g.update()
        recordEvent(ec, g, "AUTO_PAUSE", before, SYSTEM)
        return true
    }

    /** One AgentAccessGrantEvent: who acted (byUserId, else the signed-in user, else SYSTEM), the grant before and as it is now. */
    static void recordEvent(def ec, def grant, String action, Map before, String byUserId = null) {
        def e = ec.entity.makeValue(EVENT).setAll([grantId: grant.grantId, companyUserGroupId: grant.companyUserGroupId, userId: grant.userId,
                action: action, byUserId: byUserId ?: (ec.user.userId ?: SYSTEM), eventDate: ec.user.nowTimestamp,
                beforeJson: before == null ? null : JsonOutput.toJson(before), afterJson: JsonOutput.toJson(snapshot(grant))])
        e.setSequencedIdPrimary()
        e.create()
    }

    /** Dollars as micro-dollars, rounded half-up; a limit must be more than zero once rounded (a sub-micro limit is none). */
    static long microsOf(BigDecimal usd) {
        long micros = usd == null ? 0L : usd.multiply(new BigDecimal(1_000_000)).setScale(0, RoundingMode.HALF_UP).longValueExact()
        if (micros <= 0L) throw new IllegalArgumentException("A limit must be more than 0 dollars.")
        return micros
    }

    /** The grant's window, limit and status, as an event records them. */
    static Map snapshot(def g) {
        return [fromDate: g.fromDate?.toString(), thruDate: g.thruDate?.toString(), costCapMicros: g.costCapMicros, statusId: g.statusId,
                pauseReason: g.pauseReason]
    }

    /** The user's one grant in the tenant that is not REVOKED (the newest, should there ever be two). */
    static def currentGrant(def ec, String tenantId, String userId) {
        return TenantScopedFinder.findGlobalUnscoped(ec, GRANT, WHY).condition("companyUserGroupId", tenantId).condition("userId", userId)
                .condition("statusId", EntityCondition.NOT_EQUAL, STATUS_REVOKED).orderBy(["-fromDate", "-grantId"])
                .useCache(false).list().find()
    }

    // ---------------------------------------------------------------- Darpan admin (admin.AgentAccessAdminServices)
    // Each service checks super admin first; these add errors to ec.message and answer null when they refuse.

    private static final long DAY_MS = 86_400_000L

    /** A grant as the admin app and CLI show it, with its spend and its state now. */
    static Map grantView(def ec, def g) {
        long spent = spentMicros(ec, g.grantId as String)
        def account = ec.entity.find("moqui.security.UserAccount").condition("userId", g.userId).useCache(true).one()
        return [grantId: g.grantId, userId: g.userId, username: account?.username, tenantUserGroupId: g.companyUserGroupId,
                fromDate: g.fromDate, thruDate: g.thruDate, statusId: g.statusId, pauseReason: g.pauseReason, pausedDate: g.pausedDate,
                capMicros: g.costCapMicros, spentMicros: spent, costCapUsd: dollars(g.costCapMicros as Long), spentUsd: dollars(spent),
                state: stateOf(g, spent, ec.user.nowTimestamp)]
    }

    static List<Map> adminList(def ec, String tenantId, String userId, String statusId, Boolean includeRevoked) {
        def find = TenantScopedFinder.findGlobalUnscoped(ec, GRANT, "admin: Darpan staff list grants across tenants")
        if (tenantId) find.condition("companyUserGroupId", tenantId)
        if (userId) find.condition("userId", userId)
        if (statusId) find.condition("statusId", statusId)
        else if (!includeRevoked) find.condition("statusId", EntityCondition.NOT_EQUAL, STATUS_REVOKED)
        return find.orderBy(["companyUserGroupId", "userId", "-fromDate"]).useCache(false).list().collect { grantView(ec, it) }
    }

    /**
     * A new grant for a member of the tenant, always with a limit (Aditi 2026-11-10: the Darpan admin decides the spend limit
     * when granting); the user's grant there that is not revoked is revoked first.
     */
    static Map adminCreate(def ec, String userId, String tenantId, Timestamp fromDate, Timestamp thruDate, Integer days, BigDecimal costCapUsd) {
        Timestamp now = ec.user.nowTimestamp
        if (isSuperAdminUser(ec, userId)) { ec.message.addError("User ${userId} is a Darpan admin, with AI access in every tenant; there is nothing to grant.".toString()); return null }
        if (!isMember(ec, tenantId, userId)) { ec.message.addError("User ${userId} is not a member of tenant ${tenantId}.".toString()); return null }
        Timestamp from = fromDate ?: now
        Map end = windowEnd(ec, from, thruDate, days, false)
        Long cap = capOf(ec, costCapUsd)
        if (end == null || ec.message.hasError()) return null
        def existing = currentGrant(ec, tenantId, userId)
        if (existing != null) {
            Map before = snapshot(existing)
            existing.setAll([statusId: STATUS_REVOKED, lastChangedByUserId: ec.user.userId, lastChangedDate: now])
            existing.update()
            recordEvent(ec, existing, "REVOKE", before)
        }
        def g = ec.entity.makeValue(GRANT).setAll([userId: userId, companyUserGroupId: tenantId, fromDate: from, thruDate: end.thruDate,
                costCapMicros: cap, statusId: STATUS_ACTIVE, createdByUserId: ec.user.userId, lastChangedByUserId: ec.user.userId, lastChangedDate: now])
        g.setSequencedIdPrimary()
        g.create()
        recordEvent(ec, g, "GRANT", null)
        return grantView(ec, g)
    }

    /** A new end (days from now, thruDate, or none) or a new limit (never none) for a grant that is not revoked; at its limit it pauses. */
    static Map adminUpdate(def ec, String grantId, Timestamp thruDate, Integer days, BigDecimal costCapUsd, Boolean clearThruDate) {
        def g = openGrant(ec, grantId)
        if (g == null) return null
        if (thruDate == null && days == null && costCapUsd == null && !clearThruDate) {
            ec.message.addError("Give a new end (days, thruDate or clearThruDate) or a new limit (costCapUsd).")
            return null
        }
        Map change = changes(ec, g, thruDate, days, costCapUsd, clearThruDate)
        if (change == null) return null
        Map before = snapshot(g)
        g.setAll(change + [lastChangedByUserId: ec.user.userId, lastChangedDate: ec.user.nowTimestamp])
        g.update()
        recordEvent(ec, g, "CHANGE", before)
        if (g.statusId == STATUS_ACTIVE) pauseIfOverLimit(ec, grantId)
        return grantView(ec, openGrant(ec, grantId))
    }

    static Map adminPause(def ec, String grantId) {
        def g = openGrant(ec, grantId)
        if (g == null) return null
        if (g.statusId != STATUS_ACTIVE) { ec.message.addError("Grant ${grantId} is not active.".toString()); return null }
        Map before = snapshot(g)
        Timestamp now = ec.user.nowTimestamp
        g.setAll([statusId: STATUS_PAUSED, pauseReason: REASON_ADMIN, pausedDate: now, lastChangedByUserId: ec.user.userId, lastChangedDate: now])
        g.update()
        recordEvent(ec, g, "PAUSE", before)
        return grantView(ec, g)
    }

    /** Lifts a pause, optionally with a new limit or end; refused while spend would still be at or over the limit. */
    static Map adminUnpause(def ec, String grantId, BigDecimal costCapUsd, Timestamp thruDate, Integer days) {
        def g = openGrant(ec, grantId)
        if (g == null) return null
        if (g.statusId != STATUS_PAUSED) { ec.message.addError("Grant ${grantId} is not paused.".toString()); return null }
        Map change = changes(ec, g, thruDate, days, costCapUsd, false)
        if (change == null) return null
        Long cap = (change.containsKey("costCapMicros") ? change.costCapMicros : g.costCapMicros) as Long
        long spent = spentMicros(ec, grantId)
        if (cap != null && spent >= cap) {
            ec.message.addError("Grant ${grantId} has spent \$${dollars(spent)} of its \$${dollars(cap)} limit; give a higher limit to unpause it.".toString())
            return null
        }
        Timestamp thru = (change.containsKey("thruDate") ? change.thruDate : g.thruDate) as Timestamp
        if (thru != null && !thru.after(ec.user.nowTimestamp)) {
            ec.message.addError("Grant ${grantId} has ended; give days or a later thruDate to unpause it.".toString())
            return null
        }
        Map before = snapshot(g)
        g.setAll(change + [statusId: STATUS_ACTIVE, pauseReason: null, pausedDate: null, lastChangedByUserId: ec.user.userId,
                           lastChangedDate: ec.user.nowTimestamp])
        g.update()
        recordEvent(ec, g, "UNPAUSE", before)
        return grantView(ec, g)
    }

    static Map adminRevoke(def ec, String grantId) {
        def g = openGrant(ec, grantId)
        if (g == null) return null
        Map before = snapshot(g)
        g.setAll([statusId: STATUS_REVOKED, lastChangedByUserId: ec.user.userId, lastChangedDate: ec.user.nowTimestamp])
        g.update()
        recordEvent(ec, g, "REVOKE", before)
        return grantView(ec, g)
    }

    /**
     * AI spend over the last days (1 to 366) for a user, a tenant, or both, from AgentTurn: totals, by UTC day (oldest
     * first), by role and model (dearest first), and the grants whose window reaches into the range.
     */
    static Map adminUsage(def ec, String userId, String tenantId, Integer days) {
        int d = days == null ? 30 : days
        if (d < 1 || d > 366) { ec.message.addError("days is 1 to 366."); return null }
        Timestamp since = new Timestamp(ec.user.nowTimestamp.time - d * DAY_MS)
        def find = TenantScopedFinder.findGlobalUnscoped(ec, TURN, "admin: Darpan staff read AI usage across tenants")
                .condition("startedDate", EntityCondition.GREATER_THAN_EQUAL_TO, since)
        if (userId) find.condition("userId", userId)
        if (tenantId) find.condition("companyUserGroupId", tenantId)
        List rows = find.useCache(false).list()
        Closure<Long> n = { Object v -> (v ?: 0L) as long }
        Map<String, Map> byDay = new TreeMap<>()
        Map<String, Map> byRoleModel = [:]
        rows.each { r ->
            String day = (r.startedDate as Timestamp).toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
            Map dd = byDay.computeIfAbsent(day) { [date: day, costMicros: 0L, requests: 0L] }
            dd.costMicros = (dd.costMicros as long) + n(r.costMicros)
            dd.requests = (dd.requests as long) + n(r.requestCount)
            Map rm = byRoleModel.computeIfAbsent("${r.role}|${r.model}".toString()) {
                [role: r.role, model: r.model, turns: 0, requests: 0L, inputTokens: 0L, outputTokens: 0L, cacheReadTokens: 0L, cacheWriteTokens: 0L, costMicros: 0L]
            }
            rm.turns = (rm.turns as int) + 1
            ["requestCount": "requests", "inputTokens": "inputTokens", "outputTokens": "outputTokens", "cacheReadTokens": "cacheReadTokens",
             "cacheWriteTokens": "cacheWriteTokens", "costMicros": "costMicros"].each { String from, String to -> rm[to] = (rm[to] as long) + n(r.get(from)) }
        }
        List<Map> grants = adminList(ec, tenantId, userId, null, true).findAll { Map g -> g.thruDate == null || !(g.thruDate as Timestamp).before(since) }
        return [totalMicros: rows.sum(0L) { n(it.costMicros) }, requests: rows.sum(0L) { n(it.requestCount) }, totalUsd: dollars(rows.sum(0L) { n(it.costMicros) } as long),
                byDay: new ArrayList(byDay.values()), byRoleModel: byRoleModel.values().sort { -(it.costMicros as long) }, grants: grants]
    }

    /** Micro-dollars as a dollar string with 4 decimals; null for null. */
    static String dollars(Long micros) {
        return micros == null ? null : new BigDecimal(micros).divide(new BigDecimal(1_000_000), 4, RoundingMode.HALF_UP).toPlainString()
    }

    /** A member of the tenant by a membership row. */
    private static boolean isMember(def ec, String tenantId, String userId) {
        return ec.entity.find(darpan.facade.common.TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME)
                .condition("tenantUserGroupId", tenantId).condition("userId", userId).conditionDate("fromDate", "thruDate", ec.user.nowTimestamp)
                .useCache(false).count() > 0
    }

    /** The user is in a super-admin group (ADMIN, DARPAN_SUPER_ADMIN) now; named by userId, never read from the signed-in user. */
    static boolean isSuperAdminUser(def ec, String userId) {
        if (!userId) return false
        return ec.entity.find("moqui.security.UserGroupMember").disableAuthz().condition("userId", userId)
                .condition("userGroupId", EntityCondition.IN, darpan.facade.common.TenantAccessSupport.SUPER_ADMIN_GROUP_IDS)
                .conditionDate("fromDate", "thruDate", ec.user.nowTimestamp).useCache(false).count() > 0
    }

    /** The grant, unless missing or revoked (both an error). */
    private static def openGrant(def ec, String grantId) {
        def g = grantId ? TenantScopedFinder.findGlobalUnscoped(ec, GRANT, "admin: one grant by id").condition("grantId", grantId).useCache(false).one() : null
        if (g == null) { ec.message.addError("Grant ${grantId} was not found.".toString()); return null }
        if (g.statusId == STATUS_REVOKED) { ec.message.addError("Grant ${grantId} is revoked; grant access again instead.".toString()); return null }
        return g
    }

    /** The end of a window: thruDate, or days (each 24 hours) from start; null with an error when refused. */
    private static Map windowEnd(def ec, Timestamp start, Timestamp thruDate, Integer days, boolean clear) {
        if (clear) return [thruDate: null]
        if (days != null && thruDate != null) { ec.message.addError("Give days or thruDate, not both."); return null }
        Timestamp now = ec.user.nowTimestamp
        if (days != null) {
            if (days < 1) { ec.message.addError("days must be at least 1."); return null }
            return [thruDate: new Timestamp(start.time + days * DAY_MS)]
        }
        if (thruDate != null && (!thruDate.after(start) || !thruDate.after(now))) {
            ec.message.addError("thruDate must be after the start and in the future."); return null
        }
        return [thruDate: thruDate]
    }

    private static Long capOf(def ec, BigDecimal usd) {
        try { return microsOf(usd) } catch (IllegalArgumentException e) { ec.message.addError(e.message); return null }
    }

    /** The fields an update or unpause changes; days count from now (an extension), not from the grant's start. */
    private static Map changes(def ec, def g, Timestamp thruDate, Integer days, BigDecimal costCapUsd, Boolean clearThruDate) {
        Map out = [:]
        if (thruDate != null || days != null || clearThruDate) {
            Map end = windowEnd(ec, ec.user.nowTimestamp, thruDate, days, clearThruDate as boolean)
            if (end == null) return null
            out.thruDate = end.thruDate
        }
        if (costCapUsd != null) {
            Long cap = capOf(ec, costCapUsd)
            if (cap == null) return null
            out.costCapMicros = cap
        }
        return out
    }
}
