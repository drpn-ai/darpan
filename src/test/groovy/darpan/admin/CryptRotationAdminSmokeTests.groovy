package darpan.admin

import darpan.common.CryptRotationSupport
import darpan.facade.auth.TotpSupport
import darpan.facade.auth.SignInChallenges
import darpan.facade.auth.TwoFactorRecords
import darpan.facade.auth.TwoFactorSupport
import darpan.facade.common.CliMethodAllowlist
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext
import org.moqui.impl.entity.EntityJavaUtil
import org.moqui.util.MNode

import java.lang.reflect.Method
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-OPS-019 — admin.SecurityAdminServices.rotate#EncryptedFields against a booted Moqui on H2: who may run it, the dry
 * run's per-key counts, the refusal when no old key is wired, and a real rotation after switching the live entity-facade
 * key in the test (the conf node is read on every encrypt/decrypt, so changing its crypt-pass is a key change). Rows are
 * rotated back under the original key before the conf is restored.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CryptRotationAdminSmokeTests {
    private ExecutionContext ec
    private long clock = 1_800_000_000_000L
    private Closure<Long> originalClock

    private static final String SERVICE = "admin.SecurityAdminServices.rotate#EncryptedFields"
    private static final String SFTP = "darpan.reconciliation.SftpServer"
    private static final String SUPER_ID = "CR_SUPER"
    private static final String SUPER_USERNAME = "cr.super"
    private static final String OTHER_ID = "CR_OTHER"
    private static final String OTHER_USERNAME = "cr.other"
    private static final String PASSWORD = "Cr-passw0rd-one!"
    // Deliberately not secret-shaped (gitleaks-clean).
    private static final String NEW_KEY = "rotation-test-new-key"
    private static final String STRAY_KEY = "rotation-test-stray-key"
    private static final String PLAIN_PASSWORD = "rotation-plain-password"
    private static final String PLAIN_KEY = "rotation-plain-private-key"

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "crypt-rotation-admin-smoke")
        ["SecuritySeedData.xml", "TwoFactorSeedData.xml"].each {
            ec.entity.makeDataLoader().location("component://darpan/data/${it}".toString()).dummyFks(true).load()
        }
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void reset() {
        originalClock = TwoFactorSupport.codeClock
        TwoFactorSupport.codeClock = { clock }
        ec.user.logoutUser()
        [TwoFactorRecords.FACTOR, TwoFactorRecords.RECOVERY, SignInChallenges.CHALLENGE, SFTP].each {
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
        ec.user.logoutUser()
        ec.message.clearAll()
    }

    @Test
    @DisplayName("refused: not a super admin, no factor of one's own, missing or wrong code; nothing on the trail; off the CLI list")
    void refusals() {
        Map notSuper = asUser(OTHER_USERNAME, [adminCode: "123456"])
        assertFalse(notSuper.errors.isEmpty(), "a non-super-admin is refused")

        Map unenrolled = asUser(SUPER_USERNAME, [adminCode: "123456"])
        assertTrue(unenrolled.errors.any { it.contains("Set up two-factor on your own account") }, "${unenrolled.errors}")

        String secret = enrol(SUPER_ID, SUPER_USERNAME)
        Map missing = asUser(SUPER_USERNAME, [:])
        assertFalse(missing.errors.isEmpty(), "adminCode is required")
        assertNotEquals(true, missing.out.ok)
        Map wrong = asUser(SUPER_USERNAME, [adminCode: wrongCode(secret)])
        assertTrue(wrong.errors.any { it.contains("That code didn't work") }, "${wrong.errors}")
        Map wrongReal = asUser(SUPER_USERNAME, [adminCode: wrongCode(secret), dryRun: false])
        assertFalse(wrongReal.errors.isEmpty())

        assertEquals(0, auditRows(SERVICE).size(), "a refusal writes no row")
        assertTrue(SERVICE in CliMethodAllowlist.EXCLUDED)
        assertFalse(SERVICE in CliMethodAllowlist.ALLOWED)
    }

    @Test
    @DisplayName("dry run counts values per key and writes nothing but its trail row; a real run with no old key is refused")
    void dryRunAndNothingToRotate() {
        String secret = enrol(SUPER_ID, SUPER_USERNAME)
        createSftp("CR_CURRENT", PLAIN_PASSWORD, PLAIN_KEY)
        withCurrentKey(STRAY_KEY) { createSftp("CR_STRAY", PLAIN_PASSWORD, null) }
        String strayRaw = rawColumn("CR_STRAY", "password")

        Map dry = asUser(SUPER_USERNAME, [adminCode: code(secret)])
        assertEquals([], dry.errors)
        assertEquals(true, dry.out.ok)
        assertEquals(false, dry.out.oldKeyConfigured, "test conf wires the same key as old and current")
        assertEquals(false, dry.out.rotationPending)
        assertNull(dry.out.totalRows)
        assertEquals([valueCount: 3, currentKey: 2, oldKeyOnly: 0, undecryptable: 1], counts(dry.out, SFTP))
        assertEquals([valueCount: 1, currentKey: 1, oldKeyOnly: 0, undecryptable: 0], counts(dry.out, TwoFactorRecords.FACTOR))
        assertEquals(dry.out.entities.sum { it.valueCount }, dry.out.valueCount)
        assertEquals(1, dry.out.undecryptableCount)
        assertEquals(strayRaw, rawColumn("CR_STRAY", "password"), "a dry run writes nothing")

        def rows = auditRows(SERVICE)
        assertEquals(1, rows.size())
        assertEquals(["EncryptedFields", "ALL", SUPER_ID], [rows[0].targetType, rows[0].targetId, rows[0].adminUserId])
        assertTrue((rows[0].detailText as String).startsWith("Dry run: 4 encrypted values: 3 on the current key, 0 on the old key only, 1 no configured key reads"),
                rows[0].detailText as String)
        assertNoValues(rows[0].detailText as String)

        Map real = asUser(SUPER_USERNAME, [adminCode: code(secret), dryRun: false])
        assertTrue(real.errors.any { it.contains("Nothing to rotate") }, "${real.errors}")
        assertEquals(1, auditRows(SERVICE).size(), "the refused run writes no row")
        assertEquals(0, auditRows("reconciliation.ReconciliationGenericServices.rotate#EncryptedFieldValues").size(), "the sweep never ran")
    }

    @Test
    @DisplayName("after a key switch: dry run shows the old-key values, the real run moves them to the new key, a value no key reads is left alone")
    void realRotationAfterAKeySwitch() {
        String oldKey = currentKey()
        String collision = paddingCollisionPlaintext(oldKey, NEW_KEY)
        createSftp("CR_ROTATE", PLAIN_PASSWORD, PLAIN_KEY)
        createSftp("CR_COLLIDE", collision, null)
        withCurrentKey(STRAY_KEY) { createSftp("CR_STRAY", PLAIN_PASSWORD, null) }
        String rotateRawBefore = rawColumn("CR_ROTATE", "password")
        String strayRaw = rawColumn("CR_STRAY", "password")

        Map<MNode, String> saved = switchKeys(NEW_KEY, oldKey)
        try {
            // The hazard the sweep guards against: the new key "decrypts" this old-key value without an error, into bytes.
            assertNotEquals(collision, sftp("CR_COLLIDE").getString("password"), "entity read of a padding collision is garbage")
            // Enrolled under the new key, so the code check never depends on an old-key read.
            String secret = enrol(SUPER_ID, SUPER_USERNAME)

            Map dry = asUser(SUPER_USERNAME, [adminCode: code(secret)])
            assertEquals([], dry.errors)
            assertEquals(true, dry.out.oldKeyConfigured)
            assertEquals(true, dry.out.rotationPending)
            assertEquals([valueCount: 4, currentKey: 0, oldKeyOnly: 3, undecryptable: 1], counts(dry.out, SFTP))
            assertEquals([valueCount: 1, currentKey: 1, oldKeyOnly: 0, undecryptable: 0], counts(dry.out, TwoFactorRecords.FACTOR))

            Map real = asUser(SUPER_USERNAME, [adminCode: code(secret), dryRun: false])
            assertEquals([], real.errors)
            assertEquals(true, real.out.ok)
            assertEquals(2, real.out.reencryptedByEntity[SFTP], "CR_ROTATE and CR_COLLIDE; the stray row is not touched")
            assertEquals(real.out.reencryptedByEntity.values().sum(), real.out.totalRows)
            assertEquals(false, real.out.rotationPending)
            assertEquals(0, real.out.oldKeyOnlyCount)
            assertEquals([valueCount: 4, currentKey: 3, oldKeyOnly: 0, undecryptable: 1], counts(real.out, SFTP))

            assertNotEquals(rotateRawBefore, rawColumn("CR_ROTATE", "password"), "ciphertext is now under the new key")
            assertEquals(PLAIN_PASSWORD, sftp("CR_ROTATE").getString("password"))
            assertEquals(PLAIN_KEY, sftp("CR_ROTATE").getString("privateKey"))
            assertEquals(collision, sftp("CR_COLLIDE").getString("password"), "the sweep wrote the real plaintext, not the garbage read")
            assertEquals(strayRaw, rawColumn("CR_STRAY", "password"), "a value no key reads keeps its ciphertext")

            def rows = auditRows(SERVICE)
            assertEquals(2, rows.size(), "dry run + rotation")
            String rotation = rows.find { (it.detailText as String).startsWith("Rotation:") }?.detailText
            assertEquals("Rotation: re-encrypted ${real.out.totalRows} rows across ${real.out.reencryptedByEntity.size()} entities; now 5 encrypted values: 4 on the current key, 0 on the old key only, 1 no configured key reads.".toString(), rotation)
            assertNoValues(rotation)
            assertEquals(1, auditRows("reconciliation.ReconciliationGenericServices.rotate#EncryptedFieldValues").size(), "the sweep's own row")

            Map again = asUser(SUPER_USERNAME, [adminCode: code(secret), dryRun: false])
            assertEquals([], again.errors, "idempotent")
            assertEquals([valueCount: 4, currentKey: 3, oldKeyOnly: 0, undecryptable: 1], counts(again.out, SFTP))
        } finally {
            // Back under the original key (the old one is now the "old"), then the conf as it was.
            switchKeys(oldKey, NEW_KEY)
            ec.transaction.runUseOrBegin(60, "rotate test rows back") { CryptRotationSupport.reencryptAll(ec) }
            saved.each { MNode node, String pass -> node.getAttributes().put("crypt-pass", pass) }
        }
        assertEquals(PLAIN_PASSWORD, sftp("CR_ROTATE").getString("password"), "rotated back under the original key")
        assertEquals(rotateRawBefore, rawColumn("CR_ROTATE", "password"))
    }

    @Test
    @DisplayName("isText: what a credential looks like vs what a wrong key's padding collision gives")
    void textCheck() {
        assertTrue(CryptRotationSupport.isText("plain-token_123"))
        assertTrue(CryptRotationSupport.isText("-----BEGIN KEY-----\nabc\r\n\tdef"))
        assertTrue(CryptRotationSupport.isText(""))
        assertFalse(CryptRotationSupport.isText("ab\u0000c"))
        assertFalse(CryptRotationSupport.isText("ab�c"))
        assertFalse(CryptRotationSupport.isText(null))
    }

    // ---- key switching ----

    private MNode facadeNode() { ec.factory.getConfXmlRoot().first("entity-facade") }

    private String currentKey() { facadeNode().attribute("crypt-pass") }

    /** The facade encrypts with current; every decrypt-alt entry reads old. Returns each node's crypt-pass as it was. */
    private Map<MNode, String> switchKeys(String current, String old) {
        Map<MNode, String> saved = [:]
        MNode facade = facadeNode()
        List<MNode> alts = facade.children("decrypt-alt")
        assertTrue(alts.any { it.attribute("crypt-algo") == facade.attribute("crypt-algo") }, "the conf has a decrypt-alt for the active algorithm")
        saved.put(facade, facade.getAttributes().get("crypt-pass"))
        alts.each { saved.put(it, it.getAttributes().get("crypt-pass")) }
        facade.getAttributes().put("crypt-pass", current)
        alts.each { it.getAttributes().put("crypt-pass", old) }
        return saved
    }

    private void withCurrentKey(String key, Closure body) {
        MNode facade = facadeNode()
        String was = facade.getAttributes().get("crypt-pass")
        facade.getAttributes().put("crypt-pass", key)
        try { body.call() } finally { facade.getAttributes().put("crypt-pass", was) }
    }

    /**
     * A plaintext whose ciphertext under oldKey "decrypts" under newKey without an error (AES-CBC padding happens to check
     * out, about 1 in 256) into something that is not text: the case a decrypt-alt read gets wrong.
     */
    private String paddingCollisionPlaintext(String oldKey, String newKey) {
        Method cipher = EntityJavaUtil.getDeclaredMethod("enDeCrypt", String, boolean, MNode)
        cipher.setAccessible(true)
        MNode facade = facadeNode()
        Closure<MNode> node = { String pass ->
            new MNode("decrypt-alt", ["crypt-pass": pass, "crypt-salt": facade.attribute("crypt-salt"),
                    "crypt-iter": facade.attribute("crypt-iter"), "crypt-algo": facade.attribute("crypt-algo")])
        }
        MNode oldNode = node(oldKey)
        MNode newNode = node(newKey)
        for (int i = 0; i < 20_000; i++) {
            String plain = "collision-${i}".toString()
            String encrypted = cipher.invoke(null, plain, true, oldNode)
            try {
                String garbage = cipher.invoke(null, encrypted, false, newNode)
                if (!CryptRotationSupport.isText(garbage)) return plain
            } catch (Exception ignored) {
                // the usual case: bad padding
            }
        }
        fail("no padding collision in 20000 tries")
        return null
    }

    // ---- helpers ----

    private void createSftp(String id, String password, String privateKey) {
        ec.entity.makeValue(SFTP).setAll([sftpServerId: id, host: "sftp.example.com", username: "rotator",
                password: password, privateKey: privateKey]).create()
    }

    private def sftp(String id) {
        ec.entity.find(SFTP).condition("sftpServerId", id).disableAuthz().useCache(false).one()
    }

    private Map counts(Map out, String entityName) {
        Map entry = out.entities.find { it.entityName == entityName }
        assertNotNull(entry, "${entityName} in ${out.entities*.entityName}")
        return [valueCount: entry.valueCount, currentKey: entry.currentKey, oldKeyOnly: entry.oldKeyOnly, undecryptable: entry.undecryptable]
    }

    private void assertNoValues(String detail) {
        [PLAIN_PASSWORD, PLAIN_KEY, NEW_KEY, STRAY_KEY, currentKey()].each { assertFalse(detail.contains(it), "trail row carries a value") }
    }

    private List auditRows(String serviceName) {
        ec.entity.find("darpan.admin.AdminAuditLog").condition("serviceName", serviceName).disableAuthz().useCache(false).list()
    }

    private String rawColumn(String id, String field) {
        def ed = ec.entity.getEntityDefinition(SFTP)
        Connection conn = ec.entity.getConnection(ec.entity.getEntityGroupName(SFTP))
        try {
            PreparedStatement ps = conn.prepareStatement("SELECT ${ed.getColumnName(field)} FROM ${ed.getFullTableName()} WHERE ${ed.getColumnName('sftpServerId')} = ?".toString())
            ps.setString(1, id)
            ResultSet rs = ps.executeQuery()
            String raw = rs.next() ? rs.getString(1) : null
            rs.close()
            ps.close()
            return raw
        } finally {
            conn.close()
        }
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

    private String wrongCode(String secret) {
        String right = code(secret)
        return right == "000000" ? "111111" : "000000"
    }

    private Map asUser(String username, Map params) {
        signIn(username)
        return call(SERVICE, params)
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
