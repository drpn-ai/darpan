package darpan.facade.auth

import org.apache.commons.codec.binary.Base32
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

import java.nio.charset.StandardCharsets

import static org.junit.jupiter.api.Assertions.*

/** DAR-BE-264 — TOTP (RFC 6238). Pure, no boot. */
class TotpSupportTests {

    /** RFC 6238 Appendix B, SHA-1: the ASCII key "12345678901234567890". */
    private static final byte[] RFC_KEY = "12345678901234567890".getBytes(StandardCharsets.US_ASCII)
    private static final String RFC_SECRET = new Base32().encodeToString(RFC_KEY).replace("=", "")

    @Test
    @DisplayName("RFC 6238 Appendix B SHA-1 vectors, 8 digits and the low 6")
    void rfcVectors() {
        Map<Long, String> vectors = [59L: "94287082", 1111111109L: "07081804", 1111111111L: "14050471",
                1234567890L: "89005924", 2000000000L: "69279037", 20000000000L: "65353130"]
        vectors.each { long seconds, String expected ->
            long step = TotpSupport.timeStep(seconds * 1000L)
            assertEquals(expected, TotpSupport.code(RFC_KEY, step, 8), "T=${seconds}")
            assertEquals(expected.substring(2), TotpSupport.code(RFC_KEY, step), "T=${seconds}, 6 digits")
        }
    }

    @Test
    @DisplayName("steps -1, 0 and +1 are accepted, +-2 refused; the matched step is returned")
    void theWindow() {
        long now = 1_111_111_111_000L
        long current = TotpSupport.timeStep(now)
        (-1..1).each { int offset ->
            assertEquals(current + offset, TotpSupport.matchStep(RFC_SECRET, TotpSupport.code(RFC_KEY, current + offset), now, null))
        }
        [-2, 2].each { int offset ->
            assertNull(TotpSupport.matchStep(RFC_SECRET, TotpSupport.code(RFC_KEY, current + offset), now, null))
        }
    }

    @Test
    @DisplayName("the same step twice is refused, and a step older than the last one used")
    void noReplay() {
        long now = 1_111_111_111_000L
        long current = TotpSupport.timeStep(now)
        String code = TotpSupport.code(RFC_KEY, current)
        assertEquals(current, TotpSupport.matchStep(RFC_SECRET, code, now, current - 1))
        assertNull(TotpSupport.matchStep(RFC_SECRET, code, now, current), "same step")
        assertNull(TotpSupport.matchStep(RFC_SECRET, TotpSupport.code(RFC_KEY, current - 1), now, current), "older step")
        assertEquals(current + 1, TotpSupport.matchStep(RFC_SECRET, TotpSupport.code(RFC_KEY, current + 1), now, current))
    }

    @Test
    @DisplayName("anything but six digits is refused")
    void malformedCodes() {
        long now = 1_111_111_111_000L
        ["", "12345", "1234567", "abcdef", null].each { assertNull(TotpSupport.matchStep(RFC_SECRET, it, now, null), "${it}") }
    }

    @Test
    @DisplayName("a new secret is 20 random bytes in Base32; the otpauth URI and the QR carry it")
    void secretUriAndQr() {
        String secret = TotpSupport.newSecret()
        assertEquals(32, secret.length())
        assertEquals(20, TotpSupport.decodeSecret(secret).length)
        assertNotEquals(secret, TotpSupport.newSecret())
        assertEquals(20, TotpSupport.decodeSecret(TotpSupport.groupedSecret(secret).toLowerCase()).length, "grouped and lower case still decode")

        String uri = TotpSupport.otpauthUri("Darpan UAT", "riya.m@example.com", secret)
        assertEquals("otpauth://totp/Darpan%20UAT:riya.m%40example.com?secret=${secret}&issuer=Darpan%20UAT&digits=6&period=30&algorithm=SHA1".toString(), uri)
        String qr = TotpSupport.qrDataUri(uri)
        assertTrue(qr.startsWith("data:image/svg+xml;base64,"))
        String svg = new String(qr.substring("data:image/svg+xml;base64,".length()).decodeBase64(), StandardCharsets.UTF_8)
        assertTrue(svg.startsWith("<svg ") && svg.contains("<path"), svg.take(200))
    }
}
