package darpan.facade.auth

import darpan.admin.AdminAuditSupport
import org.moqui.entity.EntityCondition
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Timestamp

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-264 — two-factor sign-in: a person's factors and what they can do with them. The authenticator app (enrolment,
 * the code check and its lock), recovery codes, the sign-in step after a right password, the fresh proof some actions ask
 * for, turning factors off, admin reset, and ending a person's sign-ins. Spec: wrapper
 * docs/superpowers/specs/2026-11-13-dar-be-264-two-factor-sign-in-design.md.
 *
 * <p>DAR-BE-270: a person's factors are the authenticator app (one ACTIVE DrpnTfTotp row, activeApp) and any number of
 * passkeys (PasskeySupport). Two-factor is on while any factor is ACTIVE; recovery codes belong to the person, made with
 * the first factor and deleted with the last; every fresh proof is a code or a passkey assertion.
 *
 * <p>Built on TwoFactorRecords (rows, lock, evidence), SignInChallenges, TwoFactorPolicy and PasskeySupport; none of them
 * calls back here. Every code check runs under a row lock in its own transaction (as PasswordResetSupport.checkCodeLocked
 * does): parallel guesses are each counted before the next is compared, and the refusal that follows rolls back the
 * caller's transaction but not the count. The secret and the codes never reach a log line, an error message or any reply
 * but the one that makes them.
 */
class TwoFactorSupport {
    private static final Logger logger = LoggerFactory.getLogger(TwoFactorSupport.class)

    /** What a person can answer a sign-in challenge with (login#Session's twoFactorMethods). */
    static final String METHOD_APP = "app"
    static final String METHOD_PASSKEY = "passkey"

    /** A PENDING app row this old is ignored: setup starts again. */
    static final int PENDING_LIFETIME_SECONDS = 900
    static final int LOCK_AFTER_FAILURES = 20
    static final long LOCK_WINDOW_MILLIS = 24L * 3_600_000L
    static final int RECOVERY_CODE_COUNT = 10
    /** A refused code waits at least this long. Above the login floor (25 ms): a code check writes rows in its own
     *  transaction, so the floor must clear that, as PasswordResetSupport's does. */
    static final long CODE_REFUSAL_FLOOR_MILLIS = 250L

    static final String TEMPLATE_RECOVERY_USED = "DARPAN_2FA_RECOVERY_USED"
    static final String TEMPLATE_CODES_LOCKED = "DARPAN_2FA_CODES_LOCKED"
    static final String TEMPLATE_RESET = "DARPAN_2FA_RESET"
    static final String TEMPLATE_APP_ADDED = "DARPAN_2FA_APP_ADDED"

    /** 32 characters, so 16 of them are exactly 80 bits; no I, O, 0 or 1 to misread. */
    static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    static final int RECOVERY_CODE_LENGTH = 16

    private static final SecureRandom random = new SecureRandom()

    /** The clock the code check reads; tests move it to reach the next 30-second step. Nothing else reads it. */
    static Closure<Long> codeClock = { System.currentTimeMillis() }

    private static def find(def ec, String entityName, String reason) {
        return TwoFactorRecords.find(ec, entityName, reason)
    }

    // ---- the person's state ----

    /** The person's authenticator app (the ACTIVE DrpnTfTotp row), or null. */
    static def activeApp(def ec, String userId, boolean forUpdate = false) {
        if (!userId) return null
        return find(ec, TwoFactorRecords.FACTOR, "self-scoped auth: the person's active authenticator app").condition("userId", userId)
                .condition("factorTypeEnumId", TwoFactorRecords.TYPE_TOTP).condition("statusId", TwoFactorRecords.ACTIVE)
                .forUpdate(forUpdate).useCache(false).list()?.find { true }
    }

