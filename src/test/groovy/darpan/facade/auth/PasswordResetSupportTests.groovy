package darpan.facade.auth

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-028 — the pure helpers behind the password reset services; no Moqui boot. */
class PasswordResetSupportTests {

    @Test
    void maskEmailKeepsFirstLetterAndDomain() {
        assertEquals("a•••@hotwax.co", PasswordResetSupport.maskEmail("aditi.patel@hotwax.co"))
    }

    @Test
    void maskEmailOneLetterLocalPart() {
        assertEquals("a•••@x.io", PasswordResetSupport.maskEmail("a@x.io"))
    }

    @Test
    void maskEmailNullStaysNull() {
        assertNull(PasswordResetSupport.maskEmail(null))
    }

    @Test
    void maskEmailWithoutAtSignRevealsNothing() {
        assertEquals("•••", PasswordResetSupport.maskEmail("not-an-email"))
    }

    @Test
    void normalizeCodeKeepsDigitsOnly() {
        assertEquals("123456", PasswordResetSupport.normalizeCode(" 123 456\n"))
    }

    @Test
    void normalizeCodeNullIsEmpty() {
        assertEquals("", PasswordResetSupport.normalizeCode(null))
    }

    @Test
    void overLimitBelowBothCaps() {
        assertFalse(PasswordResetSupport.overLimit(2, 19))
    }

    @Test
    void overLimitAtThreeCodesPerAccount() {
        assertTrue(PasswordResetSupport.overLimit(3, 0))
    }

    @Test
    void overLimitAtTwentyPerIp() {
        assertTrue(PasswordResetSupport.overLimit(0, 20))
    }
}
