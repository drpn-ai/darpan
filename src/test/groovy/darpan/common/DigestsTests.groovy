package darpan.common

import org.junit.jupiter.api.Test

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import static org.junit.jupiter.api.Assertions.assertEquals

/**
 * DAR-OPS-029 S99-072. One SHA-256 hex helper for the six that were written by hand (AuditChangePlanner, BrainTicketSupport,
 * AuthPrimitives, AuditSharedReadSupport, ConfigTransferImporter, TenantConfigVersionSupport). Their stored values (token
 * hashes, idempotency keys, share keys, plan and pack hashes) must not move, so the output is pinned to test vectors and to
 * both formulas the six used (encodeHex, and %02x per byte).
 */
class DigestsTests {

    @Test
    void sha256HexMatchesTheStandardVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Digests.sha256Hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Digests.sha256Hex("abc"))
        assertEquals("1e77cb71920ad52885fab06465a5fcf2e5f98c0382aab36202689431b93b9fb3", Digests.sha256Hex("é✓"))
        assertEquals("ef192b7af54e943f206ab27075ec1805384c972c9959fc5820f1fa7d5268fcef", Digests.sha256Hex([0xff, 0x00, 0x80] as byte[]))
    }

    @Test
    void aNullTextHashesAsTheEmptyText() {
        assertEquals(Digests.sha256Hex(""), Digests.sha256Hex((String) null))
    }

    @Test
    void sameOutputAsBothHandWrittenFormulas() {
        (0..<200).each { int i ->
            String text = "tenant-${i}|é${i * 7919}".toString()
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))
            assertEquals(digest.encodeHex().toString(), Digests.sha256Hex(text), text)
            assertEquals(digest.collect { String.format("%02x", it) }.join(), Digests.sha256Hex(text), text)
        }
    }
}
