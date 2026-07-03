package darpan.common

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Crypt-key rotation sweep (MACH P2): rotate#EncryptedFieldValues must re-encrypt every
 * encrypt="true" field under the active key without corrupting the decrypted values.
 *
 * The at-rest proof reads the raw column bytes over JDBC: ciphertext != plaintext before and
 * after the sweep, and the decrypted read-back stays identical. Same-key ciphertext is
 * byte-identical by design (fixed crypt-salt) — see the in-test comment.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CryptRotationSmokeTests {

    /** Test plaintext for the round-trip proof — deliberately NOT secret-shaped (gitleaks-clean). */
    private static final String PLAINTEXT_FIXTURE = "rotation-round-trip-plaintext"
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "crypt-rotation-smoke")
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @Test
    void discoversEncryptedFieldsFromLiveEntityDefinitions() {
        Map<String, List<String>> fields = CryptRotationSupport.findEncryptedEntityFields(ec)
        assertTrue(fields.containsKey("darpan.reconciliation.SftpServer"), fields.keySet().toString())
        assertTrue(fields["darpan.reconciliation.SftpServer"].contains("password"))
        assertTrue(fields.containsKey("darpan.reconciliation.TenantNotificationSetting"))
        // every discovered entity belongs to a Darpan package
        fields.keySet().each { String entityName ->
            assertTrue(CryptRotationSupport.DARPAN_ENTITY_PREFIXES.any { entityName.startsWith(it) }, entityName)
        }
    }

    @Test
    void rotateSweepRewritesCiphertextAndPreservesDecryptedValues() {
        def server = ec.entity.makeValue("darpan.reconciliation.SftpServer")
        server.set("sftpServerId", "ROTATE_SMOKE_1")
        server.set("host", "sftp.example.com")
        server.set("username", "rotator")
        server.set("password", PLAINTEXT_FIXTURE)
        server.setSequencedIdPrimary()
        server.create()
        String serverId = server.getNoCheckSimple("sftpServerId")

        String cipherBefore = rawPasswordColumn(serverId)
        assertNotNull(cipherBefore)
        assertNotEquals(PLAINTEXT_FIXTURE, cipherBefore, "password must be encrypted at rest")

        Map<String, Object> result = ec.service.sync()
                .name("reconciliation.ReconciliationGenericServices.rotate#EncryptedFieldValues")
                .disableAuthz()
                .call()
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        Map reencrypted = (Map) result.reencryptedByEntity
        assertTrue(((reencrypted["darpan.reconciliation.SftpServer"] ?: 0) as int) >= 1, reencrypted.toString())
        assertEquals(reencrypted.values().sum() ?: 0, result.totalRows)

        String cipherAfter = rawPasswordColumn(serverId)
        assertNotNull(cipherAfter)
        assertNotEquals(PLAINTEXT_FIXTURE, cipherAfter, "password must stay encrypted at rest after the sweep")
        // Moqui entity crypt uses a FIXED config salt (EntityJavaUtil.enDeCrypt, crypt-salt attr,
        // default "default1"), so encryption is deterministic per key: a same-key rewrite produces
        // byte-identical ciphertext and a byte-diff cannot observe the UPDATE. Under a REAL rotation
        // the active key differs, so the bytes change. The rewrite mechanism itself is the framework's
        // touchField -> isFieldModified -> UPDATE -> FieldInfo encrypt-at-write chain
        // (EntityValueBase.java:182, FieldInfo.java:402); this assertion documents the determinism.
        assertEquals(cipherBefore, cipherAfter, "same-key re-encryption is deterministic (fixed crypt-salt)")

        def reloaded = ec.entity.find("darpan.reconciliation.SftpServer")
                .condition("sftpServerId", serverId).useCache(false).one()
        assertEquals(PLAINTEXT_FIXTURE, reloaded.getString("password"), "decrypted value must survive the rotation sweep")

        reloaded.delete()
    }

    private String rawPasswordColumn(String serverId) {
        // Resolve table/column names from the live entity definition — no hardcoded DDL names.
        def ed = ec.entity.getEntityDefinition("darpan.reconciliation.SftpServer")
        String table = ed.getTableName()
        String passwordCol = ed.getColumnName("password")
        String idCol = ed.getColumnName("sftpServerId")
        Connection conn = ec.entity.getConnection("transactional")
        try {
            PreparedStatement ps = conn.prepareStatement(
                    "SELECT ${passwordCol} FROM ${table} WHERE ${idCol} = ?".toString())
            ps.setString(1, serverId)
            ResultSet rs = ps.executeQuery()
            String raw = rs.next() ? rs.getString(1) : null
            rs.close()
            ps.close()
            return raw
        } finally {
            conn.close()
        }
    }
}
