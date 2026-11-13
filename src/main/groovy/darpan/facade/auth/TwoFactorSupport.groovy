package darpan.facade.auth

import darpan.admin.AdminAuditSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import org.apache.commons.validator.routines.InetAddressValidator
import org.moqui.entity.EntityCondition
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Timestamp

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-264 — two-factor sign-in (TOTP): enrolment, the sign-in challenge, recovery codes, the per-role policy, admin
 * reset and the fresh code some actions ask for. Spec: wrapper docs/superpowers/specs/2026-11-13-dar-be-264-two-factor-sign-in-design.md.
 *
 * <p>DAR-BE-270: a person's factors are the authenticator app (the one DrpnTfTotp row, "activeFactor") and any number of
 * passkeys (DrpnTfPkey rows, PasskeySupport). Two-factor is on while any factor is ACTIVE ("enabled"); recovery codes
 * belong to the person, made with the first factor and deleted with the last; every fresh-proof action takes a code or a
 * passkey assertion (freshProofProblem).
 *
 * <p>Darpan keeps its own factor rows (darpan.auth.TwoFactorFactor) and never writes moqui.security.UserAuthcFactor, so
 * the framework's second-factor paths (unlimited guesses, no replay check, unconfirmed factors counting) have nothing to
 * match. The framework's password-only routes are closed by PasswordOnlyRouteFence instead.
 *
 * <p>Every code check runs under a row lock in its own transaction (as PasswordResetSupport.checkCodeLocked does): parallel
 * guesses are each counted before the next is compared, and the refusal that follows rolls back the caller's transaction
 * but not the count. The secret and the codes never reach a log line, an error message or any reply but the one that
 * makes them.
 */
class TwoFactorSupport {
    private static final Logger logger = LoggerFactory.getLogger(TwoFactorSupport.class)

    static final String FACTOR = "darpan.auth.TwoFactorFactor"
    static final String RECOVERY = "darpan.auth.TwoFactorRecoveryCode"
    static final String CHALLENGE = "darpan.auth.SignInChallenge"
    static final String POLICY = "darpan.auth.TwoFactorRolePolicy"
    static final String EVENT = "darpan.auth.TwoFactorEvent"

    static final String TYPE_TOTP = "DrpnTfTotp"
    static final String PURPOSE_CODE = "DrpnScCode"
    static final String PURPOSE_SETUP = "DrpnScSetup"

    static final String PENDING = "PENDING"
    static final String ACTIVE = "ACTIVE"
    static final String ENDED = "ENDED"

    /** The roles the policy covers: three Darpan-wide (UserGroupMember) and tenant admin in any tenant. */
    static final List<String> DARPAN_ROLE_GROUP_IDS = [TenantAccessSupport.ADMIN_USER_GROUP_ID,
            TenantAccessSupport.DARPAN_SUPER_ADMIN_GROUP_ID, TenantAccessSupport.DARPAN_ADMIN_GROUP_ID].asImmutable()
    static final String TENANT_ADMIN_GROUP_ID = TenantAccessSupport.DARPAN_TENANT_ADMIN_GROUP_ID
    static final List<String> POLICY_GROUP_IDS = (DARPAN_ROLE_GROUP_IDS + [TENANT_ADMIN_GROUP_ID]).asImmutable()

    static final int CHALLENGE_LIFETIME_SECONDS = 300
    static final int PENDING_LIFETIME_SECONDS = 900
    static final int MAX_CHALLENGE_FAILURES = 5
    static final int LOCK_AFTER_FAILURES = 20
    static final long LOCK_WINDOW_MILLIS = 24L * 3_600_000L
    static final int RECOVERY_CODE_COUNT = 10
    static final int RECOVERY_CODES_LOW = 3
    /** A refused code waits at least this long. Above the login floor (25 ms): a code check writes rows in its own
     *  transaction, so the floor must clear that, as PasswordResetSupport's does. */
    static final long CODE_REFUSAL_FLOOR_MILLIS = 250L
    /** The rule of at least two enrolled super admins before super-admin enforcement can be switched on (break-glass). */
    static final int MIN_ENROLLED_SUPER_ADMINS = 2

    static final String CODE_REFUSAL = "That code didn't work."
    static final String CODE_NEEDED = "Enter the code from your authenticator app."
    static final String PROOF_NEEDED = "Confirm with your passkey or a code from your authenticator app."
    static final String LAST_FACTOR_REQUIRED = "Two-factor is required for your role, so your last way to sign in cannot be removed."
    static final String REQUIRED_CANNOT_TURN_OFF = "Two-factor is required for your role, so it cannot be turned off."
    static final String SETUP_EXPIRED = "Setup has expired. Start again."
    static final String NOT_ENROLLED = "Two-factor is not on for this account."
    static final String SETUP_FIRST = "Two-factor is required for this account. Set it up in the Darpan app first."

    static final String TEMPLATE_RECOVERY_USED = "DARPAN_2FA_RECOVERY_USED"
    static final String TEMPLATE_CODES_LOCKED = "DARPAN_2FA_CODES_LOCKED"
    static final String TEMPLATE_RESET = "DARPAN_2FA_RESET"

    /** 32 characters, so 16 of them are exactly 80 bits; no I, O, 0 or 1 to misread. */
    static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    static final int RECOVERY_CODE_LENGTH = 16

    private static final SecureRandom random = new SecureRandom()

    /** The clock the code check reads; tests move it to reach the next 30-second step. Nothing else reads it. */
    static Closure<Long> codeClock = { System.currentTimeMillis() }

    // ---- reads ----

