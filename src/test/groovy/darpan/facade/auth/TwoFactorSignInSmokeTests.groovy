package darpan.facade.auth

import darpan.admin.AuditLogTestSupport
import darpan.facade.common.CliMethodAllowlist
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.LoggerConfig
import org.apache.logging.log4j.core.config.Property
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext
import org.moqui.context.WebFacade
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.service.ServiceJsonRpcDispatcher

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-264 — two-factor sign-in against a booted Moqui on H2: the challenge after a right password, codes, recovery
 * codes, the per-role policy, the login-key rule, the fresh code at CLI approval, admin reset, and that the secret never
 * leaves begin#TwoFactorSetup's reply. TwoFactorSupport.codeClock is moved 30 seconds per code, so each code is a new
 * step (the replay check refuses a step twice); mail goes to PasswordResetSupport's recorder seams.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TwoFactorSignInSmokeTests {
    private ExecutionContext ec
    private long clock = 1_800_000_000_000L
    private Closure<Long> originalClock
    private Closure originalSendEmail
    private Closure originalSendResetCode
    private List<Map> sent = []

    private static final String USER_ID = "TF_USER"
    private static final String USERNAME = "tf.user"
    private static final String OTHER_ID = "TF_OTHER"
    private static final String OTHER_USERNAME = "tf.other"
    private static final String SUPER_ID = "TF_SUPER"
    private static final String SUPER_USERNAME = "tf.super"
    private static final String SUPER2_ID = "TF_SUPER2"
    private static final String SUPER2_USERNAME = "tf.super2"
    private static final String TADMIN_ID = "TF_TADMIN"
    private static final String TADMIN_USERNAME = "tf.tadmin"
    private static final String TENANT_B = "TF_TENANT_B"
    private static final String PASSWORD = "Tf-passw0rd-one!"
    private static final String NEW_PASSWORD = "Tf-passw0rd-two!"
    private static final String REFUSAL = "That code didn't work."

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "two-factor-smoke")
        ["SecuritySeedData.xml", "PasswordResetEmailSeedData.xml", "TwoFactorSeedData.xml"].each {
            ec.entity.makeDataLoader().location("component://darpan/data/${it}".toString()).dummyFks(true).load()
        }
        ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: TENANT_B, description: "TF tenant B"]).createOrUpdate()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void reset() {
        originalClock = TwoFactorSupport.codeClock
        TwoFactorSupport.codeClock = { clock }
        originalSendEmail = PasswordResetSupport.sendEmail
        originalSendResetCode = PasswordResetSupport.sendResetCode
        sent = []
        PasswordResetSupport.sendEmail = { def ecArg, String templateId, String to, Map params ->
            sent << [templateId: templateId, to: to, params: params]
        }
        PasswordResetSupport.sendResetCode = { def ecArg, String to, Map params ->
            sent << [templateId: PasswordResetSupport.TEMPLATE_RESET_CODE, to: to, params: params]
            return true
        }
        ec.user.logoutUser()
        [TwoFactorSupport.FACTOR, TwoFactorSupport.RECOVERY, TwoFactorSupport.CHALLENGE, "darpan.auth.CliSession",
         "darpan.auth.CliDeviceCode", "darpan.auth.PasswordResetCode"].each { ec.entity.find(it).disableAuthz().deleteAll() }
        AuditLogTestSupport.wipe(ec, TwoFactorSupport.EVENT)
        AuditLogTestSupport.wipe(ec)
        TwoFactorSupport.POLICY_GROUP_IDS.each { setPolicy(it, null) }
        [[USER_ID, USERNAME], [OTHER_ID, OTHER_USERNAME], [SUPER_ID, SUPER_USERNAME], [SUPER2_ID, SUPER2_USERNAME],
         [TADMIN_ID, TADMIN_USERNAME]].each { upsertAccount(it[0], it[1]) }
        group(SUPER_ID, "DARPAN_SUPER_ADMIN")
        group(SUPER2_ID, "DARPAN_SUPER_ADMIN")
        ec.entity.find("darpan.auth.TenantUserPermissionGroupMember").condition("userId", TADMIN_ID).disableAuthz().deleteAll()
        ec.message.clearAll()
    }

    @AfterEach
    void restore() {
        TwoFactorSupport.codeClock = originalClock
        PasswordResetSupport.sendEmail = originalSendEmail
        PasswordResetSupport.sendResetCode = originalSendResetCode
        ec.user.logoutUser()
        ec.message.clearAll()
    }

    // ---- sign-in ----

    @Test
    @DisplayName("an optional user with no factor signs in as before; sessionInfo says it is off")
    void optionalUserSignsInAsBefore() {
        Map login = anon("login#Session", [username: USERNAME, password: PASSWORD]).out
        assertTrue(login.authenticated as boolean)
        assertNotNull(login.authToken)
        assertFalse(login.twoFactorRequired as boolean)
        assertNull(login.challengeToken)
        assertEquals(false, login.sessionInfo.twoFactorEnabled)
        assertNull(login.sessionInfo.twoFactorDueDate)
    }

    @Test
    @DisplayName("enrolled: a right password gets no key, only a challenge; the right code gets the key")
    void enrolledSignInNeedsTheCode() {
        String secret = enrol(USER_ID, USERNAME).secret
        int keysBefore = keyCount(USER_ID)

        Map login = anon("login#Session", [username: USERNAME, password: PASSWORD])
        assertEquals([], login.errors)
        assertEquals(true, login.out.ok)
        assertFalse(login.out.authenticated as boolean)
        assertTrue(login.out.twoFactorRequired as boolean)
        assertFalse(login.out.twoFactorSetupRequired as boolean)
        assertNull(login.out.authToken)
        assertEquals(300, login.out.challengeExpiresInSeconds)
        assertEquals(keysBefore, keyCount(USER_ID), "no key before the code")

        Map verified = anon("verify#SignInCode", [challengeToken: login.out.challengeToken, code: code(secret)])
        assertEquals([], verified.errors)
        assertTrue(verified.out.authenticated as boolean)
        assertNotNull(verified.out.authToken)
        assertEquals("login_key", verified.out.authTokenHeaderName)
        assertEquals(USER_ID, verified.out.sessionInfo.userId)
        assertEquals(true, verified.out.sessionInfo.twoFactorEnabled)
        assertEquals(keysBefore + 1, keyCount(USER_ID))
        assertEquals(1, events(USER_ID, "CODE_OK").size(), "the sign-in's code is on the record")

        Map again = anon("verify#SignInCode", [challengeToken: login.out.challengeToken, code: code(secret)])
        assertEquals([REFUSAL], again.errors, "a used challenge is refused")
        assertTrue(again.out.challengeEnded as boolean)
    }

    @Test
    @DisplayName("the same code twice is refused (replay), an older step after a newer one too")
    void aCodeIsUsedOnce() {
        String secret = enrol(USER_ID, USERNAME).secret
        String token1 = challenge(USERNAME)
        String sameCode = code(secret)
        assertTrue(anon("verify#SignInCode", [challengeToken: token1, code: sameCode]).out.authenticated as boolean)
        String token2 = challenge(USERNAME)
        Map replay = anon("verify#SignInCode", [challengeToken: token2, code: sameCode])
        assertEquals([REFUSAL], replay.errors)
        assertFalse(replay.out.authenticated as boolean)
    }

    @Test
    @DisplayName("5 wrong codes end the challenge; each wrong code is a failed login; every refusal reads the same and waits the floor")
    void wrongCodesEndTheChallenge() {
        String secret = enrol(USER_ID, USERNAME).secret
        String token = challenge(USERNAME)
        long started = System.nanoTime()
        Map first = anon("verify#SignInCode", [challengeToken: token, code: "000000"])
        long elapsed = (System.nanoTime() - started).intdiv(1_000_000L)
        assertEquals([REFUSAL], first.errors)
        assertFalse(first.out.challengeEnded as boolean)
        assertTrue(elapsed >= TwoFactorSupport.CODE_REFUSAL_FLOOR_MILLIS, "held to the floor, took ${elapsed}ms")
        assertEquals(1, account(USER_ID).successiveFailedLogins as int, "a wrong code counts as a failed login")

        4.times { anon("verify#SignInCode", [challengeToken: token, code: "000000"]) }
        Map afterFive = anon("verify#SignInCode", [challengeToken: token, code: code(secret)])
        assertEquals([REFUSAL], afterFive.errors)
        assertTrue(afterFive.out.challengeEnded as boolean, "the fifth wrong code ended it")

        long bogusStarted = System.nanoTime()
        Map bogus = anon("verify#SignInCode", [challengeToken: "not-a-token", code: "123456"])
        assertEquals([REFUSAL], bogus.errors, "an unknown token reads the same")
        assertTrue((System.nanoTime() - bogusStarted).intdiv(1_000_000L) >= TwoFactorSupport.CODE_REFUSAL_FLOOR_MILLIS)
    }

    @Test
    @DisplayName("an expired challenge is refused, and one person's token cannot be finished with another's code")
    void tokensAreBoundToTheirPersonAndTime() {
        String secret = enrol(USER_ID, USERNAME).secret
        String otherSecret = enrol(OTHER_ID, OTHER_USERNAME).secret
        String token = challenge(USERNAME)
        Map crossed = anon("verify#SignInCode", [challengeToken: token, code: code(otherSecret)])
        assertEquals([REFUSAL], crossed.errors)
        assertFalse(crossed.out.authenticated as boolean)

        def row = ec.entity.find(TwoFactorSupport.CHALLENGE).condition("userId", USER_ID).disableAuthz().useCache(false).list()[0]
        row.expiresDate = new Timestamp(System.currentTimeMillis() - 1000L)
        row.update()
        Map expired = anon("verify#SignInCode", [challengeToken: token, code: code(secret)])
        assertEquals([REFUSAL], expired.errors)
        assertTrue(expired.out.challengeEnded as boolean)
    }

    @Test
    @DisplayName("a recovery code signs in once, says how many are left, and emails the person")
    void aRecoveryCodeWorksOnce() {
        List<String> codes = enrol(USER_ID, USERNAME).recoveryCodes
        assertEquals(10, codes.size())
        codes.each { assertTrue(it ==~ /^[a-z2-9]{4}-[a-z2-9]{4}-[a-z2-9]{4}-[a-z2-9]{4}$/, it) }
        assertEquals(10, codes.unique(false).size())

        Map used = anon("verify#SignInCode", [challengeToken: challenge(USERNAME), recoveryCode: " " + codes[0].toUpperCase().replace("-", " ") + " "])
        assertEquals([], used.errors)
        assertTrue(used.out.authenticated as boolean)
        assertEquals(9, used.out.recoveryCodesLeft)
        Map mail = sent.find { it.templateId == TwoFactorSupport.TEMPLATE_RECOVERY_USED }
        assertEquals("${USERNAME}@example.com".toString(), mail?.to)
        assertEquals(9, mail.params.recoveryCodesLeft)

        Map reused = anon("verify#SignInCode", [challengeToken: challenge(USERNAME), recoveryCode: codes[0]])
        assertEquals([REFUSAL], reused.errors)
    }

    @Test
    @DisplayName("20 wrong codes in 24 hours lock codes and email the person; a password reset clears the lock, not the factor")
    void twentyWrongCodesLock() {
        String secret = enrol(USER_ID, USERNAME).secret
        20.times {
            ec.transaction.runRequireNew(null, "test", { TwoFactorSupport.consumeTotp(ec, USER_ID, "000000") })
        }
        def factor = TwoFactorSupport.activeFactor(ec, USER_ID)
        assertNotNull(factor.codesLockedDate)
        assertEquals(1, sent.count { it.templateId == TwoFactorSupport.TEMPLATE_CODES_LOCKED })
        assertEquals(1, events(USER_ID, "CODES_LOCKED").size())
        // each wrong code was also a failed login; lift the framework's own lockout so the reset below can run
        ec.entity.find("moqui.security.UserAccount").condition("userId", USER_ID).disableAuthz()
                .updateAll([disabled: "N", disabledDateTime: null, successiveFailedLogins: 0])
        assertFalse((boolean) ec.transaction.runRequireNew(null, "test", { TwoFactorSupport.consumeTotp(ec, USER_ID, code(secret)) }),
                "a right code is refused while locked")

        assertEquals("sent", anon("request#PasswordReset", [usernameOrEmail: USERNAME]).out.outcome)
        String resetCode = sent.findAll { it.templateId == PasswordResetSupport.TEMPLATE_RESET_CODE }.last().params.code
        Map completed = anon("complete#PasswordReset", [usernameOrEmail: USERNAME, code: resetCode,
                newPassword: NEW_PASSWORD, newPasswordVerify: NEW_PASSWORD])
        assertTrue(completed.out.passwordUpdated as boolean, "${completed.errors}")
        factor = TwoFactorSupport.activeFactor(ec, USER_ID)
        assertNotNull(factor, "a reset never ends the factor")
        assertNull(factor.codesLockedDate)

        Map login = anon("login#Session", [username: USERNAME, password: NEW_PASSWORD]).out
        assertTrue(login.twoFactorRequired as boolean, "the reset does not bypass two-factor")
        assertTrue(anon("verify#SignInCode", [challengeToken: login.challengeToken, code: code(secret)]).out.authenticated as boolean)
    }

    // ---- policy ----

    @Test
    @DisplayName("a role required from a future date signs in with the due date; past the date with no factor gives only a setup challenge")
    void policyGraceThenRequired() {
        group(USER_ID, "DARPAN_ADMIN")
        Timestamp due = new Timestamp(System.currentTimeMillis() + 14L * 86_400_000L)
        setPolicy("DARPAN_ADMIN", due)
        Map grace = anon("login#Session", [username: USERNAME, password: PASSWORD]).out
        assertTrue(grace.authenticated as boolean)
        assertEquals(due, grace.sessionInfo.twoFactorDueDate)

        setPolicy("DARPAN_ADMIN", new Timestamp(System.currentTimeMillis() - 1000L))
        Map login = anon("login#Session", [username: USERNAME, password: PASSWORD])
        assertEquals(true, login.out.ok)
        assertFalse(login.out.authenticated as boolean)
        assertTrue(login.out.twoFactorSetupRequired as boolean)
        assertNull(login.out.authToken)
        String token = login.out.challengeToken

        Map notACode = anon("verify#SignInCode", [challengeToken: token, code: "123456"])
        assertEquals([REFUSAL], notACode.errors, "a setup challenge cannot be used as a code challenge")

        Map begun = anon("begin#TwoFactorSetup", [challengeToken: token])
        assertEquals([], begun.errors)
        assertTrue((begun.out.qrDataUri as String).startsWith("data:image/svg+xml;base64,"))
        assertTrue((begun.out.otpauthUri as String).startsWith("otpauth://totp/Darpan:tf.user?secret=${begun.out.secret}&issuer=Darpan".toString()))
        Map confirmed = anon("confirm#TwoFactorSetup", [challengeToken: token, code: code(begun.out.secret as String)])
        assertEquals([], confirmed.errors)
        assertTrue(confirmed.out.authenticated as boolean)
        assertNotNull(confirmed.out.authToken)
        assertEquals(10, (confirmed.out.recoveryCodes as List).size())
        assertEquals(true, TwoFactorSupport.status(ec, USER_ID).enabled)

        // signed in with a required factor: turning it off is refused
        assertEquals([TwoFactorSupport.REQUIRED_CANNOT_TURN_OFF], signedIn(USERNAME, "disable#TwoFactor", [code: code(begun.out.secret as String)]).errors)
    }

    @Test
    @DisplayName("tenant admin in tenant B only is required everywhere; a demoted tenant admin is not")
    void tenantAdminAnywhereIsRequired() {
        setPolicy("DARPAN_TENANT_ADMIN", new Timestamp(System.currentTimeMillis() - 1000L))
        ec.entity.makeValue("darpan.auth.TenantUserPermissionGroupMember").setAll([tenantUserGroupId: TENANT_B, userId: TADMIN_ID,
                permissionUserGroupId: "DARPAN_TENANT_ADMIN", fromDate: new Timestamp(System.currentTimeMillis() - 60_000L)]).create()
        assertTrue(TwoFactorSupport.isRequiredNow(ec, TADMIN_ID))
        assertTrue(anon("login#Session", [username: TADMIN_USERNAME, password: PASSWORD]).out.twoFactorSetupRequired as boolean)

        ec.entity.find("darpan.auth.TenantUserPermissionGroupMember").condition("userId", TADMIN_ID).disableAuthz()
                .updateAll([thruDate: new Timestamp(System.currentTimeMillis() - 1000L)])
        assertFalse(TwoFactorSupport.isRequiredNow(ec, TADMIN_ID))
        assertTrue(anon("login#Session", [username: TADMIN_USERNAME, password: PASSWORD]).out.authenticated as boolean)
    }

    @Test
    @DisplayName("super-admin enforcement needs two enrolled super admins; a policy change is on the admin trail")
    void superAdminPolicyNeedsTwoEnrolled() {
        Map refused = signedInAdmin(SUPER_USERNAME, "admin.SecurityAdminServices.update#TwoFactorPolicy",
                [permissionGroupId: "DARPAN_SUPER_ADMIN", requiredFromDate: ec.user.nowTimestamp])
        assertTrue(refused.errors.any { it.contains("At least 2 super admins") }, "${refused.errors}")
        String superSecret = enrol(SUPER_ID, SUPER_USERNAME).secret
        enrol(SUPER2_ID, SUPER2_USERNAME)
        Map ok = signedInAdmin(SUPER_USERNAME, "admin.SecurityAdminServices.update#TwoFactorPolicy",
                [permissionGroupId: "DARPAN_SUPER_ADMIN", requiredFromDate: ec.user.nowTimestamp, adminCode: code(superSecret)])
        assertEquals([], ok.errors)
        def audit = ec.entity.find("darpan.admin.AdminAuditLog").condition("targetType", "TwoFactorPolicy").disableAuthz().useCache(false).list()
        assertEquals(["DARPAN_SUPER_ADMIN"], audit*.targetId)

        Map board = signedInAdmin(SUPER_USERNAME, "admin.SecurityAdminServices.get#TwoFactorPolicies", [:]).out
        assertEquals(2, board.enrolledSuperAdminCount)
        Map superRow = (board.policies as List<Map>).find { it.permissionGroupId == "DARPAN_SUPER_ADMIN" }
        assertEquals(2, superRow.enrolledCount)
        assertEquals([SUPER_ID, SUPER2_ID] as Set, (superRow.enrolled as List<Map>)*.userId as Set)
    }

    // ---- keys and sessions ----

    @Test
    @DisplayName("confirming ends every other key (web and CLI) and every older web session, and issues a fresh key")
    void confirmEndsOtherKeys() {
        ec.user.internalLoginUser(USERNAME)
        String oldWebKey = ec.user.getLoginKey(1.0f)
        String oldHash = AuthSessionSupport.hashLoginKey(ec, oldWebKey)
        ec.entity.makeValue("darpan.auth.CliSession").setAll([loginKeyHash: oldHash, userId: USER_ID, createdDate: ec.user.nowTimestamp]).create()
        ec.user.logoutUser()
        long before = System.currentTimeMillis()

        Map enrolled = enrol(USER_ID, USERNAME)
        assertNull(loginKey(oldHash), "the earlier key ended")
        def cliRow = ec.entity.find("darpan.auth.CliSession").condition("loginKeyHash", oldHash).disableAuthz().one()
        assertNotNull(cliRow.revokedDate)
        assertEquals("This CLI session was signed out. Run darpan login to sign in again.", CliAuthSupport.cliSessionProblem(ec, oldHash, cliRow))
        assertNotNull(loginKey(AuthSessionSupport.hashLoginKey(ec, enrolled.authToken as String)), "the confirming tab gets a fresh key")
        // Web sessions without a key (a JSESSIONID alone) end at their next request: AuthSessionSupport compares this date.
        assertTrue((account(USER_ID).sessionsEndedDate as Timestamp).time >= before, "sessions ended at the confirm")
    }

    @Test
    @DisplayName("a required, not-enrolled person's CLI key is refused")
    void requiredNotEnrolledCliKeyRefused() {
        group(USER_ID, "DARPAN_ADMIN")
        ec.user.internalLoginUser(USERNAME)
        Map issued = CliAuthSupport.issueLoginKey(ec, USER_ID, "test")
        ec.user.logoutUser()
        def cliRow = ec.entity.makeValue("darpan.auth.CliSession").setAll([loginKeyHash: issued.hash, userId: USER_ID,
                createdDate: ec.user.nowTimestamp])
        cliRow.create()
        assertNull(CliAuthSupport.cliSessionProblem(ec, issued.hash as String, cliRow))
        setPolicy("DARPAN_ADMIN", new Timestamp(System.currentTimeMillis() - 1000L))
        assertEquals(CliAuthSupport.CLI_TWO_FACTOR_NEEDED, CliAuthSupport.cliSessionProblem(ec, issued.hash as String, cliRow))
    }

    @Test
    @DisplayName("approve#CliLogin needs a fresh code from an approver with two-factor on")
    void cliApprovalNeedsACode() {
        group(USER_ID, "DARPAN_ADMIN")
        String secret = enrol(USER_ID, USERNAME).secret
        ec.user.logoutUser()
        Map started = ec.service.sync().name("facade.CliAuthFacadeServices.start#CliLogin").parameters([hostLabel: "tf-host"]).call()
        ec.message.clearAll()

        Map noCode = signedInCall(USERNAME, "facade.CliAuthFacadeServices.approve#CliLogin", [userCode: started.userCode])
        assertEquals([TwoFactorSupport.CODE_NEEDED], noCode.errors)
        Map wrong = signedInCall(USERNAME, "facade.CliAuthFacadeServices.approve#CliLogin", [userCode: started.userCode, code: "000000"])
        assertEquals([REFUSAL], wrong.errors)
        assertEquals(1, events(USER_ID, "CODE_FAILED").size(), "the wrong code is counted although the call rolled back")
        Map right = signedInCall(USERNAME, "facade.CliAuthFacadeServices.approve#CliLogin", [userCode: started.userCode, code: code(secret)])
        assertEquals([], right.errors)
        assertEquals("APPROVED", ec.entity.find("darpan.auth.CliDeviceCode").condition("userCode", started.userCode)
                .disableAuthz().useCache(false).one().statusId)
    }

    // ---- signed-in management ----

    @Test
    @DisplayName("signed in: a first setup needs the password; replacing needs a code from the old app; new codes and turning off need a code")
    void signedInManagement() {
        Map noPassword = signedIn(USERNAME, "begin#TwoFactorSetup", [:])
        assertEquals(["Current password is required."], noPassword.errors)
        Map badPassword = signedIn(USERNAME, "begin#TwoFactorSetup", [currentPassword: "wrong-password-123"])
        assertEquals(["Current password is incorrect."], badPassword.errors)

        String secret = enrol(USER_ID, USERNAME).secret
        Map replaceNoCode = signedIn(USERNAME, "begin#TwoFactorSetup", [currentPassword: PASSWORD])
        assertEquals([TwoFactorSupport.CODE_NEEDED], replaceNoCode.errors)

        assertEquals([REFUSAL], signedIn(USERNAME, "create#RecoveryCodes", [code: "000000"]).errors)
        Map renewed = signedIn(USERNAME, "create#RecoveryCodes", [code: code(secret)])
        assertEquals(10, (renewed.out.recoveryCodes as List).size())
        assertEquals(10, signedIn(USERNAME, "get#TwoFactorStatus", [:]).out.recoveryCodesLeft)

        Map off = signedIn(USERNAME, "disable#TwoFactor", [code: code(secret)])
        assertEquals([], off.errors)
        assertEquals(false, TwoFactorSupport.status(ec, USER_ID).enabled)
        assertEquals(1, events(USER_ID, "TURNED_OFF").size())
        assertTrue(anon("login#Session", [username: USERNAME, password: PASSWORD]).out.authenticated as boolean)
    }

    @Test
    @DisplayName("the secret is in begin's reply only: not in status, sessionInfo, the user detail or the log")
    void theSecretIsShownOnce() {
        Capture capture = new Capture()
        LoggerContext context = (LoggerContext) LogManager.getContext(false)
        LoggerConfig root = context.configuration.rootLogger
        capture.start()
        root.addAppender(capture, Level.ALL, null)
        context.updateLoggers()
        Map enrolled
        try {
            enrolled = enrol(USER_ID, USERNAME)
            anon("verify#SignInCode", [challengeToken: challenge(USERNAME), code: code(enrolled.secret as String)])
        } finally {
            root.removeAppender(capture.name)
            context.updateLoggers()
            capture.stop()
        }
        String secret = enrolled.secret
        List<String> said = [capture.lines.join("\n"),
                signedIn(USERNAME, "get#TwoFactorStatus", [:]).out.toString(),
                signedIn(USERNAME, "get#SessionInfo", [:]).out.toString(),
                signedInAdmin(SUPER_USERNAME, "admin.UserAdminServices.get#UserDetail", [userId: USER_ID]).out.toString(),
                signedInAdmin(SUPER_USERNAME, "admin.UserAdminServices.get#UserList", [searchText: "tf.user"]).out.toString()]
        said.each { assertFalse(it.contains(secret), "secret leaked: ${it.take(400)}") }
        (enrolled.recoveryCodes as List<String>).each { String rc -> said.each { assertFalse(it.contains(rc)) } }
        assertEquals("ON", signedInAdmin(SUPER_USERNAME, "admin.UserAdminServices.get#UserDetail", [userId: USER_ID]).out.userDetail.twoFactor)
        Map listed = (signedInAdmin(SUPER_USERNAME, "admin.UserAdminServices.get#UserList", [searchText: "tf.user"]).out.users as List<Map>)
                .find { it.userId == USER_ID }
        assertEquals("ON", listed.twoFactor)
    }

    // ---- admin reset ----

    @Test
    @DisplayName("admin reset: super admins only, never their own, needs a reason and their own code; ends keys and leaves evidence")
    void adminReset() {
        enrol(USER_ID, USERNAME)
        Map notSuper = signedInAdmin(OTHER_USERNAME, "admin.UserAdminServices.reset#TwoFactor", [userId: USER_ID, reason: "lost phone", adminCode: "123456"])
        assertFalse(notSuper.errors.isEmpty(), "a non-super-admin is refused")

        Map unenrolledAdmin = signedInAdmin(SUPER_USERNAME, "admin.UserAdminServices.reset#TwoFactor", [userId: USER_ID, reason: "lost phone", adminCode: "123456"])
        assertTrue(unenrolledAdmin.errors.any { it.contains("Set up two-factor on your own account") }, "${unenrolledAdmin.errors}")

        String adminSecret = enrol(SUPER_ID, SUPER_USERNAME).secret
        Map own = signedInAdmin(SUPER_USERNAME, "admin.UserAdminServices.reset#TwoFactor", [userId: SUPER_ID, reason: "x", adminCode: code(adminSecret)])
        assertTrue(own.errors.any { it.contains("your own") }, "${own.errors}")
        Map noReason = signedInAdmin(SUPER_USERNAME, "admin.UserAdminServices.reset#TwoFactor", [userId: USER_ID, reason: " ", adminCode: code(adminSecret)])
        assertFalse(noReason.errors.isEmpty())

        ec.user.internalLoginUser(USERNAME)
        String liveKey = ec.user.getLoginKey(1.0f)
        ec.user.logoutUser()
        Map done = signedInAdmin(SUPER_USERNAME, "admin.UserAdminServices.reset#TwoFactor", [userId: USER_ID, reason: "lost phone", adminCode: code(adminSecret)])
        assertEquals([], done.errors)
        assertEquals(false, TwoFactorSupport.status(ec, USER_ID).enabled)
        assertNull(loginKey(AuthSessionSupport.hashLoginKey(ec, liveKey)), "every key ended")
        def audit = ec.entity.find("darpan.admin.AdminAuditLog").condition("serviceName", "admin.UserAdminServices.reset#TwoFactor")
                .disableAuthz().useCache(false).list()
        assertEquals(1, audit.size())
        assertEquals(["User", USER_ID, SUPER_ID, "Two-factor reset: lost phone"], [audit[0].targetType, audit[0].targetId, audit[0].adminUserId, audit[0].detailText])
        assertEquals([SUPER_ID], events(USER_ID, "ADMIN_RESET")*.byUserId)
        assertEquals(1, sent.count { it.templateId == TwoFactorSupport.TEMPLATE_RESET && it.to == "${USERNAME}@example.com".toString() })
        assertTrue(anon("login#Session", [username: USERNAME, password: PASSWORD]).out.authenticated as boolean, "optional: signs in without")
    }

    @Test
    @DisplayName("over /rpc/json a refused code is a result carrying challengeEnded (not a JSON-RPC error), and it issues no session")
    void rpcRefusalsCarryTheirFields() {
        enrol(USER_ID, USERNAME)
        String token = challenge(USERNAME)
        int keysBefore = keyCount(USER_ID)

        Map first = rpc("verify#SignInCode", [challengeToken: token, code: "000000"])
        assertNull(first.error, "${first}")
        assertEquals(7, first.id)
        assertEquals([ok: false, errors: [REFUSAL], messages: [], authenticated: false, challengeEnded: false, reason: "CODE"],
                (first.result as Map).subMap(["ok", "errors", "messages", "authenticated", "challengeEnded", "reason"]))
        assertNull(first.result.authToken)
        3.times { assertFalse(rpc("verify#SignInCode", [challengeToken: token, code: "000000"]).result.challengeEnded as boolean) }
        Map fifth = rpc("verify#SignInCode", [challengeToken: token, code: "000000"])
        assertNull(fifth.error)
        assertEquals(true, fifth.result.challengeEnded, "the fifth wrong code ends the challenge, and the browser sees it")
        assertEquals([REFUSAL], fifth.result.errors)
        assertEquals(keysBefore, keyCount(USER_ID), "no key for a refused code")
        assertNull(ec.user.userId, "no one signed in")
        assertEquals(5, account(USER_ID).successiveFailedLogins as int, "each wrong code still counts as a failed login")

        // A setup challenge: begin with a spent token, confirm with a wrong code.
        group(OTHER_ID, "DARPAN_ADMIN")
        setPolicy("DARPAN_ADMIN", new Timestamp(System.currentTimeMillis() - 1000L))
        String setupToken = anon("login#Session", [username: OTHER_USERNAME, password: PASSWORD]).out.challengeToken
        Map spent = rpc("begin#TwoFactorSetup", [challengeToken: "not-a-token"])
        assertNull(spent.error, "${spent}")
        assertEquals([false, ["This sign-in has expired. Enter your password again."], true, "CHALLENGE_ENDED"],
                [spent.result.ok, spent.result.errors, spent.result.challengeEnded, spent.result.reason])
        Map begun = rpc("begin#TwoFactorSetup", [challengeToken: setupToken])
        assertEquals([true, false], [begun.result.ok, begun.result.challengeEnded])
        Map wrongSetup = rpc("confirm#TwoFactorSetup", [challengeToken: setupToken, code: "000000"])
        assertNull(wrongSetup.error, "${wrongSetup}")
        assertEquals([false, [REFUSAL], false, false, "CODE"], [wrongSetup.result.ok, wrongSetup.result.errors,
                wrongSetup.result.challengeEnded, wrongSetup.result.authenticated, wrongSetup.result.reason])
        assertNull(wrongSetup.result.recoveryCodes)
        assertEquals(false, TwoFactorSupport.status(ec, OTHER_ID).enabled, "a refused confirm leaves two-factor off")
        assertEquals(0, keyCount(OTHER_ID))

        // Methods whose refusal carries no field stay JSON-RPC errors (review QM2: begin#PasskeyProof among them).
        Map unknown = rpc("verify#SignInCode", [code: "000000"])
        assertNotNull(unknown.error, "a missing required parameter is still a JSON-RPC error")
        Map proof = rpc("begin#PasskeyProof", [:], OTHER_USERNAME)
        assertNull(proof.result, "${proof}")
        assertEquals("You have no passkey on this account.", (proof.error?.message as String)?.trim())
    }

    @Test
    @DisplayName("DAR-BE-276: credentials inside a call's params sign no one in: one call, authUserAccount, a batch, in process")
    void parameterCredentialsSignNoOneIn() {
        enrol(USER_ID, USERNAME)

        // get#TwoFactorStatus answers for the signed-in person: the enrolled user's status would mean the params signed them in.
        Map single = rpc("get#TwoFactorStatus", [authUsername: USERNAME, authPassword: PASSWORD])
        assertNotEquals(true, single.result?.enabled, "a password inside params is not a sign-in: ${single}")
        Map viaAccount = rpc("get#TwoFactorStatus", [authUserAccount: [username: USERNAME, currentPassword: PASSWORD]])
        assertNotEquals(true, viaAccount.result?.enabled, "nor inside authUserAccount: ${viaAccount}")

        // A batch runs every call but Moqui's dispatcher sends no batch reply, so the proof is a sign-in's own side effect:
        // a successful password check resets the account's failed-login count, in the call's transaction.
        ec.entity.find("moqui.security.UserAccount").condition("userId", OTHER_ID).disableAuthz().updateAll([successiveFailedLogins: 2])
        rpcRequest([_requestBodyJsonList: [[method: "facade.AuthFacadeServices.get#TwoFactorStatus",
                params: [authUsername: OTHER_USERNAME, authPassword: PASSWORD], id: 1]]])
        assertEquals(2, account(OTHER_ID).successiveFailedLogins as int, "no password was checked inside a batch call")

        // Not only the web path: a service call in process never signs in from its parameters either.
        ec.user.logoutUser()
        Map inProcess = call("facade.AuthFacadeServices.get#TwoFactorStatus", [authUsername: USERNAME, authPassword: PASSWORD])
        assertNotEquals(true, inProcess.out.enabled, "${inProcess}")
        assertNull(ec.user.userId)
    }

    @Test
    @DisplayName("review H1: login#Session fails closed: if the two-factor step throws after the password, no one stays signed in")
    void loginFailsClosed() {
        enrol(USER_ID, USERNAME)
        int keysBefore = keyCount(USER_ID)
        TwoFactorSupport.metaClass.static.signInStep = { def ecArg, String userId -> throw new IllegalStateException("forced") }
        try {
            Map login = anon("login#Session", [username: USERNAME, password: PASSWORD])
            assertFalse(login.errors.isEmpty(), "the call failed: ${login}")
            assertNull(ec.user.userId, "the password step's sign-in did not outlive the failure")
            assertEquals(keysBefore, keyCount(USER_ID))
        } finally {
            GroovySystem.metaClassRegistry.removeMetaClass(TwoFactorSupport)
        }
    }

    @Test
    @DisplayName("review H1/L: a client IP is stored only as an IP literal; an oversized proxy header cannot break the sign-in step")
    void clientIpIsStoredOnlyWhenItIsAnIp() {
        enrol(USER_ID, USERNAME)
        def user = ec.user
        try {
            user.@clientIpInternal = "9" * 300
            Map login = anon("login#Session", [username: USERNAME, password: PASSWORD])
            assertEquals([], login.errors)
            assertTrue(login.out.twoFactorRequired as boolean, "${login}")
            def row = ec.entity.find(TwoFactorSupport.CHALLENGE).condition("userId", USER_ID).orderBy("-createdDate")
                    .disableAuthz().useCache(false).list().first()
            assertNull(row.requestIp, "not an IP: not stored")

            user.@clientIpInternal = "2001:db8::7"
            assertTrue(anon("login#Session", [username: USERNAME, password: PASSWORD]).out.twoFactorRequired as boolean)
            def ipv6 = ec.entity.find(TwoFactorSupport.CHALLENGE).condition("userId", USER_ID).orderBy("-createdDate")
                    .disableAuthz().useCache(false).list().first()
            assertEquals("2001:db8::7", ipv6.requestIp)
            assertEquals(["203.0.113.7", null, null, null], ["203.0.113.7", "203.0.113.7, 10.0.0.1", "not-an-ip", ""].collect {
                user.@clientIpInternal = it
                TwoFactorSupport.recordedIp(ec)
            })
        } finally {
            user.@clientIpInternal = null
        }
    }

    @Test
    @DisplayName("review M1: a setup challenge stops working once the person has a factor: begin, confirm and passkey finish refuse and spend it")
    void aStaleSetupChallengeIsRefused() {
        group(OTHER_ID, "DARPAN_ADMIN")
        setPolicy("DARPAN_ADMIN", new Timestamp(System.currentTimeMillis() - 1000L))
        String token = anon("login#Session", [username: OTHER_USERNAME, password: PASSWORD]).out.challengeToken
        Map begun = anon("begin#TwoFactorSetup", [challengeToken: token])
        assertEquals([], begun.errors)

        // Meanwhile the person turns two-factor on another way (here: a passkey row, as finish#PasskeyRegistration writes).
        Timestamp now = new Timestamp(System.currentTimeMillis())
        ec.entity.makeValue(TwoFactorSupport.FACTOR).setAll([userId: OTHER_ID, factorTypeEnumId: PasskeySupport.TYPE_PASSKEY,
                statusId: TwoFactorSupport.ACTIVE, createdDate: now, confirmedDate: now, failedCodeCount: 0]).setSequencedIdPrimary().create()

        Map confirmed = anon("confirm#TwoFactorSetup", [challengeToken: token, code: code(begun.out.secret as String)])
        assertEquals(["This sign-in has expired. Enter your password again."], confirmed.errors)
        assertEquals("CHALLENGE_ENDED", confirmed.out.reason)
        assertTrue(confirmed.out.challengeEnded as boolean)
        assertNull(confirmed.out.authToken)
        assertNull(confirmed.out.recoveryCodes)
        assertEquals(false, TwoFactorSupport.status(ec, OTHER_ID).appEnabled, "the stale setup put no app on the account")
        assertEquals(0, keyCount(OTHER_ID))
        assertNotNull(ec.entity.find(TwoFactorSupport.CHALLENGE).condition("tokenHash", AuthPrimitives.sha256Hex(token))
                .disableAuthz().useCache(false).one().usedDate, "the challenge is spent")

        // A fresh setup challenge cannot exist for an enrolled person; one made before they enrolled is refused at begin too.
        TwoFactorSupport.FACTOR.with { ec.entity.find(it).condition("userId", OTHER_ID).disableAuthz().deleteAll() }
        String second = anon("login#Session", [username: OTHER_USERNAME, password: PASSWORD]).out.challengeToken
        ec.entity.makeValue(TwoFactorSupport.FACTOR).setAll([userId: OTHER_ID, factorTypeEnumId: PasskeySupport.TYPE_PASSKEY,
                statusId: TwoFactorSupport.ACTIVE, createdDate: now, confirmedDate: now, failedCodeCount: 0]).setSequencedIdPrimary().create()
        Map lateBegin = anon("begin#TwoFactorSetup", [challengeToken: second])
        assertEquals(["This sign-in has expired. Enter your password again."], lateBegin.errors)
        assertTrue(lateBegin.out.challengeEnded as boolean)
        assertNull(lateBegin.out.secret)
        Map lateFinish = anon("finish#PasskeyRegistration", [challengeToken: second, credential: "{}"])
        assertTrue(lateFinish.out.challengeEnded as boolean, "${lateFinish}")
    }

    @Test
    @DisplayName("review M3: a sign-in after a challenge starts a new servlet session: verify#SignInCode and a setup confirm")
    void challengeSignInsStartANewSession() {
        List<String> signedInFresh = []
        AuthSessionSupport.metaClass.static.signInOnNewSession = { def ecArg, String username, boolean saveHistory ->
            signedInFresh << username
            return ecArg.user.internalLoginUser(username, saveHistory) as boolean
        }
        try {
            String secret = enrol(USER_ID, USERNAME).secret
            assertTrue(anon("verify#SignInCode", [challengeToken: challenge(USERNAME), code: code(secret)]).out.authenticated as boolean)

            group(OTHER_ID, "DARPAN_ADMIN")
            setPolicy("DARPAN_ADMIN", new Timestamp(System.currentTimeMillis() - 1000L))
            String token = anon("login#Session", [username: OTHER_USERNAME, password: PASSWORD]).out.challengeToken
            String otherSecret = anon("begin#TwoFactorSetup", [challengeToken: token]).out.secret
            assertTrue(anon("confirm#TwoFactorSetup", [challengeToken: token, code: code(otherSecret)]).out.authenticated as boolean)

            assertEquals([USERNAME, OTHER_USERNAME], signedInFresh)
        } finally {
            GroovySystem.metaClassRegistry.removeMetaClass(AuthSessionSupport)
        }
    }

    @Test
    @DisplayName("review M4: changing the two-factor policy (setting a date or clearing it) needs the acting admin's fresh proof")
    void policyChangeNeedsAFreshProof() {
        Map unenrolled = signedInAdmin(SUPER_USERNAME, "admin.SecurityAdminServices.update#TwoFactorPolicy",
                [permissionGroupId: "DARPAN_TENANT_ADMIN", requiredFromDate: ec.user.nowTimestamp])
        assertEquals(["Set up two-factor on your own account before changing the two-factor policy."], unenrolled.errors)

        String secret = enrol(SUPER_ID, SUPER_USERNAME).secret
        setPolicy("DARPAN_TENANT_ADMIN", new Timestamp(System.currentTimeMillis() - 1000L))
        Map noCode = signedInAdmin(SUPER_USERNAME, "admin.SecurityAdminServices.update#TwoFactorPolicy",
                [permissionGroupId: "DARPAN_TENANT_ADMIN", clear: true])
        assertEquals([TwoFactorSupport.CODE_NEEDED], noCode.errors, "a session alone cannot switch enforcement off")
        Map wrong = signedInAdmin(SUPER_USERNAME, "admin.SecurityAdminServices.update#TwoFactorPolicy",
                [permissionGroupId: "DARPAN_TENANT_ADMIN", clear: true, adminCode: "000000"])
        assertEquals([REFUSAL], wrong.errors)
        assertNotNull(ec.entity.find(TwoFactorSupport.POLICY).condition("permissionGroupId", "DARPAN_TENANT_ADMIN")
                .disableAuthz().useCache(false).one().requiredFromDate, "still required after the refusals")

        Map cleared = signedInAdmin(SUPER_USERNAME, "admin.SecurityAdminServices.update#TwoFactorPolicy",
                [permissionGroupId: "DARPAN_TENANT_ADMIN", clear: true, adminCode: code(secret)])
        assertEquals([], cleared.errors)
        assertNull(ec.entity.find(TwoFactorSupport.POLICY).condition("permissionGroupId", "DARPAN_TENANT_ADMIN")
                .disableAuthz().useCache(false).one().requiredFromDate)
    }

    @Test
    @DisplayName("review M5: a wrong current password while signed in is one failed login, wherever it is checked")
    void wrongCurrentPasswordsCountOnce() {
        Closure failures = { account(USER_ID).successiveFailedLogins as int }
        Closure fresh = { ec.entity.find("moqui.security.UserAccount").condition("userId", USER_ID).disableAuthz()
                .updateAll([successiveFailedLogins: 0, disabled: "N", disabledDateTime: null]) }

        fresh()
        Map verified = signedIn(USERNAME, "verify#OwnPassword", [currentPassword: "wrong-password"])
        assertFalse(verified.out.passwordVerified as boolean)
        assertEquals(1, failures(), "verify#OwnPassword")

        fresh()
        Map changed = signedIn(USERNAME, "change#OwnPassword", [currentPassword: "wrong-password", newPassword: NEW_PASSWORD,
                newPasswordVerify: NEW_PASSWORD])
        assertEquals(["Current password is incorrect."], changed.errors)
        assertEquals(1, failures(), "change#OwnPassword")

        fresh()
        Map email = signedIn(USERNAME, "save#RecoveryEmail", [emailAddress: "tf.user.new@example.com", currentPassword: "wrong-password"])
        assertEquals(["Current password is incorrect."], email.errors)
        assertEquals(1, failures(), "save#RecoveryEmail, not twice")

        fresh()
        Map setup = signedIn(USERNAME, "begin#TwoFactorSetup", [currentPassword: "wrong-password"])
        assertEquals(["Current password is incorrect."], setup.errors)
        assertEquals("PASSWORD", setup.out.reason)
        assertEquals(1, failures(), "begin#TwoFactorSetup, not twice")

        fresh()
        assertTrue(signedIn(USERNAME, "verify#OwnPassword", [currentPassword: PASSWORD]).out.passwordVerified as boolean)
        assertEquals(0, failures(), "a right password counts nothing")
    }

    @Test
    @DisplayName("TOCTOU: a key or CLI session minted just before a two-factor change ended sign-ins, but stored after, is refused")
    void aKeyRacingEndSessionsIsRefused() {
        group(USER_ID, "DARPAN_ADMIN") // the CLI is on for Darpan admins, so only the key rule can refuse
        Map enrolled = enrol(USER_ID, USERNAME)
        Timestamp ended = account(USER_ID).sessionsEndedDate as Timestamp
        assertNotNull(ended)

        // As a concurrent sign-in or CLI poll would leave it: fromDate before the stamp, row written after the delete.
        ec.user.internalLoginUser(USERNAME)
        String raw = ec.user.getLoginKey(1.0f)
        ec.user.logoutUser()
        String hash = AuthSessionSupport.hashLoginKey(ec, raw)
        Timestamp justBefore = new Timestamp(ended.time - 1)
        ec.entity.find("moqui.security.UserLoginKey").condition("loginKey", hash).disableAuthz().updateAll([fromDate: justBefore])
        ec.entity.makeValue("darpan.auth.CliSession").setAll([loginKeyHash: hash, userId: USER_ID, createdDate: justBefore]).create()
        def cliRow = ec.entity.find("darpan.auth.CliSession").condition("loginKeyHash", hash).disableAuthz().one()
        assertEquals(CliAuthSupport.CLI_SESSION_EXPIRED, CliAuthSupport.cliSessionProblem(ec, hash, cliRow))
        ec.entity.find("darpan.auth.CliSession").condition("loginKeyHash", hash).disableAuthz().deleteAll()
        assertTrue(AuthSessionSupport.enforceWebSignInLifetime(webEc(USER_ID, raw)), "the raced web key ends the sign-in")
        assertFalse(AuthSessionSupport.enforceWebSignInLifetime(webEc(USER_ID, enrolled.authToken as String)), "the confirm's fresh key passes")
    }

    @Test
    @DisplayName("turning two-factor off ends every key, this tab's too, and hands this tab a fresh one; an approved, unclaimed CLI request is denied")
    void turningOffReissuesThisTabsKey() {
        group(USER_ID, "DARPAN_ADMIN") // the CLI is on for Darpan admins (the role stays optional)
        String secret = enrol(USER_ID, USERNAME).secret
        ec.user.logoutUser()
        Map started = ec.service.sync().name("facade.CliAuthFacadeServices.start#CliLogin").parameters([hostLabel: "tf-host"]).call()
        ec.message.clearAll()
        assertEquals([], signedInCall(USERNAME, "facade.CliAuthFacadeServices.approve#CliLogin", [userCode: started.userCode, code: code(secret)]).errors)

        ec.user.logoutUser()
        ec.user.internalLoginUser(USERNAME)
        String tabKey = ec.user.getLoginKey(1.0f)
        Map off = call("facade.AuthFacadeServices.disable#TwoFactor", [code: code(secret)])
        assertEquals([], off.errors)
        assertTrue(off.out.authenticated as boolean)
        assertNotNull(off.out.authToken, "this tab swaps to the fresh key")
        assertNull(loginKey(AuthSessionSupport.hashLoginKey(ec, tabKey)), "the old one is gone with the rest")
        assertEquals(1, keyCount(USER_ID))
        assertFalse(AuthSessionSupport.enforceWebSignInLifetime(webEc(USER_ID, off.out.authToken as String)))
        assertEquals("DENIED", ec.entity.find("darpan.auth.CliDeviceCode").condition("userCode", started.userCode)
                .disableAuthz().useCache(false).one().statusId, "an approval made before the change mints no key after it")
    }

    @Test
    @DisplayName("review M2: turning the authenticator app on emails the account, as adding a passkey does")
    void turningTheAppOnEmails() {
        enrol(USER_ID, USERNAME)
        List appMail = sent.findAll { it.templateId == TwoFactorSupport.TEMPLATE_APP_ADDED }
        assertEquals(1, appMail.size(), "${sent*.templateId}")
        assertEquals("${USERNAME}@example.com".toString(), appMail[0].to)
        assertNotNull(appMail[0].params.at)
        def template = ec.entity.find("moqui.basic.email.EmailTemplate").condition("emailTemplateId", TwoFactorSupport.TEMPLATE_APP_ADDED)
                .disableAuthz().useCache(false).one()
        assertNotNull(template, "seeded")
        ec.context.push()
        try {
            ec.context.putAll(appMail[0].params as Map)
            String html = ec.screen.makeRender().rootScreen(template.bodyScreenLocation as String).webappName("webroot").renderMode("html").render()
            assertTrue(html.contains("an authenticator app was set up for your Darpan account"), html)
            assertFalse(html.contains("<a "), "no links in a security email")
        } finally {
            ec.context.pop()
        }
    }

    @Test
    @DisplayName("review QM1: the per-request two-factor reads (sessionInfo, the CLI key check) write no INFO line")
    void perRequestReadsAreQuiet() {
        enrol(USER_ID, USERNAME)
        setPolicy("DARPAN_TENANT_ADMIN", new Timestamp(System.currentTimeMillis() - 1000L))
        Capture capture = new Capture()
        LoggerContext context = (LoggerContext) LogManager.getContext(false)
        LoggerConfig root = context.configuration.rootLogger
        capture.start()
        root.addAppender(capture, Level.ALL, null)
        context.updateLoggers()
        try {
            assertEquals(true, TwoFactorSupport.sessionFields(ec, USER_ID).twoFactorEnabled)
            assertFalse(TwoFactorSupport.requiredButNotEnrolled(ec, USER_ID))
            assertFalse(TwoFactorSupport.requiredButNotEnrolled(ec, OTHER_ID))
        } finally {
            root.removeAppender(capture.name)
            context.updateLoggers()
            capture.stop()
        }
        List<String> loud = capture.lines.findAll { it.startsWith("INFO") && it.contains("[tenant-scope]") }
        assertEquals([], loud)
    }

    // ---- regressions and invariants ----

    @Test
    @DisplayName("change#OwnPassword and change#ExpiredPassword still work for an enrolled user")
    void passwordChangesStillWork() {
        enrol(USER_ID, USERNAME)
        Map own = signedIn(USERNAME, "change#OwnPassword", [currentPassword: PASSWORD, newPassword: NEW_PASSWORD, newPasswordVerify: NEW_PASSWORD])
        assertEquals([], own.errors)
        ec.entity.find("moqui.security.UserAccount").condition("userId", USER_ID).disableAuthz().updateAll([requirePasswordChange: "Y"])
        Map expired = anon("change#ExpiredPassword", [username: USERNAME, currentPassword: NEW_PASSWORD, newPassword: "Tf-passw0rd-three!",
                newPasswordVerify: "Tf-passw0rd-three!"])
        assertEquals([], expired.errors)
        assertTrue(expired.out.passwordUpdated as boolean)
    }

    @Test
    @DisplayName("Darpan never writes the framework's UserAuthcFactor or sets requireAuthcFactor; the new methods are off the CLI list")
    void frameworkFactorPathsStayInert() {
        enrol(USER_ID, USERNAME)
        assertEquals(0L, ec.entity.find("moqui.security.UserAuthcFactor").disableAuthz().useCache(false).count())
        assertEquals(0L, ec.entity.find("moqui.security.UserGroup").condition("requireAuthcFactor", "Y").disableAuthz().useCache(false).count())
        ["facade.AuthFacadeServices.verify#SignInCode", "facade.AuthFacadeServices.begin#TwoFactorSetup",
         "facade.AuthFacadeServices.confirm#TwoFactorSetup", "facade.AuthFacadeServices.get#TwoFactorStatus",
         "facade.AuthFacadeServices.create#RecoveryCodes", "facade.AuthFacadeServices.disable#TwoFactor",
         "admin.UserAdminServices.reset#TwoFactor", "admin.SecurityAdminServices.get#TwoFactorPolicies",
         "admin.SecurityAdminServices.update#TwoFactorPolicy"].each {
            assertTrue(it in CliMethodAllowlist.EXCLUDED, it)
            assertFalse(it in CliMethodAllowlist.ALLOWED, it)
        }
        // /transfer never carries a person's factor, codes or challenges: the transfer code names no darpan.auth entity.
        File transferDir = ReconciliationSmokeTestSupport.resolveBackendRoot()
                .resolve("runtime/component/darpan/src/main/groovy/darpan/admin/transfer").toFile()
        assertTrue(transferDir.isDirectory(), "${transferDir}")
        transferDir.eachFileRecurse { File f ->
            if (f.name.endsWith(".groovy")) assertFalse((f.text =~ /darpan\.auth\.|TwoFactor|SignInChallenge/).find(), "${f.name} touches auth data")
        }
    }

    // ---- helpers ----

    /** Signed in, set up and confirm: [secret, recoveryCodes, authToken]. */
    private Map enrol(String userId, String username) {
        Map begun = signedIn(username, "begin#TwoFactorSetup", [currentPassword: PASSWORD])
        assertEquals([], begun.errors)
        Map confirmed = signedIn(username, "confirm#TwoFactorSetup", [code: code(begun.out.secret as String)], false)
        assertEquals([], confirmed.errors)
        ec.user.logoutUser()
        return [secret: begun.out.secret, recoveryCodes: confirmed.out.recoveryCodes, authToken: confirmed.out.authToken]
    }

    /** The next step's code: the clock moves 30 seconds first, so no two calls share a step. */
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

    private Map signedInAdmin(String username, String serviceName, Map params) {
        return signedInCall(username, serviceName, params)
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
        // Refused two-factor steps answer in the envelope, not the MessageFacade (see rpcRefusalsCarryTheirFields).
        if (out.ok == false) (out.errors ?: []).each { if (!(it in errors)) errors << it }
        ec.message.clearAll()
        return [out: out, errors: errors]
    }

    /**
     * The call as /rpc/json makes it: Moqui's own ServiceJsonRpcDispatcher, with a stub web facade (the hook
     * ExecutionContextImpl.setWebFacade keeps for tests) handing it the request and catching the JSON reply.
     */
    private Map rpc(String verbNoun, Map params, String asUsername = null) {
        return rpcRequest([method: "facade.AuthFacadeServices.${verbNoun}".toString(), params: params, id: 7], asUsername)
    }

    /** The dispatcher on a raw /rpc/json request map (a single call, or _requestBodyJsonList for a batch), anonymous or
     *  signed in as asUsername. */
    private Map rpcRequest(Map requestParameters, String asUsername = null) {
        ec.user.logoutUser()
        if (asUsername) assertTrue(ec.user.internalLoginUser(asUsername))
        ec.message.clearAll()
        Map captured = [:]
        // sessionAttributes: what a sign-in inside the call touches (UserFacadeImpl.internalLoginToken), so a login the
        // framework would make from the params is made here too, not lost to a missing stub method.
        Map sessionAttributes = [:]
        WebFacade web = [getRequestParameters: { -> requestParameters },
                         getSessionAttributes: { -> sessionAttributes },
                         sendJsonResponse    : { Object reply -> captured.reply = reply }] as WebFacade
        ExecutionContextImpl eci = (ExecutionContextImpl) ec
        eci.setWebFacade(web)
        try {
            new ServiceJsonRpcDispatcher(eci).dispatch()
        } finally {
            eci.@webFacade = null
            ec.message.clearAll()
        }
        return (Map) captured.reply
    }

    /** A stub web request carrying rawKey as its login_key header, over the real entity facade, signed in as userId. */
    private def webEc(String userId, String rawKey) {
        def request = new WebSignInLifetimeTests.RequestStub(headers: [login_key: rawKey],
                session: new WebSignInLifetimeTests.SessionStub(creationTime: System.currentTimeMillis()))
        def user = new WebSignInLifetimeTests.UserStub(userId: userId, userAccount: account(userId).getMap())
        return new Expando(context: [:], web: new Expando(request: request, secureRequestParameters: [:]), user: user,
                entity: ec.entity, factory: ec.factory, transaction: ec.transaction)
    }

    private List events(String userId, String type) {
        return ec.entity.find(TwoFactorSupport.EVENT).condition("userId", userId).condition("eventType", type)
                .disableAuthz().useCache(false).list()
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

    static class Capture extends AbstractAppender {
        final List<String> lines = Collections.synchronizedList(new ArrayList<String>())

        Capture() {
            super("be264-capture", null, null, true, Property.EMPTY_ARRAY)
        }

        @Override
        void append(LogEvent event) {
            lines << (event.level.toString() + " " + event.message.formattedMessage + (event.thrown ? " " + event.thrown.toString() : ""))
        }
    }
}
