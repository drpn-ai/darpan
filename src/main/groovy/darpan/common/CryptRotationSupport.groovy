package darpan.common

import darpan.facade.common.TenantScopedFinder
import org.moqui.impl.entity.EntityJavaUtil
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.lang.reflect.Method
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * One-shot bulk re-encryption for a crypt-key rotation window (MACH P2, security), and the read-only check of which key
 * each stored value is on (DAR-OPS-019).
 *
 * <p>Completes the rotation runbook (docs/crypt-key-rotation.md step 4): while
 * {@code entity_ds_crypt_pass_old} is wired into the {@code <decrypt-alt>} entries, this sweep
 * rewrites every encrypted column of every Darpan entity that declares {@code encrypt="true"}
 * fields, re-encrypting it with the CURRENT key. After a full pass the old key can be dropped.</p>
 *
 * <p>Encrypted fields are derived from the live entity definitions ({@code FieldInfo.encrypt})
 * — no hardcoded entity list to drift. Only entities in Darpan packages are touched.</p>
 *
 * <p>Mechanics (DAR-OPS-019): the plaintext is worked out here from the raw column, not taken from the
 * entity read. The read tries the current key first and keeps the first result that does not throw,
 * but these values are AES-CBC with PKCS5 padding, so a WRONG key decrypts without an error about one
 * value in 256 and hands back random bytes; and a value no key reads comes back as Moqui's
 * {@code _DECRYPT_FAILED_} placeholder. Re-encrypting either would replace the only copy of the
 * credential. Here each configured key is tried in turn (current first) and a result counts only when
 * it is text (see {@link #isText}); the sweep then sets that plaintext on the row and {@code update()}
 * encrypts it with the active key at write time (FieldInfo.java:451). A column no key reads is left
 * as it is.</p>
 */
class CryptRotationSupport {

    private static final Logger logger = LoggerFactory.getLogger(CryptRotationSupport.class)

    /** Entity-name package prefixes owned by Darpan components (core + integration plugins). */
    static final List<String> DARPAN_ENTITY_PREFIXES =
            ["darpan.", "moqui.example.darpan."].asImmutable()

    static final String CURRENT_KEY = "currentKey"
    static final String OLD_KEY_ONLY = "oldKeyOnly"
    static final String UNDECRYPTABLE = "undecryptable"

    /**
     * Re-encrypt every non-null encrypted field value across all Darpan entities.
     * @return map of entityName -> re-encrypted row count (only entities with encrypted fields)
     */
    static Map<String, Integer> reencryptAll(def ec) {
        Map<String, List<MNode>> keys = configuredKeys(ec)
        Map<String, Integer> countsByEntity = new LinkedHashMap<>()
        Map<String, List<String>> encryptedByEntity = findEncryptedEntityFields(ec)
        encryptedByEntity.each { String entityName, List<String> fields ->
            countsByEntity[entityName] = reencryptEntity(ec, entityName, fields, keys)
        }
        int total = (countsByEntity.values().sum() ?: 0) as int
        logger.info("[crypt-rotation] re-encrypted {} rows across {} entities: {}",
                total, countsByEntity.size(), countsByEntity)
        return countsByEntity
    }

    /** entityName -> list of encrypt="true" field names, derived from live entity definitions (tables only). */
    static Map<String, List<String>> findEncryptedEntityFields(def ec) {
        Map<String, List<String>> result = new TreeMap<>()
        for (String entityName in ec.entity.getAllEntityNames()) {
            if (!DARPAN_ENTITY_PREFIXES.any { String p -> entityName.startsWith(p) }) continue
            def ed = ec.entity.getEntityDefinition(entityName)
            if (ed.isViewEntity) continue
            List<String> encrypted = []
            for (String fieldName in ed.getAllFieldNames()) {
                if (ed.getFieldInfo(fieldName)?.encrypt) encrypted << fieldName
            }
            if (encrypted) result[entityName] = encrypted
        }
        return result
    }

    protected static int reencryptEntity(def ec, String entityName, List<String> encryptedFields, Map<String, List<MNode>> keys) {
        def ed = ec.entity.getEntityDefinition(entityName)
        List<String> pkFields = ed.getPkFieldNames()
        Map<String, Map<String, String>> plainByRow = [:]
        int unreadable = 0
        // System-wide migration across all tenants — the rotation applies to every stored secret.
        // These are small config tables (auth configs, SFTP servers, notification settings).
        long rowCount = TenantScopedFinder.findGlobalUnscoped(ec, entityName,
                "crypt-key rotation: count rows holding encrypt=true fields").useCache(false).count()
        if (rowCount == 0) return 0
        eachRawRow(ec, entityName, ed, pkFields, encryptedFields) { String rowKey, Map<String, String> rawByField ->
            Map<String, String> plain = [:]
            rawByField.each { String field, String raw ->
                Map<String, String> read = readRaw(raw, keys)
                if (read == null) unreadable++
                else plain.put(field, read.get("plaintext"))
            }
            if (plain) plainByRow.put(rowKey, plain)
        }
        int rows = 0
        if (plainByRow) {
            List rowList = TenantScopedFinder.findGlobalUnscoped(ec, entityName,
                    "crypt-key rotation: bulk re-encrypt of encrypt=true fields under the active key")
                    .useCache(false).list() ?: []
            for (def row in rowList) {
                Map<String, String> plain = plainByRow.get(rowKey(pkFields.collect { row.getNoCheckSimple(it)?.toString() }))
                if (!plain) continue
                plain.each { String field, String value ->
                    row.set(field, value)
                    row.touchField(field)
                }
                row.update()
                rows++
            }
        }
        if (unreadable) logger.warn("[crypt-rotation] {}: left {} values no configured key decrypts", entityName, unreadable)
        return rows
    }

    /**
     * DAR-OPS-019. Which configured key reads each stored encrypted value, writing nothing. Per entity: values that
     * decrypt with the current key (entity_ds_crypt_pass, any configured algorithm), values that decrypt only with the old
     * key (entity_ds_crypt_pass_old, through a decrypt-alt entry: the sweep still has to rewrite them) and values that no
     * configured key decrypts. Counts only: no value, plain or encrypted, leaves this method.
     *
     * @return [oldKeyConfigured, entities: [[entityName, fields, valueCount, currentKey, oldKeyOnly, undecryptable]],
     *          valueCount, currentKeyCount, oldKeyOnlyCount, undecryptableCount, rotationPending]
     */
    static Map<String, Object> keyCheck(def ec) {
        Map<String, List<MNode>> keys = configuredKeys(ec)
        List<Map<String, Object>> entities = []
        findEncryptedEntityFields(ec).each { String entityName, List<String> fields ->
            def ed = ec.entity.getEntityDefinition(entityName)
            Map<String, Integer> counts = [valueCount: 0, (CURRENT_KEY): 0, (OLD_KEY_ONLY): 0, (UNDECRYPTABLE): 0]
            // Through the entity facade first: creates a missing table the way the sweep's read would, and skips the raw
            // read when the table is empty.
            long rowCount = TenantScopedFinder.findGlobalUnscoped(ec, entityName,
                    "crypt-key check: count rows holding encrypt=true fields").useCache(false).count()
            if (rowCount > 0) eachRawRow(ec, entityName, ed, [], fields) { String rowKey, Map<String, String> rawByField ->
                rawByField.values().each { String raw ->
                    String bucket = readRaw(raw, keys)?.get("key") ?: UNDECRYPTABLE
                    counts.put("valueCount", counts.get("valueCount") + 1)
                    counts.put(bucket, counts.get(bucket) + 1)
                }
            }
            entities << ([entityName: entityName, fields: fields] + counts)
        }
        Closure<Integer> total = { String key -> (int) (entities.sum { it.get(key) } ?: 0) }
        return [oldKeyConfigured  : !keys.get("old").isEmpty(),
                entities          : entities,
                valueCount        : total("valueCount"),
                currentKeyCount   : total(CURRENT_KEY),
                oldKeyOnlyCount   : total(OLD_KEY_ONLY),
                undecryptableCount: total(UNDECRYPTABLE),
                rotationPending   : total(OLD_KEY_ONLY) > 0]
    }

    /** One line for the admin trail: counts only. */
    static String keyCheckSummary(Map check) {
        return "${check.valueCount} encrypted values: ${check.currentKeyCount} on the current key, ${check.oldKeyOnlyCount} on the old key only, ${check.undecryptableCount} no configured key reads".toString()
    }

    /**
     * The entity-facade node (current key, the settings every write uses) and its decrypt-alt entries, split by key: an
     * entry whose crypt-pass is the current key reads legacy-algorithm values ("current"); one with any other non-empty
     * crypt-pass is the old key ("old"). Expanded the way EntityJavaUtil expands them on a failed decrypt.
     */
    static Map<String, List<MNode>> configuredKeys(def ec) {
        MNode facadeNode = ec.factory.getConfXmlRoot().first("entity-facade")
        String currentPass = facadeNode.attribute("crypt-pass")
        List<MNode> current = [facadeNode]
        List<MNode> old = []
        for (MNode alt in facadeNode.children("decrypt-alt")) {
            alt.setSystemExpandAttributes(true)
            String pass = alt.attribute("crypt-pass")
            if (!pass) continue
            if (pass == currentPass) current << alt else old << alt
        }
        return [current: current, old: old]
    }

    /** EntityJavaUtil.enDeCrypt(String, boolean, MNode): the framework's own cipher for one config node (package-private). */
    private static final Method DECRYPT_WITH_NODE = EntityJavaUtil.getDeclaredMethod("enDeCrypt", String, boolean, MNode).with {
        it.setAccessible(true)
        it
    }

    /** [key: currentKey | oldKeyOnly, plaintext] from the first configured key whose result is text, current key first; null when none. */
    static Map<String, String> readRaw(String raw, Map<String, List<MNode>> keys) {
        String plain = decryptAsText(raw, keys.get("current"))
        if (plain != null) return [key: CURRENT_KEY, plaintext: plain]
        plain = decryptAsText(raw, keys.get("old"))
        if (plain != null) return [key: OLD_KEY_ONLY, plaintext: plain]
        return null
    }

    private static String decryptAsText(String raw, List<MNode> nodes) {
        for (MNode node in nodes) {
            try {
                String plain = (String) DECRYPT_WITH_NODE.invoke(null, raw, false, node)
                if (isText(plain)) return plain
            } catch (Exception ignored) {
                // wrong key or settings for this node; try the next
            }
        }
        return null
    }

    /**
     * A stored credential is text. A wrong key that passes the padding check yields random bytes, which decode with a
     * replacement character or a control character in all but about 1 in 3 million cases for even one block.
     */
    static boolean isText(String s) {
        if (s == null) return false
        for (char c in s.toCharArray()) {
            if (c == '�' as char) return false
            if (Character.isISOControl(c) && c != '\t' as char && c != '\n' as char && c != '\r' as char) return false
        }
        return true
    }

    private static String rowKey(List<String> pkValues) { return pkValues.join("\u0000") }

    /** Each row's raw (still encrypted) non-null columns, keyed by the row's primary key values. */
    private static void eachRawRow(def ec, String entityName, def ed, List<String> pkFields, List<String> fields, Closure onRow) {
        List<String> columns = (pkFields + fields).collect { ed.getColumnName(it) }
        Connection conn = ec.entity.getConnection(ec.entity.getEntityGroupName(entityName))
        try {
            PreparedStatement ps = conn.prepareStatement("SELECT ${columns.join(', ')} FROM ${ed.getFullTableName()}".toString())
            try {
                ResultSet rs = ps.executeQuery()
                try {
                    while (rs.next()) {
                        List<String> pk = pkFields.indices.collect { int i -> rs.getString(i + 1) }
                        Map<String, String> rawByField = [:]
                        fields.eachWithIndex { String field, int i ->
                            String raw = rs.getString(pkFields.size() + i + 1)
                            if (raw != null) rawByField.put(field, raw)
                        }
                        if (rawByField) onRow(rowKey(pk), rawByField)
                    }
                } finally { rs.close() }
            } finally { ps.close() }
        } finally { conn.close() }
    }
}
