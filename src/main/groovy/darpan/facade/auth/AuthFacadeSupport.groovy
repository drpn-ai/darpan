package darpan.facade.auth

import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import org.apache.shiro.authc.SimpleAuthenticationInfo
import org.apache.shiro.authc.UsernamePasswordToken
import org.apache.shiro.lang.util.SimpleByteSource
import org.moqui.entity.EntityCondition
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import static darpan.common.ValueSupport.normalize

class AuthFacadeSupport {
    private static final Logger logger = LoggerFactory.getLogger(AuthFacadeSupport.class)

    /** Reason codes darpan-ui switches on to decide whether a rejected login can be recovered by a
     *  password change. Values match the bracketed markers the framework embeds in its own messages. */
    static final String PASSWORD_CHANGE_REQUIRED_REASON = "PWDCHG"
    static final String PASSWORD_EXPIRED_REASON = "PWDTIM"

    /** MoquiShiroRealm.loginPostPassword raises PasswordChangeRequiredException with a "[PWDCHG]" marker
     *  when UserAccount.requirePasswordChange is Y, and ExpiredCredentialsException with "[PWDTIM]" when
     *  passwordSetDate is older than user-facade.password.@change-weeks. UserFacadeImpl.internalLoginToken
     *  catches both and adds the message text to the MessageFacade, which is the only place the distinction
     *  survives — loginUser() returns a bare false for every rejection reason alike. Matching the marker is
     *  therefore the supported signal; the session attributes the framework also sets are unusable here
     *  because darpan-ui authenticates statelessly with a login-key header and has no server session.
     *
     *  Both exceptions are thrown only AFTER the submitted password matched, so reporting either reason to
     *  an unauthenticated caller reveals nothing: it answers a caller who already holds valid credentials. */
    private static final String PASSWORD_CHANGE_REQUIRED_MARKER = "[PWDCHG]"
    private static final String PASSWORD_EXPIRED_MARKER = "[PWDTIM]"
    private static final String PASSWORD_CHANGE_REQUIRED_MESSAGE =
            "Your password must be changed before you can sign in."
    private static final String PASSWORD_EXPIRED_MESSAGE =
            "Your password has expired and must be changed before you can sign in."

    /** The single answer to every login refusal that is not recoverable by changing the password. */
    private static final String INVALID_CREDENTIALS_MESSAGE = "Invalid username or password"

    /** Refusals MoquiShiroRealm raises only AFTER running the credentials matcher, so the password was
     *  already hashed on these paths and must not be hashed a second time. Everything else — including any
     *  message this list does not recognise — is treated as having skipped the hash, so drift in the
     *  framework's wording costs a redundant hash rather than reopening the timing channel. */
    private static final List<String> POST_PASSWORD_REFUSAL_MARKERS = [
            PASSWORD_CHANGE_REQUIRED_MARKER,
            PASSWORD_EXPIRED_MARKER,
            "is not in allowed list",
    ]

    /** Decoy credential for the check below: a fixed salt and a well-formed SHA-256 hex digest that is not
     *  the hash of anything anyone knows. Neither is a secret and neither is ever stored or compared against
     *  a real account — their only job is to give Shiro's matcher the same shape of work it does for a real
     *  one, so the submitted password gets hashed either way. */
    private static final String DECOY_PASSWORD_SALT = "darpan-login-timing-decoy"
    private static final String DECOY_STORED_CREDENTIAL =
            "9f2eb2b4b8e0d7a1c3f5068d4e7a9b1c2d3e4f5061728394a5b6c7d8e9f00112"

    /** Wall-clock floor for a refused login, in milliseconds.
     *
     *  Matching the work is not enough on its own. A wrong password for an account that EXISTS also runs
     *  increment#UserAccountFailedLogins — a database write, in its own transaction — and there is no
     *  honest equivalent to run for a username that does not exist, because there is no row to write to.
     *  That write is the account-lockout mechanism, so it cannot be dropped either. With the hash equalised,
     *  it was measured as the whole of the remaining ~0.7ms gap.
     *
     *  Hence a floor: hold every refusal to the same minimum so the residual has nothing to show. Deliberately
     *  small. The usual advice reaches for 100-250ms, but a refused login holds a request thread and its open
     *  transaction for the whole floor, so a large one hands the enumeration this is meant to frustrate a
     *  multiplier on the connection pool. 25ms clears the ~2.3ms this component takes locally with room to
     *  spare, and sits at or under a production database's own round-trip, where it costs almost nothing. */
    private static final long LOGIN_REFUSAL_FLOOR_MILLIS = 25L

