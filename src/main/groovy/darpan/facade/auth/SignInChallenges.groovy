package darpan.facade.auth

import org.moqui.entity.EntityCondition

import java.sql.Timestamp

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-264/270 — the SignInChallenge rows: the step between a right password and a session (enter a code or use a
 * passkey; set up first), and the two passkey ceremonies outside sign-in (a fresh proof, a registration). Only a token's
 * SHA-256 is stored; a row lives 5 minutes, is used once, and 5 wrong answers end it.
 *
 * <p>Every check against a row runs under its lock in a transaction of its own (claim, countFailure), so parallel guesses
 * are each counted before the next is compared, and the refusal that follows rolls back the caller's transaction but not
 * the count.
 */
class SignInChallenges {
    static final String CHALLENGE = "darpan.auth.SignInChallenge"

    /** Enter a code (or recovery code) or use a passkey. */
    static final String PURPOSE_CODE = "DrpnScCode"
    /** Two-factor is required and the person has no factor: set one up first. */
    static final String PURPOSE_SETUP = "DrpnScSetup"
    /** A passkey assertion for one fresh-proof action (begin#PasskeyProof). */
    static final String PURPOSE_PROOF = "DrpnScProof"
    /** A passkey registration (begin#PasskeyRegistration). */
    static final String PURPOSE_ADD = "DrpnScPkAdd"

    static final int LIFETIME_SECONDS = 300
    /** How long a row stays after it expires before the person's next challenge sweeps it (a day of evidence). */
    static final long EXPIRED_KEEP_MILLIS = 24L * 3_600_000L
    static final int MAX_FAILURES = 5

    /**
     * A new row for the person; returns its token. With webauthn the token is also the WebAuthn challenge the browser
     * signs (webauthnChallenge), so the clientDataJSON it sends back names its row (claimCeremony). The person's own rows
     * a day past their end are swept first, by the indexed userId.
     */
    static String create(def ec, String userId, String purpose, boolean webauthn = false) {
        Timestamp now = ec.user.nowTimestamp
        TwoFactorRecords.find(ec, CHALLENGE, "housekeeping: the person's sign-in challenges a day past their end")
                .condition("userId", userId)
                .condition("expiresDate", EntityCondition.ComparisonOperator.LESS_THAN, new Timestamp(now.time - EXPIRED_KEEP_MILLIS))
                .deleteAll()
        String token = AuthPrimitives.newToken()
        ec.entity.makeValue(CHALLENGE).setAll([userId: userId, tokenHash: AuthPrimitives.sha256Hex(token), purposeEnumId: purpose,
                webauthnChallenge: webauthn ? token : null, createdDate: now,
                expiresDate: new Timestamp(now.time + LIFETIME_SECONDS * 1000L), failedAttempts: 0,
                requestIp: AuthPrimitives.recordedIp(ec.user.clientIp as String)]).setSequencedIdPrimary().create()
        return token
    }

    /** The row for a token when it is live (not used, not expired, fewer than MAX_FAILURES wrong answers), else null. */
    static def live(def ec, Object token, boolean forUpdate = false) {
        String value = normalize(token)
        if (!value) return null
        def row = TwoFactorRecords.find(ec, CHALLENGE, "pre-auth: sign-in challenge by token hash, no session yet")
                .condition("tokenHash", AuthPrimitives.sha256Hex(value)).forUpdate(forUpdate).useCache(false).one()
        if (row == null || row.usedDate != null) return null
        if (!((Timestamp) row.expiresDate)?.after(ec.user.nowTimestamp)) return null
        if (((row.failedAttempts ?: 0) as int) >= MAX_FAILURES) return null
        return row
    }

    /**
     * Claims the live row of purpose for a token, locked, in a transaction of its own: check(row) answers it with a map
     * holding ok (and may change the row). A pass uses the row up; a miss counts one failure. Returns check's map with the
     * row's userId, or [ok: false, challengeEnded] (true when the row was already spent or this miss spent it).
     */
    static Map<String, Object> claim(def ec, Object token, String purpose, Closure<Map> check) {
        return (Map<String, Object>) ec.transaction.runRequireNew(null, "Could not check a sign-in challenge", {
            def row = live(ec, token, true)
            if (row == null || row.purposeEnumId != purpose) return [ok: false, challengeEnded: true]
            Map checked = check.call(row)
            if (!checked.ok) return [ok: false, challengeEnded: countFailureOn(row)]
            row.usedDate = ec.user.nowTimestamp
            row.update()
            return checked + [userId: row.userId]
        })
    }

    /** One wrong answer against a live row, in a transaction of its own (a wrong code confirming a new app, a refused
     *  passkey registration). True when the row is spent. */
    static boolean countFailure(def ec, Object token) {
        return (boolean) ec.transaction.runRequireNew(null, "Could not count a sign-in challenge failure", {
            def row = live(ec, token, true)
            return row == null || countFailureOn(row)
        })
    }

    private static boolean countFailureOn(def row) {
        int attempts = ((row.failedAttempts ?: 0) as int) + 1
        row.failedAttempts = attempts
        row.update()
        return attempts >= MAX_FAILURES
    }

    /** Uses a live row up, in the caller's transaction. */
    static void use(def ec, Object token) {
        def row = live(ec, token, true)
        if (row == null) return
        row.usedDate = ec.user.nowTimestamp
        row.update()
    }

    /**
     * A WebAuthn ceremony's row (a proof or a registration), named by the challenge the browser signed: live, of purpose
     * and of this person. Used up here whatever follows, so an assertion or credential is good for one attempt. Null
     * otherwise.
     */
    static def claimCeremony(def ec, String presentedChallenge, String purpose, String userId) {
        def row = presentedChallenge ? live(ec, presentedChallenge, true) : null
        if (row == null || row.purposeEnumId != purpose || row.userId != userId) return null
        row.usedDate = ec.user.nowTimestamp
        row.update()
        return row
    }

    /**
     * A setup challenge's person, or null: who begin/confirm#TwoFactorSetup and begin/finish#PasskeyRegistration act for
     * when no one is signed in. A setup challenge exists to put a FIRST factor on: once the person has one (set up in
     * another tab meanwhile), it is spent here and refused, or it would let whoever holds the password add a second factor
     * of their own (review M1). Checked under the person lock, held to the end of the caller's transaction.
     */
    static String setupUser(def ec, Object token) {
        // Not locked here: a wrong setup code is counted on this row in a transaction of its own (countFailure), which a
        // lock held by the caller's transaction would block.
        def row = live(ec, token)
        if (row == null || row.purposeEnumId != PURPOSE_SETUP) return null
        String userId = (String) row.userId
        TwoFactorRecords.lockPerson(ec, userId)
        if (TwoFactorRecords.enabled(ec, userId)) {
            use(ec, token)
            return null
        }
        return userId
    }
}