    private static def find(def ec, String entityName, String reason) {
        return TenantScopedFinder.findGlobalUnscoped(ec, entityName, reason)
    }

    /** The person's authenticator app (the ACTIVE DrpnTfTotp row), or null. Passkeys are PasskeySupport's. */
    static def activeFactor(def ec, String userId, boolean forUpdate = false) {
        if (!userId) return null
        def finder = find(ec, FACTOR, "self-scoped auth: the person's active authenticator app").condition("userId", userId)
                .condition("factorTypeEnumId", TYPE_TOTP).condition("statusId", ACTIVE)
        if (forUpdate) finder = finder.forUpdate(true)
        return finder.useCache(false).list()?.find { true }
    }

    /** The live PENDING row: made by begin#TwoFactorSetup within the last 15 minutes. */
    static def pendingFactor(def ec, String userId, boolean forUpdate = false) {
        Timestamp since = new Timestamp(ec.user.nowTimestamp.time - PENDING_LIFETIME_SECONDS * 1000L)
        return find(ec, FACTOR, "self-scoped auth: the person's two-factor setup in progress").condition("userId", userId)
                .condition("factorTypeEnumId", TYPE_TOTP).condition("statusId", PENDING)
                .condition("createdDate", EntityCondition.ComparisonOperator.GREATER_THAN_EQUAL_TO, since)
                .orderBy("-createdDate").forUpdate(forUpdate).useCache(false).list()?.find { true }
    }

    /**
     * When two-factor is required for this person: the earliest requiredFromDate among their roles (a live
     * UserGroupMember row in ADMIN, DARPAN_SUPER_ADMIN or DARPAN_ADMIN, or tenant admin in ANY tenant: a factor belongs
     * to the person, not the tenant). Null when no role of theirs has a date (optional). A date in the future is the
     * grace period.
     */
    static Timestamp requiredFrom(def ec, String userId) {
        if (!userId) return null
        // The policy first: while every role is optional (the shipped state) that is the only read.
        Map<String, Timestamp> dated = find(ec, POLICY, "two-factor policy is platform configuration, not tenant data")
                .useCache(false).list().findAll { it.requiredFromDate != null }
                .collectEntries { [(it.permissionGroupId as String): it.requiredFromDate as Timestamp] }
        if (!dated) return null
        Timestamp now = ec.user.nowTimestamp
        List<String> groups = find(ec, "moqui.security.UserGroupMember", "self-scoped auth: the person's Darpan-wide roles for the two-factor policy")
                .condition("userId", userId).condition("userGroupId", EntityCondition.IN, DARPAN_ROLE_GROUP_IDS)
                .conditionDate("fromDate", "thruDate", now).useCache(false).list()*.userGroupId as List<String>
        if (dated.containsKey(TENANT_ADMIN_GROUP_ID)) {
            boolean tenantAdmin = find(ec, "darpan.auth.TenantUserPermissionGroupMember",
                    "self-scoped auth: is the person a tenant admin in any tenant, for the two-factor policy")
                    .condition("userId", userId).condition("permissionUserGroupId", TENANT_ADMIN_GROUP_ID)
                    .conditionDate("fromDate", "thruDate", now).useCache(false).count() > 0
            if (tenantAdmin) groups = groups + [TENANT_ADMIN_GROUP_ID]
        }
        List<Timestamp> dates = groups.unique().collect { dated[it] }.findAll { it != null }
        return dates ? dates.min() : null
    }

    /** The person's ACTIVE factors of either kind (one indexed read). */
    static List activeFactors(def ec, String userId) {
        if (!userId) return []
        return find(ec, FACTOR, "self-scoped auth: the person's active two-factor factors").condition("userId", userId)
                .condition("statusId", ACTIVE).useCache(false).list()
    }

    /** True while any factor (the app or a passkey) is ACTIVE. */
    static boolean enabled(def ec, String userId) {
        if (!userId) return false
        return find(ec, FACTOR, "self-scoped auth: does the person have two-factor on").condition("userId", userId)
                .condition("statusId", ACTIVE).useCache(false).count() > 0
    }

    static boolean isRequiredNow(def ec, String userId) {
        Timestamp from = requiredFrom(ec, userId)
        return from != null && !from.after(ec.user.nowTimestamp)
    }

    /** The person's unused recovery codes (one set exists at a time, whichever factor's enrolment made it). */
    static int recoveryCodesLeft(def ec, String userId) {
        if (!userId) return 0
        return (int) find(ec, RECOVERY, "self-scoped auth: count the person's unused recovery codes").condition("userId", userId)
                .condition("usedDate", null).useCache(false).count()
    }

    /** get#TwoFactorStatus: never the secret, never a key or credential id. */
    static Map<String, Object> status(def ec, String userId) {
        List factors = activeFactors(ec, userId)
        def app = factors.find { it.factorTypeEnumId == TYPE_TOTP }
        Timestamp from = requiredFrom(ec, userId)
        List<Map> passkeys = factors.any { it.factorTypeEnumId == PasskeySupport.TYPE_PASSKEY } ? PasskeySupport.describe(ec, userId) : []
        return [
                enabled          : !factors.isEmpty(),
                confirmedDate    : factors*.confirmedDate.findAll { it != null }.min(),
                recoveryCodesLeft: factors ? recoveryCodesLeft(ec, userId) : 0,
                required         : from != null && !from.after(ec.user.nowTimestamp),
                requiredFromDate : from,
                codesLocked      : app?.codesLockedDate != null,
                appEnabled       : app != null,
                passkeys         : passkeys,
                passkeysAvailable: PasskeySupport.available(ec),
        ]
    }

