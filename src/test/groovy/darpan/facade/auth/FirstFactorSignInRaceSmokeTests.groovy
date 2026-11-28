package darpan.facade.auth

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.Moqui
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import static org.junit.jupiter.api.Assertions.*

/**
 * OC78 (DAR-OPS-029 S24, from S23's adversarial pass). login#Session decided "no factor" without the person lock, then
 * issued a key. A password-only sign-in racing the confirm that turns two-factor on could read the factors before the
 * confirm committed, so it kept a working key after two-factor was on, one that read as a fresh sign-in (DAR-UI-132) and
 * so could add its own passkey. Every change that turns a factor on holds TwoFactorRecords.lockPerson to its commit; a
 * sign-in that takes the same lock after the password matched either finishes first (and the confirm's endSessions ends
 * its key) or waits and sees the factor (a challenge, no key).
 *
 * The confirm is played by a thread that takes the person lock and adds an ACTIVE factor, then holds the lock until the
 * sign-in has answered or 2 seconds have passed (H2 waits 30 seconds for a lock). The sign-in must come back with a
 * challenge, never a key.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SharedMoqui
class FirstFactorSignInRaceSmokeTests {
    private static final String USER_ID = "OC78_RACER"
    private static final String USERNAME = "oc78.racer"
    private static final String PASSWORD = "Oc78-race-Aa1!"
    private static final long HOLD_MILLIS = 2_000L

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "first-factor-sign-in-race")
        // The factor type and status enums the confirm's factor row refers to.
        ec.entity.makeDataLoader().location("component://darpan/data/TwoFactorSeedData.xml").dummyFks(true).load()
        boolean was = ec.artifactExecution.disableAuthz()
        try {
            // No role and no membership: two-factor is optional for this person, so without a factor a sign-in gets a key.
            // No failed logins and not logged out: the realm then writes nothing to the row on a correct password.
            ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: USER_ID, username: USERNAME, userFullName: USERNAME,
                    emailAddress: "${USERNAME}@example.com".toString(), disabled: "N", requirePasswordChange: "N",
                    hasLoggedOut: "N", successiveFailedLogins: 0]).createOrUpdate()
            ec.entity.find(TwoFactorRecords.FACTOR).condition("userId", USER_ID).deleteAll()
            ec.entity.find("moqui.security.UserLoginKey").condition("userId", USER_ID).deleteAll()
        } finally {
            if (!was) ec.artifactExecution.enableAuthz()
        }
        ec.service.sync().name("org.moqui.impl.UserServices.update#PasswordInternal")
                .parameters([userId: USER_ID, newPassword: PASSWORD, newPasswordVerify: PASSWORD, requirePasswordChange: "N"])
                .disableAuthz().call()
        assertFalse(ec.message.hasError(), "fixture password refused: ${ec.message.errors}")
        ec.message.clearAll()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @Test
    @DisplayName("OC78: a password sign-in racing the confirm that turns two-factor on gets a challenge, never a key")
    void aSignInRacingTheFirstFactorGetsAChallenge() {
        CountDownLatch locked = new CountDownLatch(1)
        CountDownLatch answered = new CountDownLatch(1)
        Map<String, Object> confirm = [:]
        Map<String, Object> signIn = [:]

        Thread confirmThread = new Thread({
            ExecutionContext tec = Moqui.getExecutionContextFactory().getExecutionContext()
            try {
                tec.artifactExecution.disableAuthz()
                tec.transaction.runRequireNew(null, "OC78: the confirm holds the person lock while the factor goes on", {
                    TwoFactorRecords.lockPerson(tec, USER_ID)
                    tec.entity.makeValue(TwoFactorRecords.FACTOR).setAll([userId: USER_ID, factorTypeEnumId: TwoFactorRecords.TYPE_TOTP,
                            statusId: TwoFactorRecords.ACTIVE, secret: TotpSupport.newSecret(), createdDate: tec.user.nowTimestamp,
                            confirmedDate: tec.user.nowTimestamp]).setSequencedIdPrimary().create()
                    locked.countDown()
                    confirm.signInAnsweredWhileLocked = answered.await(HOLD_MILLIS, TimeUnit.MILLISECONDS)
                })
            } catch (Throwable t) {
                confirm.failure = t
            } finally {
                locked.countDown()
                tec.destroy()
            }
        } as Runnable, "oc78-confirm")

        Thread signInThread = new Thread({
            ExecutionContext tec = Moqui.getExecutionContextFactory().getExecutionContext()
            try {
                assertTrue(locked.await(10, TimeUnit.SECONDS), "the confirm never took the lock")
                signIn.result = tec.service.sync().name("facade.AuthFacadeServices.login#Session")
                        .parameters([username: USERNAME, password: PASSWORD]).call()
                signIn.errors = new ArrayList<String>(tec.message.errors)
            } catch (Throwable t) {
                signIn.failure = t
            } finally {
                answered.countDown()
                tec.destroy()
            }
        } as Runnable, "oc78-sign-in")

        confirmThread.start()
        signInThread.start()
        confirmThread.join(20_000L)
        signInThread.join(20_000L)

        if (confirm.failure) throw (Throwable) confirm.failure
        if (signIn.failure) throw (Throwable) signIn.failure
        Map result = signIn.result as Map
        assertEquals([], signIn.errors)
        assertFalse(confirm.signInAnsweredWhileLocked as boolean,
                "the sign-in answered while the confirm still held the person lock: it decided on factors it could not see yet (${result})")
        assertTrue(result.twoFactorRequired as boolean, "the sign-in must see the factor the confirm turned on: ${result}")
        assertFalse(result.authenticated as boolean)
        assertNull(result.authToken, "no key for a password-only sign-in once two-factor is on")
        boolean was = ec.artifactExecution.disableAuthz()
        try {
            assertEquals(0L, ec.entity.find("moqui.security.UserLoginKey").condition("userId", USER_ID).useCache(false).count())
        } finally {
            if (!was) ec.artifactExecution.enableAuthz()
        }
    }
}
