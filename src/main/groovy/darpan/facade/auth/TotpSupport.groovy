package darpan.facade.auth

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import groovy.transform.CompileStatic
import org.apache.commons.codec.binary.Base32

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * DAR-BE-264 — time-based one-time codes (RFC 6238 over RFC 4226): 6 digits, 30-second steps, HMAC-SHA1, the setting
 * every authenticator app reads. Own code rather than the framework's samstevens verifier because that one answers only
 * true/false, and the replay check needs to know WHICH step matched.
 *
 * <p>The QR code is drawn with zxing (Apache-2.0, already shipped through the framework's samstevens dependency) as an
 * SVG: zxing core only, no java.awt, so it renders on a headless JRE.
 */
@CompileStatic
class TotpSupport {

    static final int DIGITS = 6
    static final long STEP_SECONDS = 30L
    /** The current step and one either side: about ±30 seconds of clock difference. */
    static final int WINDOW_STEPS = 1
    static final int SECRET_BYTES = 20

    private static final SecureRandom random = new SecureRandom()
    private static final int[] POWERS = [1, 10, 100, 1000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000] as int[]

    /** 20 random bytes, Base32 without padding (32 characters). */
    static String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES]
        random.nextBytes(bytes)
        return new Base32().encodeToString(bytes).replace("=", "")
    }

    static byte[] decodeSecret(String secret) {
        return new Base32().decode(secret.replaceAll(/[\s=-]/, "").toUpperCase())
    }

    static long timeStep(long epochMillis) {
        return Math.floorDiv(epochMillis, STEP_SECONDS * 1000L)
    }

    /** The code for one step, as an app shows it (zero-padded). digits up to 8, for the RFC test vectors. */
    static String code(byte[] key, long step, int digits = DIGITS) {
        byte[] message = new byte[8]
        long value = step
        for (int i = 7; i >= 0; i--) {
            message[i] = (byte) (value & 0xff)
            value = value >>> 8
        }
        Mac mac = Mac.getInstance("HmacSHA1")
        mac.init(new SecretKeySpec(key, "HmacSHA1"))
        byte[] hash = mac.doFinal(message)
        int offset = hash[hash.length - 1] & 0x0f
        int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16) |
                ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff)
        return String.valueOf(binary % POWERS[digits]).padLeft(digits, "0")
    }

    /**
     * The step a code matches, within the window around nowMillis and strictly after lastUsedStep (no replay, and no
     * older code after a newer one was used); null when none does. Every step in the window is compared, matched or
     * not, with a constant-time compare.
     */
    static Long matchStep(String secret, String submitted, long nowMillis, Long lastUsedStep) {
        if (!secret || submitted == null || submitted.length() != DIGITS || !(submitted ==~ /\d{6}/)) return null
        byte[] key = decodeSecret(secret)
        long current = timeStep(nowMillis)
        Long matched = null
        for (long step = current - WINDOW_STEPS; step <= current + WINDOW_STEPS; step++) {
            boolean equal = MessageDigest.isEqual(code(key, step).getBytes(StandardCharsets.US_ASCII),
                    submitted.getBytes(StandardCharsets.US_ASCII))
            if (equal && matched == null && (lastUsedStep == null || step > lastUsedStep)) matched = step
        }
        return matched
    }

    /** otpauth://totp/Issuer:account?secret=...&issuer=...&digits=6&period=30&algorithm=SHA1 */
    static String otpauthUri(String issuer, String accountName, String secret) {
        String label = encode(issuer) + ":" + encode(accountName)
        return "otpauth://totp/${label}?secret=${secret}&issuer=${encode(issuer)}&digits=${DIGITS}&period=${STEP_SECONDS}&algorithm=SHA1".toString()
    }

    /** The secret grouped in fours, as the setup page shows it for typing. */
    static String groupedSecret(String secret) {
        return secret.toList().collate(4).collect { List<String> group -> group.join("") }.join(" ")
    }

    /** A QR code of text as an SVG data URI (one path, black modules on white, a 4-module quiet zone). */
    static String qrDataUri(String text) {
        BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                [(EncodeHintType.ERROR_CORRECTION): ErrorCorrectionLevel.M, (EncodeHintType.MARGIN): 4] as Map<EncodeHintType, Object>)
        int width = matrix.width
        int height = matrix.height
        StringBuilder path = new StringBuilder()
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (matrix.get(x, y)) path.append("M").append(x).append(" ").append(y).append("h1v1h-1z")
            }
        }
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ${width} ${height}\" shape-rendering=\"crispEdges\">" +
                "<rect width=\"100%\" height=\"100%\" fill=\"#fff\"/><path fill=\"#000\" d=\"${path}\"/></svg>"
        return "data:image/svg+xml;base64," + svg.getBytes(StandardCharsets.UTF_8).encodeBase64().toString()
    }

    private static String encode(String value) {
        return URLEncoder.encode(value ?: "", "UTF-8").replace("+", "%20")
    }
}