    /** The two fields sessionInfo carries: whether it is on, and by when it must be (only while it is not). */
    static Map<String, Object> sessionFields(def ec, String userId) {
        boolean on = enabled(ec, userId)
        return [twoFactorEnabled: on, twoFactorDueDate: on ? null : requiredFrom(ec, userId)]
    }

    // ---- the sign-in step ----

    /**
     * After the password matched: null when a session may be issued now (optional, or in the grace period), else the
     * challenge [purpose, challengeToken, expiresInSeconds, methods]: enter a code or use a passkey (an ACTIVE factor;
     * methods = "app" and/or "passkey"), or set up first (required, past the date, no factor; methods empty).
     */
    static Map<String, Object> signInStep(def ec, String userId) {
        String purpose
        if (enabled(ec, userId)) purpose = PURPOSE_CODE
        else if (isRequiredNow(ec, userId)) purpose = PURPOSE_SETUP
        else return null
        return [purpose : purpose, challengeToken: createChallenge(ec, userId, purpose), expiresInSeconds: CHALLENGE_LIFETIME_SECONDS,
                methods : purpose == PURPOSE_CODE ? PasskeySupport.methods(ec, userId) : []]
    }

    static String createChallenge(def ec, String userId, String purpose) {
        Timestamp now = ec.user.nowTimestamp
        // Housekeeping: a challenge a day past its end is of no use to anyone.
        find(ec, CHALLENGE, "housekeeping: sign-in challenges a day past their end")
                .condition("expiresDate", EntityCondition.ComparisonOperator.LESS_THAN, new Timestamp(now.time - 86_400_000L))
                .deleteAll()
        byte[] bytes = new byte[32]
        random.nextBytes(bytes)
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        ec.entity.makeValue(CHALLENGE).setAll([userId: userId, tokenHash: sha256(token), purposeEnumId: purpose,
                createdDate: now, expiresDate: new Timestamp(now.time + CHALLENGE_LIFETIME_SECONDS * 1000L),
                failedAttempts: 0, requestIp: recordedIp(ec)]).setSequencedIdPrimary().create()
        return token
    }

    /** The challenge for a token when it is live (not used, not expired, fewer than 5 wrong codes), else null. */
    static def liveChallenge(def ec, Object token, boolean forUpdate = false) {
        String value = normalize(token)
        if (!value) return null
        def row = find(ec, CHALLENGE, "pre-auth: sign-in challenge by token hash, no session yet")
                .condition("tokenHash", sha256(value)).forUpdate(forUpdate).useCache(false).one()
        if (row == null || row.usedDate != null) return null
        if (!((Timestamp) row.expiresDate)?.after(ec.user.nowTimestamp)) return null
        if (((row.failedAttempts ?: 0) as int) >= MAX_CHALLENGE_FAILURES) return null
        return row
    }

    /** A setup challenge's person, or null: what begin/confirm#TwoFactorSetup act for when no one is signed in. */
    static String setupChallengeUser(def ec, Object token) {
        def row = liveChallenge(ec, token)
        return row != null && row.purposeEnumId == PURPOSE_SETUP ? (String) row.userId : null
    }

    /**
     * verify#SignInCode's check: the challenge is claimed and the code, recovery code or passkey assertion checked in one
     * locked transaction of its own. A passkey assertion must sign the challenge begin#PasskeySignIn last put on the row,
     * and any attempt uses that challenge up. Returns [ok, userId, challengeEnded, recoveryCodesLeft].
     */
    static Map<String, Object> claimSignInChallenge(def ec, Object token, Object code, Object recoveryCode,
                                                    Object passkeyAssertion = null) {
        return (Map<String, Object>) ec.transaction.runRequireNew(null, "Could not check a sign-in code", {
            def challenge = liveChallenge(ec, token, true)
            if (challenge == null || challenge.purposeEnumId != PURPOSE_CODE) return [ok: false, challengeEnded: true]
            String userId = (String) challenge.userId
            Map<String, Object> check
            if (normalize(passkeyAssertion)) {
                String expected = (String) challenge.webauthnChallenge
                challenge.webauthnChallenge = null
                check = expected ? PasskeySupport.verifyAssertion(ec, userId, passkeyAssertion, expected) : [ok: false]
            } else if (normalize(recoveryCode)) {
                check = consumeRecoveryCode(ec, userId, recoveryCode)
            } else {
                check = [ok: consumeTotp(ec, userId, code)]
            }
            if (check.ok) {
                challenge.usedDate = ec.user.nowTimestamp
                challenge.update()
                return [ok: true, userId: userId, recoveryCodesLeft: check.recoveryCodesLeft]
            }
            int attempts = ((challenge.failedAttempts ?: 0) as int) + 1
            challenge.failedAttempts = attempts
            challenge.update()
            return [ok: false, challengeEnded: attempts >= MAX_CHALLENGE_FAILURES]
        })
    }

    /** One wrong code against a setup challenge (confirming a new secret counts against the challenge only). */
    static boolean countSetupChallengeFailure(def ec, Object token) {
        return (boolean) ec.transaction.runRequireNew(null, "Could not count a setup code", {
            def challenge = liveChallenge(ec, token, true)
            if (challenge == null) return true
            int attempts = ((challenge.failedAttempts ?: 0) as int) + 1
            challenge.failedAttempts = attempts
            challenge.update()
            return attempts >= MAX_CHALLENGE_FAILURES
        })
    }

    static void useChallenge(def ec, Object token) {
        def challenge = liveChallenge(ec, token, true)
        if (challenge == null) return
        challenge.usedDate = ec.user.nowTimestamp
        challenge.update()
    }