    /** The live PENDING app row, locked: made by begin#TwoFactorSetup within PENDING_LIFETIME_SECONDS. */
    private static def pendingApp(def ec, String userId) {
        Timestamp since = new Timestamp(ec.user.nowTimestamp.time - PENDING_LIFETIME_SECONDS * 1000L)
        return find(ec, TwoFactorRecords.FACTOR, "self-scoped auth: the person's two-factor setup in progress").condition("userId", userId)
                .condition("factorTypeEnumId", TwoFactorRecords.TYPE_TOTP).condition("statusId", TwoFactorRecords.PENDING)
                .condition("createdDate", EntityCondition.ComparisonOperator.GREATER_THAN_EQUAL_TO, since)
                .orderBy("-createdDate").forUpdate(true).useCache(false).list()?.find { true }
    }

    /** The person's unused recovery codes (one set exists at a time, made with their first factor). */
    static int recoveryCodesLeft(def ec, String userId) {
        if (!userId) return 0
        return (int) find(ec, TwoFactorRecords.RECOVERY, "self-scoped auth: count the person's unused recovery codes")
                .condition("userId", userId).condition("usedDate", null).useCache(false).count()
    }

    /** get#TwoFactorStatus: never the secret, never a key or credential id. */
    static Map<String, Object> status(def ec, String userId) {
        List factors = TwoFactorRecords.activeFactors(ec, userId)
        def app = factors.find { it.factorTypeEnumId == TwoFactorRecords.TYPE_TOTP }
        Timestamp from = TwoFactorPolicy.requiredFrom(ec, userId)
        return [
                enabled          : !factors.isEmpty(),
                confirmedDate    : firstConfirmed(factors),
                recoveryCodesLeft: factors ? recoveryCodesLeft(ec, userId) : 0,
                required         : from != null && !from.after(ec.user.nowTimestamp),
                requiredFromDate : from,
                codesLocked      : app?.codesLockedDate != null,
                appEnabled       : app != null,
                passkeys         : PasskeySupport.describe(ec, factors),
                passkeysAvailable: PasskeySupport.available(ec),
        ]
    }

    /** admin get#UserDetail's two-factor fields: two reads (the person's factors and their policy date), never a secret,
     *  key or credential id. */
    static Map<String, Object> adminDetail(def ec, String userId) {
        List factors = TwoFactorRecords.activeFactors(ec, userId)
        Timestamp from = TwoFactorPolicy.requiredFrom(ec, userId)
        return [twoFactor                : TwoFactorPolicy.adminState(!factors.isEmpty(), from, ec.user.nowTimestamp),
                twoFactorConfirmedDate   : firstConfirmed(factors),
                twoFactorRequiredFromDate: from,
                twoFactorAppEnabled      : factors.any { it.factorTypeEnumId == TwoFactorRecords.TYPE_TOTP },
                twoFactorPasskeyCount    : factors.count { it.factorTypeEnumId == TwoFactorRecords.TYPE_PASSKEY }]
    }

    private static Timestamp firstConfirmed(List factors) {
        return (Timestamp) factors*.confirmedDate.findAll { it != null }.min()
    }

    /** The two fields sessionInfo carries: whether it is on, and by when it must be (only while it is not). */
    static Map<String, Object> sessionFields(def ec, String userId) {
        boolean on = TwoFactorRecords.enabled(ec, userId)
        return [twoFactorEnabled: on, twoFactorDueDate: on ? null : TwoFactorPolicy.requiredFrom(ec, userId)]
    }

    // ---- the sign-in step ----

    /**
     * After the password matched: null when a session may be issued now (optional, or in the grace period), else the
     * challenge [purpose, challengeToken, expiresInSeconds, methods]: enter a code or use a passkey (an ACTIVE factor;
     * methods "app" and/or "passkey"), or set up first (required, past the date, no factor; methods empty).
     */
    static Map<String, Object> signInStep(def ec, String userId) {
        List factors = TwoFactorRecords.activeFactors(ec, userId)
        List<String> methods = []
        if (factors.any { it.factorTypeEnumId == TwoFactorRecords.TYPE_TOTP }) methods << METHOD_APP
        if (factors.any { it.factorTypeEnumId == TwoFactorRecords.TYPE_PASSKEY } && PasskeySupport.available(ec)) methods << METHOD_PASSKEY
        String purpose
        if (factors) purpose = SignInChallenges.PURPOSE_CODE
        else if (TwoFactorPolicy.isRequiredNow(ec, userId)) purpose = SignInChallenges.PURPOSE_SETUP
        else return null
        return [purpose : purpose, challengeToken: SignInChallenges.create(ec, userId, purpose),
                expiresInSeconds: SignInChallenges.LIFETIME_SECONDS, methods: methods]
    }

