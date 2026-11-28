package darpan.datasource

import org.junit.jupiter.api.Test

import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-OPS-029 S15a, invariant E3/E6. A window bound reaches the read as an instant. Zone-less text is UTC in every form,
 * whatever zone the JVM runs in: "2026-11-01 00:00:00" was read by Timestamp.valueOf in the JVM's zone while
 * "2026-11-01T00:00:00" was UTC, so the same window moved with the server's zone. An instant passes through untouched.
 */
class DataDefinedExtractWindowTests {

    private static Long millis(Object raw, List<String> errors = []) {
        return DataDefinedExtract.windowMillis(raw, "The window start", errors)
    }

    @Test
    void zoneLessTextIsUtcInEveryFormWhateverTheJvmZone() {
        TimeZone original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
            long utc = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()
            assertEquals(utc, millis("2026-11-01 00:00:00"))
            assertEquals(utc, millis("2026-11-01T00:00:00"))
            assertEquals(utc, millis("2026-11-01"))
            assertEquals(utc + 250L, millis("2026-11-01 00:00:00.25"))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    void anInstantOrAnOffsetPassesThroughAndNonsenseIsRefused() {
        long utc = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()
        assertEquals(utc, millis(new java.sql.Timestamp(utc)))
        assertEquals(utc, millis("2026-11-01T05:30:00+05:30"))
        assertEquals(utc, millis(Long.toString(utc)))
        List<String> errors = []
        assertNull(millis("first of November", errors))
        assertTrue(errors[0].contains("must be a timestamp"), errors.toString())
    }
}