    // ---- code checks (call inside a transaction; they lock the factor row) ----

    /** A current authenticator code for the person's ACTIVE factor: later than the last one used, not locked. A wrong
     *  one is counted (see recordFailure). */
    static boolean consumeTotp(def ec, String userId, Object code) {
        def factor = activeFactor(ec, userId, true)
        if (factor == null) return false
        if (factor.codesLockedDate != null) {
            event(ec, userId, "CODE_FAILED")
            return false
        }
        Long step = TotpSupport.matchStep((String) factor.secret, normalizeTotp(code), codeClock.call(),
                factor.lastUsedTimeStep as Long)
        if (step != null) {
            long offset = step - TotpSupport.timeStep(codeClock.call())
            logger.debug("Two-factor code accepted for userId {} at step offset {}", userId, offset)
            factor.lastUsedTimeStep = step
            factor.lastUsedDate = ec.user.nowTimestamp
            factor.update()
            event(ec, userId, "CODE_OK")
            return true
        }
        recordFailure(ec, userId, factor)
        return false
    }

    /** A recovery code: one-time, refused while codes are locked (the lock lives on the app's row; a person with
     *  passkeys only has no lock: 80-bit codes, 5 per challenge and the failed-login count bound guessing). Returns
     *  [ok, recoveryCodesLeft]. Emails the person. */
    static Map<String, Object> consumeRecoveryCode(def ec, String userId, Object recoveryCode) {
        if (!enabled(ec, userId)) return [ok: false]
        def factor = activeFactor(ec, userId, true)
        if (factor?.codesLockedDate != null) {
            event(ec, userId, "CODE_FAILED")
            return [ok: false]
        }
        String value = normalizeRecoveryCode(recoveryCode)
        List rows = find(ec, RECOVERY, "self-scoped auth: the person's unused recovery codes").condition("userId", userId)
                .condition("usedDate", null).forUpdate(true).useCache(false).list()
        def match = value ? rows.find { hashMatches(value, (String) it.codeSalt, (String) it.codeHash) } : null
        if (match == null) {
            recordFailure(ec, userId, factor)
            return [ok: false]
        }
        match.usedDate = ec.user.nowTimestamp
        match.usedIp = recordedIp(ec)
        match.update()
        int left = rows.size() - 1
        event(ec, userId, "RECOVERY_USED")
        notify(ec, userId, TEMPLATE_RECOVERY_USED, [recoveryCodesLeft: left])
        return [ok: true, recoveryCodesLeft: left]
    }

    /** A wrong code: counts as a failed login (the framework's own lockout throttles it like a password guess), and 20
     *  in 24 hours lock codes until a password reset or an admin reset, with an email to the person. */
    private static void recordFailure(def ec, String userId, def factor) {
        if (factor == null) {
            event(ec, userId, "CODE_FAILED")
            AuthFacadeSupport.incrementFailedLogins(ec, userId)
            return
        }
        Timestamp now = ec.user.nowTimestamp
        Timestamp windowStart = (Timestamp) factor.failedWindowStart
        if (windowStart == null || now.time - windowStart.time >= LOCK_WINDOW_MILLIS) {
            factor.failedWindowStart = now
            factor.failedCodeCount = 0
        }
        int failures = ((factor.failedCodeCount ?: 0) as int) + 1
        factor.failedCodeCount = failures
        boolean locking = failures >= LOCK_AFTER_FAILURES && factor.codesLockedDate == null
        if (locking) factor.codesLockedDate = now
        factor.update()
        event(ec, userId, "CODE_FAILED")
        if (locking) {
            event(ec, userId, "CODES_LOCKED")
            logger.warn("Two-factor codes locked for userId {} after {} wrong codes in 24 hours", userId, failures)
            notify(ec, userId, TEMPLATE_CODES_LOCKED, [:])
        }
        AuthFacadeSupport.incrementFailedLogins(ec, userId)
    }

    /**
     * A fresh proof from a signed-in person who has two-factor on, for actions that must not ride on a hijacked tab
     * (approving a CLI sign-in, a /transfer export or secret handoff, new recovery codes, turning it off, an admin reset,
     * key rotation, adding or removing a factor): a current code from the app, or (DAR-BE-270) a passkey assertion over a
     * begin#PasskeyProof challenge. Null when it passes (or the person has no ACTIVE factor and onlyIfEnrolled), else the
     * refusal. Checked in its own transaction, so the caller's refusal does not roll back the count or the used challenge.
     */
    static String freshProofProblem(def ec, Object code, Object passkeyAssertion, boolean onlyIfEnrolled = true) {
        long started = System.nanoTime()
        String userId = normalize(ec.user.userId)
        if (!userId) return "Sign in first."
        List factors = activeFactors(ec, userId)
        if (!factors) return onlyIfEnrolled ? null : NOT_ENROLLED
        boolean hasApp = factors.any { it.factorTypeEnumId == TYPE_TOTP }
        if (normalize(passkeyAssertion)) {
            Map check = (Map) ec.transaction.runRequireNew(null, "Could not check a passkey", {
                PasskeySupport.verifyAssertion(ec, userId, passkeyAssertion, null)
            })
            if (check.ok) return null
            holdToFloor(started)
            return PasskeySupport.PASSKEY_REFUSAL
        }
        if (!normalizeTotp(code)) return hasApp && factors.size() == 1 ? CODE_NEEDED : PROOF_NEEDED
        if (!hasApp) return PROOF_NEEDED
        boolean ok = (boolean) ec.transaction.runRequireNew(null, "Could not check a two-factor code", {
            consumeTotp(ec, userId, code)
        })
        if (ok) return null
        holdToFloor(started)
        return CODE_REFUSAL
    }

