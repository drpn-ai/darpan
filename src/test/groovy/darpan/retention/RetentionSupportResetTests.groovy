package darpan.retention

import org.junit.jupiter.api.Test

import java.lang.reflect.Field

import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-OPS-026: resetForTests() forgets the warn-once stored-period set. */
class RetentionSupportResetTests {
    @Test
    void resetForTestsClearsWarnedStored() {
        Field f = RetentionSupport.getDeclaredField('warnedStored')
        f.setAccessible(true)
        Set<String> warned = (Set<String>) f.get(null)
        warned.add('t1|TABLE|0')
        RetentionSupport.resetForTests()
        assertTrue(warned.isEmpty())
    }
}
