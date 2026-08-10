package darpan.facade.auth

import darpan.facade.auth.support.MysqlLoginTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.moqui.context.ExecutionContext

import java.nio.file.Paths

/**
 * Reproduces the login self-deadlock: one request transaction updates USER_ACCOUNT (taking an X lock on the
 * row), then issues the login key through a service marked requireNewTransaction, which SUSPENDS the holder
 * of that X lock and inserts an FK child of the very row it is still locking. Nothing can make progress, and
 * because the lock holder is suspended rather than waiting, InnoDB's deadlock detector never fires — the
 * insert simply sits until innodb_lock_wait_timeout (50s on this server).
 *
 * The two triggers are the ones MoquiShiroRealm.loginPostPassword tests before it fires the reset UPDATE:
 * a non-zero successiveFailedLogins, and hasLoggedOut = 'Y'. Both are ordinary states for a real account —
 * the second one is set by every logout, so effectively every returning user takes this path.
 *
 * MySQL, not H2: H2 takes no lock on the parent row for a child insert, so the defect is invisible there.
 */
@Tag("mysql")
class LoginDeadlockRegressionTests {
    static ExecutionContext ec
    static final String PASSWORD = "Testpass@12345"
    /** Usernames are unique per JVM run: the MySQL fixture database is not dropped between runs, and
     *  create#UserAccount refuses a username already in use — which would fail fast and hide the defect. */
    static final String RUN_TOKEN = Long.toString(System.currentTimeMillis(), 36)
    static final List<String> CREATED_USER_IDS = []

    long testStartedNanos

    @BeforeAll
    static void setup() {
        ec = MysqlLoginTestSupport.initMoqui(Paths.get(System.getProperty("user.dir")).parent.parent.parent,
                "login-deadlock")
    }

