package darpan.common

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * The shared detach helper must run work OUTSIDE any caller/request transaction. The JSON-RPC
 * screen path wraps a service call in the request's JTA transaction whose 60s timeout is far
 * shorter than a real extract or automation run; Bitronix then marks it rollback-only mid-run and
 * every later entity touch fails, rolling the completed work back invisibly (prod 2026-08-05:
 * run#AutomationNow died at 61.5s with no execution row left behind).
 */
class TransactionDetachSupportTests {

    /** Duck-typed stand-ins for ec/ec.transaction — the helper only touches these members. */
    static class FakeTransactionFacade {
        boolean inPlace
        boolean suspendThrows = false
        List<String> calls = []

        FakeTransactionFacade(boolean inPlace) { this.inPlace = inPlace }

        boolean isTransactionInPlace() { return inPlace }

        boolean suspend() {
            calls.add("suspend")
            if (suspendThrows) throw new IllegalStateException("suspend boom")
            inPlace = false
            return true
        }

        void resume() {
            calls.add("resume")
            inPlace = true
        }
    }

    static class FakeEc {
        FakeTransactionFacade transaction

        FakeEc(FakeTransactionFacade transaction) { this.transaction = transaction }
    }

    @Test
    void suspendsCallerTransactionRunsWorkOutsideItAndResumes() {
        FakeTransactionFacade tx = new FakeTransactionFacade(true)
        def ec = new FakeEc(tx)
        boolean txInPlaceDuringWork = true

        Object result = TransactionDetachSupport.runDetachedFromCallerTransaction(ec) { ->
            txInPlaceDuringWork = tx.isTransactionInPlace()
            return "work-result"
        }

        assertEquals("work-result", result)
        assertFalse(txInPlaceDuringWork, "work must run with the caller transaction suspended")
        assertEquals(["suspend", "resume"], tx.calls)
        assertTrue(tx.isTransactionInPlace(), "caller transaction must be resumed for the response path")
    }

    @Test
    void doesNotSuspendWhenNoCallerTransactionIsInPlace() {
        FakeTransactionFacade tx = new FakeTransactionFacade(false)
        def ec = new FakeEc(tx)

        Object result = TransactionDetachSupport.runDetachedFromCallerTransaction(ec) { -> "no-tx" }

        assertEquals("no-tx", result)
        assertEquals([], tx.calls, "nothing to suspend or resume")
    }

    @Test
    void resumesCallerTransactionEvenWhenWorkThrows() {
        FakeTransactionFacade tx = new FakeTransactionFacade(true)
        def ec = new FakeEc(tx)

        assertThrows(IllegalStateException) {
            TransactionDetachSupport.runDetachedFromCallerTransaction(ec) { ->
                throw new IllegalStateException("work boom")
            }
        }

        assertEquals(["suspend", "resume"], tx.calls, "resume must happen in a finally")
        assertTrue(tx.isTransactionInPlace())
    }

    @Test
    void aFailedSuspendIsNonFatalAndWorkStillRuns() {
        FakeTransactionFacade tx = new FakeTransactionFacade(true)
        tx.suspendThrows = true
        def ec = new FakeEc(tx)
        boolean ran = false

        Object result = TransactionDetachSupport.runDetachedFromCallerTransaction(ec) { ->
            ran = true
            return "still-ran"
        }

        assertTrue(ran, "a failed suspend must not skip the work")
        assertEquals("still-ran", result)
        assertEquals(["suspend"], tx.calls, "no resume when suspend never succeeded")
    }
}
