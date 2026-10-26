package darpan.facade.auth

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-028 — forgot password and recovery email, against a booted Moqui on H2.
 *
 * Mail never leaves the JVM: PasswordResetSupport.sendEmail is the one seam every send goes through,
 * and each test swaps it for a recorder, so a test asserts exactly which template went to which address
 * with which parameters (including the code, which nothing else ever returns).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PasswordResetServicesSmokeTests {
    private ExecutionContext ec
    private Closure originalSendEmail
    private List<Map> sent = []

    private static final String OK_ID = "PWR_OK"
    private static final String OK_USERNAME = "pwr.ok"
    private static final String OK_EMAIL = "pwr.ok@example.com"
    private static final String NOEMAIL_ID = "PWR_NOEMAIL"
    private static final String DISABLED_ID = "PWR_DISABLED"
    private static final String OLD_PASSWORD = "Old-pass1!"
    private static final String NEW_PASSWORD = "New-pass2!"
    private static final String CODE_FAILURE = "That code didn't work. Request a new one."

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "password-reset-smoke")
        ec.entity.makeDataLoader()
                .location("component://darpan/data/SecuritySeedData.xml")
                .dummyFks(true)
                .load()
        ec.entity.makeDataLoader()
                .location("component://darpan/data/PasswordResetEmailSeedData.xml")
                .dummyFks(true)
                .load()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void recordMail() {
        originalSendEmail = PasswordResetSupport.sendEmail
        sent = []
        PasswordResetSupport.sendEmail = { def ecArg, String templateId, String to, Map params ->
            sent << [templateId: templateId, to: to, params: params]
        }
        // The seed carries SYSTEM by id alone (EmailTemplate FKs it); a test that wants mail configured sets smtpHost.
        ec.entity.makeValue("moqui.basic.email.EmailServer").setAll([emailServerId: "SYSTEM", smtpHost: null]).createOrUpdate()
    }

    @BeforeEach
    void resetAccounts() {
        ec.entity.find("darpan.auth.PasswordResetCode").disableAuthz().deleteAll()
        upsertAccount(OK_ID, OK_USERNAME, OK_EMAIL, "N")
        upsertAccount(NOEMAIL_ID, "pwr.noemail", null, "N")
        upsertAccount(DISABLED_ID, "pwr.disabled", "pwr.disabled@example.com", "Y")
    }

    @AfterEach
    void restoreMail() {
        PasswordResetSupport.sendEmail = originalSendEmail
    }

    // ---- Task 1: email plumbing ----

    @Test
    void templatesSeeded() {
        [PasswordResetSupport.TEMPLATE_RESET_CODE, PasswordResetSupport.TEMPLATE_PASSWORD_CHANGED,
         PasswordResetSupport.TEMPLATE_RECOVERY_SET].each { String id ->
            def template = ec.entity.find("moqui.basic.email.EmailTemplate").condition("emailTemplateId", id)
                    .disableAuthz().one()
            assertNotNull(template, "missing EmailTemplate ${id}")
            assertEquals("SYSTEM", template.emailServerId)
            // Aditi 2026-10-21: mail comes from support@drpn.ai everywhere; DARPAN_MAIL_FROM only overrides it.
            String expectedFrom = System.getenv("DARPAN_MAIL_FROM") ?: "support@drpn.ai"
            assertEquals(expectedFrom, ec.resource.expand((String) template.fromAddress, ""), "${id} fromAddress")
            assertEquals("Darpan", template.fromName)
        }
    }

    @Test
    void resetCodeBodyRendersCode() {
        String html = renderBody("component://darpan/screen/email/PasswordResetCode.xml",
                [code: "042117", expiresMinutes: 15, username: "u1"])
        assertTrue(html.contains("042117"), html)
        assertTrue(html.contains("15 minutes"), html)
        assertFalse(html.contains("<b>") || html.contains("<strong>") || html.contains("href"), html)
    }

    @Test
    void notConfiguredWithoutSystemServer() {
        assertEquals(false, anon("get#PasswordResetStatus", [:]).out.available)
    }

    // ---- Task 2: request + verify ----

    @Test
    void requestReplyIsUniform() {
        List<List> replies = [OK_USERNAME, "pwr.noemail", "pwr.disabled", "no.such.user"].collect { String who ->
            Map call = anon("request#PasswordReset", [usernameOrEmail: who])
            [call.out.ok, call.out.messages, call.out.errors, call.errors]
        }
        assertEquals(1, replies.unique(false).size(), "replies differ: ${replies}")
        assertEquals([true, [], [], []], replies[0])
        assertEquals(1, sent.size(), "sends: ${sent}")
        assertEquals(PasswordResetSupport.TEMPLATE_RESET_CODE, sent[0].templateId)
        assertEquals(OK_EMAIL, sent[0].to)
        assertTrue(((String) sent[0].params.code) ==~ /\d{6}/, "code: ${sent[0].params.code}")
        assertEquals(15, sent[0].params.expiresMinutes)
    }

    @Test
    void requestHeldToFloor() {
        [OK_USERNAME, "pwr.noemail", "pwr.disabled", "no.such.user"].each { String who ->
            long started = System.nanoTime()
            anon("request#PasswordReset", [usernameOrEmail: who])
            long elapsedMs = (System.nanoTime() - started).intdiv(1_000_000L)
            assertTrue(elapsedMs >= 400, "${who} answered in ${elapsedMs}ms")
        }
    }

    @Test
    void mixedCaseLookup() {
        anon("request#PasswordReset", [usernameOrEmail: "PWR.OK"])
        anon("request#PasswordReset", [usernameOrEmail: OK_EMAIL.toUpperCase()])
        assertEquals(2, sent.size(), "sends: ${sent}")
    }

    @Test
    void newRequestEndsPrevious() {
        anon("request#PasswordReset", [usernameOrEmail: OK_USERNAME])
        String first = lastCode()
        anon("request#PasswordReset", [usernameOrEmail: OK_USERNAME])
        String second = lastCode()
        if (first != second) assertFalse(verify(first).out.verified as boolean)
        assertTrue(verify(second).out.verified as boolean)
    }

    @Test
    void fiveWrongBurnsCode() {
        anon("request#PasswordReset", [usernameOrEmail: OK_USERNAME])
        String code = lastCode()
        String wrong = code == "000000" ? "111111" : "000000"
        5.times { assertFalse(verify(wrong).out.verified as boolean) }
        assertFalse(verify(code).out.verified as boolean)
    }

    @Test
    void expiredCodeFails() {
        anon("request#PasswordReset", [usernameOrEmail: OK_USERNAME])
        def row = ec.entity.find("darpan.auth.PasswordResetCode").condition("userId", OK_ID).disableAuthz().one()
        row.thruDate = new java.sql.Timestamp(System.currentTimeMillis() - 60_000L)
        row.update()
        assertFalse(verify(lastCode()).out.verified as boolean)
    }

    @Test
    void fourthRequestInHourSendsNothing() {
        4.times { anon("request#PasswordReset", [usernameOrEmail: OK_USERNAME]) }
        assertEquals(3, sent.size())
    }

    @Test
    void codeFailureTextUniform() {
        anon("request#PasswordReset", [usernameOrEmail: OK_USERNAME])
        String code = lastCode()
        String wrong = code == "000000" ? "111111" : "000000"
        assertEquals([CODE_FAILURE], verify(wrong).errors)
        assertEquals([CODE_FAILURE], anon("verify#PasswordResetCode", [usernameOrEmail: "no.such.user", code: code]).errors)
        4.times { verify(wrong) }
        assertEquals([CODE_FAILURE], verify(code).errors)
    }

    @Test
    void pastedCodeVerifies() {
        anon("request#PasswordReset", [usernameOrEmail: OK_USERNAME])
        String code = lastCode()
        assertTrue(verify(" " + code[0..2] + " " + code[3..5] + "\n").out.verified as boolean)
    }

    @Test
    void verifyDoesNotConsume() {
        anon("request#PasswordReset", [usernameOrEmail: OK_USERNAME])
        assertTrue(verify(lastCode()).out.verified as boolean)
        assertTrue(verify(lastCode()).out.verified as boolean)
    }

    @Test
    void noFailedLoginCount() {
        def before = account(OK_ID).successiveFailedLogins
        anon("request#PasswordReset", [usernameOrEmail: OK_USERNAME])
        String wrong = lastCode() == "000000" ? "111111" : "000000"
        5.times { verify(wrong) }
        assertEquals(before, account(OK_ID).successiveFailedLogins)
    }

    /** Security review 2026-10-21: parallel guesses must not each slip past the cap. Every guess is checked and
     *  counted under a row lock, so once five are counted the code is ended and later guesses count nothing. */
    @Test
    void concurrentWrongGuessesNeverCountPastFive() {
        anon("request#PasswordReset", [usernameOrEmail: OK_USERNAME])
        String wrong = lastCode() == "000000" ? "111111" : "000000"
        List<Thread> threads = (1..8).collect {
            Thread.start {
                def tec = org.moqui.Moqui.getExecutionContext()
                try {
                    tec.user.loginAnonymousIfNoUser()
                    tec.service.sync().name("facade.AuthFacadeServices.verify#PasswordResetCode")
                            .parameters([usernameOrEmail: OK_USERNAME, code: wrong]).call()
                } finally {
                    tec.destroy()
                }
            }
        }
        threads*.join()
        assertEquals(5, codeRow(OK_ID).failedAttempts)
    }

    // ---- Task 3: complete ----

    @Test
    void completeSetsPasswordAndSignsOutEverywhere() {
        setPassword(OK_ID, OLD_PASSWORD)
        ["PWRKEY1", "PWRKEY2"].each { String key ->
            ec.entity.makeValue("moqui.security.UserLoginKey").setAll([loginKey: key, userId: OK_ID,
                    fromDate: ec.user.nowTimestamp, thruDate: new java.sql.Timestamp(System.currentTimeMillis() + 86_400_000L)]).create()
        }
        anon("request#PasswordReset", [usernameOrEmail: OK_USERNAME])
        Map done = complete(lastCode(), NEW_PASSWORD)
        assertTrue(done.out.passwordUpdated as boolean, "errors: ${done.errors}")
        assertEquals(0L, ec.entity.find("moqui.security.UserLoginKey").condition("userId", OK_ID).disableAuthz().count())
        // Security review 2026-10-21: session-authenticated requests elsewhere (legacy /apps via /Login) are only
        // evicted by the framework's hasLoggedOut broadcast; deleting login keys alone leaves them signed in.
        assertEquals("Y", account(OK_ID).hasLoggedOut)
        assertTrue(signIn(NEW_PASSWORD), "new password should sign in")
        def changed = sent.findAll { it.templateId == PasswordResetSupport.TEMPLATE_PASSWORD_CHANGED }
        assertEquals(1, changed.size())
        assertEquals(OK_EMAIL, changed[0].to)
    }

    @Test
    void liveCodeDoesNotBlockPasswordLogin() {
        setPassword(OK_ID, OLD_PASSWORD)
        anon("request#PasswordReset", [usernameOrEmail: OK_USERNAME])
        assertTrue(signIn(OLD_PASSWORD), "a pending reset code must not stop the correct password signing in")
    }

    @Test
    void codeDeadAfterComplete() {
        setPassword(OK_ID, OLD_PASSWORD)
        anon("request#PasswordReset", [usernameOrEmail: OK_USERNAME])
        String code = lastCode()
        assertTrue(complete(code, NEW_PASSWORD).out.passwordUpdated as boolean)
        assertFalse(verify(code).out.verified as boolean)
        Map again = complete(code, "Another-pass3!")
        assertFalse(again.out.passwordUpdated as boolean)
        assertEquals([CODE_FAILURE], again.errors)
    }

    @Test
    void policyFailurePassesThrough() {
        setPassword(OK_ID, OLD_PASSWORD)
        anon("request#PasswordReset", [usernameOrEmail: OK_USERNAME])
        String code = lastCode()
        Map refused = complete(code, "a")
        assertFalse(refused.out.passwordUpdated as boolean)
        assertFalse(refused.errors.isEmpty())
        assertNotEquals([CODE_FAILURE], refused.errors)
        assertTrue(refused.errors.any { it.contains("8") }, "policy errors: ${refused.errors}")
        assertTrue(verify(code).out.verified as boolean, "a policy refusal must not use up or count against the code")
        assertEquals(0, codeRow(OK_ID).failedAttempts ?: 0)
    }

    @Test
    void disabledNeverReEnabled() {
        String code = "123456"
        ec.entity.makeValue("darpan.auth.PasswordResetCode").setAll([userId: DISABLED_ID,
                purposeEnumId: PasswordResetSupport.PURPOSE_PASSWORD_RESET, codeSalt: "salt",
                codeHash: ec.ecfi.getSimpleHash(code, "salt"), fromDate: ec.user.nowTimestamp,
                thruDate: new java.sql.Timestamp(System.currentTimeMillis() + 600_000L), failedAttempts: 0])
                .setSequencedIdPrimary().create()
        Map refused = anon("complete#PasswordReset", [usernameOrEmail: "pwr.disabled", code: code,
                newPassword: NEW_PASSWORD, newPasswordVerify: NEW_PASSWORD])
        assertFalse(refused.out.passwordUpdated as boolean)
        assertEquals([CODE_FAILURE], refused.errors)
        assertEquals("Y", account(DISABLED_ID).disabled)
    }

    @Test
    void requirePasswordChangeCleared() {
        setPassword(OK_ID, OLD_PASSWORD)
        def row = account(OK_ID)
        row.requirePasswordChange = "Y"
        row.update()
        anon("request#PasswordReset", [usernameOrEmail: OK_USERNAME])
        assertTrue(complete(lastCode(), NEW_PASSWORD).out.passwordUpdated as boolean)
        assertEquals("N", account(OK_ID).requirePasswordChange)
    }

    // ---- Task 4: recovery email, session flags, status ----

    @Test
    void savesTrimmedLowerCased() {
        setPassword(OK_ID, OLD_PASSWORD)
        Map saved = signedIn(OK_USERNAME, "save#RecoveryEmail", [emailAddress: "  New.Addr@Example.COM ", currentPassword: OLD_PASSWORD])
        assertEquals([], saved.errors)
        assertEquals("n•••@example.com", saved.out.emailAddressMasked)
        assertEquals("new.addr@example.com", account(OK_ID).emailAddress)
        def notices = sent.findAll { it.templateId == PasswordResetSupport.TEMPLATE_RECOVERY_SET }
        assertEquals(2, notices.size(), "sends: ${sent}")
        assertEquals(["new.addr@example.com", false], [notices[0].to, notices[0].params.isOldAddress])
        assertEquals([OK_EMAIL, true], [notices[1].to, notices[1].params.isOldAddress])
    }

    @Test
    void caseOnlyChangeIsNotConflict() {
        setPassword(OK_ID, OLD_PASSWORD)
        Map saved = signedIn(OK_USERNAME, "save#RecoveryEmail", [emailAddress: OK_EMAIL.toUpperCase(), currentPassword: OLD_PASSWORD])
        assertEquals([], saved.errors)
        assertEquals(OK_EMAIL, account(OK_ID).emailAddress)
        assertEquals(1, sent.count { it.templateId == PasswordResetSupport.TEMPLATE_RECOVERY_SET })
    }

    @Test
    void conflictRefusedAsAlreadyInUse() {
        setPassword(NOEMAIL_ID, OLD_PASSWORD)
        Map saved = signedIn("pwr.noemail", "save#RecoveryEmail", [emailAddress: OK_EMAIL, currentPassword: OLD_PASSWORD])
        assertEquals(["That email is already in use by another account."], saved.errors)
        assertNull(account(NOEMAIL_ID).emailAddress)
        assertTrue(sent.isEmpty(), "sends: ${sent}")
    }

    @Test
    void invalidEmailRefused() {
        setPassword(OK_ID, OLD_PASSWORD)
        Map saved = signedIn(OK_USERNAME, "save#RecoveryEmail", [emailAddress: "not an email", currentPassword: OLD_PASSWORD])
        assertFalse(saved.errors.isEmpty())
        assertEquals(OK_EMAIL, account(OK_ID).emailAddress)
        assertTrue(sent.isEmpty())
    }

    /** Security review 2026-10-21: a session token alone must not be able to redirect future resets (that is a
     *  takeover: change the address, then reset the password). The current password is checked on the server,
     *  and a wrong one counts toward the account's failed-login lockout. */
    @Test
    void wrongCurrentPasswordRefusedAndCounted() {
        setPassword(OK_ID, OLD_PASSWORD)
        def before = (account(OK_ID).successiveFailedLogins ?: 0) as long
        Map refused = signedIn(OK_USERNAME, "save#RecoveryEmail", [emailAddress: "thief@example.com", currentPassword: "Wrong-pass9!"])
        assertEquals(["Current password is incorrect."], refused.errors)
        assertEquals(OK_EMAIL, account(OK_ID).emailAddress)
        assertTrue(sent.isEmpty(), "sends: ${sent}")
        assertEquals(before + 1, (account(OK_ID).successiveFailedLogins ?: 0) as long)
    }

    @Test
    void anonymousRefused() {
        ec.user.logoutUser()
        def refused = assertThrows(org.moqui.context.AuthenticationRequiredException) {
            ec.service.sync().name("facade.AuthFacadeServices.save#RecoveryEmail").parameters([emailAddress: "x@example.com", currentPassword: OLD_PASSWORD]).call()
        }
        assertTrue(refused.message.contains("logged in"), refused.message)
        ec.message.clearAll()
        assertNull(account(NOEMAIL_ID).emailAddress)
    }

    @Test
    void sessionFlags() {
        setPassword(NOEMAIL_ID, OLD_PASSWORD)
        Map before = signedIn("pwr.noemail", "get#SessionInfo", [:])
        assertEquals(true, before.out.sessionInfo.recoveryEmailMissing)
        assertNull(before.out.sessionInfo.emailAddressMasked)
        assertEquals([], signedIn("pwr.noemail", "save#RecoveryEmail", [emailAddress: "fresh@example.com", currentPassword: OLD_PASSWORD]).errors)
        Map after = signedIn("pwr.noemail", "get#SessionInfo", [:])
        assertEquals(false, after.out.sessionInfo.recoveryEmailMissing)
        assertEquals("f•••@example.com", after.out.sessionInfo.emailAddressMasked)
    }

    @Test
    void statusReflectsConfig() {
        assertEquals(false, anon("get#PasswordResetStatus", [:]).out.available)
        ec.entity.makeValue("moqui.basic.email.EmailServer").setAll([emailServerId: "SYSTEM", smtpHost: "smtp.test"]).createOrUpdate()
        assertEquals(true, anon("get#PasswordResetStatus", [:]).out.available, "an SMTP host is all it needs now")
    }

    // ---- Final review fixes ----

    /** Review C1: the framework's temporary lockout sets disabled=Y with a disabledDateTime and only a correct
     *  login clears it, so a reset must treat an EXPIRED lockout as enabled (as MoquiShiroRealm does) or a
     *  locked-out user who forgot their password could never get a code. */
    @Test
    void activeLockoutGetsNoCode() {
        lockOut(OK_ID, new java.sql.Timestamp(System.currentTimeMillis()))
        anon("request#PasswordReset", [usernameOrEmail: OK_USERNAME])
        assertTrue(sent.isEmpty(), "sends: ${sent}")
    }

    @Test
    void expiredLockoutCanResetAndIsCleared() {
        setPassword(OK_ID, OLD_PASSWORD)
        lockOut(OK_ID, new java.sql.Timestamp(System.currentTimeMillis() - 2L * 86_400_000L))
        anon("request#PasswordReset", [usernameOrEmail: OK_USERNAME])
        Map done = complete(lastCode(), NEW_PASSWORD)
        assertTrue(done.out.passwordUpdated as boolean, "errors: ${done.errors}")
        def row = account(OK_ID)
        assertEquals("N", row.disabled)
        assertNull(row.disabledDateTime)
        assertEquals(0L, (row.successiveFailedLogins ?: 0) as long)
    }

    /** Review I3: SMTP alone is not enough — without the seeded template the link would show and every real
     *  account's request would fail, so availability also needs DARPAN_PASSWORD_RESET_CODE. */
    @Test
    void statusNeedsTheResetTemplate() {
        ec.entity.makeValue("moqui.basic.email.EmailServer").setAll([emailServerId: "SYSTEM", smtpHost: "smtp.test"]).createOrUpdate()
        def template = ec.entity.find("moqui.basic.email.EmailTemplate")
                .condition("emailTemplateId", PasswordResetSupport.TEMPLATE_RESET_CODE).disableAuthz().one()
        Map saved = new HashMap(template.getMap())
        template.delete()
        try {
            assertEquals(false, anon("get#PasswordResetStatus", [:]).out.available)
        } finally {
            ec.entity.makeValue("moqui.basic.email.EmailTemplate").setAll(saved).createOrUpdate()
        }
    }

    // ---- helpers ----

    private void lockOut(String userId, java.sql.Timestamp since) {
        def row = account(userId)
        row.disabled = "Y"
        row.disabledDateTime = since
        row.successiveFailedLogins = 4
        row.update()
    }

    /** Call a facade service signed in as username. */
    private Map signedIn(String username, String verbNoun, Map params) {
        ec.user.logoutUser()
        assertTrue(ec.user.internalLoginUser(username), "could not sign in ${username}")
        ec.message.clearAll()
        Map out = ec.service.sync().name("facade.AuthFacadeServices.${verbNoun}".toString()).parameters(params).call() ?: [:]
        List<String> errors = new ArrayList<>(ec.message.errors)
        ec.message.clearAll()
        return [out: out, errors: errors]
    }

    private Map complete(String code, String newPassword) {
        return anon("complete#PasswordReset", [usernameOrEmail: OK_USERNAME, code: code,
                newPassword: newPassword, newPasswordVerify: newPassword])
    }

    private boolean signIn(String password) {
        Map call = anon("login#Session", [username: OK_USERNAME, password: password])
        boolean authenticated = call.out.authenticated as boolean
        ec.user.logoutUser()
        return authenticated
    }

    private void setPassword(String userId, String password) {
        ec.service.sync().name("org.moqui.impl.UserServices.update#PasswordInternal")
                .parameters([userId: userId, newPassword: password, newPasswordVerify: password]).disableAuthz().call()
        assertFalse(ec.message.hasError(), "fixture password refused: ${ec.message.errors} ${ec.message.messages}")
        ec.message.clearAll()
    }

    private def codeRow(String userId) {
        return ec.entity.find("darpan.auth.PasswordResetCode").condition("userId", userId).orderBy("-fromDate")
                .useCache(false).disableAuthz().list()[0]
    }

    /** Call a facade service with no session, the way an anonymous /rpc/json caller reaches it. */
    private Map anon(String verbNoun, Map params) {
        ec.user.logoutUser()
        ec.message.clearAll()
        Map out = ec.service.sync().name("facade.AuthFacadeServices.${verbNoun}".toString()).parameters(params).call() ?: [:]
        List<String> errors = new ArrayList<>(ec.message.errors)
        ec.message.clearAll()
        return [out: out, errors: errors]
    }

    private Map verify(String code) {
        return anon("verify#PasswordResetCode", [usernameOrEmail: OK_USERNAME, code: code])
    }

    private String lastCode() {
        def resets = sent.findAll { it.templateId == PasswordResetSupport.TEMPLATE_RESET_CODE }
        assertFalse(resets.isEmpty(), "no reset code was sent")
        return resets.last().params.code
    }

    private def account(String userId) {
        return ec.entity.find("moqui.security.UserAccount").condition("userId", userId).useCache(false).disableAuthz().one()
    }

    private void upsertAccount(String userId, String username, String email, String disabled) {
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: userId, username: username,
                userFullName: username, emailAddress: email, disabled: disabled, requirePasswordChange: "N",
                currentPassword: null, passwordSalt: null, hasLoggedOut: "N",
                successiveFailedLogins: 0]).createOrUpdate()
        ec.entity.find("moqui.security.UserPasswordHistory").condition("userId", userId).disableAuthz().deleteAll()
        ec.entity.find("moqui.security.UserLoginKey").condition("userId", userId).disableAuthz().deleteAll()
    }

    private String renderBody(String location, Map params) {
        ec.context.push()
        try {
            ec.context.putAll(params)
            return ec.screen.makeRender().rootScreen(location).webappName("webroot").renderMode("html").render()
        } finally {
            ec.context.pop()
        }
    }
}