    // ---- enrolment ----

    /** A new PENDING factor (any older pending one ends): the secret, the otpauth URI and a QR of it. The only reply
     *  that ever carries the secret. */
    static Map<String, Object> beginSetup(def ec, String userId) {
        def account = find(ec, "moqui.security.UserAccount", "self-scoped auth: the account setting up two-factor")
                .condition("userId", userId).useCache(false).one()
        Timestamp now = ec.user.nowTimestamp
        find(ec, FACTOR, "self-scoped auth: end the person's older two-factor setups").condition("userId", userId)
                .condition("factorTypeEnumId", TYPE_TOTP).condition("statusId", PENDING).useCache(false).list().each { row ->
            row.statusId = ENDED
            row.endedDate = now
            row.endedReason = "REPLACED"
            row.update()
        }
        String secret = TotpSupport.newSecret()
        ec.entity.makeValue(FACTOR).setAll([userId: userId, factorTypeEnumId: TYPE_TOTP, statusId: PENDING, secret: secret,
                createdDate: now, failedCodeCount: 0]).setSequencedIdPrimary().create()
        event(ec, userId, "SETUP_STARTED")
        String uri = TotpSupport.otpauthUri(issuer(), (String) account?.username ?: userId, secret)
        return [secret: secret, otpauthUri: uri, qrDataUri: TotpSupport.qrDataUri(uri), expiresInSeconds: PENDING_LIFETIME_SECONDS]
    }

    /**
     * The first right code from the new app: the pending factor becomes ACTIVE (an older ACTIVE one ends, REPLACED), 10
     * new recovery codes replace any old ones, and every login key of the person ends, CLI keys included (the caller
     * gets a fresh key, see AuthFacadeSupport.issueSignedInSession). Returns [ok, recoveryCodes] or [ok: false].
     */
    static Map<String, Object> confirmSetup(def ec, String userId, Object code) {
        def pending = pendingFactor(ec, userId, true)
        if (pending == null) return [ok: false, expired: true]
        Long step = TotpSupport.matchStep((String) pending.secret, normalizeTotp(code), codeClock.call(), null)
        if (step == null) return [ok: false]
        Timestamp now = ec.user.nowTimestamp
        find(ec, FACTOR, "self-scoped auth: end the person's replaced authenticator app").condition("userId", userId)
                .condition("factorTypeEnumId", TYPE_TOTP).condition("statusId", ACTIVE).useCache(false).list().each { row ->
            row.statusId = ENDED
            row.endedDate = now
            row.endedReason = "REPLACED"
            row.update()
        }
        pending.statusId = ACTIVE
        pending.confirmedDate = now
        pending.lastUsedTimeStep = step
        pending.lastUsedDate = now
        pending.update()
        List<String> codes = replaceRecoveryCodes(ec, userId, (String) pending.factorId)
        endSessions(ec, userId, [])
        event(ec, userId, "SETUP_CONFIRMED")
        return [ok: true, recoveryCodes: codes]
    }

    /**
     * begin#TwoFactorSetup and begin#PasskeyRegistration from a signed-in session (no setup challenge): with two-factor on,
     * a fresh proof from an existing factor (a code, or a passkey assertion); with none yet, the current password. So a
     * session token alone cannot put someone else's app or passkey on the account. A wrong password counts as a failed
     * login (its own transaction, as save#RecoveryEmail does).
     */
    static String signedInSetupProblem(def ec, Object currentPassword, Object code, Object passkeyAssertion = null) {
        String userId = normalize(ec.user.userId)
        if (!userId) return "Sign in first."
        if (enabled(ec, userId)) return freshProofProblem(ec, code, passkeyAssertion, false)
        if (!currentPassword) return "Current password is required."
        Map check = AuthFacadeSupport.verifyOwnPassword(ec, currentPassword)
        if (!check.passwordVerified) {
            AuthFacadeSupport.incrementFailedLogins(ec, userId)
            return "Current password is incorrect."
        }
        return null
    }

    /** DAR-BE-270: a passkey was the person's first factor: 10 recovery codes (returned once) and every login key ends,
     *  CLI keys included (the caller gets a fresh key), as confirmSetup does for the app. */
    static List<String> firstFactorOn(def ec, String userId, String factorId) {
        List<String> codes = replaceRecoveryCodes(ec, userId, factorId)
        endSessions(ec, userId, [])
        event(ec, userId, "SETUP_CONFIRMED")
        return codes
    }

    /** create#RecoveryCodes: needs a fresh proof; replaces every old code. */
    static Map<String, Object> renewRecoveryCodes(def ec, Object code, Object passkeyAssertion = null) {
        String userId = normalize(ec.user.userId)
        String problem = freshProofProblem(ec, code, passkeyAssertion, false)
        if (problem) return [error: problem]
        def factor = activeFactor(ec, userId) ?: activeFactors(ec, userId).find { true }
        List<String> codes = replaceRecoveryCodes(ec, userId, (String) factor.factorId)
        event(ec, userId, "CODES_RENEWED")
        return [recoveryCodes: codes]
    }

