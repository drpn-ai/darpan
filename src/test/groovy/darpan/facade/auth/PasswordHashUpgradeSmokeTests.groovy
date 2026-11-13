package darpan.facade.auth

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import jakarta.websocket.server.HandshakeRequest
import org.apache.shiro.crypto.hash.SimpleHash
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.UserFacadeImpl

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-268 — passwords move to a slow hash without locking anyone out, against a booted Moqui on H2.
 *
 * What could bite: an existing account on salted SHA-256 (or on no recorded type at all, or Base64) failing to
 * sign in once new passwords are PBKDF2; a re-hash that leaves the row unverifiable; the 12-character minimum
 * not reaching update#PasswordInternal; and a login key in a WebSocket handshake's query string still signing in.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PasswordHashUpgradeSmokeTests {
    private ExecutionContext ec

    private static final String USER_ID = "PWH_USER"
    private static final String USERNAME = "pwh.user"
    private static final String PASSWORD = "Correct-h0rse-battery"
    private static final String SLOW_TYPE = "PBKDF2WithHmacSHA256:600000"

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "password-hash-upgrade-smoke")
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void resetAccount() {
        ec.user.logoutUser()
        ec.message.clearAll()
        ec.entity.find("moqui.security.UserPasswordHistory").condition("userId", USER_ID).disableAuthz().deleteAll()
        ec.entity.find("moqui.security.UserLoginKey").condition("userId", USER_ID).disableAuthz().deleteAll()
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: USER_ID, username: USERNAME,
                userFullName: USERNAME, emailAddress: "pwh.user@example.com", disabled: "N", requirePasswordChange: "N",
                currentPassword: null, passwordSalt: null, passwordHashType: null, passwordBase64: null,
                hasLoggedOut: "N", successiveFailedLogins: 0, passwordSetDate: null]).createOrUpdate()
    }

    @AfterEach
    void signOut() {
        ec.user.logoutUser()
        ec.message.clearAll()
    }

    @Test
    void aNewPasswordIsStoredAsPbkdf2AndSignsIn() {
        setPassword(PASSWORD)
        def row = account()
        assertEquals(SLOW_TYPE, row.passwordHashType)
        assertEquals(24, row.passwordSalt.length(), "a fresh 144-bit salt per password")
        assertEquals(64, row.currentPassword.length(), "256-bit PBKDF2 output, hex")
        assertEquals("N", row.passwordBase64)

        assertTrue(signIn(PASSWORD))
        assertFalse(signIn("Wrong-h0rse-battery"))
    }

    @Test
    void anExistingSha256AccountSignsInAndIsReHashed() {
        storeLegacy("SHA-256", "salt1234", false)
        String passwordSetDate = account().passwordSetDate?.toString()

        assertFalse(signIn("Wrong-h0rse-battery"), "a wrong password is still refused on the old hash")
        assertEquals("SHA-256", account().passwordHashType, "a refused sign-in must not touch the hash")

        assertTrue(signIn(PASSWORD), "the existing SHA-256 password still signs in")
        def row = account()
        assertEquals(SLOW_TYPE, row.passwordHashType, "re-hashed on the successful sign-in")
        assertNotEquals("salt1234", row.passwordSalt)
        assertEquals(passwordSetDate, row.passwordSetDate?.toString(), "a re-hash is not a password change")

        assertTrue(signIn(PASSWORD), "and signs in on the new hash")
        assertFalse(signIn("Wrong-h0rse-battery"))
    }

    @Test
    void anAccountWithNoRecordedHashTypeStillSignsIn() {
        // Rows written before passwordHashType was recorded verify against encrypt-hash-type (SHA-256), which
        // must not have moved to the slow type with the new-password default.
        storeLegacy(null, "salt1234", false)
        assertTrue(signIn(PASSWORD))
        assertEquals(SLOW_TYPE, account().passwordHashType)
        assertTrue(signIn(PASSWORD))
    }

    @Test
    void aBase64Sha256AccountSignsInAndIsReHashedToHex() {
        storeLegacy("SHA-256", "salt1234", true)
        assertTrue(signIn(PASSWORD))
        def row = account()
        assertEquals(SLOW_TYPE, row.passwordHashType)
        assertEquals("N", row.passwordBase64)
        assertTrue(signIn(PASSWORD))
    }

    @Test
    void historyAndSameAsCurrentChecksHoldAcrossTheReHash() {
        storeLegacy("SHA-256", "salt1234", false)
        assertTrue(signIn(PASSWORD))
        assertEquals(SLOW_TYPE, account().passwordHashType)

        Map same = setPasswordOutcome(PASSWORD)
        assertFalse(same.ok as boolean, "the current (re-hashed) password is still recognised as the current one")

        setPassword("Second-h0rse-battery")
        Map reused = setPasswordOutcome(PASSWORD)
        assertFalse(reused.ok as boolean, "the old password is still found in the history")
        assertTrue(signIn("Second-h0rse-battery"))
    }

    @Test
    void passwordsShorterThanTwelveAreRefused() {
        Map refused = setPasswordOutcome("Short-pw1!x")   // 11 characters
        assertFalse(refused.ok as boolean)
        assertTrue(refused.text.any { it.contains("shorter than 12") }, "policy messages: ${refused.text}")

        Map accepted = setPasswordOutcome("Long-enou9h!")  // 12 characters
        assertTrue(accepted.ok as boolean, "policy messages: ${accepted.text}")
    }

    @Test
    void aLoginKeySignsInAsAHandshakeHeaderButNotAsAQueryParameter() {
        setPassword(PASSWORD)
        assertTrue(ec.user.loginUser(USERNAME, PASSWORD))
        String loginKey = ec.user.getLoginKey()
        ec.user.logoutUser()
        // logoutUser marks hasLoggedOut; the key itself stays valid
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: USER_ID, hasLoggedOut: "N"]).update()

        UserFacadeImpl user = (UserFacadeImpl) ec.user
        user.initFromHandshakeRequest(handshake([api_key: [loginKey]], [:]))
        assertEquals(USERNAME, ec.user.username, "api_key header signs in")
        ec.user.logoutUser()
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: USER_ID, hasLoggedOut: "N"]).update()

        user.initFromHandshakeRequest(handshake([login_key: [loginKey]], [:]))
        assertEquals(USERNAME, ec.user.username, "login_key header signs in")
        ec.user.logoutUser()
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: USER_ID, hasLoggedOut: "N"]).update()
        ec.message.clearAll()

        user.initFromHandshakeRequest(handshake([:], [api_key: [loginKey]]))
        assertNull(ec.user.username, "api_key as a request parameter must not sign in")
        user.initFromHandshakeRequest(handshake([:], [login_key: [loginKey]]))
        assertNull(ec.user.username, "login_key as a request parameter must not sign in")
    }

    // ---- helpers ----

    private static HandshakeRequest handshake(Map<String, List<String>> headers, Map<String, List<String>> parameters) {
        return [getHeaders     : { -> headers },
                getParameterMap: { -> parameters },
                getHttpSession : { -> null },
                getQueryString : { -> null },
                getRequestURI  : { -> new URI("ws://localhost/notws") },
                getUserPrincipal: { -> null },
                isUserInRole   : { String role -> false }] as HandshakeRequest
    }

    private void storeLegacy(String hashType, String salt, boolean base64) {
        SimpleHash hash = new SimpleHash("SHA-256", PASSWORD, salt)
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: USER_ID,
                currentPassword: base64 ? hash.toBase64() : hash.toHex(), passwordSalt: salt,
                passwordHashType: hashType, passwordBase64: base64 ? "Y" : "N",
                passwordSetDate: ec.user.nowTimestamp]).update()
    }

    private boolean signIn(String password) {
        ec.user.logoutUser()
        ec.message.clearAll()
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: USER_ID, hasLoggedOut: "N",
                successiveFailedLogins: 0, disabled: "N", disabledDateTime: null]).update()
        boolean signedIn = ec.user.loginUser(USERNAME, password)
        ec.user.logoutUser()
        ec.message.clearAll()
        return signedIn
    }

    private void setPassword(String password) {
        Map outcome = setPasswordOutcome(password)
        assertTrue(outcome.ok as boolean, "fixture password refused: ${outcome.text}")
    }

    private Map setPasswordOutcome(String password) {
        ec.message.clearAll()
        Map out = ec.service.sync().name("org.moqui.impl.UserServices.update#PasswordInternal")
                .parameters([userId: USER_ID, newPassword: password, newPasswordVerify: password]).disableAuthz().call()
        List<String> text = new ArrayList<>(ec.message.errors) + new ArrayList<>(ec.message.messages)
        boolean ok = !ec.message.hasError() && out?.updateSuccessful
        ec.message.clearAll()
        return [ok: ok, text: text]
    }

    private def account() {
        return ec.entity.find("moqui.security.UserAccount").condition("userId", USER_ID).useCache(false).disableAuthz().one()
    }
}
