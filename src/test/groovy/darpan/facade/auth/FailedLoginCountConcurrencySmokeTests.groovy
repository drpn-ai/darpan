package darpan.facade.auth

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * DAR-OPS-029 F3 (security). Every failed sign-in (a wrong password in MoquiShiroRealm or a signed-in password check,
 * and through AuthPrimitives.countFailedLogin a wrong authenticator or recovery code) is counted by the framework's
 * increment#UserAccountFailedLogins, each in a transaction of its own. A refused passkey is not a failed login: a
 * signature cannot be guessed (PasskeySignInSmokeTests asserts it is not counted). It read the account without a row lock and wrote
 * the count plus one, so guesses landing together read the same count and all but one increment was lost: the lockout let
 * through several times max-failures. Counted under a row lock, N concurrent failures end at exactly N.
 */
@SharedMoqui
class FailedLoginCountConcurrencySmokeTests {
    static ExecutionContext ec
    static final int GUESSES = 12
    static String userId

    @BeforeAll
    static void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "failed-login-count-concurrency")
        String username = "f3.concurrent.${Long.toString(System.nanoTime(), 36)}"
        Map created = ec.service.sync().name("org.moqui.impl.UserServices.create#UserAccount")
                .parameters([username: username, userFullName: username, emailAddress: "${username}@example.com",
                             newPassword: 'Testpass@12345', newPasswordVerify: 'Testpass@12345', requirePasswordChange: 'N'])
                .disableAuthz().requireNewTransaction(true).call()
        assert !ec.message.hasError(): "create#UserAccount failed: ${ec.message.errors}"
        userId = created?.userId
        assert userId
    }

    @AfterAll
    static void teardown() {
        try {
            if (userId) ec.entity.find("moqui.security.UserAccount").condition("userId", userId).disableAuthz().deleteAll()
        } finally {
            ReconciliationSmokeTestSupport.cleanupMoqui(ec)
        }
    }

    @Test
    void concurrentFailedLoginsAreEachCounted() {
        ExecutorService pool = Executors.newFixedThreadPool(GUESSES)
        CyclicBarrier together = new CyclicBarrier(GUESSES)
        try {
            List<Future<String>> guesses = (1..GUESSES).collect {
                pool.submit({
                    // Each guess is its own request: its own execution context, its own transaction, started together.
                    ExecutionContext guess = ec.factory.getExecutionContext()
                    try {
                        together.await(30, TimeUnit.SECONDS)
                        guess.service.sync().name("org.moqui.impl.UserServices.increment#UserAccountFailedLogins")
                                .parameters([userId: userId]).requireNewTransaction(true).disableAuthz().call()
                        return guess.message.hasError() ? guess.message.errorsString : ''
                    } finally {
                        guess.destroy()
                    }
                } as Callable<String>)
            }
            List<String> errors = guesses.collect { it.get(60, TimeUnit.SECONDS) }.findAll()
            assert errors.isEmpty(): "a failed login went uncounted with an error: ${errors}"
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(30, TimeUnit.SECONDS)
        }

        Map account = ec.entity.find("moqui.security.UserAccount").condition("userId", userId).disableAuthz()
                .useCache(false).one()?.getMap()
        assert (account.successiveFailedLogins as Integer) == GUESSES:
                "${GUESSES} concurrent failed logins counted as ${account.successiveFailedLogins}: increments were lost"
        // Past max-failures the account is held, whatever order the guesses landed in.
        assert account.disabled == 'Y'
    }
}
