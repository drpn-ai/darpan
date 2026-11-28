package darpan.reconciliation.support

/**
 * DAR-OPS-029 S99-201: ec.transaction for fake ECs. runUseOrBegin and runRequireNew run the work inline (no real
 * transaction) and record each call's kind and message, so a test can check which one the code asked for. No
 * transaction is ever in place.
 */
class FakeTransactionFacade {
    final List<Map<String, Object>> calls = []

    Object runUseOrBegin(Integer timeout, String message, Closure work) {
        calls << ([kind: "useOrBegin", timeout: timeout, message: message] as Map<String, Object>)
        return work.call()
    }

    Object runRequireNew(Integer timeout, String message, Closure work) {
        calls << ([kind: "requireNew", timeout: timeout, message: message] as Map<String, Object>)
        return work.call()
    }

    boolean isTransactionInPlace() { false }
}
