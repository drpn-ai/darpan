package darpan.reconciliation.flowchart

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-UI-048. Every tree rule the save service enforces, and the order the walker runs questions in. */
class RunFlowchartTreeTests {

    static Map q(String id, Map m = [:]) {
        return [reconciliationRunId: id, parentReconciliationRunId: null, parentBranch: null, questionRole: null,
                runSequence: null, isActive: null, ruleSetId: "${id}_RS".toString(), scopeMode: "EVALUATE",
                file1Signature: "NETSUITE_SUITEQL|POP"] + m
    }

    static List<Map> chain() {
        return [q("S", [questionRole: "START"]),
                q("A", [parentReconciliationRunId: "S", parentBranch: "YES", runSequence: 1]),
                q("A1", [parentReconciliationRunId: "A", parentBranch: "YES"]),
                q("B", [parentReconciliationRunId: "S", parentBranch: "YES", runSequence: 2]),
                q("B1", [parentReconciliationRunId: "B", parentBranch: "NO"])]
    }

    @Test
    void aWellFormedChainIsValid() {
        assertEquals([], RunFlowchartTree.validate(chain()))
    }

    @Test
    void walkOrderIsParentFirstAndSiblingsBySequence() {
        assertEquals(["S", "A", "A1", "B", "B1"], RunFlowchartTree.walkOrder(chain())*.reconciliationRunId)
    }

    @Test
    void anInactiveQuestionTakesItsSubtreeOutOfTheWalk() {
        List<Map> qs = chain()
        qs.find { it.reconciliationRunId == "A" }.isActive = "N"
        assertEquals(["S", "B", "B1"], RunFlowchartTree.walkOrder(qs)*.reconciliationRunId)
    }

    @Test
    void refusesACycle() {
        List<Map> qs = [q("S", [questionRole: "START"]),
                        q("A", [parentReconciliationRunId: "B", parentBranch: "YES"]),
                        q("B", [parentReconciliationRunId: "A", parentBranch: "YES"])]
        assertTrue(RunFlowchartTree.validate(qs).any { it.contains("loop") })
    }

    @Test
    void refusesAParentFromAnotherRun() {
        List<Map> qs = chain() + [q("X", [parentReconciliationRunId: "ELSEWHERE", parentBranch: "YES"])]
        assertTrue(RunFlowchartTree.validate(qs).any { it.contains("not a question in this run") })
    }

    @Test
    void refusesAParentWithoutABranchAndABranchWithoutAParent() {
        assertTrue(RunFlowchartTree.validate(chain() + [q("X", [parentReconciliationRunId: "A"])])
                .any { it.contains("yes or no") })
        assertTrue(RunFlowchartTree.validate(chain() + [q("Y", [parentBranch: "YES"])])
                .any { it.contains("has no parent") })
    }

    @Test
    void refusesASecondStartAndAStartWithAParent() {
        assertTrue(RunFlowchartTree.validate(chain() + [q("S2", [questionRole: "START"])]).any { it.contains("one start") })
        assertTrue(RunFlowchartTree.validate([q("S", [questionRole: "START", parentReconciliationRunId: "X", parentBranch: "YES"]),
                                              q("X")]).any { it.contains("start cannot hang") })
    }

    @Test
    void theStartHasNoNo() {
        assertTrue(RunFlowchartTree.validate(chain() + [q("N", [parentReconciliationRunId: "S", parentBranch: "NO"])])
                .any { it.contains("start has no 'no'") })
    }

    @Test
    void withAStartEverythingHangsFromIt() {
        assertTrue(RunFlowchartTree.validate(chain() + [q("LOOSE")]).any { it.contains("must hang from the start") })
    }

    @Test
    void aTopLevelOneSourceQuestionCannotHaveAYesChild() {
        List<Map> qs = [q("T", [scopeMode: "EVALUATE"]), q("T1", [parentReconciliationRunId: "T", parentBranch: "YES"])]
        assertTrue(RunFlowchartTree.validate(qs).any { it.contains("no list of records that passed") })
        // A top-level COMPARE question knows its records (FILE_1), so a yes child is fine.
        qs[0].scopeMode = "COMPARE"
        assertEquals([], RunFlowchartTree.validate(qs))
    }

    @Test
    void topLevelQuestionsStartFromTheSameRecords() {
        List<Map> qs = [q("T", [scopeMode: "COMPARE", file1Signature: "SHOPIFY|A"]),
                        q("U", [scopeMode: "COMPARE", file1Signature: "NETSUITE_SUITEQL|B"])]
        assertTrue(RunFlowchartTree.validate(qs).any { it.contains("same records") })
    }

    @Test
    void aChildOnTheSameSystemMustBuildTheSameKey() {
        List<Map> qs = chain()
        qs.each { it.file1System = "NETSUITE_SUITEQL"; it.file1Key = "internalId||" }
        assertEquals([], RunFlowchartTree.validate(qs))
        qs.find { it.reconciliationRunId == "A" }.file1Key = "tranId||"
        assertTrue(RunFlowchartTree.validate(qs).any { it.contains("builds its key differently") })
        // Across systems the field names differ by design, so nothing can be compared: allowed.
        qs.find { it.reconciliationRunId == "A" }.file1System = "SHOPIFY"
        assertFalse(RunFlowchartTree.validate(qs).any { it.contains("builds its key differently") })
    }
}
