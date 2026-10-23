package darpan.reconciliation.automation

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-116: what a retry can fix. A run whose compare cannot even be analysed (its own key names a column
 * the file does not have) failed the same way on every retry and sat PENDING for ~45 minutes before
 * dead-lettering (gorjana 100103, 2026-10-23).
 */
class AutomationFailureClassificationTests {

    @Test
    void aSparkAnalysisErrorIsPermanent() {
        Throwable t = new RuntimeException("Java method for service [reconciliation.ReconciliationCoreServices.prepare#RuleSetCompareScope] threw an exception",
                new RuntimeException("[UNRESOLVED_COLUMN.WITH_SUGGESTION] A column or function parameter with name `tranId` cannot be resolved. Did you mean one of the following? [`records`, `metadata`].;"))
        assertTrue(AutomationExecutionSupport.isPermanentFailure(t))
        assertTrue(AutomationExecutionSupport.isPermanentFailure(new RuntimeException("[DATATYPE_MISMATCH.BINARY_OP_DIFF_TYPES] Cannot resolve ...")))
        assertTrue(AutomationExecutionSupport.isPermanentFailure(new IllegalStateException("Reconcile compare stage reported errors: " +
                "Compare scope 'Exchange orders' FILE_2 (NetSuite SuiteQL) produced duplicate primary ids")))
    }

    @Test
    void aTimeoutOrAGatewayErrorIsStillRetried() {
        assertFalse(AutomationExecutionSupport.isPermanentFailure(new java.net.SocketTimeoutException("Read timed out")))
        assertFalse(AutomationExecutionSupport.isPermanentFailure(new IllegalStateException("OMS returns extract failed with HTTP 504")))
        assertFalse(AutomationExecutionSupport.isPermanentFailure(new IllegalStateException("OMS GraphQL extract failed [THROTTLED]: throttled")))
    }
}
