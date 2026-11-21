package darpan.email

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

class EmailCopyResetForTestsTests {
    @Test
    void resetForTestsDropsSeedCachesAndLogMemory() {
        try {
            EmailCopy.seedDefaultsCache = [a: "b"]
            EmailCopy.seedSubjectsCache = [a: "b"]
            EmailCopy.LOGGED.add("x")
            EmailCopy.resetForTests()
            EmailCopy.resetForTests()
            assertNull(EmailCopy.seedDefaultsCache)
            assertNull(EmailCopy.seedSubjectsCache)
            assertTrue(EmailCopy.LOGGED.isEmpty())
        } finally {
            EmailCopy.resetForTests()
        }
    }
}
