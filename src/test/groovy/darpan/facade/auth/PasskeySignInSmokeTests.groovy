package darpan.facade.auth

import darpan.admin.AuditLogTestSupport
import darpan.facade.auth.support.SoftwarePasskey
import darpan.facade.common.CliMethodAllowlist
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-270 — passkeys as a second factor against a booted Moqui on H2, with a software authenticator
 * (support.SoftwarePasskey: JDK P-256 keys, webauthn4j-core converters). Relying party: localhost, origin
 * http://localhost:5173 (system properties). Covers registration (first factor and added), sign-in, the counter,
 * origin/rpId and challenge replay refusals, fresh proofs, removal rules, app-only removal and admin reset.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PasskeySignInSmokeTests {
    private ExecutionContext ec
    private long clock = 1_800_000_000_000L
    private Closure<Long> originalClock
    private Closure originalSendEmail
    private List<Map> sent = []

    private static final String USER_ID = "PK_USER"
    private static final String USERNAME = "pk.user"
    private static final String OTHER_ID = "PK_OTHER"
    private static final String OTHER_USERNAME = "pk.other"
    private static final String SUPER_ID = "PK_SUPER"
    private static final String SUPER_USERNAME = "pk.super"
    private static final String PASSWORD = "Pk-passw0rd-one!"
    private static final String REFUSAL = PasskeySupport.PASSKEY_REFUSAL

    @BeforeAll
    void setup() {
        System.setProperty("darpan.webauthn.rpId", "localhost")
        System.setProperty("darpan.webauthn.origins", "http://localhost:5173")
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "passkey-smoke")
        ["SecuritySeedData.xml", "PasswordResetEmailSeedData.xml", "TwoFactorSeedData.xml"].each {
            ec.entity.makeDataLoader().location("component://darpan/data/${it}".toString()).dummyFks(true).load()
        }
    }

    @AfterAll
    void cleanup() {
        System.clearProperty("darpan.webauthn.rpId")
        System.clearProperty("darpan.webauthn.origins")
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void reset() {
        originalClock = TwoFactorSupport.codeClock
        TwoFactorSupport.codeClock = { clock }
        originalSendEmail = PasswordResetSupport.sendEmail
        sent = []
        PasswordResetSupport.sendEmail = { def ecArg, String templateId, String to, Map params ->
            sent << [templateId: templateId, to: to, params: params]
        }
        ec.user.logoutUser()
        [PasskeySupport.CREDENTIAL, TwoFactorSupport.FACTOR, TwoFactorSupport.RECOVERY, TwoFactorSupport.CHALLENGE,
         "darpan.auth.CliSession", "darpan.auth.CliDeviceCode"].each { ec.entity.find(it).disableAuthz().deleteAll() }
        AuditLogTestSupport.wipe(ec, TwoFactorSupport.EVENT)
        AuditLogTestSupport.wipe(ec)
        TwoFactorSupport.POLICY_GROUP_IDS.each { setPolicy(it, null) }
        [[USER_ID, USERNAME], [OTHER_ID, OTHER_USERNAME], [SUPER_ID, SUPER_USERNAME]].each { upsertAccount(it[0], it[1]) }
        group(SUPER_ID, "DARPAN_SUPER_ADMIN")
        ec.message.clearAll()
    }

    @AfterEach
    void restore() {
        TwoFactorSupport.codeClock = originalClock
        PasswordResetSupport.sendEmail = originalSendEmail
        ec.user.logoutUser()
        ec.message.clearAll()
    }

    // ---- registration ----

    @Test
    @DisplayName("a first passkey turns two-factor on: recovery codes once, a fresh key, other keys end, an email")
    void firstPasskeyTurnsTwoFactorOn() {
        ec.user.internalLoginUser(USERNAME)
        String oldHash = AuthSessionSupport.hashLoginKey(ec, ec.user.getLoginKey(1.0f))
        ec.user.logoutUser()

        Map begun = signedIn(USERNAME, "begin#PasskeyRegistration", [currentPassword: PASSWORD])
        assertEquals([], begun.errors)
        Map options = (Map) new JsonSlurper().parseText(begun.out.publicKeyOptions as String)
        assertEquals("localhost", options.rp.id)
        assertEquals("required", options.authenticatorSelection.userVerification)
        assertEquals("preferred", options.authenticatorSelection.residentKey)
        assertEquals("none", options.attestation)
        assertEquals(PasskeySupport.userHandle(USER_ID), options.user.id)

        SoftwarePasskey key = new SoftwarePasskey()
        Map finished = signedIn(USERNAME, "finish#PasskeyRegistration",
                [credential: key.register(begun.out.publicKeyOptions as String), name: "Test Mac"], false)
        assertEquals([], finished.errors)
        assertEquals(10, (finished.out.recoveryCodes as List).size())
        assertNotNull(finished.out.authToken, "the tab swaps to a fresh key")
        assertNotNull(finished.out.passkeyId)
        assertNull(loginKey(oldHash), "the earlier key ended")

        Map status = TwoFactorSupport.status(ec, USER_ID)
        assertTrue(status.enabled as boolean)
        assertFalse(status.appEnabled as boolean)
        assertEquals(["Test Mac"], (status.passkeys as List<Map>)*.name)
        assertEquals(["internal", "hybrid"], (status.passkeys as List<Map>)[0].transports)
        assertEquals(10, status.recoveryCodesLeft)
        assertTrue(sent.any { it.templateId == PasskeySupport.TEMPLATE_PASSKEY_ADDED && it.params.passkeyName == "Test Mac" })
        def credential = ec.entity.find(PasskeySupport.CREDENTIAL).condition("userId", USER_ID).disableAuthz().one()
        assertEquals(key.id, credential.credentialId)
        assertFalse(status.toString().contains(key.id), "status never carries the credential id")
    }

    @Test
    @DisplayName("forced setup at sign-in can be a passkey: the setup challenge's finish is the sign-in")
    void forcedSetupWithAPasskey() {
        group(USER_ID, "DARPAN_ADMIN")
        setPolicy("DARPAN_ADMIN", new Timestamp(System.currentTimeMillis() - 1000L))
        Map login = anon("login#Session", [username: USERNAME, password: PASSWORD]).out
        assertTrue(login.twoFactorSetupRequired as boolean)
        assertEquals(true, login.passkeysAvailable, "a forced setup can offer a passkey")
        Map begun = anon("begin#PasskeyRegistration", [challengeToken: login.challengeToken])
        assertEquals([], begun.errors)
        Map finished = anon("finish#PasskeyRegistration", [challengeToken: login.challengeToken,
                credential: new SoftwarePasskey().register(begun.out.publicKeyOptions as String)])
        assertEquals([], finished.errors)
        assertTrue(finished.out.authenticated as boolean)
        assertEquals(USER_ID, finished.out.sessionInfo.userId)
        assertEquals(10, (finished.out.recoveryCodes as List).size())
        assertEquals(PasskeySupport.DEFAULT_NAME, (TwoFactorSupport.status(ec, USER_ID).passkeys as List<Map>)[0].name)
        Map reused = anon("begin#PasskeyRegistration", [challengeToken: login.challengeToken])
        assertTrue(reused.out.challengeEnded as boolean, "the setup challenge is spent")
        assertEquals("CHALLENGE_ENDED", reused.out.reason)
    }

    @Test
    @DisplayName("registration refuses a replayed challenge, a wrong origin, a wrong rpId, no user verification, and a credential someone already has")
    void registrationRefusals() {
        SoftwarePasskey key = new SoftwarePasskey()
        String options = beginRegistration(USERNAME, [currentPassword: PASSWORD])
        String credential = key.register(options)
        assertEquals([], finish(USERNAME, credential).errors)
        Map replay = finish(USERNAME, credential)
        assertEquals(["This passkey setup has expired. Start again."], replay.errors)

        String proofForNext = proofAssertion(USERNAME, key)
        String o2 = beginRegistration(USERNAME, [passkeyAssertion: proofForNext])
        assertEquals([REFUSAL], finish(USERNAME, new SoftwarePasskey().register(o2, [origin: "https://evil.example"])).errors)
        String o3 = beginRegistration(USERNAME, [passkeyAssertion: proofAssertion(USERNAME, key)])
        assertEquals([REFUSAL], finish(USERNAME, new SoftwarePasskey().register(o3, [rpId: "evil.example"])).errors)
        String o4 = beginRegistration(USERNAME, [passkeyAssertion: proofAssertion(USERNAME, key)])
        assertEquals([REFUSAL], finish(USERNAME, new SoftwarePasskey().register(o4, [userVerified: false])).errors)
        assertEquals(1, (TwoFactorSupport.status(ec, USER_ID).passkeys as List).size(), "none of those was stored")

        // The same authenticator credential cannot be live for two people.
        String other = beginRegistration(OTHER_USERNAME, [currentPassword: PASSWORD])
        assertEquals(["That passkey is already registered."], finish(OTHER_USERNAME, key.register(other)).errors)

        // A registration challenge is the person's: OTHER cannot finish USER's.
        String usersOptions = beginRegistration(USERNAME, [passkeyAssertion: proofAssertion(USERNAME, key)])
        assertEquals(["This passkey setup has expired. Start again."],
                finish(OTHER_USERNAME, new SoftwarePasskey().register(usersOptions)).errors)
    }

    // ---- sign-in ----

    @Test
    @DisplayName("sign-in with a passkey: methods say passkey, the assertion gets the key; a replayed assertion and a stale challenge are refused")
    void passkeySignIn() {
        SoftwarePasskey key = enrolPasskey(USERNAME)
        Map login = anon("login#Session", [username: USERNAME, password: PASSWORD]).out
        assertTrue(login.twoFactorRequired as boolean)
        assertEquals(["passkey"], login.twoFactorMethods)

        Map noBegin = anon("verify#SignInCode", [challengeToken: login.challengeToken, passkeyAssertion:
                key.assertion('{"challenge":"AAAA","rpId":"localhost"}')])
        assertEquals([REFUSAL], noBegin.errors, "no begin#PasskeySignIn, no challenge to sign")

        Map begun = anon("begin#PasskeySignIn", [challengeToken: login.challengeToken])
        assertEquals([], begun.errors)
        Map requestOptions = (Map) new JsonSlurper().parseText(begun.out.publicKeyOptions as String)
        assertEquals([key.id], (requestOptions.allowCredentials as List<Map>)*.id)
        assertEquals("required", requestOptions.userVerification)
        String assertion = key.assertion(begun.out.publicKeyOptions as String)
        Map verified = anon("verify#SignInCode", [challengeToken: login.challengeToken, passkeyAssertion: assertion])
        assertEquals([], verified.errors)
        assertTrue(verified.out.authenticated as boolean)
        assertNotNull(verified.out.authToken)
        assertEquals(1, events(USER_ID, "PASSKEY_OK").size())
        assertEquals(key.counter, credentialRow(USER_ID).signCount as long, "the counter is saved")

        Map replay = anon("verify#SignInCode", [challengeToken: login.challengeToken, passkeyAssertion: assertion])
        assertEquals([REFUSAL], replay.errors)
        assertEquals("PASSKEY", replay.out.reason, "a machine-readable reason, so the UIs never match the text")
        assertTrue(replay.out.challengeEnded as boolean, "the challenge was used")

        String token2 = challenge(USERNAME)
        anon("begin#PasskeySignIn", [challengeToken: token2])
        Map stale = anon("verify#SignInCode", [challengeToken: token2, passkeyAssertion: assertion])
        assertEquals([REFUSAL], stale.errors, "an assertion over an older challenge is refused")
        assertFalse(stale.out.challengeEnded as boolean)
        assertEquals(0, account(USER_ID).successiveFailedLogins as int, "a refused passkey is not a failed login")
    }

    @Test
    @DisplayName("a signature counter that goes backwards is refused and recorded")
    void counterRegressionRefused() {
        SoftwarePasskey key = enrolPasskey(USERNAME)
        String token = challenge(USERNAME)
        String options = anon("begin#PasskeySignIn", [challengeToken: token]).out.publicKeyOptions
        assertTrue(anon("verify#SignInCode", [challengeToken: token, passkeyAssertion: key.assertion(options, [counter: 5])]).out.authenticated as boolean)

        String token2 = challenge(USERNAME)
        String options2 = anon("begin#PasskeySignIn", [challengeToken: token2]).out.publicKeyOptions
        Map cloned = anon("verify#SignInCode", [challengeToken: token2, passkeyAssertion: key.assertion(options2, [counter: 3])])
        assertEquals([REFUSAL], cloned.errors)
        assertEquals(1, events(USER_ID, "PASSKEY_COUNTER_REGRESSED").size())
        assertEquals(5L, credentialRow(USER_ID).signCount as long, "the stored counter does not move back")
    }

    @Test
    @DisplayName("an assertion from another origin, for another rpId, or by another person's passkey is refused")
    void wrongOriginRpIdOrPersonRefused() {
        SoftwarePasskey key = enrolPasskey(USERNAME)
        SoftwarePasskey otherKey = enrolPasskey(OTHER_USERNAME)
        [[origin: "https://evil.example"], [rpId: "evil.example"]].each { Map override ->
            String token = challenge(USERNAME)
            String options = anon("begin#PasskeySignIn", [challengeToken: token]).out.publicKeyOptions
            Map refused = anon("verify#SignInCode", [challengeToken: token, passkeyAssertion: key.assertion(options, override)])
            assertEquals([REFUSAL], refused.errors, "${override}")
        }
        String token = challenge(USERNAME)
        String options = anon("begin#PasskeySignIn", [challengeToken: token]).out.publicKeyOptions
        Map crossed = anon("verify#SignInCode", [challengeToken: token, passkeyAssertion: otherKey.assertion(options)])
        assertEquals([REFUSAL], crossed.errors, "OTHER's passkey cannot finish USER's sign-in")
        assertTrue(events(USER_ID, "PASSKEY_FAILED").size() >= 3)
    }

    @Test
    @DisplayName("a person with the app and a passkey can use either; methods list both")
    void appAndPasskeyEitherWorks() {
        String secret = enrolApp(USERNAME)
        SoftwarePasskey key = new SoftwarePasskey()
        String options = beginRegistration(USERNAME, [code: code(secret)])
        Map added = finish(USERNAME, key.register(options))
        assertEquals([], added.errors)
        assertNull(added.out.authToken, "an added passkey changes no session")
        assertNull(added.out.recoveryCodes)

        Map login = anon("login#Session", [username: USERNAME, password: PASSWORD]).out
        assertEquals(["app", "passkey"], login.twoFactorMethods)
        assertTrue(anon("verify#SignInCode", [challengeToken: login.challengeToken, code: code(secret)]).out.authenticated as boolean)
        String token = challenge(USERNAME)
        String requestOptions = anon("begin#PasskeySignIn", [challengeToken: token]).out.publicKeyOptions
        assertTrue(anon("verify#SignInCode", [challengeToken: token, passkeyAssertion: key.assertion(requestOptions)]).out.authenticated as boolean)

        // The app alone can go while a passkey stays (appOnly), keeping the recovery codes.
        int codesBefore = TwoFactorSupport.status(ec, USER_ID).recoveryCodesLeft as int
        Map appOff = signedIn(USERNAME, "disable#TwoFactor", [appOnly: true, passkeyAssertion: proofAssertion(USERNAME, key)])
        assertEquals([], appOff.errors)
        Map status = TwoFactorSupport.status(ec, USER_ID)
        assertTrue(status.enabled as boolean)
        assertFalse(status.appEnabled as boolean)
        assertEquals(codesBefore, status.recoveryCodesLeft)
        assertEquals(1, events(USER_ID, "APP_REMOVED").size())
    }

    // ---- fresh proofs ----

    @Test
    @DisplayName("fresh-proof actions take a passkey assertion once: CLI approval, new recovery codes; a passkey-only person is asked for a proof")
    void freshProofAcceptsAnAssertion() {
        group(USER_ID, "DARPAN_ADMIN")
        SoftwarePasskey key = enrolPasskey(USERNAME)
        ec.user.logoutUser()
        Map started = ec.service.sync().name("facade.CliAuthFacadeServices.start#CliLogin").parameters([hostLabel: "pk-host"]).call()
        ec.message.clearAll()

        Map none = signedInCall(USERNAME, "facade.CliAuthFacadeServices.approve#CliLogin", [userCode: started.userCode])
        assertEquals([TwoFactorSupport.PROOF_NEEDED], none.errors)
        Map wrongCode = signedInCall(USERNAME, "facade.CliAuthFacadeServices.approve#CliLogin", [userCode: started.userCode, code: "123456"])
        assertEquals([TwoFactorSupport.PROOF_NEEDED], wrongCode.errors, "a code is no use without an app")

        String proof = proofAssertion(USERNAME, key)
        Map approved = signedInCall(USERNAME, "facade.CliAuthFacadeServices.approve#CliLogin", [userCode: started.userCode, passkeyAssertion: proof])
        assertEquals([], approved.errors)
        assertEquals("APPROVED", ec.entity.find("darpan.auth.CliDeviceCode").condition("userCode", started.userCode)
                .disableAuthz().useCache(false).one().statusId)

        Map reused = signedIn(USERNAME, "create#RecoveryCodes", [passkeyAssertion: proof])
        assertEquals([REFUSAL], reused.errors, "a proof is single-use")
        Map renewed = signedIn(USERNAME, "create#RecoveryCodes", [passkeyAssertion: proofAssertion(USERNAME, key)])
        assertEquals(10, (renewed.out.recoveryCodes as List).size())

        // A proof is the person's own: OTHER's passkey over USER's proof options is refused.
        SoftwarePasskey otherKey = enrolPasskey(OTHER_USERNAME)
        String usersOptions = signedIn(USERNAME, "begin#PasskeyProof", [:]).out.publicKeyOptions
        assertEquals([REFUSAL], signedIn(OTHER_USERNAME, "create#RecoveryCodes", [passkeyAssertion: otherKey.assertion(usersOptions)]).errors)
    }

    // ---- removing ----

    @Test
    @DisplayName("the last factor of a required person cannot be removed; an optional person's last removal turns two-factor off")
    void lastFactorRules() {
        SoftwarePasskey key = enrolPasskey(USERNAME)
        group(USER_ID, "DARPAN_ADMIN")
        setPolicy("DARPAN_ADMIN", new Timestamp(System.currentTimeMillis() - 1000L))
        String passkeyId = (TwoFactorSupport.status(ec, USER_ID).passkeys as List<Map>)[0].passkeyId
        Map refused = signedIn(USERNAME, "delete#Passkey", [passkeyId: passkeyId, passkeyAssertion: proofAssertion(USERNAME, key)])
        assertEquals([TwoFactorSupport.LAST_FACTOR_REQUIRED], refused.errors)
        assertTrue(TwoFactorSupport.enabled(ec, USER_ID))

        // With a second passkey the first can go; renaming needs no proof.
        SoftwarePasskey second = new SoftwarePasskey()
        assertEquals([], finish(USERNAME, second.register(beginRegistration(USERNAME, [passkeyAssertion: proofAssertion(USERNAME, key)]))).errors)
        assertEquals([], signedIn(USERNAME, "rename#Passkey", [passkeyId: passkeyId, name: "Old laptop"]).errors)
        assertEquals("Old laptop", (TwoFactorSupport.status(ec, USER_ID).passkeys as List<Map>)[0].name)
        Map removed = signedIn(USERNAME, "delete#Passkey", [passkeyId: passkeyId, passkeyAssertion: proofAssertion(USERNAME, key)])
        assertEquals([], removed.errors)
        assertEquals(true, removed.out.twoFactorEnabled)
        assertEquals(1, (TwoFactorSupport.status(ec, USER_ID).passkeys as List).size())

        // OTHER is optional: removing their only passkey turns two-factor off and drops the recovery codes.
        SoftwarePasskey otherKey = enrolPasskey(OTHER_USERNAME)
        String otherId = (TwoFactorSupport.status(ec, OTHER_ID).passkeys as List<Map>)[0].passkeyId
        assertEquals([PasskeySupport.NOT_FOUND], signedIn(USERNAME, "delete#Passkey", [passkeyId: otherId,
                passkeyAssertion: proofAssertion(USERNAME, second)]).errors, "not someone else's")
        Map off = signedIn(OTHER_USERNAME, "delete#Passkey", [passkeyId: otherId, passkeyAssertion: proofAssertion(OTHER_USERNAME, otherKey)])
        assertEquals([], off.errors)
        assertEquals(false, off.out.twoFactorEnabled)
        assertEquals(0, TwoFactorSupport.recoveryCodesLeft(ec, OTHER_ID))
        assertEquals(1, events(OTHER_ID, "TURNED_OFF").size())
        assertTrue(anon("login#Session", [username: OTHER_USERNAME, password: PASSWORD]).out.authenticated as boolean)
    }

    @Test
    @DisplayName("admin reset (proved with the admin's own passkey) ends the person's passkeys and keys")
    void adminResetEndsPasskeys() {
        enrolPasskey(USERNAME)
        SoftwarePasskey adminKey = enrolPasskey(SUPER_USERNAME)
        Map reset = signedInCall(SUPER_USERNAME, "admin.UserAdminServices.reset#TwoFactor", [userId: USER_ID, reason: "lost laptop",
                adminPasskeyAssertion: proofAssertion(SUPER_USERNAME, adminKey)])
        assertEquals([], reset.errors)
        assertFalse(TwoFactorSupport.enabled(ec, USER_ID))
        assertEquals("ADMIN_RESET", ec.entity.find(TwoFactorSupport.FACTOR).condition("userId", USER_ID).disableAuthz().one().endedReason)
        assertEquals(0, TwoFactorSupport.recoveryCodesLeft(ec, USER_ID))
        assertEquals(1, ec.entity.find("darpan.admin.AdminAuditLog").condition("serviceName", "admin.UserAdminServices.reset#TwoFactor")
                .condition("targetId", USER_ID).disableAuthz().useCache(false).count())
        Map detail = signedInCall(SUPER_USERNAME, "admin.UserAdminServices.get#UserDetail", [userId: SUPER_ID]).out.userDetail
        assertEquals("ON", detail.twoFactor)
        assertEquals(1, detail.twoFactorPasskeyCount)
        assertEquals(false, detail.twoFactorAppEnabled)
    }

    @Test
    @DisplayName("review QH1: adding a passkey, removing the oldest one, or removing the app while a passkey stays ends no key")
    void removingAFactorEndsNoKey() {
        // A passkey first: the tab's fresh key comes from the finish, a CLI key after it.
        SoftwarePasskey first = new SoftwarePasskey()
        Map enrolled = finish(USERNAME, first.register(beginRegistration(USERNAME, [currentPassword: PASSWORD])))
        String webKey = enrolled.out.authToken
        String cliHash = cliKey(USER_ID, USERNAME)
        int keys = keyCount(USER_ID)
        Thread.sleep(5)
        SoftwarePasskey second = new SoftwarePasskey()
        assertEquals([], finish(USERNAME, second.register(beginRegistration(USERNAME, [passkeyAssertion: proofAssertion(USERNAME, first)]))).errors)
        assertEquals(keys, keyCount(USER_ID), "adding a second passkey ends no key and mints none")
        Thread.sleep(5)
        Object stamp = account(USER_ID).sessionsEndedDate
        String oldestId = (TwoFactorSupport.status(ec, USER_ID).passkeys as List<Map>)[0].passkeyId
        assertEquals([], signedIn(USERNAME, "delete#Passkey", [passkeyId: oldestId, passkeyAssertion: proofAssertion(USERNAME, second)]).errors)
        assertKeysStillWork(USER_ID, webKey, cliHash, "after removing the oldest passkey")
        assertEquals(stamp, account(USER_ID).sessionsEndedDate, "adding or removing a non-last factor ends no sign-in")

        // The app first, then a passkey, then the app goes.
        Map begun = signedIn(OTHER_USERNAME, "begin#TwoFactorSetup", [currentPassword: PASSWORD])
        Map confirmed = signedIn(OTHER_USERNAME, "confirm#TwoFactorSetup", [code: code(begun.out.secret as String)], false)
        String otherWebKey = confirmed.out.authToken
        String otherCliHash = cliKey(OTHER_ID, OTHER_USERNAME)
        Thread.sleep(5)
        SoftwarePasskey otherKey = new SoftwarePasskey()
        assertEquals([], finish(OTHER_USERNAME, otherKey.register(beginRegistration(OTHER_USERNAME, [code: code(begun.out.secret as String)]))).errors)
        Thread.sleep(5)
        Object otherStamp = account(OTHER_ID).sessionsEndedDate
        assertEquals([], signedIn(OTHER_USERNAME, "disable#TwoFactor", [appOnly: true, passkeyAssertion: proofAssertion(OTHER_USERNAME, otherKey)]).errors)
        assertKeysStillWork(OTHER_ID, otherWebKey, otherCliHash, "after removing the app")
        assertEquals(otherStamp, account(OTHER_ID).sessionsEndedDate, "removing the app (appOnly) ends no sign-in")
    }

    /** A CLI key of the person, as approve#CliLogin leaves one: a login key and its CliSession. Returns its hash. */
    private String cliKey(String userId, String username) {
        ec.user.logoutUser()
        ec.user.internalLoginUser(username)
        String hash = AuthSessionSupport.hashLoginKey(ec, ec.user.getLoginKey(1.0f))
        ec.user.logoutUser()
        ec.entity.makeValue("darpan.auth.CliSession").setAll([loginKeyHash: hash, userId: userId, createdDate: ec.user.nowTimestamp]).create()
        return hash
    }

    /** The web key passes the per-request check (a stub request over the real entity facade) and the CLI key the CLI check. */
    private void assertKeysStillWork(String userId, String webKey, String cliHash, String when) {
        def request = new WebSignInLifetimeTests.RequestStub(headers: [login_key: webKey],
                session: new WebSignInLifetimeTests.SessionStub(creationTime: System.currentTimeMillis() - 60_000L))
        def user = new WebSignInLifetimeTests.UserStub(userId: userId, userAccount: account(userId).getMap())
        def webEc = new Expando(context: [:], web: new Expando(request: request, secureRequestParameters: [:]), user: user,
                entity: ec.entity, factory: ec.factory, transaction: ec.transaction)
        assertFalse(AuthSessionSupport.enforceWebSignInLifetime(webEc), "the web key ends nothing ${when}")
        assertEquals(userId, user.userId, "still signed in ${when}")
        def cliRow = ec.entity.find("darpan.auth.CliSession").condition("loginKeyHash", cliHash).disableAuthz().useCache(false).one()
        assertNotEquals(CliAuthSupport.CLI_SESSION_EXPIRED, CliAuthSupport.cliSessionProblem(ec, cliHash, cliRow), "the CLI key ${when}")
    }

    @Test
    @DisplayName("review QM3: adding the app to a passkey-only account keeps the recovery codes the passkey made")
    void addingTheAppKeepsRecoveryCodes() {
        SoftwarePasskey key = new SoftwarePasskey()
        Map enrolled = finish(USERNAME, key.register(beginRegistration(USERNAME, [currentPassword: PASSWORD])))
        List<String> codes = enrolled.out.recoveryCodes as List<String>
        assertEquals(10, codes.size())

        Map begun = signedIn(USERNAME, "begin#TwoFactorSetup", [passkeyAssertion: proofAssertion(USERNAME, key)])
        assertEquals([], begun.errors)
        Map confirmed = signedIn(USERNAME, "confirm#TwoFactorSetup", [code: code(begun.out.secret as String)], false)
        assertEquals([], confirmed.errors)
        assertNull(confirmed.out.recoveryCodes, "no new codes: a factor was already on")
        assertTrue(confirmed.out.authenticated as boolean, "still answered with a fresh sign-in")
        assertEquals(10, TwoFactorSupport.recoveryCodesLeft(ec, USER_ID))
        assertTrue(anon("verify#SignInCode", [challengeToken: challenge(USERNAME), recoveryCode: codes[0]]).out.authenticated as boolean,
                "the passkey's codes still sign in")
    }

    @Test
    @DisplayName("review L5: a required person's last factor is re-checked after the proof, so two removals at once cannot take both")
    void lastFactorIsRecheckedAfterTheProof() {
        SoftwarePasskey first = enrolPasskey(USERNAME)
        SoftwarePasskey second = new SoftwarePasskey()
        assertEquals([], finish(USERNAME, second.register(beginRegistration(USERNAME, [passkeyAssertion: proofAssertion(USERNAME, first)]))).errors)
        group(USER_ID, "DARPAN_ADMIN")
        setPolicy("DARPAN_ADMIN", new Timestamp(System.currentTimeMillis() - 1000L))
        List<String> ids = (TwoFactorSupport.status(ec, USER_ID).passkeys as List<Map>)*.passkeyId as List<String>
        String proof = proofAssertion(USERNAME, first)

        // While this removal proves itself, another one (of the other passkey) commits: as two tabs pressing Remove at once.
        Closure concurrentRemoval = { String factorId ->
            ec.entity.find(TwoFactorSupport.FACTOR).condition("factorId", factorId).disableAuthz().updateAll([statusId: TwoFactorSupport.ENDED])
        }
        TwoFactorSupport.metaClass.static.freshProofProblem = { def ecArg, Object c, Object a, boolean onlyIfEnrolled ->
            concurrentRemoval(ids[1])
            return null
        }
        try {
            Map refused = signedIn(USERNAME, "delete#Passkey", [passkeyId: ids[0], passkeyAssertion: proof])
            assertEquals([TwoFactorSupport.LAST_FACTOR_REQUIRED], refused.errors)
        } finally {
            GroovySystem.metaClassRegistry.removeMetaClass(TwoFactorSupport)
        }
        assertTrue(TwoFactorSupport.enabled(ec, USER_ID), "the required person keeps a factor")

        // The same for the app: app-only removal while the last passkey goes.
        String secret = enrolApp(OTHER_USERNAME)
        SoftwarePasskey otherKey = new SoftwarePasskey()
        assertEquals([], finish(OTHER_USERNAME, otherKey.register(beginRegistration(OTHER_USERNAME, [code: code(secret)]))).errors)
        group(OTHER_ID, "DARPAN_ADMIN")
        String otherPasskeyId = (TwoFactorSupport.status(ec, OTHER_ID).passkeys as List<Map>)[0].passkeyId
        TwoFactorSupport.metaClass.static.freshProofProblem = { def ecArg, Object c, Object a, boolean onlyIfEnrolled ->
            concurrentRemoval(otherPasskeyId)
            return null
        }
        try {
            Map refusedApp = signedIn(OTHER_USERNAME, "disable#TwoFactor", [appOnly: true, code: code(secret)])
            assertEquals([TwoFactorSupport.REQUIRED_CANNOT_TURN_OFF], refusedApp.errors)
        } finally {
            GroovySystem.metaClassRegistry.removeMetaClass(TwoFactorSupport)
        }
        assertTrue(TwoFactorSupport.status(ec, OTHER_ID).appEnabled as boolean, "the app stays")
    }

    @Test
    @DisplayName("review QH2: a new challenge sweeps only its own person's long-expired challenges, sign-in and passkey rows alike")
    void challengeHousekeepingIsThePersonsOwn() {
        SoftwarePasskey key = enrolPasskey(USERNAME)
        Timestamp twoDaysAgo = new Timestamp(System.currentTimeMillis() - 2 * 86_400_000L)
        Closure stale = { String userId, String purpose ->
            ec.entity.makeValue(TwoFactorSupport.CHALLENGE).setAll([userId: userId, tokenHash: AuthPrimitives.sha256Hex(UUID.randomUUID().toString()),
                    purposeEnumId: purpose, createdDate: twoDaysAgo, expiresDate: twoDaysAgo, failedAttempts: 0]).setSequencedIdPrimary().create()
        }
        Closure count = { String userId -> ec.entity.find(TwoFactorSupport.CHALLENGE).condition("userId", userId)
                .condition("expiresDate", twoDaysAgo).disableAuthz().useCache(false).count() as int }
        stale(USER_ID, TwoFactorSupport.PURPOSE_CODE)
        stale(OTHER_ID, TwoFactorSupport.PURPOSE_CODE)

        challenge(USERNAME)
        assertEquals(0, count(USER_ID), "the person's own stale row went")
        assertEquals(1, count(OTHER_ID), "nobody else's is touched by this person's sign-in")

        stale(USER_ID, PasskeySupport.PURPOSE_PROOF)
        proofAssertion(USERNAME, key)
        assertEquals(0, count(USER_ID), "a passkey ceremony sweeps too")
    }

    @Test
    @DisplayName("every new passkey method is off the CLI list")
    void passkeyMethodsAreNotOnTheCli() {
        ["begin#PasskeyProof", "begin#PasskeyRegistration", "begin#PasskeySignIn", "delete#Passkey",
         "finish#PasskeyRegistration", "rename#Passkey"].each {
            String name = "facade.AuthFacadeServices.${it}".toString()
            assertTrue(name in CliMethodAllowlist.EXCLUDED, name)
            assertFalse(name in CliMethodAllowlist.ALLOWED, name)
        }
    }

    // ---- helpers ----

    private SoftwarePasskey enrolPasskey(String username) {
        SoftwarePasskey key = new SoftwarePasskey()
        Map finished = finish(username, key.register(beginRegistration(username, [currentPassword: PASSWORD])))
        assertEquals([], finished.errors)
        ec.user.logoutUser()
        return key
    }

    private String enrolApp(String username) {
        Map begun = signedIn(username, "begin#TwoFactorSetup", [currentPassword: PASSWORD])
        assertEquals([], begun.errors)
        assertEquals([], signedIn(username, "confirm#TwoFactorSetup", [code: code(begun.out.secret as String)], false).errors)
        ec.user.logoutUser()
        return begun.out.secret
    }

    private String beginRegistration(String username, Map params) {
        Map begun = signedIn(username, "begin#PasskeyRegistration", params)
        assertEquals([], begun.errors, "begin#PasskeyRegistration")
        return begun.out.publicKeyOptions
    }

    private Map finish(String username, String credential) {
        return signedIn(username, "finish#PasskeyRegistration", [credential: credential])
    }

    /** A fresh-proof assertion: begin#PasskeyProof signed in as username, answered by key. */
    private String proofAssertion(String username, SoftwarePasskey key) {
        Map begun = signedIn(username, "begin#PasskeyProof", [:])
        assertEquals([], begun.errors, "begin#PasskeyProof")
        return key.assertion(begun.out.publicKeyOptions as String)
    }

    private String code(String secret) {
        clock += 30_000L
        return TotpSupport.code(TotpSupport.decodeSecret(secret), TotpSupport.timeStep(clock))
    }

    private String challenge(String username) {
        Map login = anon("login#Session", [username: username, password: PASSWORD]).out
        assertTrue(login.twoFactorRequired as boolean, "${login}")
        return login.challengeToken
    }

    private Map anon(String verbNoun, Map params) {
        ec.user.logoutUser()
        return call("facade.AuthFacadeServices.${verbNoun}".toString(), params)
    }

    private Map signedIn(String username, String verbNoun, Map params, boolean fresh = true) {
        if (fresh || ec.user.username != username) {
            ec.user.logoutUser()
            assertTrue(ec.user.internalLoginUser(username), "could not sign in ${username}")
        }
        return call("facade.AuthFacadeServices.${verbNoun}".toString(), params)
    }

    private Map signedInCall(String username, String serviceName, Map params) {
        ec.user.logoutUser()
        assertTrue(ec.user.internalLoginUser(username), "could not sign in ${username}")
        return call(serviceName, params)
    }

    private Map call(String serviceName, Map params) {
        ec.message.clearAll()
        Map out = [:]
        List<String> errors = []
        try {
            out = ec.service.sync().name(serviceName).parameters(params).call() ?: [:]
        } catch (Exception e) {
            errors << e.message
        }
        errors.addAll(ec.message.errors)
        if (out.ok == false) (out.errors ?: []).each { if (!(it in errors)) errors << it }
        ec.message.clearAll()
        return [out: out, errors: errors]
    }

    private List events(String userId, String type) {
        return ec.entity.find(TwoFactorSupport.EVENT).condition("userId", userId).condition("eventType", type)
                .disableAuthz().useCache(false).list()
    }

    private def credentialRow(String userId) {
        return ec.entity.find(PasskeySupport.CREDENTIAL).condition("userId", userId).disableAuthz().useCache(false).list()[0]
    }

    private int keyCount(String userId) {
        return (int) ec.entity.find("moqui.security.UserLoginKey").condition("userId", userId).disableAuthz().useCache(false).count()
    }

    private def loginKey(String hash) {
        return ec.entity.find("moqui.security.UserLoginKey").condition("loginKey", hash).disableAuthz().useCache(false).one()
    }

    private def account(String userId) {
        return ec.entity.find("moqui.security.UserAccount").condition("userId", userId).disableAuthz().useCache(false).one()
    }

    private void setPolicy(String groupId, Timestamp date) {
        ec.entity.makeValue(TwoFactorSupport.POLICY).setAll([permissionGroupId: groupId, requiredFromDate: date]).createOrUpdate()
    }

    private void group(String userId, String groupId) {
        ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: groupId, userId: userId,
                fromDate: new Timestamp(System.currentTimeMillis() - 60_000L)]).createOrUpdate()
    }

    private void upsertAccount(String userId, String username) {
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: userId, username: username,
                userFullName: username, emailAddress: "${username}@example.com".toString(), disabled: "N",
                disabledDateTime: null, requirePasswordChange: "N", hasLoggedOut: "N", successiveFailedLogins: 0,
                currentPassword: null, passwordSalt: null]).createOrUpdate()
        ec.entity.find("moqui.security.UserGroupMember").condition("userId", userId).disableAuthz().deleteAll()
        ec.entity.find("moqui.security.UserLoginKey").condition("userId", userId).disableAuthz().deleteAll()
        ec.entity.find("moqui.security.UserPasswordHistory").condition("userId", userId).disableAuthz().deleteAll()
        ec.service.sync().name("org.moqui.impl.UserServices.update#PasswordInternal")
                .parameters([userId: userId, newPassword: PASSWORD, newPasswordVerify: PASSWORD, requirePasswordChange: "N"]).disableAuthz().call()
        assertFalse(ec.message.hasError(), "fixture password refused: ${ec.message.errors} ${ec.message.messages}")
        ec.message.clearAll()
    }
}