    static Map<String, Object> loginSession(def ec, Object username, Object password) {
        long attemptStartedNanos = System.nanoTime()
        String usernameValue = normalize(username)
        String passwordValue = password?.toString()

        if (!usernameValue) ec.message.addError("username is required")
        if (!passwordValue) ec.message.addError("password is required")

        boolean loggedIn = false
        String passwordChangeReason = null
        if (!ec.message.hasError()) {
            loggedIn = ec.user.loginUser(usernameValue, passwordValue)
            if (!loggedIn) {
                passwordChangeReason = resolvePasswordChangeReason(ec)
                if (passwordChangeReason == null) collapseLoginFailure(ec, usernameValue, passwordValue, attemptStartedNanos)
            }
        }

        String userId = normalize(ec?.user?.userId)
        boolean authenticated = loggedIn && userId != null

        // DAR-BE-264: the password matched; a person with two-factor on (or due and not set up) gets a challenge, not
        // a session. Said only after a right password, so it reveals nothing new (as [PWDCHG]).
        Map<String, Object> twoFactorStep = authenticated ? TwoFactorSupport.signInStep(ec, userId) : null
        if (twoFactorStep != null) {
            logoutCurrentSessionOnly(ec)
            authenticated = false
        }

        Map<String, Object> result = [
                authenticated         : authenticated,
                passwordChangeRequired: passwordChangeReason != null,
                twoFactorRequired     : twoFactorStep?.purpose == TwoFactorSupport.PURPOSE_CODE,
                twoFactorSetupRequired: twoFactorStep?.purpose == TwoFactorSupport.PURPOSE_SETUP,
        ]
        if (authenticated) authenticated = issueSignedInSession(ec, result)

        result.putAll(FacadeSupport.envelope(ec))

        if (twoFactorStep != null) {
            // A completed call carrying a required next step, like the password-change case below.
            result.challengeToken = twoFactorStep.challengeToken
            result.challengeExpiresInSeconds = twoFactorStep.expiresInSeconds
            result.ok = true
            result.errors = []
        }

        if (passwordChangeReason != null) {
            // Drop the framework text entirely: it names the account and quotes the internal code, and this
            // response goes to an unauthenticated caller. clearAll() rather than clearErrors(), because
            // clearErrors() MOVES errors into the messages list instead of discarding them.
            ec.message.clearAll()
            result.passwordChangeReason = passwordChangeReason
            // Reported as a COMPLETED call carrying a required next step, not as a failure — `authenticated`
            // stays false, which is the signal every caller actually reads. Two layers drop the payload of
            // anything shaped like a failure, and the reason code has to survive both: Moqui's JSON-RPC
            // dispatcher replaces the result with an error object when the MessageFacade ends with errors,
            // and darpan-ui's client throws on any envelope with ok:false or a non-empty errors list. The
            // user-facing text therefore travels in `messages`.
            result.ok = true
            result.errors = []
            result.messages = [passwordChangeReason == PASSWORD_EXPIRED_REASON
                    ? PASSWORD_EXPIRED_MESSAGE : PASSWORD_CHANGE_REQUIRED_MESSAGE]
        }

        return result
    }

    /**
     * Mint the signed-in user's web login key and fill result as login#Session answers a sign-in: authenticated,
     * sessionInfo, authToken (also written as the persistent-login cookie), its type, header name and lifetime. Shared by
     * login#Session, verify#SignInCode and confirm#TwoFactorSetup (DAR-BE-264). Returns whether a key was issued.
     */
    static boolean issueSignedInSession(def ec, Map<String, Object> result) {
        // DAR-BE-261: the key starts with one idle window and slides on each request
        // (AuthSessionSupport.enforceWebSignInLifetime), never past sign-in + the absolute lifetime.
        String issuedAuthToken = ec?.user?.getLoginKey(AuthSessionSupport.initialLoginKeyHours(ec))?.toString()?.trim()
        if (!issuedAuthToken) {
            ec.message.addError("Unable to issue auth token")
            logoutCurrentSessionOnly(ec)
            result.authenticated = false
            return false
        }
        // Audit W5 #39 — augment header response with a persistent-login cookie. SPA
        // contract (header `login_key`) is unchanged; the cookie is the side channel that
        // survives localStorage loss. SameSite is resolved per request inside the helper.
        AuthSessionSupport.writePersistentLoginCookie(ec, issuedAuthToken)
        result.authenticated = true
        result.sessionInfo = TenantAccessSupport.buildSessionInfo(ec)
        result.authToken = issuedAuthToken
        result.authTokenType = "LOGIN_KEY"
        result.authTokenHeaderName = "login_key"
        // The absolute lifetime, not the idle window: darpan-ui drops its token at this time, so the
        // server's sliding idle check is what ends an idle sign-in sooner.
        result.authTokenExpiresInSeconds = resolveLoginKeyExpiresInSeconds(ec)
        return true
    }

