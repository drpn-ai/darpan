package darpan.facade.auth

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.util.ContextStack
import org.moqui.util.MNode
import darpan.facade.common.RpcMethodFence
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ExecutionContext
import org.moqui.context.WebFacade
import java.lang.reflect.Proxy

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-CLI-003 Task 2 — browser-approved CLI sign-in (device code), against a booted Moqui on H2.
 *
 * The CLI starts a sign-in anonymously, the person approves the short user code in their browser, and the
 * CLI's next poll gets an ordinary Moqui login_key for that person, marked as a CLI session.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SharedMoqui
class CliLoginSmokeTests {
    private ExecutionContext ec
    private long clock = 1_800_000_000_000L
    private Closure<Long> originalClock
    private final Map<String, String> secrets = [:]

    private static final String APPROVER_ID = "CLI_APPROVER"
    private static final String APPROVER_USERNAME = "cli.approver"
    private static final String OTHER_ID = "CLI_OTHER"
    private static final String OTHER_USERNAME = "cli.other"
    private static final String SUPER_ID = "CLI_SUPER"
    private static final String SUPER_USERNAME = "cli.super"
    private static final String ADMIN_METHOD = "admin.UserAdminServices.get#UserList"
    private static final String PLAIN_ID = "CLI_PLAIN"
    private static final String PLAIN_USERNAME = "cli.plain"
    private static final String HOST = "aditi-mbp"
    private static final String USER_CODE_PATTERN = /^[BCDFGHJKLMNPQRSTVWXZ23456789]{4}-[BCDFGHJKLMNPQRSTVWXZ23456789]{4}$/

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "cli-login-smoke")
        ec.entity.makeDataLoader().location("component://darpan/data/TwoFactorSeedData.xml").dummyFks(true).load()
        // OC39's reset round: the reset-code purpose enum and email templates.
        ec.entity.makeDataLoader().location("component://darpan/data/PasswordResetEmailSeedData.xml").dummyFks(true).load()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void resetRows() {
        ec.entity.find("darpan.auth.CliDeviceCode").disableAuthz().deleteAll()
        ec.entity.find("darpan.auth.CliSession").disableAuthz().deleteAll()
        ec.entity.find(TwoFactorRecords.FACTOR).disableAuthz().deleteAll()
        secrets.clear()
        originalClock = TwoFactorSupport.codeClock
        TwoFactorSupport.codeClock = { clock }
        upsertAccount(APPROVER_ID, APPROVER_USERNAME)
        upsertAccount(OTHER_ID, OTHER_USERNAME)
        upsertAccount(PLAIN_ID, PLAIN_USERNAME)
        upsertAccount(SUPER_ID, SUPER_USERNAME)
        // DAR-BE-195: a Darpan admin needs no CLI grant; PLAIN has none unless a test gives one.
        ec.entity.find(CliAccess.GRANT).disableAuthz().deleteAll()
        ec.entity.find(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME).condition("userId", PLAIN_ID).disableAuthz().deleteAll()
        darpanAdmin(APPROVER_ID, true)
        darpanAdmin(OTHER_ID, true)
        darpanAdmin(PLAIN_ID, false)
        // DAR-BE-295: a super admin, who alone may approve a darpan-admin sign-in.
        darpanAdmin(SUPER_ID, false)
        ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: "DARPAN_SUPER_ADMIN", description: "Darpan super admin"]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: "DARPAN_SUPER_ADMIN", userId: SUPER_ID,
                fromDate: new Timestamp(System.currentTimeMillis() - 60_000L)]).create()
    }

    @AfterEach
    void clearMemo() {
        TwoFactorSupport.codeClock = originalClock
        ec.context.getSharedMap().remove(CliAuthSupport.CLI_SESSION_CONTEXT_KEY)
        ec.context.getSharedMap().remove(CliAuthSupport.ADMIN_CLI_SESSION_CONTEXT_KEY)
        ec.user.logoutUser()
        ec.message.clearAll()
    }

    // ---- DAR-BE-295: a darpan-admin (ADMIN scope) sign-in ----

    @Test
    @DisplayName("DAR-BE-295: a super admin's ADMIN sign-in issues a 12-hour key and an ADMIN session")
    void adminScopeSignInIssuesATwelveHourKeyAndAnAdminSession() {
        enrolApp(SUPER_ID)
        Map started = anon("start#CliLogin", [hostLabel: HOST, scope: "ADMIN"]).out
        assertEquals("ADMIN", signedIn(SUPER_USERNAME, "get#CliLoginRequest", [userCode: started.userCode]).out.scope)
        assertEquals("ADMIN", deviceRow(started.userCode as String).scopeId)
        assertEquals([], signedIn(SUPER_USERNAME, "approve#CliLogin", [userCode: started.userCode, code: nextCode(SUPER_ID)]).errors)
        Map polled = anon("poll#CliLogin", [deviceCode: started.deviceCode]).out
        assertEquals("APPROVED", polled.status)
        assertEquals(43200, polled.authTokenExpiresInSeconds)
        String hash = AuthSessionSupport.hashLoginKey(ec, polled.authToken as String)
        assertEquals("ADMIN", ec.entity.find("darpan.auth.CliSession").condition("loginKeyHash", hash).useCache(false).disableAuthz().one().scopeId)
        def key = ec.entity.find("moqui.security.UserLoginKey").condition("loginKey", hash).disableAuthz().one()
        assertEquals(12L * 3_600_000L, ((Timestamp) key.thruDate).time - ((Timestamp) key.fromDate).time)
        assertEquals("darpan-admin on ${HOST}".toString(), key.description)
        // a plain start is TENANT
        Map tenant = anon("start#CliLogin", [hostLabel: HOST]).out
        assertEquals("TENANT", signedIn(SUPER_USERNAME, "get#CliLoginRequest", [userCode: tenant.userCode]).out.scope)
    }

    @Test
    @DisplayName("DAR-BE-295: a Darpan admin who is not a super admin cannot approve an ADMIN sign-in")
    void adminScopeApprovalIsRefusedForANonSuperAdmin() {
        Map started = anon("start#CliLogin", [hostLabel: HOST, scope: "ADMIN"]).out
        Map approved = signedIn(APPROVER_USERNAME, "approve#CliLogin", [userCode: started.userCode])
        assertEquals(["Only a super admin can approve a darpan-admin sign-in."], approved.errors)
        assertEquals("PENDING", deviceRow(started.userCode as String).statusId)
        assertNull(deviceRow(started.userCode as String).approvedByUserId)
    }

    @Test
    @DisplayName("DAR-BE-295: the ADMIN key reaches an admin method through the fence")
    void adminScopeKeyCallsAnAdminMethod() {
        String raw = adminKey()
        fenceEnforce(raw, ADMIN_METHOD)  // a refusal throws and fails the test
        assertNull(CliAuthSupport.cliSessionProblem(ec, AuthSessionSupport.hashLoginKey(ec, raw), cliSessionOf(raw)))
        // but only admin.*: a method outside the allowlist and outside admin.* is still refused
        assertThrows(ArtifactAuthorizationException) { fenceEnforce(raw, "facade.AuthFacadeServices.save#ActiveTenant") }
    }

    @Test
    @DisplayName("DAR-BE-295: a TENANT key is refused on admin methods, a super admin's included")
    void tenantScopeKeyIsRefusedOnAdminMethodsEvenForASuperAdmin() {
        String raw = signInByCli(SUPER_USERNAME)
        ArtifactAuthorizationException refused = assertThrows(ArtifactAuthorizationException) { fenceEnforce(raw, ADMIN_METHOD) }
        assertEquals("${ADMIN_METHOD} is not available to the Darpan CLI; use the app.".toString(), refused.message)
    }

    @Test
    @DisplayName("DAR-BE-295: a tenant user's CLI key is refused on admin methods")
    void tenantUsersCliKeyIsRefusedOnAdminMethods() {
        role(PLAIN_ID, T1, "DARPAN_TENANT_USER")
        cliGrant(PLAIN_ID, T1)
        String raw = signInFor(PLAIN_USERNAME).authToken
        assertThrows(ArtifactAuthorizationException) { fenceEnforce(raw, ADMIN_METHOD) }
    }

    @Test
    @DisplayName("DAR-BE-295: an ADMIN session is refused 12 hours after sign-in, though its key's end is still ahead")
    void adminScopeKeyIsRefusedAfterTwelveHours() {
        String raw = adminKey()
        String hash = AuthSessionSupport.hashLoginKey(ec, raw)
        assertNull(CliAuthSupport.cliSessionProblem(ec, hash, cliSessionOf(raw)))
        Timestamp past = new Timestamp(System.currentTimeMillis() - 12L * 3_600_000L - 1000L)
        ec.entity.find("darpan.auth.CliSession").condition("loginKeyHash", hash).disableAuthz().updateAll([createdDate: past])
        ec.entity.find("moqui.security.UserLoginKey").condition("loginKey", hash).disableAuthz().updateAll([fromDate: past])
        assertEquals("This darpan-admin session has expired. Run the command again to sign in.", CliAuthSupport.cliSessionProblem(ec, hash, cliSessionOf(raw)))
        assertEquals(CliAuthSupport.ADMIN_SESSION_EXPIRED, CliAuthSupport.cliSessionProblem(ec, hash, cliSessionOf(raw)))
    }

    @Test
    @DisplayName("DAR-BE-295: a super admin with no two-factor is told to turn it on; no code is spent and the request stays PENDING")
    void adminScopeApprovalNeedsTwoFactorOn() {
        Map started = anon("start#CliLogin", [hostLabel: HOST, scope: "ADMIN"]).out
        Map refused = signedIn(SUPER_USERNAME, "approve#CliLogin", [userCode: started.userCode])
        assertEquals(["Turn on two-factor before approving a darpan-admin sign-in."], refused.errors)
        assertEquals("PENDING", deviceRow(started.userCode as String).statusId)
        assertNull(deviceRow(started.userCode as String).approvedByUserId)
        // enrolled, but no code: still refused, still PENDING
        enrolApp(SUPER_ID)
        assertFalse(signedIn(SUPER_USERNAME, "approve#CliLogin", [userCode: started.userCode]).errors.isEmpty())
        assertEquals("PENDING", deviceRow(started.userCode as String).statusId)
        // with a valid code: approved
        assertEquals([], signedIn(SUPER_USERNAME, "approve#CliLogin", [userCode: started.userCode, code: nextCode(SUPER_ID)]).errors)
        assertEquals("APPROVED", deviceRow(started.userCode as String).statusId)
    }

    @Test
    @DisplayName("DAR-BE-295: a TENANT approval by an approver with no two-factor is unchanged")
    void tenantApprovalNeedsNoFactor() {
        Map started = anon("start#CliLogin", [hostLabel: HOST]).out
        assertEquals([], signedIn(SUPER_USERNAME, "approve#CliLogin", [userCode: started.userCode]).errors)
        assertEquals("APPROVED", deviceRow(started.userCode as String).statusId)
    }

    @Test
    @DisplayName("DAR-BE-295: start refuses a scope that is neither TENANT nor ADMIN")
    void startRefusesAnUnknownScope() {
        Map call = anon("start#CliLogin", [hostLabel: HOST, scope: "ROOT"])
        assertEquals(["scope is TENANT or ADMIN."], call.errors)
        assertEquals(0, ec.entity.find("darpan.auth.CliDeviceCode").disableAuthz().count())
    }

    /** start ADMIN, a super admin approves, poll: the raw key. */
    private String adminKey() {
        enrolApp(SUPER_ID)
        Map started = anon("start#CliLogin", [hostLabel: HOST, scope: "ADMIN"]).out
        assertEquals([], signedIn(SUPER_USERNAME, "approve#CliLogin", [userCode: started.userCode, code: nextCode(SUPER_ID)]).errors)
        Map polled = anon("poll#CliLogin", [deviceCode: started.deviceCode]).out
        assertEquals("APPROVED", polled.status)
        return polled.authToken
    }

    /** An ACTIVE authenticator-app factor for userId, its secret kept for nextCode. */
    private void enrolApp(String userId) {
        String secret = TotpSupport.newSecret()
        secrets[userId] = secret
        ec.entity.makeValue(TwoFactorRecords.FACTOR).setAll([userId: userId, factorTypeEnumId: TwoFactorRecords.TYPE_TOTP,
                statusId: TwoFactorRecords.ACTIVE, secret: secret, createdDate: ec.user.nowTimestamp,
                confirmedDate: ec.user.nowTimestamp]).setSequencedIdPrimary().create()
    }

    /** The next step's code (the clock moves 30 seconds first, so the replay check never refuses a test's own code). */
    private String nextCode(String userId) {
        clock += 30_000L
        return TotpSupport.code(TotpSupport.decodeSecret(secrets[userId]), TotpSupport.timeStep(clock))
    }

    private def cliSessionOf(String raw) {
        return ec.entity.find("darpan.auth.CliSession").condition("loginKeyHash", AuthSessionSupport.hashLoginKey(ec, raw)).useCache(false).disableAuthz().one()
    }

    /** RpcMethodFence.enforce on a /rpc/json request carrying this CLI key in its login_key header. The real context
     *  answers everything but ec.web, which is a stub request. The memos are cleared first, as a new request has none. */
    private void fenceEnforce(String rawKey, String method) {
        ec.context.getSharedMap().remove(CliAuthSupport.CLI_SESSION_CONTEXT_KEY)
        ec.context.getSharedMap().remove(CliAuthSupport.ADMIN_CLI_SESSION_CONTEXT_KEY)
        Map<String, Object> attributes = [:]
        HttpServletRequest request = [
                getServletPath: { -> "" }, getPathInfo: { -> "/rpc/json" }, getContextPath: { -> "" },
                getRequestURI : { -> "/rpc/json" },
                getHeader     : { String name -> name == "login_key" ? rawKey : null },
                getCookies    : { -> null },
                getSession    : { boolean create -> null },
                getAttribute  : { String name -> attributes[name] },
                setAttribute  : { String name, Object value -> attributes[name] = value },
        ] as HttpServletRequest
        WebFacade web = [getRequest: { -> request }, getRequestParameters: { -> [method: method] },
                         getSecureRequestParameters: { -> [:] }] as WebFacade
        ExecutionContext wrapped = (ExecutionContext) Proxy.newProxyInstance(ExecutionContext.classLoader, [ExecutionContext] as Class[]) { Object proxy, java.lang.reflect.Method m, Object[] args ->
            m.name == "getWeb" ? web : m.invoke(ec, args)
        }
        RpcMethodFence.enforce(wrapped)
    }

    @Test
    @DisplayName("start returns a XXXX-XXXX user code, a verification url and stores only the device code hash")
    void startReturnsCodesAndStoresOnlyTheHash() {
        Map call = anon("start#CliLogin", [hostLabel: HOST])
        assertEquals([], call.errors)
        Map out = call.out
        assertTrue(out.userCode ==~ USER_CODE_PATTERN, "user code ${out.userCode}")
        assertTrue((out.deviceCode as String).length() >= 43, "device code ${out.deviceCode}")
        assertEquals("/cli-login", out.verificationPath)
        assertTrue((out.verificationUrl as String).startsWith("http"), "verification url ${out.verificationUrl}")
        assertTrue((out.verificationUrl as String).endsWith("/cli-login"), "verification url ${out.verificationUrl}")
        assertEquals(600, out.expiresInSeconds as int)
        assertEquals(3, out.intervalSeconds as int)

        List rows = ec.entity.find("darpan.auth.CliDeviceCode").disableAuthz().list()
        assertEquals(1, rows.size())
        def row = rows[0]
        assertEquals(sha256(out.deviceCode as String), row.deviceCodeHash)
        assertEquals(out.userCode, row.userCode)
        assertEquals(HOST, row.hostLabel)
        assertEquals("PENDING", row.statusId)
        assertFalse(row.getMap().values().any { it?.toString()?.contains(out.deviceCode as String) },
                "the raw device code must not be stored: ${row.getMap()}")
    }

    @Test
    @DisplayName("poll is PENDING until approved")
    void pollIsPendingUntilApproved() {
        Map started = anon("start#CliLogin", [hostLabel: HOST]).out
        Map polled = anon("poll#CliLogin", [deviceCode: started.deviceCode])
        assertEquals([], polled.errors)
        assertEquals("PENDING", polled.out.status)
        assertNull(polled.out.authToken)

        Map request = signedIn(APPROVER_USERNAME, "get#CliLoginRequest", [userCode: started.userCode])
        assertEquals([], request.errors)
        assertEquals(HOST, request.out.hostLabel)
        assertEquals("PENDING", request.out.status)
        assertNotNull(request.out.expiresDate)
        assertNotNull(request.out.createdDate)

        assertEquals("EXPIRED", anon("poll#CliLogin", [deviceCode: "not-a-real-device-code"]).out.status)
    }

    @Test
    @DisplayName("approve then poll returns a login_key once; a second poll is EXPIRED")
    void approveThenPollReturnsTheKeyOnce() {
        Map started = anon("start#CliLogin", [hostLabel: HOST]).out
        Map approved = signedIn(APPROVER_USERNAME, "approve#CliLogin", [userCode: started.userCode])
        assertEquals([], approved.errors)
        assertEquals("APPROVED", signedIn(APPROVER_USERNAME, "get#CliLoginRequest", [userCode: started.userCode]).out.status)

        Map first = anon("poll#CliLogin", [deviceCode: started.deviceCode])
        assertEquals([], first.errors)
        assertEquals("APPROVED", first.out.status)
        String raw = first.out.authToken
        assertTrue(raw?.length() >= 40, "authToken ${raw}")
        assertEquals("login_key", first.out.authTokenHeaderName)
        assertTrue((first.out.authTokenExpiresInSeconds as int) > 0)
        assertEquals(APPROVER_USERNAME, first.out.username)

        def row = deviceRow(started.userCode as String)
        assertEquals("CLAIMED", row.statusId)
        assertEquals(APPROVER_ID, row.approvedByUserId)
        String hash = AuthSessionSupport.hashLoginKey(ec, raw)
        assertEquals(hash, row.issuedLoginKeyHash)
        def session = ec.entity.find("darpan.auth.CliSession").condition("loginKeyHash", hash).disableAuthz().one()
        assertNotNull(session, "the issued key is marked as a CLI session")
        assertEquals(APPROVER_ID, session.userId)
        assertEquals(HOST, session.hostLabel)
        def key = ec.entity.find("moqui.security.UserLoginKey").condition("loginKey", hash).disableAuthz().one()
        assertNotNull(key)
        assertEquals(APPROVER_ID, key.userId)
        assertEquals("Darpan CLI on ${HOST}".toString(), key.description)
        assertNotNull(key.thruDate)

        Map second = anon("poll#CliLogin", [deviceCode: started.deviceCode])
        assertEquals("EXPIRED", second.out.status)
        assertNull(second.out.authToken)
    }

    @Test
    @DisplayName("the issued key logs in as the approver")
    void theIssuedKeyLogsInAsTheApprover() {
        String raw = signInByCli(APPROVER_USERNAME)
        ec.user.logoutUser()
        assertTrue(ec.user.loginUserKey(raw), "key refused: ${ec.message.errors}")
        assertEquals(APPROVER_ID, ec.user.userId)
    }

    @Test
    @DisplayName("deny makes poll DENIED and issues nothing")
    void denyMakesPollDeniedAndIssuesNothing() {
        Map started = anon("start#CliLogin", [hostLabel: HOST]).out
        assertEquals([], signedIn(APPROVER_USERNAME, "deny#CliLogin", [userCode: started.userCode]).errors)

        Map polled = anon("poll#CliLogin", [deviceCode: started.deviceCode])
        assertEquals("DENIED", polled.out.status)
        assertNull(polled.out.authToken)
        assertEquals(0, ec.entity.find("darpan.auth.CliSession").disableAuthz().count())
        assertEquals(0, ec.entity.find("moqui.security.UserLoginKey").condition("userId", APPROVER_ID).disableAuthz().count())
        assertEquals("DENIED", signedIn(APPROVER_USERNAME, "get#CliLoginRequest", [userCode: started.userCode]).out.status)
    }

    @Test
    @DisplayName("an expired code polls EXPIRED and cannot be approved")
    void anExpiredCodePollsExpiredAndCannotBeApproved() {
        Map started = anon("start#CliLogin", [hostLabel: HOST]).out
        def row = deviceRow(started.userCode as String)
        row.expiresDate = new Timestamp(System.currentTimeMillis() - 1000L)
        row.update()

        assertEquals("EXPIRED", anon("poll#CliLogin", [deviceCode: started.deviceCode]).out.status)
        assertEquals("EXPIRED", signedIn(APPROVER_USERNAME, "get#CliLoginRequest", [userCode: started.userCode]).out.status)
        Map approved = signedIn(APPROVER_USERNAME, "approve#CliLogin", [userCode: started.userCode])
        assertFalse(approved.errors.isEmpty(), "an expired code must not be approvable")
        assertEquals("PENDING", deviceRow(started.userCode as String).statusId)
        assertEquals(0, ec.entity.find("darpan.auth.CliSession").disableAuthz().count())
    }

    @Test
    @DisplayName("approve from a CLI session is refused")
    void approveFromACliSessionIsRefused() {
        Map started = anon("start#CliLogin", [hostLabel: HOST]).out
        Map approved = signedInAsCli(APPROVER_USERNAME, "approve#CliLogin", [userCode: started.userCode])
        assertFalse(approved.errors.isEmpty(), "a CLI session must not approve another CLI sign-in")
        Map denied = signedInAsCli(APPROVER_USERNAME, "deny#CliLogin", [userCode: started.userCode])
        assertFalse(denied.errors.isEmpty(), "a CLI session must not deny a CLI sign-in")
        assertEquals("PENDING", deviceRow(started.userCode as String).statusId)
        assertNull(deviceRow(started.userCode as String).approvedByUserId)
    }

    @Test
    @DisplayName("list shows only my sessions; revoke ends the key")
    void listShowsOnlyMySessionsAndRevokeEndsTheKey() {
        String mine = signInByCli(APPROVER_USERNAME)
        String theirs = signInByCli(OTHER_USERNAME)
        String mineHash = AuthSessionSupport.hashLoginKey(ec, mine)
        String theirsHash = AuthSessionSupport.hashLoginKey(ec, theirs)

        Map listed = signedIn(APPROVER_USERNAME, "list#CliSessions", [:])
        assertEquals([], listed.errors)
        List sessions = listed.out.cliSessions as List
        assertEquals(1, sessions.size(), "only my own sessions: ${sessions}")
        assertEquals(mineHash.take(12), sessions[0].cliSessionId)
        assertEquals(HOST, sessions[0].hostLabel)
        assertNotNull(sessions[0].createdDate)
        assertNotNull(sessions[0].expiresDate)
        assertFalse(sessions.toString().contains(mineHash), "the full key hash never leaves the server")

        Map notMine = signedIn(APPROVER_USERNAME, "revoke#CliSession", [cliSessionId: theirsHash.take(12)])
        assertFalse(notMine.errors.isEmpty(), "revoking someone else's session must be refused")
        ec.user.logoutUser()
        assertTrue(ec.user.loginUserKey(theirs), "their key still works")
        ec.user.logoutUser()

        Map revoked = signedIn(APPROVER_USERNAME, "revoke#CliSession", [cliSessionId: mineHash.take(12)])
        assertEquals([], revoked.errors)
        assertEquals([], signedIn(APPROVER_USERNAME, "list#CliSessions", [:]).out.cliSessions)
        assertNotNull(ec.entity.find("darpan.auth.CliSession").condition("loginKeyHash", mineHash).disableAuthz().one().revokedDate)
        ec.user.logoutUser()
        ec.message.clearAll()
        assertFalse(ec.user.loginUserKey(mine), "a revoked key must not log in")
    }

    @Test
    @DisplayName("a disabled user's issued key no longer logs in")
    void aDisabledUsersIssuedKeyNoLongerLogsIn() {
        String raw = signInByCli(OTHER_USERNAME)
        def account = account(OTHER_ID)
        account.disabled = "Y"
        account.update()
        ec.user.logoutUser()
        ec.message.clearAll()
        assertFalse(ec.user.loginUserKey(raw), "a disabled user's key must not log in")
    }

    @Test
    @DisplayName("an approval by a user disabled before the poll issues nothing")
    void anApprovalByAUserDisabledBeforeThePollIssuesNothing() {
        Map started = anon("start#CliLogin", [hostLabel: HOST]).out
        assertEquals([], signedIn(OTHER_USERNAME, "approve#CliLogin", [userCode: started.userCode]).errors)
        def account = account(OTHER_ID)
        account.disabled = "Y"
        account.update()

        Map polled = anon("poll#CliLogin", [deviceCode: started.deviceCode])
        assertEquals("DENIED", polled.out.status)
        assertNull(polled.out.authToken)
        assertEquals(0, ec.entity.find("moqui.security.UserLoginKey").condition("userId", OTHER_ID).disableAuthz().count())
        assertEquals(0, ec.entity.find("darpan.auth.CliSession").disableAuthz().count())
    }

    @Test
    @DisplayName("OC207: once wrong current passwords lock the account, a live session's right one is refused too, so change#OwnPassword cannot take the account")
    void aLockedOutAccountRefusesTheRightCurrentPassword() {
        String password = "Oc207-start-Aa1!"
        String next = "Oc207-next-Bb2!"
        setPassword(APPROVER_ID, password)
        // One sign-in, kept: the framework refuses a NEW sign-in of a locked account, so the guessing runs from a live session.
        ec.user.logoutUser()
        assertTrue(ec.user.internalLoginUser(APPROVER_USERNAME), "could not sign in ${APPROVER_USERNAME}")
        Closure<Map> inSession = { String verbNoun, Map params -> callService("facade.AuthFacadeServices.${verbNoun}".toString(), params) }
        int guesses = 0
        while (account(APPROVER_ID).disabled != "Y" && guesses < 20) {
            assertFalse(inSession("verify#OwnPassword", [currentPassword: "Wrong-guess-${guesses}!".toString()]).out.passwordVerified as boolean)
            guesses++
        }
        assertEquals("Y", account(APPROVER_ID).disabled, "the wrong guesses lock the account")

        assertFalse(inSession("verify#OwnPassword", [currentPassword: password]).out.passwordVerified as boolean,
                "a locked account's right password is refused, so guessing on from a live session finds nothing")
        Map changed = inSession("change#OwnPassword", [currentPassword: password, newPassword: next, newPasswordVerify: next])
        assertEquals(["Current password is incorrect."], changed.errors, "a locked account's password cannot be changed")

        // Once the lockout is lifted, the old password still matches: the refused change wrote nothing.
        ec.entity.find("moqui.security.UserAccount").condition("userId", APPROVER_ID).disableAuthz()
                .updateAll([disabled: "N", disabledDateTime: null, successiveFailedLogins: 0])
        assertTrue(inSession("verify#OwnPassword", [currentPassword: password]).out.passwordVerified as boolean,
                "the stored password is still the old one")
        ec.user.logoutUser()
    }

    @Test
    @DisplayName("OC207: a web session ends while its account is locked out, temporarily or for good, and lives once a temporary lockout has run out")
    void aWebSessionOfALockedOutAccountEnds() {
        String raw = webKey(APPROVER_USERNAME)
        long started = System.currentTimeMillis()
        assertFalse(webSignInEnds(raw, started), "control: an open account keeps its session")

        Closure lock = { Timestamp disabledAt ->
            ec.entity.find("moqui.security.UserAccount").condition("userId", APPROVER_ID).disableAuthz()
                    .updateAll([disabled: "Y", disabledDateTime: disabledAt])
        }
        lock(new Timestamp(System.currentTimeMillis()))
        assertTrue(webSignInEnds(raw, started), "a temporary lockout (wrong guesses) ends a live web session")
        lock(new Timestamp(System.currentTimeMillis() - 24L * 3_600_000L))
        assertFalse(webSignInEnds(raw, started), "a temporary lockout that has run out ends nothing")
        lock(null)
        assertTrue(webSignInEnds(raw, started), "a permanently disabled account keeps no web session")
    }

    @Test
    @DisplayName("OC39/OC86: a password change, an expired-password change, a reset and sign-out-everywhere each deny an approved, uncollected CLI sign-in, revoke live CLI sessions, and end every web session, for good")
    void everySignOutDeniesAnApprovedUncollectedCliSignIn() {
        Closure originalSendResetCode = PasswordResetSupport.sendResetCode
        Closure originalSendEmail = PasswordResetSupport.sendEmail
        String emailedCode = null
        PasswordResetSupport.sendResetCode = { def ecArg, String to, Map params -> emailedCode = params.code; return true }
        PasswordResetSupport.sendEmail = { def ecArg, String templateId, String to, Map params -> }
        Map<String, Closure<Map>> endings = [
                "change#OwnPassword"    : { String current, String next ->
                    signedInAuth(APPROVER_USERNAME, "change#OwnPassword", [currentPassword: current, newPassword: next, newPasswordVerify: next]) },
                "change#ExpiredPassword": { String current, String next ->
                    anonAuth("change#ExpiredPassword", [username: APPROVER_USERNAME, currentPassword: current, newPassword: next, newPasswordVerify: next]) },
                "complete#PasswordReset": { String current, String next ->
                    ec.entity.find("darpan.auth.PasswordResetCode").disableAuthz().deleteAll()
                    assertEquals("sent", anonAuth("request#PasswordReset", [usernameOrEmail: APPROVER_USERNAME]).out.outcome)
                    anonAuth("complete#PasswordReset", [usernameOrEmail: APPROVER_USERNAME, code: emailedCode, newPassword: next, newPasswordVerify: next]) },
                "logout#AllSessions"    : { String current, String next -> signedInAuth(APPROVER_USERNAME, "logout#AllSessions", [:]) },
        ]
        try {
            int round = 0
            endings.each { String ending, Closure<Map> end ->
                round++
                String current = "Oc39-start-${round}-Aa1!".toString()
                String next = "Oc39-next-${round}-Bb2!".toString()
                setPassword(APPROVER_ID, current)
                String liveKey = signInByCli(APPROVER_USERNAME)
                // OC86: two web sign-ins of the person, the tab that will do it and another browser, both from before it.
                String webTab = webKey(APPROVER_USERNAME)
                String otherBrowser = webKey(APPROVER_USERNAME)
                long webSessionsStarted = System.currentTimeMillis() - 60_000L
                Map started = anon("start#CliLogin", [hostLabel: HOST]).out
                assertEquals([], signedIn(APPROVER_USERNAME, "approve#CliLogin", [userCode: started.userCode]).errors)

                Map ended = end(current, next)
                assertEquals([], ended.errors, "${ending}: ${ended}")

                Map polled = anon("poll#CliLogin", [deviceCode: started.deviceCode])
                assertEquals("DENIED", polled.out.status, "${ending}: an approved CLI sign-in must not outlive it")
                assertNull(polled.out.authToken)
                def liveSession = ec.entity.find("darpan.auth.CliSession").condition("loginKeyHash", AuthSessionSupport.hashLoginKey(ec, liveKey))
                        .useCache(false).disableAuthz().one()
                assertNotNull(liveSession.revokedDate, "${ending}: the live CLI session is revoked")
                assertEquals(0L, ec.entity.find("moqui.security.UserLoginKey").condition("userId", APPROVER_ID).useCache(false).disableAuthz().count(),
                        "${ending}: no key of the person survives")

                // OC86: every web session ends on its next request, the tab that did it too (Aditi 15:12, "Sign in again
                // everywhere"), and the person's sessionsEndedDate is what ends them: a deleted key alone decides nothing.
                assertTrue(webSignInEnds(webTab, webSessionsStarted), "${ending}: the tab that did it is signed out on its next request")
                assertTrue(webSignInEnds(otherBrowser, webSessionsStarted), "${ending}: the person's other browser is signed out too")
                Timestamp stamp = account(APPROVER_ID).sessionsEndedDate as Timestamp
                assertNotNull(stamp, "${ending}: sessionsEndedDate moved")
                // Signing in again clears hasLoggedOut (MoquiShiroRealm), the framework's only other eviction; the stamp stays.
                Map again = anonAuth("login#Session", [username: APPROVER_USERNAME, password: ending == "logout#AllSessions" ? current : next])
                assertTrue(again.out.authenticated as boolean, "${ending}: signing in again: ${again}")
                assertEquals(stamp, account(APPROVER_ID).sessionsEndedDate, "${ending}: a new sign-in does not move the stamp")
                assertEquals("N", account(APPROVER_ID).hasLoggedOut)
                assertTrue(webSignInEnds(otherBrowser, webSessionsStarted), "${ending}: the old browser stays signed out after a new sign-in")
                assertFalse(webSignInEnds(again.out.authToken as String, System.currentTimeMillis()), "${ending}: the new sign-in works")
            }
        } finally {
            PasswordResetSupport.sendResetCode = originalSendResetCode
            PasswordResetSupport.sendEmail = originalSendEmail
        }
    }

    @Test
    @DisplayName("DAR-BE-195: someone the CLI is not turned on for cannot approve a CLI sign-in")
    void aNonAdminCannotApprove() {
        Map started = anon("start#CliLogin", [hostLabel: HOST]).out
        Map approved = signedIn(PLAIN_USERNAME, "approve#CliLogin", [userCode: started.userCode])
        assertEquals([CliAuthSupport.CLI_ACCESS_REFUSED], approved.errors)
        assertEquals("PENDING", deviceRow(started.userCode as String).statusId)
        assertNull(deviceRow(started.userCode as String).approvedByUserId)
        assertEquals("PENDING", anon("poll#CliLogin", [deviceCode: started.deviceCode]).out.status)
        assertEquals(0, ec.entity.find("moqui.security.UserLoginKey").condition("userId", PLAIN_ID).disableAuthz().count())
    }

    @Test
    @DisplayName("DAR-BE-195: an ended Darpan admin membership closes the CLI to that user")
    void anEndedAdminMembershipClosesTheCli() {
        assertTrue(CliAuthSupport.cliAccessAllowed(ec, APPROVER_ID))
        ec.entity.find("moqui.security.UserGroupMember").condition("userId", APPROVER_ID).disableAuthz()
                .updateAll([thruDate: new Timestamp(System.currentTimeMillis() - 1000L)])
        assertFalse(CliAuthSupport.cliAccessAllowed(ec, APPROVER_ID))
        Map started = anon("start#CliLogin", [hostLabel: HOST]).out
        assertEquals([CliAuthSupport.CLI_ACCESS_REFUSED], signedIn(APPROVER_USERNAME, "approve#CliLogin", [userCode: started.userCode]).errors)
    }

    @Test
    @DisplayName("DAR-BE-195, DAR-BE-261: a CLI grant lets a non-admin approve; every key lives 7 days, an admin's no longer")
    void everyCliKeyLivesSevenDaysAdminsIncluded() {
        role(PLAIN_ID, T1, "DARPAN_COMPANY_VIEW_ONLY")
        cliGrant(PLAIN_ID, T1)
        assertEquals(7 * 86_400, signInFor(PLAIN_USERNAME).authTokenExpiresInSeconds)
        def key = ec.entity.find("moqui.security.UserLoginKey").condition("userId", PLAIN_ID).disableAuthz().list()[0]
        long life = ((Timestamp) key.thruDate).time - ((Timestamp) key.fromDate).time
        assertEquals(7L * 86_400_000L, life, "the stored key ends with it")
        role(PLAIN_ID, T2, "DARPAN_TENANT_ADMIN")
        assertEquals(7 * 86_400, signInFor(PLAIN_USERNAME).authTokenExpiresInSeconds, "admin in T2, but the CLI is not on there")
        cliGrant(PLAIN_ID, T2)
        assertEquals(7 * 86_400, signInFor(PLAIN_USERNAME).authTokenExpiresInSeconds, "a tenant admin where the CLI is on: still 7")
        assertEquals(7 * 86_400, signInFor(APPROVER_USERNAME).authTokenExpiresInSeconds, "a Darpan admin, no grant: 7, not 30")
        def adminKey = ec.entity.find("moqui.security.UserLoginKey").condition("userId", APPROVER_ID).orderBy("-fromDate").disableAuthz().list()[0]
        assertEquals(7L * 86_400_000L, ((Timestamp) adminKey.thruDate).time - ((Timestamp) adminKey.fromDate).time, "the admin's stored key too")
    }

    @Test
    @DisplayName("DAR-BE-195: a grant revoked between approval and poll denies the poll; no key is issued")
    void aGrantRevokedBeforeThePollDeniesIt() {
        role(PLAIN_ID, T1, "DARPAN_COMPANY_EDITOR")
        cliGrant(PLAIN_ID, T1)
        Map started = anon("start#CliLogin", [hostLabel: HOST]).out
        assertEquals([], signedIn(PLAIN_USERNAME, "approve#CliLogin", [userCode: started.userCode]).errors)
        ec.entity.find(CliAccess.GRANT).condition("userId", PLAIN_ID).disableAuthz().updateAll([statusId: "REVOKED"])
        assertEquals("DENIED", anon("poll#CliLogin", [deviceCode: started.deviceCode]).out.status)
        assertEquals(0, ec.entity.find("moqui.security.UserLoginKey").condition("userId", PLAIN_ID).disableAuthz().count())
    }

    @Test
    @DisplayName("DAR-BE-195: each call is held to a grant in its own tenant, header or not; a Darpan admin passes")
    void eachCallIsHeldToTheGrantInItsTenant() {
        role(PLAIN_ID, T1, "DARPAN_TENANT_ADMIN")
        role(PLAIN_ID, T2, "DARPAN_COMPANY_VIEW_ONLY")
        cliGrant(PLAIN_ID, T1)
        Map session = [userId: PLAIN_ID, createdDate: new Timestamp(System.currentTimeMillis() - 86_400_000L)]
        assertTrue(ec.user.internalLoginUser(PLAIN_USERNAME))
        assertNull(TenantAccessSupport.withRequestedTenant(T1) { CliAuthSupport.tenantCallProblem(ec, session) })
        String refused = TenantAccessSupport.withRequestedTenant(T2) { CliAuthSupport.tenantCallProblem(ec, session) }
        assertEquals("The Darpan CLI is not turned on for you in T2 Co. A Darpan admin turns it on.", refused)
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, T2)
        assertEquals(refused, CliAuthSupport.tenantCallProblem(ec, session), "no header: the preferred tenant is checked")
        ec.user.logoutUser()
        assertTrue(ec.user.internalLoginUser(APPROVER_USERNAME))
        assertNull(CliAuthSupport.tenantCallProblem(ec, [userId: APPROVER_ID, createdDate: session.createdDate]))
    }

    @Test
    @DisplayName("DAR-UI-141: the CORS preflight lets a browser send X-Darpan-Tenant, in one header that keeps every header the framework allows")
    void corsPreflightAllowsTheTenantHeader() {
        Map<String, List<String>> sent = [:]
        HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(HttpServletResponse.classLoader, [HttpServletResponse] as Class[]) { Object proxy, java.lang.reflect.Method m, Object[] args ->
            if (m.name == "setHeader") sent[args[0] as String] = [args[1] as String]
            if (m.name == "addHeader") sent.computeIfAbsent(args[0] as String) { [] } << (args[1] as String)
            return null
        }
        ((ExecutionContextFactoryImpl) ec.factory).getWebappInfo("webroot").addHeaders("cors-preflight", response)

        List<String> values = sent["Access-Control-Allow-Headers"] ?: []
        assertEquals(1, values.size(), "one Access-Control-Allow-Headers header: ${values}")
        Set<String> allowed = values[0].split(",")*.trim()*.toLowerCase() as Set<String>
        // 2026-11-24 prod outage: the web app sent this header and every browser call was blocked because the list lacked it.
        assertTrue(allowed.contains(TenantAccessSupport.REQUESTED_TENANT_HEADER.toLowerCase()), "browsers may send ${TenantAccessSupport.REQUESTED_TENANT_HEADER}: ${allowed}")
        // darpan's entry has the framework's type and name, so it REPLACES the framework value (MNode.mergeChildrenByKeys):
        // it must restate every header the framework allows, or sign-in (login_key, X-CSRF-Token) breaks instead.
        URL defaultConf = ExecutionContextFactoryImpl.classLoader.getResource("MoquiDefaultConf.xml")
        MNode webroot = MNode.parse(defaultConf.toString(), defaultConf.openStream()).first("webapp-list").children("webapp")
                .find { it.attribute("name") == "webroot" }
        String frameworkList = webroot.children("response-header")
                .find { it.attribute("type") == "cors-preflight" && it.attribute("name") == "Access-Control-Allow-Headers" }
                .attribute("value")
        Set<String> frameworkAllowed = frameworkList.split(",")*.trim()*.toLowerCase() as Set<String>
        assertTrue(allowed.containsAll(frameworkAllowed), "missing framework headers: ${frameworkAllowed - allowed}")
    }

    @Test
    @DisplayName("DAR-BE-261: a web key starts with the 60-minute idle window and slides in the database; the conf caps a web sign-in at 24 hours")
    void aWebKeyStartsAtTheIdleWindowAndSlides() {
        assertEquals(24.0f, ec.factory.getLoginKeyExpireHours(), "darpan's MoquiConf.xml login-key expire-hours")
        assertTrue(ec.user.internalLoginUser(PLAIN_USERNAME))
        String hash = AuthSessionSupport.hashLoginKey(ec, ec.user.getLoginKey(AuthSessionSupport.initialLoginKeyHours(ec)))
        Closure<Object> reload = { ec.entity.find("moqui.security.UserLoginKey").condition("loginKey", hash).useCache(false).disableAuthz().one() }
        def key = reload()
        long signedIn = ((Timestamp) key.fromDate).time
        assertEquals(3_600_000L, ((Timestamp) key.thruDate).time - signedIn, "issued for the idle window, not 24 hours")

        long twentyMinutesOn = signedIn + 20L * 60_000L
        assertTrue(AuthSessionSupport.slideWebLoginKey(ec, key, twentyMinutesOn, AuthSessionSupport.absoluteLifetimeMillis(ec)))
        assertEquals(twentyMinutesOn + 3_600_000L, ((Timestamp) reload().thruDate).time, "a request 20 minutes on moves the end to an hour after it")
        assertFalse(AuthSessionSupport.slideWebLoginKey(ec, reload(), twentyMinutesOn + 2L * 3_600_000L, AuthSessionSupport.absoluteLifetimeMillis(ec)),
                "two idle hours later the key has run out and is not slid back")
    }

    @Test
    @DisplayName("DAR-BE-261: a CLI session older than 7 days has expired, a Darpan admin's included, even when its key still lives")
    void aCliSessionOlderThanSevenDaysHasExpiredAdminsIncluded() {
        Map started = anon("start#CliLogin", [hostLabel: HOST]).out
        assertEquals([], signedIn(APPROVER_USERNAME, "approve#CliLogin", [userCode: started.userCode]).errors)
        assertEquals("APPROVED", anon("poll#CliLogin", [deviceCode: started.deviceCode]).out.status)
        def cliSession = ec.entity.find("darpan.auth.CliSession").condition("userId", APPROVER_ID).useCache(false).disableAuthz().one()
        String hash = cliSession.loginKeyHash
        assertNull(CliAuthSupport.cliSessionProblem(ec, hash, cliSession), "a fresh admin sign-in")

        // An admin key issued for 30 days before the change: the key still lives, the sign-in is 8 days old.
        long now = System.currentTimeMillis()
        ec.entity.find("moqui.security.UserLoginKey").condition("loginKey", hash).disableAuthz()
                .updateAll([fromDate: new Timestamp(now - 8L * 86_400_000L), thruDate: new Timestamp(now + 22L * 86_400_000L)])
        cliSession.createdDate = new Timestamp(now - 8L * 86_400_000L)
        assertEquals(CliAuthSupport.CLI_SESSION_EXPIRED, CliAuthSupport.cliSessionProblem(ec, hash, cliSession))
    }

    @Test
    @DisplayName("DAR-BE-195 review C2: a CLI key the request is not signed in with (cookie, or another user's session) is refused")
    void aCliKeyNotSignedInAsItsUserIsRefused() {
        role(PLAIN_ID, T1, "DARPAN_COMPANY_VIEW_ONLY")
        cliGrant(PLAIN_ID, T1)
        Map session = [userId: PLAIN_ID, createdDate: new Timestamp(System.currentTimeMillis() - 60_000L)]
        assertEquals(CliAuthSupport.CLI_KEY_NOT_SIGNED_IN, CliAuthSupport.tenantCallProblem(ec, session), "no user signed in yet")
        assertTrue(ec.user.internalLoginUser(OTHER_USERNAME))
        assertEquals(CliAuthSupport.CLI_KEY_NOT_SIGNED_IN, CliAuthSupport.tenantCallProblem(ec, session), "signed in as someone else")
        ec.user.logoutUser()
        assertTrue(ec.user.internalLoginUser(PLAIN_USERNAME))
        assertNull(CliAuthSupport.tenantCallProblem(ec, session))
    }

    @Test
    @DisplayName("the eleventh start in a minute from one address is refused")
    void theEleventhStartInAMinuteFromOneAddressIsRefused() {
        (1..10).each { int i ->
            Map call = anon("start#CliLogin", [hostLabel: "${HOST}-${i}".toString()])
            assertEquals([], call.errors, "start ${i}")
            assertNotNull(call.out.deviceCode, "start ${i}")
        }
        Map eleventh = anon("start#CliLogin", [hostLabel: HOST])
        assertFalse(eleventh.errors.isEmpty(), "the eleventh start must be refused")
        assertNull(eleventh.out.deviceCode)
        assertEquals(10, ec.entity.find("darpan.auth.CliDeviceCode").disableAuthz().count())
    }

    // ---- helpers ----

    /** start, approve as username, poll: the raw key the CLI would hold. */
    private String signInByCli(String username) {
        Map started = anon("start#CliLogin", [hostLabel: HOST]).out
        assertEquals([], signedIn(username, "approve#CliLogin", [userCode: started.userCode]).errors)
        Map polled = anon("poll#CliLogin", [deviceCode: started.deviceCode])
        assertEquals("APPROVED", polled.out.status, "poll: ${polled}")
        return polled.out.authToken
    }

    private Map anon(String verbNoun, Map params) {
        ec.user.logoutUser()
        return call(verbNoun, params)
    }

    private Map signedIn(String username, String verbNoun, Map params) {
        ec.user.logoutUser()
        assertTrue(ec.user.internalLoginUser(username), "could not sign in ${username}")
        return call(verbNoun, params)
    }

    /** Signed in, with the request already known to be a CLI session: the memo isCliSession reads first, on the
     *  context's shared map, because a service call pushes an isolated context stack. */
    private Map signedInAsCli(String username, String verbNoun, Map params) {
        ec.user.logoutUser()
        assertTrue(ec.user.internalLoginUser(username), "could not sign in ${username}")
        ec.context.getSharedMap().put(CliAuthSupport.CLI_SESSION_CONTEXT_KEY, true)
        try {
            return call(verbNoun, params)
        } finally {
            ec.context.getSharedMap().remove(CliAuthSupport.CLI_SESSION_CONTEXT_KEY)
        }
    }

    private Map call(String verbNoun, Map params) {
        ec.message.clearAll()
        Map out = ec.service.sync().name("facade.CliAuthFacadeServices.${verbNoun}".toString()).parameters(params).call() ?: [:]
        List<String> errors = new ArrayList<>(ec.message.errors)
        ec.message.clearAll()
        return [out: out, errors: errors]
    }

    /** A facade.AuthFacadeServices call with no session. */
    private Map anonAuth(String verbNoun, Map params) {
        ec.user.logoutUser()
        return callService("facade.AuthFacadeServices.${verbNoun}".toString(), params)
    }

    /** A facade.AuthFacadeServices call signed in as username. */
    private Map signedInAuth(String username, String verbNoun, Map params) {
        ec.user.logoutUser()
        assertTrue(ec.user.internalLoginUser(username), "could not sign in ${username}")
        return callService("facade.AuthFacadeServices.${verbNoun}".toString(), params)
    }

    private Map callService(String serviceName, Map params) {
        ec.message.clearAll()
        Map out = ec.service.sync().name(serviceName).parameters(params).call() ?: [:]
        List<String> errors = new ArrayList<>(ec.message.errors)
        ec.message.clearAll()
        return [out: out, errors: errors]
    }

    /** A web login key of the person, as login#Session issues one. */
    private String webKey(String username) {
        ec.user.logoutUser()
        assertTrue(ec.user.internalLoginUser(username), "could not sign in ${username}")
        String raw = ec.user.getLoginKey(1.0f)
        ec.user.logoutUser()
        return raw
    }

    /** OC86: AuthSessionSupport.enforceWebSignInLifetime on the next request of a web session that started at
     *  sessionStartedAt and carries raw in its login_key header. The real entity, factory and transaction answer; the web
     *  request and the signed-in user (with the account as it is now) are stubs. True when the sign-in ended. */
    private boolean webSignInEnds(String raw, long sessionStartedAt) {
        def user = new Expando(userId: APPROVER_ID, userAccount: account(APPROVER_ID), nowTimestamp: new Timestamp(System.currentTimeMillis()))
        user.logoutLocal = { -> user.userId = null }
        def request = new WebRequestStub(headers: [login_key: raw], session: new WebSessionStub(creationTime: sessionStartedAt))
        def webEc = new Expando(web: new Expando(request: request, secureRequestParameters: [:]), user: user, entity: ec.entity,
                factory: ec.factory, ecfi: ec.ecfi, transaction: ec.transaction, context: new ContextStack())
        return AuthSessionSupport.enforceWebSignInLifetime(webEc)
    }

    static class WebRequestStub {
        Map<String, String> headers = [:]
        Map<String, Object> attributes = [:]
        WebSessionStub session
        String getHeader(String name) { headers[name] }
        Object getCookies() { null }
        Object getAttribute(String name) { attributes[name] }
        void setAttribute(String name, Object value) { attributes[name] = value }
        WebSessionStub getSession(boolean create) { session?.invalidated ? null : session }
    }

    static class WebSessionStub {
        long creationTime
        Map<String, Object> attributes = [:]
        boolean invalidated = false
        long getCreationTime() { creationTime }
        Object getAttribute(String name) { attributes[name] }
        void setAttribute(String name, Object value) { attributes[name] = value }
        void invalidate() { invalidated = true }
    }

    private void setPassword(String userId, String password) {
        ec.service.sync().name("org.moqui.impl.UserServices.update#PasswordInternal")
                .parameters([userId: userId, newPassword: password, newPasswordVerify: password, requirePasswordChange: "N"]).disableAuthz().call()
        assertFalse(ec.message.hasError(), "fixture password refused: ${ec.message.errors} ${ec.message.messages}")
        ec.message.clearAll()
    }

    private def deviceRow(String userCode) {
        return ec.entity.find("darpan.auth.CliDeviceCode").condition("userCode", userCode).useCache(false).disableAuthz().one()
    }

    private def account(String userId) {
        return ec.entity.find("moqui.security.UserAccount").condition("userId", userId).useCache(false).disableAuthz().one()
    }

    private void upsertAccount(String userId, String username) {
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: userId, username: username,
                userFullName: username, emailAddress: "${username}@example.com".toString(), disabled: "N",
                disabledDateTime: null, requirePasswordChange: "N", currentPassword: null, passwordSalt: null,
                hasLoggedOut: "N", successiveFailedLogins: 0]).createOrUpdate()
        ec.entity.find("moqui.security.UserLoginKey").condition("userId", userId).disableAuthz().deleteAll()
    }

    private void darpanAdmin(String userId, boolean admin) {
        ec.entity.find("moqui.security.UserGroupMember").condition("userId", userId).disableAuthz().deleteAll()
        if (admin) {
            ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: "DARPAN_ADMIN",
                    description: "Darpan admin"]).createOrUpdate()
            ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: "DARPAN_ADMIN", userId: userId,
                    fromDate: new Timestamp(System.currentTimeMillis() - 60_000L)]).create()
        }
    }

    private static final String T1 = "CLI_T1"
    private static final String T2 = "CLI_T2"

    /** A role in a tenant (DAR-BE-195); tenant and permission groups made here, no seed loaded. */
    private void role(String userId, String tenant, String group) {
        if (ec.entity.find("moqui.basic.Enumeration").condition("enumId", TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID).disableAuthz().count() == 0) {
            ec.entity.makeValue("moqui.basic.EnumerationType").setAll([enumTypeId: "UserGroupType", description: "User group type"]).createOrUpdate()
            ec.entity.makeValue("moqui.basic.Enumeration").setAll([enumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID, enumTypeId: "UserGroupType",
                    description: "Darpan company"]).create()
        }
        ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: tenant, description: "${tenant.replace('CLI_', '')} Co".toString(),
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: group, description: group]).createOrUpdate()
        ec.entity.makeValue(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME).setAll([tenantUserGroupId: tenant, userId: userId,
                permissionUserGroupId: group, fromDate: new Timestamp(System.currentTimeMillis() - 60_000L)]).create()
        ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: tenant, userId: userId,
                fromDate: new Timestamp(System.currentTimeMillis() - 60_000L)]).createOrUpdate()
    }

    private void cliGrant(String userId, String tenant) {
        ec.entity.makeValue(CliAccess.GRANT).setAll([grantId: "G_${userId}_${tenant}".toString(), userId: userId, companyUserGroupId: tenant,
                fromDate: new Timestamp(System.currentTimeMillis() - 60_000L), statusId: "ACTIVE"]).create()
    }

    /** A whole sign-in: start, approve as username, poll; the poll's answer. */
    private Map signInFor(String username) {
        Map started = anon("start#CliLogin", [hostLabel: HOST]).out
        assertEquals([], signedIn(username, "approve#CliLogin", [userCode: started.userCode]).errors)
        Map polled = anon("poll#CliLogin", [deviceCode: started.deviceCode]).out
        assertEquals("APPROVED", polled.status)
        return polled
    }

    private static String sha256(String value) {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)).encodeHex().toString()
    }
}
