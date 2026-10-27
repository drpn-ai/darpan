package darpan.admin.transfer

import groovy.transform.CompileStatic

import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * DAR-ADMIN-007. The crypto that carries a connection's secrets from the source to the target without the browser
 * between them ever holding plaintext. JDK only: the target's RSA-3072 key (OAEP, SHA-256) wraps one AES-256 key per
 * bundle, and each value is sealed with AES-256-GCM under a fresh 12-byte IV and AAD "<ref>|<field>", so a ciphertext
 * opens only on the connection and field it was sealed for. Base64 throughout.
 *
 * <p>Nothing here logs, and no message it raises carries a value.</p>
 */
@CompileStatic
class SecretSeal {

    static final String ALG = "RSA-OAEP-256+A256GCM"
    private static final String RSA = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
    private static final OAEPParameterSpec OAEP = new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
    private static final String GCM = "AES/GCM/NoPadding"
    private static final SecureRandom RANDOM = new SecureRandom()

    /** A one-time key pair: [publicKey (base64 SPKI), privateKey (base64 PKCS8), keyFingerprint]. */
    static Map<String, String> newKeyPair() {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(3072, RANDOM)
        KeyPair pair = generator.generateKeyPair()
        String publicKey = b64(pair.public.encoded)
        return [publicKey: publicKey, privateKey: b64(pair.private.encoded), keyFingerprint: fingerprint(publicKey)]
    }

    /** Hex SHA-256 of the SPKI bytes. */
    static String fingerprint(String publicKey) {
        return MessageDigest.getInstance("SHA-256").digest(Base64.decoder.decode(publicKey)).encodeHex().toString()
    }

    /** The public key, or IllegalArgumentException when the text is not a base64 RSA SPKI key. */
    static PublicKey publicKey(String publicKeyText) {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.decoder.decode(publicKeyText ?: "")))
        } catch (GeneralSecurityException | IllegalArgumentException ignored) {
            throw new IllegalArgumentException("The secrets key is not an RSA public key.")
        }
    }

    /** A fresh AES-256 key for one bundle: [key, wrappedKey (base64, RSA-OAEP to the target), keyFingerprint]. */
    static Map<String, Object> newBundleKey(String publicKeyText) {
        PublicKey target = publicKey(publicKeyText)
        KeyGenerator generator = KeyGenerator.getInstance("AES")
        generator.init(256, RANDOM)
        SecretKey key = generator.generateKey()
        Cipher rsa = Cipher.getInstance(RSA)
        rsa.init(Cipher.WRAP_MODE, target, OAEP)
        return [key: key, wrappedKey: b64(rsa.wrap(key)), keyFingerprint: fingerprint(publicKeyText)] as Map<String, Object>
    }

    static SecretKey unwrap(String privateKey, String wrappedKey) {
        PrivateKey own = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.decoder.decode(privateKey)))
        Cipher rsa = Cipher.getInstance(RSA)
        rsa.init(Cipher.UNWRAP_MODE, own, OAEP)
        return (SecretKey) rsa.unwrap(Base64.decoder.decode(wrappedKey), "AES", Cipher.SECRET_KEY)
    }

    /** One value sealed for one connection field: [iv, ct]. */
    static Map<String, String> seal(SecretKey key, String ref, String field, String value) {
        byte[] iv = new byte[12]
        RANDOM.nextBytes(iv)
        Cipher aes = Cipher.getInstance(GCM)
        aes.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv))
        aes.updateAAD(aad(ref, field))
        return [iv: b64(iv), ct: b64(aes.doFinal(value.getBytes(StandardCharsets.UTF_8)))]
    }

    /** The value, or GeneralSecurityException when it was sealed elsewhere, altered, or under another key. */
    static String open(SecretKey key, String ref, String field, Map sealed) {
        try {
            Cipher aes = Cipher.getInstance(GCM)
            aes.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.decoder.decode(sealed?.get("iv") as String)))
            aes.updateAAD(aad(ref, field))
            return new String(aes.doFinal(Base64.decoder.decode(sealed?.get("ct") as String)), StandardCharsets.UTF_8)
        } catch (IllegalArgumentException | NullPointerException ignored) {
            throw new GeneralSecurityException("The sealed value is not well formed.")
        }
    }

    private static byte[] aad(String ref, String field) {
        return "${ref}|${field}".toString().getBytes(StandardCharsets.UTF_8)
    }

    private static String b64(byte[] bytes) {
        return Base64.encoder.encodeToString(bytes)
    }
}
