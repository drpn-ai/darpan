package darpan.facade.reconciliation

import org.junit.jupiter.api.Test

import java.sql.Timestamp
import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class ReconciliationApiWindowSupportTests {
    @Test
    void utcMidnightDayPayloadIsNormalizedToTenantCalendarBoundaries() {
        Map<String, Object> window = ReconciliationApiWindowSupport.normalizeCalendarWindow(
                timestamp("2026-05-01T00:00:00Z"),
                timestamp("2026-05-02T00:00:00Z"),
                "America/New_York"
        )

        assertEquals(timestamp("2026-05-01T04:00:00Z"), window.windowStartDate)
        assertEquals(timestamp("2026-05-02T04:00:00Z"), window.windowEndDate)
        assertTrue((Boolean) window.calendarDateNormalized)
        assertEquals("America/New_York", window.timeZone)
    }

    @Test
    void explicitLocalDatesUseRequestedSourceTimezone() {
        Map<String, Object> window = ReconciliationApiWindowSupport.normalizeCalendarWindow(
                timestamp("2026-03-01T00:00:00Z"),
                timestamp("2026-04-01T00:00:00Z"),
                "America/Chicago",
                "2026-03-01",
                "2026-04-01"
        )

        assertEquals(timestamp("2026-03-01T06:00:00Z"), window.windowStartDate)
        assertEquals(timestamp("2026-04-01T05:00:00Z"), window.windowEndDate)
        assertTrue((Boolean) window.calendarDateNormalized)
        assertEquals("America/Chicago", window.timeZone)
    }

    @Test
    void explicitMarchThirtiethLocalDateUsesChicagoDayBoundaries() {
        Map<String, Object> window = ReconciliationApiWindowSupport.normalizeCalendarWindow(
                timestamp("2026-03-30T00:00:00Z"),
                timestamp("2026-03-31T00:00:00Z"),
                "America/Chicago",
                "2026-03-30",
                "2026-03-31"
        )

        assertEquals(timestamp("2026-03-30T05:00:00Z"), window.windowStartDate)
        assertEquals(timestamp("2026-03-31T05:00:00Z"), window.windowEndDate)
        assertTrue((Boolean) window.calendarDateNormalized)
        assertEquals("America/Chicago", window.timeZone)
    }

    @Test
    void explicitLocalDateUsesLosAngelesSourceMidnightForOrderApis() {
        Map<String, Object> window = ReconciliationApiWindowSupport.normalizeCalendarWindow(
                timestamp("2026-04-01T00:00:00Z"),
                timestamp("2026-04-02T00:00:00Z"),
                "America/Los_Angeles",
                "2026-04-01",
                "2026-04-02"
        )

        assertEquals(timestamp("2026-04-01T07:00:00Z"), window.windowStartDate)
        assertEquals(timestamp("2026-04-02T07:00:00Z"), window.windowEndDate)
        assertEquals(1775026800000L, ((Timestamp) window.windowStartDate).time)
        assertEquals(1775113200000L, ((Timestamp) window.windowEndDate).time)
        assertTrue((Boolean) window.calendarDateNormalized)
        assertEquals("America/Los_Angeles", window.timeZone)
    }

    @Test
    void nonMidnightInstantsArePreservedAsExactApiWindows() {
        Timestamp start = timestamp("2026-05-01T05:30:00Z")
        Timestamp end = timestamp("2026-05-01T06:30:00Z")

        Map<String, Object> window = ReconciliationApiWindowSupport.normalizeCalendarWindow(start, end, "America/New_York")

        assertEquals(start, window.windowStartDate)
        assertEquals(end, window.windowEndDate)
        assertFalse((Boolean) window.calendarDateNormalized)
    }

    private static Timestamp timestamp(String instantText) {
        return Timestamp.from(Instant.parse(instantText))
    }
}