    /**
     * disable#TwoFactor: every factor off (an optional person), with a fresh proof; every other login key ends, this one
     * carries on. appOnly (DAR-BE-270): remove just the authenticator app while a passkey remains, allowed for a required
     * person too since they stay protected; with no passkey left it is the full turn-off.
     */
    static String turnOff(def ec, Object code, Object passkeyAssertion = null, boolean appOnly = false) {
        String userId = normalize(ec.user.userId)
        List factors = activeFactors(ec, userId)
        if (!factors) return NOT_ENROLLED
        boolean keepsPasskey = factors.any { it.factorTypeEnumId == PasskeySupport.TYPE_PASSKEY }
        if (appOnly && keepsPasskey) {
            if (!factors.any { it.factorTypeEnumId == TYPE_TOTP }) return "The authenticator app is not on for this account."
            String problem = freshProofProblem(ec, code, passkeyAssertion, false)
            if (problem) return problem
            endFactor(ec, activeFactor(ec, userId, true), "USER_REMOVED")
            event(ec, userId, "APP_REMOVED")
            return null
        }
        if (isRequiredNow(ec, userId)) return REQUIRED_CANNOT_TURN_OFF
        String problem = freshProofProblem(ec, code, passkeyAssertion, false)
        if (problem) return problem
        turnOffAll(ec, userId, "USER_OFF")
        return null
    }

    /** Every factor ends, recovery codes go, every other login key of the person ends (this request's carries on). */
    static void turnOffAll(def ec, String userId, String reason) {
        endFactors(ec, userId, reason)
        endSessions(ec, userId, CliAuthSupport.requestLoginKeyHashes(ec))
        event(ec, userId, "TURNED_OFF")
    }

    /**
     * delete#Passkey (DAR-BE-270): the person's own live passkey, with a fresh proof (an assertion from the passkey being
     * removed is fine). Removing the last factor is refused when their role requires two-factor, and for an optional
     * person turns two-factor off as disable#TwoFactor does. Returns [error] or [twoFactorEnabled].
     */
    static Map<String, Object> removePasskey(def ec, Object passkeyId, Object code, Object passkeyAssertion) {
        String userId = normalize(ec.user.userId)
        if (PasskeySupport.ownLive(ec, userId, passkeyId) == null) return [error: PasskeySupport.NOT_FOUND]
        boolean last = activeFactors(ec, userId).size() == 1
        if (last && isRequiredNow(ec, userId)) return [error: LAST_FACTOR_REQUIRED]
        String problem = freshProofProblem(ec, code, passkeyAssertion, false)
        if (problem) return [error: problem]
        // Read again after the proof: the proof may have been this passkey, and nothing else may have changed meanwhile.
        if (PasskeySupport.ownLive(ec, userId, passkeyId) == null) return [error: PasskeySupport.NOT_FOUND]
        if (last) {
            turnOffAll(ec, userId, "USER_OFF")
            return [twoFactorEnabled: false]
        }
        def factor = find(ec, FACTOR, "self-scoped auth: the passkey being removed").condition("factorId", normalize(passkeyId))
                .forUpdate(true).useCache(false).one()
        endFactor(ec, factor, "USER_REMOVED")
        event(ec, userId, "PASSKEY_REMOVED")
        return [twoFactorEnabled: true]
    }

    /**
     * admin.UserAdminServices.reset#TwoFactor: the person's factor ends (ADMIN_RESET), their recovery codes go, every
     * session and CLI key ends, and they set up again at their next sign-in when required. The caller checks super admin,
     * not one's own account and the reason; this checks the acting admin's own code (so they must have two-factor on),
     * and writes the evidence: AdminAuditLog, a TwoFactorEvent naming the admin, and an email to the person.
     */
    static String adminReset(def ec, String userId, String reason, Object adminCode, Object adminPasskeyAssertion = null) {
        String problem = freshProofProblem(ec, adminCode, adminPasskeyAssertion, false)
        if (problem == NOT_ENROLLED) return "Set up two-factor on your own account before resetting someone else's."
        if (problem) return problem
        endFactors(ec, userId, "ADMIN_RESET")
        endSessions(ec, userId, [])
        event(ec, userId, "ADMIN_RESET", normalize(ec.user.userId))
        AdminAuditSupport.record(ec, "admin.UserAdminServices.reset#TwoFactor", "User", userId,
                "Two-factor reset: ${reason}".toString())
        notify(ec, userId, TEMPLATE_RESET, [:])
        return null
    }

    /** After a password reset: the code lock clears (the password an attacker held is dead); the factor stays. */
    static void clearCodeLock(def ec, String userId) {
        def factor = activeFactor(ec, userId, true)
        if (factor == null || (factor.codesLockedDate == null && !factor.failedCodeCount)) return
        boolean wasLocked = factor.codesLockedDate != null
        factor.codesLockedDate = null
        factor.failedCodeCount = 0
        factor.failedWindowStart = null
        factor.update()
        if (wasLocked) event(ec, userId, "LOCK_CLEARED")
    }

    private static void endFactor(def ec, def row, String reason) {
        if (row == null) return
        row.statusId = ENDED
        row.endedDate = ec.user.nowTimestamp
        row.endedReason = reason
        row.update()
    }

    /** Every factor of the person (the app and every passkey) ends, and their recovery codes go. */
    private static void endFactors(def ec, String userId, String reason) {
        Timestamp now = ec.user.nowTimestamp
        find(ec, FACTOR, "self-scoped auth: end the person's two-factor factors").condition("userId", userId)
                .condition("statusId", EntityCondition.IN, [ACTIVE, PENDING]).useCache(false).list().each { row ->
            row.statusId = ENDED
            row.endedDate = now
            row.endedReason = reason
            row.update()
        }
        find(ec, RECOVERY, "self-scoped auth: delete the person's recovery codes").condition("userId", userId).deleteAll()
    }

