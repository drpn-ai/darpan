package darpan.pulse

import darpan.reconciliation.automation.AutomationExecutionSupport
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-328 plan 3: the sentences and the daily-window rule behind the sync pair facade. Pure; no Moqui. */
class SyncPulseFacadeSupportTests {
    private static final Map DAILY = [relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_PREVIOUS_DAY]
    private static final Map OK_INPUT = [upstreamSide: "FILE_1", allowanceMinutes: 120, pulseEveryMinutes: 60]

    private static List<String> check(Map input = OK_INPUT, Map automation = DAILY, String system = "OMS_GQL", boolean dup = false, boolean chat = true) {
        return SyncPulseFacadeSupport.validate(input, automation, system, dup, chat)
    }

    @Test
    void aValidPairHasNoErrors() { assertEquals([], check()) }

    @Test
    void dailyWindowRule() {
        assertTrue(SyncPulseFacadeSupport.isDailyWindow(DAILY))
        assertTrue(SyncPulseFacadeSupport.isDailyWindow([:]), "a blank window is the last 1 day")
        assertTrue(SyncPulseFacadeSupport.isDailyWindow([relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_LAST_DAYS, relativeWindowCount: 1]))
        assertFalse(SyncPulseFacadeSupport.isDailyWindow([relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_LAST_DAYS, relativeWindowCount: 7]))
        assertTrue(SyncPulseFacadeSupport.isDailyWindow([relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_LAST_DAYS, relativeWindowCount: 0]), "0 reads one day, as the executor does")
        assertTrue(SyncPulseFacadeSupport.isDailyWindow([relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_LAST_DAYS, relativeWindowCount: "x"]), "non-numeric does not throw")
        assertFalse(SyncPulseFacadeSupport.isDailyWindow([relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_PREVIOUS_WEEK]))
        assertFalse(SyncPulseFacadeSupport.isDailyWindow([relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_STATE]))
    }

    @Test
    void notADailyCheckIsRefused() {
        assertEquals(["Choose a daily check."], check(OK_INPUT, null))
        assertEquals(["Choose a daily check."], check(OK_INPUT, [relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_PREVIOUS_MONTH]))
        assertEquals(["Choose a daily check."], check(OK_INPUT + [upstreamSide: "FILE_3"]))
    }

    @Test
    void oneSyncPairPerRun() { assertEquals(["That run already has a sync pair."], check(OK_INPUT, DAILY, "OMS_GQL", true)) }

    @Test
    void allowanceIsFifteenMinutesToFortyEightHours() {
        [14, 2881, null].each { assertEquals(["Allowance must be 15 minutes to 48 hours."], check(OK_INPUT + [allowanceMinutes: it]), "allowance ${it}") }
        [15, 2880].each { assertEquals([], check(OK_INPUT + [allowanceMinutes: it]), "allowance ${it}") }
    }

    @Test
    void cadenceIsOneOfTheHoursOrOff() {
        [60, 120, 180, 240, 360, null].each { assertEquals([], check(OK_INPUT + [pulseEveryMinutes: it]), "cadence ${it}") }
        [30, 90, 300, 720].each {
            assertEquals(["Pulse must be every 1, 2, 3, 4 or 6 hours, or off."], check(OK_INPUT + [pulseEveryMinutes: it]), "cadence ${it}")
        }
    }

    @Test
    void aPulseNeedsHotWaxUpstreamButAnAllowanceDoesNot() {
        assertEquals(["A pulse needs HotWax as the upstream side."], check(OK_INPUT, DAILY, "SHOPIFY"))
        assertEquals([], check(OK_INPUT + [pulseEveryMinutes: null], DAILY, "SHOPIFY"))
    }

    @Test
    void aChatSpaceOfAnotherTenantIsRefused() {
        assertEquals(["Choose a chat space of this tenant."], check(OK_INPUT, DAILY, "OMS_GQL", false, false))
    }
}
