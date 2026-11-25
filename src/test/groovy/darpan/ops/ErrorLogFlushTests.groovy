package darpan.ops

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.impl.Log4jLogEvent
import org.apache.logging.log4j.message.SimpleMessage
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import org.slf4j.LoggerFactory

import java.nio.file.Path
import java.sql.Date as SqlDate
import java.sql.Timestamp
import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-015 (spec E). Moqui wiring only: the tool factory registers the subscriber, and flush writes ErrorLogEntry rows. */
@SharedMoqui
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ErrorLogFlushTests {
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "error-log-flush")
    }

    @BeforeEach
    void clean() { ErrorLogCapture.resetForTests() }

    @AfterEach
    void reset() {
        ErrorLogCapture.resetForTests()
        ec.message.clearAll()
    }

    @AfterAll
    void cleanup() {
        ErrorLogCapture.resetForTests()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    private static void error(String logger, String message, String when) {
        ErrorLogCapture.INSTANCE.process(Log4jLogEvent.newBuilder().setLoggerName(logger).setLevel(Level.ERROR)
                .setMessage(new SimpleMessage(message)).setTimeMillis(Instant.parse(when).toEpochMilli()).build())
    }

    private EntityValue row(String fingerprint, String day) {
        return ec.entity.find("darpan.ops.ErrorLogEntry").condition("fingerprint", fingerprint)
                .condition("logDay", SqlDate.valueOf(day)).useCache(false).disableAuthz().one()
    }

    @Test
    void theToolFactoryRegistersTheSubscriberOnce() {
        assertEquals(1, ec.factory.getLogEventSubscribers().count { it.is(ErrorLogCapture.INSTANCE) })
    }

    @Test
    void aLoggedErrorReachesItsRowThroughTheSubscriber() {
        String fp = "darpan.ops.ErrorLogFlushProbe Probe # failed"
        LoggerFactory.getLogger("darpan.ops.ErrorLogFlushProbe").error("Probe 4711 failed")
        // log4j's AsyncLog thread hands the line to the subscriber a moment later
        for (int i = 0; i < 500 && !ErrorLogCapture.INSTANCE.holds(fp); i++) Thread.sleep(10)
        ErrorLogCapture.flush(ec)
        List<EntityValue> rows = ec.entity.find("darpan.ops.ErrorLogEntry").condition("fingerprint", fp).useCache(false).disableAuthz().list()
        assertEquals(1, rows.size())
        assertEquals(1L, rows[0].count as long)
        assertEquals("Probe 4711 failed", rows[0].sampleMessage)
    }

    @Test
    void flushAddsCountsIntoTheDaysRow() {
        error("darpan.F1", "Run 1 failed", "2026-11-24T10:00:00Z")
        error("darpan.F1", "Run 2 failed", "2026-11-24T10:01:00Z")
        ErrorLogCapture.flush(ec)
        error("darpan.F1", "Run 3 failed", "2026-11-24T23:59:00Z")
        ErrorLogCapture.flush(ec)

        EntityValue r = row("darpan.F1 Run # failed", "2026-11-24")
        assertNotNull(r)
        assertEquals(3L, r.count as long)
        assertEquals(Timestamp.from(Instant.parse("2026-11-24T10:00:00Z")), r.firstSeen)
        assertEquals(Timestamp.from(Instant.parse("2026-11-24T23:59:00Z")), r.lastSeen)
        assertEquals("Run 1 failed", r.sampleMessage)
        assertEquals("darpan.F1", r.loggerName)
        assertTrue(ErrorLogCapture.INSTANCE.drain().isEmpty())
    }

    @Test
    void aNewUtcDayStartsANewRow() {
        error("darpan.F2", "Run 1 failed", "2026-11-24T23:58:00Z")
        ErrorLogCapture.flush(ec)
        error("darpan.F2", "Run 2 failed", "2026-11-25T00:01:00Z")
        ErrorLogCapture.flush(ec)
        assertEquals(1L, row("darpan.F2 Run # failed", "2026-11-24").count as long)
        assertEquals(1L, row("darpan.F2 Run # failed", "2026-11-25").count as long)
    }

    @Test
    void aFlushKeepsItsRowsWhenTheCallersTransactionRollsBack() {
        error("darpan.F3", "Run 1 failed", "2026-11-23T08:00:00Z")
        boolean began = ec.transaction.begin(60)
        try {
            ErrorLogCapture.flush(ec)
            ec.transaction.rollback(began, "caller rolls back", null)
        } finally {
            if (ec.transaction.isTransactionInPlace()) ec.transaction.rollback(began, "cleanup", null)
        }
        assertEquals(1L, row("darpan.F3 Run # failed", "2026-11-23").count as long)
    }

    @Test
    void aFailedFlushPutsTheAggregatesBackAndLeavesNoErrors() {
        error("darpan.F4", "Run 1 failed", "2026-11-22T08:00:00Z")
        ErrorLogCapture.flushService = "ops.OpsServices.no#SuchService"
        ErrorLogCapture.flush(ec)
        assertFalse(ec.message.hasError())
        ErrorLogCapture.flushService = ErrorLogCapture.FLUSH_SERVICE
        List<Map> kept = ErrorLogCapture.INSTANCE.drain().findAll { it.fingerprint == "darpan.F4 Run # failed" }
        assertEquals(1, kept.size())
        assertEquals(1L, kept[0].count)
        assertEquals(null, row("darpan.F4 Run # failed", "2026-11-22"))
    }
}
