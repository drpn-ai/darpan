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
        def g = (tenantId && userId) ? currentGrant(ec, tenantId, userId) : null
        if (g == null) return [state: NOT_GRANTED, grantId: null, fromDate: null, thruDate: null, capMicros: null, spentMicros: 0L, pauseReason: null]
        long spent = spentMicros(ec, g.grantId as String)
        Long cap = g.costCapMicros as Long
        Timestamp now = ec.user.nowTimestamp
        boolean overLimit = cap != null && spent >= cap
        String state
        if ((g.fromDate as Timestamp).after(now)) state = NOT_GRANTED
        else if (g.thruDate != null && !(g.thruDate as Timestamp).after(now)) state = EXPIRED
        else if (g.statusId == STATUS_PAUSED || overLimit) state = PAUSED
        else state = LIVE
        String reason = g.statusId == STATUS_PAUSED ? (g.pauseReason as String) : (overLimit ? REASON_COST_LIMIT : null)
        return [state: state, grantId: g.grantId, fromDate: g.fromDate, thruDate: g.thruDate, capMicros: cap, spentMicros: spent, pauseReason: reason]
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

    /** Dollars as micro-dollars, rounded half-up; a limit must be more than zero. */
    static long microsOf(BigDecimal usd) {
        if (usd == null || usd.signum() <= 0) throw new IllegalArgumentException("A limit must be more than 0 dollars.")
        return usd.multiply(new BigDecimal(1_000_000)).setScale(0, RoundingMode.HALF_UP).longValueExact()
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
}
