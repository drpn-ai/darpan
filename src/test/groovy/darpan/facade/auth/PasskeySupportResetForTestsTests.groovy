package darpan.facade.auth

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertNull

class PasskeySupportResetForTestsTests {
    @Test
    void resetForTestsForgetsTheDecoyKey() {
        try {
            PasskeySupport.decoyKeyCache = [factory: new Object(), key: new byte[4]]
            PasskeySupport.resetForTests()
            PasskeySupport.resetForTests()
            assertNull(PasskeySupport.decoyKeyCache)
        } finally {
            PasskeySupport.resetForTests()
        }
    }
}
