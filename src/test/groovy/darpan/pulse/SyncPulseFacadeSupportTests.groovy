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

    @Test
    void flagsOnlyOrdersPastBeforeTheRunAndStillOpenAtItsStart() {
        java.time.Instant start = java.time.Instant.parse("2026-11-21T08:00:00Z")
        Closure<java.sql.Timestamp> t = { String z -> java.sql.Timestamp.from(java.time.Instant.parse(z)) }
        List<Map> orders = [
                [recordKey: "OPEN", firstPastStamp: t("2026-11-21T06:00:00Z"), closedStamp: null],
                [recordKey: "CLOSED_AFTER", firstPastStamp: t("2026-11-21T06:30:00Z"), closedStamp: t("2026-11-21T09:00:00Z")],
                [recordKey: "CLOSED_BEFORE", firstPastStamp: t("2026-11-21T05:00:00Z"), closedStamp: t("2026-11-21T07:00:00Z")],
                [recordKey: "FLAGGED_LATER", firstPastStamp: t("2026-11-21T08:30:00Z"), closedStamp: null],
                [recordKey: "NO_STAMP", firstPastStamp: null, closedStamp: null]]
        assertEquals([OPEN: "2026-11-21T06:00:00Z", CLOSED_AFTER: "2026-11-21T06:30:00Z"], SyncPulseFacadeSupport.flagsFor(orders, start))
        assertEquals([:], SyncPulseFacadeSupport.flagsFor(orders, null))
    }

    @Test
    void reportedInRunIsTheRunWhoseWindowHoldsTheOrderElseTheFirstAfterTheFlag() {
        java.time.Instant a = java.time.Instant.parse("2026-11-21T06:00:00Z")
        Closure<java.sql.Timestamp> ts = { java.time.Instant i -> java.sql.Timestamp.from(i) }
        java.time.Instant d1 = java.time.Instant.parse("2026-11-20T00:00:00Z"), d2 = java.time.Instant.parse("2026-11-21T00:00:00Z"), d3 = java.time.Instant.parse("2026-11-22T00:00:00Z")
        List<Map> runs = [[reconciliationRunResultId: "R3", start: a.plusSeconds(86400 + 7200), windowStart: d2, windowEnd: d3],
                          [reconciliationRunResultId: "R2", start: a.plusSeconds(3600), windowStart: d1, windowEnd: d2],
                          [reconciliationRunResultId: "R0", start: a.minusSeconds(60), windowStart: d1, windowEnd: d2]]
        assertEquals("R2", SyncPulseFacadeSupport.reportedInRun(runs, ts(a), ts(d1.plusSeconds(100))), "the run that read the order's day, not a later one")
        assertEquals("R3", SyncPulseFacadeSupport.reportedInRun(runs, ts(a), ts(d2.plusSeconds(100))))
        assertEquals("R2", SyncPulseFacadeSupport.reportedInRun(runs, ts(a), null), "no created stamp: the first run after the flag")
        assertEquals("R2", SyncPulseFacadeSupport.reportedInRun(runs, ts(a), ts(d1.minusSeconds(100))), "no window holds it: the first run after the flag")
        assertEquals("R2", SyncPulseFacadeSupport.reportedInRun(runs, ts(a.plusSeconds(7200)), ts(d1.plusSeconds(100))), "flagged after its day's run started: still that run")
        assertEquals("R3", SyncPulseFacadeSupport.reportedInRun(runs, ts(a.plusSeconds(999999)), ts(d2.plusSeconds(100))), "a window holds it, whenever the flag came")
        assertEquals(null, SyncPulseFacadeSupport.reportedInRun(runs, ts(a.plusSeconds(999999)), ts(d1.minusSeconds(100))), "no window holds it and no run after the flag")
        assertEquals(null, SyncPulseFacadeSupport.reportedInRun([], ts(a), ts(d1)))
    }

    @Test
    void aPulseNeedsAPlainSingleKeyOnBothSidesAndADayRead() {
        Map ok = [keyReliable: true, idNormalizer: null, keyFields: ["orderName"]]
        assertEquals(null, SyncPulseFacadeSupport.pulseBlockFor(DAILY, ok, ok))
        assertEquals(SyncPulseFacadeSupport.E_KEY, SyncPulseFacadeSupport.pulseBlockFor(DAILY, ok, ok + [idNormalizer: "STRIP"]))
        assertEquals(SyncPulseFacadeSupport.E_KEY, SyncPulseFacadeSupport.pulseBlockFor(DAILY, ok + [keyFields: ["a", "b"]], ok))
        assertEquals(SyncPulseFacadeSupport.E_KEY, SyncPulseFacadeSupport.pulseBlockFor(DAILY, ok, ok + [keyReliable: false]))
        assertEquals(SyncPulseFacadeSupport.E_KEY, SyncPulseFacadeSupport.pulseBlockFor(DAILY, ok, [keyReliable: false, keyFields: []]))
        assertEquals(SyncPulseFacadeSupport.E_STATUS, SyncPulseFacadeSupport.pulseBlockFor([relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_STATE], ok, ok))
    }

    @Test
    void theKeyRefusalOnlyAppliesWhenAPulseIsOn() {
        List<String> on = SyncPulseFacadeSupport.validate(OK_INPUT, DAILY, "OMS_GQL", false, true, SyncPulseFacadeSupport.E_KEY)
        assertEquals([SyncPulseFacadeSupport.E_KEY], on)
        // validate is only handed a block when a cadence is set; with a cadence of null the block is ignored too
        assertEquals([], SyncPulseFacadeSupport.validate(OK_INPUT + [pulseEveryMinutes: null], DAILY, "OMS_GQL", false, true, SyncPulseFacadeSupport.E_KEY))
        assertEquals([SyncPulseFacadeSupport.E_HOTWAX], SyncPulseFacadeSupport.validate(OK_INPUT, DAILY, "SHOPIFY", false, true, SyncPulseFacadeSupport.E_KEY))
    }
}
