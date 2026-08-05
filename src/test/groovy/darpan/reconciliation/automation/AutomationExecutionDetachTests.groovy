package darpan.reconciliation.automation

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * executeAutomation must run OUTSIDE the caller's request transaction.
 *
 * Prod 2026-08-05: pressing "Run now" on automation 100000 held the JSON-RPC request open for
 * 61,575 ms and then died with a severed connection at the gateway's ~60s idle timeout, leaving
 * NO ReconciliationAutomationExecution row behind — every write had joined the request
 * transaction via runInTransaction/runUseOrBegin and rolled back with it. Detaching lets the
 * execution row commit immediately so the UI can find it and follow the run live.
 */
class AutomationExecutionDetachTests {

    static class FakeTransactionFacade {
        boolean inPlace
        List<String> calls = []

        FakeTransactionFacade(boolean inPlace) { this.inPlace = inPlace }

        boolean isTransactionInPlace() { return inPlace }

        boolean suspend() {
            calls.add("suspend")
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
    void executeAutomationSuspendsTheCallerTransactionAndAlwaysResumesIt() {
        FakeTransactionFacade tx = new FakeTransactionFacade(true)
        def ec = new FakeEc(tx)

        // executeAutomation fails fast on a blank automationId, which is enough to prove the
        // detach wraps the WHOLE body: suspend/resume must still bracket the call even though the
        // body throws before doing any real work. (isTransactionInPlace() can't be sampled from a
        // catch block here to prove "suspended during work" — runDetachedFromCallerTransaction
        // resumes in a finally, which always completes before the exception reaches this scope, so
        // the caller transaction reads resumed again by the time control gets here. The calls log
        // is the reliable witness, exactly as TransactionDetachSupportTests.groovy's own
        // resumesCallerTransactionEvenWhenWorkThrows() verifies for the helper itself: nothing in
        // this test touches ec.transaction except executeAutomation's delegation to the detach, so
        // seeing suspend-then-resume at all proves the whole body ran through it.)
        try {
            AutomationExecutionSupport.executeAutomation(ec, [automationId: ""])
        } catch (Exception ignored) {
        }

        assertEquals(["suspend", "resume"], tx.calls, "executeAutomation must delegate its whole body to the detach helper")
        assertTrue(tx.isTransactionInPlace(), "the caller transaction must be resumed for the response path")
    }

    @Test
    void executeAutomationIsANoOpOnTransactionsWhenTheSchedulerHasNoneInPlace() {
        FakeTransactionFacade tx = new FakeTransactionFacade(false)
        def ec = new FakeEc(tx)

        try {
            AutomationExecutionSupport.executeAutomation(ec, [automationId: ""])
        } catch (Exception ignored) {
        }

        assertEquals([], tx.calls, "scan#DueAutomations has no ambient transaction to suspend")
    }
}
