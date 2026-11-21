package darpan.config

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

/** DAR-OPS-026: resetForTests restores the seams. Pure: no Moqui. */
class DarpanSettingsResetTests {
    @Test
    void resetForTestsRestoresSeams() {
        Closure<Long> defaultClock = DarpanSettings.clock
        DarpanSettings.clock = { -> 42L }
        DarpanSettings.rowLoader = { -> [:] }
        DarpanSettings.resetForTests()
        assertNotEquals(42L, DarpanSettings.clock.call())
        assertNotSame(defaultClock, DarpanSettings.clock)
        DarpanSettings.resetForTests()
    }
}
