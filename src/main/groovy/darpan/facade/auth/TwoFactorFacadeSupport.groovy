package darpan.facade.auth

import darpan.facade.common.FacadeSupport

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-264/270 — the facade.AuthFacadeServices two-factor and passkey steps that answer with a session or a refusal
 * envelope: verify#SignInCode, begin/confirm#TwoFactorSetup, begin#PasskeySignIn, begin/finish#PasskeyRegistration, and
 * disable#TwoFactor / delete#Passkey (which hand the tab a fresh key when two-factor goes off). The work is
 * TwoFactorSupport's and PasskeySupport's; this decides who a step acts for, signs the person in, and shapes the reply.
 *
 * <p>One refusal rule (review QM2): the sign-in and setup steps refuse with a completed call (refused) because the caller
 * needs the step's own fields (challengeEnded) to choose its next step; every other two-factor method refuses with a plain
 * JSON-RPC error.
 */
class TwoFactorFacadeSupport {

    /**
     * verify#SignInCode: the challenge token from login#Session with a current code, a recovery code or a passkey
     * assertion. On success the person is signed in (no second UserLoginHistory row: the password step wrote one) and the
     * reply is login#Session's. Every refusal is CODE (or PASSKEY) held to TwoFactorSupport.CODE_REFUSAL_FLOOR_MILLIS;
     * challengeEnded says the token is spent (expired, used, 5 wrong answers), so the client goes back to the password. The
     * wrong-code and failed-login counts are written in their own transaction (SignInChallenges.claim), so they stand
     * whatever happens to this one.
     */
    static Map<String, Object> verifySignInCode(def ec, Object challengeToken, Object code, Object recoveryCode, Object passkeyAssertion) {
        long started = System.nanoTime()
        Map<String, Object> claim = TwoFactorSupport.checkSignInChallenge(ec, challengeToken, code, recoveryCode, passkeyAssertion)
        Map<String, Object> result = [authenticated: false, challengeEnded: claim.challengeEnded == true]
        boolean undo = false
        if (claim.ok) {
            if (!signInAs(ec, (String) claim.userId)) {
                result.challengeEnded = true
            } else if (AuthFacadeSupport.issueSignedInSession(ec, result)) {
                if (claim.recoveryCodesLeft != null) result.recoveryCodesLeft = claim.recoveryCodesLeft
                result.putAll(FacadeSupport.envelope(ec))
                return result
            } else {
                // Signed in but no key (issueSignedInSession signed the request out again): nothing it wrote may stand.
                undo = true
            }
        }
        TwoFactorSupport.holdToFloor(started)
        return refused(ec, result, normalize(passkeyAssertion) ? TwoFactorRefusal.PASSKEY : TwoFactorRefusal.CODE, undo)
    }

    /**
     * begin#TwoFactorSetup: signed in (a first setup needs the current password, a replacement a fresh proof), or with a
     * setup challenge from login#Session. Returns the secret, the otpauth URI, the QR data URI and expiresInSeconds.
     */
    static Map<String, Object> beginTwoFactorSetup(def ec, Object challengeToken, Object currentPassword, Object code,
                                                   Object passkeyAssertion) {
        def (String userId, TwoFactorRefusal problem) = setupSubject(ec, challengeToken, currentPassword, code, passkeyAssertion)
        if (problem) return refused(ec, [challengeEnded: normalize(challengeToken) != null], problem, false)
        Map<String, Object> result = [challengeEnded: false]
        result.putAll(TwoFactorSupport.beginSetup(ec, userId))
        result.putAll(FacadeSupport.envelope(ec))
        return result
    }

    /**
     * confirm#TwoFactorSetup: the first right code from the new app turns it on, ends every other login key (CLI keys
     * too) and returns the recovery codes when it is the person's first factor. The caller is then signed in with a fresh
     * key: through a setup challenge this is the sign-in itself; from a signed-in tab the old key predates the change and
     * stops working, so the tab swaps to the authToken returned here.
     */
    static Map<String, Object> confirmTwoFactorSetup(def ec, Object challengeToken, Object code) {
        boolean viaChallenge = normalize(challengeToken) != null
        Map<String, Object> result = [authenticated: false, challengeEnded: false]
        String userId = setupUser(ec, challengeToken)
        if (!userId) return refused(ec, result + [challengeEnded: viaChallenge], noSubject(viaChallenge), false)
        Map<String, Object> confirmed = TwoFactorSupport.confirmSetup(ec, userId, code)
        if (!confirmed.ok) {
            if (viaChallenge) result.challengeEnded = SignInChallenges.countFailure(ec, challengeToken)
            return refused(ec, result, confirmed.expired ? TwoFactorRefusal.SETUP_EXPIRED : TwoFactorRefusal.CODE, false)
        }
        if (viaChallenge) {
            SignInChallenges.use(ec, challengeToken)
            if (!signInAs(ec, userId)) return cannotSignIn(ec, result)
        }
        AuthFacadeSupport.issueSignedInSession(ec, result)
        result.recoveryCodes = confirmed.recoveryCodes
        result.putAll(FacadeSupport.envelope(ec))
        return result
    }

