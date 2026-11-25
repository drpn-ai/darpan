package darpan.ops

import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.impl.Log4jLogEvent
import org.apache.logging.log4j.message.SimpleMessage
import org.apache.logging.log4j.util.SortedArrayStringMap
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow
import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-015 (spec E). The ERROR log subscriber, through its seams: no Moqui. */
class ErrorLogCaptureTests {
    private final ErrorLogCapture capture = ErrorLogCapture.INSTANCE

    @BeforeEach
    void clean() { ErrorLogCapture.resetForTests() }

    @AfterEach
    void reset() { ErrorLogCapture.resetForTests() }

    private static LogEvent event(Map over = [:]) {
        Level level = (over.level ?: Level.ERROR) as Level
        SortedArrayStringMap context = new SortedArrayStringMap()
        if (over.tenant) context.putValue("darpan.tenant", over.tenant)
        return Log4jLogEvent.newBuilder()
                .setLoggerName((over.logger ?: "darpan.Some") as String)
                .setLevel(level)
                .setMessage(new SimpleMessage((over.message ?: "Run 102311 failed") as String))
                .setThrown(over.thrown as Throwable)
                .setContextData(context)
                .setTimeMillis((over.time ?: System.currentTimeMillis()) as long)
                .setThreadId((over.threadId ?: Thread.currentThread().threadId()) as long)
                .build()
    }

    @Test
    void onlyErrorLevelIsKept() {
        capture.process(event(level: Level.WARN))
        capture.process(event(level: Level.INFO))
        assertTrue(capture.drain().isEmpty())
        capture.process(event(level: Level.ERROR))
        capture.process(event(level: Level.FATAL))
        List<Map> rows = capture.drain()
        assertEquals(1, rows.size())
        assertEquals(2L, rows[0].count)
    }

    @Test
    void sameMessageWithDifferentIdsIsOneFingerprint() {
        capture.process(event(message: "Run 102311 failed", time: 1000L))
        capture.process(event(message: "Run 102351 failed", time: 2000L))
        List<Map> rows = capture.drain()
        assertEquals(1, rows.size())
        Map row = rows[0]
        assertEquals(2L, row.count)
        assertEquals("darpan.Some Run # failed", row.fingerprint)
        assertEquals("Run 102311 failed", row.sampleMessage)
        assertEquals(new Timestamp(1000L), row.firstSeen)
        assertEquals(new Timestamp(2000L), row.lastSeen)
        assertEquals("darpan.Some", row.loggerName)
    }

    @Test
    void fingerprintMasksHexUuidAndQuotedIds() {
        assertEquals("a.B Order * for tenant * at *",
                ErrorLogCapture.fingerprint("a.B", "Order 'GOR196940093' for tenant \"OPS_T1\" at 9f86d081884c7d65"))
        assertEquals("a.B Session * expired after # min",
                ErrorLogCapture.fingerprint("a.B", "Session 123e4567-e89b-12d3-a456-426614174000 expired after 30 min"))
        assertEquals(ErrorLogCapture.fingerprint("a.B", "Job  12 \n failed"), ErrorLogCapture.fingerprint("a.B", "Job 99 failed"))
        assertNotEquals(ErrorLogCapture.fingerprint("a.B", "Job 1 failed"), ErrorLogCapture.fingerprint("a.C", "Job 1 failed"))
        assertEquals("a.B Service \"needs a key\" failed", ErrorLogCapture.fingerprint("a.B", "Service \"needs a key\" failed"))
    }

    @Test
    void aLongFingerprintStaysDistinctWithinTheKeyLength() {
        String prefix = "x" * 400
        String a = ErrorLogCapture.fingerprint("a.B", prefix + " alpha")
        String b = ErrorLogCapture.fingerprint("a.B", prefix + " beta")
        assertTrue(a.length() <= ErrorLogCapture.FINGERPRINT_MAX_CHARS, "${a.length()}")
        assertNotEquals(a, b)
    }

