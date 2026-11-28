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
import org.moqui.context.NotificationMessage.NotificationType
import org.moqui.entity.EntityValue
import org.slf4j.LoggerFactory

import java.nio.file.Path
import java.sql.Date as SqlDate
import java.sql.Timestamp
import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
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

    /**
     * S99-212: log4j's AsyncLog thread hands a line to the subscriber a moment later. Waits for it, up to 10 s on a busy
     * machine, and fails here, naming the line, rather than as a wrong count further down. A wait, never a timing bound.
     */
    private static void awaitHeld(String fingerprint) {
        long deadline = System.nanoTime() + 10_000_000_000L
        while (!ErrorLogCapture.INSTANCE.holds(fingerprint) && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(ErrorLogCapture.INSTANCE.holds(fingerprint), "the subscriber never received '${fingerprint}'")
    }

    @Test
    void theToolFactoryRegistersTheSubscriberOnce() {
        assertEquals(1, ec.factory.getLogEventSubscribers().count { it.is(ErrorLogCapture.INSTANCE) })
    }

    @Test
    void aLoggedErrorReachesItsRowThroughTheSubscriber() {
        String fp = "darpan.ops.ErrorLogFlushProbe Probe # failed"
        LoggerFactory.getLogger("darpan.ops.ErrorLogFlushProbe").error("Probe 4711 failed")
        awaitHeld(fp)
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
    void aFailedFlushPutsTheAggregatesBackAndKeepsNoLineItLogged() {
        error("darpan.F4", "Run 1 failed", "2026-11-22T08:00:00Z")
        // the store logs an ERROR on the flushing thread (as a failing insert or transaction would) and fails
        ErrorLogCapture.storeHook = { def ctx, List rows ->
            LoggerFactory.getLogger("darpan.ops.ErrorLogStoreProbe").error("Store 99 broke")
            throw new IllegalStateException("database down")
        }
        ErrorLogCapture.flush(ec)
        assertFalse(ec.message.hasError())
        // AsyncLog delivers in order: once a later line is held, the store's line has been seen (and must have been dropped)
        String sentinel = "darpan.ops.ErrorLogStoreProbe Sentinel # after"
        LoggerFactory.getLogger("darpan.ops.ErrorLogStoreProbe").error("Sentinel 1 after")
        awaitHeld(sentinel)
        List<Map> kept = ErrorLogCapture.INSTANCE.drain()
        assertEquals(["darpan.F4 Run # failed", sentinel] as Set, kept.collect { it.fingerprint } as Set)
        assertEquals(1L, kept.find { it.fingerprint == "darpan.F4 Run # failed" }.count)
        assertNull(row("darpan.F4 Run # failed", "2026-11-22"))
    }

    @Test
    void theCallersMessagesComeBackExactlyAsBefore() {
        ec.message.addMessage("caller note", NotificationType.info)
        ec.message.addPublic("caller public", NotificationType.success)
        ec.message.addError("caller error")
        error("darpan.F5", "Run 1 failed", "2026-11-21T08:00:00Z")
        ErrorLogCapture.storeHook = { def ctx, List rows ->
            ctx.message.addMessage("store chatter")
            ctx.message.addPublic("store public", NotificationType.warning)
            ctx.message.addError("SQL insert into ERROR_LOG_ENTRY failed")
        }
        ErrorLogCapture.flush(ec)
        assertEquals(["caller note", "caller public"], ec.message.messageInfos.collect { it.message })
        assertEquals(["caller public"], ec.message.publicMessageInfos.collect { it.message })
        assertEquals(["caller error"], ec.message.errors)
        assertEquals(1, ErrorLogCapture.INSTANCE.drain().count { it.fingerprint == "darpan.F5 Run # failed" })
    }

    /**
     * S99-070: purge#ErrorLogEntries deletes the days more than 30 before nowStamp's UTC day and counts them; the 30th day
     * stays. A nowStamp in 2001 keeps every other test's rows out of the count.
     */
    @Test
    void thePurgeDeletesOnlyDaysMoreThanThirtyBackAndCountsThem() {
        Map<String, String> days = [PURGE_OLD: "2000-12-31", PURGE_DAY_30: "2001-01-02", PURGE_RECENT: "2001-01-20"]
        days.each { String fp, String day ->
            ec.entity.makeValue("darpan.ops.ErrorLogEntry").setAll([fingerprint: fp, logDay: SqlDate.valueOf(day), count: 1]).createOrUpdate()
        }
        try {
            Map out = ec.service.sync().name("ops.OpsServices.purge#ErrorLogEntries")
                    .parameters([nowStamp: Timestamp.from(Instant.parse("2001-02-01T23:30:00Z"))]).call()
            assertFalse(ec.message.hasError(), ec.message.errorsString)
            assertEquals(1, out.purged)
            assertEquals(["PURGE_DAY_30", "PURGE_RECENT"], ec.entity.find("darpan.ops.ErrorLogEntry")
                    .condition("fingerprint", org.moqui.entity.EntityCondition.IN, days.keySet()).orderBy("fingerprint").list()*.fingerprint)
        } finally {
            ec.entity.find("darpan.ops.ErrorLogEntry").condition("fingerprint", org.moqui.entity.EntityCondition.IN, days.keySet()).deleteAll()
        }
    }
}
