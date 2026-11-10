package darpan.facade.auth

import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder

import java.sql.Timestamp

/**
 * DAR-BE-209. A user asks, from the app, for the CLI in their active tenant; a Darpan admin approves (making the grant
 * through CliAccess.adminCreate) or declines with an optional note the user sees. Spec: wrapper
 * docs/superpowers/specs/2026-11-10-cli-access-requests-design.md.
 *
 * The user side (facade.CliAccessFacadeServices) reads and writes only the signed-in user's rows in the active tenant,
 * with an explicit companyUserGroupId beside the tenant EntityFilter. The admin side runs after requireSuperAdmin and reads
 * across tenants. Functions that refuse add an error to ec.message and answer null.
 */
class CliAccessRequests {
    static final String REQUEST = "darpan.auth.CliAccessRequest"
    static final String PENDING = "PENDING"
    static final String APPROVED = "APPROVED"
    static final String DECLINED = "DECLINED"
    /** myAccess state for a Darpan admin, who needs no grant. */
    static final String DARPAN_ADMIN = "DARPAN_ADMIN"
    static final int MAX_TEXT = 500

    // ---------------------------------------------------------------- the signed-in user (active tenant)

    /** The user's CLI state in the active tenant, the sign-in lifetime there, whether the CLI is on anywhere, and their latest request; null with no tenant. */
    static Map myAccess(def ec) {
        String userId = TenantAccessSupport.currentUserId(ec)
        String tenantId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!userId || !tenantId) return null
        boolean admin = CliAccess.isDarpanAdmin(ec, userId)
        def grant = admin ? null : currentGrant(ec, userId, tenantId)
        String state = admin ? DARPAN_ADMIN : grant == null ? CliAccess.NOT_GRANTED : CliAccess.stateOf(grant, ec.user.nowTimestamp)
        def latest = ec.entity.find(REQUEST).condition("companyUserGroupId", tenantId).condition("userId", userId)
                .orderBy(["-requestedDate", "-requestId"]).useCache(false).list().find()
        return [tenantUserGroupId: tenantId, tenantLabel: CliAccess.tenantLabel(ec, tenantId), state: state,
                thruDate: grant?.thruDate, lifetimeDays: CliAccess.lifetimeDays(ec, userId, tenantId),
                // approve#CliLogin's own rule (a live grant in any tenant, or a Darpan admin): the Approve CLI page uses it to
                // tell a refusal for want of access from an expired code, whatever tenant is active.
                canUseCli: admin || CliAccess.cliAllowed(ec, userId),
                request: latest == null ? null : view(ec, latest)]
    }

    /** Files (or, when one is open, updates the reason of) the user's request for the CLI in the active tenant. */
    static Map request(def ec, String reason) {
        String text = reason?.trim() ?: null
        if (text != null && text.length() > MAX_TEXT) { ec.message.addError("Keep the reason to ${MAX_TEXT} characters.".toString()); return null }
        Map access = myAccess(ec)
        if (access == null) { ec.message.addError("Pick a tenant first."); return null }
        String userId = TenantAccessSupport.currentUserId(ec)
        String tenantId = access.tenantUserGroupId as String
        if (access.state == DARPAN_ADMIN) { ec.message.addError("Darpan admins use the CLI without a grant."); return null }
        if (access.state == CliAccess.LIVE) { ec.message.addError("The CLI is already on for you in ${access.tenantLabel}.".toString()); return null }
        if (!CliAccess.isMember(ec, tenantId, userId)) { ec.message.addError("You are not a member of ${access.tenantLabel}.".toString()); return null }
        def open = ec.entity.find(REQUEST).condition("companyUserGroupId", tenantId).condition("userId", userId)
                .condition("statusId", PENDING).forUpdate(true).useCache(false).list().find()
        if (open != null) {
            open.reason = text
            open.update()
            return view(ec, open)
        }
        def r = ec.entity.makeValue(REQUEST).setAll([userId: userId, companyUserGroupId: tenantId, reason: text, statusId: PENDING,
                requestedDate: ec.user.nowTimestamp])
        r.setSequencedIdPrimary()
        r.create()
        return view(ec, r)
    }

    // ---------------------------------------------------------------- Darpan admin (admin.CliAccessAdminServices)

    /** Requests across tenants, newest first; PENDING unless a status is given. */
    static List<Map> adminList(def ec, String statusId, String userId, String tenantId) {
        def find = TenantScopedFinder.findGlobalUnscoped(ec, REQUEST, "admin: Darpan staff list CLI access requests across tenants")
                .condition("statusId", statusId ?: PENDING)
        if (userId) find.condition("userId", userId)
        if (tenantId) find.condition("companyUserGroupId", tenantId)
        return find.orderBy(["-requestedDate", "-requestId"]).useCache(false).list().collect { view(ec, it) }
    }

    /** Grants the CLI as asked (end: days, thruDate, or none) and marks the request approved with the new grant. */
    static Map adminApprove(def ec, String requestId, Timestamp thruDate, Integer days) {
        def r = pending(ec, requestId)
        if (r == null) return null
        Map grant = CliAccess.adminCreate(ec, r.userId as String, r.companyUserGroupId as String, null, thruDate, days)
        if (grant == null) return null
        r.setAll([statusId: APPROVED, decidedByUserId: ec.user.userId, decidedDate: ec.user.nowTimestamp, grantId: grant.grantId])
        r.update()
        return [request: view(ec, r), grant: grant]
    }

    /** Declines the request; the note, when given, is shown to the user. */
    static Map adminDecline(def ec, String requestId, String note) {
        String text = note?.trim() ?: null
        if (text != null && text.length() > MAX_TEXT) { ec.message.addError("Keep the note to ${MAX_TEXT} characters.".toString()); return null }
        def r = pending(ec, requestId)
        if (r == null) return null
        r.setAll([statusId: DECLINED, decidedByUserId: ec.user.userId, decidedDate: ec.user.nowTimestamp, decisionNote: text])
        r.update()
        return view(ec, r)
    }

    // ---------------------------------------------------------------- helpers

    static Map view(def ec, def r) {
        def account = ec.entity.find("moqui.security.UserAccount").condition("userId", r.userId).useCache(true).one()
        return [requestId: r.requestId, userId: r.userId, username: account?.username, tenantUserGroupId: r.companyUserGroupId,
                reason: r.reason, statusId: r.statusId, requestedDate: r.requestedDate, decidedDate: r.decidedDate,
                decisionNote: r.decisionNote, grantId: r.grantId]
    }

    /** The request, locked, when it is still PENDING; else null with an error (so a second approve makes no second grant). */
    private static def pending(def ec, String requestId) {
        def r = requestId ? TenantScopedFinder.findGlobalUnscoped(ec, REQUEST, "admin: one CLI access request by id")
                .condition("requestId", requestId).forUpdate(true).useCache(false).one() : null
        if (r == null) { ec.message.addError("Request ${requestId} was not found.".toString()); return null }
        if (r.statusId != PENDING) { ec.message.addError("Request ${requestId} was already ${(r.statusId as String).toLowerCase()}.".toString()); return null }
        return r
    }

    /** The user's grant in the tenant that is not revoked, read in the user's own tenant scope. */
    private static def currentGrant(def ec, String userId, String tenantId) {
        return ec.entity.find(CliAccess.GRANT).condition("companyUserGroupId", tenantId).condition("userId", userId)
                .condition("statusId", org.moqui.entity.EntityCondition.NOT_EQUAL, CliAccess.STATUS_REVOKED)
                .orderBy(["-fromDate", "-grantId"]).useCache(false).list().find()
    }
}