    @Test
    void fingerprintsAreCappedAndOverflowIsCounted() {
        (0..<501).each { int i -> capture.process(event(logger: "darpan.L" + ((char) (97 + i % 26)), message: "Distinct ${'z' * (i.intdiv(26))} kind")) }
        List<Map> rows = capture.drain()
        assertEquals(501, rows.size())
        Map overflow = rows.find { it.fingerprint == ErrorLogCapture.OVERFLOW }
        assertEquals(1L, overflow?.count)
        assertEquals(500, rows.count { it.fingerprint != ErrorLogCapture.OVERFLOW })
        assertTrue(rows.every { (it.count as long) == 1L })
    }

    @Test
    void aThrowingEventNeverEscapes() {
        LogEvent bad = [getLevel: { Level.ERROR }, getLoggerName: { "darpan.Bad" },
                getMessage: { throw new IllegalStateException("message getter blew up") }] as LogEvent
        assertDoesNotThrow({ capture.process(bad) } as org.junit.jupiter.api.function.Executable)
        assertDoesNotThrow({ capture.process(null) } as org.junit.jupiter.api.function.Executable)
        LogEvent badLevel = [getLevel: { throw new IllegalStateException("level") }] as LogEvent
        assertDoesNotThrow({ capture.process(badLevel) } as org.junit.jupiter.api.function.Executable)
    }

    @Test
    void theStackIsCappedToFitTheColumn() {
        Throwable deep = new RuntimeException("outer", new IllegalStateException("inner " + "y" * 3000))
        capture.process(event(thrown: deep))
        String stack = capture.drain()[0].sampleStack as String
        assertEquals(ErrorLogCapture.STACK_MAX_CHARS, stack.length())
        assertTrue(stack.startsWith("java.lang.RuntimeException: outer"), stack.take(80))
        assertTrue(stack.contains("Caused by: java.lang.IllegalStateException: inner"), stack.take(400))
        assertTrue(ErrorLogCapture.STACK_MAX_CHARS <= 4000)
    }

    @Test
    void theSampleMessageFitsItsColumnAndTheTenantComesFromTheThreadContext() {
        capture.process(event(message: "m" * 1000, tenant: "OPS_T7"))
        Map row = capture.drain()[0]
        assertEquals(ErrorLogCapture.SAMPLE_MAX_CHARS, (row.sampleMessage as String).length())
        assertEquals("OPS_T7", row.tenantId)
        assertNull(row.sampleStack)
    }

    @Test
    void drainEmptiesTheBuffer() {
        capture.process(event())
        assertEquals(1, capture.drain().size())
        assertTrue(capture.drain().isEmpty())
        capture.process(event())
        assertEquals(1L, capture.drain()[0].count)
    }

    @Test
    void linesTheFlushingThreadLogsDuringAFlushAreIgnoredEvenWhenDeliveredLater() {
        long me = Thread.currentThread().threadId()
        List<LogEvent> during = []
        ErrorLogCapture.whileFlushing {
            during << event(message: "flush own line")
            during << event(message: "another thread's line", threadId: me + 1)
        }
        Thread.sleep(2)
        LogEvent after = event(message: "after the flush")
        // log4j's AsyncLog thread delivers them after the flush has ended
        Thread deliverer = Thread.start { (during + after).each { capture.process(it) } }
        deliverer.join()
        assertEquals(["another thread's line", "after the flush"] as Set, capture.drain().collect { it.sampleMessage } as Set)
    }

    @Test
    void restoredAggregatesAddBackIntoTheBuffer() {
        capture.process(event(message: "Run 1 failed", time: 5000L))
        List<Map> drained = capture.drain()
        capture.process(event(message: "Run 2 failed", time: 9000L))
        capture.restore(drained)
        Map row = capture.drain()[0]
        assertEquals(2L, row.count)
        assertEquals(new Timestamp(5000L), row.firstSeen)
        assertEquals(new Timestamp(9000L), row.lastSeen)
        assertEquals("Run 1 failed", row.sampleMessage)
    }
}