    /**
     * verify#SignInCode's check: the sign-in challenge is claimed and the code, recovery code or passkey assertion checked
     * in one locked transaction of its own (SignInChallenges.claim). A passkey assertion must sign the challenge
     * begin#PasskeySignIn last put on the row, and any attempt uses that challenge up. Returns [ok, userId,
     * recoveryCodesLeft] or [ok: false, challengeEnded].
     */
    static Map<String, Object> checkSignInChallenge(def ec, Object token, Object code, Object recoveryCode, Object passkeyAssertion) {
        return SignInChallenges.claim(ec, token, SignInChallenges.PURPOSE_CODE) { def challenge ->
            String userId = (String) challenge.userId
            if (normalize(passkeyAssertion)) {
                String expected = (String) challenge.webauthnChallenge
                challenge.webauthnChallenge = null
                return [ok: expected && PasskeySupport.verifyAssertion(ec, userId, passkeyAssertion, expected)]
            }
            if (normalize(recoveryCode)) return consumeRecoveryCode(ec, userId, recoveryCode)
            return [ok: consumeTotp(ec, userId, code)]
        }
    }

    // ---- code checks (call inside a transaction; they lock the app row) ----

    /** A current authenticator code for the person's ACTIVE app: later than the last one used, not locked. A wrong one is
     *  counted (see recordFailure). */
    static boolean consumeTotp(def ec, String userId, Object code) {
        def app = activeApp(ec, userId, true)
        if (app == null) return false
        if (app.codesLockedDate != null) {
            TwoFactorRecords.event(ec, userId, "CODE_FAILED")
            return false
        }
        Long step = TotpSupport.matchStep((String) app.secret, normalizeTotp(code), codeClock.call(), app.lastUsedTimeStep as Long)
        if (step == null) {
            recordFailure(ec, userId, app)
            return false
        }
        app.lastUsedTimeStep = step
        app.lastUsedDate = ec.user.nowTimestamp
        app.update()
        TwoFactorRecords.event(ec, userId, "CODE_OK")
        return true
    }

    /** A recovery code: one-time, refused while codes are locked (the lock lives on the app's row; a person with
     *  passkeys only has no lock: 80-bit codes, 5 per challenge and the failed-login count bound guessing). Returns
     *  [ok, recoveryCodesLeft]. Emails the person. */
    static Map<String, Object> consumeRecoveryCode(def ec, String userId, Object recoveryCode) {
        if (!TwoFactorRecords.enabled(ec, userId)) return [ok: false]
        def app = activeApp(ec, userId, true)
        if (app?.codesLockedDate != null) {
            TwoFactorRecords.event(ec, userId, "CODE_FAILED")
            return [ok: false]
        }
        String value = normalizeRecoveryCode(recoveryCode)
        List rows = find(ec, TwoFactorRecords.RECOVERY, "self-scoped auth: the person's unused recovery codes").condition("userId", userId)
                .condition("usedDate", null).forUpdate(true).useCache(false).list()
        def match = value ? rows.find { hashMatches(value, (String) it.codeSalt, (String) it.codeHash) } : null
        if (match == null) {
            recordFailure(ec, userId, app)
            return [ok: false]
        }
        match.usedDate = ec.user.nowTimestamp
        match.usedIp = AuthPrimitives.recordedIp(ec.user.clientIp as String)
        match.update()
        int left = rows.size() - 1
        TwoFactorRecords.event(ec, userId, "RECOVERY_USED")
        TwoFactorRecords.notify(ec, userId, TEMPLATE_RECOVERY_USED, [recoveryCodesLeft: left])
        return [ok: true, recoveryCodesLeft: left]
    }

