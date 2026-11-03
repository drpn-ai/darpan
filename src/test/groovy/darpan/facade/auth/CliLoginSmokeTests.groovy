package darpan.facade.auth

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

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
class CliLoginSmokeTests {
    private ExecutionContext ec

    private static final String APPROVER_ID = "CLI_APPROVER"
    private static final String APPROVER_USERNAME = "cli.approver"
    private static final String OTHER_ID = "CLI_OTHER"
    private static final String OTHER_USERNAME = "cli.other"
    private static final String HOST = "aditi-mbp"
    private static final String USER_CODE_PATTERN = /^[BCDFGHJKLMNPQRSTVWXZ23456789]{4}-[BCDFGHJKLMNPQRSTVWXZ23456789]{4}$/

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "cli-login-smoke")
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void resetRows() {
        ec.entity.find("darpan.auth.CliDeviceCode").disableAuthz().deleteAll()
        ec.entity.find("darpan.auth.CliSession").disableAuthz().deleteAll()
        upsertAccount(APPROVER_ID, APPROVER_USERNAME)
        upsertAccount(OTHER_ID, OTHER_USERNAME)
    }

    @AfterEach
    void clearMemo() {
        ec.context.getSharedMap().remove(CliAuthSupport.CLI_SESSION_CONTEXT_KEY)
        ec.user.logoutUser()
        ec.message.clearAll()
    }

    @Test
    @DisplayName("start returns a XXXX-XXXX user code, a verification url and stores only the device code hash")
    void startReturnsCodesAndStoresOnlyTheHash() {
        Map call = anon("start#CliLogin", [hostLabel: HOST])
        assertEquals([], call.errors)
        Map out = call.out
        assertTrue(out.userCode ==~ USER_CODE_PATTERN, "user code ${out.userCode}")
        assertTrue((out.deviceCode as String).length() >= 43, "device code ${out.deviceCode}")
        assertEquals("/cli-login?code=${out.userCode}".toString(), out.verificationPath)
        assertTrue((out.verificationUrl as String).startsWith("http"), "verification url ${out.verificationUrl}")
        assertTrue((out.verificationUrl as String).endsWith("/cli-login?code=${out.userCode}"), "verification url ${out.verificationUrl}")
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

    private static String sha256(String value) {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)).encodeHex().toString()
    }
}