    /**
     * disable#TwoFactor: TwoFactorSupport.turnOff, and when two-factor went off altogether every sign-in of the person
     * ended (TwoFactorSupport.endSessions), so this tab is handed a fresh key (the sign-in fields, as
     * confirm#TwoFactorSetup answers). Removing just the app ends nothing and returns no key. [refusal] when refused.
     */
    static Map<String, Object> turnOffTwoFactor(def ec, Object code, Object passkeyAssertion, boolean appOnly) {
        return reissueWhenOff(ec, TwoFactorSupport.turnOff(ec, code, passkeyAssertion, appOnly))
    }

    /** delete#Passkey: TwoFactorSupport.removePasskey; when it was the last factor, as turnOffTwoFactor. */
    static Map<String, Object> deletePasskey(def ec, Object passkeyId, Object code, Object passkeyAssertion) {
        return reissueWhenOff(ec, TwoFactorSupport.removePasskey(ec, passkeyId, code, passkeyAssertion))
    }

    private static Map<String, Object> reissueWhenOff(def ec, Map<String, Object> outcome) {
        Map<String, Object> result = new LinkedHashMap<>(outcome)
        if (outcome.twoFactorEnabled == false) AuthFacadeSupport.issueSignedInSession(ec, result)
        return result
    }

    /**
     * begin#PasskeySignIn: request options (publicKeyOptions, JSON text) for the person a sign-in challenge from
     * login#Session belongs to. Each call puts a fresh WebAuthn challenge on the row (the last one wins); the assertion
     * goes to verify#SignInCode. A spent token says challengeEnded.
     */
    static Map<String, Object> beginPasskeySignIn(def ec, Object challengeToken) {
        long started = System.nanoTime()
        Map<String, Object> begun = PasskeySupport.signInOptions(ec, challengeToken)
        if (begun.challengeEnded || begun.refusal) {
            TwoFactorSupport.holdToFloor(started)
            return refused(ec, [challengeEnded: begun.challengeEnded == true],
                    begun.challengeEnded ? TwoFactorRefusal.CHALLENGE_ENDED : (TwoFactorRefusal) begun.refusal, false)
        }
        Map<String, Object> result = [challengeEnded: false, publicKeyOptions: begun.publicKeyOptions]
        result.putAll(FacadeSupport.envelope(ec))
        return result
    }

    /**
     * begin#PasskeyRegistration: creation options for the person, after the same check begin#TwoFactorSetup makes (a
     * setup challenge; signed in: the current password with no factor yet, else a fresh proof).
     */
    static Map<String, Object> beginPasskeyRegistration(def ec, Object challengeToken, Object currentPassword, Object code,
                                                        Object passkeyAssertion) {
        def (String userId, TwoFactorRefusal problem) = setupSubject(ec, challengeToken, currentPassword, code, passkeyAssertion)
        if (problem) return refused(ec, [challengeEnded: normalize(challengeToken) != null], problem, false)
        Map<String, Object> begun = PasskeySupport.registrationOptions(ec, userId)
        if (begun.refusal) return refused(ec, [challengeEnded: false], (TwoFactorRefusal) begun.refusal, false)
        Map<String, Object> result = [challengeEnded: false, publicKeyOptions: begun.publicKeyOptions,
                                      expiresInSeconds: begun.expiresInSeconds]
        result.putAll(FacadeSupport.envelope(ec))
        return result
    }

    /**
     * finish#PasskeyRegistration: the browser's new credential (JSON text). When it is the person's first factor,
     * two-factor turns on exactly as confirm#TwoFactorSetup does: every other key ends, the caller gets a fresh key (the
     * sign-in itself with a setup challenge) and 10 recovery codes, once. An added passkey changes no session (no
     * authenticated/authToken in the reply: the tab keeps its key).
     */
    static Map<String, Object> finishPasskeyRegistration(def ec, Object challengeToken, Object credential, Object name) {
        boolean viaChallenge = normalize(challengeToken) != null
        Map<String, Object> result = [challengeEnded: false]
        String userId = setupUser(ec, challengeToken)
        if (!userId) return refused(ec, result + [challengeEnded: viaChallenge], noSubject(viaChallenge), false)
        // Under the person lock (setupUser took it already on that path), so "first" still holds at commit.
        TwoFactorRecords.lockPerson(ec, userId)
        boolean first = !TwoFactorRecords.enabled(ec, userId)
        // In this transaction, so a refusal (no undo) still commits the used-up registration challenge and its event.
        Map<String, Object> added = PasskeySupport.register(ec, userId, credential, name)
        if (added.refusal) {
            if (viaChallenge) result.challengeEnded = SignInChallenges.countFailure(ec, challengeToken)
            return refused(ec, result, (TwoFactorRefusal) added.refusal, false)
        }
        result.passkeyId = added.factorId
        if (first) result.recoveryCodes = TwoFactorSupport.factorConfirmed(ec, userId, true)
        if (viaChallenge) {
            SignInChallenges.use(ec, challengeToken)
            if (!signInAs(ec, userId)) return cannotSignIn(ec, result)
        }
        if (first || viaChallenge) AuthFacadeSupport.issueSignedInSession(ec, result)
        result.putAll(FacadeSupport.envelope(ec))
        return result
    }