    /** A wrong code: counts as a failed login (the framework's own lockout throttles it like a password guess), and
     *  LOCK_AFTER_FAILURES within LOCK_WINDOW_MILLIS lock codes until a password reset or an admin reset, with an email. */
    private static void recordFailure(def ec, String userId, def app) {
        TwoFactorRecords.event(ec, userId, "CODE_FAILED")
        if (app != null) {
            Timestamp now = ec.user.nowTimestamp
            Timestamp windowStart = (Timestamp) app.failedWindowStart
            if (windowStart == null || now.time - windowStart.time >= LOCK_WINDOW_MILLIS) {
                app.failedWindowStart = now
                app.failedCodeCount = 0
            }
            int failures = ((app.failedCodeCount ?: 0) as int) + 1
            app.failedCodeCount = failures
            boolean locking = failures >= LOCK_AFTER_FAILURES && app.codesLockedDate == null
            if (locking) app.codesLockedDate = now
            app.update()
            if (locking) {
                TwoFactorRecords.event(ec, userId, "CODES_LOCKED")
                logger.warn("Two-factor codes locked for userId {} after {} wrong codes in 24 hours", userId, failures)
                TwoFactorRecords.notify(ec, userId, TEMPLATE_CODES_LOCKED, [:])
            }
        }
        AuthPrimitives.countFailedLogin(ec, userId)
    }

    // ---- the fresh proof ----

    /**
     * A fresh proof from a signed-in person with two-factor on, for actions that must not ride on a hijacked tab (turning
     * it off, new recovery codes, adding or removing a factor, an admin reset, key rotation, a policy change): a current
     * code from the app, or (DAR-BE-270) a passkey assertion over a begin#PasskeyProof challenge. Null when it passes, else
     * the refusal: NOT_ENROLLED for a person with no factor (the caller says what to do about it). Checked in its own
     * transaction, so the caller's refusal does not roll back the count or the used challenge.
     */
    static TwoFactorRefusal freshProofProblem(def ec, Object code, Object passkeyAssertion) {
        String userId = normalize(ec.user.userId)
        if (!userId) return TwoFactorRefusal.SIGN_IN_FIRST
        List factors = TwoFactorRecords.activeFactors(ec, userId)
        return factors ? proofProblem(ec, userId, factors, code, passkeyAssertion) : TwoFactorRefusal.NOT_ENROLLED
    }

    /** freshProofProblem for actions open to a person without two-factor too (approving a CLI sign-in, a /transfer export
     *  or handoff): null for a person with no factor. */
    static TwoFactorRefusal freshProofProblemIfEnrolled(def ec, Object code, Object passkeyAssertion) {
        String userId = normalize(ec.user.userId)
        if (!userId) return TwoFactorRefusal.SIGN_IN_FIRST
        List factors = TwoFactorRecords.activeFactors(ec, userId)
        return factors ? proofProblem(ec, userId, factors, code, passkeyAssertion) : null
    }

    private static TwoFactorRefusal proofProblem(def ec, String userId, List factors, Object code, Object passkeyAssertion) {
        long started = System.nanoTime()
        boolean hasApp = factors.any { it.factorTypeEnumId == TwoFactorRecords.TYPE_TOTP }
        if (normalize(passkeyAssertion)) {
            boolean ok = (boolean) ec.transaction.runRequireNew(null, "Could not check a passkey", {
                PasskeySupport.verifyAssertion(ec, userId, passkeyAssertion, null)
            })
            if (ok) return null
            holdToFloor(started)
            return TwoFactorRefusal.PASSKEY
        }
        if (!normalizeTotp(code)) return hasApp && factors.size() == 1 ? TwoFactorRefusal.CODE_NEEDED : TwoFactorRefusal.PROOF_NEEDED
        if (!hasApp) return TwoFactorRefusal.PROOF_NEEDED
        boolean ok = (boolean) ec.transaction.runRequireNew(null, "Could not check a two-factor code", {
            consumeTotp(ec, userId, code)
        })
        if (ok) return null
        holdToFloor(started)
        return TwoFactorRefusal.CODE
    }

    /** Holds a refused code or passkey to CODE_REFUSAL_FLOOR_MILLIS. */
    static void holdToFloor(long startedNanos) {
        AuthPrimitives.holdToFloor(startedNanos, CODE_REFUSAL_FLOOR_MILLIS, "A two-factor refusal")
    }

