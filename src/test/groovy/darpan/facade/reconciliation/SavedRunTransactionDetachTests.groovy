package darpan.facade.reconciliation

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * The saved-run body must execute OUTSIDE any caller/request transaction. The JSON-RPC screen
 * path wraps the service call in the request's JTA transaction whose 60s timeout is far shorter
 * than a real extract; Bitronix then marks it rollback-only mid-run, every later entity touch
 * fails, and the run + step rows roll back invisibly (UAT 2026-07-27: every run died at the
 * EXTRACT_FILE2 boundary with no trace). runDetachedFromCallerTransaction suspends the caller's
 * transaction for the duration of the work and ALWAYS resumes it, so the response path gets the
 * caller's transaction back untouched.
 */
class SavedRunTransactionDetachTests {

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

        Object result = ReconciliationSavedRunSupport.runDetachedFromCallerTransaction(ec) { ->
            txInPlaceDuringWork = tx.isTransactionInPlace()
            return "work-result"
        }

        assertEquals("work-result", result)
        // the long-running body must NOT live inside the caller's transaction
        assertFalse(txInPlaceDuringWork)
        assertEquals(["suspend", "resume"], tx.calls)
        // the caller's transaction is back in place for the response path
        assertTrue(tx.isTransactionInPlace())
    }

    @Test
    void resumesCallerTransactionEvenWhenWorkThrows() {
        FakeTransactionFacade tx = new FakeTransactionFacade(true)
        def ec = new FakeEc(tx)

        assertThrows(IllegalStateException.class, {
            ReconciliationSavedRunSupport.runDetachedFromCallerTransaction(ec) { ->
                throw new IllegalStateException("run failed")
            }
        })

        assertEquals(["suspend", "resume"], tx.calls)
        assertTrue(tx.isTransactionInPlace())
    }

    @Test
    void withoutActiveTransactionWorkJustRuns() {
        FakeTransactionFacade tx = new FakeTransactionFacade(false)
        def ec = new FakeEc(tx)

        Object result = ReconciliationSavedRunSupport.runDetachedFromCallerTransaction(ec) { -> "plain" }

        assertEquals("plain", result)
        assertEquals([], tx.calls)
    }

    @Test
    void suspendFailureIsNonFatalAndSkipsResume() {
        FakeTransactionFacade tx = new FakeTransactionFacade(true)
        tx.suspendThrows = true
        def ec = new FakeEc(tx)

        Object result = ReconciliationSavedRunSupport.runDetachedFromCallerTransaction(ec) { -> "still-ran" }

        // a failed suspend must not fail the run — worst case it executes inside the caller's
        // transaction exactly as before this fix — and must NOT resume what it never suspended
        assertEquals("still-ran", result)
        assertEquals(["suspend"], tx.calls)
    }
}