    private static List<String> replaceRecoveryCodes(def ec, String userId, String factorId) {
        find(ec, RECOVERY, "self-scoped auth: replace the person's recovery codes").condition("userId", userId).deleteAll()
        Timestamp now = ec.user.nowTimestamp
        List<String> codes = []
        RECOVERY_CODE_COUNT.times {
            String value = newRecoveryCode()
            byte[] saltBytes = new byte[16]
            random.nextBytes(saltBytes)
            String salt = saltBytes.encodeHex().toString()
            ec.entity.makeValue(RECOVERY).setAll([userId: userId, factorId: factorId, codeHash: recoveryHash(value, salt),
                    codeSalt: salt, createdDate: now]).setSequencedIdPrimary().create()
            codes << formatRecoveryCode(value)
        }
        return codes
    }

    // ---- sessions and keys ----

    /** Ends every login key of the person but keepHashes (web and CLI), and marks their CLI sessions revoked. */
    static void endSessions(def ec, String userId, List<String> keepHashes) {
        List<String> keep = (keepHashes ?: []).findAll { it }
        def keys = find(ec, "moqui.security.UserLoginKey", "self-scoped auth: end the person's login keys after a two-factor change")
                .condition("userId", userId)
        if (keep) keys = keys.condition("loginKey", EntityCondition.NOT_IN, keep)
        keys.deleteAll()
        Timestamp now = ec.user.nowTimestamp
        def cli = find(ec, "darpan.auth.CliSession", "self-scoped auth: revoke the person's CLI sessions after a two-factor change")
                .condition("userId", userId).condition("revokedDate", null)
        if (keep) cli = cli.condition("loginKeyHash", EntityCondition.NOT_IN, keep)
        cli.useCache(false).list().each { row ->
            row.revokedDate = now
            row.update()
        }
    }

    /**
     * True when the key was issued before the person's two-factor was confirmed: such a key stops working (checked
     * by AuthSessionSupport.enforceWebSignInLifetime and CliAuthSupport.cliSessionProblem on every request). Runs per
     * request, so it is one indexed read.
     */
    static boolean keyPredatesFactor(def ec, String userId, Object keyFromDate) {
        if (!userId || !(keyFromDate instanceof Timestamp)) return false
        Timestamp confirmed = activeConfirmedDate(ec, userId)
        return confirmed != null && ((Timestamp) keyFromDate).before(confirmed)
    }

    /**
     * The date a key must not predate: the app's confirmedDate while the app is on (replacing it ends older keys), else
     * the earliest live passkey's (DAR-BE-270: adding a second passkey ends nothing). Null with no factor.
     */
    static Timestamp activeConfirmedDate(def ec, String userId) {
        if (!userId) return null
        List factors = TenantScopedFinder.findGlobalUnscopedPerRequest(ec, FACTOR,
                        "self-scoped auth: the person's two-factor confirmation date, for the login-key rule (per request)")
                .condition("userId", userId).condition("statusId", ACTIVE).useCache(false).list()
        if (!factors) return null
        def app = factors.find { it.factorTypeEnumId == TYPE_TOTP }
        if (app != null) return app.confirmedDate as Timestamp
        return factors*.confirmedDate.findAll { it != null }.min() as Timestamp
    }

    /** A CLI key of a person who is required and not enrolled is refused (the policy switched on after they signed in). */
    static boolean requiredButNotEnrolled(def ec, String userId) {
        return !enabled(ec, userId) && isRequiredNow(ec, userId)
    }

    // ---- policy (admin.SecurityAdminServices) ----

    /** Each role's date with who in it is and is not enrolled (never the secret), and the enrolled super-admin count. */
    static Map<String, Object> policies(def ec) {
        Timestamp now = ec.user.nowTimestamp
        Map<String, Object> rows = find(ec, POLICY, "two-factor policy is platform configuration, not tenant data")
                .useCache(false).list().collectEntries { [(it.permissionGroupId): it] }
        Set<String> enrolled = find(ec, FACTOR, "admin: who has two-factor on, for the policy board").condition("statusId", ACTIVE)
                .useCache(false).list()*.userId as Set<String>
        List<Map> roles = POLICY_GROUP_IDS.collect { String groupId ->
            List<String> members = roleMembers(ec, groupId, now)
            Map<String, String> names = usernames(ec, members)
            def row = rows[groupId]
            [permissionGroupId  : groupId, requiredFromDate: row?.requiredFromDate, lastChangedByUserId: row?.lastChangedByUserId,
             lastChangedDate    : row?.lastChangedDate,
             enrolledCount      : members.count { it in enrolled }, notEnrolledCount: members.count { !(it in enrolled) },
             enrolled           : members.findAll { it in enrolled }.collect { [userId: it, username: names[it]] },
             notEnrolled        : members.findAll { !(it in enrolled) }.collect { [userId: it, username: names[it]] }]
        }
        return [policies: roles, enrolledSuperAdminCount: enrolledSuperAdmins(ec, enrolled, now)]
    }

