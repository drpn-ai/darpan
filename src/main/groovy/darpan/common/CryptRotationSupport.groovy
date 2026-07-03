package darpan.common

import darpan.facade.common.TenantScopedFinder
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * One-shot bulk re-encryption for a crypt-key rotation window (MACH P2, security).
 *
 * <p>Completes the rotation runbook (docs/crypt-key-rotation.md step 4): while
 * {@code entity_ds_crypt_pass_old} still allows old-key reads via {@code <decrypt-alt>},
 * this sweep loads every row of every Darpan entity that declares {@code encrypt="true"}
 * fields and forces those columns back through an UPDATE, re-encrypting them with the
 * CURRENT key. After a full pass the old key can be dropped (runbook step 5).</p>
 *
 * <p>Encrypted fields are derived from the live entity definitions ({@code FieldInfo.encrypt})
 * — no hardcoded entity list to drift. Only entities in Darpan packages are touched.</p>
 *
 * <p>Mechanics: reading a row decrypts into the in-memory value; {@code touchField} marks the
 * column modified unconditionally so {@code update()} writes it back, and Moqui encrypts at
 * write time with the active key (FieldInfo.java:402).</p>
 */
class CryptRotationSupport {

    private static final Logger logger = LoggerFactory.getLogger(CryptRotationSupport.class)

    /** Entity-name package prefixes owned by Darpan components (core + integration plugins). */
    static final List<String> DARPAN_ENTITY_PREFIXES =
            ["darpan.", "moqui.example.darpan."].asImmutable()

    /**
     * Re-encrypt every non-null encrypted field value across all Darpan entities.
     * @return map of entityName -> re-encrypted row count (only entities with encrypted fields)
     */
    static Map<String, Integer> reencryptAll(def ec) {
        Map<String, Integer> countsByEntity = new LinkedHashMap<>()
        Map<String, List<String>> encryptedByEntity = findEncryptedEntityFields(ec)
        encryptedByEntity.each { String entityName, List<String> fields ->
            countsByEntity[entityName] = reencryptEntity(ec, entityName, fields)
        }
        int total = (countsByEntity.values().sum() ?: 0) as int
        logger.info("[crypt-rotation] re-encrypted {} rows across {} entities: {}",
                total, countsByEntity.size(), countsByEntity)
        return countsByEntity
    }

    /** entityName -> list of encrypt="true" field names, derived from live entity definitions. */
    static Map<String, List<String>> findEncryptedEntityFields(def ec) {
        Map<String, List<String>> result = new TreeMap<>()
        for (String entityName in ec.entity.getAllEntityNames()) {
            if (!DARPAN_ENTITY_PREFIXES.any { String p -> entityName.startsWith(p) }) continue
            def ed = ec.entity.getEntityDefinition(entityName)
            List<String> encrypted = []
            for (String fieldName in ed.getAllFieldNames()) {
                if (ed.getFieldInfo(fieldName)?.encrypt) encrypted << fieldName
            }
            if (encrypted) result[entityName] = encrypted
        }
        return result
    }

    protected static int reencryptEntity(def ec, String entityName, List<String> encryptedFields) {
        int rows = 0
        // System-wide migration across all tenants — the rotation applies to every stored secret.
        // These are small config tables (auth configs, SFTP servers, notification settings).
        List rowList = TenantScopedFinder.findGlobalUnscoped(ec, entityName,
                "crypt-key rotation: bulk re-encrypt of encrypt=true fields under the active key")
                .useCache(false).list() ?: []
        for (def row in rowList) {
            boolean touched = false
            for (String field in encryptedFields) {
                if (row.getNoCheckSimple(field) != null) {
                    row.touchField(field)
                    touched = true
                }
            }
            if (touched) {
                row.update()
                rows++
            }
        }
        return rows
    }
}
