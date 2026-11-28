package darpan.admin.transfer

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileDynamic
import org.moqui.util.SystemBinding

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Timestamp

/**
 * DAR-ADMIN-007. The target's side of a credentials handoff, kept without state: begin mints a one-time key pair and
 * answers the public key with a token holding the private key, the tenant, the expiry, the key's fingerprint and this
 * instance's id. The token is AES-256-GCM under a key HKDF-SHA256 derives from the instance's entity crypt pass (the
 * key material encrypt="true" columns use), so an altered token never opens, and neither does one from an instance with
 * another crypt pass. The instance id (the crypt pass with the transactional database URL) also refuses a token from an
 * instance that shares the crypt pass but not the database.
 *
 * <p>Not single-use, on purpose (DAR-OPS-029 S29, Coordinator 15:05): a token opens for every preview and apply until it
 * expires, because a preview opens it as well as the apply. A replay gains nothing. It opens only on this instance, for this
 * tenant and the key it was minted with. Using it takes a super admin on this instance. All it can do is write the same
 * credentials into this tenant's own connections, which no read hands back. Making it single-use would need stored
 * consumed tokens for no new protection. If that changes, it becomes its own backlog item.</p>
 */
@CompileDynamic
class SecretHandoff {

    static final long LIFETIME_MILLIS = 30L * 60L * 1000L

    /** [publicKey, handoffToken, keyFingerprint, expiresAt] for the target tenant. nowMillis is the clock, for tests. */
    static Map<String, Object> begin(def ec, String tenantId, long nowMillis = System.currentTimeMillis()) {
        return beginFor(cryptPass(ec), databaseUrl(), tenantId, nowMillis)
    }

    /**
     * [privateKey, keyFingerprint] when the token opens here, is the tenant's and is not expired; null for anything else.
     * An instance with no crypt pass throws: that is a configuration fault, never a refused token.
     */
    static Map<String, String> open(def ec, String token, String tenantId, long nowMillis = System.currentTimeMillis()) {
        if (!token?.trim()) return null
        return openFor(cryptPass(ec), databaseUrl(), token, tenantId, nowMillis)
    }

    /** {@link #begin} on an instance named by its crypt pass and database URL: the seam a test proves the binding through. */
    static Map<String, Object> beginFor(String cryptPass, String databaseUrl, String tenantId, long nowMillis) {
        Map<String, String> pair = SecretSeal.newKeyPair()
        long expiresAt = nowMillis + LIFETIME_MILLIS
        String token = SecretSeal.sealToken(SecretSeal.handoffKey(cryptPass), JsonOutput.toJson([privateKey: pair.privateKey,
                tenantUserGroupId: tenantId, expiresAt: expiresAt, keyFingerprint: pair.keyFingerprint,
                instanceId: instanceId(cryptPass, databaseUrl)]))
        return [publicKey: pair.publicKey, handoffToken: token, keyFingerprint: pair.keyFingerprint,
                expiresAt: new Timestamp(expiresAt)] as Map<String, Object>
    }

    /** {@link #open} on an instance named by its crypt pass and database URL. */
    static Map<String, String> openFor(String cryptPass, String databaseUrl, String token, String tenantId, long nowMillis) {
        if (!token?.trim()) return null
        try {
            Map payload = new JsonSlurper().parseText(SecretSeal.openToken(SecretSeal.handoffKey(cryptPass), token.trim())) as Map
            if (payload.instanceId != instanceId(cryptPass, databaseUrl) || payload.tenantUserGroupId != tenantId) return null
            if (!(payload.expiresAt instanceof Number) || ((Number) payload.expiresAt).longValue() < nowMillis) return null
            return [privateKey: payload.privateKey as String, keyFingerprint: payload.keyFingerprint as String]
        } catch (Exception ignored) {
            // Altered, foreign or unreadable answers like expired: one refusal, nothing about why that could help a forger.
            return null
        }
    }

    /** The entity-facade crypt-pass, expanded as Moqui expands it; a transfer cannot hand off credentials without one. */
    private static String cryptPass(def ec) {
        String raw = ec.factory.getConfXmlRoot().first("entity-facade")?.attribute("crypt-pass")
        String pass = raw?.contains('${') ? SystemBinding.expand(raw) : raw
        if (!pass?.trim() || pass.contains('${')) throw new IllegalStateException("This instance has no entity crypt pass to seal a handoff with.")
        return pass
    }

    private static String databaseUrl() {
        return SystemBinding.expand('${entity_ds_url}') ?: ""
    }

    private static String instanceId(String cryptPass, String databaseUrl) {
        return MessageDigest.getInstance("SHA-256").digest("${cryptPass}|${databaseUrl ?: ''}".toString().getBytes(StandardCharsets.UTF_8))
                .encodeHex().toString()
    }
}