    /**
     * update#TwoFactorPolicy: a date (required from then; in the future = grace), or clear (optional again). Turning on a
     * super-admin role (ADMIN, DARPAN_SUPER_ADMIN) needs at least two enrolled super admins, so one lost phone cannot lock
     * the platform out (break-glass rule). Writes an AdminAuditLog row. Returns the refusal or null.
     */
    static String updatePolicy(def ec, String groupId, Timestamp requiredFromDate, boolean clear) {
        if (!(groupId in POLICY_GROUP_IDS)) return "${groupId ?: '(missing)'} is not a role the two-factor policy covers (${POLICY_GROUP_IDS.join(', ')}).".toString()
        if (!clear && requiredFromDate == null) return "Give requiredFromDate, or clear=true to make it optional."
        Timestamp now = ec.user.nowTimestamp
        if (!clear && groupId in TenantAccessSupport.SUPER_ADMIN_GROUP_IDS) {
            Set<String> enrolled = find(ec, FACTOR, "admin: who has two-factor on, for the break-glass rule").condition("statusId", ACTIVE)
                    .useCache(false).list()*.userId as Set<String>
            int count = enrolledSuperAdmins(ec, enrolled, now)
            if (count < MIN_ENROLLED_SUPER_ADMINS) {
                return "At least ${MIN_ENROLLED_SUPER_ADMINS} super admins must have two-factor on before it is required for ${groupId} (${count} do).".toString()
            }
        }
        Timestamp date = clear ? null : requiredFromDate
        ec.entity.makeValue(POLICY).setAll([permissionGroupId: groupId, requiredFromDate: date,
                lastChangedByUserId: normalize(ec.user.userId), lastChangedDate: now]).createOrUpdate()
        AdminAuditSupport.record(ec, "admin.SecurityAdminServices.update#TwoFactorPolicy", "TwoFactorPolicy", groupId,
                date == null ? "Two-factor optional for ${groupId}.".toString() : "Two-factor required for ${groupId} from ${date}.".toString())
        return null
    }

    private static int enrolledSuperAdmins(def ec, Set<String> enrolled, Timestamp now) {
        Set<String> supers = TenantAccessSupport.SUPER_ADMIN_GROUP_IDS.collectMany { roleMembers(ec, it, now) } as Set<String>
        return supers.count { it in enrolled } as int
    }

    private static List<String> roleMembers(def ec, String groupId, Timestamp now) {
        if (groupId == TENANT_ADMIN_GROUP_ID) {
            return find(ec, "darpan.auth.TenantUserPermissionGroupMember", "admin: tenant admins in every tenant, for the two-factor policy")
                    .condition("permissionUserGroupId", groupId).conditionDate("fromDate", "thruDate", now).useCache(false)
                    .list()*.userId.unique() as List<String>
        }
        return find(ec, "moqui.security.UserGroupMember", "admin: members of a Darpan-wide role, for the two-factor policy")
                .condition("userGroupId", groupId).conditionDate("fromDate", "thruDate", now).useCache(false)
                .list()*.userId.unique() as List<String>
    }

    private static Map<String, String> usernames(def ec, List<String> userIds) {
        if (!userIds) return [:]
        return find(ec, "moqui.security.UserAccount", "admin: usernames for the two-factor policy board")
                .condition("userId", EntityCondition.IN, userIds).useCache(false).list()
                .collectEntries { [(it.userId): it.username] }
    }

    /** userId -> "ON", "OFF" or "REQUIRED_NOT_SET_UP", for the admin users list and detail. One read per table. */
    static Map<String, String> adminStates(def ec, List<String> userIds) {
        if (!userIds) return [:]
        Set<String> enrolled = find(ec, FACTOR, "admin: two-factor state for the users board").condition("statusId", ACTIVE)
                .condition("userId", EntityCondition.IN, userIds).useCache(false).list()*.userId as Set<String>
        return userIds.collectEntries { String userId ->
            [(userId): userId in enrolled ? "ON" : (isRequiredNow(ec, userId) ? "REQUIRED_NOT_SET_UP" : "OFF")]
        }
    }

    // ---- evidence and mail ----

    static void event(def ec, String userId, String type, String byUserId = null) {
        ec.entity.makeValue(EVENT).setAll([userId: userId, eventType: type, byUserId: byUserId,
                eventDate: ec.user.nowTimestamp, ip: recordedIp(ec)]).setSequencedIdPrimary().create()
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

    // ---- small helpers ----

    /** The longest text form of an IP address (IPv6 with an embedded IPv4 part). */
    static final int MAX_IP_LENGTH = 45

    /**
     * The request's client IP as challenges, events and used recovery codes store it: an IPv4 or IPv6 literal, else null.
     * It comes from a proxy header, so it is checked rather than trusted to fit a column: an oversized or made-up value
     * would otherwise fail the insert (and with it the sign-in step) or put text that is no address on the record.
     */
    static String recordedIp(def ec) {
        String ip = normalize(ec.user.clientIp)
        if (!ip || ip.length() > MAX_IP_LENGTH) return null
        return InetAddressValidator.getInstance().isValid(ip) ? ip : null
    }

    /** The authenticator app's label: "Darpan", or DARPAN_TOTP_ISSUER (e.g. "Darpan UAT") so instances are told apart. */
    static String issuer() {
        String configured = normalize(System.getenv("DARPAN_TOTP_ISSUER")) ?: normalize(System.getProperty("darpan.totp.issuer"))
        return (configured ?: "Darpan").replace(":", " ")
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
        return sha256(salt + ":" + value)
    }

    private static boolean hashMatches(String value, String salt, String expected) {
        if (!salt || !expected) return false
        return MessageDigest.isEqual(recoveryHash(value, salt).getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))
    }

    static String sha256(String value) {
        return sha256Bytes(value.getBytes(StandardCharsets.UTF_8))
    }

    static String sha256Bytes(byte[] value) {
        return MessageDigest.getInstance("SHA-256").digest(value).encodeHex().toString()
    }

    static void holdToFloor(long startedNanos) {
        long remaining = CODE_REFUSAL_FLOOR_MILLIS - (System.nanoTime() - startedNanos).intdiv(1_000_000L)
        if (remaining <= 0) {
            logger.warn("Two-factor code refusal took longer than its {}ms floor; refusals may be told apart by timing",
                    CODE_REFUSAL_FLOOR_MILLIS)
            return
        }
        try {
            Thread.sleep(remaining)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
        }
    }
}
