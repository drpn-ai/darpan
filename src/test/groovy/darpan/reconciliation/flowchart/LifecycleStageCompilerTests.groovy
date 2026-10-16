package darpan.reconciliation.flowchart

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-UI-050. A lifecycle is a start record and its stages in order; the question tree the walker runs is
 * compiled from that list, and every ending of the tree is a named place.
 */
class LifecycleStageCompilerTests {

    @Test
    void twoStagesCompileToTheGorjanaOrderChain() {
        List<Map> qs = LifecycleStageCompiler.compile("SalesOrd", ["ItemShip", "CustInvc"])

        // START, the two stages in order, and one "did it skip ahead?" question on the first stage's "no".
        assertEquals(["start", "s0", "s1", "s0.why1"], qs*.key)
        Map start = qs.find { it.key == "start" }
        assertEquals("START", start.questionRole)
        assertEquals("SalesOrd", start.recordType)
        assertEquals("Sales orders", start.runName)

        Map ship = qs.find { it.key == "s0" }
        assertEquals(["start", "YES", "ItemShip"], [ship.parentKey, ship.parentBranch, ship.stageRecordType])
        assertEquals("Fulfillment", ship.runName)

        Map bill = qs.find { it.key == "s1" }
        assertEquals(["s0", "YES", "CustInvc"], [bill.parentKey, bill.parentBranch, bill.stageRecordType])
        assertEquals("Shipped, never billed", bill.noOutcomeLabel)
        assertEquals("Shipped and billed", bill.yesOutcomeLabel, "the last stage's yes is the complete place")

        Map why = qs.find { it.key == "s0.why1" }
        assertEquals(["s0", "NO", "CustInvc"], [why.parentKey, why.parentBranch, why.stageRecordType])
        assertEquals("Billed, never shipped", why.yesOutcomeLabel)
        assertEquals("Never shipped, never billed", why.noOutcomeLabel)
    }

    @Test
    void everyEndingIsNamedExactlyOnce() {
        List<Map> qs = LifecycleStageCompiler.compile("RtnAuth", ["ItemRcpt", "CustCred", "CustRfnd"])
        Set<String> parentsOnYes = qs.findAll { it.parentBranch == "YES" }*.parentKey as Set
        Set<String> parentsOnNo = qs.findAll { it.parentBranch == "NO" }*.parentKey as Set
        qs.findAll { it.questionRole != "START" }.each { Map q ->
            boolean yesIsLeaf = !parentsOnYes.contains(q.key)
            boolean noIsLeaf = !parentsOnNo.contains(q.key)
            assertEquals(yesIsLeaf, q.yesOutcomeLabel != null, "${q.key}: a yes ending has a name, a yes that continues does not")
            assertEquals(noIsLeaf, q.noOutcomeLabel != null, "${q.key}: a no ending has a name, a no that continues does not")
        }
        // 3 stages: 3 on the spine, 2 + 1 skip-ahead questions, so 6 questions and 7 places.
        assertEquals(6, qs.count { it.questionRole != "START" })
        int places = qs.count { it.yesOutcomeLabel } + qs.count { it.noOutcomeLabel }
        assertEquals(7, places)
        assertTrue(qs*.noOutcomeLabel.contains("Never received, nothing after"))
        assertTrue(qs*.yesOutcomeLabel.contains("Refunded, never received"))
    }

    @Test
    void oneStageIsASingleQuestion() {
        List<Map> qs = LifecycleStageCompiler.compile("SalesOrd", ["CustInvc"])
        assertEquals(["start", "s0"], qs*.key)
        assertEquals("Never billed", qs[1].noOutcomeLabel)
        assertEquals("Billed", qs[1].yesOutcomeLabel)
    }

    @Test
    void theNameIsMadeFromTheStages() {
        assertEquals("Sales orders: shipped → billed", LifecycleStageCompiler.lifecycleName("SalesOrd", ["ItemShip", "CustInvc"]))
    }

    @Test
    void unknownOrRepeatedRecordTypesAreRefused() {
        assertThrows(IllegalArgumentException) { LifecycleStageCompiler.compile("SalesOrd", []) }
        assertThrows(IllegalArgumentException) { LifecycleStageCompiler.compile("SalesOrd", ["ItemShip", "ItemShip"]) }
        assertThrows(IllegalArgumentException) { LifecycleStageCompiler.compile("SalesOrd", ["SalesOrd"]) }
        // Every value reaches SuiteQL by interpolation, so only known record types pass.
        assertThrows(IllegalArgumentException) { LifecycleStageCompiler.compile("SalesOrd", ["X' OR 1=1"]) }
        assertThrows(IllegalArgumentException) { LifecycleStageCompiler.compile("Bogus", ["ItemShip"]) }
    }
}
