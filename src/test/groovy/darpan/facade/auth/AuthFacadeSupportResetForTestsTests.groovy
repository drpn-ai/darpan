package darpan.facade.auth

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse

class AuthFacadeSupportResetForTestsTests {
    @Test
    void resetForTestsReturnsToNotYetMeasured() {
        try {
            AuthFacadeSupport.recordPasswordHashCost(40L)
            AuthFacadeSupport.passwordHashCostMeasured = true
            AuthFacadeSupport.resetForTests()
            AuthFacadeSupport.resetForTests()
            assertEquals(0L, AuthFacadeSupport.passwordHashCostMillis)
            assertFalse(AuthFacadeSupport.passwordHashCostMeasured)
        } finally {
            AuthFacadeSupport.resetForTests()
        }
    }
}