    // ---- DAR-BE-264: two-factor sign-in ----

    /**
     * verify#SignInCode: the challenge token from login#Session with a current code or a recovery code. On success the
     * person is signed in (no second UserLoginHistory row: the password step wrote one) and the reply is login#Session's.
     * Every refusal is "That code didn't work." held to TwoFactorSupport.CODE_REFUSAL_FLOOR_MILLIS; challengeEnded says
     * the token is spent (expired, used, 5 wrong codes), so the client goes back to the password.
     */
    static Map<String, Object> verifySignInCode(def ec, Object challengeToken, Object code, Object recoveryCode) {
        long started = System.nanoTime()
        Map<String, Object> claim = TwoFactorSupport.claimSignInChallenge(ec, challengeToken, code, recoveryCode)
        Map<String, Object> result = [authenticated: false, challengeEnded: claim.challengeEnded == true]
        boolean signedIn = false
        if (claim.ok) {
            def account = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.security.UserAccount",
                    "pre-auth: the account a verified sign-in challenge belongs to").condition("userId", claim.userId).one()
            if (CliAuthSupport.canSignIn(ec, account)) {
                signedIn = ec.user.internalLoginUser((String) account.username, false) as boolean
            }
            if (signedIn) {
                signedIn = issueSignedInSession(ec, result)
                if (claim.recoveryCodesLeft != null) result.recoveryCodesLeft = claim.recoveryCodesLeft
            } else {
                result.challengeEnded = true
            }
        }
        if (!signedIn) {
            ec.message.clearAll()
            ec.message.addError(TwoFactorSupport.CODE_REFUSAL)
            TwoFactorSupport.holdToFloor(started)
        }
        result.putAll(FacadeSupport.envelope(ec))
        return result
    }

    /**
     * begin#TwoFactorSetup: signed in (a first setup needs the current password, a replacement a current code), or with
     * a setup challenge from login#Session. Returns the secret, the otpauth URI, the QR data URI and expiresInSeconds.
     */
    static Map<String, Object> beginTwoFactorSetup(def ec, Object challengeToken, Object currentPassword, Object code) {
        String userId = setupSubject(ec, challengeToken, currentPassword, code)
        Map<String, Object> result = [:]
        if (userId) result.putAll(TwoFactorSupport.beginSetup(ec, userId))
        result.putAll(FacadeSupport.envelope(ec))
        return result
    }

    /**
     * confirm#TwoFactorSetup: the first right code from the new app turns two-factor on, ends every other login key
     * (CLI keys too) and returns the recovery codes, once. The caller is then signed in with a fresh key: through a setup
     * challenge this is the sign-in itself; from a signed-in tab the old key predates the factor and stops working, so
     * the tab swaps to the authToken returned here.
     */
    static Map<String, Object> confirmTwoFactorSetup(def ec, Object challengeToken, Object code) {
        Map<String, Object> result = [authenticated: false]
        boolean viaChallenge = normalize(challengeToken) != null
        String userId = viaChallenge ? TwoFactorSupport.setupChallengeUser(ec, challengeToken) : normalize(ec.user.userId)
        if (!userId) {
            ec.message.addError(viaChallenge ? "This sign-in has expired. Enter your password again." : "Sign in first.")
            result.challengeEnded = viaChallenge
            result.putAll(FacadeSupport.envelope(ec))
            return result
        }
        Map<String, Object> confirmed = TwoFactorSupport.confirmSetup(ec, userId, code)
        if (!confirmed.ok) {
            if (viaChallenge) result.challengeEnded = TwoFactorSupport.countSetupChallengeFailure(ec, challengeToken)
            ec.message.addError(confirmed.expired ? TwoFactorSupport.SETUP_EXPIRED : TwoFactorSupport.CODE_REFUSAL)
            result.putAll(FacadeSupport.envelope(ec))
            return result
        }
        if (viaChallenge) {
            TwoFactorSupport.useChallenge(ec, challengeToken)
            def account = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.security.UserAccount",
                    "pre-auth: the account a setup challenge belongs to").condition("userId", userId).one()
            if (!CliAuthSupport.canSignIn(ec, account) || !ec.user.internalLoginUser((String) account.username, false)) {
                ec.message.clearAll()
                ec.message.addError("This account can no longer sign in.")
                result.putAll(FacadeSupport.envelope(ec))
                return result
            }
        }
        issueSignedInSession(ec, result)
        result.recoveryCodes = confirmed.recoveryCodes
        result.putAll(FacadeSupport.envelope(ec))
        return result
    }

    /** Who begin#TwoFactorSetup acts for: a setup challenge's person, else the signed-in person once they pass
     *  TwoFactorSupport.signedInSetupProblem. Null, with an error added, otherwise. */
    private static String setupSubject(def ec, Object challengeToken, Object currentPassword, Object code) {
        if (normalize(challengeToken)) {
            String userId = TwoFactorSupport.setupChallengeUser(ec, challengeToken)
            if (!userId) ec.message.addError("This sign-in has expired. Enter your password again.")
            return userId
        }
        String problem = TwoFactorSupport.signedInSetupProblem(ec, currentPassword, code)
        if (problem) {
            ec.message.clearAll()
            ec.message.addError(problem)
            return null
        }
        return normalize(ec.user.userId)
    }

    /** Reduce every non-recoverable login refusal to one indistinguishable answer.
     *
     *  MoquiShiroRealm names the account in all six of its refusals, and five of them are decided BEFORE the
     *  password is checked, so an anonymous caller could confirm an account exists without holding its
     *  password at all — and learn more besides:
     *
     *    "No account found for username X"                                    → X does not exist
     *    "Password incorrect for username X"                                  → X exists
     *    "...account is disabled and will not be re-enabled until T [DISTMP]" → X exists, is being attacked
     *    "...account is disabled...not schedule[d]...[DISPRM]"                → X exists, is deactivated
     *    "User account X was terminated at T [TERM]"                          → X existed, and when it left
     *    "...client IP C is not in allowed list for user or group"            → X exists and is IP-fenced
     *
     *  The framework already declines to reveal whether the password was right for a disabled account
     *  (loginPrePassword checks `disabled` first, deliberately); this closes the remaining half of the same
     *  question. The reason still goes to the server log, because support diagnosing "I cannot get in" needs
     *  to know which gate closed — it is the caller who must not be told.
     *
     *  NOT closed by this: an unknown username short-circuits before password hashing, so the reply comes
     *  back measurably sooner. That timing oracle needs a constant-time login path, not a message change. */
    private static void collapseLoginFailure(def ec, String usernameValue, String passwordValue,
                                             long attemptStartedNanos) {
        List<String> refusals = FacadeSupport.getErrors(ec)
        if (refusals) logger.info("Login refused for username [{}]: {}", usernameValue, refusals.join(" | "))
        burnSkippedPasswordHash(ec, refusals, usernameValue, passwordValue)
        holdRefusalToFloor(attemptStartedNanos)
        // clearAll(), not clearErrors(): clearErrors MOVES errors into the messages list, which would hand
        // the caller the same text under a different key.
        ec.message.clearAll()
        ec.message.addError(INVALID_CREDENTIALS_MESSAGE)
    }

    /** Spend the password hash the framework skipped, so a refusal cannot be identified by how fast it came
     *  back. Closes the other half of the enumeration oracle that collapseLoginFailure's wording fix leaves
     *  open: identical text is no use if one answer is reliably quicker.
     *
     *  MoquiShiroRealm decides "no such account", "disabled" and "terminated" in loginPrePassword, before it
     *  ever reaches the credentials matcher, so those replies skip the hash entirely and return sooner. This
     *  runs the same hash the matcher would have, against a decoy salt, and throws the result away.
     *
     *  Equalising the WORK rather than padding the CLOCK is deliberate. A fixed delay would be simpler and
     *  would mask more, but it holds a request thread and its open transaction for the whole floor on every
     *  failed login — during the enumeration this is meant to frustrate, that multiplies an attacker's grip
     *  on the connection pool by the ratio of the floor to a few milliseconds. A decoy hash is CPU-bound and
     *  holds nothing.
     *
     *  What this does NOT equalise: loginPrePassword falls through up to three entity lookups for a username
     *  it cannot find (exact, case-insensitive, then email) versus one for a hit, so lookup cost still varies
     *  by a smaller amount in the opposite direction. On SHA-256 hashing the two roughly cancel; with the
     *  PBKDF2 hash (DAR-BE-268) the floor rides above the measured hash cost instead (holdRefusalToFloor), which
     *  also covers an account still on SHA-256 until its next sign-in. A slower database would change the
     *  balance, so the residual is worth
     *  re-measuring against a production-like database rather than assumed to stay closed. The tests below
     *  pin the deterministic half of this — that both paths spend exactly one hash. */
    private static void burnSkippedPasswordHash(def ec, List<String> refusals, String usernameValue,
                                                String passwordValue) {
        boolean hashAlreadySpent = refusals.any { String refusal ->
            POST_PASSWORD_REFUSAL_MARKERS.any { marker -> refusal?.contains(marker) }
        }
        if (hashAlreadySpent) return
        try {
            // Drive the SAME matcher the real path uses rather than hashing directly: a bare SimpleHash
            // leaves out the matcher construction, salt byte-source conversion and digest comparison around
            // it, and measurement showed that shortfall still left the refusals ~0.8ms apart.
            def token = new UsernamePasswordToken(usernameValue ?: "", passwordValue ?: "")
            def info = new SimpleAuthenticationInfo(usernameValue ?: "", DECOY_STORED_CREDENTIAL,
                    new SimpleByteSource(DECOY_PASSWORD_SALT), "moquiRealm")
            long decoyStartedNanos = System.nanoTime()
            ec.ecfi.getCredentialsMatcher(decoyHashType(ec), false).doCredentialsMatch(token, info)
            recordPasswordHashCost((System.nanoTime() - decoyStartedNanos).intdiv(1_000_000L) as long)
        } catch (Exception e) {
            // Never fail a login response over the decoy; worst case the timing difference returns.
            logger.warn("Decoy credential check failed, refused-login timing may be distinguishable: ${e.message}")
        }
    }

    /** The hash type the decoy spends: the one a password set now gets (DAR-BE-268: PBKDF2, which every account
     *  moves to at its next sign-in), so an unknown username costs what a real account's check costs. Falls back
     *  to encrypt-hash-type on a framework without slow hashing. */
    private static String decoyHashType(def ec) {
        try {
            String newType = ec.ecfi.newPasswordHashType
            if (newType) return newType
        } catch (MissingPropertyException | MissingMethodException ignored) { }
        return ec.ecfi.passwordHashType
    }

    /** What one password hash costs here, smoothed over the decoys spent so far (0 until the first). The floor
     *  sits this far above LOGIN_REFUSAL_FLOOR_MILLIS: a slow hash (tens to hundreds of ms) would otherwise put
     *  every refusal past a fixed 25ms floor, leaving the residual it exists to hide uncovered. */
    private static volatile long passwordHashCostMillis = 0L

    private static void recordPasswordHashCost(long millis) {
        long previous = passwordHashCostMillis
        passwordHashCostMillis = previous == 0L ? millis : ((previous * 3L + millis).intdiv(4L) as long)
    }

    /** Hold a refusal open until the floor, so whatever the decoy could not equalise has nothing to show.
     *  The floor is LOGIN_REFUSAL_FLOOR_MILLIS above the measured cost of one hash, which both refusal paths spend.
     *
     *  Overshooting the floor is not an error but it does mean the masking has stopped working, so say so —
     *  a silent regression here looks exactly like a working defence. */
    private static void holdRefusalToFloor(long attemptStartedNanos) {
        long elapsedMillis = (System.nanoTime() - attemptStartedNanos).intdiv(1_000_000L)
        long floorMillis = LOGIN_REFUSAL_FLOOR_MILLIS + passwordHashCostMillis
        long remainingMillis = floorMillis - elapsedMillis
        if (remainingMillis <= 0) {
            logger.warn("Refused login took {}ms, past the {}ms floor — refusals are no longer held to a " +
                    "constant time and may again be distinguishable by how fast they return",
                    elapsedMillis, floorMillis)
            return
        }
        try {
            Thread.sleep(remainingMillis)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
        }
    }

    /** Null unless the failed login was rejected for a reason the user can clear themselves by setting a
     *  new password. See PASSWORD_CHANGE_REQUIRED_MARKER for why the message text is the signal. */
    private static String resolvePasswordChangeReason(def ec) {
        List<String> errors = FacadeSupport.getErrors(ec)
        if (errors.any { it?.contains(PASSWORD_CHANGE_REQUIRED_MARKER) }) return PASSWORD_CHANGE_REQUIRED_REASON
        if (errors.any { it?.contains(PASSWORD_EXPIRED_MARKER) }) return PASSWORD_EXPIRED_REASON
        return null
    }

    /** Change the password of an account that cannot sign in until it does so (PWDCHG or PWDTIM), without
     *  requiring a session — the whole point being that no session can exist until the password changes.
     *
     *  Three things this has to get right that the framework service underneath does not, because it was
     *  written for an authenticated caller:
     *
     *  1. update#Password reports "Could not find user with name X" and "Password incorrect for user X"
     *     through `<return public="true" type="danger"/>`, which calls addPublic — a plain message, not an
     *     error. Left alone those ride out on an ok:true envelope and make this a username-existence oracle.
     *  2. update#Password does not consult moqui.security.UserAccount.disabled, so an account the framework
     *     just locked after too many failed logins could reset its way straight past the lockout.
     *  3. update#Password does not count failed attempts, so an unguarded anonymous wrapper around it is an
     *     offline-speed password-guessing channel that never trips the max-failures lockout login enforces.
     *
     *  Password-policy complaints (too short, no digit, reused) are the one class of failure passed through
     *  verbatim: they are only reachable after the current password verified, so they tell an attacker
     *  nothing, and withholding them would leave the user guessing why their new password was refused. */
    static Map<String, Object> changeExpiredPassword(def ec, Object username, Object currentPassword,
                                                     Object newPassword, Object newPasswordVerify) {
        String usernameValue = normalize(username)
        String currentPasswordValue = currentPassword?.toString()
        String newPasswordValue = newPassword?.toString()
        String newPasswordVerifyValue = newPasswordVerify?.toString()

        if (!usernameValue) ec.message.addError("username is required")
        if (!currentPasswordValue) ec.message.addError("current password is required")
        if (!newPasswordValue) ec.message.addError("new password is required")
        if (!newPasswordVerifyValue) ec.message.addError("new password verification is required")

        boolean passwordUpdated = false
        if (!ec.message.hasError()) {
            def userAccount = findUserAccountForPasswordChange(ec, usernameValue)
            if (userAccount == null || "Y" == normalize(userAccount.disabled)) {
                rejectAnonymousPasswordChange(ec)
            } else {
                passwordUpdated = runFrameworkPasswordChange(ec, userAccount, currentPasswordValue,
                        newPasswordValue, newPasswordVerifyValue)
            }
        }

        Map<String, Object> result = [passwordUpdated: passwordUpdated]
        result.putAll(FacadeSupport.envelope(ec))
        return result
    }

    /** Exact username first, then case-insensitive, mirroring MoquiShiroRealm.loginPrePassword. Login accepts
     *  either casing, so resolving the account any more strictly here would strand a user who signed in
     *  successfully and then could not complete the change they were just told to make. */
    static def findUserAccountForPasswordChange(def ec, String usernameValue) {
        def finder = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.security.UserAccount",
                "self-scoped auth: resolve account for anonymous forced password change")
        def account = finder.condition("username", usernameValue).one()
        if (account != null) return account

        def caseInsensitiveFinder = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.security.UserAccount",
                "self-scoped auth: case-insensitive account lookup for anonymous forced password change")
        def conditionFactory = ec.entity?.conditionFactory
        if (conditionFactory == null) return null
        return caseInsensitiveFinder.condition(conditionFactory
                .makeCondition("username", EntityCondition.ComparisonOperator.EQUALS, usernameValue)
                .ignoreCase()).one()
    }

    private static boolean runFrameworkPasswordChange(def ec, def userAccount, String currentPasswordValue,
                                                      String newPasswordValue, String newPasswordVerifyValue) {
        Map updateOut = ec.service.sync().name("org.moqui.impl.UserServices.update#Password").parameters([
                userId           : userAccount.userId,
                oldPassword      : currentPasswordValue,
                newPassword      : newPasswordValue,
                newPasswordVerify: newPasswordVerifyValue,
        ]).call()

        if (updateOut?.updateSuccessful == true && !ec.message.hasError()) {
            // The framework success message names the account; darpan-ui renders its own confirmation copy.
            ec.message.clearAll()
            // Match change#OwnPassword: a password change invalidates every other device immediately.
            deleteAllLoginKeysForUser(ec, normalize(userAccount.userId))
            return true
        }

        if (updateOut?.passwordIssues == true) {
            promotePasswordPolicyMessagesToErrors(ec)
            return false
        }

        if (!ec.message.hasError()) {
            // No error and no policy complaint means update#Password bailed through one of its addPublic
            // returns: the account was not found or the current password did not match. Only this branch is
            // a credential guess, so only this branch feeds the framework's max-failures lockout. Its own
            // transaction, because the enclosing one is about to roll back.
            incrementFailedLogins(ec, normalize(userAccount.userId))
        }
        rejectAnonymousPasswordChange(ec)
        return false
    }

    /** update#PasswordInternal splits a policy rejection in two: the reasons ("Password shorter than 8
     *  characters", "Password was used in last 5 passwords") go to the messages list, while the error it
     *  actually fails with is the contentless "Found issues with password so not updating". A service that
     *  ends with errors is returned over JSON-RPC as an error object built from the error strings alone, so
     *  the messages are dropped and the user is told only that something was wrong. Swapping the reasons
     *  into the error position keeps the failure a failure while making it one the user can act on. */
    static void promotePasswordPolicyMessagesToErrors(def ec) {
        List<String> reasons = FacadeSupport.getMessages(ec).findAll { it }
        ec.message.clearAll()
        if (reasons) {
            reasons.each { ec.message.addError(it) }
        } else {
            ec.message.addError("That new password was refused. Choose a different one.")
        }
    }

    /** One indistinguishable answer for "no such account", "wrong current password", "locked out", and any
     *  other non-policy refusal, so the response cannot be mined for which of those is true. */
    private static void rejectAnonymousPasswordChange(def ec) {
        // clearAll(), not clearErrors(): clearErrors MOVES errors into the messages list rather than
        // discarding them, which would put the framework text back in the response under another key.
        ec.message.clearAll()
        ec.message.addError("Unable to change the password. Check the username and current password.")
    }

    static void incrementFailedLogins(def ec, String userId) {
        if (!userId) return
        try {
            ec.service.sync().name("org.moqui.impl.UserServices.increment#UserAccountFailedLogins")
                    .parameters([userId: userId]).requireNewTransaction(true).call()
        } catch (Exception e) {
            logger.warn("incrementFailedLogins failed for userId=${userId}: ${e.message}")
        }
    }

    static Map<String, Object> verifyOwnPassword(def ec, Object currentPassword) {
        String currentPasswordValue = currentPassword?.toString()

        boolean authenticated = normalize(ec?.user?.userId) != null
        boolean passwordVerified = false

        if (!authenticated) {
            ec.message.addError("Authentication required to verify password.")
        } else if (!currentPasswordValue) {
            ec.message.addError("Current password is required.")
        } else {
            def userAccount = ec?.user?.userAccount
            if (!userAccount) {
                userAccount = ec.entity.find("moqui.security.UserAccount")
                        .condition("userId", ec.user.userId)
                        .one()
            }

            String usernameValue = normalize(userAccount?.username) ?: normalize(ec?.user?.username)
            if (usernameValue && userAccount?.currentPassword) {
                def token = new UsernamePasswordToken(usernameValue, currentPasswordValue)
                def salt = userAccount?.passwordSalt ? new SimpleByteSource((String) userAccount.passwordSalt) : null
                def info = new SimpleAuthenticationInfo(usernameValue, userAccount.currentPassword, salt, "moquiRealm")
                passwordVerified = ec.ecfi
                        .getCredentialsMatcher(userAccount.passwordHashType, "Y".equals(userAccount.passwordBase64?.toString()))
                        .doCredentialsMatch(token, info)
            }
        }

        Map<String, Object> result = [
                authenticated   : authenticated,
                passwordVerified: passwordVerified,
        ]
        if (authenticated) result.sessionInfo = TenantAccessSupport.buildSessionInfo(ec)

        result.putAll(FacadeSupport.envelope(ec))
        return result
    }

    /** Delete every UserLoginKey row for a given userId; returns the count removed. Used by
     *  logoutAllSessions() and by change#OwnPassword to rotate every device after a password change.
     *  Audit M1.2 / M1.3 — previously logout only removed the current key and password changes
     *  did NOT invalidate other devices, so a live token on a compromised laptop kept working
     *  after the user changed their password. */
    static int deleteAllLoginKeysForUser(def ec, String userId) {
        if (!userId) return 0
        try {
            def deleted = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.security.UserLoginKey",
                            "self-scoped auth: delete all login keys for userId (logout-all path)")
                    .condition("userId", userId)
                    .deleteAll()
            return ((deleted ?: 0) as int)
        } catch (Exception e) {
            logger.warn("deleteAllLoginKeysForUser failed for userId=${userId}: ${e.message}")
            return 0
        }
    }

    static Map<String, Object> logoutAllSessions(def ec) {
        String userId = normalize(ec?.user?.userId)
        Map<String, Object> result = [authenticated: false, authTokenRevoked: false, allSessionsRevoked: 0]
        if (!userId) {
            ec?.message?.addError("Authentication required to revoke sessions.")
            result.putAll(FacadeSupport.envelope(ec))
            return result
        }
        int removed = deleteAllLoginKeysForUser(ec, userId)
        result.allSessionsRevoked = removed
        result.authTokenRevoked = removed > 0

        // Audit W5 #39 — `deleteAllLoginKeysForUser` already removed the row this cookie pointed
        // at, but the browser still holds the cookie value. Emit an expiring Set-Cookie so the
        // next request from this browser does not carry a usable-looking token.
        AuthSessionSupport.clearPersistentLoginCookie(ec)

        try {
            // logoutUser() (not logoutCurrentSessionOnly) is intentional here: the hasLoggedOut=Y
            // broadcast is what evicts other browsers' session-authenticated requests on this account.
            ec.user.logoutUser()
        } catch (Exception ignored) {
            def session = ec?.web?.request?.getSession(false)
            session?.invalidate()
        }

        result.putAll(FacadeSupport.envelope(ec))
        return result
    }

    static Map<String, Object> logoutSession(def ec) {
        def request = ec?.web?.request
        String requestToken = requestAuthToken(request)

        boolean authTokenRevoked = false
        if (requestToken) {
            String hashedKey = ec?.factory?.getSimpleHash(requestToken, "", ec?.factory?.getLoginKeyHashType(), false)
            if (hashedKey) {
                def deleted = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.security.UserLoginKey",
                                "self-scoped auth: logout by token hash — caller verified header token")
                        .condition("loginKey", hashedKey)
                        .deleteAll()
                authTokenRevoked = ((deleted ?: 0) as int) > 0
            }
        }

        // Audit W5 #39 — also revoke the cookie-side UserLoginKey row (the cookie can hold a
        // different token than the request header during the issuance window between login and
        // the SPA picking up the new header value) and emit an expiring Set-Cookie.
        if (AuthSessionSupport.revokePersistentLogin(ec)) {
            authTokenRevoked = true
        }
        AuthSessionSupport.clearPersistentLoginCookie(ec)

        logoutCurrentSessionOnly(ec)

        Map<String, Object> result = [
                authenticated   : false,
                authTokenRevoked: authTokenRevoked,
        ]
        result.putAll(FacadeSupport.envelope(ec))
        return result
    }

    /** End only the caller's session: local Shiro/user-stack logout plus servlet session
     *  invalidation. Deliberately avoids UserFacade.logoutUser(), which broadcasts
     *  hasLoggedOut=Y on the UserAccount row — with a shared account (UAT hotwax.user) that
     *  flag force-logs-out every other session at its next request, and the reset update it
     *  forces on the next auth serializes all logins on the single row (observed as 50s+
     *  lock-wait login failures). logout#AllSessions remains the revoke-everywhere path. */
    static void logoutCurrentSessionOnly(def ec) {
        try {
            ec.user.logoutLocal()
        } catch (Exception e) {
            logger.warn("logoutLocal failed during per-session logout, falling back to session invalidation only: ${e.message}")
        }
        def request = ec?.web?.request
        def session = request?.getSession(false)
        if (session != null) {
            session.invalidate()
            request.getSession(true)
        }
    }

    protected static Integer resolveLoginKeyExpiresInSeconds(def ec) {
        return AuthSessionSupport.resolveCookieMaxAgeSeconds(ec)
    }

    protected static String requestAuthToken(def request) {
        // Header-only. We deliberately do NOT fall back to request.getParameter("login_key" / "api_key"):
        // tokens in a URL query parameter land in webserver access logs, browser history, Referer headers
        // on cross-origin link clicks, and CDN logs — a passive leak surface that any operator with log
        // access can harvest. SameSite=None + Secure cookies (or the explicit header path) are the
        // intended carriers for the session token.
        return normalizeTokenValue(request?.getHeader("login_key") ?: request?.getHeader("api_key"))
    }

    protected static String normalizeTokenValue(Object value) {
        String normalized = normalize(value)
        if (!normalized || "null".equalsIgnoreCase(normalized) || "undefined".equalsIgnoreCase(normalized)) return null
        return normalized
    }
}
