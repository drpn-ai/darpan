package darpan.facade.auth

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull

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
}
