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
        FakeEntityFacade entity

        FakeEc(FakeTransactionFacade transaction, FakeEntityFacade entity = null) {
            this.transaction = transaction
            this.entity = entity
        }
    }

    /**
     * Duck-typed stand-in for {@code ec.entity.find(entityName).disableAuthz().condition(...)
     * .useCache(...).one()} — the exact chain {@code TenantScopedFinder.findGlobalUnscoped} drives.
     * {@code onOne} is invoked at the point {@code .one()} would actually hit the database, so a
     * test can record when a read happened relative to the surrounding suspend/resume calls.
     */
    static class FakeEntityFacade {
        Closure<Object> onOne

        FakeEntityFacade(Closure<Object> onOne) { this.onOne = onOne }

        FakeEntityFind find(String entityName) { return new FakeEntityFind(entityName, onOne) }
    }

    static class FakeEntityFind {
        String entityName
        Closure<Object> onOne
        Map<String, Object> conditions = [:]

        FakeEntityFind(String entityName, Closure<Object> onOne) {
            this.entityName = entityName
            this.onOne = onOne
        }

        FakeEntityFind disableAuthz() { return this }

        FakeEntityFind condition(String name, Object value) {
            conditions.put(name, value)
            return this
        }

        FakeEntityFind useCache(boolean useCache) { return this }

        Object one() { return onOne.call(entityName, conditions) }
    }

    @Test
    void executeAutomationSuspendsTheCallerTransactionAndAlwaysResumesIt() {
        FakeTransactionFacade tx = new FakeTransactionFacade(true)
        def ec = new FakeEc(tx)

        // executeAutomation fails fast on a blank automationId (statement 2 of 4 in the closure —
        // requireNormalized), which is enough to prove the detach wraps AT LEAST that far: the
        // caller transaction must already have been suspended by then, and must still be resumed on
        // the way out even though the body never reaches loadAutomation/withSystemTenant. It does
        // NOT prove the wrap extends past requireNormalized — see
        // executeAutomationKeepsTheDetachInPlaceThroughLoadAutomationAndIntoTheTenantScopedBody for
        // that. (isTransactionInPlace() can't be sampled from a catch block here to prove "suspended
        // during work" — runDetachedFromCallerTransaction resumes in a finally, which always
        // completes before the exception reaches this scope, so the caller transaction reads resumed
        // again by the time control gets here. The calls log is the reliable witness, exactly as
        // TransactionDetachSupportTests.groovy's own resumesCallerTransactionEvenWhenWorkThrows()
        // verifies for the helper itself: nothing in this test touches ec.transaction except
        // executeAutomation's delegation to the detach, so seeing suspend-then-resume at all proves
        // this much of the body ran through it.)
        try {
            AutomationExecutionSupport.executeAutomation(ec, [automationId: ""])
        } catch (Exception ignored) {
        }

        assertEquals(["suspend", "resume"], tx.calls,
                "executeAutomation must delegate at least its fail-fast validation to the detach helper")
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

    @Test
    void executeAutomationKeepsTheDetachInPlaceThroughLoadAutomationAndIntoTheTenantScopedBody() {
        // Regression target: a future refactor could narrow the wrap to just the fail-fast
        // validation — e.g. `String automationId = runDetachedFromCallerTransaction(ec){
        // requireNormalized(...) }` — leaving loadAutomation, resolveSystemTenantId, and
        // executeAutomationForTenant running back inside the caller's (60s-limited) request
        // transaction. The test above cannot catch that: it never reaches loadAutomation at all.
        // This one seeds a loadable automation with a companyUserGroupId left blank (so
        // resolveSystemTenantId short-circuits to null without a second entity read — see
        // AutomationExecutionSupport.resolveSystemTenantId) and an inputModeEnumId that matches
        // neither AUT_IN_API_RANGE nor AUT_IN_SFTP_FILES, so executeAutomationForTenant's own
        // fail-fast fires immediately after entering TenantAccessSupport.withSystemTenant. The fake
        // entity facade records a "load" marker into the SAME calls log the transaction facade
        // writes to, at the exact moment loadAutomation's read fires, so the three markers'
        // relative order is the witness: "load" must fall strictly between "suspend" and "resume"
        // for the whole requireNormalized -> loadAutomation -> withSystemTenant ->
        // executeAutomationForTenant chain to have run under the detach.
        FakeTransactionFacade tx = new FakeTransactionFacade(true)
        Map<String, Object> automation = [
                automationId      : "AUTO_1",
                companyUserGroupId: "",
                inputModeEnumId   : "AUT_IN_NEITHER",
        ]
        FakeEntityFacade entityFacade = new FakeEntityFacade({ String entityName, Map<String, Object> conditions ->
            if (entityName == "darpan.reconciliation.ReconciliationAutomation" && conditions.get("automationId") == "AUTO_1") {
                tx.calls.add("load")
                return automation
            }
            return null
        })
        def ec = new FakeEc(tx, entityFacade)

        try {
            AutomationExecutionSupport.executeAutomation(ec, [automationId: "AUTO_1"])
        } catch (Exception ignored) {
        }

        assertEquals(["suspend", "load", "resume"], tx.calls,
                "loadAutomation (and everything after it, up to executeAutomationForTenant's own " +
                        "validation) must still run with the caller transaction suspended")
        assertTrue(tx.isTransactionInPlace(), "the caller transaction must be resumed for the response path")
    }
}