    // ---- enrolment ----

    /** A new PENDING app row (any older pending one ends): the secret, the otpauth URI and a QR of it. The only reply
     *  that ever carries the secret. */
    static Map<String, Object> beginSetup(def ec, String userId) {
        def account = find(ec, "moqui.security.UserAccount", "self-scoped auth: the account setting up two-factor")
                .condition("userId", userId).useCache(false).one()
        TwoFactorRecords.endFactors(ec, TwoFactorRecords.factorsOf(ec, userId, "self-scoped auth: end the person's older two-factor setups")
                .condition("factorTypeEnumId", TwoFactorRecords.TYPE_TOTP).condition("statusId", TwoFactorRecords.PENDING), "REPLACED")
        String secret = TotpSupport.newSecret()
        ec.entity.makeValue(TwoFactorRecords.FACTOR).setAll([userId: userId, factorTypeEnumId: TwoFactorRecords.TYPE_TOTP,
                statusId: TwoFactorRecords.PENDING, secret: secret, createdDate: ec.user.nowTimestamp]).setSequencedIdPrimary().create()
        TwoFactorRecords.event(ec, userId, "SETUP_STARTED")
        String uri = TotpSupport.otpauthUri(TotpSupport.issuer(), (String) account?.username ?: userId, secret)
        return [secret: secret, otpauthUri: uri, qrDataUri: TotpSupport.qrDataUri(uri), expiresInSeconds: PENDING_LIFETIME_SECONDS]
    }

    /**
     * The first right code from the new app, under the person lock: the pending row becomes ACTIVE (an older ACTIVE app
     * ends, REPLACED), the person is emailed, and factorConfirmed ends their sign-ins. Returns [ok, recoveryCodes (a first
     * factor only)], or [ok: false, expired] (no live setup) / [ok: false].
     */
    static Map<String, Object> confirmSetup(def ec, String userId, Object code) {
        TwoFactorRecords.lockPerson(ec, userId)
        def pending = pendingApp(ec, userId)
        if (pending == null) return [ok: false, expired: true]
        Long step = TotpSupport.matchStep((String) pending.secret, normalizeTotp(code), codeClock.call(), null)
        if (step == null) return [ok: false]
        boolean first = !TwoFactorRecords.enabled(ec, userId)
        TwoFactorRecords.endFactors(ec, TwoFactorRecords.factorsOf(ec, userId, "self-scoped auth: end the person's replaced authenticator app")
                .condition("factorTypeEnumId", TwoFactorRecords.TYPE_TOTP).condition("statusId", TwoFactorRecords.ACTIVE), "REPLACED")
        Timestamp now = ec.user.nowTimestamp
        pending.statusId = TwoFactorRecords.ACTIVE
        pending.confirmedDate = now
        pending.lastUsedTimeStep = step
        pending.lastUsedDate = now
        pending.update()
        // A factor nobody was told about is the account-takeover step: say so, as a new passkey does (review M2).
        TwoFactorRecords.notify(ec, userId, TEMPLATE_APP_ADDED, [:])
        return [ok: true, recoveryCodes: factorConfirmed(ec, userId, first)]
    }

    /**
     * A factor went on: every login key of the person ends, CLI keys included (the caller hands out a fresh one, see
     * TwoFactorFacadeSupport). Recovery codes belong to the person: 10 are made, and returned once, only for their first
     * factor; an app added beside a passkey, or replacing the old app, keeps the codes they have (review QM3). An added
     * passkey changes no session and does not come here.
     */
    static List<String> factorConfirmed(def ec, String userId, boolean first) {
        List<String> codes = first ? replaceRecoveryCodes(ec, userId) : null
        endSessions(ec, userId)
        TwoFactorRecords.event(ec, userId, "SETUP_CONFIRMED")
        return codes
    }

    /** create#RecoveryCodes: needs a fresh proof; replaces every old code. [recoveryCodes] or [refusal]. */
    static Map<String, Object> renewRecoveryCodes(def ec, Object code, Object passkeyAssertion) {
        TwoFactorRefusal problem = freshProofProblem(ec, code, passkeyAssertion)
        if (problem) return [refusal: problem]
        String userId = normalize(ec.user.userId)
        List<String> codes = replaceRecoveryCodes(ec, userId)
        TwoFactorRecords.event(ec, userId, "CODES_RENEWED")
        return [recoveryCodes: codes]
    }

