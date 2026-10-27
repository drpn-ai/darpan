package darpan.admin

import darpan.admin.transfer.SecretSeal
import org.junit.jupiter.api.Test

import javax.crypto.SecretKey
import java.security.GeneralSecurityException
import java.security.MessageDigest

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-ADMIN-007. The transfer's secret crypto on its own: RSA-OAEP-256 wraps one AES-256-GCM key per bundle, and each
 * value is sealed with AAD "<ref>|<field>" so a ciphertext opens only where it was sealed.
 */
class SecretSealTests {

    private static final Map PAIR = SecretSeal.newKeyPair()
    private static final String REF = "NETSUITE_AUTH:A1"

    @Test
    void aValueOpensOnlyWithTheUnwrappedKey() {
        Map bundleKey = SecretSeal.newBundleKey(PAIR.publicKey as String)
        Map sealed = SecretSeal.seal(bundleKey.key as SecretKey, REF, "password", "s3cret-value")
        assertFalse(sealed.toString().contains("s3cret-value"))
        assertEquals(["ct", "iv"], (sealed.keySet() as List).sort())

        SecretKey opened = SecretSeal.unwrap(PAIR.privateKey as String, bundleKey.wrappedKey as String)
        assertEquals("s3cret-value", SecretSeal.open(opened, REF, "password", sealed))
        assertEquals(PAIR.keyFingerprint, bundleKey.keyFingerprint)
    }

    @Test
    void aCiphertextDoesNotOpenUnderAnotherRefOrField() {
        Map bundleKey = SecretSeal.newBundleKey(PAIR.publicKey as String)
        Map sealed = SecretSeal.seal(bundleKey.key as SecretKey, REF, "password", "s3cret-value")
        assertThrows(GeneralSecurityException) { SecretSeal.open(bundleKey.key as SecretKey, REF, "apiToken", sealed) }
        assertThrows(GeneralSecurityException) { SecretSeal.open(bundleKey.key as SecretKey, "NETSUITE_AUTH:B2", "password", sealed) }
    }

    @Test
    void aTamperedCiphertextDoesNotOpen() {
        Map bundleKey = SecretSeal.newBundleKey(PAIR.publicKey as String)
        Map sealed = SecretSeal.seal(bundleKey.key as SecretKey, REF, "password", "s3cret-value")
        byte[] ct = Base64.decoder.decode(sealed.ct as String)
        ct[0] = (byte) (ct[0] ^ 0x01)
        assertThrows(GeneralSecurityException) {
            SecretSeal.open(bundleKey.key as SecretKey, REF, "password", [iv: sealed.iv, ct: Base64.encoder.encodeToString(ct)])
        }
    }

    @Test
    void eachSealTakesAFreshIv() {
        Map bundleKey = SecretSeal.newBundleKey(PAIR.publicKey as String)
        Map a = SecretSeal.seal(bundleKey.key as SecretKey, REF, "password", "same")
        Map b = SecretSeal.seal(bundleKey.key as SecretKey, REF, "password", "same")
        assertNotEquals(a.iv, b.iv)
        assertNotEquals(a.ct, b.ct)
    }

    @Test
    void theFingerprintIsSha256OfTheSpkiBytes() {
        byte[] spki = Base64.decoder.decode(PAIR.publicKey as String)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(spki).encodeHex().toString(), PAIR.keyFingerprint)
        assertEquals(PAIR.keyFingerprint, SecretSeal.fingerprint(PAIR.publicKey as String))
        assertEquals(3072, ((java.security.interfaces.RSAPublicKey) SecretSeal.publicKey(PAIR.publicKey as String)).modulus.bitLength())
    }

    @Test
    void hkdfMatchesRfc5869CaseOne() {
        byte[] ikm = (1..22).collect { (byte) 0x0b } as byte[]
        byte[] salt = (0..12).collect { (byte) it } as byte[]
        byte[] info = (0xf0..0xf9).collect { (byte) it } as byte[]
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
                SecretSeal.hkdf(ikm, salt, info, 42).encodeHex().toString())
    }

    @Test
    void aTokenOpensOnlyUnderItsOwnKeyAndUnaltered() {
        byte[] key = SecretSeal.handoffKey("instance-a-crypt-pass")
        String token = SecretSeal.sealToken(key, '{"tenant":"A"}')
        assertEquals('{"tenant":"A"}', SecretSeal.openToken(key, token))
        assertFalse(token.contains("tenant"))

        assertThrows(GeneralSecurityException, { SecretSeal.openToken(SecretSeal.handoffKey("instance-b-crypt-pass"), token) },
                "a token from a differently keyed instance does not open")
        char[] chars = token.toCharArray()
        int last = chars.length - 3
        chars[last] = chars[last] == ('A' as char) ? ('B' as char) : ('A' as char)
        assertThrows(GeneralSecurityException, { SecretSeal.openToken(key, new String(chars)) }, "an altered token does not open")
        assertThrows(GeneralSecurityException) { SecretSeal.openToken(key, "not-a-token") }
    }

    @Test
    void aKeyThatIsNoPublicKeyIsRefused() {
        assertThrows(IllegalArgumentException) { SecretSeal.newBundleKey("not-a-key") }
    }
}