    @AfterAll
    static void teardown() {
        // Best effort: leave the fixture database tidy, but never let cleanup mask a test result.
        CREATED_USER_IDS.each { String userId ->
            try {
                ec.entity.find("moqui.security.UserLoginKey").condition("userId", userId).disableAuthz().deleteAll()
                ec.entity.find("moqui.security.UserLoginHistory").condition("userId", userId).disableAuthz().deleteAll()
                ec.entity.find("moqui.security.UserAccount").condition("userId", userId).disableAuthz().deleteAll()
            } catch (Throwable ignored) { }
        }
        MysqlLoginTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void resetSessionState(TestInfo testInfo) {
        // loginSession refuses to even attempt a login while ec.message carries an error, so an error left
        // behind by the previous test would short-circuit this one into a fast, meaningless failure.
        ec.message.clearAll()
        testStartedNanos = System.nanoTime()
        println "[deadlock-test] START ${testInfo.testMethod.get().name}"
    }

    @AfterEach
    void reportElapsed(TestInfo testInfo) {
        long elapsedMillis = (System.nanoTime() - testStartedNanos).intdiv(1_000_000L)
        println "[deadlock-test] END ${testInfo.testMethod.get().name} elapsedMs=${elapsedMillis}"
        try { ec.user.logoutUser() } catch (Throwable ignored) { }
        ec.message.clearAll()
    }

    /** Create a user whose account state satisfies one of the four conditions MoquiShiroRealm checks before
     *  it fires the reset UPDATE. Both the create and the arming update commit before the login starts, so
     *  the only transaction open during the login is the one the test opens. */
    private String createArmedUser(String username, Map<String, Object> armFields) {
        Map created = ec.service.sync().name("org.moqui.impl.UserServices.create#UserAccount")
                .parameters([username: username, userFullName: username,
                             newPassword: PASSWORD, newPasswordVerify: PASSWORD,
                             requirePasswordChange: "N", disabled: "N"])
                .disableAuthz().requireNewTransaction(true).call()
        assert !ec.message.hasError(): "create#UserAccount failed: ${ec.message.errors}"
        String userId = created?.userId
        assert userId: "create#UserAccount returned no userId for ${username}"
        CREATED_USER_IDS.add(userId)

        // Arm the account. The framework update#UserAccount service excludes exactly these fields, so this
        // has to go through the entity-auto update. requireNewTransaction so it is committed and visible to
        // the login below rather than sitting in an open transaction of its own.
        ec.service.sync().name("update", "moqui.security.UserAccount")
                .parameters([userId: userId] + armFields)
                .disableAuthz().requireNewTransaction(true).call()
        assert !ec.message.hasError(): "arming update failed: ${ec.message.errors}"

        // Prove the preconditions actually landed. Without this a silently unarmed account would sail
        // through the login and turn this regression test green against broken code.
        Map account = ec.entity.find("moqui.security.UserAccount").condition("userId", userId)
                .disableAuthz().one()?.getMap()
        assert account != null: "UserAccount ${userId} not readable after create"
        assert account.requirePasswordChange != "Y":
                "requirePasswordChange still Y — login would short-circuit into the password-change branch"
        armFields.each { String field, Object expected ->
            assert account.get(field)?.toString() == expected?.toString():
                    "arming field ${field} did not commit: expected ${expected}, found ${account.get(field)}"
        }
        return userId
    }

    /** Run one login exactly the way a JSON-RPC login request runs it: loginUser and getLoginKey inside a
     *  single request transaction. Committing between the two is what makes the defect disappear, so a test
     *  that does not share the transaction proves nothing. */
    private Map loginInOneTransaction(String username) {
        Map result = null
        long startedNanos = System.nanoTime()
        try {
            ec.transaction.runUseOrBegin(null, "login deadlock probe", {
                result = AuthFacadeSupport.loginSession(ec, username, PASSWORD)
            })
        } finally {
            long elapsedMillis = (System.nanoTime() - startedNanos).intdiv(1_000_000L)
            println "[deadlock-test] loginSession(${username}) took ${elapsedMillis}ms"
        }
        return result
    }

    private static void assertLoginIssuedAWorkingKey(Map result) {
        // Order matters. `ok` is checked first because the failure this test exists to catch surfaces there:
        // Moqui's service layer converts the SQLException from the lock-wait timeout into a message error
        // instead of letting it propagate, so `authenticated` stays true while the login key was never
        // written. Asserting only on `authenticated` would go green on the broken code.
        assert result != null: "loginSession returned null"
        assert result.ok == true: "login reported errors: ${result.errors}"
        assert result.authenticated == true: "login did not authenticate: ${result}"
        assert result.authToken: "login issued no auth token: ${result}"
    }

    private static void assertLoginKeyPersisted(String userId) {
        long keyCount = ec.entity.find("moqui.security.UserLoginKey").condition("userId", userId)
                .disableAuthz().count()
        assert keyCount == 1L: "expected exactly one persisted UserLoginKey for ${userId}, found ${keyCount}"
    }

    @Test
    void loginSucceedsWhenFailedLoginCounterIsArmed() {
        String username = "deadlock.counter.${RUN_TOKEN}"
        String userId = createArmedUser(username, [successiveFailedLogins: 1])
        Map result = loginInOneTransaction(username)
        assertLoginIssuedAWorkingKey(result)
        assertLoginKeyPersisted(userId)
    }

    @Test
    void loginSucceedsWhenHasLoggedOutIsArmed() {
        String username = "deadlock.loggedout.${RUN_TOKEN}"
        String userId = createArmedUser(username, [hasLoggedOut: "Y"])
        Map result = loginInOneTransaction(username)
        assertLoginIssuedAWorkingKey(result)
        assertLoginKeyPersisted(userId)
    }

    /** Makes permanent the control Task 3 ran and discarded: an unarmed account passed the identical
     *  assertions in 515 ms on the unfixed framework. It proves a future green is the fix working rather
     *  than the harness quietly failing to arm anything. */
    @Test
    void loginStillSucceedsForAnUnarmedAccount() {
        String username = "deadlock.clean.${RUN_TOKEN}"
        String userId = createArmedUser(username, [:])
        Map result = loginInOneTransaction(username)
        assertLoginIssuedAWorkingKey(result)
        assertLoginKeyPersisted(userId)
    }

    /** Regression guard for spec §5.2 — the assertion that justifies shipping without a cleanup migration
     *  for already-broken production accounts: a successful login must clear the same armed flags that
     *  would otherwise re-trigger the deadlock on the account's next login. */
    @Test
    void signingInClearsTheArmedFlags() {
        String username = "deadlock.selfheal.${RUN_TOKEN}"
        String userId = createArmedUser(username, [successiveFailedLogins: 2, hasLoggedOut: "Y"])
        Map result = loginInOneTransaction(username)
        assertLoginIssuedAWorkingKey(result)
        Map account = ec.entity.find("moqui.security.UserAccount").condition("userId", userId)
                .disableAuthz().one()?.getMap()
        assert (account.successiveFailedLogins as Integer) == 0:
                "successiveFailedLogins not cleared: ${account.successiveFailedLogins}"
        assert account.hasLoggedOut == "N": "hasLoggedOut not cleared: ${account.hasLoggedOut}"
    }
}
