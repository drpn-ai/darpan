package darpan.facade.auth

import darpan.facade.common.TenantScopedFinder
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-264/270 — a person's two-factor rows and the trail they leave: the factor kinds and states, the person's live
 * factors, the per-person lock, ending factors, the TwoFactorEvent evidence and the notice emails. The bottom layer: the
 * other two-factor classes (SignInChallenges, TwoFactorPolicy, PasskeySupport, TwoFactorSupport) build on it, it on none
 * of them.
 *
 * <p>Every entity here belongs to a person, not a tenant (no DARPAN_ACTIVE_COMPANY_SCOPE filter): every read goes through
 * TenantScopedFinder.findGlobalUnscoped with a reason. Darpan never writes moqui.security.UserAuthcFactor, so the
 * framework's own second-factor paths (unlimited guesses, no replay check) have nothing to match.
 */
class TwoFactorRecords {
    private static final Logger logger = LoggerFactory.getLogger(TwoFactorRecords.class)

    static final String FACTOR = "darpan.auth.TwoFactorFactor"
    static final String RECOVERY = "darpan.auth.TwoFactorRecoveryCode"
    static final String EVENT = "darpan.auth.TwoFactorEvent"

    /** The authenticator app: at most one ACTIVE row and one live PENDING row per person. */
    static final String TYPE_TOTP = "DrpnTfTotp"
    /** A passkey (DAR-BE-270): one ACTIVE row per passkey, never PENDING, its credential in PasskeyCredential. */
    static final String TYPE_PASSKEY = "DrpnTfPkey"

    static final String PENDING = "PENDING"
    static final String ACTIVE = "ACTIVE"
    static final String ENDED = "ENDED"

    static def find(def ec, String entityName, String reason) {
        return TenantScopedFinder.findGlobalUnscoped(ec, entityName, reason)
    }

    /** For the reads that run on every request (sessionInfo, the CLI key check): logged at DEBUG, not INFO. */
    static def findPerRequest(def ec, String entityName, String reason) {
        return TenantScopedFinder.findGlobalUnscopedPerRequest(ec, entityName, reason)
    }

    /** The person's ACTIVE factors of either kind (one indexed read). */
    static List activeFactors(def ec, String userId) {
        if (!userId) return []
        return find(ec, FACTOR, "self-scoped auth: the person's active two-factor factors").condition("userId", userId)
                .condition("statusId", ACTIVE).useCache(false).list()
    }

    /** True while any factor (the app or a passkey) is ACTIVE. Runs on every request (sessionInfo, the CLI key check). */
    static boolean enabled(def ec, String userId) {
        if (!userId) return false
        return findPerRequest(ec, FACTOR, "self-scoped auth: does the person have two-factor on").condition("userId", userId)
                .condition("statusId", ACTIVE).useCache(false).count() > 0
    }

    /**
     * Locks the person's UserAccount row to the end of the transaction: every change that decides on the person's live
     * factors takes it, so what it read still holds at commit. Turning a first factor on ("no factor yet": a setup
     * challenge and a signed-in tab cannot both turn two-factor on) and removing one ("not the last": two removals cannot
     * both go). One lock per person rather than the factor rows: a new factor is an insert no row lock would stop, and
     * TwoFactorSupport.endSessions writes this row anyway. Never take it before a check that writes a failed login (that
     * write runs in its own transaction on the same row).
     */
    static void lockPerson(def ec, String userId) {
        find(ec, "moqui.security.UserAccount", "self-scoped auth: lock the person while their factors change")
                .condition("userId", userId).forUpdate(true).useCache(false).one()
    }

    /** Ends every factor the finder selects, in one statement: ENDED, now, with the reason (USER_OFF, USER_REMOVED,
     *  ADMIN_RESET or REPLACED). */
    static void endFactors(def ec, def finder, String reason) {
        finder.updateAll([statusId: ENDED, endedDate: ec.user.nowTimestamp, endedReason: reason])
    }

    /** A finder over the person's factors, for endFactors. */
    static def factorsOf(def ec, String userId, String reason) {
        return find(ec, FACTOR, reason).condition("userId", userId)
    }

    // ---- evidence and mail ----

    static void event(def ec, String userId, String type, String byUserId = null) {
        ec.entity.makeValue(EVENT).setAll([userId: userId, eventType: type, byUserId: byUserId,
                eventDate: ec.user.nowTimestamp, ip: AuthPrimitives.recordedIp(ec.user.clientIp as String)])
                .setSequencedIdPrimary().create()
    }

    /** Through PasswordResetSupport.sendEmail (async, the seam tests record). No email on file: nothing to send. */
    static void notify(def ec, String userId, String templateId, Map params) {
        def account = find(ec, "moqui.security.UserAccount", "self-scoped auth: the email a two-factor notice goes to")
                .condition("userId", userId).useCache(false).one()
        String email = normalize(account?.emailAddress)
        if (!email) return
        try {
            PasswordResetSupport.sendEmail.call(ec, templateId, email,
                    [firstName: PasswordResetSupport.firstName(account), at: ec.l10n.format(ec.user.nowTimestamp, null)] + params)
        } catch (Exception e) {
            logger.warn("Could not send two-factor notice {} to userId {}: {}", templateId, userId, e.message)
        }
    }
}
