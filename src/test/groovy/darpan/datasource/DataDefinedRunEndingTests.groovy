package darpan.datasource

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-231. A reader thread that stops for any reason (an Error, an interrupt, a dropped connection with no message)
 * ends a parallel data-defined read with a stated reason; a run never ends with no recorded error. Many calls over many
 * day slices on the made-up AcmeLedgerFixture system, read by four threads.
 */
class DataDefinedRunEndingTests {

    static final long FROM = java.time.Instant.parse("2026-10-01T00:00:00Z").toEpochMilli()
    static final long THRU = java.time.Instant.parse("2026-10-25T00:00:00Z").toEpochMilli()

    /** The scripted system, with one hook that fires on the call whose filter holds `trigger`. */
    static class HookedTransport {
        final AcmeLedgerFixture.ScriptedTransport inner = new AcmeLedgerFixture.ScriptedTransport()
        String trigger
        Closure hook

        Map execute(String document, Map variables, int reservation) {
            if (hook != null && (variables.filter as String)?.contains(trigger)) hook.call()
            return inner.execute(document, variables, reservation)
        }
    }

    private static Map definition() {
        Map d = AcmeLedgerFixture.definition()
        d.steps = ((List<Map>) d.steps).collect { Map st ->
            if (st.id != "invoices") return st
            Map bind = new LinkedHashMap((Map) st.bind)
            bind.window = ((Map) bind.window) + [parallel: 4]
            st + [bind: bind]
        }
        return d
    }

    private static Throwable runWith(HookedTransport t) {
        Throwable caught = null
        try {
            new DataSourceRunner(definition(), t).run([mode: "window", from: FROM, thru: THRU]) { }
        } catch (Throwable e) { caught = e }
        return caught
    }

    @Test
    void aThreadErrorFailsTheExtractWithAMessage() {
        HookedTransport t = new HookedTransport(trigger: "2026-10-09T00", hook: { throw new OutOfMemoryError("heap") })
        Throwable e = runWith(t)
        assertTrue(e instanceof DataSourceCallException, String.valueOf(e))
        assertEquals("READ_STOPPED", ((DataSourceCallException) e).code)
        assertTrue(e.message.contains("OutOfMemoryError"), e.message)
    }

    @Test
    void anInterruptedReaderFailsTheExtractWithAMessage() {
        HookedTransport t = new HookedTransport(trigger: "2026-10-05T00", hook: { throw new InterruptedException() })
        Throwable e = runWith(t)
        assertTrue(e instanceof DataSourceCallException, String.valueOf(e))
        assertEquals("The read was interrupted.", e.message)
    }

    @Test
    void anInterruptOfTheWaitingThreadStopsTheReadersAndFailsWithAMessage() {
        Thread caller = Thread.currentThread()
        HookedTransport t = new HookedTransport(trigger: "2026-10-03T00", hook: { caller.interrupt(); Thread.sleep(50) })
        Throwable e = runWith(t)
        assertTrue(Thread.interrupted(), "the interrupt flag is kept for the caller")
        assertTrue(e instanceof DataSourceCallException, String.valueOf(e))
        assertEquals("The read was interrupted.", e.message)
    }

    @Test
    void aDroppedConnectionWithNoMessageStillSaysWhy() {
        HookedTransport t = new HookedTransport(trigger: "2026-10-12T00", hook: { throw new IllegalStateException() })
        Throwable e = runWith(t)
        assertTrue(e instanceof DataSourceCallException, String.valueOf(e))
        assertTrue(e.message.contains("IllegalStateException"), e.message)
        assertFalse(e.message.isEmpty())
    }

    // A checked failure (a socket timeout) keeps its own words: the class name alone would lose "Read timed out".
    @Test
    void aCheckedFailureKeepsItsMessage() {
        HookedTransport t = new HookedTransport(trigger: "2026-10-12T00", hook: { throw new java.net.SocketTimeoutException("Read timed out") })
        Throwable e = runWith(t)
        assertTrue(e instanceof DataSourceCallException, String.valueOf(e))
        assertEquals("READ_STOPPED", ((DataSourceCallException) e).code)
        assertTrue(e.message.contains("SocketTimeoutException") && e.message.contains("Read timed out"), e.message)
    }

    @Test
    void aFailureWithAMessageIsKeptAsIs() {
        HookedTransport t = new HookedTransport(trigger: "2026-10-12T00", hook: { throw new DataSourceCallException("HTTP_502", "bad gateway") })
        DataSourceCallException e = (DataSourceCallException) runWith(t)
        assertEquals("HTTP_502", e.code)
    }
}
