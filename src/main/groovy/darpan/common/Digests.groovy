package darpan.common

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * DAR-OPS-029 S99-072. SHA-256 as lowercase hex, one way for every caller: token storage (AuthPrimitives), idempotency keys
 * (BrainTicketSupport), share keys (AuditSharedReadSupport), plan hashes (AuditChangePlanner, ConfigTransferImporter) and pack
 * hashes (TenantConfigVersionSupport). Stored values depend on it: DigestsTests pins the output to test vectors.
 */
class Digests {
    /** SHA-256 of the text's UTF-8 bytes; a null text hashes as "". */
    static String sha256Hex(String text) {
        return sha256Hex((text ?: "").getBytes(StandardCharsets.UTF_8))
    }

    static String sha256Hex(byte[] bytes) {
        return MessageDigest.getInstance("SHA-256").digest(bytes).encodeHex().toString()
    }
}
