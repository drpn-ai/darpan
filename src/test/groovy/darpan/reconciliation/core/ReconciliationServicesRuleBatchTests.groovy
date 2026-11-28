package darpan.reconciliation.core

import org.apache.spark.sql.Dataset
import org.apache.spark.sql.SparkSession
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext
import org.moqui.context.MessageFacade
import org.moqui.service.ServiceCallSync
import org.moqui.service.ServiceFacade

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-OPS-029 S01a, invariant E2 (no hollow green) on executeRuleSetMatchedPairBatches. A rule failure that is not an
 * Exception (a StackOverflowError in a rule) escapes the rule engine's catch; Moqui records it as an ec.message error and
 * the call returns null, and refuses every later call while the error stands. The run must read as a failed rule stage
 * with a reason, not as "no rule differences". Spark but no Moqui: the ec is a stub.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReconciliationServicesRuleBatchTests {

    private SparkSession spark

    @BeforeAll
    void setup() {
        spark = SparkSession.builder().appName("ReconciliationServicesRuleBatchTests").master("local[1]")
                .config("spark.ui.enabled", "false").getOrCreate()
    }

    @AfterAll
    void cleanup() { if (spark != null) spark.stop() }

    /** An ec whose rule service answers each call with `answer`, which may add an error to the message facade. */
    private static ExecutionContext stubEc(List<String> errors, List<Integer> calls, Closure<Map> answer) {
        MessageFacade message = [hasError       : { -> !errors.isEmpty() },
                                 getErrorsString: { -> errors.join("; ") }] as MessageFacade
        ServiceFacade service = [sync: { ->
            Map parameters = [:]
            ServiceCallSync call = null
            call = [name      : { String n -> call },
                    parameters: { Map p -> parameters.putAll(p); call },
                    call      : { -> calls.add(((List) parameters.dataList).size()); answer.call(errors) }] as ServiceCallSync
            return call
        }] as ServiceFacade
        return [getService: { -> service }, getMessage: { -> message }] as ExecutionContext
    }

    private Dataset pairs(int n) {
        return spark.range(n).toDF("primaryId")
    }

    @Test
    void aRuleServiceThatFailsThroughTheMessageFacadeFailsTheRuleStage() {
        List<String> errors = []
        List<Integer> calls = []
        ExecutionContext ec = stubEc(errors, calls) { List<String> e ->
            e.add("java.lang.StackOverflowError in rule DARPAN_TEST")
            return null
        }
        Map result = ReconciliationServices.executeRuleSetMatchedPairBatches(ec, pairs(5), "RS1", "SCOPE1", "Orders", 2)

        assertTrue(result.ruleExecutionFailed as boolean, "a rule stage that never answered is a failed stage")
        assertTrue(((List<String>) result.ruleErrors).any { it.contains("StackOverflowError") }, "the reason travels: ${result.ruleErrors}")
        assertTrue(((List<String>) result.processingWarnings).any { it.contains("rule execution failed") }, result.processingWarnings.toString())
        assertEquals([2], calls, "the stage stops at the failed batch instead of skipping the rest silently")
        assertEquals(0L, result.ruleDifferenceCount as long)
    }

    @Test
    void aRuleServiceThatAnswersRunsEveryBatch() {
        List<String> errors = []
        List<Integer> calls = []
        ExecutionContext ec = stubEc(errors, calls) { List<String> e ->
            [firedRuleCount: 1, diffResults: [[diffType: "FIELD_MISMATCH", primaryId: "x"]], warnings: []]
        }
        Map result = ReconciliationServices.executeRuleSetMatchedPairBatches(ec, pairs(5), "RS1", "SCOPE1", "Orders", 2)

        assertFalse(result.ruleExecutionFailed as boolean)
        assertEquals(5, calls.sum() as int, "every matched pair went to the rules")
        assertEquals(calls.size(), result.firedRuleCount as int)
        assertEquals((long) calls.size(), result.ruleDifferenceCount as long)
    }

    /**
     * OC150 (DAR-OPS-029 S01a, determinism). Matched pairs reach the rules in primaryId order, whatever their partitions: which
     * rule rows survive the rule-diff cap, and what a rule that looks across one batch sees, no longer depend on partition luck.
     */
    @Test
    void matchedPairsReachTheRulesInPrimaryIdOrder() {
        List<String> errors = []
        List<String> seen = []
        MessageFacade message = [hasError: { -> false }, getErrorsString: { -> "" }] as MessageFacade
        ServiceFacade service = [sync: { ->
            Map parameters = [:]
            ServiceCallSync call = null
            call = [name      : { String n -> call },
                    parameters: { Map p -> parameters.putAll(p); call },
                    call      : { -> seen.addAll(((List<Map>) parameters.dataList).collect { it.primaryId.toString() }); [diffResults: [], warnings: []] }] as ServiceCallSync
            return call
        }] as ServiceFacade
        ExecutionContext ec = [getService: { -> service }, getMessage: { -> message }] as ExecutionContext
        List<String> ids = (1..60).collect { String.format("K%03d", it) }
        Dataset shuffled = spark.createDataset(ids.reverse(), org.apache.spark.sql.Encoders.STRING()).toDF("primaryId").repartition(4)
        ReconciliationServices.executeRuleSetMatchedPairBatches(ec, shuffled, "RS1", "SCOPE1", "Orders", 7)
        assertEquals(ids, seen)
    }
}
