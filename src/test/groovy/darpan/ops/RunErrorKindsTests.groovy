package darpan.ops

import org.junit.jupiter.api.Test

import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull

class RunErrorKindsTests {
    private static Map ex(String status, String noDataKind = null) {
        return [statusEnumId: status, noDataKind: noDataKind, file1ReadCount: 0, file2ReadCount: 5]
    }

    @Test
    void failedAndDeadLetterAreTheirOwnKinds() {
        assertEquals("FAILED", RunErrorKinds.of(ex("AUT_STAT_FAILED")))
        assertEquals("DEAD_LETTER", RunErrorKinds.of(ex("AUT_STAT_DEAD_LETTER")))
    }

    @Test
    void aNoDataRowIsTheKindTheRunPersisted() {
        assertEquals("KEPT_NOTHING", RunErrorKinds.of(ex("AUT_STAT_NO_DATA", "KEPT_NOTHING")))
        assertEquals("ONE_SIDE_EMPTY", RunErrorKinds.of(ex("AUT_STAT_NO_DATA", "ONE_SIDE_EMPTY")))
    }

    @Test
    void aNoDataRowWithNoKindIsNotAnError() {
        // a quiet day or a verify (one side read 0 by design) persists no kind, whatever its counts say
        assertNull(RunErrorKinds.of(ex("AUT_STAT_NO_DATA")))
        assertNull(RunErrorKinds.of(ex("AUT_STAT_NO_DATA", "SOMETHING_ELSE")))
    }

    @Test
    void otherStatusesAreNotErrors() {
        assertNull(RunErrorKinds.of(ex("AUT_STAT_CANCELLED", "KEPT_NOTHING")))
        assertNull(RunErrorKinds.of(ex("AUT_STAT_SKIP_DUP", "KEPT_NOTHING")))
        assertNull(RunErrorKinds.of(ex("AUT_STAT_SUCCESS", "KEPT_NOTHING")))
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
