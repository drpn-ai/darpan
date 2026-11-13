package darpan.facade.auth.support

import darpan.admin.AuditLogTestSupport
import darpan.facade.auth.PasskeySupport
import darpan.facade.auth.PasswordResetSupport
import darpan.facade.auth.SignInChallenges
import darpan.facade.auth.TotpSupport
import darpan.facade.auth.TwoFactorPolicy
import darpan.facade.auth.TwoFactorRecords
import darpan.facade.auth.TwoFactorSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.moqui.context.ExecutionContext

import java.sql.Timestamp
import java.util.concurrent.CountDownLatch

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-264/270 tests — the booted-Moqui fixture TwoFactorSignInSmokeTests and PasskeySignInSmokeTests share: the code
 * clock (moved 30 seconds per code, so each code is a new step and the replay check never refuses a test's own code), the
 * recorded notice emails, the wiped two-factor tables, people with a known password, and service-call helpers that
 * collect a refusal from either the MessageFacade or a refusal envelope. A subclass boots (boot) in its own @BeforeAll and
 * makes its people in its own @BeforeEach, which JUnit runs after this one.
 */
abstract class TwoFactorTestSupport {
    protected ExecutionContext ec
    protected long clock = 1_800_000_000_000L
    protected List<Map> sent = []
    protected final String password

    private Closure<Long> originalClock
    private Closure originalSendEmail

    protected TwoFactorTestSupport(String password) {
        this.password = password
    }

    /** Boots Moqui on H2 with the security, reset-email and two-factor seed data. */
    protected void boot(String name) {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), name)
        ["SecuritySeedData.xml", "PasswordResetEmailSeedData.xml", "TwoFactorSeedData.xml"].each {
            ec.entity.makeDataLoader().location("component://darpan/data/${it}".toString()).dummyFks(true).load()
        }
    }

    @AfterAll
    void shutDown() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void resetTwoFactor() {
        originalClock = TwoFactorSupport.codeClock
        TwoFactorSupport.codeClock = { clock }
        originalSendEmail = PasswordResetSupport.sendEmail
        sent = []
        PasswordResetSupport.sendEmail = { def ecArg, String templateId, String to, Map params ->
            sent << [templateId: templateId, to: to, params: params]
        }
        ec.user.logoutUser()
        [PasskeySupport.CREDENTIAL, TwoFactorRecords.FACTOR, TwoFactorRecords.RECOVERY, SignInChallenges.CHALLENGE,
         "darpan.auth.CliSession", "darpan.auth.CliDeviceCode"].each { ec.entity.find(it).disableAuthz().deleteAll() }
        AuditLogTestSupport.wipe(ec, TwoFactorRecords.EVENT)
        AuditLogTestSupport.wipe(ec)
        TwoFactorPolicy.POLICY_GROUP_IDS.each { setPolicy(it, null) }
        ec.message.clearAll()
    }

    @AfterEach
    void restoreTwoFactor() {
        TwoFactorSupport.codeClock = originalClock
        PasswordResetSupport.sendEmail = originalSendEmail
        ec.user.logoutUser()
        ec.message.clearAll()
    }

    // ---- calls ----

    /** The next step's code: the clock moves 30 seconds first, so no two calls share a step. */
    protected String code(String secret) {
        clock += 30_000L
        return TotpSupport.code(TotpSupport.decodeSecret(secret), TotpSupport.timeStep(clock))
    }

    /** login#Session with the right password for a person with two-factor on: the challenge token. */
    protected String challenge(String username) {
        Map login = anon("login#Session", [username: username, password: password]).out
        assertTrue(login.twoFactorRequired as boolean, "${login}")
        return login.challengeToken
    }

    protected Map anon(String verbNoun, Map params) {
        ec.user.logoutUser()
        return call("facade.AuthFacadeServices.${verbNoun}".toString(), params)
    }

    /** A facade.AuthFacadeServices call signed in as username (signed in afresh unless fresh is false and they already are). */
    protected Map signedIn(String username, String verbNoun, Map params, boolean fresh = true) {
        if (fresh || ec.user.username != username) {
            ec.user.logoutUser()
            assertTrue(ec.user.internalLoginUser(username), "could not sign in ${username}")
        }
        return call("facade.AuthFacadeServices.${verbNoun}".toString(), params)
    }

    protected Map signedInCall(String username, String serviceName, Map params) {
        ec.user.logoutUser()
        assertTrue(ec.user.internalLoginUser(username), "could not sign in ${username}")
        return call(serviceName, params)
    }

    /** [out, errors]: errors from a thrown call, the MessageFacade, and a refusal envelope (ok false: the refused steps
     *  answer there, not in the MessageFacade). */
    protected Map call(String serviceName, Map params) {
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

    /** Each params map sent to serviceName at once, anonymously, each on its own thread and ExecutionContext (started
     *  together); the results in order. */
    protected List<Map> anonInParallel(String serviceName, List<Map> paramsList) {
        CountDownLatch start = new CountDownLatch(1)
        List<Map> results = Collections.synchronizedList(new ArrayList<Map>(paramsList.collect { [:] }))
        List<Thread> threads = paramsList.withIndex().collect { Map params, int i ->
            Thread.start {
                def tec = org.moqui.Moqui.getExecutionContext()
                try {
                    start.await()
                    results[i] = tec.service.sync().name(serviceName).parameters(params).call() ?: [:]
                } finally {
                    tec.destroy()
                }
            }
        }
        start.countDown()
        threads*.join()
        return results
    }

    // ---- reads ----

    protected List events(String userId, String type) {
        return ec.entity.find(TwoFactorRecords.EVENT).condition("userId", userId).condition("eventType", type)
                .disableAuthz().useCache(false).list()
    }

    protected int keyCount(String userId) {
        return (int) ec.entity.find("moqui.security.UserLoginKey").condition("userId", userId).disableAuthz().useCache(false).count()
    }

    protected def loginKey(String hash) {
        return ec.entity.find("moqui.security.UserLoginKey").condition("loginKey", hash).disableAuthz().useCache(false).one()
    }

    protected def account(String userId) {
        return ec.entity.find("moqui.security.UserAccount").condition("userId", userId).disableAuthz().useCache(false).one()
    }

    // ---- fixtures ----

    protected void setPolicy(String groupId, Timestamp date) {
        ec.entity.makeValue(TwoFactorPolicy.POLICY).setAll([permissionGroupId: groupId, requiredFromDate: date]).createOrUpdate()
    }

    protected void group(String userId, String groupId) {
        ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: groupId, userId: userId,
                fromDate: new Timestamp(System.currentTimeMillis() - 60_000L)]).createOrUpdate()
    }

    /** A person with the fixture password, no roles, no keys and no failed logins. */
    protected void upsertAccount(String userId, String username) {
        ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: userId, username: username,
                userFullName: username, emailAddress: "${username}@example.com".toString(), disabled: "N",
                disabledDateTime: null, requirePasswordChange: "N", hasLoggedOut: "N", successiveFailedLogins: 0,
                currentPassword: null, passwordSalt: null]).createOrUpdate()
        ec.entity.find("moqui.security.UserGroupMember").condition("userId", userId).disableAuthz().deleteAll()
        ec.entity.find("moqui.security.UserLoginKey").condition("userId", userId).disableAuthz().deleteAll()
        ec.entity.find("moqui.security.UserPasswordHistory").condition("userId", userId).disableAuthz().deleteAll()
        ec.service.sync().name("org.moqui.impl.UserServices.update#PasswordInternal")
                .parameters([userId: userId, newPassword: password, newPasswordVerify: password, requirePasswordChange: "N"]).disableAuthz().call()
        assertFalse(ec.message.hasError(), "fixture password refused: ${ec.message.errors} ${ec.message.messages}")
        ec.message.clearAll()
    }
}
