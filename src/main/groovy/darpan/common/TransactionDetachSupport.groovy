package darpan.common

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Runs a long-lived service body OUTSIDE any caller/request transaction.
 *
 * The JSON-RPC/screen request path can wrap a whole service call in the request's JTA
 * transaction, whose default 60s timeout is far shorter than a real extract or automation run.
 * When Bitronix marks that transaction rollback-only mid-run, every later entity touch fails and
 * the completed work rolls back invisibly — UAT 2026-07-27 saw every saved run die at the
 * EXTRACT_FILE2 boundary, and prod 2026-08-05 saw run#AutomationNow die at 61.5s leaving no
 * ReconciliationAutomationExecution row at all.
 *
 * Suspending first lets the work own its transactional life: observability writes commit live in
 * their own short transactions, so an in-progress run is readable while it is still running. The
 * caller's transaction is ALWAYS resumed for the response path. Mirrors the suspend/resume
 * pattern in Moqui's TransactionFacade javadoc. A failed suspend is non-fatal: the work still
 * runs, at worst inside the caller's transaction exactly as before this guard existed.
 *
 * Lives in darpan.common, not darpan.facade.reconciliation, because the automation package must
 * not depend on the facade packages (see CompareIdExpressionSupport's note on that boundary).
 */
class TransactionDetachSupport {
    protected static final Logger logger = LoggerFactory.getLogger(TransactionDetachSupport.class)

    static Object runDetachedFromCallerTransaction(def ec, Closure<?> work) {
        boolean suspendedCallerTransaction = false
        try {
            if (ec.transaction.isTransactionInPlace()) suspendedCallerTransaction = ec.transaction.suspend()
        } catch (Exception e) {
            logger.warn("Could not suspend caller transaction before detached execution; continuing inside it: ${e.message}")
        }
        try {
            return work.call()
        } finally {
            if (suspendedCallerTransaction) {
                try {
                    ec.transaction.resume()
                } catch (Exception e) {
                    logger.warn("Could not resume caller transaction after detached execution: ${e.message}")
                }
            }
        }
    }
}