    // ---- who a step acts for ----

    /** A setup challenge's person (SignInChallenges.setupUser) when a token is given, else the signed-in person. */
    private static String setupUser(def ec, Object challengeToken) {
        return normalize(challengeToken) ? SignInChallenges.setupUser(ec, challengeToken) : normalize(ec.user.userId)
    }

    private static TwoFactorRefusal noSubject(boolean viaChallenge) {
        return viaChallenge ? TwoFactorRefusal.CHALLENGE_ENDED : TwoFactorRefusal.SIGN_IN_FIRST
    }

    /**
     * Who a begin step acts for: [userId, null], or [null, refusal]. A setup challenge's person; or the signed-in person
     * once they show it is them, so a session token alone cannot put someone else's app or passkey on the account: with
     * two-factor on, a fresh proof from an existing factor; with none yet, the current password (a wrong one counts as a
     * failed login).
     */
    private static List setupSubject(def ec, Object challengeToken, Object currentPassword, Object code, Object passkeyAssertion) {
        boolean viaChallenge = normalize(challengeToken) != null
        String userId = setupUser(ec, challengeToken)
        if (!userId) return [null, noSubject(viaChallenge)]
        if (viaChallenge) return [userId, null]
        TwoFactorRefusal problem
        if (TwoFactorRecords.enabled(ec, userId)) problem = TwoFactorSupport.freshProofProblem(ec, code, passkeyAssertion)
        else if (!currentPassword) problem = TwoFactorRefusal.PASSWORD_NEEDED
        else if (!AuthFacadeSupport.ownPasswordMatches(ec, currentPassword.toString())) problem = TwoFactorRefusal.PASSWORD_WRONG
        return problem ? [null, problem] : [userId, null]
    }

    /**
     * The person behind a verified challenge, signed in on a new servlet session (review M3), when the account can still
     * sign in (not disabled, locked out or terminated: CliAuthSupport.canSignIn). False otherwise.
     */
    private static boolean signInAs(def ec, String userId) {
        def account = TwoFactorRecords.find(ec, "moqui.security.UserAccount", "pre-auth: the account a verified challenge belongs to")
                .condition("userId", userId).useCache(false).one()
        return CliAuthSupport.canSignIn(ec, account) && AuthSessionSupport.signInOnNewSession(ec, (String) account.username, false)
    }

    /** A setup that turned a factor on for an account that cannot sign in: refused, and nothing it wrote stands (the
     *  factor, the used challenge). The account check comes after the work because the framework's own sign-in can still
     *  refuse after canSignIn passes (an IP allowlist), so the undo is needed either way. */
    private static Map<String, Object> cannotSignIn(def ec, Map<String, Object> result) {
        result.challengeEnded = true
        return refused(ec, result, TwoFactorRefusal.CANNOT_SIGN_IN, true)
    }

    /**
     * A refused step, answered as a completed call: [ok: false, errors: [message], messages: [], reason] plus the step's
     * own fields (challengeEnded, ...). Moqui's JSON-RPC dispatcher answers any call that ends with a MessageFacade error
     * with a bare JSON-RPC error and drops the result, so the client would never see challengeEnded; the refusal therefore
     * travels in the envelope only and the MessageFacade is left empty. With undo (the call wrote something in this
     * transaction that must not stand) the transaction is marked rollback-only; counts written in their own transaction
     * (wrong codes, failed logins) are kept either way.
     */
    private static Map<String, Object> refused(def ec, Map<String, Object> result, TwoFactorRefusal refusal, boolean undo) {
        // clearAll(), not clearErrors(): clearErrors MOVES errors into the messages list.
        ec.message.clearAll()
        if (undo && ec.transaction.isTransactionInPlace()) {
            ec.transaction.setRollbackOnly("Two-factor step refused: ${refusal.message}".toString(), null)
        }
        if (result.containsKey("authenticated")) result.authenticated = false
        ["authToken", "authTokenType", "authTokenHeaderName", "authTokenExpiresInSeconds", "sessionInfo",
         "recoveryCodes", "recoveryCodesLeft", "publicKeyOptions", "passkeyId"].each { result.remove(it) }
        result.ok = false
        result.messages = []
        result.errors = [refusal.message]
        result.reason = refusal.reason ?: "REFUSED"
        return result
    }
}
