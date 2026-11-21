package darpan.admin

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertNotSame
import static org.junit.jupiter.api.Assertions.assertSame

class AdminMembershipSupportResetForTestsTests {
    @Test
    void resetForTestsRestoresTheWelcomeEmailSeam() {
        Closure orig = AdminMembershipSupport.sendWelcomeEmail
        try {
            AdminMembershipSupport.sendWelcomeEmail = { a, b, c, d -> true }
            assertNotSame(orig, AdminMembershipSupport.sendWelcomeEmail)
            AdminMembershipSupport.resetForTests()
            AdminMembershipSupport.resetForTests()
            assertSame(orig, AdminMembershipSupport.sendWelcomeEmail)
        } finally {
            AdminMembershipSupport.resetForTests()
        }
    }
}
