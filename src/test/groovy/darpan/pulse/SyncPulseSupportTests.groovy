package darpan.pulse

import darpan.peak.PeakWindow
import org.junit.jupiter.api.Test

import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-328 plan 3, Task 4: the pulse engine's pure parts (classify, due, window, effective values). */
class SyncPulseSupportTests {

    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z")

    private static Map rec(String key, int minutesOld) {
        return [key: key, orderName: key, createdAt: NOW.minus(Duration.ofMinutes(minutesOld)), otherKey: key]
    }

    @Test
    void exactlyAllowanceOldIsPast() {
        Map out = SyncPulseSupport.classify([rec("#A", 60)], [] as Set, NOW, 60)
        assertEquals(["#A"], out.past*.key)
        assertEquals([], out.waiting)
        assertEquals(60L, out.past[0].ageMinutes)
    }

    @Test
    void youngerIsWaiting() {
        Map young = rec("#B", 0) + [createdAt: NOW.minus(Duration.ofMinutes(60)).plusSeconds(1)]
        Map out = SyncPulseSupport.classify([young], [] as Set, NOW, 60)
        assertEquals(["#B"], out.waiting*.key, "one second short of the allowance is still waiting")
        assertEquals([], out.past)
    }

    @Test
    void presentIsDone() {
        Map out = SyncPulseSupport.classify([rec("#A", 90), rec("#B", 5)], ["#A", "#B"] as Set, NOW, 60)
        assertEquals([], out.waiting)
        assertEquals([], out.past)
        assertEquals(["#A", "#B"] as Set, out.presentKeys)
    }

    @Test
    void unaskedIsNeitherWaitingNorPast() {
        Map out = SyncPulseSupport.classify([rec("bad key", 90), rec("#B", 5), rec(" #C ", 120)], [] as Set, NOW, 60,
                ["bad key ", "#C"] as Set)
        assertEquals(["#B"], out.waiting*.key)
        assertEquals([], out.past, "a key the target was never asked is not past its allowance")
        assertEquals(2, out.unaskedCount)
    }

    @Test
    void dueAfterCadence() {
        assertTrue(SyncPulseSupport.isDue([lastPulseStamp: null], 60, NOW), "never pulsed: due")
        assertTrue(SyncPulseSupport.isDue([lastPulseStamp: ts(NOW.minus(Duration.ofMinutes(60)))], 60, NOW))
        assertTrue(SyncPulseSupport.isDue([lastPulseStamp: ts(NOW.minus(Duration.ofMinutes(60)).plusMillis(400))], 60, NOW),
                "a tick a little early (job jitter) does not slip the pulse a whole tick")
    }

    @Test
    void notDueBefore() {
        assertFalse(SyncPulseSupport.isDue([lastPulseStamp: ts(NOW.minus(Duration.ofMinutes(55)))], 60, NOW))
        assertFalse(SyncPulseSupport.isDue([lastPulseStamp: ts(NOW.minus(Duration.ofMinutes(5)))], 60, NOW))
    }

    @Test
    void offWhenCadenceNull() {
        assertFalse(SyncPulseSupport.isDue([lastPulseStamp: null], null, NOW))
        assertFalse(SyncPulseSupport.isDue([lastPulseStamp: ts(NOW.minus(Duration.ofDays(3)))], null, NOW))
    }

    @Test
    void windowIsAllowancePlusTwoCadencesSlackAndATick() {
        Map w = SyncPulseSupport.pulseWindow(NOW, 60, 120)
        assertEquals(NOW.minus(Duration.ofMinutes(60 + 240 + 2 + 5)), w.from)
        assertEquals(NOW, w.thru)
        assertEquals(NOW.minus(Duration.ofMinutes(60)), SyncPulseSupport.pulseWindow(NOW, 60, null).from, "no cadence, no margin")
    }

    @Test
    void periodOverridesAllowanceAndCadence() {
        LocalDate day = LocalDate.parse("2026-11-27")
        List<PeakWindow> windows = [new PeakWindow("BF", "Black Friday", day, day.plusDays(3), null, null, [])]
        Map pair = [syncPairId: "SP1", allowanceMinutes: 60, pulseEveryMinutes: 240]
        Map rows = [BF: [allowanceMinutes: 30, pulseEveryMinutes: 60]]

        assertEquals([allowanceMinutes: 30, pulseEveryMinutes: 60, peakPeriodId: "BF"],
                SyncPulseSupport.effectiveValues(pair, windows, rows, day))
        assertEquals([allowanceMinutes: 60, pulseEveryMinutes: 240, peakPeriodId: null],
                SyncPulseSupport.effectiveValues(pair, windows, rows, day.minusDays(1)), "outside the period: the pair's own")
        assertEquals([allowanceMinutes: 60, pulseEveryMinutes: 60, peakPeriodId: "BF"],
                SyncPulseSupport.effectiveValues(pair, windows, [BF: [allowanceMinutes: null, pulseEveryMinutes: 60]], day),
                "a null period value means normal")
        assertEquals([allowanceMinutes: 60, pulseEveryMinutes: null, peakPeriodId: null],
                SyncPulseSupport.effectiveValues(pair + [pulseEveryMinutes: null], windows, [:], day.minusDays(1)))
    }

    private static Timestamp ts(Instant i) { Timestamp.from(i) }
}