    // ---- turning factors off ----

    /**
     * disable#TwoFactor: every factor off (an optional person), with a fresh proof; every sign-in ends (the caller hands
     * this tab a fresh key). appOnly (DAR-BE-270): remove just the authenticator app while a passkey remains, allowed for
     * a required person too since they stay protected, ending no sign-in; with no passkey left it is the full turn-off.
     * Returns [twoFactorEnabled] or [refusal].
     */
    static Map<String, Object> turnOff(def ec, Object code, Object passkeyAssertion, boolean appOnly) {
        String userId = normalize(ec.user.userId)
        // First a refusal that spends no proof; the same rules are decided again after it, under the person lock.
        TwoFactorRefusal refusal = turnOffRefusal(ec, userId, appOnly)
        if (refusal) return [refusal: refusal]
        refusal = freshProofProblem(ec, code, passkeyAssertion)
        if (refusal) return [refusal: refusal]
        // Another removal may have committed while the proof was checked (two tabs at once): with the person locked, what
        // is live now decides, so a required person can never be left with no factor (review L5).
        TwoFactorRecords.lockPerson(ec, userId)
        refusal = turnOffRefusal(ec, userId, appOnly)
        if (refusal) return [refusal: refusal]
        if (appOnly && TwoFactorRecords.activeFactors(ec, userId).any { it.factorTypeEnumId == TwoFactorRecords.TYPE_PASSKEY }) {
            TwoFactorRecords.endFactors(ec, TwoFactorRecords.factorsOf(ec, userId, "self-scoped auth: remove the person's authenticator app")
                    .condition("factorTypeEnumId", TwoFactorRecords.TYPE_TOTP).condition("statusId", TwoFactorRecords.ACTIVE), "USER_REMOVED")
            TwoFactorRecords.event(ec, userId, "APP_REMOVED")
            return [twoFactorEnabled: true]
        }
        turnOffAll(ec, userId)
        return [twoFactorEnabled: false]
    }

    /** Why disable#TwoFactor cannot go ahead for what is live now, or null. appOnly with a passkey staying removes just
     *  the app (allowed when required); otherwise it is the full turn-off, refused when required. */
    private static TwoFactorRefusal turnOffRefusal(def ec, String userId, boolean appOnly) {
        List factors = TwoFactorRecords.activeFactors(ec, userId)
        if (!factors) return TwoFactorRefusal.NOT_ENROLLED
        if (appOnly && factors.any { it.factorTypeEnumId == TwoFactorRecords.TYPE_PASSKEY }) {
            return factors.any { it.factorTypeEnumId == TwoFactorRecords.TYPE_TOTP } ? null : TwoFactorRefusal.APP_NOT_ON
        }
        return TwoFactorPolicy.isRequiredNow(ec, userId) ? TwoFactorRefusal.REQUIRED_CANNOT_TURN_OFF : null
    }

    /**
     * delete#Passkey (DAR-BE-270): the person's own live passkey, with a fresh proof (an assertion from the passkey being
     * removed is fine). Removing the last factor is refused when their role requires two-factor, and for an optional
     * person turns two-factor off as disable#TwoFactor does. Returns [twoFactorEnabled] or [refusal].
     */
    static Map<String, Object> removePasskey(def ec, Object passkeyId, Object code, Object passkeyAssertion) {
        String userId = normalize(ec.user.userId)
        TwoFactorRefusal refusal = removePasskeyRefusal(ec, userId, passkeyId)
        if (refusal) return [refusal: refusal]
        refusal = freshProofProblem(ec, code, passkeyAssertion)
        if (refusal) return [refusal: refusal]
        // Decided again under the person lock after the proof: the proof may have been this passkey, and another removal
        // may have committed meanwhile (two tabs at once), which would otherwise leave a required person with none (review L5).
        TwoFactorRecords.lockPerson(ec, userId)
        refusal = removePasskeyRefusal(ec, userId, passkeyId)
        if (refusal) return [refusal: refusal]
        if (TwoFactorRecords.activeFactors(ec, userId).size() == 1) {
            turnOffAll(ec, userId)
            return [twoFactorEnabled: false]
        }
        TwoFactorRecords.endFactors(ec, TwoFactorRecords.factorsOf(ec, userId, "self-scoped auth: the passkey being removed")
                .condition("factorId", normalize(passkeyId)), "USER_REMOVED")
        TwoFactorRecords.event(ec, userId, "PASSKEY_REMOVED")
        return [twoFactorEnabled: true]
    }

