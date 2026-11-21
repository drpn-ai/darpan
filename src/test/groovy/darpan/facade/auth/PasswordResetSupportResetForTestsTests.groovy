package darpan.facade.auth

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertNotSame
import static org.junit.jupiter.api.Assertions.assertSame

class PasswordResetSupportResetForTestsTests {
    @Test
    void resetForTestsRestoresBothSendSeams() {
        Closure origEmail = darpan.facade.auth.PasswordResetSupport.sendEmail
        Closure origCode = darpan.facade.auth.PasswordResetSupport.sendResetCode
        try {
            darpan.facade.auth.PasswordResetSupport.sendEmail = { a, b, c, d -> null }
            darpan.facade.auth.PasswordResetSupport.sendResetCode = { a, b, c -> true }
            assertNotSame(origEmail, darpan.facade.auth.PasswordResetSupport.sendEmail)
            darpan.facade.auth.PasswordResetSupport.resetForTests()
            darpan.facade.auth.PasswordResetSupport.resetForTests()
            assertSame(origEmail, darpan.facade.auth.PasswordResetSupport.sendEmail)
            assertSame(origCode, darpan.facade.auth.PasswordResetSupport.sendResetCode)
        } finally {
            darpan.facade.auth.PasswordResetSupport.resetForTests()
        }
    }
}
