package darpan.ops

import org.junit.jupiter.api.Test

import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull

class RunErrorKindsTests {
    private static Map ex(String status, Integer r1 = null, Integer r2 = null, Integer k1 = null, Integer k2 = null) {
        return [statusEnumId: status, file1ReadCount: r1, file2ReadCount: r2, file1RecordCount: k1, file2RecordCount: k2]
    }

    @Test
    void failedAndDeadLetterAreTheirOwnKinds() {
        assertEquals("FAILED", RunErrorKinds.of(ex("AUT_STAT_FAILED")))
        assertEquals("DEAD_LETTER", RunErrorKinds.of(ex("AUT_STAT_DEAD_LETTER")))
    }

    @Test
    void aSideThatKeptNothingAfterReadingRecordsIsKeptNothing() {
        assertEquals("KEPT_NOTHING", RunErrorKinds.of(ex("AUT_STAT_NO_DATA", 6982, 29963, 5525, 0)))
    }

    @Test
    void aSideThatReadNothingWhileTheOtherReadRecordsIsOneSideEmpty() {
        assertEquals("ONE_SIDE_EMPTY", RunErrorKinds.of(ex("AUT_STAT_NO_DATA", 2298, 0, 2298, 0)))
        assertEquals("ONE_SIDE_EMPTY", RunErrorKinds.of(ex("AUT_STAT_NO_DATA", 2298, 0)))
        assertEquals("ONE_SIDE_EMPTY", RunErrorKinds.of(ex("AUT_STAT_NO_DATA", 0, 5)))
    }

    @Test
    void bothSidesEmptyIsNotAnError() {
        assertNull(RunErrorKinds.of(ex("AUT_STAT_NO_DATA", 0, 0, 0, 0)))
        assertNull(RunErrorKinds.of(ex("AUT_STAT_NO_DATA")))
    }

    @Test
    void anAuditRecheckThatAppendedRecordsIsNotOneSideEmpty() {
        // the recent read was empty but the kept count carries the appended records: read falls back to the larger
        assertNull(RunErrorKinds.of(ex("AUT_STAT_NO_DATA", 0, 5, 3, 4)))
    }

    @Test
    void otherStatusesAreNotErrors() {
        assertNull(RunErrorKinds.of(ex("AUT_STAT_CANCELLED", 0, 5, 0, 0)))
        assertNull(RunErrorKinds.of(ex("AUT_STAT_SKIP_DUP", 0, 5, 0, 0)))
        assertNull(RunErrorKinds.of(ex("AUT_STAT_SUCCESS", 0, 5, 0, 0)))
        assertNull(RunErrorKinds.of([file1ReadCount: 0, file2ReadCount: 5]))
        assertNull(RunErrorKinds.of(null))
    }

    @Test
    void theRunDayIsTheScheduledDateInTheZone() {
        Timestamp t = Timestamp.from(Instant.parse("2026-11-25T07:50:00Z"))
        assertEquals(LocalDate.of(2026, 11, 24), RunErrorKinds.runDay(t, "America/Los_Angeles"))
        assertEquals(LocalDate.of(2026, 11, 25), RunErrorKinds.runDay(t, null))
    }
}
