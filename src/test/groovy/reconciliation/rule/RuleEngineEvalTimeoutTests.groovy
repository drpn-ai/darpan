package reconciliation.rule

import org.junit.jupiter.api.Test
import org.kie.api.runtime.KieSession

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Wall-clock watchdog for rule evaluation (MACH P2 residual-DoS bound): fireAllRulesBounded
 * must halt an over-budget session and report it; fast sessions pass through untouched.
 * KieSession is stubbed via map coercion — this tests the watchdog mechanics, not Drools.
 */
class RuleEngineEvalTimeoutTests {

    @Test
    void fastEvaluationPassesThroughWithoutHalt() {
        KieSession fast = [
                fireAllRules: { int max -> 7 },
                halt        : { -> throw new IllegalStateException("halt must not fire for a fast session") },
        ] as KieSession

        Map<String, Object> result = RuleEngineSupport.fireAllRulesBounded(fast, "RS_FAST")
        assertEquals(7, result.fired)
        assertFalse((boolean) result.halted)
    }

    @Test
    void overBudgetEvaluationIsHaltedAndReported() {
        long budgetMillis = 200L
        CountDownLatch haltCalled = new CountDownLatch(1)
        KieSession slow = [
                // Simulates Drools semantics: fireAllRules blocks until halt() releases it.
                fireAllRules: { int max ->
                    assertTrue(haltCalled.await(10, TimeUnit.SECONDS), "watchdog never invoked halt()")
                    return 3
                },
                halt        : { -> haltCalled.countDown() },
        ] as KieSession

        Map<String, Object> result = RuleEngineSupport.fireAllRulesBounded(slow, "RS_SLOW", budgetMillis)
        assertTrue((boolean) result.halted, "watchdog must flag an over-budget session as halted")
        assertEquals(3, result.fired)
        assertTrue((result.elapsedMillis as long) >= budgetMillis,
                "halt fired before the budget elapsed: ${result.elapsedMillis}ms")
    }

    /** DAR-OPS-029 S99-011: a session stopped by the fire cap may hold unfired activations, so it is incomplete too. */
    @Test
    void reachingTheFireCapIsReportedAsIncomplete() {
        KieSession capped = [
                fireAllRules: { int max -> max },
                halt        : { -> throw new IllegalStateException("the watchdog must not fire for a capped session") },
        ] as KieSession

        Map<String, Object> result = RuleEngineSupport.fireAllRulesBounded(capped, "RS_CAP")
        assertTrue((boolean) result.capped, "fired == MAX_RULE_FIRES must read as capped")
        assertTrue((boolean) result.halted, "a capped session is incomplete, never a clean result")
        assertTrue(RuleEngineSupport.incompleteReason(result).contains("fire cap"))
    }

    @Test
    void incompleteReasonNamesTheCause() {
        assertEquals(null, RuleEngineSupport.incompleteReason([fired: 7, halted: false, capped: false]))
        assertTrue(RuleEngineSupport.incompleteReason([fired: 3, halted: true, capped: false]).contains("ms"))
        assertTrue(RuleEngineSupport.incompleteReason([fired: 3, halted: true, capped: true]).contains("fire cap"))
    }

    @Test
    void defaultBudgetIsSaneAndOverridable() {
        assertTrue(RuleEngineSupport.MAX_RULE_EVAL_MILLIS >= 10_000L,
                "default eval budget must not be so low that legitimate batches fail")
    }
}
