package darpan.admin.transfer

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileDynamic
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.impl.entity.EntityJavaUtil

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.sql.Timestamp

/**
 * DAR-ADMIN-007. The target's side of a credentials handoff, kept without state: begin mints a one-time key pair and
 * answers the public key with a token holding the private key, the tenant, the expiry and the key's fingerprint, sealed
 * with this instance's own entity encryption, the same call and key material (entity-facade crypt-pass, and its
 * decrypt-alt entries on open) that encrypt="true" columns use: EntityJavaUtil.enDeCrypt(String, boolean,
 * EntityFacadeImpl). Only this instance can open it. That method is package-private in the framework, so it is reached
 * through one reflected handle rather than copied.
 */
@CompileDynamic
class SecretHandoff {

    static final long LIFETIME_MILLIS = 30L * 60L * 1000L

    private static final Method EN_DE_CRYPT = EntityJavaUtil.getDeclaredMethod("enDeCrypt", String, boolean, EntityFacadeImpl)
    static {
        EN_DE_CRYPT.setAccessible(true)
    }

    /** [publicKey, handoffToken, keyFingerprint, expiresAt] for the target tenant. nowMillis is the clock, for tests. */
    static Map<String, Object> begin(def ec, String tenantId, long nowMillis = System.currentTimeMillis()) {
        Map<String, String> pair = SecretSeal.newKeyPair()
        long expiresAt = nowMillis + LIFETIME_MILLIS
        String token = crypt(ec, JsonOutput.toJson([privateKey: pair.privateKey, tenantUserGroupId: tenantId, expiresAt: expiresAt,
                                                    keyFingerprint: pair.keyFingerprint]), true)
        return [publicKey: pair.publicKey, handoffToken: token, keyFingerprint: pair.keyFingerprint,
                expiresAt: new Timestamp(expiresAt)] as Map<String, Object>
    }

    /** [privateKey, keyFingerprint] when the token opens here, is the tenant's and is not expired; null for anything else. */
    static Map<String, String> open(def ec, String token, String tenantId, long nowMillis = System.currentTimeMillis()) {
        if (!token?.trim()) return null
        try {
            Map payload = new JsonSlurper().parseText(crypt(ec, token.trim(), false)) as Map
            if (payload.tenantUserGroupId != tenantId || !(payload.expiresAt instanceof Number)) return null
            if (((Number) payload.expiresAt).longValue() < nowMillis) return null
            return [privateKey: payload.privateKey as String, keyFingerprint: payload.keyFingerprint as String]
        } catch (Exception ignored) {
            // Unreadable answers like expired: one refusal, and nothing about why that could help a forger.
            return null
        }
    }

    private static String crypt(def ec, String value, boolean encrypt) {
        try {
            return EN_DE_CRYPT.invoke(null, value, encrypt, ec.entity) as String
        } catch (InvocationTargetException e) {
            throw e.cause ?: e
        }
    }
}