    private static TwoFactorRefusal removePasskeyRefusal(def ec, String userId, Object passkeyId) {
        if (PasskeySupport.ownLive(ec, userId, passkeyId) == null) return TwoFactorRefusal.PASSKEY_NOT_FOUND
        boolean last = TwoFactorRecords.activeFactors(ec, userId).size() == 1
        return last && TwoFactorPolicy.isRequiredNow(ec, userId) ? TwoFactorRefusal.LAST_FACTOR_REQUIRED : null
    }

    /** Every factor ends (USER_OFF), recovery codes go, and every sign-in of the person ends, this request's key too: the
     *  caller hands this tab a fresh one (TwoFactorFacadeSupport.turnOffTwoFactor / deletePasskey). */
    private static void turnOffAll(def ec, String userId) {
        endEveryFactor(ec, userId, "USER_OFF")
        endSessions(ec, userId)
        TwoFactorRecords.event(ec, userId, "TURNED_OFF")
    }

    /**
     * admin.UserAdminServices.reset#TwoFactor: the person's factors end (ADMIN_RESET), their recovery codes go, every
     * session and CLI key ends, and they set up again at their next sign-in when required. The caller checks super admin,
     * not one's own account and the reason; this checks the acting admin's own fresh proof (NOT_ENROLLED when they have no
     * factor), and writes the evidence: AdminAuditLog, a TwoFactorEvent naming the admin, and an email to the person.
     */
    static TwoFactorRefusal adminReset(def ec, String userId, String reason, Object adminCode, Object adminPasskeyAssertion) {
        TwoFactorRefusal problem = freshProofProblem(ec, adminCode, adminPasskeyAssertion)
        if (problem) return problem
        endEveryFactor(ec, userId, "ADMIN_RESET")
        endSessions(ec, userId)
        TwoFactorRecords.event(ec, userId, "ADMIN_RESET", normalize(ec.user.userId))
        AdminAuditSupport.record(ec, "admin.UserAdminServices.reset#TwoFactor", "User", userId, "Two-factor reset: ${reason}".toString())
        TwoFactorRecords.notify(ec, userId, TEMPLATE_RESET, [:])
        return null
    }

    /** After a password reset: the code lock clears (the password an attacker held is dead); the factor stays. */
    static void clearCodeLock(def ec, String userId) {
        def app = activeApp(ec, userId, true)
        if (app == null || (app.codesLockedDate == null && !app.failedCodeCount)) return
        boolean wasLocked = app.codesLockedDate != null
        app.codesLockedDate = null
        app.failedCodeCount = 0
        app.failedWindowStart = null
        app.update()
        if (wasLocked) TwoFactorRecords.event(ec, userId, "LOCK_CLEARED")
    }

    /** Every live or pending factor of the person (the app and every passkey) ends, and their recovery codes go. */
    private static void endEveryFactor(def ec, String userId, String reason) {
        TwoFactorRecords.endFactors(ec, TwoFactorRecords.factorsOf(ec, userId, "self-scoped auth: end the person's two-factor factors")
                .condition("statusId", EntityCondition.IN, [TwoFactorRecords.ACTIVE, TwoFactorRecords.PENDING]), reason)
        find(ec, TwoFactorRecords.RECOVERY, "self-scoped auth: delete the person's recovery codes").condition("userId", userId).deleteAll()
    }

    // ---- sessions and keys ----

