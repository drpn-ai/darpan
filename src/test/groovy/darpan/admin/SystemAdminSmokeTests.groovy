package darpan.admin

import darpan.admin.system.SqlReadOnlyRunner
import darpan.admin.system.SystemAdminSupport
import darpan.facade.auth.SignInChallenges
import darpan.facade.auth.TotpSupport
import darpan.facade.auth.TwoFactorRecords
import darpan.facade.auth.TwoFactorSupport
import darpan.facade.common.CliMethodAllowlist
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-ADMIN-017 — admin.SystemAdminServices on a booted Moqui (H2): who may call it, the fail-closed read-only datasource,
 * the 15-minute SQL window (opened by a fresh proof, not spent on a refused statement, closed when it runs out or on
 * sign-out), the trail row for every call (written outside the query's rolled-back transaction), the row cap, masking,
 * server facts, and the cache list and clear. The read-only connection is a separate in-memory H2 through
 * SystemAdminSupport.readOnlyConnection (restored after each test).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SharedMoqui
class SystemAdminSmokeTests {
    private ExecutionContext ec
    private long clock = 1_800_000_000_000L
    private Closure<Long> originalClock

    private static final String SQL = "admin.SystemAdminServices.get#SqlQueryResult"
    private static final String WINDOW = "admin.SystemAdminServices.get#SqlWindow"
    private static final String WINDOW_ENTITY = "darpan.admin.SqlRunnerWindow"
    private static final String SUPER_ID = "SYS_SUPER"
    private static final String SUPER_USERNAME = "sys.super"
    private static final String OTHER_ID = "SYS_OTHER"
    private static final String OTHER_USERNAME = "sys.other"
    private static final String PASSWORD = "Sys-passw0rd-one!"
    private static final String RO_URL = "jdbc:h2:mem:system_admin_smoke_ro;DB_CLOSE_DELAY=-1"
    private static final String TEST_CACHE = "darpan.test.SystemAdminSmokeTests"

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "system-admin-smoke")
        ["SecuritySeedData.xml", "TwoFactorSeedData.xml"].each {
            ec.entity.makeDataLoader().location("component://darpan/data/${it}".toString()).dummyFks(true).load()
        }
        Connection con = DriverManager.getConnection(RO_URL, "sa", "")
        def st = con.createStatement()
        st.execute("CREATE TABLE ITEM (ID INT PRIMARY KEY, NAME VARCHAR(100), CURRENT_PASSWORD VARCHAR(100))")
        (1..5).each { st.execute("INSERT INTO ITEM VALUES (${it}, 'item ${it}', 'hash-${it}')".toString()) }
        st.close()
        // keeps the in-memory database alive for the class
        roKeeper = con
    }
    private Connection roKeeper

    @AfterAll
    void cleanup() {
        SystemAdminSupport.resetForTests()
        roKeeper?.close()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void reset() {
        originalClock = TwoFactorSupport.codeClock
        TwoFactorSupport.codeClock = { clock }
        SystemAdminSupport.readOnlyConnection = { DriverManager.getConnection(RO_URL, "sa", "") }
        ec.user.logoutUser()
        [TwoFactorRecords.FACTOR, TwoFactorRecords.RECOVERY, SignInChallenges.CHALLENGE, WINDOW_ENTITY].each {
            ec.entity.find(it).disableAuthz().deleteAll()
        }
        AuditLogTestSupport.wipe(ec)
        [[SUPER_ID, SUPER_USERNAME], [OTHER_ID, OTHER_USERNAME]].each { upsertAccount(it[0], it[1]) }
        ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: "DARPAN_SUPER_ADMIN", userId: SUPER_ID,
                fromDate: new Timestamp(System.currentTimeMillis() - 60_000L)]).createOrUpdate()
        ec.message.clearAll()
    }

    @AfterEach
    void restore() {
        TwoFactorSupport.codeClock = originalClock
        SystemAdminSupport.resetForTests()
        ec.user.logoutUser()
        ec.message.clearAll()
    }

    @Test
    @DisplayName("refused: not a super admin; no read-only datasource; no window and no proof; a bad statement spends no code; a wrong code")
    void refusals() {
        Map notSuper = asUser(OTHER_USERNAME, SQL, [sql: "SELECT 1"])
        assertFalse(notSuper.errors.isEmpty(), "a non-super-admin is refused")
        ["get#SqlWindow", "get#ServerInfo", "get#Caches"].each {
            assertFalse(asUser(OTHER_USERNAME, "admin.SystemAdminServices.${it}".toString(), [:]).errors.isEmpty(), it)
        }
        assertFalse(asUser(OTHER_USERNAME, "admin.SystemAdminServices.clear#Cache", [cacheName: TEST_CACHE]).errors.isEmpty())
        assertEquals(0, sqlTrail().size(), "a non-super-admin leaves no SQL trail")

        String secret = enrol(SUPER_ID, SUPER_USERNAME)
        SystemAdminSupport.resetForTests()
        Map notSetUp = asUser(SUPER_USERNAME, SQL, [sql: "SELECT 1", adminCode: code(secret)])
        assertTrue(notSetUp.errors.any { it.startsWith("SQL_READONLY_NOT_CONFIGURED: ") }, "${notSetUp.errors}")
        assertFalse(windowOpen(), "no window opened while not set up")
        SystemAdminSupport.readOnlyConnection = { DriverManager.getConnection(RO_URL, "sa", "") }

        Map closed = asUser(SUPER_USERNAME, SQL, [sql: "SELECT ID FROM ITEM"])
        assertTrue(closed.errors.any { it.startsWith("SQL_WINDOW_CLOSED: ") }, "${closed.errors}")

        String unspent = code(secret)
        ["SELECT 1; SELECT 2", "SELECT * FROM ITEM INTO OUTFILE '/tmp/x'", "SELECT * FROM ITEM FOR UPDATE", "SELECT 1;",
         "DELETE FROM ITEM", "SELECT current_password FROM ITEM"].each { String bad ->
            Map refused = asUser(SUPER_USERNAME, SQL, [sql: bad, adminCode: unspent])
            assertTrue(refused.errors.any { it.startsWith("SQL_REFUSED: ") }, "${bad} -> ${refused.errors}")
        }
        assertFalse(windowOpen(), "a refused statement opens no window")

        String wrong = unspent == "000000" ? "111111" : "000000"
        Map wrongCode = asUser(SUPER_USERNAME, SQL, [sql: "SELECT ID FROM ITEM", adminCode: wrong])
        assertTrue(wrongCode.errors.any { it.startsWith("CODE: ") }, "${wrongCode.errors}")
        assertFalse(windowOpen())

        Map good = asUser(SUPER_USERNAME, SQL, [sql: "SELECT ID FROM ITEM ORDER BY ID", adminCode: unspent])
        assertEquals([], good.errors, "the code the refusals did not spend still works")
        assertEquals(true, good.out.ok)

        List details = sqlTrail()*.detailText
        assertEquals(1 + 1 + 6 + 1, details.count { (it as String).startsWith("Refused ") }, "${details}")
        assertTrue(details.any { (it as String).startsWith("Refused SQL_READONLY_NOT_CONFIGURED: SELECT 1") })
        assertTrue(details.any { (it as String).startsWith("Refused SQL_REFUSED: SELECT 1; SELECT 2") })
        assertTrue(details.any { (it as String).startsWith("Refused CODE: ") })
    }

    @Test
    @DisplayName("a fresh proof opens a 15-minute window; inside it no proof is needed; it closes when it runs out and on sign-out")
    void windowLifecycle() {
        String secret = enrol(SUPER_ID, SUPER_USERNAME)
        Map first = asUser(SUPER_USERNAME, SQL, [sql: "SELECT ID, NAME FROM ITEM ORDER BY ID", adminCode: code(secret)])
        assertEquals([], first.errors)
        assertEquals(["ID", "NAME"], first.out.columns)
        assertEquals(5, first.out.rowCount)
        assertNotNull(first.out.windowThruDate)

        Map window = call(WINDOW, [:])
        assertEquals(true, window.out.open)
        assertTrue((window.out.minutesLeft as int) in [14, 15], "${window.out}")
        long span = (window.out.thruDate as Timestamp).time - System.currentTimeMillis()
        assertTrue(span > 14 * 60_000L && span <= 15 * 60_000L, "15 minutes from opening: ${span}")

        Map second = call(SQL, [sql: "SELECT COUNT(*) AS N FROM ITEM"])
        assertEquals([], second.errors, "no proof inside the window")
        assertEquals(5L, (second.out.rows[0][0] as Number).longValue())

        def row = ec.entity.find(WINDOW_ENTITY).condition("userId", SUPER_ID).disableAuthz().one()
        row.set("thruDate", new Timestamp(System.currentTimeMillis() - 1000L))
        row.update()
        Map expired = call(SQL, [sql: "SELECT 1"])
        assertTrue(expired.errors.any { it.startsWith("SQL_WINDOW_CLOSED: ") }, "an expired window: ${expired.errors}")
        assertEquals(false, call(WINDOW, [:]).out.open)

        Map reopened = call(SQL, [sql: "SELECT 1 AS ONE", adminCode: code(secret)])
        assertEquals([], reopened.errors)
        assertTrue(windowOpen())

        call("facade.AuthFacadeServices.logout#Session", [:])
        assertNull(ec.entity.find(WINDOW_ENTITY).condition("userId", SUPER_ID).disableAuthz().one(), "signing out ends the window")

        List trail = ec.entity.find("darpan.admin.AdminAuditLog").condition("targetType", "SqlWindow").disableAuthz().useCache(false).list()
        assertEquals(2, trail.size(), "each opening is on the trail")
    }

    @Test
    @DisplayName("the row cap, masking, a DB failure and every query on the trail (the trail row outlives the rollback)")
    void capMaskFailureTrail() {
        String secret = enrol(SUPER_ID, SUPER_USERNAME)
        Map capped = asUser(SUPER_USERNAME, SQL, [sql: "SELECT * FROM ITEM ORDER BY ID", maxRows: 2, adminCode: code(secret)])
        assertEquals([], capped.errors)
        assertEquals(2, capped.out.rowCount)
        assertEquals(true, capped.out.truncated)
        assertEquals(["CURRENT_PASSWORD"], capped.out.maskedColumns)
        assertEquals([1, "item 1", SqlReadOnlyRunner.MASK], capped.out.rows[0])

        Map failed = call(SQL, [sql: "SELECT * FROM NO_SUCH_TABLE"])
        assertTrue(failed.errors.any { it.startsWith("SQL_FAILED: ") }, "${failed.errors}")

        List details = sqlTrail()*.detailText
        assertTrue(details.any { (it as String).startsWith("2+ rows in ") && (it as String).endsWith("SELECT * FROM ITEM ORDER BY ID") }, "${details}")
        assertTrue(details.any { (it as String).startsWith("SQL_FAILED after ") }, "${details}")
        assertTrue(sqlTrail().every { it.adminUserId == SUPER_ID })
    }

    @Test
    @DisplayName("server facts; caches listed; one cleared by name (on the trail); an unknown name refused")
    void serverAndCaches() {
        signIn(SUPER_USERNAME)
        Map info = call("admin.SystemAdminServices.get#ServerInfo", [:])
        assertEquals([], info.errors)
        Map server = info.out.serverInfo as Map
        assertTrue((server.components as List).any { it.name == "darpan" })
        assertNotNull((server.statusMap as Map).JavaStats)
        assertNotNull(server.workerPool)
        assertEquals(true, server.sqlReadOnlyConfigured, "true through the seam")
        SystemAdminSupport.resetForTests()
        assertEquals(false, (call("admin.SystemAdminServices.get#ServerInfo", [:]).out.serverInfo as Map).sqlReadOnlyConfigured)

        def cache = ec.cache.getCache(TEST_CACHE)
        cache.put("a", 1)
        cache.put("b", 2)
        Map listed = call("admin.SystemAdminServices.get#Caches", [filterRegexp: ".*SystemAdminSmokeTests"])
        assertEquals([], listed.errors)
        assertEquals([TEST_CACHE], (listed.out.caches as List)*.name)
        assertEquals(2L, (listed.out.caches as List)[0].size as long)

        Map cleared = call("admin.SystemAdminServices.clear#Cache", [cacheName: TEST_CACHE])
        assertEquals([], cleared.errors)
        assertEquals(2, cleared.out.clearedCount)
        assertEquals(0, cache.size())
        Map unknown = call("admin.SystemAdminServices.clear#Cache", [cacheName: "no.such.cache"])
        assertTrue(unknown.errors.any { it.startsWith("CACHE_UNKNOWN: ") }, "${unknown.errors}")

        List trail = ec.entity.find("darpan.admin.AdminAuditLog").condition("serviceName", "admin.SystemAdminServices.clear#Cache")
                .disableAuthz().useCache(false).list()
        assertEquals([[TEST_CACHE, "Cleared 2 entries.", SUPER_ID]], trail.collect { [it.targetId, it.detailText, it.adminUserId] })
    }

    @Test
    @DisplayName("every SystemAdminServices method is off the tenant CLI (darpan); darpan-admin reaches them as admin.*")
    void offTheTenantCli() {
        ["clear#Cache", "get#Caches", "get#ServerInfo", "get#SqlQueryResult", "get#SqlWindow"].each {
            String method = "admin.SystemAdminServices.${it}".toString()
            assertTrue(method in CliMethodAllowlist.EXCLUDED, method)
            assertFalse(method in CliMethodAllowlist.ALLOWED, method)
        }
    }

    private boolean windowOpen() {
        def row = ec.entity.find(WINDOW_ENTITY).condition("userId", SUPER_ID).disableAuthz().useCache(false).one()
        return row != null && (row.thruDate as Timestamp).after(new Timestamp(System.currentTimeMillis()))
    }

    private List sqlTrail() {
        ec.entity.find("darpan.admin.AdminAuditLog").condition("serviceName", SQL).condition("targetType", "SqlQuery")
                .disableAuthz().useCache(false).list()
    }

    /** Signed in, set up and confirm; returns the TOTP secret. */
    private String enrol(String userId, String username) {
        signIn(username)
        Map begun = call("facade.AuthFacadeServices.begin#TwoFactorSetup", [currentPassword: PASSWORD])
        assertEquals([], begun.errors)
        Map confirmed = call("facade.AuthFacadeServices.confirm#TwoFactorSetup", [code: code(begun.out.secret as String)])
        assertEquals([], confirmed.errors)
        ec.user.logoutUser()
        return begun.out.secret
    }

    /** The next step's code: the clock moves 30 seconds first, so no two calls share a step. */
    private String code(String secret) {
        clock += 30_000L
        return TotpSupport.code(TotpSupport.decodeSecret(secret), TotpSupport.timeStep(clock))
    }

    private Map asUser(String username, String service, Map params) {
        signIn(username)
        return call(service, params)
    }

    private void signIn(String username) {
        ec.user.logoutUser()
        assertTrue(ec.user.internalLoginUser(username), "could not sign in ${username}")
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
        errors.addAll(ec.message.validationErrors*.message)
        ec.message.clearAll()
        return [out: out, errors: errors]
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
