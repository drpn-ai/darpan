package darpan.reconciliation.notification

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

class RunNotificationVoiceResetForTestsTests {
    @Test
    void resetForTestsClearsPickerAndDealerHistoryAndIsIdempotent() {
        RunNotificationVoice.resetForTests()
        RunNotificationVoice.pickLine(["a", "b", "c"], "slot")
        RunNotificationVoice.setLinePicker({ List<String> pool, String slot -> pool[0] })
        assertTrue(!((Map) RunNotificationVoice.@CYCLES).isEmpty())
        RunNotificationVoice.resetForTests()
        assertNull(RunNotificationVoice.@linePicker)
        assertTrue(((Map) RunNotificationVoice.@CYCLES).isEmpty())
        assertTrue(((Map) RunNotificationVoice.@LAST_DEALT).isEmpty())
        RunNotificationVoice.resetForTests()
        assertNull(RunNotificationVoice.@linePicker)
    }
}