    /**
     * Ends every sign-in of the person, in the caller's transaction: UserAccount.sessionsEndedDate moves to now (never
     * back), their login keys (web and CLI) are deleted, their CLI sessions marked revoked, and a CLI sign-in they approved
     * but the CLI has not yet collected is denied (it would mint a key after this). The stamp is the rule; the deletes are
     * housekeeping: a key or CLI session issued before the stamp is refused on every request
     * (AuthSessionSupport.predatesSessionsEnd), so one minted while this ran, which the delete missed, is refused too, and
     * so is a web session holding no key. A caller that keeps the person signed in on this request issues a fresh key
     * afterwards (AuthFacadeSupport.issueSignedInSession).
     */
    static void endSessions(def ec, String userId) {
        Timestamp endedAt = ec.user.nowTimestamp
        def account = find(ec, "moqui.security.UserAccount", "self-scoped auth: stamp when the person's sign-ins were ended")
                .condition("userId", userId).useCache(false).one()
        Timestamp previous = account?.sessionsEndedDate as Timestamp
        if (account != null && (previous == null || previous.before(endedAt))) {
            account.sessionsEndedDate = endedAt
            account.update()
        }
        find(ec, "moqui.security.UserLoginKey", "self-scoped auth: end the person's login keys after a two-factor change")
                .condition("userId", userId).deleteAll()
        find(ec, "darpan.auth.CliSession", "self-scoped auth: revoke the person's CLI sessions after a two-factor change")
                .condition("userId", userId).condition("revokedDate", null).updateAll([revokedDate: endedAt])
        find(ec, "darpan.auth.CliDeviceCode", "self-scoped auth: deny the person's approved, uncollected CLI sign-ins after a two-factor change")
                .condition("approvedByUserId", userId).condition("statusId", "APPROVED").updateAll([statusId: "DENIED"])
    }

    // ---- recovery codes ----

    /** Every old code goes; RECOVERY_CODE_COUNT new ones, each stored as a salted SHA-256 (80 random bits leave nothing for
     *  a slow hash to protect), returned once as xxxx-xxxx-xxxx-xxxx. */
    private static List<String> replaceRecoveryCodes(def ec, String userId) {
        find(ec, TwoFactorRecords.RECOVERY, "self-scoped auth: replace the person's recovery codes").condition("userId", userId).deleteAll()
        Timestamp now = ec.user.nowTimestamp
        return (1..RECOVERY_CODE_COUNT).collect {
            String value = newRecoveryCode()
            byte[] saltBytes = new byte[16]
            random.nextBytes(saltBytes)
            String salt = saltBytes.encodeHex().toString()
            ec.entity.makeValue(TwoFactorRecords.RECOVERY).setAll([userId: userId, codeHash: recoveryHash(value, salt),
                    codeSalt: salt, createdDate: now]).setSequencedIdPrimary().create()
            formatRecoveryCode(value)
        }
    }

    /** Digits only, so a code pasted with a space or a newline still matches. */
    static String normalizeTotp(Object code) {
        return code == null ? "" : code.toString().replaceAll(/\D/, "")
    }

    /** Upper case, no spaces or dashes: what the person typed, as hashed. Empty when it cannot be a recovery code. */
    static String normalizeRecoveryCode(Object code) {
        String value = code == null ? "" : code.toString().toUpperCase().replaceAll(/[^A-Z0-9]/, "")
        return value.length() == RECOVERY_CODE_LENGTH ? value : ""
    }

    static String newRecoveryCode() {
        StringBuilder value = new StringBuilder(RECOVERY_CODE_LENGTH)
        RECOVERY_CODE_LENGTH.times { value.append(RECOVERY_ALPHABET.charAt(random.nextInt(RECOVERY_ALPHABET.length()))) }
        return value.toString()
    }

    /** xxxx-xxxx-xxxx-xxxx, lower case, as shown once. */
    static String formatRecoveryCode(String value) {
        return value.toLowerCase().toList().collate(4).collect { it.join("") }.join("-")
    }

    static String recoveryHash(String value, String salt) {
        return AuthPrimitives.sha256Hex(salt + ":" + value)
    }

    private static boolean hashMatches(String value, String salt, String expected) {
        if (!salt || !expected) return false
        return MessageDigest.isEqual(recoveryHash(value, salt).getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))
    }
}
