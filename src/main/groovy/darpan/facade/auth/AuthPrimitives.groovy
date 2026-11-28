package darpan.facade.auth

import darpan.common.MessageScope
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import org.apache.commons.validator.routines.InetAddressValidator
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The small pieces every sign-in path shares (two-factor and passkeys, password reset, CLI sign-in): a random token, the
 * SHA-256 a token is stored as, base64url, the client IP as a record keeps it, and the floor a refusal is held to.
 */
@CompileStatic
class AuthPrimitives {
    private static final Logger logger = LoggerFactory.getLogger(AuthPrimitives.class)
    private static final SecureRandom random = new SecureRandom()

    static final int TOKEN_BYTES = 32
    /** The longest text form of an IP address (IPv6 with an embedded IPv4 part). */
    static final int MAX_IP_LENGTH = 45

    /** TOKEN_BYTES random bytes, base64url without padding (43 characters). Only its hash is ever stored. */
    static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES]
        random.nextBytes(bytes)
        return b64url(bytes)
    }

    /** SHA-256, hex. Enough for a value with 80 or more random bits: nothing to guess, so no slow hash is needed. */
    static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8))
    }

    static String sha256Hex(byte[] value) {
        return MessageDigest.getInstance("SHA-256").digest(value).encodeHex().toString()
    }

    static String b64url(byte[] bytes) {
        return Base64.urlEncoder.withoutPadding().encodeToString(bytes)
    }

    static byte[] b64urlDecode(String value) {
        return Base64.urlDecoder.decode(value)
    }

    /**
     * The client IP as a record stores it: an IPv4 or IPv6 literal, else null. It comes from a proxy header, so it is
     * checked rather than trusted to fit a column: an oversized or made-up value would otherwise fail the insert (and the
     * step with it) or put text that is no address on the record.
     */
    static String recordedIp(String clientIp) {
        String ip = clientIp?.trim()
        if (!ip || ip.length() > MAX_IP_LENGTH) return null
        return InetAddressValidator.getInstance().isValid(ip) ? ip : null
    }

    /**
     * One failed login for the person (the framework's increment#UserAccountFailedLogins, in a transaction of its own so it
     * stands when the caller's refusal rolls back): a wrong password or a wrong second factor feeds the same lockout.
     * Returns whether it was counted. OC40': a count that cannot be written is an ERROR, so every uncounted guess reaches
     * Darpan's error email (ErrorLogCapture keeps ERROR, never WARN). It does not throw: the guess is already refused by
     * its caller, and TwoFactorSupport.recordFailure counts inside its own transaction, which a throw would roll back,
     * losing the wrong-code count too.
     *
     * A failed service call does not throw in Moqui: it rolls back and adds the exception text to ec.message's errors. So
     * the call is isolated: its errors (database text) never reach the caller's reply or fail the caller's transaction,
     * and the caller's own messages come back as they were (MessageScope; clearErrors would turn the counter's errors into danger messages).
     */
    @CompileDynamic
    static boolean countFailedLogin(def ec, String userId) {
        if (!userId) return false
        String failure = MessageScope.isolated(ec) {
            try {
                ec.service.sync().name("org.moqui.impl.UserServices.increment#UserAccountFailedLogins")
                        .parameters([userId: userId]).requireNewTransaction(true).call()
                return ec.message.hasError() ? (ec.message.errors.join("; ") as String) : null
            } catch (Exception e) {
                return e.message
            }
        }
        if (failure == null) return true
        logger.error("A failed login for userId {} was not counted toward its lockout: {}", userId, failure)
        return false
    }

    /**
     * Holds a refusal open until floorMillis after startedNanos, so how fast it came back says nothing about which branch
     * ran. Overshooting is not an error, but it means the masking stopped working, so it is logged: a silent regression
     * here looks exactly like a working defence.
     */
    static void holdToFloor(long startedNanos, long floorMillis, String what) {
        long elapsedMillis = Math.floorDiv(System.nanoTime() - startedNanos, 1_000_000L)
        long remainingMillis = floorMillis - elapsedMillis
        if (remainingMillis <= 0) {
            logger.warn("{} took {}ms, past its {}ms floor: replies may be told apart by timing", what, elapsedMillis, floorMillis)
            return
        }
        try {
            Thread.sleep(remainingMillis)
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt()
        }
    }
}
