package darpan.facade.auth

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertNotSame
import static org.junit.jupiter.api.Assertions.assertSame

class TwoFactorSupportResetForTestsTests {
    @Test
    void resetForTestsRestoresTheCodeClock() {
        Closure orig = darpan.facade.auth.TwoFactorSupport.codeClock
        try {
            darpan.facade.auth.TwoFactorSupport.codeClock = { 42L }
            assertNotSame(orig, darpan.facade.auth.TwoFactorSupport.codeClock)
            darpan.facade.auth.TwoFactorSupport.resetForTests()
            darpan.facade.auth.TwoFactorSupport.resetForTests()
            assertSame(orig, darpan.facade.auth.TwoFactorSupport.codeClock)
        } finally {
            darpan.facade.auth.TwoFactorSupport.resetForTests()
        }
    }
}
