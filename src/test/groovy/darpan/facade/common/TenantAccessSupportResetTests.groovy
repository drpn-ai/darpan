package darpan.facade.common

import org.junit.jupiter.api.Test

import java.lang.reflect.Field

import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-OPS-026: resetForTests() also forgets the log-once bad-zone set. */
class TenantAccessSupportResetTests {
    @Test
    void resetForTestsClearsWarnedBadZones() {
        Field f = TenantAccessSupport.getDeclaredField('WARNED_BAD_ZONES')
        f.setAccessible(true)
        Set<String> warned = (Set<String>) f.get(null)
        warned.add('t1|Not/AZone')
        TenantAccessSupport.resetForTests()
        assertTrue(warned.isEmpty())
    }
}
