package darpan.reconciliation.conclusion

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-063. Rows to nodes, nodes to DRL — and nothing a row says ever becomes DRL text. */
class ConclusionRuleLogicGeneratorTests {

    static Map node(Integer seq, Integer parent = null, Map extra = [:]) {
        return [sequenceNum: seq, parentSequenceNum: parent, conclusionEnumId: "C${seq}".toString(), label: "L${seq}".toString(),
                appliesToBucket: null, questionText: null, suggestedFilter: null, conditions: []] + extra
    }

    @Test
    void nodesGetGeneratedIdsAndResolvedParents() {
        List<Map> nodes = ConclusionRuleLogicGenerator.assignNodes([node(10), node(20, 10), node(30, 20)])
        assertEquals(["N0", "N1", "N2"], nodes*.nodeId)
        assertEquals([null, "N0", "N1"], nodes*.parentNodeId)
    }

    @Test
    void aMissingSequenceNumIsTheListPosition() {
        List<Map> nodes = ConclusionRuleLogicGenerator.assignNodes([node(null), node(null)])
        assertEquals([10, 20], nodes*.sequenceNum)
    }

    @Test
    void refusesAParentThatDoesNotExist() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException) {
            ConclusionRuleLogicGenerator.assignNodes([node(10), node(20, 99)])
        }
        assertTrue(e.message.contains("99"))
    }

    @Test
    void refusesACycle() {
        assertThrows(IllegalArgumentException) { ConclusionRuleLogicGenerator.assignNodes([node(10, 20), node(20, 10)]) }
    }

    @Test
    void refusesMoreThanThreeLevels() {
        assertThrows(IllegalArgumentException) {
            ConclusionRuleLogicGenerator.assignNodes([node(10), node(20, 10), node(30, 20), node(40, 30)])
        }
    }

    @Test
    void refusesADuplicateSequence() {
        assertThrows(IllegalArgumentException) { ConclusionRuleLogicGenerator.assignNodes([node(10), node(10)]) }
    }

    @Test
    void drlCarriesNoDataStrings() {
        String evil = 'x" ) then System.exit(1); end\nrule "pwn'
        List<Map> nodes = ConclusionRuleLogicGenerator.assignNodes([node(10, null, [label: evil, conclusionEnumId: evil,
                appliesToBucket: evil, questionText: evil,
                conditions: [[subject: evil, presence: "ANY", keyScope: "FULL", quantifier: "ALL",
                              fieldExpression: evil, operator: "IN", conditionValues: [evil], checkLabel: evil]]])])
        String drl = ConclusionRuleLogicGenerator.generate(nodes)
        assertFalse(drl.contains("System.exit"), drl)
        assertFalse(drl.contains("pwn"), drl)
        assertTrue(drl.contains('rule "N0"'))
    }

    @Test
    void siblingsAreOrderedBySalienceAndChildrenWaitOnTheirParent() {
        String drl = ConclusionRuleLogicGenerator.generate(ConclusionRuleLogicGenerator.assignNodes([node(10), node(20), node(11, 10)]))
        assertTrue(drl.contains('rule "N0"\n    salience 999990'), drl)
        assertTrue(drl.contains('rule "N1"\n    salience 999980'), drl)
        assertTrue(drl.contains('$f : Map(this["_node"] == "N0")'), "N2 waits on N0: ${drl}")
    }

    @Test
    void matchesGatesOnTheBucketThenEveryCondition() {
        Map n = node(10, null, [appliesToBucket: "MISSING_FROM_FILE_2",
                conditions: [[subject: "FILE_1", presence: "KEPT", keyScope: "FULL", quantifier: "ALL", checkLabel: "k"]]])
        assertFalse(ConclusionTreeSupport.matches(ConclusionTreeSupport.newFact("MISSING_FROM_FILE_1", [FILE_1: [presence: "KEPT"]]), n))
        assertTrue(ConclusionTreeSupport.matches(ConclusionTreeSupport.newFact("MISSING_FROM_FILE_2", [FILE_1: [presence: "KEPT"]]), n))
        assertFalse(ConclusionTreeSupport.matches(ConclusionTreeSupport.newFact("MISSING_FROM_FILE_2", [FILE_1: [presence: "ABSENT"]]), n))
    }

    @Test
    void advanceRecordsThePathAndResultReadsTheDeepestNode() {
        Map fact = ConclusionTreeSupport.newFact("RULE", [FILE_1: [presence: "KEPT", record: [s: "F"]]])
        Map parent = node(10, null, [nodeId: "N0", conditions: [[subject: "FILE_1", presence: "KEPT", keyScope: "FULL", quantifier: "ALL", checkLabel: "p"]]])
        Map child = node(11, 10, [nodeId: "N1", questionText: "Q?", suggestedFilter: [a: 1],
                conditions: [[subject: "FILE_1", presence: "ANY", keyScope: "FULL", quantifier: "ALL",
                              fieldExpression: "s", operator: "IN", conditionValues: ["F"], checkLabel: "c"]]])
        ConclusionTreeSupport.advance(fact, parent)
        ConclusionTreeSupport.advance(fact, child)
        Map r = ConclusionTreeSupport.result(fact)
        assertEquals("C11", r.code)
        assertEquals([["p", "kept"], ["c", "F"]], r.checks.collect { [it.label, it.value] })
        assertEquals(["C10", "C11"], r.path*.code)
        assertEquals("Q?", r.question.text)
        assertEquals("N1", fact._node)
    }

    @Test
    void aFactThatReachedNothingIsUnexplained() {
        Map r = ConclusionTreeSupport.result(ConclusionTreeSupport.newFact("RULE", [:]))
        assertEquals("UNEXPLAINED", r.code)
        assertEquals([], r.path)
        assertNull(r.question)
    }
}
